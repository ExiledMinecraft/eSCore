package me.clip.placeholderapi.expansion.cloud;

public record CloudExpansionVersion(
        String version,
        boolean verified,
        String createdAt,
        String downloadUrl,
        String releaseNotes
) {
}