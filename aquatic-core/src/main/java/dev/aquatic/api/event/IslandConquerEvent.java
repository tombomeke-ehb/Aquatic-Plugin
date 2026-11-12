package dev.aquatic.api.event;

import dev.aquatic.api.Crew;
import dev.aquatic.api.Island;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after an occupied island changed hands through conquest. */
public class IslandConquerEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Island island;
    private final String previousCrewId;
    private final Crew conqueror;

    public IslandConquerEvent(Island island, String previousCrewId, Crew conqueror) {
        this.island = island;
        this.previousCrewId = previousCrewId;
        this.conqueror = conqueror;
    }

    public Island island() {
        return island;
    }

    public String previousCrewId() {
        return previousCrewId;
    }

    public Crew conqueror() {
        return conqueror;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
