package dev.aquatic.world;

import dev.aquatic.api.EconomyService;
import dev.aquatic.api.ItemService;
import dev.aquatic.api.LevelService;
import dev.aquatic.api.TreasureService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ships that sail the open sea (islands.yml "ships"): a Tidewatch patrol or a crew of cursed pirates. Board one,
 * fight the crew and the captain; when the last one falls the chests fill with loot and the ship stays afloat for
 * a while (sink-minutes), then it goes down and is gone. Ships nobody boards sail away after expire-hours.
 */
final class Ships implements Listener {

    private static final class Ship {
        String id, kind, design, world, state = "active";
        int x, y, z, rot, remaining;
        boolean captainAlive = true, boarded;
        long sinkAt, created;
        // rebuilt from the design
        ShipDesign d;
        final List<int[]> cells = new ArrayList<>();          // world x, y, z of every cell
        final List<BlockData> data = new ArrayList<>();
        int minX, maxX, minY, maxY, minZ, maxZ;
        final Set<UUID> alive = new HashSet<>();
        UUID captain;
        BossBar bar;
        final Set<UUID> viewers = new HashSet<>();
        BukkitTask sinking;

        boolean contains(Block b, int margin) {
            return b.getWorld().getName().equals(world) && b.getX() >= minX - margin && b.getX() <= maxX + margin
                    && b.getY() >= minY - margin && b.getY() <= maxY + margin && b.getZ() >= minZ - margin && b.getZ() <= maxZ + margin;
        }

        Location center(World w) {
            return new Location(w, (minX + maxX) / 2.0 + 0.5, y + 5, (minZ + maxZ) / 2.0 + 0.5);
        }
    }

    private final IslandsModule m;
    private final NamespacedKey shipKey, captainKey;
    private final Map<String, Ship> ships = new LinkedHashMap<>();
    private long lastSpawn = System.currentTimeMillis();
    private boolean searching;

    Ships(IslandsModule module) {
        this.m = module;
        this.shipKey = new NamespacedKey(module.plugin(), "ship");
        this.captainKey = new NamespacedKey(module.plugin(), "ship_captain");
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = m.config().getConfigurationSection("ships");
        return s != null ? s : new YamlConfiguration();
    }

    private ConfigurationSection kind(String id) {
        return cfg().getConfigurationSection("kinds." + id);
    }

    // ------------------------------------------------------------------ state

    void load() {
        ships.clear();
        ConfigurationSection all = m.loadData("ships").getConfigurationSection("ships");
        if (all == null) {
            return;
        }
        for (String id : all.getKeys(false)) {
            ConfigurationSection c = all.getConfigurationSection(id);
            Ship s = new Ship();
            s.id = id;
            s.kind = c.getString("kind", "tidewatch");
            s.design = c.getString("design", "sloop");
            s.world = c.getString("world", "world");
            s.x = c.getInt("x");
            s.y = c.getInt("y");
            s.z = c.getInt("z");
            s.rot = c.getInt("rot");
            s.remaining = c.getInt("remaining");
            s.captainAlive = c.getBoolean("captain", true);
            s.boarded = c.getBoolean("boarded");
            s.state = c.getString("state", "active");
            s.sinkAt = c.getLong("sink-at");
            s.created = c.getLong("created");
            build(s);
            ships.put(id, s);
        }
    }

    void save() {
        YamlConfiguration out = new YamlConfiguration();
        for (Ship s : ships.values()) {
            String p = "ships." + s.id + ".";
            out.set(p + "kind", s.kind);
            out.set(p + "design", s.design);
            out.set(p + "world", s.world);
            out.set(p + "x", s.x);
            out.set(p + "y", s.y);
            out.set(p + "z", s.z);
            out.set(p + "rot", s.rot);
            out.set(p + "remaining", s.remaining);
            out.set(p + "captain", s.captainAlive);
            out.set(p + "boarded", s.boarded);
            out.set(p + "state", s.state);
            out.set(p + "sink-at", s.sinkAt);
            out.set(p + "created", s.created);
        }
        m.saveData("ships", out);
    }

