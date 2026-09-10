package ru.exiledproject.developments.api;

import org.bukkit.OfflinePlayer;

public interface eScorePlaceholderHook {
    String getId();

    default String getDescription() {
        return "No description provided.";
    }

    default boolean isNumeric() {
        return false;
    }

    String resolve(OfflinePlayer player, eScoreApi api);

    default eScorePlaceholderResult resolveResult(OfflinePlayer player, eScoreApi api) {
        String value = resolve(player, api);
        return value == null ? eScorePlaceholderResult.missing() : eScorePlaceholderResult.text(value);
    }
}
