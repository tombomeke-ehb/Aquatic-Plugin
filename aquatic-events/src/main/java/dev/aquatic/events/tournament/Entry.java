package dev.aquatic.events.tournament;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A registered crew: its roster and qualifier points. */
public final class Entry {

    public final String crewId;
    /** Last known crew name (kept for display when the crew is disbanded). */
    public String name;
    public final List<UUID> roster = new ArrayList<>();
    public int points;
    public boolean disbanded;

    public Entry(String crewId, String name) {
        this.crewId = crewId;
        this.name = name;
    }
}
