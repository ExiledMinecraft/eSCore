package ru.exiledproject.developments.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScriptLoadReport {
    private final boolean dryRun;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private int scannedFiles;
    private int loadedFiles;
    private int placeholders;
    private int watchers;

    public ScriptLoadReport(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public int getScannedFiles() {
        return scannedFiles;
    }

    public void incrementScannedFiles() {
        scannedFiles++;
    }

    public int getLoadedFiles() {
        return loadedFiles;
    }

    public void incrementLoadedFiles() {
        loadedFiles++;
    }

    public int getPlaceholders() {
        return placeholders;
    }

    public void addPlaceholders(int amount) {
        placeholders += amount;
    }

    public int getWatchers() {
        return watchers;
    }

    public void addWatchers(int amount) {
        watchers += amount;
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public void error(String message) {
        errors.add(message);
    }

    public void warn(String message) {
        warnings.add(message);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
