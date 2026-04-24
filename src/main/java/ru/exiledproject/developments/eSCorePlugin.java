package ru.exiledproject.developments;

import me.clip.placeholderapi.events.ExpansionRegisterEvent;
import me.clip.placeholderapi.events.ExpansionUnregisterEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.exiledproject.developments.command.PapiCommand;
import ru.exiledproject.developments.api.eScoreApi;
import ru.exiledproject.developments.api.eScoreProvider;
import ru.exiledproject.developments.api.event.eScoreValueChangeEvent;
import ru.exiledproject.developments.command.eScoreCommand;
import ru.exiledproject.developments.core.eScoreApiImpl;
import ru.exiledproject.developments.core.eScoreLogger;
import ru.exiledproject.developments.data.PlayerDataStore;
import ru.exiledproject.developments.data.PlayerValueChange;
import ru.exiledproject.developments.extension.ExtensionManager;
import ru.exiledproject.developments.integration.IntegrationManager;
import ru.exiledproject.developments.placeholder.PlaceholderCompatManager;
import ru.exiledproject.developments.script.ScriptLoadReport;
import ru.exiledproject.developments.script.ScriptManager;
import ru.exiledproject.developments.storage.RedisSyncManager;
import ru.exiledproject.developments.storage.StorageManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class eSCorePlugin extends JavaPlugin implements Listener {
    private eScoreLogger log;
    private PlayerDataStore dataStore;
    private eScoreApiImpl api;
    private ScriptManager scriptManager;
    private IntegrationManager integrationManager;
    private ExtensionManager extensionManager;
    private PlaceholderCompatManager placeholderCompatManager;
    private StorageManager storageManager;
    private RedisSyncManager redisSyncManager;
    private BukkitTask flushTask;
    private BukkitTask pollTask;
    private BukkitTask ecloudProbeTask;
    private BukkitTask deferredIntegrationRefreshTask;
    private ExecutorService asyncExecutor;
    private volatile boolean postWorldReady;
    private String serverId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        log = new eScoreLogger(this);
        serverId = resolveServerId();
        asyncExecutor = Executors.newCachedThreadPool(asyncThreadFactory());
        ensureRuntimeFiles();

        dataStore = new PlayerDataStore(this, log);
        api = new eScoreApiImpl(this, dataStore, log);
        scriptManager = new ScriptManager(this, dataStore, api, log);
        storageManager = new StorageManager(this, dataStore, scriptManager, log);
        placeholderCompatManager = new PlaceholderCompatManager(this, log);
        integrationManager = new IntegrationManager(this, api, dataStore, log);
        redisSyncManager = new RedisSyncManager(this, dataStore, scriptManager, storageManager, log);
        extensionManager = new ExtensionManager(this, api, log);

        api.attachScriptManager(scriptManager);
        api.attachIntegrationManager(integrationManager);
        dataStore.addListener(this::onPlayerValueChange);

        eScoreProvider.set(api);
        getServer().getServicesManager().register(eScoreApi.class, api, this, ServicePriority.Normal);

        registerCommands();
        getServer().getPluginManager().registerEvents(this, this);
        warnAboutDuplicatePlaceholderApi();
        ScriptLoadReport report = reloadRuntime();
        scheduleBackgroundTasks();

        logStartup(report);
    }

    @Override
    public void onDisable() {
        if (flushTask != null) flushTask.cancel();
        if (pollTask != null) pollTask.cancel();
        if (ecloudProbeTask != null) ecloudProbeTask.cancel();
        if (deferredIntegrationRefreshTask != null) deferredIntegrationRefreshTask.cancel();
        shutdownAsyncExecutor();
        if (extensionManager != null) extensionManager.shutdown();
        if (redisSyncManager != null) redisSyncManager.shutdown();
        if (integrationManager != null) integrationManager.shutdown();
        if (placeholderCompatManager != null) placeholderCompatManager.shutdown();
        if (scriptManager != null) scriptManager.shutdown();
        if (storageManager != null) storageManager.shutdown();
        if (api != null) getServer().getServicesManager().unregister(eScoreApi.class, api);
        eScoreProvider.clear();
        HandlerList.unregisterAll((Listener) this);
        log.info("Disabled.");
    }

    public ScriptLoadReport reloadRuntime() {
        reloadConfig();
        ensureRuntimeFiles();
        ScriptLoadReport report = scriptManager.reload();
        storageManager.reload();
        placeholderCompatManager.reload(postWorldReady);
        redisSyncManager.reload();
        integrationManager.reload();
        extensionManager.reload();
        return report;
    }

    public ScriptLoadReport checkScripts() {
        return scriptManager.check();
    }

    public Path getScriptsDirectory() {
        return getDataFolder().toPath().resolve(getConfig().getString("scripts.directory", "scripts"));
    }

    public Path getExtensionsDirectory() {
        return getDataFolder().toPath().resolve("extensions");
    }

    public Path getPlaceholderApiDirectory() {
        return getDataFolder().toPath().resolveSibling("PlaceholderAPI").normalize();
    }

    public Path getPlaceholderApiExpansionsDirectory() {
        return getPlaceholderApiDirectory().resolve("expansions");
    }

    public eScoreLogger log() {
        return log;
    }

    public PlayerDataStore getDataStore() {
        return dataStore;
    }

    public IntegrationManager getIntegrationManager() {
        return integrationManager;
    }

    public ScriptManager getScriptManager() {
        return scriptManager;
    }

    public ExtensionManager getExtensionManager() {
        return extensionManager;
    }

    public PlaceholderCompatManager getPlaceholderCompatManager() {
        return placeholderCompatManager;
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public String getServerId() {
        return serverId;
    }

    public void executeAsync(Runnable runnable) {
        ExecutorService executor = asyncExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.execute(() -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                if (isEnabled()) {
                    String detail = throwable.getMessage();
                    if (detail == null || detail.isBlank()) {
                        detail = throwable.getClass().getSimpleName();
                    }
                    log.warn("Async task failed: " + detail);
                    if (getConfig().getBoolean("debug", false)) {
                        throwable.printStackTrace();
                    }
                }
            }
        });
    }

    public void runSyncIfEnabled(Runnable runnable) {
        if (!isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (isEnabled()) {
                runnable.run();
            }
        });
    }

    private void registerCommands() {
        PluginCommand escoreCommand = getCommand("escore");
        if (escoreCommand == null) {
            log.error("Command 'escore' is missing in plugin.yml.");
        } else {
            eScoreCommand executor = new eScoreCommand(this);
            escoreCommand.setExecutor(executor);
            escoreCommand.setTabCompleter(executor);
        }

        PluginCommand placeholderApiCommand = getCommand("placeholderapi");
        if (placeholderApiCommand == null) {
            log.error("Command 'placeholderapi' is missing in plugin.yml.");
            return;
        }
        var executor = new PapiCommand(this);
        placeholderApiCommand.setExecutor(executor);
        placeholderApiCommand.setTabCompleter(executor);
    }

    private void scheduleBackgroundTasks() {
        long flushInterval = Math.max(20L, getConfig().getLong("storage.flush-check-interval-ticks", 100L));
        long pollInterval = Math.max(20L, getConfig().getLong("storage.database-poll-interval-ticks", 40L));
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> storageManager.flushDueWrites(), flushInterval, flushInterval);
        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> storageManager.pollRemoteUpdates(), pollInterval, pollInterval);
        ecloudProbeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> placeholderCompatManager.probeCloudAvailability(), 1200L, 1200L);
    }

    private void ensureRuntimeFiles() {
        try {
            Files.createDirectories(getScriptsDirectory());
            Files.createDirectories(getExtensionsDirectory());
            Files.createDirectories(getPlaceholderApiDirectory());
            Files.createDirectories(getPlaceholderApiExpansionsDirectory());
        } catch (IOException exception) {
            log.warn("Failed to prepare runtime files: " + exception.getMessage());
        }
    }

    private void onPlayerValueChange(PlayerValueChange change) {
        scriptManager.invalidateStorageKey(change.playerId(), change.key());
        scriptManager.onPlayerValueChange(change);
        storageManager.onValueChange(change);
        redisSyncManager.onValueChange(change);
        integrationManager.onPlayerValueChange(change);
        Bukkit.getPluginManager().callEvent(new eScoreValueChangeEvent(change.playerId(), change.key(), change.oldValue(), change.newValue(), change.origin(), change.updatedAt()));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        storageManager.prefetchPlayer(event.getPlayer().getUniqueId());
        redisSyncManager.prefetchPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        postWorldReady = true;
        log.debug("Server load complete. Running deferred PlaceholderAPI/eSCore integration refresh.");
        scheduleDeferredIntegrationRefresh("server-load");
        if (getConfig().getBoolean("placeholderapi.ecloud.preload-on-startup", true)) {
            placeholderCompatManager.warmupCloudCacheAsync();
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin() == this) {
            return;
        }
        logIntegrationTrace("plugin-enable", event.getPlugin().getName(), "plugin", "eSCore/PAPI retry");
        if (postWorldReady) {
            scheduleDeferredIntegrationRefresh("plugin-enable:" + event.getPlugin().getName());
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == this) {
            return;
        }
        logIntegrationTrace("plugin-disable", event.getPlugin().getName(), "plugin", "eSCore/PAPI state refresh");
        if (postWorldReady) {
            scheduleDeferredIntegrationRefresh("plugin-disable:" + event.getPlugin().getName());
        }
    }

    @EventHandler
    public void onExpansionRegister(ExpansionRegisterEvent event) {
        logIntegrationTrace(
                "expansion-register",
                event.getExpansion().getIdentifier(),
                expansionOrigin(event.getExpansion()),
                "PlaceholderAPI/eSCore"
        );
    }

    @EventHandler
    public void onExpansionUnregister(ExpansionUnregisterEvent event) {
        logIntegrationTrace(
                "expansion-unregister",
                event.getExpansion().getIdentifier(),
                expansionOrigin(event.getExpansion()),
                "PlaceholderAPI/eSCore"
        );
    }

    private String resolveServerId() {
        String configured = getConfig().getString("server-id", "auto");
        if (configured != null && !configured.equalsIgnoreCase("auto") && !configured.isBlank()) {
            return configured;
        }
        return "paper-" + Bukkit.getPort();
    }

    private void warnAboutDuplicatePlaceholderApi() {
        for (var loadedPlugin : Bukkit.getPluginManager().getPlugins()) {
            if (loadedPlugin != this && loadedPlugin.getName().equalsIgnoreCase("PlaceholderAPI")) {
                log.warn("Detected a standalone PlaceholderAPI plugin. eSCore already provides PlaceholderAPI compatibility; remove the extra plugin to avoid conflicts.");
                return;
            }
        }
    }

    private void scheduleDeferredIntegrationRefresh(String reason) {
        if (!isEnabled()) {
            return;
        }
        if (deferredIntegrationRefreshTask != null) {
            deferredIntegrationRefreshTask.cancel();
        }
        deferredIntegrationRefreshTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            deferredIntegrationRefreshTask = null;
            if (!isEnabled()) {
                return;
            }
            placeholderCompatManager.refreshDependentComponents(reason);
            integrationManager.reload();
            placeholderCompatManager.warmupCloudCacheAsync();
        }, 20L);
    }

    private void logIntegrationTrace(String action, String name, String from, String to) {
        if (!getConfig().getBoolean("debug", false)) {
            return;
        }
        log.debug("Integration event: action=" + action + ", name=" + name + ", from=" + from + ", to=" + to);
    }

    private String expansionOrigin(me.clip.placeholderapi.expansion.PlaceholderExpansion expansion) {
        return placeholderCompatManager.getLocalExpansionManager()
                .getExpansionSource(expansion.getIdentifier())
                .map(path -> "jar:" + path.getFileName())
                .orElseGet(() -> {
                    String requiredPlugin = expansion.getRequiredPlugin();
                    if (requiredPlugin != null && !requiredPlugin.isBlank()) {
                        return "plugin:" + requiredPlugin;
                    }
                    return "class:" + expansion.getClass().getName();
                });
    }

    private void shutdownAsyncExecutor() {
        ExecutorService executor = asyncExecutor;
        asyncExecutor = null;
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private ThreadFactory asyncThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "eSCore-Async-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private void logStartup(ScriptLoadReport report) {
        log.info("v" + getPluginMeta().getVersion()
                + "  |  " + Bukkit.getName() + " " + Bukkit.getMinecraftVersion()
                + "  |  server=" + serverId);
        boolean worldGuardCompanion = Bukkit.getPluginManager().getPlugin("eSCoreWorldGuard") != null
                || Bukkit.getPluginManager().getPlugin("WScoreWorldGuard") != null;
        log.success("scripts=" + report.getPlaceholders()
                + "  |  watchers=" + report.getWatchers()
                + "  |  internal_extensions=" + extensionManager.getLoadedCount()
                + "  |  worldguard_companion=" + (worldGuardCompanion ? "yes" : "no")
                + "  |  storage=" + storageManager.getBackendId());
        if (report.hasErrors()) {
            log.warn(report.getErrors().size() + " script error(s) detected during startup. Run /escore check for details.");
        }
        if (!report.getWarnings().isEmpty()) {
            log.warn(report.getWarnings().size() + " script warning(s) detected during startup. Run /escore check for details.");
        }
    }
}
