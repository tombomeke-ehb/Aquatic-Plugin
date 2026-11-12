package dev.aquatic.api.event;

import dev.aquatic.api.Crew;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a crew's flag changed (new banner, colour or preset), so planted flags can be updated. */
public class CrewFlagChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Crew crew;

    public CrewFlagChangeEvent(Crew crew) {
        this.crew = crew;
    }

    public Crew crew() {
        return crew;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
