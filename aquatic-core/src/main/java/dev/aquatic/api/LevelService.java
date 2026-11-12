package dev.aquatic.api;

import java.util.UUID;

/** Pirate levels (XP from fights, treasure and exploring). Provided by the "levels" module. */
public interface LevelService {

    int level(UUID player);

    long xp(UUID player);

    /** XP still needed for the next level. */
    long xpToNext(UUID player);

    /** Gives XP (e.g. for an event reward); handles level-ups. */
    void addXp(UUID player, long amount, String reason);
}
