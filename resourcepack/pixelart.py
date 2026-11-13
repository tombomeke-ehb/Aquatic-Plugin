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


def icon(rows, palette, edits=()):
    """A 32x32 icon from a 16x16 drawing; edits = (x, y, key) changes to the drawing (accents per weapon)."""
    rows = [list(r) for r in rows]
    for x, y, ch in edits:
        rows[y][x] = ch
    return scale2x(sprite(["".join(r) for r in rows], palette))


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


WEAPONS = {
    "storm_saber": (SWORD, pal("#141a33", "#f4fbff #b9dcf5 #6fa3d8 #3f6aa8", "#ffe066 #c9972a", "#3d4f7a #27335a", "#ffe066 #fff7c2",
                               x="#ffe066"), [(12, 3, "x"), (11, 4, "x"), (11, 5, "x"), (10, 6, "x"), (9, 6, "x")]),
    "nightbloom": (SWORD, pal("#1c0b2b", "#ffd9ec #e58fc2 #a34f9c #5d2a78", "#f48fb1 #b8477e", "#3a1d52 #241135", "#ff9ec4 #ffe3f0",
                              x="#ffe3f0"), [(12, 2, "x"), (10, 4, "x"), (8, 6, "x")]),
    "tidecleaver": (CLEAVER, pal("#152233", "#ffffff #d5e3ec #9fb4c4 #66798c", "#8fd3ff #4a8fc2", "#2d4a6b #1b2f47", "#9fe3ff #e6f9ff"), []),
    "leviathans_fang": (FANG, pal("#140406", "#ff8f7a #c23b32 #7a1c1c #3d0c10", "#6a6a78 #3a3a46", "#4a1414 #2a0a0a", "#ff5a44 #ffc0b0"), []),
    "tide_rapier": (RAPIER, pal("#0e2a33", "#f0fffb #8fe6d6 #43cea2 #1f8f7a", "#f2c84b #b88a1e", "#1f5f8f #143f63", "#43cea2 #c9fff0"), []),
    "abyss_cutlass": (CUTLASS, pal("#071c1e", "#b5fff0 #4fc7b5 #23807a #12494a", "#2fe0c0 #178f80", "#2b2f33 #17191c", "#00c9a7 #b5fff0"), []),
    "coral_scimitar": (CUTLASS, pal("#3a1220", "#fff1e6 #ffb59e #f0706a #b83f54", "#f6d28a #c79a45", "#7a4a2e #4f2c1a", "#ff8f7a #fff1e6",
                                    x="#fff1e6"), [(11, 3, "x"), (10, 5, "x")]),
    "krakens_maul": (MAUL, pal("#1a0d33", "#e2c8ff #a678e0 #6b3fb0 #3d2073", "#b0b0c4 #6a6a82", "#8a6245 #55392a", x="#7dfff0"), []),
    "harpoon": (HARPOON, pal("#24160c", "#f6fafc #c2ccd3 #8996a0 #5f6b75", "#e8d8a8 #b89e62", "#b07a45 #6e4524"), []),
    "abyssal_anchor": (ANCHOR, pal("#06201f", "#a8fff0 #3fb8a8 #1f7a72 #0f4a48"), []),
    "abyssal_baton": (BATON, pal("#071c1e", "#8ff5e6 #3fa79a #1c5f58 #0f3a38", "#e8f0f0 #9fb3b3", "#4a5058 #2a2e33", "#7dfff0 #e0fffb"), []),
    "deepcore_pickaxe": (PICKAXE, pal("#0c2238", "#d6fbff #5fd3f0 #2a8fc0 #1a5a8a", None, "#9a7650 #5c432c"), []),
    "key_sailor": (KEY, pal("#1d2a33", "#ffffff #d7e3ea #9aabb8 #6a7a88", None, None, "#3fb8c9 #bff6ff"), []),
    "key_captain": (KEY, pal("#3a2408", "#fff3b0 #f2c230 #c98f1a #8f5f10", None, None, "#c0392b #ffb3a6"), []),
    "key_kraken": (KEY, pal("#120a26", "#cbb8ee #8f72c8 #5a4296 #33245e", None, None, "#43e0c8 #c9fff5"), []),
    "abyssal_shackles": (SHACKLES, pal("#06201f", "#a8fff0 #3fb8a8 #1f7a72 #0f4a48", "#d4dde3 #7d8b96"), []),
}

STEEL = "#e4ebf1 #a3b3c2 #6f8194 #46566c"
ARMOUR = {
    "tidewarden_helmet": (HELMET, pal("#1a2230", STEEL, x="#d9b45a")),
    "tidewarden_chestplate": (CHESTPLATE, pal("#1a2230", STEEL, x="#d9b45a")),
    "tidewarden_leggings": (LEGGINGS, pal("#1a2230", STEEL, x="#8a6a34", y="#d9b45a")),
    "tidewarden_boots": (BOOTS, pal("#1a2230", STEEL, x="#d9b45a")),
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


VFX = {"slash": slash_frames, "burst": burst_frames, "ring": ring_frames, "spark": spark_frames,
       "beam": beam_frames, "bolt": bolt_frames}
