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
    /** Bought island upgrades: type -> level (see IslandUpgrades). */
    final java.util.Map<String, Integer> upgrades = new java.util.HashMap<>();
    /** Facilities (buildings) the crew placed on this island (see IslandFacilities). */
    final java.util.List<IslandFacilities.Placed> facilities = new java.util.ArrayList<>();

    /** true = only Captain, First Mate, Navigator and Quartermaster may build/break/open (not plain members). */
    boolean officersOnly;
    /** Vault upgrade: the crew's storage on this island (54 slots; the level decides how many are usable). */
    final org.bukkit.inventory.ItemStack[] vault = new org.bukkit.inventory.ItemStack[54];

    int upgrade(String type) {
        return upgrades.getOrDefault(type, 0);
    }

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
        s.set("officers-only", officersOnly ? true : null);
        s.set("upgrades", null);
        for (java.util.Map.Entry<String, Integer> e : upgrades.entrySet()) {
            if (e.getValue() > 0) {
                s.set("upgrades." + e.getKey(), e.getValue());
            }
        }
        s.set("facilities", facilities.isEmpty() ? null : facilities.stream().map(IslandFacilities.Placed::serialize).toList());
        s.set("vault", null);
        for (int i = 0; i < vault.length; i++) {
            if (vault[i] != null && !vault[i].getType().isAir()) {
                s.set("vault." + i, java.util.Base64.getEncoder().encodeToString(vault[i].serializeAsBytes()));
            }
        }
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
        is.officersOnly = s.getBoolean("officers-only", false);
        for (String raw : s.getStringList("facilities")) {
            IslandFacilities.Placed f = IslandFacilities.Placed.parse(raw);
            if (f != null) {
                is.facilities.add(f);
            }
        }
        ConfigurationSection up = s.getConfigurationSection("upgrades");
        if (up != null) {
            for (String key : up.getKeys(false)) {
                is.upgrades.put(key, Math.max(0, up.getInt(key)));
            }
        }
        ConfigurationSection v = s.getConfigurationSection("vault");
        if (v != null) {
            for (String key : v.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    if (slot >= 0 && slot < is.vault.length) {
                        is.vault[slot] = org.bukkit.inventory.ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(v.getString(key, "")));
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        return is;
    }
}
