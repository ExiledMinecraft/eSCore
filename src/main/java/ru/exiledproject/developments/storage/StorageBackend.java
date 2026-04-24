package ru.exiledproject.developments.storage;

import ru.exiledproject.developments.data.PersistedValue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface StorageBackend extends AutoCloseable {
    String getId();

    void initialize() throws Exception;

    Map<UUID, Map<String, PersistedValue>> loadAll(Set<String> keys) throws Exception;

    default Map<String, PersistedValue> loadPlayer(UUID playerId, Set<String> keys) throws Exception {
        return Map.of();
    }

    void writeBatch(List<StorageWrite> writes) throws Exception;

    default List<StorageWrite> pollUpdates(long sinceTimestamp, Set<String> keys) throws Exception {
        return List.of();
    }

    default boolean supportsPolling() {
        return false;
    }

    boolean isShared();

    @Override
    void close() throws Exception;
}
