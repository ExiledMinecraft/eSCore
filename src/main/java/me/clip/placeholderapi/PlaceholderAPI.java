package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import me.clip.placeholderapi.replacer.CharsReplacer;
import me.clip.placeholderapi.replacer.Replacer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaceholderAPI {
    private static final Replacer REPLACER_PERCENT = new CharsReplacer(Replacer.Closure.PERCENT);
    private static final Replacer REPLACER_BRACKET = new CharsReplacer(Replacer.Closure.BRACKET);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("[%]([^%]+)[%]");
    private static final Pattern BRACKET_PLACEHOLDER_PATTERN = Pattern.compile("[{]([^{}]+)[}]");
    private static final Pattern RELATIONAL_PLACEHOLDER_PATTERN = Pattern.compile("[%](rel_)([^%]+)[%]");
    private static final Map<String, PlaceholderHook> LEGACY_HOOKS = new ConcurrentHashMap<>();

    private PlaceholderAPI() {
    }

    public static String setPlaceholders(OfflinePlayer player, String text) {
        PlaceholderAPIPlugin plugin = PlaceholderAPIPlugin.getInstance();
        return plugin == null ? text : applyPasses(REPLACER_PERCENT, player, text, plugin);
    }

    public static List<String> setPlaceholders(OfflinePlayer player, List<String> text) {
        List<String> result = new ArrayList<>(text.size());
        for (String line : text) {
            result.add(setPlaceholders(player, line));
        }
        return result;
    }

    public static String setPlaceholders(Player player, String text) {
        return setPlaceholders((OfflinePlayer) player, text);
    }

    public static List<String> setPlaceholders(Player player, List<String> text) {
        return setPlaceholders((OfflinePlayer) player, text);
    }

    public static String setBracketPlaceholders(OfflinePlayer player, String text) {
        PlaceholderAPIPlugin plugin = PlaceholderAPIPlugin.getInstance();
        return plugin == null ? text : applyPasses(REPLACER_BRACKET, player, text, plugin);
    }

    public static List<String> setBracketPlaceholders(OfflinePlayer player, List<String> text) {
        List<String> result = new ArrayList<>(text.size());
        for (String line : text) {
            result.add(setBracketPlaceholders(player, line));
        }
        return result;
    }

    public static String setBracketPlaceholders(Player player, String text) {
        return setBracketPlaceholders((OfflinePlayer) player, text);
    }

    public static List<String> setBracketPlaceholders(Player player, List<String> text) {
        return setBracketPlaceholders((OfflinePlayer) player, text);
    }

    public static String setRelationalPlaceholders(Player one, Player two, String text) {
        PlaceholderAPIPlugin plugin = PlaceholderAPIPlugin.getInstance();
        if (plugin == null || text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = RELATIONAL_PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String format = matcher.group(2);
            int index = format.indexOf('_');
            if (index <= 0) {
                continue;
            }

            String identifier = format.substring(0, index).toLowerCase(Locale.ROOT);
            String params = format.substring(index + 1);
            PlaceholderExpansion expansion = plugin.getLocalExpansionManager()
                    .getExpansion(identifier);
            if (!(expansion instanceof Relational relational)) {
                continue;
            }

            String value = relational.onPlaceholderRequest(one, two, params);
            if (value != null) {
                text = text.replaceAll(Pattern.quote(matcher.group()), Matcher.quoteReplacement(value));
            }
        }
        return text;
    }

    public static List<String> setRelationalPlaceholders(Player one, Player two, List<String> text) {
        List<String> result = new ArrayList<>(text.size());
        for (String line : text) {
            result.add(setRelationalPlaceholders(one, two, line));
        }
        return result;
    }

    public static boolean isRegistered(String identifier) {
        PlaceholderAPIPlugin plugin = PlaceholderAPIPlugin.getInstance();
        if (plugin == null || identifier == null) {
            return false;
        }
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        return plugin.getLocalExpansionManager().findExpansionByIdentifier(normalized).isPresent()
                || LEGACY_HOOKS.containsKey(normalized);
    }

    public static Set<String> getRegisteredIdentifiers() {
        PlaceholderAPIPlugin plugin = PlaceholderAPIPlugin.getInstance();
        if (plugin == null) {
            return Set.of();
        }
        Set<String> identifiers = new java.util.LinkedHashSet<>(plugin.getLocalExpansionManager().getIdentifiers());
        identifiers.addAll(LEGACY_HOOKS.keySet());
        return Set.copyOf(identifiers);
    }

    public static Pattern getPlaceholderPattern() {
        return PLACEHOLDER_PATTERN;
    }

    public static Pattern getBracketPlaceholderPattern() {
        return BRACKET_PLACEHOLDER_PATTERN;
    }

    public static Pattern getRelationalPlaceholderPattern() {
        return RELATIONAL_PLACEHOLDER_PATTERN;
    }

    public static boolean containsPlaceholders(String text) {
        if (text == null) {
            return false;
        }
        int firstPercent = text.indexOf('%');
        return firstPercent >= 0 && text.indexOf('%', firstPercent + 1) != -1;
    }

    public static boolean containsBracketPlaceholders(String text) {
        if (text == null) {
            return false;
        }
        int firstOpen = text.indexOf('{');
        return firstOpen >= 0 && text.indexOf('}', firstOpen + 1) != -1;
    }

    @Deprecated
    public static boolean registerExpansion(me.clip.placeholderapi.expansion.PlaceholderExpansion expansion) {
        return expansion.register();
    }

    @Deprecated
    public static boolean unregisterExpansion(me.clip.placeholderapi.expansion.PlaceholderExpansion expansion) {
        return expansion.unregister();
    }

    @Deprecated
    public static Map<String, PlaceholderHook> getPlaceholders() {
        PlaceholderAPIPlugin plugin = PlaceholderAPIPlugin.getInstance();
        if (plugin == null) {
            return Map.of();
        }
        Map<String, PlaceholderHook> hooks = new LinkedHashMap<>(LEGACY_HOOKS);
        plugin.getLocalExpansionManager().getExpansions()
                .forEach(expansion -> hooks.put(expansion.getIdentifier().toLowerCase(Locale.ROOT), expansion));
        return Map.copyOf(hooks);
    }

    @Deprecated
    public static boolean registerPlaceholderHook(org.bukkit.plugin.Plugin plugin, PlaceholderHook placeholderHook) {
        return plugin != null && registerPlaceholderHook(plugin.getName(), placeholderHook);
    }

    @Deprecated
    public static boolean registerPlaceholderHook(String identifier, PlaceholderHook placeholderHook) {
        if (identifier == null || identifier.isBlank() || placeholderHook == null) {
            return false;
        }
        return LEGACY_HOOKS.putIfAbsent(identifier.trim().toLowerCase(Locale.ROOT), placeholderHook) == null;
    }

    @Deprecated
    public static boolean unregisterPlaceholderHook(org.bukkit.plugin.Plugin plugin) {
        return plugin != null && unregisterPlaceholderHook(plugin.getName());
    }

    @Deprecated
    public static boolean unregisterPlaceholderHook(String identifier) {
        return identifier != null && LEGACY_HOOKS.remove(identifier.trim().toLowerCase(Locale.ROOT)) != null;
    }

    @Deprecated
    public static Set<String> getRegisteredPlaceholderPlugins() {
        return getRegisteredIdentifiers();
    }

    @Deprecated
    public static Set<String> getExternalPlaceholderPlugins() {
        return Set.copyOf(LEGACY_HOOKS.keySet());
    }

    @Deprecated
    public static String setPlaceholders(OfflinePlayer player, String text, Pattern pattern, boolean colorize) {
        return setPlaceholders(player, text);
    }

    @Deprecated
    public static List<String> setPlaceholders(OfflinePlayer player, List<String> text, Pattern pattern, boolean colorize) {
        return setPlaceholders(player, text);
    }

    @Deprecated
    public static List<String> setPlaceholders(OfflinePlayer player, List<String> text, boolean colorize) {
        return setPlaceholders(player, text);
    }

    @Deprecated
    public static List<String> setPlaceholders(OfflinePlayer player, List<String> text, Pattern pattern) {
        return setPlaceholders(player, text);
    }

    static void clearLegacyHooks() {
        LEGACY_HOOKS.clear();
    }

    private static PlaceholderHook findHook(String identifier) {
        PlaceholderAPIPlugin plugin = PlaceholderAPIPlugin.getInstance();
        if (plugin == null) {
            return null;
        }
        PlaceholderExpansion expansion = plugin.getLocalExpansionManager().getExpansion(identifier);
        return expansion == null ? LEGACY_HOOKS.get(identifier) : expansion;
    }

    private static String applyPasses(Replacer replacer, OfflinePlayer player, String text, PlaceholderAPIPlugin plugin) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int maxPasses = Math.max(1, Math.min(16, plugin.getConfig().getInt("placeholderapi.parser.max-passes", 2)));
        String current = text;
        Set<String> seen = new java.util.HashSet<>();
        for (int pass = 0; pass < maxPasses && seen.add(current); pass++) {
            String replaced = replacer.apply(current, player, PlaceholderAPI::findHook);
            if (replaced.equals(current)) {
                break;
            }
            current = replaced;
        }
        return current;
    }
}
