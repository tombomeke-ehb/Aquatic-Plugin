package dev.aquatic.api;

import java.util.UUID;

/** Combat tags and protections. Provided by the "combat" module. */
public interface CombatService {

    boolean inCombat(UUID player);

    /** Seconds left of the combat tag (0 = not in combat). */
    long combatSecondsLeft(UUID player);
}
