package dev.aquatic.api;

import java.util.Collection;
import java.util.UUID;

/** Provided by the "crews" module. */
public interface CrewService {

    /** @return the crew of the player, or null. */
    Crew crewOf(UUID player);

    Crew crewById(String id);

    /** Case-insensitive lookup by name. */
    Crew crewByName(String name);

    Collection<Crew> crews();

    boolean sameCrew(UUID a, UUID b);

    /** Never null; NEUTRAL when either is null. */
    CrewRelation relation(Crew a, Crew b);

    /** Relation between the crews of two players (NEUTRAL when either has no crew). */
    CrewRelation relation(UUID a, UUID b);

    int maxMembers();
}
