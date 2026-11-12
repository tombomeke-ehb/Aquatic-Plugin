package dev.aquatic.api;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** A pirate crew. Live view: always reflects the current state. */
public interface Crew {

    /** Stable unique id (never changes, safe to store). */
    String id();

    String name();

    /** Short tag shown in chat, e.g. "SHP". */
    String tag();

    TextColor color();

    /** Crew name in the crew colour. */
    Component displayName();

    UUID captain();

    Set<UUID> members();

    /** @return the rank, or null when the player is not in this crew. */
    CrewRank rankOf(UUID player);

    Collection<Player> onlineMembers();

    /** The crew flag (a banner item copy); a plain white banner when none was set. */
    ItemStack flag();

    /** Fame/infamy score used for leaderboards. */
    int reputation();

    void addReputation(int amount);

    double bankBalance();

    void depositBank(double amount);

    /** @return false when the bank does not hold enough. */
    boolean withdrawBank(double amount);

    /** Sends a message to all online members. */
    void broadcast(Component message);
}
