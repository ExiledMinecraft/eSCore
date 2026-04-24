package ru.exiledproject.developments.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import ru.exiledproject.developments.data.ChangeOrigin;

import java.util.UUID;

public final class eScoreValueChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String key;
    private final Object oldValue;
    private final Object newValue;
    private final ChangeOrigin origin;
    private final long updatedAt;

    public eScoreValueChangeEvent(UUID playerId, String key, Object oldValue, Object newValue, ChangeOrigin origin, long updatedAt) {
        this.playerId = playerId;
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.origin = origin;
        this.updatedAt = updatedAt;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getKey() {
        return key;
    }

    public Object getOldValue() {
        return oldValue;
    }

    public Object getNewValue() {
        return newValue;
    }

    public ChangeOrigin getOrigin() {
        return origin;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
