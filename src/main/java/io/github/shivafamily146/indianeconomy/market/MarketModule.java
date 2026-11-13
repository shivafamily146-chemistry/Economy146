package io.github.shivafamily146.indianeconomy.market;

import io.github.shivafamily146.indianeconomy.IndianEconomyPlugin;
import io.github.shivafamily146.indianeconomy.economy.EconomyService;
import io.github.shivafamily146.indianeconomy.utils.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MarketModule {
    private static final int MAX_AMOUNT = 2304;
    private final IndianEconomyPlugin plugin;
    private final MarketplaceService service;
    private final ConcurrentMap<UUID, Boolean> busy = new ConcurrentHashMap<>();

    public MarketModule(IndianEconomyPlugin plugin, MarketplaceService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public boolean command(CommandSender sender, String root, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        try {
            if (root.equals("auction") || root.equals("market")) {
                MarketplaceService.Kind kind = root.equals("auction")
                        ? MarketplaceService.Kind.AUCTION : MarketplaceService.Kind.MARKET;
                if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                    showListings(player, kind);
                } else if (args[0].equalsIgnoreCase("sell") && args.length == 4) {
                    createListing(player, kind, args[1], args[2], args[3]);
                } else if (args[0].equalsIgnoreCase("buy") && args.length == 2) {
                    buyListing(player, kind, args[1]);
                } else {
                    plugin.sendText(player, "&e/" + root + " [list | sell <item> <amount> <total-price> | buy <id>]");
                }
            } else if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                showListings(player, MarketplaceService.Kind.ORDERS);
            } else if (args[0].equalsIgnoreCase("create") && args.length == 4) {
                createOrder(player, args[1], args[2], args[3]);
            } else if (args[0].equalsIgnoreCase("fulfill") && args.length == 2) {
                fulfillOrder(player, args[1]);
            } else if (args[0].equalsIgnoreCase("cancel") && args.length == 2) {
                cancelOrder(player, args[1]);
            } else {
                plugin.sendText(player, "&e/orders [list | create <item> <amount> <total-budget> | fulfill <id> | cancel <id>]");
            }
        } catch (IllegalArgumentException exception) {
            plugin.sendText(player, "&c" + exception.getMessage());
        }
        return true;
    }

    private void createListing(Player player, MarketplaceService.Kind kind, String materialName,
                               String amountText, String priceText) {
        Material material = parseMaterial(materialName);
        int amount = parseAmount(amountText);
        long price = Money.toPaise(priceText);
        if (material == null || amount < 1 || price > plugin.getTransactionLimit()) {
            throw new IllegalArgumentException("Invalid item, quantity, or price.");
        }
        if (!hasPlainStock(player.getInventory(), material, amount)) {
            plugin.sendText(player, "&cYou need that many plain, unmodified items to list.");
            return;
        }
        if (!acquire(player)) {
            return;
        }
        List<ItemStack> removed = remove(player.getInventory(), material, amount);
        World world = player.getWorld();
        var location = player.getLocation();
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                MarketplaceService.Listing listing = service.createListing(kind, player.getUniqueId(), player.getName(),
                        material.name(), amount, price);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (listing == null) {
                        restore(player, world, location, removed);
                    }
                    busy.remove(player.getUniqueId());
                    plugin.sendText(player, listing == null ? "&cFrozen accounts cannot create listings."
                            : "&aCreated listing #" + listing.id() + " for "
                            + amount + " " + material.name() + " at ₹" + Money.format(price) + ".");
                });
            } catch (SQLException | RuntimeException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    restore(player, world, location, removed);
                    busy.remove(player.getUniqueId());
                    plugin.sendText(player, "&cCould not create listing; your items were returned.");
                });
                plugin.reportDatabaseFailure("Could not create " + kind + " listing", exception instanceof SQLException sql ? sql : null);
            }
        });
    }

    private void buyListing(Player player, MarketplaceService.Kind kind, String idText) {
        long id = parseId(idText);
        if (!acquire(player)) {
            return;
        }
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                MarketplaceService.Listing listing = service.buyListing(kind, id, player.getUniqueId(), player.getName());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(player.getUniqueId());
                    if (listing == null) {
                        plugin.sendText(player, "&cListing unavailable, frozen, or not affordable.");
                        return;
                    }
                    plugin.getShopModule().deliverPending(player);
                    plugin.sendText(player, "&aPurchased listing #" + id + " for ₹" + Money.format(listing.pricePaise()) + ".");
                });
            } catch (SQLException exception) {
                busy.remove(player.getUniqueId());
                plugin.reportDatabaseFailure("Could not purchase listing #" + id, exception);
            }
        });
    }

    private void createOrder(Player player, String materialName, String amountText, String budgetText) {
        Material material = parseMaterial(materialName);
        int amount = parseAmount(amountText);
        long budget = Money.toPaise(budgetText);
        if (material == null || amount < 1 || budget > plugin.getTransactionLimit()) {
            throw new IllegalArgumentException("Invalid item, quantity, or order budget.");
        }
        if (!acquire(player)) {
            return;
        }
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                MarketplaceService.Order order = service.createOrder(player.getUniqueId(), player.getName(),
                        material.name(), amount, budget);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(player.getUniqueId());
                    plugin.sendText(player, order == null ? "&cOrder not placed (check balance or account status)."
                            : "&aCreated order #" + order.id() + " with ₹" + Money.format(budget) + " escrowed.");
                });
            } catch (SQLException exception) {
                busy.remove(player.getUniqueId());
                plugin.reportDatabaseFailure("Could not create player order", exception);
            }
        });
    }

    private void fulfillOrder(Player player, String idText) {
        long id = parseId(idText);
        if (!acquire(player)) {
            return;
        }
        plugin.getDatabaseExecutor().execute(() -> {
            MarketplaceService.Listing order;
            try {
                order = service.list(MarketplaceService.Kind.ORDERS, 1000).stream()
                        .filter(listing -> listing.id() == id).findFirst().orElse(null);
            } catch (SQLException exception) {
                busy.remove(player.getUniqueId());
                plugin.reportDatabaseFailure("Could not look up order #" + id, exception);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (order == null || order.owner().equals(player.getUniqueId())) {
                    busy.remove(player.getUniqueId());
                    plugin.sendText(player, "&cOrder unavailable or owned by you.");
                    return;
                }
                Material material = parseMaterial(order.material());
                if (material == null || !hasPlainStock(player.getInventory(), material, order.amount())) {
                    busy.remove(player.getUniqueId());
                    plugin.sendText(player, "&cYou need that many plain, unmodified items to fulfill the order.");
                    return;
                }
                List<ItemStack> removed = remove(player.getInventory(), material, order.amount());
                World world = player.getWorld();
                var location = player.getLocation();
                plugin.getDatabaseExecutor().execute(() -> {
                    try {
                        MarketplaceService.Order filled = service.fillOrder(id, player.getUniqueId(), player.getName());
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (filled == null) {
                                restore(player, world, location, removed);
                                plugin.sendText(player, "&cCould not fulfill order; your items were returned.");
                            } else {
                                Player buyer = Bukkit.getPlayer(filled.buyer());
                                if (buyer != null) {
                                    plugin.getShopModule().deliverPending(buyer);
                                }
                                plugin.sendText(player, "&aFulfilled order #" + id + " for ₹"
                                        + Money.format(filled.budgetPaise()) + ".");
                            }
                            busy.remove(player.getUniqueId());
                        });
                    } catch (SQLException exception) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            restore(player, world, location, removed);
                            busy.remove(player.getUniqueId());
                            plugin.sendText(player, "&cCould not fulfill order; your items were returned.");
                        });
                        plugin.reportDatabaseFailure("Could not fulfill order #" + id, exception);
                    }
                });
            });
        });
    }

    private void cancelOrder(Player player, String idText) {
        long id = parseId(idText);
        if (!acquire(player)) {
            return;
        }
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                boolean cancelled = plugin.getMarketplace().cancelOrder(id, player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(player.getUniqueId());
                    plugin.sendText(player, cancelled ? "&aOrder cancelled and escrow refunded."
                            : "&cOrder not found or not owned by you.");
                });
            } catch (SQLException exception) {
                busy.remove(player.getUniqueId());
                plugin.reportDatabaseFailure("Could not cancel order #" + id, exception);
            }
        });
    }

    public void showListings(Player player, MarketplaceService.Kind kind) {
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                List<MarketplaceService.Listing> listings = service.list(kind, 10);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.sendText(player, "&6" + kind.name() + " listings");
                    if (listings.isEmpty()) {
                        plugin.sendText(player, "&7No listings.");
                    }
                    for (MarketplaceService.Listing listing : listings) {
                        plugin.sendText(player, "&e#" + listing.id() + " &f" + listing.amount() + "x "
                                + listing.material() + " &7— &a₹" + Money.format(listing.pricePaise()));
                    }
                });
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Could not list " + kind, exception);
            }
        });
    }

    private boolean acquire(Player player) {
        if (busy.putIfAbsent(player.getUniqueId(), true) != null) {
            plugin.sendText(player, "&cWait for your previous marketplace operation.");
            return false;
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
            return amount > 0 && amount <= MAX_AMOUNT ? amount : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private long parseId(String input) {
        try {
            long id = Long.parseLong(input);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // Report the same validation message for malformed and non-positive identifiers.
        }
        throw new IllegalArgumentException("Listing ID must be a positive whole number.");
    }

    private boolean hasPlainStock(PlayerInventory inventory, Material material, int amount) {
        int found = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType() == material) {
                if (item.hasItemMeta() && !item.getItemMeta().equals(new ItemStack(material).getItemMeta())) {
                    continue;
                }
                found = Math.addExact(found, item.getAmount());
            }
        }
        return found >= amount;
    }

    private List<ItemStack> remove(PlayerInventory inventory, Material material, int amount) {
        List<ItemStack> removed = new ArrayList<>();
        int left = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != material
                    || (item.hasItemMeta() && !item.getItemMeta().equals(new ItemStack(material).getItemMeta()))) {
                continue;
            }
            int take = Math.min(left, item.getAmount());
            ItemStack clone = item.clone();
            clone.setAmount(take);
            removed.add(clone);
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() == 0) {
                contents[slot] = null;
            }
            left -= take;
        }
        if (left != 0) {
            throw new IllegalStateException("Inventory changed during listing.");
        }
        inventory.setStorageContents(contents);
        return removed;
    }

    private void restore(Player player, World world, org.bukkit.Location location, List<ItemStack> items) {
        for (ItemStack item : items) {
            Map<Integer, ItemStack> leftover = player.isOnline()
                    ? player.getInventory().addItem(item) : Map.of(0, item);
            leftover.values().forEach(stack -> world.dropItemNaturally(location, stack));
        }
    }
}
