package ru.exiledproject.developments.integration;

import ru.exiledproject.developments.data.PlayerValueChange;

import java.util.List;

public interface IntegrationAdapter {
    String getId();

    String getDisplayName();

    void reload();

    void shutdown();

    IntegrationStatus getStatus();

    List<String> check();

    default void onPlayerValueChange(PlayerValueChange change) {
    }
}
