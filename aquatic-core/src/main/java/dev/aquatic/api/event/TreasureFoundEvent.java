package dev.aquatic.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired when a player opens a treasure chest for the first time. */
public class TreasureFoundEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Location location;
    private final String tier;

    public TreasureFoundEvent(Player player, Location location, String tier) {
        this.player = player;
        this.location = location;
        this.tier = tier;
    }

    public Player player() {
        return player;
    }

    public Location location() {
        return location;
    }

    public String tier() {
        return tier;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
