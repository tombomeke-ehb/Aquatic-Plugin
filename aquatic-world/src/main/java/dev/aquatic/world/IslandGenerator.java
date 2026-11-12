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
                o.caveMinRadius = s.getInt("caves.min-island-radius", 60);
                o.caveSize = Math.max(0.2, Math.min(3, s.getDouble("caves.size", 1.0)));
                o.caveDepth = Math.max(0, s.getInt("caves.depth", 14));
                o.caveEntrances = s.getBoolean("caves.entrances", true);
                o.veins = s.getBoolean("ore-veins.enabled", true);
                o.veinsPer1000 = Math.max(0, s.getDouble("ore-veins.per-1000-land", 4));
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
    private org.bukkit.util.noise.SimplexNoiseGenerator caveA, caveB, caveGate;

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
     * Cave tunnels: where two 3D noise fields are both near zero ("spaghetti" caves). Only inland (so the
     * sea never floods in), below the surface, with occasional openings to the surface.
     */
    private void carveCaves(int wx, int wz, int floor, int topY, double tt, byte k) {
        if (!terrain.caves || radius < terrain.caveMinRadius || (k != LAND && k != MOUNTAIN) || tt < 0.14) {
            return;
        }
        double scale = 1.0 / (22.0 * terrain.caveSize);
        double width = 0.075 * terrain.caveSize;
        boolean entrance = terrain.caveEntrances && caveGate.noise(wx * 0.02, wz * 0.02) > 0.72;
        int low = Math.max(Math.max(floor + 2, minY + 2), sea - terrain.caveDepth);
        int high = entrance ? topY : topY - 5;
        for (int y = low; y <= high; y++) {
            double a = caveA.noise(wx * scale, y * scale * 1.6, wz * scale);
            if (Math.abs(a) > width) {
                continue;
            }
            double b = caveB.noise(wx * scale, y * scale * 1.6, wz * scale);
            if (Math.abs(b) <= width) {
                Block block = world.getBlockAt(wx, y, wz);
                if (block.getType().isSolid()) {
                    block.setType(y <= low + 1 && t.volcano ? Material.LAVA : Material.CAVE_AIR, false);
                }
            }
        }
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
                int[] spot = landSpot(r, 6, false);
                if (spot != null) {
                    long veinSeed = r.nextLong();
                    list.add(new Feature(spot[0], spot[1], 3, () -> buildVein(spot[0], spot[1], new Random(veinSeed))));
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
        List<int[]> houseSpots = new ArrayList<>();
        boolean lighthouse = false;
        for (int[] d : DIRS) {
            int step = plazaR - 1;
            int lastX = 0, lastZ = 0;
            List<int[]> road = new ArrayList<>();
            while (step < radius) {
                int rx = d[0] * step, rz = d[1] * step;
                byte k = kindAt(rx, rz);
                if (k != LAND && k != PLAZA && k != MOUNTAIN && k != BUILT) {
                    break;
                }
                for (int w = -1; w <= 1; w++) {
                    int px = rx + (d[1] != 0 ? w : 0), pz = rz + (d[0] != 0 ? w : 0);
                    if (inGrid(px, pz) && kind[index(px, pz)] != PLAZA) {
                        kind[index(px, pz)] = BUILT;
                        road.add(new int[]{px, pz});
                    }
                }
                lastX = rx;
                lastZ = rz;
                step++;
            }
            // road in segments, so each feature only needs a few chunks
            for (int i = 0; i < road.size(); i += 24) {
                List<int[]> seg = road.subList(i, Math.min(road.size(), i + 24));
                int[] mid = seg.get(seg.size() / 2);
                list.add(new Feature(mid[0], mid[1], 12, () -> paveRoad(seg, d)));
            }
            // houses on both sides, every ~11 blocks
            int roadLen = Math.max(0, step - plazaR);
            for (int off = plazaR + 4; off < plazaR + roadLen - 6; off += 11) {
                for (int side = -1; side <= 1; side += 2) {
                    int hx = d[0] * off + (d[1] != 0 ? side * 6 : 0);
                    int hz = d[1] * off + (d[0] != 0 ? side * 6 : 0);
                    houseSpots.add(new int[]{hx, hz, d[0], d[1], side});
                }
            }
            // pier where the road meets the sea, lighthouse at the first one
            int px = lastX + d[0] * 2, pz = lastZ + d[1] * 2;
            int[] dir = d;
            list.add(new Feature(px + d[0] * 9, pz + d[1] * 9, 12, () -> buildPier(px, pz, dir)));
            if (!lighthouse) {
                lighthouse = true;
                int lx = lastX + d[1] * 6, lz = lastZ + d[0] * 6;
                if (kindAt(lx, lz) == LAND || kindAt(lx, lz) == BEACH) {
                    list.add(new Feature(lx, lz, 4, () -> buildLighthouse(lx, lz)));
                }
            }
        }
        java.util.Collections.shuffle(houseSpots, r);
        int built = 0;
        for (int[] h : houseSpots) {
            if (built >= t.townHouses) {
                break;
            }
            int w = 2 + r.nextInt(2), l = 2 + r.nextInt(2); // half sizes: 5x5 .. 7x7
            if (!footprintFree(h[0], h[1], w, l)) {
                continue;
            }
            for (int ox = -w - 1; ox <= w + 1; ox++) {
                for (int oz = -l - 1; oz <= l + 1; oz++) {
                    kind[index(h[0] + ox, h[1] + oz)] = BUILT;
                }
            }
            long houseSeed = r.nextLong();
            // the door faces the road
            int doorDx, doorDz;
            if (h[2] != 0) { // road runs along x: houses beside it (z offset), door faces -side in z
                doorDx = 0;
                doorDz = -h[4];
            } else {
                doorDx = -h[4];
                doorDz = 0;
            }
            int hx = h[0], hz = h[1];
            list.add(new Feature(hx, hz, 6, () -> buildHouse(hx, hz, w, l, doorDx, doorDz, new Random(houseSeed))));
            built++;
        }
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
            set(x, y, z, t.roadBlocks.isEmpty() ? Material.STONE_BRICKS : t.roadBlocks.pick(rng));
            for (int up = 1; up <= 4; up++) {
                Block b = world.getBlockAt(x, y + up, z);
                if (!b.isEmpty()) {
                    b.setType(Material.AIR, false);
                }
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

    private void buildHouse(int hx, int hz, int w, int l, int doorDx, int doorDz, Random r) {
        int base = Integer.MIN_VALUE;
        for (int ox = -w; ox <= w; ox++) {
            for (int oz = -l; oz <= l; oz++) {
                base = Math.max(base, top[index(hx + ox, hz + oz)]);
            }
        }
        Material[] walls = {Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS, Material.WHITE_TERRACOTTA};
        Material wall = walls[r.nextInt(walls.length)];
        Material roof = r.nextBoolean() ? Material.SPRUCE_SLAB : Material.DARK_OAK_SLAB;
        int height = 4;
        for (int ox = -w; ox <= w; ox++) {
            for (int oz = -l; oz <= l; oz++) {
                int x = cx + hx + ox, z = cz + hz + oz;
                // foundation down to the ground
                for (int y = top[index(hx + ox, hz + oz)]; y <= base; y++) {
                    set(x, y, z, Material.COBBLESTONE);
                }
                boolean edgeX = Math.abs(ox) == w, edgeZ = Math.abs(oz) == l;
                for (int y = base + 1; y <= base + height; y++) {
                    Material m;
                    if (edgeX && edgeZ) {
                        m = Material.STRIPPED_SPRUCE_LOG;
                    } else if (edgeX || edgeZ) {
                        boolean window = y == base + 2 && ((edgeX && Math.abs(oz) == 1) || (edgeZ && Math.abs(ox) == 1));
                        m = window ? Material.GLASS_PANE : wall;
                    } else {
                        m = Material.AIR;
                    }
                    set(x, y, z, m);
                }
                set(x, base, z, edgeX || edgeZ ? Material.COBBLESTONE : Material.SPRUCE_PLANKS);
            }
        }
        // slab roof with a 1-block overhang
        for (int ox = -w - 1; ox <= w + 1; ox++) {
            for (int oz = -l - 1; oz <= l + 1; oz++) {
                set(cx + hx + ox, base + height + 1, cz + hz + oz, roof);
            }
        }
        // door in the middle of the wall that faces the road
        int dx = cx + hx + doorDx * w, dz = cz + hz + doorDz * l;
        BlockFace facing = doorDx > 0 ? BlockFace.EAST : doorDx < 0 ? BlockFace.WEST : doorDz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
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
        // a lantern inside and a light above the door
        set(cx + hx, base + 1, cz + hz, Material.LANTERN);
        set(dx + doorDx, base + 3, dz + doorDz, Material.LANTERN);
    }

    private void buildPier(int px, int pz, int[] d) {
        int y = sea;
        for (int i = 0; i < 18; i++) {
            int x = cx + px + d[0] * i, z = cz + pz + d[1] * i;
            for (int w = -1; w <= 1; w++) {
                int wx = x + (d[1] != 0 ? w : 0), wz = z + (d[0] != 0 ? w : 0);
                Block b = world.getBlockAt(wx, y, wz);
                if (b.getType() == Material.WATER || b.isEmpty() || b.getType() == Material.SAND || b.isReplaceable()) {
                    b.setType(Material.SPRUCE_PLANKS, false);
                }
                if (Math.abs(w) == 1 && i % 4 == 0) {
                    // posts down to the sea floor
                    for (int yy = y - 1; yy > minY && !world.getBlockAt(wx, yy, wz).getType().isSolid(); yy--) {
                        world.getBlockAt(wx, yy, wz).setType(Material.SPRUCE_LOG, false);
                    }
                    set(wx, y + 1, wz, Material.SPRUCE_FENCE);
                    if (i % 8 == 0) {
                        set(wx, y + 2, wz, Material.LANTERN);
                    }
                }
            }
        }
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

    /** A blob of ore inside the island's rock (never in the open air or the sea). */
    private void buildVein(int dx, int dz, Random r) {
        int surface = top[index(dx, dz)];
        int low = Math.max(minY + 2, sea - 10), high = surface - 4;
        if (high <= low) {
            return;
        }
        Material ore = (t.ores.isEmpty() ? terrain.veinOres : r.nextBoolean() ? t.ores : terrain.veinOres).pick(r);
        if (ore == null) {
            return;
        }
        int cy = low + r.nextInt(high - low + 1);
        int size = 3 + r.nextInt(7);
        int x = cx + dx, y = cy, z = cz + dz;
        for (int i = 0; i < size; i++) {
            Block b = world.getBlockAt(x, y, z);
            Material type = b.getType();
            if (type == Material.STONE || type == Material.ANDESITE || type == Material.DIORITE || type == Material.GRANITE
                    || type == Material.TUFF || type == Material.DEEPSLATE || type == Material.SANDSTONE
                    || type == Material.BASALT || type == Material.BLACKSTONE || type == Material.CALCITE || type == Material.TERRACOTTA) {
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
}
