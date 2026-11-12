package dev.aquatic.api.event;

import dev.aquatic.api.Crew;
import dev.aquatic.api.Island;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired before an unclaimed island is claimed; cancel to prevent it. */
public class IslandClaimEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Island island;
    private final Crew crew;
    private final Player player;
    private boolean cancelled;

    public IslandClaimEvent(Island island, Crew crew, Player player) {
        this.island = island;
        this.crew = crew;
        this.player = player;
    }

    public Island island() {
        return island;
    }

    public Crew crew() {
        return crew;
    }

    public Player player() {
        return player;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
