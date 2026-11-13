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
    private final Database database;
    private final EconomyService economy;

    public MarketplaceService(Database database, EconomyService economy) {
        this.database = database;
        this.economy = economy;
    }

    public synchronized Listing createListing(Kind kind, UUID seller, String sellerName, String material, int amount,
                                               long pricePaise) throws SQLException {
        if (kind == Kind.ORDERS || amount < 1 || pricePaise < 1) {
            throw new IllegalArgumentException("Invalid item listing.");
        }
        economy.ensureAccount(seller, sellerName);
        if (economy.isFrozen(seller)) {
            return null;
        }
        try (PreparedStatement insert = database.connection().prepareStatement(
                "INSERT INTO " + kind.table + "(seller_uuid, material, amount, price_paise, created_at) VALUES(?, ?, ?, ?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            long now = System.currentTimeMillis();
            insert.setString(1, seller.toString());
            insert.setString(2, material);
            insert.setInt(3, amount);
            insert.setLong(4, pricePaise);
            insert.setLong(5, now);
            insert.executeUpdate();
            try (ResultSet key = insert.getGeneratedKeys()) {
                if (!key.next()) {
                    throw new SQLException("Database did not return the new listing identifier.");
                }
                return new Listing(key.getLong(1), seller, material, amount, pricePaise, now);
            }
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
        String sql = "SELECT id, " + ownerColumn + ", material, amount, price_paise, created_at FROM "
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

    public synchronized Listing buyListing(Kind kind, long id, UUID buyer, String buyerName) throws SQLException {
        if (kind == Kind.ORDERS) {
            throw new IllegalArgumentException("Use fillOrder for player orders.");
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

    public record Listing(long id, UUID owner, String material, int amount, long pricePaise, long createdAt) {
    }

    public record Order(long id, UUID buyer, String material, int amount, long budgetPaise, long createdAt) {
    }
}
