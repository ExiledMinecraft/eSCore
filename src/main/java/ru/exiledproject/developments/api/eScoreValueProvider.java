package ru.exiledproject.developments.api;

import org.bukkit.OfflinePlayer;

import java.util.Collection;
import java.util.List;

public interface eScoreValueProvider {
    String getId();

    default String getDescription() {
        return "No description provided.";
    }

    default Collection<String> getKnownKeys() {
        return List.of();
    }

    default boolean supports(String key) {
        Collection<String> knownKeys = getKnownKeys();
        return knownKeys.isEmpty() || knownKeys.stream().anyMatch(known -> known.equalsIgnoreCase(key));
    }

    Object resolve(OfflinePlayer player, String key, eScoreApi api);
}
