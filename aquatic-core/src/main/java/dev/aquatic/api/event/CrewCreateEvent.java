package dev.aquatic.api.event;

import dev.aquatic.api.Crew;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after the action happened. */
public class CrewCreateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Crew crew;
    private final Player founder;

    public CrewCreateEvent(Crew crew, Player founder) {
        this.crew = crew;
        this.founder = founder;
    }

    public Crew crew() {
        return crew;
    }

    public Player founder() {
        return founder;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
