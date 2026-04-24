package ru.exiledproject.developments.integration;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.exiledproject.developments.core.eScoreApiImpl;
import ru.exiledproject.developments.core.eScoreLogger;
import ru.exiledproject.developments.data.PlayerDataStore;
import ru.exiledproject.developments.data.PlayerValueChange;
import ru.exiledproject.developments.eSCorePlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IntegrationManager {
    private final List<IntegrationAdapter> adapters = new ArrayList<>();
    private final PlaceholderApiIntegration placeholderApiIntegration;
    private final LuckPermsIntegration luckPermsIntegration;

    public IntegrationManager(eSCorePlugin plugin, eScoreApiImpl api, PlayerDataStore dataStore, eScoreLogger log) {
        this.placeholderApiIntegration = new PlaceholderApiIntegration(plugin, api, log);
        this.luckPermsIntegration = new LuckPermsIntegration(plugin, api, dataStore, log);
        adapters.add(placeholderApiIntegration);
        adapters.add(luckPermsIntegration);
    }

    public void reload() {
        adapters.forEach(IntegrationAdapter::reload);
    }

    public void shutdown() {
        adapters.forEach(IntegrationAdapter::shutdown);
    }

    public List<IntegrationStatus> getStatuses() {
        return adapters.stream().map(IntegrationAdapter::getStatus).toList();
    }

    public Map<String, String> getStatusMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (IntegrationAdapter adapter : adapters) {
            IntegrationStatus status = adapter.getStatus();
            map.put(status.id(), status.message());
        }
        return map;
    }

    public List<String> check() {
        List<String> lines = new ArrayList<>();
        for (IntegrationAdapter adapter : adapters) {
            lines.add(adapter.getDisplayName() + ":");
            adapter.check().forEach(line -> lines.add(" - " + line));
        }
        return lines;
    }

    public void onPlayerValueChange(PlayerValueChange change) {
        adapters.forEach(adapter -> adapter.onPlayerValueChange(change));
    }

    public String formatWithPlaceholderApi(OfflinePlayer player, String input, Object fallback) {
        return placeholderApiIntegration.format(player, input, fallback);
    }

    /** Returns the player's primary LuckPerms group or an empty string when unavailable. */
    public String getPrimaryGroup(Player player) {
        return luckPermsIntegration.getPrimaryGroup(player);
    }

    /** Returns whether the player belongs to the given LuckPerms group. */
    public boolean hasGroup(Player player, String group) {
        return luckPermsIntegration.hasGroup(player, group);
    }

    /** Returns the player's LuckPerms prefix or an empty string when unavailable. */
    public String getPrefix(Player player) {
        return luckPermsIntegration.getPrefix(player);
    }

    /** Returns the player's LuckPerms suffix or an empty string when unavailable. */
    public String getSuffix(Player player) {
        return luckPermsIntegration.getSuffix(player);
    }

    public boolean setLuckPermsContext(OfflinePlayer player, String key, Object value) {
        return luckPermsIntegration.setScriptContext(player, key, value);
    }

    public boolean removeLuckPermsContext(OfflinePlayer player, String key) {
        return luckPermsIntegration.removeScriptContext(player, key);
    }

    public void clearLuckPermsContexts(OfflinePlayer player) {
        luckPermsIntegration.clearScriptContexts(player);
    }

    public Object getLuckPermsContext(OfflinePlayer player, String key) {
        return luckPermsIntegration.getScriptContextValue(player, key);
    }
}
