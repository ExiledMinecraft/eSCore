package me.clip.placeholderapi.events;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collection;
import java.util.List;

public final class ExpansionsLoadedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final List<PlaceholderExpansion> expansions;

    public ExpansionsLoadedEvent(Collection<PlaceholderExpansion> expansions) {
        this.expansions = List.copyOf(expansions);
    }

    public List<PlaceholderExpansion> getExpansions() {
        return expansions;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
