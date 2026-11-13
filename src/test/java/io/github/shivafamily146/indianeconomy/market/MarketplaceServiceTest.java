package io.github.shivafamily146.indianeconomy.market;

import io.github.shivafamily146.indianeconomy.database.Database;
import io.github.shivafamily146.indianeconomy.economy.EconomyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketplaceServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void listingPurchaseTransfersMoneyAndPersistsItemDelivery() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("market.db"))) {
            EconomyService economy = new EconomyService(database, 1000);
            MarketplaceService market = new MarketplaceService(database, economy);
            UUID seller = UUID.randomUUID();
            UUID buyer = UUID.randomUUID();
            economy.ensureAccount(seller, "Seller");
            economy.ensureAccount(buyer, "Buyer");
            MarketplaceService.Listing listing = createListing(
                    market, MarketplaceService.Kind.MARKET, seller, "Seller", "DIAMOND", 2, 300);
            MarketplaceService.Listing purchased = market.buyListing(
                    MarketplaceService.Kind.MARKET, listing.id(), buyer, "Buyer");
            assertNotNull(purchased);
            assertTrue(market.list(MarketplaceService.Kind.MARKET, 10).isEmpty());
            assertEquals(700, economy.balance(buyer));
            assertEquals(1300, economy.balance(seller));
            assertEquals(1, economy.pendingDeliveries(buyer).size());
        }
    }

    @Test
    void orderEscrowCanBeFilledOrCancelledAndRefunded() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("orders.db"))) {
            EconomyService economy = new EconomyService(database, 1000);
            MarketplaceService market = new MarketplaceService(database, economy);
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();
            economy.ensureAccount(buyer, "Buyer");
            economy.ensureAccount(seller, "Seller");
            MarketplaceService.Order order = market.createOrder(buyer, "Buyer", "BREAD", 10, 250);
            assertNotNull(order);
            assertEquals(750, economy.balance(buyer));
            MarketplaceService.Order filled = market.fillOrder(order.id(), seller, "Seller");
            assertNotNull(filled);
            assertEquals(1250, economy.balance(seller));
            assertEquals(1, economy.pendingDeliveries(buyer).size());

            MarketplaceService.Order refundable = market.createOrder(buyer, "Buyer", "STONE", 4, 100);
            assertNotNull(refundable);
            assertTrue(market.cancelOrder(refundable.id(), buyer));
            assertEquals(750, economy.balance(buyer));
            assertFalse(market.cancelOrder(refundable.id(), buyer));
            assertNull(market.buyListing(MarketplaceService.Kind.MARKET, 999, buyer, "Buyer"));
        }
    }

    @Test
    void preparedSaleIsCrashRecoverableAndCreditsOnlyOnce() throws Exception {
        Path file = temporaryDirectory.resolve("sale-journal.db");
        UUID seller = UUID.randomUUID();
        String operationId;
        try (Database database = new Database(file)) {
            EconomyService economy = new EconomyService(database, 1000);
            MarketplaceService market = new MarketplaceService(database, economy);
            economy.ensureAccount(seller, "Seller");
            operationId = market.prepareInventoryOperation(MarketplaceService.Operation.SELL,
                    seller, "Seller", "STONE", 4, 500);
            assertEquals(1, market.pendingInventoryOperations(seller).size());
        }
        try (Database database = new Database(file)) {
            EconomyService economy = new EconomyService(database, 0);
            MarketplaceService market = new MarketplaceService(database, economy);
            MarketplaceService.InventoryOperation completed = market.completeInventoryOperation(operationId);
            assertEquals(0L, completed.resultId());
            assertEquals(1500, economy.balance(seller));
            assertEquals(0, market.pendingInventoryOperations(seller).size());

            assertEquals("COMPLETED", market.completeInventoryOperation(operationId).state());
            assertEquals(1500, economy.balance(seller));
            assertEquals(1, economy.transactions(seller, 10, false).size());
        }
    }

    @Test
    void preparedListingIsInvisibleUntilEscrowCommitAndCannotDuplicate() throws Exception {
        Path file = temporaryDirectory.resolve("listing-journal.db");
        String operationId;
        try (Database database = new Database(file)) {
            EconomyService economy = new EconomyService(database, 1000);
            MarketplaceService market = new MarketplaceService(database, economy);
            UUID seller = UUID.randomUUID();
            economy.ensureAccount(seller, "Seller");
            operationId = market.prepareInventoryOperation(MarketplaceService.Operation.AUCTION,
                    seller, "Seller", "DIAMOND", 3, 500);
            assertTrue(market.list(MarketplaceService.Kind.AUCTION, 10).isEmpty());
        }

        try (Database database = new Database(file)) {
            MarketplaceService market = new MarketplaceService(database, new EconomyService(database, 0));
            MarketplaceService.InventoryOperation completed = market.completeInventoryOperation(operationId);
            assertNotNull(completed.resultId());
            assertEquals(1, market.list(MarketplaceService.Kind.AUCTION, 10).size());
            market.completeInventoryOperation(operationId);
            assertEquals(1, market.list(MarketplaceService.Kind.AUCTION, 10).size());
        }
    }

    @Test
    void escrowedOrderFulfillmentCommitsPaymentAndDeliveryTogether() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("order-journal.db"))) {
            EconomyService economy = new EconomyService(database, 1000);
            MarketplaceService market = new MarketplaceService(database, economy);
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();
            economy.ensureAccount(buyer, "Buyer");
            economy.ensureAccount(seller, "Seller");
            MarketplaceService.Order order = market.createOrder(buyer, "Buyer", "BREAD", 10, 250);
            String operationId = market.prepareInventoryOperation(MarketplaceService.Operation.MARKET,
                    seller, "Seller", "BREAD", 10, order.budgetPaise(), order.id());

            assertEquals(750, economy.balance(buyer));
            assertEquals(1000, economy.balance(seller));
            MarketplaceService.InventoryOperation completed = market.completeInventoryOperation(operationId);
            assertEquals(order.id(), completed.resultId());
            assertEquals(1250, economy.balance(seller));
            assertTrue(market.list(MarketplaceService.Kind.ORDERS, 10).isEmpty());
            assertEquals(1, economy.pendingDeliveries(buyer).size());
            market.completeInventoryOperation(operationId);
            assertEquals(1250, economy.balance(seller));
            assertEquals(1, economy.pendingDeliveries(buyer).size());
        }
    }

    @Test
    void auctionsEscrowBidsRefundOutbidPlayersAndSettleWhenExpired() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("bidding.db"))) {
            EconomyService economy = new EconomyService(database, 1000);
            MarketplaceService market = new MarketplaceService(database, economy);
            UUID seller = UUID.randomUUID();
            UUID bidder1 = UUID.randomUUID();
            UUID bidder2 = UUID.randomUUID();
            economy.ensureAccount(seller, "Seller");
            economy.ensureAccount(bidder1, "Bidder1");
            economy.ensureAccount(bidder2, "Bidder2");
            MarketplaceService.Listing auction = createListing(
                    market, MarketplaceService.Kind.AUCTION, seller, "Seller", "DIAMOND", 2, 300);

            assertTrue(market.bidAuction(auction.id(), bidder1, "Bidder1", 300).accepted());
            assertEquals(700, economy.balance(bidder1));
            assertTrue(market.bidAuction(auction.id(), bidder2, "Bidder2", 400).accepted());
            assertEquals(1000, economy.balance(bidder1));
            assertEquals(600, economy.balance(bidder2));
            assertTrue(market.bidAuction(auction.id(), bidder2, "Bidder2", 450).accepted());
            assertEquals(550, economy.balance(bidder2));
            assertFalse(market.bidAuction(auction.id(), bidder1, "Bidder1", 449).accepted());
            assertFalse(market.bidAuction(auction.id(), seller, "Seller", 500).accepted());

            assertEquals(1, market.expireAuctions(System.currentTimeMillis() + 25L * 60 * 60 * 1000));
            assertEquals(1450, economy.balance(seller));
            assertEquals(550, economy.balance(bidder2));
            assertTrue(market.list(MarketplaceService.Kind.AUCTION, 10).isEmpty());
            assertEquals(1, economy.pendingDeliveries(bidder2).size());
            assertFalse(market.bidAuction(auction.id(), bidder1, "Bidder1", 500).accepted());
        }
    }

    @Test
    void migrationAddsTimedAuctionAndInventoryJournalColumns() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v3.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_version(version INTEGER NOT NULL)");
            statement.execute("INSERT INTO schema_version VALUES(3)");
            statement.execute("""
                    CREATE TABLE auctions(
                      id INTEGER PRIMARY KEY AUTOINCREMENT, seller_uuid TEXT NOT NULL, material TEXT NOT NULL,
                      amount INTEGER NOT NULL, price_paise INTEGER NOT NULL, created_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO auctions(seller_uuid, material, amount, price_paise, created_at) "
                    + "VALUES('seller', 'DIAMOND', 2, 500, 1000)");
        }

        try (Database database = new Database(file);
             var result = database.connection().createStatement().executeQuery(
                     "SELECT expires_at, current_bid_paise FROM auctions WHERE id = 1")) {
            assertTrue(result.next());
            assertEquals(86_401_000L, result.getLong("expires_at"));
            assertEquals(0, result.getLong("current_bid_paise"));
            try (var version = database.connection().createStatement().executeQuery(
                    "SELECT version FROM schema_version")) {
                assertTrue(version.next());
                assertEquals(4, version.getInt(1));
            }
        }
    }

    @Test
    void adminAuctionRemovalRefundsHighestBidAndReturnsItems() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("auction-remove.db"))) {
            EconomyService economy = new EconomyService(database, 1000);
            MarketplaceService market = new MarketplaceService(database, economy);
            UUID seller = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.ensureAccount(seller, "Seller");
            economy.ensureAccount(bidder, "Bidder");
            MarketplaceService.Listing auction = createListing(
                    market, MarketplaceService.Kind.AUCTION, seller, "Seller", "IRON_INGOT", 5, 100);
            assertTrue(market.bidAuction(auction.id(), bidder, "Bidder", 150).accepted());

            assertTrue(market.removeListing(MarketplaceService.Kind.AUCTION, auction.id(), null));
            assertEquals(1000, economy.balance(bidder));
            assertEquals(1, economy.pendingDeliveries(seller).size());
            assertTrue(market.list(MarketplaceService.Kind.AUCTION, 10).isEmpty());
        }
    }

    @Test
    void concurrentMarketPurchasesCannotChargeOrDeliverTwice() throws Exception {
        try (Database database = new Database(temporaryDirectory.resolve("concurrent-buy.db"))) {
            EconomyService economy = new EconomyService(database, 1000);
            MarketplaceService market = new MarketplaceService(database, economy);
            UUID seller = UUID.randomUUID();
            UUID buyerOne = UUID.randomUUID();
            UUID buyerTwo = UUID.randomUUID();
            economy.ensureAccount(seller, "Seller");
            economy.ensureAccount(buyerOne, "BuyerOne");
            economy.ensureAccount(buyerTwo, "BuyerTwo");
            MarketplaceService.Listing listing = createListing(
                    market, MarketplaceService.Kind.MARKET, seller, "Seller", "STONE", 4, 250);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Future<MarketplaceService.Listing> one = pool.submit(() -> {
                    start.await();
                    return market.buyListing(MarketplaceService.Kind.MARKET, listing.id(), buyerOne, "BuyerOne");
                });
                Future<MarketplaceService.Listing> two = pool.submit(() -> {
                    start.await();
                    return market.buyListing(MarketplaceService.Kind.MARKET, listing.id(), buyerTwo, "BuyerTwo");
                });
                start.countDown();
                int successes = (one.get() == null ? 0 : 1) + (two.get() == null ? 0 : 1);
                assertEquals(1, successes);
            }
            assertEquals(1250, economy.balance(seller));
            assertEquals(1, economy.pendingDeliveries(buyerOne).size()
                    + economy.pendingDeliveries(buyerTwo).size());
        }
    }

    private MarketplaceService.Listing createListing(MarketplaceService market, MarketplaceService.Kind kind,
                                                      UUID seller, String name, String material, int amount,
                                                      long price) throws Exception {
        MarketplaceService.Operation operation = kind == MarketplaceService.Kind.AUCTION
                ? MarketplaceService.Operation.AUCTION : MarketplaceService.Operation.MARKET;
        String operationId = market.prepareInventoryOperation(operation, seller, name, material, amount, price);
        MarketplaceService.InventoryOperation completed = market.completeInventoryOperation(operationId);
        return market.list(kind, 10).stream()
                .filter(listing -> listing.id() == completed.resultId()).findFirst().orElseThrow();
    }
}
