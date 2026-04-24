package ru.exiledproject.developments.script;

import org.bukkit.event.EventPriority;
import org.graalvm.polyglot.Value;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class eScoreScript {
    private final Path file;
    private final ScriptManager.ScriptRuntime runtime;
    private final String id;
    private final Set<String> aliases;
    private final String name;
    private final String description;
    private final String author;
    private final String version;
    private final String storageKey;
    private final Object defaultValue;
    private final boolean numeric;
    private final long cacheTtlMillis;
    private final long sourceRefreshTtlMillis;
    private final long flushIntervalMillis;
    private final long remoteTtlMillis;
    private final PersistenceMode persistenceMode;
    private final SyncMode syncMode;
    private final Set<String> dependencies;
    private final ScriptValueSource source;
    private final Value transformFunction;
    private final Value renderFunction;
    private final Value contextFunction;
    private final Object contextValue;
    private final ScriptNoDataPolicy noDataPolicy;
    private final ScriptLeaderboardDefinition leaderboardDefinition;
    private final List<WatchDefinition> watches;
    /**
     * Разрешение, при наличии которого watch-событие не применяется к игроку.
     * {@code null} — bypass отключён для этого скрипта.
     * Глобальный bypass {@code wsc.bypass} проверяется всегда, когда поле не null.
     */
    @Nullable
    private final String bypassPermission;

    public eScoreScript(
            Path file,
            ScriptManager.ScriptRuntime runtime,
            String id,
            Set<String> aliases,
            String name,
            String description,
            String author,
            String version,
            String storageKey,
            Object defaultValue,
            boolean numeric,
            long cacheTtlMillis,
            long sourceRefreshTtlMillis,
            long flushIntervalMillis,
            long remoteTtlMillis,
            PersistenceMode persistenceMode,
            SyncMode syncMode,
            Set<String> dependencies,
            ScriptValueSource source,
            Value transformFunction,
            Value renderFunction,
            Value contextFunction,
            Object contextValue,
            ScriptNoDataPolicy noDataPolicy,
            ScriptLeaderboardDefinition leaderboardDefinition,
            List<WatchDefinition> watches,
            @Nullable String bypassPermission
    ) {
        this.file = file;
        this.runtime = runtime;
        this.id = id;
        this.aliases = aliases;
        this.name = name;
        this.description = description;
        this.author = author;
        this.version = version;
        this.storageKey = storageKey;
        this.defaultValue = defaultValue;
        this.numeric = numeric;
        this.cacheTtlMillis = cacheTtlMillis;
        this.sourceRefreshTtlMillis = sourceRefreshTtlMillis;
        this.flushIntervalMillis = flushIntervalMillis;
        this.remoteTtlMillis = remoteTtlMillis;
        this.persistenceMode = persistenceMode;
        this.syncMode = syncMode;
        this.dependencies = dependencies;
        this.source = source;
        this.transformFunction = transformFunction;
        this.renderFunction = renderFunction;
        this.contextFunction = contextFunction;
        this.contextValue = contextValue;
        this.noDataPolicy = noDataPolicy;
        this.leaderboardDefinition = leaderboardDefinition;
        this.watches = watches;
        this.bypassPermission = bypassPermission;
    }

    public Path file() {
        return file;
    }

    public ScriptManager.ScriptRuntime runtime() {
        return runtime;
    }

    public String id() {
        return id;
    }

    public Set<String> aliases() {
        return aliases;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String author() {
        return author;
    }

    public String version() {
        return version;
    }

    public String storageKey() {
        return storageKey;
    }

    public Object defaultValue() {
        return defaultValue;
    }

    public boolean numeric() {
        return numeric;
    }

    public long cacheTtlMillis() {
        return cacheTtlMillis;
    }

    public long sourceRefreshTtlMillis() {
        return sourceRefreshTtlMillis;
    }

    public long flushIntervalMillis() {
        return flushIntervalMillis;
    }

    public long remoteTtlMillis() {
        return remoteTtlMillis;
    }

    public PersistenceMode persistenceMode() {
        return persistenceMode;
    }

    public SyncMode syncMode() {
        return syncMode;
    }

    public Set<String> dependencies() {
        return dependencies;
    }

    public ScriptValueSource source() {
        return source;
    }

    public Value transformFunction() {
        return transformFunction;
    }

    public Value renderFunction() {
        return renderFunction;
    }

    public Value contextFunction() {
        return contextFunction;
    }

    public Object contextValue() {
        return contextValue;
    }

    public ScriptNoDataPolicy noDataPolicy() {
        return noDataPolicy;
    }

    public ScriptLeaderboardDefinition leaderboardDefinition() {
        return leaderboardDefinition;
    }

    public List<WatchDefinition> watches() {
        return watches;
    }

    /**
     * Разрешение для bypass watch-событий. {@code null} — bypass отключён.
     * Глобальное {@code wsc.bypass} проверяется автоматически при ненулевом значении.
     */
    @Nullable
    public String bypassPermission() {
        return bypassPermission;
    }

    public record WatchDefinition(
            String eventClassName,
            Class<? extends org.bukkit.event.Event> eventClass,
            String channel,
            EventPriority priority,
            boolean ignoreCancelled,
            Value playerFunction,
            Value filterFunction,
            Value updateFunction
    ) {
    }
}
