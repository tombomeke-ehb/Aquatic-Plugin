package dev.aquatic.api;

public enum CrewRelation {
    /** Both are the same crew. */
    SAME,
    ALLY,
    NEUTRAL,
    RIVAL,
    /** Open war: raiding/conquest restrictions between the two crews are lifted. */
    WAR
}
