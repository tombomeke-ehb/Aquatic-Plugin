package dev.aquatic.crews;

import dev.aquatic.command.CommandFail;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.UUID;

/** Small helpers shared by the economy, crews and bounty modules. */
final class Players {

    private Players() {
    }

    /**
     * Resolves a player by name without ever doing a blocking web lookup: online players first,
     * then the user cache of the server. Returns null for names the server has never seen.
     */
    static OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) {
            return cached;
        }
        return null;
    }

    /** Best known name of a player, falling back to a shortened UUID. */
    static String name(UUID id, String stored) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        String cached = Bukkit.getOfflinePlayer(id).getName();
        return cached != null ? cached : id.toString().substring(0, 8);
    }

    /**
     * Parses a positive money amount. Accepts the suffixes k, m and b (10k = 10,000).
     * Rejects NaN, infinity, zero and negative numbers.
     */
    static double amount(String raw) {
        String s = raw.trim().toLowerCase().replace(",", "");
        double factor = 1;
        if (s.endsWith("k")) {
            factor = 1_000d;
        } else if (s.endsWith("m")) {
            factor = 1_000_000d;
        } else if (s.endsWith("b")) {
            factor = 1_000_000_000d;
        }
        if (factor != 1) {
            s = s.substring(0, s.length() - 1);
        }
        double value;
        try {
            value = Double.parseDouble(s) * factor;
        } catch (NumberFormatException e) {
            throw new CommandFail("<red><raw> is not a valid amount.", "raw", raw);
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) {
            throw new CommandFail("<red><raw> is not a valid amount.", "raw", raw);
        }
        return value;
    }

    /** Registers a permission node if no plugin has defined it yet. */
    static void ensurePermission(String node, PermissionDefault def) {
        if (Bukkit.getPluginManager().getPermission(node) == null) {
            try {
                Bukkit.getPluginManager().addPermission(new Permission(node, def));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
