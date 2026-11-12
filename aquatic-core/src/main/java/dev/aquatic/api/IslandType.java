package dev.aquatic.api;

public enum IslandType {
    TROPICAL, JUNGLE, VOLCANIC, SNOWY, DESERT, ANCIENT, CAPITAL;

    /** @return the type, or null when the text is not a valid type. */
    public static IslandType parse(String raw) {
        try {
            return valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
