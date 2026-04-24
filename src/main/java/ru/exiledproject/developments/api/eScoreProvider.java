package ru.exiledproject.developments.api;

public final class eScoreProvider {
    private static volatile eScoreApi api;

    private eScoreProvider() {
    }

    public static eScoreApi get() {
        if (api == null) {
            throw new IllegalStateException("eSCore API is not available yet.");
        }
        return api;
    }

    public static boolean isAvailable() {
        return api != null;
    }

    public static void set(eScoreApi newApi) {
        api = newApi;
    }

    public static void clear() {
        api = null;
    }
}
