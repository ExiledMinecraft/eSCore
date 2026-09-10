package me.clip.placeholderapi.expansion.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import ru.exiledproject.developments.core.eScoreLogger;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarFile;

public final class CloudExpansionManager {
    private static final String OFFICIAL_API_URL = "https://ecloud.placeholderapi.com/api/v3/?platform=bukkit";
    private static final String OFFICIAL_SITE_URL = "https://ecloud.placeholderapi.com";
    private static final String OFFICIAL_SITE_BASE_URL = "https://ecloud.placeholderapi.com";
    private static final String OFFICIAL_SOURCE_ID = "official";
    private static final Set<String> OFFICIAL_SITE_HOSTS = Set.of(
            "api.extendedclip.com",
            "ecloud.placeholderapi.com"
    );

    private final PlaceholderAPIPlugin plugin;
    private final eScoreLogger log;
    private final Gson gson = new Gson();
    private final OfficialEcloudCatalogParser officialCatalogParser = new OfficialEcloudCatalogParser(gson);
    private final HttpClient httpClient;

    private volatile Map<String, AggregatedExpansion> cache = Map.of();
    private volatile Map<String, SourceState> sourceStates = Map.of();
    private volatile List<String> lastErrors = List.of();
    private volatile long expiresAt;
    private volatile boolean shuttingDown;
    private volatile boolean refreshing;
    private volatile boolean suspended;
    private volatile String suspensionReason = "";

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
        return versionsFor(aggregate).stream().map(CloudExpansionVersion::version).toList();
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
        if (aggregate == null) {
            return List.of();
        }
        return versionsFor(aggregate);
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

            if (newStates.values().stream().noneMatch(SourceState::success)) {
                sourceStates = Map.copyOf(newStates);
                lastErrors = List.copyOf(errors);
                expiresAt = System.currentTimeMillis() + 10_000L;
                return new RefreshResult(uniqueExpansionCount(cache), lastErrors);
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
                AggregatedExpansion aggregate = new AggregatedExpansion(expansion, List.copyOf(mergedExpansion.entries));
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
        return download(name, version, targetDirectory, false);
    }

