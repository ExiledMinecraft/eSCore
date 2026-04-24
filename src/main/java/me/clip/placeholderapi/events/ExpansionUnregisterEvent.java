package me.clip.placeholderapi.events;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class ExpansionUnregisterEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final PlaceholderExpansion expansion;

    public ExpansionUnregisterEvent(PlaceholderExpansion expansion) {
        this.expansion = expansion;
    }

    public PlaceholderExpansion getExpansion() {
        return expansion;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
