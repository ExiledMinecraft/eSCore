package me.clip.placeholderapi.expansion;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.PlaceholderHook;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;

public abstract class PlaceholderExpansion extends PlaceholderHook {
    protected Type expansionType = Type.INTERNAL;

    public abstract String getIdentifier();

    public abstract String getAuthor();

    public abstract String getVersion();

    public String getName() {
        return getIdentifier();
    }

    @SuppressWarnings("deprecation")
    public String getRequiredPlugin() {
        return getPlugin();
    }

    public List<String> getPlaceholders() {
        return Collections.emptyList();
    }

    public boolean persist() {
        return false;
    }

    public final boolean isRegistered() {
        return getPlaceholderAPI().getLocalExpansionManager().findExpansionByIdentifier(getIdentifier())
                .map(expansion -> expansion.equals(this))
                .orElse(false);
    }

    public boolean canRegister() {
        String requiredPlugin = getRequiredPlugin();
        if (requiredPlugin == null || requiredPlugin.isBlank()) {
            return true;
        }
        if (requiredPlugin.equalsIgnoreCase("PlaceholderAPI")) {
            return getPlaceholderAPI() != null;
        }
        if (Bukkit.getPluginManager().getPlugin(requiredPlugin) != null) {
            return true;
        }
        for (org.bukkit.plugin.Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getPluginMeta().getProvidedPlugins().stream().anyMatch(provided -> provided.equalsIgnoreCase(requiredPlugin))) {
                return true;
            }
        }
        return false;
    }

    public boolean register() {
        return getPlaceholderAPI().getLocalExpansionManager().register(this);
    }

    public final boolean unregister() {
        return getPlaceholderAPI().getLocalExpansionManager().unregister(this);
    }

    public final PlaceholderAPIPlugin getPlaceholderAPI() {
        return PlaceholderAPIPlugin.getInstance();
    }

    public Type getExpansionType() {
        return expansionType;
    }

    public void setExpansionType(Type expansionType) {
        this.expansionType = expansionType == null ? Type.INTERNAL : expansionType;
    }

    public final ConfigurationSection getConfigSection() {
        PlaceholderAPIPlugin plugin = getPlaceholderAPI();
        return plugin == null ? null : plugin.getConfig().getConfigurationSection("expansions." + getIdentifier().toLowerCase(Locale.ROOT));
    }

    public final ConfigurationSection getConfigSection(String path) {
        ConfigurationSection section = getConfigSection();
        return section == null ? null : section.getConfigurationSection(path);
    }

    public final Object get(String path, Object fallback) {
        ConfigurationSection section = getConfigSection();
        return section == null ? fallback : section.get(path, fallback);
    }

    public final int getInt(String path, int fallback) {
        ConfigurationSection section = getConfigSection();
        return section == null ? fallback : section.getInt(path, fallback);
    }

    public final long getLong(String path, long fallback) {
        ConfigurationSection section = getConfigSection();
        return section == null ? fallback : section.getLong(path, fallback);
    }

    public final double getDouble(String path, double fallback) {
        ConfigurationSection section = getConfigSection();
        return section == null ? fallback : section.getDouble(path, fallback);
    }

    public final String getString(String path, String fallback) {
        ConfigurationSection section = getConfigSection();
        return section == null ? fallback : section.getString(path, fallback);
    }

    public final List<String> getStringList(String path) {
        ConfigurationSection section = getConfigSection();
        return section == null ? Collections.emptyList() : section.getStringList(path);
    }

    public final boolean getBoolean(String path, boolean fallback) {
        ConfigurationSection section = getConfigSection();
        return section == null ? fallback : section.getBoolean(path, fallback);
    }

    public final boolean configurationContains(String path) {
        ConfigurationSection section = getConfigSection();
        return section != null && section.contains(path);
    }

    public void log(Level level, String message) {
        PlaceholderAPIPlugin plugin = getPlaceholderAPI();
        if (plugin != null) {
            plugin.getLogger().log(level, '[' + getName() + "] " + message);
        }
    }

    public void log(Level level, String message, Throwable throwable) {
        PlaceholderAPIPlugin plugin = getPlaceholderAPI();
        if (plugin != null) {
            plugin.getLogger().log(level, '[' + getName() + "] " + message, throwable);
        }
    }

    public void info(String message) {
        log(Level.INFO, message);
    }

    public void warning(String message) {
        log(Level.WARNING, message);
    }

    public void severe(String message) {
        log(Level.SEVERE, message);
    }

    public void severe(String message, Throwable throwable) {
        log(Level.SEVERE, message, throwable);
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PlaceholderExpansion expansion)) {
            return false;
        }
        return getIdentifier().equals(expansion.getIdentifier())
                && getAuthor().equals(expansion.getAuthor())
                && getVersion().equals(expansion.getVersion());
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getIdentifier(), getAuthor(), getVersion());
    }

    @Override
    public final String toString() {
        return "PlaceholderExpansion[name='" + getName() + "', author='" + getAuthor() + "', version='" + getVersion() + "', type='" + getExpansionType() + "']";
    }

    @Deprecated
    public String getPlugin() {
        return null;
    }

    @Deprecated
    public String getDescription() {
        return null;
    }

    @Deprecated
    public String getLink() {
        return null;
    }

    public enum Type {
        INTERNAL,
        EXTERNAL
    }
}
