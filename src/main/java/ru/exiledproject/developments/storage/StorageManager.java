package ru.exiledproject.developments.storage;

import org.bukkit.Bukkit;
import ru.exiledproject.developments.core.eScoreLogger;
import ru.exiledproject.developments.data.ChangeOrigin;
import ru.exiledproject.developments.data.PersistedValue;
import ru.exiledproject.developments.data.PlayerDataStore;
import ru.exiledproject.developments.data.PlayerValueChange;
import ru.exiledproject.developments.eSCorePlugin;
import ru.exiledproject.developments.script.PersistenceMode;
import ru.exiledproject.developments.script.ScriptManager;
import ru.exiledproject.developments.script.SyncMode;
import ru.exiledproject.developments.script.eScoreScript;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StorageManager {
    private final eSCorePlugin plugin;
    private final PlayerDataStore dataStore;
    private final ScriptManager scriptManager;
    private final eScoreLogger log;

    private final Map<ValueKey, PendingWrite> pendingWrites = new ConcurrentHashMap<>();

    private StorageBackend backend;
    private long lastPollTimestamp;

    public StorageManager(eSCorePlugin plugin, PlayerDataStore dataStore, ScriptManager scriptManager, eScoreLogger log) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.scriptManager = scriptManager;
        this.log = log;
    }

    public void reload() {
        try {
            if (backend != null) {
                backend.close();
            }
            backend = createBackend();
            backend.initialize();
            Set<String> persistentKeys = scriptManager.getPersistentKeys();
            Map<UUID, Map<String, PersistedValue>> snapshot = persistentKeys.isEmpty()
                    ? Map.of()
                    : filterSnapshot(backend.loadAll(Set.of()));
            dataStore.loadSnapshot(snapshot);
            pendingWrites.clear();
            lastPollTimestamp = System.currentTimeMillis();
            log.success("Storage backend initialized: " + backend.getId());
        } catch (Exception exception) {
            log.error("Failed to initialize storage backend: " + exception.getMessage());
        }
    }

    public void shutdown() {
        flushAll();
        if (backend != null) {
            try {
                backend.close();
            } catch (Exception exception) {
                log.warn("Failed to close storage backend: " + exception.getMessage());
            }
        }
    }

    public void onValueChange(PlayerValueChange change) {
        if (change.origin() != ChangeOrigin.LOCAL) {
            return;
        }

        eScoreScript script = scriptManager.getPrimaryScriptByStorageKey(change.key());
        if (script == null || script.persistenceMode() != PersistenceMode.BACKEND || backend == null) {
            return;
        }

        long flushAt = change.updatedAt() + Math.max(0L, script.flushIntervalMillis());
        pendingWrites.put(new ValueKey(change.playerId(), change.key()), new PendingWrite(new StorageWrite(change.playerId(), change.key(), change.newValue(), change.updatedAt()), flushAt));
    }

    public void flushDueWrites() {
        if (backend == null || pendingWrites.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<ValueKey> dueKeys = new ArrayList<>();
        List<StorageWrite> writes = new ArrayList<>();
        pendingWrites.forEach((key, pendingWrite) -> {
            if (pendingWrite.flushAt() <= now) {
                dueKeys.add(key);
                writes.add(pendingWrite.write());
            }
        });
        if (writes.isEmpty()) {
            return;
        }
        try {
            backend.writeBatch(writes);
            dueKeys.forEach(pendingWrites::remove);
        } catch (Exception exception) {
            log.warn("Failed to flush storage writes: " + exception.getMessage());
        }
    }

    public void flushAll() {
        if (backend == null || pendingWrites.isEmpty()) {
            return;
        }
        List<StorageWrite> writes = pendingWrites.values().stream().map(PendingWrite::write).toList();
        try {
            backend.writeBatch(writes);
            pendingWrites.clear();
        } catch (Exception exception) {
            log.warn("Failed to flush all storage writes: " + exception.getMessage());
        }
    }

    public void pollRemoteUpdates() {
        if (backend == null || !backend.supportsPolling() || !plugin.getConfig().getBoolean("sync.database-fallback", true)) {
            return;
        }
        try {
            long previousPoll = lastPollTimestamp;
            lastPollTimestamp = System.currentTimeMillis();
            for (StorageWrite update : backend.pollUpdates(previousPoll, Set.of())) {
                if (!scriptManager.isTrackedStorageKey(update.key())) {
                    continue;
                }
                dataStore.setValue(update.playerId(), update.key(), update.value(), ChangeOrigin.REMOTE_DATABASE, update.updatedAt());
            }
        } catch (Exception exception) {
            log.warn("Failed to poll remote updates: " + exception.getMessage());
        }
    }

    public void prefetchPlayer(UUID playerId) {
        Set<String> persistentKeys = scriptManager.getPersistentKeys();
        if (backend == null || persistentKeys.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, PersistedValue> values = filterPlayerValues(backend.loadPlayer(playerId, Set.of()));
                values.forEach((key, value) -> dataStore.setValue(playerId, key, value.value(), ChangeOrigin.REMOTE_DATABASE, value.updatedAt()));
            } catch (Exception exception) {
                log.debug("Prefetch failed for " + playerId + ": " + exception.getMessage());
            }
        });
    }

    public String getBackendId() {
        return backend == null ? "none" : backend.getId();
    }

    public boolean isSharedBackend() {
        return backend != null && backend.isShared();
    }

    public SyncMode resolveSyncMode(eScoreScript script) {
        return switch (script.syncMode()) {
            case NONE, REDIS, DATABASE -> script.syncMode();
            case AUTO -> plugin.getConfig().getBoolean("sync.redis.enabled", false) ? SyncMode.REDIS : isSharedBackend() ? SyncMode.DATABASE : SyncMode.NONE;
        };
    }

    private StorageBackend createBackend() throws Exception {
        String backendId = plugin.getConfig().getString("storage.backend", "sqlite").toLowerCase();
        return switch (backendId) {
            case "json" -> new JsonStorageBackend(plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString("storage.json.file", "player-data.json")));
            case "sqlite" -> {
                var path = plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString("storage.sqlite.file", "escore.db"));
                Files.createDirectories(path.getParent());
                yield new SqliteStorageBackend(path, plugin.getConfig().getString("storage.sqlite.table", "escore_values"));
            }
            case "mysql" -> new MysqlStorageBackend(
                    plugin.getConfig().getString("storage.mysql.host", "127.0.0.1"),
                    plugin.getConfig().getInt("storage.mysql.port", 3306),
                    plugin.getConfig().getString("storage.mysql.database", "escore"),
                    plugin.getConfig().getString("storage.mysql.username", "root"),
                    plugin.getConfig().getString("storage.mysql.password", ""),
                    plugin.getConfig().getString("storage.mysql.parameters", "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC"),
                    plugin.getConfig().getString("storage.mysql.table", "escore_values")
            );
            default -> throw new IllegalArgumentException("Unknown storage backend: " + backendId);
        };
    }

    private Map<UUID, Map<String, PersistedValue>> filterSnapshot(Map<UUID, Map<String, PersistedValue>> snapshot) {
        Map<UUID, Map<String, PersistedValue>> filtered = new ConcurrentHashMap<>();
        snapshot.forEach((playerId, values) -> {
            Map<String, PersistedValue> filteredValues = filterPlayerValues(values);
            if (!filteredValues.isEmpty()) {
                filtered.put(playerId, filteredValues);
            }
        });
        return filtered;
    }

    private Map<String, PersistedValue> filterPlayerValues(Map<String, PersistedValue> values) {
        Map<String, PersistedValue> filtered = new ConcurrentHashMap<>();
        values.forEach((key, value) -> {
            if (scriptManager.isTrackedStorageKey(key)) {
                filtered.put(key, value);
            }
        });
        return filtered;
    }

    private record ValueKey(UUID playerId, String key) {
    }

    private record PendingWrite(StorageWrite write, long flushAt) {
    }
}