    void shutdown() {
        for (Ship s : ships.values()) {
            despawnCrew(s);
            hideBar(s);
            if (s.sinking != null) {
                s.sinking.cancel();
                World w = Bukkit.getWorld(s.world);
                if (w != null) {
                    wipe(s, w);                       // a ship caught half-way down is simply gone
                }
                s.state = "gone";
            }
        }
        ships.values().removeIf(s -> s.state.equals("gone"));
        save();
    }

    // ------------------------------------------------------------------ building

    private static Material material(String name, Material fallback) {
        Material mat = name == null ? null : Material.matchMaterial(name);
        return mat == null ? fallback : mat;
    }

    /** Works out the ship's blocks in the world from its design, position and heading (nothing is placed). */
    private void build(Ship s) {
        ConfigurationSection k = kind(s.kind);
        s.d = ShipDesign.of(s.design, material(k == null ? null : k.getString("sail"), Material.WHITE_WOOL),
                material(k == null ? null : k.getString("trim"), Material.BLUE_WOOL),
                material(k == null ? null : k.getString("banner"), Material.BLUE_BANNER));
        StructureRotation sr = switch (s.rot & 3) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
        s.cells.clear();
        s.data.clear();
        s.minX = s.minY = s.minZ = Integer.MAX_VALUE;
        s.maxX = s.maxY = s.maxZ = Integer.MIN_VALUE;
        for (Blueprint.Cell c : s.d.bp.cells()) {
            int[] p = world(s, c.x(), c.y(), c.z());
            BlockData bd = c.data().clone();
            bd.rotate(sr);
            s.cells.add(p);
            s.data.add(bd);
            s.minX = Math.min(s.minX, p[0]);
            s.maxX = Math.max(s.maxX, p[0]);
            s.minY = Math.min(s.minY, p[1]);
            s.maxY = Math.max(s.maxY, p[1]);
            s.minZ = Math.min(s.minZ, p[2]);
            s.maxZ = Math.max(s.maxZ, p[2]);
        }
    }

    /** Local design coordinates -> world. The design's middle (along its length) sits on the ship's position. */
    private static int[] world(Ship s, int lx, int ly, int lz) {
        int x = lx, z = lz - ShipDesign.half(s.design);
        for (int i = 0; i < (s.rot & 3); i++) {
            int t = x;
            x = -z;
            z = t;
        }
        return new int[]{s.x + x, s.y + ly, s.z + z};
    }

    private static Location feet(World w, int[] p) {
        return new Location(w, p[0] + 0.5, p[1], p[2] + 0.5);
    }

    private static BlockData sea(World w, int y) {
        return Bukkit.createBlockData(y < w.getSeaLevel() ? Material.WATER : Material.AIR);
    }

    /** Puts the sea back where the ship is. */
    private void wipe(Ship s, World w) {
        for (int[] p : s.cells) {
            w.getBlockAt(p[0], p[1], p[2]).setBlockData(sea(w, p[1]), false);
        }
    }

    /** Creates a ship of this kind at a spot on the sea. */
    private Ship create(String kindId, Location at, int rot) {
        ConfigurationSection k = kind(kindId);
        World w = at.getWorld();
        if (k == null || w == null) {
            return null;
        }
        Ship s = new Ship();
        s.id = kindId + "-" + Long.toString(System.currentTimeMillis() % 1679616L, 36);
        s.kind = kindId;
        s.design = k.getString("design", "sloop");
        s.world = w.getName();
        s.x = at.getBlockX();
        s.y = w.getSeaLevel() - 3;
        s.z = at.getBlockZ();
        s.rot = rot & 3;
        s.remaining = Math.max(1, k.getInt("crew-size", 8));
        s.created = System.currentTimeMillis();
        build(s);
        for (int i = 0; i < s.cells.size(); i++) {
            int[] p = s.cells.get(i);
            w.getBlockAt(p[0], p[1], p[2]).setBlockData(s.data.get(i), false);
        }
        ships.put(s.id, s);
        save();
        return s;
    }

