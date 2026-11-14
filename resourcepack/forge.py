"""The weapons, drawn at full 32x32 and turned into 3D models, plus the chest textures.

Every weapon is described in its own frame: s runs along the weapon from the pommel (bottom-left of the icon) to
the point (top-right), t across it (negative = the lit, upper-left side). A weapon is a function (s, t) -> part:
  a b c d  blade / head, from the lit side to the shaded side      g G  guard, fittings, bindings
  h        grip (wrapped in two tones by the shader)               p P  pommel and gems
  x        glowing core, runes, crystals                           T    teeth, spikes, coral
pixelart.shade() then lights the parts (bevels, colour shift along the blade, coloured outline), and voxels()
gives every pixel its own thickness, so in the hand the weapon is a real 3D object: a thick guard and pommel,
a thin blade, gems that stand out.
"""
import math

from PIL import Image

import pixelart

R2 = math.sqrt(2)
OX, OY = 3.0, 29.0


def frame(x, y):
    dx, up = x + 0.5 - OX, OY - (y + 0.5)
    return (dx + up) / R2, (dx - up) / R2


def build(fn):
    grid = [[fn(*frame(x, y)) for x in range(32)] for y in range(32)]
    out = [row[:] for row in grid]
    for y in range(32):
        for x in range(32):
            if grid[y][x] is None and any(0 <= x + dx < 32 and 0 <= y + dy < 32 and grid[y + dy][x + dx] is not None
                                          for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out[y][x] = "#"
    return out


def bands(tt):
    return "a" if tt < -0.5 else "b" if tt < 0.2 else "c" if tt < 0.7 else "d"


def sword(L=37.0, wb=2.6, tip=7.0, profile="straight", curve=0.0, guard=5.5, horns=0.0, teeth=0.0, core=None,
          clip=False, cup=False, g0=10.0):
    b0 = g0 + 2.4

    def fn(s, t):
        d = math.hypot(s - 1.6, t)
        if d <= 2.1:
            return "P" if d <= 0.95 else "p"
        if 3.0 <= s < g0 and abs(t) <= 1.25:
            return "h"
        gc = g0 + 1.2
        if cup and math.hypot(s - gc, t) <= 3.7:
            return "g" if math.hypot(s - gc, t) > 2.5 else ("P" if math.hypot(s - gc, t) <= 1.0 else "G")
        if g0 <= s < b0:
            if abs(t) <= guard - 0.6 * abs(s - gc):
                if math.hypot(s - gc, t) <= 1.2:
                    return "P"
                return "G" if s < gc else "g"
        if horns and b0 <= s <= b0 + horns:
            reach = horns * (1 - abs(abs(t) - (guard - 1.2)) / 1.2)
            if abs(t) <= guard and s - b0 <= reach:
                return "g"
        if b0 <= s <= L:
            u = (s - b0) / (L - b0)
            c = curve * u * u
            w = wb
            if profile == "leaf":
                w = wb * (0.7 + 0.5 * math.sin(math.pi * min(1.0, u * 1.15)))
            elif profile == "flare":
                w = wb * (0.75 + 0.85 * u)
            rem = L - s
            lo, hi = -w, w
            if rem < tip:
                k = (rem / tip) ** 0.8
                if clip:
                    lo = w - 2 * w * k
                else:
                    lo, hi = -w * k, w * k
            dt = t - c
            if lo <= dt <= hi:
                if core and abs(dt - (lo + hi) / 2) <= 0.6 and rem > tip * 0.55 and core(s):
                    return "x"
                return bands((dt - lo) / max(1e-6, hi - lo) * 2 - 1)
            if teeth and dt > hi and rem > 2.5:
                ph = (s - b0) % teeth
                h = 2.4 * (1 - ph / (teeth * 0.7)) if ph < teeth * 0.7 else 0
                if dt - hi <= h:
                    return "T"
        return None
    return fn


def maul(s, t):
    if s < 2.2 and abs(t) <= 1.9:
        return "p"
    if s <= 22.5 and abs(t) <= 1.2:
        return "g" if (8 <= s < 9.6 or 15 <= s < 16.6) else "h"
    if 22 <= s <= 32.5 and abs(t) <= 7:
        if abs(t) > 5.5:
            return "g" if t < 0 else "G"                       # the striking faces
        dd = abs(s - 27.2) + abs(t)
        if dd <= 2.6:
            return "P" if dd <= 1.1 else "x"                   # the eye of the kraken
        if abs(s - 22.7) < 0.8 or abs(s - 31.8) < 0.8:
            return "d"
        return bands(t / 5.5)
    for off in (-3.6, 0.0, 3.6):                               # spikes on top
        if 32.5 < s <= 35.6 and abs(t - off) <= (35.6 - s) * 0.5:
            return "T"
    return None


def harpoon(s, t):
    if s <= 29 and abs(t) <= 1.0:
        return "g" if (5 <= s < 7.5 or 23.5 <= s < 26.5) else "h"
    if 29 < s <= 39.5:
        u = (s - 29) / 10.5
        w = 2.6 * (0.5 + u * 2.0) if u < 0.25 else 2.6 * ((1 - u) / 0.75) ** 0.7
        if abs(t) <= w:
            return "x" if abs(t) < 0.5 and 0.15 < u < 0.6 else bands(t / max(w, 0.01))
    if 25.5 <= s <= 32 and abs(t) > 1.0 and (s - 29.8) * 1.5 + 1.0 <= abs(t) <= (s - 25.5) * 0.85 + 0.9 and abs(t) <= 5.2:
        return "a" if t < 0 else "c"                           # the barbs
    return None


def baton(s, t):
    if s < 2.2 and abs(t) <= 1.9:
        return "p"
    if s <= 12 and abs(t) <= 1.3:
        return "h"
    if 12 < s <= 31 and abs(t) <= 1.6:
        if 12 < s <= 13.6 or 20.5 <= s < 22 or 29.4 <= s <= 31:
            return "g"
        return "x" if abs(t) < 0.5 and int(s) % 3 == 0 else bands(t / 1.6)
    d = math.hypot(s - 34.2, t)
    if d <= 3.1:
        return "P" if d <= 1.2 else ("x" if d <= 2.2 else "g")
    if 30.5 <= s <= 33 and 1.6 < abs(t) <= 2.8:
        return "g"
    return None


def pickaxe(s, t):
    if s <= 29.5 and abs(t) <= 1.05:
        return "p" if s < 1.8 else ("g" if 22 <= s < 23.5 else "h")
    cen = 31.6 - 0.052 * t * t
    hw = 2.2 * (1 - (abs(t) / 13.6) ** 2) + 0.45
    if abs(t) <= 13.6 and abs(s - cen) <= hw:
        if abs(t) < 2.3:
            return "g"
        if abs(abs(t) - 6.2) < 0.9 and abs(s - cen) < 0.9:
            return "x"
        return bands(-(s - cen) / hw)
    return None


def pal(outline, main, guard, grip, gem, x, T="#efe6cf"):
    p = {"#": outline, "x": x, "T": T}
    p.update(zip("abcd", main.split()))
    p.update(zip("gG", guard.split()))
    p.update(zip("hH", grip.split()))
    p.update(zip("pP", gem.split()))
    return p


# name: (shape, palette, colour the blade shifts to at the point, thickness overrides)
WEAPONS = {
    "storm_saber": (sword(L=38, wb=2.5, tip=8, guard=5.2, horns=2.6, core=lambda s: int(s * 0.5) % 3 != 0),
                    pal("#0d1433", "#eaf6ff #8fc0f5 #4a7fd0 #2a4a96", "#ffd84a #b8842a", "#39508a #1f2c56", "#b8842a #fff3a0", "#fff06a"),
                    "#ffffff", {}),
    "nightbloom": (sword(L=37, wb=2.3, tip=8, profile="leaf", guard=4.6, horns=3.2, core=lambda s: True),
                   pal("#16071f", "#c58ae0 #7a3fa8 #48206e #2a1146", "#ff8fc0 #a83a78", "#3a1d52 #1f0f30", "#a83a78 #ffd0e6", "#ff9ed0"),
                   "#ffd9f0", {}),
    "tidecleaver": (sword(L=36, wb=3.7, tip=6.5, clip=True, guard=4.8, core=lambda s: int(s) % 5 < 3),
                    pal("#101c2b", "#f4fbff #b4cee0 #7393ae #47617c", "#6fd0ff #2f7fb8", "#2d4a6b #182c44", "#2f7fb8 #d8f6ff", "#7fe6ff"),
                    "#ffffff", {}),
    "leviathans_fang": (sword(L=38, wb=2.7, tip=9, guard=5.6, horns=3.0, teeth=3.6, core=lambda s: True),
                        pal("#0c0507", "#6a5a66 #3c3540 #26212b #16121a", "#8a2a2a #4a1216", "#3a2a2e #1e1518", "#4a1216 #ff7a5a", "#e8322e"),
                        "#8a7a86", {}),
    "tide_rapier": (sword(L=39.5, wb=1.15, tip=11, guard=3.0, cup=True),
                    pal("#0b2630", "#f2fffb #9eeedd #49cfae #1f8f7a", "#ffd65a #b88a1e", "#1f5f8f #123c5e", "#b88a1e #c9fff0", "#c9fff0"),
                    "#ffffff", {"g": 3.4, "G": 2.6}),
    "abyss_cutlass": (sword(L=34, wb=2.1, tip=6, profile="flare", curve=-2.6, guard=4.2, horns=2.0, core=lambda s: int(s) % 4 == 0),
                      pal("#04181a", "#9af2e2 #36a898 #1b6e69 #0d3c3f", "#24d8b8 #128070", "#2b2f33 #15171a", "#128070 #c8fff3", "#d8fff7"),
                      "#d8fff7", {}),
    "coral_scimitar": (sword(L=34, wb=1.9, tip=7, profile="flare", curve=-3.6, guard=4.0, teeth=4.2),
                       pal("#3a0f1e", "#ffe0d0 #ff9d8a #e65c5c #a2364c", "#ffd98a #c7903a", "#7a4a2e #4a2816", "#c7903a #fff1e6", "#fff1e6",
                           T="#ffc2a8"),
                       "#fff4ea", {}),
    "krakens_maul": (maul, pal("#150a2b", "#b891ea #7c4fc4 #52309a #331c66", "#c9c9dc #6e6e88", "#8a6245 #4f3524", "#6e6e88 #eafffb", "#6ff5e0",
                               T="#e6dcf5"),
                     None, {"a": 5.0, "b": 5.0, "c": 5.0, "d": 5.0, "g": 5.6, "G": 5.6, "x": 5.6, "P": 6.0, "h": 1.6, "T": 2.0}),
    "harpoon": (harpoon, pal("#1f1309", "#f6fafc #c2ccd3 #8996a0 #5a6670", "#e9d9a6 #a88c50", "#b07a45 #68411f", "#a88c50 #e9d9a6", "#ffffff"),
                None, {"g": 2.0, "h": 1.5}),
    "abyssal_baton": (baton, pal("#04181a", "#8ff5e6 #3fa79a #1c5f58 #0f3a38", "#e8f0f0 #8fa6a6", "#4a5058 #262a2f", "#8fa6a6 #e8fffb", "#b8fff3"),
                      None, {"a": 2.2, "b": 2.2, "c": 2.2, "d": 2.2, "x": 2.4, "g": 3.0, "P": 3.6}),
    "deepcore_pickaxe": (pickaxe, pal("#08203a", "#d8fbff #62d6f2 #2a8fc0 #18568a", "#f2d27a #a8842e", "#9a7650 #563e28", "#a8842e #f2d27a", "#ffffff"),
                         None, {"a": 1.4, "b": 1.4, "c": 1.4, "d": 1.4, "x": 2.2, "g": 2.4}),
}

DEPTH = {"#": 0.5, "a": 0.7, "b": 1.1, "c": 1.1, "d": 0.7, "x": 1.3, "T": 0.7, "g": 2.2, "G": 2.2, "h": 1.5, "H": 1.5,
         "p": 2.2, "P": 2.6}


def make(name):
    """-> (32x32 image, part grid, thickness overrides) of a weapon."""
    fn, palette, tip, depth = WEAPONS[name]
    grid = build(fn)
    return pixelart.shade(grid, palette, tip=tip), grid, depth


def voxels(grid, depth=None):
    """Model elements for a part grid: every pixel gets the thickness of its part."""
    d = dict(DEPTH)
    d.update(depth or {})
    return boxes([[None if k is None else d.get(k, 1.0) for k in row] for row in grid])


def boxes(depths):
    """Model elements for a 32x32 map of thicknesses (None = empty): every run of pixels of the same thickness is
    a box, textured with its own pixels on the front and back and with its edge pixels on the sides."""
    els = []
    for y in range(32):
        x = 0
        while x < 32:
            dep = depths[y][x]
            if dep is None:
                x += 1
                continue
            x0 = x
            while x < 32 and depths[y][x] == dep:
                x += 1
            u0, u1, v0, v1 = x0 / 2, x / 2, y / 2, (y + 1) / 2
            face = lambda uv: {"uv": uv, "texture": "#t"}
            els.append({"from": [u0, 16 - v1, 8 - dep / 2], "to": [u1, 16 - v0, 8 + dep / 2], "faces": {
                "south": face([u0, v0, u1, v1]), "north": face([u1, v0, u0, v1]),
                "up": face([u0, v0, u1, v1]), "down": face([u0, v0, u1, v1]),
                "west": face([u0, v0, u0 + 0.5, v1]), "east": face([u1 - 0.5, v0, u1, v1])}})
    return els


def puff(images, cap=5.0):
    """A rounded body for an icon (or the frames of an animated one): the further a pixel is from the edge of
    the shape, the thicker it is, so round things bulge like the real object. -> thickness map for boxes()."""
    if not isinstance(images, list):
        images = [images]
    solid = [[any(im.getpixel((x, y))[3] > 0 for im in images) for x in range(32)] for y in range(32)]
    dist = [[0 if not solid[y][x] else 99 for x in range(32)] for y in range(32)]
    for _ in range(16):                                           # distance to the nearest empty pixel
        for y in range(32):
            for x in range(32):
                if dist[y][x]:
                    near = min(dist[yy][xx] if 0 <= xx < 32 and 0 <= yy < 32 else 0
                               for xx, yy in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)))
                    dist[y][x] = min(dist[y][x], near + 1)
    return [[None if not solid[y][x] else min(cap, round((0.8 + 1.9 * math.sqrt(dist[y][x] - 1)) * 2) / 2)
             for x in range(32)] for y in range(32)]


