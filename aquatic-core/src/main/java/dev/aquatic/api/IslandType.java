package dev.aquatic.api;

public enum IslandType {
    TROPICAL, PLAINS, FOREST, JUNGLE, VOLCANIC, SNOWY, DESERT, ANCIENT, CAPITAL,
    /** A Tidewatch outpost: a fort with a garrison that must be defeated before the island can be claimed. */
    TIDEWATCH;

    /** @return the type, or null when the text is not a valid type. */
    public static IslandType parse(String raw) {
        try {
            return valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
