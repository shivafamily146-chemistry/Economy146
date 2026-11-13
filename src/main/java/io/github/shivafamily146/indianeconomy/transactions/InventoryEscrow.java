package io.github.shivafamily146.indianeconomy.transactions;

import io.github.shivafamily146.indianeconomy.IndianEconomyPlugin;
import io.github.shivafamily146.indianeconomy.market.MarketplaceService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class InventoryEscrow implements Listener {
    private final IndianEconomyPlugin plugin;
    private final MarketplaceService marketplace;
    private final NamespacedKey operationKey;
    private final Set<UUID> busyPlayers = ConcurrentHashMap.newKeySet();

    public InventoryEscrow(IndianEconomyPlugin plugin, MarketplaceService marketplace) {
        this.plugin = plugin;
        this.marketplace = marketplace;
        this.operationKey = new NamespacedKey(plugin, "item_escrow");
    }

    public boolean begin(Player player, MarketplaceService.Operation operation, Material material,
                         int amount, long pricePaise,
                         Consumer<MarketplaceService.InventoryOperation> completion) {
        return begin(player, operation, material, amount, pricePaise, null, completion);
    }

    public boolean begin(Player player, MarketplaceService.Operation operation, Material material,
                         int amount, long pricePaise, Long relatedId,
                         Consumer<MarketplaceService.InventoryOperation> completion) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Inventory escrow must start on the server thread.");
        }
        if (!player.isOnline()) {
            return false;
        }
        if (!busyPlayers.add(player.getUniqueId())) {
            plugin.sendText(player, "&cWait for your previous inventory transaction to finish.");
            return false;
        }
        String id = UUID.randomUUID().toString();
        if (!tagItems(player.getInventory(), material, amount, id)) {
            busyPlayers.remove(player.getUniqueId());
            plugin.sendText(player, "&cYou do not have enough plain, unmodified items.");
            return false;
        }
        player.saveData();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                String preparedId = marketplace.prepareInventoryOperation(
                        id, operation, playerId, playerName, material.name(), amount, pricePaise, relatedId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (preparedId == null) {
                        clearTags(player, id);
                        player.saveData();
                        busyPlayers.remove(playerId);
                        plugin.sendText(player, "&cInventory transaction rejected (account may be frozen).");
                        completion.accept(null);
                        return;
                    }
                    if (!player.isOnline()) {
                        busyPlayers.remove(playerId);
                        return;
                    }
                    removeTaggedItems(player, id, amount);
                    player.saveData();
                    completePrepared(player, preparedId, completion);
                });
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Could not prepare inventory escrow " + id, exception);
                Bukkit.getScheduler().runTask(plugin, () -> recover(player, completion));
            }
        });
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        recover(event.getPlayer(), operation -> {
            if (operation == null) {
                return;
            }
            if (operation.operation() == MarketplaceService.Operation.SELL) {
                plugin.getShopModule().deliverPending(event.getPlayer());
            } else if (operation.relatedId() != null) {
                Bukkit.getOnlinePlayers().forEach(plugin.getShopModule()::deliverPending);
            } else if (operation.resultId() != null && operation.resultId() > 0) {
                plugin.sendText(event.getPlayer(), "&aRecovered marketplace listing #" + operation.resultId() + ".");
            }
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (hasOperationTag(event.getCurrentItem()) || hasOperationTag(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getNewItems().values().stream().anyMatch(this::hasOperationTag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (hasOperationTag(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (hasOperationTag(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (event.getInventory().getMatrix() != null
                && java.util.Arrays.stream(event.getInventory().getMatrix()).anyMatch(this::hasOperationTag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        PlayerInventory inventory = event.getEntity().getInventory();
        boolean hasProtectedItem = java.util.Arrays.stream(inventory.getContents()).anyMatch(this::isProtectedItem)
                || event.getDrops().stream().anyMatch(this::isProtectedItem);
        if (!hasProtectedItem || event.getKeepInventory()) {
            return;
        }
        if (event.getDrops().isEmpty()) {
            event.setKeepInventory(true);
            return;
        }
        List<ItemStack> ordinaryDrops = event.getDrops().stream().filter(item -> !isProtectedItem(item))
                .map(ItemStack::clone).toList();
        event.setKeepInventory(true);
        event.getDrops().clear();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!isProtectedItem(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
        ordinaryDrops.forEach(item ->
                event.getEntity().getWorld().dropItemNaturally(event.getEntity().getLocation(), item));
    }

    private void recover(Player player, Consumer<MarketplaceService.InventoryOperation> completion) {
        UUID playerId = player.getUniqueId();
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                List<MarketplaceService.InventoryOperation> pending =
                        marketplace.pendingInventoryOperations(playerId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    Set<String> validIds = new HashSet<>();
                    pending.forEach(operation -> validIds.add(operation.id()));
                    clearOrphanTags(player, validIds);
                    player.saveData();
                    if (pending.isEmpty()) {
                        completion.accept(null);
                        return;
                    }
                    recoverNext(player, pending, 0, completion);
                });
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Could not recover inventory escrow for " + playerId, exception);
            }
        });
    }

    private void recoverNext(Player player, List<MarketplaceService.InventoryOperation> pending, int index,
                             Consumer<MarketplaceService.InventoryOperation> completion) {
        if (index >= pending.size() || !player.isOnline()) {
            return;
        }
        MarketplaceService.InventoryOperation operation = pending.get(index);
        removeTaggedItems(player, operation.id(), operation.amount());
        player.saveData();
        completePrepared(player, operation.id(), completed -> {
            completion.accept(completed);
            recoverNext(player, pending, index + 1, completion);
        });
    }

    private void completePrepared(Player player, String id,
                                  Consumer<MarketplaceService.InventoryOperation> completion) {
        UUID playerId = player.getUniqueId();
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                MarketplaceService.InventoryOperation operation = marketplace.completeInventoryOperation(id);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busyPlayers.remove(playerId);
                    if (operation == null) {
                        plugin.reportDatabaseFailure("Inventory escrow disappeared before completion " + id, null);
                        return;
                    }
                    if (operation.resultId() != null && operation.resultId() == -1L) {
                        plugin.getShopModule().deliverPending(player);
                        plugin.sendText(player, "&eYour sale was refunded with items because the balance could not be credited.");
                    }
                    completion.accept(operation);
                });
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Could not complete inventory escrow " + id, exception);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busyPlayers.remove(playerId);
                    plugin.sendText(player, "&cInventory escrow is still pending. Rejoin after the database recovers.");
                    completion.accept(null);
                });
            }
        });
    }

    private boolean tagItems(PlayerInventory inventory, Material material, int amount, String id) {
        if (countPlain(inventory, material) < amount) {
            return false;
        }
        ItemStack[] contents = inventory.getStorageContents();
        int freeSlot = -1;
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] == null || contents[slot].getType() == Material.AIR) {
                freeSlot = slot;
                break;
            }
        }
        int remaining = amount;
        boolean needsSplit = false;
        for (ItemStack item : contents) {
            if (remaining <= 0) {
                break;
            }
            if (item != null && item.getType() == material && !item.hasItemMeta()
                    && remaining < item.getAmount()) {
                needsSplit = true;
                break;
            }
            if (item != null && item.getType() == material && !item.hasItemMeta()) {
                remaining -= item.getAmount();
            }
        }
        if (needsSplit && freeSlot < 0) {
            return false;
        }
        remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (remaining == 0) {
                break;
            }
            if (item == null || item.getType() != material || item.hasItemMeta()) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            ItemStack tagged = item.clone();
            tagged.setAmount(take);
            ItemMeta meta = tagged.getItemMeta();
            meta.getPersistentDataContainer().set(operationKey, PersistentDataType.STRING, id);
            tagged.setItemMeta(meta);
            if (take == item.getAmount()) {
                contents[slot] = tagged;
            } else {
                item.setAmount(item.getAmount() - take);
                contents[freeSlot] = tagged;
                freeSlot = -1;
            }
            remaining -= take;
        }
        inventory.setStorageContents(contents);
        return remaining == 0;
    }

    private int countPlain(PlayerInventory inventory, Material material) {
        int count = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType() == material && !item.hasItemMeta()) {
                count = Math.addExact(count, item.getAmount());
            }
        }
        return count;
    }

    private void removeTaggedItems(Player player, String id, int expectedAmount) {
        PlayerInventory inventory = player.getInventory();
        int remaining = expectedAmount;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!taggedWith(item, id) || remaining <= 0) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            if (take == item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - take);
                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().remove(operationKey);
                item.setItemMeta(meta);
            }
            remaining -= take;
        }
    }

    private void clearTags(Player player, String id) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!taggedWith(item, id)) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().remove(operationKey);
            item.setItemMeta(meta);
        }
    }

    private void clearOrphanTags(Player player, Set<String> validIds) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!hasOperationTag(item)) {
                continue;
            }
            String id = item.getItemMeta().getPersistentDataContainer()
                    .get(operationKey, PersistentDataType.STRING);
            if (validIds.contains(id)) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().remove(operationKey);
            item.setItemMeta(meta);
        }
    }

    private boolean taggedWith(ItemStack item, String id) {
        return item != null && item.hasItemMeta()
                && id.equals(item.getItemMeta().getPersistentDataContainer()
                .get(operationKey, PersistentDataType.STRING));
    }

    private boolean isProtectedItem(ItemStack item) {
        return hasOperationTag(item) || plugin.getShopModule().isPendingDeliveryItem(item);
    }

    private boolean hasOperationTag(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(operationKey, PersistentDataType.STRING);
    }
}
