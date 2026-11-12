package dev.aquatic.world;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Base of the budgeted terrain jobs: loads every chunk of a circular area asynchronously (in small
 * batches), keeps them loaded with plugin chunk tickets while the job runs and releases them afterwards.
 */
abstract class AreaJob implements WorldJob {

    private static final int MAX_OUTSTANDING = 32;

    protected final Plugin plugin;
    protected final World world;
    protected final int cx, cz, radius;
    protected final int sea, minY, maxY;

    private final List<long[]> chunks = new ArrayList<>();
    private final List<long[]> ticketed = new ArrayList<>();
    private int requested;
    private int outstanding;
    private boolean cleaned;

    AreaJob(Plugin plugin, World world, int cx, int cz, int radius) {
        this.plugin = plugin;
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.radius = radius;
        this.sea = world.getSeaLevel();
        this.minY = world.getMinHeight();
        this.maxY = world.getMaxHeight() - 1;
        int pad = 2;
        for (int x = (cx - radius - pad) >> 4; x <= (cx + radius + pad) >> 4; x++) {
            for (int z = (cz - radius - pad) >> 4; z <= (cz + radius + pad) >> 4; z++) {
                chunks.add(new long[]{x, z});
            }
        }
    }

    /** @return true once every chunk of the area is loaded and ticketed. */
    protected final boolean pumpChunks() {
        while (outstanding < MAX_OUTSTANDING && requested < chunks.size()) {
            long[] c = chunks.get(requested++);
            outstanding++;
            world.getChunkAtAsync((int) c[0], (int) c[1], true).whenComplete((chunk, error) -> {
                outstanding--;
                if (!cleaned && chunk != null) {
                    chunk.addPluginChunkTicket(plugin);
                    ticketed.add(c);
                }
            });
        }
        return requested >= chunks.size() && outstanding <= 0;
    }

    protected final double chunkProgress() {
        return chunks.isEmpty() ? 1 : Math.max(0, requested - outstanding) / (double) chunks.size();
    }

    /** Makes clients re-read the chunks (needed for biome changes to show up without relogging). */
    @SuppressWarnings("deprecation")
    protected final void refreshChunks() {
        for (long[] c : chunks) {
            if (world.isChunkLoaded((int) c[0], (int) c[1])) {
                world.refreshChunk((int) c[0], (int) c[1]);
            }
        }
    }

    @Override
    public void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        for (long[] c : ticketed) {
            world.removePluginChunkTicket((int) c[0], (int) c[1], plugin);
        }
        ticketed.clear();
    }
}
