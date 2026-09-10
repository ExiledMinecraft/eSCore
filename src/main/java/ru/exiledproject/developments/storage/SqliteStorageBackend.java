package ru.exiledproject.developments.storage;

import java.nio.file.Path;
import java.sql.PreparedStatement;

public final class SqliteStorageBackend extends AbstractJdbcStorageBackend {
    private final Path databaseFile;

    public SqliteStorageBackend(Path databaseFile, String tableName) {
        super(tableName);
        this.databaseFile = databaseFile;
    }

    @Override
    public String getId() {
        return "sqlite";
    }

    @Override
    protected String jdbcUrl() {
        return "jdbc:sqlite:" + databaseFile.toAbsolutePath();
    }

    @Override
    protected String upsertSql() {
        return "INSERT INTO " + tableName() + " (player_uuid, data_key, value_json, updated_at, expires_at) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(player_uuid, data_key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at, expires_at = excluded.expires_at " +
                "WHERE excluded.updated_at >= " + tableName() + ".updated_at";
    }

    @Override
    protected void bindUpsertTail(PreparedStatement statement, StorageWrite write) {
    }

    @Override
    public boolean isShared() {
        return false;
    }
}
