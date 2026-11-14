package dev.aquatic.crews;

import dev.aquatic.util.Ranks;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Rank prefixes, suffixes and display names from LuckPerms (through Vault): see {@link Ranks}. */
final class PermsHook {

    private PermsHook() {
    }

    static Component prefix(Player p) {
        return Ranks.prefix(p);
    }

    static Component suffix(Player p) {
        return Ranks.suffix(p);
    }

    static Component name(Player p) {
        return Ranks.name(p);
    }
}
