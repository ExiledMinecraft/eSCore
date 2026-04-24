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
import java.util.List;
import java.util.ServiceLoader;

public final class ExtensionManager {
    private final eSCorePlugin plugin;
    private final eScoreApi api;
    private final eScoreLogger log;
    private final List<LoadedExtension> loadedExtensions = new ArrayList<>();

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
        for (LoadedExtension loadedExtension : loadedExtensions) {
            try {
                loadedExtension.extension().disable();
            } catch (Exception exception) {
                log.warn("Failed to disable extension " + loadedExtension.extension().getId() + ": " + exception.getMessage());
            }
            try {
                loadedExtension.classLoader().close();
            } catch (IOException exception) {
                log.warn("Failed to close classloader for extension " + loadedExtension.extension().getId() + ": " + exception.getMessage());
            }
        }
        loadedExtensions.clear();
    }

    public int getLoadedCount() {
        return loadedExtensions.size();
    }

    private void loadExtensionJar(Path path) {
        try {
            URLClassLoader classLoader = new URLClassLoader(new URL[]{path.toUri().toURL()}, plugin.getClass().getClassLoader());
            ServiceLoader<eScoreExtension> serviceLoader = ServiceLoader.load(eScoreExtension.class, classLoader);
            for (eScoreExtension extension : serviceLoader) {
                extension.enable(api, plugin);
                loadedExtensions.add(new LoadedExtension(extension, classLoader));
                log.success("Loaded extension " + extension.getId() + " v" + extension.getVersion());
            }
        } catch (Exception exception) {
            log.warn("Failed to load extension jar " + path.getFileName() + ": " + exception.getMessage());
        }
    }

    private record LoadedExtension(eScoreExtension extension, URLClassLoader classLoader) {
    }
}
