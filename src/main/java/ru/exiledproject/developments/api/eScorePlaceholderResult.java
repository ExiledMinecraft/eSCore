package ru.exiledproject.developments.api;

import java.time.Duration;
import java.util.Objects;

/** Typed placeholder outcome used by the eSCore API before it is rendered as text. */
public record eScorePlaceholderResult(Status status, String value, Duration cacheTtl, String message) {
    public enum Status {
        TEXT,
        MISSING,
        ERROR
    }

    public eScorePlaceholderResult {
        status = Objects.requireNonNull(status, "status");
        cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? Duration.ZERO : cacheTtl;
        message = message == null ? "" : message;
    }

    public static eScorePlaceholderResult text(String value) {
        return new eScorePlaceholderResult(Status.TEXT, value == null ? "" : value, Duration.ZERO, "");
    }

    public static eScorePlaceholderResult cached(String value, Duration ttl) {
        return new eScorePlaceholderResult(Status.TEXT, value == null ? "" : value, ttl, "");
    }

    public static eScorePlaceholderResult missing() {
        return new eScorePlaceholderResult(Status.MISSING, null, Duration.ZERO, "");
    }

    public static eScorePlaceholderResult error(String message) {
        return new eScorePlaceholderResult(Status.ERROR, null, Duration.ZERO, message);
    }

    public boolean hasValue() {
        return status == Status.TEXT;
    }
}
