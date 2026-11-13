package dev.aquatic.crews;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Rank prefixes and suffixes from a permissions plugin (LuckPerms, ...) through Vault's chat API, used as
 * &lt;rank&gt; and &lt;suffix&gt; in the chat line and the tab list. Works without Vault (then both are empty).
 * Accepts legacy colour codes (&amp;c, &amp;#ff8800, section signs) and MiniMessage.
 */
final class PermsHook {

    private static Object chat;
    private static long lastLookup;

    private PermsHook() {
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
    }

    /** The rank prefix with a space after it, or empty. */
    static Component prefix(Player p) {
        Object c = chat();
        if (c == null) {
            return Component.empty();
        }
        try {
            Component out = parse(Lookup.prefix(c, p));
            String plain = PlainTextComponentSerializer.plainText().serialize(out);
            return plain.isEmpty() || plain.endsWith(" ") ? out : out.append(Component.space());
        } catch (Throwable t) {
            return Component.empty();
        }
    }

    /** The suffix (e.g. a logo) with a space in front of it, or empty. */
    static Component suffix(Player p) {
        Object c = chat();
        if (c == null) {
            return Component.empty();
        }
        try {
            Component out = parse(Lookup.suffix(c, p));
            String plain = PlainTextComponentSerializer.plainText().serialize(out);
            return plain.isEmpty() || plain.startsWith(" ") ? out : Component.space().append(out);
        } catch (Throwable t) {
            return Component.empty();
        }
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.contains("<") && raw.contains(">") && !raw.contains("&") && !raw.contains("§")) {
            return MiniMessage.miniMessage().deserialize(raw);
        }
        return LEGACY.deserialize(raw.replace('§', '&'));
    }
}