    public DownloadResult download(String name, String version, Path targetDirectory, boolean dryRun) {
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
        String sourceId;
        String resolvedVersion;
        boolean verified;
        if (version != null && !version.isBlank()) {
            Optional<CloudExpansionVersion> selectedVersion = findVersion(name, version);
            if (selectedVersion.isEmpty()) {
                return new DownloadResult(false, null, "Version '" + version + "' not found.");
            }
            CloudExpansionVersion cloudVersion = selectedVersion.get();
            downloadUrl = cloudVersion.downloadUrl();
            sourceId = OFFICIAL_SOURCE_ID;
            resolvedVersion = cloudVersion.version();
            verified = cloudVersion.verified();
        } else {
            CatalogEntry bestDownload = pickBestDownloadCandidate(aggregate.candidates(), sourceStates);
            if (bestDownload == null) {
                return new DownloadResult(false, null, "The selected expansion does not expose a download URL.");
            }
            downloadUrl = bestDownload.downloadUrl();
            sourceId = bestDownload.sourceId();
            resolvedVersion = bestDownload.latestVersion();
            verified = bestDownload.verified();
        }
        if (downloadUrl == null || downloadUrl.isBlank()) {
            return new DownloadResult(false, null, "The selected expansion does not expose a download URL.");
        }
        if (!isDownloadAllowed(name)) {
            return new DownloadResult(false, null, "This expansion is blocked by PAPI_BLOCKED_EXPANSIONS.");
        }
        if (!verified && !Boolean.parseBoolean(System.getenv("PAPI_ALLOW_UNVERIFIED_EXPANSIONS"))) {
            return new DownloadResult(false, null, "This expansion version is not verified and can only be downloaded manually.");
        }

        String fileName = expansionFileName(aggregate.view());
        Path normalizedDirectory = targetDirectory.toAbsolutePath().normalize();
        Path plannedTarget = normalizedDirectory.resolve(fileName).normalize();
        if (!plannedTarget.getParent().equals(normalizedDirectory)) {
            return new DownloadResult(false, null, "Refused an unsafe expansion file name.");
        }
        if (dryRun) {
            return new DownloadResult(true, plannedTarget, "Dry run: would install " + name + " " + resolvedVersion + " from '" + sourceId + "'.");
        }

        Path temporary = null;
        try {
            Files.createDirectories(targetDirectory);
            HttpResponse<InputStream> response = send(request(downloadUrl), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                return new DownloadResult(false, null, "eCloud returned HTTP " + response.statusCode() + '.');
            }

            long maximumBytes = Math.max(1_048_576L, plugin.getConfig().getLong("placeholderapi.ecloud.max-download-bytes", 52_428_800L));
            long declaredBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredBytes > maximumBytes) {
                response.body().close();
                return new DownloadResult(false, null, "Expansion jar exceeds the configured download size limit.");
            }

            Path target = targetDirectory.resolve(fileName).normalize();
            target = target.toAbsolutePath().normalize();
            if (!target.getParent().equals(normalizedDirectory)) {
                response.body().close();
                return new DownloadResult(false, null, "Refused an unsafe expansion file name.");
            }

            temporary = Files.createTempFile(normalizedDirectory, ".escore-download-", ".tmp");
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                copyLimited(input, output, maximumBytes);
            }
            validateJar(temporary);
            backupExisting(target, normalizedDirectory);
            moveIntoPlace(temporary, target);
            temporary = null;
            updateLockFile(normalizedDirectory, normalize(name), resolvedVersion, sourceId, target);
            return new DownloadResult(true, target, "Downloaded from source '" + sourceId + "'.");
        } catch (Exception exception) {
            log.debug("eCloud download failed for " + name + ": " + exception.getMessage());
            String message = exception.getMessage();
            return new DownloadResult(false, null, message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public DownloadResult rollback(String name, Path targetDirectory) {
        Path normalizedDirectory = targetDirectory.toAbsolutePath().normalize();
        AggregatedExpansion aggregate = cache.get(normalize(name));
        String fileName = aggregate == null
                ? "Expansion-" + normalize(name).replaceAll("[^a-z0-9]+", "-") + ".jar"
                : expansionFileName(aggregate.view());
        Path target = normalizedDirectory.resolve(fileName).normalize();
        Path backupDirectory = backupDirectory(normalizedDirectory, fileName);
        try {
            if (!Files.isDirectory(backupDirectory)) {
                return new DownloadResult(false, null, "No backup exists for " + name + '.');
            }
            Path backup;
            try (var files = Files.list(backupDirectory)) {
                backup = files.filter(Files::isRegularFile).max(Comparator.comparing(path -> path.getFileName().toString())).orElse(null);
            }
            if (backup == null) {
                return new DownloadResult(false, null, "No backup exists for " + name + '.');
            }
            validateJar(backup);
            backupExisting(target, normalizedDirectory);
            Path temporary = Files.createTempFile(normalizedDirectory, ".escore-rollback-", ".tmp");
            Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveIntoPlace(temporary, target);
            updateLockFile(normalizedDirectory, normalize(name), "rollback", "local-backup", target);
            return new DownloadResult(true, target, "Rolled back from " + backup.getFileName() + '.');
        } catch (Exception exception) {
            return new DownloadResult(false, null, "Rollback failed: " + safeMessage(exception));
        }
    }

    public List<String> verifyInstalled(Path targetDirectory) {
        Path normalizedDirectory = targetDirectory.toAbsolutePath().normalize();
        Map<String, LockEntry> lockEntries = readLockFile(normalizedDirectory);
        List<String> results = new ArrayList<>();
        for (Map.Entry<String, LockEntry> entry : lockEntries.entrySet()) {
            Path file = normalizedDirectory.resolve(entry.getValue().file()).normalize();
            try {
                if (!file.getParent().equals(normalizedDirectory) || !Files.isRegularFile(file)) {
                    results.add(entry.getKey() + ": missing");
                    continue;
                }
                validateJar(file);
                String actualHash = sha256(file);
                results.add(entry.getKey() + (actualHash.equalsIgnoreCase(entry.getValue().sha256()) ? ": ok" : ": hash mismatch"));
            } catch (Exception exception) {
                results.add(entry.getKey() + ": invalid - " + safeMessage(exception));
            }
        }
        if (results.isEmpty()) {
            results.add("No eCloud-managed expansions are recorded.");
        }
        return List.copyOf(results);
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
                OFFICIAL_API_URL,
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
                        siteBaseUrl + "/api/v3/?platform=bukkit",
                        siteBaseUrl,
                        priority
                );
            }
        } catch (Exception ignored) {
        }
        return new CatalogSource(id, "json", mirrorUrl, "", priority);
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
                case "official", "official-alias" -> {
                    HttpResponse<Void> response = send(request(source.catalogUrl()), HttpResponse.BodyHandlers.discarding());
                    requireSuccess(response.statusCode(), "eCloud");
                }
                case "json" -> {
                    HttpResponse<Void> response = send(request(source.catalogUrl()), HttpResponse.BodyHandlers.discarding());
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
        HttpResponse<String> response = send(request(source.catalogUrl()), HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), "eCloud");
        List<CatalogEntry> entries = new ArrayList<>();
        for (OfficialEcloudCatalogParser.Entry entry : officialCatalogParser.parse(response.body(), source.siteBaseUrl())) {
            entries.add(new CatalogEntry(
                    source.id(),
                    source.type(),
                    entry.name(),
                    entry.name(),
                    entry.slug(),
                    entry.author(),
                    entry.latestVersion(),
                    entry.description(),
                    entry.downloadUrl(),
                    entry.pageUrl(),
                    entry.sourceUrl(),
                    entry.dependencyUrl(),
                    entry.placeholders(),
                    List.of("BUKKIT"),
                    entry.verified(),
                    false,
                    entry.versions()
            ));
        }
        return entries;
    }

    private List<CatalogEntry> fetchJsonMirrorEntries(CatalogSource source) throws Exception {
        HttpResponse<String> response = send(request(source.catalogUrl()), HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), "Mirror");
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
            List<CloudExpansionVersion> versions = parseVersions(object.get("versions"));
            String latestVersion = stringAny(object, "latestVersion", "latest_version", "version");
            CloudExpansionVersion latest = findVersion(versions, latestVersion).orElse(null);
            entries.add(new CatalogEntry(
                    source.id(),
                    source.type(),
                    stringAny(object, "id", "slug", "name"),
                    name,
                    slug,
                    stringAny(object, "author", "owner"),
                    latestVersion,
                    stringAny(object, "description", "summary"),
                    latest == null ? stringAny(object, "downloadUrl", "download_url", "jarUrl", "jar_url") : latest.downloadUrl(),
                    stringAny(object, "pageUrl", "page_url", "url"),
                    stringAny(object, "sourceUrl", "source_url", "source"),
                    stringAny(object, "dependencyUrl", "dependency_url", "dependency"),
                    stringListAny(object, "placeholders", "placeholderList"),
                    stringListAny(object, "platforms", "platform"),
                    boolAny(object, "verified"),
                    boolAny(object, "authorVerified", "author_verified"),
                    versions
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
                .thenComparing(Comparator.comparing(CatalogEntry::authorVerified).reversed())
                .thenComparing(Comparator.comparingInt((CatalogEntry entry) -> stateFor(states, entry.sourceId()).priority()).reversed())
                .thenComparingLong(entry -> stateFor(states, entry.sourceId()).latencyMillis())
                .thenComparing(CatalogEntry::name, String.CASE_INSENSITIVE_ORDER);
    }

    private SourceState stateFor(Map<String, SourceState> states, String sourceId) {
        return states.getOrDefault(sourceId, new SourceState(sourceId, "unknown", "", 0, Long.MAX_VALUE, false, 0, "unknown"));
    }

    private HttpClient client() {
        return httpClient;
    }

    private HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000, plugin.getConfig().getInt("placeholderapi.ecloud.read-timeout-millis", 15000))))
                .header("User-Agent", plugin.getConfig().getString("placeholderapi.ecloud.user-agent", "eSCore/1.0"))
                .header("Accept", "application/json")
                .GET()
                .build();
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

    private String expansionFileName(CloudExpansion expansion) {
        String safeName = normalize(expansion.getName()).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (safeName.isBlank()) {
            safeName = "unnamed";
        }
        return "Expansion-" + safeName + ".jar";
    }

    private void copyLimited(InputStream input, OutputStream output, long maximumBytes) throws Exception {
        byte[] buffer = new byte[8192];
        long copied = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            copied += read;
            if (copied > maximumBytes) {
                throw new IllegalStateException("Expansion jar exceeds the configured download size limit.");
            }
            output.write(buffer, 0, read);
        }
    }

    private void validateJar(Path file) throws Exception {
        try (JarFile jar = new JarFile(file.toFile())) {
            if (!jar.entries().hasMoreElements()) {
                throw new IllegalStateException("Downloaded expansion jar is empty.");
            }
        }
    }

    private void moveIntoPlace(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void backupExisting(Path target, Path targetDirectory) throws Exception {
        if (!Files.isRegularFile(target)) {
            return;
        }
        Path backupDirectory = backupDirectory(targetDirectory, target.getFileName().toString());
        Files.createDirectories(backupDirectory);
        Path backup = backupDirectory.resolve(String.format(Locale.ROOT, "%019d-%s.jar",
                System.currentTimeMillis(), UUID.randomUUID().toString().substring(0, 8)));
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        int retain = Math.max(1, plugin.getConfig().getInt("placeholderapi.ecloud.backups-to-keep", 3));
        try (var files = Files.list(backupDirectory)) {
            List<Path> oldBackups = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .skip(retain)
                    .toList();
            for (Path oldBackup : oldBackups) {
                Files.deleteIfExists(oldBackup);
            }
        }
    }

    private Path backupDirectory(Path targetDirectory, String fileName) {
        return targetDirectory.resolve(".escore-backups").resolve(fileName);
    }

    private void updateLockFile(Path targetDirectory, String name, String version, String source, Path file) throws Exception {
        Map<String, LockEntry> entries = new LinkedHashMap<>(readLockFile(targetDirectory));
        entries.put(name, new LockEntry(file.getFileName().toString(), version == null ? "" : version,
                source, sha256(file), System.currentTimeMillis()));
        Path lockFile = targetDirectory.resolve(".escore-ecloud-lock.json");
        Path temporary = Files.createTempFile(targetDirectory, ".escore-ecloud-lock-", ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
            output.write(gson.toJson(entries).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        moveIntoPlace(temporary, lockFile);
    }

    private Map<String, LockEntry> readLockFile(Path targetDirectory) {
        Path lockFile = targetDirectory.resolve(".escore-ecloud-lock.json");
        if (!Files.isRegularFile(lockFile)) {
            return Map.of();
        }
        try {
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, LockEntry>>() { }.getType();
            Map<String, LockEntry> entries = gson.fromJson(Files.readString(lockFile), type);
            return entries == null ? Map.of() : entries;
        } catch (Exception exception) {
            log.warn("Cannot read eCloud lock file: " + safeMessage(exception));
            return Map.of();
        }
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private boolean isDownloadAllowed(String expansionName) {
        String blocked = System.getenv("PAPI_BLOCKED_EXPANSIONS");
        if (blocked == null || blocked.isBlank()) {
            return true;
        }
        for (String name : blocked.split(",")) {
            if (name.trim().equalsIgnoreCase(expansionName)) {
                return false;
            }
        }
        return true;
    }

    private void requireSuccess(int statusCode, String source) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException(source + " returned HTTP " + statusCode);
        }
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
            String downloadUrl = stringAny(object, "url", "download_url", "downloadUrl", "jar_file_path");
            if (version.isBlank() || downloadUrl.isBlank()) {
                continue;
            }
            versions.add(new CloudExpansionVersion(
                    version,
                    bool(object, "verified"),
                    stringAny(object, "created_at", "createdAt"),
                    downloadUrl,
                    stringAny(object, "release_notes", "releaseNotes")
            ));
        }
        return List.copyOf(versions);
    }

    private Optional<CloudExpansionVersion> findVersion(List<CloudExpansionVersion> versions, String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        return versions.stream().filter(candidate -> candidate.version().equalsIgnoreCase(version)).findFirst();
    }

    private List<CloudExpansionVersion> versionsFor(AggregatedExpansion aggregate) {
        LinkedHashMap<String, CloudExpansionVersion> versions = new LinkedHashMap<>();
        for (CatalogEntry candidate : aggregate.candidates()) {
            for (CloudExpansionVersion version : candidate.versions()) {
                versions.putIfAbsent(normalize(version.version()), version);
            }
        }
        return List.copyOf(versions.values());
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
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
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

    private record LockEntry(String file, String version, String source, String sha256, long installedAt) {
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
            boolean authorVerified,
            List<CloudExpansionVersion> versions
    ) {
    }

    private record AggregatedExpansion(CloudExpansion view, List<CatalogEntry> candidates) {
    }

    private static final class MutableMergedExpansion {
        private final String key;
        private final List<CatalogEntry> entries = new ArrayList<>();

        private MutableMergedExpansion(String key) {
            this.key = key;
        }

        private void add(CatalogEntry entry) {
            entries.add(entry);
        }
    }
}
