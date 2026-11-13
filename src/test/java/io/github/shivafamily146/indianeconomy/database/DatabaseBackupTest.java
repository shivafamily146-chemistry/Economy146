package io.github.shivafamily146.indianeconomy.database;

import io.github.shivafamily146.indianeconomy.economy.EconomyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseBackupTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void backupCanRestorePreviouslyCommittedAccountData() throws Exception {
        Path live = temporaryDirectory.resolve("live.db");
        Path backup = temporaryDirectory.resolve("backup.db");
        UUID player = UUID.randomUUID();
        try (Database database = new Database(live)) {
            EconomyService economy = new EconomyService(database, 1000);
            economy.ensureAccount(player, "Player");
            database.backup(backup);
            economy.adjust(player, "Player", 500, "ADMIN_GIVE");
            assertEquals(1500, economy.balance(player));
            database.restore(backup);
            assertEquals(1000, economy.balance(player));
        }
    }
}
