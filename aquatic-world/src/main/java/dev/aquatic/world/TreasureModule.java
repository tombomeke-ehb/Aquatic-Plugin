package dev.aquatic.world;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.api.ItemService;
import dev.aquatic.api.TreasureService;
import dev.aquatic.api.event.TreasureFoundEvent;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.ElderGuardian;
import org.bukkit.entity.Guardian;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Buried treasure: loot tiers, real chests tracked by location, treasure maps with a red X,
 * natural spawning and map drops. Provides {@link TreasureService}.
 */
public final class TreasureModule extends AquaticModule implements TreasureService, Listener {

    private static final String[] COMPASS = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};

    /** A placed, not yet found treasure. */
    static final class Treasure {
        final String world;
        final int x, y, z;
        final String tier;
        final long created;
        final boolean natural;
        String islandId;
        double doubloons;
        /** Players who bought this treasure's map in the shop, and how many may (rolled once, e.g. 2-3). */
        final List<String> mapBuyers = new ArrayList<>();
        int mapCap;
        final List<String> marker = new ArrayList<>();

        Treasure(String world, int x, int y, int z, String tier, long created, boolean natural) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tier = tier;
            this.created = created;
            this.natural = natural;
        }

        String key() {
            return key(world, x, y, z);
        }

        static String key(String world, int x, int y, int z) {
            return world + ";" + x + ";" + y + ";" + z;
        }

        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    /** Draws the red X on a treasure map (and nothing once the treasure is gone). */
    private final class XRenderer extends MapRenderer {
        private final String treasureKey;
        private final int tx, tz;

        XRenderer(String treasureKey, int tx, int tz) {
            super(false);
            this.treasureKey = treasureKey;
            this.tx = tx;
            this.tz = tz;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            MapCursorCollection cursors = canvas.getCursors();
            while (cursors.size() > 0) {
                cursors.removeCursor(cursors.getCursor(0));
            }
            int blocksPerPixel = 1 << view.getScale().getValue();
            int cx = (tx - view.getCenterX()) * 2 / blocksPerPixel;
            int cz = (tz - view.getCenterZ()) * 2 / blocksPerPixel;
            cx = Math.max(-128, Math.min(127, cx));
            cz = Math.max(-128, Math.min(127, cz));
            boolean active = treasures.containsKey(treasureKey);
            cursors.addCursor(new MapCursor((byte) cx, (byte) cz, (byte) 8,
                    active ? MapCursor.Type.RED_X : MapCursor.Type.TARGET_POINT, true));
        }
    }

    private final Map<String, LootTier> tiers = new LinkedHashMap<>();
    private final Map<String, Treasure> treasures = new LinkedHashMap<>();
    /** map id -> "world;x;y;z;tier" */
    private final Map<Integer, String> mapLinks = new HashMap<>();
    private final Map<Integer, XRenderer> attached = new HashMap<>();
    /** Chests that were found and should vanish (key -> tier vanish mode). */
    private final Map<String, String> opened = new HashMap<>();
    private final Map<UUID, Long> hintCooldown = new HashMap<>();
    private NamespacedKey mapKey;
    private NamespacedKey chestKey;
    private NamespacedKey sealedKey;
    private boolean dirty;

    @Override
    public String id() {
        return "treasure";
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onEnable() {
        tiers.clear();
        treasures.clear();
        mapLinks.clear();
        attached.clear();
        opened.clear();
        hintCooldown.clear();
        mapKey = new NamespacedKey(plugin(), "treasure_map");
        chestKey = new NamespacedKey(plugin(), "treasure_chest");
        sealedKey = new NamespacedKey(plugin(), "sealed_map");
        ConfigurationSection sec = config().getConfigurationSection("tiers");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s != null) {
                    tiers.put(id.toLowerCase(Locale.ROOT), LootTier.load(id.toLowerCase(Locale.ROOT), s, logger()));
                }
            }
        }
        if (tiers.isEmpty()) {
            logger().warning("[treasure] No loot tiers configured in modules/treasure.yml");
        }
        load();
        for (Map.Entry<Integer, String> e : mapLinks.entrySet()) {
            @SuppressWarnings("deprecation")
            MapView view = Bukkit.getMap(e.getKey());
            if (view != null) {
                attach(view, e.getValue());
            }
        }
        registerService(TreasureService.class, this);
        registerListener(this);
        registerCommand(buildCommand());
        long autosave = Math.max(10, config().getLong("autosave-seconds", 120)) * 20L;
        runTimer(() -> {
            if (dirty) {
                save();
            }
        }, autosave, autosave);
        if (config().getBoolean("natural.enabled", true)) {
            long interval = Math.max(1, config().getLong("natural.interval-minutes", 20)) * 1200L;
            runTimer(this::naturalSpawn, interval, interval);
        }
        runTimer(this::expire, 1200L, 1200L);
        long effectTicks = Math.max(5, config().getLong("effects.interval-ticks", 40));
        runTimer(this::effects, effectTicks, effectTicks);
    }

    @Override
    protected void onDisable() {
        for (Map.Entry<Integer, XRenderer> e : attached.entrySet()) {
            @SuppressWarnings("deprecation")
            MapView view = Bukkit.getMap(e.getKey());
            if (view != null) {
                view.removeRenderer(e.getValue());
            }
        }
        attached.clear();
        save();
    }

    private void load() {
        YamlConfiguration data = loadData("treasures");
        ConfigurationSection sec = data.getConfigurationSection("treasures");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(k);
                if (s == null || s.getString("world") == null) {
                    continue;
                }
                Treasure t = new Treasure(s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"),
                        s.getString("tier", "common"), s.getLong("created"), s.getBoolean("natural"));
                t.islandId = s.getString("island");
                t.doubloons = s.getDouble("doubloons");
                t.mapBuyers.addAll(s.getStringList("map-buyers"));
                t.mapCap = s.getInt("map-cap");
                t.marker.addAll(s.getStringList("marker"));
                treasures.put(t.key(), t);
            }
        }
        ConfigurationSection maps = data.getConfigurationSection("maps");
        if (maps != null) {
            for (String k : maps.getKeys(false)) {
                try {
                    mapLinks.put(Integer.parseInt(k), maps.getString(k));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        dirty = false;
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        int i = 0;
        for (Treasure t : treasures.values()) {
            ConfigurationSection s = data.createSection("treasures.t" + (i++));
            s.set("world", t.world);
            s.set("x", t.x);
            s.set("y", t.y);
            s.set("z", t.z);
            s.set("tier", t.tier);
            s.set("created", t.created);
            s.set("natural", t.natural);
            s.set("island", t.islandId);
            s.set("doubloons", t.doubloons);
            s.set("map-buyers", t.mapBuyers);
            s.set("map-cap", t.mapCap);
            s.set("marker", t.marker);
        }
        for (Map.Entry<Integer, String> e : mapLinks.entrySet()) {
            data.set("maps." + e.getKey(), e.getValue());
        }
        saveData("treasures", data);
        dirty = false;
    }

    // ------------------------------------------------------------------ TreasureService

    @Override
    public Collection<String> tiers() {
        return List.copyOf(tiers.keySet());
    }

    @Override
    public Location spawnTreasure(Location near, String tier) {
        Treasure t = spawn(near, tier, false, false);
        return t == null ? null : t.location();
    }

    @Override
    public Location spawnRandomTreasure(String tier) {
        Treasure t = spawnRandom(tier, false);
        return t == null ? null : t.location();
    }

    @Override
    public ItemStack createTreasureMap(Location treasure, String tier) {
        if (treasure == null || treasure.getWorld() == null) {
            return null;
        }
        String tierId = tier == null ? "common" : tier.toLowerCase(Locale.ROOT);
        LootTier lt = tiers.get(tierId);
        Component tierName = lt == null ? Component.text(tierId) : lt.displayName;
        World world = treasure.getWorld();
        int tx = treasure.getBlockX(), ty = treasure.getBlockY(), tz = treasure.getBlockZ();
        String link = Treasure.key(world.getName(), tx, ty, tz) + ";" + tierId;

        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        try {
            MapView view = Bukkit.createMap(world);
            // Every treasure map is its own map. If the world handed out an id that an older treasure map
            // already uses (its map counter was not saved, e.g. after a crash), that old map would suddenly
            // show this new treasure: skip such ids.
            for (int guard = 0; mapLinks.containsKey(view.getId()) && guard < 256; guard++) {
                view = Bukkit.createMap(world);
            }
            int jitter = Math.max(0, config().getInt("maps.center-jitter", 40));
            ThreadLocalRandom r = ThreadLocalRandom.current();
            view.setCenterX(tx + (jitter > 0 ? r.nextInt(-jitter, jitter + 1) : 0));
            view.setCenterZ(tz + (jitter > 0 ? r.nextInt(-jitter, jitter + 1) : 0));
            try {
                view.setScale(MapView.Scale.valueOf(config().getString("maps.scale", "CLOSE").toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                view.setScale(MapView.Scale.CLOSE);
            }
            view.setTrackingPosition(true);
            view.setUnlimitedTracking(false);
            attach(view, link);
            mapLinks.put(view.getId(), link);
            dirty = true;
            meta.setMapView(view);
        } catch (RuntimeException e) {
            logger().log(Level.WARNING, "[treasure] Could not create a map view, using a paper map", e);
            item = new ItemStack(Material.PAPER);
            meta = null;
        }
        ItemMeta im = meta != null ? meta : item.getItemMeta();
        Object[] ph = {"tier", tierName, "hint", locationHint(treasure), "island", islandName(treasure)};
        im.displayName(Text.item(config().getString("maps.name", "<gold>Treasure Map"), ph));
        im.lore(Text.itemLore(config().getStringList("maps.lore"), ph));
        im.getPersistentDataContainer().set(mapKey, PersistentDataType.STRING, link);
        item.setItemMeta(im);
        return item;
    }

    /** Tiers a shop map kind leads to (shop-maps.kinds in treasure.yml). */
    private List<String> mapKindTiers(String kind) {
        String k = kind == null || kind.isBlank() ? "standard" : kind.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String t : config().getStringList("shop-maps.kinds." + k)) {
            out.add(t.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** How many players may buy this treasure's map: rolled once per treasure (shop-maps.copies, e.g. 2-3). */
    private int mapCap(Treasure t) {
        if (t.mapCap <= 0) {
            String[] range = config().getString("shop-maps.copies", "2-3").split("-");
            try {
                int lo = Integer.parseInt(range[0].trim()), hi = range.length > 1 ? Integer.parseInt(range[1].trim()) : lo;
                t.mapCap = Math.max(1, lo + ThreadLocalRandom.current().nextInt(Math.max(1, hi - lo + 1)));
            } catch (NumberFormatException e) {
                t.mapCap = 3;
            }
            dirty = true;
        }
        return t.mapCap;
    }

    /** Treasures of that kind with copies left (and, for a buyer, that they don't already have). */
    private List<Treasure> sellable(String kind, UUID buyer) {
        List<String> tiersOfKind = mapKindTiers(kind);
        List<Treasure> out = new ArrayList<>();
        for (Treasure t : treasures.values()) {
            if (tiersOfKind.contains(t.tier) && t.location() != null && t.mapBuyers.size() < mapCap(t)
                    && (buyer == null || !t.mapBuyers.contains(buyer.toString()))) {
                out.add(t);
            }
        }
        return out;
    }

    @Override
    public int treasureMapsAvailable(String kind, UUID buyer) {
        if (buyer != null) {
            return sellable(kind, buyer).size();
        }
        int n = 0;
        for (Treasure t : sellable(kind, null)) {
            n += mapCap(t) - t.mapBuyers.size();
        }
        return n;
    }

    @Override
    public ItemStack sellTreasureMap(String kind, UUID buyer) {
        // only treasures that are already out there, never a new one: buying can't be used to roll for legendaries
        List<Treasure> open = new ArrayList<>();
        int fewest = Integer.MAX_VALUE;
        for (Treasure t : sellable(kind, buyer)) {
            int sold = t.mapBuyers.size();
            if (sold < fewest) {
                open.clear();
                fewest = sold;
            }
            if (sold == fewest) {
                open.add(t);
            }
        }
        if (open.isEmpty()) {
            return null;
        }
        Treasure t = open.get(ThreadLocalRandom.current().nextInt(open.size()));
        t.mapBuyers.add(buyer.toString());
        dirty = true;
        return createTreasureMap(t.location(), t.tier);
    }

    @Override
    public boolean isTreasureDigSpot(Location block) {
        if (block == null || block.getWorld() == null) {
            return false;
        }
        String world = block.getWorld().getName();
        int bx = block.getBlockX(), by = block.getBlockY(), bz = block.getBlockZ();
        for (Treasure t : treasures.values()) {
            if (t.x != bx || t.z != bz || !t.world.equals(world)) {
                continue;
            }
            LootTier tier = tiers.get(t.tier);
            int depth = tier == null ? 4 : tier.depth;
            // the chest itself, the earth above it and the marker block on the surface
            if (by >= t.y && by <= t.y + depth + 1) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void removeTreasure(Location treasure) {
        if (treasure == null || treasure.getWorld() == null) {
            return;
        }
        Treasure t = treasures.remove(Treasure.key(treasure.getWorld().getName(), treasure.getBlockX(), treasure.getBlockY(), treasure.getBlockZ()));
        if (t == null) {
            return;
        }
        dirty = true;
        clearChest(t);
        restoreMarker(t);
    }

    // ------------------------------------------------------------------ spawning

    LootTier tier(String id) {
        return id == null ? null : tiers.get(id.toLowerCase(Locale.ROOT));
    }

    private static boolean badSurface(Material m) {
        return m == Material.WATER || m == Material.LAVA || m == Material.ICE || m == Material.PACKED_ICE
                || m == Material.BLUE_ICE || Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || m == Material.BEDROCK
                || !m.isSolid() || m == Material.CHEST || m == Material.BARREL || m == Material.TRAPPED_CHEST;
    }

    /** Surface block of a dry land column, or null. */
    private Block landSurface(World world, int x, int z) {
        Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (badSurface(top.getType()) || top.getRelative(0, 1, 0).isLiquid()) {
            return null;
        }
        return top;
    }

    /**
     * Places a treasure chest of the tier near the location.
     *
     * @param exact when no good land column is found near the location, use the location itself
     */
    Treasure spawn(Location near, String tierId, boolean natural, boolean exact) {
        LootTier tier = tier(tierId);
        if (tier == null || near == null || near.getWorld() == null) {
            return null;
        }
        World world = near.getWorld();
        int bx = near.getBlockX(), bz = near.getBlockZ();
        Block surface = null;
        int search = Math.max(0, config().getInt("placement.search-radius", 6));
        outer:
        for (int r = 0; r <= search; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    Block candidate = landSurface(world, bx + dx, bz + dz);
                    if (candidate != null && !treasureNear(world.getName(), candidate.getX(), candidate.getZ())) {
                        surface = candidate;
                        break outer;
                    }
                }
            }
        }
        if (surface == null) {
            if (!exact) {
                return null;
            }
            Block feet = near.getBlock();
            surface = feet.getRelative(0, -1, 0);
        }
        int y = tier.depth == 0 ? surface.getY() + 1 : surface.getY() - tier.depth;
        if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 1) {
            return null;
        }
        Block chestBlock = world.getBlockAt(surface.getX(), y, surface.getZ());
        if (treasures.containsKey(Treasure.key(world.getName(), chestBlock.getX(), y, chestBlock.getZ()))) {
            return null;
        }
        Treasure t = new Treasure(world.getName(), chestBlock.getX(), y, chestBlock.getZ(), tier.id, System.currentTimeMillis(), natural);
        if (tier.marker && tier.depth > 0 && config().getBoolean("marker.enabled", true)) {
            placeMarker(t, world, surface.getX(), surface.getZ());
        }
        chestBlock.setType(tier.chest, false);
        LootTier.Roll roll = tier.roll(ThreadLocalRandom.current(), () -> service(ItemService.class));
        t.doubloons = roll.doubloons();
        BlockState state = chestBlock.getState();
        if (state instanceof Container container) {
            Inventory inv = container.getSnapshotInventory();
            inv.clear();
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < inv.getSize(); i++) {
                slots.add(i);
            }
            java.util.Collections.shuffle(slots);
            int si = 0;
            List<ItemStack> all = new ArrayList<>(roll.items());
            for (String mapTier : roll.maps()) {
                ItemStack sealed = sealedMap(mapTier);
                if (sealed != null) {
                    all.add(sealed);
                }
            }
            for (ItemStack stack : all) {
                if (si >= slots.size()) {
                    break;
                }
                inv.setItem(slots.get(si++), stack);
            }
            container.getPersistentDataContainer().set(chestKey, PersistentDataType.STRING, tier.id);
            if (config().getBoolean("chest-name.enabled", true)) {
                container.customName(Text.mm(config().getString("chest-name.format", "<tier> Treasure"), "tier", tier.displayName));
            }
            container.update(true, false);
        }
        IslandService islands = service(IslandService.class);
        Island island = islands == null ? null : islands.islandAt(chestBlock.getLocation());
        t.islandId = island == null ? null : island.id();
        treasures.put(t.key(), t);
        dirty = true;
        return t;
    }

    private boolean treasureNear(String world, int x, int z) {
        for (Treasure t : treasures.values()) {
            if (t.world.equals(world) && Math.abs(t.x - x) <= 3 && Math.abs(t.z - z) <= 3) {
                return true;
            }
        }
        return false;
    }

    /** Spawns on a random island (statuses from config) or, without islands, random land in the configured area. */
    Treasure spawnRandom(String tierId, boolean natural) {
        if (tier(tierId) == null) {
            return null;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        IslandService islands = service(IslandService.class);
        if (islands != null) {
            Set<String> statuses = new HashSet<>();
            for (String s : config().getStringList("placement.island-statuses")) {
                statuses.add(s.toUpperCase(Locale.ROOT));
            }
            List<Island> candidates = new ArrayList<>();
            for (Island is : islands.islands()) {
                if ((statuses.isEmpty() || statuses.contains(is.status().name())) && is.center().getWorld() != null) {
                    candidates.add(is);
                }
            }
            double inner = Math.max(0.1, Math.min(0.95, config().getDouble("placement.island-inner-fraction", 0.6)));
            for (int attempt = 0; attempt < 12 && !candidates.isEmpty(); attempt++) {
                Island is = candidates.get(r.nextInt(candidates.size()));
                Location c = is.center();
                double angle = r.nextDouble() * Math.PI * 2, dist = Math.sqrt(r.nextDouble()) * is.radius() * inner;
                Location spot = new Location(c.getWorld(), c.getX() + Math.cos(angle) * dist, c.getY(), c.getZ() + Math.sin(angle) * dist);
                Treasure t = spawn(spot, tierId, natural, false);
                if (t != null && is.contains(t.location())) {
                    return t;
                }
                if (t != null) {
                    removeTreasure(t.location()); // slipped off the island while searching for land
                }
            }
            if (!candidates.isEmpty()) {
                return null;
            }
        }
        World world = Bukkit.getWorld(config().getString("random-area.world", "world"));
        if (world == null) {
            return null;
        }
        int cx = config().getInt("random-area.center-x"), cz = config().getInt("random-area.center-z");
        int radius = Math.max(16, config().getInt("random-area.radius", 2000));
        int attempts = Math.max(1, config().getInt("random-area.attempts", 12));
        for (int i = 0; i < attempts; i++) {
            int x = cx + r.nextInt(-radius, radius + 1), z = cz + r.nextInt(-radius, radius + 1);
            Block surface = landSurface(world, x, z);
            if (surface == null || surface.getY() < world.getSeaLevel()) {
                continue;
            }
            Treasure t = spawn(surface.getLocation(), tierId, natural, false);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private void placeMarker(Treasure t, World world, int x, int z) {
        Material block = Material.matchMaterial(config().getString("marker.block", "RED_WOOL"));
        if (block == null || !block.isBlock()) {
            return;
        }
        int size = Math.max(0, Math.min(3, config().getInt("marker.size", 1)));
        Set<String> done = new HashSet<>();
        for (int i = -size; i <= size; i++) {
            for (int[] p : new int[][]{{x + i, z + i}, {x + i, z - i}}) {
                if (!done.add(p[0] + ";" + p[1])) {
                    continue;
                }
                Block top = world.getHighestBlockAt(p[0], p[1], HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (badSurface(top.getType()) || top.getState() instanceof Container) {
                    continue;
                }
                t.marker.add(top.getX() + ";" + top.getY() + ";" + top.getZ() + ";" + top.getBlockData().getAsString());
                top.setType(block, false);
            }
        }
    }

    private void restoreMarker(Treasure t) {
        World world = Bukkit.getWorld(t.world);
        if (world == null || t.marker.isEmpty()) {
            return;
        }
        Material block = Material.matchMaterial(config().getString("marker.block", "RED_WOOL"));
        for (String entry : t.marker) {
            String[] p = entry.split(";", 4);
            try {
                Block b = world.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                if (b.getType() == block) {
                    b.setBlockData(Bukkit.createBlockData(p[3]), false);
                }
            } catch (RuntimeException ignored) {
            }
        }
        t.marker.clear();
    }

    private void clearChest(Treasure t) {
        Location loc = t.location();
        if (loc == null) {
            return;
        }
        Block b = loc.getBlock();
        if (b.getState() instanceof Container container) {
            container.getInventory().clear();
            b.setType(Material.AIR, false);
        }
    }

    // ------------------------------------------------------------------ natural spawning & upkeep

    private String weightedTier(String path) {
        WeightedList<String> list = new WeightedList<>();
        ConfigurationSection sec = config().getConfigurationSection(path);
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                if (tiers.containsKey(k.toLowerCase(Locale.ROOT))) {
                    list.add(k.toLowerCase(Locale.ROOT), sec.getDouble(k));
                }
            }
        }
        if (list.isEmpty()) {
            for (LootTier t : tiers.values()) {
                list.add(t.id, t.weight);
            }
        }
        return list.pick(ThreadLocalRandom.current());
    }

    private void naturalSpawn() {
        if (Bukkit.getOnlinePlayers().size() < config().getInt("natural.min-players-online", 1)) {
            return;
        }
        int max = config().getInt("natural.max-active", 8);
        long natural = treasures.values().stream().filter(t -> t.natural).count();
        if (natural >= max) {
            return;
        }
        String tierId = weightedTier("natural.tier-weights");
        Treasure t = tierId == null ? null : spawnRandom(tierId, true);
        if (t == null) {
            return;
        }
        if (config().getBoolean("natural.announce", true)) {
            Location loc = t.location();
            Bukkit.broadcast(msg("natural-spawn", "tier", tiers.get(t.tier).displayName, "island", islandName(loc),
                    "hint", locationHint(loc)));
        }
    }

    private void expire() {
        long hours = config().getLong("natural.expire-hours", 48);
        if (hours <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - hours * 3600_000L;
        List<Treasure> old = new ArrayList<>();
        for (Treasure t : treasures.values()) {
            // at most a few per minute: removing loads the chunk briefly
            if (t.natural && t.created < cutoff) {
                old.add(t);
            }
            if (old.size() >= 3) {
                break;
            }
        }
        for (Treasure t : old) {
            Location loc = t.location();
            if (loc != null) {
                removeTreasure(loc);
            } else {
                treasures.remove(t.key());
                dirty = true;
            }
        }
    }

    private void effects() {
        double range = Math.max(4, config().getDouble("effects.range", 24));
        for (Treasure t : treasures.values()) {
            LootTier tier = tiers.get(t.tier);
            if (tier == null || (tier.particle == null && !tier.beam)) {
                continue;
            }
            World world = Bukkit.getWorld(t.world);
            if (world == null || !world.isChunkLoaded(t.x >> 4, t.z >> 4)) {
                continue;
            }
            Location base = new Location(world, t.x + 0.5, Math.max(t.y + 1, world.getHighestBlockYAt(t.x, t.z) + 1) + 0.2, t.z + 0.5);
            boolean anyone = false;
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(base) < (tier.beam ? range * range * 9 : range * range)) {
                    anyone = true;
                    break;
                }
            }
            if (!anyone) {
                continue;
            }
            if (tier.particle != null) {
                world.spawnParticle(tier.particle, base, 6, 0.4, 0.3, 0.4, 0.01);
            }
            if (tier.beam) {
                for (int i = 0; i < 24; i += 2) {
                    world.spawnParticle(org.bukkit.Particle.END_ROD, base.clone().add(0, i, 0), 1, 0.05, 0.3, 0.05, 0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ hints

    String islandName(Location loc) {
        IslandService islands = service(IslandService.class);
        Island is = islands == null || loc == null ? null : islands.islandAt(loc);
        return is == null ? Text.plain(rawMsg("no-island-name")) : is.name();
    }

    private static int compassIndex(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        return (int) Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
    }

    /** Rough description of where on its island (or where in the world) a treasure lies. */
    String locationHint(Location loc) {
        if (loc == null) {
            return "";
        }
        IslandService islands = service(IslandService.class);
        Island is = islands == null ? null : islands.islandAt(loc);
        if (is != null) {
            Location c = is.center();
            double dx = loc.getX() - c.getX(), dz = loc.getZ() - c.getZ();
            double rel = Math.sqrt(dx * dx + dz * dz) / Math.max(1, is.radius());
            if (rel < 0.25) {
                return Text.plain(rawMsg("hint-heart"));
            }
            return Text.plain(rawMsg(rel < 0.55 ? "hint-inland" : "hint-shore", "direction", COMPASS[compassIndex(dx, dz)]));
        }
        int round = Math.max(10, config().getInt("maps.coordinate-rounding", 100));
        return Text.plain(rawMsg("hint-coords", "x", Math.round(loc.getX() / round) * round, "z", Math.round(loc.getZ() / round) * round));
    }

    private Component temperature(double distance) {
        ConfigurationSection levels = config().getConfigurationSection("maps.hints.levels");
        if (levels != null) {
            List<double[]> sorted = new ArrayList<>();
            Map<Double, String> text = new HashMap<>();
            for (String k : levels.getKeys(false)) {
                try {
                    double d = Double.parseDouble(k);
                    sorted.add(new double[]{d});
                    text.put(d, levels.getString(k));
                } catch (NumberFormatException ignored) {
                }
            }
            sorted.sort(Comparator.comparingDouble(a -> a[0]));
            for (double[] d : sorted) {
                if (distance <= d[0]) {
                    return Text.mm(text.get(d[0]));
                }
            }
        }
        return Text.mm(config().getString("maps.hints.beyond", "<blue>Freezing"));
    }

    private void showHint(Player player, String link) {
        String[] p = link.split(";");
        if (p.length < 5) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = hintCooldown.get(player.getUniqueId());
        if (last != null && now - last < Math.max(0, config().getLong("maps.hints.cooldown-ms", 1000))) {
            return;
        }
        hintCooldown.put(player.getUniqueId(), now);
        String key;
        int x, y, z;
        try {
            x = Integer.parseInt(p[1]);
            y = Integer.parseInt(p[2]);
            z = Integer.parseInt(p[3]);
        } catch (NumberFormatException e) {
            return;
        }
        key = Treasure.key(p[0], x, y, z);
        if (!treasures.containsKey(key)) {
            player.sendActionBar(rawMsg("map-gone"));
            markMapGone(player);
            return;
        }
        if (!player.getWorld().getName().equals(p[0])) {
            player.sendActionBar(rawMsg("map-wrong-world"));
            return;
        }
        Location loc = player.getLocation();
        double dx = x + 0.5 - loc.getX(), dz = z + 0.5 - loc.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        Component direction = dist > config().getDouble("maps.hints.show-direction-above", 12)
                ? rawMsg("hint-direction", "direction", COMPASS[compassIndex(dx, dz)]) : Component.empty();
        int rounding = config().getInt("maps.hints.distance-rounding", 50);
        Component distance = rounding > 0 && dist > config().getDouble("maps.hints.show-distance-above", 60)
                ? rawMsg("hint-distance", "distance", Text.number(Math.max(rounding, Math.round(dist / rounding) * rounding)))
                : Component.empty();
        player.sendActionBar(rawMsg("map-hint", "temperature", temperature(dist), "direction", direction, "distance", distance));
    }

    private void markMapGone(Player player) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (!meta.getPersistentDataContainer().has(mapKey, PersistentDataType.STRING)) {
                continue;
            }
            meta.displayName(Text.item(config().getString("maps.gone-name", "<gray>Looted Treasure Map")));
            meta.lore(Text.itemLore(config().getStringList("maps.gone-lore")));
            item.setItemMeta(meta);
            player.getInventory().setItem(slot, item);
        }
    }

    private void attach(MapView view, String link) {
        String[] p = link.split(";");
        if (p.length < 5 || attached.containsKey(view.getId())) {
            return;
        }
        try {
            XRenderer renderer = new XRenderer(Treasure.key(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])),
                    Integer.parseInt(p[1]), Integer.parseInt(p[3]));
            view.addRenderer(renderer);
            attached.put(view.getId(), renderer);
        } catch (NumberFormatException ignored) {
        }
    }

    // ------------------------------------------------------------------ finding

    private Treasure treasureAt(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        return treasures.get(Treasure.key(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
    }

    private void found(Player player, Treasure t) {
        treasures.remove(t.key());
        dirty = true;
        LootTier tier = tiers.get(t.tier);
        Location loc = t.location();
        Bukkit.getPluginManager().callEvent(new TreasureFoundEvent(player, loc, t.tier));
        Component tierName = tier == null ? Component.text(t.tier) : tier.displayName;
        String island = islandName(loc);
        EconomyService eco = service(EconomyService.class);
        if (t.doubloons > 0 && eco != null) {
            eco.deposit(player.getUniqueId(), t.doubloons);
            send(player, "found-doubloons", "amount", eco.format(t.doubloons));
        }
        if (tier != null && tier.reputation != 0) {
            CrewService crews = service(CrewService.class);
            Crew crew = crews == null ? null : crews.crewOf(player.getUniqueId());
            if (crew != null) {
                crew.addReputation(tier.reputation);
                crew.broadcast(msg("found-reputation", "player", player.getName(), "reputation", tier.reputation, "tier", tierName));
            }
        }
        if (tier != null && tier.celebrate) {
            celebrate(player, loc);
        }
        if (tier != null && tier.broadcast) {
            Bukkit.broadcast(msg("found-broadcast", "player", player.getName(), "tier", tierName, "island", island));
        } else {
            send(player, "found", "tier", tierName, "island", island);
        }
        if (config().getBoolean("marker.remove-on-find", true)) {
            restoreMarker(t);
        }
        if (tier != null && !tier.vanish.equals("never")) {
            opened.put(t.key(), tier.vanish);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || treasures.isEmpty()) {
            return;
        }
        Treasure t = treasureAt(event.getInventory().getLocation());
        if (t != null) {
            found(player, t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (opened.isEmpty()) {
            return;
        }
        Location loc = event.getInventory().getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        String key = Treasure.key(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        String mode = opened.get(key);
        if (mode == null || event.getInventory().getViewers().size() > 1) {
            return;
        }
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Container)) {
            opened.remove(key);
            return;
        }
        boolean empty = event.getInventory().isEmpty();
        if (mode.equals("close") || empty) {
            if (!empty) {
                for (ItemStack stack : event.getInventory().getContents()) {
                    if (stack != null && !stack.getType().isAir()) {
                        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1, 0.5), stack);
                    }
                }
            }
            event.getInventory().clear();
            opened.remove(key);
            runLater(() -> {
                if (block.getState() instanceof Container c && c.getInventory().isEmpty()) {
                    block.setType(Material.AIR);
                    block.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, block.getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3, 0.01);
                }
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Treasure t = treasureAt(event.getBlock().getLocation());
        if (t != null) {
            found(event.getPlayer(), t);
        }
        opened.remove(Treasure.key(event.getBlock().getWorld().getName(), event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!treasures.isEmpty()) {
            event.blockList().removeIf(b -> treasureAt(b.getLocation()) != null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!treasures.isEmpty()) {
            event.blockList().removeIf(b -> treasureAt(b.getLocation()) != null);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (!treasures.isEmpty() && treasureAt(event.getSource().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseMap(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        String link = item.getItemMeta().getPersistentDataContainer().get(mapKey, PersistentDataType.STRING);
        if (link != null) {
            showHint(event.getPlayer(), link);
        }
    }

    @EventHandler
    public void onMapInit(MapInitializeEvent event) {
        String link = mapLinks.get(event.getMap().getId());
        if (link != null) {
            attach(event.getMap(), link);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hintCooldown.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ map drops

    /** Spawns a fresh treasure and returns a map to it, or null. */
    private ItemStack freshMap() {
        int max = config().getInt("map-drops.max-active", 12);
        if (treasures.size() >= max) {
            return null;
        }
        String tierId = weightedTier("map-drops.tier-weights");
        Treasure t = tierId == null ? null : spawnRandom(tierId, true);
        if (t == null) {
            return null;
        }
        return createTreasureMap(t.location(), t.tier);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        String source;
        if (entity instanceof ElderGuardian) {
            source = "elder-guardian";
        } else if (entity instanceof Guardian) {
            source = "guardian";
        } else if (entity instanceof Drowned) {
            source = "drowned";
        } else {
            return;
        }
        if (config().getBoolean("map-drops.require-player-kill", true) && entity.getKiller() == null) {
            return;
        }
        double chance = config().getDouble("map-drops.chances." + source, 0);
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        ItemStack map = freshMap();
        if (map != null) {
            event.getDrops().add(map);
            if (entity.getKiller() != null) {
                send(entity.getKiller(), "map-dropped");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) {
            return;
        }
        double chance = config().getDouble("map-drops.chances.fishing", 0);
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        ItemStack map = freshMap();
        if (map != null) {
            caught.setItemStack(map);
            send(event.getPlayer(), "map-fished");
        }
    }

    // ------------------------------------------------------------------ commands

    private List<Treasure> sorted() {
        List<Treasure> list = new ArrayList<>(treasures.values());
        list.sort(Comparator.comparingLong(t -> t.created));
        return list;
    }

    private LootTier tierArg(String raw) {
        LootTier t = tier(raw);
        if (t == null) {
            throw new dev.aquatic.command.CommandFail(rawMsg("unknown-tier", "tier", raw, "tiers", String.join(", ", tiers.keySet())));
        }
        return t;
    }

    private Treasure indexArg(String raw) {
        List<Treasure> list = sorted();
        int i = dev.aquatic.command.AquaticCommand.integer(raw);
        if (i < 1 || i > list.size()) {
            throw new dev.aquatic.command.CommandFail(rawMsg("bad-index", "max", list.size()));
        }
        return list.get(i - 1);
    }

    private void give(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    private dev.aquatic.command.AquaticCommand buildCommand() {
        String admin = "aquatic.treasure.admin";
        var cmd = new dev.aquatic.command.AquaticCommand("treasure", "Buried treasure", "tr");
        cmd.sub("info", (sender, args) -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            Set<String> islands = new java.util.TreeSet<>();
            for (Treasure t : treasures.values()) {
                counts.merge(t.tier, 1, Integer::sum);
                Location loc = t.location();
                IslandService is = service(IslandService.class);
                Island island = is == null || loc == null ? null : is.islandAt(loc);
                if (island != null) {
                    islands.add(island.name());
                }
            }
            send(sender, "info-header", "count", treasures.size());
            for (LootTier tier : tiers.values()) {
                int n = counts.getOrDefault(tier.id, 0);
                if (n > 0) {
                    sender.sendMessage(rawMsg("info-tier", "tier", tier.displayName, "count", n));
                }
            }
            if (config().getBoolean("info.reveal-islands", true) && !islands.isEmpty()) {
                sender.sendMessage(rawMsg("info-islands", "islands", String.join(", ", islands)));
            }
            sender.sendMessage(rawMsg("info-tip"));
        }).description("Rumours about buried treasure").playerPermission("aquatic.treasure.info");

        cmd.sub("spawn", (sender, args) -> {
            dev.aquatic.command.AquaticCommand.need(args, 1, "/treasure spawn <tier> [here|random]");
            LootTier tier = tierArg(args[0]);
            boolean random = args.length > 1 && args[1].equalsIgnoreCase("random");
            Treasure t;
            if (random || !(sender instanceof Player)) {
                t = spawnRandom(tier.id, false);
            } else {
                t = spawn(((Player) sender).getLocation(), tier.id, false, true);
            }
            if (t == null) {
                throw new dev.aquatic.command.CommandFail(rawMsg("spawn-failed"));
            }
            send(sender, "spawned", "tier", tier.displayName, "x", t.x, "y", t.y, "z", t.z, "island", islandName(t.location()));
        }).usage("<tier> [here|random]").description("Bury a treasure here or at a random spot").permission(admin)
                .completer((s, a) -> switch (a.length) {
                    case 1 -> dev.aquatic.command.AquaticCommand.filter(tiers.keySet(), a[0]);
                    case 2 -> dev.aquatic.command.AquaticCommand.filter(List.of("here", "random"), a[1]);
                    default -> List.of();
                });

        cmd.sub("map", (sender, args) -> {
            dev.aquatic.command.AquaticCommand.need(args, 1, "/treasure map <tier> [player]");
            LootTier tier = tierArg(args[0]);
            Player target = args.length > 1 ? dev.aquatic.command.AquaticCommand.onlinePlayer(args[1]) : dev.aquatic.command.AquaticCommand.player(sender);
            Treasure t = spawnRandom(tier.id, false);
            if (t == null) {
                throw new dev.aquatic.command.CommandFail(rawMsg("spawn-failed"));
            }
            ItemStack map = createTreasureMap(t.location(), t.tier);
            give(target, map);
            send(target, "map-received", "tier", tier.displayName);
            if (target != sender) {
                send(sender, "map-given", "player", target.getName(), "tier", tier.displayName, "x", t.x, "y", t.y, "z", t.z);
            }
        }).usage("<tier> [player]").description("Bury a treasure and hand out a map to it").permission(admin)
                .completer((s, a) -> switch (a.length) {
                    case 1 -> dev.aquatic.command.AquaticCommand.filter(tiers.keySet(), a[0]);
                    case 2 -> dev.aquatic.command.AquaticCommand.playerNames(a[1]);
                    default -> List.of();
                });

        cmd.sub("list", (sender, args) -> {
            List<Treasure> list = sorted();
            if (list.isEmpty()) {
                send(sender, "list-empty");
                return;
            }
            send(sender, "list-header", "count", list.size());
            int i = 0;
            for (Treasure t : list) {
                i++;
                LootTier tier = tiers.get(t.tier);
                sender.sendMessage(rawMsg("list-entry", "index", i, "tier", tier == null ? Component.text(t.tier) : tier.displayName,
                        "world", t.world, "x", t.x, "y", t.y, "z", t.z, "island", islandName(t.location()),
                        "age", Text.duration((System.currentTimeMillis() - t.created) / 1000), "source", t.natural ? "natural" : "admin"));
            }
        }).description("List active treasures").permission(admin);

        cmd.sub("tp", (sender, args) -> {
            dev.aquatic.command.AquaticCommand.need(args, 1, "/treasure tp <index>");
            Player p = dev.aquatic.command.AquaticCommand.player(sender);
            Treasure t = indexArg(args[0]);
            World w = Bukkit.getWorld(t.world);
            if (w == null) {
                throw new dev.aquatic.command.CommandFail(rawMsg("spawn-failed"));
            }
            int y = Math.max(t.y + 1, w.getHighestBlockYAt(t.x, t.z) + 1);
            p.teleportAsync(new Location(w, t.x + 0.5, y, t.z + 0.5));
        }).usage("<index>").description("Teleport to a treasure").permission(admin).playerOnly()
                .completer((s, a) -> a.length == 1 ? indexes(a[0]) : List.of());

        cmd.sub("remove", (sender, args) -> {
            dev.aquatic.command.AquaticCommand.need(args, 1, "/treasure remove <index|all>");
            if (args[0].equalsIgnoreCase("all")) {
                int n = treasures.size();
                for (Treasure t : new ArrayList<>(treasures.values())) {
                    Location loc = t.location();
                    if (loc != null) {
                        removeTreasure(loc);
                    }
                    treasures.remove(t.key());
                }
                dirty = true;
                send(sender, "removed-all", "count", n);
                return;
            }
            Treasure t = indexArg(args[0]);
            Location loc = t.location();
            if (loc != null) {
                removeTreasure(loc);
            }
            treasures.remove(t.key());
            dirty = true;
            send(sender, "removed", "x", t.x, "y", t.y, "z", t.z);
        }).usage("<index|all>").description("Remove treasures (chest included)").permission(admin)
                .completer((s, a) -> {
                    if (a.length != 1) {
                        return List.of();
                    }
                    List<String> out = new ArrayList<>(indexes(""));
                    out.add("all");
                    return dev.aquatic.command.AquaticCommand.filter(out, a[0]);
                });

        cmd.sub("sealedmap", (sender, args) -> {
            dev.aquatic.command.AquaticCommand.need(args, 1, "/treasure sealedmap <tier> [player]");
            LootTier tier = tierArg(args[0]);
            Player target = args.length > 1 ? dev.aquatic.command.AquaticCommand.onlinePlayer(args[1]) : dev.aquatic.command.AquaticCommand.player(sender);
            give(target, sealedMap(tier.id));
            send(sender, "sealed-map-given", "player", target.getName(), "tier", tier.displayName);
        }).usage("<tier> [player]").description("Hand out a sealed map (the treasure is buried when it is opened)").permission(admin)
                .completer((s, a) -> switch (a.length) {
                    case 1 -> dev.aquatic.command.AquaticCommand.filter(tiers.keySet(), a[0]);
                    case 2 -> dev.aquatic.command.AquaticCommand.playerNames(a[1]);
                    default -> List.of();
                });
        cmd.sub("loot", (sender, args) -> {
            dev.aquatic.command.AquaticCommand.need(args, 2, "/treasure loot <add|list|remove> <tier> ...");
            String what = args[0].toLowerCase(Locale.ROOT);
            LootTier tier = tierArg(args[1]);
            String path = "tiers." + tier.id + ".loot";
            List<Map<?, ?>> loot = new java.util.ArrayList<>(config().getMapList(path));
            switch (what) {
                case "add" -> {
                    Player p = dev.aquatic.command.AquaticCommand.player(sender);
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand.getType().isAir()) {
                        throw new dev.aquatic.command.CommandFail(rawMsg("loot-hold-item"));
                    }
                    double weight = args.length > 2 ? dev.aquatic.command.AquaticCommand.number(args[2]) : 5;
                    loot.add(lootEntry(hand, weight));
                    config().set(path, loot);
                    saveConfig();
                    reloadTier(tier.id);
                    send(sender, "loot-added", "item", dev.aquatic.util.ItemSpecs.describe(hand), "tier", tier.displayName, "weight", weight);
                }
                case "list" -> {
                    send(sender, "loot-list-header", "tier", tier.displayName, "count", loot.size());
                    for (int i = 0; i < loot.size(); i++) {
                        sender.sendMessage(rawMsg("loot-list-line", "number", i + 1, "entry", String.valueOf(loot.get(i))));
                    }
                }
                case "remove" -> {
                    dev.aquatic.command.AquaticCommand.need(args, 3, "/treasure loot remove <tier> <number>");
                    int n = (int) dev.aquatic.command.AquaticCommand.number(args[2]);
                    if (n < 1 || n > loot.size()) {
                        throw new dev.aquatic.command.CommandFail(rawMsg("loot-bad-number", "max", loot.size()));
                    }
                    Map<?, ?> removed = loot.remove(n - 1);
                    config().set(path, loot);
                    saveConfig();
                    reloadTier(tier.id);
                    send(sender, "loot-removed", "entry", String.valueOf(removed), "tier", tier.displayName);
                }
                default -> throw new dev.aquatic.command.CommandFail(rawMsg("loot-usage"));
            }
        }).usage("<add|list|remove> <tier> [weight|number]").description("Edit a tier's loot in-game (add = the item in your hand)")
                .permission("aquatic.treasure.admin")
                .completer((s, a) -> a.length == 1 ? dev.aquatic.command.AquaticCommand.filter(List.of("add", "list", "remove"), a[0])
                        : a.length == 2 ? dev.aquatic.command.AquaticCommand.filter(List.copyOf(tiers.keySet()), a[1]) : List.of());
        cmd.sub("give-loot", (sender, args) -> {
            dev.aquatic.command.AquaticCommand.need(args, 1, "/treasure give-loot <tier>");
            Player p = dev.aquatic.command.AquaticCommand.player(sender);
            LootTier tier = tierArg(args[0]);
            LootTier.Roll roll = tier.roll(new Random(), () -> service(ItemService.class));
            for (ItemStack stack : roll.items()) {
                give(p, stack);
            }
            for (String mapTier : roll.maps()) {
                ItemStack sealed = sealedMap(mapTier);
                if (sealed != null) {
                    give(p, sealed);
                }
            }
            if (roll.jackpot()) {
                send(p, "loot-preview-jackpot");
            }
            EconomyService eco = service(EconomyService.class);
            send(p, "loot-preview", "tier", tier.displayName, "count", roll.items().size(),
                    "doubloons", eco != null ? eco.format(roll.doubloons()) : Text.number(roll.doubloons()));
        }).usage("<tier>").description("Preview a loot roll (items go to you)").permission(admin).playerOnly()
                .completer((s, a) -> a.length == 1 ? dev.aquatic.command.AquaticCommand.filter(tiers.keySet(), a[0]) : List.of());
        return cmd;
    }

    private List<String> indexes(String prefix) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= treasures.size(); i++) {
            out.add(String.valueOf(i));
        }
        return dev.aquatic.command.AquaticCommand.filter(out, prefix);
    }

    @Override
    protected void migrateConfig(org.bukkit.configuration.file.YamlConfiguration file,
                                 org.bukkit.configuration.file.YamlConfiguration defaults, int from) {
        if (from < 9) {
            // 2.28.0: the Dread Captain set in epic treasure
            List<Map<?, ?>> loot = new ArrayList<>(file.getMapList("tiers.epic.loot"));
            if (file.contains("tiers.epic.loot") && loot.stream().noneMatch(e -> "dread_captain_coat".equals(String.valueOf(e.get("custom-item"))))) {
                for (Map<?, ?> d : defaults.getMapList("tiers.epic.loot")) {
                    if (String.valueOf(d.get("custom-item")).startsWith("dread_captain_")) {
                        loot.add(d);
                    }
                }
                file.set("tiers.epic.loot", loot);
            }
        }
        if (from < 8) {
            // 2.27.0: the Steel Cutlass and the Boarding Axe in common treasure
            List<Map<?, ?>> loot = new ArrayList<>(file.getMapList("tiers.common.loot"));
            if (file.contains("tiers.common.loot") && loot.stream().noneMatch(e -> "steel_cutlass".equals(String.valueOf(e.get("custom-item"))))) {
                loot.add(Map.of("custom-item", "steel_cutlass", "weight", 1.5));
                loot.add(Map.of("custom-item", "boarding_axe", "weight", 1));
                file.set("tiers.common.loot", loot);
            }
        }
        if (from < 7) {
            // 2.26.0: MEGA treasure holds an Epic or Legendary fruit
            List<Map<?, ?>> g = file.getMapList("tiers.mega.guaranteed");
            if (g.size() == 1 && "fruit_random".equals(String.valueOf(g.get(0).get("custom-item")))) {
                file.set("tiers.mega.guaranteed", defaults.get("tiers.mega.guaranteed"));
            }
        }
        if (from < 6) {
            // 2.23.0: MEGA Legendary is a tier of its own; legendary holds exactly one fruit
            if ("14-18".equals(file.getString("tiers.legendary.rolls")) && file.getBoolean("tiers.legendary.celebrate")) {
                file.set("tiers.legendary", defaults.get("tiers.legendary"));
            }
            addMissing(file, defaults, "tiers.mega");
            addMissing(file, defaults, "natural.tier-weights.mega");
            addMissing(file, defaults, "map-drops.tier-weights.mega");
        }
        if (from < 5) {
            // 2.16.0: MEGA legendary treasure (only if the legendary tier was never edited)
            String p = "tiers.legendary.";
            if ("7-9".equals(file.getString(p + "rolls")) && "1500-3000".equals(file.getString(p + "doubloons"))) {
                file.set("tiers.legendary", defaults.get("tiers.legendary"));
            }
            addMissing(file, defaults, "messages.mega-title");
            addMissing(file, defaults, "messages.mega-subtitle");
        }
        if (from < 4) {
            // 2.15.0: crate keys in treasure, editing loot in-game
            for (String tier : List.of("uncommon", "rare", "epic", "legendary")) {
                String path = "tiers." + tier + ".loot";
                List<Map<?, ?>> loot = new ArrayList<>(file.getMapList(path));
                boolean hasKey = loot.stream().anyMatch(m -> String.valueOf(m.get("item")).startsWith("key:"));
                if (!file.contains(path) || hasKey) {
                    continue;
                }
                for (Map<?, ?> d : defaults.getMapList(path)) {
                    if (String.valueOf(d.get("item")).startsWith("key:")) {
                        loot.add(d);
                    }
                }
                file.set(path, loot);
            }
            org.bukkit.configuration.ConfigurationSection msgs = defaults.getConfigurationSection("messages");
            if (msgs != null) {
                for (String key : msgs.getKeys(false)) {
                    if (key.startsWith("loot-")) {
                        addMissing(file, defaults, "messages." + key);
                    }
                }
            }
        }
        if (from < 1) {
            // 2.4.0: common and uncommon treasure turn up more often
            raise(file, "natural.interval-minutes", 20, 8);
            raise(file, "natural.max-active", 8, 16);
            raise(file, "natural.tier-weights.common", 10, 16);
            raise(file, "natural.tier-weights.uncommon", 7, 11);
        }
        if (from < 2) {
            // 2.4.1: better common/uncommon loot (only replaced when the loot list is still the old default)
            replaceLoot(file, defaults, "common", "GOLD_NUGGET,GOLD_INGOT,IRON_INGOT,EMERALD,COOKED_COD,BREAD,ARROW,GUNPOWDER,"
                    + "NAUTILUS_SHELL,NAME_TAG,LEATHER_HELMET,custom:smoke_bomb,doubloons");
            replaceLoot(file, defaults, "uncommon", "GOLD_INGOT,IRON_INGOT,EMERALD,COPPER_INGOT,LAPIS_LAZULI,LANTERN,SPYGLASS,"
                    + "GOLDEN_CARROT,EXPERIENCE_BOTTLE,IRON_PICKAXE,IRON_AXE,CROSSBOW,ENCHANTED_BOOK,NAUTILUS_SHELL,custom:tide_compass,"
                    + "custom:rum,custom:fire_bomb,custom:frost_bomb,custom:healing_tonic,doubloons");
            raise(file, "tiers.common.rolls", "3-5", "4-6");
            raise(file, "tiers.common.doubloons", "25-75", "60-140");
            raise(file, "tiers.uncommon.rolls", "3-5", "4-6");
            raise(file, "tiers.uncommon.doubloons", "50-120", "90-200");
            raise(file, "tiers.rare.rolls", "4-6", "5-7");
            raise(file, "tiers.rare.doubloons", "100-250", "200-400");
            addMissing(file, defaults, "sealed-map-lore");
        }
        if (from < 3) {
            // 2.5.0: rebalanced rare/epic/legendary loot (no totems), jackpots, sealed maps
            replaceLoot(file, defaults, "rare", "GOLD_INGOT,EMERALD,DIAMOND,GOLDEN_APPLE,NAUTILUS_SHELL,NAME_TAG,SADDLE,EXPERIENCE_BOTTLE,IRON_SWORD,BOW,ENCHANTED_BOOK,ENCHANTED_BOOK,MUSIC_DISC_CAT,MUSIC_DISC_MALL,custom:tide_compass,custom:smoke_bomb,custom:frost_bomb,custom:healing_tonic,custom:tide_rapier,custom:marauders_buckler,doubloons");
            replaceLoot(file, defaults, "epic", "DIAMOND,EMERALD_BLOCK,GOLD_BLOCK,HEART_OF_THE_SEA,TRIDENT,DIAMOND_SWORD,DIAMOND_CHESTPLATE,ENCHANTED_BOOK,ENCHANTED_BOOK,ENCHANTED_GOLDEN_APPLE,TOTEM_OF_UNDYING,MUSIC_DISC_PIGSTEP,SPYGLASS,custom:healing_tonic,custom:gale_grog,custom:captains_coat,custom:fruit_random,custom:fire_bomb,custom:storm_saber,custom:abyss_cutlass,custom:krakens_maul,custom:tide_rapier,doubloons");
            replaceLoot(file, defaults, "legendary", "NETHERITE_SCRAP,NETHERITE_INGOT,DIAMOND_BLOCK,HEART_OF_THE_SEA,ENCHANTED_GOLDEN_APPLE,TOTEM_OF_UNDYING,TRIDENT,NETHERITE_SWORD,ENCHANTED_BOOK,MUSIC_DISC_OTHERSIDE,MUSIC_DISC_RELIC,NAME_TAG,custom:storm_saber,custom:abyss_cutlass,custom:krakens_maul,custom:coral_trident,custom:captains_coat,custom:fire_bomb,custom:healing_tonic,custom:fruit_random,doubloons");
            raise(file, "tiers.epic.rolls", "5-7", "6-8");
            raise(file, "tiers.epic.doubloons", "300-600", "400-800");
            raise(file, "tiers.legendary.rolls", "6-8", "7-9");
            raise(file, "tiers.legendary.doubloons", "1000-2500", "1500-3000");
        }
    }

    /** Swaps a tier's loot (and adds its jackpot) when the list still matches the old default. */
    private static void replaceLoot(org.bukkit.configuration.file.YamlConfiguration file,
                                    org.bukkit.configuration.file.YamlConfiguration defaults, String tier, String oldSignature) {
        String path = "tiers." + tier + ".loot";
        List<String> names = new ArrayList<>();
        for (Map<?, ?> m : file.getMapList(path)) {
            names.add(m.get("material") != null ? String.valueOf(m.get("material"))
                    : m.get("custom-item") != null ? "custom:" + m.get("custom-item")
                    : m.get("treasure-map") != null ? "map:" + m.get("treasure-map") : "doubloons");
        }
        if (String.join(",", names).equals(oldSignature)) {
            file.set(path, defaults.get(path));
        }
        addMissing(file, defaults, "tiers." + tier + ".jackpot");
    }

    // ------------------------------------------------------------------ sealed treasure maps (found in chests)

    /** A sealed map: right-click to break the seal, which buries a fresh treasure of that tier and charts it. */
    ItemStack sealedMap(String tierId) {
        LootTier tier = tiers.get(tierId == null ? "" : tierId.toLowerCase(Locale.ROOT));
        if (tier == null) {
            return null;
        }
        ItemStack stack = new ItemStack(org.bukkit.Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.item(config().getString("messages.sealed-map-name", "<gold>Sealed Treasure Map"), "tier", tier.displayName));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : config().getStringList("sealed-map-lore")) {
            lore.add(Text.item(line, "tier", tier.displayName));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(sealedKey, PersistentDataType.STRING, tier.id);
        stack.setItemMeta(meta);
        return stack;
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = false)
    public void onUnseal(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || event.getItem() == null
                || (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemStack hand = event.getItem();
        String tierId = hand.hasItemMeta() ? hand.getItemMeta().getPersistentDataContainer().get(sealedKey, PersistentDataType.STRING) : null;
        if (tierId == null) {
            return;
        }
        event.setCancelled(true);
        Player p = event.getPlayer();
        LootTier tier = tiers.get(tierId);
        Treasure t = tier == null ? null : spawnRandom(tier.id, false);
        if (t == null) {
            send(p, "map-seal-failed");
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        give(p, createTreasureMap(t.location(), t.tier));
        send(p, "map-unsealed", "tier", tier.displayName);
    }

    /** A loot entry for the item in someone's hand: a custom item, a crate key, or a vanilla item with its enchantments. */
    private Map<String, Object> lootEntry(ItemStack hand, double weight) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        ItemService items = service(ItemService.class);
        String custom = items == null ? null : items.idOf(hand);
        String key = hand.hasItemMeta() ? hand.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin(), "crate_key"), org.bukkit.persistence.PersistentDataType.STRING) : null;
        if (custom != null) {
            e.put("custom-item", custom);
            e.put("amount", String.valueOf(hand.getAmount()));
        } else if (key != null) {
            e.put("item", "key:" + key + ":" + hand.getAmount());
        } else {
            e.put("material", hand.getType().name());
            e.put("amount", String.valueOf(hand.getAmount()));
            List<String> enchants = new java.util.ArrayList<>();
            java.util.Map<org.bukkit.enchantments.Enchantment, Integer> all = hand.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta book
                    ? book.getStoredEnchants() : hand.getEnchantments();
            all.forEach((ench, lvl) -> enchants.add(ench.getKey().getKey().toUpperCase(Locale.ROOT) + ":" + lvl));
            if (!enchants.isEmpty()) {
                e.put("enchants", enchants);
            }
        }
        e.put("weight", weight);
        return e;
    }

    private void reloadTier(String id) {
        ConfigurationSection s = config().getConfigurationSection("tiers." + id);
        if (s != null) {
            tiers.put(id, LootTier.load(id, s, logger()));
        }
    }

    /** MEGA finds: fireworks over the chest, a fanfare for everyone and a title for the finder. */
    private void celebrate(Player player, Location loc) {
        org.bukkit.World w = loc.getWorld();
        if (w == null) {
            return;
        }
        org.bukkit.Color[] colors = {org.bukkit.Color.fromRGB(0xFFCC00), org.bukkit.Color.fromRGB(0xFF6A00), org.bukkit.Color.fromRGB(0x3FD8FF),
                org.bukkit.Color.fromRGB(0xFFFFFF)};
        for (int i = 0; i < 6; i++) {
            int n = i;
            runLater(() -> {
                Location at = loc.clone().add(0.5 + (Math.random() - 0.5) * 6, 2, 0.5 + (Math.random() - 0.5) * 6);
                w.spawn(at, org.bukkit.entity.Firework.class, fw -> {
                    org.bukkit.inventory.meta.FireworkMeta meta = fw.getFireworkMeta();
                    meta.addEffect(org.bukkit.FireworkEffect.builder().with(n % 2 == 0 ? org.bukkit.FireworkEffect.Type.BALL_LARGE
                                    : org.bukkit.FireworkEffect.Type.STAR).withColor(colors[n % colors.length], colors[(n + 1) % colors.length])
                            .withFade(org.bukkit.Color.WHITE).trail(true).flicker(true).build());
                    meta.setPower(1);
                    fw.setFireworkMeta(meta);
                    fw.getPersistentDataContainer().set(new NamespacedKey(plugin(), "harmless"), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                });
            }, i * 8L);
        }
        w.spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, loc.clone().add(0.5, 1, 0.5), 120, 0.6, 1.2, 0.6, 0.4);
        player.showTitle(net.kyori.adventure.title.Title.title(rawMsg("mega-title"), rawMsg("mega-subtitle")));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 0.8f);
        }
    }

    /** Celebration fireworks never hurt anyone. */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onFireworkDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof org.bukkit.entity.Firework fw
                && fw.getPersistentDataContainer().has(new NamespacedKey(plugin(), "harmless"), org.bukkit.persistence.PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }
}
