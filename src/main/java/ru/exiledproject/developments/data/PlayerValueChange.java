package ru.exiledproject.developments.data;

import java.util.UUID;

public record PlayerValueChange(
        UUID playerId,
        String key,
        Object oldValue,
        Object newValue,
        ChangeOrigin origin,
        long updatedAt
) {
}
