package ru.exiledproject.developments.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import ru.exiledproject.developments.core.eScoreLogger;
import ru.exiledproject.developments.data.ChangeOrigin;
import ru.exiledproject.developments.data.PlayerDataStore;
import ru.exiledproject.developments.data.PlayerValueChange;
import ru.exiledproject.developments.eSCorePlugin;
import ru.exiledproject.developments.script.ScriptManager;
import ru.exiledproject.developments.script.SyncMode;
import ru.exiledproject.developments.script.eScoreScript;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class RedisSyncManager {
    private final eSCorePlugin plugin;
    private final PlayerDataStore dataStore;
    private final ScriptManager scriptManager;
    private final StorageManager storageManager;
    private final eScoreLogger log;
    private final Gson gson = new GsonBuilder().create();

    private Thread subscriberThread;
    private JedisPubSub pubSub;

    public RedisSyncManager(eSCorePlugin plugin, PlayerDataStore dataStore, ScriptManager scriptManager, StorageManager storageManager, eScoreLogger log) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.scriptManager = scriptManager;
        this.storageManager = storageManager;
        this.log = log;
    }

    public void reload() {
        shutdown();
        if (!isEnabled()) {
            return;
        }
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                handleIncoming(message);
            }
        };
        subscriberThread = new Thread(this::runSubscriber, "eSCore-RedisSync");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        log.success("Redis sync enabled.");
    }

    public void shutdown() {
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {
            }
            pubSub = null;
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
            subscriberThread = null;
        }
    }

    public void onValueChange(PlayerValueChange change) {
        if (!isEnabled() || change.origin() != ChangeOrigin.LOCAL) {
            return;
        }
        eScoreScript script = scriptManager.getPrimaryScriptByStorageKey(change.key());
        if (script == null || storageManager.resolveSyncMode(script) != SyncMode.REDIS) {
            return;
        }

        RedisMessage message = new RedisMessage(
                plugin.getServerId(),
                change.playerId(),
                change.key(),
                change.newValue(),
                change.updatedAt(),
                script.remoteTtlMillis()
        );

        withJedis(jedis -> {
            jedis.publish(channel(), gson.toJson(message));
            String runtimeKey = runtimeKey(change.key(), change.playerId());
            if (change.newValue() == null) {
                jedis.del(runtimeKey);
            } else {
                if (script.remoteTtlMillis() > 0L) {
                    jedis.psetex(runtimeKey, script.remoteTtlMillis(), gson.toJson(message));
                } else {
                    jedis.set(runtimeKey, gson.toJson(message));
                }
            }
        });
    }

    public void prefetchPlayer(UUID playerId) {
        if (!isEnabled() || !plugin.getConfig().getBoolean("sync.runtime-prefetch-on-join", true)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<String> keys = new LinkedHashSet<>();
            for (eScoreScript script : scriptManager.getScripts()) {
                if (storageManager.resolveSyncMode(script) == SyncMode.REDIS) {
                    keys.add(script.serverScoped()
                            ? scriptManager.resolveStorageWriteKey(script.storageKey())
                            : script.storageKey());
                }
            }
            withJedis(jedis -> {
                for (String key : keys) {
                    String raw = jedis.get(runtimeKey(key, playerId));
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    RedisMessage message = gson.fromJson(raw, RedisMessage.class);
                    dataStore.setValue(playerId, message.key(), message.value(), ChangeOrigin.REMOTE_REDIS, message.updatedAt());
                }
            });
        });
    }

    private void runSubscriber() {
        try (Jedis jedis = new Jedis(host(), port(), timeoutMillis())) {
            if (!password().isBlank()) {
                jedis.auth(password());
            }
            jedis.select(database());
            jedis.subscribe(pubSub, channel());
        } catch (Exception exception) {
            if (!Thread.currentThread().isInterrupted()) {
                log.warn("Redis subscriber stopped: " + exception.getMessage());
            }
        }
    }

    private void handleIncoming(String rawMessage) {
        try {
            RedisMessage message = gson.fromJson(rawMessage, RedisMessage.class);
            if (message == null || plugin.getServerId().equals(message.serverId())) {
                return;
            }
            dataStore.setValue(message.playerId(), message.key(), message.value(), ChangeOrigin.REMOTE_REDIS, message.updatedAt());
        } catch (Exception exception) {
            log.debug("Ignored invalid Redis payload: " + exception.getMessage());
        }
    }

    private void withJedis(UnsafeConsumer<Jedis> consumer) {
        try (Jedis jedis = new Jedis(host(), port(), timeoutMillis())) {
            if (!password().isBlank()) {
                jedis.auth(password());
            }
            jedis.select(database());
            consumer.accept(jedis);
        } catch (Exception exception) {
            log.debug("Redis operation failed: " + exception.getMessage());
        }
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("sync.redis.enabled", false);
    }

    private String host() {
        return plugin.getConfig().getString("sync.redis.host", "127.0.0.1");
    }

    private int port() {
        return plugin.getConfig().getInt("sync.redis.port", 6379);
    }

    private String password() {
        return plugin.getConfig().getString("sync.redis.password", "");
    }

    private int database() {
        return plugin.getConfig().getInt("sync.redis.database", 0);
    }

    private int timeoutMillis() {
        return plugin.getConfig().getInt("sync.redis.connection-timeout-millis", 2000);
    }

    private String channel() {
        return plugin.getConfig().getString("sync.redis.channel", "escore:sync");
    }

    private String prefix() {
        return plugin.getConfig().getString("sync.redis.key-prefix", "escore");
    }

    private String runtimeKey(String key, UUID playerId) {
        return prefix() + ":runtime:" + key + ":" + playerId;
    }

    private record RedisMessage(String serverId, UUID playerId, String key, Object value, long updatedAt, long ttlMillis) {
    }

    @FunctionalInterface
    private interface UnsafeConsumer<T> {
        void accept(T value) throws Exception;
    }
}
