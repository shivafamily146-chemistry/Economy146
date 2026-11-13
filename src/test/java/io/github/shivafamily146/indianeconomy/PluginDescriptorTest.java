package io.github.shivafamily146.indianeconomy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorTest {
    @Test
    @SuppressWarnings("unchecked")
    void declaresOnlyCommandsWithCurrentExecutorsAndRestrictsAdminByDefault() {
        InputStream resource = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        Map<String, Object> descriptor = new Yaml().load(resource);
        Map<String, Object> commands = (Map<String, Object>) descriptor.get("commands");
        assertTrue(commands.keySet().containsAll(java.util.Set.of(
                "help", "bal", "pay", "baltop", "daily", "shop", "buy", "sell", "sellall",
                "auction", "market", "orders", "ie")));
        assertEquals(java.util.Set.of("help", "bal", "pay", "baltop", "daily", "shop", "buy", "sell",
                "sellall", "auction", "market", "orders", "ie"), commands.keySet());
        Map<String, Object> permissions = (Map<String, Object>) descriptor.get("permissions");
        Map<String, Object> admin = (Map<String, Object>) permissions.get("indianeconomy.admin");
        assertEquals("op", admin.get("default"));
        Map<String, Object> use = (Map<String, Object>) permissions.get("indianeconomy.use");
        assertEquals(true, use.get("default"));
        for (String permission : java.util.Set.of("indianeconomy.shop", "indianeconomy.sell",
                "indianeconomy.pay")) {
            assertEquals(true, ((Map<String, Object>) permissions.get(permission)).get("default"));
        }
        for (String permission : java.util.Set.of("indianeconomy.reload", "indianeconomy.give",
                "indianeconomy.take", "indianeconomy.set", "indianeconomy.shop.manage",
                "indianeconomy.auction.manage", "indianeconomy.market.manage",
                "indianeconomy.orders.manage")) {
            assertEquals("op", ((Map<String, Object>) permissions.get(permission)).get("default"));
        }
    }
}
