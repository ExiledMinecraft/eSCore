package me.clip.placeholderapi.expansion.manager;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.events.ExpansionRegisterEvent;
import me.clip.placeholderapi.events.ExpansionUnregisterEvent;
import me.clip.placeholderapi.events.ExpansionsLoadedEvent;
import me.clip.placeholderapi.expansion.Cacheable;
import me.clip.placeholderapi.expansion.Cleanable;
import me.clip.placeholderapi.expansion.Configurable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Taskable;
import me.clip.placeholderapi.expansion.VersionSpecific;
import me.clip.placeholderapi.expansion.cloud.CloudExpansion;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@SuppressWarnings("deprecation")
public final class LocalExpansionManager implements Listener {
    private final PlaceholderAPIPlugin plugin;
    private final Map<String, PlaceholderExpansion> expansions = new ConcurrentHashMap<>();
    private final Map<String, LoadedExpansionSource> externalSources = new ConcurrentHashMap<>();

    public LocalExpansionManager(PlaceholderAPIPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ensureDirectories();
    }

    public Set<String> getIdentifiers() {
        return new LinkedHashSet<>(expansions.keySet());
    }

    public Collection<PlaceholderExpansion> getExpansions() {
        return List.copyOf(expansions.values());
    }

    public PlaceholderExpansion getExpansion(String identifier) {
        return expansions.get(normalize(identifier));
    }

    public Optional<PlaceholderExpansion> findExpansionByIdentifier(String identifier) {
        return Optional.ofNullable(getExpansion(identifier));
    }

    public Optional<PlaceholderExpansion> findExpansionByName(String name) {
        for (PlaceholderExpansion expansion : expansions.values()) {
            if (expansion.getName().equalsIgnoreCase(name)) {
                return Optional.of(expansion);
            }
        }
        return Optional.empty();
    }

    public Optional<Path> getExpansionSource(String identifier) {
        LoadedExpansionSource source = externalSources.get(normalize(identifier));
        return source == null ? Optional.empty() : Optional.of(source.file());
    }

    public List<Path> getConfiguredDirectories() {
        return List.of(plugin.getPlaceholderApiExpansionsDirectory());
    }

    public Optional<PlaceholderExpansion> register(Class<? extends PlaceholderExpansion> expansionClass) {
        PlaceholderExpansion expansion = createExpansionInstance(expansionClass);
        if (expansion == null) {
            return Optional.empty();
        }
        expansion.setExpansionType(PlaceholderExpansion.Type.EXTERNAL);
        return register(expansion) ? Optional.of(expansion) : Optional.empty();
    }

    public boolean register(PlaceholderExpansion expansion) {
        Objects.requireNonNull(expansion, "expansion");
        String identifier = normalize(expansion.getIdentifier());
        if (!isValidIdentifier(identifier) || expansion.getAuthor() == null || expansion.getVersion() == null) {
            return false;
        }
        if (!expansion.canRegister()) {
            return false;
        }
        if (expansion instanceof VersionSpecific versionSpecific && !versionSpecific.isCompatibleWith(PlaceholderAPIPlugin.getServerVersion())) {
            return false;
        }

        applyDefaults(identifier, expansion);

        PlaceholderExpansion existing = expansions.get(identifier);
        if (existing != null && existing != expansion && !existing.unregister()) {
            return false;
        }

        ExpansionRegisterEvent event = new ExpansionRegisterEvent(expansion);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }

