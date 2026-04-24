package ru.exiledproject.developments.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;

public final class MysqlStorageBackend extends AbstractJdbcStorageBackend {
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String parameters;

    public MysqlStorageBackend(String host, int port, String database, String username, String password, String parameters, String tableName) {
        super(tableName);
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.parameters = parameters;
    }

    @Override
    public String getId() {
        return "mysql";
    }

    @Override
    protected String jdbcUrl() {
        String suffix = parameters == null || parameters.isBlank() ? "" : (parameters.startsWith("?") ? parameters : "?" + parameters);
        return "jdbc:mysql://" + host + ":" + port + "/" + database + suffix;
    }

    @Override
    protected Connection openConnection() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        return DriverManager.getConnection(jdbcUrl(), properties);
    }

    @Override
    protected String upsertSql() {
        return "INSERT INTO " + tableName() + " (player_uuid, data_key, value_json, updated_at) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE value_json = VALUES(value_json), updated_at = VALUES(updated_at)";
    }

    @Override
    protected void bindUpsertTail(PreparedStatement statement, StorageWrite write) {
    }

    @Override
    public boolean isShared() {
        return true;
    }
}
