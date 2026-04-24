package ru.exiledproject.developments.placeholder;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.cloud.CloudExpansionManager;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.exiledproject.developments.core.eScoreLogger;
import ru.exiledproject.developments.eSCorePlugin;

public final class PlaceholderCompatManager {
    private final PlaceholderAPIPlugin plugin;
    private final eScoreLogger log;
    private final LocalExpansionManager localExpansionManager;
    private final CloudExpansionManager cloudExpansionManager;

    public PlaceholderCompatManager(eSCorePlugin plugin, eScoreLogger log) {
        if (!(plugin instanceof PlaceholderAPIPlugin placeholderAPIPlugin)) {
            throw new IllegalStateException("eSCore main plugin must be PlaceholderAPIPlugin.");
        }
        this.plugin = placeholderAPIPlugin;
        this.log = log;
        this.localExpansionManager = new LocalExpansionManager(placeholderAPIPlugin);
        this.cloudExpansionManager = new CloudExpansionManager(placeholderAPIPlugin, log);
    }

    public void reload(boolean loadExternalExpansions) {
        if (loadExternalExpansions) {
            localExpansionManager.reloadExternal();
            log.success("Placeholder compatibility initialized. External expansions: " + localExpansionManager.getExpansions().size());
        } else {
            log.success("Placeholder compatibility initialized. External expansions will load after server startup.");
        }
        cloudExpansionManager.markStale();
    }

    public void refreshDependentComponents(String reason) {
        localExpansionManager.reloadExternal();
        cloudExpansionManager.markStale();
        if ("server-load".equals(reason)) {
            log.success("External PlaceholderAPI expansions loaded: " + localExpansionManager.getExpansions().size());
        } else {
            log.debug("Placeholder compatibility refresh triggered by " + reason + ". External expansions: " + localExpansionManager.getExpansions().size());
        }
    }

    public void warmupCloudCacheAsync() {
        plugin.executeAsync(() -> {
            CloudExpansionManager.RefreshResult result = cloudExpansionManager.startupWarmup();
            if (result.errors().isEmpty()) {
                log.debug("eCloud warmup complete. Expansions: " + result.totalExpansions());
            }
        });
    }

    public void probeCloudAvailability() {
        cloudExpansionManager.probeSuspendedSources();
    }

    public void shutdown() {
        cloudExpansionManager.shutdown();
        localExpansionManager.shutdown();
    }

    public LocalExpansionManager getLocalExpansionManager() {
        return localExpansionManager;
    }

    public CloudExpansionManager getCloudExpansionManager() {
        return cloudExpansionManager;
    }

    public String setPlaceholders(OfflinePlayer player, String input) {
        return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, input);
    }

    public String setBracketPlaceholders(OfflinePlayer player, String input) {
        return me.clip.placeholderapi.PlaceholderAPI.setBracketPlaceholders(player, input);
    }

    public String setRelationalPlaceholders(Player one, Player two, String input) {
        return me.clip.placeholderapi.PlaceholderAPI.setRelationalPlaceholders(one, two, input);
    }
}
