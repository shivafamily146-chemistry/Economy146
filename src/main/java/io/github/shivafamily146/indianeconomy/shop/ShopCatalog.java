package io.github.shivafamily146.indianeconomy.shop;

import io.github.shivafamily146.indianeconomy.utils.Money;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ShopCatalog {
    private final Map<String, List<Material>> categories;
    private final Map<Material, Price> prices;
    private final Set<Material> sellBlacklist;

    private ShopCatalog(Map<String, List<Material>> categories, Map<Material, Price> prices,
                        Set<Material> sellBlacklist) {
        this.categories = Collections.unmodifiableMap(categories);
        this.prices = Collections.unmodifiableMap(prices);
        this.sellBlacklist = Set.copyOf(sellBlacklist);
    }

    public static ShopCatalog load(File shopFile, File priceFile) throws IOException, InvalidConfigurationException {
        YamlConfiguration shop = new YamlConfiguration();
        YamlConfiguration priceConfig = new YamlConfiguration();
        shop.load(shopFile);
        priceConfig.load(priceFile);

        Map<Material, Price> prices = new LinkedHashMap<>();
        for (String materialName : priceConfig.getConfigurationSection("prices") == null
                ? List.<String>of() : priceConfig.getConfigurationSection("prices").getKeys(false)) {
            Material material = parseMaterial(materialName);
            String path = "prices." + materialName + ".";
            long buy = Money.toPaiseAllowZero(priceConfig.getString(path + "buy", "0"));
            long sell = Money.toPaiseAllowZero(priceConfig.getString(path + "sell", "0"));
            prices.put(material, new Price(buy, sell));
        }

        Map<String, List<Material>> categories = new LinkedHashMap<>();
        var categorySection = shop.getConfigurationSection("categories");
        if (categorySection == null || categorySection.getKeys(false).isEmpty()) {
            throw new IllegalArgumentException("shop.yml must define at least one category.");
        }
        for (String category : categorySection.getKeys(false)) {
            List<Material> materials = new ArrayList<>();
            for (String name : shop.getStringList("categories." + category)) {
                Material material = parseMaterial(name);
                if (!prices.containsKey(material)) {
                    throw new IllegalArgumentException("Missing price configuration for " + material.name() + ".");
                }
                if (!materials.contains(material)) {
                    materials.add(material);
                }
            }
            categories.put(category, List.copyOf(materials));
        }

        Set<Material> blacklist = shop.getStringList("sell-blacklist").stream()
                .map(ShopCatalog::parseMaterial).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ShopCatalog(categories, prices, blacklist);
    }

    private static Material parseMaterial(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || material.name().equals("AIR") || material.name().endsWith("_AIR")) {
            throw new IllegalArgumentException("Unknown or non-item shop material: " + name);
        }
        return material;
    }

    public Map<String, List<Material>> categories() {
        return categories;
    }

    public List<Material> category(String name) {
        return categories.getOrDefault(name, List.of());
    }

    public Price price(Material material) {
        return prices.get(material);
    }

    public Map<Material, Price> prices() {
        return prices;
    }

    public boolean isSellable(Material material) {
        Price price = prices.get(material);
        return price != null && price.sellPaise() > 0 && !sellBlacklist.contains(material);
    }

    public void validateRuntimeItems() {
        prices.keySet().forEach(material -> {
            if (material.isAir() || !material.isItem()) {
                throw new IllegalArgumentException("Shop material is not a valid item: " + material.name());
            }
        });
    }

    public List<Material> search(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return prices.keySet().stream()
                .filter(material -> material.name().toLowerCase(Locale.ROOT).contains(normalized))
                .sorted(java.util.Comparator.comparing(Material::name))
                .toList();
    }

    public record Price(long buyPaise, long sellPaise) {
    }
}
