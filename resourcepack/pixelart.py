"""Hand-drawn pixel art for the item icons and the effect sprites.

Icons are drawn as 16x16 character grids (the same grid and proportions as Minecraft's own items), coloured
with small hand-picked ramps (highlight, light, mid, dark - shadows shift towards blue, highlights towards
warm white), and then doubled to 32x32 with the Scale2x pixel-art scaler, which keeps hard pixels and smooths
the diagonals. No noise, no gradients.

Effect sprites are 32x32 frames with hard edges: a tinted layer (grey levels the game multiplies with the
effect's colour) and a white core layer on top that stays white.
"""
import math
from PIL import Image


def _rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def sprite(rows, palette):
    """16 strings of 16 characters -> a 16x16 image; '.' is empty, every other character is a palette key."""
    assert len(rows) == 16, len(rows)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(rows):
        assert len(row) == 16, (y, row, len(row))
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, y] = _rgb(palette[ch]) + (255,)
    return img


def scale2x(img):
    """Scale2x / EPX: doubles pixel art, rounding the stair steps of diagonals without blurring."""
    w, h = img.size
    src = img.load()
    out = Image.new("RGBA", (w * 2, h * 2), (0, 0, 0, 0))
    dst = out.load()

    def at(x, y):
        return src[x, y] if 0 <= x < w and 0 <= y < h else (0, 0, 0, 0)

    for y in range(h):
        for x in range(w):
            p = at(x, y)
            a, b, c, d = at(x, y - 1), at(x + 1, y), at(x - 1, y), at(x, y + 1)
            e0 = e1 = e2 = e3 = p
            if c == a and c != d and a != b:
                e0 = a
            if a == b and a != c and b != d:
                e1 = b
            if d == c and d != b and c != a:
                e2 = c
            if b == d and b != a and d != c:
                e3 = d
            dst[x * 2, y * 2], dst[x * 2 + 1, y * 2] = e0, e1
            dst[x * 2, y * 2 + 1], dst[x * 2 + 1, y * 2 + 1] = e2, e3
    return out


def _mix(c1, c2, t):
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(3))


WARM, COOL = (255, 246, 214), (26, 18, 58)       # highlights lean to warm white, shadows to a cool dark violet
BLADE, GRIP = "abcd", "hH"


def icon(rows, palette, edits=(), tip=None, inlay=None, details=()):
    """A 32x32 icon from a 16x16 drawing.

    The drawing gives the shape and the base colours; the detail is added at 32x32:
      - every part is bevelled: the pixels along its upper-left edge are lit (towards warm white), the ones along
        its lower-right edge shaded (towards cool violet), so metal and cloth get volume and more colours
      - blades get a fuller (a dark groove with a bright line beside it) and shift in colour towards "tip"
      - grips are wrapped: two tones in bands
      - the outline takes the colour of what it surrounds instead of being one flat dark colour
      - "inlay" (a palette key) is set into the middle of the blade every few pixels
      - details = extra pixels at 32x32: (x, y, key) or (x0, y0, x1, y1, key) rectangles
    edits = (x, y, key) changes to the 16x16 drawing."""
    rows = [list(r) for r in rows]
    for x, y, ch in edits:
        rows[y][x] = ch
    keys = sorted({ch for r in rows for ch in r if ch != "."})
    index = {k: (i + 1, 0, 0) for i, k in enumerate(keys)}
    big = scale2x(sprite(["".join(r) for r in rows], {k: "#%02x0000" % (i + 1) for i, k in enumerate(keys)}))
    bp = big.load()
    key = [[None] * 32 for _ in range(32)]
    for y in range(32):
        for x in range(32):
            if bp[x, y][3]:
                key[y][x] = keys[bp[x, y][0] - 1]
    for d in details:
        if len(d) == 3:
            key[d[1]][d[0]] = d[2]
        else:
            for yy in range(d[1], d[3] + 1):
                for xx in range(d[0], d[2] + 1):
                    key[yy][xx] = d[4]
    return shade(key, palette, tip, inlay)


