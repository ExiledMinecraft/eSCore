package ru.exiledproject.developments.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.exiledproject.developments.core.eScoreApiImpl;
import ru.exiledproject.developments.eSCorePlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class eScorePlaceholderExpansion extends PlaceholderExpansion {
    private final eSCorePlugin plugin;
    private final eScoreApiImpl api;
    private final String identifier;

    public eScorePlaceholderExpansion(eSCorePlugin plugin, eScoreApiImpl api, String identifier) {
        this.plugin = plugin;
        this.api = api;
        this.identifier = identifier.toLowerCase(Locale.ROOT);
    }

    @Override
    public @NotNull String getIdentifier() {
        return identifier;
    }

    @Override
    public @NotNull String getAuthor() {
        return "WillowStudio";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String normalized = params.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("data_")) {
            if (player == null) {
                return "";
            }
            Object value = api.getPlayerValue(player.getUniqueId(), normalized.substring("data_".length()), "");
            return String.valueOf(value);
        }
        if (normalized.startsWith("top_")) {
            return topValue(normalized);
        }
        return api.resolvePlaceholder(player, normalized).orElse("");
    }

    private String topValue(String input) {
        String[] parts = input.split("_");
        if (parts.length < 4) {
            return "";
        }

        String type = parts[parts.length - 1];
        String positionRaw = parts[parts.length - 2];
        int position;
        try {
            position = Integer.parseInt(positionRaw);
        } catch (NumberFormatException exception) {
            return "";
        }

        String key = String.join("_", List.of(parts).subList(1, parts.length - 2));
        List<Map.Entry<UUID, Double>> topEntries = plugin.getDataStore().getTopNumbers(key, position);
        if (topEntries.size() < position || position < 1) {
            return "";
        }

        Map.Entry<UUID, Double> entry = topEntries.get(position - 1);
        if ("name".equals(type)) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(entry.getKey());
            return offlinePlayer.getName() == null ? entry.getKey().toString() : offlinePlayer.getName();
        }
        if ("value".equals(type)) {
            double value = entry.getValue();
            return value % 1D == 0D ? Long.toString((long) value) : Double.toString(value);
        }
        return "";
    }
}
