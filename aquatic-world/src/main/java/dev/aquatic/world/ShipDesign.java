package dev.aquatic.world;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * A sailing ship as blocks. Local frame: the bow points to z = 0, the stern is at the highest z, x runs across
 * the deck (0 = the keel line). y = 0 is the keel; rows 0-2 lie underwater, the deck is row 4.
 * Designs: "sloop" (one mast, 25 long) and "brig" (two masts, 33 long).
 */
final class ShipDesign {

    final Blueprint bp = new Blueprint();
    /** Loot chests (local x, y, z): one in the hold, one in the captain's cabin. */
    final List<int[]> chests = new ArrayList<>();
    /** Free spots on the main deck (feet position) for the crew. */
    final List<int[]> deck = new ArrayList<>();
    /** Where the captain stands: in front of the cabin door. */
    int[] captain;

    static ShipDesign of(String design, Material sail, Material trim, Material banner) {
        boolean brig = "brig".equalsIgnoreCase(design);
        int length = brig ? 33 : 25;
        int[] masts = brig ? new int[]{9, 19} : new int[]{10};
        ShipDesign d = new ShipDesign();
        Blueprint b = d.bp;
        BlockData air = Blueprint.bd(Material.AIR);
        Material hull = Material.DARK_OAK_PLANKS, wale = Material.SPRUCE_PLANKS, plank = Material.SPRUCE_PLANKS;
        IntUnaryOperator hw = z -> z < 0 || z >= length ? -1 : z < 4 ? z : z >= length - 2 ? 3 : 4;

        // ---- the hull: keel, bottom, sides with a lighter wale, the hold, the deck, a low rail
        for (int z = 0; z < length; z++) {
            int h = hw.applyAsInt(z);
            for (int x = -h; x <= h; x++) {
                boolean edge = Math.abs(x) == h || hw.applyAsInt(z - 1) < Math.abs(x) || hw.applyAsInt(z + 1) < Math.abs(x);
                if (Math.abs(x) <= Math.max(0, h - 2)) {
                    b.set(x, 0, z, hull);
                }
                b.set(x, 1, z, hull);
                b.set(x, 2, z, edge ? Blueprint.bd(hull) : air);
                b.set(x, 3, z, edge ? Blueprint.bd(wale) : air);
                b.set(x, 4, z, edge ? Material.STRIPPED_SPRUCE_WOOD : plank);
                b.set(x, 5, z, edge ? Blueprint.slab(Material.DARK_OAK_SLAB, false) : air);
            }
        }
        for (int z = -3; z < 0; z++) {
            b.set(0, 5, z, Bukkit.createBlockData("minecraft:spruce_log[axis=z]"));   // the bowsprit
        }
        b.set(0, 5, 0, Material.STRIPPED_SPRUCE_WOOD);

        // ---- stairs down into the hold
        b.set(-2, 4, 5, Blueprint.facing(Material.SPRUCE_STAIRS, BlockFace.NORTH));
        b.set(-2, 4, 6, air);
        b.set(-2, 3, 6, Blueprint.facing(Material.SPRUCE_STAIRS, BlockFace.NORTH));
        b.set(-2, 4, 7, air);
        b.set(-2, 3, 7, air);
        b.set(-2, 2, 7, Blueprint.facing(Material.SPRUCE_STAIRS, BlockFace.NORTH));

        // ---- masts: a yard below and above the sail, striped sails, a crow's nest with the flag
        for (int mz : masts) {
            for (int y = 2; y <= 18; y++) {
                b.set(0, y, mz, Material.SPRUCE_LOG);
            }
            for (int x = -4; x <= 4; x++) {
                if (x != 0) {
                    b.set(x, 8, mz, Bukkit.createBlockData("minecraft:spruce_log[axis=x]"));
                    b.set(x, 16, mz, Bukkit.createBlockData("minecraft:spruce_log[axis=x]"));
                }
                for (int y = 9; y <= 15; y++) {
                    b.set(x, y, mz - 1, y == 9 || y == 15 || x % 4 == 0 ? trim : sail);
                }
            }
            for (int x = -1; x <= 1; x++) {
                for (int z = mz - 1; z <= mz + 1; z++) {
                    if (x != 0 || z != mz) {
                        b.set(x, 18, z, Blueprint.slab(Material.SPRUCE_SLAB, false));
                    }
                }
            }
            b.set(0, 19, mz, banner);
        }

        // ---- the captain's cabin at the stern, with the quarterdeck on its roof
        int c0 = length - 7;
        for (int z = c0; z < length; z++) {
            int h = hw.applyAsInt(z);
            for (int x = -h; x <= h; x++) {
                boolean edge = Math.abs(x) == h || hw.applyAsInt(z + 1) < Math.abs(x) || z == c0;
                boolean corner = (Math.abs(x) == h && (z == c0 || z == length - 1 || hw.applyAsInt(z + 1) < Math.abs(x)));
                for (int y = 5; y <= 7; y++) {
                    b.set(x, y, z, edge ? Blueprint.bd(corner ? Material.DARK_OAK_LOG : plank) : air);
                }
                b.set(x, 8, z, plank);
                if (edge && !(z == c0 && x == 3)) {
                    b.set(x, 9, z, Blueprint.slab(Material.DARK_OAK_SLAB, false));
                }
            }
        }
        b.door(0, 5, c0, Material.SPRUCE_DOOR, BlockFace.NORTH);
        for (int x = -1; x <= 1; x++) {
            b.set(x, 6, length - 1, Material.GLASS);                               // stern windows
        }
        b.set(-4, 6, c0 + 3, Material.GLASS);
        b.set(4, 6, c0 + 3, Material.GLASS);
        for (int y = 5; y <= 8; y++) {
            b.set(3, y, c0 - 1, Blueprint.facing(Material.LADDER, BlockFace.NORTH)); // up to the quarterdeck
        }
        b.set(-2, 9, length - 2, Material.LANTERN);
        b.set(2, 5, length - 2, Material.BARREL);
        b.set(-2, 5, c0 + 1, Material.LANTERN);

        // ---- loot chests and deck clutter
        b.set(0, 5, length - 2, Blueprint.facing(Material.CHEST, BlockFace.NORTH));
        d.chests.add(new int[]{0, 5, length - 2});
        b.set(1, 2, length - 9, Blueprint.facing(Material.CHEST, BlockFace.NORTH));
        d.chests.add(new int[]{1, 2, length - 9});
        b.set(3, 5, 7, Material.BARREL);
        b.set(-3, 5, 13, Material.BARREL);
        b.set(-3, 5, c0 - 1, Material.LANTERN);
        b.set(2, 2, 12, Material.BARREL);
        b.set(-2, 2, 14, Material.BARREL);

        d.captain = new int[]{0, 5, c0 - 2};
        for (int z = 5; z <= c0 - 3; z++) {
            boolean nearMast = false;
            for (int mz : masts) {
                nearMast |= Math.abs(z - mz) <= 1;
            }
            if (!nearMast) {
                d.deck.add(new int[]{-1, 5, z});
                d.deck.add(new int[]{1, 5, z});
                d.deck.add(new int[]{2, 5, z});
            }
        }
        return d;
    }
}
