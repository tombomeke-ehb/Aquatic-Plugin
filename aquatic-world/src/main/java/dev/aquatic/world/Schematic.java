package dev.aquatic.world;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A build loaded from a file in plugins/AquaticSMP/schematics:
 * <ul>
 *   <li><b>.schem</b>: WorldEdit / FastAsyncWorldEdit (Sponge schematic version 2 and 3)</li>
 *   <li><b>.nbt</b>: a vanilla structure (saved with a structure block)</li>
 * </ul>
 * Only the blocks are read (chest contents and sign text are not copied yet). Structure void is skipped,
 * air is kept (it clears what was there).
 */
final class Schematic {

    record Cell(int x, int y, int z, BlockData data) {
    }

    final String name;
    final int width, height, length;
    final List<Cell> cells;
    final int skipped;

    private Schematic(String name, int width, int height, int length, List<Cell> cells, int skipped) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.length = length;
        this.cells = cells;
        this.skipped = skipped;
    }

    static Schematic load(File file) throws IOException {
        Map<String, Object> root = NbtReader.read(file);
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".nbt")) {
            return structure(file.getName(), root);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> s = root.get("Schematic") instanceof Map<?, ?> m ? (Map<String, Object>) m : root;
        return sponge(file.getName(), s);
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** Sponge schematic v2 (Palette + BlockData at the top) or v3 (inside "Blocks"). */
    @SuppressWarnings("unchecked")
    private static Schematic sponge(String file, Map<String, Object> s) throws IOException {
        int w = num(s.get("Width")) & 0xffff, h = num(s.get("Height")) & 0xffff, l = num(s.get("Length")) & 0xffff;
        Map<String, Object> palette;
        byte[] data;
        if (s.get("Blocks") instanceof Map<?, ?> blocks) {           // version 3
            palette = (Map<String, Object>) blocks.get("Palette");
            data = (byte[]) blocks.get("Data");
        } else {                                                     // version 2
            palette = (Map<String, Object>) s.get("Palette");
            data = (byte[]) s.get("BlockData");
        }
        if (palette == null || data == null || w == 0 || h == 0 || l == 0) {
            throw new IOException("not a WorldEdit schematic this plugin can read (version 2 or 3)");
        }
        BlockData[] states = new BlockData[palette.size() + 1];
        int skipped = 0;
        for (Map.Entry<String, Object> e : palette.entrySet()) {
            int id = num(e.getValue());
            if (id >= states.length) {
                states = java.util.Arrays.copyOf(states, id + 1);
            }
            states[id] = parse(e.getKey());
        }
        List<Cell> cells = new ArrayList<>();
        int index = 0, i = 0;
        while (i < data.length) {
            int value = 0, shift = 0, b;
            do {                                                     // varint
                b = data[i++];
                value |= (b & 0x7f) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);
            int y = index / (w * l), rest = index % (w * l), z = rest / w, x = rest % w;
            index++;
            BlockData d = value < states.length ? states[value] : null;
            if (d == null) {
                skipped++;
                continue;
            }
            if (d.getMaterial() != Material.STRUCTURE_VOID) {
                cells.add(new Cell(x, y, z, d));
            }
        }
        return new Schematic(file, w, h, l, cells, skipped);
    }

    /** A vanilla structure file: size, palette (or palettes) and the blocks with their positions. */
    @SuppressWarnings("unchecked")
    private static Schematic structure(String file, Map<String, Object> root) throws IOException {
        List<Object> size = (List<Object>) root.get("size");
        List<Object> palette = (List<Object>) root.get("palette");
        if (palette == null && root.get("palettes") instanceof List<?> all && !all.isEmpty()) {
            palette = (List<Object>) all.get(0);
        }
        List<Object> blocks = (List<Object>) root.get("blocks");
        if (size == null || palette == null || blocks == null) {
            throw new IOException("not a structure file (size, palette or blocks missing)");
        }
        BlockData[] states = new BlockData[palette.size()];
        for (int i = 0; i < states.length; i++) {
            Map<String, Object> p = (Map<String, Object>) palette.get(i);
            StringBuilder sb = new StringBuilder(String.valueOf(p.get("Name")));
            if (p.get("Properties") instanceof Map<?, ?> props && !props.isEmpty()) {
                sb.append('[');
                boolean first = true;
                for (Map.Entry<?, ?> e : props.entrySet()) {
                    sb.append(first ? "" : ",").append(e.getKey()).append('=').append(e.getValue());
                    first = false;
                }
                sb.append(']');
            }
            states[i] = parse(sb.toString());
        }
        List<Cell> cells = new ArrayList<>();
        int skipped = 0;
        for (Object o : blocks) {
            Map<String, Object> b = (Map<String, Object>) o;
            List<Object> pos = (List<Object>) b.get("pos");
            int state = num(b.get("state"));
            BlockData d = state >= 0 && state < states.length ? states[state] : null;
            if (d == null || pos == null || pos.size() < 3) {
                skipped++;
                continue;
            }
            if (d.getMaterial() != Material.STRUCTURE_VOID) {
                cells.add(new Cell(num(pos.get(0)), num(pos.get(1)), num(pos.get(2)), d));
            }
        }
        return new Schematic(file, num(size.get(0)), num(size.get(1)), num(size.get(2)), cells, skipped);
    }

    /** "minecraft:oak_stairs[facing=east]" -> block data; null for blocks this server doesn't know. */
    private static BlockData parse(String state) {
        try {
            return Bukkit.createBlockData(state);
        } catch (IllegalArgumentException e) {
            int bracket = state.indexOf('[');
            if (bracket > 0) {
                try {
                    return Bukkit.createBlockData(state.substring(0, bracket)); // keep the block, lose odd properties
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    /**
     * As a blueprint in the facility frame: the front (the side with the entrance) towards the player,
     * centred left/right, the bottom layer at y = yOffset (0 = it replaces the ground surface).
     *
     * @param front which side of the build is its front as it was saved: north, east, south or west
     */
    Blueprint toBlueprint(String front, int yOffset, Material foundation) {
        int turns = switch (front == null ? "north" : front.toLowerCase(Locale.ROOT)) {
            case "west" -> 1;
            case "south" -> 2;
            case "east" -> 3;
            default -> 0;
        };
        org.bukkit.block.structure.StructureRotation sr = switch (turns) {
            case 1 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90;
            case 2 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180;
            case 3 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90;
            default -> org.bukkit.block.structure.StructureRotation.NONE;
        };
        // turn around the centre, then put the front row at z = 0
        double mx = (width - 1) / 2.0, mz = (length - 1) / 2.0;
        List<int[]> pos = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        for (Cell c : cells) {
            double x = c.x - mx, z = c.z - mz;
            for (int i = 0; i < turns; i++) {
                double t = x;
                x = -z;
                z = t;
            }
            int ix = (int) Math.floor(x + 0.5 + 1e-6), iz = (int) Math.floor(z + 0.5 + 1e-6);
            pos.add(new int[]{ix, iz});
            minX = Math.min(minX, ix);
            maxX = Math.max(maxX, ix);
            minZ = Math.min(minZ, iz);
        }
        int shiftX = -(minX + maxX) / 2;
        Blueprint b = new Blueprint();
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            BlockData d = c.data.clone();
            d.rotate(sr);
            b.set(pos.get(i)[0] + shiftX, c.y + yOffset, pos.get(i)[1] - minZ, d);
        }
        if (foundation != null) {
            for (Blueprint.Cell c : b.cells()) {
                if (c.y() == yOffset && !c.data().getMaterial().isAir()) {
                    b.support(c.x(), c.z(), foundation);
                }
            }
        }
        b.fromFile = true;
        return b;
    }
}
