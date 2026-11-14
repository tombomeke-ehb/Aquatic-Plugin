package dev.aquatic.world;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;

/**
 * A sailing ship as blocks. Local frame: the bow points to z = 0 (north), the stern is at the highest z, x runs
 * across the ship (0 = the keel line, +x = starboard/east). Row 0 is the keel; rows 0-2 lie under water, row 6 is
 * the main deck (players stand on row 7).
 *
 * The hull is rounded at the bow and V-shaped below the waterline. It has a stripe along the waterline, cannons
 * behind gunports, a raised forecastle, a captain's cabin with a quarterdeck on top, masts with curved sails and
 * crow's nests, a bowsprit with a jib, an anchor, and a hold below deck.
 *   sloop: one mast, 27 long, 9 wide.   brig: two masts, 35 long, 11 wide.
 */
final class ShipDesign {

    final Blueprint bp = new Blueprint();
    /** Loot chests (local x, y, z): one in the hold, one in the captain's cabin. */
    final List<int[]> chests = new ArrayList<>();
    /** Free spots on the main deck (feet position) for the crew. */
    final List<int[]> deck = new ArrayList<>();
    /** Where the captain stands: on the main deck in front of the cabin door. */
    int[] captain;
    /** Where a visitor is put by /ship tp: the middle of the main deck. */
    int[] arrival;

    private final int length, beam;

    private ShipDesign(int length, int beam) {
        this.length = length;
        this.beam = beam;
    }

    /** Half the length: the ship's position is its middle. */
    static int half(String design) {
        return "brig".equalsIgnoreCase(design) ? 17 : 13;
    }

    private static BlockData bd(String data) {
        return Bukkit.createBlockData(data);
    }

    /** Half-width of the hull at deck height along its length: a rounded bow, a slightly narrower stern. */
    private int hw(int z) {
        if (z < 0 || z >= length) {
            return -1;
        }
        int bow = beam + 3;
        if (z < bow) {
            return Math.max(1, (int) Math.round(beam * Math.pow((z + 1) / (double) bow, 0.6)));
        }
        return z >= length - 2 ? beam - 1 : beam;
    }

    private static final double[] SHAPE = {0.35, 0.65, 0.85, 0.95};

    /** Half-width of the hull at a row: narrow at the keel (a V-shaped hull), full from row 4 up. */
    private int width(int row, int z) {
        int w = hw(z);
        if (w < 0) {
            return -1;
        }
        return row < SHAPE.length ? (int) Math.round(w * SHAPE[row]) : w;
    }

    private boolean edge(int row, int x, int z) {
        int w = width(row, z);
        return Math.abs(x) == w || Math.abs(x) > width(row, z - 1) || Math.abs(x) > width(row, z + 1) || z == length - 1;
    }

    static ShipDesign of(String design, Material sail, Material trim, Material banner) {
        boolean brig = "brig".equalsIgnoreCase(design);
        ShipDesign d = new ShipDesign(brig ? 35 : 27, brig ? 5 : 4);
        d.build(brig, sail, trim, banner);
        return d;
    }

