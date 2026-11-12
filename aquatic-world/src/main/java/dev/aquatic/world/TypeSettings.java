package dev.aquatic.world;

import dev.aquatic.api.IslandStatus;
import dev.aquatic.api.IslandType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Generation settings of one island type, read from "types.&lt;TYPE&gt;" in islands.yml. */
final class TypeSettings {

    final IslandType type;
    Biome biome;
    IslandStatus defaultStatus = IslandStatus.UNCLAIMED;
    int maxHeight;
    int beachHeight;
    double roughness;
    double coastVariation;
    final WeightedList<Material> surface = new WeightedList<>();
    final WeightedList<Material> subsurface = new WeightedList<>();
    final WeightedList<Material> core = new WeightedList<>();
    final WeightedList<Material> beach = new WeightedList<>();
    final WeightedList<Material> underwater = new WeightedList<>();
    final WeightedList<Material> ores = new WeightedList<>();
    double oreChance;
    Material topLayer;
    boolean frozenShore;

    /** Multiplier for mountains on this island type (0 = none). */
    double mountainScale = 1.0;

    /** Sea life on the underwater slope: chances per column. */
    double coral, seagrass, kelp;

    double treeDensity;
    /** Entries are org.bukkit.TreeType names or the custom type PALM. */
    final WeightedList<String> trees = new WeightedList<>();
    boolean palmsOnBeach;
    Material palmLog = Material.JUNGLE_LOG;
    Material palmLeaves = Material.JUNGLE_LEAVES;

    /** material -> chance per surface column */
    final Map<Material, Double> decorations = new LinkedHashMap<>();

    double ruinDensity;
    final WeightedList<Material> ruinBlocks = new WeightedList<>();
    boolean ruinPillars;
    Material pillarCap;

    boolean volcano;
    int coneHeight;
    double coneSize;
    double craterSize;
    int craterDepth;
    Material craterFluid = Material.LAVA;
    /** Lava streams that spill over the crater rim and flow down the volcano. */
    int lavaStreams = 2;
    final WeightedList<Material> coneBlocks = new WeightedList<>();

    boolean plaza;
    double plazaSize;
    int plazaHeight;
    final WeightedList<Material> plazaBlocks = new WeightedList<>();
    boolean throne;
    /** Capital town: roads, houses, piers and a lighthouse around the plaza. */
    boolean town;
    int townHouses = 16;
    final WeightedList<Material> roadBlocks = new WeightedList<>();
    Material throneBase = Material.POLISHED_ANDESITE;
    Material throneAccent = Material.GOLD_BLOCK;
    Material throneSeat = Material.QUARTZ_STAIRS;
    Material throneCarpet = Material.RED_CARPET;
    Material throneLight = Material.SEA_LANTERN;

    TypeSettings(IslandType type) {
        this.type = type;
    }

