package me.clip.placeholderapi.expansion.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class OfficialEcloudCatalogParser {
    private final Gson gson;

    OfficialEcloudCatalogParser(Gson gson) {
        this.gson = gson;
    }

    List<Entry> parse(String body, String siteBaseUrl) {
        JsonObject root = gson.fromJson(body, JsonObject.class);
        if (root == null) {
            return List.of();
        }

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, JsonElement> catalogEntry : root.entrySet()) {
            if (!catalogEntry.getValue().isJsonObject() || catalogEntry.getKey().isBlank()) {
                continue;
            }
            String name = catalogEntry.getKey();
            JsonObject object = catalogEntry.getValue().getAsJsonObject();
            List<CloudExpansionVersion> versions = parseVersions(object.get("versions"));
            String declaredLatestVersion = string(object, "latest_version");
            CloudExpansionVersion latest = findVersion(versions, declaredLatestVersion)
                    .orElseGet(() -> versions.isEmpty() ? null : versions.getFirst());
            if (latest == null) {
                continue;
            }

            String normalizedBase = siteBaseUrl.endsWith("/")
                    ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1)
                    : siteBaseUrl;
            String slug = normalize(name).replace(' ', '-');
            entries.add(new Entry(
                    name,
                    slug,
                    string(object, "author"),
                    latest.version(),
                    string(object, "description"),
                    latest.downloadUrl(),
                    normalizedBase + "/expansions/" + slug + '/',
                    string(object, "source_url"),
                    string(object, "dependency_url"),
                    stringList(object.get("placeholders")),
                    latest.verified(),
                    versions
            ));
        }
        return List.copyOf(entries);
    }

    private List<CloudExpansionVersion> parseVersions(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<CloudExpansionVersion> versions = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String version = string(object, "version");
            String downloadUrl = string(object, "url");
            if (version.isBlank() || downloadUrl.isBlank()) {
                continue;
            }
            versions.add(new CloudExpansionVersion(
                    version,
                    bool(object, "verified"),
                    string(object, "created_at"),
                    downloadUrl,
                    string(object, "release_notes")
            ));
        }
        return List.copyOf(versions);
    }

    private Optional<CloudExpansionVersion> findVersion(List<CloudExpansionVersion> versions, String version) {
        return versions.stream().filter(candidate -> candidate.version().equalsIgnoreCase(version)).findFirst();
    }

    private List<String> stringList(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement item : array) {
            if (item.isJsonPrimitive()) {
                String value = item.getAsString();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return List.copyOf(values);
    }

    private String string(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private boolean bool(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return false;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    record Entry(
            String name,
            String slug,
            String author,
            String latestVersion,
            String description,
            String downloadUrl,
            String pageUrl,
            String sourceUrl,
            String dependencyUrl,
            List<String> placeholders,
            boolean verified,
            List<CloudExpansionVersion> versions
    ) {
    }
}
