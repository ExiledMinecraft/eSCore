package ru.exiledproject.developments.storage;

import java.util.UUID;

public record StorageWrite(UUID playerId, String key, Object value, long updatedAt) {
    public boolean isDelete() {
        return value == null;
    }
}
