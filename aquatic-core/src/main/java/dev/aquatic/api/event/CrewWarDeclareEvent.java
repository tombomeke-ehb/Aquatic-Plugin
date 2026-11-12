package dev.aquatic.api.event;

import dev.aquatic.api.Crew;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after the action happened. */
public class CrewWarDeclareEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Crew attacker;
    private final Crew defender;

    public CrewWarDeclareEvent(Crew attacker, Crew defender) {
        this.attacker = attacker;
        this.defender = defender;
    }

    public Crew attacker() {
        return attacker;
    }

    public Crew defender() {
        return defender;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
