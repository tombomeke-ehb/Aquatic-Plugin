package dev.aquatic.world;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Procedural island builder. The whole island is a pure function of (seed, type settings, radius):
 * a noise-distorted radial falloff gives an irregular coastline, a second noise the hills. Work is
 * split into phases (chunk loading, terrain columns, biome, features) and executed within a
 * per-tick time budget handed in by the job queue.
 */
final class IslandGenerator extends AreaJob {

    private static final byte NONE = 0, SLOPE = 1, BEACH = 2, LAND = 3, CONE = 4, CRATER = 5, PLAZA = 6, MOUNTAIN = 7, BUILT = 8;

    /** Mountains, caves and ore veins: "generation.terrain" in islands.yml. */
    static final class TerrainOptions {
        boolean mountains = true;
        double mountainHeight = 45;
        double mountainFrequency = 1.0;
        int rockAbove = 22;
        int snowAbove = 48;
        final WeightedList<Material> rock = new WeightedList<>();
        boolean caves = true;
        int caveMinRadius = 60;
        double caveSize = 1.0;
        int caveDepth = 14;
        boolean caveEntrances = true;
        boolean caverns = true;
        boolean veins = true;
        double veinsPer1000 = 4;
        final WeightedList<Material> veinOres = new WeightedList<>();

        static TerrainOptions load(org.bukkit.configuration.ConfigurationSection s, java.util.logging.Logger log) {
            TerrainOptions o = new TerrainOptions();
            if (s != null) {
                o.mountains = s.getBoolean("mountains.enabled", true);
                o.mountainHeight = Math.max(0, s.getDouble("mountains.height", 45));
                o.mountainFrequency = Math.max(0.1, s.getDouble("mountains.frequency", 1.0));
                o.rockAbove = s.getInt("mountains.rock-above", 22);
                o.snowAbove = s.getInt("mountains.snow-above", 48);
                TypeSettings.blocks(o.rock, s.getStringList("mountains.rock"), Material.STONE, log, "generation.terrain.mountains.rock");
                o.caves = s.getBoolean("caves.enabled", true);
                o.caveMinRadius = s.getInt("caves.min-island-radius", 40);
                o.caveSize = Math.max(0.2, Math.min(3, s.getDouble("caves.size", 1.4)));
                o.caveDepth = Math.max(0, s.getInt("caves.depth", 40));
                o.caveEntrances = s.getBoolean("caves.entrances", true);
                o.caverns = s.getBoolean("caves.caverns", true);
                o.veins = s.getBoolean("ore-veins.enabled", true);
                o.veinsPer1000 = Math.max(0, s.getDouble("ore-veins.per-1000-land", 10));
                TypeSettings.blocks(o.veinOres, s.getStringList("ore-veins.ores"), Material.COAL_ORE, log, "generation.terrain.ore-veins.ores");
            } else {
                o.rock.add(Material.STONE, 1);
                o.veinOres.add(Material.COAL_ORE, 1);
            }
            return o;
        }
    }

    private TerrainOptions terrain = TerrainOptions.load(null, null);
    private org.bukkit.util.noise.SimplexOctaveGenerator ridges;
    private org.bukkit.util.noise.SimplexNoiseGenerator caveA, caveB, caveGate, caveRoom;

    /** Mountains, caves and veins settings; call before the job starts. */
    void terrain(TerrainOptions options) {
        this.terrain = options;
    }

    private final String label;
    private final long seed;
    private final TypeSettings t;
    private final double landR;
    private final int n;
    private final int[] top;
    private final byte[] kind;
    private final SimplexOctaveGenerator shape;
    private final SimplexOctaveGenerator detail;
    private final Random rng;
    private final int lavaY;
    private final int maxElevation;

    /** Height multiplier: bigger islands get taller land (see generation.height-scaling). */
    private final double maxH, coneH;
    /** Big islands mix flat plains (room to build) with hilly regions. */
    private final boolean regions;

    private int phase;
    private int landCount;
    private Deque<Feature> features;
    private int featureTotal;
    private Random decoRandom;

    /** A tree/ruin/throne: needs the chunks within "reach" blocks of its spot loaded. */
    private record Feature(int dx, int dz, int reach, Runnable action) {
    }

    IslandGenerator(Plugin plugin, String label, World world, int cx, int cz, int radius, long seed,
                    TypeSettings settings, double landFraction) {
        this(plugin, label, world, cx, cz, radius, seed, settings, landFraction, 1.0, 3.0);
    }

    IslandGenerator(Plugin plugin, String label, World world, int cx, int cz, int radius, long seed,
                    TypeSettings settings, double landFraction, double heightScaling, double maxBoost) {
        super(plugin, world, cx, cz, radius);
        // radius 70 = the configured heights; radius 1000 = up to maxBoost times taller
        double boost = heightScaling <= 0 ? 1 : Math.max(1, Math.min(Math.max(1, maxBoost), Math.pow(radius / 70.0, 0.5 * heightScaling)));
        this.maxH = settings.maxHeight * boost;
        this.coneH = settings.coneHeight * boost;
        this.regions = radius >= 150;
        this.label = label;
        this.seed = seed;
        this.t = settings;
        this.landR = Math.max(2, radius * Math.max(0.3, Math.min(0.76, landFraction)));
        this.n = radius * 2 + 1;
        this.top = new int[n * n];
        this.kind = new byte[n * n];
        this.shape = new SimplexOctaveGenerator(new Random(seed), 3);
        this.shape.setScale(1.0 / Math.max(12.0, landR * 0.9));
        this.detail = new SimplexOctaveGenerator(new Random(seed * 31 + 7), 4);
        this.detail.setScale(t.roughness / 28.0);
        this.ridges = new org.bukkit.util.noise.SimplexOctaveGenerator(new Random(seed * 17 + 3), 3);
        this.ridges.setScale(1.0 / 90.0);
        this.caveA = new org.bukkit.util.noise.SimplexNoiseGenerator(new Random(seed * 101 + 11));
        this.caveB = new org.bukkit.util.noise.SimplexNoiseGenerator(new Random(seed * 211 + 13));
        this.caveGate = new org.bukkit.util.noise.SimplexNoiseGenerator(new Random(seed * 307 + 17));
        this.caveRoom = new org.bukkit.util.noise.SimplexNoiseGenerator(new Random(seed * 401 + 19));
        this.rng = new Random(seed ^ 0x9E3779B97F4A7C15L);
        double base = 1 + maxH * 0.65;
        double rim = coneH * Math.pow(1 - t.craterSize / t.coneSize, 1.4);
        this.lavaY = sea - 1 + (int) Math.round(base + rim) - 2;
        this.maxElevation = (int) Math.ceil(maxH + (t.volcano ? coneH : 0)) + 6;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public double progress() {
        return switch (phase) {
            case 0 -> 0.70 * streamProgress();
            case 1 -> 0.70 + 0.10 * streamProgress();
            case 2 -> 0.80 + 0.20 * (featureTotal == 0 ? 1 : 1 - features.size() / (double) featureTotal);
            default -> 1;
        };
    }

    /** Surface height at the island centre once generation has finished. */
    int centerSurfaceY() {
        return world.getHighestBlockYAt(cx, cz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
    }

    @Override
    public boolean tick(long deadline) {
        // pass 1: terrain + biome, one chunk at a time
        if (phase == 0) {
            if (!stream(deadline, this::terrainChunk)) {
                return false;
            }
            phase = 1;
            resetStream();
            decoRandom = new Random(seed * 6364136223846793005L + 1442695040888963407L);
        }
        // pass 2: ground decorations and snow layer (only touch their own column)
        if (phase == 1) {
            if ((t.decorations.isEmpty() && t.topLayer == null) || stream(deadline, this::decorateChunk)) {
                phase = 2;
                buildFeatures();
            } else {
                return false;
            }
        }
        // pass 3: trees, ruins, throne; each loads only the chunks around it
        if (phase == 2) {
            while (!features.isEmpty()) {
                Feature f = features.peek();
                int wx = cx + f.dx(), wz = cz + f.dz();
                if (!requestArea(wx - f.reach(), wz - f.reach(), wx + f.reach(), wz + f.reach())) {
                    return false; // loading
                }
                features.poll().action().run();
                trimTickets(64);
                if (System.nanoTime() > deadline) {
                    return false;
                }
            }
            phase = 3;
            trimTickets(0);
        }
        return true;
    }

    private void terrainChunk(int chunkX, int chunkZ) {
        int bx = chunkX << 4, bz = chunkZ << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int dx = bx + x - cx, dz = bz + z - cz;
                if (Math.abs(dx) <= radius && Math.abs(dz) <= radius) {
                    column(dx, dz);
                    if (t.biome != null) {
                        biomeColumn(dx, dz);
                    }
                }
            }
        }
        if (t.biome != null) {
            refreshChunk(chunkX, chunkZ);
        }
    }

