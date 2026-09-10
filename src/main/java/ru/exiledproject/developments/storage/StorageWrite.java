package ru.exiledproject.developments.storage;

import java.util.UUID;

public record StorageWrite(UUID playerId, String key, Object value, long updatedAt, long expiresAt) {
    public StorageWrite(UUID playerId, String key, Object value, long updatedAt) {
        this(playerId, key, value, updatedAt, 0L);
    }

    public boolean isDelete() {
        return value == null;
    }
}
