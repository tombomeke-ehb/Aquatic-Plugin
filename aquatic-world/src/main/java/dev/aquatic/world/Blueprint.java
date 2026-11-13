package dev.aquatic.world;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A facility building as blocks around an origin. Local frame: the player stands at the front looking
 * SOUTH (+z); the building spans z = 0 (its front, with the door) to the back, x left/right of the middle,
 * y = 0 is the ground layer (it replaces the surface). Rotated as a whole when placed.
 */
final class Blueprint {

    record Cell(int x, int y, int z, BlockData data) {
    }

    /** A column under (x, z) filled down to the ground with this material (foundations, dock posts). */
    record Support(int x, int z, Material material) {
    }

    private final Map<Long, Cell> cells = new LinkedHashMap<>();
    final List<Support> supports = new ArrayList<>();
    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
    /** A dock must reach out over the water. */
    boolean needsWater;
    /** Loaded from a schematic file (block states are used as saved). */
    boolean fromFile;

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    List<Cell> cells() {
        return new ArrayList<>(cells.values());
    }

    Cell at(int x, int y, int z) {
        return cells.get(key(x, y, z));
    }

    Blueprint set(int x, int y, int z, BlockData data) {
        cells.put(key(x, y, z), new Cell(x, y, z, data));
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minZ = Math.min(minZ, z);
        maxZ = Math.max(maxZ, z);
        maxY = Math.max(maxY, y);
        return this;
    }

    Blueprint set(int x, int y, int z, Material m) {
        return set(x, y, z, Bukkit.createBlockData(m));
    }

