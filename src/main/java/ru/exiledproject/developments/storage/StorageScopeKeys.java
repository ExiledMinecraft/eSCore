package ru.exiledproject.developments.storage;

import java.util.Locale;

public final class StorageScopeKeys {
    public static final String SERVER_SEPARATOR = "::srv::";

    private StorageScopeKeys() {
    }

    public static String logicalKey(String key) {
        String normalized = normalize(key);
        int separator = normalized.indexOf(SERVER_SEPARATOR);
        return separator >= 0 ? normalized.substring(0, separator) : normalized;
    }

    public static String serverKey(String logicalKey, String serverId) {
        String normalizedLogicalKey = logicalKey(logicalKey);
        String normalizedServerId = normalize(serverId);
        if (normalizedServerId.isBlank()) {
            return normalizedLogicalKey;
        }
        return normalizedLogicalKey + SERVER_SEPARATOR + normalizedServerId;
    }

    public static boolean isServerScopedKey(String key) {
        return normalize(key).contains(SERVER_SEPARATOR);
    }

    public static boolean matchesLogicalKey(String candidate, String logicalKey) {
        String normalizedCandidate = normalize(candidate);
        String normalizedLogicalKey = logicalKey(logicalKey);
        return normalizedCandidate.equals(normalizedLogicalKey)
                || normalizedCandidate.startsWith(normalizedLogicalKey + SERVER_SEPARATOR);
    }

    public static boolean matchesServerKey(String candidate, String logicalKey, String serverId) {
        return normalize(candidate).equals(serverKey(logicalKey, serverId));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
