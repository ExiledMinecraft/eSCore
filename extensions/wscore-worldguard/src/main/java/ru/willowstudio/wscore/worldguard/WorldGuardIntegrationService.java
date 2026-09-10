package ru.willowstudio.wscore.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import ru.exiledproject.developments.api.eScoreApi;
import ru.exiledproject.developments.api.eScorePlaceholderHook;
import ru.exiledproject.developments.api.eScoreRegistration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

final class WorldGuardIntegrationService implements Listener {
    private static final long SNAPSHOT_TTL_MILLIS = 1000L;

    private final WScoreWorldGuardPlugin plugin;
    private final eScoreApi api;
    private final StateFlag trackFlag;
    private final Map<UUID, WorldGuardSnapshot> snapshots = new ConcurrentHashMap<>();
    private final List<eScoreRegistration> registrations = new ArrayList<>();

    WorldGuardIntegrationService(WScoreWorldGuardPlugin plugin, eScoreApi api, StateFlag trackFlag) {
        this.plugin = plugin;
        this.api = api;
        this.trackFlag = trackFlag;
    }

    void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registerPlaceholderHooks();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            syncPlayer(player, "startup");
        }
    }

    void disable() {
        HandlerList.unregisterAll(this);
        for (eScoreRegistration registration : registrations) {
            registration.unregister();
        }
        registrations.clear();
        snapshots.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        syncPlayer(event.getPlayer(), "join");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        snapshots.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> syncPlayer(event.getPlayer(), "respawn"));
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        syncPlayer(event.getPlayer(), "world-change");
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        syncPlayer(event.getPlayer(), "teleport");
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        syncPlayer(event.getPlayer(), "move");
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }

        WorldGuardSnapshot deathSnapshot = querySnapshot(event.getEntity().getLocation());
        WorldGuardSnapshot killerSnapshot = querySnapshot(killer.getLocation());

        Map<String, Object> payload = new HashMap<>();
        payload.put("entityType", event.getEntity().getType().name());
        payload.put("world", safeWorldName(event.getEntity().getLocation()));
        payload.put("deathX", event.getEntity().getLocation().getBlockX());
        payload.put("deathY", event.getEntity().getLocation().getBlockY());
        payload.put("deathZ", event.getEntity().getLocation().getBlockZ());
        payload.put("trackingAllowed", deathSnapshot.trackAllowed());
        payload.put("regionIds", deathSnapshot.regionIds());
        payload.put("blockedRegions", deathSnapshot.blockedRegionIds());
        payload.put("killerRegionIds", killerSnapshot.regionIds());
        payload.put("killerTrackingAllowed", killerSnapshot.trackAllowed());

        api.fireChannelEvent("worldguard:entity_death", killer.getUniqueId(), payload);
        if (deathSnapshot.trackAllowed()) {
            api.fireChannelEvent("worldguard:allowed_entity_death", killer.getUniqueId(), payload);
        } else {
            api.fireChannelEvent("worldguard:blocked_entity_death", killer.getUniqueId(), payload);
        }
    }

    private void registerPlaceholderHooks() {
        registrations.add(registerHook(
                "worldguard_current_regions",
                "Comma-separated list of current WorldGuard regions for the player.",
                false,
                player -> String.join(", ", snapshotFor(player).regionIds())
        ));
        registrations.add(registerHook(
                "worldguard_first_region",
                "Highest-priority WorldGuard region for the player.",
                false,
                player -> {
                    List<String> ids = snapshotFor(player).regionIds();
                    return ids.isEmpty() ? "" : ids.get(0);
                }
        ));
        registrations.add(registerHook(
                "worldguard_region_count",
                "Amount of WorldGuard regions at the player's position.",
                true,
                player -> Integer.toString(snapshotFor(player).regionIds().size())
        ));
        registrations.add(registerHook(
                "worldguard_track_allowed",
                "Whether eSCore tracking is allowed in the player's current WorldGuard regions.",
                false,
                player -> {
                    Player online = player.getPlayer();
                    if (online == null || !online.isOnline()) {
                        return "";
                    }
                    return Boolean.toString(snapshotFor(player).trackAllowed());
                }
        ));
        registrations.add(registerHook(
                "worldguard_blocked_regions",
                "Comma-separated list of regions that explicitly deny the wscore-track flag.",
                false,
                player -> String.join(", ", snapshotFor(player).blockedRegionIds())
        ));
    }

    private eScoreRegistration registerHook(String id, String description, boolean numeric, Function<OfflinePlayer, String> resolver) {
        return api.registerPlaceholderHook(new eScorePlaceholderHook() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public boolean isNumeric() {
                return numeric;
            }

            @Override
            public String resolve(OfflinePlayer player, eScoreApi api) {
                return resolver.apply(player);
            }
        });
    }

    private void syncPlayer(Player player, String reason) {
        WorldGuardSnapshot current = querySnapshot(player.getLocation());
        WorldGuardSnapshot previous = snapshots.put(player.getUniqueId(), current);
        if (previous == null) {
            fireRegionChange(player, WorldGuardSnapshot.empty(), current, reason);
            return;
        }
        if (!previous.sameMembership(current)) {
            fireRegionChange(player, previous, current, reason);
        }
    }

    private void fireRegionChange(Player player, WorldGuardSnapshot previous, WorldGuardSnapshot current, String reason) {
        Set<String> previousIds = new HashSet<>(previous.regionIds());
        Set<String> currentIds = new HashSet<>(current.regionIds());

        List<String> entered = currentIds.stream()
                .filter(id -> !previousIds.contains(id))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        List<String> left = previousIds.stream()
                .filter(id -> !currentIds.contains(id))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        Map<String, Object> payload = new HashMap<>();
        payload.put("reason", reason);
        payload.put("world", safeWorldName(player.getLocation()));
        payload.put("x", player.getLocation().getBlockX());
        payload.put("y", player.getLocation().getBlockY());
        payload.put("z", player.getLocation().getBlockZ());
        payload.put("fromRegions", previous.regionIds());
        payload.put("toRegions", current.regionIds());
        payload.put("enteredRegions", entered);
        payload.put("leftRegions", left);
        payload.put("trackingAllowed", current.trackAllowed());
        payload.put("blockedRegions", current.blockedRegionIds());

        api.fireChannelEvent("worldguard:region_change", player.getUniqueId(), payload);
        for (String regionId : entered) {
            api.fireChannelEvent("worldguard:enter_region", player.getUniqueId(), singleRegionPayload(payload, regionId));
        }
        for (String regionId : left) {
            api.fireChannelEvent("worldguard:leave_region", player.getUniqueId(), singleRegionPayload(payload, regionId));
        }
    }

    private Map<String, Object> singleRegionPayload(Map<String, Object> base, String regionId) {
        Map<String, Object> payload = new HashMap<>(base);
        payload.put("regionId", regionId);
        return payload;
    }

    private WorldGuardSnapshot snapshotFor(OfflinePlayer offlinePlayer) {
        Player player = offlinePlayer.getPlayer();
        if (player == null || !player.isOnline()) {
            return WorldGuardSnapshot.empty();
        }
        WorldGuardSnapshot cached = snapshots.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (cached != null && cached.capturedAtMillis() + SNAPSHOT_TTL_MILLIS >= now) {
            return cached;
        }
        WorldGuardSnapshot fresh = querySnapshot(player.getLocation());
        snapshots.put(player.getUniqueId(), fresh);
        return fresh;
    }

    private WorldGuardSnapshot querySnapshot(Location location) {
        if (location.getWorld() == null) {
            return WorldGuardSnapshot.empty();
        }

        RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(location.getWorld()));
        if (regionManager == null) {
            return new WorldGuardSnapshot(List.of(), List.of(), true, System.currentTimeMillis());
        }

        ApplicableRegionSet applicableRegions = regionManager.getApplicableRegions(BukkitAdapter.asBlockVector(location));
        List<ProtectedRegion> sortedRegions = applicableRegions.getRegions().stream()
                .sorted(Comparator.comparingInt(ProtectedRegion::getPriority).reversed().thenComparing(ProtectedRegion::getId, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<String> regionIds = sortedRegions.stream()
                .map(ProtectedRegion::getId)
                .toList();
        List<String> blockedRegions = sortedRegions.stream()
                .filter(region -> region.getFlag(trackFlag) == State.DENY)
                .map(ProtectedRegion::getId)
                .toList();
        boolean trackAllowed = blockedRegions.isEmpty() && applicableRegions.testState(null, trackFlag);
        return new WorldGuardSnapshot(regionIds, blockedRegions, trackAllowed, System.currentTimeMillis());
    }

    private String safeWorldName(Location location) {
        return location.getWorld() == null ? "" : location.getWorld().getName();
    }

    private record WorldGuardSnapshot(
            List<String> regionIds,
            List<String> blockedRegionIds,
            boolean trackAllowed,
            long capturedAtMillis
    ) {
        static WorldGuardSnapshot empty() {
            return new WorldGuardSnapshot(List.of(), List.of(), true, 0L);
        }

        boolean sameMembership(WorldGuardSnapshot other) {
            return trackAllowed == other.trackAllowed
                    && regionIds.equals(other.regionIds)
                    && blockedRegionIds.equals(other.blockedRegionIds);
        }
    }
}
