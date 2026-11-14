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

        static String group(Object chat, Player p) {
            return ((net.milkbowl.vault.chat.Chat) chat).getPrimaryGroup(p);
        }

        static String groupMeta(Object chat, Player p, String group, String key) {
            return ((net.milkbowl.vault.chat.Chat) chat).getGroupInfoString(p.getWorld().getName(), group, key, null);
        }

        static String groupPrefix(Object chat, Player p, String group) {
            return ((net.milkbowl.vault.chat.Chat) chat).getGroupPrefix(p.getWorld().getName(), group);
        }
    }

    // ------------------------------------------------------------------ LuckPerms itself (read through its API, no
    // compile-time dependency): the group display name (/lp group admin setdisplayname ...) is not visible to Vault.

    private static Object luckPerms;

    private static Object lp() {
        if (luckPerms != null) {
            return luckPerms;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return null;
        }
        try {
            Class<?> api = Class.forName("net.luckperms.api.LuckPerms", true, Bukkit.getPluginManager().getPlugin("LuckPerms").getClass().getClassLoader());
            org.bukkit.plugin.RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(api);
            luckPerms = rsp == null ? null : rsp.getProvider();
        } catch (Throwable t) {
            luckPerms = null;
        }
        return luckPerms;
    }

    private static Object call(Object target, String iface, String method, Class<?>[] types, Object... args) throws Exception {
        Class<?> type = Class.forName(iface, true, target.getClass().getClassLoader());
        return type.getMethod(method, types).invoke(target, args);
    }

    /** LuckPerms: the user's primary group, or null. */
    private static String lpGroup(Player p) {
        Object lp = lp();
        if (lp == null) {
            return null;
        }
        try {
            Object users = call(lp, "net.luckperms.api.LuckPerms", "getUserManager", new Class<?>[0]);
            Object user = call(users, "net.luckperms.api.model.user.UserManager", "getUser", new Class<?>[]{java.util.UUID.class}, p.getUniqueId());
            return user == null ? null : (String) call(user, "net.luckperms.api.model.user.User", "getPrimaryGroup", new Class<?>[0]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** LuckPerms: a group's display name (/lp group <g> setdisplayname ...), or null. */
    private static String lpDisplayName(String group) {
        Object lp = lp();
        if (lp == null || group == null) {
            return null;
        }
        try {
            Object groups = call(lp, "net.luckperms.api.LuckPerms", "getGroupManager", new Class<?>[0]);
            Object g = call(groups, "net.luckperms.api.model.group.GroupManager", "getGroup", new Class<?>[]{String.class}, group);
            return g == null ? null : (String) call(g, "net.luckperms.api.model.group.Group", "getDisplayName", new Class<?>[0]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** LuckPerms: a meta value of the user (own or inherited from groups), or null. */
    private static String lpMeta(Player p, String key) {
        Object lp = lp();
        if (lp == null) {
            return null;
        }
        try {
            Object users = call(lp, "net.luckperms.api.LuckPerms", "getUserManager", new Class<?>[0]);
            Object user = call(users, "net.luckperms.api.model.user.UserManager", "getUser", new Class<?>[]{java.util.UUID.class}, p.getUniqueId());
            if (user == null) {
                return null;
            }
            Object data = call(user, "net.luckperms.api.model.PermissionHolder", "getCachedData", new Class<?>[0]);
            Object meta = call(data, "net.luckperms.api.cacheddata.CachedDataManager", "getMetaData", new Class<?>[0]);
            return (String) call(meta, "net.luckperms.api.cacheddata.CachedMetaData", "getMetaValue", new Class<?>[]{String.class}, key);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The colour of the player's name: LuckPerms meta "name-color" (or namecolor / name_color / username-color), else a
     * group display name that is only a colour code (/lp group admin setdisplayname &amp;e), else none.
     */
    public static String nameColour(Player p) {
        for (String key : new String[]{"name-color", "namecolor", "name_color", "username-color", "name-colour"}) {
            String v = lpMeta(p, key);
            if (v == null) {
                Object c = chat();
                try {
                    v = c == null ? null : Lookup.meta(c, p, key);
                } catch (Throwable ignored) {
                    v = null;
                }
            }
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        String shown = lpDisplayName(groupId(p));
        if (shown != null && !shown.isBlank() && plain(parse(shown)).isBlank()) {
            return shown.trim();
        }
        return null;
    }

    /** Short report for /sidebar debug: what the plugin reads from LuckPerms and Vault for this player. */
    public static String describe(Player p) {
        return "LuckPerms API: " + (lp() != null) + ", Vault chat: " + (chat() != null)
                + ", group: " + groupId(p) + ", group display name: " + lpDisplayName(groupId(p))
                + ", prefix: " + plain(prefix(p)).trim() + ", name colour: " + nameColour(p) + ", shown name: " + plain(name(p));
    }

    /** The id of the player's primary group in the permissions plugin ("default", "admin" ...), or null. */
    public static String groupId(Player p) {
        String direct = lpGroup(p);
        if (direct != null) {
            return direct;
        }
        Object c = chat();
        if (c == null) {
            return null;
        }
        try {
            String g = Lookup.group(c, p);
            return g == null || g.isBlank() ? null : g;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The player's rank: the primary group's "displayname" meta (LuckPerms: /lp group admin meta set displayname
     * "&amp;cAdmin"), else the group's prefix without brackets around it, else the group name with a capital.
     * Empty when there is no permissions plugin.
     */
    public static Component group(Player p) {
        Object c = chat();
        String g = groupId(p);
        if (g == null) {
            return Component.empty();
        }
        if (c == null) {
            String own = lpDisplayName(g);
            return own != null && !plain(parse(own)).isBlank() ? parse(own)
                    : Component.text(Character.toUpperCase(g.charAt(0)) + g.substring(1));
        }
        try {
            String own = lpDisplayName(g);                        // /lp group admin setdisplayname "&cAdmin"
            if (own != null && !own.isBlank() && !plain(parse(own)).isBlank()) {
                return parse(own);
            }
            String shown = Lookup.groupMeta(c, p, g, "displayname");
            if (shown == null || shown.isBlank()) {
                String prefix = Lookup.groupPrefix(c, p, g);
                String plainPrefix = prefix == null ? "" : plain(parse(prefix)).trim();
                if (!plainPrefix.isEmpty()) {
                    return parse(prefix.trim().replaceAll("^(&[0-9a-fk-or]|§[0-9a-fk-or])*\\[", "$1").replaceAll("\\]\\s*$", ""));
                }
                return Component.text(Character.toUpperCase(g.charAt(0)) + g.substring(1));
            }
            return parse(shown);
        } catch (Throwable t) {
            return Component.text(g);
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
        for (String key : new String[]{"displayname", "display-name", "nickname"}) {
            String raw = lpMeta(p, key);
            if (raw == null) {
                Object c = chat();
                try {
                    raw = c == null ? null : Lookup.meta(c, p, key);
                } catch (Throwable ignored) {
                    raw = null;                                   // a permissions plugin without meta support
                }
            }
            if (raw != null && !raw.isBlank() && !plain(parse(raw)).isBlank()) {
                return parse(raw);
            }
        }
        Component shown = p.displayName();
        boolean own = plain(shown).equals(p.getName()) || plain(shown).isBlank();
        String colour = nameColour(p);
        if (colour != null) {
            return parse(colour + (own ? p.getName() : plain(shown)));
        }
        return own ? Component.text(p.getName()) : shown;
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