    private String randomKind() {
        ConfigurationSection kinds = cfg().getConfigurationSection("kinds");
        if (kinds == null) {
            return null;
        }
        double total = 0;
        for (String id : kinds.getKeys(false)) {
            total += Math.max(0, kinds.getDouble(id + ".weight", 1));
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (String id : kinds.getKeys(false)) {
            roll -= Math.max(0, kinds.getDouble(id + ".weight", 1));
            if (roll < 0) {
                return id;
            }
        }
        return null;
    }

    /** Looks for a free stretch of deep sea and puts a new ship there. */
    void spawnRandom(String kindId, org.bukkit.command.CommandSender tell) {
        String id = kindId != null ? kindId : randomKind();
        if (id == null || kind(id) == null || searching) {
            return;
        }
        searching = true;
        java.util.function.Consumer<Location> place = spot -> {
            searching = false;
            if (spot == null) {
                if (tell != null) {
                    tell.sendMessage(Text.mm("<red>No free stretch of sea found. Try again."));
                }
                return;
            }
            for (Ship other : ships.values()) {
                if (other.world.equals(spot.getWorld().getName()) && Math.abs(other.x - spot.getBlockX()) < 120
                        && Math.abs(other.z - spot.getBlockZ()) < 120) {
                    return;                               // too close to another ship: next time
                }
            }
            Ship s = create(id, spot, ThreadLocalRandom.current().nextInt(4));
            if (s == null) {
                return;
            }
            if (cfg().getBoolean("announce", true)) {
                Bukkit.broadcast(m.msg("ship-sighted", "ship", Text.mm(kind(id).getString("name", id)),
                        "x", Math.round(s.x / 50.0) * 50, "z", Math.round(s.z / 50.0) * 50));
            }
            if (tell != null) {
                tell.sendMessage(Text.mm("<green>Ship <white><id></white> at <white><x>, <z></white>.", "id", s.id, "x", s.x, "z", s.z));
            }
        };
        List<Player> sailors = new ArrayList<>();
        World sea = m.oceanWorld();
        if (sea != null) {
            for (Player p : sea.getPlayers()) {
                if (p.getGameMode() != GameMode.SPECTATOR) {
                    sailors.add(p);
                }
            }
        }
        if (sailors.isEmpty()) {
            m.findFreeOceanSpotAsync(26, place);
        } else {
            nearPlayer(sailors.get(ThreadLocalRandom.current().nextInt(sailors.size())), 10, place);
        }
    }

    /** A spot of deep, open sea a little way from a player (spawn-distance-min to -max blocks), or null. */
    private void nearPlayer(Player p, int tries, java.util.function.Consumer<Location> done) {
        World w = p.getWorld();
        if (tries <= 0 || !p.isOnline()) {
            m.findFreeOceanSpotAsync(26, done);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int min = Math.max(40, cfg().getInt("spawn-distance-min", 120));
        int max = Math.max(min + 10, cfg().getInt("spawn-distance-max", 320));
        double a = r.nextDouble() * Math.PI * 2, d = min + r.nextDouble() * (max - min);
        int x = p.getLocation().getBlockX() + (int) Math.round(Math.cos(a) * d);
        int z = p.getLocation().getBlockZ() + (int) Math.round(Math.sin(a) * d);
        boolean crowded = m.overlaps(w.getName(), x, z, 40, null);
        for (Ship other : ships.values()) {
            crowded |= other.world.equals(w.getName()) && Math.abs(other.x - x) < 80 && Math.abs(other.z - z) < 80;
        }
        if (crowded) {
            nearPlayer(p, tries - 1, done);
            return;
        }
        int[][] pts = {{x, z}, {x + 18, z}, {x - 18, z}, {x, z + 18}, {x, z - 18}};
        java.util.concurrent.CompletableFuture<?>[] loads = new java.util.concurrent.CompletableFuture<?>[pts.length];
        for (int i = 0; i < pts.length; i++) {
            loads[i] = w.getChunkAtAsync(pts[i][0] >> 4, pts[i][1] >> 4);
        }
        java.util.concurrent.CompletableFuture.allOf(loads).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(m.plugin(), () -> {
            boolean deep = err == null;
            for (int[] pt : pts) {
                deep &= w.getHighestBlockYAt(pt[0], pt[1], org.bukkit.HeightMap.OCEAN_FLOOR) <= w.getSeaLevel() - 8;
            }
            if (deep) {
                done.accept(new Location(w, x + 0.5, w.getSeaLevel(), z + 0.5));
            } else {
                nearPlayer(p, tries - 1, done);
            }
        }));
    }

    // ------------------------------------------------------------------ the crew

    private ConfigurationSection template(String name) {
        ConfigurationSection own = cfg().getConfigurationSection("mobs." + name);
        return own != null ? own : m.config().getConfigurationSection("tidewatch.mobs." + name);
    }

    private String pickTemplate(ConfigurationSection k) {
        List<String> list = k.getStringList("crew");
        double total = 0;
        for (String s : list) {
            String[] p = s.split(":");
            total += p.length > 1 ? Double.parseDouble(p[1]) : 1;
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (String s : list) {
            String[] p = s.split(":");
            roll -= p.length > 1 ? Double.parseDouble(p[1]) : 1;
            if (roll < 0) {
                return p[0];
            }
        }
        return list.isEmpty() ? "recruit" : list.get(0).split(":")[0];
    }

    private LivingEntity spawn(Ship s, String templateName, int[] local, List<Player> targets, boolean captain) {
        World w = Bukkit.getWorld(s.world);
        if (w == null || m.garrison() == null) {
            return null;
        }
        LivingEntity le = m.garrison().spawnFrom(template(templateName), feet(w, world(s, local[0], local[1], local[2])), targets);
        if (le != null) {
            le.getPersistentDataContainer().set(shipKey, PersistentDataType.STRING, s.id);
            if (captain) {
                le.getPersistentDataContainer().set(captainKey, PersistentDataType.BYTE, (byte) 1);
            }
        }
        return le;
    }

    private void despawnCrew(Ship s) {
        for (UUID u : s.alive) {
            Entity e = Bukkit.getEntity(u);
            if (e != null) {
                e.remove();
            }
        }
        s.alive.clear();
        Entity c = s.captain == null ? null : Bukkit.getEntity(s.captain);
        if (c != null) {
            c.remove();
        }
        s.captain = null;
    }

    private List<Player> near(Ship s, World w, double radius) {
        List<Player> out = new ArrayList<>();
        Location c = s.center(w);
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR && p.getLocation().distanceSquared(c) <= radius * radius) {
                out.add(p);
            }
        }
        return out;
    }

