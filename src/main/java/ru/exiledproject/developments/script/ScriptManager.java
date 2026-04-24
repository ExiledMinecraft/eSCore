package ru.exiledproject.developments.script;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.projectiles.ProjectileSource;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import ru.exiledproject.developments.api.eScoreApi;
import ru.exiledproject.developments.core.eScoreLogger;
import ru.exiledproject.developments.data.ChangeOrigin;
import ru.exiledproject.developments.data.PlayerDataStore;
import ru.exiledproject.developments.data.PlayerValueChange;
import ru.exiledproject.developments.eSCorePlugin;

import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class ScriptManager {
    private static final String BOOTSTRAP_PRELUDE = """
            const __escoreBootstrap = typeof escore !== 'undefined' ? escore : wscore;
            const __escoreApi = {
              register(definition) { __escoreBootstrap.register(definition); return definition; },
              registerAll(definitions) { __escoreBootstrap.registerAll(definitions); return definitions; },
              require(path) { return __escoreBootstrap.requireModule(path); },
              createCounter(definition) {
                return Object.assign({
                  numeric: true,
                  defaultValue: 0,
                  persistence: { mode: 'backend' },
                  sync: { mode: 'auto' }
                }, definition || {});
              },
              createFormatter(definition) {
                return Object.assign({
                  persistence: { mode: 'memory' },
                  sync: { mode: 'none' }
                }, definition || {});
              },
              createLeaderboardCounter(definition) {
                return Object.assign({
                  numeric: true,
                  defaultValue: 0,
                  persistence: { mode: 'backend' },
                  sync: { mode: 'auto' },
                  leaderboards: {
                    periods: {
                      alltime: { mode: 'alltime' }
                    }
                  }
                }, definition || {});
              },
              math: __escoreBootstrap.getMath(),
              text: __escoreBootstrap.getText(),
              normalize: __escoreBootstrap.getNormalize(),
              decorate: __escoreBootstrap.getDecorate(),
              convert: __escoreBootstrap.getConvert(),
              builders: __escoreBootstrap.getBuilders(),
              format: __escoreBootstrap.getFormat(),
              time: __escoreBootstrap.getTime(),
              events: __escoreBootstrap.getEventAliases()
            };
            wscore = __escoreApi;
            escore = __escoreApi;
            """;

    private static final Map<String, String> EVENT_ALIASES = Map.ofEntries(
            Map.entry("paper.entity_death", "org.bukkit.event.entity.EntityDeathEvent"),
            Map.entry("paper.player_join", "org.bukkit.event.player.PlayerJoinEvent"),
            Map.entry("paper.player_quit", "org.bukkit.event.player.PlayerQuitEvent"),
            Map.entry("paper.block_break", "org.bukkit.event.block.BlockBreakEvent"),
            Map.entry("paper.block_place", "org.bukkit.event.block.BlockPlaceEvent"),
            Map.entry("paper.player_command", "org.bukkit.event.player.PlayerCommandPreprocessEvent"),
            Map.entry("paper.inventory_click", "org.bukkit.event.inventory.InventoryClickEvent")
    );
    private static final String LEADERBOARD_KEY_SEPARATOR = "::lb::";

    private final eSCorePlugin plugin;
    private final PlayerDataStore dataStore;
    private final eScoreApi api;
    private final eScoreLogger log;
    private final Listener bridgeListener = new Listener() {
    };
    private final ThreadLocal<ArrayDeque<String>> placeholderStack = ThreadLocal.withInitial(ArrayDeque::new);

    private final Map<String, eScoreScript> scripts = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new ConcurrentHashMap<>();
    private final Map<String, List<WatcherBinding>> channelWatchers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> dependentPlaceholders = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> dataKeyDependents = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> storageKeyOwners = new ConcurrentHashMap<>();
    private final Map<PlaceholderCacheKey, CacheEntry> placeholderCache = new ConcurrentHashMap<>();
    private final Map<PlaceholderCacheKey, CacheEntry> sourceCache = new ConcurrentHashMap<>();
    private final Map<String, ScriptProfile> profiles = new ConcurrentHashMap<>();
    private final List<ScriptRuntime> runtimes = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> registeredBypassPermissions = ConcurrentHashMap.newKeySet();

    public ScriptManager(eSCorePlugin plugin, PlayerDataStore dataStore, eScoreApi api, eScoreLogger log) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.api = api;
        this.log = log;
    }

    public synchronized ScriptLoadReport reload() {
        ScriptLoadReport report = buildCandidate(true);
        if (!report.hasErrors()) {
            log.success("Loaded " + report.getPlaceholders() + " placeholder script(s).");
        }
        return report;
    }

    public synchronized ScriptLoadReport check() {
        return buildCandidate(false);
    }

    public Collection<String> getPlaceholderIds() {
        return new LinkedHashSet<>(scripts.keySet());
    }

    public Collection<eScoreScript> getScripts() {
        return List.copyOf(scripts.values());
    }

    public boolean isNumeric(String placeholderId) {
        String key = normalize(placeholderId);
        eScoreScript script = scripts.get(aliases.getOrDefault(key, key));
        return script != null && script.numeric();
    }

    public eScoreScript getScriptByPlaceholder(String placeholderId) {
        String key = normalize(placeholderId);
        return scripts.get(aliases.getOrDefault(key, key));
    }

    public eScoreScript getPrimaryScriptByStorageKey(String storageKey) {
        String normalizedStorageKey = normalize(storageKey);
        Set<String> owners = storageKeyOwners.getOrDefault(normalizedStorageKey, Set.of());
        if (owners.isEmpty()) {
            for (eScoreScript script : scripts.values()) {
                if (!script.leaderboardDefinition().enabled()) {
                    continue;
                }
                for (String leaderboardStorageKey : activeLeaderboardStorageKeys(script, System.currentTimeMillis())) {
                    if (leaderboardStorageKey.equals(normalizedStorageKey)) {
                        return script;
                    }
                }
            }
            return null;
        }
        return scripts.get(owners.iterator().next());
    }

    public Optional<Object> resolveContext(OfflinePlayer player, String placeholderId) {
        eScoreScript script = getScriptByPlaceholder(placeholderId);
        if (script == null) {
            return Optional.empty();
        }
        if (script.contextFunction() == null && script.contextValue() == null) {
            return Optional.empty();
        }

        Object sourceValue = resolveBaseValue(script, player);
        ScriptInvocationContext context = new ScriptInvocationContext(script, player, sourceValue);
        try {
            synchronized (script.runtime()) {
                if (script.contextFunction() != null) {
                    return Optional.ofNullable(convertValue(script.contextFunction().execute(sourceValue, context)));
                }
                return Optional.ofNullable(script.contextValue());
            }
        } catch (Exception exception) {
            log.debug("Failed to resolve script context for " + script.id() + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    public Set<String> getPersistentKeys() {
        Set<String> keys = new LinkedHashSet<>();
        long now = System.currentTimeMillis();
        for (eScoreScript script : scripts.values()) {
            if (script.persistenceMode() == PersistenceMode.BACKEND) {
                keys.add(script.storageKey());
                for (String leaderboardKey : activeLeaderboardStorageKeys(script, now)) {
                    keys.add(leaderboardKey);
                }
            }
        }
        return keys;
    }

    public List<String> getProfileLines() {
        return getProfileLines(Integer.MAX_VALUE);
    }

    public List<String> getProfileLines(int limit) {
        List<ScriptProfileSnapshot> snapshots = getProfileSnapshots(limit);
        List<String> lines = new ArrayList<>(snapshots.size());
        for (ScriptProfileSnapshot snapshot : snapshots) {
            lines.add(snapshot.id()
                    + ": calls=" + snapshot.calls()
                    + ", total=" + new DecimalFormat("0.00").format(snapshot.totalMillis()) + "ms"
                    + ", avg=" + new DecimalFormat("0.00").format(snapshot.avgMillis()) + "ms"
                    + ", max=" + new DecimalFormat("0.00").format(snapshot.maxMillis()) + "ms");
        }
        return lines;
    }

    public List<ScriptProfileSnapshot> getProfileSnapshots(int limit) {
        List<Map.Entry<String, ScriptProfile>> entries = new ArrayList<>(profiles.entrySet());
        entries.sort((left, right) -> {
            int total = Long.compare(right.getValue().totalNanos().get(), left.getValue().totalNanos().get());
            if (total != 0) {
                return total;
            }
            return Long.compare(right.getValue().maxNanos().get(), left.getValue().maxNanos().get());
        });
        List<ScriptProfileSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, ScriptProfile> entry : entries) {
            long calls = entry.getValue().calls().get();
            if (calls == 0L) {
                continue;
            }
            double avgMillis = entry.getValue().totalNanos().get() / 1_000_000D / calls;
            double totalMillis = entry.getValue().totalNanos().get() / 1_000_000D;
            double maxMillis = entry.getValue().maxNanos().get() / 1_000_000D;
            snapshots.add(new ScriptProfileSnapshot(entry.getKey(), calls, totalMillis, avgMillis, maxMillis));
            if (snapshots.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return snapshots;
    }

    public void onPlayerValueChange(PlayerValueChange change) {
        eScoreScript script = getPrimaryScriptByStorageKey(change.key());
        if (script == null || !script.leaderboardDefinition().enabled()) {
            return;
        }

        String normalizedKey = normalize(change.key());
        if (!normalizedKey.equals(script.storageKey())) {
            return;
        }

        updateLeaderboardBuckets(script, change.playerId(), change.oldValue(), change.newValue(), change.origin(), change.updatedAt());
    }

    public void handleChannelEvent(String channel, UUID playerId, Map<String, Object> payload) {
        String normalized = normalize(channel);
        List<WatcherBinding> bindings = channelWatchers.get(normalized);
        if (bindings == null || bindings.isEmpty()) {
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
        ScriptChannelEvent event = new ScriptChannelEvent(normalized, playerId, payload);
        for (WatcherBinding binding : bindings) {
            processWatch(binding, event, offlinePlayer);
        }
    }

    public void shutdown() {
        HandlerList.unregisterAll(bridgeListener);
        unregisterBypassPermissions();
        synchronized (runtimes) {
            for (ScriptRuntime runtime : runtimes) {
                runtime.close();
            }
            runtimes.clear();
        }
        scripts.clear();
        aliases.clear();
        channelWatchers.clear();
        dependentPlaceholders.clear();
        dataKeyDependents.clear();
        storageKeyOwners.clear();
        placeholderCache.clear();
        sourceCache.clear();
        profiles.clear();
    }

    private ScriptLoadReport buildCandidate(boolean activate) {
        ScriptLoadReport report = new ScriptLoadReport(!activate);
        Path scriptsDirectory = plugin.getScriptsDirectory();
        Map<String, eScoreScript> newScripts = new LinkedHashMap<>();
        Map<String, String> newAliases = new LinkedHashMap<>();
        Map<String, List<WatcherBinding>> newChannelWatchers = new LinkedHashMap<>();
        Map<String, Set<String>> newDependentPlaceholders = new LinkedHashMap<>();
        Map<String, Set<String>> newDataKeyDependents = new LinkedHashMap<>();
        Map<String, Set<String>> newStorageKeyOwners = new LinkedHashMap<>();
        List<WatcherBinding> eventBindings = new ArrayList<>();
        List<ScriptRuntime> newRuntimes = new ArrayList<>();

        try {
            Files.createDirectories(scriptsDirectory);
        } catch (IOException exception) {
            report.error("Cannot create scripts directory: " + exception.getMessage());
            return report;
        }

        try (var stream = Files.walk(scriptsDirectory)) {
            List<Path> files = stream
                    .filter(path -> isLoadableScriptFile(scriptsDirectory, path))
                    .sorted()
                    .toList();

            for (Path file : files) {
                report.incrementScannedFiles();
                try {
                    CompiledScriptFile compiled = compileFile(file);
                    if (compiled.scripts().isEmpty()) {
                        report.warn(file.getFileName() + ": file did not register any placeholders.");
                        compiled.runtime().close();
                        continue;
                    }

                    boolean accepted = false;
                    for (eScoreScript script : compiled.scripts()) {
                        if (newScripts.containsKey(script.id())) {
                            report.error(file.getFileName() + ": duplicate placeholder id '" + script.id() + "'.");
                            continue;
                        }
                        newScripts.put(script.id(), script);
                        newAliases.put(script.id(), script.id());
                        newStorageKeyOwners.computeIfAbsent(script.storageKey(), ignored -> new LinkedHashSet<>()).add(script.id());

                        if (script.source().type() == ScriptValueSource.Type.PLAYER_DATA && script.source().key() != null) {
                            newDataKeyDependents.computeIfAbsent(script.source().key(), ignored -> new LinkedHashSet<>()).add(script.id());
                        }
                        for (String dependency : script.dependencies()) {
                            newDependentPlaceholders.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(script.id());
                        }

                        for (String alias : script.aliases()) {
                            if (newAliases.containsKey(alias)) {
                                report.error(file.getFileName() + ": duplicate alias '" + alias + "'.");
                                continue;
                            }
                            newAliases.put(alias, script.id());
                        }

                        for (eScoreScript.WatchDefinition watch : script.watches()) {
                            WatcherBinding binding = new WatcherBinding(script, watch);
                            if (watch.channel() != null) {
                                newChannelWatchers.computeIfAbsent(watch.channel(), ignored -> new ArrayList<>()).add(binding);
                            }
                            if (watch.eventClass() != null) {
                                eventBindings.add(binding);
                            }
                        }

                        accepted = true;
                    }

                    if (accepted) {
                        newRuntimes.add(compiled.runtime());
                        report.incrementLoadedFiles();
                        report.addPlaceholders(compiled.scripts().size());
                        report.addWatchers(compiled.watcherCount());
                    } else {
                        compiled.runtime().close();
                    }
                } catch (Exception exception) {
                    report.error(file.getFileName() + ": " + exception.getMessage());
                    if (plugin.getConfig().getBoolean("scripts.stop-on-first-error", false)) {
                        break;
                    }
                }
            }
        } catch (IOException exception) {
            report.error("Cannot read scripts directory: " + exception.getMessage());
        }

        if (activate) {
            swapState(newScripts, newAliases, newChannelWatchers, newDependentPlaceholders, newDataKeyDependents, newStorageKeyOwners, eventBindings, newRuntimes);
        } else {
            for (ScriptRuntime runtime : newRuntimes) {
                runtime.close();
            }
        }
        return report;
    }

    private boolean isLoadableScriptFile(Path scriptsDirectory, Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".js")) {
            return false;
        }
        Path relative = scriptsDirectory.relativize(path);
        for (Path part : relative) {
            if ("lib".equalsIgnoreCase(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private void swapState(
            Map<String, eScoreScript> newScripts,
            Map<String, String> newAliases,
            Map<String, List<WatcherBinding>> newChannelWatchers,
            Map<String, Set<String>> newDependentPlaceholders,
            Map<String, Set<String>> newDataKeyDependents,
            Map<String, Set<String>> newStorageKeyOwners,
            List<WatcherBinding> eventBindings,
            List<ScriptRuntime> newRuntimes
    ) {
        HandlerList.unregisterAll(bridgeListener);
        unregisterBypassPermissions();
        synchronized (runtimes) {
            for (ScriptRuntime runtime : runtimes) {
                runtime.close();
            }
            runtimes.clear();
            runtimes.addAll(newRuntimes);
        }

        scripts.clear();
        scripts.putAll(newScripts);
        registerBypassPermissions(newScripts.values());

        aliases.clear();
        aliases.putAll(newAliases);

        channelWatchers.clear();
        channelWatchers.putAll(newChannelWatchers);
        dependentPlaceholders.clear();
        dependentPlaceholders.putAll(newDependentPlaceholders);
        dataKeyDependents.clear();
        dataKeyDependents.putAll(newDataKeyDependents);
        storageKeyOwners.clear();
        storageKeyOwners.putAll(newStorageKeyOwners);
        placeholderCache.clear();
        sourceCache.clear();
        profiles.clear();

        for (WatcherBinding binding : eventBindings) {
            registerEventWatcher(binding);
        }
    }

    private void registerBypassPermissions(Collection<eScoreScript> loadedScripts) {
        for (eScoreScript script : loadedScripts) {
            String permissionName = script.bypassPermission();
            if (permissionName == null || permissionName.isBlank()) {
                continue;
            }
            registerPermissionIfMissing(permissionName);
            registerPermissionIfMissing(legacyBypassPermission(permissionName));
        }
    }

    private void unregisterBypassPermissions() {
        for (String permissionName : registeredBypassPermissions) {
            Permission permission = Bukkit.getPluginManager().getPermission(permissionName);
            if (permission != null) {
                Bukkit.getPluginManager().removePermission(permission);
            }
        }
        registeredBypassPermissions.clear();
    }

    private void registerPermissionIfMissing(String permissionName) {
        if (permissionName == null || permissionName.isBlank() || Bukkit.getPluginManager().getPermission(permissionName) != null) {
            return;
        }
        try {
            Bukkit.getPluginManager().addPermission(new Permission(permissionName, PermissionDefault.FALSE));
            registeredBypassPermissions.add(permissionName);
        } catch (IllegalArgumentException ignored) {
            // Permission was registered by another reload path.
        }
    }

    private String legacyBypassPermission(String permissionName) {
        if (permissionName == null || !permissionName.startsWith("escore.bypass.")) {
            return null;
        }
        return "wsc.bypass." + permissionName.substring("escore.bypass.".length());
    }

    private CompiledScriptFile compileFile(Path file) throws IOException {
        String sourceCode = Files.readString(file);
        Context context = Context.newBuilder("js")
                .allowExperimentalOptions(true)
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(className -> false)
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.ecmascript-version", "2022")
                .option("js.nashorn-compat", "true")
                .build();

        ScriptRuntime runtime = new ScriptRuntime(file, context);
        ScriptBootstrap bootstrap = new ScriptBootstrap(runtime, plugin.getScriptsDirectory(), log);
        context.getBindings("js").putMember("wscore", bootstrap);
        context.getBindings("js").putMember("escore", bootstrap);
        context.eval(Source.newBuilder("js", BOOTSTRAP_PRELUDE, "escore-bootstrap.js").buildLiteral());
        context.eval(Source.newBuilder("js", sourceCode, file.getFileName().toString()).build());

        List<eScoreScript> loadedScripts = new ArrayList<>();
        int watcherCount = 0;
        for (Value definition : bootstrap.definitions()) {
            eScoreScript script = parseDefinition(file, runtime, definition);
            loadedScripts.add(script);
            watcherCount += script.watches().size();
        }
        return new CompiledScriptFile(runtime, loadedScripts, watcherCount);
    }

    private eScoreScript parseDefinition(Path file, ScriptRuntime runtime, Value definition) {
        String rawId = readString(definition, "id");
        if (rawId == null || rawId.isBlank()) {
            throw new IllegalArgumentException("script without id");
        }

        String id = normalize(rawId);
        if (!id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("placeholder id '" + rawId + "' must use [a-z0-9_]");
        }

        Set<String> aliases = new LinkedHashSet<>();
        Value aliasesValue = definition.getMember("aliases");
        if (aliasesValue != null && aliasesValue.hasArrayElements()) {
            for (long index = 0; index < aliasesValue.getArraySize(); index++) {
                String alias = normalize(aliasesValue.getArrayElement(index).asString());
                if (!alias.matches("[a-z0-9_]+")) {
                    throw new IllegalArgumentException("alias '" + alias + "' is invalid");
                }
                aliases.add(alias);
            }
        }

        Object defaultValue = convertValue(definition.getMember("defaultValue"));
        boolean numeric = readBoolean(definition, "numeric")
                || "number".equalsIgnoreCase(readString(definition, "valueType"));
        long cacheTtlMillis = readLong(definition, "cacheTtlMillis", plugin.getConfig().getLong("scripts.cache.default-placeholder-ttl-millis", 250L));
        long sourceRefreshTtlMillis = readLong(definition, "sourceRefreshTtlMillis", plugin.getConfig().getLong("scripts.cache.default-source-refresh-ttl-millis", 250L));
        String storageKey = Optional.ofNullable(readString(definition, "storageKey"))
                .map(this::normalize)
                .orElse(id);
        ScriptValueSource source = parseSource(definition.getMember("source"), storageKey, defaultValue);
        Value persistenceValue = definition.getMember("persistence");
        PersistenceMode persistenceMode = parsePersistenceMode(persistenceValue, definition.getMember("watch"));
        long flushIntervalMillis = readLong(persistenceValue, "flushIntervalMillis", readLong(definition, "flushIntervalMillis", 1000L));
        Value syncValue = definition.getMember("sync");
        SyncMode syncMode = parseSyncMode(syncValue, persistenceMode);
        long remoteTtlMillis = readLong(syncValue, "remoteTtlMillis", plugin.getConfig().getLong("sync.redis.runtime-ttl-millis", 300000L));
        Set<String> dependencies = readStringArray(definition.getMember("dependsOn"));
        if (source.type() == ScriptValueSource.Type.WSCORE && source.key() != null) {
            dependencies.add(source.key());
        }
        Value transform = executable(definition.getMember("transform"));
        Value render = executable(definition.getMember("render"));
        Value contextFunction = executable(definition.getMember("context"));
        Object contextValue = contextFunction == null ? convertValue(definition.getMember("context")) : null;
        ScriptNoDataPolicy noDataPolicy = parseNoDataPolicy(definition, null);
        ScriptLeaderboardDefinition leaderboardDefinition = parseLeaderboardDefinition(definition.getMember("leaderboards"), id, numeric, noDataPolicy);

        List<eScoreScript.WatchDefinition> watches = new ArrayList<>();
        Value watchValue = definition.getMember("watch");
        if (watchValue != null && watchValue.hasArrayElements()) {
            for (long index = 0; index < watchValue.getArraySize(); index++) {
                watches.add(parseWatch(watchValue.getArrayElement(index)));
            }
        }

        // bypass: true           → "wsc.bypass.<id>"
        // bypass: false/default  → null (отключено)
        // bypassPermission: "x"  → "x" (кастомное разрешение)
        Value bypassValue = definition.getMember("bypass");
        boolean bypassEnabled = bypassValue != null && !bypassValue.isNull() && bypassValue.asBoolean();
        String customBypassPermission = readString(definition, "bypassPermission");
        String bypassPermission = !bypassEnabled ? null
                : (customBypassPermission != null && !customBypassPermission.isBlank()
                ? customBypassPermission
                : "escore.bypass." + id);

        return new eScoreScript(
                file,
                runtime,
                id,
                aliases,
                Optional.ofNullable(readString(definition, "name")).orElse(id),
                Optional.ofNullable(readString(definition, "description")).orElse(""),
                Optional.ofNullable(readString(definition, "author")).orElse("Unknown"),
                Optional.ofNullable(readString(definition, "version")).orElse("1.0.0"),
                storageKey,
                defaultValue,
                numeric,
                cacheTtlMillis,
                sourceRefreshTtlMillis,
                flushIntervalMillis,
                remoteTtlMillis,
                persistenceMode,
                syncMode,
                dependencies,
                source,
                transform,
                render,
                contextFunction,
                contextValue,
                noDataPolicy,
                leaderboardDefinition,
                watches,
                bypassPermission
        );
    }

    @SuppressWarnings("unchecked")
    private eScoreScript.WatchDefinition parseWatch(Value watch) {
        String channel = Optional.ofNullable(readString(watch, "channel")).map(this::normalize).orElse(null);
        String eventClassName = Optional.ofNullable(readString(watch, "event"))
                .map(value -> EVENT_ALIASES.getOrDefault(normalize(value), value))
                .orElse(null);
        Class<? extends Event> eventClass = null;

        if (eventClassName != null && !eventClassName.isBlank()) {
            try {
                Class<?> rawClass = Class.forName(eventClassName, false, plugin.getClass().getClassLoader());
                if (!Event.class.isAssignableFrom(rawClass)) {
                    throw new IllegalArgumentException("Class is not a Bukkit event: " + eventClassName);
                }
                eventClass = (Class<? extends Event>) rawClass;
            } catch (ClassNotFoundException exception) {
                throw new IllegalArgumentException("Unknown event class: " + eventClassName);
            }
        }

        if (eventClass == null && channel == null) {
            throw new IllegalArgumentException("watch must define 'event' or 'channel'");
        }

        Value update = executable(watch.getMember("update"));
        if (update == null) {
            throw new IllegalArgumentException("watch is missing update(event, current, ctx)");
        }

        String priorityName = Optional.ofNullable(readString(watch, "priority"))
                .orElse("NORMAL")
                .toUpperCase(Locale.ROOT);

        return new eScoreScript.WatchDefinition(
                eventClassName,
                eventClass,
                channel,
                EventPriority.valueOf(priorityName),
                readBoolean(watch, "ignoreCancelled"),
                executable(watch.getMember("player")),
                executable(watch.getMember("filter")),
                update
        );
    }

    private ScriptValueSource parseSource(Value sourceValue, String storageKey, Object defaultValue) {
        if (sourceValue == null || sourceValue.isNull()) {
            return new ScriptValueSource(ScriptValueSource.Type.PLAYER_DATA, storageKey, defaultValue);
        }

        if (sourceValue.isString()) {
            String source = sourceValue.asString();
            if (source.startsWith("%") && source.endsWith("%")) {
                return new ScriptValueSource(ScriptValueSource.Type.PLACEHOLDER_API, source, defaultValue);
            }
            if (source.startsWith("escore:") || source.startsWith("wscore:")) {
                String prefix = source.startsWith("escore:") ? "escore:" : "wscore:";
                return new ScriptValueSource(
                        ScriptValueSource.Type.WSCORE,
                        normalize(source.substring(prefix.length())),
                        defaultValue
                );
            }
            if (source.startsWith("data:")) {
                return new ScriptValueSource(
                        ScriptValueSource.Type.PLAYER_DATA,
                        normalize(source.substring("data:".length())),
                        defaultValue
                );
            }
            if (source.startsWith("hook:")) {
                return new ScriptValueSource(
                        ScriptValueSource.Type.HOOK,
                        normalizeHookReference(source.substring("hook:".length())),
                        defaultValue
                );
            }
            return new ScriptValueSource(ScriptValueSource.Type.CONSTANT, null, source);
        }

        String type = Optional.ofNullable(readString(sourceValue, "type"))
                .orElse("player-data")
                .toLowerCase(Locale.ROOT);

        return switch (type) {
            case "player-data", "data" -> new ScriptValueSource(
                    ScriptValueSource.Type.PLAYER_DATA,
                    Optional.ofNullable(readString(sourceValue, "key")).map(this::normalize).orElse(storageKey),
                    defaultValue
            );
            case "placeholderapi", "papi" -> new ScriptValueSource(
                    ScriptValueSource.Type.PLACEHOLDER_API,
                    readString(sourceValue, "value"),
                    defaultValue
            );
            case "escore", "wscore", "internal" -> new ScriptValueSource(
                    ScriptValueSource.Type.WSCORE,
                    Optional.ofNullable(readString(sourceValue, "placeholder")).map(this::normalize).orElse(storageKey),
                    defaultValue
            );
            case "hook", "provider", "integration-hook" -> new ScriptValueSource(
                    ScriptValueSource.Type.HOOK,
                    normalizeHookReference(
                            Optional.ofNullable(readString(sourceValue, "provider")).orElse(readString(sourceValue, "id")),
                            Optional.ofNullable(readString(sourceValue, "key")).orElse(readString(sourceValue, "value"))
                    ),
                    defaultValue
            );
            case "constant" -> new ScriptValueSource(
                    ScriptValueSource.Type.CONSTANT,
                    null,
                    convertValue(sourceValue.getMember("value"))
            );
            default -> throw new IllegalArgumentException("Unknown source type: " + type);
        };
    }

    private String normalizeHookReference(String rawReference) {
        if (rawReference == null || rawReference.isBlank()) {
            throw new IllegalArgumentException("hook source must define provider:key");
        }
        int separator = rawReference.indexOf(':');
        if (separator <= 0 || separator + 1 >= rawReference.length()) {
            throw new IllegalArgumentException("hook source must define provider:key");
        }
        return normalizeHookReference(rawReference.substring(0, separator), rawReference.substring(separator + 1));
    }

    private String normalizeHookReference(String providerId, String key) {
        String normalizedProvider = normalize(providerId);
        String normalizedKey = normalize(key);
        if (normalizedProvider.isBlank() || normalizedKey.isBlank()) {
            throw new IllegalArgumentException("hook source must define provider and key");
        }
        return normalizedProvider + ':' + normalizedKey;
    }

    private PersistenceMode parsePersistenceMode(Value persistenceValue, Value watchValue) {
        String mode = Optional.ofNullable(readString(persistenceValue, "mode")).orElse(null);
        if (mode == null) {
            boolean hasWatch = watchValue != null && watchValue.hasArrayElements() && watchValue.getArraySize() > 0;
            return hasWatch ? PersistenceMode.BACKEND : PersistenceMode.MEMORY;
        }
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "memory", "none", "volatile" -> PersistenceMode.MEMORY;
            case "backend", "persistent", "db", "database" -> PersistenceMode.BACKEND;
            default -> throw new IllegalArgumentException("Unknown persistence mode: " + mode);
        };
    }

    private SyncMode parseSyncMode(Value syncValue, PersistenceMode persistenceMode) {
        String mode = Optional.ofNullable(readString(syncValue, "mode"))
                .orElse(persistenceMode == PersistenceMode.BACKEND ? "auto" : "none");
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "none", "off" -> SyncMode.NONE;
            case "auto" -> SyncMode.AUTO;
            case "redis" -> SyncMode.REDIS;
            case "database", "db" -> SyncMode.DATABASE;
            default -> throw new IllegalArgumentException("Unknown sync mode: " + mode);
        };
    }

    private ScriptNoDataPolicy parseNoDataPolicy(Value holder, ScriptNoDataPolicy fallback) {
        String zeroDataPlaceholder = readStringAny(holder, "zeroDataPlaceholder", "zero_data_placeholder");
        Value hasDataFunction = executable(memberOrNull(holder, "hasData", "has_data"));
        Value noDataWhenFunction = executable(memberOrNull(holder, "noDataWhen", "no_data_when"));

        if (zeroDataPlaceholder == null && fallback != null) {
            zeroDataPlaceholder = fallback.zeroDataPlaceholder();
        }
        if (hasDataFunction == null && fallback != null) {
            hasDataFunction = fallback.hasDataFunction();
        }
        if (noDataWhenFunction == null && fallback != null) {
            noDataWhenFunction = fallback.noDataWhenFunction();
        }

        return new ScriptNoDataPolicy(zeroDataPlaceholder, hasDataFunction, noDataWhenFunction);
    }

    private ScriptLeaderboardDefinition parseLeaderboardDefinition(
            Value leaderboardsValue,
            String scriptId,
            boolean numeric,
            ScriptNoDataPolicy scriptNoDataPolicy
    ) {
        if (leaderboardsValue == null || leaderboardsValue.isNull()) {
            return new ScriptLeaderboardDefinition(parseNoDataPolicy(null, scriptNoDataPolicy), Map.of());
        }
        if (leaderboardsValue.isBoolean() && !leaderboardsValue.asBoolean()) {
            return new ScriptLeaderboardDefinition(parseNoDataPolicy(null, scriptNoDataPolicy), Map.of());
        }
        if (!numeric) {
            throw new IllegalArgumentException("leaderboards require numeric: true for script '" + scriptId + "'");
        }

        ScriptNoDataPolicy leaderboardNoDataPolicy = parseNoDataPolicy(leaderboardsValue, scriptNoDataPolicy);
        Map<String, ScriptLeaderboardDefinition.PeriodDefinition> periods = new LinkedHashMap<>();
        Value periodsValue = memberOrNull(leaderboardsValue, "periods", "leaderboardPeriods", "leaderboard_periods");

        if (periodsValue == null || periodsValue.isNull()) {
            periods.put("alltime", new ScriptLeaderboardDefinition.PeriodDefinition(
                    "alltime",
                    ScriptLeaderboardDefinition.PeriodMode.ALLTIME,
                    null,
                    null
            ));
        } else if (periodsValue.hasArrayElements()) {
            for (long index = 0; index < periodsValue.getArraySize(); index++) {
                String periodId = normalize(periodsValue.getArrayElement(index).asString());
                periods.put(periodId, parseLeaderboardPeriodDefinition(periodId, periodsValue.getArrayElement(index)));
            }
        } else if (periodsValue.hasMembers()) {
            List<String> keys = new ArrayList<>(periodsValue.getMemberKeys());
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            for (String rawPeriodId : keys) {
                String periodId = normalize(rawPeriodId);
                if (!periodId.matches("[a-z0-9_]+")) {
                    throw new IllegalArgumentException("leaderboard period id '" + rawPeriodId + "' is invalid");
                }
                periods.put(periodId, parseLeaderboardPeriodDefinition(periodId, periodsValue.getMember(rawPeriodId)));
            }
        } else {
            throw new IllegalArgumentException("leaderboards.periods must be an array or object");
        }

        return new ScriptLeaderboardDefinition(leaderboardNoDataPolicy, Map.copyOf(periods));
    }

    private ScriptLeaderboardDefinition.PeriodDefinition parseLeaderboardPeriodDefinition(String periodId, Value value) {
        if (value == null || value.isNull()) {
            return new ScriptLeaderboardDefinition.PeriodDefinition(
                    periodId,
                    periodMode(periodId),
                    null,
                    null
            );
        }
        if (value.isString()) {
            return new ScriptLeaderboardDefinition.PeriodDefinition(
                    periodId,
                    periodMode(value.asString()),
                    null,
                    null
            );
        }

        String rawMode = Optional.ofNullable(readStringAny(value, "mode", "type", "bucket", "bucketType"))
                .orElse(periodId);
        ScriptLeaderboardDefinition.PeriodMode mode = periodMode(rawMode);
        String pattern = readString(value, "pattern");
        String zoneId = readStringAny(value, "zone", "zoneId", "zone_id", "timezone");
        if (mode == ScriptLeaderboardDefinition.PeriodMode.PATTERN && (pattern == null || pattern.isBlank())) {
            throw new IllegalArgumentException("leaderboard period '" + periodId + "' with mode=pattern requires 'pattern'");
        }
        return new ScriptLeaderboardDefinition.PeriodDefinition(periodId, mode, pattern, zoneId);
    }

    private ScriptLeaderboardDefinition.PeriodMode periodMode(String rawMode) {
        String normalized = normalize(rawMode);
        return switch (normalized) {
            case "alltime", "all_time", "total", "lifetime" -> ScriptLeaderboardDefinition.PeriodMode.ALLTIME;
            case "hour", "hourly" -> ScriptLeaderboardDefinition.PeriodMode.HOUR;
            case "day", "daily" -> ScriptLeaderboardDefinition.PeriodMode.DAY;
            case "week", "weekly" -> ScriptLeaderboardDefinition.PeriodMode.WEEK;
            case "month", "monthly" -> ScriptLeaderboardDefinition.PeriodMode.MONTH;
            case "year", "yearly" -> ScriptLeaderboardDefinition.PeriodMode.YEAR;
            case "pattern", "custom" -> ScriptLeaderboardDefinition.PeriodMode.PATTERN;
            default -> throw new IllegalArgumentException("Unknown leaderboard period mode: " + rawMode);
        };
    }

    public Optional<String> resolvePlaceholder(OfflinePlayer player, String id) {
        String normalized = normalize(id);
        String resolvedId = aliases.getOrDefault(normalized, normalized);
        eScoreScript script = scripts.get(resolvedId);
        ScriptLeaderboardRequest leaderboardRequest = null;
        if (script == null) {
            leaderboardRequest = parseLeaderboardRequest(normalized);
            if (leaderboardRequest == null) {
                return Optional.empty();
            }
            String leaderboardBaseId = aliases.getOrDefault(leaderboardRequest.baseId(), leaderboardRequest.baseId());
            script = scripts.get(leaderboardBaseId);
            if (script == null || !script.leaderboardDefinition().enabled()) {
                return Optional.empty();
            }
        }

        ArrayDeque<String> stack = placeholderStack.get();
        if (stack.contains(script.id())) {
            return Optional.empty();
        }

        stack.push(script.id());
        try {
            if (leaderboardRequest != null) {
                return Optional.of(resolveLeaderboardPlaceholder(script, leaderboardRequest, player));
            }
            if (player != null && script.cacheTtlMillis() > 0L) {
                return Optional.ofNullable(resolveCachedPlaceholder(script, player));
            }
            return Optional.ofNullable(renderPlaceholder(script, player));
        } catch (Exception exception) {
            log.warn("Failed to resolve script placeholder " + script.id() + ": " + exception.getMessage());
            return Optional.empty();
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                placeholderStack.remove();
            }
        }
    }

    private ScriptLeaderboardRequest parseLeaderboardRequest(String normalizedId) {
        int fieldSeparator = normalizedId.lastIndexOf('_');
        if (fieldSeparator <= 0 || fieldSeparator >= normalizedId.length() - 1) {
            return null;
        }

        String field = normalizedId.substring(fieldSeparator + 1);
        if (!field.equals("name") && !field.equals("value") && !field.equals("uuid")) {
            return null;
        }

        String withoutField = normalizedId.substring(0, fieldSeparator);
        int periodSeparator = withoutField.lastIndexOf('_');
        if (periodSeparator <= 0 || periodSeparator >= withoutField.length() - 1) {
            return null;
        }

        String periodId = normalize(withoutField.substring(periodSeparator + 1));
        String withoutPeriod = withoutField.substring(0, periodSeparator);
        int positionSeparator = withoutPeriod.lastIndexOf('_');
        if (positionSeparator <= 0 || positionSeparator >= withoutPeriod.length() - 1) {
            return null;
        }

        int position;
        try {
            position = Integer.parseInt(withoutPeriod.substring(positionSeparator + 1));
        } catch (NumberFormatException exception) {
            return null;
        }
        if (position < 1) {
            return null;
        }

        String baseId = normalize(withoutPeriod.substring(0, positionSeparator));
        if (baseId.isBlank()) {
            return null;
        }
        return new ScriptLeaderboardRequest(baseId, position, periodId, field);
    }

    private String resolveLeaderboardPlaceholder(eScoreScript script, ScriptLeaderboardRequest request, OfflinePlayer player) {
        ScriptLeaderboardEntry entry = resolveLeaderboardEntry(script, request.position(), request.periodId());
        ScriptNoDataPolicy noDataPolicy = script.leaderboardDefinition().noDataPolicy();
        ScriptInvocationContext context = new ScriptInvocationContext(script, player, entry);

        synchronized (script.runtime()) {
            if (entry.isMissing() || isNoData(noDataPolicy, entry, context)) {
                return noDataPolicy.placeholderOrEmpty();
            }
        }

        return switch (request.field()) {
            case "uuid" -> entry.uuid();
            case "name" -> entry.name();
            case "value" -> formatLeaderboardValue(entry.value());
            default -> noDataPolicy.placeholderOrEmpty();
        };
    }

    private ScriptLeaderboardEntry resolveLeaderboardEntry(eScoreScript script, int position, String periodId) {
        ScriptLeaderboardDefinition leaderboardDefinition = script.leaderboardDefinition();
        ScriptLeaderboardDefinition.PeriodDefinition periodDefinition = leaderboardDefinition.periods().get(normalize(periodId));
        if (periodDefinition == null) {
            return ScriptLeaderboardEntry.missing(script, periodId, position);
        }

        String storageKey = leaderboardStorageKey(script, periodDefinition, System.currentTimeMillis());
        List<Map.Entry<UUID, Double>> entries = dataStore.getTopNumbers(storageKey, position);
        if (entries.size() < position) {
            return ScriptLeaderboardEntry.missing(script, periodId, position);
        }

        Map.Entry<UUID, Double> entry = entries.get(position - 1);
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(entry.getKey());
        String name = offlinePlayer.getName() == null ? entry.getKey().toString() : offlinePlayer.getName();
        return new ScriptLeaderboardEntry(
                script.id(),
                periodDefinition.id(),
                position,
                entry.getKey().toString(),
                name,
                entry.getValue(),
                true
        );
    }

    private String formatLeaderboardValue(double value) {
        return value % 1D == 0D ? Long.toString((long) value) : Double.toString(value);
    }

    private boolean isNoData(ScriptNoDataPolicy policy, Object value, ScriptInvocationContext context) {
        if (policy == null) {
            return value == null;
        }
        if (policy.noDataWhenFunction() != null) {
            Object decision = convertValue(policy.noDataWhenFunction().execute(value, context));
            if (decision instanceof Boolean bool) {
                return bool;
            }
        }
        if (policy.hasDataFunction() != null) {
            Object decision = convertValue(policy.hasDataFunction().execute(value, context));
            if (decision instanceof Boolean bool) {
                return !bool;
            }
        }
        return value == null;
    }

    private String zeroDataPlaceholder(ScriptNoDataPolicy policy) {
        return policy == null ? "" : policy.placeholderOrEmpty();
    }

    public void invalidatePlayerCaches(UUID playerId) {
        placeholderCache.keySet().removeIf(key -> key.playerId().equals(playerId));
        sourceCache.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public void invalidateAllCaches() {
        placeholderCache.clear();
        sourceCache.clear();
    }

    public void invalidateStorageKey(UUID playerId, String storageKey) {
        String normalizedKey = normalize(storageKey);
        Set<String> affected = new LinkedHashSet<>();
        affected.addAll(storageKeyOwners.getOrDefault(normalizedKey, Set.of()));
        affected.addAll(dataKeyDependents.getOrDefault(normalizedKey, Set.of()));

        ArrayDeque<String> queue = new ArrayDeque<>(affected);
        while (!queue.isEmpty()) {
            String placeholderId = queue.removeFirst();
            PlaceholderCacheKey cacheKey = new PlaceholderCacheKey(playerId, placeholderId);
            placeholderCache.remove(cacheKey);
            sourceCache.remove(cacheKey);
            for (String dependent : dependentPlaceholders.getOrDefault(placeholderId, Set.of())) {
                if (affected.add(dependent)) {
                    queue.addLast(dependent);
                }
            }
        }
    }

    private String resolveCachedPlaceholder(eScoreScript script, OfflinePlayer player) {
        PlaceholderCacheKey cacheKey = new PlaceholderCacheKey(player.getUniqueId(), script.id());
        CacheEntry cached = placeholderCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() >= now) {
            return cached.value() == null ? "" : String.valueOf(cached.value());
        }

        String rendered = renderPlaceholder(script, player);
        placeholderCache.put(cacheKey, new CacheEntry(rendered, now + script.cacheTtlMillis()));
        return rendered;
    }

    private String renderPlaceholder(eScoreScript script, OfflinePlayer player) {
        long startedAt = System.nanoTime();
        try {
            Object value = resolveBaseValue(script, player);
            ScriptInvocationContext context = new ScriptInvocationContext(script, player, value);

            synchronized (script.runtime()) {
                if (script.transformFunction() != null) {
                    value = convertValue(script.transformFunction().execute(value, context));
                }
                if (isNoData(script.noDataPolicy(), value, context)) {
                    return zeroDataPlaceholder(script.noDataPolicy());
                }
                if (script.renderFunction() != null) {
                    Object rendered = convertValue(script.renderFunction().execute(value, context));
                    return rendered == null ? zeroDataPlaceholder(script.noDataPolicy()) : String.valueOf(rendered);
                }
            }
            return value == null ? zeroDataPlaceholder(script.noDataPolicy()) : String.valueOf(value);
        } finally {
            recordProfile(script.id(), System.nanoTime() - startedAt);
        }
    }

    private void updateLeaderboardBuckets(eScoreScript script, UUID playerId, Object current, Object updated, ChangeOrigin origin, long timestamp) {
        ScriptLeaderboardDefinition leaderboardDefinition = script.leaderboardDefinition();
        if (!leaderboardDefinition.enabled()) {
            return;
        }

        double delta = toNumber(updated) - toNumber(current);
        if (delta == 0D) {
            return;
        }

        for (String storageKey : activeLeaderboardStorageKeys(script, timestamp)) {
            double bucketCurrent = toNumber(dataStore.getValue(playerId, storageKey, 0D));
            double bucketUpdated = bucketCurrent + delta;
            dataStore.setValue(playerId, storageKey, bucketUpdated, origin, timestamp);
        }
    }

    private List<String> activeLeaderboardStorageKeys(eScoreScript script, long timestamp) {
        ScriptLeaderboardDefinition leaderboardDefinition = script.leaderboardDefinition();
        if (!leaderboardDefinition.enabled()) {
            return List.of();
        }

        List<String> keys = new ArrayList<>();
        List<ScriptLeaderboardDefinition.PeriodDefinition> periods = new ArrayList<>(leaderboardDefinition.periods().values());
        periods.sort(Comparator.comparing(ScriptLeaderboardDefinition.PeriodDefinition::id));
        for (ScriptLeaderboardDefinition.PeriodDefinition period : periods) {
            if (period.mode() != ScriptLeaderboardDefinition.PeriodMode.ALLTIME) {
                keys.add(leaderboardStorageKey(script, period, timestamp));
            }
        }
        return keys;
    }

    private String leaderboardStorageKey(eScoreScript script, ScriptLeaderboardDefinition.PeriodDefinition period, long timestamp) {
        if (period.mode() == ScriptLeaderboardDefinition.PeriodMode.ALLTIME) {
            return normalize(script.storageKey());
        }
        return normalize(script.storageKey() + LEADERBOARD_KEY_SEPARATOR + period.id() + LEADERBOARD_KEY_SEPARATOR + leaderboardBucket(period, timestamp));
    }

    private String leaderboardBucket(ScriptLeaderboardDefinition.PeriodDefinition period, long timestamp) {
        ZoneId zoneId = resolveZone(period.zoneId());
        Instant instant = Instant.ofEpochMilli(timestamp);
        return switch (period.mode()) {
            case ALLTIME -> "alltime";
            case HOUR -> DateTimeFormatter.ofPattern("yyyy-MM-dd-HH").withZone(zoneId).format(instant);
            case DAY -> DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zoneId).format(instant);
            case WEEK -> {
                var zonedDateTime = instant.atZone(zoneId);
                WeekFields weekFields = WeekFields.ISO;
                int weekBasedYear = zonedDateTime.get(weekFields.weekBasedYear());
                int weekOfYear = zonedDateTime.get(weekFields.weekOfWeekBasedYear());
                yield weekBasedYear + "-W" + String.format(Locale.ROOT, "%02d", weekOfYear);
            }
            case MONTH -> DateTimeFormatter.ofPattern("yyyy-MM").withZone(zoneId).format(instant);
            case YEAR -> DateTimeFormatter.ofPattern("yyyy").withZone(zoneId).format(instant);
            case PATTERN -> DateTimeFormatter.ofPattern(period.pattern(), Locale.ROOT).withZone(zoneId).format(instant);
        };
    }

    private ZoneId resolveZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(zoneId);
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    private double toNumber(Object value) {
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

    private Object resolveBaseValue(eScoreScript script, OfflinePlayer player) {
        ScriptValueSource source = script.source();
        if (player != null && script.sourceRefreshTtlMillis() > 0L && (source.type() == ScriptValueSource.Type.PLACEHOLDER_API
                || source.type() == ScriptValueSource.Type.WSCORE
                || source.type() == ScriptValueSource.Type.HOOK)) {
            PlaceholderCacheKey cacheKey = new PlaceholderCacheKey(player.getUniqueId(), script.id());
            CacheEntry cached = sourceCache.get(cacheKey);
            long now = System.currentTimeMillis();
            if (cached != null && cached.expiresAt() >= now) {
                return cached.value();
            }
            Object resolved = resolveSourceValue(script, player, source);
            sourceCache.put(cacheKey, new CacheEntry(resolved, now + script.sourceRefreshTtlMillis()));
            return resolved;
        }
        return resolveSourceValue(script, player, source);
    }

    private Object resolveSourceValue(eScoreScript script, OfflinePlayer player, ScriptValueSource source) {
        return switch (source.type()) {
            case PLAYER_DATA -> player == null
                    ? script.defaultValue()
                    : dataStore.getValue(player.getUniqueId(), source.key(), script.defaultValue());
            case PLACEHOLDER_API -> player == null
                    ? script.defaultValue()
                    : plugin.getIntegrationManager().formatWithPlaceholderApi(player, String.valueOf(source.key()), script.defaultValue());
            case WSCORE -> player == null
                    ? script.defaultValue()
                    : resolvePlaceholder(player, source.key()).orElse(script.defaultValue() == null ? null : String.valueOf(script.defaultValue()));
            case HOOK -> player == null
                    ? script.defaultValue()
                    : resolveHookValue(player, source.key(), script.defaultValue());
            case CONSTANT -> source.value();
        };
    }

    private Object resolveHookValue(OfflinePlayer player, String reference, Object fallback) {
        if (reference == null || reference.isBlank()) {
            return fallback;
        }
        int separator = reference.indexOf(':');
        if (separator <= 0 || separator + 1 >= reference.length()) {
            return fallback;
        }
        return api.resolveValueHook(player, reference.substring(0, separator), reference.substring(separator + 1)).orElse(fallback);
    }

    private void registerEventWatcher(WatcherBinding binding) {
        EventExecutor executor = (listener, event) -> {
            if (binding.watch().eventClass() != null && binding.watch().eventClass().isInstance(event)) {
                UUID playerId = extractPlayerId(binding, event);
                if (playerId != null) {
                    processWatch(binding, event, Bukkit.getOfflinePlayer(playerId));
                }
            }
        };

        Bukkit.getPluginManager().registerEvent(
                binding.watch().eventClass(),
                bridgeListener,
                binding.watch().priority(),
                executor,
                plugin,
                binding.watch().ignoreCancelled()
        );
    }

    private void processWatch(WatcherBinding binding, Object event, OfflinePlayer player) {
        if (player == null) {
            return;
        }

        // Bypass-проверка: если у игрока есть глобальное wsc.bypass или скриптовое
        // разрешение (wsc.bypass.<id> или кастомное), watch-событие пропускается.
        String bypassPerm = binding.script().bypassPermission();
        if (bypassPerm != null) {
            Player onlineForBypass = player.getPlayer();
            if (onlineForBypass != null && hasBypassPermission(onlineForBypass, bypassPerm)) {
                log.debug("watch bypassed: player=" + onlineForBypass.getName() + " script=" + binding.script().id());
                return;
            }
        }

        Object current = dataStore.getValue(player.getUniqueId(), binding.script().storageKey(), binding.script().defaultValue());
        ScriptInvocationContext context = new ScriptInvocationContext(binding.script(), player, current);
        long startedAt = System.nanoTime();
        try {
            synchronized (binding.script().runtime()) {
                if (binding.watch().filterFunction() != null) {
                    Object accepted = convertValue(binding.watch().filterFunction().execute(event, current, context));
                    if (!(accepted instanceof Boolean bool) || !bool) {
                        return;
                    }
                }

                Object updated = convertValue(binding.watch().updateFunction().execute(event, current, context));
                dataStore.setValue(player.getUniqueId(), binding.script().storageKey(), updated);
            }
        } finally {
            recordProfile(binding.script().id(), System.nanoTime() - startedAt);
        }
    }

    private UUID extractPlayerId(WatcherBinding binding, Object event) {
        if (binding.watch().playerFunction() != null) {
            synchronized (binding.script().runtime()) {
                Object result = convertValue(binding.watch().playerFunction().execute(event, new ScriptInvocationContext(binding.script(), null, null)));
                UUID playerId = asPlayerId(result);
                if (playerId != null) {
                    return playerId;
                }
            }
        }

        UUID damageSourcePlayerId = tryDamageSourcePlayerId(event);
        if (damageSourcePlayerId != null) {
            return damageSourcePlayerId;
        }

        if (event instanceof PlayerEvent playerEvent) {
            return playerEvent.getPlayer().getUniqueId();
        }
        if (event instanceof EntityEvent entityEvent && entityEvent.getEntity() instanceof Player player) {
            return player.getUniqueId();
        }

        return tryCommonPlayerMethods(event);
    }

    private UUID tryCommonPlayerMethods(Object event) {
        for (String methodName : List.of("getPlayer", "getWhoClicked", "getEntity", "getDamager", "getOfflinePlayer")) {
            try {
                Object result = event.getClass().getMethod(methodName).invoke(event);
                UUID playerId = asPlayerId(result);
                if (playerId != null) {
                    return playerId;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private UUID tryDamageSourcePlayerId(Object event) {
        if (event == null) {
            return null;
        }
        try {
            Object source = event.getClass().getMethod("getDamageSource").invoke(event);
            if (source == null) {
                return null;
            }
            Object causingEntity = source.getClass().getMethod("getCausingEntity").invoke(source);
            UUID causingPlayerId = asPlayerId(causingEntity);
            if (causingPlayerId != null) {
                return causingPlayerId;
            }
            Object directEntity = source.getClass().getMethod("getDirectEntity").invoke(source);
            return asPlayerId(directEntity);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private UUID asPlayerId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof Player player) {
            return player.getUniqueId();
        }
        if (value instanceof OfflinePlayer offlinePlayer) {
            return offlinePlayer.getUniqueId();
        }
        if (value instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Entity shooterEntity) {
                return asPlayerId(shooterEntity);
            }
            return null;
        }
        if (value instanceof Entity entity && entity instanceof Player player) {
            return player.getUniqueId();
        }
        if (value instanceof String string) {
            try {
                return UUID.fromString(string);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private Value executable(Value value) {
        return value != null && value.canExecute() ? value : null;
    }

    private String readString(Value holder, String member) {
        if (holder == null || !holder.hasMember(member)) {
            return null;
        }
        Value value = holder.getMember(member);
        return value == null || value.isNull() ? null : value.asString();
    }

    private String readStringAny(Value holder, String... members) {
        for (String member : members) {
            String value = readString(holder, member);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Value memberOrNull(Value holder, String... members) {
        if (holder == null) {
            return null;
        }
        for (String member : members) {
            if (holder.hasMember(member)) {
                Value value = holder.getMember(member);
                if (value != null && !value.isNull()) {
                    return value;
                }
            }
        }
        return null;
    }

    private boolean readBoolean(Value holder, String member) {
        return holder != null
                && holder.hasMember(member)
                && holder.getMember(member) != null
                && holder.getMember(member).asBoolean();
    }

    private boolean readBooleanAny(Value holder, String... members) {
        for (String member : members) {
            if (holder != null && holder.hasMember(member) && holder.getMember(member) != null && !holder.getMember(member).isNull()) {
                return holder.getMember(member).asBoolean();
            }
        }
        return false;
    }

    private long readLong(Value holder, String member, long fallback) {
        if (holder == null || !holder.hasMember(member) || holder.getMember(member) == null || holder.getMember(member).isNull()) {
            return fallback;
        }
        Value value = holder.getMember(member);
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asString());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private Set<String> readStringArray(Value value) {
        Set<String> items = new LinkedHashSet<>();
        if (value == null || value.isNull() || !value.hasArrayElements()) {
            return items;
        }
        for (long index = 0; index < value.getArraySize(); index++) {
            items.add(normalize(value.getArrayElement(index).asString()));
        }
        return items;
    }

    private Object convertValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            if (value.fitsInInt()) {
                return value.asInt();
            }
            if (value.fitsInLong()) {
                return value.asLong();
            }
            return value.asDouble();
        }
        if (value.isHostObject()) {
            return value.asHostObject();
        }
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long index = 0; index < value.getArraySize(); index++) {
                list.add(convertValue(value.getArrayElement(index)));
            }
            return list;
        }
        if (value.hasMembers()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convertValue(value.getMember(key)));
            }
            return map;
        }
        return value.toString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void recordProfile(String scriptId, long nanos) {
        ScriptProfile profile = profiles.computeIfAbsent(scriptId, ignored -> new ScriptProfile());
        profile.calls().incrementAndGet();
        profile.totalNanos().addAndGet(nanos);
        profile.maxNanos().accumulateAndGet(nanos, Math::max);

        if (plugin.getConfig().getBoolean("scripts.profile.enabled", true)) {
            double millis = nanos / 1_000_000D;
            double threshold = plugin.getConfig().getDouble("scripts.profile.slow-call-threshold-millis", 15D);
            if (millis >= threshold) {
                log.debug("Slow script call: " + scriptId + " took " + new DecimalFormat("0.00").format(millis) + "ms");
            }
        }
    }

    public final class ScriptInvocationContext {
        private final eScoreScript script;
        private final OfflinePlayer player;
        private final Object sourceValue;

        public ScriptInvocationContext(eScoreScript script, OfflinePlayer player, Object sourceValue) {
            this.script = script;
            this.player = player;
            this.sourceValue = sourceValue;
        }

        public String id() {
            return script.id();
        }

        public OfflinePlayer offlinePlayer() {
            return player;
        }

        public Player player() {
            return player != null ? player.getPlayer() : null;
        }

        public boolean online() {
            return player != null && player.isOnline();
        }

        public String playerName() {
            return player == null ? "" : (player.getName() == null ? player.getUniqueId().toString() : player.getName());
        }

        public UUID playerId() {
            return player != null ? player.getUniqueId() : null;
        }

        public Object sourceValue() {
            return sourceValue;
        }

        public long now() {
            return System.currentTimeMillis();
        }

        public double number(Object value, double fallback) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String string) {
                try {
                    return Double.parseDouble(string);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }

        public String string(Object value, String fallback) {
            return value == null ? fallback : String.valueOf(value);
        }

        public boolean bool(Object value) {
            if (value instanceof Boolean bool) {
                return bool;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }

        public boolean isBlank(Object value) {
            return value == null || String.valueOf(value).trim().isEmpty();
        }

        public boolean hasText(Object value) {
            return !isBlank(value);
        }

        public Object data(String key, Object fallback) {
            return player == null ? fallback : dataStore.getValue(player.getUniqueId(), key, fallback);
        }

        public void setData(String key, Object value) {
            if (player != null) {
                dataStore.setValue(player.getUniqueId(), key, value);
            }
        }

        public double add(String key, double delta) {
            return player == null ? 0D : dataStore.addToPlayerNumber(player.getUniqueId(), key, delta);
        }

        public String placeholder(String placeholder) {
            if (player == null) {
                return placeholder;
            }
            return plugin.getIntegrationManager().formatWithPlaceholderApi(player, placeholder, placeholder);
        }

        public String wscore(String placeholderId) {
            return player == null ? "" : api.resolvePlaceholder(player, placeholderId).orElse("");
        }

        public String escore(String placeholderId) {
            return wscore(placeholderId);
        }

        public String integration(String id) {
            return api.getIntegrationStates().getOrDefault(normalize(id), "unknown");
        }

        public Object hook(String providerId, String key, Object fallback) {
            return player == null ? fallback : api.resolveValueHook(player, providerId, key).orElse(fallback);
        }

        public Object scriptContext(String placeholderId) {
            return player == null ? "" : api.getScriptContext(player, placeholderId).orElse("");
        }

        public Map<String, Object> context() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", script.id());
            values.put("name", script.name());
            values.put("description", script.description());
            values.put("author", script.author());
            values.put("version", script.version());
            values.put("storageKey", script.storageKey());
            values.put("playerName", playerName());
            values.put("playerId", playerId() == null ? "" : playerId().toString());
            values.put("online", online());
            values.put("sourceValue", sourceValue);
            return values;
        }

        public Object causingPlayer(Object event) {
            UUID playerId = tryDamageSourcePlayerId(event);
            if (playerId == null) {
                return null;
            }
            Player online = Bukkit.getPlayer(playerId);
            return online != null ? online : Bukkit.getOfflinePlayer(playerId);
        }

        public String causingPlayerId(Object event) {
            UUID playerId = tryDamageSourcePlayerId(event);
            return playerId == null ? null : playerId.toString();
        }

        public boolean setLuckPermsContext(String key, Object value) {
            return player != null && plugin.getIntegrationManager().setLuckPermsContext(player, key, value);
        }

        public boolean removeLuckPermsContext(String key) {
            return player != null && plugin.getIntegrationManager().removeLuckPermsContext(player, key);
        }

        public void clearLuckPermsContexts() {
            if (player != null) {
                plugin.getIntegrationManager().clearLuckPermsContexts(player);
            }
        }

        public Object luckPermsContext(String key, Object fallback) {
            if (player == null) {
                return fallback;
            }
            Object value = plugin.getIntegrationManager().getLuckPermsContext(player, key);
            return value == null ? fallback : value;
        }

        public Object top(String key, int position, String type) {
            if (position < 1) {
                return "";
            }
            List<Map.Entry<UUID, Double>> entries = dataStore.getTopNumbers(key, position);
            if (entries.size() < position) {
                return "";
            }
            Map.Entry<UUID, Double> entry = entries.get(position - 1);
            if ("uuid".equalsIgnoreCase(type)) {
                return entry.getKey().toString();
            }
            if ("name".equalsIgnoreCase(type)) {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(entry.getKey());
                return offlinePlayer.getName() == null ? entry.getKey().toString() : offlinePlayer.getName();
            }
            return entry.getValue();
        }

        public Object leaderboard(String placeholderId, int position, String periodId, String field) {
            eScoreScript targetScript = getScriptByPlaceholder(placeholderId);
            if (targetScript == null || !targetScript.leaderboardDefinition().enabled()) {
                return "";
            }
            ScriptLeaderboardRequest request = new ScriptLeaderboardRequest(
                    normalize(placeholderId),
                    position,
                    normalize(periodId),
                    normalize(field)
            );
            return resolveLeaderboardPlaceholder(targetScript, request, player);
        }

        /**
         * Проверяет, есть ли у онлайн-игрока указанное разрешение.
         * Возвращает {@code false} если игрок оффлайн.
         */
        public boolean hasPermission(String permission) {
            Player online = player();
            return online != null && online.hasPermission(permission);
        }

        /**
         * Проверяет, есть ли у онлайн-игрока хотя бы одно из перечисленных разрешений.
         */
        public boolean hasAnyPermission(Object... permissions) {
            Player online = player();
            if (online == null) {
                return false;
            }
            for (Object perm : permissions) {
                if (online.hasPermission(String.valueOf(perm))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Первичная группа игрока из LuckPerms.
         * Возвращает {@code ""} если LuckPerms не установлен или игрок оффлайн.
         */
        public String group() {
            Player online = player();
            return online == null ? "" : plugin.getIntegrationManager().getPrimaryGroup(online);
        }

        /**
         * Проверяет, состоит ли игрок в указанной группе (или унаследовал её).
         */
        public boolean hasGroup(String group) {
            Player online = player();
            return online != null && plugin.getIntegrationManager().hasGroup(online, group);
        }

        /**
         * Префикс игрока из LuckPerms (с форматированием).
         * Возвращает {@code ""} если LuckPerms не установлен или игрок оффлайн.
         */
        public String prefix() {
            Player online = player();
            return online == null ? "" : plugin.getIntegrationManager().getPrefix(online);
        }

        /**
         * Суффикс игрока из LuckPerms (с форматированием).
         */
        public String suffix() {
            Player online = player();
            return online == null ? "" : plugin.getIntegrationManager().getSuffix(online);
        }

        public void debug(String message) {
            log.debug(script.id() + ": " + message);
        }
    }

    public static final class ScriptRuntime implements AutoCloseable {
        private final Path file;
        private final Context context;

        public ScriptRuntime(Path file, Context context) {
            this.file = file;
            this.context = context;
        }

        public Path file() {
            return file;
        }

        public Context context() {
            return context;
        }

        @Override
        public void close() {
            context.close(true);
        }
    }

    public static final class ScriptBootstrap {
        private final ScriptRuntime runtime;
        private final Path scriptsDirectory;
        private final eScoreLogger log;
        private final List<Value> definitions = new CopyOnWriteArrayList<>();
        private final Map<String, Value> modules = new ConcurrentHashMap<>();
        private final ScriptMath math = new ScriptMath();
        private final ScriptText text = new ScriptText();
        private final ScriptToolkit.Normalize normalize = new ScriptToolkit.Normalize();
        private final ScriptToolkit.Decorate decorate = new ScriptToolkit.Decorate();
        private final ScriptToolkit.Convert convert = new ScriptToolkit.Convert();
        private final ScriptToolkit.Builders builders = new ScriptToolkit.Builders();
        private final ScriptFormat format = new ScriptFormat();
        private final ScriptTime time = new ScriptTime();

        public ScriptBootstrap(ScriptRuntime runtime, Path scriptsDirectory, eScoreLogger log) {
            this.runtime = runtime;
            this.scriptsDirectory = scriptsDirectory;
            this.log = log;
        }

        public void register(Value definition) {
            definitions.add(definition);
        }

        public void registerAll(Value values) {
            if (values != null && values.hasArrayElements()) {
                for (long index = 0; index < values.getArraySize(); index++) {
                    definitions.add(values.getArrayElement(index));
                }
            }
        }

        public Value requireModule(String relativePath) {
            String normalized = relativePath.replace('\\', '/');
            return modules.computeIfAbsent(normalized, this::loadModule);
        }

        private Value loadModule(String relativePath) {
            try {
                Path path = scriptsDirectory.resolve(relativePath).normalize();
                if (!path.startsWith(scriptsDirectory)) {
                    throw new IllegalArgumentException("Module path escapes scripts directory.");
                }
                String code = Files.readString(path);
                Value factory = runtime.context().eval(Source.newBuilder("js", "(function(exports, wscore){\n" + code + "\n; return exports; })", path.getFileName().toString()).build());
                Value exports = runtime.context().eval("js", "({})");
                factory.execute(exports, runtime.context().eval("js", "wscore"));
                return exports;
            } catch (Exception exception) {
                log.warn("Failed to load JS module '" + relativePath + "': " + exception.getMessage());
                return runtime.context().eval("js", "({})");
            }
        }

        public List<Value> definitions() {
            return definitions;
        }

        public ScriptMath getMath() {
            return math;
        }

        public ScriptText getText() {
            return text;
        }

        public ScriptToolkit.Normalize getNormalize() {
            return normalize;
        }

        public ScriptToolkit.Decorate getDecorate() {
            return decorate;
        }

        public ScriptToolkit.Convert getConvert() {
            return convert;
        }

        public ScriptToolkit.Builders getBuilders() {
            return builders;
        }

        public ScriptFormat getFormat() {
            return format;
        }

        public ScriptTime getTime() {
            return time;
        }

        public Map<String, String> getEventAliases() {
            return EVENT_ALIASES;
        }
    }

    public record ScriptChannelEvent(String channel, UUID playerId, Map<String, Object> payload) {
    }

    private record ScriptLeaderboardRequest(String baseId, int position, String periodId, String field) {
    }

    public static final class ScriptLeaderboardEntry {
        private final String basePlaceholder;
        private final String period;
        private final int position;
        private final String uuid;
        private final String name;
        private final double value;
        private final boolean hasEntry;

        private ScriptLeaderboardEntry(
                String basePlaceholder,
                String period,
                int position,
                String uuid,
                String name,
                double value,
                boolean hasEntry
        ) {
            this.basePlaceholder = basePlaceholder;
            this.period = period;
            this.position = position;
            this.uuid = uuid;
            this.name = name;
            this.value = value;
            this.hasEntry = hasEntry;
        }

        private static ScriptLeaderboardEntry missing(eScoreScript script, String periodId, int position) {
            return new ScriptLeaderboardEntry(script.id(), periodId, position, "", "", 0D, false);
        }

        private boolean isMissing() {
            return !hasEntry;
        }

        public String basePlaceholder() {
            return basePlaceholder;
        }

        public String period() {
            return period;
        }

        public int position() {
            return position;
        }

        public String uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }

        public double value() {
            return value;
        }

        public boolean hasEntry() {
            return hasEntry;
        }
    }

    private record WatcherBinding(eScoreScript script, eScoreScript.WatchDefinition watch) {
    }

    private record CompiledScriptFile(ScriptRuntime runtime, List<eScoreScript> scripts, int watcherCount) {
    }

    private record PlaceholderCacheKey(UUID playerId, String placeholderId) {
    }

    private record CacheEntry(Object value, long expiresAt) {
    }

    private record ScriptProfile(AtomicLong calls, AtomicLong totalNanos, AtomicLong maxNanos) {
        private ScriptProfile() {
            this(new AtomicLong(), new AtomicLong(), new AtomicLong());
        }
    }

    public record ScriptProfileSnapshot(String id, long calls, double totalMillis, double avgMillis, double maxMillis) {
    }

    public static final class ScriptMath {
        public double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }

        public long round(double value) {
            return Math.round(value);
        }

        public double floor(double value) {
            return Math.floor(value);
        }

        public double ceil(double value) {
            return Math.ceil(value);
        }

        public double percent(double part, double total, int decimals) {
            if (total == 0D) {
                return 0D;
            }
            double scale = Math.pow(10D, Math.max(0, decimals));
            return Math.round((part / total * 100D) * scale) / scale;
        }
    }

    public static final class ScriptText {
        public String upper(String input) {
            return input == null ? "" : input.toUpperCase(Locale.ROOT);
        }

        public String lower(String input) {
            return input == null ? "" : input.toLowerCase(Locale.ROOT);
        }

        public String replace(String input, String target, String replacement) {
            return input == null ? "" : input.replace(target, replacement);
        }

        public String join(String delimiter, Object first, Object second) {
            return String.valueOf(first) + delimiter + second;
        }
    }

    public static final class ScriptFormat {
        public String number(double value, String pattern) {
            return new DecimalFormat(pattern == null || pattern.isBlank() ? "#,##0.##" : pattern).format(value);
        }

        public String shortNumber(double value) {
            double absolute = Math.abs(value);
            if (absolute >= 1_000_000_000D) {
                return new DecimalFormat("0.##").format(value / 1_000_000_000D) + "B";
            }
            if (absolute >= 1_000_000D) {
                return new DecimalFormat("0.##").format(value / 1_000_000D) + "M";
            }
            if (absolute >= 1_000D) {
                return new DecimalFormat("0.##").format(value / 1_000D) + "K";
            }
            return new DecimalFormat("#,##0.##").format(value);
        }
    }

    public static final class ScriptTime {
        public long seconds(long value) {
            return value * 1000L;
        }

        public long minutes(long value) {
            return seconds(value * 60L);
        }

        public long hours(long value) {
            return minutes(value * 60L);
        }

        public String duration(long millis) {
            Duration duration = Duration.ofMillis(Math.max(0L, millis));
            long hours = duration.toHours();
            long minutes = duration.toMinutesPart();
            long seconds = duration.toSecondsPart();
            if (hours > 0) {
                return hours + "h " + minutes + "m " + seconds + "s";
            }
            if (minutes > 0) {
                return minutes + "m " + seconds + "s";
            }
            return seconds + "s";
        }
    }

    private boolean hasBypassPermission(Player player, String bypassPermission) {
        if (player.hasPermission("escore.bypass") || player.hasPermission("wsc.bypass")) {
            return true;
        }
        if (player.hasPermission(bypassPermission)) {
            return true;
        }
        String legacyPermission = legacyBypassPermission(bypassPermission);
        return legacyPermission != null && player.hasPermission(legacyPermission);
    }
}
