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
}