        expansions.put(identifier, expansion);
        if (expansion instanceof Listener listener) {
            Bukkit.getPluginManager().registerEvents(listener, plugin);
        }
        if (expansion instanceof Taskable taskable) {
            if (!runExpansionHook(expansion, "start()", taskable::start)) {
                rollbackRegistration(expansion);
                return false;
            }
        }
        updateCloudState(expansion, true);
        return true;
    }

    public boolean unregister(PlaceholderExpansion expansion) {
        String identifier = normalize(expansion.getIdentifier());
        PlaceholderExpansion removed = expansions.remove(identifier);
        if (removed == null) {
            return false;
        }

        Bukkit.getPluginManager().callEvent(new ExpansionUnregisterEvent(removed));
        if (removed instanceof Listener listener) {
            HandlerList.unregisterAll(listener);
        }
        if (removed instanceof Taskable taskable) {
            runExpansionHook(removed, "stop()", taskable::stop);
        }
        if (removed instanceof Cacheable cacheable) {
            runExpansionHook(removed, "clear()", cacheable::clear);
        }
        updateCloudState(removed, false);
        LoadedExpansionSource source = externalSources.remove(identifier);
        if (source != null) {
            source.identifiers().remove(identifier);
            if (source.identifiers().isEmpty()) {
                try {
                    source.classLoader().close();
                } catch (IOException ignored) {
                }
            }
        }
        return true;
    }

    public boolean unregister(String identifier) {
        return findExpansionByIdentifier(identifier).map(this::unregister).orElse(false);
    }

    public void reloadExternal() {
        reloadExternalDetailed();
    }

    public List<PlaceholderExpansion> reloadExternalDetailed() {
        unloadExternalExpansions();
        ensureDirectories();

        List<PlaceholderExpansion> loaded = new ArrayList<>();
        for (Path directory : getConfiguredDirectories()) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var stream = Files.list(directory)) {
                List<Path> jars = stream
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .toList();
                for (Path jar : jars) {
                    loaded.addAll(loadFromJar(jar));
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("Failed to scan expansion directory " + directory + ": " + exception.getMessage());
            }
        }

        if (!loaded.isEmpty()) {
            Bukkit.getPluginManager().callEvent(new ExpansionsLoadedEvent(loaded));
        }
        return List.copyOf(loaded);
    }

    public List<PlaceholderExpansion> loadExpansionJar(Path jarFile) {
        ensureDirectories();
        return loadFromJar(jarFile);
    }

    public void shutdown() {
        for (PlaceholderExpansion expansion : List.copyOf(expansions.values())) {
            if (expansion.persist()) {
                continue;
            }
            unregister(expansion);
        }
        expansions.clear();
        closeExternalSources();
        HandlerList.unregisterAll(this);
    }

    public PlaceholderExpansion createExpansionInstance(Class<? extends PlaceholderExpansion> expansionClass) throws LinkageError {
        try {
            return expansionClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof LinkageError linkageError) {
                throw linkageError;
            }
            return null;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        for (PlaceholderExpansion expansion : getExpansions()) {
            if (expansion instanceof Cleanable cleanable) {
                runExpansionHook(expansion, "cleanup()", () -> cleanable.cleanup(event.getPlayer()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            return;
        }
        String disabled = event.getPlugin().getName();
        Set<String> provided = new LinkedHashSet<>(event.getPlugin().getPluginMeta().getProvidedPlugins());
        provided.add(disabled);
        for (PlaceholderExpansion expansion : List.copyOf(expansions.values())) {
            String requiredPlugin = expansion.getRequiredPlugin();
            if (requiredPlugin != null && provided.stream().anyMatch(name -> name.equalsIgnoreCase(requiredPlugin))) {
                unregister(expansion);
            }
        }
    }

    private List<PlaceholderExpansion> loadFromJar(Path jarFile) {
        List<PlaceholderExpansion> loaded = new ArrayList<>();
        URLClassLoader classLoader = null;
        long jarStartedAt = System.nanoTime();
        try {
            classLoader = new URLClassLoader(new java.net.URL[]{jarFile.toUri().toURL()}, plugin.getClass().getClassLoader());
            List<Class<? extends PlaceholderExpansion>> classes = timedFindExpansionClasses(classLoader, jarFile);
            if (classes.isEmpty()) {
                classLoader.close();
                return List.of();
            }

            LoadedExpansionSource source = new LoadedExpansionSource(jarFile, classLoader, new LinkedHashSet<>());
            for (Class<? extends PlaceholderExpansion> expansionClass : classes) {
                long classStartedAt = System.nanoTime();
                PlaceholderExpansion expansion = createExpansionInstance(expansionClass);
                if (expansion == null) {
                    warnSlowLoad("instantiate expansion class " + expansionClass.getName(), classStartedAt);
                    continue;
                }
                expansion.setExpansionType(PlaceholderExpansion.Type.EXTERNAL);
                if (!register(expansion)) {
                    warnSlowLoad("register expansion " + expansionClass.getName(), classStartedAt);
                    continue;
                }
                loaded.add(expansion);
                source.identifiers().add(normalize(expansion.getIdentifier()));
                warnSlowLoad("load expansion " + expansion.getIdentifier() + " from " + jarFile.getFileName(), classStartedAt);
            }

            if (source.identifiers().isEmpty()) {
                classLoader.close();
                return List.of();
            }

            for (String identifier : source.identifiers()) {
                externalSources.put(identifier, source);
            }
            warnSlowLoad("scan/load expansion jar " + jarFile.getFileName(), jarStartedAt);
            return loaded;
        } catch (LinkageError | Exception exception) {
            plugin.getLogger().warning("Failed to load expansion jar " + jarFile.getFileName() + ": " + exception.getMessage());
            if (classLoader != null) {
                try {
                    classLoader.close();
                } catch (IOException ignored) {
                }
            }
            return List.of();
        }
    }

    private List<Class<? extends PlaceholderExpansion>> timedFindExpansionClasses(URLClassLoader classLoader, Path jarFile) throws IOException {
        long timeoutMillis = Math.max(250L, plugin.getConfig().getLong("placeholderapi.expansions.class-scan-timeout-millis", 5000L));
        CompletableFuture<List<Class<? extends PlaceholderExpansion>>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return findExpansionClasses(classLoader, jarFile);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }, plugin::executeAsync);

        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            plugin.getLogger().warning("Timed out scanning expansion jar " + jarFile.getFileName() + " after " + timeoutMillis + "ms.");
            return List.of();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime && runtime.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(cause == null ? exception.getMessage() : cause.getMessage(), cause);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Class<? extends PlaceholderExpansion>> findExpansionClasses(URLClassLoader classLoader, Path jarFile) throws IOException {
        List<Class<? extends PlaceholderExpansion>> classes = new ArrayList<>();
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class") || entry.getName().contains("$")) {
                    continue;
                }
                String className = entry.getName().replace('/', '.').substring(0, entry.getName().length() - 6);
                try {
                    Class<?> loadedClass = Class.forName(className, false, classLoader);
                    if (!PlaceholderExpansion.class.isAssignableFrom(loadedClass)
                            || Modifier.isAbstract(loadedClass.getModifiers())
                            || loadedClass.isInterface()) {
                        continue;
                    }
                    classes.add((Class<? extends PlaceholderExpansion>) loadedClass);
                } catch (Throwable ignored) {
                }
            }
        }
        return classes;
    }

    private void unloadExternalExpansions() {
        Set<LoadedExpansionSource> sources = new LinkedHashSet<>(externalSources.values());
        for (String identifier : List.copyOf(externalSources.keySet())) {
            PlaceholderExpansion expansion = expansions.get(identifier);
            if (expansion != null
                    && expansion.getExpansionType() == PlaceholderExpansion.Type.EXTERNAL
                    && !expansion.persist()) {
                unregister(expansion);
            }
        }
        externalSources.clear();
        for (LoadedExpansionSource source : sources) {
            try {
                source.classLoader().close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeExternalSources() {
        Set<LoadedExpansionSource> sources = new LinkedHashSet<>(externalSources.values());
        externalSources.clear();
        for (LoadedExpansionSource source : sources) {
            try {
                source.classLoader().close();
            } catch (IOException ignored) {
            }
        }
    }

    private void ensureDirectories() {
        for (Path directory : getConfiguredDirectories()) {
            try {
                Files.createDirectories(directory);
            } catch (IOException exception) {
                plugin.getLogger().warning("Failed to create expansion directory " + directory + ": " + exception.getMessage());
            }
        }
    }

    private void applyDefaults(String identifier, PlaceholderExpansion expansion) {
        if (!(expansion instanceof Configurable configurable)) {
            return;
        }
        Map<String, Object> defaults = configurable.getDefaults();
        if (defaults == null || defaults.isEmpty()) {
            return;
        }

        boolean changed = false;
        String prefix = "expansions." + identifier + '.';
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String path = prefix + entry.getKey();
            if (entry.getValue() == null) {
                if (plugin.getConfig().contains(path)) {
                    plugin.getConfig().set(path, null);
                    changed = true;
                }
            } else if (!plugin.getConfig().contains(path)) {
                plugin.getConfig().set(path, entry.getValue());
                changed = true;
            }
        }
        if (changed) {
            plugin.saveConfig();
            plugin.reloadConfig();
        }
    }

    private boolean isValidIdentifier(String identifier) {
        return identifier != null && !identifier.isBlank() && !identifier.contains("%") && !identifier.contains("{") && !identifier.contains("}") && !identifier.contains("_");
    }

    private void rollbackRegistration(PlaceholderExpansion expansion) {
        expansions.remove(normalize(expansion.getIdentifier()));
        if (expansion instanceof Listener listener) {
            HandlerList.unregisterAll(listener);
        }
    }

    private void updateCloudState(PlaceholderExpansion expansion, boolean installed) {
        if (!plugin.getConfig().getBoolean("placeholderapi.ecloud.enabled", true)) {
            return;
        }
        plugin.getCloudExpansionManager().findCachedCloudExpansionByName(expansion.getName())
                .ifPresent(cloudExpansion -> {
                    cloudExpansion.setHasExpansion(installed);
                    cloudExpansion.setShouldUpdate(installed
                            && cloudExpansion.getLatestVersion() != null
                            && !cloudExpansion.getLatestVersion().isBlank()
                            && !cloudExpansion.getLatestVersion().equalsIgnoreCase(expansion.getVersion()));
                });
    }

    private boolean runExpansionHook(PlaceholderExpansion expansion, String action, Runnable runnable) {
        long startedAt = System.nanoTime();
        try {
            runnable.run();
            return true;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Expansion '" + expansion.getIdentifier() + "' failed during " + action + ": " + throwable.getMessage());
            return false;
        } finally {
            warnSlowLoad("expansion " + expansion.getIdentifier() + ' ' + action, startedAt);
        }
    }

    private void warnSlowLoad(String action, long startedAt) {
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        long thresholdMillis = Math.max(1L, plugin.getConfig().getLong("placeholderapi.expansions.slow-load-warning-millis", 1000L));
        if (elapsedMillis >= thresholdMillis) {
            plugin.getLogger().warning("Slow PlaceholderAPI operation: " + action + " took " + elapsedMillis + "ms.");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record LoadedExpansionSource(Path file, URLClassLoader classLoader, Set<String> identifiers) {
    }
}
