package me.clip.placeholderapi.expansion.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import ru.exiledproject.developments.core.eScoreLogger;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CloudExpansionManager {
    private static final String OFFICIAL_SITE_URL = "https://api.extendedclip.com/expansions/all/";
    private static final String OFFICIAL_SITE_BASE_URL = "https://api.extendedclip.com";
    private static final String OFFICIAL_EXPANSION_PAGE_PREFIX = "https://api.extendedclip.com/expansions/";
    private static final String OFFICIAL_REST_URL = "https://supa.placeholderapi.com/rest/v1";
    private static final String OFFICIAL_SOURCE_ID = "official";
    private static final Set<String> OFFICIAL_SITE_HOSTS = Set.of(
            "api.extendedclip.com",
            "ecloud.placeholderapi.com"
    );
    private static final String OFFICIAL_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzY2MzcwMDI0LCJleHAiOjE5MjQwNTAwMjR9.JgEsI1BwgOTbZN_lDp83Gebs71s9cwpl-2Pp9k_fjKM";
    private static final String EXPANSION_SELECT = "id,name,slug,description,verified,source_url,dependency_url,placeholders,platform,profiles!expansions_author_id_fkey(username,verified),versions!fk_latest_version(version,verified,created_at,jar_file_path)";
    private static final String VERSION_SELECT = "version,verified,created_at,jar_file_path,release_notes";

    private final PlaceholderAPIPlugin plugin;
    private final eScoreLogger log;
    private final Gson gson = new Gson();
    private final HttpClient httpClient;

    private volatile Map<String, AggregatedExpansion> cache = Map.of();
    private volatile Map<String, SourceState> sourceStates = Map.of();
    private volatile List<String> lastErrors = List.of();
    private volatile long expiresAt;
    private volatile boolean shuttingDown;
    private volatile boolean refreshing;
    private volatile boolean suspended;
    private volatile String suspensionReason = "";
    private final Map<String, VersionCacheEntry> versionCache = new LinkedHashMap<>();

    public CloudExpansionManager(PlaceholderAPIPlugin plugin, eScoreLogger log) {
        this.plugin = plugin;
        this.log = log;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, plugin.getConfig().getInt("placeholderapi.ecloud.connect-timeout-millis", 5000))))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void invalidateCache() {
        expiresAt = 0L;
        cache = Map.of();
        sourceStates = Map.of();
        lastErrors = List.of();
        synchronized (versionCache) {
            versionCache.clear();
        }
    }

    public void markStale() {
        expiresAt = 0L;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public String getSuspensionReason() {
        return suspensionReason;
    }

    public void reactivate() {
        suspended = false;
        suspensionReason = "";
        markStale();
    }

    public void warmupAsync() {
        if (shuttingDown || !plugin.isEnabled() || suspended) {
            return;
        }
        plugin.executeAsync(this::refreshNow);
    }

    public RefreshResult startupWarmup() {
        long timeoutMillis = Math.max(1000L, plugin.getConfig().getLong("placeholderapi.ecloud.startup-timeout-millis", 15000L));
        RefreshResult result = refreshNowWithin(timeoutMillis);
        if (result.totalExpansions() == 0 && !result.errors().isEmpty()) {
            suspend("Startup warmup timed out or failed. Background probe continues every 60s.");
        }
        return result;
    }

    public void probeSuspendedSources() {
        if (shuttingDown || !plugin.isEnabled() || !suspended || !plugin.getConfig().getBoolean("placeholderapi.ecloud.enabled", true)) {
            return;
        }

        LinkedHashMap<String, SourceState> states = new LinkedHashMap<>();
        boolean reachable = false;
        for (CatalogSource source : configuredSources()) {
            SourceState state = probeSource(source);
            states.put(source.id(), state);
            if (state.success()) {
                reachable = true;
            }
        }
        sourceStates = Map.copyOf(states);
        if (!reachable) {
            return;
        }

        resume();
        RefreshResult refreshResult = refreshNowWithin(Math.max(1000L, plugin.getConfig().getLong("placeholderapi.ecloud.read-timeout-millis", 15000L)));
        if (refreshResult.totalExpansions() == 0 && !refreshResult.errors().isEmpty()) {
            suspend(refreshResult.errors().get(0));
        }
    }

    public Collection<CloudExpansion> getExpansions() {
        ensureFresh();
        LinkedHashSet<CloudExpansion> values = new LinkedHashSet<>();
        for (AggregatedExpansion expansion : cache.values()) {
            values.add(expansion.view());
        }
        return List.copyOf(values);
    }

    public Collection<CloudExpansion> getCachedExpansions() {
        LinkedHashSet<CloudExpansion> values = new LinkedHashSet<>();
        for (AggregatedExpansion expansion : cache.values()) {
            values.add(expansion.view());
        }
        return List.copyOf(values);
    }

    public Optional<CloudExpansion> findCloudExpansionByName(String name) {
        ensureFresh();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        AggregatedExpansion aggregate = cache.get(normalize(name));
        return aggregate == null ? Optional.empty() : Optional.of(aggregate.view());
    }

    public Optional<CloudExpansion> findCachedCloudExpansionByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        AggregatedExpansion aggregate = cache.get(normalize(name));
        return aggregate == null ? Optional.empty() : Optional.of(aggregate.view());
    }

    public int getCachedExpansionCount() {
        return uniqueExpansionCount(cache);
    }

    public List<String> getCachedExpansionNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (AggregatedExpansion expansion : cache.values()) {
            String canonical = expansion.view().getName();
            if (canonical == null || canonical.isBlank()) {
                canonical = expansion.view().getSlug();
            }
            if (canonical != null && !canonical.isBlank()) {
                names.putIfAbsent(normalize(canonical), canonical);
            }
        }
        return names.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<String> getCachedVersionNames(String name) {
        AggregatedExpansion aggregate = cache.get(normalize(name));
        if (aggregate == null) {
            return List.of();
        }
        if (aggregate.officialExpansionId() != null) {
            synchronized (versionCache) {
                VersionCacheEntry cached = versionCache.get(aggregate.officialExpansionId());
                if (cached != null && System.currentTimeMillis() < cached.expiresAt()) {
                    return cached.versions().stream().map(CloudExpansionVersion::version).toList();
                }
            }
        }
        if (aggregate.view().getLatestVersion() == null || aggregate.view().getLatestVersion().isBlank()) {
            return List.of();
        }
        return List.of(aggregate.view().getLatestVersion());
    }

    public Map<String, String> getSourceStatusMap() {
        Map<String, String> output = new LinkedHashMap<>();
        if (suspended) {
            output.put("runtime", "suspended: " + suspensionReason);
        }
        for (SourceState state : sourceStates.values()) {
            output.put(state.id(), state.success()
                    ? "ok, latency=" + state.latencyMillis() + "ms, expansions=" + state.expansionCount()
                    : "failed: " + state.error());
        }
        return output;
    }

    public List<CloudExpansionVersion> getVersions(String name) {
        ensureFresh();
        AggregatedExpansion aggregate = cache.get(normalize(name));
        if (aggregate == null || aggregate.officialExpansionId() == null) {
            return List.of();
        }
        synchronized (versionCache) {
            VersionCacheEntry cached = versionCache.get(aggregate.officialExpansionId());
            if (cached != null && System.currentTimeMillis() < cached.expiresAt()) {
                return cached.versions();
            }
        }
        if (suspended) {
            return List.of();
        }

        try {
            String body = requestRest(
                    "versions",
                    Map.of(
                            "select", VERSION_SELECT,
                            "expansion_id", "eq." + aggregate.officialExpansionId(),
                            "order", "created_at.desc"
                    )
            );
            JsonArray root = gson.fromJson(body, JsonArray.class);
            List<CloudExpansionVersion> versions = new ArrayList<>();
            if (root != null) {
                for (JsonElement element : root) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    versions.add(new CloudExpansionVersion(
                            string(object, "version"),
                            bool(object, "verified"),
                            string(object, "created_at"),
                            string(object, "jar_file_path"),
                            string(object, "release_notes")
                    ));
                }
            }
            List<CloudExpansionVersion> immutable = List.copyOf(versions);
            synchronized (versionCache) {
                versionCache.put(aggregate.officialExpansionId(), new VersionCacheEntry(immutable, expiryTime()));
            }
            return immutable;
        } catch (Exception exception) {
            log.debug("Failed to fetch eCloud versions for " + name + ": " + exception.getMessage());
            return List.of();
        }
    }

    public Optional<CloudExpansionVersion> findVersion(String name, String version) {
        for (CloudExpansionVersion candidate : getVersions(name)) {
            if (candidate.version().equalsIgnoreCase(version)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public List<String> getLastErrors() {
        return lastErrors;
    }

    public String getOfficialSiteUrl() {
        return OFFICIAL_SITE_URL;
    }

    public synchronized RefreshResult refreshNow() {
        if (shuttingDown || !plugin.isEnabled()) {
            return new RefreshResult(0, List.of("Plugin is shutting down."));
        }
        if (suspended) {
            return new RefreshResult(uniqueExpansionCount(cache), List.of(suspensionReason));
        }
        if (refreshing) {
            return new RefreshResult(uniqueExpansionCount(cache), lastErrors);
        }
        if (!plugin.getConfig().getBoolean("placeholderapi.ecloud.enabled", true)) {
            invalidateCache();
            return new RefreshResult(0, List.of("eCloud is disabled in config."));
        }

        refreshing = true;
        try {
            List<CatalogSource> sources = configuredSources();
            LinkedHashMap<String, MutableMergedExpansion> merged = new LinkedHashMap<>();
            LinkedHashMap<String, SourceState> newStates = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();

            for (CatalogSource source : sources) {
                SourceFetchResult result = fetchSource(source);
                newStates.put(source.id(), result.state());
                if (!result.state().success()) {
                    errors.add(source.id() + ": " + result.state().error());
                    continue;
                }
                for (CatalogEntry entry : result.entries()) {
                    String key = normalize(entry.slug().isBlank() ? entry.name() : entry.slug());
                    MutableMergedExpansion bucket = merged.computeIfAbsent(key, ignored -> new MutableMergedExpansion(key));
                    bucket.add(entry);
                }
            }

            LinkedHashMap<String, AggregatedExpansion> aliasCache = new LinkedHashMap<>();
            for (MutableMergedExpansion mergedExpansion : merged.values()) {
                CatalogEntry primary = pickPrimaryCandidate(mergedExpansion.entries, newStates);
                if (primary == null) {
                    continue;
                }
                CloudExpansion expansion = new CloudExpansion(
                        primary.expansionId(),
                        primary.name(),
                        primary.slug(),
                        primary.author(),
                        primary.latestVersion(),
                        primary.description(),
                        primary.downloadUrl(),
                        primary.pageUrl(),
                        primary.sourceId(),
                        primary.sourceUrl(),
                        primary.dependencyUrl(),
                        primary.placeholders(),
                        primary.platforms(),
                        primary.verified(),
                        primary.authorVerified()
                );
                AggregatedExpansion aggregate = new AggregatedExpansion(expansion, List.copyOf(mergedExpansion.entries), mergedExpansion.officialExpansionId);
                aliasCache.put(normalize(expansion.getName()), aggregate);
                if (!expansion.getSlug().isBlank()) {
                    aliasCache.put(normalize(expansion.getSlug()), aggregate);
                }
            }

            updateInstalledState(aliasCache);
            cache = Map.copyOf(aliasCache);
            sourceStates = Map.copyOf(newStates);
            lastErrors = List.copyOf(errors);
            expiresAt = expiryTime();
            if (!cache.isEmpty() || errors.isEmpty()) {
                suspended = false;
                suspensionReason = "";
            }
            return new RefreshResult(uniqueExpansionCount(cache), lastErrors);
        } finally {
            refreshing = false;
        }
    }

    public RefreshResult refreshNowWithin(long timeoutMillis) {
        if (suspended) {
            return new RefreshResult(uniqueExpansionCount(cache), List.of(suspensionReason));
        }
        long effectiveTimeout = Math.max(500L, timeoutMillis);
        CompletableFuture<RefreshResult> future = CompletableFuture.supplyAsync(
                this::refreshNow,
                command -> plugin.executeAsync(command)
        );
        try {
            return future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            String message = "Timed out while fetching eCloud data after " + effectiveTimeout + "ms.";
            lastErrors = List.of(message);
            return new RefreshResult(uniqueExpansionCount(cache), lastErrors);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            String message = "Interrupted while fetching eCloud data.";
            lastErrors = List.of(message);
            return new RefreshResult(uniqueExpansionCount(cache), lastErrors);
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            lastErrors = List.of(message);
            return new RefreshResult(uniqueExpansionCount(cache), lastErrors);
        }
    }

    public DownloadResult download(String name, String version, Path targetDirectory) {
        if (shuttingDown || !plugin.isEnabled() || Thread.currentThread().isInterrupted()) {
            return new DownloadResult(false, null, "Plugin is shutting down.");
        }
        if (suspended) {
            return new DownloadResult(false, null, suspensionReason);
        }
        ensureFresh();
        AggregatedExpansion aggregate = cache.get(normalize(name));
        if (aggregate == null) {
            return new DownloadResult(false, null, "Expansion not found in the eCloud catalog.");
        }

        String downloadUrl;
        String resolvedVersion;
        String sourceId;
        if (version != null && !version.isBlank()) {
            Optional<CloudExpansionVersion> selectedVersion = findVersion(name, version);
            if (selectedVersion.isEmpty()) {
                return new DownloadResult(false, null, "Version '" + version + "' not found.");
            }
            CloudExpansionVersion cloudVersion = selectedVersion.get();
            downloadUrl = cloudVersion.downloadUrl();
            resolvedVersion = cloudVersion.version();
            sourceId = OFFICIAL_SOURCE_ID;
        } else {
            CatalogEntry bestDownload = pickBestDownloadCandidate(aggregate.candidates(), sourceStates);
            if (bestDownload == null) {
                return new DownloadResult(false, null, "The selected expansion does not expose a download URL.");
            }
            downloadUrl = bestDownload.downloadUrl();
            resolvedVersion = bestDownload.latestVersion();
            sourceId = bestDownload.sourceId();
        }
        if (downloadUrl == null || downloadUrl.isBlank()) {
            return new DownloadResult(false, null, "The selected expansion does not expose a download URL.");
        }

        try {
            Files.createDirectories(targetDirectory);
            HttpResponse<InputStream> response = send(request(downloadUrl, false), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new DownloadResult(false, null, "eCloud returned HTTP " + response.statusCode() + '.');
            }

            String fileName = resolveFileName(aggregate.view(), resolvedVersion, response, downloadUrl);
            Path target = targetDirectory.resolve(fileName);
            try (InputStream input = response.body()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new DownloadResult(true, target, "Downloaded from source '" + sourceId + "'.");
        } catch (Exception exception) {
            log.debug("eCloud download failed for " + name + ": " + exception.getMessage());
            return new DownloadResult(false, null, exception.getMessage());
        }
    }

    public void shutdown() {
        shuttingDown = true;
        invalidateCache();
    }

    private void ensureFresh() {
        if (shuttingDown || !plugin.isEnabled() || suspended) {
            return;
        }
        if (System.currentTimeMillis() >= expiresAt) {
            refreshNow();
        }
    }

    private void updateInstalledState(Map<String, AggregatedExpansion> expansions) {
        LinkedHashSet<AggregatedExpansion> unique = new LinkedHashSet<>(expansions.values());
        for (AggregatedExpansion aggregate : unique) {
            CloudExpansion cloudExpansion = aggregate.view();
            Optional<PlaceholderExpansion> installed = plugin.getLocalExpansionManager().findExpansionByIdentifier(cloudExpansion.getName());
            if (installed.isEmpty()) {
                installed = plugin.getLocalExpansionManager().findExpansionByName(cloudExpansion.getName());
            }
            if (installed.isEmpty() && !cloudExpansion.getSlug().isBlank()) {
                installed = plugin.getLocalExpansionManager().findExpansionByIdentifier(cloudExpansion.getSlug());
            }
            if (installed.isEmpty()) {
                cloudExpansion.setHasExpansion(false);
                cloudExpansion.setShouldUpdate(false);
                continue;
            }
            PlaceholderExpansion expansion = installed.get();
            cloudExpansion.setHasExpansion(true);
            cloudExpansion.setShouldUpdate(cloudExpansion.getLatestVersion() != null
                    && !cloudExpansion.getLatestVersion().isBlank()
                    && !cloudExpansion.getLatestVersion().equalsIgnoreCase(expansion.getVersion()));
        }
    }

    private List<CatalogSource> configuredSources() {
        List<CatalogSource> sources = new ArrayList<>();
        LinkedHashSet<String> seenCatalogs = new LinkedHashSet<>();
        CatalogSource officialSource = new CatalogSource(
                OFFICIAL_SOURCE_ID,
                "official",
                OFFICIAL_SITE_URL,
                OFFICIAL_SITE_BASE_URL,
                100
        );
        sources.add(officialSource);
        seenCatalogs.add(normalizeCatalogUrl(officialSource.catalogUrl()));
        List<String> mirrors = plugin.getConfig().getStringList("placeholderapi.ecloud.mirrors");
        int index = 0;
        for (String mirrorUrl : mirrors) {
            if (mirrorUrl == null || mirrorUrl.isBlank()) {
                continue;
            }
            index++;
            CatalogSource source = classifyMirrorSource("mirror-" + index, mirrorUrl.trim(), 50 - index);
            if (source == null) {
                continue;
            }
            if (!seenCatalogs.add(normalizeCatalogUrl(source.catalogUrl()))) {
                continue;
            }
            sources.add(source);
        }
        return sources;
    }

    private CatalogSource classifyMirrorSource(String id, String mirrorUrl, int priority) {
        try {
            URI uri = URI.create(mirrorUrl);
            String host = uri.getHost();
            if (host != null && OFFICIAL_SITE_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
                String siteBaseUrl = uri.getScheme() == null || uri.getScheme().isBlank()
                        ? OFFICIAL_SITE_BASE_URL
                        : uri.getScheme() + "://" + host;
                return new CatalogSource(
                        id,
                        "official-alias",
                        normalizeOfficialCatalogUrl(siteBaseUrl, uri.getPath()),
                        siteBaseUrl,
                        priority
                );
            }
        } catch (Exception ignored) {
        }
        return new CatalogSource(id, "json", mirrorUrl, "", priority);
    }

    private String normalizeOfficialCatalogUrl(String siteBaseUrl, String path) {
        String normalizedBase = siteBaseUrl.endsWith("/")
                ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1)
                : siteBaseUrl;
        String normalizedPath = path == null ? "" : path.trim();
        if (normalizedPath.isBlank() || "/".equals(normalizedPath)) {
            return normalizedBase + "/expansions/all/";
        }
        if (normalizedPath.endsWith("/")) {
            return normalizedBase + normalizedPath;
        }
        return normalizedBase + normalizedPath + '/';
    }

    private String normalizeCatalogUrl(String url) {
        if (url == null) {
            return "";
        }
        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private SourceFetchResult fetchSource(CatalogSource source) {
        long startedAt = System.nanoTime();
        try {
            List<CatalogEntry> entries = switch (source.type()) {
                case "official", "official-alias" -> fetchOfficialEntries(source);
                case "json" -> fetchJsonMirrorEntries(source);
                default -> List.of();
            };
            long latencyMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
            return new SourceFetchResult(entries, new SourceState(source.id(), source.type(), source.catalogUrl(), source.priority(), latencyMillis, true, entries.size(), ""));
        } catch (Exception exception) {
            long latencyMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
            return new SourceFetchResult(List.of(), new SourceState(source.id(), source.type(), source.catalogUrl(), source.priority(), latencyMillis, false, 0, exception.getMessage()));
        }
    }

    private SourceState probeSource(CatalogSource source) {
        long startedAt = System.nanoTime();
        try {
            switch (source.type()) {
                case "official", "official-alias" -> requestRest(
                        "expansions",
                        Map.of(
                                "select", "id",
                                "limit", "1"
                        )
                );
                case "json" -> {
                    HttpResponse<Void> response = send(request(source.catalogUrl(), false), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("Mirror returned HTTP " + response.statusCode());
                    }
                }
                default -> {
                }
            }
            long latencyMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
            return new SourceState(source.id(), source.type(), source.catalogUrl(), source.priority(), latencyMillis, true, 0, "");
        } catch (Exception exception) {
            long latencyMillis = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            return new SourceState(source.id(), source.type(), source.catalogUrl(), source.priority(), latencyMillis, false, 0, message);
        }
    }

    private List<CatalogEntry> fetchOfficialEntries(CatalogSource source) throws Exception {
        String body = requestRest(
                "expansions",
                Map.of(
                        "select", EXPANSION_SELECT,
                        "order", "updated_at.desc"
                )
        );
        JsonArray root = gson.fromJson(body, JsonArray.class);
        List<CatalogEntry> entries = new ArrayList<>();
        if (root == null) {
            return entries;
        }
        for (JsonElement element : root) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            JsonObject profile = object.has("profiles") && object.get("profiles").isJsonObject()
                    ? object.getAsJsonObject("profiles")
                    : new JsonObject();
            JsonObject version = object.has("versions") && object.get("versions").isJsonObject()
                    ? object.getAsJsonObject("versions")
                    : new JsonObject();
            String slug = string(object, "slug");
            String name = string(object, "name");
            if (name.isBlank()) {
                continue;
            }
            entries.add(new CatalogEntry(
                    source.id(),
                    source.type(),
                    string(object, "id"),
                    name,
                    slug,
                    string(profile, "username"),
                    string(version, "version"),
                    string(object, "description"),
                    string(version, "jar_file_path"),
                    officialExpansionPageUrl(source, slug),
                    string(object, "source_url"),
                    string(object, "dependency_url"),
                    stringList(object.get("placeholders")),
                    stringList(object.get("platform")),
                    bool(object, "verified"),
                    bool(profile, "verified")
            ));
        }
        return entries;
    }

    private String officialExpansionPageUrl(CatalogSource source, String slug) {
        if (slug == null || slug.isBlank()) {
            return source.catalogUrl();
        }
        String siteBaseUrl = source.siteBaseUrl() == null || source.siteBaseUrl().isBlank()
                ? OFFICIAL_SITE_BASE_URL
                : source.siteBaseUrl();
        String normalizedBase = siteBaseUrl.endsWith("/")
                ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1)
                : siteBaseUrl;
        return normalizedBase + "/expansions/" + slug + '/';
    }

    private List<CatalogEntry> fetchJsonMirrorEntries(CatalogSource source) throws Exception {
        HttpResponse<String> response = send(request(source.catalogUrl(), false), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Mirror returned HTTP " + response.statusCode());
        }
        JsonElement root = gson.fromJson(response.body(), JsonElement.class);
        JsonArray expansions = null;
        if (root != null && root.isJsonArray()) {
            expansions = root.getAsJsonArray();
        } else if (root != null && root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("expansions") && object.get("expansions").isJsonArray()) {
                expansions = object.getAsJsonArray("expansions");
            }
        }
        if (expansions == null) {
            throw new IllegalStateException("Mirror JSON must be an array or contain an 'expansions' array.");
        }

        List<CatalogEntry> entries = new ArrayList<>();
        for (JsonElement element : expansions) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String name = stringAny(object, "name", "identifier", "id");
            String slug = stringAny(object, "slug", "key");
            if (name.isBlank()) {
                continue;
            }
            entries.add(new CatalogEntry(
                    source.id(),
                    source.type(),
                    stringAny(object, "id", "slug", "name"),
                    name,
                    slug,
                    stringAny(object, "author", "owner"),
                    stringAny(object, "latestVersion", "latest_version", "version"),
                    stringAny(object, "description", "summary"),
                    stringAny(object, "downloadUrl", "download_url", "jarUrl", "jar_url"),
                    stringAny(object, "pageUrl", "page_url", "url"),
                    stringAny(object, "sourceUrl", "source_url", "source"),
                    stringAny(object, "dependencyUrl", "dependency_url", "dependency"),
                    stringListAny(object, "placeholders", "placeholderList"),
                    stringListAny(object, "platforms", "platform"),
                    boolAny(object, "verified"),
                    boolAny(object, "authorVerified", "author_verified")
            ));
        }
        return entries;
    }

    private CatalogEntry pickPrimaryCandidate(List<CatalogEntry> entries, Map<String, SourceState> states) {
        return entries.stream()
                .sorted(candidateComparator(states))
                .findFirst()
                .orElse(null);
    }

    private CatalogEntry pickBestDownloadCandidate(List<CatalogEntry> entries, Map<String, SourceState> states) {
        return entries.stream()
                .filter(entry -> entry.downloadUrl() != null && !entry.downloadUrl().isBlank())
                .sorted(candidateComparator(states))
                .findFirst()
                .orElse(null);
    }

    private Comparator<CatalogEntry> candidateComparator(Map<String, SourceState> states) {
        return Comparator
                .comparing(CatalogEntry::verified).reversed()
                .thenComparing(CatalogEntry::authorVerified).reversed()
                .thenComparingInt((CatalogEntry entry) -> stateFor(states, entry.sourceId()).priority()).reversed()
                .thenComparingLong(entry -> stateFor(states, entry.sourceId()).latencyMillis())
                .thenComparing(CatalogEntry::name, String.CASE_INSENSITIVE_ORDER);
    }

    private SourceState stateFor(Map<String, SourceState> states, String sourceId) {
        return states.getOrDefault(sourceId, new SourceState(sourceId, "unknown", "", 0, Long.MAX_VALUE, false, 0, "unknown"));
    }

    private HttpClient client() {
        return httpClient;
    }

    private HttpRequest request(String url, boolean json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000, plugin.getConfig().getInt("placeholderapi.ecloud.read-timeout-millis", 15000))))
                .header("User-Agent", plugin.getConfig().getString("placeholderapi.ecloud.user-agent", "eSCore/1.0"))
                .GET();
        if (json) {
            builder.header("apikey", OFFICIAL_ANON_KEY)
                    .header("Authorization", "Bearer " + OFFICIAL_ANON_KEY)
                    .header("Accept", "application/json");
        }
        return builder.build();
    }

    private String requestRest(String table, Map<String, String> parameters) throws Exception {
        if (shuttingDown || !plugin.isEnabled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Plugin is shutting down.");
        }
        StringBuilder url = new StringBuilder(OFFICIAL_REST_URL)
                .append('/')
                .append(table)
                .append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (!first) {
                url.append('&');
            }
            first = false;
            url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            url.append('=');
            url.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = send(request(url.toString(), true), HttpResponse.BodyHandlers.ofString());
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Request interrupted.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("eCloud REST returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        try {
            return client().sendAsync(request, handler)
                    .orTimeout(requestTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause == null ? exception.getMessage() : cause.getMessage(), cause);
        }
    }

    private long requestTimeoutMillis() {
        return Math.max(1000L, plugin.getConfig().getLong("placeholderapi.ecloud.read-timeout-millis", 15000L));
    }

    private void suspend(String reason) {
        String message = reason == null || reason.isBlank() ? "eCloud temporarily unavailable." : reason;
        boolean changed = !suspended || !message.equals(suspensionReason);
        suspended = true;
        suspensionReason = message;
        lastErrors = List.of(message);
        if (changed) {
            log.warn("eCloud suspended: " + message);
        }
    }

    private void resume() {
        boolean changed = suspended;
        suspended = false;
        suspensionReason = "";
        if (changed) {
            log.info("eCloud connectivity restored.");
        }
    }

    private String resolveFileName(CloudExpansion expansion, String version, HttpResponse<?> response, String downloadUrl) {
        Optional<String> disposition = response.headers().firstValue("Content-Disposition");
        if (disposition.isPresent()) {
            String value = disposition.get();
            int index = value.toLowerCase(Locale.ROOT).indexOf("filename=");
            if (index >= 0) {
                String fileName = value.substring(index + "filename=".length()).replace("\"", "").trim();
                if (!fileName.isBlank()) {
                    return fileName;
                }
            }
        }

        String path = URI.create(downloadUrl).getPath();
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) {
            String fileName = path.substring(slash + 1);
            if (!fileName.isBlank()) {
                return fileName;
            }
        }

        String safeName = expansion.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String safeVersion = version == null || version.isBlank() ? "latest" : version;
        return safeName + '-' + safeVersion + ".jar";
    }

    private List<String> stringListAny(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key)) {
                return stringList(object.get(key));
            }
        }
        return List.of();
    }

    private List<String> stringList(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (element.isJsonArray()) {
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
        if (element.isJsonPrimitive()) {
            String value = element.getAsString();
            if (value.isBlank()) {
                return List.of();
            }
            return List.of(value);
        }
        return List.of();
    }

    private String stringAny(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private boolean boolAny(JsonObject object, String... keys) {
        for (String key : keys) {
            if (bool(object, key)) {
                return true;
            }
        }
        return false;
    }

    private boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private long expiryTime() {
        return System.currentTimeMillis() + Math.max(10_000L, plugin.getConfig().getLong("placeholderapi.ecloud.cache-ttl-millis", 1_800_000L));
    }

    private int uniqueExpansionCount(Map<String, AggregatedExpansion> expansions) {
        return new LinkedHashSet<>(expansions.values()).size();
    }

    public record RefreshResult(int totalExpansions, List<String> errors) {
    }

    public record DownloadResult(boolean success, Path file, String message) {
    }

    private record VersionCacheEntry(List<CloudExpansionVersion> versions, long expiresAt) {
    }

    private record CatalogSource(String id, String type, String catalogUrl, String siteBaseUrl, int priority) {
    }

    private record SourceState(String id, String type, String catalogUrl, int priority, long latencyMillis, boolean success, int expansionCount, String error) {
    }

    private record SourceFetchResult(List<CatalogEntry> entries, SourceState state) {
    }

    private record CatalogEntry(
            String sourceId,
            String sourceType,
            String expansionId,
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
            List<String> platforms,
            boolean verified,
            boolean authorVerified
    ) {
    }

    private record AggregatedExpansion(CloudExpansion view, List<CatalogEntry> candidates, String officialExpansionId) {
    }

    private static final class MutableMergedExpansion {
        private final String key;
        private final List<CatalogEntry> entries = new ArrayList<>();
        private String officialExpansionId;

        private MutableMergedExpansion(String key) {
            this.key = key;
        }

        private void add(CatalogEntry entry) {
            entries.add(entry);
            if (entry.expansionId() != null
                    && !entry.expansionId().isBlank()
                    && (OFFICIAL_SOURCE_ID.equals(entry.sourceId()) || entry.sourceType().startsWith("official"))) {
                officialExpansionId = entry.expansionId();
            }
        }
    }
}
