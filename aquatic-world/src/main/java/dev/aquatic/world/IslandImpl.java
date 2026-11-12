package dev.aquatic.world;

import dev.aquatic.api.Island;
import dev.aquatic.api.IslandStatus;
import dev.aquatic.api.IslandType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** Mutable island record owned by the islands module. */
final class IslandImpl implements Island {

    final String id;
    String name;
    IslandType type;
    IslandStatus status = IslandStatus.UNCLAIMED;
    final String world;
    final int x;
    int y;
    final int z;
    int radius;
    String ownerCrewId;
    long seed;
    /** Base block of the flag pole (the conquest point); only meaningful when hasFlag. */
    boolean hasFlag;
    int flagX, flagY, flagZ;
    /** Epoch millis until which the island cannot be conquered. */
    long shieldUntil;
    long claimedAt;

    IslandImpl(String id, String name, IslandType type, String world, int x, int y, int z, int radius, long seed) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.seed = seed;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public IslandType type() {
        return type;
    }

    @Override
    public IslandStatus status() {
        return status;
    }

    @Override
    public Location center() {
        return new Location(Bukkit.getWorld(world), x + 0.5, y, z + 0.5);
    }

    @Override
    public int radius() {
        return radius;
    }

    @Override
    public String ownerCrewId() {
        return ownerCrewId;
    }

    @Override
    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().getName().equals(world)) {
            return false;
        }
        return contains(location.getBlockX(), location.getBlockZ());
    }

    boolean contains(int bx, int bz) {
        long dx = bx - x, dz = bz - z;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    Location flagLocation() {
        if (!hasFlag) {
            return null;
        }
        return new Location(Bukkit.getWorld(world), flagX, flagY, flagZ);
    }

    void setFlag(int fx, int fy, int fz) {
        hasFlag = true;
        flagX = fx;
        flagY = fy;
        flagZ = fz;
    }

    double distance(int bx, int bz) {
        double dx = bx - x, dz = bz - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("type", type.name());
        s.set("status", status.name());
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("radius", radius);
        s.set("owner", ownerCrewId);
        s.set("seed", seed);
        if (hasFlag) {
            s.set("flag.x", flagX);
            s.set("flag.y", flagY);
            s.set("flag.z", flagZ);
        }
        s.set("shield-until", shieldUntil);
        s.set("claimed-at", claimedAt);
    }

    static IslandImpl load(String id, ConfigurationSection s) {
        IslandType type = IslandType.parse(s.getString("type"));
        String world = s.getString("world");
        if (type == null || world == null) {
            return null;
        }
        IslandImpl is = new IslandImpl(id, s.getString("name", id), type, world,
                s.getInt("x"), s.getInt("y"), s.getInt("z"), Math.max(1, s.getInt("radius", 30)), s.getLong("seed"));
        try {
            is.status = IslandStatus.valueOf(s.getString("status", "UNCLAIMED"));
        } catch (IllegalArgumentException e) {
            is.status = IslandStatus.UNCLAIMED;
        }
        is.ownerCrewId = s.getString("owner");
        if (is.ownerCrewId == null && is.status == IslandStatus.OCCUPIED) {
            is.status = IslandStatus.UNCLAIMED;
        }
        if (s.isSet("flag.x")) {
            is.setFlag(s.getInt("flag.x"), s.getInt("flag.y"), s.getInt("flag.z"));
        }
        is.shieldUntil = s.getLong("shield-until");
        is.claimedAt = s.getLong("claimed-at");
        return is;
    }
}
