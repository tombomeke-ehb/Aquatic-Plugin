package dev.aquatic.api;

import java.util.UUID;

/** Provided by the "tournament" module. */
public interface TournamentService {

    /** @return the reigning Pirate King, or null when the throne is empty. */
    UUID conqueror();

    /** Last known name of the Pirate King, or null. */
    String conquerorName();

    boolean isConqueror(UUID player);

    /** True while the player is fighting in a tournament match (deaths and logouts are handled by the tournament). */
    default boolean isInMatch(UUID player) {
        return false;
    }

    /** True while a tournament is in progress (registration through finale). */
    boolean tournamentRunning();
}