def shade(key, palette, tip=None, inlay=None):
    """Lights a 32x32 grid of part keys (see icon()); the grid stays available as shade.grid for 3D models."""
    shade.grid = key
    col = {k: _rgb(v) for k, v in palette.items()}
    tipc = _rgb(tip) if tip else None

    def k(x, y):
        return key[y][x] if 0 <= x < 32 and 0 <= y < 32 else None

    def edge(x, y):
        v = k(x, y)
        return v is None or v == "#"

    out = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    op = out.load()
    for y in range(32):
        for x in range(32):
            v = key[y][x]
            if v is None or v == "#":
                continue
            c = col[v]
            if v in GRIP:                                             # the wrap
                c = col["h"] if ((x - y) // 2) % 2 == 0 else col.get("H", col["h"])
            if v in BLADE and "b" in col:
                if v == "b" and k(x + 1, y + 1) in ("c", "d") and k(x - 1, y - 1) in ("a", "b"):
                    c = _mix(col["b"], col.get("c", col["b"]), 0.75)   # the fuller: a groove down the middle
                elif v == "b" and k(x - 1, y - 1) == "a":
                    c = _mix(col["a"], col["b"], 0.35)                 # and the bright line beside it
                if inlay and v == "b" and ((x - y) % 7 == 0) and not edge(x - 1, y) and not edge(x + 1, y):
                    c = col[inlay]
                elif tipc is not None:
                    t = max(0.0, min(1.0, (x - y + 20) / 44.0))        # 0 at the guard, 1 at the point
                    c = _mix(c, tipc, int(t * 5) / 5.0 * 0.6)          # in steps, not a smooth gradient
            lit = edge(x, y - 1) or edge(x - 1, y)
            dark = edge(x, y + 1) or edge(x + 1, y)
            if lit and not dark:
                c = _mix(c, WARM, 0.36)
            elif dark and not lit:
                c = _mix(c, COOL, 0.34)
            elif not lit and not dark and (edge(x - 1, y - 1)) and v not in GRIP:
                c = _mix(c, WARM, 0.16)                               # a soft second step of light
            op[x, y] = c + (255,)
    base_outline = col["#"]
    for y in range(32):
        for x in range(32):
            if key[y][x] != "#":
                continue
            near = [op[xx, yy][:3] for xx, yy in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1), (x + 1, y + 1), (x - 1, y - 1))
                    if 0 <= xx < 32 and 0 <= yy < 32 and op[xx, yy][3] and key[yy][xx] not in (None, "#")]
            if near:
                avg = tuple(sum(c[i] for c in near) // len(near) for i in range(3))
                c = _mix(base_outline, _mix(avg, COOL, 0.72), 0.55)   # the outline picks up the colour it surrounds
            else:
                c = base_outline
            op[x, y] = c + (255,)
    return out


# ------------------------------------------------------------------ the drawings
# '#' outline; a b c d = the main material from highlight to shadow; g G = guard / fittings; h H = grip;
# p P = pommel or gem; x y z l = accents.

SWORD = [
    ".............###",
    "............#ab#",
    "...........#abc#",
    "..........#abc#.",
    ".........#abc#..",
    "........#abc#...",
    ".......#abc#....",
    "..##..#abc#.....",
    "..#g##abc#......",
    "...#gGbc#.......",
    "...#GgG#........",
    "..#hH#gG#.......",
    ".#hH#.#g#.......",
    "#hH#...##.......",
    "#pP#............",
    "###.............",
]

FANG = [  # a serrated blade
    ".............###",
    "............#ab#",
    "...........#ab##",
    "..........#abc#.",
    ".........#ab##..",
    "........#abc#...",
    ".......#ab##....",
    "..##..#abc#.....",
    "..#g##abc#......",
    "...#gGbc#.......",
    "...#GgG#........",
    "..#hH#gG#.......",
    ".#hH#.#g#.......",
    "#hH#...##.......",
    "#pP#............",
    "###.............",
]

CLEAVER = [  # a broad blade with a clipped point
    "............###.",
    "...........#abc#",
    "..........#abbc#",
    ".........#abbc#.",
    "........#abbc#..",
    ".......#abbc#...",
    "......#abbc#....",
    "..##.#abbc#.....",
    "..#g#abbc#......",
    "...#gGbc#.......",
    "...#GgG#........",
    "..#hH#gG#.......",
    ".#hH#.#g#.......",
    "#hH#...##.......",
    "#pP#............",
    "###.............",
]

RAPIER = [  # a thin blade and a cup guard
    "..............##",
    ".............#a#",
    "............#ab#",
    "...........#ab#.",
    "..........#ab#..",
    ".........#ab#...",
    "........#ab#....",
    ".......#ab#.....",
    "..###.#ab#......",
    ".#ggg#ab#.......",
    ".#gGgGb#........",
    "..#GgGg#........",
    ".#hH#Gg#........",
    "#hH#.##.........",
    "#pP#............",
    "###.............",
]

CUTLASS = [  # a curved blade that widens towards the point
    "...........###..",
    "..........#abc#.",
    "..........#abc#.",
    ".........#abcc#.",
    ".........#abc#..",
    "........#abcc#..",
    ".......#abcc#...",
    "..##..#abcc#....",
    "..#g##abcc#.....",
    "...#gGbc#.......",
    "...#GgG#........",
    "..#hH#gG#.......",
    ".#hH#.#g#.......",
    "#hH#...##.......",
    "#pP#............",
    "###.............",
]

MAUL = [
    ".........####...",
    "........#abbc#..",
    ".......#abbbcc#.",
    "......#abbbbccd#",
    ".....#abbbxbcd#.",
    ".....#bbbxbccd#.",
    "......#bbbccd#..",
    ".....#h#bccd#...",
    "....#hH##cd#....",
    "...#hH#..##.....",
    "..#hH#..........",
    ".#hH#...........",
    "#hH#............",
    "#gG#............",
    "###.............",
    "................",
]

HARPOON = [
    ".............###",
    "...........##ab#",
    "..........#aabc#",
    "..........#abc#.",
    ".........#hbc##.",
    "........#hH###..",
    ".......#hH#.....",
    "......#hH#......",
    ".....#gG#.......",
    "....#hH#........",
    "...#hH#.........",
    "..#hH#..........",
    ".#hH#...........",
    "#hH#............",
    "#H#.............",
    "##..............",
]

ANCHOR = [
    "......####......",
    ".....#a##b#.....",
    ".....#a##b#.....",
    "......#ab#......",
    "...####ab####...",
    "...#aaaabbbb#...",
    "...####ab####...",
    "......#ab#......",
    "......#ab#......",
    ".#....#ab#....#.",
    "#a#...#ab#...#b#",
    "#ab#..#ab#..#bc#",
    "#abb##abb##bbc#.",
    ".#abbbbbbbbbc#..",
    "..##bbbbbcc##...",
    "....#######.....",
]

BATON = [
    ".............###",
    "............#ab#",
    "...........#abc#",
    "..........#gGc#.",
    ".........#abc#..",
    "........#abc#...",
    ".......#abc#....",
    "......#gGc#.....",
    ".....#abc#......",
    "....#abc#.......",
    "...#hHH#........",
    "..#hHH#.........",
    ".#hHH#..........",
    "#gGG#...........",
    "#pP#............",
    "###.............",
]

PICKAXE = [
    "................",
    "....########....",
    "...#aabbbbbcc#..",
    "....####hHbbcc#.",
    ".......#hH##bcc#",
    "......#hH#.#bcd#",
    ".....#hH#..#bcd#",
    "....#hH#...#cd#.",
    "...#hH#....#d#..",
    "..#hH#......#...",
    ".#hH#...........",
    "#hH#............",
    "#H#.............",
    "##..............",
    "................",
    "................",
]

KEY = [
    "................",
    "..........####..",
    ".........#abbc#.",
    "........#ab##bc#",
    "........#b#pP#c#",
    "........#bc##cd#",
    ".......#abccdd#.",
    "......#ab#####..",
    ".....#ab#.......",
    "....#ab#........",
    "...#ab##........",
    "..#ab#c#........",
    ".#ab###.........",
    "#ab#c#..........",
    "#b###...........",
    "##..............",
]

SHACKLES = [
    "................",
    "..####..........",
    ".#abbc#.........",
    "#ab##bc#........",
    "#b#..#c#........",
    "#b#..#c##.......",
    "#bc##cd#g#......",
    ".#ccdd##G#......",
    "..####.#g#####..",
    "........#abbbc#.",
    ".......#ab###bc#",
    ".......#b#...#c#",
    ".......#b#...#c#",
    ".......#bc###cd#",
    "........#ccddd#.",
    ".........#####..",
]

HELMET = [
    "................",
    "................",
    ".....######.....",
    "....#aabbbc#....",
    "...#aabxxbbc#...",
    "..#aabbxxbbcc#..",
    "..#abbbxxbbbc#..",
    "..#abbbbbbbbc#..",
    "..#abbbbbbbbc#..",
    "..#ab######bc#..",
    "..#ab#....#bc#..",
    "..#bc#....#cd#..",
    "..#bc#....#cd#..",
    "..####....####..",
    "................",
    "................",
]

TRICORN = [
    "................",
    "................",
    "................",
    "................",
    "......####......",
    ".....#abbc#.....",
    "....#abbbbc#....",
    "....#xxxxxx#....",
    ".###abbbbbbc###.",
    "#aabbbbbbbbbbcc#",
    ".##bbccccccdd##.",
    "...##########...",
    "................",
    "................",
    "................",
    "................",
]

CHESTPLATE = [
    "................",
    "..##........##..",
    ".#ab##....##bc#.",
    "#aabbb####bbbcc#",
    "#abbbbbbbbbbbbc#",
    "#ab#bbbbbbbb#bc#",
    "###.#bbxxbb#.###",
    "....#bbxxbc#....",
    "....#bbbbbc#....",
    "....#bbbbcc#....",
    "....#bbbbcc#....",
    "....#bccccd#....",
    "....########....",
    "................",
    "................",
    "................",
]

COAT = [
    "................",
    "..##........##..",
    ".#ab##....##bc#.",
    "#aabbx####xbbcc#",
    "#abbbbxllxbbbbc#",
    "#ab#bbbxxbbb#bc#",
    "###.#bybbyb#.###",
    "....#bbbbbc#....",
    "....#bybbyc#....",
    "....#zzzzzz#....",
    "....#bbbbcc#....",
    "....#bcc#cd#....",
    "....########....",
    "................",
    "................",
    "................",
]

LEGGINGS = [
    "................",
    "................",
    "...##########...",
    "...#xxxyyxxx#...",
    "...#abbbbbbc#...",
    "...#abbbbbbc#...",
    "...#abb##bbc#...",
    "...#ab#..#bc#...",
    "...#ab#..#bc#...",
    "...#ab#..#bc#...",
    "...#ab#..#bc#...",
    "...#bc#..#cd#...",
    "...#bc#..#cd#...",
    "...####..####...",
    "................",
    "................",
]

BOOTS = [
    "................",
    "................",
    "..#####..#####..",
    "..#xxx#..#xxx#..",
    "..#abc#..#abc#..",
    "..#abc#..#abc#..",
    "..#abc#..#abc#..",
    "..#abc#..#abc#..",
    "..#abc#..#abc#..",
    ".##abc#..#abc##.",
    "#aabbc#..#abbcc#",
    "#abbcd#..#bbccd#",
    "#bccdd#..#ccddd#",
    "#######..#######",
    "................",
    "................",
]


def pal(outline, main, guard=None, grip=None, gem=None, **extra):
    """A palette: main = 'a b c d' colours, guard = 'g G', grip = 'h H', gem = 'p P', plus named accents."""
    p = {"#": outline}
    p.update(zip("abcd", main.split()))
    if guard:
        p.update(zip("gG", guard.split()))
    if grip:
        p.update(zip("hH", grip.split()))
    if gem:
        p.update(zip("pP", gem.split()))
    p.update(extra)
    return p


WEAPONS = {  # name: (drawing, palette, edits at 16x16, extras for icon(): tip colour, inlay key, details at 32x32)
    "storm_saber": (SWORD, pal("#141a33", "#f4fbff #a9cdf2 #5f8fd0 #34579a", "#ffe066 #c9972a", "#3d4f7a #27335a", "#ffe066 #fff7c2",
                               x="#ffe066"), [], dict(tip="#fff6a8", inlay="x")),
    "nightbloom": (SWORD, pal("#1c0b2b", "#f3b6e0 #b565b8 #6f3398 #3d1b63", "#f48fb1 #b8477e", "#3a1d52 #241135", "#ff9ec4 #ffe3f0",
                              x="#ffe3f0"), [], dict(tip="#ffd3ea", inlay="x")),
    "tidecleaver": (CLEAVER, pal("#152233", "#f2f8fc #b9cfdf #7d98b0 #516a85", "#8fd3ff #4a8fc2", "#2d4a6b #1b2f47", "#9fe3ff #e6f9ff",
                                 x="#7fe3ff"), [], dict(tip="#ffffff", inlay="x")),
    "leviathans_fang": (FANG, pal("#140406", "#e06a55 #8f2a26 #521416 #2a090c", "#77778a #3a3a46", "#4a1414 #2a0a0a", "#ff5a44 #ffc0b0"),
                        [], dict(tip="#ff9a7a")),
    "tide_rapier": (RAPIER, pal("#0e2a33", "#e6fff8 #86dccb #3fbf9c #1f8f7a", "#f2c84b #b88a1e", "#1f5f8f #143f63", "#43cea2 #c9fff0"),
                    [], dict(tip="#ffffff")),
    "abyss_cutlass": (CUTLASS, pal("#071c1e", "#9af2e2 #3fb3a3 #1f7470 #103f42", "#2fe0c0 #178f80", "#2b2f33 #17191c", "#00c9a7 #b5fff0",
                                   x="#b5fff0"), [], dict(tip="#c8fff3", inlay="x")),
    "coral_scimitar": (CUTLASS, pal("#3a1220", "#ffd9c7 #ff9d8a #e8625f #a83a50", "#f6d28a #c79a45", "#7a4a2e #4f2c1a", "#ff8f7a #fff1e6",
                                    x="#fff1e6"), [], dict(tip="#fff4ea", inlay="x")),
    "krakens_maul": (MAUL, pal("#1a0d33", "#e2c8ff #a678e0 #6b3fb0 #3d2073", "#b0b0c4 #6a6a82", "#8a6245 #55392a", x="#7dfff0"),
                     [], dict(details=[(17, 8, 18, 9, "x"), (15, 10, 16, 11, "x"), (19, 6, 20, 7, "x")])),
    "harpoon": (HARPOON, pal("#24160c", "#f6fafc #c2ccd3 #8996a0 #5f6b75", "#e8d8a8 #b89e62", "#b07a45 #6e4524"), [], dict()),
    "abyssal_anchor": (ANCHOR, pal("#06201f", "#a8fff0 #3fb8a8 #1f7a72 #0f4a48", x="#e0fffb"),
                       [], dict(details=[(11, 3, "x"), (12, 3, "x"), (14, 17, "x"), (14, 20, "x"), (14, 23, "x"), (9, 10, "x"), (22, 10, "x")])),
    "abyssal_baton": (BATON, pal("#071c1e", "#8ff5e6 #3fa79a #1c5f58 #0f3a38", "#e8f0f0 #9fb3b3", "#4a5058 #2a2e33", "#7dfff0 #e0fffb"),
                      [], dict(tip="#d5fff8")),
    "deepcore_pickaxe": (PICKAXE, pal("#0c2238", "#d6fbff #5fd3f0 #2a8fc0 #1a5a8a", None, "#9a7650 #5c432c", x="#ffffff"),
                         [], dict(details=[(9, 5, "x"), (14, 5, "x"), (19, 6, "x"), (26, 12, "x")])),
    "key_sailor": (KEY, pal("#1d2a33", "#ffffff #d7e3ea #9aabb8 #6a7a88", None, None, "#3fb8c9 #bff6ff"), [], dict()),
    "key_captain": (KEY, pal("#3a2408", "#fff3b0 #f2c230 #c98f1a #8f5f10", None, None, "#c0392b #ffb3a6"), [], dict()),
    "key_kraken": (KEY, pal("#120a26", "#cbb8ee #8f72c8 #5a4296 #33245e", None, None, "#43e0c8 #c9fff5"), [], dict()),
    "abyssal_shackles": (SHACKLES, pal("#06201f", "#a8fff0 #3fb8a8 #1f7a72 #0f4a48", "#d4dde3 #7d8b96"), [], dict()),
}

STEEL = "#e4ebf1 #a3b3c2 #6f8194 #46566c"
ARMOUR = {
    "tidewarden_helmet": (HELMET, pal("#1a2230", STEEL, x="#d9b45a")),
    "tidewarden_chestplate": (CHESTPLATE, pal("#1a2230", STEEL, x="#d9b45a")),
    "tidewarden_leggings": (LEGGINGS, pal("#1a2230", STEEL, x="#8a6a34", y="#d9b45a", plate="1")),
    "tidewarden_boots": (BOOTS, pal("#1a2230", STEEL, x="#d9b45a", plate="1")),
    "corsair_hat": (TRICORN, pal("#0a0604", "#6a5040 #44322a #2c1f18 #1a120d", x="#d9b45a")),
    "corsair_coat": (COAT, pal("#2a0a0c", "#d0604e #a3332c #74201f #4a1416", x="#3a2318", l="#efe7d6", y="#e8c25a", z="#4a3020")),
    "corsair_breeches": (LEGGINGS, pal("#33230f", "#e0cba3 #bfa57a #96794f #6b5433", x="#4a3020", y="#e8c25a")),
    "corsair_boots": (BOOTS, pal("#0a0604", "#6a5040 #44322a #2c1f18 #1a120d", x="#7a5a40")),
    "captains_coat": (COAT, pal("#0c0b10", "#5a5866 #3a3844 #26242e #17161d", x="#8f2a24", l="#efe7d6", y="#e8c25a", z="#4a3020")),
    "tidewatch_hat": (TRICORN, pal("#0a0f1c", "#4a628f #2f4268 #1f2d4a #141d31", x="#e8e2d0")),
    "tidewatch_coat": (COAT, pal("#0a0f1c", "#4a628f #2f4268 #1f2d4a #141d31", x="#e8e2d0", l="#efe7d6", y="#e8c25a", z="#4a3020")),
    "tidewatch_trousers": (LEGGINGS, pal("#3a3a36", "#ffffff #e6e0d0 #bdb6a4 #8f8979", x="#4a3020", y="#e8c25a")),
    "tidewatch_boots": (BOOTS, pal("#050403", "#55483c #33291f #1f1812 #120d09", x="#6a5a48")),
    "tidewatch_officer_coat": (COAT, pal("#070b16", "#3a4f7a #243556 #17233d #0e1628", x="#e8c25a", l="#efe7d6", y="#e8c25a", z="#4a3020")),
    "tidewatch_captain_hat": (TRICORN, pal("#3a3426", "#ffffff #ece7da #c9c2b0 #9a927d", x="#e8c25a")),
    "tidewatch_captain_coat": (COAT, pal("#3a3426", "#ffffff #ece7da #c9c2b0 #9a927d", x="#e8c25a", l="#ffffff", y="#e8c25a", z="#4a3020")),
}


# Details at 32x32 for the armour shapes. Keys: r = rivet / button light, R = its shadow, s = seam (dark line),
# t = trim, e = emblem, w = white glint. They are added to every palette by armour_icon().
PLATE_DETAILS = {
    "helmet": [(14, 6, 17, 17, "t"), (15, 6, 15, 17, "w"),                       # the crest, with a glint
               (6, 17, 25, 17, "s"), (6, 18, 25, 18, "t"),                       # brow band
               (7, 14, "r"), (24, 14, "r"), (7, 15, "R"), (24, 15, "R"),         # rivets
               (6, 21, 9, 21, "s"), (22, 21, 25, 21, "s"), (10, 10, 11, 10, "w")],
    "chestplate": [(15, 8, 15, 23, "w"), (16, 8, 16, 23, "s"),                   # the ridge down the middle
                   (10, 16, 21, 16, "s"), (10, 19, 21, 19, "s"), (10, 22, 21, 22, "s"),   # belly plates
                   (4, 10, 9, 10, "t"), (22, 10, 27, 10, "t"),                   # pauldron rims
                   (3, 7, "r"), (28, 7, "r"), (3, 8, "R"), (28, 8, "R"), (11, 9, "r"), (20, 9, "r"),
                   (13, 11, 18, 14, "e"), (14, 12, 15, 12, "w"), (12, 6, 19, 6, "t")],
    "leggings": [(8, 14, 13, 14, "s"), (18, 14, 23, 14, "s"),                    # thigh plates
                 (8, 19, 13, 20, "t"), (18, 19, 23, 20, "t"),                    # knee guards
                 (10, 19, "w"), (20, 19, "w"), (9, 24, 12, 24, "s"), (19, 24, 22, 24, "s"),
                 (14, 6, 17, 7, "e"), (15, 6, "w")],
    "boots": [(6, 12, 11, 12, "s"), (20, 12, 25, 12, "s"), (6, 16, 11, 16, "s"), (20, 16, 25, 16, "s"),
              (8, 9, "r"), (22, 9, "r"), (3, 21, 5, 21, "w"), (27, 21, 28, 21, "w"),
              (2, 26, 11, 26, "s"), (20, 26, 29, 26, "s")],
}
COAT_DETAILS = [(12, 12, 13, 12, "r"), (18, 12, 19, 12, "r"), (12, 16, 13, 16, "r"), (18, 16, 19, 16, "r"),   # buttons
                (15, 18, 16, 19, "e"), (15, 18, "w"),                             # belt buckle
                (2, 9, 7, 9, "t"), (24, 9, 29, 9, "t"),                           # cuffs
                (4, 5, 7, 5, "e"), (24, 5, 27, 5, "e"),                           # epaulettes
                (10, 21, 10, 22, "s"), (21, 21, 21, 22, "s"), (15, 20, 16, 23, "s")]
CLOTH_LEG_DETAILS = [(14, 6, 17, 7, "e"), (15, 6, "w"), (10, 12, 10, 17, "s"), (21, 12, 21, 17, "s"),
                     (8, 20, 13, 20, "s"), (18, 20, 23, 20, "s"), (9, 22, "r"), (22, 22, "r")]
TRICORN_DETAILS = [(21, 12, 23, 14, "e"), (22, 12, "w"),                          # cockade
                   (2, 18, 5, 18, "t"), (26, 18, 29, 18, "t"), (11, 10, 12, 10, "w")]
BOOT_DETAILS = [(6, 14, 11, 14, "s"), (20, 14, 25, 14, "s"), (7, 10, 8, 11, "e"), (22, 10, 23, 11, "e"),
                (3, 21, 5, 21, "w"), (27, 21, 28, 21, "w"), (2, 26, 11, 26, "s"), (20, 26, 29, 26, "s")]


def armour_icon(rows, palette):
    """An armour icon with the details that belong to its shape."""
    p = dict(palette)
    main = [_rgb(p[k]) for k in "abcd"]
    trim = _rgb(p.get("x", p["a"]))
    p.setdefault("t", "#%02x%02x%02x" % trim)
    p.setdefault("e", p.get("y", p.get("x", p["a"])))
    p.setdefault("r", "#%02x%02x%02x" % _mix(trim, WARM, 0.45))
    p.setdefault("R", "#%02x%02x%02x" % _mix(main[3], COOL, 0.4))
    p.setdefault("s", "#%02x%02x%02x" % _mix(main[2], COOL, 0.45))
    p.setdefault("w", "#fffdf2")
    details = {id(HELMET): PLATE_DETAILS["helmet"], id(CHESTPLATE): PLATE_DETAILS["chestplate"], id(COAT): COAT_DETAILS,
               id(TRICORN): TRICORN_DETAILS}.get(id(rows))
    if details is None and rows is LEGGINGS:
        details = PLATE_DETAILS["leggings"] if p.get("plate") else CLOTH_LEG_DETAILS
    if details is None and rows is BOOTS:
        details = PLATE_DETAILS["boots"] if p.get("plate") else BOOT_DETAILS
    p.pop("plate", None)
    return icon(rows, p, details=details or ())


# ------------------------------------------------------------------ effect sprites

S = 32
WHITE_CORE, LIGHT, MID, DARK = "core", 255, 190, 125


def _hash(x, y, seed):
    h = (x * 73856093) ^ (y * 19349663) ^ (seed * 83492791)
    h ^= h >> 13
    return ((h * 0x5bd1e995) & 0xffff) / 65535.0


def _frame():
    """(tint layer, core layer): the tint layer holds grey levels, the core layer white pixels."""
    return Image.new("RGBA", (S, S), (0, 0, 0, 0)), Image.new("RGBA", (S, S), (0, 0, 0, 0))


def _put(frame, x, y, tone):
    tint, core = frame
    if not (0 <= x < S and 0 <= y < S):
        return
    if tone == WHITE_CORE:
        core.putpixel((x, y), (255, 255, 255, 255))
        tint.putpixel((x, y), (255, 255, 255, 255))
    else:
        tint.putpixel((x, y), (tone, tone, tone, 255))


def slash_frames():
    """A sword sweep, 6 frames: the arc shoots across, flares, then thins out and breaks up."""
    frames = []
    spans = [(-82, -30), (-82, 30), (-82, 82), (-55, 82), (-15, 82), (35, 82)]
    thick = [4, 8, 9, 7, 5, 3]
    dissolve = [0, 0, 0, 0.18, 0.4, 0.62]
    cx, cy, R = 16.0, 25.0, 15.5
    for f in range(6):
        fr = _frame()
        a0, a1 = spans[f]
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                r = math.hypot(dx, dy)
                ang = math.degrees(math.atan2(dx, -dy))
                if not a0 <= ang <= a1:
                    continue
                u = (ang - a0) / max(1e-6, a1 - a0)                  # 0 = tail, 1 = leading end
                taper = math.sin(math.pi * min(1.0, 0.12 + u * 0.88)) ** 0.6
                th = thick[f] * taper
                d = R - r                                            # depth below the outer edge
                if d < 0 or d > th:
                    continue
                if _hash(x // 2, y // 2, 7 + f) < dissolve[f] * (1.3 - u):
                    continue                                         # the tail breaks into pixels
                if d < min(2.2, th * 0.4) and f <= 3 and u > 0.25:
                    tone = WHITE_CORE
                elif d < th * 0.62:
                    tone = LIGHT
                elif d < th * 0.85:
                    tone = MID
                else:
                    tone = DARK
                _put(fr, x, y, tone)
        frames.append(fr)
    return frames


def burst_frames():
    """An impact star, 5 frames: a white pop, eight spikes shooting out, then only their tips."""
    frames = []
    length = [5, 11, 15, 15.5, 15.5]
    hole = [0, 0, 5, 9, 12.5]
    core = [3.2, 4.2, 0, 0, 0]
    width = [2.2, 2.6, 2.2, 1.6, 1.1]
    for f in range(5):
        fr = _frame()
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - 16, y + 0.5 - 16
                r = math.hypot(dx, dy)
                if core[f] and abs(dx) + abs(dy) <= core[f] * 1.25:
                    _put(fr, x, y, WHITE_CORE)
                    continue
                for k in range(8):
                    a = k * math.pi / 4
                    ux, uy = math.cos(a), math.sin(a)
                    L = length[f] * (1.0 if k % 2 == 0 else 0.72)
                    h = hole[f] * (1.0 if k % 2 == 0 else 0.72)
                    t = dx * ux + dy * uy
                    s = abs(-dx * uy + dy * ux)
                    if h <= t <= L:
                        w = width[f] * (1 - (t - h) / max(1e-6, L - h)) + 0.35
                        if s <= w:
                            near = (t - h) / max(1e-6, L - h)
                            tone = WHITE_CORE if (f <= 1 and near < 0.45 and s <= w * 0.55) else (LIGHT if near < 0.7 else MID)
                            _put(fr, x, y, tone)
                            break
        frames.append(fr)
    return frames


def ring_frames():
    """A shockwave, 6 frames: a thick ring that grows, thins and finally breaks into dashes."""
    frames = []
    radius = [4.5, 7.5, 10.5, 12.5, 14.2, 15.3]
    thick = [3.2, 3.0, 2.6, 2.0, 1.6, 1.2]
    for f in range(6):
        fr = _frame()
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - 16, y + 0.5 - 16
                r = math.hypot(dx, dy)
                d = radius[f] - r
                if d < 0 or d > thick[f]:
                    continue
                seg = int((math.atan2(dy, dx) + math.pi) / (2 * math.pi) * 24)
                if f >= 4 and _hash(seg, f, 3) < (0.3 if f == 4 else 0.55):
                    continue
                if f <= 1 and d < 1.2:
                    tone = WHITE_CORE
                elif d < thick[f] * 0.55:
                    tone = LIGHT
                else:
                    tone = MID if f < 4 else DARK
                _put(fr, x, y, tone)
        frames.append(fr)
    return frames


def spark_frames():
    """A twinkle, 4 frames: a four-pointed star that opens and closes."""
    frames = []
    arm = [3, 8, 13, 6]
    for f in range(4):
        fr = _frame()
        for y in range(S):
            for x in range(S):
                dx, dy = abs(x + 0.5 - 16), abs(y + 0.5 - 16)
                lo, hi = min(dx, dy), max(dx, dy)
                if hi > arm[f]:
                    continue
                w = 0.5 + 2.2 * (1 - hi / arm[f]) ** 1.5              # arms taper to one pixel
                if lo <= w:
                    tone = WHITE_CORE if hi < arm[f] * 0.45 and lo <= w * 0.6 else (LIGHT if hi < arm[f] * 0.75 else MID)
                    _put(fr, x, y, tone)
                elif dx + dy <= arm[f] * 0.42:
                    _put(fr, x, y, LIGHT)                             # small diagonal rays
        frames.append(fr)
    return frames


def beam_frames():
    """A beam segment along x, 3 frames: a white core, tinted edges, notches that flicker along it."""
    frames = []
    for f in range(3):
        fr = _frame()
        for x in range(S):
            notch_top = _hash(x // 3, f, 11) < 0.3
            notch_bot = _hash(x // 3, f, 12) < 0.3
            for y in range(S):
                d = abs(y + 0.5 - 16)
                if d <= 1.5:
                    _put(fr, x, y, WHITE_CORE)
                elif d <= 3.0:
                    _put(fr, x, y, LIGHT)
                elif d <= 4.5 and not (notch_top if y < 16 else notch_bot):
                    _put(fr, x, y, MID)
                elif d <= 5.5 and (notch_top if y >= 16 else notch_bot) and x % 3 == 1:
                    _put(fr, x, y, DARK)
        frames.append(fr)
    return frames


def bolt_frames():
    """A lightning bolt along x, 3 different shapes: a jagged white core with a tinted edge and a branch."""
    frames = []
    for f in range(3):
        fr = _frame()
        pts = [(0, 16)]
        y = 16.0
        for x in range(4, S + 1, 4):
            y = max(7, min(25, y + (_hash(x, f, 21) - 0.5) * 11))
            pts.append((x if x < S else S - 1, y if x < S else 16))
        segs = list(zip(pts, pts[1:]))
        bx = 8 + int(_hash(1, f, 22) * 12)
        by = next(p[1] for p in pts if p[0] >= bx)
        segs.append(((bx, by), (bx + 5, by + (6 if f % 2 else -6))))
        segs.append(((bx + 5, by + (6 if f % 2 else -6)), (bx + 9, by + (5 if f % 2 else -5))))
        for yy in range(S):
            for xx in range(S):
                best = 99.0
                for (x0, y0), (x1, y1) in segs:
                    vx, vy = x1 - x0, y1 - y0
                    t = max(0.0, min(1.0, ((xx + 0.5 - x0) * vx + (yy + 0.5 - y0) * vy) / max(1e-6, vx * vx + vy * vy)))
                    best = min(best, math.hypot(xx + 0.5 - (x0 + vx * t), yy + 0.5 - (y0 + vy * t)))
                if best <= 0.9:
                    _put(fr, xx, yy, WHITE_CORE)
                elif best <= 2.0:
                    _put(fr, xx, yy, LIGHT)
                elif best <= 2.8 and (xx + yy) % 2 == 0:
                    _put(fr, xx, yy, MID)
        frames.append(fr)
    return frames


def _seg(px, py, x0, y0, x1, y1):
    vx, vy = x1 - x0, y1 - y0
    t = max(0.0, min(1.0, ((px - x0) * vx + (py - y0) * vy) / max(1e-6, vx * vx + vy * vy)))
    return math.hypot(px - (x0 + vx * t), py - (y0 + vy * t)), t


def rune_frames():
    """A magic circle (lies on the ground and turns), 2 frames: two rings, tick marks, a six-pointed star."""
    frames = []
    for f in range(2):
        fr = _frame()
        tri = []
        for k in range(2):
            pts = [(16 + 9.2 * math.cos(math.radians(90 + 60 * k + 120 * j)), 16 + 9.2 * math.sin(math.radians(90 + 60 * k + 120 * j)))
                   for j in range(3)]
            tri += list(zip(pts, pts[1:] + pts[:1]))
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - 16, y + 0.5 - 16
                r = math.hypot(dx, dy)
                ang = math.degrees(math.atan2(dy, dx)) % 360
                tone = None
                if 14.3 <= r <= 15.6:
                    tone = LIGHT
                elif 10.4 <= r <= 11.3:
                    tone = MID
                elif 11.3 < r < 14.3:
                    a = (ang + (15 if f else 0)) % 30
                    if min(a, 30 - a) * math.pi / 180 * r <= 0.7:
                        tone = LIGHT if int(((ang + (15 if f else 0)) + 15) // 30) % 3 == 0 else MID
                elif r < 10.4:
                    if abs(dx) + abs(dy) <= (2.2 if f == 0 else 3.2):
                        tone = WHITE_CORE
                    else:
                        d = min(_seg(x + 0.5, y + 0.5, a[0], a[1], b[0], b[1])[0] for a, b in tri)
                        if d <= 0.62:
                            tone = LIGHT
                if tone is not None:
                    _put(fr, x, y, tone)
        frames.append(fr)
    return frames


def flame_frames():
    """A tongue of fire, 6 frames: it flares up, sways and dies down, with embers above it."""
    frames = []
    height = [13, 20, 25, 24, 17, 9]
    for f in range(6):
        fr = _frame()
        H = height[f]
        for y in range(S):
            t = (29.5 - (y + 0.5)) / H
            if not 0 <= t <= 1:
                continue
            w = 8.2 * (1 - t) ** 0.75 * min(1.0, t * 5 + 0.35)
            sway = math.sin(t * 5.2 + f * 1.1) * 2.4 * t
            for x in range(S):
                dx = abs(x + 0.5 - 16 - sway)
                if dx > w:
                    continue
                if dx <= w * 0.32 and t < 0.5:
                    tone = WHITE_CORE
                elif dx <= w * 0.62 and t < 0.78:
                    tone = LIGHT
                elif dx <= w - 1.0:
                    tone = MID
                else:
                    tone = DARK
                _put(fr, x, y, tone)
        for k in range(3):                                           # embers
            ex = 10 + int(_hash(k, f, 31) * 12)
            ey = 29 - H - 1 - int(_hash(k, f, 32) * 4)
            if ey >= 0 and f < 5:
                _put(fr, ex, ey, LIGHT if k else WHITE_CORE)
        frames.append(fr)
    return frames


def shard_frames():
    """Ice crystals bursting out, 5 frames: six sharp shards, lit on one side."""
    frames = []
    start = [1, 3, 6, 9.5, 12.5]
    length = [5, 9, 9.5, 6.5, 3.5]
    half = [2.0, 2.8, 2.6, 1.9, 1.2]
    for f in range(5):
        fr = _frame()
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - 16, y + 0.5 - 16
                if f == 0 and abs(dx) + abs(dy) <= 3:
                    _put(fr, x, y, WHITE_CORE)
                    continue
                for k in range(6):
                    a = math.radians(15 + 60 * k)
                    ux, uy = math.cos(a), math.sin(a)
                    L = length[f] * (1.0 if k % 2 == 0 else 0.75)
                    t = (dx * ux + dy * uy - start[f]) / L
                    sgn = -dx * uy + dy * ux
                    if 0 <= t <= 1:
                        w = half[f] * (t / 0.35 if t < 0.35 else (1 - t) / 0.65)
                        if abs(sgn) <= w + 0.3:
                            if abs(sgn) <= 0.5 and f <= 2 and 0.15 < t < 0.8:
                                tone = WHITE_CORE
                            else:
                                tone = LIGHT if sgn < 0 else MID
                            if abs(sgn) > w - 0.4 and sgn >= 0:
                                tone = DARK
                            _put(fr, x, y, tone)
                            break
        frames.append(fr)
    return frames


def puff_frames():
    """A puff of smoke, 6 frames: a round cloud that swells, splits into lumps and thins out (no white core)."""
    frames = []
    spread = [2, 4.5, 7, 9, 10.5, 11.5]
    lump = [5, 6, 6, 5, 4, 2.6]
    middle = [6, 7, 6, 4, 0, 0]
    for f in range(6):
        circles = [(16 + spread[f] * math.cos(math.radians(72 * k + f * 14)), 16 + spread[f] * math.sin(math.radians(72 * k + f * 14)),
                    lump[f] * (1.0 if k % 2 == 0 else 0.8)) for k in range(5)]
        if middle[f]:
            circles.append((16, 16, middle[f]))

        def inside(px, py):
            return any(math.hypot(px - cx, py - cy) <= r for cx, cy, r in circles)

        fr = _frame()
        for y in range(S):
            for x in range(S):
                if not inside(x + 0.5, y + 0.5):
                    continue
                if not inside(x + 2.5, y + 2.5):
                    tone = DARK
                elif not inside(x - 1.5, y - 1.5):
                    tone = LIGHT
                else:
                    tone = MID
                _put(fr, x, y, tone)
        frames.append(fr)
    return frames


def splash_frames():
    """A splash of water, 5 frames: a crown of drops that rises and falls over a widening ripple."""
    frames = []
    for f in range(5):
        fr = _frame()
        n = f + 1
        rx = 3 + 2.4 * n
        for y in range(S):
            for x in range(S):
                dx, dy = (x + 0.5 - 16) / rx, (y + 0.5 - 28.5) / 1.8
                d = math.hypot(dx, dy)
                if 0.72 <= d <= 1.0:
                    _put(fr, x, y, LIGHT if dy < 0 else MID)           # the ripple
        for k in range(-2, 3):
            hx = 16 + k * 1.25 * n
            hy = 27 - (5.6 * n - 0.78 * n * n) * (1 - 0.13 * abs(k))
            r = 1.9 if f < 3 else 1.3
            for y in range(S):
                for x in range(S):
                    d, t = _seg(x + 0.5, y + 0.5, hx, hy, 16 + k * 0.9 * n, min(28.0, hy + 5.5))
                    if math.hypot(x + 0.5 - hx, y + 0.5 - hy) <= r:
                        _put(fr, x, y, WHITE_CORE if f < 3 and math.hypot(x + 0.5 - hx, y + 0.5 - hy) <= r * 0.55 else LIGHT)
                    elif d <= 0.75 and f < 4:
                        _put(fr, x, y, MID if t < 0.6 else DARK)      # the trail under the drop
        frames.append(fr)
    return frames


def swirl_frames():
    """A vortex, 6 frames: three spiral arms turning around a bright centre."""
    frames = []
    for f in range(6):
        fr = _frame()
        fade = 1.0 if f < 4 else (0.7 if f == 4 else 0.45)
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - 16, y + 0.5 - 16
                r = math.hypot(dx, dy)
                if r > 15.2:
                    continue
                if r <= 2.4:
                    _put(fr, x, y, WHITE_CORE if f < 5 else LIGHT)
                    continue
                ang = math.atan2(dy, dx) - r * 0.3 - f * math.radians(24)
                a = ang % (2 * math.pi / 3)
                off = min(a, 2 * math.pi / 3 - a)
                width = (0.52 * (1 - r / 16.5) + 0.1) * fade
                if off <= width:
                    if r < 5.5 and off <= width * 0.5:
                        tone = WHITE_CORE
                    elif r < 9:
                        tone = LIGHT
                    elif r < 12.5:
                        tone = MID
                    else:
                        tone = DARK
                    _put(fr, x, y, tone)
        frames.append(fr)
    return frames


def crack_frames():
    """Cracks in the ground, 4 frames: jagged lines shoot out from the middle, glow, then fade to dashes."""
    frames = []
    lines = []
    for k in range(6):
        a = math.radians(60 * k + (_hash(k, 1, 41) - 0.5) * 30)
        pts = [(16.0, 16.0)]
        for step in range(1, 5):
            a += (_hash(k, step, 42) - 0.5) * 0.9
            r = step * 3.6 * (1.0 if k % 2 == 0 else 0.8)
            pts.append((16 + r * math.cos(a), 16 + r * math.sin(a)))
        lines.append(pts)
    reach = [0.45, 1.0, 1.0, 1.0]
    for f in range(4):
        fr = _frame()
        for y in range(S):
            for x in range(S):
                best, where = 99.0, 0.0
                for pts in lines:
                    n = len(pts) - 1
                    for i, (a, b) in enumerate(zip(pts, pts[1:])):
                        if (i + 1) / n > reach[f] + 1e-6 and i / n >= reach[f]:
                            continue
                        d, t = _seg(x + 0.5, y + 0.5, a[0], a[1], b[0], b[1])
                        if d < best:
                            best, where = d, (i + t) / n
                thick = 1.5 * (1 - where) + 0.45
                if best > thick:
                    continue
                if f == 3 and _hash(x // 2, y // 2, 43) < 0.5:
                    continue
                if best <= thick * 0.4 and f <= 1 and where < 0.6:
                    tone = WHITE_CORE
                elif best <= thick * 0.65:
                    tone = LIGHT if f < 3 else MID
                else:
                    tone = MID if f < 3 else DARK
                _put(fr, x, y, tone)
        frames.append(fr)
    return frames


def claw_frames():
    """Three claw marks, 5 frames: thin parallel arcs that rip across and break up."""
    frames = []
    spans = [(-70, -15), (-70, 40), (-70, 70), (-35, 70), (10, 70)]
    thick = [2.0, 3.2, 3.4, 2.6, 1.8]
    dissolve = [0, 0, 0, 0.25, 0.5]
    cx, cy = 16.0, 27.0
    for f in range(5):
        fr = _frame()
        a0, a1 = spans[f]
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                r = math.hypot(dx, dy)
                ang = math.degrees(math.atan2(dx, -dy))
                if not a0 <= ang <= a1:
                    continue
                u = (ang - a0) / max(1e-6, a1 - a0)
                th = thick[f] * math.sin(math.pi * min(1.0, 0.1 + u * 0.9)) ** 0.6
                for R in (24.5, 18.5, 12.5):
                    d = R - r
                    if 0 <= d <= th:
                        if _hash(x // 2, y // 2, 51 + f) < dissolve[f] * (1.3 - u):
                            break
                        tone = WHITE_CORE if (d < th * 0.45 and f <= 2 and u > 0.3) else (LIGHT if d < th * 0.75 else MID)
                        _put(fr, x, y, tone)
                        break
        frames.append(fr)
    return frames


def shield_frames():
    """A shield flash, 4 frames: a hexagon that snaps open with a honeycomb inside and fades."""
    frames = []
    size = [8, 12, 14, 15.2]
    thick = [3, 2.6, 2, 1.2]
    for f in range(4):
        fr = _frame()
        for y in range(S):
            for x in range(S):
                dx, dy = abs(x + 0.5 - 16), abs(y + 0.5 - 16)
                h = max(dx * 0.866 + dy * 0.5, dy)
                d = size[f] - h
                if d < 0:
                    continue
                if d <= thick[f]:
                    tone = WHITE_CORE if (f == 0 or (f == 1 and d < 1.1)) else (LIGHT if d < thick[f] * 0.6 else MID)
                    _put(fr, x, y, tone)
                elif f <= 2:
                    cellx, celly = (x + (4 if (y // 7) % 2 else 0)) % 8, y % 7
                    if cellx == 0 or celly == 0:
                        if f < 2 or (x + y) % 2 == 0:
                            _put(fr, x, y, MID if f == 0 else DARK)
        frames.append(fr)
    return frames


def lines_frames():
    """Speed lines, 4 frames: streaks that rush past from right to left."""
    frames = []
    rows = [(4, 14, 0), (8, 22, 5), (12, 10, 2), (15, 26, 8), (19, 16, 3), (23, 20, 6), (27, 12, 1)]
    for f in range(4):
        fr = _frame()
        for (y0, L, off) in rows:
            head = 31 - f * 7 - off
            for x in range(S):
                t = (x - head) / L
                if not 0 <= t <= 1:
                    continue
                tone = WHITE_CORE if t < 0.18 else (LIGHT if t < 0.5 else (MID if t < 0.8 else DARK))
                _put(fr, x, y0, tone)
                if L >= 18 and t < 0.6:
                    _put(fr, x, y0 + 1, LIGHT if t < 0.3 else MID)
        frames.append(fr)
    return frames


VFX = {"slash": slash_frames, "burst": burst_frames, "ring": ring_frames, "spark": spark_frames,
       "beam": beam_frames, "bolt": bolt_frames, "rune": rune_frames, "flame": flame_frames, "shard": shard_frames,
       "puff": puff_frames, "splash": splash_frames, "swirl": swirl_frames, "crack": crack_frames, "claw": claw_frames,
       "shield": shield_frames, "lines": lines_frames}


# ------------------------------------------------------------------ drinks, bombs and small items
# l m n o = the main colour from light to shadow; w = glint; g G = glass; k = cork; other letters per item.

FLASK = [
    "......####......",
    "......#kk#......",
    ".....######.....",
    "......#gG#......",
    "......#gG#......",
    ".....#ggGG#.....",
    "....#gggGGG#....",
    "...#lllllmmm#...",
    "..#wlmmmmmmno#..",
    "..#wmmmmmmmno#..",
    "..#lmmmmmmnno#..",
    "..#mmmmmmnnoo#..",
    "...#mmmnnnoo#...",
    "....#nnnooo#....",
    ".....######.....",
    "................",
]

BOTTLE = [
    "......####......",
    "......#kk#......",
    "......#gG#......",
    "......#gG#......",
    ".....#ggGG#.....",
    "....#llmmno#....",
    "....#wmmmno#....",
    "....#wmmmno#....",
    "....#yyyyyY#....",
    "....#yzzzyY#....",
    "....#yyyyyY#....",
    "....#lmmmno#....",
    "....#mmmnno#....",
    "....#mmnnoo#....",
    "....########....",
    "................",
]

SHAKE = [
    "...........rw...",
    "..........rw....",
    ".....cc..rw.....",
    "....#cC#rw......",
    "...##ww####.....",
    "..#wwwwwwwW#....",
    "..#WwwwwwWW#....",
    "..##########....",
    "...#gllllmG#....",
    "...#gwlllmG#....",
    "...#gwlllmG#....",
    "....#gllmG#.....",
    "....#gllmG#.....",
    "....#gllmG#.....",
    "....######......",
    "................",
]

BOMB = [
    "..........sS....",
    ".........f......",
    "........f.......",
    "......####......",
    ".....#kkkk#.....",
    "....########....",
    "...#llmmmmno#...",
    "..#lwmmmmmmno#..",
    "..#lwmmmmmmno#..",
    "..#lmmmmmmmno#..",
    "..#mmmmmmmnno#..",
    "..#mmmmmmnnoo#..",
    "...#mmmnnnoo#...",
    "....#nnnooo#....",
    ".....######.....",
    "................",
]

BALL = [
    "................",
    "................",
    ".....######.....",
    "...##llmmmn##...",
    "..#llwmmmmmno#..",
    ".#llwwmmmmmmno#.",
    ".#lwwmmmmmmmno#.",
    ".#lmmmmmmmmnno#.",
    ".#mmmmmmmmmnno#.",
    ".#mmmmmmmmnnoo#.",
    ".#mmmmmmmnnnoo#.",
    "..#mmmmnnnnoo#..",
    "...##nnnooo##...",
    ".....######.....",
    "................",
    "................",
]

CONCH = [
    "................",
    "...........##...",
    ".........##lm#..",
    ".......##llmm#..",
    ".....##llmmmn#..",
    "....#llmlmmnn#..",
    "...#llmllmnno#..",
    "..#lllmlmmnoo#..",
    "..#llmllmnnoo#..",
    "..#lmllmmnoo#...",
    "...#mlmmnnoo#...",
    "...#ppmnnoo#....",
    "..#pPp#ooo#.....",
    "..#ppp####......",
    "...###..........",
    "................",
]


def ramp(colour, **extra):
    c = _rgb(colour)
    hexa = lambda t: "#%02x%02x%02x" % t
    p = {"#": hexa(_mix(c, COOL, 0.78)), "l": hexa(_mix(c, WARM, 0.38)), "m": colour, "n": hexa(_mix(c, COOL, 0.24)),
         "o": hexa(_mix(c, COOL, 0.46)), "w": "#fffdf2", "g": "#eef8fa", "G": "#bcd6dd", "k": "#b9824a"}
    p.update(extra)
    return p


FLAME = [(7, 8, "e"), (6, 9, "e"), (7, 9, "E"), (8, 9, "e"), (6, 10, "e"), (7, 10, "E"), (8, 10, "e")]
FLAKE = [(7, 8, "e"), (6, 9, "e"), (7, 9, "E"), (8, 9, "e"), (7, 10, "e"), (5, 9, "e"), (9, 9, "e"), (7, 7, "e"), (7, 11, "e")]
CLOUD = [(7, 8, "e"), (8, 8, "e"), (6, 9, "e"), (7, 9, "e"), (8, 9, "e"), (9, 9, "E"), (7, 10, "E"), (8, 10, "E")]
FUSE = dict(f="#8a6a44", s="#ffe680", S="#fff7c2", k="#3a3d42")

MISC = {  # name: (drawing, palette, edits)
    "healing_tonic": (FLASK, ramp("#e84393"), []),
    "gale_grog": (FLASK, ramp("#4dd0e1", k="#7a5630"), []),
    "rum": (BOTTLE, ramp("#8a4416", y="#ecd9a8", Y="#c7ae78", z="#6b4a22"), []),
    "seafoam_milkshake": (SHAKE, ramp("#9fe6d2", r="#e23b3b", c="#e23b3b", C="#9c1f2a", W="#d9e6e2"), []),
    "smoke_bomb": (BOMB, ramp("#7a8087", e="#e8ecef", E="#b9c0c6", **FUSE), CLOUD),
    "frost_bomb": (BOMB, ramp("#6cc4f2", e="#ffffff", E="#d6f3ff", **FUSE), FLAKE),
    "fire_bomb": (BOMB, ramp("#b02a1e", e="#ffb23d", E="#fff0a0", **FUSE), FLAME),
    "cannonball": (BALL, ramp("#4a4f58"), []),
    "echo_conch": (CONCH, ramp("#dc9f72", l="#fff0dc", p="#f29bb0", P="#c7607e"), []),
}
