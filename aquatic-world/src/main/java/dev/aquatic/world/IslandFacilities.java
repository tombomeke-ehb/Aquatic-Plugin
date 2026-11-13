package dev.aquatic.world;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewRank;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.IslandStatus;
import dev.aquatic.command.CommandFail;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Facilities: buildings a crew buys from the crew bank and places on its island ("facilities" in islands.yml,
 * the buildings themselves are in {@link Blueprint}). Before building, a player gets a preview: the building
 * appears in front of them as fake blocks only they can see, with its footprint outlined and anything in the
 * way shown as red glass. They can rotate it, move it to where they stand, build it or cancel.
 */
final class IslandFacilities implements Listener {

    static final List<String> TYPES = List.of("watchtower", "forge", "farm", "dock", "storehouse", "lighthouse");

    /** A built facility: its anchor (ground level), rotation and footprint. */
    record Placed(String type, int ax, int ay, int az, int rot, int minX, int minZ, int maxX, int maxZ) {
        String serialize() {
            return String.join(";", type, "" + ax, "" + ay, "" + az, "" + rot, "" + minX, "" + minZ, "" + maxX, "" + maxZ);
        }

        static Placed parse(String raw) {
            String[] p = raw.split(";");
            if (p.length < 9) {
                return null;
            }
            try {
                return new Placed(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                        Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7]), Integer.parseInt(p[8]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** One block of a planned building in the world. */
    record Spot(Location loc, BlockData data, int localY, boolean support) {
    }

    /** Where a building would go and what is wrong with that spot. */
    record Plan(String type, World world, int ax, int ay, int az, int rot, List<Spot> spots, Set<Location> conflicts,
                List<Component> problems, int minX, int minZ, int maxX, int maxZ, int maxY) {
        boolean ok() {
            return problems.isEmpty();
        }
    }

    private static final class Preview {
        String type;
        String islandId;
        Location stand;
        int rot;
        Plan plan;
        final List<Location> shown = new ArrayList<>();
        long expires;
    }

    private static final class Menu implements InventoryHolder {
        final String islandId;
        Inventory inventory;

        Menu(String islandId) {
            this.islandId = islandId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int[] MENU_SLOTS = {10, 12, 14, 16, 28, 30, 32, 34};
    private static final int SLOT_BACK = 49;

    private final IslandsModule m;
    private final Map<UUID, Preview> previews = new HashMap<>();
    private final BlockData redGlass = Bukkit.createBlockData(Material.RED_STAINED_GLASS);

    IslandFacilities(IslandsModule module) {
        this.m = module;
    }

    private ConfigurationSection def(String type) {
        return m.config().getConfigurationSection("facilities.types." + type);
    }

    boolean enabled() {
        return m.config().getBoolean("facilities.enabled", true);
    }

    Component name(String type) {
        ConfigurationSection d = def(type);
        return Text.mm(d == null ? type : d.getString("display-name", type));
    }

    double cost(String type) {
        ConfigurationSection d = def(type);
        return d == null ? 0 : Math.max(0, d.getDouble("cost", 0));
    }

    /** Extra income of an island from its facilities (0.05 = +5%). */
    double incomeBonus(IslandImpl is) {
        double sum = 0;
        for (Placed f : is.facilities) {
            ConfigurationSection d = def(f.type);
            sum += d == null ? 0 : d.getDouble("income-bonus", 0);
        }
        return sum;
    }

    private String money(double amount) {
        EconomyService eco = m.service(EconomyService.class);
        return eco == null ? Text.number(amount) : eco.format(amount);
    }

    private String type(String raw) {
        String t = raw.toLowerCase(Locale.ROOT);
        if (!TYPES.contains(t) || def(t) == null || !def(t).getBoolean("enabled", true)) {
            throw new CommandFail(m.rawMsg("facility-unknown", "type", raw, "types", String.join(", ", TYPES)));
        }
        return t;
    }

    private Crew ownCrew(Player p, IslandImpl is) {
        CrewService crews = m.service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewOf(p.getUniqueId());
        if (crew == null || is.status != IslandStatus.OCCUPIED || !crew.id().equals(is.ownerCrewId)) {
            throw new CommandFail(m.rawMsg("manage-not-yours", "island", is.name));
        }
        return crew;
    }

    private Crew officer(Player p, IslandImpl is) {
        Crew crew = ownCrew(p, is);
        CrewRank rank = crew.rankOf(p.getUniqueId());
        if (rank == null || !rank.isOfficer()) {
            throw new CommandFail(m.rawMsg("upgrade-officer"));
        }
        return crew;
    }

    // ------------------------------------------------------------------ planning

    private static int rotationOf(BlockFace facing) {
        return switch (facing) {
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> 0;
        };
    }

    private static StructureRotation rotation(int rot) {
        return switch (rot & 3) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }

    /** Local (x, z) turned clockwise (seen from above) rot times. */
    private static int[] turn(int x, int z, int rot) {
        for (int i = 0; i < (rot & 3); i++) {
            int t = x;
            x = -z;
            z = t;
        }
        return new int[]{x, z};
    }

    private static final Set<Material> NATURAL = Set.of(Material.GRAVEL, Material.CLAY, Material.SNOW, Material.SNOW_BLOCK,
            Material.ICE, Material.PACKED_ICE, Material.SUGAR_CANE, Material.CACTUS, Material.KELP, Material.KELP_PLANT,
            Material.SEAGRASS, Material.TALL_SEAGRASS, Material.VINE, Material.BAMBOO, Material.PUMPKIN, Material.MELON,
            Material.MOSS_BLOCK, Material.MOSS_CARPET, Material.SWEET_BERRY_BUSH, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
            Material.LILY_PAD, Material.SANDSTONE, Material.RED_SANDSTONE, Material.TERRACOTTA, Material.MAGMA_BLOCK,
            Material.BASALT, Material.OBSIDIAN, Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN, Material.COCOA);

    /** Things the island grew by itself (fine to build over), as opposed to blocks players placed. */
    static boolean natural(Material t) {
        return t.isAir() || NATURAL.contains(t) || Tag.REPLACEABLE.isTagged(t) || Tag.LEAVES.isTagged(t) || Tag.LOGS.isTagged(t)
                || Tag.FLOWERS.isTagged(t) || Tag.SAPLINGS.isTagged(t) || Tag.DIRT.isTagged(t) || Tag.SAND.isTagged(t)
                || Tag.BASE_STONE_OVERWORLD.isTagged(t) || t.name().endsWith("_ORE") || t.name().contains("CORAL");
    }

    private static boolean fillable(Block b) {
        Material t = b.getType();
        return t.isAir() || t == Material.WATER || Tag.REPLACEABLE.isTagged(t) || Tag.LEAVES.isTagged(t)
                || t == Material.KELP || t == Material.KELP_PLANT || t == Material.SEAGRASS || t == Material.TALL_SEAGRASS;
    }

    /** The anchor is 2 blocks in front of where the player stands, at ground level there. */
    Plan plan(IslandImpl is, String type, Location stand, int rot) {
        Blueprint bp = Blueprint.of(type);
        World w = stand.getWorld();
        int[] f = turn(0, 1, rot); // the local front direction (+z) in the world
        int ax = stand.getBlockX() + f[0] * 2, az = stand.getBlockZ() + f[1] * 2;
        int ay = w.getHighestBlockYAt(ax, az, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        StructureRotation sr = rotation(rot);
        List<Spot> spots = new ArrayList<>();
        Set<Location> conflicts = new HashSet<>();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Blueprint.Cell c : bp.cells()) {
            int[] r = turn(c.x(), c.z(), rot);
            BlockData d = c.data().clone();
            d.rotate(sr);
            Location l = new Location(w, ax + r[0], ay + c.y(), az + r[1]);
            spots.add(new Spot(l, d, c.y(), false));
            minX = Math.min(minX, l.getBlockX());
            maxX = Math.max(maxX, l.getBlockX());
            minZ = Math.min(minZ, l.getBlockZ());
            maxZ = Math.max(maxZ, l.getBlockZ());
        }
        Set<Long> supported = new HashSet<>();
        for (Blueprint.Support s : bp.supports) {
            int[] r = turn(s.x(), s.z(), rot);
            int x = ax + r[0], z = az + r[1];
            if (!supported.add(((long) x << 32) | (z & 0xffffffffL))) {
                continue;
            }
            BlockData d = Bukkit.createBlockData(s.material());
            for (int y = ay - 1; y > ay - 14 && y > w.getMinHeight(); y--) {
                Block b = w.getBlockAt(x, y, z);
                if (!fillable(b)) {
                    break;
                }
                spots.add(new Spot(b.getLocation(), d, y - ay, true));
            }
        }
        // what is wrong with this spot
        List<Component> problems = new ArrayList<>();
        int outside = 0, water = 0, columns = 0;
        Set<Long> seen = new HashSet<>();
        for (Spot s : spots) {
            Material now = s.loc.getBlock().getType();
            if (!natural(now) && now != s.data.getMaterial()) {
                conflicts.add(s.loc);
            }
            long col = ((long) s.loc.getBlockX() << 32) | (s.loc.getBlockZ() & 0xffffffffL);
            if (!s.support && seen.add(col)) {
                columns++;
                if (m.at(s.loc) != is) {
                    outside++;
                }
                for (int dy = 0; dy >= -3; dy--) {
                    if (w.getBlockAt(s.loc.getBlockX(), ay + dy, s.loc.getBlockZ()).getType() == Material.WATER) {
                        water++;
                        break;
                    }
                }
            }
        }
        if (outside > 0) {
            problems.add(m.rawMsg("facility-problem-outside", "count", outside));
        }
        if (!conflicts.isEmpty()) {
            problems.add(m.rawMsg("facility-problem-blocks", "count", conflicts.size()));
        }
        if (bp.needsWater && water < columns / 3) {
            problems.add(m.rawMsg("facility-problem-water"));
        }
        if (ay + bp.maxY >= w.getMaxHeight() - 1) {
            problems.add(m.rawMsg("facility-problem-height"));
        }
        for (Placed p : is.facilities) {
            if (minX <= p.maxX + 1 && maxX >= p.minX - 1 && minZ <= p.maxZ + 1 && maxZ >= p.minZ - 1) {
                problems.add(m.rawMsg("facility-problem-overlap", "name", name(p.type)));
                break;
            }
        }
        if (is.hasFlag && is.flagX >= minX - 2 && is.flagX <= maxX + 2 && is.flagZ >= minZ - 2 && is.flagZ <= maxZ + 2) {
            problems.add(m.rawMsg("facility-problem-flag"));
        }
        int limit = def(type).getInt("limit", 1);
        long same = is.facilities.stream().filter(p -> p.type.equals(type)).count();
        if (same >= limit) {
            problems.add(m.rawMsg("facility-problem-limit", "max", limit, "name", name(type)));
        }
        int total = m.config().getInt("facilities.max-per-island", 8);
        if (is.facilities.size() >= total) {
            problems.add(m.rawMsg("facility-problem-total", "max", total));
        }
        return new Plan(type, w, ax, ay, az, rot, spots, conflicts, problems, minX, minZ, maxX, maxZ, bp.maxY);
    }

    // ------------------------------------------------------------------ preview

    void preview(Player p, IslandImpl is, String rawType) {
        if (!enabled()) {
            throw new CommandFail(m.rawMsg("facility-disabled"));
        }
        String type = type(rawType);
        ownCrew(p, is);
        cancel(p, false);
        Preview pv = new Preview();
        pv.type = type;
        pv.islandId = is.id;
        pv.stand = p.getLocation().getBlock().getLocation();
        pv.rot = rotationOf(p.getFacing());
        refresh(p, pv, is);
        previews.put(p.getUniqueId(), pv);
    }

    private void refresh(Player p, Preview pv, IslandImpl is) {
        hide(p, pv);
        pv.plan = plan(is, pv.type, pv.stand, pv.rot);
        pv.expires = System.currentTimeMillis() + Math.max(15, m.config().getLong("facilities.preview-seconds", 120)) * 1000L;
        for (Spot s : pv.plan.spots) {
            BlockData now = s.loc.getBlock().getBlockData();
            BlockData show = pv.plan.conflicts.contains(s.loc) ? redGlass : s.data;
            if (now.matches(show)) {
                continue;
            }
            p.sendBlockChange(s.loc, show);
            pv.shown.add(s.loc);
        }
        report(p, pv);
    }

    private void report(Player p, Preview pv) {
        Plan plan = pv.plan;
        p.sendMessage(Component.empty());
        p.sendMessage(m.msg("facility-preview", "name", name(pv.type)));
        if (plan.ok()) {
            p.sendMessage(m.rawMsg("facility-preview-ok", "cost", money(cost(pv.type))));
        } else {
            for (Component problem : plan.problems) {
                p.sendMessage(m.rawMsg("facility-preview-problem", "problem", problem));
            }
        }
        String buttons = m.config().getString("messages.facility-buttons", "");
        String build = plan.ok() ? m.config().getString("messages.facility-button-build", "") : "";
        p.sendMessage(Text.mm(build + buttons, "cost", money(cost(pv.type))));
    }

    private void hide(Player p, Preview pv) {
        for (Location l : pv.shown) {
            if (p.getWorld().equals(l.getWorld())) {
                p.sendBlockChange(l, l.getBlock().getBlockData());
            }
        }
        pv.shown.clear();
    }

    boolean cancel(Player p, boolean tell) {
        Preview pv = previews.remove(p.getUniqueId());
        if (pv == null) {
            return false;
        }
        hide(p, pv);
        if (tell) {
            p.sendMessage(m.msg("facility-cancelled"));
        }
        return true;
    }

    private Preview current(Player p) {
        Preview pv = previews.get(p.getUniqueId());
        if (pv == null) {
            throw new CommandFail(m.rawMsg("facility-no-preview"));
        }
        return pv;
    }

    private IslandImpl islandOf(Player p, Preview pv) {
        IslandImpl is = m.islandImpl(pv.islandId);
        if (is == null) {
            cancel(p, false);
            throw new CommandFail(m.rawMsg("facility-no-preview"));
        }
        return is;
    }

    void rotate(Player p) {
        Preview pv = current(p);
        pv.rot = (pv.rot + 1) & 3;
        refresh(p, pv, islandOf(p, pv));
    }

    void move(Player p) {
        Preview pv = current(p);
        IslandImpl is = islandOf(p, pv);
        if (m.at(p.getLocation()) != is) {
            throw new CommandFail(m.rawMsg("facility-move-off-island", "island", is.name));
        }
        pv.stand = p.getLocation().getBlock().getLocation();
        pv.rot = rotationOf(p.getFacing());
        refresh(p, pv, is);
    }

    void build(Player p) {
        Preview pv = current(p);
        IslandImpl is = islandOf(p, pv);
        Crew crew = officer(p, is);
        Plan plan = plan(is, pv.type, pv.stand, pv.rot); // the world may have changed since the preview
        if (!plan.ok()) {
            pv.plan = plan;
            report(p, pv);
            throw new CommandFail(m.rawMsg("facility-blocked"));
        }
        double cost = cost(pv.type);
        if (cost > 0 && !crew.withdrawBank(cost)) {
            throw new CommandFail(m.rawMsg("upgrade-no-money", "cost", money(cost), "bank", money(crew.bankBalance())));
        }
        hide(p, pv);
        previews.remove(p.getUniqueId());
        Placed placed = new Placed(plan.type, plan.ax, plan.ay, plan.az, plan.rot, plan.minX, plan.minZ, plan.maxX, plan.maxZ);
        is.facilities.add(placed);
        m.markDirty();
        Job job = new Job(plan, placed, is, crew.id());
        jobs.add(job);
        crew.broadcast(m.msg("facility-started", "player", p.getName(), "name", name(plan.type), "island", is.name,
                "cost", money(cost), "seconds", job.seconds));
    }

    // ------------------------------------------------------------------ construction (the building goes up bit by bit)

    private final List<Job> jobs = new ArrayList<>();

    /** A block flying into place: shown as a display entity, set for real when it lands. */
    private record Flying(org.bukkit.entity.BlockDisplay display, Spot spot, int landTick) {
    }

    private final class Job {
        final Plan plan;
        final Placed placed;
        final String islandId, crewId;
        final List<Spot> clearing = new ArrayList<>(), building = new ArrayList<>();
        final List<Flying> flying = new ArrayList<>();
        final net.kyori.adventure.bossbar.BossBar bar;
        final Set<UUID> viewers = new HashSet<>();
        final int seconds, clearPerTick, buildPerTick;
        final boolean animate;
        final Location middle;
        int tick, cleared, built;

        Job(Plan plan, Placed placed, IslandImpl is, String crewId) {
            this.plan = plan;
            this.placed = placed;
            this.islandId = is.id;
            this.crewId = crewId;
            for (Spot s : plan.spots) {
                if (s.data.getMaterial().isAir()) {
                    if (!s.loc.getBlock().getType().isAir()) {
                        clearing.add(s);
                    }
                } else {
                    building.add(s);
                }
            }
            double cx = (plan.minX + plan.maxX) / 2.0 + 0.5, cz = (plan.minZ + plan.maxZ) / 2.0 + 0.5;
            // bottom to top (foundations first), each layer from the middle outwards
            building.sort((a, b) -> {
                int y = Integer.compare(a.loc.getBlockY(), b.loc.getBlockY());
                if (y != 0) {
                    return y;
                }
                double da = Math.hypot(a.loc.getX() + 0.5 - cx, a.loc.getZ() + 0.5 - cz);
                double db = Math.hypot(b.loc.getX() + 0.5 - cx, b.loc.getZ() + 0.5 - cz);
                return Double.compare(da, db);
            });
            ConfigurationSection d = def(plan.type);
            int secs = d != null && d.contains("build-seconds") ? d.getInt("build-seconds")
                    : m.config().getInt("facilities.build-seconds", 30);
            this.seconds = Math.max(3, Math.min(600, secs));
            this.clearPerTick = Math.max(1, (int) Math.ceil(clearing.size() / 40.0));
            this.buildPerTick = Math.max(1, (int) Math.ceil(building.size() / (seconds * 20.0 - 40)));
            this.animate = m.config().getBoolean("facilities.animate", true);
            this.middle = new Location(plan.world, cx, plan.ay + 1, cz);
            this.bar = net.kyori.adventure.bossbar.BossBar.bossBar(m.rawMsg("facility-bar", "name", name(plan.type), "percent", 0),
                    0f, net.kyori.adventure.bossbar.BossBar.Color.YELLOW, net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_10);
        }

        boolean done() {
            return cleared >= clearing.size() && built >= building.size() && flying.isEmpty();
        }

        float progress() {
            int total = clearing.size() + building.size();
            return total == 0 ? 1f : Math.min(1f, (cleared + built - flying.size()) / (float) total);
        }

        /** One server tick of work. */
        void step() {
            tick++;
            World w = plan.world;
            // 1. the site is cleared (trees and grass go in a puff)
            for (int i = 0; i < clearPerTick && cleared < clearing.size(); i++) {
                Spot s = clearing.get(cleared++);
                Block b = s.loc.getBlock();
                if (!b.getType().isAir()) {
                    if (i % 3 == 0) {
                        w.spawnParticle(Particle.BLOCK, s.loc.clone().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0, b.getBlockData());
                    }
                    b.setType(Material.AIR, false);
                }
            }
            // 2. then the building goes up, block by block
            if (cleared >= clearing.size()) {
                for (int i = 0; i < buildPerTick && built < building.size(); i++) {
                    Spot s = building.get(built++);
                    if (animate && !s.support) {
                        flying.add(new Flying(launch(s), s, tick + 5));
                    } else {
                        land(s, i == 0);
                    }
                }
            }
            for (java.util.Iterator<Flying> it = flying.iterator(); it.hasNext(); ) {
                Flying f = it.next();
                if (tick >= f.landTick) {
                    if (f.display != null) {
                        f.display.remove();
                    }
                    land(f.spot, (tick & 1) == 0);
                    it.remove();
                }
            }
            if (tick % 10 == 0) {
                w.spawnParticle(Particle.CLOUD, middle.clone().add(0, built / (double) Math.max(1, building.size()) * (plan.maxY), 0),
                        4, (plan.maxX - plan.minX) / 3.0, 0.4, (plan.maxZ - plan.minZ) / 3.0, 0.01);
                w.playSound(middle, tick % 20 == 0 ? Sound.BLOCK_ANVIL_USE : Sound.ENTITY_VILLAGER_WORK_MASON, 0.35f, 1.1f);
            }
            if (tick % 5 == 0) {
                updateBar();
            }
        }

        private org.bukkit.entity.BlockDisplay launch(Spot s) {
            return s.loc.getWorld().spawn(s.loc.clone(), org.bukkit.entity.BlockDisplay.class, d -> {
                d.setBlock(s.data);
                d.setPersistent(false);
                d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0.3f, 1.6f, 0.3f),
                        new org.joml.Quaternionf(), new org.joml.Vector3f(0.4f, 0.4f, 0.4f), new org.joml.Quaternionf()));
                m.runLater(() -> {
                    if (d.isValid()) {
                        d.setInterpolationDelay(0);
                        d.setInterpolationDuration(4);
                        d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0f, 0f, 0f),
                                new org.joml.Quaternionf(), new org.joml.Vector3f(1f, 1f, 1f), new org.joml.Quaternionf()));
                    }
                }, 1L);
            });
        }

