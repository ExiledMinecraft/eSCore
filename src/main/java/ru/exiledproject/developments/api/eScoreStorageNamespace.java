package ru.exiledproject.developments.api;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A collision-safe storage view for one extension or script. */
public final class eScoreStorageNamespace {
    private final eScoreApi api;
    private final String namespace;

    public eScoreStorageNamespace(eScoreApi api, String namespace) {
        this.api = Objects.requireNonNull(api, "api");
        String normalized = namespace == null ? "" : namespace.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_.-]{0,47}")) {
            throw new IllegalArgumentException("Storage namespace must match [a-z0-9][a-z0-9_.-]{0,47}");
        }
        this.namespace = normalized;
    }

    public String namespace() {
        return namespace;
    }

    public Optional<Object> get(UUID playerId, String key) {
        return api.getPlayerValue(playerId, namespaced(key));
    }

    public Object get(UUID playerId, String key, Object fallback) {
        return api.getPlayerValue(playerId, namespaced(key), fallback);
    }

    public void set(UUID playerId, String key, Object value) {
        api.setPlayerValue(playerId, namespaced(key), value);
    }

    public void set(UUID playerId, String key, Object value, Duration ttl) {
        api.setPlayerValue(playerId, namespaced(key), value, ttl);
    }

    public void setAll(UUID playerId, Map<String, ?> values) {
        Map<String, Object> namespaced = new LinkedHashMap<>();
        values.forEach((key, value) -> namespaced.put(namespaced(key), value));
        api.setPlayerValues(playerId, namespaced);
    }

    public double add(UUID playerId, String key, double delta) {
        return api.addToPlayerNumber(playerId, namespaced(key), delta);
    }

    public void remove(UUID playerId, String key) {
        set(playerId, key, null);
    }

    private String namespaced(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_.-]{0,78}")) {
            throw new IllegalArgumentException("Storage key contains unsupported characters or is too long.");
        }
        return namespace + ':' + normalized;
    }
}
