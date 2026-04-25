package ru.exiledproject.developments.data;

import ru.exiledproject.developments.api.eScoreScope;
import ru.exiledproject.developments.core.eScoreLogger;
import ru.exiledproject.developments.eSCorePlugin;
import ru.exiledproject.developments.storage.StorageScopeKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class PlayerDataStore {
    private final eSCorePlugin plugin;
    private final eScoreLogger log;
    private final Map<UUID, Map<String, ValueHolder>> data = new ConcurrentHashMap<>();
    private final List<Consumer<PlayerValueChange>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<Map.Entry<UUID, Double>>> topCache = new ConcurrentHashMap<>();

    public PlayerDataStore(eSCorePlugin plugin, eScoreLogger log) {
        this.plugin = plugin;
        this.log = log;
    }

    public void addListener(Consumer<PlayerValueChange> listener) {
        listeners.add(listener);
    }

    public void clear() {
        data.clear();
        topCache.clear();
    }

    public void loadSnapshot(Map<UUID, Map<String, PersistedValue>> snapshot) {
        clear();
        snapshot.forEach((playerId, values) -> {
            Map<String, ValueHolder> playerValues = new ConcurrentHashMap<>();
            values.forEach((key, persistedValue) -> playerValues.put(normalizeKey(key), new ValueHolder(normalizeValue(persistedValue.value()), persistedValue.updatedAt())));
            if (!playerValues.isEmpty()) {
                data.put(playerId, playerValues);
            }
        });
        log.success("Loaded in-memory player state for " + data.size() + " player(s).");
    }

    public Map<UUID, Map<String, PersistedValue>> snapshot(Set<String> keys) {
        Set<String> normalizedKeys = keys.stream().map(PlayerDataStore::normalizeKey).collect(java.util.stream.Collectors.toSet());
        Map<UUID, Map<String, PersistedValue>> snapshot = new LinkedHashMap<>();
        data.forEach((playerId, values) -> {
            Map<String, PersistedValue> playerValues = new LinkedHashMap<>();
            values.forEach((key, holder) -> {
                if (normalizedKeys.isEmpty() || normalizedKeys.contains(key)) {
                    playerValues.put(key, new PersistedValue(holder.value(), holder.updatedAt()));
                }
            });
            if (!playerValues.isEmpty()) {
                snapshot.put(playerId, playerValues);
            }
        });
        return snapshot;
    }

    public Map<String, PersistedValue> snapshotPlayer(UUID playerId, Set<String> keys) {
        Map<String, ValueHolder> values = data.get(playerId);
        if (values == null) {
            return Map.of();
        }
        Set<String> normalizedKeys = keys.stream().map(PlayerDataStore::normalizeKey).collect(java.util.stream.Collectors.toSet());
        Map<String, PersistedValue> snapshot = new LinkedHashMap<>();
        values.forEach((key, holder) -> {
            if (normalizedKeys.isEmpty() || normalizedKeys.contains(key)) {
                snapshot.put(key, new PersistedValue(holder.value(), holder.updatedAt()));
            }
        });
        return snapshot;
    }

    public Object getValue(UUID playerId, String key, Object defaultValue) {
        String normalizedKey = normalizeKey(key);
        Map<String, ValueHolder> values = data.getOrDefault(playerId, Collections.emptyMap());
        if (StorageScopeKeys.isServerScopedKey(normalizedKey)) {
            ValueHolder holder = values.get(normalizedKey);
            if (holder != null) {
                return holder.value();
            }
            String logicalKey = StorageScopeKeys.logicalKey(normalizedKey);
            boolean hasScopedValues = values.keySet().stream()
                    .anyMatch(candidateKey -> StorageScopeKeys.isServerScopedKey(candidateKey) && StorageScopeKeys.matchesLogicalKey(candidateKey, logicalKey));
            if (!hasScopedValues) {
                ValueHolder exact = values.get(logicalKey);
                return exact == null ? defaultValue : exact.value();
            }
            return defaultValue;
        }
        return getValue(playerId, normalizedKey, defaultValue, eScoreScope.ALL_SERVERS, plugin.getServerId());
    }

    public Object getValue(UUID playerId, String key, Object defaultValue, eScoreScope scope, String serverId) {
        String normalizedKey = normalizeKey(key);
        Map<String, ValueHolder> values = data.getOrDefault(playerId, Collections.emptyMap());
        ValueHolder direct = values.get(normalizedKey);
        if (scope == eScoreScope.CURRENT_SERVER && StorageScopeKeys.isServerScopedKey(normalizedKey)) {
            return direct == null ? defaultValue : direct.value();
        }
        AggregatedValue aggregatedValue = aggregateValue(values, normalizedKey, scope, serverId);
        return aggregatedValue == null ? defaultValue : aggregatedValue.value();
    }

    public PersistedValue getPersistedValue(UUID playerId, String key) {
        ValueHolder holder = data.getOrDefault(playerId, Collections.emptyMap()).get(normalizeKey(key));
        return holder == null ? null : new PersistedValue(holder.value(), holder.updatedAt());
    }

    public double addToPlayerNumber(UUID playerId, String key, double delta) {
        double current = toNumber(getValue(playerId, key, 0D));
        double updated = current + delta;
        setValue(playerId, key, updated);
        return updated;
    }

    public void setValue(UUID playerId, String key, Object value) {
        setValue(playerId, key, value, ChangeOrigin.LOCAL, System.currentTimeMillis());
    }

    public void setValue(UUID playerId, String key, Object value, ChangeOrigin origin, long updatedAt) {
        String normalizedKey = normalizeKey(key);
        Map<String, ValueHolder> values = data.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        ValueHolder oldHolder = values.get(normalizedKey);
        Object oldValue = oldHolder == null ? null : oldHolder.value();
        long previousUpdatedAt = oldHolder == null ? 0L : oldHolder.updatedAt();

        if (oldHolder != null && origin != ChangeOrigin.LOCAL && updatedAt < previousUpdatedAt) {
            return;
        }

        topCache.clear();
        Object newValue = normalizeValue(value);
        if (newValue == null) {
            values.remove(normalizedKey);
            if (values.isEmpty()) {
                data.remove(playerId);
            }
        } else {
            values.put(normalizedKey, new ValueHolder(newValue, updatedAt));
        }

        if (!Objects.equals(oldValue, newValue) || previousUpdatedAt != updatedAt) {
            PlayerValueChange change = new PlayerValueChange(playerId, normalizedKey, oldValue, newValue, origin, updatedAt);
            listeners.forEach(listener -> listener.accept(change));
        }
    }

    public List<Map.Entry<UUID, Double>> getTopNumbers(String key, int limit) {
        return getTopNumbers(key, limit, eScoreScope.ALL_SERVERS, plugin.getServerId());
    }

    public List<Map.Entry<UUID, Double>> getTopNumbers(String key, int limit, eScoreScope scope, String serverId) {
        String normalizedKey = normalizeKey(key);
        String cacheKey = topCacheKey(normalizedKey, scope, serverId);
        List<Map.Entry<UUID, Double>> entries = topCache.computeIfAbsent(cacheKey, ignored -> computeTopNumbers(normalizedKey, scope, serverId));
        if (entries.size() > limit) {
            return new ArrayList<>(entries.subList(0, limit));
        }
        return new ArrayList<>(entries);
    }

    private List<Map.Entry<UUID, Double>> computeTopNumbers(String normalizedKey, eScoreScope scope, String serverId) {
        List<Map.Entry<UUID, Double>> entries = new ArrayList<>();
        data.forEach((uuid, values) -> {
            AggregatedValue value = aggregateValue(values, normalizedKey, scope, serverId);
            if (value != null && isNumeric(value.value())) {
                entries.add(Map.entry(uuid, toNumber(value.value())));
            }
        });
        entries.sort(Comparator.comparingDouble((Map.Entry<UUID, Double> entry) -> entry.getValue()).reversed());
        return List.copyOf(entries);
    }

    private AggregatedValue aggregateValue(Map<String, ValueHolder> values, String logicalOrRawKey, eScoreScope scope, String serverId) {
        String logicalKey = StorageScopeKeys.logicalKey(logicalOrRawKey);
        ValueHolder exact = values.get(logicalKey);
        List<ValueHolder> scopedValues = new ArrayList<>();
        String currentServerKey = StorageScopeKeys.serverKey(logicalKey, serverId);

        values.forEach((candidateKey, holder) -> {
            if (StorageScopeKeys.isServerScopedKey(candidateKey) && StorageScopeKeys.matchesLogicalKey(candidateKey, logicalKey)) {
                scopedValues.add(holder);
            }
        });

        if (scope == eScoreScope.CURRENT_SERVER) {
            ValueHolder currentServerValue = values.get(currentServerKey);
            if (currentServerValue != null) {
                return new AggregatedValue(currentServerValue.value(), currentServerValue.updatedAt());
            }
            if (!scopedValues.isEmpty()) {
                return null;
            }
            return exact == null ? null : new AggregatedValue(exact.value(), exact.updatedAt());
        }

        if (scopedValues.isEmpty()) {
            return exact == null ? null : new AggregatedValue(exact.value(), exact.updatedAt());
        }

        boolean numeric = scopedValues.stream().allMatch(holder -> isNumeric(holder.value()));
        if (numeric) {
            double total = 0D;
            long updatedAt = 0L;
            for (ValueHolder holder : scopedValues) {
                total += toNumber(holder.value());
                updatedAt = Math.max(updatedAt, holder.updatedAt());
            }
            return new AggregatedValue(total, updatedAt);
        }

        ValueHolder latest = scopedValues.stream()
                .max(Comparator.comparingLong(ValueHolder::updatedAt))
                .orElse(null);
        return latest == null ? null : new AggregatedValue(latest.value(), latest.updatedAt());
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase();
    }

    private static double toNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string);
            } catch (NumberFormatException ignored) {
                return 0D;
            }
        }
        return 0D;
    }

    private static boolean isNumeric(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof String string) {
            try {
                Double.parseDouble(string);
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private static String topCacheKey(String key, eScoreScope scope, String serverId) {
        return scope.name() + "::" + (serverId == null ? "" : serverId.toLowerCase()) + "::" + StorageScopeKeys.logicalKey(key);
    }

    private Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((entryKey, entryValue) -> normalized.put(String.valueOf(entryKey), normalizeValue(entryValue)));
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : iterable) {
                normalized.add(normalizeValue(item));
            }
            return normalized;
        }
        if (value.getClass().isArray() && value instanceof Object[] array) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : array) {
                normalized.add(normalizeValue(item));
            }
            return normalized;
        }
        return String.valueOf(value);
    }

    private record ValueHolder(Object value, long updatedAt) {
    }

    private record AggregatedValue(Object value, long updatedAt) {
    }
}
