package dev.aquatic.api;

/** Crew hierarchy, highest authority first. */
public enum CrewRank {
    CAPTAIN(4, "Captain"),
    FIRST_MATE(3, "First Mate"),
    NAVIGATOR(2, "Navigator"),
    QUARTERMASTER(2, "Quartermaster"),
    MEMBER(1, "Crew Member");

    private final int weight;
    private final String display;

    CrewRank(int weight, String display) {
        this.weight = weight;
        this.display = display;
    }

    public int weight() {
        return weight;
    }

    public String display() {
        return display;
    }

    /** Captain and First Mate: may claim islands, declare war, register for tournaments, ... */
    public boolean isOfficer() {
        return weight >= 3;
    }
}
