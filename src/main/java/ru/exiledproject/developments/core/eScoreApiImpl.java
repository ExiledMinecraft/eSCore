package ru.exiledproject.developments.core;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import ru.exiledproject.developments.api.eScoreApi;
import ru.exiledproject.developments.api.eScoreEventBridge;
import ru.exiledproject.developments.api.eScorePlaceholderHook;
import ru.exiledproject.developments.api.eScoreRegistration;
import ru.exiledproject.developments.api.eScoreScope;
import ru.exiledproject.developments.api.eScoreValueProvider;
import ru.exiledproject.developments.data.PlayerDataStore;
import ru.exiledproject.developments.eSCorePlugin;
import ru.exiledproject.developments.integration.IntegrationManager;
import ru.exiledproject.developments.script.ScriptManager;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class eScoreApiImpl implements eScoreApi {
    private final eSCorePlugin plugin;
    private final PlayerDataStore dataStore;
    private final eScoreLogger log;
    private final Map<String, eScorePlaceholderHook> externalHooks = new ConcurrentHashMap<>();
    private final Map<String, eScoreValueProvider> valueProviders = new ConcurrentHashMap<>();
    private final ThreadLocal<ArrayDeque<String>> resolutionStack = ThreadLocal.withInitial(ArrayDeque::new);

    private volatile ScriptManager scriptManager;
    private volatile IntegrationManager integrationManager;

    public eScoreApiImpl(eSCorePlugin plugin, PlayerDataStore dataStore, eScoreLogger log) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.log = log;
    }

    public void attachScriptManager(ScriptManager scriptManager) {
        this.scriptManager = scriptManager;
    }

    public void attachIntegrationManager(IntegrationManager integrationManager) {
        this.integrationManager = integrationManager;
    }

    @Override
    public Optional<Object> getPlayerValue(UUID playerId, String key) {
        return Optional.ofNullable(getPlayerValue(playerId, key, null, eScoreScope.ALL_SERVERS));
    }

    @Override
    public Object getPlayerValue(UUID playerId, String key, Object defaultValue) {
        return getPlayerValue(playerId, key, defaultValue, eScoreScope.ALL_SERVERS);
    }

    @Override
    public Object getPlayerValue(UUID playerId, String key, Object defaultValue, eScoreScope scope) {
        return dataStore.getValue(playerId, key, defaultValue, scope, plugin.getServerId());
    }

    @Override
    public void setPlayerValue(UUID playerId, String key, Object value) {
        dataStore.setValue(playerId, resolveWriteKey(key), value);
    }

    @Override
    public double addToPlayerNumber(UUID playerId, String key, double delta) {
        return dataStore.addToPlayerNumber(playerId, resolveWriteKey(key), delta);
    }

    @Override
    public Optional<String> resolvePlaceholder(OfflinePlayer player, String placeholderId) {
        return resolvePlaceholder(player, placeholderId, eScoreScope.ALL_SERVERS);
    }

    @Override
    public Optional<String> resolvePlaceholder(OfflinePlayer player, String placeholderId, eScoreScope scope) {
        String normalized = normalize(placeholderId);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        ArrayDeque<String> stack = resolutionStack.get();
        if (stack.contains(normalized)) {
            log.warn("Placeholder recursion prevented for id: " + normalized);
            return Optional.empty();
        }

        stack.push(normalized);
        try {
            eScorePlaceholderHook externalHook = externalHooks.get(normalized);
            if (externalHook == null) {
                externalHook = externalHooks.get(stripScopeSuffix(normalized));
            }
            if (externalHook != null) {
                return Optional.ofNullable(externalHook.resolve(player, this));
            }
            ScriptManager manager = scriptManager;
            return manager == null ? Optional.empty() : manager.resolvePlaceholder(player, normalized, scope);
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                resolutionStack.remove();
            }
        }
    }

    @Override
    public Optional<Object> resolveValueHook(OfflinePlayer player, String providerId, String key) {
        String normalizedProvider = normalize(providerId);
        String normalizedKey = normalize(key);
        if (normalizedProvider.isEmpty() || normalizedKey.isEmpty()) {
            return Optional.empty();
        }
        eScoreValueProvider provider = valueProviders.get(normalizedProvider);
        if (provider == null || !provider.supports(normalizedKey)) {
            return Optional.empty();
        }
        return Optional.ofNullable(provider.resolve(player, normalizedKey, this));
    }

    @Override
    public Optional<Object> getScriptContext(OfflinePlayer player, String placeholderId) {
        ScriptManager manager = scriptManager;
        return manager == null ? Optional.empty() : manager.resolveContext(player, placeholderId);
    }

    @Override
    public Collection<String> getRegisteredPlaceholders() {
        Set<String> ids = new LinkedHashSet<>(externalHooks.keySet());
        ScriptManager manager = scriptManager;
        if (manager != null) {
            ids.addAll(manager.getPlaceholderIds());
        }
        return ids;
    }

    @Override
    public eScoreRegistration registerPlaceholderHook(eScorePlaceholderHook hook) {
        String id = normalize(hook.getId());
        externalHooks.put(id, hook);
        log.debug("Registered external placeholder hook: " + id);
        return () -> {
            externalHooks.remove(id);
            log.debug("Unregistered external placeholder hook: " + id);
        };
    }

    @Override
    public eScoreRegistration registerValueProvider(eScoreValueProvider provider) {
        String id = normalize(provider.getId());
        valueProviders.put(id, provider);
        log.debug("Registered external value provider: " + id);
        return () -> {
            valueProviders.remove(id);
            log.debug("Unregistered external value provider: " + id);
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public eScoreRegistration registerEventBridge(eScoreEventBridge<?> bridge) {
        Listener listener = new Listener() {
        };
        EventExecutor executor = (ignored, event) -> {
            eScoreEventBridge<Event> typedBridge = (eScoreEventBridge<Event>) bridge;
            if (!bridge.getEventClass().isInstance(event)) {
                return;
            }
            if (!typedBridge.test((Event) event, this)) {
                return;
            }
            UUID playerId = typedBridge.extractPlayerId((Event) event, this);
            if (playerId == null) {
                return;
            }
            fireChannelEvent(bridge.getChannel(), playerId, typedBridge.payload((Event) event, this));
        };
        plugin.getServer().getPluginManager().registerEvent(
                bridge.getEventClass(),
                listener,
                bridge.getPriority(),
                executor,
                plugin,
                bridge.ignoreCancelled()
        );
        return () -> org.bukkit.event.HandlerList.unregisterAll(listener);
    }

    @Override
    public void fireChannelEvent(String channel, UUID playerId, Map<String, Object> payload) {
        ScriptManager manager = scriptManager;
        if (manager != null) {
            manager.handleChannelEvent(channel, playerId, payload == null ? Map.of() : payload);
        }
    }

    @Override
    public Map<String, String> getIntegrationStates() {
        IntegrationManager manager = integrationManager;
        return manager == null ? Map.of() : manager.getStatusMap();
    }

    public PlayerDataStore getDataStore() {
        return dataStore;
    }

    public boolean hasExternalHook(String id) {
        return externalHooks.containsKey(normalize(id));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String resolveWriteKey(String key) {
        ScriptManager manager = scriptManager;
        return manager == null ? normalize(key) : manager.resolveStorageWriteKey(key);
    }

    private String stripScopeSuffix(String placeholderId) {
        String normalized = normalize(placeholderId);
        String[] suffixes = {"__current_server", "__all_servers", "__server", "__current", "__local", "__all", "__global"};
        for (String suffix : suffixes) {
            if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return normalized;
    }
}
