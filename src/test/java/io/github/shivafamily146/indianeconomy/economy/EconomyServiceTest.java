package io.github.shivafamily146.indianeconomy.economy;

import io.github.shivafamily146.indianeconomy.database.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void transfersAreAtomicAndPersistAcrossDatabaseRestart() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        Path file = temporaryDirectory.resolve("economy.db");
        try (Database database = new Database(file)) {
            EconomyService economy = new EconomyService(database, 1000);
            economy.ensureAccount(sender, "Sender");
            economy.ensureAccount(receiver, "Receiver");
            assertTrue(economy.transfer(sender, "Sender", receiver, "Receiver", 250));
            assertFalse(economy.transfer(sender, "Sender", receiver, "Receiver", 1000));
            assertEquals(750, economy.balance(sender));
            assertEquals(1250, economy.balance(receiver));
        }
        try (Database database = new Database(file)) {
            EconomyService economy = new EconomyService(database, 0);
            assertEquals(750, economy.balance(sender));
            assertEquals(1250, economy.balance(receiver));
        }
    }

    @Test
    void dailyRewardCannotBeClaimedTwiceDuringCooldown() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("daily.db"))) {
            EconomyService economy = new EconomyService(database, 100);
            UUID player = UUID.randomUUID();
            assertTrue(economy.claimDaily(player, 10_000, 86_400_000, 25));
            assertFalse(economy.claimDaily(player, 10_001, 86_400_000, 25));
            assertEquals(125, economy.balance(player));
        }
    }

    @Test
    void administrativeDebitCannotCreateNegativeBalances() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("admin.db"))) {
            EconomyService economy = new EconomyService(database, 100);
            UUID player = UUID.randomUUID();
            economy.ensureAccount(player, "Player");
            assertFalse(economy.adjust(player, "Player", -101, "ADMIN_TAKE"));
            assertEquals(100, economy.balance(player));
            assertTrue(economy.adjust(player, "Player", 50, "ADMIN_GIVE"));
            assertEquals(150, economy.balance(player));
        }
    }

    @Test
    void transferRollsBackWhenReceiverBalanceWouldOverflow() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("overflow.db"))) {
            EconomyService economy = new EconomyService(database, 100);
            UUID sender = UUID.randomUUID();
            UUID receiver = UUID.randomUUID();
            economy.ensureAccount(sender, "Sender");
            economy.ensureAccount(receiver, "Receiver");
            assertTrue(economy.setBalance(receiver, "Receiver", Long.MAX_VALUE));
            assertFalse(economy.transfer(sender, "Sender", receiver, "Receiver", 1));
            assertEquals(100, economy.balance(sender));
            assertEquals(Long.MAX_VALUE, economy.balance(receiver));
        }
    }

    @Test
    void shopPurchasePersistsAsPendingDeliveryUntilAcknowledged() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = temporaryDirectory.resolve("shop.db");
        String purchaseId;
        try (Database database = new Database(file)) {
            EconomyService economy = new EconomyService(database, 1000);
            purchaseId = economy.reserveShopPurchase(player, "Player", "STONE", 4, 400);
            assertEquals(600, economy.balance(player));
            assertEquals(1, economy.pendingDeliveries(player).size());
        }
        try (Database database = new Database(file)) {
            EconomyService economy = new EconomyService(database, 0);
            assertEquals(purchaseId, economy.pendingDeliveries(player).getFirst().id());
            assertTrue(economy.completeDelivery(purchaseId, player));
            assertTrue(economy.pendingDeliveries(player).isEmpty());
        }
    }

    @Test
    void frozenPlayersCannotTransferMoney() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("frozen.db"))) {
            EconomyService economy = new EconomyService(database, 1000);
            UUID sender = UUID.randomUUID();
            UUID receiver = UUID.randomUUID();
            economy.ensureAccount(sender, "Sender");
            economy.ensureAccount(receiver, "Receiver");
            assertTrue(economy.setFrozen(sender, "admin", true));
            assertFalse(economy.transfer(sender, "Sender", receiver, "Receiver", 100));
            assertEquals(1000, economy.balance(sender));
        }
    }

    @Test
    void bulkAdjustAndResetOnlyReviewedAccountsAtomically() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("bulk.db"))) {
            EconomyService economy = new EconomyService(database, 1000);
            UUID reviewed = UUID.randomUUID();
            UUID reviewed2 = UUID.randomUUID();
            UUID laterAccount = UUID.randomUUID();
            economy.ensureAccount(reviewed, "Reviewed");
            economy.ensureAccount(reviewed2, "Reviewed2");
            List<UUID> snapshot = economy.allAccounts().stream().map(EconomyService.Account::playerId).toList();
            economy.ensureAccount(laterAccount, "Later");

            assertEquals(2, economy.adjustAccounts(snapshot, -250, "ADMIN_REMOVEALL"));
            assertEquals(750, economy.balance(reviewed));
            assertEquals(750, economy.balance(reviewed2));
            assertEquals(1000, economy.balance(laterAccount));

            economy.setBalance(reviewed, "Reviewed", 50);
            assertEquals(2, economy.resetAccounts(snapshot));
            assertEquals(1000, economy.balance(reviewed));
            assertEquals(1000, economy.balance(reviewed2));
            assertEquals(1000, economy.balance(laterAccount));
        }
    }

    @Test
    void dailyResetSnapshotDoesNotDeleteLaterClaims() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("daily-snapshot.db"))) {
            EconomyService economy = new EconomyService(database, 100);
            UUID reviewed = UUID.randomUUID();
            UUID later = UUID.randomUUID();
            assertTrue(economy.claimDaily(reviewed, 10_000, 86_400_000, 25));
            List<UUID> snapshot = economy.dailyPlayerIds();
            assertTrue(economy.claimDaily(later, 10_000, 86_400_000, 25));

            assertEquals(1, economy.resetDailyPlayers(snapshot));
            assertTrue(economy.claimDaily(reviewed, 10_001, 86_400_000, 25));
            assertFalse(economy.claimDaily(later, 10_001, 86_400_000, 25));
        }
    }

    @Test
    void administrativeCommandsArePersistedInAuditLog() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("admin-log.db"))) {
            EconomyService economy = new EconomyService(database, 0);
            economy.logAdminAction("admin-uuid", "freeze Player", "Player");
            EconomyService.AdminLog entry = economy.recentAdminLogs(10).getFirst();
            assertEquals("admin-uuid", entry.adminId());
            assertEquals("freeze Player", entry.action());
            assertEquals("Player", entry.target());
        }
    }
}
