package io.github.shivafamily146.indianeconomy.market;

import io.github.shivafamily146.indianeconomy.IndianEconomyPlugin;
import io.github.shivafamily146.indianeconomy.economy.EconomyService;
import io.github.shivafamily146.indianeconomy.utils.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;
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
                } else if (root.equals("auction") && args[0].equalsIgnoreCase("bid") && args.length == 3) {
                    bidAuction(player, args[1], args[2]);
                } else if (root.equals("market") && args[0].equalsIgnoreCase("buy") && args.length == 2) {
                    buyListing(player, kind, args[1]);
                } else {
                    plugin.sendText(player, root.equals("auction")
                            ? "&e/auction [list | sell <item> <amount> <starting-bid> | bid <id> <amount>]"
                            : "&e/market [list | sell <item> <amount> <total-price> | buy <id>]");
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

    private void bidAuction(Player player, String idText, String bidText) {
        long id = parseId(idText);
        long amount = Money.toPaise(bidText);
        if (amount > plugin.getTransactionLimit()) {
            throw new IllegalArgumentException("Bid exceeds the configured transaction limit.");
        }
        if (!acquire(player)) {
            return;
        }
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                MarketplaceService.BidResult result = service.bidAuction(id, player.getUniqueId(),
                        player.getName(), amount);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(player.getUniqueId());
                    plugin.sendText(player, result.accepted()
                            ? "&a" + result.message() + " Current bid: ₹" + Money.format(result.currentBidPaise()) + "."
                            : "&c" + result.message());
                });
            } catch (SQLException exception) {
                busy.remove(player.getUniqueId());
                plugin.reportDatabaseFailure("Could not place bid on auction #" + id, exception);
            }
        });
    }

    private void createListing(Player player, MarketplaceService.Kind kind, String materialName,
                               String amountText, String priceText) {
        Material material = parseMaterial(materialName);
        int amount = parseAmount(amountText);
        long price = Money.toPaise(priceText);
        if (material == null || amount < 1 || price > plugin.getTransactionLimit()) {
            throw new IllegalArgumentException("Invalid item, quantity, or price.");
        }
        if (!acquire(player)) {
            return;
        }
        MarketplaceService.Operation operation = kind == MarketplaceService.Kind.AUCTION
                ? MarketplaceService.Operation.AUCTION : MarketplaceService.Operation.MARKET;
        boolean started = plugin.getInventoryEscrow().begin(player, operation, material, amount, price, result -> {
            busy.remove(player.getUniqueId());
            if (result != null && result.resultId() != null && result.resultId() > 0) {
                plugin.sendText(player, "&aCreated listing #" + result.resultId() + " for "
                        + amount + " " + material.name() + " at ₹" + Money.format(price) + ".");
            }
        });
        if (!started) {
            busy.remove(player.getUniqueId());
        }
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
                if (material == null) {
                    busy.remove(player.getUniqueId());
                    plugin.sendText(player, "&cThe order contains an invalid item.");
                    return;
                }
                boolean started = plugin.getInventoryEscrow().begin(player,
                        MarketplaceService.Operation.MARKET, material, order.amount(), order.pricePaise(), id,
                        result -> {
                            busy.remove(player.getUniqueId());
                            if (result != null && result.resultId() != null && result.resultId() == -1L) {
                                plugin.getShopModule().deliverPending(player);
                                plugin.sendText(player, "&cOrder could not be fulfilled; your items are being returned.");
                            } else if (result != null && result.relatedId() != null) {
                                plugin.sendText(player, "&aFulfilled order #" + id + " for ₹"
                                        + Money.format(result.pricePaise()) + ".");
                                Player buyer = Bukkit.getPlayer(order.owner());
                                if (buyer != null) {
                                    plugin.getShopModule().deliverPending(buyer);
                                }
                            }
                        });
                if (!started) {
                    busy.remove(player.getUniqueId());
                }
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
                        String expiry = kind == MarketplaceService.Kind.AUCTION
                                ? " &7expires in " + Math.max(0,
                                (listing.createdAt() + 24L * 60 * 60 * 1000 - System.currentTimeMillis())
                                        / 3_600_000L) + "h"
                                : "";
                        plugin.sendText(player, "&e#" + listing.id() + " &f" + listing.amount() + "x "
                                + listing.material() + " &7— &a₹" + Money.format(listing.pricePaise()) + expiry);
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

}
