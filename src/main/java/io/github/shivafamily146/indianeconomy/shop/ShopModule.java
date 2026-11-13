package io.github.shivafamily146.indianeconomy.shop;

import io.github.shivafamily146.indianeconomy.IndianEconomyPlugin;
import io.github.shivafamily146.indianeconomy.utils.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShopModule implements Listener {
    private static final int PAGE_SIZE = 28;
    private static final int MAX_QUANTITY = 2304;
    private static final int[] ITEM_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final IndianEconomyPlugin plugin;
    private final Set<UUID> pendingTransactions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingSearch = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingDeliveryPlayers = ConcurrentHashMap.newKeySet();
    private final NamespacedKey deliveryKey;
    private volatile ShopCatalog catalog;

    public ShopModule(IndianEconomyPlugin plugin, ShopCatalog catalog) {
        this.plugin = plugin;
        this.deliveryKey = new NamespacedKey(plugin, "pending_delivery");
        setCatalog(catalog);
    }

    public void setCatalog(ShopCatalog catalog) {
        catalog.validateRuntimeItems();
        this.catalog = catalog;
    }

    public boolean command(CommandSender sender, String command, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        switch (command) {
            case "shop" -> {
                if (args.length == 2 && args[0].equalsIgnoreCase("search")) {
                    openItems(player, null, args[1], 0);
                } else if (args.length == 0) {
                    openCategories(player);
                } else {
                    plugin.sendText(player, "&eUsage: /shop [search <query>]");
                }
            }
            case "buy" -> {
                if (args.length != 2) {
                    plugin.sendText(player, "&eUsage: /buy <item> <amount>");
                } else {
                    Material material = parseMaterial(args[0]);
                    int amount = parseAmount(args[1]);
                    if (material == null || amount < 1) {
                        plugin.sendText(player, "&cInvalid item or amount.");
                    } else {
                        purchase(player, material, amount);
                    }
                }
            }
            case "sell" -> {
                if (args.length != 2) {
                    plugin.sendText(player, "&eUsage: /sell <item> <amount>");
                } else {
                    Material material = parseMaterial(args[0]);
                    int amount = parseAmount(args[1]);
                    if (material == null || amount < 1) {
                        plugin.sendText(player, "&cInvalid item or amount.");
                    } else {
                        sell(player, material, amount);
                    }
                }
            }
            case "sellall" -> {
                if (args.length != 0) {
                    plugin.sendText(player, "&eUsage: /sellall");
                } else {
                    sellAll(player);
                }
            }
            default -> plugin.sendText(player, "&cUnknown shop action.");
        }
        return true;
    }

    private Material parseMaterial(String name) {
        Material material = Material.matchMaterial(name);
        return material == null || material.isAir() || !material.isItem() ? null : material;
    }

    private int parseAmount(String input) {
        try {
            int amount = Integer.parseInt(input);
            return amount > 0 && amount <= MAX_QUANTITY ? amount : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    public void openCategories(Player player) {
        ShopHolder holder = new ShopHolder(player.getUniqueId(), Screen.CATEGORIES, null, null, 0, null, 1, 54,
                "IndianEconomy Shop");
        Inventory inventory = holder.getInventory();
        int[] slots = ITEM_SLOTS;
        int index = 0;
        for (Map.Entry<String, List<Material>> entry : catalog.categories().entrySet()) {
            if (index >= slots.length) {
                break;
            }
            Material icon = entry.getValue().isEmpty() ? Material.CHEST : entry.getValue().getFirst();
            inventory.setItem(slots[index++], button(icon, "&6" + entry.getKey(),
                    List.of("&7" + entry.getValue().size() + " configured items", "&eClick to browse")));
        }
        inventory.setItem(49, button(Material.BARRIER, "&cClose", List.of()));
        player.openInventory(inventory);
    }

    private void openItems(Player player, String category, String search, int page) {
        List<Material> items = search == null ? catalog.category(category) : catalog.search(search);
        if (items.isEmpty()) {
            plugin.sendText(player, "&cNo shop items match that selection.");
            if (category == null && search != null) {
                pendingSearch.add(player.getUniqueId());
                plugin.sendText(player, "&7Type a search word in chat, or run /shop to return to categories.");
            }
        }
        int lastPage = Math.max(0, (items.size() - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, lastPage));
        ShopHolder holder = new ShopHolder(player.getUniqueId(), Screen.ITEMS, category, search, safePage, null, 1, 54,
                search == null ? "Shop: " + category : "Shop search: " + search);
        Inventory inventory = holder.getInventory();
        int start = safePage * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < items.size(); i++) {
            Material material = items.get(start + i);
            ShopCatalog.Price price = catalog.price(material);
            List<String> lore = new ArrayList<>();
            lore.add("&7Buy: " + (price.buyPaise() > 0 ? "₹" + Money.format(price.buyPaise()) : "Unavailable"));
            lore.add("&7Sell: " + (catalog.isSellable(material) ? "₹" + Money.format(price.sellPaise()) : "Unavailable"));
            lore.add("&eClick to select quantity");
            inventory.setItem(ITEM_SLOTS[i], button(material, "&f" + readable(material), lore));
        }
        if (safePage > 0) {
            inventory.setItem(45, button(Material.ARROW, "&ePrevious page", List.of()));
        }
        inventory.setItem(47, button(Material.COMPASS, "&bSearch", List.of("&7Click, then type in chat")));
        inventory.setItem(49, button(Material.BARRIER, "&cBack", List.of()));
        if (safePage < lastPage) {
            inventory.setItem(53, button(Material.ARROW, "&eNext page", List.of()));
        }
        player.openInventory(inventory);
    }

    private void openConfirmation(Player player, Material material, int amount) {
        ShopCatalog.Price price = catalog.price(material);
        if (price == null || price.buyPaise() <= 0) {
            plugin.sendText(player, "&cThis item is not available for purchase.");
            return;
        }
        ShopHolder holder = new ShopHolder(player.getUniqueId(), Screen.CONFIRM, null, null, 0, material, amount, 27,
                "Confirm shop purchase");
        Inventory inventory = holder.getInventory();
        String total;
        try {
            total = Money.format(Math.multiplyExact(price.buyPaise(), amount));
        } catch (ArithmeticException exception) {
            total = "Over limit";
        }
        ItemStack preview = button(material, "&f" + readable(material),
                List.of("&7Quantity: " + amount, "&7Total: ₹" + total));
        inventory.setItem(13, preview);
        inventory.setItem(10, button(Material.REDSTONE, "&c-1", List.of()));
        inventory.setItem(11, button(Material.REDSTONE_BLOCK, "&c-16", List.of()));
        inventory.setItem(12, button(Material.REDSTONE_TORCH, "&c-64", List.of()));
        inventory.setItem(14, button(Material.EMERALD, "&a+1", List.of()));
        inventory.setItem(15, button(Material.EMERALD_BLOCK, "&a+16", List.of()));
        inventory.setItem(16, button(Material.EMERALD_ORE, "&a+64", List.of()));
        inventory.setItem(22, button(Material.LIME_CONCRETE, "&aConfirm purchase", List.of("&7Click once to buy")));
        inventory.setItem(26, button(Material.BARRIER, "&cCancel", List.of()));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && pendingDeliveryPlayers.contains(player.getUniqueId())
                && !(event.getView().getTopInventory().getHolder() instanceof ShopHolder)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> deliverPending(player), 1L);
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (holder.screen == Screen.CATEGORIES) {
            int itemIndex = indexOf(ITEM_SLOTS, slot);
            if (itemIndex < 0 || itemIndex >= catalog.categories().size()) {
                if (slot == 49) {
                    player.closeInventory();
                }
                return;
            }
            String category = new ArrayList<>(catalog.categories().keySet()).get(itemIndex);
            openItems(player, category, null, 0);
        } else if (holder.screen == Screen.ITEMS) {
            if (slot == 49) {
                openCategories(player);
            } else if (slot == 47) {
                pendingSearch.add(player.getUniqueId());
                player.closeInventory();
                plugin.sendText(player, "&eType your shop search in chat.");
            } else if (slot == 45) {
                openItems(player, holder.category, holder.search, holder.page - 1);
            } else if (slot == 53) {
                openItems(player, holder.category, holder.search, holder.page + 1);
            } else {
                int itemIndex = indexOf(ITEM_SLOTS, slot);
                List<Material> items = holder.search == null ? catalog.category(holder.category) : catalog.search(holder.search);
                int itemAt = holder.page * PAGE_SIZE + itemIndex;
                if (itemIndex >= 0 && itemAt < items.size()) {
                    openConfirmation(player, items.get(itemAt), 1);
                }
            }
        } else if (holder.screen == Screen.CONFIRM) {
            if (slot == 26) {
                openCategories(player);
                return;
            }
            int delta = switch (slot) {
                case 10 -> -1;
                case 11 -> -16;
                case 12 -> -64;
                case 14 -> 1;
                case 15 -> 16;
                case 16 -> 64;
                default -> 0;
            };
            if (delta != 0) {
                openConfirmation(player, holder.material,
                        Math.max(1, Math.min(MAX_QUANTITY, holder.quantity + delta)));
            } else if (slot == 22) {
                player.closeInventory();
                purchase(player, holder.material, holder.quantity);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopHolder) {
            event.setCancelled(true);
            return;
        }
        if (event.getNewItems().values().stream().anyMatch(this::isPendingDelivery)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPendingDeliveryClick(InventoryClickEvent event) {
        if (isPendingDelivery(event.getCurrentItem()) || isPendingDelivery(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPendingDeliveryDrop(PlayerDropItemEvent event) {
        if (isPendingDelivery(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPendingDeliveryUse(PlayerInteractEvent event) {
        if (isPendingDelivery(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPendingDeliveryCraft(CraftItemEvent event) {
        if (event.getInventory().getMatrix() != null
                && java.util.Arrays.stream(event.getInventory().getMatrix()).anyMatch(this::isPendingDelivery)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPendingDeliveryDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isPendingDelivery);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        deliverPending(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player
                && pendingDeliveryPlayers.contains(player.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> deliverPending(player), 1L);
        }
    }

    @EventHandler
    public void onSearchChat(AsyncPlayerChatEvent event) {
        if (!pendingSearch.remove(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String query = event.getMessage().trim();
        if (query.isBlank() || query.length() > 32) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.sendText(event.getPlayer(), "&cSearch must be 1-32 characters."));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> openItems(event.getPlayer(), null, query, 0));
    }

    private void purchase(Player player, Material material, int amount) {
        ShopCatalog.Price price = catalog.price(material);
        if (price == null || price.buyPaise() <= 0 || amount < 1 || amount > MAX_QUANTITY) {
            plugin.sendText(player, "&cThat item cannot be purchased.");
            return;
        }
        long total;
        try {
            total = Math.multiplyExact(price.buyPaise(), amount);
            if (total > plugin.getTransactionLimit()) {
                throw new ArithmeticException("transaction limit");
            }
        } catch (ArithmeticException exception) {
            plugin.sendText(player, "&cPurchase exceeds the allowed transaction limit.");
            return;
        }
        if (!pendingTransactions.add(player.getUniqueId())) {
            plugin.sendText(player, "&cWait for your previous shop transaction to finish.");
            return;
        }
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                String deliveryId = plugin.getEconomy().reserveShopPurchase(playerId, playerName,
                        material.name(), amount, total);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (deliveryId == null) {
                        pendingTransactions.remove(playerId);
                        plugin.sendText(player, "&cPurchase could not be completed (check balance or account status).");
                        return;
                    }
                    pendingDeliveryPlayers.add(playerId);
                    if (!player.isOnline()) {
                        pendingTransactions.remove(playerId);
                        return;
                    }
                    deliverOne(player, new io.github.shivafamily146.indianeconomy.economy.EconomyService.PendingDelivery(
                            deliveryId, material.name(), amount));
                });
            } catch (SQLException exception) {
                pendingTransactions.remove(playerId);
                plugin.reportDatabaseFailure("Shop purchase failed", exception);
            }
        });
    }

    public void deliverPending(Player player) {
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                List<io.github.shivafamily146.indianeconomy.economy.EconomyService.PendingDelivery> deliveries =
                        plugin.getEconomy().pendingDeliveries(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!deliveries.isEmpty()) {
                        pendingDeliveryPlayers.add(player.getUniqueId());
                    }
                    deliveries.forEach(delivery -> deliverOne(player, delivery));
                    if (deliveries.isEmpty()) {
                        pendingDeliveryPlayers.remove(player.getUniqueId());
                    }
                });
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Could not load pending shop deliveries", exception);
            }
        });
    }

    private void deliverOne(Player player,
                            io.github.shivafamily146.indianeconomy.economy.EconomyService.PendingDelivery delivery) {
        if (!player.isOnline()) {
            return;
        }
        if (!hasDeliveryTag(player, delivery.id())) {
            Material material = parseMaterial(delivery.material());
            if (material == null || !canHold(player.getInventory(), material, delivery.amount())) {
                plugin.sendText(player, "&eA paid shop purchase is waiting for inventory space.");
                return;
            }
            List<ItemStack> items = new ArrayList<>();
            int amountLeft = delivery.amount();
            while (amountLeft > 0) {
                int stackAmount = Math.min(amountLeft, material.getMaxStackSize());
                ItemStack item = new ItemStack(material, stackAmount);
                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(deliveryKey, PersistentDataType.STRING, delivery.id());
                item.setItemMeta(meta);
                items.add(item);
                amountLeft -= stackAmount;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(items.toArray(ItemStack[]::new));
            if (!leftovers.isEmpty()) {
                removeTaggedItems(player, delivery.id());
                plugin.sendText(player, "&eA paid shop purchase is waiting for inventory space.");
                return;
            }
        }
        UUID playerId = player.getUniqueId();
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                plugin.getEconomy().completeDelivery(delivery.id(), playerId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        removeDeliveryTag(player, delivery.id());
                        plugin.sendText(player, "&aShop purchase delivered: " + delivery.amount() + " "
                                + delivery.material().toLowerCase(java.util.Locale.ROOT) + ".");
                    }
                    pendingTransactions.remove(playerId);
                    deliverPending(player);
                });
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Could not finalize shop delivery " + delivery.id(), exception);
            }
        });
    }

    private boolean canHold(PlayerInventory inventory, Material material, int amount) {
        long capacity = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) {
                capacity += material.getMaxStackSize();
            } else if (item.getType() == material && !item.hasItemMeta()) {
                capacity += Math.max(0, material.getMaxStackSize() - item.getAmount());
            }
        }
        return capacity >= amount;
    }

    private boolean hasDeliveryTag(Player player, String id) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && id.equals(item.getItemMeta().getPersistentDataContainer()
                    .get(deliveryKey, PersistentDataType.STRING))) {
                return true;
            }
        }
        return false;
    }

    private boolean isPendingDelivery(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(deliveryKey, PersistentDataType.STRING);
    }

    private void removeDeliveryTag(Player player, String id) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !id.equals(item.getItemMeta().getPersistentDataContainer()
                    .get(deliveryKey, PersistentDataType.STRING))) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().remove(deliveryKey);
            item.setItemMeta(meta);
        }
    }

    private void removeTaggedItems(Player player, String id) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null && id.equals(item.getItemMeta().getPersistentDataContainer()
                    .get(deliveryKey, PersistentDataType.STRING))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void sell(Player player, Material material, int amount) {
        if (!catalog.isSellable(material)) {
            plugin.sendText(player, "&cThis item cannot be sold.");
            return;
        }
        if (count(player.getInventory(), material) < amount) {
            plugin.sendText(player, "&cYou do not have that many items.");
            return;
        }
        long total;
        try {
            total = Math.multiplyExact(catalog.price(material).sellPaise(), amount);
        } catch (ArithmeticException exception) {
            plugin.sendText(player, "&cSale exceeds the transaction limit.");
            return;
        }
        if (total > plugin.getTransactionLimit()) {
            plugin.sendText(player, "&cSale exceeds the transaction limit.");
            return;
        }
        if (!pendingTransactions.add(player.getUniqueId())) {
            plugin.sendText(player, "&cWait for your previous shop transaction to finish.");
            return;
        }
        List<ItemStack> removed = remove(player.getInventory(), material, amount);
        payForSale(player, total, removed);
    }

    private void sellAll(Player player) {
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() != Material.AIR && catalog.isSellable(item.getType())) {
                counts.merge(item.getType(), item.getAmount(), Math::addExact);
            }
        }
        long total = 0;
        try {
            for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
                total = Math.addExact(total, Math.multiplyExact(catalog.price(entry.getKey()).sellPaise(), entry.getValue()));
            }
        } catch (ArithmeticException exception) {
            plugin.sendText(player, "&cSale exceeds the transaction limit.");
            return;
        }
        if (counts.isEmpty() || total == 0) {
            plugin.sendText(player, "&eNo eligible items to sell.");
            return;
        }
        if (total > plugin.getTransactionLimit()) {
            plugin.sendText(player, "&cSale exceeds the transaction limit.");
            return;
        }
        if (!pendingTransactions.add(player.getUniqueId())) {
            plugin.sendText(player, "&cWait for your previous shop transaction to finish.");
            return;
        }
        List<ItemStack> removed = new ArrayList<>();
        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            removed.addAll(remove(player.getInventory(), entry.getKey(), entry.getValue()));
        }
        payForSale(player, total, removed);
    }

    private void payForSale(Player player, long total, List<ItemStack> removed) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        World world = player.getWorld();
        var location = player.getLocation();
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                boolean credited = !plugin.getEconomy().isFrozen(playerId)
                        && plugin.getEconomy().adjust(playerId, playerName, total, "SHOP_SELL");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (credited) {
                        pendingTransactions.remove(playerId);
                        plugin.sendText(player, "&aItems sold for ₹" + Money.format(total) + ".");
                    } else {
                        restore(player, world, location, removed);
                        pendingTransactions.remove(playerId);
                        plugin.sendText(player, "&cSale could not be credited; your items were returned.");
                    }
                });
            } catch (SQLException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    restore(player, world, location, removed);
                    pendingTransactions.remove(playerId);
                    plugin.sendText(player, "&cSale failed; your items were returned.");
                });
                plugin.reportDatabaseFailure("Shop sale failed", exception);
            }
        });
    }

    private List<ItemStack> remove(PlayerInventory inventory, Material material, int amount) {
        List<ItemStack> removed = new ArrayList<>();
        int left = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(left, stack.getAmount());
            ItemStack removedStack = stack.clone();
            removedStack.setAmount(take);
            removed.add(removedStack);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() == 0) {
                contents[slot] = null;
            }
            left -= take;
        }
        inventory.setStorageContents(contents);
        if (left != 0) {
            throw new IllegalStateException("Inventory changed during item removal.");
        }
        return removed;
    }

    private int count(PlayerInventory inventory, Material material) {
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                count = Math.addExact(count, stack.getAmount());
            }
        }
        return count;
    }

    private void restore(Player player, World world, org.bukkit.Location location, List<ItemStack> removed) {
        if (player.isOnline()) {
            for (ItemStack item : removed) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                leftover.values().forEach(stack -> world.dropItemNaturally(location, stack));
            }
        } else {
            removed.forEach(stack -> world.dropItemNaturally(location, stack));
        }
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(plugin.colorText(name));
        meta.setLore(lore.stream().map(plugin::colorText).toList());
        item.setItemMeta(meta);
        return item;
    }

    private String readable(Material material) {
        return material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private enum Screen {
        CATEGORIES, ITEMS, CONFIRM
    }

    private static final class ShopHolder implements InventoryHolder {
        private final UUID owner;
        private final Screen screen;
        private final String category;
        private final String search;
        private final int page;
        private final Material material;
        private final int quantity;
        private final Inventory inventory;

        private ShopHolder(UUID owner, Screen screen, String category, String search, int page, Material material,
                          int quantity, int size, String title) {
            this.owner = owner;
            this.screen = screen;
            this.category = category;
            this.search = search;
            this.page = page;
            this.material = material;
            this.quantity = quantity;
            this.inventory = Bukkit.createInventory(this, size, title);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