    private void build(boolean brig, Material sail, Material trim, Material banner) {
        Blueprint b = bp;
        boolean dark = sail.name().contains("BLACK") || sail.name().contains("GRAY");
        Material lower = Material.DARK_OAK_PLANKS;
        Material wale = dark ? Material.STRIPPED_DARK_OAK_WOOD : Material.STRIPPED_BIRCH_WOOD;
        Material upper = dark ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS;
        Material gunwale = dark ? Material.STRIPPED_DARK_OAK_WOOD : Material.STRIPPED_SPRUCE_WOOD;
        Material planks = Material.SPRUCE_PLANKS;
        Material fence = dark ? Material.DARK_OAK_FENCE : Material.SPRUCE_FENCE;
        Material post = dark ? Material.DARK_OAK_LOG : Material.SPRUCE_LOG;
        BlockData air = Blueprint.bd(Material.AIR);
        int fz = beam + 2;                                    // the forecastle covers z < fz
        int c0 = length - (brig ? 10 : 8);                    // the cabin starts here
        int[] masts = brig ? new int[]{(int) Math.round(length * 0.32), (int) Math.round(length * 0.56)}
                : new int[]{(int) Math.round(length * 0.46)};
        int hatch = fz + 3;                                   // the ladder down into the hold

        // ---- the hull, row by row
        for (int z = 0; z < length; z++) {
            for (int row = 0; row <= 6; row++) {
                int w = width(row, z);
                if (w < 0 || (row == 0 && w < 1)) {
                    continue;
                }
                for (int x = -w; x <= w; x++) {
                    boolean e = edge(row, x, z);
                    BlockData c;
                    if (row == 0 || row == 1) {
                        c = Blueprint.bd(e ? lower : planks);          // keel and the floor of the hold
                    } else if (row <= 5) {
                        Material m = row <= 2 ? lower : row == 3 ? wale : upper;
                        c = e ? Blueprint.bd(m) : air;
                    } else {
                        c = Blueprint.bd(e ? gunwale : planks);        // the main deck
                    }
                    b.set(x, row, z, c);
                }
            }
        }
        // gunports with cannons behind them (row 5, above the waterline)
        for (int z = fz + 2; z < c0 - 1; z += 3) {
            int w = width(5, z);
            for (int side : new int[]{-1, 1}) {
                b.set(side * w, 5, z, air);
                b.set(side * (w - 1), 5, z, Blueprint.facing(Material.DISPENSER, side > 0 ? BlockFace.EAST : BlockFace.WEST));
            }
        }

        // ---- the rail along the main deck, with gaps for two deck cannons on the brig
        for (int z = fz; z < c0; z++) {
            int w = width(6, z);
            boolean gun = brig && (z == c0 - 3);
            for (int side : new int[]{-1, 1}) {
                if (gun) {
                    b.set(side * (w - 1), 7, z, Blueprint.facing(Material.DISPENSER, side > 0 ? BlockFace.EAST : BlockFace.WEST));
                } else {
                    b.set(side * w, 7, z, (z - fz) % 4 == 0 ? post : fence);
                }
            }
        }

        // ---- the forecastle: one step up at the bow
        for (int z = 0; z < fz; z++) {
            int w = width(6, z);
            for (int x = -w; x <= w; x++) {
                boolean e = edge(6, x, z) || z == 0;
                b.set(x, 7, z, e ? upper : planks);
                if (e) {
                    b.set(x, 8, z, fence);
                }
            }
        }
        for (int x = -1; x <= 1; x++) {
            b.set(x, 7, fz, Blueprint.facing(Material.SPRUCE_STAIRS, BlockFace.NORTH));
        }
        b.set(-width(6, fz - 1), 9, fz - 1, Material.LANTERN);
        b.set(width(6, fz - 1), 9, fz - 1, Material.LANTERN);

        // ---- the bowsprit and the jib
        int[][] sprit = {{8, -1}, {8, -2}, {9, -3}, {9, -4}, {10, -5}};
        for (int[] s : sprit) {
            b.set(0, s[0], s[1], bd("minecraft:spruce_log[axis=z]"));
        }
        b.set(0, 11, -5, Material.SPRUCE_FENCE);
        int[][] jib = {{-4, 10, 11}, {-3, 10, 12}, {-2, 9, 13}, {-1, 9, 14}};
        for (int[] j : jib) {
            for (int y = j[1]; y <= j[2]; y++) {
                b.set(0, y, j[0], y == j[2] ? trim : sail);
            }
        }

        // ---- an anchor hanging at the bow
        int aw = width(4, 3) + 1;
        for (int y = 3; y <= 6; y++) {
            b.set(-aw, y, 3, bd("minecraft:chain[axis=y]"));
        }
        b.set(-aw, 2, 3, Material.ANVIL);

        // ---- the captain's cabin with the quarterdeck on its roof
        for (int z = c0; z < length; z++) {
            int w = width(6, z);
            for (int x = -w; x <= w; x++) {
                boolean e = Math.abs(x) == w || z == c0 || z == length - 1 || Math.abs(x) > width(6, z + 1);
                boolean corner = e && (Math.abs(x) == w) && (z == c0 || z == length - 1);
                for (int y = 7; y <= 9; y++) {
                    b.set(x, y, z, e ? Blueprint.bd(corner ? post : upper) : air);
                }
                b.set(x, 10, z, e ? Blueprint.bd(gunwale) : Blueprint.bd(planks));
                if (e) {
                    b.set(x, 11, z, corner ? post : fence);
                }
            }
        }
        b.door(0, 7, c0, Material.SPRUCE_DOOR, BlockFace.NORTH);
        b.set(-1, 9, c0, Material.LANTERN);
        for (int z = c0 + 2; z < length - 1; z += 2) {                           // side windows
            int w = width(6, z);
            b.set(-w, 8, z, Material.GLASS_PANE);
            b.set(w, 8, z, Material.GLASS_PANE);
        }
        for (int x = -(beam - 3); x <= beam - 3; x++) {                           // the stern windows
            b.set(x, 8, length - 1, Material.GLASS_PANE);
        }
        int lx = width(6, c0 - 1) - 1;                                            // a ladder up to the quarterdeck
        for (int y = 7; y <= 10; y++) {
            b.set(lx, y, c0 - 1, Blueprint.facing(Material.LADDER, BlockFace.NORTH));
        }
        b.set(lx, 11, c0, air);
        int sw = width(6, length - 1);
        b.set(-sw, 12, length - 1, Material.LANTERN);                             // stern lanterns
        b.set(sw, 12, length - 1, Material.LANTERN);
        b.set(0, 11, length - 3, Material.SPRUCE_FENCE);                          // the ship's wheel
        b.set(0, 12, length - 3, Blueprint.facing(Material.SPRUCE_TRAPDOOR, BlockFace.SOUTH));
        // inside the cabin: a desk, a map, a lantern, the captain's chest
        b.set(0, 7, length - 2, Blueprint.facing(Material.CHEST, BlockFace.NORTH));
        chests.add(new int[]{0, 7, length - 2});
        b.set(-(beam - 2), 7, length - 2, Material.CARTOGRAPHY_TABLE);
        b.set(beam - 2, 7, length - 2, Material.BARREL);
        b.set(beam - 2, 7, c0 + 1, Material.BOOKSHELF);
        b.set(0, 9, c0 + 3, Blueprint.hangingLantern());

        // ---- the hold: a ladder down, cargo, a lantern and the second chest
        for (int y = 2; y <= 5; y++) {
            b.set(-2, y, hatch - 1, planks);
        }
        for (int y = 2; y <= 6; y++) {
            b.set(-2, y, hatch, Blueprint.facing(Material.LADDER, BlockFace.SOUTH));
        }
        b.set(1, 2, c0 - 2, Blueprint.facing(Material.CHEST, BlockFace.NORTH));
        chests.add(new int[]{1, 2, c0 - 2});
        b.set(-1, 2, c0 - 2, Material.BARREL);
        b.set(2, 2, c0 - 4, Material.BARREL);
        b.set(-2, 2, c0 - 5, Material.BARREL);
        b.set(2, 3, c0 - 4, Material.BARREL);
        b.set(0, 2, hatch + 3, Material.LANTERN);
        b.set(0, 5, c0 - 3, Blueprint.hangingLantern());

        // ---- masts with sails, yards and a crow's nest
        for (int i = 0; i < masts.length; i++) {
            boolean main = i == masts.length - 1;
            int mz = masts[i];
            int y1 = main ? 15 : 14, y2 = main ? 21 : 19, top = main ? 27 : 23;
            for (int y = 2; y <= top; y++) {
                b.set(0, y, mz, y > top - 3 ? Blueprint.bd(Material.SPRUCE_FENCE) : Blueprint.bd(Material.SPRUCE_LOG));
            }
            sail(b, mz, 9, y1 - 1, beam + 1, sail, trim);                       // the course
            yard(b, mz, y1, beam + 2);
            sail(b, mz, y1 + 1, y2 - 1, beam, sail, trim);                      // the topsail
            yard(b, mz, y2, beam + 1);
            int nest = y2 + 1;
            for (int x = -1; x <= 1; x++) {
                for (int z = mz - 1; z <= mz + 1; z++) {
                    if (x != 0 || z != mz) {
                        b.set(x, nest, z, Blueprint.slab(Material.SPRUCE_SLAB, false));
                    }
                }
            }
            for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                b.set(c[0], nest + 1, mz + c[1], Material.SPRUCE_FENCE);
            }
            b.set(0, top + 1, mz, banner);
            b.set(0, y1 - 1, mz + 1, Blueprint.facing(Material.LANTERN, BlockFace.UP));
        }

