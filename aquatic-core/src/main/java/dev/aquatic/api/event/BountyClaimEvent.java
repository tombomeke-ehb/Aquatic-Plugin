package dev.aquatic.api.event;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a killer was paid the bounty on the victim's head. */
public class BountyClaimEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player killer;
    private final UUID victim;
    private final double amount;

    public BountyClaimEvent(Player killer, UUID victim, double amount) {
        this.killer = killer;
        this.victim = victim;
        this.amount = amount;
    }

    public Player killer() {
        return killer;
    }

    public UUID victim() {
        return victim;
    }

    public double amount() {
        return amount;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
