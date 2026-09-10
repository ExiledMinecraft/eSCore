package ru.exiledproject.developments.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.exiledproject.developments.eSCorePlugin;
import ru.exiledproject.developments.integration.IntegrationStatus;
import ru.exiledproject.developments.script.ScriptLoadReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.file.Path;

public final class eScoreCommand implements CommandExecutor, TabCompleter {
    private final eSCorePlugin plugin;

    public eScoreCommand(eSCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            plugin.log().send(sender, "&cInsufficient permissions.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "check" -> {
                if (args.length >= 2 && "profile".equalsIgnoreCase(args[1])) {
                    handleProfile(sender, args);
                } else {
                    handleCheck(sender, 5);
                }
            }
            case "script", "scripts" -> handleScripts(sender, args);
            case "diagnose" -> handleDiagnose(sender);
            case "storage" -> handleStorage(sender, args);
            case "integrations" -> handleIntegrations(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(args[0], List.of("reload", "check", "script", "diagnose", "storage", "integrations"));
        }
        if (args.length == 2 && "check".equalsIgnoreCase(args[0])) {
            return filter(args[1], List.of("profile"));
        }
        if (args.length == 2 && "integrations".equalsIgnoreCase(args[0])) {
            return filter(args[1], List.of("reload", "list", "check"));
        }
        if (args.length == 2 && ("script".equalsIgnoreCase(args[0]) || "scripts".equalsIgnoreCase(args[0]))) {
            return filter(args[1], List.of("list", "validate", "reload", "profile"));
        }
        if (args.length == 2 && "storage".equalsIgnoreCase(args[0])) {
            return filter(args[1], List.of("status", "flush", "export", "import"));
        }
        if (args.length == 3 && ("script".equalsIgnoreCase(args[0]) || "scripts".equalsIgnoreCase(args[0]))) {
            if ("profile".equalsIgnoreCase(args[1])) {
                return filter(args[2], List.of("5", "10", "20", "50"));
            }
            return filter(args[2], new ArrayList<>(plugin.getScriptManager().getPlaceholderIds()));
        }
        if (args.length == 3 && "check".equalsIgnoreCase(args[0]) && "profile".equalsIgnoreCase(args[1])) {
            return filter(args[2], List.of("5", "10", "20", "50"));
        }
        return List.of();
    }

    private void handleReload(CommandSender sender) {
        ScriptLoadReport report = plugin.reloadRuntime();
        plugin.log().send(
                sender,
                "&aReload completed.&7 Scripts: &f" + report.getPlaceholders()
                        + "&7, watchers: &f" + report.getWatchers()
                        + "&7, internal extensions: &f" + plugin.getExtensionManager().getLoadedCount()
                        + "&7, storage: &f" + plugin.getStorageManager().getBackendId()
        );
        sendReportIssues(sender, report);
    }

    private void handleCheck(CommandSender sender, int profileLimit) {
        ScriptLoadReport report = plugin.checkScripts();
        if (report.hasErrors()) {
            plugin.log().send(sender, "&cScript validation completed with errors.&7 Files scanned: &f" + report.getScannedFiles());
        } else {
            plugin.log().send(sender, "&aScript validation completed successfully.&7 Files scanned: &f" + report.getScannedFiles() + "&7, placeholders: &f" + report.getPlaceholders());
        }
        plugin.log().send(sender, "&7Storage backend: &f" + plugin.getStorageManager().getBackendId());
        plugin.log().send(sender, "&7Shared backend: &f" + plugin.getStorageManager().isSharedBackend());
        for (String line : plugin.getScriptManager().getProfileLines(profileLimit)) {
            plugin.log().send(sender, "&7Profile: &f" + line);
        }
        sendReportIssues(sender, report);
    }

