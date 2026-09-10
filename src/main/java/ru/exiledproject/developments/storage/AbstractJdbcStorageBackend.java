package ru.exiledproject.developments.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import ru.exiledproject.developments.data.PersistedValue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public abstract class AbstractJdbcStorageBackend implements StorageBackend {
    private final Gson gson = new GsonBuilder().create();
    private final String tableName;

    protected AbstractJdbcStorageBackend(String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid storage table name: " + tableName);
        }
        this.tableName = tableName;
    }

    protected abstract String jdbcUrl();

    protected final String tableName() {
        return tableName;
    }

    protected Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl());
    }

    @Override
    public void initialize() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                            "player_uuid VARCHAR(36) NOT NULL, " +
                            "data_key VARCHAR(128) NOT NULL, " +
                            "value_json TEXT NULL, " +
                            "updated_at BIGINT NOT NULL, " +
                            "expires_at BIGINT NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY (player_uuid, data_key))"
            );
            try {
                statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN expires_at BIGINT NOT NULL DEFAULT 0");
            } catch (java.sql.SQLException ignored) {
                // Existing installations already containing the column are current.
            }
        }
    }

    @Override
    public Map<UUID, Map<String, PersistedValue>> loadAll(Set<String> keys) throws Exception {
        Map<UUID, Map<String, PersistedValue>> result = new LinkedHashMap<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT player_uuid, data_key, value_json, updated_at, expires_at FROM " + tableName);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String key = resultSet.getString("data_key");
                if (!keys.isEmpty() && !keys.contains(key)) {
                    continue;
                }
                UUID playerId = UUID.fromString(resultSet.getString("player_uuid"));
                result.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>()).put(
                        key,
                        new PersistedValue(deserialize(resultSet.getString("value_json")), resultSet.getLong("updated_at"), resultSet.getLong("expires_at"))
                );
            }
        }
        return result;
    }

    @Override
    public Map<String, PersistedValue> loadPlayer(UUID playerId, Set<String> keys) throws Exception {
        Map<String, PersistedValue> result = new LinkedHashMap<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT data_key, value_json, updated_at, expires_at FROM " + tableName + " WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String key = resultSet.getString("data_key");
                    if (!keys.isEmpty() && !keys.contains(key)) {
                        continue;
                    }
                    result.put(key, new PersistedValue(deserialize(resultSet.getString("value_json")), resultSet.getLong("updated_at"), resultSet.getLong("expires_at")));
                }
            }
        }
        return result;
    }

    @Override
    public void writeBatch(List<StorageWrite> writes) throws Exception {
        if (writes.isEmpty()) {
            return;
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement(upsertSql())) {
                for (StorageWrite write : writes) {
                    // Keep null values as tombstones so database polling can propagate deletions.
                    upsert.setString(1, write.playerId().toString());
                    upsert.setString(2, write.key());
                    upsert.setString(3, gson.toJson(write.value()));
                    upsert.setLong(4, write.updatedAt());
                    upsert.setLong(5, write.expiresAt());
                    bindUpsertTail(upsert, write);
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
            connection.commit();
        }
    }

    @Override
    public List<StorageWrite> pollUpdates(long sinceTimestamp, Set<String> keys) throws Exception {
        List<StorageWrite> updates = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT player_uuid, data_key, value_json, updated_at, expires_at FROM " + tableName + " WHERE updated_at > ? ORDER BY updated_at ASC")) {
            statement.setLong(1, sinceTimestamp);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String key = resultSet.getString("data_key");
                    if (!keys.isEmpty() && !keys.contains(key)) {
                        continue;
                    }
                    updates.add(new StorageWrite(
                            UUID.fromString(resultSet.getString("player_uuid")),
                            key,
                            deserialize(resultSet.getString("value_json")),
                            resultSet.getLong("updated_at"),
                            resultSet.getLong("expires_at")
                    ));
                }
            }
        }
        return updates;
    }

    @Override
    public boolean supportsPolling() {
        return isShared();
    }

    @Override
    public void close() {
    }

    protected abstract String upsertSql();

    protected abstract void bindUpsertTail(PreparedStatement statement, StorageWrite write) throws Exception;

    private Object deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonElement element = JsonParser.parseString(raw);
        return gson.fromJson(element, Object.class);
    }
}
