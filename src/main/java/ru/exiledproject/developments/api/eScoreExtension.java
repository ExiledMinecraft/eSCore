package ru.exiledproject.developments.api;

import org.bukkit.plugin.java.JavaPlugin;

public interface eScoreExtension {
    String getId();

    default String getAuthor() {
        return "Unknown";
    }

    default String getVersion() {
        return "unspecified";
    }

    void enable(eScoreApi api, JavaPlugin plugin) throws Exception;

    default void disable() throws Exception {
    }
}
