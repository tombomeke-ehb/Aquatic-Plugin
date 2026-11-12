package dev.aquatic.api;

import org.bukkit.Location;

import java.util.Collection;

/** Provided by the "islands" module. */
public interface IslandService {

    /** @return the island at the location, or null when it is open sea. */
    Island islandAt(Location location);

    Island islandById(String id);

    Collection<Island> islands();

    Collection<Island> islandsOwnedBy(String crewId);

    /**
     * Registers a new island and, when "generate" is true, builds its terrain in the world
     * (spread over several ticks). Must be called from the main thread.
     *
     * @return the island, or null when the name is taken or the area overlaps another island.
     */
    Island createIsland(String name, IslandType type, Location center, int radius, boolean generate);

    /** Unregisters the island. When "restoreOcean" is true the terrain is replaced by water again. */
    boolean removeIsland(String id, boolean restoreOcean);

    /** Changes ownership (crewId null = unclaimed). Does not fire conquest logic, only updates state/markers. */
    void setOwner(Island island, String crewId);

    void setStatus(Island island, IslandStatus status);

    /** The coloured rarity name of an island type ("Common" ... "Legendary"), or null. */
    default net.kyori.adventure.text.Component rarityDisplay(IslandType type) {
        return null;
    }

    /** A random free spot in the configured ocean area that does not overlap any island, or null. */
    Location findFreeOceanSpot(int radius);
}
