package ru.exiledproject.developments.data;

public record PersistedValue(Object value, long updatedAt, long expiresAt) {
    public PersistedValue(Object value, long updatedAt) {
        this(value, updatedAt, 0L);
    }

    public boolean isExpired(long now) {
        return expiresAt > 0L && expiresAt <= now;
    }
}
