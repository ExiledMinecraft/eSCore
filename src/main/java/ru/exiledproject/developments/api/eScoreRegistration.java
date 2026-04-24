package ru.exiledproject.developments.api;

public interface eScoreRegistration extends AutoCloseable {
    void unregister();

    @Override
    default void close() {
        unregister();
    }
}
