package io.github.shivafamily146.indianeconomy.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database implements AutoCloseable {
    private static final int SCHEMA_VERSION = 4;
    private final Path file;
    private Connection connection;

    public Database(Path file) throws SQLException, IOException {
        this.file = file.toAbsolutePath().normalize();
        Files.createDirectories(this.file.getParent());
        connection = connect(this.file);
        migrate();
    }

    private Connection connect(Path path) throws SQLException {
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement statement = opened.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return opened;
    }

    private synchronized void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                      version INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO schema_version(version)
                    SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM schema_version)
                    """);
            try (var version = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                if (version.next() && version.getInt(1) > SCHEMA_VERSION) {
                    throw new SQLException("Database schema is newer than this plugin.");
                }
            }
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                      uuid TEXT PRIMARY KEY,
                      username TEXT NOT NULL COLLATE NOCASE,
                      balance_paise INTEGER NOT NULL CHECK(balance_paise >= 0),
                      updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS accounts_balance_idx ON accounts(balance_paise DESC)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                      id TEXT PRIMARY KEY,
                      type TEXT NOT NULL,
                      sender_uuid TEXT,
                      receiver_uuid TEXT,
                      amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS transactions_sender_idx ON transactions(sender_uuid, created_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS transactions_receiver_idx ON transactions(receiver_uuid, created_at DESC)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS daily_rewards (
                      uuid TEXT PRIMARY KEY,
                      claimed_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS shop_items (
                      material TEXT PRIMARY KEY,
                      buy_paise INTEGER NOT NULL CHECK(buy_paise >= 0),
                      sell_paise INTEGER NOT NULL CHECK(sell_paise >= 0)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS auctions (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      seller_uuid TEXT NOT NULL,
                      material TEXT NOT NULL,
                      amount INTEGER NOT NULL CHECK(amount > 0),
                      price_paise INTEGER NOT NULL CHECK(price_paise > 0),
                      created_at INTEGER NOT NULL
                    )
                    """);
            ensureColumn(statement, "auctions", "expires_at", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(statement, "auctions", "current_bid_paise", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(statement, "auctions", "current_bidder_uuid", "TEXT");
            statement.execute("UPDATE auctions SET expires_at = created_at + 86400000 WHERE expires_at = 0");
            statement.execute("CREATE INDEX IF NOT EXISTS auctions_expiry_idx ON auctions(expires_at)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS auction_bids (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      auction_id INTEGER NOT NULL,
                      bidder_uuid TEXT NOT NULL,
                      amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS auction_bids_auction_idx ON auction_bids(auction_id, created_at DESC)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS market_listings (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      seller_uuid TEXT NOT NULL,
                      material TEXT NOT NULL,
                      amount INTEGER NOT NULL CHECK(amount > 0),
                      price_paise INTEGER NOT NULL CHECK(price_paise > 0),
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_orders (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      buyer_uuid TEXT NOT NULL,
                      material TEXT NOT NULL,
                      amount INTEGER NOT NULL CHECK(amount > 0),
                      price_paise INTEGER NOT NULL CHECK(price_paise > 0),
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS frozen_accounts (
                      uuid TEXT PRIMARY KEY,
                      frozen_by TEXT NOT NULL,
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS admin_logs (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      admin_uuid TEXT NOT NULL,
                      action TEXT NOT NULL,
                      target TEXT,
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pending_deliveries (
                      id TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      material TEXT NOT NULL,
                      amount INTEGER NOT NULL CHECK(amount > 0),
                      created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS pending_deliveries_player_idx ON pending_deliveries(player_uuid)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS item_operations (
                      id TEXT PRIMARY KEY,
                      owner_uuid TEXT NOT NULL,
                      username TEXT NOT NULL,
                      operation TEXT NOT NULL CHECK(operation IN ('SELL', 'AUCTION', 'MARKET')),
                      material TEXT NOT NULL,
                      amount INTEGER NOT NULL CHECK(amount > 0),
                      price_paise INTEGER NOT NULL CHECK(price_paise > 0),
                      related_id INTEGER,
                      created_at INTEGER NOT NULL,
                      state TEXT NOT NULL CHECK(state IN ('PREPARED', 'COMPLETED')),
                      result_id INTEGER
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS item_operations_owner_idx ON item_operations(owner_uuid, state)");
            statement.execute("DELETE FROM schema_version");
            statement.execute("INSERT INTO schema_version(version) VALUES(" + SCHEMA_VERSION + ")");
        }
    }

    private void ensureColumn(Statement statement, String table, String column, String definition)
            throws SQLException {
        boolean exists = false;
        try (var columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equalsIgnoreCase(columns.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    public synchronized Connection connection() {
        return connection;
    }

    public synchronized Path backup(Path destination) throws SQLException, IOException {
        Path target = destination.toAbsolutePath().normalize();
        if (target.equals(file) || Files.exists(target)) {
            throw new IOException("Backup destination already exists or is the live database.");
        }
        Files.createDirectories(target.getParent());
        try (java.sql.PreparedStatement vacuum = connection.prepareStatement("VACUUM INTO ?")) {
            vacuum.setString(1, target.toString());
            vacuum.execute();
        }
        return target;
    }

    public synchronized void save() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(FULL)");
            statement.execute("PRAGMA optimize");
        }
    }

    public synchronized String integrityCheck() throws SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA quick_check")) {
            return result.next() ? result.getString(1) : "no-result";
        }
    }

    public synchronized void restore(Path backup) throws SQLException, IOException {
        Path source = backup.toAbsolutePath().normalize();
        if (source.equals(file) || !Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
            throw new IOException("Restore source is not a safe regular backup file.");
        }
        try (Connection check = DriverManager.getConnection("jdbc:sqlite:" + source + "?mode=ro");
             Statement statement = check.createStatement();
             var result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("Backup integrity check failed.");
            }
        }

        Path staged = file.resolveSibling(file.getFileName() + ".restore");
        Path previous = file.resolveSibling(file.getFileName() + ".previous");
        Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        connection.close();
        boolean originalMoved = false;
        try {
            move(file, previous);
            originalMoved = true;
            move(staged, file);
            connection = connect(file);
            migrate();
            Files.deleteIfExists(previous);
        } catch (SQLException | IOException exception) {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
            if (originalMoved && Files.exists(previous)) {
                move(previous, file);
            }
            connection = connect(file);
            throw exception;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
