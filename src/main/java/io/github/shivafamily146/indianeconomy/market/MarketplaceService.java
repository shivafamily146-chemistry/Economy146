package io.github.shivafamily146.indianeconomy.market;

import io.github.shivafamily146.indianeconomy.database.Database;
import io.github.shivafamily146.indianeconomy.economy.EconomyService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MarketplaceService {
    private static final long DEFAULT_AUCTION_DURATION_MILLIS = 24L * 60 * 60 * 1000;
    private final Database database;
    private final EconomyService economy;

    public MarketplaceService(Database database, EconomyService economy) {
        this.database = database;
        this.economy = economy;
    }

    public synchronized String prepareInventoryOperation(Operation operation, UUID owner, String username,
                                                         String material, int amount, long pricePaise)
            throws SQLException {
        return prepareInventoryOperation(operation, owner, username, material, amount, pricePaise, null);
    }

    public synchronized String prepareInventoryOperation(Operation operation, UUID owner, String username,
                                                         String material, int amount, long pricePaise,
                                                         Long relatedId) throws SQLException {
        return prepareInventoryOperation(UUID.randomUUID().toString(), operation, owner, username,
                material, amount, pricePaise, relatedId);
    }

    public synchronized String prepareInventoryOperation(String id, Operation operation, UUID owner, String username,
                                                         String material, int amount, long pricePaise,
                                                         Long relatedId) throws SQLException {
        if (amount < 1 || pricePaise < 1) {
            throw new IllegalArgumentException("Invalid inventory operation.");
        }
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            economy.ensureAccount(owner, username);
            if (economy.isFrozen(owner)) {
                connection.rollback();
                return null;
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO item_operations(id, owner_uuid, username, operation, material, amount,
                      price_paise, related_id, created_at, state, result_id)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', NULL)
                    """)) {
                insert.setString(1, id);
                insert.setString(2, owner.toString());
                insert.setString(3, username);
                insert.setString(4, operation.name());
                insert.setString(5, material);
                insert.setInt(6, amount);
                insert.setLong(7, pricePaise);
                if (relatedId == null) {
                    insert.setNull(8, java.sql.Types.BIGINT);
                } else {
                    insert.setLong(8, relatedId);
                }
                insert.setLong(9, System.currentTimeMillis());
                insert.executeUpdate();
            }
            connection.commit();
            return id;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized InventoryOperation completeInventoryOperation(String operationId) throws SQLException {
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            InventoryOperation operation = findInventoryOperation(connection, operationId);
            if (operation == null) {
                connection.rollback();
                return null;
            }
            if (operation.state.equals("COMPLETED")) {
                connection.rollback();
                return operation;
            }
            long now = System.currentTimeMillis();
            Long resultId = null;
            if (operation.operation == Operation.SELL) {
                try (PreparedStatement credit = connection.prepareStatement(
                        "UPDATE accounts SET balance_paise = balance_paise + ?, updated_at = ? WHERE uuid = ? AND balance_paise <= ?");
                     PreparedStatement log = connection.prepareStatement("""
                             INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                             VALUES(?, 'SHOP_SELL', ?, NULL, ?, ?)
                             """)) {
                    credit.setLong(1, operation.pricePaise);
                    credit.setLong(2, now);
                    credit.setString(3, operation.owner.toString());
                    credit.setLong(4, Long.MAX_VALUE - operation.pricePaise);
                    if (credit.executeUpdate() == 1) {
                        log.setString(1, operation.id);
                        log.setString(2, operation.owner.toString());
                        log.setLong(3, operation.pricePaise);
                        log.setLong(4, now);
                        log.executeUpdate();
                        resultId = 0L;
                    } else {
                        try (PreparedStatement refund = connection.prepareStatement("""
                                INSERT INTO pending_deliveries(id, player_uuid, material, amount, created_at)
                                VALUES(?, ?, ?, ?, ?)
                                """)) {
                            refund.setString(1, "refund-" + operation.id);
                            refund.setString(2, operation.owner.toString());
                            refund.setString(3, operation.material);
                            refund.setInt(4, operation.amount);
                            refund.setLong(5, now);
                            refund.executeUpdate();
                        }
                        resultId = -1L;
                    }
                }
            } else if (operation.relatedId != null) {
                resultId = completeOrderFill(connection, operation, now);
            } else {
                Kind kind = operation.operation == Operation.AUCTION ? Kind.AUCTION : Kind.MARKET;
                String insertSql = kind == Kind.AUCTION
                        ? "INSERT INTO auctions(seller_uuid, material, amount, price_paise, created_at, expires_at) "
                                + "VALUES(?, ?, ?, ?, ?, ?)"
                        : "INSERT INTO market_listings(seller_uuid, material, amount, price_paise, created_at) "
                                + "VALUES(?, ?, ?, ?, ?)";
                try (PreparedStatement insert = connection.prepareStatement(insertSql, PreparedStatement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, operation.owner.toString());
                    insert.setString(2, operation.material);
                    insert.setInt(3, operation.amount);
                    insert.setLong(4, operation.pricePaise);
                    insert.setLong(5, now);
                    if (kind == Kind.AUCTION) {
                        insert.setLong(6, now + DEFAULT_AUCTION_DURATION_MILLIS);
                    }
                    insert.executeUpdate();
                    try (ResultSet key = insert.getGeneratedKeys()) {
                        if (!key.next()) {
                            throw new SQLException("Database did not return the new listing identifier.");
                        }
                        resultId = key.getLong(1);
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE item_operations SET state = 'COMPLETED', result_id = ? WHERE id = ? AND state = 'PREPARED'")) {
                if (resultId == null) {
                    update.setNull(1, java.sql.Types.BIGINT);
                } else {
                    update.setLong(1, resultId);
                }
                update.setString(2, operation.id);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Inventory operation state changed unexpectedly.");
                }
            }
            connection.commit();
            return findInventoryOperation(connection, operation.id);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized List<InventoryOperation> pendingInventoryOperations(UUID owner) throws SQLException {
        List<InventoryOperation> operations = new ArrayList<>();
        try (PreparedStatement query = database.connection().prepareStatement("""
                SELECT id, owner_uuid, username, operation, material, amount, price_paise, created_at, state, result_id
                , related_id
                FROM item_operations WHERE owner_uuid = ? AND state = 'PREPARED' ORDER BY created_at, id
                """)) {
            query.setString(1, owner.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    operations.add(readInventoryOperation(result));
                }
            }
        }
        return operations;
    }

    private InventoryOperation findInventoryOperation(Connection connection, String id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, owner_uuid, username, operation, material, amount, price_paise, created_at, state, result_id,
                  related_id
                FROM item_operations WHERE id = ?
                """)) {
            query.setString(1, id);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? readInventoryOperation(result) : null;
            }
        }
    }

    private InventoryOperation readInventoryOperation(ResultSet result) throws SQLException {
        long resultValue = result.getLong(10);
        Long resultId = result.wasNull() ? null : resultValue;
        return new InventoryOperation(result.getString(1), UUID.fromString(result.getString(2)),
                result.getString(3), Operation.valueOf(result.getString(4)), result.getString(5),
                result.getInt(6), result.getLong(7), result.getLong(8), result.getString(9), resultId,
                result.getObject(11) == null ? null : result.getLong(11));
    }

    private Long completeOrderFill(Connection connection, InventoryOperation operation, long now) throws SQLException {
        Listing order = findListing(connection, Kind.ORDERS, operation.relatedId);
        if (order == null || order.owner.equals(operation.owner) || !order.material.equals(operation.material)
                || order.amount != operation.amount || economy.isFrozen(order.owner) || economy.isFrozen(operation.owner)) {
            refundEscrowedItems(connection, operation, now);
            return -1L;
        }
        try (PreparedStatement credit = connection.prepareStatement(
                "UPDATE accounts SET balance_paise = balance_paise + ?, updated_at = ? WHERE uuid = ? AND balance_paise <= ?");
             PreparedStatement delete = connection.prepareStatement("DELETE FROM player_orders WHERE id = ?");
             PreparedStatement log = connection.prepareStatement("""
                     INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                     VALUES(?, 'ORDER_FILL', ?, ?, ?, ?)
                     """);
             PreparedStatement delivery = connection.prepareStatement("""
                     INSERT INTO pending_deliveries(id, player_uuid, material, amount, created_at)
                     VALUES(?, ?, ?, ?, ?)
                     """)) {
            credit.setLong(1, order.pricePaise);
            credit.setLong(2, now);
            credit.setString(3, operation.owner.toString());
            credit.setLong(4, Long.MAX_VALUE - order.pricePaise);
            if (credit.executeUpdate() != 1) {
                refundEscrowedItems(connection, operation, now);
                return -1L;
            }
            delete.setLong(1, order.id);
            if (delete.executeUpdate() != 1) {
                throw new SQLException("Player order changed while being fulfilled.");
            }
            log.setString(1, UUID.randomUUID().toString());
            log.setString(2, operation.owner.toString());
            log.setString(3, order.owner.toString());
            log.setLong(4, order.pricePaise);
            log.setLong(5, now);
            log.executeUpdate();
            delivery.setString(1, UUID.randomUUID().toString());
            delivery.setString(2, order.owner.toString());
            delivery.setString(3, order.material);
            delivery.setInt(4, order.amount);
            delivery.setLong(5, now);
            delivery.executeUpdate();
            return order.id;
        }
    }

    private void refundEscrowedItems(Connection connection, InventoryOperation operation, long now) throws SQLException {
        try (PreparedStatement refund = connection.prepareStatement("""
                INSERT INTO pending_deliveries(id, player_uuid, material, amount, created_at)
                VALUES(?, ?, ?, ?, ?)
                """)) {
            refund.setString(1, "refund-" + operation.id);
            refund.setString(2, operation.owner.toString());
            refund.setString(3, operation.material);
            refund.setInt(4, operation.amount);
            refund.setLong(5, now);
            refund.executeUpdate();
        }
    }

    public synchronized Order createOrder(UUID buyer, String username, String material, int amount,
                                          long totalBudget) throws SQLException {
        if (amount < 1 || totalBudget < 1) {
            throw new IllegalArgumentException("Invalid player order.");
        }
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            economy.ensureAccount(buyer, username);
            if (economy.isFrozen(buyer)) {
                connection.rollback();
                return null;
            }
            try (PreparedStatement debit = connection.prepareStatement(
                    "UPDATE accounts SET balance_paise = balance_paise - ?, updated_at = ? WHERE uuid = ? AND balance_paise >= ?");
                 PreparedStatement insert = connection.prepareStatement("""
                         INSERT INTO player_orders(buyer_uuid, material, amount, price_paise, created_at)
                         VALUES(?, ?, ?, ?, ?)
                         """, PreparedStatement.RETURN_GENERATED_KEYS);
                 PreparedStatement log = connection.prepareStatement("""
                         INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                         VALUES(?, 'ORDER_ESCROW', ?, NULL, ?, ?)
                         """)) {
                long now = System.currentTimeMillis();
                debit.setLong(1, totalBudget);
                debit.setLong(2, now);
                debit.setString(3, buyer.toString());
                debit.setLong(4, totalBudget);
                if (debit.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
                insert.setString(1, buyer.toString());
                insert.setString(2, material);
                insert.setInt(3, amount);
                insert.setLong(4, totalBudget);
                insert.setLong(5, now);
                insert.executeUpdate();
                long generatedId;
                try (ResultSet key = insert.getGeneratedKeys()) {
                    if (!key.next()) {
                        throw new SQLException("Database did not return the new order identifier.");
                    }
                    generatedId = key.getLong(1);
                }
                log.setString(1, UUID.randomUUID().toString());
                log.setString(2, buyer.toString());
                log.setLong(3, totalBudget);
                log.setLong(4, now);
                log.executeUpdate();
                connection.commit();
                return new Order(generatedId, buyer, material, amount, totalBudget, now);
            }
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized List<Listing> list(Kind kind, int limit) throws SQLException {
        String ownerColumn = kind == Kind.ORDERS ? "buyer_uuid" : "seller_uuid";
        String priceColumn = kind == Kind.AUCTION
                ? "CASE WHEN current_bid_paise > 0 THEN current_bid_paise ELSE price_paise END"
                : "price_paise";
        String sql = "SELECT id, " + ownerColumn + ", material, amount, " + priceColumn + ", created_at FROM "
                + kind.table + " ORDER BY created_at, id LIMIT ?";
        List<Listing> listings = new ArrayList<>();
        try (PreparedStatement query = database.connection().prepareStatement(sql)) {
            query.setInt(1, Math.max(1, Math.min(1000, limit)));
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    listings.add(new Listing(result.getLong(1), UUID.fromString(result.getString(2)),
                            result.getString(3), result.getInt(4), result.getLong(5), result.getLong(6)));
                }
            }
        }
        return listings;
    }

    public synchronized BidResult bidAuction(long id, UUID bidder, String bidderName, long amountPaise)
            throws SQLException {
        if (amountPaise < 1) {
            throw new IllegalArgumentException("Bid must be positive.");
        }
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            economy.ensureAccount(bidder, bidderName);
            if (economy.isFrozen(bidder)) {
                connection.rollback();
                return BidResult.rejected("Your account is frozen.");
            }
            AuctionState auction = findAuction(connection, id);
            long now = System.currentTimeMillis();
            if (auction == null || auction.expiresAt <= now) {
                connection.rollback();
                return BidResult.rejected("Auction does not exist or has expired.");
            }
            if (auction.seller.equals(bidder)) {
                connection.rollback();
                return BidResult.rejected("You cannot bid on your own auction.");
            }
            if (economy.isFrozen(auction.seller)) {
                connection.rollback();
                return BidResult.rejected("The auction owner account is frozen.");
            }
            long minimum = auction.currentBid > 0 ? Math.addExact(auction.currentBid, 1) : auction.startingBid;
            if (amountPaise < minimum) {
                connection.rollback();
                return BidResult.rejected("Minimum valid bid is ₹" + amountText(minimum) + ".");
            }
            long debitAmount = auction.currentBidder != null && auction.currentBidder.equals(bidder)
                    ? amountPaise - auction.currentBid : amountPaise;
            if (!debitAccount(connection, bidder, debitAmount, now)) {
                connection.rollback();
                return BidResult.rejected("Insufficient balance for this bid.");
            }
            if (auction.currentBidder != null && !auction.currentBidder.equals(bidder)
                    && !creditAccount(connection, auction.currentBidder, auction.currentBid, now)) {
                connection.rollback();
                return BidResult.rejected("Previous bidder could not be refunded; bid was not accepted.");
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE auctions SET current_bid_paise = ?, current_bidder_uuid = ?
                    WHERE id = ? AND expires_at > ? AND current_bid_paise = ?
                    """);
                 PreparedStatement bid = connection.prepareStatement("""
                         INSERT INTO auction_bids(auction_id, bidder_uuid, amount_paise, created_at)
                         VALUES(?, ?, ?, ?)
                         """);
                 PreparedStatement debitLog = connection.prepareStatement("""
                         INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                         VALUES(?, 'AUCTION_BID', ?, NULL, ?, ?)
                         """);
                 PreparedStatement refundLog = connection.prepareStatement("""
                         INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                         VALUES(?, 'AUCTION_BID_REFUND', NULL, ?, ?, ?)
                         """)) {
                update.setLong(1, amountPaise);
                update.setString(2, bidder.toString());
                update.setLong(3, id);
                update.setLong(4, now);
                update.setLong(5, auction.currentBid);
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return BidResult.rejected("Auction changed while your bid was being placed.");
                }
                bid.setLong(1, id);
                bid.setString(2, bidder.toString());
                bid.setLong(3, amountPaise);
                bid.setLong(4, now);
                bid.executeUpdate();
                debitLog.setString(1, UUID.randomUUID().toString());
                debitLog.setString(2, bidder.toString());
                debitLog.setLong(3, debitAmount);
                debitLog.setLong(4, now);
                debitLog.executeUpdate();
                if (auction.currentBidder != null && !auction.currentBidder.equals(bidder)) {
                    refundLog.setString(1, UUID.randomUUID().toString());
                    refundLog.setString(2, auction.currentBidder.toString());
                    refundLog.setLong(3, auction.currentBid);
                    refundLog.setLong(4, now);
                    refundLog.executeUpdate();
                }
            }
            connection.commit();
            return new BidResult(true, "Bid accepted.", amountPaise);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized int expireAuctions(long now) throws SQLException {
        List<Long> expired = new ArrayList<>();
        try (PreparedStatement query = database.connection().prepareStatement(
                "SELECT id FROM auctions WHERE expires_at <= ? ORDER BY expires_at, id")) {
            query.setLong(1, now);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    expired.add(result.getLong(1));
                }
            }
        }
        int settled = 0;
        for (long id : expired) {
            if (settleAuction(id, now)) {
                settled++;
            }
        }
        return settled;
    }

    private boolean settleAuction(long id, long now) throws SQLException {
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            AuctionState auction = findAuction(connection, id);
            if (auction == null || auction.expiresAt > now) {
                connection.rollback();
                return false;
            }
            UUID recipient = auction.currentBidder == null ? auction.seller : auction.currentBidder;
            if (auction.currentBidder != null
                    && (economy.isFrozen(auction.seller)
                    || !creditAccount(connection, auction.seller, auction.currentBid, now))) {
                if (!creditAccount(connection, auction.currentBidder, auction.currentBid, now)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement log = connection.prepareStatement("""
                        INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                        VALUES(?, 'AUCTION_BID_REFUND', NULL, ?, ?, ?)
                        """)) {
                    log.setString(1, UUID.randomUUID().toString());
                    log.setString(2, auction.currentBidder.toString());
                    log.setLong(3, auction.currentBid);
                    log.setLong(4, now);
                    log.executeUpdate();
                }
                recipient = auction.seller;
            } else if (auction.currentBidder != null) {
                try (PreparedStatement log = connection.prepareStatement("""
                        INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                        VALUES(?, 'AUCTION_WIN', ?, ?, ?, ?)
                        """)) {
                    log.setString(1, UUID.randomUUID().toString());
                    log.setString(2, auction.currentBidder.toString());
                    log.setString(3, auction.seller.toString());
                    log.setLong(4, auction.currentBid);
                    log.setLong(5, now);
                    log.executeUpdate();
                }
            }
            try (PreparedStatement delivery = connection.prepareStatement("""
                    INSERT INTO pending_deliveries(id, player_uuid, material, amount, created_at)
                    VALUES(?, ?, ?, ?, ?)
                    """);
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM auctions WHERE id = ?")) {
                delivery.setString(1, UUID.randomUUID().toString());
                delivery.setString(2, recipient.toString());
                delivery.setString(3, auction.material);
                delivery.setInt(4, auction.amount);
                delivery.setLong(5, now);
                delivery.executeUpdate();
                delete.setLong(1, id);
                if (delete.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private AuctionState findAuction(Connection connection, long id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT seller_uuid, material, amount, price_paise, current_bid_paise,
                  current_bidder_uuid, expires_at
                FROM auctions WHERE id = ?
                """)) {
            query.setLong(1, id);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                String bidder = result.getString(6);
                return new AuctionState(UUID.fromString(result.getString(1)), result.getString(2),
                        result.getInt(3), result.getLong(4), result.getLong(5),
                        bidder == null ? null : UUID.fromString(bidder), result.getLong(7));
            }
        }
    }

    private boolean debitAccount(Connection connection, UUID playerId, long amount, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE accounts SET balance_paise = balance_paise - ?, updated_at = ? WHERE uuid = ? AND balance_paise >= ?")) {
            update.setLong(1, amount);
            update.setLong(2, now);
            update.setString(3, playerId.toString());
            update.setLong(4, amount);
            return update.executeUpdate() == 1;
        }
    }

    private boolean creditAccount(Connection connection, UUID playerId, long amount, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE accounts SET balance_paise = balance_paise + ?, updated_at = ? WHERE uuid = ? AND balance_paise <= ?")) {
            update.setLong(1, amount);
            update.setLong(2, now);
            update.setString(3, playerId.toString());
            update.setLong(4, Long.MAX_VALUE - amount);
            return update.executeUpdate() == 1;
        }
    }

    private String amountText(long paise) {
        return java.math.BigDecimal.valueOf(paise, 2).toPlainString();
    }

    public synchronized Listing buyListing(Kind kind, long id, UUID buyer, String buyerName) throws SQLException {
        if (kind == Kind.ORDERS) {
            throw new IllegalArgumentException("Use fillOrder for player orders.");
        }
        if (kind == Kind.AUCTION) {
            throw new IllegalArgumentException("Auctions use bids, not direct purchases.");
        }
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Listing listing = findListing(connection, kind, id);
            if (listing == null || listing.owner.equals(buyer)) {
                connection.rollback();
                return null;
            }
            economy.ensureAccount(buyer, buyerName);
            if (economy.isFrozen(buyer) || economy.isFrozen(listing.owner)) {
                connection.rollback();
                return null;
            }
            String sellerName = accountName(connection, listing.owner);
            if (sellerName == null) {
                connection.rollback();
                return null;
            }
            try (PreparedStatement debit = connection.prepareStatement(
                    "UPDATE accounts SET balance_paise = balance_paise - ?, updated_at = ? WHERE uuid = ? AND balance_paise >= ?");
                 PreparedStatement credit = connection.prepareStatement(
                         "UPDATE accounts SET balance_paise = balance_paise + ?, updated_at = ? WHERE uuid = ? AND balance_paise <= ?");
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM " + kind.table + " WHERE id = ?");
                 PreparedStatement log = connection.prepareStatement("""
                         INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                         VALUES(?, ?, ?, ?, ?, ?)
                         """);
                 PreparedStatement delivery = connection.prepareStatement("""
                         INSERT INTO pending_deliveries(id, player_uuid, material, amount, created_at)
                         VALUES(?, ?, ?, ?, ?)
                         """)) {
                long now = System.currentTimeMillis();
                debit.setLong(1, listing.pricePaise);
                debit.setLong(2, now);
                debit.setString(3, buyer.toString());
                debit.setLong(4, listing.pricePaise);
                if (debit.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
                credit.setLong(1, listing.pricePaise);
                credit.setLong(2, now);
                credit.setString(3, listing.owner.toString());
                credit.setLong(4, Long.MAX_VALUE - listing.pricePaise);
                if (credit.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
                delete.setLong(1, id);
                if (delete.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
                log.setString(1, UUID.randomUUID().toString());
                log.setString(2, kind == Kind.AUCTION ? "AUCTION_BUY" : "MARKET_BUY");
                log.setString(3, buyer.toString());
                log.setString(4, listing.owner.toString());
                log.setLong(5, listing.pricePaise);
                log.setLong(6, now);
                log.executeUpdate();
                delivery.setString(1, UUID.randomUUID().toString());
                delivery.setString(2, buyer.toString());
                delivery.setString(3, listing.material);
                delivery.setInt(4, listing.amount);
                delivery.setLong(5, now);
                delivery.executeUpdate();
            }
            connection.commit();
            return listing;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized Order fillOrder(long id, UUID seller, String sellerName) throws SQLException {
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Listing order = findListing(connection, Kind.ORDERS, id);
            if (order == null || order.owner.equals(seller)) {
                connection.rollback();
                return null;
            }
            economy.ensureAccount(seller, sellerName);
            if (economy.isFrozen(seller) || economy.isFrozen(order.owner)) {
                connection.rollback();
                return null;
            }
            try (PreparedStatement credit = connection.prepareStatement(
                    "UPDATE accounts SET balance_paise = balance_paise + ?, updated_at = ? WHERE uuid = ? AND balance_paise <= ?");
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM player_orders WHERE id = ?");
                 PreparedStatement log = connection.prepareStatement("""
                         INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                         VALUES(?, 'ORDER_FILL', ?, ?, ?, ?)
                         """);
                 PreparedStatement delivery = connection.prepareStatement("""
                         INSERT INTO pending_deliveries(id, player_uuid, material, amount, created_at)
                         VALUES(?, ?, ?, ?, ?)
                         """)) {
                long now = System.currentTimeMillis();
                credit.setLong(1, order.pricePaise);
                credit.setLong(2, now);
                credit.setString(3, seller.toString());
                credit.setLong(4, Long.MAX_VALUE - order.pricePaise);
                if (credit.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
                delete.setLong(1, id);
                if (delete.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
                log.setString(1, UUID.randomUUID().toString());
                log.setString(2, seller.toString());
                log.setString(3, order.owner.toString());
                log.setLong(4, order.pricePaise);
                log.setLong(5, now);
                log.executeUpdate();
                delivery.setString(1, UUID.randomUUID().toString());
                delivery.setString(2, order.owner.toString());
                delivery.setString(3, order.material);
                delivery.setInt(4, order.amount);
                delivery.setLong(5, now);
                delivery.executeUpdate();
            }
            connection.commit();
            return new Order(order.id, order.owner, order.material, order.amount, order.pricePaise, order.createdAt);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized boolean removeListing(Kind kind, long id, UUID owner) throws SQLException {
        if (kind == Kind.ORDERS) {
            throw new IllegalArgumentException("Use cancelOrder for player orders.");
        }
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Listing listing = findListing(connection, kind, id);
            if (listing == null || (owner != null && !listing.owner.equals(owner))) {
                connection.rollback();
                return false;
            }
            AuctionState auction = kind == Kind.AUCTION ? findAuction(connection, id) : null;
            if (auction != null && auction.currentBidder != null) {
                if (!creditAccount(connection, auction.currentBidder, auction.currentBid, System.currentTimeMillis())) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement log = connection.prepareStatement("""
                        INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                        VALUES(?, 'AUCTION_BID_REFUND', NULL, ?, ?, ?)
                        """)) {
                    log.setString(1, UUID.randomUUID().toString());
                    log.setString(2, auction.currentBidder.toString());
                    log.setLong(3, auction.currentBid);
                    log.setLong(4, System.currentTimeMillis());
                    log.executeUpdate();
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + kind.table + " WHERE id = ?");
                 PreparedStatement delivery = connection.prepareStatement("""
                         INSERT INTO pending_deliveries(id, player_uuid, material, amount, created_at)
                         VALUES(?, ?, ?, ?, ?)
                         """)) {
                delete.setLong(1, id);
                delete.executeUpdate();
                delivery.setString(1, UUID.randomUUID().toString());
                delivery.setString(2, listing.owner.toString());
                delivery.setString(3, listing.material);
                delivery.setInt(4, listing.amount);
                delivery.setLong(5, System.currentTimeMillis());
                delivery.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized boolean cancelOrder(long id, UUID owner) throws SQLException {
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Listing order = findListing(connection, Kind.ORDERS, id);
            if (order == null || (owner != null && !order.owner.equals(owner))) {
                connection.rollback();
                return false;
            }
            try (PreparedStatement refund = connection.prepareStatement(
                    "UPDATE accounts SET balance_paise = balance_paise + ?, updated_at = ? WHERE uuid = ? AND balance_paise <= ?");
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM player_orders WHERE id = ?");
                 PreparedStatement log = connection.prepareStatement("""
                         INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                         VALUES(?, 'ORDER_REFUND', NULL, ?, ?, ?)
                         """)) {
                long now = System.currentTimeMillis();
                refund.setLong(1, order.pricePaise);
                refund.setLong(2, now);
                refund.setString(3, order.owner.toString());
                refund.setLong(4, Long.MAX_VALUE - order.pricePaise);
                if (refund.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
                delete.setLong(1, id);
                delete.executeUpdate();
                log.setString(1, UUID.randomUUID().toString());
                log.setString(2, order.owner.toString());
                log.setLong(3, order.pricePaise);
                log.setLong(4, now);
                log.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized int clear(Kind kind) throws SQLException {
        List<Listing> existing = list(kind, 1000);
        int removed = 0;
        for (Listing listing : existing) {
            boolean success = kind == Kind.ORDERS
                    ? cancelOrder(listing.id, null)
                    : removeListing(kind, listing.id, null);
            if (success) {
                removed++;
            }
        }
        return removed;
    }

    private Listing findListing(Connection connection, Kind kind, long id) throws SQLException {
        String ownerColumn = kind == Kind.ORDERS ? "buyer_uuid" : "seller_uuid";
        try (PreparedStatement query = connection.prepareStatement("SELECT id, " + ownerColumn
                + ", material, amount, price_paise, created_at FROM " + kind.table + " WHERE id = ?")) {
            query.setLong(1, id);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new Listing(result.getLong(1), UUID.fromString(result.getString(2)),
                        result.getString(3), result.getInt(4), result.getLong(5), result.getLong(6));
            }
        }
    }

    private String accountName(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT username FROM accounts WHERE uuid = ?")) {
            query.setString(1, id.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    public enum Kind {
        AUCTION("auctions"), MARKET("market_listings"), ORDERS("player_orders");

        private final String table;

        Kind(String table) {
            this.table = table;
        }
    }

    public enum Operation {
        SELL, AUCTION, MARKET
    }

    public record InventoryOperation(String id, UUID owner, String username, Operation operation, String material,
                                     int amount, long pricePaise, long createdAt, String state, Long resultId,
                                     Long relatedId) {
    }

    public record Listing(long id, UUID owner, String material, int amount, long pricePaise, long createdAt) {
    }

    public record Order(long id, UUID buyer, String material, int amount, long budgetPaise, long createdAt) {
    }

    public record BidResult(boolean accepted, String message, long currentBidPaise) {
        private static BidResult rejected(String message) {
            return new BidResult(false, message, 0);
        }
    }

    private record AuctionState(UUID seller, String material, int amount, long startingBid,
                                long currentBid, UUID currentBidder, long expiresAt) {
    }
}
