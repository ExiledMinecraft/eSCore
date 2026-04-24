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
            case "integrations" -> handleIntegrations(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(args[0], List.of("reload", "check", "integrations"));
        }
        if (args.length == 2 && "check".equalsIgnoreCase(args[0])) {
            return filter(args[1], List.of("profile"));
        }
        if (args.length == 2 && "integrations".equalsIgnoreCase(args[0])) {
            return filter(args[1], List.of("reload", "list", "check"));
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
