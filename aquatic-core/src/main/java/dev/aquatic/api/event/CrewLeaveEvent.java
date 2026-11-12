package dev.aquatic.api.event;

import dev.aquatic.api.Crew;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after the action happened. */
public class CrewLeaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Crew crew;
    private final UUID player;

    public CrewLeaveEvent(Crew crew, UUID player) {
        this.crew = crew;
        this.player = player;
    }

    public Crew crew() {
        return crew;
    }

    public UUID player() {
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
