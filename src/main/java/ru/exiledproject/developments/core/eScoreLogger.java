package ru.exiledproject.developments.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class eScoreLogger {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final ComponentLogger logger;
    private final JavaPlugin plugin;

    public eScoreLogger(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getComponentLogger();
    }

    /** Neutral informational line. */
    public void info(String message) {
        logger.info(line("?", NamedTextColor.DARK_AQUA, message, NamedTextColor.GRAY));
    }

    /** Successful operation. */
    public void success(String message) {
        logger.info(line("?", NamedTextColor.GREEN, message, NamedTextColor.GRAY));
    }

    /** Recoverable problem that still deserves attention. */
    public void warn(String message) {
        logger.warn(line("?", NamedTextColor.YELLOW, message, NamedTextColor.YELLOW));
    }

    /** Error condition. */
    public void error(String message) {
        logger.error(line("?", NamedTextColor.RED, message, NamedTextColor.RED));
    }

    /** Debug output, shown only when debug: true is enabled. */
    public void debug(String message) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            logger.info(line("?", NamedTextColor.DARK_GRAY, message, NamedTextColor.DARK_GRAY));
        }
    }

    /** Sends a formatted command response to a player or console sender. */
    public void send(CommandSender sender, String message) {
        sender.sendMessage(LEGACY.deserialize("&6[eSCore]&7 " + message));
    }

    /** Applies legacy color processing to an internal message. */
    public String color(String input) {
        return LEGACY.serialize(LEGACY.deserialize(input));
    }

    private static Component line(String marker, NamedTextColor markerColor, String message, NamedTextColor messageColor) {
        return Component.text(marker + " ", markerColor)
                .append(Component.text(message, messageColor));
    }
}
