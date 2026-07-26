package com.powersmp.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * MiniMessage helpers. Only ever called with literal format strings from this plugin -- player
 * input is passed through {@link #plain(String)} so stray tags cannot be injected.
 */
public final class Text {

    public static final String PREFIX = "<gradient:#b06cff:#6cc9ff><bold>PowerSMP</bold></gradient> <dark_gray>»</dark_gray> ";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component mm(String message) {
        return MM.deserialize(message);
    }

    /** Escapes MiniMessage tags in untrusted text (player names, item names, command args). */
    public static String plain(String raw) {
        return raw == null ? "" : MM.escapeTags(raw);
    }

    public static void msg(CommandSender to, String message) {
        to.sendMessage(mm(PREFIX + message));
    }

    /** Sends without the prefix -- for multi-line blocks where the prefix would be noise. */
    public static void raw(CommandSender to, String message) {
        to.sendMessage(mm(message));
    }

    public static void actionBar(Player to, String message) {
        to.sendActionBar(mm(message));
    }

    /** "1m 05s" / "12.4s" -- compact enough for an action bar. */
    public static String duration(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        long totalSeconds = millis / 1000L;
        if (totalSeconds >= 60) {
            return String.format("%dm %02ds", totalSeconds / 60, totalSeconds % 60);
        }
        return String.format("%.1fs", millis / 1000.0d);
    }

    /** "the_world" -> "The World" */
    public static String prettify(String id) {
        StringBuilder out = new StringBuilder(id.length());
        boolean upper = true;
        for (char c : id.toCharArray()) {
            if (c == '_' || c == '-') {
                out.append(' ');
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
