package dev.aquatic.events.tournament;

import dev.aquatic.events.Util;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

/** A tournament arena: two team spawns and an optional spectator point. */
public final class Arena {

    public final String name;
    public Location spawn1;
    public Location spawn2;
    public Location spectator;

    public Arena(String name) {
        this.name = name;
    }

    public boolean complete() {
        return spawn1 != null && spawn2 != null && spawn1.getWorld() != null && spawn2.getWorld() != null;
    }

    /** Where eliminated fighters watch from. */
    public Location watchPoint() {
        return spectator != null ? spectator : spawn1;
    }

    public void save(ConfigurationSection s) {
        s.set("spawn1", Util.locToString(spawn1));
        s.set("spawn2", Util.locToString(spawn2));
        s.set("spectator", Util.locToString(spectator));
    }

    public static Arena load(String name, ConfigurationSection s) {
        Arena a = new Arena(name);
        a.spawn1 = Util.locFromString(s.getString("spawn1"));
        a.spawn2 = Util.locFromString(s.getString("spawn2"));
        a.spectator = Util.locFromString(s.getString("spectator"));
        return a;
    }
}
