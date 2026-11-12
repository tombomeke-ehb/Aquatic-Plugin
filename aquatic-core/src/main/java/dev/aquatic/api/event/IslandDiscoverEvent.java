package dev.aquatic.api.event;

import dev.aquatic.api.Island;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired the first time a player sets foot on an island. */
public class IslandDiscoverEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Island island;
    private final Player player;

    public IslandDiscoverEvent(Island island, Player player) {
        this.island = island;
        this.player = player;
    }

    public Island island() {
        return island;
    }

    public Player player() {
        return player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