CARRIED = {  # the same hold as Minecraft's own flat items (item/generated), a little bigger
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3.4, 1.2], "scale": [0.7, 0.7, 0.7]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
}


def round_model(texture, images, cap=5.0):
    return {"gui_light": "front", "textures": {"t": texture, "particle": texture}, "elements": boxes(puff(images, cap)),
            "display": CARRIED}


HELD = {  # Minecraft's own sword hold (item/handheld), but half as big again; the grip stays in the hand
    "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 7.3, 0.08], "scale": [1.3, 1.3, 1.3]},
    "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 7.3, 0.08], "scale": [1.3, 1.3, 1.3]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 4.02, 1.47], "scale": [0.8, 0.8, 0.8]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 4.02, 1.47], "scale": [0.8, 0.8, 0.8]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
}


SMALL = dict(HELD)  # small things held like a tool (keys): well under the size of a sword
SMALL.update({
    "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 1.8, 0.78], "scale": [0.55, 0.55, 0.55]},
    "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 1.8, 0.78], "scale": [0.55, 0.55, 0.55]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.6, 0.6, 0.6]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.6, 0.6, 0.6]},
})


def model(texture, grid, depth=None, small=False):
    return {"gui_light": "front", "textures": {"t": texture, "particle": texture}, "elements": voxels(grid, depth),
            "display": SMALL if small else HELD}


