package dev.aquatic.world;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewRelation;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.api.IslandStatus;
import dev.aquatic.api.IslandType;
import dev.aquatic.api.event.CrewDisbandEvent;
import dev.aquatic.api.event.IslandDiscoverEvent;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Islands of the Grand Line: procedural generation, registration, claiming, protection, conquest,
 * discovery and passive income. Provides {@link IslandService}.
 */
public final class IslandsModule extends AquaticModule implements IslandService, Listener {

    /** What a player is trying to do on an island (see {@link #denyReason}). */
    enum Action {
        BREAK, PLACE, CONTAINER
    }

    private record QueuedJob(WorldJob job, String islandId, CommandSender sender, Runnable onDone) {
    }

    private static final int CELL_SHIFT = 6;

    private final Map<String, IslandImpl> islands = new LinkedHashMap<>();
    private final Map<String, Map<Long, List<IslandImpl>>> index = new HashMap<>();
    private final Map<IslandType, TypeSettings> types = new EnumMap<>(IslandType.class);
    private final Map<UUID, Set<String>> discoveries = new HashMap<>();
    private final Map<String, Long> crewLastSeen = new HashMap<>();
    private final Map<UUID, String> presence = new HashMap<>();
    private final Map<UUID, long[]> lastBlock = new HashMap<>();
    /** Last enter/leave message per player: "key:islandId" -> time (ms), against border flicker spam. */
    private final Map<UUID, String> lastPresenceKey = new HashMap<>();
    private final Map<UUID, Long> lastPresenceAt = new HashMap<>();
    /** Generation progress bar per command sender (a boss bar, so it never hides other action bar messages). */
    private final Map<UUID, net.kyori.adventure.bossbar.BossBar> jobBars = new HashMap<>();
    private final Map<UUID, Long> lastDenyMessage = new HashMap<>();
    private final Deque<QueuedJob> jobs = new ArrayDeque<>();
    private QueuedJob currentJob;
    private int jobTicks;
    private String spawnIslandId;
    private IslandUpgrades upgrades;
    private IslandFacilities facilities;
    private TidewatchGarrison garrison;
    private Ships ships;
    /** Exact spawn point set with /island admin setspawn ("world;x;y;z;yaw;pitch"), or null. */
    private String spawnPoint;
    private boolean dirty;
    private volatile boolean markersDirty;
    private ConquestManager conquest;
    private BlueMapHook blueMap;

