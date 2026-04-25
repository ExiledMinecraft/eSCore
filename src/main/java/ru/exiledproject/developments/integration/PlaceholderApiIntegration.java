package ru.exiledproject.developments.integration;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;
import ru.exiledproject.developments.core.eScoreApiImpl;
import ru.exiledproject.developments.core.eScoreLogger;
import ru.exiledproject.developments.eSCorePlugin;

import java.util.ArrayList;
import java.util.List;

public final class PlaceholderApiIntegration implements IntegrationAdapter {
    private final eSCorePlugin plugin;
    private final eScoreApiImpl api;
    private final eScoreLogger log;

    private final List<eScorePlaceholderExpansion> expansions = new ArrayList<>();
    private IntegrationStatus status = new IntegrationStatus("placeholderapi", "PlaceholderAPI", false, false, "Pending initialization.");

    public PlaceholderApiIntegration(eSCorePlugin plugin, eScoreApiImpl api, eScoreLogger log) {
        this.plugin = plugin;
        this.api = api;
        this.log = log;
    }

    @Override
    public String getId() {
        return "placeholderapi";
    }

    @Override
    public String getDisplayName() {
        return "PlaceholderAPI";
    }

    @Override
    public void reload() {
        shutdown();
        boolean escoreRegistered = registerExpansion("escore");
        boolean legacyRegistered = registerExpansion("wscore");
        boolean registered = escoreRegistered || legacyRegistered;
        int loadedExpansions = plugin.getPlaceholderCompatManager().getLocalExpansionManager().getExpansions().size();
        status = new IntegrationStatus(
                getId(),
                getDisplayName(),
                true,
                registered,
                registered
                        ? "Core provider active. Registered %escore_*% and legacy %wscore_*%, loaded " + loadedExpansions + " external expansion(s)."
                        : "Internal eSCore PlaceholderAPI bridge failed to register."
        );
        if (registered) {
            log.success("PlaceholderAPI bridge active.");
        }
    }

    @Override
    public void shutdown() {
        for (eScorePlaceholderExpansion expansion : expansions) {
            expansion.unregister();
        }
        expansions.clear();
    }

    @Override
    public IntegrationStatus getStatus() {
        return status;
    }

    @Override
    public List<String> check() {
        List<String> lines = new ArrayList<>();
        lines.add(status.message());
        if (status.active()) {
            lines.add("Identifiers: %escore_<placeholder>%, legacy %wscore_<placeholder>%");
            lines.add("Scope suffixes: __server for current server, __all for all servers (default).");
            lines.add("Loaded expansions: " + plugin.getPlaceholderCompatManager().getLocalExpansionManager().getExpansions().size());
        }
        return lines;
    }

    public String format(OfflinePlayer player, String input, Object fallback) {
        if (input == null) {
            return fallback == null ? "" : String.valueOf(fallback);
        }
        if (!status.active()) {
            return fallback == null ? input : String.valueOf(fallback);
        }
        return PlaceholderAPI.setPlaceholders(player, input);
    }

    private boolean registerExpansion(String identifier) {
        eScorePlaceholderExpansion expansion = new eScorePlaceholderExpansion(plugin, api, identifier);
        if (!expansion.register()) {
            return false;
        }
        expansions.add(expansion);
        return true;
    }
}