        // ---- deck cargo and the crew's spots
        b.set(-(beam - 1), 7, c0 - 1, Material.BARREL);
        b.set(beam - 2, 7, fz + 1, Material.BARREL);
        b.set(beam - 2, 8, fz + 1, Blueprint.facing(Material.LANTERN, BlockFace.UP));
        captain = new int[]{0, 7, c0 - 2};
        arrival = new int[]{1, 7, (fz + c0) / 2};
        for (int z = fz + 1; z <= c0 - 2; z++) {
            boolean clear = true;
            for (int mz : masts) {
                clear &= z != mz;
            }
            if (!clear) {
                continue;
            }
            for (int x = -(beam - 2); x <= beam - 2; x++) {
                if (!(x == -2 && z == hatch) && x != 0 && b.at(x, 7, z) == null) {
                    deck.add(new int[]{x, 7, z});
                }
            }
        }
    }

    /** A sail from row y0 to y1, half-width w, hanging just in front of the mast and bellied in the middle. */
    private static void sail(Blueprint b, int mz, int y0, int y1, int w, Material sail, Material trim) {
        for (int y = y0; y <= y1; y++) {
            for (int x = -w; x <= w; x++) {
                boolean rim = y == y0 || y == y1;
                int belly = !rim && Math.abs(x) <= w - 2 ? 1 : 0;
                b.set(x, y, mz - 1 - belly, rim || Math.abs(x) == w ? trim : sail);
            }
        }
    }

    private static void yard(Blueprint b, int mz, int y, int w) {
        for (int x = -w; x <= w; x++) {
            if (x != 0) {
                b.set(x, y, mz, Bukkit.createBlockData("minecraft:stripped_spruce_log[axis=x]"));
            }
        }
        b.set(-w, y - 1, mz, Material.CHAIN);
        b.set(w, y - 1, mz, Material.CHAIN);
    }
}
