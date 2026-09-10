package ru.exiledproject.developments.extension;

import ru.exiledproject.developments.api.eScoreApi;
import ru.exiledproject.developments.api.eScoreExtension;
import ru.exiledproject.developments.core.eScoreLogger;
import ru.exiledproject.developments.eSCorePlugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceConfigurationError;
import java.util.Set;

public final class ExtensionManager {
    private final eSCorePlugin plugin;
    private final eScoreApi api;
    private final eScoreLogger log;
    private final List<LoadedExtensionJar> loadedJars = new ArrayList<>();
    private final Set<String> loadedIds = new HashSet<>();

    public ExtensionManager(eSCorePlugin plugin, eScoreApi api, eScoreLogger log) {
        this.plugin = plugin;
        this.api = api;
        this.log = log;
    }

    public void reload() {
        shutdown();
        Path directory = plugin.getExtensionsDirectory();
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                files.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().forEach(this::loadExtensionJar);
            }
        } catch (IOException exception) {
            log.warn("Failed to scan extensions directory: " + exception.getMessage());
        }
    }

    public void shutdown() {
        for (LoadedExtensionJar loadedJar : loadedJars.reversed()) {
            disableExtensions(loadedJar.extensions());
            try {
                loadedJar.classLoader().close();
            } catch (IOException exception) {
                log.warn("Failed to close classloader for " + loadedJar.path().getFileName() + ": " + exception.getMessage());
            }
        }
        loadedJars.clear();
        loadedIds.clear();
    }

    public int getLoadedCount() {
        return loadedJars.stream().mapToInt(loadedJar -> loadedJar.extensions().size()).sum();
    }

    private void loadExtensionJar(Path path) {
        URLClassLoader classLoader = null;
        List<eScoreExtension> enabled = new ArrayList<>();
        try {
            classLoader = new URLClassLoader(new URL[]{path.toUri().toURL()}, plugin.getClass().getClassLoader());
            ServiceLoader<eScoreExtension> serviceLoader = ServiceLoader.load(eScoreExtension.class, classLoader);
            for (eScoreExtension extension : serviceLoader) {
                String id = extension.getId();
                if (id == null || id.isBlank()) {
                    throw new IllegalStateException("Extension in " + path.getFileName() + " has an empty id.");
                }
                String normalizedId = id.toLowerCase(java.util.Locale.ROOT);
                if (loadedIds.contains(normalizedId)) {
                    throw new IllegalStateException("Duplicate extension id: " + id);
                }
                extension.enable(api, plugin);
                loadedIds.add(normalizedId);
                enabled.add(extension);
                log.success("Loaded extension " + extension.getId() + " v" + extension.getVersion());
            }
            if (enabled.isEmpty()) {
                classLoader.close();
                log.warn("Extension jar " + path.getFileName() + " does not provide an eScoreExtension service.");
                return;
            }
            loadedJars.add(new LoadedExtensionJar(path, classLoader, List.copyOf(enabled)));
        } catch (Exception | LinkageError | ServiceConfigurationError exception) {
            log.warn("Failed to load extension jar " + path.getFileName() + ": " + exception.getMessage());
            disableExtensions(enabled);
            enabled.forEach(extension -> loadedIds.remove(extension.getId().toLowerCase(java.util.Locale.ROOT)));
            if (classLoader != null) {
                try {
                    classLoader.close();
                } catch (IOException closeException) {
                    log.warn("Failed to close classloader for " + path.getFileName() + ": " + closeException.getMessage());
                }
            }
        }
    }

    private void disableExtensions(List<eScoreExtension> extensions) {
        for (eScoreExtension extension : extensions.reversed()) {
            try {
                extension.disable();
            } catch (Exception exception) {
                log.warn("Failed to disable extension " + extension.getId() + ": " + exception.getMessage());
            }
        }
    }

    private record LoadedExtensionJar(Path path, URLClassLoader classLoader, List<eScoreExtension> extensions) {
    }
}
