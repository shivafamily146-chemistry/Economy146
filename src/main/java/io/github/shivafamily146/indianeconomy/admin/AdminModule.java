package io.github.shivafamily146.indianeconomy.admin;

import io.github.shivafamily146.indianeconomy.IndianEconomyPlugin;
import io.github.shivafamily146.indianeconomy.economy.EconomyService;
import io.github.shivafamily146.indianeconomy.market.MarketplaceService;
import io.github.shivafamily146.indianeconomy.shop.ShopCatalog;
import io.github.shivafamily146.indianeconomy.utils.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AdminModule implements Listener {
    private static final long CONFIRMATION_LIFETIME_MILLIS = 60_000;
    private final IndianEconomyPlugin plugin;
    private final Map<UUID, PendingConfirmation> confirmations = new ConcurrentHashMap<>();

    public AdminModule(IndianEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return false;
        }
        String root = args[0].toLowerCase();
        if (!root.equals("help")) {
            logAdminCommand(sender, args);
        }
        try {
            return switch (root) {
                case "admin" -> openAdmin(sender);
                case "shop" -> shop(sender, args);
                case "additem", "removeitem", "setprice", "setbuy", "setsell", "shopreload", "shoplist", "shopclear" ->
                        shopManagement(sender, args);
                case "history", "transactions", "freeze", "unfreeze", "inspect", "paylog" ->
                        playerManagement(sender, args);
                case "setdaily", "daily" -> daily(sender, args);
                case "auction", "market", "orders" -> marketplace(sender, args);
                case "pay" -> adminPay(sender, args);
                case "giveall", "removeall", "baltop" -> bulkEconomy(sender, args);
                case "backup", "restore", "save", "debug", "maintenance" -> server(sender, args);
                default -> false;
            };
        } catch (IllegalArgumentException exception) {
            plugin.sendText(sender, "&c" + exception.getMessage());
            return true;
        }
    }

    private void logAdminCommand(CommandSender sender, String[] args) {
        String adminId = sender instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
        String action = String.join(" ", args);
        if (action.length() > 256) {
            action = action.substring(0, 256);
        }
        String target = args.length > 1 ? args[1] : null;
        if (target != null && target.length() > 128) {
            target = target.substring(0, 128);
        }
        String loggedAction = action;
        String loggedTarget = target;
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                plugin.getEconomy().logAdminAction(adminId, loggedAction, loggedTarget);
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Could not write admin audit log", exception);
            }
        });
    }

    private boolean openAdmin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.sendText(sender, "&cAdmin GUI is player-only.");
            return true;
        }
        if (!player.hasPermission("indianeconomy.admin")) {
            plugin.sendText(player, "&cYou do not have permission.");
            return true;
        }
        AdminHolder holder = new AdminHolder(player.getUniqueId());
        Inventory inventory = holder.getInventory();
        inventory.setItem(10, button(Material.GOLD_INGOT, "&6Economy Management", "&7Show the balance leaderboard"));
        inventory.setItem(11, button(Material.PLAYER_HEAD, "&6Player Management", "&7Inspect online accounts"));
        inventory.setItem(12, button(Material.CHEST, "&6Shop Catalog & Prices", "&7List configured items and prices"));
        inventory.setItem(13, button(Material.DIAMOND, "&6Auction Listings", "&7Review auction listings"));
        inventory.setItem(14, button(Material.EMERALD, "&6Market Listings", "&7Review market listings"));
        inventory.setItem(15, button(Material.WRITABLE_BOOK, "&6Player Orders", "&7Review escrowed player orders"));
        inventory.setItem(16, button(Material.BOOK, "&6Transaction Logs", "&7Show recent transaction history"));
        inventory.setItem(17, button(Material.WRITABLE_BOOK, "&6Admin Audit Logs", "&7Show recent admin commands"));
        inventory.setItem(21, button(Material.CLOCK, "&6Daily Reward Settings", "&7View current reward and cooldown"));
        inventory.setItem(23, button(Material.REDSTONE, "&6Server Settings", "&7View maintenance and version"));
        inventory.setItem(31, button(Material.ENDER_CHEST, "&6Database Management", "&7Create an integrity-checked backup"));
        inventory.setItem(49, button(Material.BARRIER, "&cClose", ""));
        player.openInventory(inventory);
        return true;
    }

    @EventHandler
    public void onAdminClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AdminHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || !player.hasPermission("indianeconomy.admin")
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        switch (event.getRawSlot()) {
            case 10 -> plugin.getServer().dispatchCommand(player, "ie baltop");
            case 11 -> inspectOnline(player);
            case 12 -> plugin.getServer().dispatchCommand(player, "ie shoplist");
            case 13 -> showListings(player, MarketplaceService.Kind.AUCTION);
            case 14 -> showListings(player, MarketplaceService.Kind.MARKET);
            case 15 -> showListings(player, MarketplaceService.Kind.ORDERS);
            case 16 -> showTransactions(player, null, false);
            case 17 -> showAdminLogs(player);
            case 21 -> dailyInfo(player);
            case 23 -> {
                player.closeInventory();
                plugin.sendText(player, "&eMaintenance: " + plugin.getConfig().getBoolean("maintenance")
                        + " | Version: " + plugin.getPluginMeta().getVersion());
            }
            case 31 -> {
                player.closeInventory();
                plugin.getServer().dispatchCommand(player, "ie backup");
            }
            case 49 -> player.closeInventory();
            default -> {
            }
        }
    }

    @EventHandler
    public void onAdminDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AdminHolder) {
            event.setCancelled(true);
        }
    }

    private boolean shop(CommandSender sender, String[] args) {
        if (!require(sender, "indianeconomy.shop.manage")) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.sendText(sender, "&c/ie shop requires a player.");
            return true;
        }
        if (args.length != 1) {
            plugin.sendText(sender, "&eUsage: /ie shop");
        } else {
            plugin.getShopModule().openCategories(player);
        }
        return true;
    }

    private boolean shopManagement(CommandSender sender, String[] args) {
        if (!require(sender, "indianeconomy.shop.manage")) {
            return true;
        }
        String root = args[0].toLowerCase();
        if (root.equals("shopreload")) {
            plugin.sendText(sender, plugin.reloadValidated()
                    ? "&aShop and price files validated and loaded." : "&cInvalid files; current runtime settings retained.");
            return true;
        }
        if (root.equals("shoplist")) {
            ShopCatalog catalog = plugin.getShopCatalog();
            plugin.sendText(sender, "&6Configured shop items (" + catalog.prices().size() + ")");
            catalog.prices().forEach((material, price) -> plugin.sendText(sender, "&e" + material.name()
                    + " &7buy ₹" + Money.format(price.buyPaise()) + " | sell ₹" + Money.format(price.sellPaise())));
            return true;
        }
        if (root.equals("shopclear")) {
            return clearShop(sender, args);
        }
        if (root.equals("additem")) {
            if (args.length != 2) {
                usage(sender, "/ie additem <material>");
                return true;
            }
            Material material = parseItem(args[1]);
            if (plugin.getShopCatalog().price(material) != null) {
                plugin.sendText(sender, "&cThat material is already configured.");
                return true;
            }
            return editShop(sender, shop -> {
                addToCategory(shop, material.name());
                return prices -> {
                    prices.set("prices." + material.name() + ".buy", "0.00");
                    prices.set("prices." + material.name() + ".sell", "0.00");
                };
            }, "&aAdded " + material.name() + " with buying and selling disabled until priced.");
        }
        if (root.equals("removeitem")) {
            if (args.length != 2 && args.length != 3) {
                usage(sender, "/ie removeitem <material> [confirm]");
                return true;
            }
            Material material = parseItem(args[1]);
            if (plugin.getShopCatalog().price(material) == null) {
                plugin.sendText(sender, "&cThat material is not in the shop.");
                return true;
            }
            String operation = "removeitem:" + material.name();
            if (args.length == 2) {
                confirmations.put(senderId(sender), new PendingConfirmation(operation, List.of(material),
                        System.currentTimeMillis() + CONFIRMATION_LIFETIME_MILLIS));
                plugin.sendText(sender, "&cThis removes " + material.name() + " from all shop categories and prices. "
                        + "Run /ie removeitem " + material.name() + " confirm within 60 seconds.");
                return true;
            }
            if (!args[2].equalsIgnoreCase("confirm") || !validConfirmation(sender, operation)) {
                plugin.sendText(sender, "&cNo matching item-removal confirmation.");
                return true;
            }
            confirmations.remove(senderId(sender));
            return editShop(sender, shop -> {
                removeFromCategories(shop, material.name());
                return prices -> prices.set("prices." + material.name(), null);
            }, "&aRemoved " + material.name() + " from the shop.");
        }
        if (root.equals("setprice") || root.equals("setbuy") || root.equals("setsell")) {
            int needed = root.equals("setprice") ? 4 : 3;
            if (args.length != needed) {
                usage(sender, root.equals("setprice")
                        ? "/ie setprice <material> <buy-price> <sell-price>"
                        : "/ie " + root + " <material> <price>");
                return true;
            }
            Material material = parseItem(args[1]);
            if (plugin.getShopCatalog().price(material) == null) {
                plugin.sendText(sender, "&cAdd this item to the shop first.");
                return true;
            }
            long buy = root.equals("setsell") ? 0 : parseConfiguredPrice(args[2]);
            long sell = root.equals("setbuy") ? 0 : parseConfiguredPrice(args[root.equals("setprice") ? 3 : 2]);
            return editShop(sender, shop -> prices -> {
                String base = "prices." + material.name() + ".";
                if (!root.equals("setsell")) {
                    prices.set(base + "buy", Money.format(buy));
                }
                if (!root.equals("setbuy")) {
                    prices.set(base + "sell", Money.format(sell));
                }
            }, "&aUpdated shop price for " + material.name() + ".");
        }
        return false;
    }

    private boolean clearShop(CommandSender sender, String[] args) {
        Map<Material, ShopCatalog.Price> items = plugin.getShopCatalog().prices();
        if (args.length == 1) {
            String itemNames = items.keySet().stream().map(Material::name).sorted().toList().toString();
            confirmations.put(senderId(sender), new PendingConfirmation("shop", List.copyOf(items.keySet()),
                    System.currentTimeMillis() + CONFIRMATION_LIFETIME_MILLIS));
            plugin.sendText(sender, "&cThis will remove exactly " + items.size() + " configured shop items "
                    + itemNames + ". Run /ie shopclear confirm within 60 seconds.");
            return true;
        }
        if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")
                || !validConfirmation(sender, "shop")) {
            plugin.sendText(sender, "&cNo matching pending shop clear. Run /ie shopclear to review and confirm.");
            return true;
        }
        @SuppressWarnings("unchecked")
        List<Material> selected = (List<Material>) confirmations.remove(senderId(sender)).values;
        return editShop(sender, shop -> {
            for (Material material : selected) {
                removeFromCategories(shop, material.name());
            }
            return prices -> selected.forEach(material -> prices.set("prices." + material.name(), null));
        }, "&aCleared " + selected.size() + " reviewed shop items.");
    }

    private boolean editShop(CommandSender sender, java.util.function.Function<YamlConfiguration, java.util.function.Consumer<YamlConfiguration>> edit,
                             String success) {
        File shopFile = new File(plugin.getDataFolder(), "shop.yml");
        File priceFile = new File(plugin.getDataFolder(), "prices.yml");
        byte[] oldShop = null;
        byte[] oldPrices = null;
        try {
            oldShop = Files.readAllBytes(shopFile.toPath());
            oldPrices = Files.readAllBytes(priceFile.toPath());
            YamlConfiguration shop = loadYaml(shopFile);
            YamlConfiguration prices = loadYaml(priceFile);
            edit.apply(shop).accept(prices);
            shop.save(shopFile);
            prices.save(priceFile);
            if (!plugin.reloadValidated()) {
                Files.write(shopFile.toPath(), oldShop);
                Files.write(priceFile.toPath(), oldPrices);
                plugin.reloadValidated();
                plugin.sendText(sender, "&cShop edit rejected; previous configuration restored.");
                return true;
            }
            plugin.sendText(sender, success);
            return true;
        } catch (IOException | InvalidConfigurationException exception) {
            try {
                if (oldShop != null) {
                    Files.write(shopFile.toPath(), oldShop);
                }
                if (oldPrices != null) {
                    Files.write(priceFile.toPath(), oldPrices);
                }
                plugin.reloadValidated();
            } catch (IOException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            plugin.reportDatabaseFailure("Could not update shop configuration", exception instanceof IOException io ? new SQLException(io) : null);
            plugin.sendText(sender, "&cShop configuration could not be saved.");
            return true;
        }
    }

    private void addToCategory(YamlConfiguration shop, String material) {
        List<String> items = new ArrayList<>(shop.getStringList("categories.Blocks"));
        if (!items.contains(material)) {
            items.add(material);
        }
        shop.set("categories.Blocks", items);
    }

    private void removeFromCategories(YamlConfiguration shop, String material) {
        var categories = shop.getConfigurationSection("categories");
        if (categories == null) {
            return;
        }
        for (String category : categories.getKeys(false)) {
            List<String> items = new ArrayList<>(shop.getStringList("categories." + category));
            items.removeIf(item -> item.equalsIgnoreCase(material));
            shop.set("categories." + category, items);
        }
    }

    private YamlConfiguration loadYaml(File file) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        return yaml;
    }

    private long parseConfiguredPrice(String text) {
        long price = Money.toPaiseAllowZero(text);
        if (price > plugin.getTransactionLimit()) {
            throw new IllegalArgumentException("Price exceeds the configured transaction limit.");
        }
        return price;
    }

    private boolean adminPay(CommandSender sender, String[] args) {
        if (!require(sender, "indianeconomy.admin")) {
            return true;
        }
        if (!(sender instanceof Player payer) || args.length != 3) {
            usage(sender, "/ie pay <player> <amount> (player admin only)");
            return true;
        }
        long amount = Money.toPaise(args[2]);
        if (amount > plugin.getTransactionLimit()) {
            throw new IllegalArgumentException("Payment exceeds transaction limit.");
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        UUID onlineId = online == null ? null : online.getUniqueId();
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                UUID receiverId = onlineId == null ? plugin.getEconomy().findPlayer(args[1]) : onlineId;
                if (receiverId == null) {
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> plugin.sendText(payer, "&cNo known account for " + args[1] + "."));
                    return;
                }
                boolean paid = plugin.getEconomy().transfer(payer.getUniqueId(), payer.getName(),
                        receiverId, args[1], amount);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.sendText(payer, paid ? "&aPaid ₹" + Money.format(amount) + " to " + args[1] + "."
                            : "&cPayment failed; check your balance and account status.");
                    if (paid && online != null && online.isOnline()) {
                        plugin.sendText(online, "&aReceived ₹" + Money.format(amount) + " from " + payer.getName() + ".");
                    }
                });
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Admin payment failed", exception);
            }
        });
        return true;
    }

    private boolean bulkEconomy(CommandSender sender, String[] args) {
        String root = args[0].toLowerCase();
        if (root.equals("baltop")) {
            if (args.length == 1) {
                plugin.getDatabaseExecutor().execute(() -> {
                    try {
                        List<EconomyService.Account> top = plugin.getEconomy().leaderboard(10);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            plugin.sendText(sender, "&6Economy leaderboard");
                            for (int i = 0; i < top.size(); i++) {
                                EconomyService.Account account = top.get(i);
                                plugin.sendText(sender, "&e" + (i + 1) + ". " + account.username()
                                        + " ₹" + Money.format(account.balancePaise()));
                            }
                        });
                    } catch (SQLException exception) {
                        plugin.reportDatabaseFailure("Could not read economy leaderboard", exception);
                    }
                });
                return true;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
                plugin.getDatabaseExecutor().execute(() -> {
                    try {
                        List<EconomyService.Account> accounts = plugin.getEconomy().allAccounts();
                        List<UUID> ids = accounts.stream().map(EconomyService.Account::playerId).toList();
                        String names = accounts.stream().map(EconomyService.Account::username).toList().toString();
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            confirmations.put(senderId(sender), new PendingConfirmation("baltop-reset",
                                    List.copyOf(ids), System.currentTimeMillis() + CONFIRMATION_LIFETIME_MILLIS));
                            plugin.sendText(sender, "&cResetting balances to the configured start will affect exactly "
                                    + ids.size() + " accounts " + names + ". Run /ie baltop reset confirm.");
                        });
                    } catch (SQLException exception) {
                        plugin.reportDatabaseFailure("Could not inspect accounts before reset", exception);
                    }
                });
                return true;
            }
            if (args.length != 3 || !args[1].equalsIgnoreCase("reset")
                    || !args[2].equalsIgnoreCase("confirm") || !validConfirmation(sender, "baltop-reset")) {
                plugin.sendText(sender, "&cNo matching balance-reset confirmation.");
                return true;
            }
            @SuppressWarnings("unchecked")
            List<UUID> ids = (List<UUID>) confirmations.remove(senderId(sender)).values;
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    int count = plugin.getEconomy().resetAccounts(ids);
                    plugin.getServer().getScheduler().runTask(plugin, () -> plugin.sendText(sender,
                            "&aReset " + count + " reviewed balances to the configured starting amount."));
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Balance reset failed", exception);
                }
            });
            return true;
        }

        boolean give = root.equals("giveall");
        if (!require(sender, give ? "indianeconomy.give" : "indianeconomy.take")) {
            return true;
        }
        if (args.length != 2 && args.length != 3) {
            usage(sender, "/ie " + root + " <amount> [confirm]");
            return true;
        }
        long amount = Money.toPaise(args[1]);
        if (amount > plugin.getTransactionLimit()) {
            throw new IllegalArgumentException("Amount exceeds transaction limit.");
        }
        String operation = "bulk:" + root + ":" + amount;
        if (args.length == 2) {
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    List<EconomyService.Account> accounts = plugin.getEconomy().allAccounts();
                    List<UUID> ids = accounts.stream().map(EconomyService.Account::playerId).toList();
                    String names = accounts.stream().map(EconomyService.Account::username).toList().toString();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        confirmations.put(senderId(sender), new PendingConfirmation(operation, List.copyOf(ids),
                                System.currentTimeMillis() + CONFIRMATION_LIFETIME_MILLIS));
                        plugin.sendText(sender, "&e" + root + " ₹" + Money.format(amount) + " will affect exactly "
                                + ids.size() + " accounts " + names + ". Run /ie " + root + " "
                                + Money.format(amount) + " confirm.");
                    });
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Could not inspect accounts before bulk update", exception);
                }
            });
            return true;
        }
        if (!args[2].equalsIgnoreCase("confirm") || !validConfirmation(sender, operation)) {
            plugin.sendText(sender, "&cNo matching bulk-operation confirmation.");
            return true;
        }
        @SuppressWarnings("unchecked")
        List<UUID> ids = (List<UUID>) confirmations.remove(senderId(sender)).values;
        long delta = give ? amount : -amount;
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                int count = plugin.getEconomy().adjustAccounts(ids, delta,
                        give ? "ADMIN_GIVEALL" : "ADMIN_REMOVEALL");
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> plugin.sendText(sender, "&aUpdated " + count + " reviewed accounts."));
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Bulk economy operation failed", exception);
            }
        });
        return true;
    }

    private boolean playerManagement(CommandSender sender, String[] args) {
        if (!require(sender, "indianeconomy.admin")) {
            return true;
        }
        String command = args[0].toLowerCase();
        if (command.equals("transactions")) {
            showTransactions(sender, null, false);
            return true;
        }
        if (command.equals("inspect") && args.length == 1 && sender instanceof Player player) {
            inspectOnline(player);
            return true;
        }
        if (args.length < 2) {
            usage(sender, "/ie " + command + " <player>");
            return true;
        }
        UUID playerId = resolveOnline(args[1]);
        if (command.equals("freeze") || command.equals("unfreeze")) {
            boolean freeze = command.equals("freeze");
            String actor = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    UUID target = playerId == null ? plugin.getEconomy().findPlayer(args[1]) : playerId;
                    if (target == null) {
                        plugin.getServer().getScheduler().runTask(plugin,
                                () -> plugin.sendText(sender, "&cNo account found for " + args[1] + "."));
                        return;
                    }
                    boolean changed = plugin.getEconomy().setFrozen(target, actor, freeze);
                    plugin.getServer().getScheduler().runTask(plugin, () -> plugin.sendText(sender,
                            changed ? "&aAccount " + args[1] + (freeze ? " frozen." : " unfrozen.")
                                    : "&eAccount state was already " + (freeze ? "frozen." : "unfrozen.")));
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Freeze operation failed", exception);
                }
            });
            return true;
        }
        if (playerId == null) {
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    UUID known = plugin.getEconomy().findPlayer(args[1]);
                    if (known == null) {
                        plugin.getServer().getScheduler().runTask(plugin,
                                () -> plugin.sendText(sender, "&cNo account found for " + args[1] + "."));
                    } else {
                        scheduleHistory(sender, command, known, args[1]);
                    }
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Player lookup failed", exception);
                }
            });
            return true;
        }
        scheduleHistory(sender, command, playerId, args[1]);
        return true;
    }

    private void scheduleHistory(CommandSender sender, String command, UUID id, String playerName) {
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                if (command.equals("inspect")) {
                    long balance = plugin.getEconomy().balance(id);
                    boolean frozen = plugin.getEconomy().isFrozen(id);
                    plugin.getServer().getScheduler().runTask(plugin, () -> plugin.sendText(sender,
                            "&e" + playerName + ": ₹" + Money.format(balance) + " | frozen: " + frozen));
                } else {
                    showTransactions(sender, id, command.equals("paylog"));
                }
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Player inspection failed", exception);
            }
        });
    }

    private void inspectOnline(Player admin) {
        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        plugin.sendText(admin, "&6Online players (use /ie inspect <player> for details):");
        online.forEach(player -> plugin.sendText(admin, "&e" + player.getName()));
    }

    private void showTransactions(CommandSender sender, UUID playerId, boolean paymentsOnly) {
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                List<EconomyService.Transaction> transactions = playerId == null
                        ? plugin.getEconomy().recentTransactions(10)
                        : plugin.getEconomy().transactions(playerId, 10, paymentsOnly);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.sendText(sender, "&6Recent transactions");
                    if (transactions.isEmpty()) {
                        plugin.sendText(sender, "&7No transactions found.");
                    }
                    for (EconomyService.Transaction transaction : transactions) {
                        plugin.sendText(sender, "&e" + transaction.type() + " &f₹"
                                + Money.format(transaction.amountPaise()) + " &7id=" + transaction.id());
                    }
                });
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Transaction query failed", exception);
            }
        });
    }

    private void showAdminLogs(CommandSender sender) {
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                List<EconomyService.AdminLog> logs = plugin.getEconomy().recentAdminLogs(20);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.sendText(sender, "&6Recent admin commands");
                    if (logs.isEmpty()) {
                        plugin.sendText(sender, "&7No admin commands recorded.");
                    }
                    for (EconomyService.AdminLog log : logs) {
                        plugin.sendText(sender, "&e" + log.adminId() + " &7/ie " + log.action()
                                + (log.target() == null ? "" : " &8(target " + log.target() + ")"));
                    }
                });
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Admin audit-log query failed", exception);
            }
        });
    }

    private boolean daily(CommandSender sender, String[] args) {
        if (!require(sender, "indianeconomy.admin")) {
            return true;
        }
        if (args[0].equalsIgnoreCase("setdaily")) {
            if (args.length != 2) {
                usage(sender, "/ie setdaily <amount>");
                return true;
            }
            long reward = Money.toPaise(args[1]);
            if (reward > plugin.getTransactionLimit()) {
                throw new IllegalArgumentException("Daily reward exceeds transaction limit.");
            }
            return updateConfig(sender, "daily-reward", Money.format(reward), "&aDaily reward updated.");
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("info")) {
            dailyInfo(sender);
            return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("cooldown")) {
            int hours;
            try {
                hours = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Cooldown must be a whole number of hours.");
            }
            if (hours < 1 || hours > 8760) {
                throw new IllegalArgumentException("Cooldown must be between 1 and 8760 hours.");
            }
            return updateConfig(sender, "daily-cooldown-hours", hours, "&aDaily cooldown updated.");
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("resetall")) {
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    List<UUID> ids = plugin.getEconomy().dailyPlayerIds();
                    String exactIds = ids.toString();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        confirmations.put(senderId(sender), new PendingConfirmation("daily-resetall", List.copyOf(ids),
                                System.currentTimeMillis() + CONFIRMATION_LIFETIME_MILLIS));
                        plugin.sendText(sender, "&cThis resets exactly " + ids.size() + " daily claim records "
                                + exactIds + ". Run /ie daily resetall confirm within 60 seconds.");
                    });
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Could not inspect daily records", exception);
                }
            });
            return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("resetall")) {
            if (!args[2].equalsIgnoreCase("confirm") || !validConfirmation(sender, "daily-resetall")) {
                plugin.sendText(sender, "&cNo matching pending daily reset confirmation.");
                return true;
            }
            @SuppressWarnings("unchecked")
            List<UUID> ids = (List<UUID>) confirmations.remove(senderId(sender)).values;
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    int count = plugin.getEconomy().resetDailyPlayers(ids);
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> plugin.sendText(sender, "&aReset daily cooldowns for " + count + " players."));
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Daily reset failed", exception);
                }
            });
            return true;
        }
        if ((args.length == 3 || args.length == 4) && args[1].equalsIgnoreCase("reset")) {
            UUID playerId = resolveOnline(args[2]);
            String operation = "daily-reset:" + args[2].toLowerCase(java.util.Locale.ROOT);
            if (args.length == 4) {
                if (!args[3].equalsIgnoreCase("confirm") || !validConfirmation(sender, operation)) {
                    plugin.sendText(sender, "&cNo matching daily reset confirmation.");
                    return true;
                }
                @SuppressWarnings("unchecked")
                List<UUID> ids = (List<UUID>) confirmations.remove(senderId(sender)).values;
                plugin.getDatabaseExecutor().execute(() -> {
                    try {
                        int changed = plugin.getEconomy().resetDailyPlayers(ids);
                        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.sendText(sender,
                                changed == 1 ? "&aDaily cooldown reset for " + args[2] + "."
                                        : "&eThe reviewed daily record no longer exists."));
                    } catch (SQLException exception) {
                        plugin.reportDatabaseFailure("Daily reset failed", exception);
                    }
                });
                return true;
            }
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    UUID target = playerId == null ? plugin.getEconomy().findPlayer(args[2]) : playerId;
                    if (target == null || !plugin.getEconomy().dailyPlayerIds().contains(target)) {
                        plugin.getServer().getScheduler().runTask(plugin,
                                () -> plugin.sendText(sender, "&eNo daily record for " + args[2] + "."));
                        return;
                    }
                    UUID reviewed = target;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        confirmations.put(senderId(sender), new PendingConfirmation(operation,
                                List.of(reviewed), System.currentTimeMillis() + CONFIRMATION_LIFETIME_MILLIS));
                        plugin.sendText(sender, "&cThis deletes " + args[2]
                                + "'s daily cooldown record. Run /ie daily reset " + args[2] + " confirm.");
                    });
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Daily reset failed", exception);
                }
            });
            return true;
        }
        usage(sender, "/ie daily <info | cooldown <hours> | reset <player> [confirm] | resetall [confirm]>");
        return true;
    }

    private void dailyInfo(CommandSender sender) {
        plugin.sendText(sender, "&eDaily reward: ₹" + Money.format(plugin.getDailyReward())
                + " | cooldown: " + plugin.getDailyCooldownHours() + " hours");
    }

    private boolean updateConfig(CommandSender sender, String path, Object value, String success) {
        Object old = plugin.getConfig().get(path);
        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        if (!plugin.reloadValidated()) {
            plugin.getConfig().set(path, old);
            plugin.saveConfig();
            plugin.reloadValidated();
            plugin.sendText(sender, "&cInvalid configuration; previous setting restored.");
        } else {
            plugin.sendText(sender, success);
        }
        return true;
    }

    private boolean marketplace(CommandSender sender, String[] args) {
        MarketplaceService.Kind kind = switch (args[0].toLowerCase()) {
            case "auction" -> MarketplaceService.Kind.AUCTION;
            case "market" -> MarketplaceService.Kind.MARKET;
            default -> MarketplaceService.Kind.ORDERS;
        };
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            showListings(sender, kind);
            return true;
        }
        String action = args.length > 1 ? args[1].toLowerCase() : "";
        if (!List.of("remove", "cancel", "clear").contains(action)) {
            usage(sender, "/ie " + args[0] + " <list | remove/cancel <id> confirm | clear confirm>");
            return true;
        }
        if (!require(sender, switch (kind) {
            case AUCTION -> "indianeconomy.auction.manage";
            case MARKET -> "indianeconomy.market.manage";
            case ORDERS -> "indianeconomy.orders.manage";
        })) {
            return true;
        }
        if (action.equals("clear")) {
            return confirmClear(sender, kind, args);
        }
        if (args.length < 3) {
            usage(sender, "/ie " + args[0] + " " + action + " <id> confirm");
            return true;
        }
        long id = parseId(args[2]);
        if (args.length == 3) {
            String root = args[0];
            String subcommand = action;
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    MarketplaceService.Listing listing = plugin.getMarketplace().list(kind, 1000).stream()
                            .filter(row -> row.id() == id).findFirst().orElse(null);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (listing == null) {
                            plugin.sendText(sender, "&cNo such listing.");
                            return;
                        }
                        confirmations.put(senderId(sender), new PendingConfirmation(root + ":" + subcommand + ":" + id, List.of(id),
                                System.currentTimeMillis() + CONFIRMATION_LIFETIME_MILLIS));
                        plugin.sendText(sender, "&cThis will remove #" + id + ": " + listing.amount() + "x "
                                + listing.material() + " (₹" + Money.format(listing.pricePaise())
                                + "). Run /ie " + root + " " + subcommand + " " + id + " confirm.");
                    });
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Listing lookup failed", exception);
                }
            });
            return true;
        }
        if (args.length != 4 || !args[3].equalsIgnoreCase("confirm")
                || !validConfirmation(sender, args[0] + ":" + action + ":" + id)) {
            plugin.sendText(sender, "&cNo matching pending confirmation.");
            return true;
        }
        confirmations.remove(senderId(sender));
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                boolean changed = kind == MarketplaceService.Kind.ORDERS
                        ? plugin.getMarketplace().cancelOrder(id, null)
                        : plugin.getMarketplace().removeListing(kind, id, null);
                plugin.getServer().getScheduler().runTask(plugin, () -> plugin.sendText(sender,
                        changed ? "&aListing removed; escrow was refunded or returned."
                                : "&cListing no longer exists."));
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Admin listing removal failed", exception);
            }
        });
        return true;
    }

    private boolean confirmClear(CommandSender sender, MarketplaceService.Kind kind, String[] args) {
        if (args.length == 2) {
            String root = args[0];
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    List<MarketplaceService.Listing> snapshot = plugin.getMarketplace().list(kind, 1000);
                    List<Long> ids = snapshot.stream().map(MarketplaceService.Listing::id).toList();
                    String items = snapshot.stream().map(row -> "#" + row.id() + " " + row.amount() + "x "
                            + row.material() + " ₹" + Money.format(row.pricePaise())).toList().toString();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        confirmations.put(senderId(sender), new PendingConfirmation(root, ids,
                                System.currentTimeMillis() + CONFIRMATION_LIFETIME_MILLIS));
                        plugin.sendText(sender, "&cThis will remove exactly " + ids.size() + " "
                                + kind.name().toLowerCase() + " records: " + items + ". Run /ie "
                                + root + " clear confirm within 60 seconds.");
                    });
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Could not inspect listings before clear", exception);
                }
            });
            return true;
        }
        if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")
                || !validConfirmation(sender, args[0])) {
            plugin.sendText(sender, "&cNo matching pending clear confirmation.");
            return true;
        }
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) confirmations.remove(senderId(sender)).values;
        plugin.getDatabaseExecutor().execute(() -> {
            int completed = 0;
            try {
                for (long id : ids) {
                    boolean changed = kind == MarketplaceService.Kind.ORDERS
                            ? plugin.getMarketplace().cancelOrder(id, null)
                            : plugin.getMarketplace().removeListing(kind, id, null);
                    if (changed) {
                        completed++;
                    }
                }
                int count = completed;
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> plugin.sendText(sender, "&aRemoved " + count + " reviewed " + kind.name().toLowerCase() + " records."));
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Could not clear marketplace listings", exception);
                int count = completed;
                plugin.getServer().getScheduler().runTask(plugin, () -> plugin.sendText(sender,
                        "&cClear stopped after " + count + " records; check server log."));
            }
        });
        return true;
    }

    private void showListings(CommandSender sender, MarketplaceService.Kind kind) {
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                List<MarketplaceService.Listing> listings = plugin.getMarketplace().list(kind, 20);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.sendText(sender, "&6" + kind.name() + " (" + listings.size() + ")");
                    listings.forEach(listing -> plugin.sendText(sender, "&e#" + listing.id() + " "
                            + listing.amount() + "x " + listing.material() + " ₹"
                            + Money.format(listing.pricePaise()) + " by " + listing.owner()));
                });
            } catch (SQLException exception) {
                plugin.reportDatabaseFailure("Admin marketplace listing failed", exception);
            }
        });
    }

    private boolean server(CommandSender sender, String[] args) {
        if (args[0].equalsIgnoreCase("maintenance")) {
            if (!require(sender, "indianeconomy.admin")) {
                return true;
            }
            if (args.length != 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
                usage(sender, "/ie maintenance <on|off>");
                return true;
            }
            return updateConfig(sender, "maintenance", args[1].equalsIgnoreCase("on"),
                    "&aMaintenance mode " + args[1].toLowerCase() + ".");
        }
        if (args[0].equalsIgnoreCase("save")) {
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    plugin.getDatabase().save();
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> plugin.sendText(sender, "&aDatabase checkpoint completed."));
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Database checkpoint failed", exception);
                }
            });
            return true;
        }
        if (args[0].equalsIgnoreCase("debug")) {
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    String integrity = plugin.getDatabase().integrityCheck();
                    int accountCount = plugin.getEconomy().accountCount();
                    plugin.getServer().getScheduler().runTask(plugin, () -> plugin.sendText(sender,
                            "&eSQLite integrity: " + integrity + " | accounts: " + accountCount
                                    + " | queued DB tasks: " + plugin.getDatabaseQueueLength()));
                } catch (SQLException exception) {
                    plugin.reportDatabaseFailure("Debug database check failed", exception);
                }
            });
            return true;
        }
        if (args[0].equalsIgnoreCase("backup")) {
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    Path directory = plugin.getDataFolder().toPath().resolve("backups");
                    String name = "economy-" + Instant.now().toEpochMilli() + ".db";
                    Path backup = plugin.getDatabase().backup(directory.resolve(name));
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> plugin.sendText(sender, "&aBackup created: " + backup.getFileName()));
                } catch (SQLException | IOException exception) {
                    plugin.reportDatabaseFailure("Database backup failed",
                            exception instanceof SQLException sql ? sql : new SQLException(exception));
                }
            });
            return true;
        }
        if (args[0].equalsIgnoreCase("restore")) {
            if (args.length < 2 || args.length > 3 || !args[1].matches("[A-Za-z0-9._-]+\\.db")) {
                usage(sender, "/ie restore <backup-file.db> [confirm]");
                return true;
            }
            if (args.length == 2) {
                plugin.sendText(sender, "&cThis replaces the live economy database with backups/" + args[1]
                        + ". Run /ie restore " + args[1] + " confirm to continue.");
                confirmations.put(senderId(sender), new PendingConfirmation("restore:" + args[1], List.of(args[1]),
                        System.currentTimeMillis() + CONFIRMATION_LIFETIME_MILLIS));
                return true;
            }
            if (!args[2].equalsIgnoreCase("confirm") || !validConfirmation(sender, "restore:" + args[1])) {
                plugin.sendText(sender, "&cNo matching restore confirmation.");
                return true;
            }
            confirmations.remove(senderId(sender));
            plugin.getDatabaseExecutor().execute(() -> {
                try {
                    Path backupDirectory = plugin.getDataFolder().toPath().resolve("backups").normalize();
                    Path source = backupDirectory.resolve(args[1]).normalize();
                    if (!source.getParent().equals(backupDirectory)) {
                        throw new IOException("Backup path escaped the backup directory.");
                    }
                    String safetyName = "pre-restore-" + Instant.now().toEpochMilli() + ".db";
                    plugin.getDatabase().backup(backupDirectory.resolve(safetyName));
                    plugin.getDatabase().restore(source);
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> plugin.sendText(sender, "&aRestored " + args[1] + " (safety backup " + safetyName + ")."));
                } catch (SQLException | IOException exception) {
                    plugin.reportDatabaseFailure("Database restore failed",
                            exception instanceof SQLException sql ? sql : new SQLException(exception));
                }
            });
            return true;
        }
        return false;
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.sendText(sender, "&cYou need " + permission + " permission.");
        return false;
    }

    private UUID resolveOnline(String name) {
        Player player = Bukkit.getPlayerExact(name);
        return player == null ? null : player.getUniqueId();
    }

    private Material parseItem(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException("Unknown or non-item material: " + name);
        }
        return material;
    }

    private long parseId(String text) {
        try {
            long id = Long.parseLong(text);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("ID must be a positive whole number.");
    }

    private UUID senderId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : new UUID(0, 0);
    }

    private boolean validConfirmation(CommandSender sender, String operation) {
        PendingConfirmation pending = confirmations.get(senderId(sender));
        if (pending == null || !pending.operation.equals(operation)
                || pending.expiresAt < System.currentTimeMillis()) {
            confirmations.remove(senderId(sender));
            return false;
        }
        return true;
    }

    private void usage(CommandSender sender, String usage) {
        plugin.sendText(sender, "&eUsage: " + usage);
    }

    private ItemStack button(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(plugin.colorText(name));
        meta.setLore(List.of(plugin.colorText(lore)));
        item.setItemMeta(meta);
        return item;
    }

    private static final class AdminHolder implements InventoryHolder {
        private final UUID owner;
        private final Inventory inventory;

        private AdminHolder(UUID owner) {
            this.owner = owner;
            this.inventory = Bukkit.createInventory(this, 54, "IndianEconomy Admin");
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record PendingConfirmation(String operation, List<?> values, long expiresAt) {
    }
}