    @Override
    public String id() {
        return "islands";
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onEnable() {
        islands.clear();
        index.clear();
        types.clear();
        discoveries.clear();
        crewLastSeen.clear();
        presence.clear();
        lastBlock.clear();
        lastDenyMessage.clear();
        jobs.clear();
        currentJob = null;

        for (IslandType type : IslandType.values()) {
            ConfigurationSection section = config().getConfigurationSection("types." + type.name());
            if ((section == null || section.getKeys(false).isEmpty()) && config().getDefaults() != null) {
                // a type added in a newer version (e.g. PLAINS) is not in an older islands.yml yet
                section = config().getDefaults().getConfigurationSection("types." + type.name());
            }
            types.put(type, TypeSettings.load(type, section, logger()));
        }
        loadAll();

        registerService(IslandService.class, this);
        registerListener(this);
        registerListener(new IslandProtectionListener(this));
        upgrades = new IslandUpgrades(this);
        registerListener(upgrades);
        facilities = new IslandFacilities(this);
        facilities.schematicFolder();
        registerListener(facilities);
        runTimer(facilities::tick, 10L, 10L);
        runTimer(facilities::tickJobs, 1L, 1L);
        garrison = new TidewatchGarrison(this);
        garrison.load();
        registerListener(garrison);
        runTimer(garrison::tick, 60L, 60L);
        runTimer(garrison::tickPowers, 20L, 20L);
        ships = new Ships(this);
        ships.load();
        registerListener(ships);
        registerCommand(ships.command());
        runTimer(ships::tick, 100L, 40L);
        runTimer(() -> upgrades.tickHarbor(presence), 40L, 40L);
        conquest = new ConquestManager(this);
        registerCommand(new IslandCommands(this).build());
        registerSpawnCommand();

        runTimer(this::tickJobs, 1L, 1L);
        spotPool.clear();
        probing = false;
        runTimer(this::refillSpotPool, 100L, 100L);
        long presenceInterval = Math.max(1, config().getLong("presence.check-interval-ticks", 10));
        runTimer(this::pollPresence, 20L, presenceInterval);
        runTimer(conquest::tick, 20L, 20L);
        long autosave = Math.max(10, config().getLong("autosave-seconds", 120)) * 20L;
        runTimer(this::saveIfDirty, autosave, autosave);
        runTimer(this::refreshMarkers, 100L, 100L);
        runTimer(this::releaseOrphanedIslands, 200L, 1200L);
        if (config().getBoolean("income.enabled", true)) {
            long interval = Math.max(1, config().getLong("income.interval-minutes", 30)) * 1200L;
            runTimer(this::payIncome, interval, interval);
        }

        if (config().getBoolean("bluemap.enabled", true) && Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            try {
                blueMap = new BlueMapHook(this);
                blueMap.enable();
            } catch (Throwable t) {
                blueMap = null;
                logger().log(Level.WARNING, "[islands] BlueMap integration could not be started", t);
            }
        }
        markersDirty = true;
    }

    @Override
    protected void onDisable() {
        if (facilities != null) {
            facilities.clear();
        }
        if (ships != null) {
            ships.shutdown();
        }
        if (garrison != null) {
            garrison.shutdown();
        }
        cancelSpawnRuns();
        hideAllJobBars();
        lastPresenceKey.clear();
        lastPresenceAt.clear();
        if (currentJob != null) {
            currentJob.job().cleanup();
            currentJob = null;
        }
        for (QueuedJob q : jobs) {
            q.job().cleanup();
        }
        jobs.clear();
        if (conquest != null) {
            conquest.shutdown();
        }
        if (blueMap != null) {
            try {
                blueMap.disable();
            } catch (Throwable ignored) {
            }
            blueMap = null;
        }
        saveAll();
    }

    // ------------------------------------------------------------------ persistence

    private void loadAll() {
        YamlConfiguration data = loadData("islands");
        ConfigurationSection sec = data.getConfigurationSection("islands");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                IslandImpl is = s == null ? null : IslandImpl.load(id, s);
                if (is == null) {
                    logger().warning("[islands] Skipping broken island entry '" + id + "' in islands.yml");
                    continue;
                }
                islands.put(id, is);
            }
        }
        rebuildIndex();
        YamlConfiguration state = loadData("state");
        spawnIslandId = state.getString("spawn-island");
        spawnPoint = state.getString("spawn-point");
        ConfigurationSection seen = state.getConfigurationSection("crew-last-seen");
        if (seen != null) {
            for (String crew : seen.getKeys(false)) {
                crewLastSeen.put(crew, seen.getLong(crew));
            }
        }
        YamlConfiguration disc = loadData("discoveries");
        for (String key : disc.getKeys(false)) {
            try {
                discoveries.put(UUID.fromString(key), new LinkedHashSet<>(disc.getStringList(key)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        dirty = false;
    }

    private void saveAll() {
        YamlConfiguration data = new YamlConfiguration();
        for (IslandImpl is : islands.values()) {
            is.save(data.createSection("islands." + is.id));
        }
        saveData("islands", data);
        YamlConfiguration state = new YamlConfiguration();
        state.set("spawn-island", spawnIslandId);
        state.set("spawn-point", spawnPoint);
        for (Map.Entry<String, Long> e : crewLastSeen.entrySet()) {
            state.set("crew-last-seen." + e.getKey(), e.getValue());
        }
        saveData("state", state);
        YamlConfiguration disc = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> e : discoveries.entrySet()) {
            disc.set(e.getKey().toString(), new ArrayList<>(e.getValue()));
        }
        saveData("discoveries", disc);
        dirty = false;
    }

    private void saveIfDirty() {
        if (dirty) {
            saveAll();
        }
    }

    void markDirty() {
        dirty = true;
        markersDirty = true;
    }

    /** Asks for a BlueMap marker refresh on the next marker cycle (thread safe enough: a plain flag). */
    void requestMarkerRefresh() {
        markersDirty = true;
    }

    ConquestManager conquest() {
        return conquest;
    }

    private void refreshMarkers() {
        if (markersDirty && blueMap != null) {
            try {
                blueMap.refresh();
            } catch (Throwable t) {
                logger().log(Level.WARNING, "[islands] BlueMap marker update failed", t);
            }
        }
        markersDirty = false;
    }

    // ------------------------------------------------------------------ spatial index

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    private void rebuildIndex() {
        index.clear();
        for (IslandImpl is : islands.values()) {
            Map<Long, List<IslandImpl>> grid = index.computeIfAbsent(is.world, k -> new HashMap<>());
            for (int cx = (is.x - is.radius) >> CELL_SHIFT; cx <= (is.x + is.radius) >> CELL_SHIFT; cx++) {
                for (int cz = (is.z - is.radius) >> CELL_SHIFT; cz <= (is.z + is.radius) >> CELL_SHIFT; cz++) {
                    grid.computeIfAbsent(cellKey(cx, cz), k -> new ArrayList<>(2)).add(is);
                }
            }
        }
    }

    IslandImpl at(String world, int x, int z) {
        Map<Long, List<IslandImpl>> grid = index.get(world);
        if (grid == null) {
            return null;
        }
        List<IslandImpl> cell = grid.get(cellKey(x >> CELL_SHIFT, z >> CELL_SHIFT));
        if (cell == null) {
            return null;
        }
        for (int i = 0; i < cell.size(); i++) {
            IslandImpl is = cell.get(i);
            if (is.contains(x, z)) {
                return is;
            }
        }
        return null;
    }

    IslandImpl at(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        return at(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
    }

    IslandImpl at(Block block) {
        return at(block.getWorld().getName(), block.getX(), block.getZ());
    }

    // ------------------------------------------------------------------ IslandService

    @Override
    public Island islandAt(Location location) {
        return at(location);
    }

    @Override
    public Island islandById(String id) {
        return id == null ? null : islands.get(id.toLowerCase(Locale.ROOT));
    }

    @Override
    public Collection<Island> islands() {
        return Collections.unmodifiableCollection(new ArrayList<Island>(islands.values()));
    }

    @Override
    public Collection<Island> islandsOwnedBy(String crewId) {
        List<Island> out = new ArrayList<>();
        if (crewId != null) {
            for (IslandImpl is : islands.values()) {
                if (crewId.equals(is.ownerCrewId)) {
                    out.add(is);
                }
            }
        }
        return out;
    }

    @Override
    public Island createIsland(String name, IslandType type, Location center, int radius, boolean generate) {
        return createIsland(name, type, center, radius, generate, null);
    }

    IslandImpl createIsland(String name, IslandType type, Location center, int radius, boolean generate, CommandSender feedback) {
        if (name == null || type == null || center == null || center.getWorld() == null) {
            return null;
        }
        name = name.trim();
        String id = slug(name);
        if (id.isEmpty() || islands.containsKey(id) || findByName(name) != null) {
            return null;
        }
        radius = Math.max(4, radius);
        World world = center.getWorld();
        int x = center.getBlockX(), z = center.getBlockZ();
        if (overlaps(world.getName(), x, z, radius, null)) {
            return null;
        }
        int y = generate ? world.getSeaLevel() : center.getBlockY();
        IslandImpl is = new IslandImpl(id, name, type, world.getName(), x, y, z, radius, ThreadLocalRandom.current().nextLong());
        is.status = settings(type).defaultStatus;
        islands.put(id, is);
        rebuildIndex();
        markDirty();
        if (generate) {
            queueGeneration(is, feedback);
        }
        return is;
    }

    @Override
    public boolean removeIsland(String id, boolean restoreOcean) {
        return removeIsland(id, restoreOcean, null);
    }

    boolean removeIsland(String id, boolean restoreOcean, CommandSender feedback) {
        IslandImpl is = id == null ? null : islands.get(id.toLowerCase(Locale.ROOT));
        if (is == null) {
            return false;
        }
        cancelJobs(is.id);
        removeFlagBlocks(is);
        islands.remove(is.id);
        rebuildIndex();
        if (is.id.equals(spawnIslandId)) {
            spawnIslandId = null;
        }
        conquest.forget(is.id);
        presence.values().removeIf(is.id::equals);
        markDirty();
        World world = is.bukkitWorld();
        if (restoreOcean && world != null) {
            enqueue(restoreJob(is, world), null, feedback, null);
        }
        return true;
    }

    @Override
    public void setOwner(Island island, String crewId) {
        IslandImpl is = own(island);
        if (is == null) {
            return;
        }
        conquest.forget(is.id);
        if (crewId == null) {
            is.ownerCrewId = null;
            if (is.status == IslandStatus.OCCUPIED) {
                is.status = IslandStatus.UNCLAIMED;
            }
            removeFlagBlocks(is);
        } else {
            is.ownerCrewId = crewId;
            is.status = IslandStatus.OCCUPIED;
            is.claimedAt = System.currentTimeMillis();
            World world = is.bukkitWorld();
            if (world != null) {
                if (is.hasFlag) {
                    placeFlag(is, is.flagX, is.flagY, is.flagZ);
                } else {
                    placeFlag(is, is.x, world.getHighestBlockYAt(is.x, is.z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, is.z);
                }
            }
        }
        markDirty();
    }

    @Override
    public void setStatus(Island island, IslandStatus status) {
        IslandImpl is = own(island);
        if (is == null || status == null) {
            return;
        }
        if (status == IslandStatus.OCCUPIED) {
            if (is.ownerCrewId == null) {
                return; // an occupied island needs an owner: use setOwner
            }
            is.status = IslandStatus.OCCUPIED;
        } else {
            conquest.forget(is.id);
            is.ownerCrewId = null;
            is.status = status;
            removeFlagBlocks(is);
        }
        markDirty();
    }

    // ------------------------------------------------------------------ free ocean spots
    //
    // Checking that a spot is deep ocean reads terrain heights, and reading an ungenerated chunk makes the
    // server generate it on the main thread (seconds of lag for far-away spots). So spots are probed with
    // asynchronously loaded chunks, and a small pool of pre-checked spots is kept ready in the background.

    /** Pre-checked deep-ocean spots: {x, z, radius they were checked for}. */
    private final Deque<int[]> spotPool = new ArrayDeque<>();
    private boolean probing;

    private int poolRadius() {
        return Math.max(16, config().getInt("ocean-area.prefetch-radius", 80));
    }

    /** Background task: keeps up to ocean-area.prefetch-spots checked spots ready. */
    private void refillSpotPool() {
        int target = Math.max(0, config().getInt("ocean-area.prefetch-spots", 4));
        if (probing || spotPool.size() >= target || queuedJobs() > 0) {
            return;
        }
        probing = true;
        int radius = poolRadius();
        probeAsync(radius, 6, loc -> {
            probing = false;
            if (loc != null) {
                spotPool.add(new int[]{loc.getBlockX(), loc.getBlockZ(), radius});
            }
        });
    }

    /** Takes a pooled spot that is big enough and still free, or null. */
    private Location takePooledSpot(World world, int radius) {
        int spacing = Math.max(0, config().getInt("ocean-area.min-spacing", 150));
        for (var it = spotPool.iterator(); it.hasNext(); ) {
            int[] s = it.next();
            if (overlaps(world.getName(), s[0], s[1], s[2] + spacing, null)) {
                it.remove(); // an island was placed near it in the meantime
                continue;
            }
            if (s[2] >= radius) {
                it.remove();
                return new Location(world, s[0] + 0.5, world.getSeaLevel(), s[1] + 0.5);
            }
        }
        return null;
    }

    World oceanWorld() {
        return Bukkit.getWorld(config().getString("ocean-area.world", "world"));
    }

    /** A random candidate in the ocean area that does not overlap an island, or null after many tries. */
    /**
     * A spot for a new island. "rings" (the default): close to the centre first - a ring at ring-start blocks out,
     * the next ring ring-step further, and so on - so the world fills from spawn outwards; a few islands
     * (scatter) land anywhere for a natural look. "random": anywhere in the square, like before 2.26.0.
     * attempt = how many spots were already tried for this island (failed tries move outwards).
     */
    private int[] randomCandidate(World world, int radius, int attempt) {
        int centerX = config().getInt("ocean-area.center-x"), centerZ = config().getInt("ocean-area.center-z");
        int area = Math.max(radius + 1, config().getInt("ocean-area.radius", 3000));
        int spacing = Math.max(0, config().getInt("ocean-area.min-spacing", 150));
        int range = Math.max(1, area - radius);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean rings = !"random".equalsIgnoreCase(config().getString("ocean-area.placement", "rings"));
        int start = Math.max(0, config().getInt("ocean-area.ring-start", 500));
        int step = Math.max(50, config().getInt("ocean-area.ring-step", 350));
        double scatter = Math.max(0, Math.min(1, config().getDouble("ocean-area.scatter", 0.12)));
        for (int i = 0; i < 200; i++) {
            int x, z;
            if (rings) {
                double far = Math.max(start + 1, range);
                double d = random.nextDouble() < scatter ? start + random.nextDouble() * (far - start)
                        : start + radius + (attempt / 4 + i / 25) * (double) step + random.nextDouble() * step;
                if (d > far) {
                    d = start + random.nextDouble() * (far - start);
                }
                double a = random.nextDouble() * Math.PI * 2;
                x = centerX + (int) Math.round(Math.cos(a) * d);
                z = centerZ + (int) Math.round(Math.sin(a) * d);
            } else {
                x = centerX + random.nextInt(-range, range + 1);
                z = centerZ + random.nextInt(-range, range + 1);
            }
            if (!overlaps(world.getName(), x, z, radius + spacing, null)) {
                return new int[]{x, z};
            }
        }
        return null;
    }

    private static int[][] samplePoints(int x, int z, int radius) {
        int half = radius / 2;
        return new int[][]{{x, z}, {x + half, z}, {x - half, z}, {x, z + half}, {x, z - half}};
    }

    private boolean deepEnough(World world, int x, int z, int radius) {
        int limit = world.getSeaLevel() - Math.max(1, config().getInt("ocean-area.min-water-depth", 8));
        for (int[] p : samplePoints(x, z, radius)) {
            if (world.getHighestBlockYAt(p[0], p[1], HeightMap.OCEAN_FLOOR) > limit) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds a free deep-ocean spot without blocking the main thread: chunks are loaded asynchronously
     * and the callback runs on the main thread with the spot, or null when none was found.
     */
    public void findFreeOceanSpotAsync(int radius, java.util.function.Consumer<Location> callback) {
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<Location> once = loc -> {
            if (fired.compareAndSet(false, true)) {
                callback.accept(loc);
            }
        };
        findFreeOceanSpotAsyncImpl(radius, once);
    }

    private void findFreeOceanSpotAsyncImpl(int radius, java.util.function.Consumer<Location> callback) {
        World world = oceanWorld();
        if (world == null) {
            callback.accept(null);
            return;
        }
        Location pooled = takePooledSpot(world, radius);
        if (pooled != null) {
            callback.accept(pooled);
            return;
        }
        probeAsync(radius, Math.max(1, config().getInt("ocean-area.attempts", 80)), callback);
    }

    private void probeAsync(int radius, int attemptsLeft, java.util.function.Consumer<Location> callback) {
        probeAsync(radius, attemptsLeft, 0, callback);
    }

    private void probeAsync(int radius, int attemptsLeft, int attempt, java.util.function.Consumer<Location> callback) {
        World world = oceanWorld();
        if (!isEnabled() || world == null || attemptsLeft <= 0) {
            callback.accept(null);
            return;
        }
        int[] c = randomCandidate(world, radius, attempt);
        if (c == null) {
            callback.accept(null);
            return;
        }
        if (!config().getBoolean("ocean-area.require-ocean", true)) {
            callback.accept(new Location(world, c[0] + 0.5, world.getSeaLevel(), c[1] + 0.5));
            return;
        }
        int[][] pts = samplePoints(c[0], c[1], radius);
        java.util.concurrent.CompletableFuture<?>[] loads = new java.util.concurrent.CompletableFuture<?>[pts.length];
        for (int i = 0; i < pts.length; i++) {
            loads[i] = world.getChunkAtAsync(pts[i][0] >> 4, pts[i][1] >> 4);
        }
        java.util.concurrent.CompletableFuture.allOf(loads).whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin(), () -> {
            if (!isEnabled()) {
                callback.accept(null);
                return;
            }
            // chunks are loaded now, so these height reads are cheap; re-check overlap (time has passed)
            int spacing = Math.max(0, config().getInt("ocean-area.min-spacing", 150));
            if (error == null && !overlaps(world.getName(), c[0], c[1], radius + spacing, null)
                    && deepEnough(world, c[0], c[1], radius)) {
                callback.accept(new Location(world, c[0] + 0.5, world.getSeaLevel(), c[1] + 0.5));
            } else {
                probeAsync(radius, attemptsLeft - 1, attempt + 1, callback);
            }
        }));
    }

    /**
     * Synchronous variant required by the IslandService API. Uses a pre-checked spot from the background
     * pool when possible; otherwise it only probes chunks that are already generated, so it never freezes
     * the server. Prefer {@link #findFreeOceanSpotAsync}.
     */
    @Override
    public Location findFreeOceanSpot(int radius) {
        World world = oceanWorld();
        if (world == null) {
            return null;
        }
        Location pooled = takePooledSpot(world, radius);
        if (pooled != null) {
            return pooled;
        }
        return findFreeOceanSpotGenerated(world, radius);
    }

    private Location findFreeOceanSpotGenerated(World world, int radius) {
        int centerX = config().getInt("ocean-area.center-x"), centerZ = config().getInt("ocean-area.center-z");
        int area = Math.max(radius + 1, config().getInt("ocean-area.radius", 3000));
        int spacing = Math.max(0, config().getInt("ocean-area.min-spacing", 150));
        int attempts = Math.max(1, config().getInt("ocean-area.attempts", 80));
        boolean requireOcean = config().getBoolean("ocean-area.require-ocean", true);
        int minDepth = Math.max(1, config().getInt("ocean-area.min-water-depth", 8));
        int range = Math.max(1, area - radius);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int oceanChecks = 0;
        for (int i = 0; i < attempts; i++) {
            int x = centerX + random.nextInt(-range, range + 1);
            int z = centerZ + random.nextInt(-range, range + 1);
            if (overlaps(world.getName(), x, z, radius + spacing, null)) {
                continue;
            }
            if (requireOcean) {
                // sampling loads/generates chunks, so cap the number of expensive probes per call
                if (++oceanChecks > 24) {
                    break;
                }
                int limit = world.getSeaLevel() - minDepth;
                int half = radius / 2;
                boolean generated = true;
                for (int[] p : samplePoints(x, z, radius)) {
                    generated &= world.isChunkGenerated(p[0] >> 4, p[1] >> 4);
                }
                if (!generated) {
                    continue; // never generate chunks synchronously here
                }
                if (world.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR) > limit
                        || world.getHighestBlockYAt(x + half, z, HeightMap.OCEAN_FLOOR) > limit
                        || world.getHighestBlockYAt(x - half, z, HeightMap.OCEAN_FLOOR) > limit
                        || world.getHighestBlockYAt(x, z + half, HeightMap.OCEAN_FLOOR) > limit
                        || world.getHighestBlockYAt(x, z - half, HeightMap.OCEAN_FLOOR) > limit) {
                    continue;
                }
            }
            return new Location(world, x + 0.5, world.getSeaLevel(), z + 0.5);
        }
        return null;
    }

    private IslandImpl own(Island island) {
        if (island == null) {
            return null;
        }
        IslandImpl is = islands.get(island.id());
        return is == island ? is : null;
    }

    /** True when a circle would touch another island (the island "ignore" itself is skipped). */
    boolean overlaps(String world, int x, int z, int radius, IslandImpl ignore) {
        for (IslandImpl other : islands.values()) {
            if (other == ignore || !other.world.equals(world)) {
                continue;
            }
            double min = radius + other.radius;
            double dx = other.x - x, dz = other.z - z;
            if (dx * dx + dz * dz < min * min) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ lookups & helpers

    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        s = s.replaceAll("^_+|_+$", "");
        return s;
    }

    Collection<IslandImpl> all() {
        return islands.values();
    }

    IslandImpl byId(String id) {
        return islands.get(id);
    }

    IslandImpl findByName(String name) {
        for (IslandImpl is : islands.values()) {
            if (is.name.equalsIgnoreCase(name)) {
                return is;
            }
        }
        return null;
    }

    /** Resolves a command argument: island id, slug of the name, or exact name. */
    IslandImpl find(String arg) {
        IslandImpl is = islands.get(arg.toLowerCase(Locale.ROOT));
        if (is == null) {
            is = islands.get(slug(arg));
        }
        if (is == null) {
            is = findByName(arg.replace('_', ' '));
        }
        return is;
    }

    TypeSettings settings(IslandType type) {
        return types.get(type);
    }

    @Override
    public Component rarityDisplay(IslandType type) {
        return rarityName(type);
    }

    /** The coloured rarity name of an island type, e.g. "Rare". */
    Component rarityName(IslandType type) {
        String r = settings(type).rarity;
        return Text.mm(config().getString("rarities." + r + ".display", r));
    }

    Component typeName(IslandType type) {
        String raw = config().getString("types." + type.name() + ".display-name");
        if (raw == null) {
            String lower = type.name().toLowerCase(Locale.ROOT);
            raw = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
        return Text.mm(raw);
    }

    Component crewDisplay(String crewId) {
        CrewService crews = service(CrewService.class);
        Crew crew = crews == null || crewId == null ? null : crews.crewById(crewId);
        return crew != null ? crew.displayName() : rawMsg("unknown-crew");
    }

    /** "Territory of X" / "Unclaimed" / "Neutral waters" */
    Component ownerLine(IslandImpl is) {
        return switch (is.status) {
            case OCCUPIED -> rawMsg("owner-occupied", "crew", crewDisplay(is.ownerCrewId));
            case NEUTRAL -> rawMsg("owner-neutral");
            default -> rawMsg("owner-unclaimed");
        };
    }

    int ownedCount(String crewId) {
        int n = 0;
        for (IslandImpl is : islands.values()) {
            if (crewId.equals(is.ownerCrewId)) {
                n++;
            }
        }
        return n;
    }

    String spawnIslandId() {
        return spawnIslandId;
    }

    void setSpawnIsland(IslandImpl is) {
        spawnIslandId = is.id;
        setStatus(is, IslandStatus.NEUTRAL);
        markDirty();
    }

    /** Stores the exact spawn point (and sets the world spawn there). */
    void setSpawnPoint(Location l) {
        spawnPoint = l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
        l.getWorld().setSpawnLocation(l);
        markDirty();
    }

    // ------------------------------------------------------------------ /spawn

    private final Map<UUID, org.bukkit.scheduler.BukkitTask> spawnWarmups = new HashMap<>();
    private final Map<UUID, Long> spawnCooldowns = new HashMap<>();

    /** Where /spawn goes: the exact /setspawn point, else the spawn island, else the capital, else the world spawn. */
    Location spawnDestination() {
        Location set = spawnLocation();
        if (set != null) {
            return set;
        }
        for (IslandImpl is : islands.values()) {
            if (is.type == IslandType.CAPITAL) {
                Location spot = teleportSpot(is);
                if (spot != null) {
                    return spot;
                }
            }
        }
        World w = Bukkit.getWorld(config().getString("ocean-area.world", "world"));
        return w == null ? null : w.getSpawnLocation();
    }

    private void registerSpawnCommand() {
        if (!config().getBoolean("spawn.command.enabled", true)) {
            return;
        }
        dev.aquatic.command.AquaticCommand cmd = new dev.aquatic.command.AquaticCommand("spawn", "Sail back to spawn / the capital");
        cmd.defaultExecutor((sender, args) -> {
            Player p = dev.aquatic.command.AquaticCommand.player(sender);
            if (args.length > 0 && sender.hasPermission("aquatic.islands.admin")) {
                Player target = dev.aquatic.command.AquaticCommand.onlinePlayer(args[0]);
                Location to = spawnDestination();
                if (to == null) {
                    throw new dev.aquatic.command.CommandFail(rawMsg("spawn-none"));
                }
                target.teleportAsync(to);
                send(sender, "spawn-sent", "player", target.getName());
                return;
            }
            startSpawnWarmup(p);
        });
        cmd.takeOver(config().getBoolean("spawn.command.take-over", true));
        registerCommand(cmd);
    }

    private void startSpawnWarmup(Player p) {
        startTravel(p, this::spawnDestination, "spawn-arrived");
    }

    /** /island home [island]: to an island your crew owns (next to its flag), with the same warmup as /spawn. */
    void islandHome(Player p, String[] args) {
        CrewService crews = service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewOf(p.getUniqueId());
        if (crew == null) {
            throw new dev.aquatic.command.CommandFail(rawMsg("home-no-crew"));
        }
        List<IslandImpl> owned = new ArrayList<>();
        for (Island is : islandsOwnedBy(crew.id())) {
            IslandImpl impl = islands.get(is.id());
            if (impl != null) {
                owned.add(impl);
            }
        }
        if (owned.isEmpty()) {
            throw new dev.aquatic.command.CommandFail(rawMsg("home-no-island"));
        }
        if (owned.size() > 1 && args.length == 0) {
            // several islands: let the player pick one (clickable list), never guess
            owned.sort(java.util.Comparator.comparing(is -> is.name.toLowerCase(java.util.Locale.ROOT)));
            send(p, "home-choose", "count", owned.size());
            for (IslandImpl is : owned) {
                p.sendMessage(rawMsg("home-choose-line", "island", is.name, "type", is.type.name().charAt(0)
                        + is.type.name().substring(1).toLowerCase(java.util.Locale.ROOT))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/island home " + is.id))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(rawMsg("home-choose-hover", "island", is.name))));
            }
            return;
        }
        IslandImpl target = owned.get(0);
        if (args.length > 0) {
            String want = String.join(" ", args);
            target = null;
            for (IslandImpl is : owned) {
                if (is.id.equalsIgnoreCase(want) || is.name.equalsIgnoreCase(want)) {
                    target = is;
                }
            }
            if (target == null) {
                throw new dev.aquatic.command.CommandFail(rawMsg("home-unknown", "island", want));
            }
        }
        IslandImpl home = target;
        startTravel(p, () -> homeSpot(home), "home-arrived", "island", home.name);
    }

    List<String> ownIslandNames(Player p) {
        CrewService crews = service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewOf(p.getUniqueId());
        List<String> out = new ArrayList<>();
        if (crew != null) {
            for (Island is : islandsOwnedBy(crew.id())) {
                out.add(is.name());
            }
        }
        return out;
    }

    private Location homeSpot(IslandImpl is) {
        Location flag = is.flagLocation();
        if (flag != null && flag.getWorld() != null) {
            int x = flag.getBlockX() + 2, z = flag.getBlockZ();
            int y = flag.getWorld().getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) + 1;
            return new Location(flag.getWorld(), x + 0.5, y, z + 0.5);
        }
        return teleportSpot(is);
    }

    private void startTravel(Player p, java.util.function.Supplier<Location> dest, String arrivedKey, Object... ph) {
        Location to = dest.get();
        if (to == null) {
            throw new dev.aquatic.command.CommandFail(rawMsg("spawn-none"));
        }
        UUID id = p.getUniqueId();
        boolean bypass = p.hasPermission("aquatic.islands.admin");
        dev.aquatic.api.CombatService combat = service(dev.aquatic.api.CombatService.class);
        if (!bypass && combat != null && combat.inCombat(id)) {
            throw new dev.aquatic.command.CommandFail(rawMsg("travel-combat"));
        }
        long cooldown = Math.max(0, config().getLong("spawn.command.cooldown-seconds", 30)) * 1000L;
        Long last = spawnCooldowns.get(id);
        if (!bypass && last != null && System.currentTimeMillis() - last < cooldown) {
            throw new dev.aquatic.command.CommandFail(rawMsg("spawn-cooldown", "seconds",
                    (cooldown - (System.currentTimeMillis() - last) + 999) / 1000));
        }
        if (spawnWarmups.containsKey(id)) {
            throw new dev.aquatic.command.CommandFail(rawMsg("spawn-already"));
        }
        int warmup = bypass ? 0 : Math.max(0, config().getInt("spawn.command.warmup-seconds", 3));
        if (warmup == 0) {
            p.teleportAsync(to);
            spawnCooldowns.put(id, System.currentTimeMillis());
            send(p, arrivedKey, ph);
            return;
        }
        Location start = p.getLocation();
        double health = p.getHealth();
        send(p, "spawn-warmup", "seconds", warmup);
        int[] ticks = {0};
        spawnWarmups.put(id, runTimer(() -> {
            if (!p.isOnline()) {
                cancelSpawnWarmup(id, null);
                return;
            }
            if (p.getLocation().getWorld() != start.getWorld() || p.getLocation().distanceSquared(start) > 1.0) {
                cancelSpawnWarmup(id, p);
                return;
            }
            if (p.getHealth() < health) {
                cancelSpawnWarmup(id, p);
                return;
            }
            ticks[0] += 5;
            p.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, p.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0.2);
            if (ticks[0] >= warmup * 20) {
                org.bukkit.scheduler.BukkitTask task = spawnWarmups.remove(id);
                if (task != null) {
                    task.cancel();
                }
                Location now = dest.get();
                p.teleportAsync(now != null ? now : to);
                spawnCooldowns.put(id, System.currentTimeMillis());
                send(p, arrivedKey, ph);
            }
        }, 5L, 5L));
    }

    private void cancelSpawnWarmup(UUID id, Player tell) {
        org.bukkit.scheduler.BukkitTask task = spawnWarmups.remove(id);
        if (task != null) {
            task.cancel();
        }
        if (tell != null) {
            send(tell, "spawn-cancelled");
        }
    }

    Location islandSpawnSpot(IslandImpl is) {
        return teleportSpot(is);
    }

    /** The spawn point: the exact /setspawn spot, else the spawn island, else null. */
    Location spawnLocation() {
        if (spawnPoint != null) {
            String[] p = spawnPoint.split(";");
            World w = p.length == 6 ? Bukkit.getWorld(p[0]) : null;
            if (w != null) {
                try {
                    return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                            Float.parseFloat(p[4]), Float.parseFloat(p[5]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        IslandImpl spawn = spawnIslandId == null ? null : islands.get(spawnIslandId);
        return spawn == null ? null : teleportSpot(spawn);
    }

    Set<String> discoveriesOf(UUID player) {
        Set<String> set = discoveries.get(player);
        return set == null ? Set.of() : set;
    }

    void rename(IslandImpl is, String newName) {
        is.name = newName;
        markDirty();
    }

    void setRadius(IslandImpl is, int radius) {
        is.radius = radius;
        rebuildIndex();
        markDirty();
    }

    /** A safe-ish place to stand at the island centre. */
    Location teleportSpot(IslandImpl is) {
        World world = is.bukkitWorld();
        if (world == null) {
            return null;
        }
        if (is.type == IslandType.CAPITAL) {
            // in front of the throne, looking at it (not on top of it)
            int y = world.getHighestBlockYAt(is.x, is.z + 12, HeightMap.MOTION_BLOCKING) + 1;
            return new Location(world, is.x + 0.5, y, is.z + 12.5, 180f, 0f);
        }
        if (is.type == IslandType.VILLAGE) {
            // in front of the village well (not on its roof)
            int y = world.getHighestBlockYAt(is.x, is.z + 5, HeightMap.MOTION_BLOCKING) + 1;
            return new Location(world, is.x + 0.5, y, is.z + 5.5, 180f, 0f);
        }
        int y = world.getHighestBlockYAt(is.x, is.z, HeightMap.MOTION_BLOCKING) + 1;
        return new Location(world, is.x + 0.5, y, is.z + 0.5);
    }

    @Override
    protected void migrateConfig(org.bukkit.configuration.file.YamlConfiguration file,
                                 org.bukkit.configuration.file.YamlConfiguration defaults, int from) {
        if (from < 1) {
            // 2.4.0: bigger islands, deeper caves, ores on every island
            raise(file, "generation.radius-limit", 750, 1000);
            String s = "generation.island-sizes.";
            raise(file, s + "small.min", 50, 60);
            raise(file, s + "small.max", 100, 150);
            raise(file, s + "medium.min", 100, 150);
            raise(file, s + "medium.max", 175, 275);
            raise(file, s + "large.min", 175, 275);
            raise(file, s + "large.max", 300, 450);
            raise(file, s + "huge.min", 300, 450);
            raise(file, s + "huge.max", 500, 750);
            String c = "generation.terrain.caves.";
            raise(file, c + "min-island-radius", 60, 40);
            raise(file, c + "size", 1.0, 1.4);
            raise(file, c + "depth", 14, 40);
            addMissing(file, defaults, c + "caverns");
        }
        if (from < 2) {
            // 2.6.0: Tidewatch outposts, vault and granary upgrades
            addMissing(file, defaults, "generation.island-type-weights.TIDEWATCH");
            addMissing(file, defaults, "island-names.TIDEWATCH");
            addMissing(file, defaults, "upgrades.types.vault");
            addMissing(file, defaults, "upgrades.types.granary");
        }
        if (from < 3) {
            // 2.6.1: large islands a bit smaller
            raise(file, "generation.island-sizes.large.min", 275, 225);
            raise(file, "generation.island-sizes.large.max", 450, 375);
        }
        if (from < 4) {
            // 2.7.0: a livelier capital town
            raise(file, "types.CAPITAL.town.houses", 16, 30);
            String o = "generation.terrain.ore-veins.";
            raise(file, o + "per-1000-land", 4, 10);
            raise(file, o + "ores", List.of("COAL_ORE:6", "IRON_ORE:5", "COPPER_ORE:4", "GOLD_ORE:1.5", "REDSTONE_ORE:1.5",
                    "LAPIS_ORE:1", "DIAMOND_ORE:0.25", "EMERALD_ORE:0.2"), defaults.getStringList(o + "ores"));
        }
        if (from < 5) {
            // 2.12.0: neutral villages with quest givers
            addMissing(file, defaults, "types.VILLAGE");
            addMissing(file, defaults, "generation.island-type-weights.VILLAGE");
            addMissing(file, defaults, "island-names.VILLAGE");
        }
        if (from < 12) {
            // 2.27.0: enemies fight with the custom weapons; island upkeep; forts lock the waters around them
            Map<String, String[]> gear = new java.util.LinkedHashMap<>();
            gear.put("tidewatch.mobs.recruit.equipment.mainhand", new String[]{"IRON_SWORD", "item:steel_cutlass"});
            gear.put("tidewatch.mobs.rifleman.equipment.mainhand", new String[]{"CROSSBOW", "item:musket"});
            gear.put("tidewatch.mobs.lieutenant.equipment.mainhand", new String[]{"IRON_AXE", "item:boarding_axe"});
            gear.put("tidewatch.mobs.captain.equipment.mainhand", new String[]{"DIAMOND_AXE", "item:storm_saber"});
            gear.put("ships.mobs.deckhand.equipment.mainhand", new String[]{"IRON_SWORD", "item:steel_cutlass"});
            gear.put("ships.mobs.bosun.equipment.mainhand", new String[]{"IRON_AXE", "item:boarding_axe"});
            gear.put("ships.mobs.dread_captain.equipment.mainhand", new String[]{"NETHERITE_SWORD", "item:abyss_cutlass"});
            gear.forEach((path, v) -> raise(file, path, v[0], v[1]));
            if ("SKELETON".equals(file.getString("ships.mobs.marksman.type")) && "BOW".equals(file.getString("ships.mobs.marksman.equipment.mainhand"))) {
                file.set("ships.mobs.marksman", defaults.get("ships.mobs.marksman"));
            }
            addMissing(file, defaults, "income.upkeep");
            addMissing(file, defaults, "tidewatch.lock-radius");
            for (String key : List.of("income-net", "upkeep-unpaid", "upkeep-lost", "claim-tidewatch-region")) {
                addMissing(file, defaults, "messages." + key);
            }
        }
        if (from < 11) {
            // 2.26.0: islands in rings around spawn, ships near players
            for (String k : List.of("placement", "ring-start", "ring-step", "scatter")) {
                addMissing(file, defaults, "ocean-area." + k);
            }
            addMissing(file, defaults, "ships.spawn-distance-min");
            addMissing(file, defaults, "ships.spawn-distance-max");
        }
        if (from < 10) {
            // 2.25.0: ships on the open sea
            addMissing(file, defaults, "ships");
            for (String key : List.of("ship-sighted", "ship-boarded", "ship-crew-left", "ship-cleared", "ship-cleared-you",
                    "ship-bar", "ship-sinking", "ship-protected")) {
                addMissing(file, defaults, "messages." + key);
            }
        }
        if (from < 9) {
            // 2.18.0: new Tidewatch forts (three variants); the strongbox lies in the open yard
            raise(file, "messages.tidewatch-strongbox", "<aqua>The fort's strongbox is buried in the keep. <gray>Dig it up!",
                    defaults.getString("messages.tidewatch-strongbox"));
            if (file.contains("types.TIDEWATCH.fort")) {
                addMissing(file, defaults, "types.TIDEWATCH.fort.variant");
            }
        }
        if (from < 8) {
            // 2.16.3: every Tidewatch soldier gets a random 3D hat and face (only if the garrison was never edited)
            String base = "tidewatch.mobs.";
            if (file.contains("tidewatch.mobs")) {
                for (String mob : List.of("rifleman", "lieutenant", "captain")) {
                    String path = base + mob + ".equipment";
                    Object old = file.get(path + ".helmet");
                    boolean untouched = mob.equals("rifleman") ? old == null
                            : mob.equals("lieutenant") ? "item:tidewatch_hat".equals(old) : "item:tidewatch_captain_hat".equals(old);
                    if (untouched && file.contains(path)) {
                        file.set(path + ".helmet", defaults.getList(path + ".helmet"));
                    }
                }
            }
        }
        if (from < 7) {
            // 2.14.0: builds from schematic files
            addMissing(file, defaults, "schematics");
            addMissing(file, defaults, "builds");
            org.bukkit.configuration.ConfigurationSection msgs7 = defaults.getConfigurationSection("messages");
            if (msgs7 != null) {
                for (String key : msgs7.getKeys(false)) {
                    if (key.startsWith("schematic-") || key.startsWith("builds-")) {
                        addMissing(file, defaults, "messages." + key);
                    }
                }
            }
        }
        if (from < 6) {
            // 2.13.0: facilities (buildings placed with a preview)
            addMissing(file, defaults, "facilities");
            addMissing(file, defaults, "facilities.build-seconds");
            addMissing(file, defaults, "facilities.animate");
            org.bukkit.configuration.ConfigurationSection msgs = defaults.getConfigurationSection("messages");
            if (msgs != null) {
                for (String key : msgs.getKeys(false)) {
                    if (key.startsWith("facility-") || key.startsWith("manage-facilities")) {
                        addMissing(file, defaults, "messages." + key);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ generation jobs

    double millisPerTick() {
        return Math.max(1, Math.min(45, config().getDouble("generation.millis-per-tick", 20)));
    }

    void queueGeneration(IslandImpl is, CommandSender feedback) {
        World world = is.bukkitWorld();
        if (world == null) {
            return;
        }
        IslandGenerator gen = new IslandGenerator(plugin(), is.name, world, is.x, is.z, is.radius, is.seed,
                settings(is.type), config().getDouble("generation.land-fraction", 0.72),
                config().getDouble("generation.height-scaling", 1.0), config().getDouble("generation.max-height-boost", 3.0));
        gen.terrain(IslandGenerator.TerrainOptions.load(config().getConfigurationSection("generation.terrain"), logger()));
        enqueue(gen, is.id, feedback, () -> {
            is.y = gen.centerSurfaceY();
            if (facilities != null) {
                facilities.applyBuilds(is, null); // the team's own builds for this island type (islands.yml builds)
            }
            if (is.status == IslandStatus.OCCUPIED && is.ownerCrewId != null) {
                int fx = is.hasFlag ? is.flagX : is.x, fz = is.hasFlag ? is.flagZ : is.z;
                is.hasFlag = false;
                placeFlag(is, fx, world.getHighestBlockYAt(fx, fz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, fz);
            }
            markDirty();
        });
    }

    /** Clears the island back to ocean and generates it again from its seed. */
    void queueRegeneration(IslandImpl is, CommandSender feedback) {
        World world = is.bukkitWorld();
        if (world == null) {
            return;
        }
        cancelJobs(is.id);
        enqueue(restoreJob(is, world), is.id, feedback, null);
        queueGeneration(is, feedback);
    }

    private OceanRestoreJob restoreJob(IslandImpl is, World world) {
        WeightedList<Material> floor = new WeightedList<>();
        TypeSettings.blocks(floor, config().getStringList("restore.floor"), Material.GRAVEL, logger(), "restore.floor");
        Biome biome = null;
        String biomeName = config().getString("restore.biome", "ocean");
        if (biomeName != null && !biomeName.isBlank() && !biomeName.equalsIgnoreCase("none")) {
            try {
                biome = Registry.BIOME.get(NamespacedKey.minecraft(biomeName.toLowerCase(Locale.ROOT)));
            } catch (RuntimeException ignored) {
            }
        }
        return new OceanRestoreJob(plugin(), Text.plain(rawMsg("job-restore-label", "island", is.name)), world,
                is.x, is.z, is.radius, is.seed, config().getInt("restore.depth", 22), floor, biome);
    }

    private void enqueue(WorldJob job, String islandId, CommandSender sender, Runnable onDone) {
        jobs.add(new QueuedJob(job, islandId, sender, onDone));
    }

    int queuedJobs() {
        return jobs.size() + (currentJob == null ? 0 : 1);
    }

    /** Aborts running/queued jobs; islandId null = everything. @return number of jobs removed */
    /** Picks a size class from generation.island-sizes by weight, then a radius in its range. */
    int randomIslandRadius(java.util.Random random) {
        ConfigurationSection sizes = config().getConfigurationSection("generation.island-sizes");
        double total = 0;
        List<ConfigurationSection> list = new ArrayList<>();
        if (sizes != null) {
            for (String key : sizes.getKeys(false)) {
                ConfigurationSection s = sizes.getConfigurationSection(key);
                if (s != null && s.getDouble("weight", 1) > 0) {
                    list.add(s);
                    total += s.getDouble("weight", 1);
                }
            }
        }
        int limit = config().getInt("generation.radius-limit", 750), floor = config().getInt("generation.min-radius", 16);
        if (list.isEmpty()) {
            return Math.max(floor, Math.min(limit, 100 + random.nextInt(250)));
        }
        double roll = random.nextDouble() * total;
        ConfigurationSection pick = list.get(list.size() - 1);
        for (ConfigurationSection s : list) {
            roll -= s.getDouble("weight", 1);
            if (roll < 0) {
                pick = s;
                break;
            }
        }
        int lo = Math.max(floor, pick.getInt("min", 100)), hi = Math.min(limit, Math.max(lo, pick.getInt("max", lo)));
        return lo + random.nextInt(hi - lo + 1);
    }

    /** A running /island admin spawnrandom: islands are placed one by one, so cancel must stop the chain too. */
    static final class SpawnRun {
        final int total;
        int placed;
        boolean cancelled;
        boolean finished;

        SpawnRun(int total) {
            this.total = total;
        }
    }

    final List<SpawnRun> spawnRuns = new ArrayList<>();

    /** Stops every running spawnrandom chain. @return how many were running. */
    int cancelSpawnRuns() {
        int n = 0;
        for (SpawnRun run : spawnRuns) {
            if (!run.cancelled) {
                run.cancelled = true;
                n++;
            }
        }
        spawnRuns.clear();
        return n;
    }

    int cancelJobs(String islandId) {
        int removed = 0;
        if (currentJob != null && (islandId == null || islandId.equals(currentJob.islandId()))) {
            currentJob.job().cleanup();
            currentJob = null;
            removed++;
        }
        for (var it = jobs.iterator(); it.hasNext(); ) {
            QueuedJob q = it.next();
            if (islandId == null || islandId.equals(q.islandId())) {
                q.job().cleanup();
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private void tickJobs() {
        if (currentJob == null) {
            currentJob = jobs.poll();
            jobTicks = 0;
            if (currentJob == null) {
                return;
            }
        }
        QueuedJob q = currentJob;
        long deadline = System.nanoTime() + (long) (millisPerTick() * 1_000_000L);
        boolean done;
        try {
            done = q.job().tick(deadline);
        } catch (Throwable t) {
            logger().log(Level.SEVERE, "[islands] Terrain job '" + q.job().label() + "' failed", t);
            q.job().cleanup();
            currentJob = null;
            if (q.sender() != null) {
                send(q.sender(), "job-failed", "label", q.job().label());
            }
            return;
        }
        if (currentJob != q) {
            return; // cancelled from inside
        }
        jobTicks++;
        if (done) {
            q.job().cleanup();
            currentJob = null;
            if (q.onDone() != null) {
                try {
                    q.onDone().run();
                } catch (Throwable t) {
                    logger().log(Level.SEVERE, "[islands] Error finishing terrain job '" + q.job().label() + "'", t);
                }
            }
            if (q.sender() != null && !(q.sender() instanceof Player p && !p.isOnline())) {
                if (jobs.isEmpty()) {
                    send(q.sender(), "job-finished", "label", q.job().label());
                } else {
                    send(q.sender(), "job-finished-queued", "label", q.job().label(), "queued", jobs.size());
                }
            }
            if (q.sender() instanceof Player p && jobs.stream().noneMatch(o -> o.sender() == p)) {
                hideJobBar(p);
            }
            return;
        }
        CommandSender sender = q.sender();
        if (sender instanceof Player p) {
            if (jobTicks % 10 == 0 && p.isOnline()) {
                showJobBar(p, q);
            }
        } else if (sender != null && jobTicks % 200 == 0) {
            sender.sendMessage(rawMsg("job-progress", "label", q.job().label(),
                    "percent", (int) Math.floor(q.job().progress() * 100), "queued", jobs.size()));
        }
    }

    // ------------------------------------------------------------------ names

    /** Picks an unused island name for the type from the configured lists. */
    String randomName(IslandType type, Random random) {
        String pool = "island-names." + type.name();
        List<String> fixed = new ArrayList<>(config().getStringList(pool + ".fixed"));
        Collections.shuffle(fixed, random);
        for (String n : fixed) {
            if (nameFree(n)) {
                return n;
            }
        }
        // "<first> <second>" combinations: hundreds of unique names per type
        List<String> first = config().getStringList(pool + ".first"), second = config().getStringList(pool + ".second");
        if (!first.isEmpty() && !second.isEmpty()) {
            for (int i = 0; i < 60; i++) {
                String n = first.get(random.nextInt(first.size())) + " " + second.get(random.nextInt(second.size()));
                if (nameFree(n)) {
                    return n;
                }
            }
        }
        List<String> prefixes = config().getStringList("island-names.fallback-first");
        List<String> suffixes = config().getStringList("island-names.fallback-second");
        if (!prefixes.isEmpty() && !suffixes.isEmpty()) {
            for (int i = 0; i < 40; i++) {
                String n = prefixes.get(random.nextInt(prefixes.size())) + " " + suffixes.get(random.nextInt(suffixes.size()));
                if (nameFree(n)) {
                    return n;
                }
            }
        }
        String base = Text.plain(typeName(type)) + " Isle ";
        int i = 1;
        while (!nameFree(base + i)) {
            i++;
        }
        return base + i;
    }

    boolean nameFree(String name) {
        String id = slug(name);
        return !id.isEmpty() && !islands.containsKey(id) && findByName(name) == null;
    }

    IslandType randomType(Random random) {
        WeightedList<IslandType> list = new WeightedList<>();
        // "island-type-weights" (1.9.0+) replaces the older "random-type-weights"
        ConfigurationSection sec = config().getConfigurationSection("generation.island-type-weights");
        if (sec == null || sec.getKeys(false).isEmpty()) {
            sec = config().getConfigurationSection("generation.random-type-weights");
        }
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                list.add(IslandType.parse(key), sec.getDouble(key));
            }
            // types added in a newer version get their bundled default weight
            ConfigurationSection defaults = config().getDefaults() == null ? null
                    : config().getDefaults().getConfigurationSection("generation.island-type-weights");
            if (defaults != null) {
                for (String key : defaults.getKeys(false)) {
                    if (!sec.contains(key, true) && IslandType.parse(key) != null) {
                        list.add(IslandType.parse(key), defaults.getDouble(key));
                    }
                }
            }
        }
        if (list.isEmpty()) {
            for (IslandType t : IslandType.values()) {
                if (t != IslandType.CAPITAL) {
                    list.add(t, 1);
                }
            }
        }
        return list.pick(random);
    }

    // ------------------------------------------------------------------ flags

    int poleHeight() {
        return Math.max(1, Math.min(8, config().getInt("flag.pole-height", 3)));
    }

    private Material poleMaterial() {
        Material m = Material.matchMaterial(config().getString("flag.pole-material", "OAK_FENCE"));
        return m != null && m.isBlock() ? m : Material.OAK_FENCE;
    }

    /** Builds pole + crew banner with its base block at x/y/z and makes it the island's conquest point. */
    void placeFlag(IslandImpl is, int x, int y, int z) {
        World world = is.bukkitWorld();
        if (world == null) {
            return;
        }
        removeFlagBlocks(is);
        int height = poleHeight();
        y = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - height - 2, y));
        Material pole = poleMaterial();
        for (int i = 0; i < height; i++) {
            world.getBlockAt(x, y + i, z).setType(pole, false);
        }
        ItemStack flag = null;
        CrewService crews = service(CrewService.class);
        Crew crew = crews == null || is.ownerCrewId == null ? null : crews.crewById(is.ownerCrewId);
        if (crew != null) {
            flag = crew.flag();
        }
        Material bannerType = Material.matchMaterial(config().getString("flag.default-banner", "WHITE_BANNER"));
        if (bannerType == null || !Tag.BANNERS.isTagged(bannerType)) {
            bannerType = Material.WHITE_BANNER;
        }
        if (flag != null && Tag.BANNERS.isTagged(flag.getType()) && flag.getType().isBlock()) {
            bannerType = flag.getType();
        }
        Block top = world.getBlockAt(x, y + height, z);
        top.setType(bannerType, false);
        if (flag != null && flag.getItemMeta() instanceof BannerMeta meta && top.getState() instanceof Banner banner) {
            banner.setPatterns(meta.getPatterns());
            banner.update(true, false);
        }
        is.setFlag(x, y, z);
        markDirty();
    }

    void removeFlagBlocks(IslandImpl is) {
        if (!is.hasFlag) {
            return;
        }
        World world = is.bukkitWorld();
        if (world != null) {
            Material pole = poleMaterial();
            for (int i = 0; i <= 9; i++) {
                Block b = world.getBlockAt(is.flagX, is.flagY + i, is.flagZ);
                Material type = b.getType();
                if (Tag.BANNERS.isTagged(type)) {
                    b.setType(Material.AIR, false);
                    break;
                }
                if (type != pole && !Tag.FENCES.isTagged(type)) {
                    break;
                }
                b.setType(Material.AIR, false);
            }
        }
        is.hasFlag = false;
        markDirty();
    }

    /** True when "treasure-digging" is on and the block is a treasure chest or the shaft above it. */
    boolean isTreasureDigSpot(Block block) {
        if (!config().getBoolean("treasure-digging", true)) {
            return false;
        }
        dev.aquatic.api.TreasureService treasure = service(dev.aquatic.api.TreasureService.class);
        return treasure != null && treasure.isTreasureDigSpot(block.getLocation());
    }

    boolean isFlagBlock(Block block) {
        IslandImpl is = at(block);
        return is != null && is.hasFlag && block.getX() == is.flagX && block.getZ() == is.flagZ
                && block.getY() >= is.flagY && block.getY() <= is.flagY + poleHeight();
    }

    // ------------------------------------------------------------------ rules

    boolean hasBypass(Player player) {
        return player.hasPermission("aquatic.islands.bypass");
    }

    /** True when the owners are offline (past the grace period) and offline protection is on. */
    boolean offlineProtected(IslandImpl is) {
        if (!config().getBoolean("offline-protection.enabled", true) || is.ownerCrewId == null) {
            return false;
        }
        CrewService crews = service(CrewService.class);
        if (crews == null) {
            return true;
        }
        Crew owner = crews.crewById(is.ownerCrewId);
        if (owner == null) {
            return false;
        }
        if (!owner.onlineMembers().isEmpty()) {
            return false;
        }
        long grace = Math.max(0, config().getLong("offline-protection.grace-seconds", 300)) * 1000L;
        return System.currentTimeMillis() - crewLastSeen.getOrDefault(owner.id(), 0L) > grace;
    }

    boolean shielded(IslandImpl is) {
        return is.shieldUntil > System.currentTimeMillis();
    }

    /** Does the mode ("always" | "war-only" | "never") permit hostile action given the relation to the owners? */
    static boolean modeAllows(String mode, CrewRelation relation) {
        if (relation == CrewRelation.SAME || relation == CrewRelation.ALLY) {
            return false;
        }
        return switch (mode == null ? "war-only" : mode.toLowerCase(Locale.ROOT)) {
            case "always" -> true;
            case "never" -> false;
            default -> relation == CrewRelation.WAR;
        };
    }

    /** True when at least somebody could currently raid the island (used for explosions without a known source). */
    boolean raidableNow(IslandImpl is) {
        CrewService crews = service(CrewService.class);
        if (crews == null || is.ownerCrewId == null) {
            return false;
        }
        String mode = config().getString("raiding.mode", "war-only").toLowerCase(Locale.ROOT);
        if (mode.equals("never") || offlineProtected(is)
                || (config().getBoolean("raiding.respect-shield", true) && shielded(is))) {
            return false;
        }
        if (mode.equals("always")) {
            return true;
        }
        Crew owner = crews.crewById(is.ownerCrewId);
        if (owner == null) {
            return false;
        }
        for (Crew other : crews.crews()) {
            if (crews.relation(other, owner) == CrewRelation.WAR && !other.onlineMembers().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return null when the player may do this on the island, otherwise the key of the message
     * explaining why not.
     */
    String denyReason(Player player, IslandImpl is, Action action) {
        if (is == null || hasBypass(player)) {
            return null;
        }
        switch (is.status) {
            case NEUTRAL:
                return action == Action.CONTAINER && config().getBoolean("protection.neutral.allow-containers", false)
                        ? null : "deny-neutral";
            case UNCLAIMED:
                return config().getBoolean("protection.unclaimed-build", true) ? null : "deny-unclaimed";
            default:
                break;
        }
        if (action == Action.CONTAINER && !config().getBoolean("protection.occupied.protect-containers", true)) {
            return null;
        }
        CrewService crews = service(CrewService.class);
        if (crews == null) {
            return "deny-occupied";
        }
        Crew owner = crews.crewById(is.ownerCrewId);
        if (owner == null) {
            return null; // owner vanished; the island is released by the periodic check
        }
        Crew mine = crews.crewOf(player.getUniqueId());
        if (mine == null) {
            if (!config().getBoolean("raiding.crewless-can-raid", false)
                    || !"always".equalsIgnoreCase(config().getString("raiding.mode", "war-only"))) {
                return "deny-occupied";
            }
        } else {
            CrewRelation relation = crews.relation(mine, owner);
            if (relation == CrewRelation.SAME) {
                dev.aquatic.api.CrewRank rank = mine.rankOf(player.getUniqueId());
                return is.officersOnly && (rank == null || rank == dev.aquatic.api.CrewRank.MEMBER) ? "deny-officers-only" : null;
            }
            if (relation == CrewRelation.ALLY) {
                return config().getBoolean("protection.allies-can-build", true) ? null : "deny-occupied";
            }
            if (!modeAllows(config().getString("raiding.mode", "war-only"), relation)) {
                return "deny-occupied";
            }
        }
        // hostile action from here on
        boolean allowed = switch (action) {
            case BREAK -> config().getBoolean("raiding.allow-break", true);
            case PLACE -> config().getBoolean("raiding.allow-place", true);
            case CONTAINER -> config().getBoolean("raiding.allow-containers", true);
        };
        if (!allowed) {
            return "deny-occupied";
        }
        if (offlineProtected(is)) {
            return "deny-offline";
        }
        if (config().getBoolean("raiding.respect-shield", true) && shielded(is)) {
            return "deny-shield";
        }
        return null;
    }

    /** Shows a (rate limited) denial action bar. */
    void deny(Player player, IslandImpl is, String key) {
        long now = System.currentTimeMillis();
        Long last = lastDenyMessage.get(player.getUniqueId());
        if (last != null && now - last < 1500) {
            return;
        }
        lastDenyMessage.put(player.getUniqueId(), now);
        player.sendActionBar(rawMsg(key, "island", is.name, "crew", crewDisplay(is.ownerCrewId),
                "time", Text.duration(Math.max(0, (is.shieldUntil - now) / 1000))));
    }

    // ------------------------------------------------------------------ ownership changes

    /** Hands the island to a crew after a successful conquest (state only; events are fired by the caller). */
    TidewatchGarrison garrison() {
        return garrison;
    }

    /** All islands (live objects, for features in this package). */
    java.util.Collection<IslandImpl> islandImpls() {
        return new ArrayList<>(islands.values());
    }

    IslandUpgrades upgrades() {
        return upgrades;
    }

    IslandFacilities facilities() {
        return facilities;
    }

    IslandImpl islandImpl(String id) {
        return id == null ? null : islands.get(id);
    }

    /** Passive income of one island per payout (type base x treasury upgrade). */
    double incomeOf(IslandImpl is) {
        double base = config().getDouble("income.per-type." + is.type.name(), config().getDouble("income.default", 0));
        return base * (1 + (upgrades == null ? 0 : upgrades.value(is, "treasury"))
                + (facilities == null ? 0 : facilities.incomeBonus(is)));
    }

    void conquer(IslandImpl is, Crew winner) {
        if (upgrades != null) {
            upgrades.onConquered(is);
        }
        is.ownerCrewId = winner.id();
        is.status = IslandStatus.OCCUPIED;
        is.claimedAt = System.currentTimeMillis();
        is.shieldUntil = System.currentTimeMillis() + Math.max(0, config().getLong("conquest.shield-seconds", 3600)) * 1000L;
        if (is.hasFlag) {
            placeFlag(is, is.flagX, is.flagY, is.flagZ);
        }
        markDirty();
    }

    void claim(IslandImpl is, Crew crew, int flagX, int flagY, int flagZ) {
        is.ownerCrewId = crew.id();
        is.status = IslandStatus.OCCUPIED;
        is.claimedAt = System.currentTimeMillis();
        long shield = Math.max(0, config().getLong("claiming.shield-seconds", 0)) * 1000L;
        is.shieldUntil = shield > 0 ? System.currentTimeMillis() + shield : 0;
        placeFlag(is, flagX, flagY, flagZ);
        markDirty();
    }

    private void releaseOrphanedIslands() {
        CrewService crews = service(CrewService.class);
        if (crews == null) {
            return;
        }
        for (IslandImpl is : islands.values()) {
            if (is.status == IslandStatus.OCCUPIED && is.ownerCrewId != null && crews.crewById(is.ownerCrewId) == null) {
                String name = is.name;
                setOwner(is, null);
                Bukkit.broadcast(msg("island-abandoned", "island", name));
            }
        }
    }

    /** A crew redesigned its flag: swap the banner on every island it owns (same pole, same spot). */
    @EventHandler
    public void onCrewFlagChange(dev.aquatic.api.event.CrewFlagChangeEvent event) {
        String crewId = event.crew().id();
        for (IslandImpl is : islands.values()) {
            if (crewId.equals(is.ownerCrewId) && is.hasFlag && is.bukkitWorld() != null
                    && is.bukkitWorld().isChunkLoaded(is.flagX >> 4, is.flagZ >> 4)) {
                placeFlag(is, is.flagX, is.flagY, is.flagZ);
            } else if (crewId.equals(is.ownerCrewId) && is.hasFlag) {
                pendingFlagRefresh.add(is.id); // chunk not loaded: update when someone comes near
            }
        }
    }

    /** Islands whose banner must be redrawn once their flag chunk loads. */
    private final Set<String> pendingFlagRefresh = new HashSet<>();

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        if (pendingFlagRefresh.isEmpty()) {
            return;
        }
        org.bukkit.Chunk chunk = event.getChunk();
        for (var it = pendingFlagRefresh.iterator(); it.hasNext(); ) {
            IslandImpl is = islands.get(it.next());
            if (is == null || !is.hasFlag) {
                it.remove();
                continue;
            }
            if (is.flagX >> 4 == chunk.getX() && is.flagZ >> 4 == chunk.getZ() && chunk.getWorld().getName().equals(is.world)) {
                it.remove();
                int x = is.flagX, y = is.flagY, z = is.flagZ;
                runLater(() -> placeFlag(is, x, y, z), 1L);
            }
        }
    }

    @EventHandler
    public void onCrewDisband(CrewDisbandEvent event) {
        String crewId = event.crew().id();
        for (IslandImpl is : islands.values()) {
            if (crewId.equals(is.ownerCrewId)) {
                setOwner(is, null);
                Bukkit.broadcast(msg("island-abandoned", "island", is.name));
            }
        }
        crewLastSeen.remove(crewId);
    }

    // ------------------------------------------------------------------ income

    private void payIncome() {
        CrewService crews = service(CrewService.class);
        if (crews == null) {
            return;
        }
        boolean requireOnline = config().getBoolean("income.require-online", true);
        double def = config().getDouble("income.default", 0);
        Map<String, Double> totals = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (IslandImpl is : islands.values()) {
            if (is.status != IslandStatus.OCCUPIED || is.ownerCrewId == null) {
                continue;
            }
            double amount = incomeOf(is);
            if (amount > 0) {
                totals.merge(is.ownerCrewId, amount, Double::sum);
                counts.merge(is.ownerCrewId, 1, Integer::sum);
            }
        }
        EconomyService economy = service(EconomyService.class);
        ConfigurationSection up = config().getConfigurationSection("income.upkeep");
        boolean upkeep = up != null && up.getBoolean("enabled", true);
        Map<String, List<IslandImpl>> owned = new HashMap<>();
        for (IslandImpl is : islands.values()) {
            if (is.status == IslandStatus.OCCUPIED && is.ownerCrewId != null) {
                owned.computeIfAbsent(is.ownerCrewId, k -> new ArrayList<>()).add(is);
            }
        }
        YamlConfiguration missedData = upkeep ? loadData("upkeep") : null;
        for (Map.Entry<String, List<IslandImpl>> e : owned.entrySet()) {
            Crew crew = crews.crewById(e.getKey());
            if (crew == null || (requireOnline && crew.onlineMembers().isEmpty())) {
                continue;
            }
            double income = totals.getOrDefault(e.getKey(), 0.0);
            double cost = 0;
            if (upkeep) {
                double growth = 1 + up.getDouble("growth", 0.15) * (e.getValue().size() - 1);
                for (IslandImpl is : e.getValue()) {
                    cost += (up.getDouble("per-island", 50) + up.getDouble("per-facility", 12) * is.facilities.size()) * growth;
                }
            }
            String fmtIncome = economy != null ? economy.format(income) : Text.number(income);
            if (cost <= 0) {
                if (income > 0) {
                    crew.depositBank(income);
                    crew.broadcast(msg("income-paid", "amount", fmtIncome, "count", e.getValue().size()));
                }
                continue;
            }
            double net = income - cost;
            boolean paid = net >= 0 || crew.withdrawBank(-net);
            if (net >= 0) {
                crew.depositBank(net);
            }
            if (paid) {
                missedData.set(crew.id(), null);
                crew.broadcast(msg("income-net", "count", e.getValue().size(), "income", fmtIncome,
                        "upkeep", economy != null ? economy.format(cost) : Text.number(cost),
                        "net", (net < 0 ? "-" : "+") + (economy != null ? economy.format(Math.abs(net)) : Text.number(Math.abs(net)))));
                continue;
            }
            int limit = Math.max(1, up.getInt("unpaid-limit", 12));
            int missed = missedData.getInt(crew.id()) + 1;
            if (missed >= limit) {
                IslandImpl newest = e.getValue().stream().max(java.util.Comparator.comparingLong(i -> i.claimedAt)).orElse(null);
                if (newest != null) {
                    setOwner(newest, null);
                    crew.broadcast(msg("upkeep-lost", "island", newest.name));
                }
                missed = 0;
            } else {
                crew.broadcast(msg("upkeep-unpaid", "missed", missed, "limit", limit));
            }
            missedData.set(crew.id(), missed == 0 ? null : missed);
        }
        if (missedData != null) {
            saveData("upkeep", missedData);
        }
    }

    // ------------------------------------------------------------------ presence & discovery

    private void pollPresence() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location loc = player.getLocation();
            UUID uuid = player.getUniqueId();
            long worldHash = loc.getWorld() == null ? 0 : loc.getWorld().getUID().getLeastSignificantBits();
            long[] last = lastBlock.get(uuid);
            if (last != null && last[0] == loc.getBlockX() && last[1] == loc.getBlockZ() && last[2] == worldHash) {
                continue; // did not change block column
            }
            lastBlock.put(uuid, new long[]{loc.getBlockX(), loc.getBlockZ(), worldHash});
            IslandImpl now = at(loc);
            String before = presence.get(uuid);
            String nowId = now == null ? null : now.id;
            if (java.util.Objects.equals(before, nowId)) {
                continue;
            }
            if (nowId == null) {
                presence.remove(uuid);
                IslandImpl left = islands.get(before);
                if (left != null) {
                    showPresence(player, "leave", left, false);
                }
            } else {
                presence.put(uuid, nowId);
                boolean discovered = discover(player, now);
                showPresence(player, "enter", now, discovered);
                if (upgrades != null) {
                    upgrades.onEnter(player, now);
                }
            }
        }
    }

    private void showJobBar(Player p, QueuedJob q) {
        float progress = (float) Math.max(0, Math.min(1, q.job().progress()));
        Component name = rawMsg("job-progress", "label", q.job().label(),
                "percent", (int) Math.floor(progress * 100), "queued", jobs.size());
        net.kyori.adventure.bossbar.BossBar bar = jobBars.get(p.getUniqueId());
        if (bar == null) {
            bar = net.kyori.adventure.bossbar.BossBar.bossBar(name, progress,
                    net.kyori.adventure.bossbar.BossBar.Color.BLUE, net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
            jobBars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(name);
            bar.progress(progress);
        }
    }

    private void hideJobBar(Player p) {
        net.kyori.adventure.bossbar.BossBar bar = jobBars.remove(p.getUniqueId());
        if (bar != null) {
            p.hideBossBar(bar);
        }
    }

    private void hideAllJobBars() {
        for (Map.Entry<UUID, net.kyori.adventure.bossbar.BossBar> e : jobBars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                p.hideBossBar(e.getValue());
            }
        }
        jobBars.clear();
    }

    private void showPresence(Player player, String key, IslandImpl is, boolean suppressTitle) {
        // enter-display / leave-display (no explicit fallback, so the bundled default "title" applies to
        // configs created by older versions that only had the single "display" key)
        String configured = config().getString("presence." + key + "-display");
        String mode = (configured == null ? "title" : configured).toLowerCase(Locale.ROOT);
        if (mode.equals("none")) {
            return;
        }
        // walking along a coastline flips between island and sea every few blocks: don't repeat the same message
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, config().getLong("presence.repeat-cooldown-seconds", 5)) * 1000L;
        String stamp = key + ":" + is.id;
        Long last = lastPresenceAt.get(player.getUniqueId());
        if (cooldown > 0 && stamp.equals(lastPresenceKey.get(player.getUniqueId())) && last != null && now - last < cooldown) {
            return;
        }
        lastPresenceKey.put(player.getUniqueId(), stamp);
        lastPresenceAt.put(player.getUniqueId(), now);
        Object[] ph = {"island", is.name, "type", typeName(is.type), "owner", ownerLine(is)};
        Component line = rawMsg(key, ph);
        if (Text.plain(line).isBlank()) {
            return;
        }
        switch (mode) {
            case "chat" -> player.sendMessage(line);
            case "title" -> {
                if (suppressTitle) {
                    player.sendActionBar(line);
                } else {
                    Title.Times times = Title.Times.times(
                            java.time.Duration.ofMillis(50L * Math.max(0, config().getInt("presence.title-fade-in-ticks", 8))),
                            java.time.Duration.ofMillis(50L * Math.max(1, config().getInt("presence.title-stay-ticks", 40))),
                            java.time.Duration.ofMillis(50L * Math.max(0, config().getInt("presence.title-fade-out-ticks", 12))));
                    player.showTitle(Title.title(rawMsg(key + "-title", ph), rawMsg(key + "-subtitle", ph), times));
                }
            }
            case "both" -> {
                player.sendActionBar(line);
                if (!suppressTitle) {
                    player.showTitle(Title.title(rawMsg(key + "-title", ph), rawMsg(key + "-subtitle", ph)));
                }
            }
            default -> player.sendActionBar(line);
        }
    }

    /** @return true when this was the player's first visit. */
    private boolean discover(Player player, IslandImpl is) {
        if (!config().getBoolean("discovery.enabled", true)) {
            return false;
        }
        Set<String> set = discoveries.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashSet<>());
        if (!set.add(is.id)) {
            return false;
        }
        dirty = true;
        Bukkit.getPluginManager().callEvent(new IslandDiscoverEvent(is, player));
        Object[] ph = {"island", is.name, "type", typeName(is.type), "owner", ownerLine(is)};
        if (config().getBoolean("discovery.title", true)) {
            player.showTitle(Title.title(rawMsg("discover-title", ph), rawMsg("discover-subtitle", ph)));
        }
        String sound = config().getString("discovery.sound", "ui.toast.challenge_complete");
        if (sound != null && !sound.isBlank() && !sound.equalsIgnoreCase("none")) {
            try {
                player.playSound(Sound.sound(Key.key(sound.toLowerCase(Locale.ROOT)), Sound.Source.MASTER,
                        (float) config().getDouble("discovery.sound-volume", 0.8), (float) config().getDouble("discovery.sound-pitch", 1.0)));
            } catch (RuntimeException ignored) {
                // invalid key in config
            }
        }
        double reward = config().getDouble("discovery.reward-per-type." + is.type.name(), config().getDouble("discovery.reward", 0))
                * Math.max(0, config().getDouble("rarities." + settings(is.type).rarity + ".discovery-multiplier", 1));
        EconomyService economy = service(EconomyService.class);
        if (reward > 0 && economy != null) {
            economy.deposit(player.getUniqueId(), reward);
            send(player, "discover-reward", "island", is.name, "amount", economy.format(reward));
        } else {
            send(player, "discovered", "island", is.name);
        }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean first = !player.hasPlayedBefore();
        if ((first && config().getBoolean("spawn.teleport-on-first-join", true))
                || config().getBoolean("spawn.teleport-on-every-join", false)) {
            Location spot = spawnLocation();
            if (spot != null) {
                player.teleportAsync(spot);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        Location barracks = upgrades == null ? null : upgrades.barracksSpawn(event.getPlayer());
        if (barracks != null) {
            event.setRespawnLocation(barracks);
            return;
        }
        if (!config().getBoolean("spawn.respawn-here", true)) {
            return;
        }
        Location spot = spawnLocation();
        if (spot != null) {
            event.setRespawnLocation(spot);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        presence.remove(uuid);
        lastBlock.remove(uuid);
        lastPresenceKey.remove(uuid);
        lastPresenceAt.remove(uuid);
        jobBars.remove(uuid);
        lastDenyMessage.remove(uuid);
        conquest.playerQuit(player);
        CrewService crews = service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewOf(uuid);
        if (crew != null) {
            boolean othersOnline = false;
            for (Player member : crew.onlineMembers()) {
                if (!member.getUniqueId().equals(uuid)) {
                    othersOnline = true;
                    break;
                }
            }
            if (!othersOnline) {
                crewLastSeen.put(crew.id(), System.currentTimeMillis());
                dirty = true;
            }
        }
    }

    /** Online players currently standing on the island. */
    List<Player> playersOn(IslandImpl is) {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (is.contains(p.getLocation())) {
                out.add(p);
            }
        }
        return out;
    }

    Set<String> islandIds() {
        return new HashSet<>(islands.keySet());
    }
}
