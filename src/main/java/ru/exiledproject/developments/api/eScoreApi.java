package ru.exiledproject.developments.api;

import org.bukkit.OfflinePlayer;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    double addToPlayerNumber(UUID playerId, String key, double delta);

    default Optional<String> resolvePlaceholder(OfflinePlayer player, String placeholderId) {
        return resolvePlaceholder(player, placeholderId, eScoreScope.ALL_SERVERS);
    }

    Optional<String> resolvePlaceholder(OfflinePlayer player, String placeholderId, eScoreScope scope);

    Optional<Object> resolveValueHook(OfflinePlayer player, String providerId, String key);

    Optional<Object> getScriptContext(OfflinePlayer player, String placeholderId);

    Collection<String> getRegisteredPlaceholders();

    eScoreRegistration registerPlaceholderHook(eScorePlaceholderHook hook);

    eScoreRegistration registerValueProvider(eScoreValueProvider provider);

    eScoreRegistration registerEventBridge(eScoreEventBridge<?> bridge);

    void fireChannelEvent(String channel, UUID playerId, Map<String, Object> payload);

    Map<String, String> getIntegrationStates();
}