    private void decorateChunk(int chunkX, int chunkZ) {
        int bx = chunkX << 4, bz = chunkZ << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int dx = bx + x - cx, dz = bz + z - cz;
                if (!inGrid(dx, dz)) {
                    continue;
                }
                if (!t.decorations.isEmpty()) {
                    // cactus checks its neighbours: skip the chunk border so no other chunk gets loaded
                    decorateColumn(dx, dz, decoRandom, x > 0 && x < 15 && z > 0 && z < 15);
                }
                if (t.topLayer != null) {
                    topLayerColumn(dx, dz);
                }
            }
        }
    }

    // ------------------------------------------------------------------ terrain

    private static double smooth(double v) {
        v = Math.max(0, Math.min(1, v));
        return v * v * (3 - 2 * v);
    }

    private static double lerp(double a, double b, double w) {
        return a + (b - a) * w;
    }

    private int index(int dx, int dz) {
        return (dz + radius) * n + (dx + radius);
    }

    private void column(int dx, int dz) {
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (dist > radius) {
            return;
        }
        int idx = index(dx, dz);
        int wx = cx + dx, wz = cz + dz;
        double d = dist / landR;
        double scale = 1.0 + t.coastVariation * shape.noise(wx, wz, 2.0, 0.5, true);
        double tt = 1.0 - d / scale;
        int floor = world.getHighestBlockYAt(wx, wz, HeightMap.OCEAN_FLOOR);

        if (tt <= 0) {
            // underwater shelf sloping from the shore down to the existing sea floor at the island border
            double edge = (radius / landR) / scale;
            double u = Math.min(1, -tt / Math.max(0.05, edge - 1));
            int target = sea - 2 - (int) Math.round(Math.pow(u, 0.85) * Math.max(0, sea - 2 - floor));
            if (u < 1 && target > floor) {
                for (int y = Math.max(floor + 1, minY); y <= target; y++) {
                    world.getBlockAt(wx, y, wz).setType(t.underwater.pick(rng), false);
                }
                top[idx] = target;
                kind[idx] = SLOPE;
                seaLife(wx, target, wz);
            }
            if (t.frozenShore && u < 0.3 && detail.noise(wx, wz, 2.0, 0.5, true) > -0.15) {
                Block surface = world.getBlockAt(wx, sea - 1, wz);
                if (surface.getType() == Material.WATER) {
                    surface.setType(Material.ICE, false);
                }
            }
            return;
        }

        double hill = detail.noise(wx, wz, 2.0, 0.5, true) * 0.5 + 0.5;
        double coneT = t.volcano ? Math.max(0, 1 - d / t.coneSize) : 0;
        if (coneT > 0) {
            hill = lerp(hill, 0.5, Math.min(1, coneT * 2));
        }
        double s = smooth(tt / 0.6);
        double amp = maxH;
        double region = 0.5;
        if (regions) {
            // large-scale regions: flat plains (good building ground) next to hills and mountains
            region = shape.noise(wx + 7919, wz - 7919, 2.0, 0.5, true) * 0.5 + 0.5;
            amp *= 0.3 + 0.9 * smooth(region);
        }
        double elev = 1 + s * amp * (0.3 + 0.7 * hill);
        if (terrain.mountains && t.mountainScale > 0 && terrain.mountainHeight > 0) {
            // ridged noise: sharp crests; only in "mountain" regions and away from the coast
            double ridge = 1 - Math.abs(ridges.noise(wx * terrain.mountainFrequency, wz * terrain.mountainFrequency, 2.0, 0.5, true));
            double mask = regions ? smooth((region - 0.45) / 0.35) : 0.45;
            double size = Math.min(1, radius / 250.0); // small islands get small mountains
            elev += Math.pow(ridge, 2.4) * terrain.mountainHeight * t.mountainScale * size * mask * smooth((tt - 0.12) / 0.45);
        }
        byte k = (elev - 1 < t.beachHeight || tt < 0.04) ? BEACH : LAND;
        if (coneT > 0) {
            double cone = coneH * Math.pow(coneT, 1.4);
            if (d < t.craterSize) {
                double rim = coneH * Math.pow(1 - t.craterSize / t.coneSize, 1.4);
                double q = d / t.craterSize;
                cone = rim - t.craterDepth * (1 - q * q);
                k = CRATER;
            } else if (cone > 1.5) {
                k = CONE;
            }
            elev += cone;
        }
        if (t.plaza) {
            double plazaSize = Math.min(0.62, t.plazaSize);
            double pe = 1 + t.plazaHeight;
            if (d < plazaSize) {
                elev = pe;
                k = PLAZA;
            } else if (d < plazaSize + 0.15) {
                elev = lerp(pe, elev, smooth((d - plazaSize) / 0.15));
                if (elev - 1 >= t.beachHeight && tt >= 0.04) {
                    k = LAND;
                }
            }
        }
        int topY = Math.min(maxY - 40, sea - 1 + Math.max(1, (int) Math.round(elev)));
        if (k == LAND && terrain.mountains && topY - sea >= terrain.rockAbove + (int) Math.round(hill * 4)) {
            k = MOUNTAIN; // bare rock (no trees or plants)
        }
        if (floor >= topY && k != CRATER) {
            // existing terrain is already higher: leave it alone but remember it so features can use it
            top[idx] = floor;
            kind[idx] = k == BEACH ? BEACH : LAND;
            landCount++;
            return;
        }
        for (int y = Math.max(floor + 1, minY); y <= topY; y++) {
            int depth = topY - y;
            Material m;
            if (depth == 0) {
                m = switch (k) {
                    case BEACH -> t.beach.pick(rng);
                    case CONE, CRATER -> t.coneBlocks.pick(rng);
                    case PLAZA -> t.plazaBlocks.pick(rng);
                    case MOUNTAIN -> topY - sea >= terrain.snowAbove ? Material.SNOW_BLOCK : terrain.rock.pick(rng);
                    default -> t.surface.pick(rng);
                };
            } else if (depth < 4) {
                m = switch (k) {
                    case BEACH -> t.beach.pick(rng);
                    case CONE, CRATER -> t.coneBlocks.pick(rng);
                    case PLAZA -> t.core.pick(rng);
                    case MOUNTAIN -> terrain.rock.pick(rng);
                    default -> t.subsurface.pick(rng);
                };
            } else if (t.oreChance > 0 && rng.nextDouble() < t.oreChance) {
                m = t.ores.pick(rng);
            } else {
                m = t.core.pick(rng);
            }
            world.getBlockAt(wx, y, wz).setType(m, false);
        }
        // the crater lake is filled after the terrain (fillCrater), once the real rim height is known
        top[idx] = Math.max(topY, floor);
        kind[idx] = k;
        if (k == LAND || k == MOUNTAIN) {
            landCount++;
        }
        if (k == MOUNTAIN && topY - sea >= terrain.snowAbove + 2) {
            world.getBlockAt(wx, topY + 1, wz).setType(Material.SNOW, false); // snow cap
        }
        carveCaves(wx, wz, floor, topY, tt, k);
    }

    /**
     * Caves: "spaghetti" tunnels where two 3D noise fields are both near zero, plus big caverns deep down.
     * Only inland (never near the coast). They reach well below sea level into the rock under the island;
     * there a block next to water (or lava) stays solid, so the sea never floods in.
     */
    private void carveCaves(int wx, int wz, int floor, int topY, double tt, byte k) {
        if (!terrain.caves || radius < terrain.caveMinRadius || (k != LAND && k != MOUNTAIN) || tt < 0.14) {
            return;
        }
        double scale = 1.0 / (24.0 * terrain.caveSize);
        double width = 0.085 * terrain.caveSize;
        boolean entrance = terrain.caveEntrances && caveGate.noise(wx * 0.02, wz * 0.02) > 0.62;
        double inland = smooth((tt - 0.14) / 0.3); // deeper caves further from the coast
        int low = Math.max(minY + 6, sea - (int) Math.round(terrain.caveDepth * (0.35 + 0.65 * inland)));
        int high = entrance ? topY : topY - 5;
        int lastCarved = Integer.MIN_VALUE, runStart = Integer.MIN_VALUE;
        for (int y = low; y <= high; y++) {
            boolean open = false;
            double a = caveA.noise(wx * scale, y * scale * 1.4, wz * scale);
            if (Math.abs(a) <= width) {
                open = Math.abs(caveB.noise(wx * scale, y * scale * 1.4, wz * scale)) <= width;
            }
            if (!open && terrain.caverns && y > low + 2 && y < topY - 8 && inland > 0.3) {
                open = caveRoom.noise(wx / 38.0, y / 15.0, wz / 38.0) > 0.62;
            }
            if (!open) {
                continue;
            }
            // Near the surface only open a column that continues a shaft from deep below: an entrance has
            // to lead into the caves, never a shallow random pit in the grass.
            if (y > topY - 5 && (lastCarved != y - 1 || runStart > topY - 8)) {
                continue;
            }
            Block block = world.getBlockAt(wx, y, wz);
            Material type = block.getType();
            if (!type.isSolid() || type == Material.BEDROCK) {
                if (type.isAir()) {
                    if (lastCarved != y - 1) {
                        runStart = y;
                    }
                    lastCarved = y; // already open (e.g. a cavern): the shaft continues
                }
                continue;
            }
            if (y <= floor + 1 && (touchesFluid(wx, y, wz) || world.getBlockAt(wx, y + 1, wz).getType().hasGravity())) {
                continue; // under the old sea floor: never open into water or under loose sand
            }
            block.setType(y <= low + 1 && t.volcano ? Material.LAVA : Material.CAVE_AIR, false);
            if (lastCarved != y - 1) {
                runStart = y;
            }
            lastCarved = y;
        }
    }

    private boolean touchesFluid(int x, int y, int z) {
        return fluid(world.getBlockAt(x + 1, y, z)) || fluid(world.getBlockAt(x - 1, y, z))
                || fluid(world.getBlockAt(x, y, z + 1)) || fluid(world.getBlockAt(x, y, z - 1))
                || fluid(world.getBlockAt(x, y + 1, z)) || fluid(world.getBlockAt(x, y - 1, z));
    }

    private boolean fluid(Block b) {
        Material m = b.getType();
        if (m == Material.LAVA) {
            return !t.volcano;
        }
        return m == Material.WATER || m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS
                || m == Material.TALL_SEAGRASS || m == Material.BUBBLE_COLUMN
                || (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged());
    }

    private static final Material[] CORALS = {Material.TUBE_CORAL, Material.BRAIN_CORAL, Material.BUBBLE_CORAL,
            Material.FIRE_CORAL, Material.HORN_CORAL, Material.TUBE_CORAL_FAN, Material.BRAIN_CORAL_FAN,
            Material.BUBBLE_CORAL_FAN, Material.FIRE_CORAL_FAN, Material.HORN_CORAL_FAN};
    private static final Material[] CORAL_BLOCKS = {Material.TUBE_CORAL_BLOCK, Material.BRAIN_CORAL_BLOCK,
            Material.BUBBLE_CORAL_BLOCK, Material.FIRE_CORAL_BLOCK, Material.HORN_CORAL_BLOCK};

    /** Coral, seagrass and kelp on the underwater slope (only where the water is deep enough). */
    private void seaLife(int wx, int floorTop, int wz) {
        int depth = sea - 1 - floorTop;
        if (depth < 2) {
            return;
        }
        Block above = world.getBlockAt(wx, floorTop + 1, wz);
        if (above.getType() != Material.WATER) {
            return;
        }
        double roll = rng.nextDouble();
        if (roll < t.coral) {
            if (rng.nextInt(4) == 0 && depth >= 3) {
                above.setType(CORAL_BLOCKS[rng.nextInt(CORAL_BLOCKS.length)], false); // a small coral mound
                Block top2 = world.getBlockAt(wx, floorTop + 2, wz);
                if (top2.getType() == Material.WATER) {
                    top2.setType(CORALS[rng.nextInt(CORALS.length)], false);
                }
            } else {
                above.setType(CORALS[rng.nextInt(CORALS.length)], false);
            }
        } else if (roll < t.coral + t.kelp && depth >= 4) {
            int h = 1 + rng.nextInt(Math.max(1, depth - 2));
            for (int i = 0; i < h; i++) {
                world.getBlockAt(wx, floorTop + 1 + i, wz).setType(i == h - 1 ? Material.KELP : Material.KELP_PLANT, false);
            }
        } else if (roll < t.coral + t.kelp + t.seagrass) {
            above.setType(Material.SEAGRASS, false);
        }
    }

    private void biomeColumn(int dx, int dz) {
        int wx = cx + dx, wz = cz + dz;
        if ((wx & 3) != 0 || (wz & 3) != 0 || (long) dx * dx + (long) dz * dz > (long) (radius + 3) * (radius + 3)) {
            return;
        }
        // only above dry land: water keeps the ocean biome (otherwise square patches of different water colour)
        byte k = kindAt(dx, dz);
        if (k == NONE || k == SLOPE) {
            return;
        }
        int from = Math.max(minY, sea - 24), to = Math.min(maxY, sea + maxElevation + 32);
        for (int y = from; y <= to; y += 4) {
            world.setBiome(wx, y, wz, t.biome);
        }
    }

    // ------------------------------------------------------------------ features

    private void buildFeatures() {
        features = new ArrayDeque<>();
        Random r = new Random(seed * 6364136223846793005L + 1442695040888963407L);
        List<Feature> list = new ArrayList<>();
        if (t.plaza && t.throne) {
            list.add(new Feature(0, 0, 10, this::buildThrone));
        }
        if (t.fort) {
            list.add(new Feature(0, 0, 18, this::buildFort));
        }
        if (t.volcano) {
            int craterR = (int) Math.ceil(t.craterSize * landR);
            list.add(new Feature(0, 0, craterR + 4, () -> fillCrater(craterR, new Random(seed * 53 + 7))));
        }
        if (t.plaza && t.town) {
            planTown(new Random(seed * 41 + 5), list); // before trees: roads and houses reserve their ground
        }
        planLakes(new Random(seed * 59 + 3), list);
        planShipwreck(new Random(seed * 67 + 9), list);
        if (t.ruinDensity > 0 && !t.ruinBlocks.isEmpty()) {
            int ruins = Math.max(1, (int) Math.round(landCount / 10000.0 * t.ruinDensity));
            for (int i = 0; i < ruins; i++) {
                int[] spot = landSpot(r, 12, false);
                if (spot != null) {
                    long ruinSeed = r.nextLong();
                    list.add(new Feature(spot[0], spot[1], 8, () -> buildRuin(spot[0], spot[1], new Random(ruinSeed))));
                }
            }
        }
        if (t.treeDensity > 0 && !t.trees.isEmpty()) {
            int trees = (int) Math.round(landCount / 100.0 * t.treeDensity);
            for (int i = 0; i < trees; i++) {
                String name = t.trees.pick(r);
                boolean palm = "PALM".equals(name);
                int[] spot = landSpot(r, 8, palm && t.palmsOnBeach);
                if (spot != null) {
                    long treeSeed = r.nextLong();
                    list.add(new Feature(spot[0], spot[1], palm ? 5 : 8,
                            () -> plantTree(spot[0], spot[1], name, new Random(treeSeed))));
                }
            }
        }
        if (terrain.veins && terrain.veinsPer1000 > 0) {
            int veins = (int) Math.round(landCount / 1000.0 * terrain.veinsPer1000);
            for (int i = 0; i < veins; i++) {
                int[] spot = null;
                for (int a = 0; a < 6 && spot == null; a++) {
                    int vx = r.nextInt(n) - radius, vz = r.nextInt(n) - radius;
                    byte vk = kindAt(vx, vz);
                    if (vk == LAND || vk == MOUNTAIN || vk == BUILT || vk == PLAZA) {
                        spot = new int[]{vx, vz};
                    }
                }
                if (spot != null) {
                    long veinSeed = r.nextLong();
                    int vx = spot[0], vz = spot[1];
                    list.add(new Feature(vx, vz, 3, () -> buildVein(vx, vz, new Random(veinSeed))));
                }
            }
        }
        // work through them chunk by chunk, so neighbouring features reuse loaded chunks
        list.sort(java.util.Comparator.comparingInt((Feature f) -> (cz + f.dz()) >> 4).thenComparingInt(f -> (cx + f.dx()) >> 4));
        features.addAll(list);
        featureTotal = features.size();
    }

    // ------------------------------------------------------------------ volcano crater

    /**
     * Fills the crater with lava up to just below the LOWEST point of its rim, so the lake is always
     * walled in, then lets a few streams spill over the rim and run down the volcano.
     */
    private void fillCrater(int craterR, Random r) {
        int minRim = Integer.MAX_VALUE, lowestFloor = Integer.MAX_VALUE;
        List<int[]> rimCols = new ArrayList<>();
        int reach = craterR + 3;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (!inGrid(dx, dz)) {
                    continue;
                }
                byte k = kind[index(dx, dz)];
                if (k == CRATER) {
                    lowestFloor = Math.min(lowestFloor, top[index(dx, dz)]);
                    continue;
                }
                // a rim column: not crater itself, but touching a crater column
                boolean touches = false;
                for (int[] d : DIRS) {
                    if (kindAt(dx + d[0], dz + d[1]) == CRATER) {
                        touches = true;
                        break;
                    }
                }
                if (touches && k != NONE && k != SLOPE) {
                    minRim = Math.min(minRim, top[index(dx, dz)]);
                    rimCols.add(new int[]{dx, dz});
                }
            }
        }
        if (minRim == Integer.MAX_VALUE || lowestFloor == Integer.MAX_VALUE) {
            return;
        }
        int level = Math.min(lavaY, minRim - 1);
        if (level <= lowestFloor) {
            return;
        }
        for (int dx = -craterR; dx <= craterR; dx++) {
            for (int dz = -craterR; dz <= craterR; dz++) {
                if (kindAt(dx, dz) != CRATER) {
                    continue;
                }
                for (int y = top[index(dx, dz)] + 1; y <= level; y++) {
                    world.getBlockAt(cx + dx, y, cz + dz).setType(t.craterFluid, false);
                }
            }
        }
        // lava streams: a source on the rim, with physics, so it really flows down the slope
        if (t.craterFluid == Material.LAVA && !rimCols.isEmpty()) {
            java.util.Collections.shuffle(rimCols, r);
            int streams = Math.min(t.lavaStreams, rimCols.size());
            for (int i = 0; i < streams; i++) {
                int[] c = rimCols.get(i);
                int y = top[index(c[0], c[1])] + 1;
                Block b = world.getBlockAt(cx + c[0], y, cz + c[1]);
                if (b.isEmpty()) {
                    b.setType(Material.LAVA, true);
                }
            }
        }
    }

    // ------------------------------------------------------------------ lakes & shipwrecks

    private void planLakes(Random r, List<Feature> list) {
        int lakes = (int) Math.round(landCount / 10000.0 * t.lakeDensity);
        for (int i = 0; i < lakes; i++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                int dx = r.nextInt(n) - radius, dz = r.nextInt(n) - radius;
                int lr = 3 + r.nextInt(4);
                if (!flatLand(dx, dz, lr + 1, 1)) {
                    continue;
                }
                for (int ox = -lr - 1; ox <= lr + 1; ox++) {
                    for (int oz = -lr - 1; oz <= lr + 1; oz++) {
                        if (ox * ox + oz * oz <= (lr + 1) * (lr + 1)) {
                            kind[index(dx + ox, dz + oz)] = BUILT; // no trees in or right next to the water
                        }
                    }
                }
                long lakeSeed = r.nextLong();
                list.add(new Feature(dx, dz, lr + 2, () -> buildLake(dx, dz, lr, new Random(lakeSeed))));
                break;
            }
        }
    }

    /** True when the square of land around dx/dz is dry land that varies at most "maxDiff" blocks in height. */
    private boolean flatLand(int dx, int dz, int half, int maxDiff) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int ox = -half; ox <= half; ox++) {
            for (int oz = -half; oz <= half; oz++) {
                int px = dx + ox, pz = dz + oz;
                if (!inGrid(px, pz) || kind[index(px, pz)] != LAND) {
                    return false;
                }
                lo = Math.min(lo, top[index(px, pz)]);
                hi = Math.max(hi, top[index(px, pz)]);
            }
        }
        return hi - lo <= maxDiff;
    }

    private void buildLake(int dx, int dz, int lr, Random r) {
        int level = Integer.MAX_VALUE;
        for (int ox = -lr; ox <= lr; ox++) {
            for (int oz = -lr; oz <= lr; oz++) {
                if (ox * ox + oz * oz <= lr * lr) {
                    level = Math.min(level, top[index(dx + ox, dz + oz)]);
                }
            }
        }
        Material[] bottom = {Material.SAND, Material.CLAY, Material.GRAVEL, Material.DIRT};
        for (int ox = -lr; ox <= lr; ox++) {
            for (int oz = -lr; oz <= lr; oz++) {
                double d = Math.sqrt(ox * ox + oz * oz);
                if (d > lr) {
                    continue;
                }
                int x = cx + dx + ox, z = cz + dz + oz;
                int depth = d < lr - 2 ? 3 : d < lr - 1 ? 2 : 1;
                for (int y = top[index(dx + ox, dz + oz)] + 4; y > level; y--) {
                    set(x, y, z, Material.AIR); // plants and the higher ground on the rim
                }
                for (int y = level; y > level - depth; y--) {
                    set(x, y, z, Material.WATER);
                }
                set(x, level - depth, z, bottom[r.nextInt(bottom.length)]);
                for (int y = level - depth - 1; y >= level - depth - 3; y--) {
                    if (!world.getBlockAt(x, y, z).getType().isSolid()) {
                        set(x, y, z, Material.STONE); // seal: never leak into a cave below
                    }
                }
                if (r.nextDouble() < 0.07 && depth >= 2) {
                    set(x, level + 1, z, Material.LILY_PAD);
                }
            }
        }
    }

    private void planShipwreck(Random r, List<Feature> list) {
        if (t.shipwreckChance <= 0 || r.nextDouble() >= t.shipwreckChance) {
            return;
        }
        for (int attempt = 0; attempt < 40; attempt++) {
            int dx = r.nextInt(n) - radius, dz = r.nextInt(n) - radius;
            if (kindAt(dx, dz) != BEACH) {
                continue;
            }
            int[] dir = DIRS[r.nextInt(DIRS.length)];
            long wreckSeed = r.nextLong();
            list.add(new Feature(dx, dz, 7, () -> buildShipwreck(dx, dz, dir, new Random(wreckSeed))));
            return;
        }
    }

    /** A broken hull half buried in the beach: gaps in the planks, a snapped mast and a barrel of loot. */
    private void buildShipwreck(int dx, int dz, int[] dir, Random r) {
        int base = top[index(dx, dz)];
        int ax = dir[0], az = dir[1];       // along the ship
        int px = -dir[1], pz = dir[0];       // across the ship
        for (int i = -5; i <= 5; i++) {
            int half = Math.abs(i) >= 4 ? 1 : 2;
            for (int w = -half; w <= half; w++) {
                int x = cx + dx + ax * i + px * w, z = cz + dz + az * i + pz * w;
                boolean side = Math.abs(w) == half || Math.abs(i) == 5;
                // keel and floor sit in the sand
                set(x, base, z, Math.abs(w) == 0 ? Material.STRIPPED_SPRUCE_LOG : Material.DARK_OAK_PLANKS);
                if (side) {
                    for (int y = 1; y <= 2; y++) {
                        if (r.nextDouble() < 0.75) { // broken planks
                            set(x, base + y, z, y == 2 && r.nextBoolean() ? Material.SPRUCE_SLAB : Material.SPRUCE_PLANKS);
                        }
                    }
                } else {
                    for (int y = 1; y <= 3; y++) {
                        Block b = world.getBlockAt(x, base + y, z);
                        if (b.getType() == Material.SAND || b.isReplaceable()) {
                            b.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }
        // snapped, leaning mast
        int mx = cx + dx, mz = cz + dz;
        for (int y = 1; y <= 6; y++) {
            set(mx + px * (y / 3), base + y, mz + pz * (y / 3), Material.SPRUCE_LOG);
        }
        set(mx + px * 2 + ax, base + 6, mz + pz * 2 + az, Material.SPRUCE_FENCE);
        set(mx + px * 2 - ax, base + 6, mz + pz * 2 - az, Material.WHITE_WOOL);
        // a barrel of salvage at the stern
        Block barrel = world.getBlockAt(cx + dx - ax * 3, base + 1, cz + dz - az * 3);
        barrel.setType(Material.BARREL, false);
        if (barrel.getState() instanceof org.bukkit.block.Barrel state) {
            org.bukkit.inventory.Inventory inv = state.getInventory();
            Material[] loot = {Material.GOLD_NUGGET, Material.STRING, Material.GUNPOWDER, Material.ROTTEN_FLESH,
                    Material.PAPER, Material.IRON_NUGGET, Material.COAL, Material.EMERALD};
            int rolls = 3 + r.nextInt(4);
            for (int k = 0; k < rolls; k++) {
                Material m = loot[r.nextInt(loot.length)];
                inv.setItem(r.nextInt(inv.getSize()), new org.bukkit.inventory.ItemStack(m, 1 + r.nextInt(m == Material.EMERALD ? 2 : 6)));
            }
            if (r.nextDouble() < 0.15) {
                inv.addItem(new org.bukkit.inventory.ItemStack(Material.NAUTILUS_SHELL));
            }
        }
    }

    // ------------------------------------------------------------------ capital town

    private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    /** Plans the capital's town: 4 roads to the coast with lamps, houses along them, piers and a lighthouse. */
    // ------------------------------------------------------------------ capital: harbour town

    /**
     * The capital: a plaza with the throne, a decorated ring, planters, eight market stalls (the hub module
     * puts shopkeepers in them), a bounty board, the founders' monument and a parkour tower; paved roads to
     * the coast that cross the beach as a boardwalk and end in piers; houses along the roads; palms on the beach.
     * Stall/board/monument positions must match hub.yml.
     */
    private void planTown(Random r, List<Feature> list) {
        int plazaR = (int) Math.round(Math.min(0.62, t.plazaSize) * landR);
        // lamp posts around the plaza
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i + Math.PI / 8;
            int lx = (int) Math.round(Math.cos(a) * (plazaR - 2)), lz = (int) Math.round(Math.sin(a) * (plazaR - 2));
            if (kindAt(lx, lz) == PLAZA) {
                list.add(new Feature(lx, lz, 1, () -> lamp(lx, lz)));
            }
        }
        if (plazaR >= 24) {
            list.add(new Feature(0, 0, 12, this::buildPlazaRing));
            int[][] planters = {{9, 9}, {-9, 9}, {9, -9}, {-9, -9}};
            for (int[] p : planters) {
                int px = p[0], pz = p[1];
                long ps = r.nextLong();
                list.add(new Feature(px, pz, 6, () -> buildPlanter(px, pz, new Random(ps))));
            }
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(22.5 + 45 * i);
                int sx = (int) Math.round(Math.cos(a) * 18), sz = (int) Math.round(Math.sin(a) * 18);
                int stall = i;
                if (kindAt(sx, sz) == PLAZA) {
                    list.add(new Feature(sx, sz, 5, () -> buildStall(sx, sz, stall)));
                }
            }
        }
        if (plazaR >= 31) {
            if (kindAt(-27, 0) == PLAZA) {
                list.add(new Feature(-27, 0, 6, () -> buildBountyBoard(-27, 0)));
            }
            if (kindAt(27, 0) == PLAZA) {
                list.add(new Feature(27, 0, 6, () -> buildMonument(27, 0)));
            }
        }
        if (plazaR >= 24 && kindAt(0, -18) == PLAZA) {
            list.add(new Feature(0, -18, 6, () -> buildFountain(0, -18)));
        }
        if (plazaR >= 24) {
            planPlazaDecor(new Random(seed * 113 + 9), list, plazaR);
        }
        if (plazaR >= 38 && kindAt(0, -31) == PLAZA) {
            list.add(new Feature(0, -31, 8, () -> buildParkour(0, -31)));
        }

        List<int[]> houseSpots = new ArrayList<>();
        boolean lighthouse = false;
        for (int[] d : DIRS) {
            int step = plazaR - 1;
            int lastX = 0, lastZ = 0;
            List<int[]> road = new ArrayList<>();
            int landEnd = step;
            while (step < radius) {
                int rx = d[0] * step, rz = d[1] * step;
                byte k = kindAt(rx, rz);
                if (k != LAND && k != PLAZA && k != MOUNTAIN && k != BUILT && k != BEACH) {
                    break;
                }
                if (k != BEACH) {
                    landEnd = step;
                }
                for (int w = -1; w <= 1; w++) {
                    int px = rx + (d[1] != 0 ? w : 0), pz = rz + (d[0] != 0 ? w : 0);
                    if (inGrid(px, pz) && kind[index(px, pz)] != PLAZA) {
                        boolean beach = kind[index(px, pz)] == BEACH;
                        kind[index(px, pz)] = BUILT;
                        road.add(new int[]{px, pz, beach ? 1 : 0});
                    }
                }
                lastX = rx;
                lastZ = rz;
                step++;
            }
            // road in segments, so each feature only needs a few chunks; over the beach it is a boardwalk
            for (int i = 0; i < road.size(); i += 24) {
                List<int[]> seg = road.subList(i, Math.min(road.size(), i + 24));
                int[] mid = seg.get(seg.size() / 2);
                list.add(new Feature(mid[0], mid[1], 12, () -> paveRoad(seg, d)));
            }
            // houses on both sides (only on land, not on the beach), every ~12 blocks
            for (int off = plazaR + 5; off < landEnd - 6; off += 10) {
                for (int side = -1; side <= 1; side += 2) {
                    for (int row = 0; row <= 1; row++) {
                        houseSpots.add(new int[]{off, side, d[0], d[1], row}); // sized and placed below
                    }
                }
            }
            // pier where the boardwalk meets the sea, lighthouse at the first one
            int px = lastX + d[0], pz = lastZ + d[1];
            int startY = inGrid(lastX, lastZ) ? top[index(lastX, lastZ)] : sea;
            int[] dir = d;
            list.add(new Feature(px + d[0] * 10, pz + d[1] * 10, 13, () -> buildPier(px, pz, dir, startY)));
            if (!lighthouse) {
                lighthouse = true;
                int lx = lastX + d[1] * 6, lz = lastZ + d[0] * 6;
                if (kindAt(lx, lz) == LAND || kindAt(lx, lz) == BEACH) {
                    list.add(new Feature(lx, lz, 4, () -> buildLighthouse(lx, lz)));
                }
            }
        }
        java.util.Collections.shuffle(houseSpots, r);
        int built = 0, taverns = 0, warehouses = 0;
        for (int[] h : houseSpots) {
            if (built >= t.townHouses) {
                break;
            }
            // size: small cottages, normal houses and big buildings (some are a tavern or a warehouse)
            double roll = r.nextDouble();
            int along, across, building = 0;
            if (roll < 0.3) {
                along = 2;
                across = 2;
            } else if (roll < 0.75) {
                along = 2 + r.nextInt(2);
                across = 2 + r.nextInt(2);
            } else {
                along = 3 + r.nextInt(2);
                across = 3 + r.nextInt(2);
                building = taverns < 2 && r.nextBoolean() ? 1 : warehouses < 2 && r.nextBoolean() ? 2 : 0;
            }
            int[] spot = place(h, along, across);
            if (!footprintFree(spot[0], spot[1], spot[2], spot[3])) {
                along = 2;
                across = 2;
                building = 0;
                spot = place(h, along, across);
                if (!footprintFree(spot[0], spot[1], spot[2], spot[3])) {
                    continue;
                }
            }
            int hx = spot[0], hz = spot[1], w = spot[2], l = spot[3];
            for (int ox = -w - 1; ox <= w + 1; ox++) {
                for (int oz = -l - 1; oz <= l + 1; oz++) {
                    kind[index(hx + ox, hz + oz)] = BUILT;
                }
            }
            long houseSeed = r.nextLong();
            int doorDx, doorDz; // the door faces the road
            if (h[2] != 0) {
                doorDx = 0;
                doorDz = -h[1];
            } else {
                doorDx = -h[1];
                doorDz = 0;
            }
            if (building == 1) {
                taverns++;
            } else if (building == 2) {
                warehouses++;
            }
            int kindOfBuilding = building;
            list.add(new Feature(hx, hz, 8, () -> buildHouse(hx, hz, w, l, doorDx, doorDz, kindOfBuilding, new Random(houseSeed))));
            built++;
        }
        // palms along the beach
        int palms = (int) Math.round(2 * Math.PI * landR / 11.0);
        for (int i = 0; i < palms; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            int skip = r.nextInt(3);
            for (int dist = (int) (landR * 0.8); dist < radius; dist++) {
                int dx = (int) Math.round(Math.cos(a) * dist), dz = (int) Math.round(Math.sin(a) * dist);
                if (kindAt(dx, dz) == BEACH && top[index(dx, dz)] >= sea) {
                    if (skip-- > 0) {
                        continue;
                    }
                    kind[index(dx, dz)] = BUILT;
                    long ps = r.nextLong();
                    list.add(new Feature(dx, dz, 5, () -> {
                        int y = top[index(dx, dz)] + 1;
                        if (world.getBlockAt(cx + dx, y, cz + dz).isEmpty()) {
                            buildPalm(cx + dx, y, cz + dz, new Random(ps));
                        }
                    }));
                    break;
                }
                if (kindAt(dx, dz) == NONE || kindAt(dx, dz) == SLOPE) {
                    break;
                }
            }
        }
    }

    /**
     * Centre and half sizes {x, z, w, l} of a building beside a road: spot = {offset along the road, side,
     * road dx, road dz, row}. Row 0 stands right next to the road, row 1 behind it with a walkway between.
     */
    private static int[] place(int[] spot, int along, int across) {
        int perp = spot[4] == 0 ? 3 + across : 15 + across;
        if (spot[2] != 0) { // road along x: buildings beside it in z
            return new int[]{spot[2] * spot[0], spot[1] * perp, along, across};
        }
        return new int[]{spot[1] * perp, spot[3] * spot[0], across, along};
    }

    private boolean footprintFree(int cxr, int czr, int w, int l) {
        int minY = Integer.MAX_VALUE, maxYv = Integer.MIN_VALUE;
        for (int ox = -w - 1; ox <= w + 1; ox++) {
            for (int oz = -l - 1; oz <= l + 1; oz++) {
                int px = cxr + ox, pz = czr + oz;
                if (!inGrid(px, pz) || kind[index(px, pz)] != LAND) {
                    return false;
                }
                minY = Math.min(minY, top[index(px, pz)]);
                maxYv = Math.max(maxYv, top[index(px, pz)]);
            }
        }
        return maxYv - minY <= 3;
    }

    private void lamp(int dx, int dz) {
        int x = cx + dx, z = cz + dz, y = top[index(dx, dz)] + 1;
        set(x, y, z, Material.SPRUCE_FENCE);
        set(x, y + 1, z, Material.SPRUCE_FENCE);
        set(x, y + 2, z, Material.LANTERN);
    }

    private void paveRoad(List<int[]> seg, int[] dir) {
        for (int[] c : seg) {
            int x = cx + c[0], z = cz + c[1], y = top[index(c[0], c[1])];
            boolean beach = c.length > 2 && c[2] == 1;
            set(x, y, z, beach ? Material.SPRUCE_PLANKS : t.roadBlocks.isEmpty() ? Material.STONE_BRICKS : t.roadBlocks.pick(rng));
            for (int up = 1; up <= 4; up++) {
                Block b = world.getBlockAt(x, y + up, z);
                if (!b.isEmpty()) {
                    b.setType(Material.AIR, false);
                }
            }
            if (beach) {
                continue;
            }
            // a lamp at the road edge every 9 blocks
            int along = dir[0] != 0 ? c[0] : c[1];
            int across = dir[0] != 0 ? c[1] : c[0];
            if (Math.abs(along) % 9 == 0 && Math.abs(across) == 1) {
                int lx = c[0] + (dir[1] != 0 ? Integer.signum(across) : 0), lz = c[1] + (dir[0] != 0 ? Integer.signum(across) : 0);
                if (inGrid(lx, lz) && (kind[index(lx, lz)] == LAND || kind[index(lx, lz)] == BUILT)) {
                    lamp(lx, lz);
                }
            }
        }
    }

    private static BlockFace face(int dx, int dz) {
        return dx > 0 ? BlockFace.EAST : dx < 0 ? BlockFace.WEST : dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void setData(int x, int y, int z, BlockData data) {
        if (y > minY && y < maxY) {
            world.getBlockAt(x, y, z).setBlockData(data, false);
        }
    }

    private BlockData stairs(Material m, BlockFace facing) {
        BlockData data = Bukkit.createBlockData(m);
        if (data instanceof Directional d) {
            d.setFacing(facing);
        }
        return data;
    }

    /**
     * A pier that starts at the end of the boardwalk (on top of the beach, never buried in sand) and steps
     * down to just above the water.
     */
    private void buildPier(int px, int pz, int[] d, int startY) {
        int prev = Math.max(sea, startY);
        BlockFace up = face(-d[0], -d[1]);
        for (int i = 0; i < 20; i++) {
            int deck = Math.max(sea, Math.max(sea, startY) - i);
            boolean step = deck < prev;
            int x = cx + px + d[0] * i, z = cz + pz + d[1] * i;
            for (int w = -1; w <= 1; w++) {
                int wx = x + (d[1] != 0 ? w : 0), wz = z + (d[0] != 0 ? w : 0);
                if (step) {
                    setData(wx, deck, wz, stairs(Material.SPRUCE_STAIRS, up));
                } else {
                    set(wx, deck, wz, Material.SPRUCE_PLANKS);
                }
                for (int yy = deck + 1; yy <= deck + 3; yy++) {
                    Block b = world.getBlockAt(wx, yy, wz);
                    if (!b.isEmpty() && !(Math.abs(w) == 1 && i % 4 == 0)) {
                        b.setType(Material.AIR, false); // dig the walkway through the beach sand
                    }
                }
                if (Math.abs(w) == 1 && i % 4 == 0) {
                    // posts down to the sea floor
                    for (int yy = deck - 1; yy > minY && !world.getBlockAt(wx, yy, wz).getType().isSolid(); yy--) {
                        world.getBlockAt(wx, yy, wz).setType(Material.SPRUCE_LOG, false);
                    }
                    set(wx, deck + 1, wz, Material.SPRUCE_FENCE);
                    set(wx, deck + 2, wz, i % 8 == 0 ? Material.LANTERN : Material.AIR);
                }
            }
            prev = deck;
        }
    }

    private static final Material[][] HOUSE_STYLES = {
            // wall, frame, roof stairs, roof ridge, foundation, floor
            {Material.WHITE_TERRACOTTA, Material.DARK_OAK_LOG, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS, Material.COBBLESTONE, Material.SPRUCE_PLANKS},
            {Material.SPRUCE_PLANKS, Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_STAIRS, Material.SPRUCE_PLANKS, Material.STONE_BRICKS, Material.OAK_PLANKS},
            {Material.BIRCH_PLANKS, Material.STRIPPED_OAK_LOG, Material.MANGROVE_STAIRS, Material.MANGROVE_PLANKS, Material.MOSSY_COBBLESTONE, Material.BIRCH_PLANKS},
            {Material.STONE_BRICKS, Material.SPRUCE_LOG, Material.DEEPSLATE_TILE_STAIRS, Material.DEEPSLATE_TILES, Material.COBBLED_DEEPSLATE, Material.DARK_OAK_PLANKS},
    };
    private static final Material[] BEDS = {Material.RED_BED, Material.BLUE_BED, Material.GREEN_BED, Material.YELLOW_BED, Material.CYAN_BED};

    /** A timber-framed house with a gabled roof, chimney, windows and a small interior (some have two floors). */
    private void buildHouse(int hx, int hz, int w, int l, int doorDx, int doorDz, int building, Random r) {
        int base = Integer.MIN_VALUE;
        for (int ox = -w; ox <= w; ox++) {
            for (int oz = -l; oz <= l; oz++) {
                base = Math.max(base, top[index(hx + ox, hz + oz)]);
            }
        }
        Material[] st = building == 2 ? HOUSE_STYLES[1] : HOUSE_STYLES[r.nextInt(HOUSE_STYLES.length)];
        Material wall = st[0], frame = st[1], roofStair = st[2], roofBlock = st[3], found = st[4], floor = st[5];
        // small cottages stay low; bigger houses often get a second floor; the tavern always does
        boolean twoStory = building == 1 || (building == 0 && w + l >= 5 && r.nextInt(5) < 2);
        int wallH = twoStory ? 7 : building == 2 ? 5 : 4;
        int x0 = cx + hx, z0 = cz + hz;
        for (int ox = -w; ox <= w; ox++) {
            for (int oz = -l; oz <= l; oz++) {
                int x = x0 + ox, z = z0 + oz;
                for (int y = top[index(hx + ox, hz + oz)]; y < base; y++) {
                    set(x, y, z, found);
                }
                boolean edgeX = Math.abs(ox) == w, edgeZ = Math.abs(oz) == l;
                set(x, base, z, edgeX || edgeZ ? found : floor);
                for (int y = base + 1; y <= base + wallH; y++) {
                    int h = y - base;
                    Material m;
                    if (edgeX && edgeZ) {
                        m = frame;
                    } else if (edgeX || edgeZ) {
                        int o = edgeX ? oz : ox, half = edgeX ? l : w;
                        boolean windowRow = h == 2 || (twoStory && h == 6);
                        if (twoStory && h == 4) {
                            m = frame;
                        } else if (windowRow && o != 0 && Math.abs(o) < half && Math.abs(o) % 2 == 1) {
                            m = Material.GLASS_PANE;
                        } else {
                            m = wall;
                        }
                    } else {
                        m = twoStory && h == 4 ? floor : Material.AIR;
                    }
                    set(x, y, z, m);
                }
            }
        }
        // gabled roof; the ridge runs along the longer side
        boolean alongZ = l >= w;
        int half = alongZ ? w : l, len = alongZ ? l : w;
        int ry = base + wallH + 1;
        for (int i = 0; i <= half + 1; i++) {
            int y = ry + i, lo = -(half + 1) + i, hi = (half + 1) - i;
            if (lo > hi) {
                break;
            }
            for (int along = -len - 1; along <= len + 1; along++) {
                if (lo == hi) {
                    roofSet(alongZ, x0, z0, lo, along, y, Bukkit.createBlockData(roofBlock));
                    continue;
                }
                roofSet(alongZ, x0, z0, lo, along, y, stairs(roofStair, alongZ ? BlockFace.EAST : BlockFace.SOUTH));
                roofSet(alongZ, x0, z0, hi, along, y, stairs(roofStair, alongZ ? BlockFace.WEST : BlockFace.NORTH));
                for (int c = lo + 1; c <= hi - 1; c++) {
                    if (Math.abs(along) == len && Math.abs(c) <= half) {
                        roofSet(alongZ, x0, z0, c, along, y, Bukkit.createBlockData(wall)); // gable end
                    } else if (Math.abs(along) < len) {
                        roofSet(alongZ, x0, z0, c, along, y, Bukkit.createBlockData(Material.AIR)); // attic
                    }
                }
            }
        }
        // chimney in a back corner (furnace at the bottom, campfire smoke on top)
        int chX = -(w - 1), chZ = -(l - 1);
        set(x0 + chX, base + 1, z0 + chZ, Material.FURNACE);
        for (int y = base + 2; y <= ry + half + 2; y++) {
            set(x0 + chX, y, z0 + chZ, Material.BRICKS);
        }
        set(x0 + chX, ry + half + 3, z0 + chZ, Material.CAMPFIRE);
        java.util.Set<Long> used = new java.util.HashSet<>();
        used.add(key(chX, chZ));
        used.add(key(doorDx * (w - 1), doorDz * (l - 1))); // keep the doorway free
        if (twoStory) {
            int ldX = -(w - 1), ldZ = l - 1; // ladder against the west wall
            for (int y = base + 1; y <= base + 4; y++) {
                setData(x0 + ldX, y, z0 + ldZ, stairs(Material.LADDER, BlockFace.EAST));
            }
            used.add(key(ldX, ldZ));
            set(x0, base + 5, z0, Material.LANTERN);
        }
        // door in the middle of the wall that faces the road
        int dx = x0 + doorDx * w, dz = z0 + doorDz * l;
        BlockFace facing = face(doorDx, doorDz);
        BlockData lower = Bukkit.createBlockData(Material.SPRUCE_DOOR);
        BlockData upper = Bukkit.createBlockData(Material.SPRUCE_DOOR);
        if (lower instanceof org.bukkit.block.data.type.Door dl && upper instanceof org.bukkit.block.data.type.Door du) {
            dl.setFacing(facing);
            dl.setHalf(Bisected.Half.BOTTOM);
            du.setFacing(facing);
            du.setHalf(Bisected.Half.TOP);
        }
        world.getBlockAt(dx, base + 1, dz).setBlockData(lower, false);
        world.getBlockAt(dx, base + 2, dz).setBlockData(upper, false);
        set(dx + doorDx, base + 3, dz + doorDz, Material.LANTERN);
        if (building == 1) {
            furnishTavern(x0, z0, base, w, l, doorDx, doorDz, used, r);
            return;
        }
        if (building == 2) {
            furnishWarehouse(x0, z0, base, w, l, doorDx, doorDz, used);
            return;
        }
        // interior: a bed, a crafting table with a lantern, a barrel
        int[][] corners = {{w - 1, l - 1}, {w - 1, -(l - 1)}, {-(w - 1), l - 1}, {-(w - 1), -(l - 1)}};
        boolean bed = false, table = false, barrel = false;
        for (int[] c : corners) {
            if (used.contains(key(c[0], c[1]))) {
                continue;
            }
            int footZ = c[1] - Integer.signum(c[1]);
            if (!bed && c[1] != 0 && footZ != 0 && !used.contains(key(c[0], footZ))) {
                BlockData head = Bukkit.createBlockData(BEDS[r.nextInt(BEDS.length)]);
                BlockData foot = head.clone();
                BlockFace bf = c[1] > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
                if (head instanceof org.bukkit.block.data.type.Bed bh && foot instanceof org.bukkit.block.data.type.Bed bt) {
                    bh.setPart(org.bukkit.block.data.type.Bed.Part.HEAD);
                    bh.setFacing(bf);
                    bt.setPart(org.bukkit.block.data.type.Bed.Part.FOOT);
                    bt.setFacing(bf);
                }
                setData(x0 + c[0], base + 1, z0 + c[1], head);
                setData(x0 + c[0], base + 1, z0 + footZ, foot);
                used.add(key(c[0], c[1]));
                used.add(key(c[0], footZ));
                bed = true;
            } else if (!table) {
                set(x0 + c[0], base + 1, z0 + c[1], Material.CRAFTING_TABLE);
                set(x0 + c[0], base + 2, z0 + c[1], Material.LANTERN);
                used.add(key(c[0], c[1]));
                table = true;
            } else if (!barrel) {
                set(x0 + c[0], base + 1, z0 + c[1], Material.BARREL);
                used.add(key(c[0], c[1]));
                barrel = true;
            }
        }
        if (!used.contains(key(0, 0))) {
            set(x0, base + 1, z0, Material.RED_CARPET);
        }
    }

    /** Tavern: a bar along the back wall with barrels, tables with chairs, lanterns, a sign by the door. */
    private void furnishTavern(int x0, int z0, int base, int w, int l, int doorDx, int doorDz, java.util.Set<Long> used, Random r) {
        // back wall = opposite the door
        int bx = -doorDx, bz = -doorDz;
        int lateralX = bz != 0 ? 1 : 0, lateralZ = bx != 0 ? 1 : 0;
        int depth = bx != 0 ? w : l, width = bx != 0 ? l : w;
        for (int i = -(width - 1); i <= width - 1; i++) {
            int x = x0 + bx * (depth - 1) + lateralX * i, z = z0 + bz * (depth - 1) + lateralZ * i;
            if (used.contains(key(x - x0, z - z0))) {
                continue;
            }
            set(x, base + 1, z, Material.BARREL); // behind the bar
            int cx2 = x - bx, cz2 = z - bz;
            if (!used.contains(key(cx2 - x0, cz2 - z0))) {
                set(cx2, base + 1, cz2, Material.SPRUCE_PLANKS); // the bar
                set(cx2, base + 2, cz2, i == 0 ? Material.BREWING_STAND : (i & 1) == 0 ? Material.LANTERN : Material.AIR);
            }
        }
        // two tables with chairs in the front half
        for (int t = -1; t <= 1; t += 2) {
            int tx = x0 + lateralX * t * Math.max(1, width - 2) + doorDx, tz = z0 + lateralZ * t * Math.max(1, width - 2) + doorDz;
            if (used.contains(key(tx - x0, tz - z0))) {
                continue;
            }
            set(tx, base + 1, tz, Material.SPRUCE_FENCE);
            set(tx, base + 2, tz, Material.OAK_PRESSURE_PLATE);
            for (int[] c : new int[][]{{lateralX, lateralZ}, {-lateralX, -lateralZ}}) {
                int sx = tx + c[0], sz = tz + c[1];
                if (!used.contains(key(sx - x0, sz - z0)) && world.getBlockAt(sx, base + 1, sz).isEmpty()) {
                    setData(sx, base + 1, sz, stairs(Material.SPRUCE_STAIRS, face(-c[0], -c[1])));
                }
            }
        }
        set(x0, base + wallH(base, x0, z0) , z0, Material.LANTERN);
        // a lantern on a post outside the door
        int ox = x0 + doorDx * (doorDx != 0 ? w + 1 : l + 1) + (doorDz != 0 ? 2 : 0), oz = z0 + doorDz * (doorDz != 0 ? l + 1 : w + 1) + (doorDx != 0 ? 2 : 0);
        set(ox, base + 1, oz, Material.SPRUCE_FENCE);
        set(ox, base + 2, oz, Material.SPRUCE_FENCE);
        set(ox, base + 3, oz, Material.LANTERN);
    }

    /** Height of the first floor's ceiling at a spot (first solid block above the floor), for a hanging light. */
    private int wallH(int base, int x, int z) {
        for (int up = 2; up <= 8; up++) {
            if (world.getBlockAt(x, base + up, z).getType().isSolid()) {
                return up - 1;
            }
        }
        return 3;
    }

    /** Warehouse: stacks of barrels and hay, a crafting table, a wide doorway. */
    private void furnishWarehouse(int x0, int z0, int base, int w, int l, int doorDx, int doorDz, java.util.Set<Long> used) {
        for (int ox = -(w - 1); ox <= w - 1; ox++) {
            for (int oz = -(l - 1); oz <= l - 1; oz++) {
                boolean aisle = doorDx != 0 ? oz == 0 : ox == 0;
                if (aisle || used.contains(key(ox, oz)) || ((ox + oz) & 1) != 0) {
                    continue;
                }
                set(x0 + ox, base + 1, z0 + oz, (ox * 7 + oz * 3 & 3) == 0 ? Material.HAY_BLOCK : Material.BARREL);
                if (((ox * 5 + oz) & 3) == 1) {
                    set(x0 + ox, base + 2, z0 + oz, Material.BARREL);
                }
            }
        }
        // widen the doorway to three blocks (no door)
        int dx = x0 + doorDx * w, dz = z0 + doorDz * l;
        for (int s = -1; s <= 1; s++) {
            int x = dx + (doorDz != 0 ? s : 0), z = dz + (doorDx != 0 ? s : 0);
            for (int up = 1; up <= 3; up++) {
                set(x, base + up, z, Material.AIR);
            }
        }
        set(x0, base + 4, z0, Material.LANTERN);
    }

    private static long key(int a, int b) {
        return ((long) a << 32) ^ (b & 0xffffffffL);
    }

    /** Roof helper: "across" is perpendicular to the ridge, "along" follows it. */
    private void roofSet(boolean alongZ, int x0, int z0, int across, int along, int y, BlockData data) {
        if (alongZ) {
            setData(x0 + across, y, z0 + along, data);
        } else {
            setData(x0 + along, y, z0 + across, data);
        }
    }

    /** A decorated stone ring around the throne. */
    private void buildPlazaRing() {
        for (int dx = -11; dx <= 11; dx++) {
            for (int dz = -11; dz <= 11; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d >= 9.5 && d < 11 && kindAt(dx, dz) == PLAZA) {
                    int seg = Math.floorMod((int) Math.floor(Math.atan2(dz, dx) / (Math.PI / 8)), 2);
                    set(cx + dx, top[index(dx, dz)], cz + dz, seg == 0 ? Material.POLISHED_DIORITE : Material.SMOOTH_STONE);
                }
            }
        }
    }

    // ------------------------------------------------------------------ plaza decoration

    /** True where plaza decoration must not go: the throne, stalls, fountain, parkour, signs and the paths. */
    private boolean plazaReserved(int dx, int dz) {
        if (Math.abs(dx) <= 2 || Math.abs(dz) <= 2) {
            return true; // the four paths from the throne to the roads
        }
        if (dx * dx + dz * dz < 13 * 13) {
            return true; // throne, ring and planters
        }
        int[][] spots = {{0, -18, 6}, {0, -31, 9}, {-27, 0, 5}, {27, 0, 5}};
        for (int[] s : spots) {
            if ((dx - s[0]) * (dx - s[0]) + (dz - s[1]) * (dz - s[1]) < s[2] * s[2]) {
                return true;
            }
        }
        for (int k = 0; k < 8; k++) {
            double a = Math.toRadians(22.5 + 45 * k);
            int sx = (int) Math.round(Math.cos(a) * 18), sz = (int) Math.round(Math.sin(a) * 18);
            if ((dx - sx) * (dx - sx) + (dz - sz) * (dz - sz) < 25) {
                return true;
            }
        }
        return kindAt(dx, dz) != PLAZA;
    }

    /** True when a whole square around dx/dz is free plaza. */
    private boolean plazaFree(int dx, int dz, int half) {
        for (int ox = -half; ox <= half; ox++) {
            for (int oz = -half; oz <= half; oz++) {
                if (plazaReserved(dx + ox, dz + oz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Benches, extra lamp posts, banner poles at the road entrances, hedges along the edge and crate piles. */
    private void planPlazaDecor(Random r, List<Feature> list, int plazaR) {
        // benches facing the throne
        for (int k = 0; k < 4; k++) {
            double a = Math.toRadians(45 + 90 * k);
            int bx = (int) Math.round(Math.cos(a) * 24), bz = (int) Math.round(Math.sin(a) * 24);
            if (bx * bx + bz * bz < (plazaR - 3) * (plazaR - 3) && plazaFree(bx, bz, 2)) {
                list.add(new Feature(bx, bz, 3, () -> buildBench(bx, bz)));
            }
        }
        // a ring of lamp posts
        for (int k = 0; k < 16; k++) {
            double a = Math.toRadians(11.25 + 22.5 * k);
            int lx = (int) Math.round(Math.cos(a) * 21), lz = (int) Math.round(Math.sin(a) * 21);
            if (plazaFree(lx, lz, 1)) {
                list.add(new Feature(lx, lz, 1, () -> lamp(lx, lz)));
            }
        }
        // banner poles on both sides of each road entrance
        for (int[] d : DIRS) {
            for (int side = -1; side <= 1; side += 2) {
                int along = plazaR - 3;
                int px = d[0] * along + (d[1] != 0 ? side * 3 : 0), pz = d[1] * along + (d[0] != 0 ? side * 3 : 0);
                if (kindAt(px, pz) == PLAZA) {
                    Material banner = side < 0 ? Material.RED_BANNER : Material.WHITE_BANNER;
                    list.add(new Feature(px, pz, 1, () -> bannerPole(px, pz, banner)));
                }
            }
        }
        // flowering hedges along the plaza edge
        for (int k = 0; k < 72; k++) {
            double a = Math.toRadians(k * 5);
            int hx = (int) Math.round(Math.cos(a) * (plazaR - 2)), hz = (int) Math.round(Math.sin(a) * (plazaR - 2));
            if (!plazaReserved(hx, hz) && Math.abs(hx) > 4 && Math.abs(hz) > 4) {
                boolean tall = r.nextInt(3) == 0;
                list.add(new Feature(hx, hz, 1, () -> hedge(hx, hz, tall)));
            }
        }
        // crate piles and flower pots
        for (int i = 0; i < 14; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 20 + r.nextDouble() * Math.max(1, plazaR - 26);
            int px = (int) Math.round(Math.cos(a) * d), pz = (int) Math.round(Math.sin(a) * d);
            if (plazaFree(px, pz, 1)) {
                long seedI = r.nextLong();
                list.add(new Feature(px, pz, 2, () -> cratePile(px, pz, new Random(seedI))));
            }
        }
    }

    private void buildBench(int bx, int bz) {
        int y = top[index(bx, bz)];
        // the seat backs face away from the throne, so people sit looking at it
        boolean alongX = Math.abs(bx) < Math.abs(bz);
        BlockFace back = alongX ? (bz > 0 ? BlockFace.SOUTH : BlockFace.NORTH) : (bx > 0 ? BlockFace.EAST : BlockFace.WEST);
        for (int s = -1; s <= 1; s++) {
            int x = cx + bx + (alongX ? s : 0), z = cz + bz + (alongX ? 0 : s);
            setData(x, y + 1, z, stairs(Material.SPRUCE_STAIRS, back));
        }
        for (int s : new int[]{-2, 2}) {
            int x = cx + bx + (alongX ? s : 0), z = cz + bz + (alongX ? 0 : s);
            BlockData leaves = Bukkit.createBlockData(Material.AZALEA_LEAVES);
            if (leaves instanceof Leaves l) {
                l.setPersistent(true);
            }
            setData(x, y + 1, z, leaves);
        }
    }

    private void bannerPole(int px, int pz, Material banner) {
        int y = top[index(px, pz)];
        set(cx + px, y + 1, cz + pz, Material.STONE_BRICK_WALL);
        set(cx + px, y + 2, cz + pz, Material.SPRUCE_FENCE);
        set(cx + px, y + 3, cz + pz, Material.SPRUCE_FENCE);
        set(cx + px, y + 4, cz + pz, banner);
    }

    private void hedge(int hx, int hz, boolean tall) {
        int y = top[index(hx, hz)];
        BlockData leaves = Bukkit.createBlockData(tall ? Material.FLOWERING_AZALEA_LEAVES : Material.AZALEA_LEAVES);
        if (leaves instanceof Leaves l) {
            l.setPersistent(true);
        }
        set(cx + hx, y, cz + hz, Material.GRASS_BLOCK);
        setData(cx + hx, y + 1, cz + hz, leaves);
        if (tall) {
            setData(cx + hx, y + 2, cz + hz, leaves);
        }
    }

    private void cratePile(int px, int pz, Random r) {
        int y = top[index(px, pz)];
        Material[] crates = {Material.BARREL, Material.BARREL, Material.HAY_BLOCK, Material.CHEST};
        set(cx + px, y + 1, cz + pz, crates[r.nextInt(crates.length)]);
        if (r.nextBoolean()) {
            set(cx + px, y + 2, cz + pz, Material.BARREL);
        }
        int ox = r.nextBoolean() ? 1 : -1;
        set(cx + px + ox, y + 1, cz + pz, crates[r.nextInt(crates.length)]);
        set(cx + px + ox, y + 2, cz + pz, FLOWERS[r.nextInt(FLOWERS.length)]);
        if (r.nextBoolean()) {
            set(cx + px, y + 1, cz + pz + 1, Material.COMPOSTER);
        }
    }

    /** A fountain: a round stone basin with water, a prismarine centrepiece pouring water from the top. */
    private void buildFountain(int fx, int fz) {
        int y = top[index(fx, fz)];
        int x = cx + fx, z = cz + fz;
        for (int ox = -4; ox <= 4; ox++) {
            for (int oz = -4; oz <= 4; oz++) {
                double d = Math.sqrt(ox * ox + oz * oz);
                if (d > 4.2) {
                    continue;
                }
                if (d > 3.3) {
                    set(x + ox, y, z + oz, Material.POLISHED_DIORITE); // a light ring around it
                } else if (d > 2.4) {
                    set(x + ox, y, z + oz, Material.STONE_BRICKS);
                    set(x + ox, y + 1, z + oz, Material.SMOOTH_STONE_SLAB); // the rim
                } else {
                    set(x + ox, y - 1, z + oz, Material.STONE_BRICKS);
                    set(x + ox, y, z + oz, (ox + oz & 1) == 0 ? Material.SEA_LANTERN : Material.PRISMARINE_BRICKS);
                    set(x + ox, y + 1, z + oz, Material.WATER);
                }
            }
        }
        for (int up = 1; up <= 3; up++) {
            set(x, y + up, z, up == 3 ? Material.CHISELED_STONE_BRICKS : Material.PRISMARINE_BRICKS);
        }
        set(x, y + 4, z, Material.PRISMARINE_BRICK_SLAB);
        for (int[] c : new int[][]{{3, 3}, {3, -3}, {-3, 3}, {-3, -3}}) {
            set(x + c[0], y + 1, z + c[1], Material.LANTERN);
        }
        // the water on top flows down into the basin (placed with physics so it actually runs)
        world.getBlockAt(x, y + 5, z).setType(Material.WATER, true);
    }

    /** A raised flower bed with a small tree. */
    private void buildPlanter(int px, int pz, Random r) {
        int y = top[index(px, pz)];
        Material[] flowers = {Material.POPPY, Material.CORNFLOWER, Material.OXEYE_DAISY, Material.ALLIUM, Material.AZURE_BLUET};
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int x = cx + px + ox, z = cz + pz + oz;
                if (Math.abs(ox) == 2 || Math.abs(oz) == 2) {
                    set(x, y + 1, z, Material.STONE_BRICKS);
                } else {
                    set(x, y + 1, z, Material.GRASS_BLOCK);
                    if (ox != 0 || oz != 0) {
                        set(x, y + 2, z, flowers[r.nextInt(flowers.length)]);
                    }
                }
            }
        }
        try {
            world.generateTree(new Location(world, cx + px, y + 2, cz + pz), r, TreeType.AZALEA);
        } catch (RuntimeException ignored) {
        }
    }

    private static final Material[][] AWNINGS = {
            {Material.RED_WOOL, Material.WHITE_WOOL}, {Material.LIME_WOOL, Material.WHITE_WOOL},
            {Material.LIGHT_BLUE_WOOL, Material.WHITE_WOOL}, {Material.YELLOW_WOOL, Material.ORANGE_WOOL},
            {Material.BROWN_WOOL, Material.WHITE_WOOL}, {Material.BLACK_WOOL, Material.RED_WOOL},
            {Material.BLUE_WOOL, Material.WHITE_WOOL}, {Material.GRAY_WOOL, Material.WHITE_WOOL}};
    private static final Material[] STALL_GOODS = {Material.ANVIL, Material.MELON, Material.CARTOGRAPHY_TABLE,
            Material.GOLD_BLOCK, Material.CRAFTING_TABLE, Material.TNT, Material.LECTERN, Material.STONECUTTER};

    /** Goods on the back shelf of each stall (three blocks) and on its side, matching the trade. */
    private static final Material[][] STALL_SHELF = {
            {Material.ANVIL, Material.GRINDSTONE, Material.BLAST_FURNACE, Material.SMITHING_TABLE},
            {Material.MELON, Material.HAY_BLOCK, Material.PUMPKIN, Material.SMOKER},
            {Material.CARTOGRAPHY_TABLE, Material.BARREL, Material.LOOM, Material.CHAIN},
            {Material.GOLD_BLOCK, Material.CHEST, Material.EMERALD_BLOCK, Material.BARREL},
            {Material.OAK_LOG, Material.CRAFTING_TABLE, Material.STRIPPED_OAK_LOG, Material.FLETCHING_TABLE},
            {Material.TNT, Material.TARGET, Material.TNT, Material.DISPENSER},
            {Material.BOOKSHELF, Material.LECTERN, Material.BOOKSHELF, Material.CARTOGRAPHY_TABLE},
            {Material.STONECUTTER, Material.CHISELED_STONE_BRICKS, Material.STONE_BRICKS, Material.POLISHED_ANDESITE}};
    private static final Material[] FLOWERS = {Material.POTTED_RED_TULIP, Material.POTTED_POPPY, Material.POTTED_CORNFLOWER,
            Material.POTTED_OXEYE_DAISY, Material.POTTED_ALLIUM, Material.POTTED_FERN};

    /**
     * A market stall facing the throne: log posts, a striped awning with a scalloped front edge and a raised
     * middle, a counter with goods, a lantern over the shopkeeper (who stands in the middle), shelves and crates
     * with goods matching the trade, and barrels with flowers out front.
     */
    private void buildStall(int sx, int sz, int stall) {
        int y = top[index(sx, sz)];
        int fx, fz;
        if (Math.abs(sx) >= Math.abs(sz)) {
            fx = -Integer.signum(sx);
            fz = 0;
        } else {
            fx = 0;
            fz = -Integer.signum(sz);
        }
        int lx = -fz, lz = fx;
        Material[] awning = AWNINGS[stall % AWNINGS.length];
        Material[] shelf = STALL_SHELF[stall % STALL_SHELF.length];
        java.util.function.BiFunction<Integer, Integer, int[]> at = (a, b) -> new int[]{cx + sx + fx * a + lx * b, cz + sz + fz * a + lz * b};
        for (int a = -3; a <= 3; a++) {
            for (int b = -3; b <= 3; b++) {
                int[] p = at.apply(a, b);
                if (Math.abs(a) <= 2 && Math.abs(b) <= 2) {
                    set(p[0], y, p[1], Math.abs(a) == 2 || Math.abs(b) == 2 ? Material.STRIPPED_SPRUCE_WOOD : Material.SPRUCE_PLANKS);
                    for (int up = 1; up <= 3; up++) {
                        set(p[0], y + up, p[1], Material.AIR);
                    }
                }
                set(p[0], y + 4, p[1], Math.floorMod(b, 2) == 0 ? awning[0] : awning[1]);
            }
            // raised middle of the awning
            if (Math.abs(a) <= 2) {
                for (int b = -1; b <= 1; b++) {
                    int[] p = at.apply(a, b);
                    set(p[0], y + 5, p[1], Math.floorMod(b, 2) == 0 ? awning[0] : awning[1]);
                }
            }
        }
        // scalloped front edge of the awning
        for (int b = -3; b <= 3; b += 2) {
            int[] p = at.apply(3, b);
            set(p[0], y + 3, p[1], awning[0]);
        }
        // posts
        for (int a : new int[]{-2, 2}) {
            for (int b : new int[]{-2, 2}) {
                int[] p = at.apply(a, b);
                for (int up = 1; up <= 3; up++) {
                    set(p[0], y + up, p[1], Material.STRIPPED_SPRUCE_LOG);
                }
            }
        }
        // counter at the front: planks with slabs on top, goods and a lantern on it
        for (int b = -1; b <= 1; b++) {
            int[] p = at.apply(2, b);
            set(p[0], y + 1, p[1], Material.SPRUCE_PLANKS);
        }
        int[] c1 = at.apply(2, -1), c2 = at.apply(2, 1);
        set(c1[0], y + 2, c1[1], Material.LANTERN);
        set(c2[0], y + 2, c2[1], STALL_GOODS[stall % STALL_GOODS.length]);
        // back shelf with three goods on barrels, crates on the sides
        for (int b = -1; b <= 1; b++) {
            int[] p = at.apply(-2, b);
            set(p[0], y + 1, p[1], Material.BARREL);
            set(p[0], y + 2, p[1], shelf[b + 1]);
        }
        int[] side1 = at.apply(0, -2), side2 = at.apply(0, 2);
        set(side1[0], y + 1, side1[1], shelf[3]);
        set(side2[0], y + 1, side2[1], Material.BARREL);
        set(side2[0], y + 2, side2[1], FLOWERS[stall % FLOWERS.length]);
        // a lantern hanging over the shopkeeper
        int[] mid = at.apply(0, 0);
        BlockData hanging = Bukkit.createBlockData(Material.LANTERN);
        if (hanging instanceof org.bukkit.block.data.type.Lantern lantern) {
            lantern.setHanging(true);
        }
        setData(mid[0], y + 3, mid[1], hanging);
        // barrels with flowers on the front corners
        for (int b : new int[]{-3, 3}) {
            int[] p = at.apply(3, b);
            set(p[0], y + 1, p[1], Material.BARREL);
            set(p[0], y + 2, p[1], FLOWERS[(stall + b + 3) % FLOWERS.length]);
        }
    }

    /** The bounty board (the hub module shows the most wanted pirates in front of it). Faces +x. */
    private void buildBountyBoard(int bx, int bz) {
        int y = top[index(bx, bz)];
        int x = cx + bx;
        for (int b = -3; b <= 3; b++) {
            int z = cz + bz + b;
            for (int up = 1; up <= 5; up++) {
                Material m;
                if (Math.abs(b) == 3) {
                    m = Material.DARK_OAK_LOG;
                } else if (up == 1) {
                    m = Material.AIR;
                } else if (up == 5) {
                    m = Material.DARK_OAK_PLANKS;
                } else {
                    boolean poster = (up == 3 && Math.abs(b) == 2) || (up == 4 && b == 0) || (up == 2 && b == -1);
                    m = poster ? Material.WHITE_WOOL : Material.SPRUCE_PLANKS;
                }
                set(x, y + up, z, m);
            }
            set(x - 1, y + 6, z, Material.DARK_OAK_SLAB);
            set(x, y + 6, z, Material.DARK_OAK_SLAB);
            set(x + 1, y + 6, z, Material.DARK_OAK_SLAB);
        }
        set(x + 1, y + 1, cz + bz - 3, Material.LANTERN);
        set(x + 1, y + 1, cz + bz + 3, Material.LANTERN);
    }

    /** The founders' monument (the hub module shows the names above it). */
    private void buildMonument(int mx, int mz) {
        int y = top[index(mx, mz)];
        int x = cx + mx, z = cz + mz;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                set(x + ox, y + 1, z + oz, Material.POLISHED_ANDESITE);
                if (Math.abs(ox) <= 1 && Math.abs(oz) <= 1) {
                    set(x + ox, y + 2, z + oz, Material.CHISELED_STONE_BRICKS);
                }
            }
        }
        for (int up = 3; up <= 5; up++) {
            set(x, y + up, z, Material.QUARTZ_PILLAR);
        }
        set(x, y + 6, z, Material.GOLD_BLOCK);
        set(x, y + 7, z, Material.LANTERN);
        for (int[] c : new int[][]{{2, 2}, {2, -2}, {-2, 2}, {-2, -2}}) {
            set(x + c[0], y + 2, z + c[1], Material.LANTERN);
        }
    }

    /**
     * A parkour tower: a spiral of jumps around a pillar. Start on the light (gold) pressure plate at its
     * foot, finish on the heavy (iron) plate on top; the hub module times the run.
     */
    private void buildParkour(int px, int pz) {
        int y = top[index(px, pz)];
        int x = cx + px, z = cz + pz;
        int steps = 16;
        for (int up = 1; up <= steps + 1; up++) {
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    set(x + ox, y + up, z + oz, up % 5 == 0 ? Material.SEA_LANTERN : Material.STONE_BRICKS);
                }
            }
        }
        set(x, y + steps + 1, z, Material.GOLD_BLOCK);
        set(x, y + steps + 2, z, Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
        for (int i = 0; i < steps; i++) {
            double a = Math.toRadians(90 + 45 * i);
            int jx = x + (int) Math.round(Math.cos(a) * 4), jz = z + (int) Math.round(Math.sin(a) * 4);
            Material m = i % 4 == 3 ? Material.SEA_LANTERN : i % 2 == 0 ? Material.QUARTZ_BLOCK : Material.PRISMARINE_BRICKS;
            set(jx, y + 1 + i, jz, m);
            for (int up = 2; up <= 3; up++) {
                set(jx, y + up + i, jz, Material.AIR);
            }
        }
        set(x, y, z + 6, Material.EMERALD_BLOCK);
        set(x, y + 1, z + 6, Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
    }


    private void buildLighthouse(int lx, int lz) {
        int base = top[index(lx, lz)];
        int height = 20;
        for (int y = 0; y <= height; y++) {
            Material ring = (y / 4) % 2 == 0 ? Material.WHITE_CONCRETE : Material.RED_CONCRETE;
            for (int ox = -2; ox <= 2; ox++) {
                for (int oz = -2; oz <= 2; oz++) {
                    boolean corner = Math.abs(ox) == 2 && Math.abs(oz) == 2;
                    boolean wall = Math.abs(ox) == 2 || Math.abs(oz) == 2;
                    if (corner) {
                        continue;
                    }
                    set(cx + lx + ox, base + y, cz + lz + oz, wall ? ring : (y == 0 ? Material.STONE_BRICKS : Material.AIR));
                }
            }
        }
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                set(cx + lx + ox, base + height + 1, cz + lz + oz, Material.GLASS);
            }
        }
        set(cx + lx, base + height + 1, cz + lz, Material.SEA_LANTERN);
        set(cx + lx, base + height + 2, cz + lz, Material.SEA_LANTERN);
        set(cx + lx, base + height + 3, cz + lz, Material.DARK_OAK_SLAB);
    }

    /** A blob of ore inside the island's rock or the rock below it (never in the open air or the sea). */
    private void buildVein(int dx, int dz, Random r) {
        int surface = top[index(dx, dz)];
        Material ore = (t.ores.isEmpty() || r.nextDouble() < 0.7 ? terrain.veinOres : t.ores).pick(r);
        if (ore == null) {
            return;
        }
        int[] range = oreRange(ore, surface);
        int low = Math.max(minY + 4, range[0]), high = Math.min(surface - 3, range[1]);
        if (high <= low) {
            return;
        }
        int cy = low + r.nextInt(high - low + 1);
        int size = ore == Material.DIAMOND_ORE || ore == Material.EMERALD_ORE ? 2 + r.nextInt(4) : 4 + r.nextInt(8);
        int x = cx + dx, y = cy, z = cz + dz;
        for (int i = 0; i < size; i++) {
            Block b = world.getBlockAt(x, y, z);
            Material type = b.getType();
            if (type == Material.DEEPSLATE || type == Material.TUFF) {
                Material deep = Material.matchMaterial("DEEPSLATE_" + ore.name());
                b.setType(deep != null ? deep : ore, false);
            } else if (type == Material.STONE || type == Material.ANDESITE || type == Material.DIORITE || type == Material.GRANITE
                    || type == Material.SANDSTONE || type == Material.BASALT || type == Material.BLACKSTONE
                    || type == Material.CALCITE || type == Material.TERRACOTTA || type == Material.COBBLESTONE) {
                b.setType(ore, false);
            }
            x += r.nextInt(3) - 1;
            y += r.nextInt(3) - 1;
            z += r.nextInt(3) - 1;
            if (Math.abs(x - (cx + dx)) > 2 || Math.abs(z - (cz + dz)) > 2) {
                x = cx + dx;
                z = cz + dz;
            }
        }
    }

    /** {lowest, highest} y of an ore: common ores high up, rare ones deep (reached through the caves). */
    private int[] oreRange(Material ore, int surface) {
        return switch (ore) {
            case COAL_ORE -> new int[]{sea - 25, surface};
            case IRON_ORE, COPPER_ORE -> new int[]{sea - 35, surface};
            case GOLD_ORE -> new int[]{sea - 40, sea + 6};
            case REDSTONE_ORE, LAPIS_ORE -> new int[]{sea - 45, sea - 4};
            case DIAMOND_ORE -> new int[]{sea - 50, sea - 20};
            case EMERALD_ORE -> new int[]{sea + 10, surface};
            default -> new int[]{sea - 30, surface};
        };
    }

    /** A random spot on dry land (or beach when allowed), or null. */
    private int[] landSpot(Random r, int attempts, boolean beachOk) {
        for (int a = 0; a < attempts; a++) {
            int dx = r.nextInt(n) - radius, dz = r.nextInt(n) - radius;
            byte k = kindAt(dx, dz);
            if (k == LAND || (beachOk && k == BEACH)) {
                return new int[]{dx, dz};
            }
        }
        return null;
    }

    private boolean inGrid(int dx, int dz) {
        return dx >= -radius && dx <= radius && dz >= -radius && dz <= radius;
    }

    private byte kindAt(int dx, int dz) {
        return inGrid(dx, dz) ? kind[index(dx, dz)] : NONE;
    }

    private void set(int wx, int y, int wz, Material m) {
        if (y > minY && y < maxY && m != null) {
            world.getBlockAt(wx, y, wz).setType(m, false);
        }
    }

    private void setIfOpen(int wx, int y, int wz, BlockData data) {
        if (y <= minY || y >= maxY) {
            return;
        }
        Block b = world.getBlockAt(wx, y, wz);
        if (b.isEmpty() || b.isReplaceable()) {
            b.setBlockData(data, false);
        }
    }

    private void plantTree(int dx, int dz, String name, Random r) {
        int wx = cx + dx, wz = cz + dz, y = top[index(dx, dz)] + 1;
        if (!world.getBlockAt(wx, y, wz).isEmpty()) {
            return;
        }
        if ("PALM".equals(name)) {
            buildPalm(wx, y, wz, r);
            return;
        }
        try {
            world.generateTree(new Location(world, wx, y, wz), r, TreeType.valueOf(name));
        } catch (IllegalArgumentException ignored) {
            // unknown tree type in config
        }
    }

    private void buildPalm(int wx, int y, int wz, Random r) {
        int height = 5 + r.nextInt(4);
        int leanX = r.nextInt(3) - 1, leanZ = r.nextInt(3) - 1;
        BlockData log = Bukkit.createBlockData(t.palmLog);
        BlockData leaves = Bukkit.createBlockData(t.palmLeaves);
        if (leaves instanceof Leaves l) {
            l.setPersistent(true);
        }
        int px = wx, pz = wz;
        for (int i = 0; i < height; i++) {
            if (i == height / 2 + 1) {
                px += leanX;
                pz += leanZ;
            }
            setIfOpen(px, y + i, pz, log);
        }
        int ty = y + height - 1;
        setIfOpen(px, ty + 1, pz, leaves);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            setIfOpen(px + dir[0], ty, pz + dir[1], leaves);
            setIfOpen(px + dir[0], ty + 1, pz + dir[1], leaves);
            setIfOpen(px + dir[0] * 2, ty + 1, pz + dir[1] * 2, leaves);
            setIfOpen(px + dir[0] * 3, ty, pz + dir[1] * 3, leaves);
            setIfOpen(px + dir[0] * 4, ty - 1, pz + dir[1] * 4, leaves);
        }
        int[][] diagonals = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] dir : diagonals) {
            setIfOpen(px + dir[0], ty + 1, pz + dir[1], leaves);
            setIfOpen(px + dir[0] * 2, ty, pz + dir[1] * 2, leaves);
        }
    }

    private void buildRuin(int dx, int dz, Random r) {
        if (t.ruinPillars && r.nextBoolean()) {
            int pillars = 3 + r.nextInt(4);
            for (int i = 0; i < pillars; i++) {
                int px = dx + r.nextInt(13) - 6, pz = dz + r.nextInt(13) - 6;
                if (kindAt(px, pz) != LAND) {
                    continue;
                }
                int base = top[index(px, pz)], h = 3 + r.nextInt(6);
                for (int y = 1; y <= h; y++) {
                    set(cx + px, base + y, cz + pz, t.ruinBlocks.pick(r));
                }
                if (t.pillarCap != null && r.nextInt(3) != 0) {
                    set(cx + px, base + h + 1, cz + pz, t.pillarCap);
                }
            }
            return;
        }
        int w = 2 + r.nextInt(3), l = 2 + r.nextInt(3); // half sizes
        for (int ox = -w; ox <= w; ox++) {
            for (int oz = -l; oz <= l; oz++) {
                int px = dx + ox, pz = dz + oz;
                if (kindAt(px, pz) != LAND) {
                    continue;
                }
                int base = top[index(px, pz)];
                boolean wall = Math.abs(ox) == w || Math.abs(oz) == l;
                if (wall) {
                    boolean corner = Math.abs(ox) == w && Math.abs(oz) == l;
                    int h = corner ? 2 + r.nextInt(4) : r.nextInt(5);
                    if (ox == 0 && oz == l) {
                        h = 0; // doorway
                    }
                    for (int y = 1; y <= h; y++) {
                        set(cx + px, base + y, cz + pz, t.ruinBlocks.pick(r));
                    }
                } else if (r.nextInt(3) == 0) {
                    set(cx + px, base, cz + pz, t.ruinBlocks.pick(r));
                }
            }
        }
    }

    private void decorateColumn(int dx, int dz, Random r, boolean cactusOk) {
        {
            if (kind[index(dx, dz)] != LAND) {
                return;
            }
            double roll = r.nextDouble();
            Material chosen = null;
            for (Map.Entry<Material, Double> e : t.decorations.entrySet()) {
                roll -= e.getValue();
                if (roll < 0) {
                    chosen = e.getKey();
                    break;
                }
            }
            if (chosen == null) {
                return;
            }
            int wx = cx + dx, wz = cz + dz, y = top[index(dx, dz)] + 1;
            Block above = world.getBlockAt(wx, y, wz);
            Block ground = world.getBlockAt(wx, y - 1, wz);
            if (!above.isEmpty() || !ground.getType().isOccluding()) {
                return;
            }
            if (chosen == Material.CACTUS) {
                if (!cactusOk) {
                    return;
                }
                Material g = ground.getType();
                if ((g != Material.SAND && g != Material.RED_SAND)
                        || !above.getRelative(BlockFace.NORTH).isEmpty() || !above.getRelative(BlockFace.SOUTH).isEmpty()
                        || !above.getRelative(BlockFace.EAST).isEmpty() || !above.getRelative(BlockFace.WEST).isEmpty()) {
                    return;
                }
                int h = 1 + r.nextInt(3);
                for (int i = 0; i < h; i++) {
                    set(wx, y + i, wz, Material.CACTUS);
                }
                return;
            }
            BlockData data = Bukkit.createBlockData(chosen);
            if (data instanceof Bisected bisected && !(data instanceof org.bukkit.block.data.type.Stairs)
                    && !(data instanceof org.bukkit.block.data.type.TrapDoor)) {
                if (!world.getBlockAt(wx, y + 1, wz).isEmpty()) {
                    return;
                }
                bisected.setHalf(Bisected.Half.BOTTOM);
                above.setBlockData(bisected, false);
                Bisected upper = (Bisected) Bukkit.createBlockData(chosen);
                upper.setHalf(Bisected.Half.TOP);
                world.getBlockAt(wx, y + 1, wz).setBlockData(upper, false);
            } else {
                above.setBlockData(data, false);
            }
        }
    }

    private void topLayerColumn(int dx, int dz) {
        {
            byte k = kind[index(dx, dz)];
            if (k != LAND && k != BEACH && k != CONE && k != PLAZA) {
                return;
            }
            int wx = cx + dx, wz = cz + dz, y = top[index(dx, dz)] + 1;
            Block above = world.getBlockAt(wx, y, wz);
            if (above.isEmpty() && above.getRelative(BlockFace.DOWN).getType().isOccluding()) {
                above.setType(t.topLayer, false);
            }
        }
    }

    private void buildThrone() {
        int base = inGrid(0, 0) ? top[index(0, 0)] : sea + t.plazaHeight;
        // three stacked tiers
        for (int tier = 0; tier < 3; tier++) {
            int half = 4 - tier;
            for (int ox = -half; ox <= half; ox++) {
                for (int oz = -half; oz <= half; oz++) {
                    set(cx + ox, base + 1 + tier, cz + oz, t.throneBase);
                }
            }
        }
        int seatY = base + 4;
        // back rest and arm rests
        for (int y = 0; y < 3; y++) {
            set(cx, seatY + y, cz - 1, t.throneAccent);
        }
        set(cx - 1, seatY + 3, cz - 1, t.throneAccent);
        set(cx + 1, seatY + 3, cz - 1, t.throneAccent);
        set(cx - 1, seatY, cz - 1, t.throneAccent);
        set(cx + 1, seatY, cz - 1, t.throneAccent);
        set(cx - 1, seatY, cz, t.throneAccent);
        set(cx + 1, seatY, cz, t.throneAccent);
        BlockData seat = Bukkit.createBlockData(t.throneSeat);
        if (seat instanceof Directional directional && directional.getFaces().contains(BlockFace.NORTH)) {
            directional.setFacing(BlockFace.NORTH);
        }
        world.getBlockAt(cx, seatY, cz).setBlockData(seat, false);
        // carpet down the southern steps
        set(cx, seatY, cz + 1, t.throneCarpet);
        set(cx, seatY, cz + 2, t.throneCarpet);
        set(cx, seatY - 1, cz + 3, t.throneCarpet);
        set(cx, seatY - 2, cz + 4, t.throneCarpet);
        for (int oz = 5; oz <= 8; oz++) {
            if (kindAt(0, oz) == PLAZA) {
                set(cx, top[index(0, oz)] + 1, cz + oz, t.throneCarpet);
            }
        }
        // corner pillars with lights
        int[][] corners = {{4, 4}, {4, -4}, {-4, 4}, {-4, -4}};
        for (int[] c : corners) {
            for (int y = 2; y <= 5; y++) {
                set(cx + c[0], base + y, cz + c[1], t.throneBase);
            }
            set(cx + c[0], base + 6, cz + c[1], t.throneLight);
        }
    }

    // ------------------------------------------------------------------ Tidewatch fort

    private Material wallBlock(Random r) {
        double v = r.nextDouble();
        return v < 0.15 ? Material.MOSSY_STONE_BRICKS : v < 0.25 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
    }

    /**
     * A Tidewatch fort on the plaza: square walls with crenellations and a walkway, a gate to the south,
     * four corner towers with banners, and a two-storey keep with a flag in the middle (the Warden Captain's).
     */
    private void buildFort() {
        int y = inGrid(0, 0) ? top[index(0, 0)] : sea + 3;
        Random r = new Random(seed * 97 + 1);
        int R = 12;
        // clear the courtyard and give it a floor
        for (int dx = -R - 2; dx <= R + 2; dx++) {
            for (int dz = -R - 2; dz <= R + 2; dz++) {
                for (int up = 1; up <= 14; up++) {
                    set(cx + dx, y + up, cz + dz, Material.AIR);
                }
                set(cx + dx, y, cz + dz, r.nextDouble() < 0.3 ? Material.GRAVEL : Material.COBBLESTONE);
            }
        }
        // walls
        for (int i = -R; i <= R; i++) {
            int[][] spots = {{i, -R}, {i, R}, {-R, i}, {R, i}};
            for (int[] s : spots) {
                for (int up = 1; up <= 5; up++) {
                    set(cx + s[0], y + up, cz + s[1], wallBlock(r));
                }
                if ((i & 1) == 0) {
                    set(cx + s[0], y + 6, cz + s[1], Material.STONE_BRICK_WALL);
                }
            }
        }
        // walkway along the inside of the walls
        for (int i = -R + 1; i <= R - 1; i++) {
            set(cx + i, y + 4, cz - R + 1, Material.SPRUCE_PLANKS);
            set(cx + i, y + 4, cz + R - 1, Material.SPRUCE_PLANKS);
            set(cx - R + 1, y + 4, cz + i, Material.SPRUCE_PLANKS);
            set(cx + R - 1, y + 4, cz + i, Material.SPRUCE_PLANKS);
        }
        // gate (south) and ladders up to the walkway
        for (int gx = -1; gx <= 1; gx++) {
            for (int up = 1; up <= 3; up++) {
                set(cx + gx, y + up, cz + R, Material.AIR);
                set(cx + gx, y + up, cz + R - 1, Material.AIR);
            }
            set(cx + gx, y + 4, cz + R - 1, Material.SPRUCE_PLANKS);
        }
        for (int up = 1; up <= 3; up++) {
            setData(cx - R + 1, y + up, cz - 3, stairs(Material.LADDER, BlockFace.EAST));
            setData(cx + R - 1, y + up, cz + 3, stairs(Material.LADDER, BlockFace.WEST));
        }
        // corner towers
        for (int[] c : new int[][]{{R, R}, {R, -R}, {-R, R}, {-R, -R}}) {
            for (int ox = -2; ox <= 2; ox++) {
                for (int oz = -2; oz <= 2; oz++) {
                    boolean edge = Math.abs(ox) == 2 || Math.abs(oz) == 2;
                    for (int up = 1; up <= 9; up++) {
                        set(cx + c[0] + ox, y + up, cz + c[1] + oz, edge ? wallBlock(r) : (up == 4 || up == 9 ? Material.SPRUCE_PLANKS : Material.AIR));
                    }
                    if (edge && ((ox + oz) & 1) == 0) {
                        set(cx + c[0] + ox, y + 10, cz + c[1] + oz, Material.STONE_BRICK_WALL);
                    }
                }
            }
            set(cx + c[0], y + 10, cz + c[1], Material.BLUE_BANNER);
            set(cx + c[0], y + 8, cz + c[1], Material.LANTERN);
        }
        // the keep (9x9, two floors)
        int K = 4;
        for (int ox = -K; ox <= K; ox++) {
            for (int oz = -K; oz <= K; oz++) {
                boolean edge = Math.abs(ox) == K || Math.abs(oz) == K;
                for (int up = 1; up <= 9; up++) {
                    Material m;
                    if (edge) {
                        boolean window = (up == 2 || up == 6) && Math.abs(ox) != Math.abs(oz) && ((Math.abs(ox) == K ? oz : ox) & 1) == 1;
                        m = window ? Material.GLASS_PANE : (up == 5 ? Material.POLISHED_ANDESITE : wallBlock(r));
                    } else {
                        m = up == 5 || up == 9 ? Material.SPRUCE_PLANKS : Material.AIR;
                    }
                    set(cx + ox, y + up, cz + oz, m);
                }
                set(cx + ox, y, cz + oz, Material.POLISHED_ANDESITE);
                if (edge && ((ox + oz) & 1) == 0) {
                    set(cx + ox, y + 10, cz + oz, Material.STONE_BRICK_WALL);
                }
            }
        }
        for (int up = 1; up <= 2; up++) {
            set(cx, y + up, cz + K, Material.AIR); // keep door
        }
        for (int up = 1; up <= 5; up++) {
            setData(cx - K + 1, y + up, cz + K - 1, stairs(Material.LADDER, BlockFace.EAST)); // to the upper floor
        }
        for (int up = 6; up <= 9; up++) {
            setData(cx + K - 1, y + up, cz - K + 1, stairs(Material.LADDER, BlockFace.WEST)); // to the roof
        }
        set(cx, y + 4, cz, Material.LANTERN);
        set(cx, y + 8, cz, Material.LANTERN);
        set(cx + K - 1, y + 1, cz - K + 1, Material.BARREL);
        set(cx - K + 1, y + 1, cz - K + 1, Material.BARREL);
        set(cx + K - 1, y + 1, cz + K - 1, Material.CRAFTING_TABLE);
        set(cx - K + 1, y + 6, cz - K + 1, Material.CARTOGRAPHY_TABLE);
        // flag on the keep roof
        for (int up = 10; up <= 14; up++) {
            set(cx, y + up, cz, Material.SPRUCE_FENCE);
        }
        for (int fx = 1; fx <= 3; fx++) {
            set(cx + fx, y + 14, cz, Material.BLUE_WOOL);
            set(cx + fx, y + 13, cz, fx == 2 ? Material.WHITE_WOOL : Material.BLUE_WOOL);
        }
        // courtyard: training targets, hay, campfires, crates
        for (int i = -6; i <= 6; i += 4) {
            set(cx - R + 2, y + 1, cz + i, Material.TARGET);
            set(cx + R - 2, y + 1, cz + i, Material.HAY_BLOCK);
        }
        set(cx - 7, y + 1, cz + 7, Material.CAMPFIRE);
        set(cx + 7, y + 1, cz + 7, Material.CAMPFIRE);
        set(cx + 7, y + 1, cz - 7, Material.BARREL);
        set(cx + 8, y + 1, cz - 7, Material.BARREL);
        set(cx + 7, y + 2, cz - 7, Material.BARREL);
    }
}