    /** Every two seconds: new ships, crews for ships with visitors, the countdown of cleared ships. */
    void tick() {
        if (!cfg().getBoolean("enabled", true)) {
            return;
        }
        long now = System.currentTimeMillis();
        long gap = Math.max(1, cfg().getLong("interval-minutes", 20)) * 60_000L;
        if (now - lastSpawn >= gap) {
            lastSpawn = now;
            long sailing = ships.values().stream().filter(s -> s.state.equals("active")).count();
            if (sailing < cfg().getInt("max-active", 4) && Bukkit.getOnlinePlayers().size() >= cfg().getInt("min-players-online", 1)) {
                spawnRandom(null, null);
            }
        }
        for (Ship s : new ArrayList<>(ships.values())) {
            World w = Bukkit.getWorld(s.world);
            if (w == null || s.state.equals("sinking")) {
                continue;
            }
            List<Player> near = w.isChunkLoaded(s.x >> 4, s.z >> 4) ? near(s, w, 48) : List.of();
            if (s.state.equals("cleared")) {
                long left = s.sinkAt - now;
                if (left <= 0) {
                    sink(s);
                } else {
                    showBar(s, near, left);
                }
                continue;
            }
            if (near.isEmpty()) {
                despawnCrew(s);
                long expire = Math.max(1, cfg().getLong("expire-hours", 6)) * 3600_000L;
                if (!s.boarded && now - s.created > expire) {
                    wipe(s, w);                           // nobody came: it sailed on
                    ships.remove(s.id);
                    save();
                }
                continue;
            }
            ConfigurationSection k = kind(s.kind);
            if (k == null) {
                continue;
            }
            List<Player> fighters = new ArrayList<>(near);
            fighters.removeIf(p -> p.getGameMode() == GameMode.CREATIVE);
            if (!s.boarded) {
                s.boarded = true;
                for (Player p : near) {
                    p.sendMessage(m.msg("ship-boarded", "ship", Text.mm(k.getString("name", s.kind))));
                    p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1.2f);
                }
            }
            s.alive.removeIf(u -> {
                Entity e = Bukkit.getEntity(u);
                return e == null || !e.isValid();
            });
            Entity cap = s.captain == null ? null : Bukkit.getEntity(s.captain);
            if (s.captainAlive && (cap == null || !cap.isValid())) {
                LivingEntity c = spawn(s, k.getString("captain", "captain"), s.d.captain, fighters, true);
                s.captain = c == null ? null : c.getUniqueId();
            }
            int want = Math.min(s.remaining, Math.max(1, cfg().getInt("max-on-deck", 6)));
            for (int i = s.alive.size(); i < want; i++) {
                int[] spot = s.d.deck.get(ThreadLocalRandom.current().nextInt(s.d.deck.size()));
                LivingEntity le = spawn(s, pickTemplate(k), spot, fighters, false);
                if (le != null) {
                    s.alive.add(le.getUniqueId());
                }
            }
        }
    }

    // ------------------------------------------------------------------ cleared: loot and the countdown

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        String id = dead.getPersistentDataContainer().get(shipKey, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        event.getDrops().clear();
        Ship s = ships.get(id);
        if (s == null || !s.state.equals("active")) {
            return;
        }
        ConfigurationSection k = kind(s.kind);
        boolean captain = dead.getPersistentDataContainer().has(captainKey, PersistentDataType.BYTE);
        if (captain) {
            s.captainAlive = false;
            s.captain = null;
        } else {
            s.alive.remove(dead.getUniqueId());
            s.remaining = Math.max(0, s.remaining - 1);
        }
        Player killer = dead.getKiller();
        EconomyService eco = m.service(EconomyService.class);
        double reward = k == null ? 0 : k.getDouble("kill-reward", 40) * (captain ? 5 : 1);
        if (killer != null && eco != null && reward > 0) {
            eco.deposit(killer.getUniqueId(), reward);
        }
        int left = s.remaining + (s.captainAlive ? 1 : 0);
        World w = dead.getWorld();
        for (Player p : near(s, w, 48)) {
            p.sendActionBar(m.rawMsg("ship-crew-left", "left", left));
        }
        if (left == 0) {
            cleared(s, w);
        }
        save();
    }

    private void cleared(Ship s, World w) {
        ConfigurationSection k = kind(s.kind);
        long minutes = Math.max(1, cfg().getLong("sink-minutes", 10));
        s.state = "cleared";
        s.sinkAt = System.currentTimeMillis() + minutes * 60_000L;
        despawnCrew(s);
        double money = fillChests(s, w, k == null ? "rare" : k.getString("loot-tier", "rare"));
        List<Player> heroes = near(s, w, 48);
        heroes.removeIf(p -> p.getGameMode() == GameMode.CREATIVE && heroes.size() > 1);
        EconomyService eco = m.service(EconomyService.class);
        LevelService levels = m.service(LevelService.class);
        double reward = (k == null ? 0 : k.getDouble("clear-reward", 1200)) + (heroes.isEmpty() ? 0 : money / heroes.size());
        long xp = k == null ? 0 : k.getLong("clear-xp", 300);
        Object name = Text.mm(k == null ? s.kind : k.getString("name", s.kind));
        for (Player p : heroes) {
            if (eco != null && reward > 0) {
                eco.deposit(p.getUniqueId(), reward);
            }
            if (levels != null && xp > 0) {
                levels.addXp(p.getUniqueId(), xp, "Ship cleared");
            }
            p.sendMessage(m.msg("ship-cleared-you", "ship", name, "amount", eco == null ? Text.number(reward) : eco.format(reward),
                    "minutes", minutes));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        if (cfg().getBoolean("announce", true) && !heroes.isEmpty()) {
            Bukkit.broadcast(m.msg("ship-cleared", "ship", name, "player", heroes.get(0).getName()));
        }
    }

    /** Fills the ship's chests from a treasure tier. @return the Doubloons that were in the loot */
    private double fillChests(Ship s, World w, String tierId) {
        TreasureModule treasure = m.plugin().module("treasure") instanceof TreasureModule t && t.isEnabled() ? t : null;
        LootTier tier = treasure == null ? null : treasure.tier(tierId);
        if (tier == null) {
            return 0;
        }
        TreasureService maps = m.service(TreasureService.class);
        double money = 0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int[] local : s.d.chests) {
            int[] p = world(s, local[0], local[1], local[2]);
            if (!(w.getBlockAt(p[0], p[1], p[2]).getState() instanceof Chest chest)) {
                continue;
            }
            LootTier.Roll roll = tier.roll(r, () -> m.service(ItemService.class));
            money += roll.doubloons();
            List<ItemStack> items = new ArrayList<>(roll.items());
            for (String mapTier : roll.maps()) {
                Location buried = maps == null ? null : maps.spawnRandomTreasure(mapTier);
                ItemStack map = buried == null ? null : maps.createTreasureMap(buried, mapTier);
                if (map != null) {
                    items.add(map);
                }
            }
            org.bukkit.inventory.Inventory inv = chest.getBlockInventory();
            for (ItemStack item : items) {
                int slot = r.nextInt(inv.getSize());
                for (int i = 0; i < inv.getSize() && inv.getItem(slot) != null; i++) {
                    slot = (slot + 1) % inv.getSize();
                }
                if (inv.getItem(slot) == null) {
                    inv.setItem(slot, item);
                }
            }
        }
        return money;
    }

    private void showBar(Ship s, List<Player> near, long leftMs) {
        long total = Math.max(1, cfg().getLong("sink-minutes", 10)) * 60_000L;
        long sec = leftMs / 1000;
        net.kyori.adventure.text.Component title = m.rawMsg("ship-bar", "time", String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60));
        float progress = Math.max(0f, Math.min(1f, leftMs / (float) total));
        if (s.bar == null) {
            s.bar = BossBar.bossBar(title, progress, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        }
        s.bar.name(title);
        s.bar.progress(progress);
        Set<UUID> now = new HashSet<>();
        for (Player p : near) {
            now.add(p.getUniqueId());
            if (s.viewers.add(p.getUniqueId())) {
                p.showBossBar(s.bar);
            }
        }
        s.viewers.removeIf(u -> {
            if (now.contains(u)) {
                return false;
            }
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                p.hideBossBar(s.bar);
            }
            return true;
        });
        if (sec == 60 || sec == 30 || sec == 10) {
            for (Player p : near) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            }
        }
    }

    private void hideBar(Ship s) {
        if (s.bar != null) {
            for (UUID u : s.viewers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) {
                    p.hideBossBar(s.bar);
                }
            }
        }
        s.viewers.clear();
        s.bar = null;
    }

    // ------------------------------------------------------------------ sinking

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** The ship goes down: one block every half second, with spray and bubbles, until the sea closes over it. */
    private void sink(Ship s) {
        World w = Bukkit.getWorld(s.world);
        if (w == null || s.state.equals("sinking")) {
            return;
        }
        s.state = "sinking";
        hideBar(s);
        despawnCrew(s);
        for (Player p : near(s, w, 64)) {
            p.sendMessage(m.msg("ship-sinking"));
            p.playSound(s.center(w), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.2f, 0.6f);
        }
        // what is left of the ship, by position; the hold floods at once
        Map<Long, int[]> where = new HashMap<>();
        Map<Long, BlockData> what = new HashMap<>();
        for (int i = 0; i < s.cells.size(); i++) {
            int[] p = s.cells.get(i);
            Block b = w.getBlockAt(p[0], p[1], p[2]);
            if (b.getState() instanceof Chest chest) {
                chest.getBlockInventory().clear();        // what was not taken goes down with the ship
            }
            if (s.data.get(i).getMaterial().isAir()) {
                b.setBlockData(sea(w, p[1]), false);
            } else {
                where.put(key(p[0], p[1], p[2]), p);
                what.put(key(p[0], p[1], p[2]), s.data.get(i));
            }
        }
        int steps = Math.max(4, s.maxY - w.getSeaLevel() + 4);
        int[] step = {0};
        s.sinking = m.runTimer(() -> {
            step[0]++;
            Map<Long, int[]> nextWhere = new HashMap<>();
            Map<Long, BlockData> nextWhat = new HashMap<>();
            for (Map.Entry<Long, int[]> e : where.entrySet()) {
                int[] p = e.getValue();
                int ny = p[1] - 1;
                long nk = key(p[0], ny, p[2]);
                if (ny <= w.getMinHeight()) {
                    continue;
                }
                if (!where.containsKey(nk) && w.getBlockAt(p[0], ny, p[2]).getType().isSolid()) {
                    continue;                             // it hit the sea bed: that piece is gone
                }
                nextWhere.put(nk, new int[]{p[0], ny, p[2]});
                nextWhat.put(nk, what.get(e.getKey()));
            }
            for (Map.Entry<Long, int[]> e : where.entrySet()) {
                if (!nextWhere.containsKey(e.getKey())) {
                    int[] p = e.getValue();
                    w.getBlockAt(p[0], p[1], p[2]).setBlockData(sea(w, p[1]), false);
                }
            }
            for (Map.Entry<Long, int[]> e : nextWhere.entrySet()) {
                int[] p = e.getValue();
                w.getBlockAt(p[0], p[1], p[2]).setBlockData(nextWhat.get(e.getKey()), false);
            }
            where.clear();
            where.putAll(nextWhere);
            what.clear();
            what.putAll(nextWhat);
            Location c = s.center(w);
            double surface = w.getSeaLevel();
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < 14; i++) {
                Location at = new Location(w, s.minX + r.nextDouble() * (s.maxX - s.minX + 1), surface + 0.2,
                        s.minZ + r.nextDouble() * (s.maxZ - s.minZ + 1));
                w.spawnParticle(Particle.SPLASH, at, 12, 0.6, 0.2, 0.6, 0.1);
                w.spawnParticle(Particle.BUBBLE_POP, at, 6, 0.5, 0.2, 0.5, 0.02);
                w.spawnParticle(Particle.CLOUD, at, 2, 0.4, 0.3, 0.4, 0.02);
            }
            w.playSound(c, step[0] % 3 == 0 ? Sound.BLOCK_WOOD_BREAK : Sound.ENTITY_GENERIC_SPLASH, 2f, 0.6f);
            if (step[0] >= steps || where.isEmpty()) {
                for (int[] p : where.values()) {
                    w.getBlockAt(p[0], p[1], p[2]).setBlockData(sea(w, p[1]), false);
                }
                w.playSound(c, Sound.AMBIENT_UNDERWATER_EXIT, 2f, 0.7f);
                w.spawnParticle(Particle.BUBBLE_COLUMN_UP, c.clone().add(0, -4, 0), 200, 4, 2, 6, 0.1);
                s.sinking.cancel();
                s.sinking = null;
                ships.remove(s.id);
                save();
            }
        }, 40L, 10L);
        save();
    }

    // ------------------------------------------------------------------ the ship cannot be taken apart

    private Ship at(Block b) {
        for (Ship s : ships.values()) {
            if (s.contains(b, 0)) {
                return s;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (at(event.getBlock()) != null && !event.getPlayer().hasPermission("aquatic.ships.admin")) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(m.rawMsg("ship-protected"));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (at(event.getBlock()) != null && !event.getPlayer().hasPermission("aquatic.ships.admin")) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(m.rawMsg("ship-protected"));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> at(b) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> at(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /** Only the ship's own crew is aboard: no monsters spawn on its dark decks by themselves. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Enemy)) {
            return;
        }
        switch (event.getSpawnReason()) {
            case CUSTOM, COMMAND, SPAWNER_EGG, DISPENSE_EGG, DEFAULT -> {
                return;
            }
            default -> {
            }
        }
        Block b = event.getLocation().getBlock();
        for (Ship ship : ships.values()) {
            if (ship.contains(b, 3)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onSun(EntityCombustEvent event) {
        if (event.getClass() == EntityCombustEvent.class && event.getEntity().getPersistentDataContainer().has(shipKey, PersistentDataType.STRING)) {
            event.setCancelled(true);                     // the cursed crew does not burn in daylight
        }
    }

    // ------------------------------------------------------------------ /ship (admins)

    AquaticCommand command() {
        AquaticCommand cmd = new AquaticCommand("ship", "Ships on the open sea", "ships");
        cmd.permission("aquatic.ships.admin");
        cmd.sub("list", (sender, a) -> {
            sender.sendMessage(Text.mm("<aqua>Ships <gray>(<n>)</gray>:", "n", ships.size()));
            for (Ship s : ships.values()) {
                sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <click:run_command:'/ship tp <id>'><white><id></white></click> "
                                + "<gray><x>, <z> <dark_gray>|</dark_gray> <state><gray>, crew left <white><left>",
                        "id", s.id, "x", s.x, "z", s.z, "state", s.state, "left", s.remaining + (s.captainAlive ? 1 : 0)));
            }
        }).description("All ships (click one to go there)");
        cmd.sub("spawn", (sender, a) -> {
            Player p = AquaticCommand.player(sender);
            String kindId = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : randomKind();
            if (kindId == null || kind(kindId) == null) {
                throw new CommandFail("<red>Unknown kind. Kinds: <white><kinds>", "kinds", String.join(", ", kinds()));
            }
            Ship s = create(kindId, p.getLocation(), Math.floorMod(Math.round(p.getLocation().getYaw() / 90f) + 2, 4));
            if (s == null) {
                throw new CommandFail("<red>Could not build the ship here.");
            }
            p.teleport(feet(p.getWorld(), world(s, s.d.arrival[0], s.d.arrival[1], s.d.arrival[2])));
            sender.sendMessage(Text.mm("<green>Ship <white><id></white> built around you.", "id", s.id));
        }).usage("[kind]").description("Build a ship where you stand (stand over open water)").playerOnly()
                .completer((sender, a) -> a.length == 1 ? AquaticCommand.filter(kinds(), a[0]) : List.of());
        cmd.sub("random", (sender, a) -> {
            String kindId = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : null;
            if (kindId != null && kind(kindId) == null) {
                throw new CommandFail("<red>Unknown kind. Kinds: <white><kinds>", "kinds", String.join(", ", kinds()));
            }
            sender.sendMessage(Text.mm("<gray>Looking for a free stretch of sea..."));
            spawnRandom(kindId, sender);
        }).usage("[kind]").description("Send a ship out to a random spot on the sea")
                .completer((sender, a) -> a.length == 1 ? AquaticCommand.filter(kinds(), a[0]) : List.of());
        cmd.sub("tp", (sender, a) -> {
            Ship s = ship(a);
            Player p = AquaticCommand.player(sender);
            World w = Bukkit.getWorld(s.world);
            if (w != null) {
                p.teleport(feet(w, world(s, s.d.arrival[0], s.d.arrival[1], s.d.arrival[2])));
            }
        }).usage("<id>").description("Go to a ship").playerOnly().completer((sender, a) -> ids(a));
        cmd.sub("sink", (sender, a) -> {
            Ship s = ship(a);
            sink(s);
            sender.sendMessage(Text.mm("<green>Ship <white><id></white> is going down.", "id", s.id));
        }).usage("<id>").description("Sink a ship now (with the animation)").completer((sender, a) -> ids(a));
        cmd.sub("remove", (sender, a) -> {
            Ship s = ship(a);
            World w = Bukkit.getWorld(s.world);
            despawnCrew(s);
            hideBar(s);
            if (s.sinking != null) {
                s.sinking.cancel();
            }
            if (w != null) {
                wipe(s, w);
            }
            ships.remove(s.id);
            save();
            sender.sendMessage(Text.mm("<green>Ship <white><id></white> removed.", "id", s.id));
        }).usage("<id>").description("Remove a ship at once").completer((sender, a) -> ids(a));
        return cmd;
    }

    private List<String> kinds() {
        ConfigurationSection k = cfg().getConfigurationSection("kinds");
        return k == null ? List.of() : new ArrayList<>(k.getKeys(false));
    }

    private List<String> ids(String[] a) {
        return a.length == 1 ? AquaticCommand.filter(new ArrayList<>(ships.keySet()), a[0]) : List.of();
    }

    private Ship ship(String[] a) {
        AquaticCommand.need(a, 1, "/ship <tp|sink|remove> <id>");
        Ship s = ships.get(a[0]);
        if (s == null) {
            throw new CommandFail("<red>No ship <white><id></white>. See <white>/ship list</white>.", "id", a[0]);
        }
        return s;
    }
}
