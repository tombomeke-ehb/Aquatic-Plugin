package dev.aquatic.api;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;

/** Provided by the "treasure" module. */
public interface TreasureService {

    /** Configured loot tiers, e.g. common, rare, legendary. */
    Collection<String> tiers();

    /**
     * Buries/places a treasure chest of the tier at (or just below the surface near) the location.
     *
     * @return the chest location, or null when the tier is unknown or no spot was found.
     */
    Location spawnTreasure(Location near, String tier);

    /** Spawns a treasure on a random registered island (or random land when there are none). */
    Location spawnRandomTreasure(String tier);

    /** A treasure map item pointing to an existing treasure location. */
    ItemStack createTreasureMap(Location treasure, String tier);

    /**
     * True when the block is a buried treasure chest or one of the blocks right above it (the shaft a
     * player digs down). Protection can use this to let anyone dig up treasure, even on claimed land.
     */
    boolean isTreasureDigSpot(Location block);

        /** Removes an unopened treasure (used by events that time out). */
    void removeTreasure(Location treasure);
}
