package dev.aquatic.api;

import org.bukkit.Location;

/** A registered island (circular region around a centre). Live view. */
public interface Island {

    /** Stable unique id (lowercase, no spaces). */
    String id();

    String name();

    IslandType type();

    IslandStatus status();

    /** Centre of the island at surface height. */
    Location center();

    int radius();

    /** Id of the owning crew, or null. */
    String ownerCrewId();

    /** True when the location is in the island's world and within its radius (height is ignored). */
    boolean contains(Location location);
}
