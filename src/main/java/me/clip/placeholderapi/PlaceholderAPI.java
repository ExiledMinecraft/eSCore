package me.clip.placeholderapi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import me.clip.placeholderapi.replacer.CharsReplacer;
import me.clip.placeholderapi.replacer.Replacer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class PlaceholderAPI {
    private static final Replacer REPLACER_PERCENT = new CharsReplacer(Replacer.Closure.PERCENT);
    private static final Replacer REPLACER_BRACKET = new CharsReplacer(Replacer.Closure.BRACKET);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("[%]([^%]+)[%]");
    private static final Pattern BRACKET_PLACEHOLDER_PATTERN = Pattern.compile("[{]([^{}]+)[}]");
    private static final Pattern RELATIONAL_PLACEHOLDER_PATTERN = Pattern.compile("[%](rel_)([^%]+)[%]");

    private PlaceholderAPI() {
    }

    public static String setPlaceholders(OfflinePlayer player, String text) {
        PlaceholderAPIPlugin plugin = PlaceholderAPIPlugin.getInstance();
        return plugin == null ? text : REPLACER_PERCENT.apply(text, player, plugin.getLocalExpansionManager()::getExpansion);
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
        return plugin == null ? text : REPLACER_BRACKET.apply(text, player, plugin.getLocalExpansionManager()::getExpansion);
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
        Matcher matcher = RELATIONAL_PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String format = matcher.group(2);
            int index = format.indexOf('_');
            if (index <= 0) {
                continue;
            }

            String identifier = format.substring(0, index).toLowerCase(Locale.ROOT);
            String params = format.substring(index + 1);
            PlaceholderExpansion expansion = PlaceholderAPIPlugin.getInstance()
                    .getLocalExpansionManager()
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
        return plugin != null && plugin.getLocalExpansionManager().findExpansionByIdentifier(identifier).isPresent();
    }

    public static Set<String> getRegisteredIdentifiers() {
        PlaceholderAPIPlugin plugin = PlaceholderAPIPlugin.getInstance();
        return plugin == null ? Set.of() : plugin.getLocalExpansionManager().getIdentifiers();
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
        return plugin.getLocalExpansionManager().getExpansions().stream()
                .collect(Collectors.toMap(me.clip.placeholderapi.expansion.PlaceholderExpansion::getIdentifier, expansion -> expansion));
    }

    @Deprecated
    public static boolean registerPlaceholderHook(org.bukkit.plugin.Plugin plugin, PlaceholderHook placeholderHook) {
        return false;
    }

    @Deprecated
    public static boolean registerPlaceholderHook(String identifier, PlaceholderHook placeholderHook) {
        return false;
    }

    @Deprecated
    public static boolean unregisterPlaceholderHook(org.bukkit.plugin.Plugin plugin) {
        return false;
    }

    @Deprecated
    public static boolean unregisterPlaceholderHook(String identifier) {
        return false;
    }

    @Deprecated
    public static Set<String> getRegisteredPlaceholderPlugins() {
        return getRegisteredIdentifiers();
    }

    @Deprecated
    public static Set<String> getExternalPlaceholderPlugins() {
        return Set.of();
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
}
