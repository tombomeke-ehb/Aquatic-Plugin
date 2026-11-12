package dev.aquatic.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/** MiniMessage helpers shared by all modules. */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    /**
     * Parses a MiniMessage string. Placeholders are name/value pairs: mm("Hello <player>", "player", name).
     * A value that is a Component is inserted as-is, anything else is inserted as plain (unparsed) text.
     */
    public static Component mm(String miniMessage, Object... placeholders) {
        if (miniMessage == null) {
            return Component.empty();
        }
        if (placeholders.length == 0) {
            return MM.deserialize(miniMessage);
        }
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String key = String.valueOf(placeholders[i]).toLowerCase();
            Object value = placeholders[i + 1];
            if (value instanceof Component c) {
                resolvers.add(Placeholder.component(key, c));
            } else {
                resolvers.add(Placeholder.unparsed(key, String.valueOf(value)));
            }
        }
        return MM.deserialize(miniMessage, TagResolver.resolver(resolvers));
    }

    /** For item names/lore: parsed MiniMessage without the default italic style. */
    public static Component item(String miniMessage, Object... placeholders) {
        return mm(miniMessage, placeholders).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> itemLore(List<String> lines, Object... placeholders) {
        List<Component> out = new ArrayList<>();
        for (String line : lines) {
            out.add(item(line, placeholders));
        }
        return out;
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Legacy section-sign string, for APIs that still need it (scoreboards, Vault, ...). */
    public static String legacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    /** 1234567.5 -> "1,234,567.5" ; whole numbers are printed without decimals. */
    public static String number(double value) {
        if (value == Math.rint(value)) {
            return String.format("%,d", (long) value);
        }
        return String.format("%,.2f", value);
    }

    /** 3725 -> "1h 2m 5s" */
    public static String duration(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
