package dev.aquatic.api;

public enum IslandStatus {
    /** Free to claim. */
    UNCLAIMED,
    /** Owned by a crew; can be conquered. */
    OCCUPIED,
    /** Safe zone (spawn, trading posts, capital): no PvP, no claiming, no griefing. */
    NEUTRAL
}
