package io.github.shivafamily146.indianeconomy.economy;

import io.github.shivafamily146.indianeconomy.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class EconomyService {
    private final Database database;
    private long startingBalance;

    public EconomyService(Database database, long startingBalance) {
        this.database = database;
        this.startingBalance = startingBalance;
    }

    public synchronized void setStartingBalance(long startingBalance) {
        this.startingBalance = startingBalance;
    }

    public synchronized long balance(UUID playerId) throws SQLException {
        try (PreparedStatement query = database.connection().prepareStatement(
                "SELECT balance_paise FROM accounts WHERE uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : startingBalance;
            }
        }
    }

    public synchronized long ensureAccount(UUID playerId, String username) throws SQLException {
        Connection connection = database.connection();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO accounts(uuid, username, balance_paise, updated_at) VALUES(?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET username = excluded.username
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, username);
            insert.setLong(3, startingBalance);
            insert.setLong(4, System.currentTimeMillis());
            insert.executeUpdate();
        }
        return balance(playerId);
    }

    public synchronized boolean transfer(UUID sender, String senderName, UUID receiver, String receiverName,
                                         long amount) throws SQLException {
        if (sender.equals(receiver) || amount <= 0) {
            return false;
        }
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            ensureAccount(sender, senderName);
            ensureAccount(receiver, receiverName);
            if (isFrozen(sender) || isFrozen(receiver)) {
                connection.rollback();
                return false;
            }
            try (PreparedStatement debit = connection.prepareStatement(
                    "UPDATE accounts SET balance_paise = balance_paise - ?, updated_at = ? WHERE uuid = ? AND balance_paise >= ?");
                 PreparedStatement credit = connection.prepareStatement(
                         "UPDATE accounts SET balance_paise = balance_paise + ?, updated_at = ? WHERE uuid = ? AND balance_paise <= ?");
                 PreparedStatement log = connection.prepareStatement("""
                         INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                         VALUES(?, 'PAYMENT', ?, ?, ?, ?)
                         """)) {
                long now = System.currentTimeMillis();
                debit.setLong(1, amount);
                debit.setLong(2, now);
                debit.setString(3, sender.toString());
                debit.setLong(4, amount);
                if (debit.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
                credit.setLong(1, amount);
                credit.setLong(2, now);
                credit.setString(3, receiver.toString());
                credit.setLong(4, Long.MAX_VALUE - amount);
                if (credit.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
                log.setString(1, UUID.randomUUID().toString());
                log.setString(2, sender.toString());
                log.setString(3, receiver.toString());
                log.setLong(4, amount);
                log.setLong(5, now);
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

    public synchronized boolean adjust(UUID playerId, String username, long amount, String type) throws SQLException {
        if (amount == 0) {
            return false;
        }
        if (amount == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Amount is outside the supported range.");
        }
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            ensureAccount(playerId, username);
            String operation = amount > 0
                    ? "UPDATE accounts SET balance_paise = balance_paise + ?, updated_at = ? WHERE uuid = ? AND balance_paise <= ?"
                    : "UPDATE accounts SET balance_paise = balance_paise - ?, updated_at = ? WHERE uuid = ? AND balance_paise >= ?";
            try (PreparedStatement update = connection.prepareStatement(operation)) {
                update.setLong(1, Math.abs(amount));
                update.setLong(2, System.currentTimeMillis());
                update.setString(3, playerId.toString());
                if (amount > 0) {
                    update.setLong(4, Long.MAX_VALUE - amount);
                } else {
                    update.setLong(4, Math.abs(amount));
                }
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            try (PreparedStatement log = connection.prepareStatement("""
                    INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                    VALUES(?, ?, ?, ?, ?, ?)
                    """)) {
                log.setString(1, UUID.randomUUID().toString());
                log.setString(2, type);
                log.setString(3, amount < 0 ? playerId.toString() : null);
                log.setString(4, amount > 0 ? playerId.toString() : null);
                log.setLong(5, Math.abs(amount));
                log.setLong(6, System.currentTimeMillis());
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

    public synchronized boolean setBalance(UUID playerId, String username, long amount) throws SQLException {
        if (amount < 0) {
            return false;
        }
        ensureAccount(playerId, username);
        long oldBalance = balance(playerId);
        if (oldBalance == amount) {
            return true;
        }
        return adjust(playerId, username, amount - oldBalance, "ADMIN_SET");
    }

    public synchronized boolean claimDaily(UUID playerId, long now, long cooldownMillis, long reward) throws SQLException {
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (isFrozen(playerId)) {
                connection.rollback();
                return false;
            }
            long lastClaim = 0;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT claimed_at FROM daily_rewards WHERE uuid = ?")) {
                query.setString(1, playerId.toString());
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        lastClaim = result.getLong(1);
                    }
                }
            }
            if (lastClaim != 0 && now - lastClaim < cooldownMillis) {
                connection.rollback();
                return false;
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO daily_rewards(uuid, claimed_at) VALUES(?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET claimed_at = excluded.claimed_at
                    """)) {
                insert.setString(1, playerId.toString());
                insert.setLong(2, now);
                insert.executeUpdate();
            }
            try (PreparedStatement account = connection.prepareStatement("""
                    INSERT INTO accounts(uuid, username, balance_paise, updated_at) VALUES(?, '', ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET balance_paise = balance_paise + ?,
                      updated_at = excluded.updated_at WHERE balance_paise <= ?
                    """)) {
                account.setString(1, playerId.toString());
                account.setLong(2, Math.addExact(startingBalance, reward));
                account.setLong(3, now);
                account.setLong(4, reward);
                account.setLong(5, Long.MAX_VALUE - reward);
                if (account.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            try (PreparedStatement log = connection.prepareStatement("""
                    INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                    VALUES(?, 'DAILY', NULL, ?, ?, ?)
                    """)) {
                log.setString(1, UUID.randomUUID().toString());
                log.setString(2, playerId.toString());
                log.setLong(3, reward);
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

    public synchronized String reserveShopPurchase(UUID playerId, String username, String material, int amount,
                                                   long totalPrice) throws SQLException {
        if (amount < 1 || totalPrice <= 0) {
            return null;
        }
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        String id = UUID.randomUUID().toString();
        try {
            ensureAccount(playerId, username);
            if (isFrozen(playerId)) {
                connection.rollback();
                return null;
            }
            try (PreparedStatement debit = connection.prepareStatement(
                    "UPDATE accounts SET balance_paise = balance_paise - ?, updated_at = ? WHERE uuid = ? AND balance_paise >= ?");
                 PreparedStatement receipt = connection.prepareStatement("""
                         INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                         VALUES(?, 'SHOP_BUY', ?, NULL, ?, ?)
                         """);
                 PreparedStatement delivery = connection.prepareStatement("""
                         INSERT INTO pending_deliveries(id, player_uuid, material, amount, created_at)
                         VALUES(?, ?, ?, ?, ?)
                         """)) {
                long now = System.currentTimeMillis();
                debit.setLong(1, totalPrice);
                debit.setLong(2, now);
                debit.setString(3, playerId.toString());
                debit.setLong(4, totalPrice);
                if (debit.executeUpdate() != 1) {
                    connection.rollback();
                    return null;
                }
                receipt.setString(1, id);
                receipt.setString(2, playerId.toString());
                receipt.setLong(3, totalPrice);
                receipt.setLong(4, now);
                receipt.executeUpdate();
                delivery.setString(1, id);
                delivery.setString(2, playerId.toString());
                delivery.setString(3, material);
                delivery.setInt(4, amount);
                delivery.setLong(5, now);
                delivery.executeUpdate();
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

    public synchronized List<PendingDelivery> pendingDeliveries(UUID playerId) throws SQLException {
        List<PendingDelivery> pending = new ArrayList<>();
        try (PreparedStatement query = database.connection().prepareStatement(
                "SELECT id, material, amount FROM pending_deliveries WHERE player_uuid = ? ORDER BY created_at")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    pending.add(new PendingDelivery(result.getString(1), result.getString(2), result.getInt(3)));
                }
            }
        }
        return pending;
    }

    public synchronized boolean completeDelivery(String id, UUID playerId) throws SQLException {
        try (PreparedStatement delete = database.connection().prepareStatement(
                "DELETE FROM pending_deliveries WHERE id = ? AND player_uuid = ?")) {
            delete.setString(1, id);
            delete.setString(2, playerId.toString());
            return delete.executeUpdate() == 1;
        }
    }

    public synchronized List<Account> leaderboard(int limit) throws SQLException {
        List<Account> accounts = new ArrayList<>();
        try (PreparedStatement query = database.connection().prepareStatement(
                "SELECT uuid, username, balance_paise FROM accounts ORDER BY balance_paise DESC, username COLLATE NOCASE LIMIT ?")) {
            query.setInt(1, limit);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    accounts.add(new Account(UUID.fromString(result.getString(1)), result.getString(2), result.getLong(3)));
                }
            }
        }
        return accounts;
    }

    public synchronized List<Account> allAccounts() throws SQLException {
        List<Account> accounts = new ArrayList<>();
        try (PreparedStatement query = database.connection().prepareStatement(
                "SELECT uuid, username, balance_paise FROM accounts ORDER BY username COLLATE NOCASE");
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                accounts.add(new Account(UUID.fromString(result.getString(1)), result.getString(2), result.getLong(3)));
            }
        }
        return accounts;
    }

    public synchronized UUID findPlayer(String username) throws SQLException {
        try (PreparedStatement query = database.connection().prepareStatement(
                "SELECT uuid FROM accounts WHERE username = ? COLLATE NOCASE")) {
            query.setString(1, username);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? UUID.fromString(result.getString(1)) : null;
            }
        }
    }

    public synchronized void resetBalance(UUID playerId, String username) throws SQLException {
        ensureAccount(playerId, username);
        long oldBalance = balance(playerId);
        if (oldBalance != startingBalance) {
            adjust(playerId, username, startingBalance - oldBalance, "ADMIN_RESET");
        }
    }

    public synchronized int adjustAll(long amount, String type) throws SQLException {
        List<UUID> ids = allAccounts().stream().map(Account::playerId).toList();
        return adjustAccounts(ids, amount, type);
    }

    public synchronized int adjustAccounts(List<UUID> playerIds, long amount, String type) throws SQLException {
        if (amount == 0) {
            return 0;
        }
        if (amount == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Amount is outside the supported range.");
        }
        long magnitude = Math.abs(amount);
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        int changed = 0;
        try (PreparedStatement update = connection.prepareStatement(amount > 0
                ? "UPDATE accounts SET balance_paise = balance_paise + ?, updated_at = ? WHERE uuid = ? AND balance_paise <= ?"
                : "UPDATE accounts SET balance_paise = balance_paise - ?, updated_at = ? WHERE uuid = ? AND balance_paise >= ?");
             PreparedStatement log = connection.prepareStatement("""
                     INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                     VALUES(?, ?, ?, ?, ?, ?)
                     """)) {
            long now = System.currentTimeMillis();
            for (UUID playerId : playerIds.stream().distinct().toList()) {
                update.setLong(1, magnitude);
                update.setLong(2, now);
                update.setString(3, playerId.toString());
                update.setLong(4, amount > 0 ? Long.MAX_VALUE - amount : magnitude);
                if (update.executeUpdate() != 1) {
                    continue;
                }
                log.setString(1, UUID.randomUUID().toString());
                log.setString(2, type);
                log.setString(3, amount < 0 ? playerId.toString() : null);
                log.setString(4, amount > 0 ? playerId.toString() : null);
                log.setLong(5, magnitude);
                log.setLong(6, now);
                log.executeUpdate();
                changed++;
            }
            connection.commit();
            return changed;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized int resetAccounts(List<UUID> playerIds) throws SQLException {
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        int changed = 0;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance_paise FROM accounts WHERE uuid = ?");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE accounts SET balance_paise = ?, updated_at = ? WHERE uuid = ?");
             PreparedStatement log = connection.prepareStatement("""
                     INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                     VALUES(?, 'ADMIN_RESET', ?, ?, ?, ?)
                     """)) {
            long now = System.currentTimeMillis();
            for (UUID playerId : playerIds.stream().distinct().toList()) {
                query.setString(1, playerId.toString());
                long oldBalance;
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        continue;
                    }
                    oldBalance = result.getLong(1);
                }
                update.setLong(1, startingBalance);
                update.setLong(2, now);
                update.setString(3, playerId.toString());
                update.executeUpdate();
                if (oldBalance != startingBalance) {
                    log.setString(1, UUID.randomUUID().toString());
                    log.setString(2, oldBalance > startingBalance ? playerId.toString() : null);
                    log.setString(3, oldBalance < startingBalance ? playerId.toString() : null);
                    log.setLong(4, Math.abs(startingBalance - oldBalance));
                    log.setLong(5, now);
                    log.executeUpdate();
                }
                changed++;
            }
            connection.commit();
            return changed;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized int accountCount() throws SQLException {
        try (Statement statement = database.connection().createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    public synchronized boolean setFrozen(UUID playerId, String adminId, boolean frozen) throws SQLException {
        String sql = frozen
                ? "INSERT OR IGNORE INTO frozen_accounts(uuid, frozen_by, created_at) VALUES(?, ?, ?)"
                : "DELETE FROM frozen_accounts WHERE uuid = ?";
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            if (frozen) {
                statement.setString(2, adminId);
                statement.setLong(3, System.currentTimeMillis());
            }
            return statement.executeUpdate() == 1;
        }
    }

    public synchronized boolean isFrozen(UUID playerId) throws SQLException {
        try (PreparedStatement query = database.connection().prepareStatement(
                "SELECT 1 FROM frozen_accounts WHERE uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    public synchronized List<Transaction> transactions(UUID playerId, int limit, boolean paymentsOnly) throws SQLException {
        String sql = paymentsOnly
                ? "SELECT id, type, sender_uuid, receiver_uuid, amount_paise, created_at FROM transactions WHERE type = 'PAYMENT' AND (sender_uuid = ? OR receiver_uuid = ?) ORDER BY created_at DESC LIMIT ?"
                : "SELECT id, type, sender_uuid, receiver_uuid, amount_paise, created_at FROM transactions WHERE sender_uuid = ? OR receiver_uuid = ? ORDER BY created_at DESC LIMIT ?";
        return queryTransactions(sql, playerId.toString(), playerId.toString(), limit);
    }

    public synchronized List<Transaction> recentTransactions(int limit) throws SQLException {
        return queryTransactions("SELECT id, type, sender_uuid, receiver_uuid, amount_paise, created_at "
                + "FROM transactions ORDER BY created_at DESC LIMIT ?", limit);
    }

    public synchronized void logAdminAction(String adminId, String action, String target) throws SQLException {
        try (PreparedStatement insert = database.connection().prepareStatement(
                "INSERT INTO admin_logs(admin_uuid, action, target, created_at) VALUES(?, ?, ?, ?)")) {
            insert.setString(1, adminId);
            insert.setString(2, action);
            insert.setString(3, target);
            insert.setLong(4, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    public synchronized List<AdminLog> recentAdminLogs(int limit) throws SQLException {
        List<AdminLog> logs = new ArrayList<>();
        try (PreparedStatement query = database.connection().prepareStatement(
                "SELECT admin_uuid, action, target, created_at FROM admin_logs ORDER BY id DESC LIMIT ?")) {
            query.setInt(1, Math.max(1, Math.min(1000, limit)));
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    logs.add(new AdminLog(result.getString(1), result.getString(2),
                            result.getString(3), result.getLong(4)));
                }
            }
        }
        return logs;
    }

    private List<Transaction> queryTransactions(String sql, String first, String second, int limit) throws SQLException {
        List<Transaction> rows = new ArrayList<>();
        try (PreparedStatement query = database.connection().prepareStatement(sql)) {
            query.setString(1, first);
            query.setString(2, second);
            query.setInt(3, limit);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(readTransaction(result));
                }
            }
        }
        return rows;
    }

    private List<Transaction> queryTransactions(String sql, int limit) throws SQLException {
        List<Transaction> rows = new ArrayList<>();
        try (PreparedStatement query = database.connection().prepareStatement(sql)) {
            query.setInt(1, limit);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(readTransaction(result));
                }
            }
        }
        return rows;
    }

    private Transaction readTransaction(ResultSet result) throws SQLException {
        String sender = result.getString(3);
        String receiver = result.getString(4);
        return new Transaction(result.getString(1), result.getString(2),
                sender == null ? null : UUID.fromString(sender),
                receiver == null ? null : UUID.fromString(receiver), result.getLong(5), result.getLong(6));
    }

    public synchronized boolean resetDaily(UUID playerId) throws SQLException {
        try (PreparedStatement delete = database.connection().prepareStatement(
                "DELETE FROM daily_rewards WHERE uuid = ?")) {
            delete.setString(1, playerId.toString());
            return delete.executeUpdate() == 1;
        }
    }

    public synchronized int resetAllDaily() throws SQLException {
        try (Statement statement = database.connection().createStatement()) {
            return statement.executeUpdate("DELETE FROM daily_rewards");
        }
    }

    public synchronized int dailyRecordCount() throws SQLException {
        try (Statement statement = database.connection().createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM daily_rewards")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    public synchronized List<UUID> dailyPlayerIds() throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Statement statement = database.connection().createStatement();
             ResultSet result = statement.executeQuery("SELECT uuid FROM daily_rewards ORDER BY uuid")) {
            while (result.next()) {
                ids.add(UUID.fromString(result.getString(1)));
            }
        }
        return ids;
    }

    public synchronized int resetDailyPlayers(List<UUID> playerIds) throws SQLException {
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        int changed = 0;
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM daily_rewards WHERE uuid = ?")) {
            for (UUID playerId : playerIds.stream().distinct().toList()) {
                delete.setString(1, playerId.toString());
                changed += delete.executeUpdate();
            }
            connection.commit();
            return changed;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public synchronized void resetAll() throws SQLException {
        Connection connection = database.connection();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            long now = System.currentTimeMillis();
            try (PreparedStatement query = connection.prepareStatement("SELECT uuid, balance_paise FROM accounts");
                 ResultSet result = query.executeQuery();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE accounts SET balance_paise = ?, updated_at = ? WHERE uuid = ?");
                 PreparedStatement log = connection.prepareStatement("""
                         INSERT INTO transactions(id, type, sender_uuid, receiver_uuid, amount_paise, created_at)
                         VALUES(?, 'ADMIN_RESET_ALL', ?, ?, ?, ?)
                         """)) {
                while (result.next()) {
                    String id = result.getString(1);
                    long oldBalance = result.getLong(2);
                    update.setLong(1, startingBalance);
                    update.setLong(2, now);
                    update.setString(3, id);
                    update.addBatch();
                    if (oldBalance != startingBalance) {
                        log.setString(1, UUID.randomUUID().toString());
                        log.setString(2, oldBalance > startingBalance ? id : null);
                        log.setString(3, oldBalance < startingBalance ? id : null);
                        log.setLong(4, Math.abs(startingBalance - oldBalance));
                        log.setLong(5, now);
                        log.addBatch();
                    }
                }
                update.executeBatch();
                log.executeBatch();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public record Account(UUID playerId, String username, long balancePaise) {
    }

    public record Transaction(String id, String type, UUID sender, UUID receiver, long amountPaise, long createdAt) {
    }

    public record AdminLog(String adminId, String action, String target, long createdAt) {
    }

    public record PendingDelivery(String id, String material, int amount) {
    }
}
