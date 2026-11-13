package io.github.shivafamily146.indianeconomy.market;

import io.github.shivafamily146.indianeconomy.database.Database;
import io.github.shivafamily146.indianeconomy.economy.EconomyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

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
            MarketplaceService.Listing listing = market.createListing(
                    MarketplaceService.Kind.AUCTION, seller, "Seller", "DIAMOND", 2, 300);
            MarketplaceService.Listing purchased = market.buyListing(
                    MarketplaceService.Kind.AUCTION, listing.id(), buyer, "Buyer");
            assertNotNull(purchased);
            assertTrue(market.list(MarketplaceService.Kind.AUCTION, 10).isEmpty());
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
            assertNull(market.buyListing(MarketplaceService.Kind.AUCTION, 999, buyer, "Buyer"));
        }
    }
}
