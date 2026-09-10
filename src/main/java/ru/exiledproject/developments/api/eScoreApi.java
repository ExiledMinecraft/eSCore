package ru.exiledproject.developments.api;

import org.bukkit.OfflinePlayer;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.time.Duration;

public interface eScoreApi {
    default Optional<Object> getPlayerValue(UUID playerId, String key) {
        return Optional.ofNullable(getPlayerValue(playerId, key, null, eScoreScope.ALL_SERVERS));
    }

    default Object getPlayerValue(UUID playerId, String key, Object defaultValue) {
        return getPlayerValue(playerId, key, defaultValue, eScoreScope.ALL_SERVERS);
    }

    default Optional<Object> getPlayerValue(UUID playerId, String key, eScoreScope scope) {
        return Optional.ofNullable(getPlayerValue(playerId, key, null, scope));
    }

    Object getPlayerValue(UUID playerId, String key, Object defaultValue, eScoreScope scope);

    void setPlayerValue(UUID playerId, String key, Object value);

    default void setPlayerValue(UUID playerId, String key, Object value, Duration ttl) {
        setPlayerValue(playerId, key, value);
    }

    default void setPlayerValues(UUID playerId, Map<String, ?> values) {
        values.forEach((key, value) -> setPlayerValue(playerId, key, value));
    }

    default eScoreWriteResult putPlayerValue(UUID playerId, String key, Object value) {
        Optional<Object> previous = getPlayerValue(playerId, key);
        setPlayerValue(playerId, key, value);
        if (previous.isEmpty() && value != null) {
            return eScoreWriteResult.CREATED;
        }
        if (previous.isPresent() && Objects.equals(previous.get(), value)) {
            return eScoreWriteResult.UNCHANGED;
        }
        return eScoreWriteResult.UPDATED;
    }

    default Map<String, eScoreWriteResult> putPlayerValues(UUID playerId, Map<String, ?> values) {
        Map<String, eScoreWriteResult> results = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> results.put(key, putPlayerValue(playerId, key, value)));
        return Map.copyOf(results);
    }

    default eScoreStorageNamespace storage(String namespace) {
        return new eScoreStorageNamespace(this, namespace);
    }

    double addToPlayerNumber(UUID playerId, String key, double delta);

    default Optional<String> resolvePlaceholder(OfflinePlayer player, String placeholderId) {
        return resolvePlaceholder(player, placeholderId, eScoreScope.ALL_SERVERS);
    }

    Optional<String> resolvePlaceholder(OfflinePlayer player, String placeholderId, eScoreScope scope);

    default eScorePlaceholderResult resolvePlaceholderResult(OfflinePlayer player, String placeholderId, eScoreScope scope) {
        return resolvePlaceholder(player, placeholderId, scope)
                .map(eScorePlaceholderResult::text)
                .orElseGet(eScorePlaceholderResult::missing);
    }

    Optional<Object> resolveValueHook(OfflinePlayer player, String providerId, String key);

    Optional<Object> getScriptContext(OfflinePlayer player, String placeholderId);

    Collection<String> getRegisteredPlaceholders();

    eScoreRegistration registerPlaceholderHook(eScorePlaceholderHook hook);

    eScoreRegistration registerValueProvider(eScoreValueProvider provider);

    eScoreRegistration registerEventBridge(eScoreEventBridge<?> bridge);

    void fireChannelEvent(String channel, UUID playerId, Map<String, Object> payload);

    Map<String, String> getIntegrationStates();
}
