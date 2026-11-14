package dev.aquatic.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Ranks and names from the permissions plugin (LuckPerms, or any other that hooks into Vault's chat API), for the
 * chat, the tab list and the sidebar:
 * <ul>
 *   <li>prefix / suffix: the rank prefix and suffix (LuckPerms: /lp user|group ... meta setprefix / setsuffix)</li>
 *   <li>name: the display name. LuckPerms meta "displayname" (or "nickname") when it is set, else a nickname set by
 *       another plugin (EssentialsX /nick), else the plain name.</li>
 * </ul>
 * Prefixes, suffixes and names may use legacy colour codes (&amp;c, &amp;#ff8800, section signs) or MiniMessage.
 * Without Vault everything falls back to the plain name and nothing else.
 */
public final class Ranks {

    private static Object chat;
    private static long lastLookup;

    private Ranks() {
    }

    private static Object chat() {
        if (chat != null || Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return chat;
        }
        long now = System.currentTimeMillis();
        if (now - lastLookup < 10_000) {
            return null; // the permissions plugin may register its Vault hook after us: look again later
        }
        lastLookup = now;
        try {
            chat = Lookup.provider();
        } catch (Throwable t) {
            chat = null;
        }
        return chat;
    }

    /** Only loaded when Vault is installed, so the plugin also works without it. */
    private static final class Lookup {
        static Object provider() {
            org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.chat.Chat> rsp =
                    Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.chat.Chat.class);
            return rsp == null ? null : rsp.getProvider();
        }

        static String prefix(Object chat, Player p) {
            return ((net.milkbowl.vault.chat.Chat) chat).getPlayerPrefix(p);
        }

        static String suffix(Object chat, Player p) {
            return ((net.milkbowl.vault.chat.Chat) chat).getPlayerSuffix(p);
        }

        static String meta(Object chat, Player p, String key) {
            return ((net.milkbowl.vault.chat.Chat) chat).getPlayerInfoString(p, key, null);
        }
    }

    /** The rank prefix with a space after it, or empty. */
    public static Component prefix(Player p) {
        Object c = chat();
        if (c == null) {
            return Component.empty();
        }
        try {
            Component out = parse(Lookup.prefix(c, p));
            String plain = plain(out);
            return plain.isEmpty() || plain.endsWith(" ") ? out : out.append(Component.space());
        } catch (Throwable t) {
            return Component.empty();
        }
    }

    /** The suffix (e.g. a logo) with a space in front of it, or empty. */
    public static Component suffix(Player p) {
        Object c = chat();
        if (c == null) {
            return Component.empty();
        }
        try {
            Component out = parse(Lookup.suffix(c, p));
            String plain = plain(out);
            return plain.isEmpty() || plain.startsWith(" ") ? out : Component.space().append(out);
        } catch (Throwable t) {
            return Component.empty();
        }
    }

    /** The name to show: the LuckPerms display name, a nickname, or the plain name. */
    public static Component name(Player p) {
        Object c = chat();
        if (c != null) {
            try {
                for (String key : new String[]{"displayname", "display-name", "nickname"}) {
                    String raw = Lookup.meta(c, p, key);
                    if (raw != null && !raw.isBlank()) {
                        return parse(raw);
                    }
                }
            } catch (Throwable ignored) {
                // a permissions plugin without meta support
            }
        }
        Component shown = p.displayName();
        return plain(shown).equals(p.getName()) || plain(shown).isBlank() ? Component.text(p.getName()) : shown;
    }

    /** The plain text of the shown name (for sorting, logs, commands). */
    public static String plainName(Player p) {
        return plain(name(p));
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    /** Legacy colour codes or MiniMessage to a component. */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.contains("<") && raw.contains(">") && !raw.contains("&") && !raw.contains("§")) {
            return MiniMessage.miniMessage().deserialize(raw);
        }
        return LEGACY.deserialize(raw.replace('§', '&'));
    }
}
