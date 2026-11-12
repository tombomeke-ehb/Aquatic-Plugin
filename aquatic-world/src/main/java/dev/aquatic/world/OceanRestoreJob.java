package dev.aquatic.world;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Random;

/** Turns a circular area back into open ocean (used by /island delete ... restore and /island regen). */
final class OceanRestoreJob extends AreaJob {

    private final String label;
    private final int depth;
    private final WeightedList<Material> floor;
    private final Biome biome;
    private final SimplexOctaveGenerator noise;
    private final Random random;
    private final int n;
    private int phase;
    private int cursor;

    OceanRestoreJob(Plugin plugin, String label, World world, int cx, int cz, int radius, long seed,
                    int depth, WeightedList<Material> floor, Biome biome) {
        super(plugin, world, cx, cz, radius);
        this.label = label;
        this.depth = Math.max(2, depth);
        this.floor = floor;
        this.biome = biome;
        this.noise = new SimplexOctaveGenerator(new Random(seed), 2);
        this.noise.setScale(1.0 / 24.0);
        this.random = new Random(seed);
        this.n = radius * 2 + 1;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public double progress() {
        if (phase == 0) {
            return 0.15 * chunkProgress();
        }
        return 0.15 + 0.85 * cursor / (double) ((long) n * n);
    }

    @Override
    public boolean tick(long deadline) {
        if (phase == 0) {
            if (!pumpChunks()) {
                return false;
            }
            phase = 1;
        }
        long total = (long) n * n;
        while (cursor < total) {
            column(cursor % n - radius, cursor / n - radius);
            cursor++;
            if (System.nanoTime() > deadline) {
                return false;
            }
        }
        refreshChunks();
        return true;
    }

    private void column(int dx, int dz) {
        if ((long) dx * dx + (long) dz * dz > (long) radius * radius) {
            return;
        }
        int wx = cx + dx, wz = cz + dz;
        int floorY = Math.max(minY + 1, sea - depth + (int) Math.round(noise.noise(wx, wz, 2.0, 0.5, true) * 2));
        int topY = Math.min(maxY, world.getHighestBlockYAt(wx, wz, HeightMap.WORLD_SURFACE));
        for (int y = topY; y > floorY; y--) {
            Block b = world.getBlockAt(wx, y, wz);
            Material want = y >= sea ? Material.AIR : Material.WATER;
            if (b.getType() != want) {
                b.setType(want, false);
            }
        }
        Block f = world.getBlockAt(wx, floorY, wz);
        if (f.getType().isSolid() && topY > floorY) {
            Material m = floor.pick(random);
            if (m != null) {
                f.setType(m, false);
            }
        }
        if (biome != null && (wx & 3) == 0 && (wz & 3) == 0) {
            for (int y = Math.max(minY, floorY - 8); y <= Math.min(maxY, sea + 64); y += 4) {
                world.setBiome(wx, y, wz, biome);
            }
        }
    }
}
