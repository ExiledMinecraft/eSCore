package ru.exiledproject.developments.api;

import org.bukkit.OfflinePlayer;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface eScoreApi {
    Optional<Object> getPlayerValue(UUID playerId, String key);

    Object getPlayerValue(UUID playerId, String key, Object defaultValue);

    void setPlayerValue(UUID playerId, String key, Object value);

    double addToPlayerNumber(UUID playerId, String key, double delta);

    Optional<String> resolvePlaceholder(OfflinePlayer player, String placeholderId);

    Optional<Object> resolveValueHook(OfflinePlayer player, String providerId, String key);

    Optional<Object> getScriptContext(OfflinePlayer player, String placeholderId);

    Collection<String> getRegisteredPlaceholders();

    eScoreRegistration registerPlaceholderHook(eScorePlaceholderHook hook);

    eScoreRegistration registerValueProvider(eScoreValueProvider provider);

    eScoreRegistration registerEventBridge(eScoreEventBridge<?> bridge);

    void fireChannelEvent(String channel, UUID playerId, Map<String, Object> payload);

    Map<String, String> getIntegrationStates();
}
