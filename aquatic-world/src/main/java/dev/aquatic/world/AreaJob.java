package dev.aquatic.world;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base of the budgeted terrain jobs. The area is worked on <b>chunk by chunk</b>: chunks are loaded
 * asynchronously a little ahead of the work, kept loaded with a plugin chunk ticket only while they are
 * needed, and released again right after. Memory use therefore stays small even for islands with a
 * radius of a thousand blocks or more (which cover ~16,000 chunks).
 */
abstract class AreaJob implements WorldJob {

    /** Chunks requested ahead of the one being worked on. */
    private static final int LOOKAHEAD = 24;

    protected final Plugin plugin;
    protected final World world;
    protected final int cx, cz, radius;
    protected final int sea, minY, maxY;

    /** Every chunk touching the circular area, row by row. */
    protected final List<long[]> chunks = new ArrayList<>();
    private final Map<Long, CompletableFuture<Chunk>> pending = new HashMap<>();
    /** When each pending load was requested (ms), to catch loads that never finish. */
    private final Map<Long, Long> requestedAt = new HashMap<>();
    /** A background load that takes longer than this is replaced by a direct load. */
    private static final long LOAD_TIMEOUT_MS = 10_000;
    private final LinkedHashSet<Long> ticketed = new LinkedHashSet<>();
    private int streamPos;
    private boolean cleaned;
    /** Chunks kept loaded behind the work front: about three chunk columns of the area. */
    private final int keepLoaded;

    @FunctionalInterface
    interface ChunkWork {
        void run(int chunkX, int chunkZ);
    }

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
        long reach = (long) (radius + pad + 23) * (radius + pad + 23); // + chunk diagonal
        for (int x = (cx - radius - pad) >> 4; x <= (cx + radius + pad) >> 4; x++) {
            for (int z = (cz - radius - pad) >> 4; z <= (cz + radius + pad) >> 4; z++) {
                long ddx = (x << 4) + 8 - cx, ddz = (z << 4) + 8 - cz;
                if (ddx * ddx + ddz * ddz <= reach) {
                    chunks.add(new long[]{x, z});
                }
            }
        }
        int column = ((radius + pad) * 2 >> 4) + 3;
        this.keepLoaded = column * 3 + LOOKAHEAD + 16;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * Makes sure the chunk is (being) loaded and ticketed.
     *
     * @return true when it is loaded and safe to edit right now
     */
    protected final boolean request(int x, int z) {
        long k = key(x, z);
        if (ticketed.contains(k)) {
            return true;
        }
        if (cleaned) {
            return false;
        }
        // All bookkeeping happens here, on the main thread. The load future may complete on a chunk
        // worker thread, so it must not touch our state (that raced and could stall a job forever).
        if (world.isChunkLoaded(x, z)) {
            pending.remove(k);
            requestedAt.remove(k);
            world.getChunkAt(x, z).addPluginChunkTicket(plugin);
            ticketed.add(k);
            return true;
        }
        CompletableFuture<Chunk> future = pending.get(k);
        if (future == null) {
            pending.put(k, world.getChunkAtAsync(x, z, true));
            requestedAt.put(k, System.currentTimeMillis());
            return false;
        }
        if (!future.isDone()) {
            Long since = requestedAt.get(k);
            if (since != null && System.currentTimeMillis() - since > LOAD_TIMEOUT_MS) {
                // ask again in the background (never load directly: that can freeze the server)
                plugin.getLogger().warning("[islands] Chunk " + x + "," + z + " is slow to load, requesting it again.");
                pending.put(k, world.getChunkAtAsync(x, z, true));
                requestedAt.put(k, System.currentTimeMillis());
            }
            return false; // still loading
        }
        requestedAt.remove(k);
        pending.remove(k);
        if (future.isCompletedExceptionally()) {
            plugin.getLogger().warning("[islands] Loading chunk " + x + "," + z + " failed, trying again.");
            return false; // re-requested on the next call
        }
        // Fully generated now, but it may have been unloaded again before this tick: claim it with a ticket
        // right away (reloads it from the cache/disk, which is quick for a generated chunk).
        world.addPluginChunkTicket(x, z, plugin);
        ticketed.add(k);
        return true;
    }

    /** Requests every chunk of a block rectangle. @return true when all of them are loaded. */
    protected final boolean requestArea(int minX, int minZ, int maxX, int maxZ) {
        boolean all = true;
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                all &= request(x, z);
            }
        }
        return all;
    }

    protected final void release(int x, int z) {
        if (ticketed.remove(key(x, z))) {
            world.removePluginChunkTicket(x, z, plugin);
        }
    }

    /** Releases the oldest tickets until at most "max" chunks are held. */
    protected final void trimTickets(int max) {
        Iterator<Long> it = ticketed.iterator();
        while (ticketed.size() > max && it.hasNext()) {
            long k = it.next();
            it.remove();
            world.removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
        }
    }

    /**
     * Runs "work" once for every chunk of the area, in order, loading chunks ahead and releasing each
     * one when it is done. Call again on the next tick when it returns false.
     *
     * @return true once every chunk was processed
     */
    protected final boolean stream(long deadline, ChunkWork work) {
        while (streamPos < chunks.size()) {
            int end = Math.min(chunks.size(), streamPos + LOOKAHEAD);
            for (int i = streamPos + 1; i < end; i++) {
                request((int) chunks.get(i)[0], (int) chunks.get(i)[1]);
            }
            long[] c = chunks.get(streamPos);
            int x = (int) c[0], z = (int) c[1];
            // Edit a chunk only when it AND its 8 neighbours are fully loaded. Editing next to chunks that
            // are still generating stalls Minecraft's light engine (the neighbour never finishes loading).
            boolean ready = true;
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    ready &= request(x + ox, z + oz);
                }
            }
            if (!ready) {
                return false; // still loading
            }
            work.run(x, z);
            streamPos++;
            trimTickets(keepLoaded); // let go of chunks a few columns behind the work front
            if (System.nanoTime() > deadline) {
                return false;
            }
        }
        return true;
    }

    protected final void resetStream() {
        streamPos = 0;
    }

    protected final double streamProgress() {
        return chunks.isEmpty() ? 1 : streamPos / (double) chunks.size();
    }

    /** Makes clients re-read one chunk (biome changes show up without relogging). */
    @SuppressWarnings("deprecation")
    protected final void refreshChunk(int x, int z) {
        if (world.isChunkLoaded(x, z)) {
            world.refreshChunk(x, z);
        }
    }

    @Override
    public void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        for (long k : ticketed) {
            world.removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
        }
        ticketed.clear();
        pending.clear();
        requestedAt.clear();
    }
}
