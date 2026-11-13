package io.github.shivafamily146.indianeconomy;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.SQLException;

public final class AccountListener implements Listener {
    private final IndianEconomyPlugin plugin;

    public AccountListener(IndianEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getDatabaseExecutor().execute(() -> {
            try {
                plugin.getEconomy().ensureAccount(event.getPlayer().getUniqueId(), event.getPlayer().getName());
            } catch (SQLException exception) {
                plugin.getLogger().severe("Could not initialize player account: " + exception.getMessage());
            }
        });
    }
}
