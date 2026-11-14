package dev.aquatic.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlaceholderAPI placeholders (%luckperms_prefix%, %luckperms_meta_displayname%, %player_ping%, ...) in our own
 * MiniMessage lines: the sidebar, the tab list and the chat format. Placeholders are filled in first, then any
 * legacy colour codes they return (&amp;c, &amp;#ff8800, section signs, &amp;x&amp;r&amp;r...) become MiniMessage tags, so
 * both can be mixed in one line. Without PlaceholderAPI the line is left as it is.
 */
public final class Placeholders {

    private Placeholders() {
    }

    private static boolean papi() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /** Only loaded when PlaceholderAPI is installed. */
    private static final class Hook {
        static String apply(Player p, String text) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, text);
        }
    }

    /** Fills in %placeholders% for this player and turns legacy colour codes into MiniMessage. */
    public static String apply(Player p, String line) {
        if (line == null || line.indexOf('%') < 0 || !papi()) {
            return line;
        }
        String out;
        try {
            out = Hook.apply(p, line);
        } catch (Throwable t) {
            return line;
        }
        return legacyToMiniMessage(out);
    }

    private static final Map<Character, String> CODES = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"), Map.entry('2', "<dark_green>"), Map.entry('3', "<dark_aqua>"),
            Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"), Map.entry('6', "<gold>"), Map.entry('7', "<gray>"),
            Map.entry('8', "<dark_gray>"), Map.entry('9', "<blue>"), Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"), Map.entry('e', "<yellow>"), Map.entry('f', "<white>"),
            Map.entry('k', "<obfuscated>"), Map.entry('l', "<bold>"), Map.entry('m', "<strikethrough>"), Map.entry('n', "<underlined>"),
            Map.entry('o', "<italic>"), Map.entry('r', "<reset>"));

    private static final Pattern SPIGOT_HEX = Pattern.compile("[&§]x((?:[&§][0-9a-fA-F]){6})");
    private static final Pattern HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern CODE = Pattern.compile("[&§]([0-9a-fA-Fk-oK-OrR])");

    /** "&amp;cRed &amp;#ff8800Orange §lBold" -> "&lt;red&gt;Red &lt;#ff8800&gt;Orange &lt;bold&gt;Bold". */
    public static String legacyToMiniMessage(String s) {
        if (s == null || (s.indexOf('&') < 0 && s.indexOf('§') < 0)) {
            return s;
        }
        Matcher m = SPIGOT_HEX.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement("<#" + m.group(1).replaceAll("[&§]", "") + ">"));
        }
        m.appendTail(b);
        s = HEX.matcher(b.toString()).replaceAll("<#$1>");
        m = CODE.matcher(s);
        b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(b, Matcher.quoteReplacement(CODES.get(Character.toLowerCase(m.group(1).charAt(0)))));
        }
        m.appendTail(b);
        return b.toString();
    }
}
