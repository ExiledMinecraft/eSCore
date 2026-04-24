package ru.exiledproject.developments.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import ru.exiledproject.developments.api.eScoreApi;
import ru.exiledproject.developments.api.eScoreValueProvider;
import ru.exiledproject.developments.api.eScoreRegistration;
import ru.exiledproject.developments.core.eScoreApiImpl;
import ru.exiledproject.developments.core.eScoreLogger;
import ru.exiledproject.developments.data.PlayerDataStore;
import ru.exiledproject.developments.data.PlayerValueChange;
import ru.exiledproject.developments.eSCorePlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class LuckPermsIntegration implements IntegrationAdapter {
    private static final String PROVIDER_ID = "luckperms";

    private final eSCorePlugin plugin;
    private final eScoreApiImpl api;
    private final PlayerDataStore dataStore;
    private final eScoreLogger log;

    private LuckPerms luckPerms;
    private eScoreRegistration providerRegistration;
    private EScoreContextCalculator calculator;
    private List<ContextRule> contextRules = List.of();
    private Set<String> trackedStorageKeys = Set.of();
    private final Map<UUID, Map<String, Set<String>>> scriptedContexts = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> scriptedPotentialContexts = new ConcurrentHashMap<>();
    private IntegrationStatus status = new IntegrationStatus("luckperms", "LuckPerms", false, false, "Pending initialization.");

    public LuckPermsIntegration(eSCorePlugin plugin, eScoreApiImpl api, PlayerDataStore dataStore, eScoreLogger log) {
        this.plugin = plugin;
        this.api = api;
        this.dataStore = dataStore;
        this.log = log;
    }

    @Override
    public String getId() {
        return "luckperms";
    }

    @Override
    public String getDisplayName() {
        return "LuckPerms";
    }

    @Override
    public void reload() {
        shutdown();
        if (!plugin.getConfig().getBoolean("integrations.luckperms", true)) {
            status = new IntegrationStatus(getId(), getDisplayName(), true, false, "Disabled in config.");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            status = new IntegrationStatus(getId(), getDisplayName(), false, false, "Plugin not installed.");
            return;
        }

        try {
            luckPerms = LuckPermsProvider.get();
            contextRules = readContextRules();
            trackedStorageKeys = contextRules.stream()
                    .map(ContextRule::storageKey)
                    .filter(key -> key != null && !key.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            providerRegistration = api.registerValueProvider(new LuckPermsValueProvider());
            calculator = new EScoreContextCalculator();
            luckPerms.getContextManager().registerCalculator(calculator);

            status = new IntegrationStatus(
                    getId(),
                    getDisplayName(),
                    true,
                    true,
                    buildStatusMessage()
            );
            log.success("LuckPerms integration active.");
        } catch (Exception exception) {
            status = new IntegrationStatus(getId(), getDisplayName(), true, false, "Initialization failed: " + safeMessage(exception));
            shutdown();
        }
    }

    @Override
    public void shutdown() {
        if (luckPerms != null && calculator != null) {
            luckPerms.getContextManager().unregisterCalculator(calculator);
        }
        if (providerRegistration != null) {
            providerRegistration.unregister();
        }
        providerRegistration = null;
        calculator = null;
        luckPerms = null;
        contextRules = List.of();
        trackedStorageKeys = Set.of();
        scriptedContexts.clear();
        scriptedPotentialContexts.clear();
    }

    @Override
    public IntegrationStatus getStatus() {
        return status;
    }

    @Override
    public List<String> check() {
        List<String> lines = new ArrayList<>();
        lines.add(status.message());
        if (!status.active()) {
            return lines;
        }
        lines.add("Features: group, groups, prefix, suffix, meta:<key>, has_group:<group>, permission:<node>, script contexts");
        if (contextRules.isEmpty()) {
            lines.add("Context rules: none configured.");
        } else {
            lines.add("Context rules: " + contextRules.size());
            for (ContextRule rule : contextRules) {
                lines.add(" - " + rule.describe());
            }
        }
        lines.add("Script-defined contexts: " + scriptedContexts.size() + " player(s)");
        return lines;
    }

    @Override
    public void onPlayerValueChange(PlayerValueChange change) {
        if (!status.active() || luckPerms == null || calculator == null) {
            return;
        }
        if (!trackedStorageKeys.contains(change.key())) {
            return;
        }
        Player player = Bukkit.getPlayer(change.playerId());
        if (player != null) {
            luckPerms.getContextManager().signalContextUpdate(player);
        }
    }

    public String getPrimaryGroup(Player player) {
        User user = resolveUser(player);
        return user == null ? "" : safe(user.getPrimaryGroup());
    }

    public boolean hasGroup(Player player, String group) {
        User user = resolveUser(player);
        if (user == null || group == null || group.isBlank()) {
            return false;
        }
        return user.getNodes(NodeType.INHERITANCE).stream()
                .anyMatch(node -> node.getGroupName().equalsIgnoreCase(group));
    }

    public String getPrefix(Player player) {
        return getMetaValue(player, "prefix");
    }

    public String getSuffix(Player player) {
        return getMetaValue(player, "suffix");
    }

    public boolean setScriptContext(OfflinePlayer player, String key, Object value) {
        if (!status.active() || player == null) {
            return false;
        }
        String normalizedKey = normalizeContextKey(key);
        if (normalizedKey.isBlank()) {
            return false;
        }
        Set<String> resolvedValues = contextValues(value);
        if (resolvedValues.isEmpty()) {
            return removeScriptContext(player, normalizedKey);
        }

        scriptedContexts.compute(player.getUniqueId(), (playerId, existing) -> {
            Map<String, Set<String>> updated = existing == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(existing);
            updated.put(normalizedKey, Set.copyOf(resolvedValues));
            return updated;
        });
        rebuildScriptedPotentialContexts();
        signalContextUpdate(player);
        return true;
    }

    public boolean removeScriptContext(OfflinePlayer player, String key) {
        if (!status.active() || player == null) {
            return false;
        }
        String normalizedKey = normalizeContextKey(key);
        if (normalizedKey.isBlank()) {
            return false;
        }

        Map<String, Set<String>> existing = scriptedContexts.get(player.getUniqueId());
        if (existing == null || !existing.containsKey(normalizedKey)) {
            return false;
        }
        scriptedContexts.computeIfPresent(player.getUniqueId(), (playerId, contexts) -> {
            Map<String, Set<String>> updated = new ConcurrentHashMap<>(contexts);
            updated.remove(normalizedKey);
            return updated.isEmpty() ? null : updated;
        });
        rebuildScriptedPotentialContexts();
        signalContextUpdate(player);
        return true;
    }

    public void clearScriptContexts(OfflinePlayer player) {
        if (!status.active() || player == null) {
            return;
        }
        scriptedContexts.remove(player.getUniqueId());
        rebuildScriptedPotentialContexts();
        signalContextUpdate(player);
    }

    public Object getScriptContextValue(OfflinePlayer player, String key) {
        if (!status.active() || player == null) {
            return null;
        }
        String normalizedKey = normalizeContextKey(key);
        if (normalizedKey.isBlank()) {
            return null;
        }
        Map<String, Set<String>> contexts = scriptedContexts.get(player.getUniqueId());
        if (contexts == null) {
            return null;
        }
        Set<String> values = contexts.get(normalizedKey);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() == 1) {
            return values.iterator().next();
        }
        return List.copyOf(values);
    }

    private String buildStatusMessage() {
        if (contextRules.isEmpty()) {
            return "Metadata bridge active. Provides group, prefix, suffix, meta access and script-defined contexts.";
        }
        return "Metadata bridge and context bridge active. "
                + contextRules.size()
                + " configured context rule(s) loaded, plus script-defined contexts.";
    }

    private List<ContextRule> readContextRules() {
        List<Map<?, ?>> configuredRules = plugin.getConfig().getMapList("luckperms.contexts");
        List<ContextRule> rules = new ArrayList<>();
        for (Map<?, ?> rawRule : configuredRules) {
            ContextRule rule = parseContextRule(rawRule);
            if (rule != null) {
                rules.add(rule);
            }
        }
        if (!rules.isEmpty()) {
            return List.copyOf(rules);
        }

        Set<String> legacyKeys = plugin.getConfig().getStringList("luckperms.context-storage-keys").stream()
                .map(this::normalize)
                .filter(key -> !key.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (legacyKeys.isEmpty()) {
            legacyKeys = Set.of("dragon_kills");
        }

        String exactKey = normalizeContextKey(plugin.getConfig().getString("luckperms.exact-context-key", "escore:dragon-kills"));
        String bucketKey = normalizeContextKey(plugin.getConfig().getString("luckperms.bucket-context-key", "escore:dragon-kills-bucket"));
        String flagKey = normalizeContextKey(plugin.getConfig().getString("luckperms.flag-context-key", "escore:has-dragon-kill"));
        Map<String, Double> buckets = readBucketMap(plugin.getConfig().getConfigurationSection("luckperms.buckets"));

        List<ContextRule> legacyRules = new ArrayList<>();
        for (String storageKey : legacyKeys) {
            legacyRules.add(new ContextRule(storageKey, exactKey, bucketKey, flagKey, buckets));
        }
        return List.copyOf(legacyRules);
    }

    private ContextRule parseContextRule(Map<?, ?> rawRule) {
        if (rawRule == null || rawRule.isEmpty()) {
            return null;
        }
        String storageKey = normalize(rawRule.get("storage-key"));
        if (storageKey.isBlank()) {
            storageKey = normalize(rawRule.get("storageKey"));
        }
        if (storageKey.isBlank()) {
            return null;
        }

        String exactKey = normalizeContextKey(firstNonBlank(rawRule.get("exact-key"), rawRule.get("exactKey")));
        String bucketKey = normalizeContextKey(firstNonBlank(rawRule.get("bucket-key"), rawRule.get("bucketKey")));
        String flagKey = normalizeContextKey(firstNonBlank(rawRule.get("flag-key"), rawRule.get("flagKey")));
        Map<String, Double> buckets = readBucketMap(rawRule.get("buckets"));
        return new ContextRule(storageKey, exactKey, bucketKey, flagKey, buckets);
    }

    private Map<String, Double> readBucketMap(Object source) {
        if (source instanceof ConfigurationSection section) {
            Map<String, Double> result = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                result.put(key.toLowerCase(Locale.ROOT), section.getDouble(key));
            }
            return result;
        }
        if (source instanceof Map<?, ?> map) {
            Map<String, Double> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                result.put(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT), toNumber(entry.getValue()));
            }
            return result;
        }
        return Map.of();
    }

    private User resolveUser(OfflinePlayer player) {
        if (!status.active() || luckPerms == null || player == null) {
            return null;
        }
        try {
            if (player instanceof Player online) {
                return luckPerms.getPlayerAdapter(Player.class).getUser(online);
            }
            return luckPerms.getUserManager().getUser(player.getUniqueId());
        } catch (Exception exception) {
            log.debug("LuckPerms user lookup failed: " + safeMessage(exception));
            return null;
        }
    }

    private QueryOptions resolveQueryOptions(OfflinePlayer player) {
        if (luckPerms == null) {
            return QueryOptions.nonContextual();
        }
        if (player instanceof Player online) {
            QueryOptions options = luckPerms.getContextManager().getQueryOptions(online);
            if (options != null) {
                return options;
            }
        }
        return QueryOptions.nonContextual();
    }

    private String getMetaValue(OfflinePlayer player, String key) {
        User user = resolveUser(player);
        if (user == null || key == null || key.isBlank()) {
            return "";
        }
        try {
            CachedMetaData meta = user.getCachedData().getMetaData(resolveQueryOptions(player));
            return switch (key.toLowerCase(Locale.ROOT)) {
                case "prefix" -> safe(meta.getPrefix());
                case "suffix" -> safe(meta.getSuffix());
                default -> safe(meta.getMetaValue(key));
            };
        } catch (Exception exception) {
            log.debug("LuckPerms meta lookup failed: " + safeMessage(exception));
            return "";
        }
    }

    private String resolveValue(OfflinePlayer player, String key) {
        User user = resolveUser(player);
        if (user == null || key == null || key.isBlank()) {
            return "";
        }

        String normalizedKey = key.toLowerCase(Locale.ROOT);
        if (normalizedKey.equals("group") || normalizedKey.equals("primary_group")) {
            return safe(user.getPrimaryGroup());
        }
        if (normalizedKey.equals("groups")) {
            return user.getNodes(NodeType.INHERITANCE).stream()
                    .map(node -> node.getGroupName().toLowerCase(Locale.ROOT))
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(","));
        }
        if (normalizedKey.equals("prefix")) {
            return getMetaValue(player, "prefix");
        }
        if (normalizedKey.equals("suffix")) {
            return getMetaValue(player, "suffix");
        }
        if (normalizedKey.startsWith("meta:")) {
            return getMetaValue(player, key.substring("meta:".length()));
        }
        if (normalizedKey.startsWith("has_group:")) {
            String group = key.substring("has_group:".length()).trim();
            return Boolean.toString(hasGroup(player.getPlayer(), group));
        }
        if (normalizedKey.startsWith("permission:")) {
            return Boolean.toString(checkPermission(user, player, key.substring("permission:".length()).trim()));
        }
        if (normalizedKey.startsWith("has_permission:")) {
            return Boolean.toString(checkPermission(user, player, key.substring("has_permission:".length()).trim()));
        }
        if (normalizedKey.startsWith("context:")) {
            Object value = getScriptContextValue(player, key.substring("context:".length()));
            if (value instanceof Collection<?> collection) {
                return collection.stream().map(String::valueOf).collect(Collectors.joining(","));
            }
            return value == null ? "" : String.valueOf(value);
        }
        if (normalizedKey.startsWith("has_context:")) {
            String raw = key.substring("has_context:".length()).trim();
            int separator = raw.indexOf(':');
            if (separator > 0 && separator + 1 < raw.length()) {
                String contextKey = raw.substring(0, separator).trim();
                String expectedValue = raw.substring(separator + 1).trim();
                return Boolean.toString(hasScriptContextValue(player, contextKey, expectedValue));
            }
        }
        return "";
    }

    private boolean hasScriptContextValue(OfflinePlayer player, String key, String expectedValue) {
        if (player == null || expectedValue == null || expectedValue.isBlank()) {
            return false;
        }
        Map<String, Set<String>> contexts = scriptedContexts.get(player.getUniqueId());
        if (contexts == null) {
            return false;
        }
        Set<String> values = contexts.get(normalizeContextKey(key));
        return values != null && values.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(expectedValue.trim()));
    }

    private boolean checkPermission(User user, OfflinePlayer player, String permission) {
        if (permission == null || permission.isBlank()) {
            return false;
        }
        try {
            return user.getCachedData().getPermissionData(resolveQueryOptions(player)).checkPermission(permission).asBoolean();
        } catch (Exception exception) {
            log.debug("LuckPerms permission lookup failed: " + safeMessage(exception));
            return false;
        }
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeContextKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(Object first, Object second) {
        String firstValue = first == null ? "" : String.valueOf(first).trim();
        if (!firstValue.isBlank()) {
            return firstValue;
        }
        return second == null ? "" : String.valueOf(second).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private double toNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0D;
        }
    }

    private String formatExactValue(Object value) {
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            long longValue = Math.round(doubleValue);
            return Math.abs(doubleValue - longValue) < 0.0000001D ? Long.toString(longValue) : Double.toString(doubleValue);
        }
        if (value instanceof Boolean bool) {
            return Boolean.toString(bool);
        }
        return safe(Objects.toString(value, ""));
    }

    private boolean hasSignalValue(Object value) {
        if (value instanceof Number number) {
            return Math.abs(number.doubleValue()) > 0.0000001D;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private String bucketFor(ContextRule rule, Object value) {
        if (rule.buckets().isEmpty()) {
            return "";
        }
        double numericValue = toNumber(value);
        return rule.buckets().entrySet().stream()
                .filter(entry -> numericValue >= entry.getValue())
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private final class EScoreContextCalculator implements ContextCalculator<Player> {
        @Override
        public void calculate(Player target, ContextConsumer consumer) {
            for (ContextRule rule : contextRules) {
                Object rawValue = dataStore.getValue(target.getUniqueId(), rule.storageKey(), 0D);
                if (!rule.exactKey().isBlank()) {
                    consumer.accept(rule.exactKey(), formatExactValue(rawValue));
                }
                if (!rule.flagKey().isBlank()) {
                    consumer.accept(rule.flagKey(), Boolean.toString(hasSignalValue(rawValue)));
                }
                if (!rule.bucketKey().isBlank()) {
                    String bucket = bucketFor(rule, rawValue);
                    if (!bucket.isBlank()) {
                        consumer.accept(rule.bucketKey(), bucket);
                    }
                }
            }
            Map<String, Set<String>> dynamicContexts = scriptedContexts.get(target.getUniqueId());
            if (dynamicContexts != null) {
                dynamicContexts.forEach((key, values) -> values.forEach(value -> consumer.accept(key, value)));
            }
        }

        @Override
        public ImmutableContextSet estimatePotentialContexts() {
            ImmutableContextSet.Builder builder = ImmutableContextSet.builder();
            for (ContextRule rule : contextRules) {
                if (!rule.flagKey().isBlank()) {
                    builder.add(rule.flagKey(), "true");
                    builder.add(rule.flagKey(), "false");
                }
                if (!rule.bucketKey().isBlank()) {
                    rule.buckets().keySet().forEach(bucket -> builder.add(rule.bucketKey(), bucket));
                }
            }
            scriptedPotentialContexts.forEach((key, values) -> values.forEach(value -> builder.add(key, value)));
            return builder.build();
        }
    }

    private final class LuckPermsValueProvider implements eScoreValueProvider {
        @Override
        public String getId() {
            return PROVIDER_ID;
        }

        @Override
        public String getDescription() {
            return "LuckPerms metadata bridge for groups, prefixes, suffixes and permission-aware script logic.";
        }

        @Override
        public Collection<String> getKnownKeys() {
            return List.of("group", "primary_group", "groups", "prefix", "suffix", "meta:*", "has_group:*", "permission:*", "has_permission:*", "context:*", "has_context:*");
        }

        @Override
        public boolean supports(String key) {
            String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
            return normalizedKey.equals("group")
                    || normalizedKey.equals("primary_group")
                    || normalizedKey.equals("groups")
                    || normalizedKey.equals("prefix")
                    || normalizedKey.equals("suffix")
                    || normalizedKey.startsWith("meta:")
                    || normalizedKey.startsWith("has_group:")
                    || normalizedKey.startsWith("permission:")
                    || normalizedKey.startsWith("has_permission:")
                    || normalizedKey.startsWith("context:")
                    || normalizedKey.startsWith("has_context:");
        }

        @Override
        public Object resolve(OfflinePlayer player, String key, eScoreApi api) {
            return resolveValue(player, key);
        }
    }

    private Set<String> contextValues(Object value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        appendContextValues(result, value);
        return result;
    }

    private void appendContextValues(Set<String> target, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                appendContextValues(target, item);
            }
            return;
        }
        if (value instanceof Object[] array) {
            for (Object item : array) {
                appendContextValues(target, item);
            }
            return;
        }
        String string = String.valueOf(value).trim();
        if (!string.isBlank()) {
            target.add(string);
        }
    }

    private void rebuildScriptedPotentialContexts() {
        scriptedPotentialContexts.clear();
        scriptedContexts.values().forEach(contexts -> contexts.forEach((key, values) ->
                scriptedPotentialContexts.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).addAll(values)));
    }

    private void signalContextUpdate(OfflinePlayer player) {
        if (luckPerms == null || player == null) {
            return;
        }
        Player online = player.getPlayer();
        if (online != null) {
            luckPerms.getContextManager().signalContextUpdate(online);
        }
    }

    private record ContextRule(
            String storageKey,
            String exactKey,
            String bucketKey,
            String flagKey,
            Map<String, Double> buckets
    ) {
        private String describe() {
            List<String> parts = new ArrayList<>();
            parts.add("storage=" + storageKey);
            if (!exactKey.isBlank()) {
                parts.add("exact=" + exactKey);
            }
            if (!bucketKey.isBlank()) {
                parts.add("bucket=" + bucketKey + " (" + buckets.size() + " buckets)");
            }
            if (!flagKey.isBlank()) {
                parts.add("flag=" + flagKey);
            }
            return String.join(", ", parts);
        }
    }
}
