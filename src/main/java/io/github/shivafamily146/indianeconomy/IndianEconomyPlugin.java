package io.github.shivafamily146.indianeconomy;

import io.github.shivafamily146.indianeconomy.database.Database;
import io.github.shivafamily146.indianeconomy.economy.EconomyService;
import io.github.shivafamily146.indianeconomy.shop.ShopCatalog;
import io.github.shivafamily146.indianeconomy.shop.ShopModule;
import io.github.shivafamily146.indianeconomy.market.MarketModule;
import io.github.shivafamily146.indianeconomy.market.MarketplaceService;
import io.github.shivafamily146.indianeconomy.admin.AdminModule;
import io.github.shivafamily146.indianeconomy.utils.Money;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class IndianEconomyPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private Database database;
    private EconomyService economy;
    private ThreadPoolExecutor databaseExecutor;
    private FileConfiguration messages;
    private volatile long dailyReward;
    private volatile long dailyCooldownMillis;
    private volatile long transactionLimit;
    private ShopCatalog shopCatalog;
    private ShopModule shopModule;
    private MarketplaceService marketplace;
    private MarketModule marketModule;
    private AdminModule adminModule;
    private final Set<UUID> pendingPayments = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("prices.yml");
        saveResourceIfMissing("shop.yml");
        ThreadFactory databaseThreadFactory = runnable -> {
            Thread thread = new Thread(runnable, "IndianEconomy-SQLite");
            thread.setDaemon(true);
            return thread;
        };
        databaseExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), databaseThreadFactory);
        if (!reloadValidated()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        String databaseFile = getConfig().getString("database-file", "economy.db");
        try {
            database = new Database(getDataFolder().toPath().resolve(databaseFile));
            economy = new EconomyService(database, Money.toPaise(getConfig().getString("starting-balance", "100.00")));
            shopModule = new ShopModule(this, shopCatalog);
            marketplace = new MarketplaceService(database, economy);
            marketModule = new MarketModule(this, marketplace);
            adminModule = new AdminModule(this);
        } catch (SQLException | IOException | IllegalArgumentException exception) {
            getLogger().severe("Could not initialize economy database: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (String commandName : List.of("help", "bal", "pay", "baltop", "daily", "shop", "buy", "sell",
                "sellall", "auction", "market", "orders", "ie")) {
            PluginCommand command = getCommand(commandName);
            if (command != null) {
                command.setExecutor(this);
                command.setTabCompleter(this);
            }
        }
        getServer().getPluginManager().registerEvents(new AccountListener(this), this);
        getServer().getPluginManager().registerEvents(shopModule, this);
        getServer().getPluginManager().registerEvents(adminModule, this);
        getLogger().info("IndianEconomy enabled using SQLite.");
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }

    public boolean reloadValidated() {
        File configFile = new File(getDataFolder(), "config.yml");
        File messagesFile = new File(getDataFolder(), "messages.yml");
        YamlConfiguration candidateConfig = new YamlConfiguration();
        YamlConfiguration candidateMessages = new YamlConfiguration();
        try {
            candidateConfig.load(configFile);
            candidateMessages.load(messagesFile);
            ShopCatalog candidateShop = ShopCatalog.load(new File(getDataFolder(), "shop.yml"),
                    new File(getDataFolder(), "prices.yml"));
            candidateShop.validateRuntimeItems();
            long startingBalance = Money.toPaise(candidateConfig.getString("starting-balance", "100.00"));
            long reward = Money.toPaise(candidateConfig.getString("daily-reward", "25.00"));
            long limit = Money.toPaise(candidateConfig.getString("transaction-limit", "1000000.00"));
            if (!candidateConfig.isInt("daily-cooldown-hours")) {
                throw new IllegalArgumentException("daily-cooldown-hours must be a whole number.");
            }
            long cooldown = candidateConfig.getLong("daily-cooldown-hours", 24);
            String dbFile = candidateConfig.getString("database-file");
            if (cooldown < 1 || cooldown > 8760 || dbFile == null || dbFile.isBlank()
                    || Path.of(dbFile).isAbsolute() || dbFile.contains("..")) {
                throw new IllegalArgumentException("Invalid cooldown or database-file setting.");
            }
            if (database != null && !dbFile.equals(getConfig().getString("database-file", "economy.db"))) {
                throw new IllegalArgumentException("database-file cannot be changed while the database is open.");
            }
            messages = candidateMessages;
            shopCatalog = candidateShop;
            if (shopModule != null) {
                shopModule.setCatalog(candidateShop);
            }
            dailyReward = reward;
            dailyCooldownMillis = cooldown * 60L * 60L * 1000L;
            transactionLimit = limit;
            reloadConfig();
            if (economy != null) {
                economy.setStartingBalance(startingBalance);
            }
            return true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            getLogger().severe("Configuration reload rejected; retaining previous settings: " + exception.getMessage());
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ie")) {
            handleAdmin(sender, args);
            return true;
        }
        if (getConfig().getBoolean("maintenance") && !sender.hasPermission("indianeconomy.admin")) {
            send(sender, "error", "message", "The economy is temporarily in maintenance mode.");
            return true;
        }
        if (!sender.hasPermission(command.getPermission())) {
            send(sender, "no-permission");
            return true;
        }
        switch (command.getName().toLowerCase()) {
            case "help" -> help(sender);
            case "bal" -> handleBalance(sender, args);
            case "pay" -> handlePay(sender, args);
            case "baltop" -> handleBaltop(sender);
            case "daily" -> handleDaily(sender);
            case "shop", "buy", "sell", "sellall" -> shopModule.command(sender, command.getName().toLowerCase(), args);
            case "auction", "market", "orders" -> marketModule.command(sender, command.getName().toLowerCase(), args);
            default -> send(sender, "usage", "usage", "/" + command.getName());
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase();
        if (args.length == 0) {
            return List.of();
        }
        String current = args[args.length - 1];
        List<String> materials = shopCatalog.prices().keySet().stream().map(Material::name).toList();
        if (name.equals("shop") && args.length == 1) {
            return matching(List.of("search"), current);
        }
        if ((name.equals("buy") || name.equals("sell")) && args.length == 1) {
            return matching(materials, current).stream().limit(50).toList();
        }
        if ((name.equals("buy") || name.equals("sell")) && args.length == 2) {
            return matching(List.of("1", "16", "64"), current);
        }
        if ((name.equals("auction") || name.equals("market")) && args.length == 1) {
            return matching(List.of("list", "sell", "buy"), current);
        }
        if ((name.equals("auction") || name.equals("market")) && args.length == 2
                && args[0].equalsIgnoreCase("sell")) {
            return matching(materials, current).stream().limit(50).toList();
        }
        if ((name.equals("auction") || name.equals("market")) && args.length == 3
                && args[0].equalsIgnoreCase("sell")) {
            return matching(List.of("1", "16", "64"), current);
        }
        if (name.equals("orders") && args.length == 1) {
            return matching(List.of("list", "create", "fulfill", "cancel"), current);
        }
        if (name.equals("orders") && args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return matching(materials, current).stream().limit(50).toList();
        }
        if (name.equals("ie")) {
            if (args.length == 1) {
                return matching(List.of("help", "give", "take", "set", "reset", "balance", "pay",
                        "giveall", "removeall", "baltop", "stats", "reload", "shop", "shopreload",
                        "shoplist", "shopclear", "additem", "removeitem", "setprice", "setbuy",
                        "setsell", "history", "transactions", "freeze", "unfreeze", "inspect",
                        "paylog", "setdaily", "daily", "auction", "market", "orders", "save",
                        "backup", "restore", "version", "debug", "maintenance", "admin"), current);
            }
            if (args.length == 2 && List.of("daily", "baltop", "auction", "market", "orders")
                    .contains(args[0].toLowerCase())) {
                List<String> nested = switch (args[0].toLowerCase()) {
                    case "daily" -> List.of("info", "cooldown", "reset", "resetall");
                    case "baltop" -> List.of("reset");
                    case "auction", "market" -> List.of("list", "remove", "clear");
                    default -> List.of("list", "cancel", "clear");
                };
                return matching(nested, current);
            }
            if (args.length == 2 && List.of("give", "take", "set", "reset", "balance", "history",
                    "freeze", "unfreeze", "inspect", "paylog", "daily").contains(args[0].toLowerCase())) {
                return matching(getServer().getOnlinePlayers().stream().map(Player::getName).toList(), current);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("daily")
                    && args[1].equalsIgnoreCase("reset")) {
                return matching(getServer().getOnlinePlayers().stream().map(Player::getName).toList(), current);
            }
            if (args.length == 2 && List.of("removeitem", "additem", "setprice", "setbuy", "setsell")
                    .contains(args[0].toLowerCase())) {
                return matching(materials, current).stream().limit(50).toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("restore")) {
                File backupDirectory = new File(getDataFolder(), "backups");
                File[] backups = backupDirectory.listFiles((directory, file) -> file.matches("[A-Za-z0-9._-]+\\.db"));
                return backups == null ? List.of() : matching(
                        java.util.Arrays.stream(backups).map(File::getName).toList(), current);
            }
            if (args.length >= 3 && (args[args.length - 1].isBlank()
                    || "confirm".startsWith(current.toLowerCase()))) {
                return matching(List.of("confirm"), current);
            }
        }
        return List.of();
    }

    private List<String> matching(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(java.util.Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(java.util.Locale.ROOT).startsWith(normalized))
                .toList();
    }

    private void handleBalance(CommandSender sender, String[] args) {
        UUID playerId;
        String name;
        if (args.length == 0 && sender instanceof Player player) {
            playerId = player.getUniqueId();
            name = player.getName();
        } else if (args.length == 1) {
            Player online = getServer().getPlayerExact(args[0]);
            name = args[0];
            if (online == null) {
                databaseExecutor.execute(() -> {
                    try {
                        UUID offlineId = economy.findPlayer(name);
                        if (offlineId == null) {
                            getServer().getScheduler().runTask(this,
                                    () -> send(sender, "player-not-found", "player", name));
                            return;
                        }
                        long balance = economy.ensureAccount(offlineId, name);
                        getServer().getScheduler().runTask(this,
                                () -> send(sender, "balance", "amount", Money.format(balance)));
                    } catch (SQLException exception) {
                        reportDatabaseError(sender, exception);
                    }
                });
                return;
            }
            playerId = online.getUniqueId();
        } else {
            send(sender, "usage", "usage", "/bal [player]");
            return;
        }
        databaseExecutor.execute(() -> {
            try {
                long balance = economy.ensureAccount(playerId, name);
                getServer().getScheduler().runTask(this,
                        () -> send(sender, "balance", "amount", Money.format(balance)));
            } catch (SQLException exception) {
                reportDatabaseError(sender, exception);
            }
        });
    }

    private void handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "player-only");
            return;
        }
        if (args.length != 2) {
            send(sender, "usage", "usage", "/pay <player> <amount>");
            return;
        }
        long amount;
        try {
            amount = Money.toPaise(args[1]);
            if (amount > transactionLimit) {
                throw new IllegalArgumentException("Amount exceeds transaction limit.");
            }
        } catch (IllegalArgumentException exception) {
            send(sender, "invalid-amount");
            return;
        }
        Player target = getServer().getPlayerExact(args[0]);
        String targetName = target == null ? args[0] : target.getName();
        if (!pendingPayments.add(player.getUniqueId())) {
            send(player, "error", "message", "Wait for your previous payment to finish.");
            return;
        }
        databaseExecutor.execute(() -> {
            try {
                UUID targetId = target == null ? economy.findPlayer(targetName) : target.getUniqueId();
                if (targetId == null) {
                    getServer().getScheduler().runTask(this,
                            () -> {
                                pendingPayments.remove(player.getUniqueId());
                                send(sender, "player-not-found", "player", targetName);
                            });
                    return;
                }
                boolean success = economy.transfer(player.getUniqueId(), player.getName(), targetId, targetName, amount);
                getServer().getScheduler().runTask(this, () -> {
                    pendingPayments.remove(player.getUniqueId());
                    if (!success) {
                        send(player, "not-enough-money");
                        return;
                    }
                    send(player, "paid", "amount", Money.format(amount), "player", targetName);
                    if (target != null && target.isOnline()) {
                        send(target, "received", "amount", Money.format(amount), "player", player.getName());
                    }
                });
            } catch (SQLException exception) {
                pendingPayments.remove(player.getUniqueId());
                reportDatabaseError(sender, exception);
            }
        });
    }

    private void handleDaily(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "player-only");
            return;
        }
        databaseExecutor.execute(() -> {
            try {
                boolean claimed = economy.claimDaily(player.getUniqueId(), System.currentTimeMillis(),
                        dailyCooldownMillis, dailyReward);
                getServer().getScheduler().runTask(this, () -> {
                    if (claimed) {
                        send(player, "success", "message", "Daily reward claimed: ₹" + Money.format(dailyReward));
                    } else {
                        send(player, "error", "message", "Your daily reward is still on cooldown.");
                    }
                });
            } catch (SQLException exception) {
                reportDatabaseError(sender, exception);
            }
        });
    }

    private void handleBaltop(CommandSender sender) {
        databaseExecutor.execute(() -> {
            try {
                List<EconomyService.Account> top = economy.leaderboard(10);
                getServer().getScheduler().runTask(this, () -> {
                    sender.sendMessage(color("&6Economy leaderboard"));
                    for (int i = 0; i < top.size(); i++) {
                        EconomyService.Account account = top.get(i);
                        sender.sendMessage(color("&e" + (i + 1) + ". &f" + account.username()
                                + " &7- &a₹" + Money.format(account.balancePaise())));
                    }
                });
            } catch (SQLException exception) {
                reportDatabaseError(sender, exception);
            }
        });
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("indianeconomy.admin")) {
            send(sender, "no-permission");
            return;
        }
        if (adminModule.execute(sender, args)) {
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            List.of(
                    "&6IndianEconomy admin commands",
                    "&e/ie balance <player> | give <player> <amount> | take <player> <amount> confirm",
                    "&e/ie set <player> <amount> confirm | reset <player> confirm | pay <player> <amount>",
                    "&e/ie giveall|removeall <amount> [confirm] | baltop [reset [confirm]] | stats",
                    "&e/ie shop | additem|removeitem|setprice|setbuy|setsell|shopreload|shoplist|shopclear",
                    "&e/ie history|paylog|inspect|freeze|unfreeze <player> | transactions",
                    "&e/ie setdaily <amount> | daily info|cooldown <hours>|reset <player> [confirm]|resetall [confirm]",
                    "&e/ie auction|market list|remove <id> confirm|clear confirm",
                    "&e/ie orders list|cancel <id> confirm|clear confirm",
                    "&e/ie save | backup | restore <file.db> [confirm] | debug | maintenance on|off | reload | version",
                    "&e/ie admin &7- Open the admin overview GUI"
            ).forEach(line -> sender.sendMessage(color(line)));
            return;
        }
        if (args[0].equalsIgnoreCase("version")) {
            sender.sendMessage(color("&eIndianEconomy " + getPluginMeta().getVersion() + " (Paper API 26.3 preview)"));
            return;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("indianeconomy.reload")) {
                send(sender, "no-permission");
            } else {
                boolean valid = reloadValidated();
                sender.sendMessage(color(valid ? "&aConfiguration reloaded." : "&cInvalid configuration; previous settings retained."));
            }
            return;
        }
        if (args[0].equalsIgnoreCase("baltop")) {
            if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
                sender.sendMessage(color("&cThis resets every account balance to the configured starting balance. Run /ie baltop reset confirm to continue."));
                return;
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("reset") && args[2].equalsIgnoreCase("confirm")) {
                databaseExecutor.execute(() -> {
                    try {
                        int count = economy.accountCount();
                        economy.resetAll();
                        getServer().getScheduler().runTask(this,
                                () -> sender.sendMessage(color("&aReset " + count + " player balances to the configured starting balance.")));
                    } catch (SQLException exception) {
                        reportDatabaseError(sender, exception);
                    }
                });
                return;
            }
            handleBaltop(sender);
            return;
        }
        if (args[0].equalsIgnoreCase("stats")) {
            databaseExecutor.execute(() -> {
                try {
                    int count = economy.accountCount();
                    getServer().getScheduler().runTask(this,
                            () -> sender.sendMessage(color("&eKnown accounts: &f" + count)));
                } catch (SQLException exception) {
                    reportDatabaseError(sender, exception);
                }
            });
            return;
        }
        if (args[0].equalsIgnoreCase("giveall") || args[0].equalsIgnoreCase("removeall")) {
            boolean give = args[0].equalsIgnoreCase("giveall");
            if (!sender.hasPermission(give ? "indianeconomy.give" : "indianeconomy.take")) {
                send(sender, "no-permission");
                return;
            }
            if (args.length != 2 && args.length != 3) {
                send(sender, "usage", "usage", "/ie " + args[0] + " <amount> [confirm]");
                return;
            }
            if (args.length == 2) {
                sender.sendMessage(color("&cThis affects every eligible account. Run /ie " + args[0] + " "
                        + args[1] + " confirm to continue."));
                return;
            }
            if (!args[2].equalsIgnoreCase("confirm")) {
                send(sender, "usage", "usage", "/ie " + args[0] + " <amount> confirm");
                return;
            }
            long amount;
            try {
                amount = Money.toPaise(args[1]);
                if (amount > transactionLimit) {
                    throw new IllegalArgumentException("Amount exceeds transaction limit.");
                }
            } catch (IllegalArgumentException exception) {
                send(sender, "invalid-amount");
                return;
            }
            long delta = give ? amount : -amount;
            databaseExecutor.execute(() -> {
                try {
                    int affected = economy.adjustAll(delta, give ? "ADMIN_GIVEALL" : "ADMIN_REMOVEALL");
                    getServer().getScheduler().runTask(this, () -> sender.sendMessage(color("&aUpdated " + affected
                            + " eligible accounts by ₹" + (give ? "" : "-") + Money.format(amount) + ".")));
                } catch (SQLException exception) {
                    reportDatabaseError(sender, exception);
                }
            });
            return;
        }
        if (List.of("give", "take", "set", "reset", "balance").contains(args[0].toLowerCase())) {
            handleAdminAccount(sender, args);
            return;
        }
        sender.sendMessage(color("&cUnknown admin command. Use /ie help."));
    }

    private void handleAdminAccount(CommandSender sender, String[] args) {
        String action = args[0].toLowerCase();
        String permission = switch (action) {
            case "give" -> "indianeconomy.give";
            case "take" -> "indianeconomy.take";
            case "set", "reset" -> "indianeconomy.set";
            default -> "indianeconomy.admin";
        };
        if (!sender.hasPermission(permission)) {
            send(sender, "no-permission");
            return;
        }
        int expected = action.equals("balance") ? 2
                : action.equals("give") ? 3
                : action.equals("reset") ? 3 : 4;
        if (args.length != expected) {
            send(sender, "usage", "usage", action.equals("balance") || action.equals("reset")
                    ? "/ie " + action + " <player> confirm"
                    : "/ie " + action + " <player> <amount>" + (action.equals("give") ? "" : " confirm"));
            return;
        }
        if (action.equals("reset") && !args[2].equalsIgnoreCase("confirm")
                || !action.equals("give") && !action.equals("balance") && !action.equals("reset")
                && !args[3].equalsIgnoreCase("confirm")) {
            send(sender, "usage", "usage", "/ie " + action + " <player>"
                    + (action.equals("reset") ? " confirm" : " <amount> confirm"));
            return;
        }
        long amount = 0;
        if (!action.equals("balance") && !action.equals("reset")) {
            try {
                amount = Money.toPaise(args[2]);
                if (amount > transactionLimit) {
                    throw new IllegalArgumentException("Amount exceeds transaction limit.");
                }
            } catch (IllegalArgumentException exception) {
                send(sender, "invalid-amount");
                return;
            }
        }
        final long operationAmount = amount;
        String playerName = args[1];
        Player online = getServer().getPlayerExact(playerName);
        UUID knownId = online == null ? null : online.getUniqueId();
        databaseExecutor.execute(() -> {
            try {
                UUID playerId = knownId == null ? economy.findPlayer(playerName) : knownId;
                if (playerId == null && online != null) {
                    playerId = online.getUniqueId();
                }
                if (playerId == null) {
                    getServer().getScheduler().runTask(this,
                            () -> send(sender, "player-not-found", "player", playerName));
                    return;
                }
                long oldBalance = economy.ensureAccount(playerId, playerName);
                boolean success = switch (action) {
                    case "give" -> economy.adjust(playerId, playerName, operationAmount, "ADMIN_GIVE");
                    case "take" -> economy.adjust(playerId, playerName, -operationAmount, "ADMIN_TAKE");
                    case "set" -> economy.setBalance(playerId, playerName, operationAmount);
                    case "reset" -> {
                        economy.resetBalance(playerId, playerName);
                        yield true;
                    }
                    default -> true;
                };
                long resultingBalance = action.equals("balance") ? oldBalance : economy.balance(playerId);
                getServer().getScheduler().runTask(this, () -> {
                    if (!success) {
                        sender.sendMessage(color("&cThe operation failed: insufficient balance or invalid account state."));
                    } else {
                        sender.sendMessage(color("&a" + playerName + "'s balance: ₹" + Money.format(resultingBalance)));
                    }
                });
            } catch (SQLException exception) {
                reportDatabaseError(sender, exception);
            }
        });
    }

    private void help(CommandSender sender) {
        for (String line : messages.getStringList("help")) {
            sender.sendMessage(color(line));
        }
    }

    private void reportDatabaseError(CommandSender sender, SQLException exception) {
        getLogger().severe("Database operation failed: " + exception.getMessage());
        getServer().getScheduler().runTask(this, () -> sender.sendMessage(color("&cDatabase operation failed; check the server log.")));
    }

    private void send(CommandSender sender, String key, String... replacements) {
        String text = messages.getString(key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        sender.sendMessage(color(messages.getString("prefix", "") + text));
    }

    private String color(String text) {
        return text.replace('&', '§');
    }

    @Override
    public void onDisable() {
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
            try {
                if (!databaseExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    getLogger().severe("Database tasks did not finish before shutdown.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                getLogger().severe("Interrupted while waiting for database tasks to finish.");
            }
        }
        if (database != null) {
            try {
                database.close();
            } catch (SQLException exception) {
                getLogger().severe("Could not close economy database: " + exception.getMessage());
            }
        }
    }

    public EconomyService getEconomy() {
        return economy;
    }

    public ExecutorService getDatabaseExecutor() {
        return databaseExecutor;
    }

    public long getTransactionLimit() {
        return transactionLimit;
    }

    public ShopCatalog getShopCatalog() {
        return shopCatalog;
    }

    public long getDailyReward() {
        return dailyReward;
    }

    public long getDailyCooldownHours() {
        return dailyCooldownMillis / 3_600_000L;
    }

    public int getDatabaseQueueLength() {
        return databaseExecutor.getQueue().size();
    }

    public ShopModule getShopModule() {
        return shopModule;
    }

    public MarketplaceService getMarketplace() {
        return marketplace;
    }

    public Database getDatabase() {
        return database;
    }

    public void sendText(CommandSender recipient, String text) {
        recipient.sendMessage(color(text));
    }

    public String colorText(String text) {
        return color(text);
    }

    public void reportDatabaseFailure(String context, SQLException exception) {
        if (exception == null) {
            getLogger().severe(context);
        } else {
            getLogger().severe(context + ": " + exception.getMessage());
        }
    }
}
