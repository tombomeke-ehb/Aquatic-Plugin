package dev.aquatic.world;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A small reader for Minecraft NBT files (gzip-compressed or not), enough for schematics: compounds become
 * maps, lists become lists, arrays stay arrays and numbers become boxed numbers.
 */
final class NbtReader {

    private NbtReader() {
    }

    /** The root compound of the file. */
    static Map<String, Object> read(File file) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(file))) {
            raw.mark(2);
            int b1 = raw.read(), b2 = raw.read();
            raw.reset();
            InputStream in = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(raw) : raw;
            DataInputStream data = new DataInputStream(in);
            int type = data.readUnsignedByte();
            if (type != 10) {
                throw new IOException("not an NBT file (root is not a compound)");
            }
            data.readUTF(); // root name
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) payload(data, 10, 0);
            return root;
        }
    }

    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 512) {
            throw new IOException("NBT nested too deep");
        }
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                byte[] a = new byte[checked(in.readInt())];
                in.readFully(a);
                return a;
            }
            case 8: {
                int len = in.readUnsignedShort();
                byte[] b = new byte[len];
                in.readFully(b);
                return new String(b, StandardCharsets.UTF_8); // close enough to modified UTF-8 for block names
            }
            case 9: {
                int elem = in.readUnsignedByte();
                int len = checked(in.readInt());
                List<Object> list = new ArrayList<>(Math.min(len, 4096));
                for (int i = 0; i < len; i++) {
                    list.add(payload(in, elem, depth + 1));
                }
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) {
                        return map;
                    }
                    String name = in.readUTF();
                    map.put(name, payload(in, t, depth + 1));
                }
            }
            case 11: {
                int[] a = new int[checked(in.readInt())];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case 12: {
                long[] a = new long[checked(in.readInt())];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("unknown NBT tag " + type);
        }
    }

    private static int checked(int len) throws IOException {
        if (len < 0 || len > 64_000_000) {
            throw new IOException("bad NBT length " + len);
        }
        return len;
    }
}
