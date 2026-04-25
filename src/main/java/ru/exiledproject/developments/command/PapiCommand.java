package ru.exiledproject.developments.command;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.cloud.CloudExpansion;
import me.clip.placeholderapi.expansion.cloud.CloudExpansionManager;
import me.clip.placeholderapi.expansion.cloud.CloudExpansionVersion;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.exiledproject.developments.eSCorePlugin;
import ru.exiledproject.developments.integration.IntegrationStatus;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class PapiCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DUMP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int ECLOUD_PAGE_SIZE = 10;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final eSCorePlugin plugin;

    public PapiCommand(eSCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            sendLine(sender, "Insufficient permissions.");
            return true;
        }

        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sendHelp(sender);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "list" -> handleList(sender);
            case "info" -> handleInfo(sender, args);
            case "parse" -> handleParse(sender, args);
            case "bcparse" -> handleBroadcastParse(sender, args);
            case "cmdparse" -> handleCommandParse(sender, args);
            case "parserel" -> handleParseRel(sender, args);
            case "register" -> handleRegister(sender, args);
            case "unregister" -> handleUnregister(sender, args);
            case "version" -> handleVersion(sender);
            case "dump" -> handleDump(sender);
            case "ecloud" -> handleEcloud(sender, args);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(args[0], List.of("help", "reload", "list", "info", "parse", "bcparse", "cmdparse", "parserel", "register", "unregister", "version", "dump", "ecloud"));
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        if (("parse".equals(subCommand) || "bcparse".equals(subCommand) || "cmdparse".equals(subCommand)) && args.length == 2) {
            return filter(args[1], parsePlayerSuggestions(sender));
        }
        if ("parserel".equals(subCommand) && args.length <= 3) {
            return filter(args[args.length - 1], onlinePlayerSuggestions(sender));
        }
        if (("parse".equals(subCommand) || "bcparse".equals(subCommand) || "cmdparse".equals(subCommand)) && args.length >= 3) {
            return completeParsePlaceholder(args[args.length - 1]);
        }

        if (args.length == 2) {
            return switch (subCommand) {
                case "info", "unregister" -> filter(args[1], installedIdentifiers());
                case "ecloud" -> filter(args[1], List.of("status", "refresh", "clear", "enable", "disable", "list", "info", "placeholders", "download", "update"));
                default -> List.of();
            };
        }

        if (!"ecloud".equals(subCommand)) {
            return List.of();
        }

        String ecloudSub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            return switch (ecloudSub) {
                case "list" -> filter(args[2], List.of("all", "installed", "search"));
                case "info", "placeholders", "download" -> filterCloudExpansionNames(args[2]);
                case "update" -> filter(args[2], updatableExpansionSuggestions());
                default -> List.of();
            };
        }

        if (args.length == 4) {
            return switch (ecloudSub) {
                case "info", "download" -> filterCloudVersions(args[2], args[3]);
                case "list" -> {
                    if ("all".equalsIgnoreCase(args[2]) || "installed".equalsIgnoreCase(args[2])) {
                        yield filter(args[3], pageSuggestions(args[2], "", false));
                    }
                    yield List.of();
                }
                default -> List.of();
            };
        }

        if (args.length == 5 && "list".equalsIgnoreCase(args[1]) && "search".equalsIgnoreCase(args[2])) {
            return filter(args[4], pageSuggestions("search", join(args, 3, args.length - 1), true));
        }

        return List.of();
    }

    private boolean handleReload(CommandSender sender) {
        sendPapi(sender, "Placeholder expansion registration initializing...");
        sendPapi(sender, "Fetching available expansion information...");

        runAsync(() -> {
            CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
            LocalExpansionManager local = plugin.getPlaceholderCompatManager().getLocalExpansionManager();

            cloud.markStale();
            CloudExpansionManager.RefreshResult refreshResult = cloud.refreshNowWithin(ecloudCommandTimeoutMillis());
            List<PlaceholderExpansion> loaded = local.reloadExternalDetailed().stream()
                    .sorted(Comparator.comparing(expansion -> expansion.getIdentifier().toLowerCase(Locale.ROOT)))
                    .toList();
            plugin.getIntegrationManager().reload();

            long updates = loaded.stream()
                    .map(expansion -> resolveCachedCloudExpansion(cloud, expansion))
                    .flatMap(Optional::stream)
                    .filter(CloudExpansion::shouldUpdate)
                    .count();
            List<IntegrationStatus> statuses = plugin.getIntegrationManager().getStatuses();

            runSync(() -> {
                for (PlaceholderExpansion expansion : loaded) {
                    sendPapi(sender, "Successfully registered external expansion: " + expansion.getIdentifier() + " [" + expansion.getVersion() + "]");
                }

                String summary = loaded.size() + " placeholder hook(s) registered!";
                if (updates > 0) {
                    summary += " " + updates + " placeholder hook(s) have an update available.";
                }
                sendPapi(sender, summary);

                List<IntegrationStatus> failedHooks = statuses.stream()
                        .filter(status -> status.available() && !status.active())
                        .toList();
                if (!failedHooks.isEmpty()) {
                    sendPapi(sender, "Hook check complete: " + failedHooks.size() + " integration hook(s) need attention.");
                    for (IntegrationStatus status : failedHooks) {
                        sendPapi(sender, status.displayName() + ": " + status.message());
                    }
                }

                for (String error : refreshResult.errors()) {
                    sendPapi(sender, "eCloud: " + error);
                }
            });
        });
        return true;
    }

    private boolean handleList(CommandSender sender) {
        List<String> identifiers = new ArrayList<>(plugin.getPlaceholderCompatManager().getLocalExpansionManager().getIdentifiers());
        identifiers.sort(String.CASE_INSENSITIVE_ORDER);
        if (identifiers.isEmpty()) {
            sendLine(sender, "There are no placeholder hooks active!");
            return true;
        }

        sendLine(sender, "A total of " + identifiers.size() + " placeholder hook(s) are active:");
        for (int index = 0; index < identifiers.size(); index += 10) {
            int end = Math.min(identifiers.size(), index + 10);
            sendLine(sender, String.join(", ", identifiers.subList(index, end)));
        }
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendLine(sender, "You must specify the name of the expansion.");
            return true;
        }

        LocalExpansionManager manager = plugin.getPlaceholderCompatManager().getLocalExpansionManager();
        Optional<PlaceholderExpansion> installed = manager.findExpansionByIdentifier(args[1]);
        if (installed.isPresent()) {
            PlaceholderExpansion expansion = installed.get();
            sendLine(sender, "Placeholder expansion info for: " + expansion.getName());
            sendLine(sender, "Status: " + (expansion.isRegistered() ? "Registered" : "NotRegistered"));
            if (expansion.getAuthor() != null) {
                sendLine(sender, "Author: " + expansion.getAuthor());
            }
            if (expansion.getVersion() != null) {
                sendLine(sender, "Version: " + expansion.getVersion());
            }
            if (expansion.getRequiredPlugin() != null) {
                sendLine(sender, "Requires plugin: " + expansion.getRequiredPlugin());
            }
            manager.getExpansionSource(expansion.getIdentifier()).ifPresent(path -> sendLine(sender, "Jar: " + path.getFileName()));
            List<String> placeholders = expansion.getPlaceholders();
            if (placeholders != null && !placeholders.isEmpty()) {
                sendLine(sender, "-- Placeholders --");
                placeholders.forEach(placeholder -> sendLine(sender, placeholder));
            }
            return true;
        }

        runAsync(() -> {
            CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
            Optional<CloudExpansion> remote = cloud.findCloudExpansionByName(args[1]);
            List<CloudExpansionVersion> versions = remote.isPresent() ? cloud.getVersions(remote.get().getName()) : List.of();
            runSync(() -> {
                if (remote.isEmpty()) {
                    sendLine(sender, "There is no expansion loaded with the identifier: " + args[1]);
                    cloud.getLastErrors().forEach(error -> sendPapi(sender, "eCloud: " + error));
                    return;
                }
                sendLine(sender, "eCloud expansion info for: " + remote.get().getName());
                sendLine(sender, "Author: " + blank(remote.get().getAuthor()));
                sendLine(sender, "Latest version: " + blank(remote.get().getLatestVersion()));
                sendLine(sender, "Installed: " + remote.get().hasExpansion());
                sendLine(sender, "Update available: " + remote.get().shouldUpdate());
                if (!remote.get().getDescription().isBlank()) {
                    sendLine(sender, "Description: " + remote.get().getDescription());
                }
                if (!versions.isEmpty()) {
                    sendLine(sender, "Available versions: " + String.join(", ", versions.stream().map(CloudExpansionVersion::version).toList()));
                }
            });
        });
        return true;
    }

    private boolean handleParse(CommandSender sender, String[] args) {
        ParsedPlaceholderTarget target = parseTarget(sender, args, 1, "/papi parse <me|--null|player name> <message>");
        if (target == null) {
            return true;
        }
        if (!target.nullTarget() && target.player() == null) {
            sendLine(sender, "Failed to find player: " + args[1]);
            return true;
        }
        sendRaw(sender, PlaceholderAPI.setPlaceholders(target.player(), join(args, target.textStartIndex())));
        return true;
    }

    private boolean handleBroadcastParse(CommandSender sender, String[] args) {
        ParsedPlaceholderTarget target = parseTarget(sender, args, 1, "/papi bcparse <me|--null|player name> <message>");
        if (target == null) {
            return true;
        }
        if (!target.nullTarget() && target.player() == null) {
            sendLine(sender, "Failed to find player: " + args[1]);
            return true;
        }
        String message = PlaceholderAPI.setPlaceholders(target.player(), join(args, target.textStartIndex()));
        Bukkit.getOnlinePlayers().forEach(player -> player.sendMessage(message));
        Bukkit.getConsoleSender().sendMessage(message);
        return true;
    }

    private boolean handleCommandParse(CommandSender sender, String[] args) {
        ParsedPlaceholderTarget target = parseTarget(sender, args, 1, "/papi cmdparse <me|--null|player name> <command>");
        if (target == null) {
            return true;
        }
        if (!target.nullTarget() && target.player() == null) {
            sendLine(sender, "Failed to find player: " + args[1]);
            return true;
        }
        Bukkit.dispatchCommand(sender, PlaceholderAPI.setPlaceholders(target.player(), join(args, target.textStartIndex())));
        return true;
    }

    private boolean handleParseRel(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sendLine(sender, "You must supply two targets, and a message: /papi parserel {target one} {target two} {message}");
            return true;
        }

        Player first = resolveOnlinePlayer(sender, args[1]);
        if (first == null) {
            sendLine(sender, "Failed to find player: " + args[1]);
            return true;
        }

        Player second = resolveOnlinePlayer(sender, args[2]);
        if (second == null) {
            sendLine(sender, "Failed to find player: " + args[2]);
            return true;
        }

        sendRaw(sender, PlaceholderAPI.setRelationalPlaceholders(first, second, join(args, 3)));
        return true;
    }

    private boolean handleRegister(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendLine(sender, "Usage: /papi register <file name>");
            return true;
        }
        Path jar = resolveJarPath(args[1]);
        if (jar == null || !Files.exists(jar)) {
            sendLine(sender, "Jar file not found.");
            return true;
        }

        List<PlaceholderExpansion> loaded = plugin.getPlaceholderCompatManager().getLocalExpansionManager().loadExpansionJar(jar);
        if (loaded.isEmpty()) {
            sendLine(sender, "No PlaceholderExpansion classes found in jar.");
            return true;
        }

        for (PlaceholderExpansion expansion : loaded.stream().sorted(Comparator.comparing(PlaceholderExpansion::getIdentifier, String.CASE_INSENSITIVE_ORDER)).toList()) {
            sendPapi(sender, "Successfully registered external expansion: " + expansion.getIdentifier() + " [" + expansion.getVersion() + "]");
        }
        return true;
    }

    private boolean handleUnregister(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendLine(sender, "Usage: /papi unregister <expansion name>");
            return true;
        }

        LocalExpansionManager manager = plugin.getPlaceholderCompatManager().getLocalExpansionManager();
        List<PlaceholderExpansion> removed = new ArrayList<>();
        String query = args[1];
        if (query.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            for (PlaceholderExpansion expansion : manager.getExpansions()) {
                boolean matches = manager.getExpansionSource(expansion.getIdentifier())
                        .map(path -> path.getFileName().toString().equalsIgnoreCase(query))
                        .orElse(false);
                if (matches && manager.unregister(expansion)) {
                    removed.add(expansion);
                }
            }
        } else {
            manager.findExpansionByIdentifier(query).ifPresent(expansion -> {
                if (manager.unregister(expansion)) {
                    removed.add(expansion);
                }
            });
        }

        if (removed.isEmpty()) {
            sendLine(sender, "Expansion not found.");
            return true;
        }

        for (PlaceholderExpansion expansion : removed.stream().sorted(Comparator.comparing(PlaceholderExpansion::getIdentifier, String.CASE_INSENSITIVE_ORDER)).toList()) {
            sendPapi(sender, "Unregistered expansion: " + expansion.getIdentifier());
        }
        return true;
    }

    private boolean handleVersion(CommandSender sender) {
        sendLine(sender, "&bPlaceholderAPI &7(" + PlaceholderAPIPlugin.getEmbeddedPapiVersion() + ")");
        sendLine(sender, "&7Author: &f[PlaceholderAPI Team, WillowStudio]");
        sendLine(sender, "&7PAPI Commands: &e/papi help");
        sendLine(sender, "&7eCloud Commands: &e/papi ecloud");
        sendLine(sender, "&7Embedded in eSCore: &f" + plugin.getPluginMeta().getVersion());
        return true;
    }

    private boolean handleDump(CommandSender sender) {
        try {
            Path dumpsDirectory = plugin.getDataFolder().toPath().resolve("dumps");
            Files.createDirectories(dumpsDirectory);
            Path file = dumpsDirectory.resolve("papi-dump-" + LocalDateTime.now().format(DUMP_TIME) + ".txt");
            List<String> lines = new ArrayList<>();
            lines.add("eSCore PlaceholderAPI Dump");
            lines.add("Embedded PAPI Version: " + PlaceholderAPIPlugin.getEmbeddedPapiVersion());
            lines.add("eSCore Version: " + plugin.getPluginMeta().getVersion());
            lines.add("Server: " + Bukkit.getName() + ' ' + Bukkit.getVersion());
            lines.add("Online players: " + Bukkit.getOnlinePlayers().size());
            lines.add("Storage: " + plugin.getStorageManager().getBackendId());
            lines.add("Cloud enabled: " + plugin.getConfig().getBoolean("placeholderapi.ecloud.enabled", true));
            lines.add("Cloud url: " + plugin.getPlaceholderCompatManager().getCloudExpansionManager().getOfficialSiteUrl());
            lines.add("Registered expansions: " + plugin.getPlaceholderCompatManager().getLocalExpansionManager().getExpansions().size());
            for (PlaceholderExpansion expansion : plugin.getPlaceholderCompatManager().getLocalExpansionManager().getExpansions().stream().sorted(Comparator.comparing(PlaceholderExpansion::getIdentifier)).toList()) {
                lines.add(" - " + expansion.getIdentifier() + " v" + expansion.getVersion() + " [" + expansion.getExpansionType() + ']');
            }
            Files.writeString(file, String.join(System.lineSeparator(), lines));
            sendLine(sender, "Dump saved: " + file.getFileName());
        } catch (IOException exception) {
            sendLine(sender, "Failed to create dump: " + exception.getMessage());
        }
        return true;
    }

    private boolean handleEcloud(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendEcloudHelp(sender);
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> handleEcloudStatus(sender);
            case "refresh" -> handleEcloudRefresh(sender);
            case "clear" -> handleEcloudClear(sender);
            case "enable" -> handleEcloudToggle(sender, true);
            case "disable" -> handleEcloudToggle(sender, false);
            case "list" -> handleEcloudList(sender, args);
            case "info" -> handleEcloudInfo(sender, args);
            case "placeholders" -> handleEcloudPlaceholders(sender, args);
            case "download" -> handleEcloudDownload(sender, args);
            case "update" -> handleEcloudUpdate(sender, args);
            default -> {
                sendEcloudHelp(sender);
                yield true;
            }
        };
    }

    private boolean handleEcloudStatus(CommandSender sender) {
        CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
        sendLine(sender, "&bPlaceholderAPI eCloud");
        sendLine(sender, "&7Enabled: " + (plugin.getConfig().getBoolean("placeholderapi.ecloud.enabled", true) ? "&atrue" : "&cfalse"));
        sendLine(sender, "&7Suspended: " + (cloud.isSuspended() ? "&ctrue" : "&afalse"));
        if (cloud.isSuspended()) {
            sendLine(sender, "&7Reason: &e" + cloud.getSuspensionReason());
        }
        sendLine(sender, "&7Provider: &fofficial PlaceholderAPI eCloud");
        sendLine(sender, "&7Website: &f" + cloud.getOfficialSiteUrl());
        sendLine(sender, "&7Expansions dir: &f" + plugin.getPlaceholderApiExpansionsDirectory());
        sendLine(sender, "&7Cached expansions: &f" + cloud.getCachedExpansionCount());
        cloud.getSourceStatusMap().forEach((id, state) -> sendLine(sender, "&8- &b" + id + "&7 -> " + state));
        cloud.getLastErrors().forEach(error -> sendLine(sender, "&e" + error));
        return true;
    }

    private boolean handleEcloudRefresh(CommandSender sender) {
        runAsync(() -> {
            CloudExpansionManager.RefreshResult result = plugin.getPlaceholderCompatManager().getCloudExpansionManager().refreshNowWithin(ecloudCommandTimeoutMillis());
            runSync(() -> {
                sendLine(sender, "eCloud cache refreshed. Expansions: " + result.totalExpansions());
                result.errors().forEach(error -> sendLine(sender, "eCloud: " + error));
            });
        });
        return true;
    }

    private boolean handleEcloudClear(CommandSender sender) {
        plugin.getPlaceholderCompatManager().getCloudExpansionManager().invalidateCache();
        sendLine(sender, "eCloud cache cleared.");
        return true;
    }

    private boolean handleEcloudToggle(CommandSender sender, boolean enabled) {
        plugin.getConfig().set("placeholderapi.ecloud.enabled", enabled);
        plugin.saveConfig();
        CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
        if (enabled) {
            cloud.reactivate();
            cloud.markStale();
            cloud.warmupAsync();
        } else {
            cloud.invalidateCache();
        }
        sendLine(sender, enabled ? "&aeCloud enabled." : "&ceCloud disabled.");
        return true;
    }

    private boolean handleEcloudList(CommandSender sender, String[] args) {
        EcloudListRequest request = parseEcloudListRequest(args);
        runAsync(() -> {
            CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
            CloudExpansionManager.RefreshResult refreshResult = cloud.refreshNowWithin(ecloudCommandTimeoutMillis());
            List<CloudExpansion> expansions = new ArrayList<>(cloud.getCachedExpansions());
            expansions.sort(Comparator.comparing(expansion -> expansion.getName().toLowerCase(Locale.ROOT)));

            switch (request.mode()) {
                case "installed" -> expansions.removeIf(expansion -> !expansion.hasExpansion());
                case "author" -> expansions.removeIf(expansion -> !expansion.getAuthor().toLowerCase(Locale.ROOT).contains(request.query().toLowerCase(Locale.ROOT)));
                case "search" -> expansions.removeIf(expansion -> !(expansion.getName().toLowerCase(Locale.ROOT).contains(request.query().toLowerCase(Locale.ROOT))
                        || expansion.getAuthor().toLowerCase(Locale.ROOT).contains(request.query().toLowerCase(Locale.ROOT))));
                default -> {
                }
            }

            int pageCount = Math.max(1, (int) Math.ceil((double) expansions.size() / ECLOUD_PAGE_SIZE));
            int page = Math.max(1, Math.min(request.page(), pageCount));
            int from = Math.max(0, (page - 1) * ECLOUD_PAGE_SIZE);
            int to = Math.min(expansions.size(), from + ECLOUD_PAGE_SIZE);
            List<CloudExpansion> pageValues = from >= to ? List.of() : expansions.subList(from, to);

            runSync(() -> {
                if (expansions.isEmpty()) {
                    sendLine(sender, "No expansions available to list.");
                    return;
                }
                sendLine(sender, "eCloud expansions: " + request.mode() + " (page " + page + '/' + pageCount + ")");
                int counter = from + 1;
                for (CloudExpansion expansion : pageValues) {
                    String state = expansion.shouldUpdate() ? "update available" : (expansion.hasExpansion() ? "installed" : "remote");
                    sendLine(sender, counter++ + ". " + expansion.getName() + " [" + blank(expansion.getLatestVersion()) + "] - " + blank(expansion.getAuthor()) + " - " + state);
                }
                refreshResult.errors().forEach(error -> sendLine(sender, "eCloud: " + error));
                cloud.getLastErrors().forEach(error -> sendLine(sender, "eCloud: " + error));
            });
        });
        return true;
    }

    private boolean handleEcloudInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendLine(sender, "Usage: /papi ecloud info <name> [version]");
            return true;
        }

        String name = args[2];
        String version = args.length >= 4 ? args[3] : "";
        runAsync(() -> {
            CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
            CloudExpansionManager.RefreshResult refreshResult = cloud.refreshNowWithin(ecloudCommandTimeoutMillis());
            Optional<CloudExpansion> remote = cloud.findCachedCloudExpansionByName(name);
            Optional<CloudExpansionVersion> selected = version.isBlank() ? Optional.empty() : cloud.findVersion(name, version);
            runSync(() -> {
                if (remote.isEmpty()) {
                    sendLine(sender, "Failed to find an expansion named: " + name);
                    refreshResult.errors().forEach(error -> sendLine(sender, "eCloud: " + error));
                    cloud.getLastErrors().forEach(error -> sendLine(sender, "eCloud: " + error));
                    return;
                }
                sendLine(sender, "Name: " + remote.get().getName());
                sendLine(sender, "Author: " + blank(remote.get().getAuthor()));
                sendLine(sender, "Latest version: " + blank(remote.get().getLatestVersion()));
                sendLine(sender, "Installed: " + remote.get().hasExpansion());
                sendLine(sender, "Update available: " + remote.get().shouldUpdate());
                sendLine(sender, "Platforms: " + String.join(", ", remote.get().getPlatforms()));
                if (!remote.get().getDescription().isBlank()) {
                    sendLine(sender, "Description: " + remote.get().getDescription());
                }
                if (!version.isBlank()) {
                    if (selected.isEmpty()) {
                        sendLine(sender, "Could not find specified version: " + version);
                    } else {
                        sendLine(sender, "Version info: " + selected.get().version() + " (verified=" + selected.get().verified() + ")");
                        if (selected.get().releaseNotes() != null && !selected.get().releaseNotes().isBlank()) {
                            sendLine(sender, "Release notes: " + selected.get().releaseNotes());
                        }
                    }
                }
            });
        });
        return true;
    }

    private boolean handleEcloudPlaceholders(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendLine(sender, "Usage: /papi ecloud placeholders <name>");
            return true;
        }
        runAsync(() -> {
            CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
            CloudExpansionManager.RefreshResult refreshResult = cloud.refreshNowWithin(ecloudCommandTimeoutMillis());
            Optional<CloudExpansion> remote = cloud.findCachedCloudExpansionByName(args[2]);
            runSync(() -> {
                if (remote.isEmpty()) {
                    sendLine(sender, "Failed to find an expansion named: " + args[2]);
                    refreshResult.errors().forEach(error -> sendLine(sender, "eCloud: " + error));
                    cloud.getLastErrors().forEach(error -> sendLine(sender, "eCloud: " + error));
                    return;
                }
                CloudExpansion expansion = remote.get();
                if (expansion.getPlaceholders().isEmpty()) {
                    sendLine(sender, "This expansion does not publish a placeholder list.");
                    return;
                }
                sendLine(sender, "Placeholders for " + expansion.getName() + ":");
                expansion.getPlaceholders().stream().limit(100).forEach(placeholder -> sendLine(sender, placeholder));
            });
        });
        return true;
    }

    private boolean handleEcloudDownload(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendLine(sender, "You must supply the name of an expansion.");
            return true;
        }

        String name = args[2];
        String version = args.length >= 4 ? args[3] : "";
        Path targetDirectory = plugin.getPlaceholderApiExpansionsDirectory();
        runAsync(() -> {
            CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
            CloudExpansionManager.RefreshResult refreshResult = cloud.refreshNowWithin(ecloudCommandTimeoutMillis());
            Optional<CloudExpansion> cloudExpansion = cloud.findCachedCloudExpansionByName(name);
            if (cloudExpansion.isEmpty()) {
                runSync(() -> {
                    sendLine(sender, "Failed to find an expansion named: " + name);
                    refreshResult.errors().forEach(error -> sendLine(sender, "eCloud: " + error));
                });
                return;
            }

            CloudExpansionManager.DownloadResult result = cloud.download(name, version, targetDirectory);
            runSync(() -> {
                if (!result.success()) {
                    sendLine(sender, "Failed to download expansion: " + result.message());
                    refreshResult.errors().forEach(error -> sendLine(sender, "eCloud: " + error));
                    return;
                }

                sendLine(sender, "Successfully downloaded expansion " + cloudExpansion.get().getName() + " to file: " + result.file().getFileName());
                sendLine(sender, "Make sure to type /papi reload to enable your new expansion.");
            });
        });
        return true;
    }

    private boolean handleEcloudUpdate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendLine(sender, "You must define 'all' or the name of an expansion to update.");
            return true;
        }

        runAsync(() -> {
            CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
            CloudExpansionManager.RefreshResult refreshResult = cloud.refreshNowWithin(ecloudCommandTimeoutMillis());
            List<CloudExpansion> targets = new ArrayList<>();
            if ("all".equalsIgnoreCase(args[2])) {
                cloud.getCachedExpansions().stream().filter(CloudExpansion::hasExpansion).filter(CloudExpansion::shouldUpdate).forEach(targets::add);
            } else {
                cloud.findCachedCloudExpansionByName(args[2]).filter(CloudExpansion::shouldUpdate).ifPresent(targets::add);
            }

            if (targets.isEmpty()) {
                runSync(() -> {
                    sendLine(sender, "No updates available for this request.");
                    refreshResult.errors().forEach(error -> sendLine(sender, "eCloud: " + error));
                });
                return;
            }

            Path targetDirectory = plugin.getPlaceholderApiExpansionsDirectory();
            List<String> results = new ArrayList<>();
            for (CloudExpansion expansion : targets) {
                CloudExpansionManager.DownloadResult result = cloud.download(expansion.getName(), "", targetDirectory);
                if (!result.success()) {
                    results.add(expansion.getName() + ": failed - " + result.message());
                    continue;
                }
                List<PlaceholderExpansion> loaded = plugin.getPlaceholderCompatManager().getLocalExpansionManager().loadExpansionJar(result.file());
                if (loaded.isEmpty()) {
                    results.add(expansion.getName() + ": downloaded, but nothing was registered yet.");
                    continue;
                }
                String versions = String.join(", ", loaded.stream().map(loadedExpansion -> loadedExpansion.getIdentifier() + " [" + loadedExpansion.getVersion() + "]").toList());
                results.add(expansion.getName() + ": updated -> " + versions);
            }

            runSync(() -> {
                sendLine(sender, "Update results:");
                results.forEach(line -> sendLine(sender, line));
                refreshResult.errors().forEach(error -> sendLine(sender, "eCloud: " + error));
            });
        });
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sendLine(sender, "&bPlaceholderAPI &8- &fHelp Menu &8- &7(" + PlaceholderAPIPlugin.getEmbeddedPapiVersion() + ")");
        sendLine(sender, "");
        sendLine(sender, "&e/papi bcparse <me|--null|player name> <message>");
        sendLine(sender, "  Parse a message with placeholders and broadcast it");
        sendLine(sender, "&e/papi cmdparse <me|player> <command with placeholders>");
        sendLine(sender, "  Parse a message with relational placeholders");
        sendLine(sender, "&e/papi dump");
        sendLine(sender, "  Dump all relevant information needed to help debug issues into a paste link.");
        sendLine(sender, "&e/papi info <placeholder name>");
        sendLine(sender, "  View information for a specific expansion");
        sendLine(sender, "&e/papi list");
        sendLine(sender, "  List active expansions");
        sendLine(sender, "&e/papi parse <me|--null|player name> <message>");
        sendLine(sender, "  Parse a message with placeholders");
        sendLine(sender, "&e/papi parserel <player one> <player two> <message>");
        sendLine(sender, "  Parse a message with relational placeholders");
        sendLine(sender, "&e/papi register <file name>");
        sendLine(sender, "  Register an expansion by the name of the file");
        sendLine(sender, "&e/papi reload");
        sendLine(sender, "  Reload the config of PAPI");
        sendLine(sender, "&e/papi unregister <expansion name>");
        sendLine(sender, "  Unregister an expansion by name");
        sendLine(sender, "&e/papi version");
        sendLine(sender, "  View plugin info/version");
    }

    private void sendEcloudHelp(CommandSender sender) {
        sendLine(sender, "&e/papi ecloud status");
        sendLine(sender, "&e/papi ecloud refresh");
        sendLine(sender, "&e/papi ecloud clear");
        sendLine(sender, "&e/papi ecloud enable");
        sendLine(sender, "&e/papi ecloud disable");
        sendLine(sender, "&e/papi ecloud list <all|installed|search>");
        sendLine(sender, "&e/papi ecloud info <name> [version]");
        sendLine(sender, "&e/papi ecloud placeholders <name>");
        sendLine(sender, "&e/papi ecloud download <name> [version]");
        sendLine(sender, "&e/papi ecloud update <all|name>");
    }

    private @Nullable OfflinePlayer resolveOfflinePlayer(CommandSender sender, String token) {
        if ("me".equalsIgnoreCase(token)) {
            if (sender instanceof Player player) {
                return player;
            }
            return null;
        }
        Player online = Bukkit.getPlayerExact(token);
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(token);
        return offline.hasPlayedBefore() ? offline : null;
    }

    private @Nullable Player resolveOnlinePlayer(CommandSender sender, String token) {
        if ("me".equalsIgnoreCase(token)) {
            return sender instanceof Player player ? player : null;
        }
        return Bukkit.getPlayerExact(token);
    }

    private Optional<CloudExpansion> resolveCachedCloudExpansion(CloudExpansionManager cloud, PlaceholderExpansion expansion) {
        Optional<CloudExpansion> direct = cloud.findCachedCloudExpansionByName(expansion.getName());
        if (direct.isPresent()) {
            return direct;
        }
        return cloud.findCachedCloudExpansionByName(expansion.getIdentifier());
    }

    private Path resolveJarPath(String value) {
        Path candidate = Path.of(value);
        if (candidate.isAbsolute()) {
            return candidate.normalize();
        }
        Path dataFolderPath = plugin.getPlaceholderApiExpansionsDirectory().resolve(value).normalize();
        if (Files.exists(dataFolderPath)) {
            return dataFolderPath;
        }
        return plugin.getPlaceholderApiDirectory().resolve(value).normalize();
    }

    private List<String> installedIdentifiers() {
        return plugin.getPlaceholderCompatManager().getLocalExpansionManager().getIdentifiers().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private List<String> parsePlayerSuggestions(CommandSender sender) {
        List<String> suggestions = new ArrayList<>();
        if (sender instanceof Player player) {
            suggestions.add("me");
            suggestions.add(player.getName());
        }
        suggestions.add("--null");
        Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> suggestions.stream().noneMatch(existing -> existing.equalsIgnoreCase(name)))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(suggestions::add);
        return suggestions;
    }

    private List<String> onlinePlayerSuggestions(CommandSender sender) {
        List<String> suggestions = new ArrayList<>();
        if (sender instanceof Player player) {
            suggestions.add("me");
            suggestions.add(player.getName());
        }
        Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> suggestions.stream().noneMatch(existing -> existing.equalsIgnoreCase(name)))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(suggestions::add);
        return suggestions;
    }

    private List<String> completeParsePlaceholder(String token) {
        if (!token.startsWith("%") || token.endsWith("%")) {
            return List.of();
        }
        int index = token.indexOf('_');
        if (index == -1) {
            return List.of();
        }

        String identifier = token.substring(1, index);
        PlaceholderExpansion expansion = plugin.getPlaceholderCompatManager().getLocalExpansionManager()
                .findExpansionByIdentifier(identifier)
                .orElse(null);
        if (expansion == null) {
            return List.of();
        }

        LinkedHashSet<String> possible = new LinkedHashSet<>(expansion.getPlaceholders());
        plugin.getPlaceholderCompatManager().getCloudExpansionManager()
                .findCloudExpansionByName(expansion.getName())
                .ifPresent(cloud -> possible.addAll(cloud.getPlaceholders()));
        return filter(token, new ArrayList<>(possible));
    }

    private List<String> filterCloudExpansionNames(String input) {
        CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
        List<String> names = cloud.getCachedExpansionNames();
        if (names.isEmpty()) {
            cloud.warmupAsync();
        }
        return filter(input, names);
    }

    private List<String> filterCloudVersions(String expansionName, String input) {
        CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
        List<String> versions = cloud.getCachedVersionNames(expansionName);
        if (versions.isEmpty()) {
            plugin.executeAsync(() -> cloud.getVersions(expansionName));
        }
        return filter(input, versions);
    }

    private List<String> updatableExpansionSuggestions() {
        CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
        cloud.warmupAsync();
        List<String> names = new ArrayList<>();
        for (String name : cloud.getCachedExpansionNames()) {
            cloud.findCachedCloudExpansionByName(name)
                    .filter(CloudExpansion::hasExpansion)
                    .filter(CloudExpansion::shouldUpdate)
                    .map(CloudExpansion::getName)
                    .ifPresent(names::add);
        }
        names = names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (names.isEmpty()) {
            return List.of("all");
        }
        List<String> suggestions = new ArrayList<>();
        suggestions.add("all");
        suggestions.addAll(names);
        return suggestions;
    }

    private List<String> pageSuggestions(String mode, String query, boolean searchMode) {
        CloudExpansionManager cloud = plugin.getPlaceholderCompatManager().getCloudExpansionManager();
        List<CloudExpansion> expansions = new ArrayList<>();
        for (String name : cloud.getCachedExpansionNames()) {
            cloud.findCachedCloudExpansionByName(name).ifPresent(expansions::add);
        }
        if (expansions.isEmpty()) {
            cloud.warmupAsync();
            return List.of("1");
        }
        if ("installed".equalsIgnoreCase(mode)) {
            expansions.removeIf(expansion -> !expansion.hasExpansion());
        } else if (searchMode) {
            String lowered = query.toLowerCase(Locale.ROOT);
            expansions.removeIf(expansion -> !(expansion.getName().toLowerCase(Locale.ROOT).contains(lowered)
                    || expansion.getAuthor().toLowerCase(Locale.ROOT).contains(lowered)));
        }
        int pageCount = Math.max(1, (int) Math.ceil((double) expansions.size() / ECLOUD_PAGE_SIZE));
        List<String> suggestions = new ArrayList<>();
        for (int page = 1; page <= pageCount; page++) {
            suggestions.add(String.valueOf(page));
        }
        return suggestions;
    }

    private @Nullable ParsedPlaceholderTarget parseTarget(CommandSender sender, String[] args, int targetIndex, String usage) {
        if (args.length <= targetIndex) {
            sendLine(sender, "You must provide a target and message: " + usage);
            return null;
        }

        String token = args[targetIndex];
        if ("--null".equalsIgnoreCase(token)) {
            if (args.length <= targetIndex + 1) {
                sendLine(sender, "You must provide a target and message: " + usage);
                return null;
            }
            return new ParsedPlaceholderTarget(null, true, targetIndex + 1);
        }

        if (args.length <= targetIndex + 1) {
            sendLine(sender, "You must provide a target and message: " + usage);
            return null;
        }
        if ("me".equalsIgnoreCase(token) && !(sender instanceof Player)) {
            sendLine(sender, "You must be a player to use me as a target!");
            return null;
        }
        return new ParsedPlaceholderTarget(resolveOfflinePlayer(sender, token), false, targetIndex + 1);
    }

    private EcloudListRequest parseEcloudListRequest(String[] args) {
        if (args.length < 3) {
            return new EcloudListRequest("all", "", 1);
        }

        String mode = args[2].toLowerCase(Locale.ROOT);
        if ("all".equals(mode) || "installed".equals(mode)) {
            int page = args.length >= 4 ? parseIntegerOrDefault(args[3], 1) : 1;
            return new EcloudListRequest(mode, "", page);
        }
        if ("search".equals(mode)) {
            int page = 1;
            String query = "";
            if (args.length >= 4) {
                if (isInteger(args[args.length - 1])) {
                    page = parseIntegerOrDefault(args[args.length - 1], 1);
                    query = join(args, 3, args.length - 1);
                } else {
                    query = join(args, 3);
                }
            }
            return new EcloudListRequest("search", query, page);
        }

        int page = 1;
        String query;
        if (isInteger(args[args.length - 1])) {
            page = parseIntegerOrDefault(args[args.length - 1], 1);
            query = join(args, 2, args.length - 1);
        } else {
            query = join(args, 2);
        }
        return new EcloudListRequest("author", query, page);
    }

    private boolean isInteger(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            if (!Character.isDigit(input.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private int parseIntegerOrDefault(String input, int fallback) {
        if (!isInteger(input)) {
            return fallback;
        }
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? "n/a" : value;
    }

    private String join(String[] args, int startIndex) {
        return join(args, startIndex, args.length);
    }

    private String join(String[] args, int startIndex, int endExclusive) {
        StringBuilder builder = new StringBuilder();
        for (int index = startIndex; index < endExclusive; index++) {
            if (index > startIndex) {
                builder.append(' ');
            }
            builder.append(args[index]);
        }
        return builder.toString();
    }

    private void runAsync(Runnable runnable) {
        plugin.executeAsync(runnable);
    }

    private void runSync(Runnable runnable) {
        plugin.runSyncIfEnabled(runnable);
    }

    private long ecloudCommandTimeoutMillis() {
        return Math.max(1000L, plugin.getConfig().getLong("placeholderapi.ecloud.command-timeout-millis", 15000L));
    }

    private List<String> filter(String input, List<String> values) {
        String lowered = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                matches.add(value);
            }
        }
        return matches.stream().filter(Objects::nonNull).distinct().toList();
    }

    private void sendLine(CommandSender sender, String message) {
        sender.sendMessage(LEGACY.deserialize(message == null ? "" : "&7" + message));
    }

    private void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(message == null ? "" : message);
    }

    private void sendPapi(CommandSender sender, String message) {
        sender.sendMessage(LEGACY.deserialize("&b[PlaceholderAPI]&7 " + (message == null ? "" : message)));
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission("escore.admin") || sender.hasPermission("wscore.admin");
    }

    private record ParsedPlaceholderTarget(@Nullable OfflinePlayer player, boolean nullTarget, int textStartIndex) {
    }

    private record EcloudListRequest(String mode, String query, int page) {
    }
}
