package ru.exiledproject.developments.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StorageManager {
    private final eSCorePlugin plugin;
    private final PlayerDataStore dataStore;
    private final ScriptManager scriptManager;
    private final eScoreLogger log;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<ValueKey, PendingWrite> pendingWrites = new ConcurrentHashMap<>();
    private final Object backendLock = new Object();

    private volatile StorageBackend backend;
    private long lastPollTimestamp;

    public StorageManager(eSCorePlugin plugin, PlayerDataStore dataStore, ScriptManager scriptManager, eScoreLogger log) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.scriptManager = scriptManager;
        this.log = log;
    }

    public void reload() {
        StorageBackend candidate = null;
        try {
            flushAll();
            candidate = createBackend();
            candidate.initialize();
            Set<String> persistentKeys = scriptManager.getPersistentKeys();
            Map<UUID, Map<String, PersistedValue>> snapshot = persistentKeys.isEmpty()
                    ? Map.of()
                    : filterSnapshot(candidate.loadAll(Set.of()));
            StorageBackend previous;
            synchronized (backendLock) {
                previous = backend;
                backend = candidate;
                candidate = null;
            }
            closeBackend(previous);
            dataStore.loadSnapshot(snapshot);
            pendingWrites.clear();
            lastPollTimestamp = System.currentTimeMillis();
            log.success("Storage backend initialized: " + backend.getId());
        } catch (Exception exception) {
            log.error("Failed to initialize storage backend: " + exception.getMessage());
            closeBackend(candidate);
        }
    }

    public void shutdown() {
        flushAll();
        StorageBackend current;
        synchronized (backendLock) {
            current = backend;
            backend = null;
        }
        closeBackend(current);
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
        PersistedValue current = dataStore.getPersistedValue(change.playerId(), change.key());
        long expiresAt = current == null ? 0L : current.expiresAt();
        pendingWrites.put(new ValueKey(change.playerId(), change.key()), new PendingWrite(new StorageWrite(change.playerId(), change.key(), change.newValue(), change.updatedAt(), expiresAt), flushAt));
    }

    public void flushDueWrites() {
        if (backend == null || pendingWrites.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<ValueKey, PendingWrite> dueWrites = new ConcurrentHashMap<>();
        List<StorageWrite> writes = new ArrayList<>();
        pendingWrites.forEach((key, pendingWrite) -> {
            if (pendingWrite.flushAt() <= now) {
                dueWrites.put(key, pendingWrite);
                writes.add(pendingWrite.write());
            }
        });
        if (writes.isEmpty()) {
            return;
        }
        try {
            synchronized (backendLock) {
                if (backend == null) {
                    return;
                }
                backend.writeBatch(writes);
            }
            dueWrites.forEach((key, pendingWrite) -> pendingWrites.remove(key, pendingWrite));
        } catch (Exception exception) {
            log.warn("Failed to flush storage writes: " + exception.getMessage());
        }
    }

    public void flushAll() {
        if (backend == null || pendingWrites.isEmpty()) {
            return;
        }
        Map<ValueKey, PendingWrite> writesByKey = Map.copyOf(pendingWrites);
        List<StorageWrite> writes = writesByKey.values().stream().map(PendingWrite::write).toList();
        try {
            synchronized (backendLock) {
                if (backend == null) {
                    return;
                }
                backend.writeBatch(writes);
            }
            writesByKey.forEach((key, pendingWrite) -> pendingWrites.remove(key, pendingWrite));
        } catch (Exception exception) {
            log.warn("Failed to flush all storage writes: " + exception.getMessage());
        }
    }

    public void pollRemoteUpdates() {
        if (backend == null || !backend.supportsPolling() || !plugin.getConfig().getBoolean("sync.database-fallback", true)) {
            return;
        }
        try {
            long overlapMillis = Math.max(0L, plugin.getConfig().getLong("sync.database-poll-overlap-millis", 5000L));
            long previousPoll = Math.max(0L, lastPollTimestamp - overlapMillis);
            long pollUntil = System.currentTimeMillis();
            List<StorageWrite> updates;
            synchronized (backendLock) {
                if (backend == null) {
                    return;
                }
                updates = backend.pollUpdates(previousPoll, Set.of());
            }
            lastPollTimestamp = pollUntil;
            plugin.runSyncIfEnabled(() -> updates.stream()
                    .filter(update -> scriptManager.isTrackedStorageKey(update.key()))
                    .forEach(update -> dataStore.setValue(update.playerId(), update.key(), update.value(), ChangeOrigin.REMOTE_DATABASE, update.updatedAt(), update.expiresAt())));
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
                Map<String, PersistedValue> values;
                synchronized (backendLock) {
                    if (backend == null) {
                        return;
                    }
                    values = filterPlayerValues(backend.loadPlayer(playerId, Set.of()));
                }
                plugin.runSyncIfEnabled(() -> values.forEach((key, value) -> dataStore.setValue(playerId, key, value.value(), ChangeOrigin.REMOTE_DATABASE, value.updatedAt(), value.expiresAt())));
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

    public int getPendingWriteCount() {
        return pendingWrites.size();
    }

    public long getLastPollTimestamp() {
        return lastPollTimestamp;
    }

    public List<String> getStatusLines() {
        return List.of(
                "Backend: " + getBackendId() + ", shared=" + isSharedBackend(),
                "Pending writes: " + getPendingWriteCount(),
                "Tracked persistent keys: " + scriptManager.getPersistentKeys().size(),
                "Last database poll: " + (lastPollTimestamp == 0L ? "never" : Long.toString(lastPollTimestamp))
        );
    }

    public Path exportSnapshot(Path file) throws Exception {
        flushAll();
        Map<UUID, Map<String, PersistedValue>> values = dataStore.snapshot(Set.of());
        Path target = file.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".escore-export-", ".tmp");
        Files.writeString(temporary, gson.toJson(new ExportDocument(1, System.currentTimeMillis(), values)));
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    public ImportDocument readImport(Path file) throws Exception {
        ImportDocument document = gson.fromJson(Files.readString(file), ImportDocument.class);
        if (document == null || document.values() == null) {
            throw new IllegalArgumentException("Invalid eSCore storage export.");
        }
        if (document.schemaVersion() < 1 || document.schemaVersion() > 1) {
            throw new IllegalArgumentException("Unsupported storage export schema: " + document.schemaVersion());
        }
        return document;
    }

    public int applyImport(ImportDocument document) {
        int imported = 0;
        for (Map.Entry<UUID, Map<String, PersistedValue>> player : document.values().entrySet()) {
            for (Map.Entry<String, PersistedValue> value : player.getValue().entrySet()) {
                PersistedValue persisted = value.getValue();
                if (persisted == null || persisted.isExpired(System.currentTimeMillis())) {
                    continue;
                }
                dataStore.setValue(player.getKey(), value.getKey(), persisted.value(),
                        ChangeOrigin.LOCAL, Math.max(System.currentTimeMillis(), persisted.updatedAt()), persisted.expiresAt());
                imported++;
            }
        }
        return imported;
    }

    public SyncMode resolveSyncMode(eScoreScript script) {
        return switch (script.syncMode()) {
            case NONE, REDIS, DATABASE -> script.syncMode();
            case AUTO -> plugin.getConfig().getBoolean("sync.redis.enabled", false) ? SyncMode.REDIS : isSharedBackend() ? SyncMode.DATABASE : SyncMode.NONE;
        };
    }

    private StorageBackend createBackend() throws Exception {
        String backendId = plugin.getConfig().getString("storage.backend", "sqlite").toLowerCase(Locale.ROOT);
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

    private void closeBackend(StorageBackend storageBackend) {
        if (storageBackend == null) {
            return;
        }
        try {
            storageBackend.close();
        } catch (Exception exception) {
            log.warn("Failed to close storage backend: " + exception.getMessage());
        }
    }

    private record ValueKey(UUID playerId, String key) {
    }

    private record PendingWrite(StorageWrite write, long flushAt) {
    }

    private record ExportDocument(int schemaVersion, long exportedAt, Map<UUID, Map<String, PersistedValue>> values) {
    }

    public record ImportDocument(int schemaVersion, long exportedAt, Map<UUID, Map<String, PersistedValue>> values) {
    }
}