        private void land(Spot s, boolean sound) {
            Block b = s.loc.getBlock();
            b.setBlockData(s.data, false);
            if (!s.support) {
                b.getWorld().spawnParticle(Particle.BLOCK, s.loc.clone().add(0.5, 0.1, 0.5), 3, 0.25, 0.05, 0.25, 0, s.data);
                if (sound) {
                    b.getWorld().playSound(s.loc, s.data.getSoundGroup().getPlaceSound(), 0.45f,
                            0.85f + (float) Math.random() * 0.3f);
                }
            }
        }

        void updateBar() {
            bar.progress(progress());
            bar.name(m.rawMsg("facility-bar", "name", name(plan.type), "percent", Math.round(progress() * 100)));
            CrewService crews = m.service(CrewService.class);
            Set<UUID> now = new HashSet<>();
            for (Player p : plan.world.getPlayers()) {
                if (p.getLocation().distanceSquared(middle) > 96 * 96) {
                    continue;
                }
                Crew c = crews == null ? null : crews.crewOf(p.getUniqueId());
                if (c != null && c.id().equals(crewId)) {
                    now.add(p.getUniqueId());
                }
            }
            for (UUID id : now) {
                if (viewers.add(id)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        p.showBossBar(bar);
                    }
                }
            }
            for (java.util.Iterator<UUID> it = viewers.iterator(); it.hasNext(); ) {
                UUID id = it.next();
                if (!now.contains(id)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        p.hideBossBar(bar);
                    }
                    it.remove();
                }
            }
        }

        /** Places everything that is left right away (server stop). */
        void finishNow() {
            for (Flying f : flying) {
                if (f.display != null) {
                    f.display.remove();
                }
                f.spot.loc.getBlock().setBlockData(f.spot.data, false);
            }
            flying.clear();
            while (cleared < clearing.size()) {
                clearing.get(cleared++).loc.getBlock().setType(Material.AIR, false);
            }
            while (built < building.size()) {
                Spot s = building.get(built++);
                s.loc.getBlock().setBlockData(s.data, false);
            }
            hideBar();
        }

        /** Stops without placing more (the facility is being demolished). */
        void abort() {
            for (Flying f : flying) {
                if (f.display != null) {
                    f.display.remove();
                }
            }
            flying.clear();
            hideBar();
        }

        void hideBar() {
            for (UUID id : viewers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.hideBossBar(bar);
                }
            }
            viewers.clear();
        }

        void celebrate() {
            World w = plan.world;
            Location top = middle.clone().add(0, plan.maxY + 1, 0);
            w.spawnParticle(Particle.HAPPY_VILLAGER, middle.clone().add(0, plan.maxY / 2.0, 0), 50,
                    (plan.maxX - plan.minX) / 2.0, plan.maxY / 2.0, (plan.maxZ - plan.minZ) / 2.0, 0);
            w.spawnParticle(Particle.FIREWORK, top, 80, 0.4, 0.4, 0.4, 0.25);
            w.spawnParticle(Particle.END_ROD, top, 30, 0.3, 0.6, 0.3, 0.08);
            w.playSound(middle, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f);
            w.playSound(middle, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1f, 1f);
        }
    }

    /** Every tick: the buildings under construction go up a bit. */
    void tickJobs() {
        for (java.util.Iterator<Job> it = jobs.iterator(); it.hasNext(); ) {
            Job job = it.next();
            job.step();
            if (job.done()) {
                it.remove();
                job.hideBar();
                job.celebrate();
                IslandImpl is = m.islandImpl(job.islandId);
                CrewService crews = m.service(CrewService.class);
                Crew crew = crews == null ? null : crews.crewById(job.crewId);
                if (crew != null) {
                    crew.broadcast(m.msg("facility-finished", "name", name(job.plan.type), "island", is == null ? "?" : is.name));
                }
            }
        }
    }

    private void stopJob(Placed placed) {
        for (java.util.Iterator<Job> it = jobs.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (job.placed.equals(placed)) {
                job.abort();
                it.remove();
            }
        }
    }

    /** Demolishes the facility the player stands in: its blocks go (container contents drop), part of the price comes back. */
    void remove(Player p, IslandImpl is) {
        Crew crew = officer(p, is);
        Placed found = null;
        for (Placed f : is.facilities) {
            if (f.contains(p.getLocation().getBlockX(), p.getLocation().getBlockZ())) {
                found = f;
                break;
            }
        }
        if (found == null) {
            throw new CommandFail(m.rawMsg("facility-not-here"));
        }
        stopJob(found);
        World w = p.getWorld();
        Blueprint bp = Blueprint.of(found.type);
        if (bp != null) {
            StructureRotation sr = rotation(found.rot);
            for (Blueprint.Cell c : bp.cells()) {
                if (c.y() < 0 || c.data().getMaterial().isAir()) {
                    continue;
                }
                int[] r = turn(c.x(), c.z(), found.rot);
                Block b = w.getBlockAt(found.ax + r[0], found.ay + c.y(), found.az + r[1]);
                BlockData d = c.data().clone();
                d.rotate(sr);
                if (b.getType() != d.getMaterial()) {
                    continue; // changed by the crew since: leave it
                }
                BlockState state = b.getState();
                if (state instanceof Container container) {
                    for (ItemStack it : container.getInventory().getContents()) {
                        if (it != null && !it.getType().isAir()) {
                            w.dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), it);
                        }
                    }
                    container.getInventory().clear();
                }
                if (c.y() == 0) {
                    // the floor goes back to grass (or open water under a dock)
                    Material below = b.getRelative(BlockFace.DOWN).getType();
                    b.setType(below == Material.WATER || below.isAir() ? Material.AIR : Material.GRASS_BLOCK, false);
                    continue;
                }
                b.setType(Material.AIR, false);
            }
        }
        is.facilities.remove(found);
        m.markDirty();
        double back = cost(found.type) * Math.max(0, Math.min(1, m.config().getDouble("facilities.refund", 0.5)));
        if (back > 0) {
            crew.depositBank(back);
        }
        crew.broadcast(m.msg("facility-removed", "player", p.getName(), "name", name(found.type), "island", is.name, "refund", money(back)));
    }

    void list(Player p, IslandImpl is) {
        int max = m.config().getInt("facilities.max-per-island", 8);
        p.sendMessage(m.msg("facility-list-header", "island", is.name, "count", is.facilities.size(), "max", max));
        if (is.facilities.isEmpty()) {
            p.sendMessage(m.rawMsg("facility-list-empty"));
        }
        for (Placed f : is.facilities) {
            p.sendMessage(m.rawMsg("facility-list-line", "name", name(f.type), "x", (f.minX + f.maxX) / 2, "z", (f.minZ + f.maxZ) / 2));
        }
    }

    /** Every half second: outline previews (only their owner sees it) and drop old ones. */
    void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Preview> e : new ArrayList<>(previews.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Preview pv = e.getValue();
            if (p == null) {
                previews.remove(e.getKey());
                continue;
            }
            if (now > pv.expires || !p.getWorld().equals(pv.plan.world)
                    || p.getLocation().distanceSquared(pv.stand) > 80 * 80) {
                cancel(p, false);
                p.sendMessage(m.msg("facility-expired", "name", name(pv.type)));
                continue;
            }
            Plan plan = pv.plan;
            Particle.DustOptions dust = new Particle.DustOptions(plan.ok() ? Color.fromRGB(0x55FF77) : Color.fromRGB(0xFF5555), 1.2f);
            double y = plan.ay + 1.1;
            for (int x = plan.minX; x <= plan.maxX + 1; x++) {
                p.spawnParticle(Particle.DUST, x, y, plan.minZ, 1, 0, 0, 0, 0, dust);
                p.spawnParticle(Particle.DUST, x, y, plan.maxZ + 1, 1, 0, 0, 0, 0, dust);
            }
            for (int z = plan.minZ; z <= plan.maxZ + 1; z++) {
                p.spawnParticle(Particle.DUST, plan.minX, y, z, 1, 0, 0, 0, 0, dust);
                p.spawnParticle(Particle.DUST, plan.maxX + 1, y, z, 1, 0, 0, 0, 0, dust);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        previews.remove(event.getPlayer().getUniqueId());
    }

    void clear() {
        for (Job job : jobs) {
            job.finishNow();
        }
        jobs.clear();
        for (UUID id : new ArrayList<>(previews.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                cancel(p, false);
            }
        }
        previews.clear();
    }

    // ------------------------------------------------------------------ menu

    void open(Player p, IslandImpl is) {
        if (!enabled()) {
            throw new CommandFail(m.rawMsg("facility-disabled"));
        }
        Menu holder = new Menu(is.id);
        Inventory inv = Bukkit.createInventory(holder, 54, m.rawMsg("facility-menu-title", "island", is.name));
        holder.inventory = inv;
        ItemStack glass = item(Material.BROWN_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, glass);
        }
        int total = m.config().getInt("facilities.max-per-island", 8);
        for (int i = 0; i < TYPES.size() && i < MENU_SLOTS.length; i++) {
            String type = TYPES.get(i);
            ConfigurationSection d = def(type);
            if (d == null || !d.getBoolean("enabled", true)) {
                continue;
            }
            Blueprint bp = Blueprint.of(type);
            List<Component> lore = new ArrayList<>();
            for (String line : d.getStringList("description")) {
                lore.add(Text.item(line));
            }
            lore.add(Component.empty());
            lore.add(m.rawMsg("facility-menu-cost", "cost", money(cost(type))));
            lore.add(m.rawMsg("facility-menu-size", "w", bp.maxX - bp.minX + 1, "l", bp.maxZ - bp.minZ + 1, "h", bp.maxY + 1));
            double bonus = d.getDouble("income-bonus", 0);
            if (bonus > 0) {
                lore.add(m.rawMsg("facility-menu-bonus", "bonus", Text.number(Math.round(bonus * 1000) / 10.0)));
            }
            long built = is.facilities.stream().filter(f -> f.type.equals(type)).count();
            lore.add(m.rawMsg("facility-menu-count", "count", built, "limit", d.getInt("limit", 1)));
            lore.add(Component.empty());
            lore.add(m.rawMsg("facility-menu-click"));
            Material icon = Material.matchMaterial(d.getString("icon", "BRICKS"));
            inv.setItem(MENU_SLOTS[i], item(icon == null ? Material.BRICKS : icon, Text.item(d.getString("display-name", type)), lore));
        }
        inv.setItem(4, item(Material.FILLED_MAP, m.rawMsg("facility-menu-info", "count", is.facilities.size(), "max", total),
                List.of(m.rawMsg("facility-menu-info-hint"), m.rawMsg("facility-menu-info-remove"))));
        inv.setItem(SLOT_BACK, item(Material.ARROW, m.rawMsg("facility-menu-back"), List.of()));
        p.openInventory(inv);
    }

    private static ItemStack item(Material type, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(type);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                net.kyori.adventure.text.format.TextDecoration.State.FALSE));
        List<Component> clean = new ArrayList<>();
        for (Component c : lore) {
            clean.add(c.decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                    net.kyori.adventure.text.format.TextDecoration.State.FALSE));
        }
        meta.lore(clean);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        IslandImpl is = m.islandImpl(menu.islandId);
        if (is == null) {
            p.closeInventory();
            return;
        }
        if (event.getSlot() == SLOT_BACK) {
            m.upgrades().open(p, is);
            return;
        }
        for (int i = 0; i < TYPES.size() && i < MENU_SLOTS.length; i++) {
            if (event.getSlot() == MENU_SLOTS[i]) {
                p.closeInventory();
                try {
                    if (m.at(p.getLocation()) != is) {
                        throw new CommandFail(m.rawMsg("facility-move-off-island", "island", is.name));
                    }
                    preview(p, is, TYPES.get(i));
                } catch (CommandFail fail) {
                    p.sendMessage(m.plugin().prefix().append(fail.component()));
                }
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
