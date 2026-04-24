package ru.willowstudio.wscore.worldguard;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import org.bukkit.plugin.java.JavaPlugin;
import ru.willowstudio.wscore.api.WScoreApi;
import ru.willowstudio.wscore.api.WScoreProvider;

public final class WScoreWorldGuardPlugin extends JavaPlugin {
    public static final String TRACK_FLAG_NAME = "wscore-track";
    private static StateFlag trackFlag;

    private WorldGuardIntegrationService integrationService;

    @Override
    public void onLoad() {
        registerTrackFlag();
    }

    @Override
    public void onEnable() {
        if (trackFlag == null) {
            getLogger().severe("WorldGuard flag '" + TRACK_FLAG_NAME + "' is unavailable. Disabling extension.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        WScoreApi api = resolveApi();
        integrationService = new WorldGuardIntegrationService(this, api, trackFlag);
        integrationService.enable();
        getLogger().info("WorldGuard integration ready. Flag: " + TRACK_FLAG_NAME + ", placeholders: 5, channels: 6");
    }

    @Override
    public void onDisable() {
        if (integrationService != null) {
            integrationService.disable();
            integrationService = null;
        }
    }

    public static StateFlag getTrackFlag() {
        return trackFlag;
    }

    private void registerTrackFlag() {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        try {
            StateFlag flag = new StateFlag(TRACK_FLAG_NAME, true);
            registry.register(flag);
            trackFlag = flag;
        } catch (FlagConflictException exception) {
            Flag<?> existing = registry.get(TRACK_FLAG_NAME);
            if (existing instanceof StateFlag stateFlag) {
                trackFlag = stateFlag;
                getLogger().warning("Using already registered WorldGuard flag '" + TRACK_FLAG_NAME + "'.");
                return;
            }
            getLogger().severe("WorldGuard flag conflict for '" + TRACK_FLAG_NAME + "': expected StateFlag, got "
                    + (existing == null ? "null" : existing.getClass().getName()));
        } catch (Throwable throwable) {
            getLogger().severe("Failed to register WorldGuard flag '" + TRACK_FLAG_NAME + "': " + throwable.getMessage());
        }
    }

    private WScoreApi resolveApi() {
        if (WScoreProvider.isAvailable()) {
            return WScoreProvider.get();
        }
        WScoreApi api = getServer().getServicesManager().load(WScoreApi.class);
        if (api != null) {
            return api;
        }
        throw new IllegalStateException("WScore API is not available.");
    }
}

