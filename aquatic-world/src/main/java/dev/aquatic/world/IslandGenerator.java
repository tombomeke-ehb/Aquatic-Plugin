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
import java.util.Random;

/**
 * Procedural island builder. The whole island is a pure function of (seed, type settings, radius):
 * a noise-distorted radial falloff gives an irregular coastline, a second noise the hills. Work is
 * split into phases (chunk loading, terrain columns, biome, features) and executed within a
 * per-tick time budget handed in by the job queue.
 */
final class IslandGenerator extends AreaJob {

    private static final byte NONE = 0, SLOPE = 1, BEACH = 2, LAND = 3, CONE = 4, CRATER = 5, PLAZA = 6;

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

    private int phase;
    private long cursor;
    private int landCount;
    private Deque<Runnable> features;
    private int featureTotal;

    IslandGenerator(Plugin plugin, String label, World world, int cx, int cz, int radius, long seed,
                    TypeSettings settings, double landFraction) {
        super(plugin, world, cx, cz, radius);
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
        this.rng = new Random(seed ^ 0x9E3779B97F4A7C15L);
        double base = 1 + t.maxHeight * 0.65;
        double rim = t.coneHeight * Math.pow(1 - t.craterSize / t.coneSize, 1.4);
        this.lavaY = sea - 1 + (int) Math.round(base + rim) - 2;
        this.maxElevation = t.maxHeight + (t.volcano ? t.coneHeight : 0) + 6;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public double progress() {
        return switch (phase) {
            case 0 -> 0.10 * chunkProgress();
            case 1 -> 0.10 + 0.60 * cursor / (double) ((long) n * n);
            case 2 -> 0.70 + 0.05 * cursor / (double) ((long) n * n);
            case 3 -> 0.75 + 0.25 * (featureTotal == 0 ? 1 : 1 - features.size() / (double) featureTotal);
            default -> 1;
        };
    }

    /** Surface height at the island centre once generation has finished. */
    int centerSurfaceY() {
        return world.getHighestBlockYAt(cx, cz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
    }

    @Override
    public boolean tick(long deadline) {
        if (phase == 0) {
            if (!pumpChunks()) {
                return false;
            }
            phase = 1;
            cursor = 0;
        }
        long total = (long) n * n;
        if (phase == 1) {
            while (cursor < total) {
                column((int) (cursor % n) - radius, (int) (cursor / n) - radius);
                cursor++;
                if (System.nanoTime() > deadline) {
                    return false;
                }
            }
            phase = 2;
            cursor = 0;
        }
        if (phase == 2) {
            if (t.biome != null) {
                while (cursor < total) {
                    biomeColumn((int) (cursor % n) - radius, (int) (cursor / n) - radius);
                    cursor++;
                    if ((cursor & 63) == 0 && System.nanoTime() > deadline) {
                        return false;
                    }
                }
            }
            phase = 3;
            buildFeatures();
        }
        if (phase == 3) {
            while (!features.isEmpty()) {
                features.poll().run();
                if (System.nanoTime() > deadline) {
                    return false;
                }
            }
            phase = 4;
        }
        refreshChunks();
        return true;
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
        double elev = 1 + s * t.maxHeight * (0.3 + 0.7 * hill);
        byte k = (elev - 1 < t.beachHeight || tt < 0.04) ? BEACH : LAND;
        if (coneT > 0) {
            double cone = t.coneHeight * Math.pow(coneT, 1.4);
            if (d < t.craterSize) {
                double rim = t.coneHeight * Math.pow(1 - t.craterSize / t.coneSize, 1.4);
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
                    default -> t.surface.pick(rng);
                };
            } else if (depth < 4) {
                m = switch (k) {
                    case BEACH -> t.beach.pick(rng);
                    case CONE, CRATER -> t.coneBlocks.pick(rng);
                    case PLAZA -> t.core.pick(rng);
                    default -> t.subsurface.pick(rng);
                };
            } else if (t.oreChance > 0 && rng.nextDouble() < t.oreChance) {
                m = t.ores.pick(rng);
            } else {
                m = t.core.pick(rng);
            }
            world.getBlockAt(wx, y, wz).setType(m, false);
        }
        if (k == CRATER) {
            for (int y = Math.max(topY, floor) + 1; y <= lavaY; y++) {
                world.getBlockAt(wx, y, wz).setType(t.craterFluid, false);
            }
        }
        top[idx] = Math.max(topY, floor);
        kind[idx] = k;
        if (k == LAND) {
            landCount++;
        }
    }

    private void biomeColumn(int dx, int dz) {
        int wx = cx + dx, wz = cz + dz;
        if ((wx & 3) != 0 || (wz & 3) != 0 || (long) dx * dx + (long) dz * dz > (long) (radius + 3) * (radius + 3)) {
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
        if (t.plaza && t.throne) {
            features.add(this::buildThrone);
        }
        if (t.ruinDensity > 0 && !t.ruinBlocks.isEmpty()) {
            int ruins = Math.max(1, (int) Math.round(landCount / 10000.0 * t.ruinDensity));
            for (int i = 0; i < ruins; i++) {
                features.add(() -> buildRuin(r));
            }
        }
        if (t.treeDensity > 0 && !t.trees.isEmpty()) {
            int trees = (int) Math.round(landCount / 100.0 * t.treeDensity);
            for (int i = 0; i < trees; i++) {
                features.add(() -> plantTree(r));
            }
        }
        if (!t.decorations.isEmpty()) {
            for (int dz = -radius; dz <= radius; dz++) {
                int row = dz;
                features.add(() -> decorateRow(row, r));
            }
        }
        if (t.topLayer != null) {
            for (int dz = -radius; dz <= radius; dz++) {
                int row = dz;
                features.add(() -> topLayerRow(row));
            }
        }
        featureTotal = features.size();
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

    private void plantTree(Random r) {
        String name = t.trees.pick(r);
        boolean palm = "PALM".equals(name);
        for (int attempt = 0; attempt < 8; attempt++) {
            int dx = r.nextInt(n) - radius, dz = r.nextInt(n) - radius;
            byte k = kindAt(dx, dz);
            if (k != LAND && !(palm && t.palmsOnBeach && k == BEACH)) {
                continue;
            }
            int wx = cx + dx, wz = cz + dz, y = top[index(dx, dz)] + 1;
            if (!world.getBlockAt(wx, y, wz).isEmpty()) {
                continue;
            }
            if (palm) {
                buildPalm(wx, y, wz, r);
                return;
            }
            TreeType type;
            try {
                type = TreeType.valueOf(name);
            } catch (IllegalArgumentException e) {
                return;
            }
            if (world.generateTree(new Location(world, wx, y, wz), r, type)) {
                return;
            }
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

    private void buildRuin(Random r) {
        int dx = 0, dz = 0;
        boolean found = false;
        for (int attempt = 0; attempt < 12 && !found; attempt++) {
            dx = r.nextInt(n) - radius;
            dz = r.nextInt(n) - radius;
            found = kindAt(dx, dz) == LAND;
        }
        if (!found) {
            return;
        }
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

    private void decorateRow(int dz, Random r) {
        for (int dx = -radius; dx <= radius; dx++) {
            if (kind[index(dx, dz)] != LAND) {
                continue;
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
                continue;
            }
            int wx = cx + dx, wz = cz + dz, y = top[index(dx, dz)] + 1;
            Block above = world.getBlockAt(wx, y, wz);
            Block ground = world.getBlockAt(wx, y - 1, wz);
            if (!above.isEmpty() || !ground.getType().isOccluding()) {
                continue;
            }
            if (chosen == Material.CACTUS) {
                Material g = ground.getType();
                if ((g != Material.SAND && g != Material.RED_SAND)
                        || !above.getRelative(BlockFace.NORTH).isEmpty() || !above.getRelative(BlockFace.SOUTH).isEmpty()
                        || !above.getRelative(BlockFace.EAST).isEmpty() || !above.getRelative(BlockFace.WEST).isEmpty()) {
                    continue;
                }
                int h = 1 + r.nextInt(3);
                for (int i = 0; i < h; i++) {
                    set(wx, y + i, wz, Material.CACTUS);
                }
                continue;
            }
            BlockData data = Bukkit.createBlockData(chosen);
            if (data instanceof Bisected bisected && !(data instanceof org.bukkit.block.data.type.Stairs)
                    && !(data instanceof org.bukkit.block.data.type.TrapDoor)) {
                if (!world.getBlockAt(wx, y + 1, wz).isEmpty()) {
                    continue;
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

    private void topLayerRow(int dz) {
        for (int dx = -radius; dx <= radius; dx++) {
            byte k = kind[index(dx, dz)];
            if (k != LAND && k != BEACH && k != CONE && k != PLAZA) {
                continue;
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
