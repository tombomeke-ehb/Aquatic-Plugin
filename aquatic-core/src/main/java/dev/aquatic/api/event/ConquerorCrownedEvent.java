package dev.aquatic.api.event;

import dev.aquatic.api.Crew;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired when a new Pirate King is crowned (crew may be null). */
public class ConquerorCrownedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID king;
    private final String kingName;
    private final Crew crew;

    public ConquerorCrownedEvent(UUID king, String kingName, Crew crew) {
        this.king = king;
        this.kingName = kingName;
        this.crew = crew;
    }

    public UUID king() {
        return king;
    }

    public String kingName() {
        return kingName;
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