    private void handleProfile(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length >= 3) {
            try {
                limit = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException ignored) {
            }
        }
        List<String> lines = plugin.getScriptManager().getProfileLines(limit);
        if (lines.isEmpty()) {
            plugin.log().send(sender, "&eProfiler has no samples yet. Let the server run for a bit and try again.");
            return;
        }
        plugin.log().send(sender, "&aProfiler snapshot.&7 Entries: &f" + lines.size());
        lines.forEach(line -> plugin.log().send(sender, "&7Profile: &f" + line));
    }

    private void handleScripts(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.log().send(sender, "&eUsage: /escore script <list|validate|reload> [id] | profile [limit]");
            return;
        }
        String requestedId = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                List<String> ids = new ArrayList<>(plugin.getScriptManager().getPlaceholderIds());
                ids.sort(String.CASE_INSENSITIVE_ORDER);
                plugin.log().send(sender, "&aLoaded script placeholders: &f" + ids.size());
                ids.forEach(id -> plugin.log().send(sender, "&7- &f" + id));
            }
            case "validate" -> {
                ScriptLoadReport report = plugin.checkScripts();
                plugin.log().send(sender, report.hasErrors() ? "&cValidation failed." : "&aValidation successful.");
                if (!requestedId.isBlank()) {
                    plugin.log().send(sender, "&7Requested id: &f" + requestedId + "&7 (the complete pack is checked to preserve dependency safety)");
                }
                sendReportIssues(sender, report);
            }
            case "reload" -> {
                ScriptLoadReport report = plugin.getScriptManager().reload();
                plugin.log().send(sender, report.hasErrors() ? "&cScript reload rejected; previous runtime remains active." : "&aScripts reloaded atomically.");
                if (!requestedId.isBlank()) {
                    plugin.log().send(sender, "&7Requested id: &f" + requestedId + "&7 (dependent scripts were reloaded with it)");
                }
                sendReportIssues(sender, report);
            }
            case "profile" -> handleProfile(sender, new String[]{"check", "profile", args.length >= 3 ? args[2] : "10"});
            default -> plugin.log().send(sender, "&eUsage: /escore script <list|validate|reload> [id] | profile [limit]");
        }
    }

    private void handleDiagnose(CommandSender sender) {
        plugin.log().send(sender, "&a--- eSCore diagnostics ---");
        plugin.getScriptManager().getRuntimeStatusLines().forEach(line -> plugin.log().send(sender, "&7" + line));
        plugin.getStorageManager().getStatusLines().forEach(line -> plugin.log().send(sender, "&7" + line));
        plugin.log().send(sender, "&7" + plugin.getRedisSyncManager().getStatusLine());
        plugin.log().send(sender, "&7Extensions: &f" + plugin.getExtensionManager().getLoadedCount());
        plugin.log().send(sender, "&7eCloud cached expansions: &f" + plugin.getPlaceholderCompatManager().getCloudExpansionManager().getCachedExpansionCount());
        plugin.getPlaceholderCompatManager().getCloudExpansionManager().getSourceStatusMap()
                .forEach((source, state) -> plugin.log().send(sender, "&7eCloud " + source + ": &f" + state));
    }

    private void handleStorage(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if ("status".equals(action)) {
            plugin.getStorageManager().getStatusLines().forEach(line -> plugin.log().send(sender, "&7" + line));
            return;
        }
        if ("flush".equals(action)) {
            plugin.executeAsync(() -> {
                plugin.getStorageManager().flushAll();
                plugin.runSyncIfEnabled(() -> plugin.log().send(sender, "&aStorage flush completed."));
            });
            return;
        }
        if ("export".equals(action)) {
            String name = args.length >= 3 ? args[2] : "storage-" + System.currentTimeMillis();
            Path file = transferFile(name);
            if (file == null) {
                plugin.log().send(sender, "&cExport name may only contain letters, numbers, dot, dash and underscore.");
                return;
            }
            plugin.executeAsync(() -> {
                try {
                    Path exported = plugin.getStorageManager().exportSnapshot(file);
                    plugin.runSyncIfEnabled(() -> plugin.log().send(sender, "&aStorage exported to &f" + exported.getFileName()));
                } catch (Exception exception) {
                    plugin.runSyncIfEnabled(() -> plugin.log().send(sender, "&cStorage export failed: &7" + exception.getMessage()));
                }
            });
            return;
        }
        if ("import".equals(action)) {
            if (args.length < 4 || !"--confirm".equalsIgnoreCase(args[3])) {
                plugin.log().send(sender, "&eUsage: /escore storage import <file> --confirm");
                return;
            }
            Path file = transferFile(args[2]);
            if (file == null || !java.nio.file.Files.isRegularFile(file)) {
                plugin.log().send(sender, "&cImport file was not found in plugins/eSCore/exports.");
                return;
            }
            plugin.executeAsync(() -> {
                try {
                    var document = plugin.getStorageManager().readImport(file);
                    plugin.runSyncIfEnabled(() -> {
                        int count = plugin.getStorageManager().applyImport(document);
                        plugin.log().send(sender, "&aImported &f" + count + "&a storage values.");
                    });
                } catch (Exception exception) {
                    plugin.runSyncIfEnabled(() -> plugin.log().send(sender, "&cStorage import failed: &7" + exception.getMessage()));
                }
            });
            return;
        }
        plugin.log().send(sender, "&eUsage: /escore storage <status|flush|export|import>");
    }

    private Path transferFile(String requestedName) {
        String name = requestedName.endsWith(".json") ? requestedName : requestedName + ".json";
        if (!name.matches("[A-Za-z0-9._-]{1,100}\\.json")) {
            return null;
        }
        Path directory = plugin.getDataFolder().toPath().resolve("exports").toAbsolutePath().normalize();
        Path file = directory.resolve(name).normalize();
        return file.getParent().equals(directory) ? file : null;
    }

    private void handleIntegrations(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.log().send(sender, "&eUsage: /escore integrations <reload|list|check>");
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.getIntegrationManager().reload();
                plugin.log().send(sender, "&aIntegration registry reloaded.");
                listIntegrations(sender);
            }
            case "list" -> listIntegrations(sender);
            case "check" -> plugin.getIntegrationManager().check().forEach(line -> plugin.log().send(sender, line));
            default -> plugin.log().send(sender, "&eUsage: /escore integrations <reload|list|check>");
        }
    }

    private void listIntegrations(CommandSender sender) {
        for (IntegrationStatus status : plugin.getIntegrationManager().getStatuses()) {
            String stateColor = status.active() ? "&a" : status.available() ? "&e" : "&c";
            String state = status.active() ? "active" : status.available() ? "available" : "missing";
            plugin.log().send(sender, stateColor + status.displayName() + "&7 [" + state + "] &f" + status.message());
        }
    }

    private void sendReportIssues(CommandSender sender, ScriptLoadReport report) {
        report.getWarnings().forEach(warning -> plugin.log().send(sender, "&eWarning: &7" + warning));
        report.getErrors().forEach(error -> plugin.log().send(sender, "&cError: &7" + error));
    }

    private void sendHelp(CommandSender sender) {
        plugin.log().send(sender, "&e/escore reload &7- reload runtime, scripts, storage and integrations");
        plugin.log().send(sender, "&e/escore check &7- validate scripts and report issues");
        plugin.log().send(sender, "&e/escore check profile [limit] &7- show the busiest scripts");
        plugin.log().send(sender, "&e/escore script <list|validate|reload|profile> [id] &7- manage JavaScript placeholders");
        plugin.log().send(sender, "&e/escore diagnose &7- inspect scripts, cache, storage and eCloud");
        plugin.log().send(sender, "&e/escore storage <status|flush|export|import> &7- manage persistence");
        plugin.log().send(sender, "&e/escore integrations <reload|list|check> &7- inspect integration state");
    }

    private List<String> filter(String input, List<String> values) {
        String lowered = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(lowered)) {
                matches.add(value);
            }
        }
        return matches;
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission("escore.admin") || sender.hasPermission("wscore.admin");
    }
}
