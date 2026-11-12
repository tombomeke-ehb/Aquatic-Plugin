package dev.aquatic.api;

import java.util.Map;
import java.util.UUID;

/** Provided by the "bounty" module. */
public interface BountyService {

    /** Total bounty on a player's head (0 when none). */
    double bountyOf(UUID player);

    /** Raises a player's bounty without charging anyone (events, notoriety, admin). */
    void addBounty(UUID target, double amount, String reason);

    /** Lowers a player's bounty by up to "amount" (never below 0) without paying anything out. */
    void reduceBounty(UUID target, double amount);

    /** Removes the bounty without paying it out. */
    void clearBounty(UUID target);

    /** Highest bounties first. */
    Map<UUID, Double> top(int limit);
}