# ------------------------------------------------------------------ chest textures (32 px, clean pixel art)

def _h(x, y, seed):
    return pixelart._hash(x, y, seed)


def planks(wood, dark, seed, vertical=False, px=32):
    """Planks 8 px wide: a lit top edge, a shaded lower edge, a dark seam, a few grain streaks, board joints with nails."""
    mix, warm, cool = pixelart._mix, pixelart.WARM, pixelart.COOL
    light, low, seam = mix(wood, warm, 0.2), mix(wood, cool, 0.2), mix(dark, cool, 0.35)
    nail, glint = mix(dark, cool, 0.6), mix(wood, warm, 0.5)
    img = Image.new("RGBA", (px, px))
    p = img.load()
    for y in range(px):
        for x in range(px):
            a, b = (x, y) if vertical else (y, x)
            board, r = a // 8, a % 8
            c = wood
            if r == 0:
                c = light
            elif r == 7:
                c = seam
            elif r == 6:
                c = low
            elif _h(board * 9 + r, b // 5, seed) < 0.28 and b % 5 < 4:
                c = low if _h(board, r, seed + 2) < 0.65 else light
            joint = 5 + int(_h(board, 3, seed + 5) * 20)
            if b % 32 == joint:
                c = seam
            elif b % 32 == joint + 1 and r not in (0, 7):
                c = light
            if r in (3, 4) and b % 32 in (joint - 3, joint + 4):
                c = nail if r == 4 else glint
            p[x, y] = c + (255,)
    return img


def plate(metal, dark, seed, rivets=True, px=32):
    """Metal: broad brushed bands in three tones; rivets (a light and a dark pixel) where the chest's bands run."""
    mix, warm, cool = pixelart._mix, pixelart.WARM, pixelart.COOL
    hi, lo = mix(metal, warm, 0.35), mix(metal, cool, 0.25)
    img = Image.new("RGBA", (px, px))
    p = img.load()
    for y in range(px):
        for x in range(px):
            r = (y + (x // 16) * 3) % 8
            c = hi if r == 0 else (lo if r in (5, 6) else metal)
            if rivets and x in (6, 7, 24, 25) and y % 6 in (2, 3):
                c = hi if (x in (6, 24) and y % 6 == 2) else mix(dark, cool, 0.3)
            p[x, y] = c + (255,)
    return img


def lock(metal, dark, gem, glow, frame, px=32):
    """The lock plate: a bevelled plate with a round gem in a bezel (it pulses over 8 frames) and a keyhole."""
    mix, warm, cool = pixelart._mix, pixelart.WARM, pixelart.COOL
    hi, lo, edge = mix(metal, warm, 0.4), mix(metal, cool, 0.3), mix(dark, cool, 0.45)
    pulse = (0, 1, 2, 3, 4, 3, 2, 1)[frame % 8] / 4.0
    img = Image.new("RGBA", (px, px))
    p = img.load()
    for y in range(px):
        for x in range(px):
            e = min(x, y, px - 1 - x, px - 1 - y)
            c = metal
            if e == 0:
                c = edge
            elif e <= 2:
                c = hi if (x <= 2 or y <= 2) and not (x >= px - 3 or y >= px - 3) else lo
            d = math.hypot(x + 0.5 - 16, y + 0.5 - 12)
            if d <= 8:
                if d > 6.2:
                    c = hi if (x + y) < 28 else lo                    # the bezel
                else:
                    c = mix(gem, glow, 0.15 + 0.5 * pulse) if d > 3.4 else mix(gem, glow, 0.45 + 0.5 * pulse)
                    if (x + 0.5 - 16) + (y + 0.5 - 12) > 4.5:
                        c = mix(gem, cool, 0.3)                       # the gem's shaded side
                    if (x, y) in ((13, 9), (14, 9), (13, 10)):
                        c = (255, 255, 255)
            if (abs(x + 0.5 - 16) <= 1 and 22 <= y <= 27) or math.hypot(x + 0.5 - 16, y + 0.5 - 22.5) <= 2.1:
                c = (16, 10, 8)                                       # the keyhole
            if (x in (4, px - 5)) and (y in (4, px - 5)):
                c = hi
            p[x, y] = c + (255,)
    return img


# ------------------------------------------------------------------ chests, modern style (16 px)

def _tones(wood, metal):
    mix, warm, cool = pixelart._mix, pixelart.WARM, pixelart.COOL
    return {"w1": mix(wood, warm, 0.13), "w2": wood, "w3": mix(wood, cool, 0.14), "w4": mix(wood, cool, 0.34),
            "m1": mix(metal, warm, 0.42), "m2": metal, "m3": mix(metal, cool, 0.26), "m4": mix(metal, cool, 0.5)}


def _plank(c, r, rows, along, board, seed):
    """One pixel of a plank that is "rows" high: a soft light top row, a dark line under it, a few short streaks."""
    if r == 0:
        return c["w1"]
    if r == rows - 1:
        return c["w4"]
    if along == 3 + int(_h(board, 1, seed) * 9):
        return c["w3"]                                              # where two boards meet
    if _h(board * 5 + r, along // 3, seed + 2) < 0.22:
        return c["w3"] if _h(board, along // 3, seed + 3) < 0.7 else c["w1"]
    return c["w2"]


def chest_side(wood, metal, seed):
    c = _tones(wood, metal)
    img = Image.new("RGBA", (16, 16))
    p = img.load()
    for y in range(16):
        for x in range(16):
            if y in (5, 6):                                         # the lid's rim
                k = c["m1"] if y == 5 else c["m3"]
                if x in (0, 15):
                    k = c["m4"]
            elif x in (0, 15) or y in (0, 15):                      # the frame: a dark outer line ...
                k = c["m4"] if (x in (0, 15) or y == 15) else c["m3"]
            elif x in (1, 14):                                      # ... and the corner posts
                k = c["m1"] if x == 1 else c["m2"]
                if y in (2, 9, 13):
                    k = c["m4"]                                     # rivets
                elif y in (3, 10, 14):
                    k = c["m1"]
            elif y < 5:
                k = _plank(c, (y - 1) % 2, 2, x, 10 + (y - 1) // 2, seed) if y > 1 else _plank(c, 0, 4, x, 9, seed)
                if y == 4:
                    k = c["w3"]
            else:
                k = _plank(c, (y - 7) % 4, 4, x, (y - 7) // 4, seed)
            p[x, y] = k + (255,)
    return img


def chest_top(wood, metal, seed, bottom=False):
    c = _tones(wood, metal)
    if bottom:
        c = {k: pixelart._mix(v, pixelart.COOL, 0.3) for k, v in c.items()}
    img = Image.new("RGBA", (16, 16))
    p = img.load()
    for y in range(16):
        for x in range(16):
            e = min(x, y, 15 - x, 15 - y)
            if e == 0:
                k = c["m4"]
            elif e == 1:
                k = c["m1"] if (x == 1 or y == 1) else c["m3"]
                if (x in (1, 14)) and (y in (1, 14)):
                    k = c["m1"]
            elif x in (7, 8) and not bottom:                         # a strap over the lid
                k = c["m2"] if x == 7 else c["m3"]
                if y in (4, 11):
                    k = c["m1"]
            else:
                k = _plank(c, (x - 2) % 4, 4, y, 20 + (x - 2) // 4, seed)
            p[x, y] = k + (255,)
    return img


def chest_latch(metal, gem, glow, frame):
    """4 x 5 pixels in the corner of a 16 px frame: a plate, a gem that pulses, a keyhole."""
    c = _tones(metal, metal)
    pulse = (0, 1, 2, 3, 4, 3, 2, 1)[frame % 8] / 4.0
    g1, g2 = pixelart._mix(gem, glow, 0.35 + 0.6 * pulse), pixelart._mix(gem, pixelart.COOL, 0.25 - 0.2 * pulse)
    rows = ["1223",
            "2gG3",
            "2Gh3",
            "22k3",
            "3334"]
    key = {"1": c["m1"], "2": c["m2"], "3": c["m3"], "4": c["m4"], "g": g1, "G": pixelart._mix(gem, glow, 0.15 + 0.4 * pulse),
           "h": g2, "k": (20, 14, 12)}
    img = Image.new("RGBA", (16, 16), c["m2"] + (255,))
    p = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            p[x, y] = key[ch] + (255,)
    return img