    static TypeSettings load(IslandType type, ConfigurationSection s, Logger log) {
        TypeSettings t = new TypeSettings(type);
        if (s == null) {
            t.maxHeight = 12;
            t.beachHeight = 2;
            t.roughness = 1;
            t.coastVariation = 0.25;
            t.surface.add(Material.GRASS_BLOCK, 1);
            t.subsurface.add(Material.DIRT, 1);
            t.core.add(Material.STONE, 1);
            t.beach.add(Material.SAND, 1);
            t.underwater.add(Material.SAND, 1);
            return t;
        }
        String ctx = "types." + type.name();
        String biomeName = s.getString("biome");
        if (biomeName != null && !biomeName.isBlank() && !biomeName.equalsIgnoreCase("none")) {
            try {
                t.biome = Registry.BIOME.get(NamespacedKey.minecraft(biomeName.toLowerCase(Locale.ROOT)));
            } catch (RuntimeException e) {
                t.biome = null;
            }
            if (t.biome == null) {
                log.warning("[islands] Unknown biome '" + biomeName + "' in " + ctx);
            }
        }
        try {
            t.defaultStatus = IslandStatus.valueOf(s.getString("status", "UNCLAIMED").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            t.defaultStatus = IslandStatus.UNCLAIMED;
        }
        if (t.defaultStatus == IslandStatus.OCCUPIED) {
            t.defaultStatus = IslandStatus.UNCLAIMED;
        }
        t.maxHeight = Math.max(1, s.getInt("max-height", 12));
        t.lavaStreams = Math.max(0, s.getInt("volcano.lava-streams", 2));
        boolean warm = t.type == IslandType.TROPICAL || t.type == IslandType.PLAINS || t.type == IslandType.JUNGLE;
        boolean frozen = t.type == IslandType.SNOWY;
        t.coral = Math.max(0, s.getDouble("sea-life.coral", warm ? 0.07 : 0));
        t.seagrass = Math.max(0, s.getDouble("sea-life.seagrass", frozen ? 0.05 : 0.25));
        t.kelp = Math.max(0, s.getDouble("sea-life.kelp", warm ? 0.01 : frozen ? 0 : 0.05));
        t.town = s.getBoolean("town.enabled", t.type == IslandType.CAPITAL);
        t.townHouses = Math.max(0, s.getInt("town.houses", 16));
        blocks(t.roadBlocks, s.getStringList("town.road"), Material.STONE_BRICKS, log, ctx + ".town.road");
        t.mountainScale = Math.max(0, s.getDouble("mountains", t.type == IslandType.CAPITAL ? 0.4 : 1.0));
        t.beachHeight = Math.max(0, s.getInt("beach-height", 2));
        t.roughness = Math.max(0.1, s.getDouble("roughness", 1.0));
        t.coastVariation = Math.max(0, Math.min(0.3, s.getDouble("coast-variation", 0.25)));
        blocks(t.surface, s.getStringList("surface"), Material.GRASS_BLOCK, log, ctx);
        blocks(t.subsurface, s.getStringList("subsurface"), Material.DIRT, log, ctx);
        blocks(t.core, s.getStringList("core"), Material.STONE, log, ctx);
        blocks(t.beach, s.getStringList("beach"), Material.SAND, log, ctx);
        blocks(t.underwater, s.getStringList("underwater"), Material.SAND, log, ctx);
        blocks(t.ores, s.getStringList("ores.blocks"), null, log, ctx);
        t.oreChance = t.ores.isEmpty() ? 0 : Math.max(0, Math.min(1, s.getDouble("ores.chance", 0.0)));
        t.topLayer = block(s.getString("top-layer"), log, ctx);
        t.frozenShore = s.getBoolean("frozen-shore", false);

        t.treeDensity = Math.max(0, s.getDouble("trees.density", 0));
        for (String raw : s.getStringList("trees.types")) {
            String[] parts = raw.split(":");
            t.trees.add(parts[0].trim().toUpperCase(Locale.ROOT), weight(parts));
        }
        t.palmsOnBeach = s.getBoolean("trees.palms-on-beach", true);
        t.palmLog = orDefault(block(s.getString("trees.palm-log"), log, ctx), t.palmLog);
        t.palmLeaves = orDefault(block(s.getString("trees.palm-leaves"), log, ctx), t.palmLeaves);

        ConfigurationSection deco = s.getConfigurationSection("decorations");
        if (deco != null) {
            for (String key : deco.getKeys(false)) {
                Material dm = block(key, log, ctx);
                double chance = deco.getDouble(key);
                if (dm != null && chance > 0) {
                    t.decorations.put(dm, chance);
                }
            }
        }

        t.ruinDensity = Math.max(0, s.getDouble("ruins.density", 0));
        blocks(t.ruinBlocks, s.getStringList("ruins.blocks"), null, log, ctx);
        t.ruinPillars = s.getBoolean("ruins.pillars", false);
        t.pillarCap = block(s.getString("ruins.pillar-cap"), log, ctx);

        t.volcano = s.getBoolean("volcano.enabled", false);
        t.coneHeight = Math.max(4, s.getInt("volcano.cone-height", 26));
        t.coneSize = Math.max(0.2, Math.min(0.9, s.getDouble("volcano.cone-size", 0.55)));
        t.craterSize = Math.max(0.04, Math.min(t.coneSize * 0.45, s.getDouble("volcano.crater-size", 0.14)));
        t.craterDepth = Math.max(2, s.getInt("volcano.crater-depth", 7));
        t.craterFluid = orDefault(block(s.getString("volcano.crater-fluid"), log, ctx), t.craterFluid);
        blocks(t.coneBlocks, s.getStringList("volcano.blocks"), Material.BASALT, log, ctx);

        t.plaza = s.getBoolean("plaza.enabled", false);
        t.plazaSize = Math.max(0.1, Math.min(0.8, s.getDouble("plaza.size", 0.4)));
        t.plazaHeight = Math.max(1, s.getInt("plaza.height", 4));
        blocks(t.plazaBlocks, s.getStringList("plaza.blocks"), Material.STONE_BRICKS, log, ctx);
        t.throne = s.getBoolean("plaza.throne.enabled", true);
        t.throneBase = orDefault(block(s.getString("plaza.throne.base"), log, ctx), t.throneBase);
        t.throneAccent = orDefault(block(s.getString("plaza.throne.accent"), log, ctx), t.throneAccent);
        t.throneSeat = orDefault(block(s.getString("plaza.throne.seat"), log, ctx), t.throneSeat);
        t.throneCarpet = orDefault(block(s.getString("plaza.throne.carpet"), log, ctx), t.throneCarpet);
        t.throneLight = orDefault(block(s.getString("plaza.throne.light"), log, ctx), t.throneLight);
        return t;
    }

    private static Material orDefault(Material m, Material def) {
        return m == null ? def : m;
    }

    private static double weight(String[] parts) {
        if (parts.length < 2) {
            return 1;
        }
        try {
            return Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static Material block(String raw, Logger log, String ctx) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return null;
        }
        Material m = Material.matchMaterial(raw.trim());
        if (m == null || !m.isBlock()) {
            log.warning("[islands] '" + raw + "' in " + ctx + " is not a valid block material");
            return null;
        }
        return m;
    }

    /** Parses "MATERIAL" / "MATERIAL:weight" entries. */
    static void blocks(WeightedList<Material> into, List<String> raw, Material fallback, Logger log, String ctx) {
        for (String entry : raw) {
            String[] parts = entry.split(":");
            Material m = block(parts[0], log, ctx);
            if (m != null) {
                into.add(m, weight(parts));
            }
        }
        if (into.isEmpty() && fallback != null) {
            into.add(fallback, 1);
        }
    }
}
