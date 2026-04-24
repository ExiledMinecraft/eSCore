package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public abstract class PlaceholderHook {
    public String onRequest(OfflinePlayer player, String params) {
        if (player != null && player.isOnline()) {
            return onPlaceholderRequest(player.getPlayer(), params);
        }
        return onPlaceholderRequest(null, params);
    }

    public String onPlaceholderRequest(Player player, String params) {
        return null;
    }
}
