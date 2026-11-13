package io.github.shivafamily146.indianeconomy.shop;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void shippedCatalogDefinesAllRequestedCategoriesAndPricedItems() throws Exception {
        ShopCatalog catalog = ShopCatalog.load(
                new File("src/main/resources/shop.yml"),
                new File("src/main/resources/prices.yml"));
        assertEquals(Set.of("Blocks", "Weapons", "Armor", "Tools", "Food", "Farming", "Redstone",
                "Ores and Minerals", "Decoration", "Special Items"), catalog.categories().keySet());
        assertTrue(catalog.category("Blocks").contains(Material.STONE));
        assertTrue(catalog.isSellable(Material.STONE));
        assertEquals(100L, catalog.price(Material.STONE).buyPaise());
    }

    @Test
    void zeroSellPriceDisablesSellingAndUnknownMaterialsAreRejected() throws Exception {
        File shop = temporaryDirectory.resolve("shop.yml").toFile();
        File prices = temporaryDirectory.resolve("prices.yml").toFile();
        java.nio.file.Files.writeString(shop.toPath(), "categories:\n  Blocks:\n    - STONE\nsell-blacklist:\n  - DIRT\n");
        java.nio.file.Files.writeString(prices.toPath(),
                "prices:\n  STONE:\n    buy: 1.00\n    sell: 0.00\n  DIRT:\n    buy: 1.00\n    sell: 1.00\n");
        ShopCatalog catalog = ShopCatalog.load(shop, prices);
        assertFalse(catalog.isSellable(Material.STONE));
        assertFalse(catalog.isSellable(Material.DIRT));
        java.nio.file.Files.writeString(shop.toPath(), "categories:\n  Blocks:\n    - NOT_A_MATERIAL\n");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ShopCatalog.load(shop, prices));
    }
}
