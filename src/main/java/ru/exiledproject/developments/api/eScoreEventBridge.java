package ru.exiledproject.developments.api;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;

import java.util.Map;
import java.util.UUID;

public interface eScoreEventBridge<T extends Event> {
    String getChannel();

    Class<T> getEventClass();

    default EventPriority getPriority() {
        return EventPriority.NORMAL;
    }

    default boolean ignoreCancelled() {
        return false;
    }

    default boolean test(T event, eScoreApi api) {
        return true;
    }

    UUID extractPlayerId(T event, eScoreApi api);

    default Map<String, Object> payload(T event, eScoreApi api) {
        return Map.of();
    }
}