    Blueprint fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, m);
                }
            }
        }
        return this;
    }

    Blueprint support(int x, int z, Material m) {
        supports.add(new Support(x, z, m));
        return this;
    }

    /** Foundation under every ground-layer block. */
    Blueprint supportAll(Material m) {
        for (Cell c : cells()) {
            if (c.y == 0 && !c.data.getMaterial().isAir()) {
                supports.add(new Support(c.x, c.z, m));
            }
        }
        return this;
    }

    static BlockData bd(Material m) {
        return Bukkit.createBlockData(m);
    }

    static BlockData facing(Material m, BlockFace f) {
        BlockData d = Bukkit.createBlockData(m);
        if (d instanceof Directional dir && dir.getFaces().contains(f)) {
            dir.setFacing(f);
        }
        return d;
    }

    static BlockData slab(Material m, boolean top) {
        BlockData d = Bukkit.createBlockData(m);
        if (d instanceof Slab s) {
            s.setType(top ? Slab.Type.TOP : Slab.Type.BOTTOM);
        }
        return d;
    }

    static BlockData hangingLantern() {
        BlockData d = Bukkit.createBlockData(Material.LANTERN);
        if (d instanceof Lantern l) {
            l.setHanging(true);
        }
        return d;
    }

    /** A door (two blocks) at (x, 1..2, z) facing f. */
    Blueprint door(int x, int y, int z, Material m, BlockFace f) {
        BlockData lower = Bukkit.createBlockData(m), upper = Bukkit.createBlockData(m);
        if (lower instanceof Door dl && upper instanceof Door du) {
            dl.setFacing(f);
            du.setFacing(f);
            dl.setHalf(Bisected.Half.BOTTOM);
            du.setHalf(Bisected.Half.TOP);
        }
        set(x, y, z, lower);
        return set(x, y + 1, z, upper);
    }

    /** Empties the rest of the bounding box above the ground (trees and grass inside it go). */
    Blueprint clearInside() {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = 1; y <= maxY; y++) {
                    if (!cells.containsKey(key(x, y, z))) {
                        cells.put(key(x, y, z), new Cell(x, y, z, Bukkit.createBlockData(Material.AIR)));
                    }
                }
            }
        }
        return this;
    }

    /** Connects fences, walls of panes and bars to their neighbours (call last). */
    Blueprint connect() {
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        for (Cell c : cells()) {
            if (!(c.data instanceof MultipleFacing mf) || c.data.getMaterial() == Material.VINE) {
                continue;
            }
            for (BlockFace f : faces) {
                if (!mf.getAllowedFaces().contains(f)) {
                    continue;
                }
                Cell n = at(c.x + f.getModX(), c.y, c.z + f.getModZ());
                boolean join = n != null && (n.data instanceof MultipleFacing
                        || n.data.getMaterial().name().endsWith("_FENCE_GATE")
                        || (n.data.getMaterial().isOccluding() && n.data.getMaterial().isSolid()));
                mf.setFace(f, join);
            }
        }
        return this;
    }

    // ------------------------------------------------------------------ helpers for detail

    /** A weathered mix: the same spot always gets the same block, so the preview matches the build. */
    static Material mix(int x, int y, int z, Material... options) {
        int h = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
        h ^= h >>> 13;
        return options[Math.floorMod(h * 0x5bd1e995, options.length)];
    }

    static final Material[] STONE_MIX = {Material.STONE_BRICKS, Material.STONE_BRICKS, Material.STONE_BRICKS, Material.STONE_BRICKS,
            Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.STONE_BRICKS, Material.COBBLESTONE};
    static final Material[] COBBLE_MIX = {Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
            Material.STONE, Material.ANDESITE};
    static final Material[] FLOOR_MIX = {Material.POLISHED_ANDESITE, Material.POLISHED_ANDESITE, Material.STONE_BRICKS, Material.ANDESITE,
            Material.CRACKED_STONE_BRICKS};

    Blueprint mixed(int x, int y, int z, Material... options) {
        return set(x, y, z, mix(x, y, z, options));
    }

    static BlockData log(Material m, org.bukkit.Axis axis) {
        BlockData d = Bukkit.createBlockData(m);
        if (d instanceof org.bukkit.block.data.Orientable o) {
            o.setAxis(axis);
        }
        return d;
    }

    static BlockData stairs(Material m, BlockFace f, boolean top) {
        BlockData d = facing(m, f);
        if (d instanceof org.bukkit.block.data.type.Stairs s) {
            s.setHalf(top ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        }
        return d;
    }

    static BlockData trapdoor(Material m, BlockFace f, boolean open, boolean top) {
        BlockData d = facing(m, f);
        if (d instanceof org.bukkit.block.data.type.TrapDoor t) {
            t.setOpen(open);
            t.setHalf(top ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        }
        return d;
    }

    static BlockData bell() {
        BlockData d = facing(Material.BELL, BlockFace.NORTH);
        if (d instanceof org.bukkit.block.data.type.Bell b) {
            b.setAttachment(org.bukkit.block.data.type.Bell.Attachment.CEILING);
        }
        return d;
    }

    static BlockData grown(Material crop) {
        BlockData d = Bukkit.createBlockData(crop);
        if (d instanceof org.bukkit.block.data.Ageable ag) {
            ag.setAge(ag.getMaximumAge());
        }
        return d;
    }

    /** Stairs facing the middle of a ring (for skirts and hip roofs). */
    static BlockFace inward(int x, int zc) {
        if (Math.abs(x) >= Math.abs(zc)) {
            return x < 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return zc < 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** A hip roof: rings of stairs from half-size r down to the top, centre (0, cz), starting at y. */
    Blueprint hipRoof(int r, int cz, int y, Material stair, Material fill) {
        for (int i = 0; r - i >= 0; i++) {
            int rr = r - i;
            for (int x = -rr; x <= rr; x++) {
                for (int z = -rr; z <= rr; z++) {
                    boolean ring = Math.abs(x) == rr || Math.abs(z) == rr;
                    if (rr == 0) {
                        set(x, y + i, z + cz, slab(stairToSlab(stair), false));
                    } else if (ring) {
                        set(x, y + i, z + cz, stairs(stair, inward(x, z), false));
                    } else {
                        set(x, y + i, z + cz, fill);
                    }
                }
            }
        }
        return this;
    }

    private static Material stairToSlab(Material stair) {
        Material m = Material.matchMaterial(stair.name().replace("_STAIRS", "_SLAB"));
        return m == null ? Material.SPRUCE_SLAB : m;
    }

    static boolean oct(int x, int zc, int r) {
        return Math.abs(x) <= r && Math.abs(zc) <= r && Math.abs(x) + Math.abs(zc) <= r + r / 2;
    }

    // ------------------------------------------------------------------ the facilities

    static Blueprint of(String type) {
        return switch (type) {
            case "watchtower" -> watchtower();
            case "forge" -> forge();
            case "farm" -> farm();
            case "dock" -> dock();
            case "storehouse" -> storehouse();
            case "lighthouse" -> lighthouse();
            case "gambling_den" -> gamblingDen();
            case "founders_shrine" -> foundersShrine();
            default -> null;
        };
    }

    /**
     * A stone-footed lookout: a flared, weathered base with a door, a timber shaft with windows and beams,
     * a wide platform on corbels with a railing and corner lanterns, a bell, and a hipped roof.
     */
    private static Blueprint watchtower() {
        Blueprint b = new Blueprint();
        int c = 2; // centre z
        for (int x = -3; x <= 3; x++) {                                   // footing
            for (int z = -1; z <= 5; z++) {
                b.mixed(x, 0, z, STONE_MIX);
                boolean skirt = Math.abs(x) == 3 || z == -1 || z == 5;
                if (skirt) {
                    boolean corner = Math.abs(x) == 3 && (z == -1 || z == 5);
                    b.set(x, 1, z, corner ? slab(Material.STONE_BRICK_SLAB, false)
                            : stairs(Material.STONE_BRICK_STAIRS, inward(x, z - c), false));
                }
            }
        }
        for (int y = 1; y <= 3; y++) {                                    // stone base walls
            for (int x = -2; x <= 2; x++) {
                for (int z = 0; z <= 4; z++) {
                    if (Math.abs(x) == 2 || z == 0 || z == 4) {
                        b.mixed(x, y, z, STONE_MIX);
                    }
                }
            }
        }
        b.door(0, 1, 0, Material.SPRUCE_DOOR, BlockFace.NORTH);
        b.set(0, 3, 0, Material.CHISELED_STONE_BRICKS);
        for (int y = 4; y <= 8; y++) {                                    // timber shaft
            for (int x = -2; x <= 2; x++) {
                for (int z = 0; z <= 4; z++) {
                    if (!(Math.abs(x) == 2 || z == 0 || z == 4)) {
                        continue;
                    }
                    boolean corner = Math.abs(x) == 2 && (z == 0 || z == 4);
                    if (corner) {
                        b.set(x, y, z, Material.SPRUCE_LOG);
                    } else if (y == 4 || y == 8) {
                        b.set(x, y, z, log(Material.STRIPPED_SPRUCE_LOG, Math.abs(x) == 2 ? org.bukkit.Axis.Z : org.bukkit.Axis.X));
                    } else if ((y == 6 || y == 7) && (x == 0 || z == c) && !(x == 0 && z == 4)) {
                        b.set(x, y, z, Material.GLASS_PANE);
                    } else {
                        b.set(x, y, z, Material.SPRUCE_PLANKS);
                    }
                }
            }
        }
        for (int y = 1; y <= 9; y++) {
            b.set(0, y, 3, facing(Material.LADDER, BlockFace.NORTH));
        }
        b.set(0, 4, 1, hangingLantern());
        for (int x = -3; x <= 3; x++) {                                   // platform and corbels
            for (int z = -1; z <= 5; z++) {
                if (x == 0 && z == 3) {
                    continue;
                }
                boolean edge = Math.abs(x) == 3 || z == -1 || z == 5;
                b.set(x, 9, z, edge ? log(Material.STRIPPED_SPRUCE_LOG, Math.abs(x) == 3 ? org.bukkit.Axis.Z : org.bukkit.Axis.X)
                        : bd(Material.SPRUCE_PLANKS));
                if (edge && !(Math.abs(x) == 3 && (z == -1 || z == 5)) && (x + z) % 2 == 0) {
                    b.set(x, 8, z, stairs(Material.SPRUCE_STAIRS, inward(x, z - c), true));
                }
            }
        }
        for (int x = -3; x <= 3; x++) {                                   // railing
            for (int z = -1; z <= 5; z++) {
                boolean edge = Math.abs(x) == 3 || z == -1 || z == 5;
                boolean corner = Math.abs(x) == 3 && (z == -1 || z == 5);
                if (edge) {
                    b.set(x, 10, z, corner ? Material.SPRUCE_LOG : Material.SPRUCE_FENCE);
                }
                if (corner) {
                    b.set(x, 11, z, Material.LANTERN);
                }
            }
        }
        for (int x : new int[]{-2, 2}) {                                  // roof posts
            for (int z : new int[]{0, 4}) {
                b.fill(x, 10, z, x, 12, z, Material.SPRUCE_LOG);
            }
        }
        b.hipRoof(4, c, 13, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS);
        b.set(0, 12, c, bell());
        b.set(-1, 12, 1, hangingLantern()).set(1, 12, 3, hangingLantern());
        b.set(0, 18, c, Material.LIGHTNING_ROD);
        b.clearInside();
        b.supportAll(Material.COBBLESTONE);
        return b.connect();
    }

    /**
     * An open-fronted smithy under a steep gabled roof: weathered stone, a brick chimney stack with smoke,
     * a blast furnace, lava, a quenching cauldron, anvil, grindstone, smithing table and storage.
     */
    private static Blueprint forge() {
        Blueprint b = new Blueprint();
        int[] roofY = {5, 6, 7, 8, 8, 7, 6, 5}; // rows z = -1 .. 6
        for (int x = -4; x <= 4; x++) {
            for (int z = -1; z <= 5; z++) {
                b.mixed(x, 0, z, FLOOR_MIX);
            }
        }
        for (int y = 1; y <= 4; y++) {                                    // back and side walls
            for (int x = -4; x <= 4; x++) {
                b.mixed(x, y, 5, Material.STONE_BRICKS, Material.BRICKS, Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS, Material.STONE_BRICKS);
            }
            for (int z = 2; z <= 4; z++) {
                b.mixed(-4, y, z, STONE_MIX).mixed(4, y, z, STONE_MIX);
            }
            b.set(-4, y, 0, Material.DARK_OAK_LOG).set(4, y, 0, Material.DARK_OAK_LOG);
        }
        b.set(-4, 1, 1, Material.DARK_OAK_FENCE).set(4, 1, 1, Material.DARK_OAK_FENCE);
        b.set(-4, 2, 3, Material.IRON_BARS).set(4, 2, 3, Material.IRON_BARS);
        for (int x = -3; x <= 3; x++) {
            b.set(x, 5, 0, log(Material.STRIPPED_DARK_OAK_LOG, org.bukkit.Axis.X)); // front beam
        }
        for (int z = 0; z <= 5; z++) {                                    // gable ends
            for (int y = 5; y < roofY[z + 1]; y++) {
                b.set(-4, y, z, Material.DARK_OAK_PLANKS).set(4, y, z, Material.DARK_OAK_PLANKS);
            }
        }
        for (int x = -5; x <= 5; x++) {                                   // roof
            for (int i = 0; i < roofY.length; i++) {
                int z = i - 1;
                b.set(x, roofY[i], z, stairs(Material.DEEPSLATE_TILE_STAIRS, i < 4 ? BlockFace.SOUTH : BlockFace.NORTH, false));
            }
        }
        for (int y = 0; y <= 10; y++) {                                   // chimney stack behind the furnaces
            for (int x = 2; x <= 3; x++) {
                for (int z = 6; z <= 7; z++) {
                    b.set(x, y, z, mix(x, y, z, Material.BRICKS, Material.BRICKS, Material.BRICKS, Material.MUD_BRICKS));
                }
            }
        }
        b.set(2, 11, 6, Material.CAMPFIRE).set(3, 11, 7, Material.CAMPFIRE);
        b.set(3, 11, 6, slab(Material.BRICK_SLAB, false)).set(2, 11, 7, slab(Material.BRICK_SLAB, false));
        b.set(3, 1, 4, facing(Material.BLAST_FURNACE, BlockFace.NORTH));
        b.set(2, 1, 4, Material.LAVA_CAULDRON);
        b.set(1, 1, 4, Material.WATER_CAULDRON);
        b.set(0, 1, 2, facing(Material.ANVIL, BlockFace.EAST));
        b.set(-2, 1, 1, facing(Material.GRINDSTONE, BlockFace.NORTH));
        b.set(-3, 1, 4, Material.SMITHING_TABLE);
        b.set(-2, 1, 4, facing(Material.CHEST, BlockFace.NORTH));
        b.set(-3, 1, 3, Material.BARREL).set(-3, 2, 4, Material.BARREL);
        b.set(-1, 3, 4, trapdoor(Material.SPRUCE_TRAPDOOR, BlockFace.NORTH, false, true));
        b.set(0, 3, 4, trapdoor(Material.SPRUCE_TRAPDOOR, BlockFace.NORTH, false, true));
        b.fill(-1, 6, 2, -1, 7, 2, Material.CHAIN).set(-1, 5, 2, hangingLantern());
        b.fill(2, 6, 2, 2, 7, 2, Material.CHAIN).set(2, 5, 2, hangingLantern());
        b.clearInside();
        b.supportAll(Material.STONE_BRICKS);
        return b.connect();
    }

    /** A fenced 9x9 field with log corner posts and lanterns, a water channel with lily pads, ripe crops and a scarecrow. */
    private static Blueprint farm() {
        Blueprint b = new Blueprint();
        Material[] rows = {Material.WHEAT, Material.CARROTS, Material.WHEAT, Material.POTATOES, Material.WHEAT, Material.BEETROOTS, Material.WHEAT};
        for (int x = -4; x <= 4; x++) {
            for (int z = 0; z <= 8; z++) {
                boolean edge = Math.abs(x) == 4 || z == 0 || z == 8;
                boolean corner = Math.abs(x) == 4 && (z == 0 || z == 8);
                if (edge) {
                    b.set(x, 0, z, Material.COARSE_DIRT);
                    if (corner) {
                        b.set(x, 1, z, Material.OAK_LOG).set(x, 2, z, Material.LANTERN);
                    } else {
                        b.set(x, 1, z, Material.OAK_FENCE);
                    }
                } else if (z == 4) {
                    b.set(x, 0, z, Material.WATER);
                    if (Math.floorMod(x, 3) == 0) {
                        b.set(x, 1, z, Material.LILY_PAD);
                    }
                } else {
                    BlockData soil = Bukkit.createBlockData(Material.FARMLAND);
                    if (soil instanceof org.bukkit.block.data.type.Farmland f) {
                        f.setMoisture(f.getMaximumMoisture());
                    }
                    b.set(x, 0, z, soil);
                    b.set(x, 1, z, grown(rows[x + 3]));
                }
            }
        }
        b.set(0, 0, 1, Material.DIRT_PATH).set(0, 1, 1, Material.AIR);
        b.set(0, 1, 0, facing(Material.OAK_FENCE_GATE, BlockFace.NORTH));
        b.set(0, 0, 4, Material.OAK_PLANKS).set(0, 1, 4, Material.AIR);        // a little footbridge over the channel
        b.set(-3, 0, 7, Material.COARSE_DIRT).set(-3, 1, 7, Material.HAY_BLOCK).set(-3, 2, 7, Material.HAY_BLOCK);
        b.set(-2, 0, 7, Material.COARSE_DIRT).set(-2, 1, 7, Material.COMPOSTER);
        b.set(3, 0, 6, Material.COARSE_DIRT).set(3, 1, 6, Material.OAK_FENCE).set(3, 2, 6, Material.HAY_BLOCK)
                .set(3, 3, 6, facing(Material.CARVED_PUMPKIN, BlockFace.NORTH));
        b.set(2, 2, 6, Material.OAK_FENCE).set(4, 2, 6, Material.OAK_FENCE);    // the scarecrow's arms
        b.clearInside();
        b.supportAll(Material.DIRT);
        return b.connect();
    }

    /** A sturdy dock: log-edged deck on posts, bollards, railings, lanterns and cargo at the end. */
    private static Blueprint dock() {
        Blueprint b = new Blueprint();
        b.needsWater = true;
        for (int z = 0; z <= 13; z++) {
            for (int x = -2; x <= 2; x++) {
                b.set(x, 0, z, Math.abs(x) == 2 ? log(Material.STRIPPED_SPRUCE_LOG, org.bukkit.Axis.Z)
                        : (z % 2 == 0 ? bd(Material.SPRUCE_PLANKS) : bd(Material.OAK_PLANKS)));
            }
            boolean post = z % 4 == 1 || z == 13;
            if (post) {
                b.support(-2, z, Material.SPRUCE_LOG).support(2, z, Material.SPRUCE_LOG).support(0, z, Material.SPRUCE_LOG);
                b.set(-2, 1, z, Material.SPRUCE_LOG).set(2, 1, z, Material.SPRUCE_LOG);
            } else if (z >= 1) {
                b.set(-2, 1, z, Material.SPRUCE_FENCE).set(2, 1, z, Material.SPRUCE_FENCE);
            }
        }
        b.support(-2, 0, Material.SPRUCE_LOG).support(2, 0, Material.SPRUCE_LOG);
        for (int z : new int[]{5, 13}) {
            b.set(-2, 2, z, Material.LANTERN).set(2, 2, z, Material.LANTERN);
        }
        b.set(2, 1, 7, Material.AIR).set(2, 1, 8, Material.AIR);              // a gap to step into a boat
        b.set(-1, 1, 12, Material.BARREL).set(-1, 2, 12, Material.BARREL).set(-1, 1, 11, Material.BARREL);
        b.set(1, 1, 12, facing(Material.CHEST, BlockFace.WEST));
        b.set(0, 1, 13, Material.AIR);
        b.clearInside();
        return b.connect();
    }

    /**
     * A timber-framed storehouse on a stone plinth: log frame and beams, a door with an awning, windows
     * with flower boxes, a dark oak gabled roof with a chimney, and barrels and chests inside.
     */
    private static Blueprint storehouse() {
        Blueprint b = new Blueprint();
        for (int x = -3; x <= 3; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean wall = Math.abs(x) == 3 || z == 0 || z == 6;
                b.set(x, 0, z, wall ? mix(x, 0, z, STONE_MIX) : Material.SPRUCE_PLANKS);
                if (!wall) {
                    continue;
                }
                boolean corner = Math.abs(x) == 3 && (z == 0 || z == 6);
                b.mixed(x, 1, z, COBBLE_MIX);
                for (int y = 2; y <= 4; y++) {
                    if (corner || (x == 0 && (z == 0 || z == 6)) || (z == 3 && Math.abs(x) == 3)) {
                        b.set(x, y, z, Material.STRIPPED_SPRUCE_LOG);
                    } else if (y == 4) {
                        b.set(x, y, z, log(Material.STRIPPED_SPRUCE_LOG, Math.abs(x) == 3 ? org.bukkit.Axis.Z : org.bukkit.Axis.X));
                    } else {
                        b.set(x, y, z, Material.SPRUCE_PLANKS);
                    }
                }
            }
        }
        b.set(0, 1, 0, Material.AIR).set(0, 2, 0, Material.AIR);
        b.door(0, 1, 0, Material.SPRUCE_DOOR, BlockFace.NORTH);
        for (int x = -1; x <= 1; x++) {
            b.set(x, 4, -1, stairs(Material.SPRUCE_STAIRS, BlockFace.SOUTH, false)); // awning over the door
        }
        b.set(0, 3, 0, Material.SPRUCE_PLANKS);
        for (int x : new int[]{-2, 2}) {                                  // front windows with flower boxes
            b.set(x, 2, 0, Material.GLASS_PANE).set(x, 3, 0, Material.GLASS_PANE);
            b.set(x, 1, -1, trapdoor(Material.SPRUCE_TRAPDOOR, BlockFace.SOUTH, false, true));
            b.set(x, 2, -1, mix(x, 2, -1, Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.AZURE_BLUET));
        }
        for (int x : new int[]{-3, 3}) {                                  // side windows
            b.set(x, 2, 1, Material.GLASS_PANE).set(x, 2, 5, Material.GLASS_PANE);
        }
        for (int i = 0; i <= 3; i++) {                                    // gabled roof, ridge front to back
            int y = 5 + i, xl = -4 + i, xr = 4 - i;
            for (int z = -1; z <= 7; z++) {
                b.set(xl, y, z, stairs(Material.DARK_OAK_STAIRS, BlockFace.EAST, false));
                b.set(xr, y, z, stairs(Material.DARK_OAK_STAIRS, BlockFace.WEST, false));
            }
            for (int x = xl + 1; x <= xr - 1; x++) {
                b.set(x, y, 0, Material.SPRUCE_PLANKS).set(x, y, 6, Material.SPRUCE_PLANKS);
            }
        }
        for (int z = -1; z <= 7; z++) {
            b.set(0, 9, z, slab(Material.DARK_OAK_SLAB, false));
        }
        b.set(0, 6, 0, log(Material.STRIPPED_SPRUCE_LOG, org.bukkit.Axis.Z));
        b.set(0, 7, 0, Material.GLASS_PANE);                               // a little attic window
        for (int y = 1; y <= 10; y++) {                                   // chimney
            b.set(-2, y, 5, mix(-2, y, 5, Material.BRICKS, Material.BRICKS, Material.STONE_BRICKS));
        }
        b.set(-2, 11, 5, Material.CAMPFIRE);
        for (int x = -2; x <= 2; x++) {                                   // storage inside
            if (x != -2) {
                b.set(x, 1, 5, Material.BARREL);
            }
        }
        b.set(-1, 2, 5, Material.BARREL).set(2, 2, 5, Material.BARREL);
        b.set(-2, 1, 3, facing(Material.CHEST, BlockFace.EAST)).set(-2, 1, 2, Material.BARREL);
        b.set(2, 1, 1, Material.CRAFTING_TABLE).set(2, 1, 2, Material.BARREL);
        b.set(0, 8, 3, hangingLantern());
        b.set(-3, 1, -1, Material.HAY_BLOCK).set(3, 1, -1, Material.BARREL);
        b.clearInside();
        b.supportAll(Material.STONE_BRICKS);
        return b.connect();
    }

    /**
     * A tapering lighthouse: a stone plinth, a red and white octagonal tower with windows and a ladder,
     * a gallery with iron railings, a glass lantern room with a beacon of sea lanterns, and a red dome.
     */
    private static Blueprint lighthouse() {
        Blueprint b = new Blueprint();
        int c = 3;
        for (int x = -3; x <= 3; x++) {
            for (int z = 0; z <= 6; z++) {
                int zc = z - c;
                if (oct(x, zc, 3)) {
                    b.mixed(x, 0, z, STONE_MIX);
                    boolean ring3 = !oct(x, zc, 2);
                    for (int y = 1; y <= 3; y++) {
                        if (ring3) {
                            b.mixed(x, y, z, STONE_MIX);
                        }
                    }
                    b.set(x, 4, z, ring3 ? stairs(Material.STONE_BRICK_STAIRS, inward(x, zc), false) : bd(Material.POLISHED_ANDESITE));
                    b.set(x, 16, z, ring3 ? log(Material.STRIPPED_DARK_OAK_LOG, Math.abs(x) >= Math.abs(zc) ? org.bukkit.Axis.Z : org.bukkit.Axis.X)
                            : bd(Material.POLISHED_ANDESITE));
                    if (ring3) {
                        b.set(x, 17, z, Material.IRON_BARS);
                        b.set(x, 15, z, stairs(Material.STONE_BRICK_STAIRS, inward(x, zc), true));
                    }
                }
                if (oct(x, zc, 2)) {
                    boolean ring2 = !oct(x, zc, 1);
                    for (int y = 5; y <= 15; y++) {
                        if (ring2) {
                            b.set(x, y, z, (y - 5) / 3 % 2 == 0 ? Material.WHITE_TERRACOTTA : Material.RED_TERRACOTTA);
                        }
                    }
                    if (ring2) {
                        b.set(x, 17, z, Material.GLASS).set(x, 18, z, Material.GLASS).set(x, 19, z, Material.GLASS);
                        b.set(x, 20, z, stairs(Material.RED_NETHER_BRICK_STAIRS, inward(x, zc), false));
                    } else {
                        b.set(x, 20, z, Material.RED_NETHER_BRICKS);
                        b.set(x, 21, z, Math.abs(x) + Math.abs(zc) == 0 ? bd(Material.RED_NETHER_BRICKS)
                                : stairs(Material.RED_NETHER_BRICK_STAIRS, inward(x, zc), false));
                    }
                }
            }
        }
        b.fill(0, 1, c + 2, 0, 3, c + 2, Material.STONE_BRICKS);            // backing for the ladder in the plinth
        for (int y = 1; y <= 16; y++) {
            b.set(0, y, c + 1, facing(Material.LADDER, BlockFace.NORTH));
        }
        b.set(0, 1, 0, Material.AIR).set(0, 2, 0, Material.AIR);
        b.door(0, 1, 0, Material.SPRUCE_DOOR, BlockFace.NORTH);
        b.set(0, 3, 0, Material.CHISELED_STONE_BRICKS);
        b.set(0, 1, 1, Material.AIR).set(0, 2, 1, Material.AIR);
        for (int y : new int[]{8, 13}) {                                  // windows
            b.set(0, y, c - 2, Material.GLASS_PANE).set(-2, y, c, Material.GLASS_PANE).set(2, y, c, Material.GLASS_PANE);
        }
        b.fill(0, 17, c, 0, 18, c, Material.SEA_LANTERN);                  // the light
        b.set(0, 19, c, Material.GLOWSTONE);
        b.set(0, 22, c, Material.LIGHTNING_ROD);
        b.set(0, 7, c, hangingLantern()).set(0, 12, c, hangingLantern());
        b.clearInside();
        b.supportAll(Material.STONE_BRICKS);
        return b.connect();
    }

    /**
     * The gambling den for spawn (paste it with /island admin schematic preview builtin:gambling_den): a red and
     * white striped tent on a dark oak floor, a dealer's counter, a dice table with stools, and three crate
     * pedestals at the back (make them crates with /crate set sailor | captain | kraken).
     */
    /**
     * The founders' shrine (15 x 9): a raised marble platform with steps at the front, five pedestals in a row
     * (the owner's in the middle is taller, with a diamond top), a white obelisk with a gold cap behind them,
     * corner pillars with lanterns, a flowering hedge along the back, candles and a sea-blue runway.
     * The hub module puts a founder's head on every pedestal (hub.yml "founders"). Local: x -7..7, z 0..8;
     * the pedestals stand on z = 4 at x = -6, -3, 0, 3, 6 (x = +6 is the viewer's left).
     */
    private static Blueprint foundersShrine() {
        Blueprint b = new Blueprint();
        BlockData hedge = Bukkit.createBlockData("minecraft:flowering_azalea_leaves[persistent=true]");
        BlockData hedge2 = Bukkit.createBlockData("minecraft:azalea_leaves[persistent=true]");
        BlockData candles = Bukkit.createBlockData("minecraft:white_candle[candles=3,lit=true]");
        for (int x = -7; x <= 7; x++) {
            for (int z = 0; z <= 8; z++) {
                b.set(x, 0, z, Material.POLISHED_ANDESITE);                          // the footing
            }
        }
        // the platform: a quartz rim, a stone floor, a prismarine runway to the owner's pedestal
        for (int x = -7; x <= 7; x++) {
            for (int z = 1; z <= 8; z++) {
                boolean rim = Math.abs(x) == 7 || z == 1 || z == 8;
                Material m;
                if (rim) {
                    m = (Math.abs(x) == 7 && (z == 1 || z == 8)) ? Material.CHISELED_QUARTZ_BLOCK : Material.QUARTZ_BRICKS;
                } else if (Math.abs(x) <= 1 && z <= 3) {
                    m = Math.abs(x) == 1 ? Material.DARK_PRISMARINE : Material.PRISMARINE_BRICKS;
                } else if ((Math.abs(x) == 4 || Math.abs(x) == 1) && z == 6) {
                    m = Material.SEA_LANTERN;                                       // lights in the floor
                } else {
                    m = (x + z) % 2 == 0 ? Material.POLISHED_DIORITE : Material.SMOOTH_STONE;
                }
                b.set(x, 1, z, m);
            }
        }
        for (int x = -5; x <= 5; x++) {
            b.set(x, 1, 0, facing(Material.QUARTZ_STAIRS, BlockFace.SOUTH));         // the steps up
        }
        for (int x : new int[]{-7, -6, 6, 7}) {
            b.set(x, 1, 0, Material.QUARTZ_BRICKS);
        }
        b.set(-6, 2, 0, Material.POTTED_BLUE_ORCHID);
        b.set(6, 2, 0, Material.POTTED_BLUE_ORCHID);
        b.set(0, 2, 1, Material.LIGHT_BLUE_CARPET);
        b.set(0, 2, 2, Material.LIGHT_BLUE_CARPET);
        b.set(0, 2, 3, Material.LIGHT_BLUE_CARPET);
        // the pedestals: quartz and gold for the founders, quartz, sea lantern and diamond for the owner
        for (int x : new int[]{-6, -3, 3, 6}) {
            b.set(x, 2, 4, Material.CHISELED_QUARTZ_BLOCK);
            b.set(x, 3, 4, Material.GOLD_BLOCK);
            b.set(x, 2, 3, slab(Material.SMOOTH_QUARTZ_SLAB, false));                // a step in front
        }
        b.set(0, 2, 4, Material.CHISELED_QUARTZ_BLOCK);
        b.set(0, 3, 4, Material.SEA_LANTERN);
        b.set(0, 4, 4, Material.DIAMOND_BLOCK);
        b.set(-1, 2, 4, facing(Material.QUARTZ_STAIRS, BlockFace.EAST));
        b.set(1, 2, 4, facing(Material.QUARTZ_STAIRS, BlockFace.WEST));
        for (int x : new int[]{-5, -4, -2, 2, 4, 5}) {
            if (Math.abs(x) == 2 || Math.abs(x) == 5) {
                b.set(x, 2, 5, candles);
            }
        }
        // the obelisk behind the owner
        for (int x = -1; x <= 1; x++) {
            for (int z = 6; z <= 8; z++) {
                b.set(x, 2, z, Material.QUARTZ_BRICKS);
                if (x != 0 || z != 7) {
                    b.set(x, 3, z, slab(Material.SMOOTH_QUARTZ_SLAB, false));
                }
            }
        }
        for (int y = 3; y <= 11; y++) {
            b.set(0, y, 7, y == 7 ? Material.CHISELED_QUARTZ_BLOCK : Material.QUARTZ_PILLAR);
        }
        b.set(0, 12, 7, Material.GOLD_BLOCK);
        b.set(0, 13, 7, Material.LIGHTNING_ROD);
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            b.set(f.getModX(), 7, 7 + f.getModZ(), facing(Material.SOUL_WALL_TORCH, f));   // a ring of blue flames
        }
        // corner pillars with lanterns, a low rail along the sides, a hedge along the back
        for (int x : new int[]{-7, 7}) {
            for (int z : new int[]{1, 8}) {
                for (int y = 2; y <= 4; y++) {
                    b.set(x, y, z, Material.QUARTZ_PILLAR);
                }
                b.set(x, 5, z, Material.LANTERN);
            }
            for (int z = 2; z <= 7; z++) {
                b.set(x, 2, z, slab(Material.SMOOTH_QUARTZ_SLAB, false));
            }
        }
        for (int x = -6; x <= 6; x++) {
            if (Math.abs(x) <= 1) {
                continue;
            }
            b.set(x, 2, 8, (x & 1) == 0 ? hedge : hedge2);
            if (Math.abs(x) >= 3 && Math.abs(x) <= 5) {
                b.set(x, 3, 8, x % 2 == 0 ? hedge2 : hedge);
            }
        }
        b.supportAll(Material.STONE_BRICKS);
        return b.clearInside();
    }

    private static Blueprint gamblingDen() {
        Blueprint b = new Blueprint();
        for (int x = -5; x <= 5; x++) {
            for (int z = 0; z <= 8; z++) {
                boolean edge = Math.abs(x) == 5 || z == 0 || z == 8;
                b.set(x, 0, z, edge ? Material.POLISHED_ANDESITE : ((x + z) % 2 == 0 ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS));
            }
        }
        for (int x : new int[]{-5, 5}) {
            for (int z : new int[]{0, 4, 8}) {
                b.fill(x, 1, z, x, 4, z, Material.DARK_OAK_LOG);
            }
        }
        for (int z = 1; z <= 7; z++) {                                    // side railings
            if (z != 4) {
                b.set(-5, 1, z, Material.DARK_OAK_FENCE).set(5, 1, z, Material.DARK_OAK_FENCE);
            }
        }
        for (int x = -4; x <= 4; x++) {                                   // striped back curtain
            for (int y = 1; y <= 4; y++) {
                b.set(x, y, 8, (x & 1) == 0 ? Material.RED_WOOL : Material.WHITE_WOOL);
            }
        }
        for (int x = -6; x <= 6; x++) {                                   // the striped canopy, stepped up to a ridge
            int y = Math.abs(x) >= 5 ? 5 : Math.abs(x) >= 3 ? 6 : Math.abs(x) >= 1 ? 7 : 8;
            for (int z = -1; z <= 9; z++) {
                b.set(x, y, z, (z & 1) == 0 ? Material.RED_WOOL : Material.WHITE_WOOL);
            }
        }
        for (int x = -4; x <= 4; x++) {                                   // gable ends under the canopy
            int top = Math.abs(x) >= 3 ? 5 : Math.abs(x) >= 1 ? 6 : 7;
            for (int y = 5; y <= top; y++) {
                b.set(x, y, 8, (x & 1) == 0 ? Material.RED_WOOL : Material.WHITE_WOOL);
            }
        }
        for (int x : new int[]{-3, 0, 3}) {                               // crate pedestals
            b.set(x, 1, 7, Material.CHISELED_STONE_BRICKS);
            b.set(x, 3, 6, hangingLantern());
        }
        b.set(-3, 2, 7, Material.NETHER_QUARTZ_ORE);   // the Sailor's, Captain's and Kraken's crates (crates.yml blocks)
        b.set(0, 2, 7, Material.NETHER_GOLD_ORE);
        b.set(3, 2, 7, Material.SHROOMLIGHT);
        for (int x = -4; x <= -2; x++) {                                  // the dealer's counter
            b.set(x, 1, 2, log(Material.STRIPPED_DARK_OAK_LOG, org.bukkit.Axis.X));
            b.set(x, 2, 2, slab(Material.DARK_OAK_SLAB, false));
        }
        b.set(-3, 3, 2, Material.AIR);
        b.set(2, 1, 3, Material.DARK_OAK_FENCE).set(2, 2, 3, Material.GREEN_CARPET);   // the dice table
        b.set(2, 1, 2, facing(Material.SPRUCE_STAIRS, BlockFace.SOUTH)).set(2, 1, 4, facing(Material.SPRUCE_STAIRS, BlockFace.NORTH));
        b.set(1, 1, 3, facing(Material.SPRUCE_STAIRS, BlockFace.EAST)).set(3, 1, 3, facing(Material.SPRUCE_STAIRS, BlockFace.WEST));
        b.fill(0, 6, 4, 0, 7, 4, Material.CHAIN).set(0, 5, 4, hangingLantern());
        b.set(-4, 1, 0, Material.POTTED_RED_TULIP).set(4, 1, 0, Material.POTTED_RED_TULIP);
        b.clearInside();
        b.supportAll(Material.STONE_BRICKS);
        return b.connect();
    }

    // ------------------------------------------------------------------ Tidewatch forts (island type TIDEWATCH)
    // Every variant is centred on (0, 0): the keep stands in the middle with its door to the south (+z) and an
    // open ground floor (the Warden Captain spawns there), and the courtyard in front of it (z = 6 .. 10) stays open
    // to the sky (the strongbox is buried there, soldiers spawn on open courtyard ground). The gate is in the south.

    static Blueprint fort(int variant) {
        return switch (Math.floorMod(variant, 3)) {
            case 1 -> fortBattery();
            case 2 -> fortStockade();
            default -> fortBastion();
        };
    }

    static final Material[] FORT_FLOOR = {Material.COBBLESTONE, Material.COBBLESTONE, Material.GRAVEL, Material.STONE_BRICKS,
            Material.ANDESITE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE};

    private Blueprint courtyard(int r, Material[] floor) {
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                mixed(x, 0, z, floor);
            }
        }
        for (int z = 5; z <= r; z++) {                                     // a paved path from the gate to the keep
            for (int x = -1; x <= 1; x++) {
                set(x, 0, z, (x + z) % 2 == 0 ? Material.STONE_BRICKS : Material.POLISHED_ANDESITE);
            }
        }
        return this;
    }

    /** The stone keep: three storeys, arrow slits, a crenellated roof with corner turrets and the Tidewatch flag. */
    private Blueprint stoneKeep() {
        int K = 4, top = 13;
        for (int x = -K; x <= K; x++) {
            for (int z = -K; z <= K; z++) {
                set(x, 0, z, Material.POLISHED_ANDESITE);
                boolean edge = Math.abs(x) == K || Math.abs(z) == K;
                boolean corner = Math.abs(x) == K && Math.abs(z) == K;
                for (int y = 1; y < top; y++) {
                    if (edge) {
                        int along = Math.abs(x) == K ? z : x;
                        boolean slit = !corner && Math.abs(along) == 2 && (y == 3 || y == 7 || y == 11);
                        set(x, y, z, corner ? bd(y % 4 == 0 ? Material.CHISELED_STONE_BRICKS : Material.POLISHED_ANDESITE)
                                : slit ? bd(Material.IRON_BARS) : bd(y == 5 || y == 9 ? Material.POLISHED_ANDESITE : mix(x, y, z, STONE_MIX)));
                    } else if (y == 5 || y == 9) {
                        set(x, y, z, (x + z) % 2 == 0 ? Material.SPRUCE_PLANKS : Material.DARK_OAK_PLANKS);   // upper floors
                    }
                }
                set(x, top, z, edge ? bd(Material.POLISHED_ANDESITE) : slab(Material.STONE_BRICK_SLAB, false));
                if (edge && (x + z) % 2 == 0) {
                    set(x, top + 1, z, Material.STONE_BRICK_WALL);             // merlons
                }
            }
        }
        for (int[] c : new int[][]{{K, K}, {K, -K}, {-K, K}, {-K, -K}}) {      // little corner turrets
            set(c[0], top + 1, c[1], Material.POLISHED_ANDESITE).set(c[0], top + 2, c[1], Material.POLISHED_ANDESITE);
            set(c[0], top + 3, c[1], slab(Material.DEEPSLATE_TILE_SLAB, false));
        }
        door(0, 1, K, Material.SPRUCE_DOOR, BlockFace.SOUTH);
        set(0, 3, K, Material.CHISELED_STONE_BRICKS);
        for (int y = 1; y <= top; y++) {                                       // ladder up every floor to the roof
            set(-K + 1, y, -K + 1, facing(Material.LADDER, BlockFace.SOUTH));
        }
        set(-K + 1, 0, -K + 1, Material.POLISHED_ANDESITE);
        set(0, 4, 0, hangingLantern()).set(0, 8, 0, hangingLantern()).set(0, 12, 0, hangingLantern());
        // ground floor: the captain's hall; first floor: bunks; second floor: the map room
        set(K - 1, 1, -K + 1, Material.BARREL).set(K - 1, 2, -K + 1, Material.BARREL).set(K - 2, 1, -K + 1, Material.BARREL);
        set(-K + 1, 1, K - 1, Material.CRAFTING_TABLE).set(K - 1, 1, K - 1, facing(Material.CHEST, BlockFace.WEST));
        set(K - 1, 6, -K + 1, Material.RED_BED).set(K - 1, 6, K - 1, Material.BLUE_BED);
        set(K - 1, 10, -K + 1, Material.CARTOGRAPHY_TABLE).set(K - 2, 10, -K + 1, Material.LECTERN);
        set(0, 10, -K + 1, Material.BARREL);
        for (int y = top + 1; y <= top + 6; y++) {                             // the Tidewatch flag on the roof
            set(1, y, 1, Material.SPRUCE_FENCE);
        }
        for (int fx = 2; fx <= 4; fx++) {
            set(fx, top + 6, 1, Material.BLUE_WOOL).set(fx, top + 5, 1, fx == 3 ? Material.WHITE_WOOL : Material.BLUE_WOOL);
            set(fx, top + 4, 1, Material.BLUE_WOOL);
        }
        return this;
    }

    private Blueprint cannon(int x, int y, int z, BlockFace out) {
        BlockData barrel = log(Material.POLISHED_BASALT, out.getModX() != 0 ? org.bukkit.Axis.X : org.bukkit.Axis.Z);
        set(x, y, z, slab(Material.DARK_OAK_SLAB, false));
        set(x, y + 1, z, barrel);
        set(x + out.getModX(), y + 1, z + out.getModZ(), barrel);
        set(x - out.getModX(), y + 1, z - out.getModZ(), Material.DARK_OAK_TRAPDOOR);
        return this;
    }

    private Blueprint brazier(int x, int y, int z) {
        return set(x, y, z, Material.POLISHED_ANDESITE).set(x, y + 1, z, Material.CAMPFIRE);
    }

    private Blueprint dummy(int x, int y, int z) {
        return set(x, y, z, Material.OAK_FENCE).set(x, y + 1, z, Material.HAY_BLOCK).set(x, y + 2, z, facing(Material.CARVED_PUMPKIN, BlockFace.SOUTH));
    }

    /** A long barracks house along a wall (x from x0 to x1, depth 5 towards +z from z0), with a gabled roof. */
    private Blueprint barracks(int x0, int x1, int z0, Material wall) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z0 + 4; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z0 + 4;
                for (int y = 1; y <= 4; y++) {
                    if (edge) {
                        boolean corner = (x == x0 || x == x1) && (z == z0 || z == z0 + 4);
                        set(x, y, z, corner ? bd(Material.STRIPPED_SPRUCE_LOG) : y == 1 ? bd(mix(x, y, z, COBBLE_MIX))
                                : (y == 2 && z == z0 + 4 && (x - x0) % 3 == 1) ? bd(Material.GLASS_PANE) : bd(wall));
                    } else {
                        set(x, y, z, Material.AIR);
                    }
                }
            }
            set(x, 5, z0 - 1, stairs(Material.DARK_OAK_STAIRS, BlockFace.SOUTH, false));
            set(x, 5, z0, stairs(Material.DARK_OAK_STAIRS, BlockFace.SOUTH, false));
            set(x, 6, z0 + 1, stairs(Material.DARK_OAK_STAIRS, BlockFace.SOUTH, false));
            set(x, 7, z0 + 2, slab(Material.DARK_OAK_SLAB, false));
            set(x, 6, z0 + 3, stairs(Material.DARK_OAK_STAIRS, BlockFace.NORTH, false));
            set(x, 5, z0 + 4, stairs(Material.DARK_OAK_STAIRS, BlockFace.NORTH, false));
            set(x, 5, z0 + 5, stairs(Material.DARK_OAK_STAIRS, BlockFace.NORTH, false));
            if ((x - x0) % 2 == 1 && x < x1) {
                set(x, 1, z0 + 1, Material.WHITE_BED);                       // bunks
            }
        }
        for (int z = z0 + 1; z <= z0 + 3; z++) {                               // gable ends
            for (int y = 5; y <= (z == z0 + 2 ? 6 : 5); y++) {
                set(x0, y, z, wall).set(x1, y, z, wall);
            }
        }
        door((x0 + x1) / 2, 1, z0 + 4, Material.SPRUCE_DOOR, BlockFace.SOUTH);
        return this;
    }

    /** Variant 1, the star fort: stone curtain walls with a wall walk, four arrowhead bastions with cannons, a gatehouse. */
    private static Blueprint fortBastion() {
        Blueprint b = new Blueprint();
        int R = 12;
        b.courtyard(R + 4, FORT_FLOOR);
        for (int i = -R; i <= R; i++) {                                        // curtain walls
            for (int[] s : new int[][]{{i, -R}, {i, R}, {-R, i}, {R, i}}) {
                for (int y = 1; y <= 6; y++) {
                    b.mixed(s[0], y, s[1], STONE_MIX);
                }
                if ((i & 1) == 0) {
                    b.set(s[0], 7, s[1], Material.STONE_BRICK_WALL);
                }
            }
        }
        for (int i = -R + 1; i <= R - 1; i++) {                                // the wall walk
            for (int[] s : new int[][]{{i, -R + 1}, {i, R - 1}, {-R + 1, i}, {R - 1, i}}) {
                b.set(s[0], 5, s[1], slab(Material.SPRUCE_SLAB, true));
            }
        }
        for (int[] c : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {       // diamond bastions on the corners
            int bx0 = c[0] * (R + 2), bz0 = c[1] * (R + 2);
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int d = Math.abs(dx) + Math.abs(dz);
                    if (d > 5) {
                        continue;
                    }
                    int x = bx0 + dx, z = bz0 + dz;
                    b.mixed(x, 0, z, STONE_MIX);
                    for (int y = 1; y <= 5; y++) {
                        b.mixed(x, y, z, STONE_MIX);
                    }
                    b.set(x, 6, z, d == 5 ? bd(Material.POLISHED_ANDESITE) : bd(Material.SMOOTH_STONE));
                    if (d == 5 && (dx + dz) % 2 == 0) {
                        b.set(x, 7, z, Material.STONE_BRICK_WALL);
                    }
                }
            }
            b.cannon(bx0 + c[0] * 2, 6, bz0 + c[1] * 2, c[0] > 0 ? BlockFace.EAST : BlockFace.WEST);
            b.brazier(bx0 - c[0], 6, bz0 - c[1]);
        }
        for (int x = -3; x <= 3; x++) {                                        // the gatehouse
            for (int z = R - 1; z <= R + 2; z++) {
                for (int y = 1; y <= 9; y++) {
                    boolean arch = Math.abs(x) <= 1 && y <= 4 && !(Math.abs(x) == 1 && y == 4);
                    b.set(x, y, z, arch ? bd(Material.AIR) : bd(y == 9 && (x + z) % 2 == 0 ? Material.STONE_BRICK_WALL
                            : y == 9 ? Material.AIR : mix(x, y, z, STONE_MIX)));
                }
            }
        }
        b.fill(-1, 4, R + 2, 1, 4, R + 2, Material.IRON_BARS);               // the portcullis, raised
        b.set(-2, 3, R + 3, Material.LANTERN).set(2, 3, R + 3, Material.LANTERN);
        b.stoneKeep();
        b.barracks(-R + 2, -R + 10, -R + 2, Material.SPRUCE_PLANKS);
        for (int z = -6; z <= 2; z += 4) {
            b.dummy(R - 3, 1, z);
        }
        b.brazier(-6, 1, 7).brazier(6, 1, 7);
        b.set(R - 2, 1, R - 3, Material.BARREL).set(R - 3, 1, R - 3, Material.BARREL).set(R - 2, 2, R - 3, Material.BARREL);
        b.set(-R + 2, 1, R - 3, Material.HAY_BLOCK).set(-R + 3, 1, R - 3, Material.HAY_BLOCK);
        for (int y = 1; y <= 5; y++) {                                         // ladders up to the wall walk
            b.set(-R + 1, y, 3, facing(Material.LADDER, BlockFace.EAST)).set(R - 1, y, 3, facing(Material.LADDER, BlockFace.WEST));
        }
        b.clearInside();
        b.supportAll(Material.STONE_BRICKS);
        return b.connect();
    }

    /** Variant 2, the coastal battery: a low wall, a raised gun deck with five cannons, a powder magazine, a watchtower. */
    private static Blueprint fortBattery() {
        Blueprint b = new Blueprint();
        int R = 13;
        b.courtyard(R, FORT_FLOOR);
        for (int i = -R; i <= R; i++) {                                        // the low outer wall
            for (int[] s : new int[][]{{i, -R}, {i, R}, {-R, i}, {R, i}}) {
                for (int y = 1; y <= 4; y++) {
                    b.mixed(s[0], y, s[1], STONE_MIX);
                }
                b.set(s[0], 5, s[1], (i & 1) == 0 ? bd(Material.STONE_BRICK_WALL) : slab(Material.STONE_BRICK_SLAB, false));
            }
        }
        for (int x = -R + 1; x <= R - 1; x++) {                                // the raised gun deck along the north wall
            for (int z = -R + 1; z <= -R + 4; z++) {
                for (int y = 1; y <= 3; y++) {
                    b.mixed(x, y, z, STONE_MIX);
                }
                b.set(x, 4, z, (x + z) % 2 == 0 ? Material.SPRUCE_PLANKS : Material.DARK_OAK_PLANKS);
            }
        }
        for (int x = -R + 1; x <= R - 1; x++) {
            b.set(x, 4, -R + 5, stairs(Material.STONE_BRICK_STAIRS, BlockFace.NORTH, false));
        }
        for (int k = -2; k <= 2; k++) {                                        // five cannons in embrasures
            int x = k * 5;
            b.cannon(x, 4, -R + 2, BlockFace.NORTH);
            b.set(x, 5, -R, Material.AIR).set(x, 4, -R, Material.AIR);
            b.set(x + 1, 5, -R + 3, Material.COAL_BLOCK);
        }
        for (int x = 8; x <= 12; x++) {                                        // the powder magazine (south-east corner)
            for (int z = 6; z <= 11; z++) {
                boolean edge = x == 8 || x == 12 || z == 6 || z == 11;
                for (int y = 1; y <= 3; y++) {
                    b.set(x, y, z, edge ? bd(mix(x, y, z, Material.BRICKS, Material.BRICKS, Material.STONE_BRICKS)) : bd(Material.AIR));
                }
                b.set(x, 4, z, Material.STONE_BRICK_SLAB);
            }
        }
        b.door(10, 1, 6, Material.IRON_DOOR, BlockFace.NORTH);
        b.set(9, 1, 10, Material.BARREL).set(11, 1, 10, Material.BARREL).set(9, 2, 10, Material.BARREL).set(11, 1, 9, Material.RED_WOOL);
        for (int y = 0; y <= 16; y++) {                                        // the watchtower (north-east)
            for (int x = 9; x <= 12; x++) {
                for (int z = -9; z <= -6; z++) {
                    boolean edge = x == 9 || x == 12 || z == -9 || z == -6;
                    if (y == 0) {
                        b.set(x, 0, z, Material.STONE_BRICKS);
                    } else if (y <= 12) {
                        b.set(x, y, z, edge ? bd(mix(x, y, z, STONE_MIX)) : bd(Material.AIR));
                    } else if (y == 13) {
                        b.set(x, y, z, Material.SPRUCE_PLANKS);
                    } else if (edge && (x + z) % 2 == 0 && y == 14) {
                        b.set(x, y, z, Material.STONE_BRICK_WALL);
                    }
                }
            }
        }
        b.set(10, 1, -6, Material.AIR).set(10, 2, -6, Material.AIR);
        for (int y = 1; y <= 13; y++) {
            b.set(11, y, -8, facing(Material.LADDER, BlockFace.SOUTH));
        }
        b.brazier(10, 13, -8);
        b.set(10, 15, -8, Material.AIR);
        b.stoneKeep();
        b.barracks(-12, -6, 6, Material.SPRUCE_PLANKS);
        for (int x = -4; x <= 4; x++) {                                        // the gate
            for (int y = 1; y <= 4; y++) {
                if (Math.abs(x) <= 1) {
                    b.set(x, y, R, Material.AIR);
                }
            }
        }
        b.set(-2, 5, R, Material.LANTERN).set(2, 5, R, Material.LANTERN);
        b.brazier(-6, 1, 11).brazier(6, 1, 11);
        b.dummy(-7, 1, -2).dummy(-7, 1, 1);
        b.clearInside();
        b.supportAll(Material.STONE_BRICKS);
        return b.connect();
    }

    /** Variant 3, the stockade: a pointed log palisade, stilted corner watchtowers, a timber blockhouse, tents. */
    private static Blueprint fortStockade() {
        Blueprint b = new Blueprint();
        int R = 12;
        b.courtyard(R, new Material[]{Material.COARSE_DIRT, Material.DIRT_PATH, Material.GRAVEL, Material.COARSE_DIRT, Material.PACKED_MUD});
        for (int i = -R; i <= R; i++) {                                        // the palisade
            for (int[] s : new int[][]{{i, -R}, {i, R}, {-R, i}, {R, i}}) {
                int h = 6 + Math.floorMod(i * 7 + s[0] + s[1], 2);
                for (int y = 1; y <= h; y++) {
                    b.set(s[0], y, s[1], (i & 1) == 0 ? Material.SPRUCE_LOG : Material.STRIPPED_SPRUCE_LOG);
                }
                b.set(s[0], h + 1, s[1], Material.SPRUCE_FENCE);
            }
        }
        for (int i = -R + 1; i <= R - 1; i++) {                                // a walk behind the stakes
            for (int[] s : new int[][]{{i, -R + 1}, {i, R - 1}, {-R + 1, i}, {R - 1, i}}) {
                b.set(s[0], 4, s[1], slab(Material.SPRUCE_SLAB, true));
            }
        }
        for (int[] c : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {       // stilted watchtowers
            int tx = c[0] * (R - 1), tz = c[1] * (R - 1);
            for (int y = 1; y <= 8; y++) {
                for (int[] leg : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                    b.set(tx + leg[0], y, tz + leg[1], Material.SPRUCE_LOG);
                }
            }
            for (int ox = -2; ox <= 2; ox++) {
                for (int oz = -2; oz <= 2; oz++) {
                    b.set(tx + ox, 9, tz + oz, Material.SPRUCE_PLANKS);
                    if (Math.abs(ox) == 2 || Math.abs(oz) == 2) {
                        b.set(tx + ox, 10, tz + oz, Material.SPRUCE_FENCE);
                    }
                    b.set(tx + ox, 13, tz + oz, stairs(Material.SPRUCE_STAIRS, inward(ox, oz), false));
                }
            }
            b.set(tx, 13, tz, Material.SPRUCE_PLANKS).set(tx, 14, tz, slab(Material.SPRUCE_SLAB, false));
            for (int y = 10; y <= 12; y++) {
                b.set(tx - 2, y, tz - 2, Material.SPRUCE_LOG).set(tx + 2, y, tz + 2, Material.SPRUCE_LOG)
                        .set(tx - 2, y, tz + 2, Material.SPRUCE_LOG).set(tx + 2, y, tz - 2, Material.SPRUCE_LOG);
            }
            b.set(tx, 12, tz, hangingLantern());
            for (int y = 1; y <= 9; y++) {
                b.set(tx, y, tz, facing(Material.LADDER, c[1] > 0 ? BlockFace.NORTH : BlockFace.SOUTH));
            }
        }
        // the blockhouse keep: a stone base, log walls, two floors and a steep roof
        int K = 4;
        for (int x = -K; x <= K; x++) {
            for (int z = -K; z <= K; z++) {
                b.set(x, 0, z, Material.SPRUCE_PLANKS);
                boolean edge = Math.abs(x) == K || Math.abs(z) == K;
                boolean corner = Math.abs(x) == K && Math.abs(z) == K;
                for (int y = 1; y <= 9; y++) {
                    if (edge) {
                        boolean window = !corner && (y == 3 || y == 7) && (Math.abs(Math.abs(x) == K ? z : x) == 2);
                        b.set(x, y, z, corner ? bd(Material.SPRUCE_LOG) : y <= 2 ? bd(mix(x, y, z, COBBLE_MIX))
                                : window ? bd(Material.GLASS_PANE) : log(Material.STRIPPED_SPRUCE_LOG, Math.abs(x) == K ? org.bukkit.Axis.Z : org.bukkit.Axis.X));
                    } else if (y == 5) {
                        b.set(x, y, z, Material.SPRUCE_PLANKS);
                    } else {
                        b.set(x, y, z, Material.AIR);
                    }
                }
            }
        }
        b.hipRoof(K + 1, 0, 10, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS);
        b.door(0, 1, K, Material.SPRUCE_DOOR, BlockFace.SOUTH);
        for (int y = 1; y <= 5; y++) {
            b.set(-K + 1, y, -K + 1, facing(Material.LADDER, BlockFace.SOUTH));
        }
        b.set(0, 4, 0, hangingLantern()).set(K - 1, 1, -K + 1, Material.BARREL).set(K - 1, 1, K - 1, facing(Material.CHEST, BlockFace.WEST));
        b.set(K - 1, 6, -K + 1, Material.RED_BED).set(-K + 2, 6, K - 1, Material.CARTOGRAPHY_TABLE);
        for (int y = 15; y <= 20; y++) {
            b.set(0, y, 0, Material.SPRUCE_FENCE);
        }
        for (int fx = 1; fx <= 3; fx++) {
            b.set(fx, 20, 0, Material.BLUE_WOOL).set(fx, 19, 0, fx == 2 ? Material.WHITE_WOOL : Material.BLUE_WOOL).set(fx, 18, 0, Material.BLUE_WOOL);
        }
        // tents and the camp
        for (int[] t : new int[][]{{-8, -2}, {-8, 4}, {8, 3}}) {
            for (int z = -2; z <= 2; z++) {
                b.set(t[0] - 1, 1, t[1] + z, stairs(Material.SPRUCE_STAIRS, BlockFace.EAST, false));
                b.set(t[0], 2, t[1] + z, Material.WHITE_WOOL);
                b.set(t[0] + 1, 1, t[1] + z, stairs(Material.SPRUCE_STAIRS, BlockFace.WEST, false));
                b.set(t[0], 1, t[1] + z, z == 2 ? Material.AIR : Material.WHITE_CARPET);
            }
        }
        b.set(5, 1, -5, Material.CAMPFIRE).set(4, 1, -5, Material.SPRUCE_SLAB).set(6, 1, -5, Material.SPRUCE_SLAB);
        b.set(-5, 1, -8, Material.BARREL).set(-6, 1, -8, Material.BARREL).set(-5, 2, -8, Material.BARREL).set(-7, 1, -8, Material.HAY_BLOCK);
        b.dummy(7, 1, -8).dummy(9, 1, -8);
        for (int x = -1; x <= 1; x++) {                                        // the gate
            for (int y = 1; y <= 5; y++) {
                b.set(x, y, R, Material.AIR);
            }
            b.set(x, 6, R, Material.SPRUCE_LOG);
        }
        b.set(-2, 7, R, Material.LANTERN).set(2, 7, R, Material.LANTERN);
        b.clearInside();
        b.supportAll(Material.SPRUCE_LOG);
        return b.connect();
    }
}
