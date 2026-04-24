package me.clip.placeholderapi.expansion.cloud;

import java.util.List;

public final class CloudExpansion {
    private final String id;
    private final String name;
    private final String slug;
    private final String author;
    private final String latestVersion;
    private final String description;
    private final String downloadUrl;
    private final String pageUrl;
    private final String sourceId;
    private final String sourceUrl;
    private final String dependencyUrl;
    private final List<String> placeholders;
    private final List<String> platforms;
    private final boolean verified;
    private final boolean authorVerified;
    private boolean hasExpansion;
    private boolean shouldUpdate;

    public CloudExpansion(
            String id,
            String name,
            String slug,
            String author,
            String latestVersion,
            String description,
            String downloadUrl,
            String pageUrl,
            String sourceId,
            String sourceUrl,
            String dependencyUrl,
            List<String> placeholders,
            List<String> platforms,
            boolean verified,
            boolean authorVerified
    ) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.author = author;
        this.latestVersion = latestVersion;
        this.description = description;
        this.downloadUrl = downloadUrl;
        this.pageUrl = pageUrl;
        this.sourceId = sourceId;
        this.sourceUrl = sourceUrl;
        this.dependencyUrl = dependencyUrl;
        this.placeholders = List.copyOf(placeholders);
        this.platforms = List.copyOf(platforms);
        this.verified = verified;
        this.authorVerified = authorVerified;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getAuthor() {
        return author;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getDescription() {
        return description;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getDependencyUrl() {
        return dependencyUrl;
    }

    public List<String> getPlaceholders() {
        return placeholders;
    }

    public List<String> getPlatforms() {
        return platforms;
    }

    public boolean isVerified() {
        return verified;
    }

    public boolean isAuthorVerified() {
        return authorVerified;
    }

    public boolean hasExpansion() {
        return hasExpansion;
    }

    public void setHasExpansion(boolean hasExpansion) {
        this.hasExpansion = hasExpansion;
    }

    public boolean shouldUpdate() {
        return shouldUpdate;
    }

    public void setShouldUpdate(boolean shouldUpdate) {
        this.shouldUpdate = shouldUpdate;
    }
}