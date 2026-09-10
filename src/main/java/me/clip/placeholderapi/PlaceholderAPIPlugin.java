package me.clip.placeholderapi;

import me.clip.placeholderapi.expansion.Version;
import me.clip.placeholderapi.expansion.cloud.CloudExpansionManager;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import org.bukkit.Bukkit;
import ru.exiledproject.developments.eSCorePlugin;

@SuppressWarnings("deprecation")
public final class PlaceholderAPIPlugin extends eSCorePlugin {
    private static final String EMBEDDED_PAPI_VERSION = "2.11.6";
    private static PlaceholderAPIPlugin instance;

    public static PlaceholderAPIPlugin getInstance() {
        return instance;
    }

    public static Version getServerVersion() {
        String version = Bukkit.getBukkitVersion();
        int dash = version.indexOf('-');
        if (dash > 0) {
            version = version.substring(0, dash);
        }
        return new Version(version, false);
    }

    public static String getEmbeddedPapiVersion() {
        return EMBEDDED_PAPI_VERSION;
    }

    @Override
    public void onEnable() {
        instance = this;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        try {
            super.onDisable();
        } finally {
            PlaceholderAPI.clearLegacyHooks();
            instance = null;
        }
    }

    public LocalExpansionManager getLocalExpansionManager() {
        return getPlaceholderCompatManager().getLocalExpansionManager();
    }

    public CloudExpansionManager getCloudExpansionManager() {
        return getPlaceholderCompatManager().getCloudExpansionManager();
    }

    // Legacy compatibility for old expansions that still call these static helpers.
    public static String booleanTrue() {
        return "yes";
    }

    public static String booleanFalse() {
        return "no";
    }

    @Deprecated
    public static String getConfigBooleanAsString(boolean value) {
        return value ? booleanTrue() : booleanFalse();
    }
}
