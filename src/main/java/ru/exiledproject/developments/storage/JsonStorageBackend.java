package ru.exiledproject.developments.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import ru.exiledproject.developments.data.PersistedValue;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class JsonStorageBackend implements StorageBackend {
    private static final Type FILE_TYPE = new TypeToken<Map<String, Map<String, JsonValue>>>() {
    }.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<UUID, Map<String, PersistedValue>> snapshot = new ConcurrentHashMap<>();

    public JsonStorageBackend(Path file) {
        this.file = file;
    }

    @Override
    public String getId() {
        return "json";
    }

    @Override
    public synchronized void initialize() throws Exception {
        snapshot.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Map<String, JsonValue>> raw = gson.fromJson(reader, FILE_TYPE);
            if (raw == null) {
                return;
            }
            raw.forEach((playerId, values) -> {
                try {
                    UUID uuid = UUID.fromString(playerId);
                    Map<String, PersistedValue> playerValues = new ConcurrentHashMap<>();
                    values.forEach((key, value) -> playerValues.put(key, new PersistedValue(value.value(), value.updatedAt())));
                    snapshot.put(uuid, playerValues);
                } catch (IllegalArgumentException ignored) {
                }
            });
        }
    }

    @Override
    public synchronized Map<UUID, Map<String, PersistedValue>> loadAll(Set<String> keys) {
        Map<UUID, Map<String, PersistedValue>> result = new LinkedHashMap<>();
        snapshot.forEach((playerId, values) -> {
            Map<String, PersistedValue> filtered = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (keys.isEmpty() || keys.contains(key)) {
                    filtered.put(key, value);
                }
            });
            if (!filtered.isEmpty()) {
                result.put(playerId, filtered);
            }
        });
        return result;
    }

    @Override
    public synchronized Map<String, PersistedValue> loadPlayer(UUID playerId, Set<String> keys) {
        Map<String, PersistedValue> values = snapshot.get(playerId);
        if (values == null) {
            return Map.of();
        }
        Map<String, PersistedValue> filtered = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (keys.isEmpty() || keys.contains(key)) {
                filtered.put(key, value);
            }
        });
        return filtered;
    }

    @Override
    public synchronized void writeBatch(List<StorageWrite> writes) throws Exception {
        for (StorageWrite write : writes) {
            Map<String, PersistedValue> playerValues = snapshot.computeIfAbsent(write.playerId(), ignored -> new ConcurrentHashMap<>());
            if (write.isDelete()) {
                playerValues.remove(write.key());
                if (playerValues.isEmpty()) {
                    snapshot.remove(write.playerId());
                }
            } else {
                playerValues.put(write.key(), new PersistedValue(write.value(), write.updatedAt()));
            }
        }
        Files.createDirectories(file.getParent());
        Map<String, Map<String, JsonValue>> raw = new LinkedHashMap<>();
        snapshot.forEach((playerId, values) -> {
            Map<String, JsonValue> playerValues = new LinkedHashMap<>();
            values.forEach((key, value) -> playerValues.put(key, new JsonValue(value.value(), value.updatedAt())));
            raw.put(playerId.toString(), playerValues);
        });
        try (Writer writer = Files.newBufferedWriter(file)) {
            gson.toJson(raw, FILE_TYPE, writer);
        }
    }

    @Override
    public boolean isShared() {
        return false;
    }

    @Override
    public void close() {
    }

    private record JsonValue(Object value, long updatedAt) {
    }
}
