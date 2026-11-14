"""
Builds the AquaticSMP resource pack (Minecraft 1.21.4, pack format 46).

  python resourcepack/build_pack.py [version]

Draws the textures, writes the item definitions and models, and zips everything into
resourcepack/dist/AquaticSMP-ResourcePack.zip. It also prints the SHA-1 for server.properties.

The textures are drawn in code at 32x32 (twice the vanilla 16x16, which Minecraft supports): shapes are
defined mathematically and lit from the top-left, so every item gets real shading, highlights and a clean
outline. Change a colour or a shape below and run the script again.

Items use the 1.21.4 "item_model" component: items.yml sets item-model: "aquatic:<name>", which loads
assets/aquatic/items/<name>.json. Worn items (the crown) also use "equipment-model", which loads
assets/aquatic/equipment/<name>.json.
"""
import hashlib
import json
import math
import os
import sys
import zipfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pixelart  # noqa: E402
import forge  # noqa: E402
PACK = os.path.join(HERE, "pack")
DIST = os.path.join(HERE, "dist")
N = 32  # item texture size

WHITE, BLACK = (255, 255, 255), (0, 0, 0)


# ---------------------------------------------------------------- colour helpers

def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def shade(c, k):
    """k > 0 lightens towards white, k < 0 darkens towards black."""
    return mix(c, WHITE, k) if k >= 0 else mix(c, BLACK, -k)


def new():
    return Image.new("RGBA", (N, N), (0, 0, 0, 0))


def put(img, x, y, c, a=255):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), tuple(c) + (a,))


def opaque(img, x, y):
    return 0 <= x < img.width and 0 <= y < img.height and img.getpixel((x, y))[3] > 0


def outline(img, color):
    """A 1px outline around the shape (only on empty pixels next to it)."""
    out = img.copy()
    for y in range(img.height):
        for x in range(img.width):
            if not opaque(img, x, y) and any(opaque(img, x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                put(out, x, y, color)
    return out


LIGHT = (-0.55, -0.65, 0.52)
_l = math.sqrt(sum(v * v for v in LIGHT))
LIGHT = tuple(v / _l for v in LIGHT)


# ---------------------------------------------------------------- Abyss Fruits

def fruit(main, swirl, leaf="#3f9b3a", outline_col="#2a1626", phase=0.0):
    img = new()
    main, swirl, leaf = rgb(main), rgb(swirl), rgb(leaf)
    cx, cy, R = 16.0, 18.5, 11.5
    for y in range(N):
        for x in range(N):
            dx, dy = (x + 0.5 - cx) / R, (y + 0.5 - cy) / (R * 0.94)
            d2 = dx * dx + dy * dy
            if d2 > 1:
                continue
            nz = math.sqrt(1 - d2)
            lam = max(0.0, dx * LIGHT[0] + dy * LIGHT[1] + nz * LIGHT[2])
            def tone(c):
                if lam > 0.80:
                    return mix(c, (255, 246, 214), 0.30)
                if lam > 0.56:
                    return c
                if lam > 0.36:
                    return mix(c, (26, 18, 58), 0.18)
                if lam > 0.18:
                    return mix(c, (26, 18, 58), 0.36)
                return mix(c, (26, 18, 58), 0.54)
            col = tone(main)
            r = math.sqrt(d2)
            ang = math.atan2(dy, dx)
            v = (r * 2.3 - ang / (2 * math.pi) + phase) % 1.0  # the abyss swirl: a spiral over the skin
            if 0.12 < r < 0.93 and v < 0.28:
                col = tone(swirl)
            if (dx + 0.36) ** 2 + (dy + 0.42) ** 2 < 0.028:
                col = mix(col, WHITE, 0.8)  # shine
            elif (dx + 0.36) ** 2 + (dy + 0.42) ** 2 < 0.06:
                col = mix(col, WHITE, 0.35)
            put(img, x, y, col)
    # stem (curving up to the right) and a leaf with a vein
    stem = rgb("#6b4423")
    for i, (x, y) in enumerate([(15, 8), (15, 7), (16, 6), (16, 5), (17, 4), (17, 3)]):
        put(img, x, y, shade(stem, 0.15 if i % 2 else 0))
        put(img, x + 1, y, shade(stem, -0.3))
    for y in range(N):
        for x in range(N):
            u, w = x + 0.5 - 22.5, y + 0.5 - 5.0
            a = math.radians(-25)
            lu, lw = u * math.cos(a) - w * math.sin(a), u * math.sin(a) + w * math.cos(a)
            if (lu / 4.6) ** 2 + (lw / 2.2) ** 2 <= 1:
                col = shade(leaf, 0.2 if lw < 0 else -0.15)
                if abs(lw) < 0.45 and lu > -3.5:
                    col = shade(leaf, -0.4)  # vein
                put(img, x, y, col)
    return outline(img, rgb(outline_col))


# ---------------------------------------------------------------- Crown of the Conqueror

def crown():
    img = new()
    gold, pale, deep = rgb("#f2c230"), rgb("#fff2b0"), rgb("#9a6608")
    band_top, band_bot = 18, 25
    # spikes: (centre x, tip y)
    spikes = [(5.5, 11), (10.5, 8), (16, 5), (21.5, 8), (26.5, 11)]
    for sx, tip in spikes:
        for y in range(tip, band_top + 1):
            half = 2.6 * (y - tip) / (band_top - tip) + 0.6
            for x in range(N):
                off = x + 0.5 - sx
                if abs(off) <= half:
                    k = 0.35 if off < -half * 0.3 else (-0.25 if off > half * 0.4 else 0.1)
                    put(img, x, y, shade(gold, k))
        # a gold ball on top of every spike
        for y in range(tip - 3, tip + 1):
            for x in range(N):
                if (x + 0.5 - sx) ** 2 + (y + 0.5 - (tip - 1.2)) ** 2 <= 2.4:
                    hi = (x + 0.5 - sx) + (y + 0.5 - (tip - 1.2)) < -0.6
                    put(img, x, y, pale if hi else shade(gold, 0.1))
    # the band: rounded (lighter in the middle), light rim on top, dark rim below, rivets
    for y in range(band_top, band_top + 8):
        for x in range(3, 29):
            curve = 1 - abs(x + 0.5 - 16) / 13.0
            k = 0.25 * curve - 0.15 + (0.25 if y == band_top else (-0.35 if y >= band_top + 6 else 0))
            put(img, x, y, shade(gold, k))
    for x in range(4, 28, 3):
        put(img, x, band_top + 6, deep)
    # gems: a big ruby in the middle, sapphires on the sides
    def gem(cx, cy, r, color):
        c = rgb(color)
        for y in range(N):
            for x in range(N):
                if abs(x + 0.5 - cx) + abs(y + 0.5 - cy) <= r:
                    hi = (x + 0.5 - cx) + (y + 0.5 - cy) < -r * 0.3
                    put(img, x, y, shade(c, 0.5 if hi else (-0.3 if (x + 0.5 - cx) > 0 else 0)))
        put(img, int(cx - r * 0.4), int(cy - r * 0.4), WHITE)
    gem(16, 21.5, 2.6, "#d1102e")
    gem(9.5, 21.5, 1.8, "#1f5fd1")
    gem(22.5, 21.5, 1.8, "#1f5fd1")
    return outline(img, rgb("#4a2c00"))


def crown_worn():
    """Helmet layer at double resolution (128x64): a band with spikes and gems around the head."""
    img = Image.new("RGBA", (128, 64), (0, 0, 0, 0))
    gold, pale, deep = rgb("#f2c230"), rgb("#fff2b0"), rgb("#9a6608")
    for face, x0 in enumerate((0, 16, 32, 48)):  # right, front, left, back side faces of the head
        for x in range(16):
            curve = 1 - abs(x + 0.5 - 8) / 8.0
            for y in (20, 21, 22):
                put(img, x0 + x, y, shade(gold, 0.2 * curve - 0.05 + (0.2 if y == 20 else 0)))
            put(img, x0 + x, 23, deep)
        for sx in (1.5, 8, 14.5):  # three spikes per side
            for y in range(16, 20):
                half = 0.5 + (y - 16) * 0.7
                for x in range(16):
                    if abs(x + 0.5 - sx) <= half:
                        put(img, x0 + x, y, shade(gold, 0.3 if x + 0.5 < sx else -0.1))
            put(img, x0 + int(sx), 15, pale)
        gem = rgb("#d1102e") if face == 1 else rgb("#1f5fd1")
        for (gx, gy) in ((7, 21), (8, 21), (7, 22), (8, 22)):
            put(img, x0 + gx, gy, gem)
        put(img, x0 + 7, 21, shade(gem, 0.5))
    for i in range(16):  # rim on the top face so it looks round from above
        for (x, y) in ((16 + i, 0), (16 + i, 15), (16, i), (31, i)):
            put(img, x, y, gold)
    return img


# ---------------------------------------------------------------- blades (diagonal, handle bottom-left)

def blade_frame(x, y, origin):
    """(along, across): along the blade towards the top-right, across it."""
    ux, uy = 1 / math.sqrt(2), -1 / math.sqrt(2)
    vx, vy = 1 / math.sqrt(2), 1 / math.sqrt(2)
    px, py = x + 0.5 - origin[0], y + 0.5 - origin[1]
    return px * ux + py * uy, px * vx + py * vy


def storm_saber():
    img = new()
    edge, steel, core, spine = rgb("#f2fdff"), rgb("#9fdcff"), rgb("#4a9ee8"), rgb("#1f5fa8")
    bolt, gold, grip, gem = rgb("#fff35c"), rgb("#f2c230"), rgb("#4b3478"), rgb("#fff35c")
    origin, length = (10.5, 21.5), 26.0
    for y in range(N):
        for x in range(N):
            s, t = blade_frame(x, y, origin)
            # blade: tapers to a point in the last 6 pixels
            if 0 <= s <= length:
                w = 2.1 if s < length - 6 else 2.1 * (length - s) / 6
                if abs(t) <= w:
                    col = edge if t < -w * 0.55 else (steel if t < w * 0.15 else (core if t < w * 0.7 else spine))
                    zig = 0.8 * (1 if int(s / 2.2) % 2 == 0 else -1) * ((s / 2.2) % 1 - 0.5) * 2
                    if 2.5 < s < length - 5 and abs(t - zig * 0.7) < 0.55:
                        col = bolt  # lightning along the blade
                    put(img, x, y, col)
            # crossguard
            if -1.6 <= s <= 0.2 and abs(t) <= 5.6:
                put(img, x, y, shade(gold, 0.3 if s > -0.7 else -0.2))
            # wrapped grip
            if -8.5 <= s < -1.6 and abs(t) <= 1.25:
                put(img, x, y, shade(grip, 0.25 if int(s * 1.2) % 2 == 0 else -0.15))
            # pommel with a gem
            if (s + 10.2) ** 2 + t * t <= 3.4:
                put(img, x, y, gem if (s + 10.2) ** 2 + t * t <= 0.9 else shade(gold, -0.1))
    return outline(img, rgb("#12203a"))


def abyssal_baton():
    """A heavy club of dark sea-green Abyssal Iron with bands and glowing runes."""
    img = new()
    iron, band, rune, wrap = rgb("#2f8f86"), rgb("#9fe8de"), rgb("#7dfff0"), rgb("#4a3424")
    origin = (9.5, 22.5)
    for y in range(N):
        for x in range(N):
            s, t = blade_frame(x, y, origin)
            if -9 <= s <= 22:
                if s < 0:
                    w = 1.25  # handle
                elif s < 18:
                    w = 1.5 + 2.1 * (s / 18)  # the club widens towards the head
                else:
                    w = 3.6 * math.sqrt(max(0.0, 1 - ((s - 18) / 4.0) ** 2))  # rounded end
                if abs(t) <= w:
                    if s < 0:
                        col = shade(wrap, 0.2 if int(s * 1.1) % 2 == 0 else -0.2)
                    else:
                        k = 0.35 if t < -w * 0.45 else (-0.35 if t > w * 0.5 else 0)
                        col = shade(iron, k)
                        if any(abs(s - b) < 0.7 for b in (2, 9, 15)):
                            col = shade(band, k * 0.6)
                        if any((s - r) ** 2 + t * t < 0.8 for r in (5.5, 12, 18.5)):
                            col = rune
                    put(img, x, y, col)
            if (s + 10.2) ** 2 + t * t <= 2.8:
                put(img, x, y, shade(band, -0.1))
    return outline(img, rgb("#071c1a"))


# ---------------------------------------------------------------- Flintlock

def dist_to_polyline(px, py, pts):
    best = 1e9
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        dx, dy = bx - ax, by - ay
        t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        best = min(best, math.hypot(px - ax - t * dx, py - ay - t * dy))
    return best


def flintlock():
    img = new()
    metal, wood, brass = rgb("#8f96a3"), rgb("#9a6232"), rgb("#d9a521")
    grip_line = [(12.0, 15.0), (8.5, 20.5), (5.0, 25.5), (3.5, 28.0)]
    for y in range(N):
        for x in range(N):
            px, py = x + 0.5, y + 0.5
            # wooden grip curving down (with grain) and a brass butt cap
            d = dist_to_polyline(px, py, grip_line)
            if d <= 3.3 and py >= 13:
                grain = int((px + py * 0.6) / 2.2) % 2
                col = shade(wood, (0.2 if d < 1.2 else -0.15) + (0.08 if grain else -0.08))
                if math.hypot(px - 3.5, py - 28.0) < 3.0:
                    col = shade(brass, 0.1)
                put(img, x, y, col)
            # wooden fore-stock under the barrel
            if 10 <= px <= 28 and 13.5 <= py <= 16.0:
                put(img, x, y, shade(wood, 0.15 if py < 14.6 else -0.2))
            # barrel: polished, with a highlight line and a brass muzzle ring
            if 9 <= px <= 30.5 and 9 <= py <= 13.5:
                k = 0.55 if py < 10 else (0.15 if py < 11.5 else -0.35)
                col = shade(metal, k)
                if px >= 29.5:
                    col = shade(brass, 0.2 if py < 11 else -0.15)
                put(img, x, y, col)
            # lock plate and hammer (brass)
            if 10.5 <= px <= 13.5 and 11.5 <= py <= 13.8:
                put(img, x, y, shade(brass, 0.25 if py < 12.5 else -0.1))
            if math.hypot(px - 10.3, py - 7.8) < 1.5 or (10 <= px <= 11.4 and 7.8 <= py <= 11.5):
                put(img, x, y, shade(rgb("#6f747d"), 0.3))  # the steel cock that holds the flint
            # trigger guard (a brass loop) and the trigger
            ring = math.hypot(px - 15.5, py - 18.0)
            if 2.0 <= ring <= 3.0 and py > 15.5:
                put(img, x, y, shade(brass, 0.05))
            if 14.5 <= px <= 15.5 and 15.5 <= py <= 18.5:
                put(img, x, y, rgb("#2b2b2b"))
    put(img, 29, 8, shade(brass, 0.3))  # front sight
    img = outline(img, rgb("#1c120a"))
    return img.transpose(Image.FLIP_LEFT_RIGHT)  # the muzzle points away from the player in the hand


# ---------------------------------------------------------------- general shapes

def ball(img, cx, cy, R, color, spec=True, squash=1.0):
    """A lit sphere (bombs, cannonballs, gems)."""
    c = rgb(color) if isinstance(color, str) else color
    for y in range(N):
        for x in range(N):
            dx, dy = (x + 0.5 - cx) / R, (y + 0.5 - cy) / (R * squash)
            d2 = dx * dx + dy * dy
            if d2 > 1:
                continue
            nz = math.sqrt(1 - d2)
            lam = max(0.0, dx * LIGHT[0] + dy * LIGHT[1] + nz * LIGHT[2])
            col = shade(c, max(-0.6, min(0.45, (lam - 0.5) * 1.2)))
            if spec and (dx + 0.38) ** 2 + (dy + 0.42) ** 2 < 0.03:
                col = mix(col, WHITE, 0.8)
            put(img, x, y, col)


def blade(edge, light, core, spine, guard, grip, pommel, length=26.0, width=2.1, tip=6.0, curve=0.0,
          serrate=False, origin=(10.5, 21.5), guard_half=5.6, pattern=None, outline_col="#12203a"):
    """A sword lying diagonally (handle bottom-left, like vanilla swords). curve bends the blade, serrate
    gives it teeth on the back, pattern(s, t, w) may return a colour for engravings/glow."""
    img = new()
    edge, light, core, spine = rgb(edge), rgb(light), rgb(core), rgb(spine)
    guard, grip, pommel = rgb(guard), rgb(grip), rgb(pommel)
    for y in range(N):
        for x in range(N):
            s, t = blade_frame(x, y, origin)
            if 0 <= s <= length:
                t2 = t + curve * (s / length) ** 2  # a curved blade (cutlass)
                w = width if s < length - tip else width * (length - s) / tip
                if serrate and t2 > 0 and int(s) % 3 == 0 and s < length - tip:
                    w += 0.9
                if abs(t2) <= w:
                    col = edge if t2 < -w * 0.55 else (light if t2 < w * 0.15 else (core if t2 < w * 0.7 else spine))
                    if pattern:
                        col = pattern(s, t2, w) or col
                    put(img, x, y, col)
            if -1.6 <= s <= 0.2 and abs(t) <= guard_half:
                put(img, x, y, shade(guard, 0.3 if s > -0.7 else -0.2))
            if -8.5 <= s < -1.6 and abs(t) <= 1.25:
                put(img, x, y, shade(grip, 0.25 if int(s * 1.2) % 2 == 0 else -0.15))
            if (s + 10.2) ** 2 + t * t <= 3.4:
                put(img, x, y, shade(pommel, 0.3) if (s + 10.2) ** 2 + t * t <= 0.9 else pommel)
    return outline(img, rgb(outline_col))


def krakens_maul():
    """A heavy war hammer: dark wood haft, a purple iron head with tentacle curls."""
    img = new()
    head, haft, curl = rgb("#6a3093"), rgb("#5a3a22"), rgb("#c47cff")
    origin = (8.5, 23.5)
    for y in range(N):
        for x in range(N):
            s, t = blade_frame(x, y, origin)
            if -8 <= s <= 17 and abs(t) <= 1.2:
                put(img, x, y, shade(haft, 0.2 if t < 0 else -0.15))
            if 13 <= s <= 22 and abs(t) <= 6.2:  # the head, across the haft
                k = 0.35 if s > 20.5 else (-0.35 if s < 14.5 else 0.05)
                col = shade(head, k)
                if (abs(t) > 4.6 and int(s) % 2 == 0) or (abs(t) < 1 and 16.5 < s < 18.5):
                    col = curl
                put(img, x, y, col)
            if (s + 9.5) ** 2 + t * t <= 2.6:
                put(img, x, y, shade(head, -0.2))
    return outline(img, rgb("#1c0b2a"))


def shackles():
    """Two Abyssal Iron cuffs joined by a chain."""
    img = new()
    iron, glow = rgb("#2f8f86"), rgb("#7dfff0")
    for (cx, cy) in ((9.5, 20.5), (22.5, 11.5)):
        for y in range(N):
            for x in range(N):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if 3.6 <= d <= 6.0:
                    k = 0.35 if (x + 0.5 - cx) + (y + 0.5 - cy) < -2 else (-0.3 if (x + 0.5 - cx) + (y + 0.5 - cy) > 2 else 0)
                    put(img, x, y, shade(iron, k))
                if 4.6 <= d <= 5.0 and int(math.degrees(math.atan2(y - cy, x - cx)) / 40) % 2 == 0:
                    put(img, x, y, glow)
    for i in range(5):  # chain links between the cuffs
        lx, ly = 13.5 + i * 1.7, 17.0 - i * 1.7
        for y in range(N):
            for x in range(N):
                d = math.hypot(x + 0.5 - lx, (y + 0.5 - ly) * (1.6 if i % 2 else 0.7))
                if 0.6 <= d <= 1.5:
                    put(img, x, y, shade(rgb("#8a9399"), 0.2 if i % 2 else -0.1))
    return outline(img, rgb("#071c1a"))


def bomb(body, band, fuse_spark, mark=None):
    img = new()
    ball(img, 15.5, 18.5, 10.5, body)
    b = rgb(band)
    for x in range(N):  # a metal band around the bomb
        for y in range(N):
            if 7 <= y <= 9 and 11 <= x <= 20:
                put(img, x, y, shade(b, 0.2 if y == 7 else -0.15))
    for i, (x, y) in enumerate([(16, 6), (17, 5), (18, 4), (19, 4), (20, 3)]):  # the fuse
        put(img, x, y, rgb("#b38b5d") if i % 2 else rgb("#8a6a40"))
    for (x, y) in ((21, 2), (22, 1), (21, 1), (22, 2), (23, 3), (20, 1)):
        put(img, x, y, rgb(fuse_spark))
    if mark:
        mark(img)
    return outline(img, rgb("#160f0b"))


def smoke_mark(img):
    for (cx, cy, r) in ((11.5, 17.5, 2.6), (14.5, 15.5, 2.2), (17.5, 17.0, 2.4)):
        for y in range(N):
            for x in range(N):
                if math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= r:
                    put(img, x, y, rgb("#e8e8e8") if y < cy else rgb("#c4c4c4"))


def frost_mark(img):
    c = rgb("#ffffff")
    for i in range(-4, 5):
        put(img, 15 + i, 19, c)
        put(img, 15, 19 + i, c)
        put(img, 15 + i, 19 + i, c if abs(i) < 3 else rgb("#dff6ff"))
        put(img, 15 + i, 19 - i, c if abs(i) < 3 else rgb("#dff6ff"))


def fire_mark(img):
    for y in range(N):
        for x in range(N):
            dx, dy = x + 0.5 - 15.5, y + 0.5 - 20.0
            if dy > -6 and abs(dx) < 3.8 - abs(dy + 1) * 0.45 and dy < 4:
                put(img, x, y, rgb("#ffd34d") if abs(dx) < 1.4 and dy > -2 else rgb("#ff6a1a"))


def cannonball():
    img = new()
    ball(img, 16, 17, 11.5, "#3a3d42")
    for (x, y) in ((11, 23), (20, 21), (22, 13)):  # a few dents
        put(img, x, y, rgb("#24262a"))
    return outline(img, rgb("#0d0e10"))


def echo_conch():
    """A spiral sea shell."""
    img = new()
    outer, inner, lip = rgb("#f6c08a"), rgb("#ffe7d1"), rgb("#e88a5a")
    for y in range(N):
        for x in range(N):
            px, py = x + 0.5, y + 0.5
            # the shell: a cone from the tip (top-right) widening to the opening (bottom-left)
            ax, ay, bx, by = 26.0, 5.0, 10.0, 21.0
            vx, vy = bx - ax, by - ay
            L2 = vx * vx + vy * vy
            t = ((px - ax) * vx + (py - ay) * vy) / L2
            if 0 <= t <= 1.15:
                cxp, cyp = ax + t * vx, ay + t * vy
                d = math.hypot(px - cxp, py - cyp)
                r = 1.2 + 7.0 * min(1.0, t)
                if d <= r:
                    band = int(t * 9) % 2 == 0
                    side = ((px - cxp) * vy - (py - cyp) * vx) < 0
                    col = shade(outer, (0.25 if side else -0.2) + (0.08 if band else -0.08))
                    if t > 0.85 and d < r * 0.55:
                        col = inner if t < 1.05 else lip  # the opening
                    put(img, x, y, col)
    return outline(img, rgb("#5a2e14"))


def bottle(liquid, shape="round", cork="#8a5a2b", label=None, glass="#dfeff2"):
    """A glass bottle or flask with a coloured liquid, a cork and maybe a label."""
    img = new()
    liq, gl = rgb(liquid), rgb(glass)
    for y in range(N):
        for x in range(N):
            px, py = x + 0.5, y + 0.5
            if shape == "round":
                inside = math.hypot(px - 16, (py - 20.5) * 1.05) <= 9.5 or (12.5 <= px <= 19.5 and 6 <= py <= 13)
                liquid_here = py >= 15.5
            else:  # tall rum bottle
                inside = (10 <= px <= 22 and 13 <= py <= 30) or (13.5 <= px <= 18.5 and 5 <= py <= 13) or \
                         (11 <= px <= 21 and 11 <= py <= 13 and abs(px - 16) <= (py - 9) * 1.4)
                liquid_here = py >= 15
            if not inside:
                continue
            k = 0.3 if px < 13 else (-0.25 if px > 19 else 0)
            col = shade(liq, k) if liquid_here else mix(gl, WHITE, 0.2 if px < 14 else 0)
            if 13 <= px <= 14 and py < 27:
                col = mix(col, WHITE, 0.55)  # glass shine
            put(img, x, y, col)
    for y in range(2, 6):  # the cork
        for x in range(13, 19):
            put(img, x, y, shade(rgb(cork), 0.2 if x < 15 else -0.15))
    if label:
        for y in range(19, 26):
            for x in range(11, 21):
                if opaque(img, x, y):
                    put(img, x, y, rgb(label) if 20 <= y <= 24 else shade(rgb(label), -0.3))
        for x in range(13, 19):
            put(img, x, 22, rgb("#3a1f0a"))
    return outline(img, rgb("#24323a"))


def milkshake():
    """A tall glass of Seafoam Milkshake with foam, a cherry and a striped straw."""
    img = new()
    shake, foam, glass = rgb("#cfeee6"), rgb("#ffffff"), rgb("#e6f5f7")
    for y in range(N):
        for x in range(N):
            px, py = x + 0.5, y + 0.5
            half = 6.5 - (py - 11) * 0.12  # the glass narrows to the bottom
            if 11 <= py <= 29 and abs(px - 16) <= half:
                col = shade(shake, 0.25 if px < 14 else (-0.2 if px > 19 else 0))
                if abs(px - 16) > half - 0.9:
                    col = mix(glass, WHITE, 0.3)
                put(img, x, y, col)
            if 6.5 <= py <= 11.5 and math.hypot((px - 16) / 7.2, (py - 10) / 3.6) <= 1:
                put(img, x, y, foam if py < 9.5 else rgb("#e4f4f1"))  # foam on top
    for i in range(10):  # the straw
        put(img, 20 + i // 3, 9 - i, rgb("#e0312b") if i % 2 else WHITE)
    for (x, y) in ((14, 5), (15, 5), (14, 6), (15, 6), (16, 6)):  # cherry
        put(img, x, y, rgb("#d1102e"))
    put(img, 14, 5, rgb("#ff8a95"))
    return outline(img, rgb("#28433f"))


def trident_icon():
    img = new()
    coral, light = rgb("#ff7e5f"), rgb("#ffd1b8")
    origin = (8.5, 23.5)
    for y in range(N):
        for x in range(N):
            s, t = blade_frame(x, y, origin)
            if -8 <= s <= 15 and abs(t) <= 0.9:
                put(img, x, y, shade(coral, 0.2 if t < 0 else -0.2))
            if 14 <= s <= 15.5 and abs(t) <= 4.6:
                put(img, x, y, shade(coral, -0.1))
            for off in (-4.0, 0.0, 4.0):  # three prongs
                top = 22 if off == 0 else 20
                if 15 <= s <= top and abs(t - off) <= 0.8:
                    put(img, x, y, light if s > top - 2 else coral)
    return outline(img, rgb("#4a1a10"))


def buckler():
    img = new()
    wood, rim, boss = rgb("#9a6a42"), rgb("#8f96a3"), rgb("#d9a521")
    for y in range(N):
        for x in range(N):
            d = math.hypot(x + 0.5 - 16, y + 0.5 - 16)
            if d <= 13:
                k = 0.2 if (x + y) < 30 else -0.15
                col = shade(rim, k) if d > 11 else shade(wood, k + (0.06 if int(x / 3) % 2 else -0.06))
                if d <= 4:
                    col = shade(boss, 0.35 if (x + y) < 30 else -0.1)
                put(img, x, y, col)
    for a in range(0, 360, 45):  # rivets on the rim
        put(img, int(16 + math.cos(math.radians(a)) * 12), int(16 + math.sin(math.radians(a)) * 12), rgb("#dfe3e8"))
    return outline(img, rgb("#24180e"))


# ---------------------------------------------------------------- armour icons

def polish(img):
    """A light pass for every item icon: a lit rim along the top-left edges and a shadow along the bottom-right."""
    src = img.copy()
    p, s = img.load(), src.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = s[x, y]
            if not a:
                continue
            def empty(dx, dy):
                xx, yy = x + dx, y + dy
                return not (0 <= xx < w and 0 <= yy < h) or s[xx, yy][3] == 0
            if empty(-1, 0) or empty(0, -1) or empty(1, 0) or empty(0, 1):
                continue                                  # the outline itself stays
            if empty(-2, 0) or empty(0, -2):
                p[x, y] = shade((r, g, b), 0.14) + (a,)
            elif empty(2, 0) or empty(0, 2):
                p[x, y] = shade((r, g, b), -0.14) + (a,)
    return img


def armor_icon(piece, base, trim, accent, style="plate"):
    """Item icons for armour and clothing: style plate (armour), coat, cloth (trousers) or tricorn (hats)."""
    img = new()
    base, trim, accent = rgb(base), rgb(trim), rgb(accent)
    dark = shade(trim, -0.35)

    def shaded(c, x, y, top=4, bottom=28):
        k = 0.16 - 0.3 * (y - top) / max(1, bottom - top) - 0.006 * (x - 16)
        return shade(c, k + (_noise(x, y, 17) - 0.5) * 0.05)

    if piece == "helmet" and style == "tricorn":
        for y in range(8, 24):
            for x in range(3, 29):
                brim = y >= 16 and abs(x - 16) <= 13 - (y - 16) * 1.2
                crown = 8 <= y < 17 and abs(x - 16) <= 6 + (y - 8) * 0.25
                if brim or crown:
                    c = shaded(base, x, y, 8, 23)
                    if brim and (y in (16, 17) or abs(x - 16) >= 12 - (y - 16) * 1.2):
                        c = shade(trim, 0.1)                     # gold or white edging
                    put(img, x, y, c)
        for (x, y) in ((22, 13), (23, 13), (22, 14), (23, 14)):
            put(img, x, y, accent)                               # cockade
        put(img, 22, 13, shade(accent, 0.4))
    elif piece == "helmet":
        for y in range(5, 26):
            for x in range(6, 26):
                d = ((x - 16) / 10) ** 2 + ((y - 15) / 10.5) ** 2
                if d <= 1 and not (y > 21 and 11 <= x <= 20):
                    c = shaded(base, x, y, 5, 25)
                    if abs(x - 16) <= 1 and y < 17:
                        c = shade(accent, 0.15)                  # crest
                    if y in (16, 17) and 8 <= x <= 23:
                        c = rgb("#101317") if y == 17 else shade(trim, -0.1)   # visor slit
                    if y >= 19 and (x < 11 or x > 20):
                        c = shaded(trim, x, y, 5, 25)            # cheek guards
                    put(img, x, y, c)
        for (x, y) in ((9, 12), (22, 12)):
            put(img, x, y, shade(trim, 0.4))
    elif piece == "chestplate":
        for y in range(5, 29):
            for x in range(3, 29):
                torso = 9 <= x <= 22 and 7 <= y <= 27
                shoulders = 5 <= y <= 11 and abs(x - 15.5) <= 10.5 - max(0, 7 - y)
                sleeve = (4 <= x <= 8 or 23 <= x <= 27) and 7 <= y <= 21 - (abs(x - 15.5) - 7) * 0.5
                if not (torso or shoulders or sleeve):
                    continue
                c = shaded(base, x, y, 5, 28)
                if style == "coat":
                    if torso and abs(x - 15.5) < 0.6 + max(0, (y - 18) * 0) and y >= 22:
                        c = shade(base, -0.4)                    # the split coat tails
                    if torso and 7 <= y <= 15 and abs(x - 15.5) <= 7 - (y - 7) * 0.6 and abs(x - 15.5) >= 4 - (y - 7) * 0.45:
                        c = shaded(trim, x, y)                   # lapels
                    if 7 <= y <= 9 and 13 <= x <= 18:
                        c = rgb("#ece8dc")                       # shirt collar
                    if torso and x in (12, 19) and 12 <= y <= 21 and y % 3 == 0:
                        c = shade(accent, 0.25)                  # two rows of buttons
                    if torso and y == 20:
                        c = shade(rgb("#3a2414"), 0.05)          # belt
                    if sleeve and y >= 18:
                        c = shaded(trim, x, y)                   # cuffs
                    if y >= 26 and torso:
                        c = shade(trim, -0.05)                   # hem
                else:
                    if shoulders and not torso:
                        c = shaded(trim, x, y)                   # pauldrons
                    if torso and abs(x - 15.5) < 1:
                        c = shade(base, 0.25)                    # ridge
                    if torso and y >= 18 and (y - 18) % 3 == 2:
                        c = shade(base, -0.3)                    # belly plates
                    if torso and 11 <= y <= 14 and 14 <= x <= 17:
                        c = shade(accent, 0.2 if (x + y) % 2 else 0.4)   # emblem gem
                    if sleeve and y >= 17:
                        c = shaded(trim, x, y)                   # bracers
                put(img, x, y, c)
    elif piece == "leggings":
        for y in range(5, 29):
            for x in range(8, 24):
                waist = 5 <= y <= 9
                leg = y > 9 and (8 <= x <= 14 or 17 <= x <= 23)
                if not (waist or leg):
                    continue
                c = shaded(base, x, y, 5, 28)
                if waist:
                    c = shade(rgb("#3a2414") if style != "plate" else trim, 0.05 if y == 6 else -0.05)
                    if 14 <= x <= 17 and 6 <= y <= 8:
                        c = shade(accent, 0.2)                   # buckle
                if leg and x in (8, 23):
                    c = shade(trim, -0.05)                       # side stripes
                if leg and 17 <= y <= 19 and style == "plate":
                    c = shaded(trim, x, y)                       # knee guards
                if leg and style != "plate" and y > 12 and x in (11, 20) and y % 4 == 0:
                    c = shade(base, -0.15)                       # creases
                put(img, x, y, c)
    else:  # boots
        for side, x0 in ((0, 6), (1, 17)):
            for y in range(9, 28):
                for x in range(x0, x0 + 10):
                    shaft = x0 <= x <= x0 + 5 and y <= 22
                    foot = 21 <= y <= 27 and x0 <= x <= x0 + 8 - (1 if y == 21 else 0)
                    if not (shaft or foot):
                        continue
                    c = shaded(base, x, y, 9, 27)
                    if y <= 11:
                        c = shaded(trim, x, y)                   # cuff
                    if y >= 26:
                        c = shade(dark, -0.1)                    # sole
                    if foot and x >= x0 + 6 and y in (23, 24):
                        c = shade(base, 0.3)                     # toe shine
                    put(img, x, y, c)
    return outline(img, shade(base, -0.62) if style != "plate" else shade(base, -0.7))


def armor_layers(base, trim, accent, scale=4, legs=None, boots=None, hat=None):
    """Worn clothing (humanoid + humanoid_leggings layers) at 4x: wool coats with a twill weave and soft folds,
    facings and cuffs, brass buttons, a leather belt, a felt hat with a ribbon band and braid, linen breeches
    and leather boots with a turned-down cuff. Edges are worn and slightly darker, nothing is outlined."""
    S = 4
    W, H = 64 * S, 32 * S
    l1 = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    l2 = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    base, trim, accent = rgb(base), rgb(trim), rgb(accent)
    legs_col = rgb(legs) if legs else base
    boots_col = rgb(boots) if boots else rgb("#2a1d14")
    hat_col = rgb(hat) if hat else base
    leather = rgb("#3b2617")
    linen = rgb("#d6cfbd")
    LIGHT = {"top": 0.1, "bottom": -0.3, "front": 0.03, "right": -0.07, "left": -0.07, "back": -0.13}

    def wool(c, x, y, seed=3, folds=True):
        twill = 0.03 if ((x + y) // 2) % 2 else -0.03
        n = (_noise(x, y, seed) - 0.5) * 0.09 + (_noise(x // 3, y // 3, seed + 7) - 0.5) * 0.06
        fold = 0.08 * math.sin(x * 0.42 + math.sin(y * 0.13 + seed) * 1.6) if folds else 0
        return shade(c, twill + n + fold)

    def felt(c, x, y):
        return shade(c, (_noise(x, y, 21) - 0.5) * 0.12 + (_noise(x // 2, y // 2, 22) - 0.5) * 0.08)

    def hide(c, x, y, w):
        """Leather: a soft vertical highlight, fine grain and a few creases."""
        hl = 0.16 * math.exp(-((x - w * 0.35) / (w * 0.18)) ** 2)
        crease = -0.12 if _noise(0, y, 31) > 0.86 else 0
        return shade(c, hl + crease + (_noise(x, y, 33) - 0.5) * 0.07)

    def brass(x, y, cx, cy):
        """A 3x3 brass button with a highlight top-left and a shadow bottom-right."""
        dx, dy = x - cx, y - cy
        if max(abs(dx), abs(dy)) > 1:
            return None
        if dx + dy < 0:
            return shade(accent, 0.35)
        if dx + dy > 0:
            return shade(accent, -0.35)
        return accent

    def worn(c, x, y, w, h):
        """Worn edges: the outer pixel row of every face is a little darker (seams), the next one lighter (rubbed)."""
        e = min(x, y, w - 1 - x, h - 1 - y)
        if e == 0:
            return shade(c, -0.18)
        if e == 1:
            return shade(c, 0.06)
        return c

    def box(img, u, v, w, h, d, paint):
        faces = {"top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d), "right": (u, v + d, d, h),
                 "front": (u + d, v + d, w, h), "left": (u + d + w, v + d, d, h), "back": (u + d + w + d, v + d, w, h)}
        px = img.load()
        for face, (fu, fv, fw, fh) in faces.items():
            for fy in range(fh * S):
                for fx in range(fw * S):
                    c = paint(face, fx, fy, fw * S, fh * S)
                    if c is None:
                        continue
                    px[fu * S + fx, fv * S + fy] = shade(c, LIGHT[face]) + (255,)

    # ---- hat (8x8x8): felt crown, a ribbon band, braided edge and a cockade
    def hat_paint(face, x, y, w, h):
        if face == "bottom":
            return None
        if face == "top":
            r = math.hypot(x - w / 2, y - h / 2) / (w / 2)
            return worn(shade(felt(hat_col, x, y), -0.12 * (1 - min(1, r))), x, y, w, h)
        rim = 13 if face != "back" else 16                                   # the hat ends above the eyes
        if y >= rim:
            return None
        if y >= rim - 2:
            return shade(accent, 0.15 if (x + y) % 3 == 0 else -0.1)          # braid along the brim
        if rim - 7 <= y < rim - 3:
            c = shade(trim, 0.08 if y == rim - 7 else (-0.12 if y == rim - 4 else 0))   # ribbon band
            if face == "front" and abs(x - (w - 8)) <= 2 and rim - 8 <= y <= rim - 3:
                r = math.hypot(x - (w - 8), y - (rim - 5.5))
                return shade(accent, 0.25 - r * 0.12) if r <= 1 else shade(linen, -0.05 - r * 0.04)   # cockade
            return c
        return felt(hat_col, x, y)
    box(l1, 0, 0, 8, 8, 8, hat_paint)

    # ---- coat (8x12x4)
    def coat(face, x, y, w, h):
        cx = w / 2
        if face in ("top", "bottom"):
            return wool(base, x, y, 5, False)
        if face == "front":
            if y < 22 and abs(x - cx) <= 2 + y * 0.18:
                c = shade(linen, (_noise(x, y, 41) - 0.5) * 0.08 - 0.04 * (y % 4 == 0))   # shirt and cravat
                return shade(c, -0.15) if abs(x - cx) > 1 + y * 0.18 else c
            if y < 24 and abs(x - cx) <= 9 - y * 0.12:
                c = wool(trim, x, y, 9, False)                               # lapels / facings
                return shade(c, -0.2) if abs(abs(x - cx) - (2 + y * 0.18)) < 1 else c
            if 31 <= y <= 35:
                if abs(x - cx) <= 2 and y in (31, 35) or abs(x - cx) == 2:
                    return shade(accent, 0.2)                                # buckle
                return shade(hide(leather, x, y, w), 0.06 if y == 31 else (-0.15 if y == 35 else 0))   # belt
            for by in (14, 20, 26):
                for bx in (cx - 6.5, cx + 5.5):
                    b = brass(x, y, int(bx), by)
                    if b is not None:
                        return b
            c = wool(base, x, y)
            if y > 35 and abs(x - cx) < 1:
                return shade(c, -0.3)                                        # the coat opens below the belt
            return worn(c, x, y, w, h)
        if face == "back":
            c = wool(base, x, y, 6)
            if y > 35 and abs(x - cx) < 1:
                return shade(c, -0.3)                                        # back vent
            if 31 <= y <= 35:
                return shade(hide(leather, x, y, w), -0.05)
            return worn(c, x, y, w, h)
        if 31 <= y <= 35:
            return shade(hide(leather, x, y, w), -0.05)
        return worn(wool(base, x, y, 7), x, y, w, h)
    box(l1, 16, 16, 8, 12, 4, coat)

    # ---- sleeves (4x12x4): an epaulette with fringe, wool sleeve, turned-back cuff with two buttons
    def sleeve(face, x, y, w, h):
        if face == "bottom":
            return None
        if face == "top":
            return shade(accent, -0.05 + 0.18 * ((x // 2) % 2) + (_noise(x, y, 51) - 0.5) * 0.1)   # epaulette
        if y < 3:
            return shade(accent, 0.1 if x % 2 else -0.25)                    # fringe
        if y >= h - 10:
            c = wool(trim, x, y, 11, False)
            if y == h - 10:
                return shade(c, 0.12)
            if face == "right" or face == "left":
                for by in (h - 7, h - 3):
                    b = brass(x, y, w // 2, by)
                    if b is not None:
                        return b
            return worn(c, x, y, w, h)
        c = wool(base, x, y, 13)
        if (y - 18) % 11 == 0 and y > 10:
            c = shade(c, -0.12)                                              # elbow wrinkles
        return worn(c, x, y, w, h)
    box(l1, 40, 16, 4, 12, 4, sleeve)

    # ---- boots (layer 1, 4x12x4): leather with a turned-down cuff, a sole and a polished toe
    def boot(face, x, y, w, h):
        if face == "top" or y < 22:
            return None
        if face == "bottom":
            return shade(boots_col, -0.5)
        if y >= h - 3:
            return shade(boots_col, -0.45 if y == h - 1 else -0.32)          # sole
        if y < 29:
            return shade(hide(boots_col, x, y, w), 0.22 if y == 22 else (0.08 if y < 27 else -0.25))   # cuff
        c = hide(boots_col, x, y, w)
        if face == "front" and y >= h - 9:
            c = shade(c, 0.1 + 0.15 * math.exp(-((x - w * 0.4) / 2.5) ** 2))   # toe shine
        return c
    box(l1, 0, 16, 4, 12, 4, boot)

    # ---- breeches (layer 2): a waistband with a buckle, legs with creases, buttons at the knee
    def waist(face, x, y, w, h):
        if face in ("top", "bottom") or y < 30:
            return None
        if y < 35:
            if face == "front" and abs(x - w / 2) <= 2 and 30 <= y <= 34:
                return shade(accent, 0.25 if (x + y) % 2 else 0)
            return shade(hide(leather, x, y, w), 0.06 if y == 30 else 0)
        return worn(wool(legs_col, x, y, 15), x, y, w, h)
    box(l2, 16, 16, 8, 12, 4, waist)

    def breeches(face, x, y, w, h):
        if face in ("top", "bottom"):
            return None
        c = wool(legs_col, x, y, 17)
        if face in ("right", "left") and abs(x - w / 2) < 1:
            c = shade(c, -0.12)                                              # side seam
            if 26 <= y <= 34 and y % 4 == 2:
                return shade(accent, 0.2)                                    # knee buttons
        if 34 <= y <= 36:
            return shade(wool(trim if legs is None else legs_col, x, y, 19), -0.15)    # knee band
        return worn(c, x, y, w, h)
    box(l2, 0, 16, 4, 12, 4, breeches)
    return l1, l2


def plate_armor_layers(base, trim, accent, seed=11):
    """Worn plate armour at 4x (256x128 per layer, vanilla humanoid layout): bevelled plates with a metal sheen,
    rivets and a glowing accent; a helmet with a crest and a face opening, a breastplate with a ridge and
    segmented belly plates, layered pauldrons and bracers, a belt with tassets, knee guards, greaves, sabatons."""
    S = 4
    W, H = 64 * S, 32 * S
    l1 = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    l2 = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    base, trim, accent = rgb(base), rgb(trim), rgb(accent)
    dark = shade(base, -0.55)
    leather_dark = rgb("#24170e")
    LIGHT = {"top": 0.18, "bottom": -0.3, "front": 0.05, "right": -0.08, "left": -0.08, "back": -0.16}

    def metal(c, x, y, h):
        """Brushed steel: a broad soft sheen, fine streaks along the plate, light scratches and pitting."""
        sheen = 0.13 * math.sin(y / max(1, h) * math.pi * 1.1 + 0.4) + 0.04 * math.sin(x * 0.21 + y * 0.07)
        streak = (_noise(x // 5, y, seed) - 0.5) * 0.09
        grain = (_noise(x, y, seed + 1) - 0.5) * 0.05
        scratch = 0.22 if ((x * 2 - y) % 29 == 0 and _noise(x // 4, y // 4, seed + 2) > 0.72) else 0
        pit = -0.18 if _noise(x, y, seed + 3) > 0.985 else 0
        return shade(c, sheen + streak + grain + scratch + pit)

    def box(img, u, v, w, h, d, paint):
        faces = {"top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d), "right": (u, v + d, d, h),
                 "front": (u + d, v + d, w, h), "left": (u + d + w, v + d, d, h), "back": (u + d + w + d, v + d, w, h)}
        px = img.load()
        for face, (fu, fv, fw, fh) in faces.items():
            for fy in range(fh * S):
                for fx in range(fw * S):
                    c = paint(face, fx, fy, fw * S, fh * S)
                    if c is None:
                        continue
                    px[fu * S + fx, fv * S + fy] = shade(c, LIGHT[face]) + (255,)

    def plate(x, y, w, h, top, bottom, col=None):
        """A horizontal plate between rows top..bottom: bevel light on top, shadow at the bottom, dark seam."""
        c = metal(col or base, x, y, h)
        if y == top:
            return shade(c, 0.24)                                               # the rolled edge catches light
        if y == top + 1:
            return shade(c, 0.1)
        if y == bottom:
            return shade(dark, (_noise(x, y, seed + 5) - 0.5) * 0.1)
        if y >= bottom - 2:
            return shade(c, -0.16 - 0.1 * _noise(x, y, seed + 6))               # grime under the overlap
        return c

    def rivet(x, y, every=10, row=None):
        return (x % every in (3, 4)) and (row is None or y in row)

    # ---- helmet (8x8x8): a crest down the middle, a face opening with cheek guards on the front
    def helmet(face, x, y, w, h):
        if face == "top":
            if abs(x - w / 2) < 3:
                return metal(trim, x, y, h) if abs(x - w / 2) < 1.5 else shade(trim, -0.2)   # brass crest
            return metal(base, x, y, h) if (x + y) % 9 else shade(base, 0.2)
        if face == "bottom":
            return None
        if face == "front":
            if y >= 12 and 4 <= x < w - 4:
                return None                                                     # the face opening, open at the chin
            if y >= 12 and (x < 4 or x >= w - 4):
                return plate(x, y, w, h, 12, h - 1, shade(base, -0.08))          # cheek guards
            if 10 <= y < 12:
                return shade(trim, 0.1)                                         # brow band
        if y >= h - 3:
            return dark                                                         # rim
        if face != "front" and y in (14, 15) and rivet(x, y, 8):
            return shade(trim, 0.45)
        if abs(x - w / 2) < 2 and face in ("front", "back") and y < 10:
            return metal(trim, x, y, h)                                         # the crest runs down front and back
        return plate(x, y, w, h, 0, h - 1)
    box(l1, 0, 0, 8, 8, 8, helmet)

    # ---- chest (8x12x4): ridge, pectorals, belly plates, a glowing wave emblem
    def chest(face, x, y, w, h):
        if face in ("top", "bottom"):
            return metal(trim, x, y, h)
        if face == "front":
            cx = w / 2
            if 6 <= y <= 18 and abs(x - cx) <= 6 - (y - 6) * 0.25:
                emblem = abs(math.sin((x - cx) * 0.6) * 3 + 11 - y) < 1.4 or (y > 13 and abs(x - cx) < 1.2)
                if emblem:
                    return shade(mix(metal(base, x, y, h), accent, 0.35), 0.12)  # the etched wave and trident
            if abs(x - cx) < 1:
                return shade(base, 0.22)                                        # the ridge
            if y < 22:
                return plate(x, y, w, h, 0, 21)                                 # breastplate
            seg = 22 + ((y - 22) // 6) * 6
            return plate(x, y, w, h, seg, seg + 5, shade(base, -0.05))          # belly plates
        if face == "back":
            seg = (y // 8) * 8
            return plate(x, y, w, h, seg, seg + 7, shade(base, -0.05))
        seg = (y // 12) * 12
        return plate(x, y, w, h, seg, seg + 11, shade(base, -0.05))
    box(l1, 16, 16, 8, 12, 4, chest)

    # ---- arms (4x12x4): layered pauldrons and bracers, cloth between
    def arm(face, x, y, w, h):
        if face == "top":
            return metal(trim, x, y, h)
        if face == "bottom":
            return None
        if y < 18:
            seg = (y // 6) * 6
            c = plate(x, y, w, h, seg, seg + 5, trim if seg == 0 else base)
            return metal(trim, x, y, h) if y in (16, 17) else c                 # brass edge on the pauldron
        if y < 26:
            quilt = -0.14 if ((x + y) % 8 == 0 or (x - y) % 8 == 0) else 0
            return shade(rgb("#3a3a36"), quilt + (_noise(x, y, 4) - 0.5) * 0.1)  # quilted gambeson
        if y >= h - 10:
            c = plate(x, y, w, h, h - 10, h - 1)
            return shade(trim, 0.4) if rivet(x, y, 6, (h - 6,)) else c          # bracer
        return shade(rgb("#3a3a36"), -0.05 + (_noise(x, y, 4) - 0.5) * 0.1)
    box(l1, 40, 16, 4, 12, 4, arm)

    # ---- boots (layer 1, 4x12x4): greaves and sabatons with a toe cap
    def boot(face, x, y, w, h):
        if face == "top":
            return None
        if face == "bottom":
            return dark
        if y < 20:
            return None                                                         # (the leggings cover the thighs)
        if y < 34:
            c = plate(x, y, w, h, 20, 33)
            return metal(trim, x, y, h) if face == "front" and abs(x - w / 2) < 1 else c
        seg = 34 + ((y - 34) // 7) * 7
        c = plate(x, y, w, h, seg, seg + 6, shade(base, -0.08))
        if face == "front" and y >= h - 5:
            return shade(trim, 0.1)                                             # toe cap
        return c
    box(l1, 0, 16, 4, 12, 4, boot)

    # ---- leggings (layer 2): a belt with a buckle, tassets, knee guards
    def waist(face, x, y, w, h):
        if face in ("top", "bottom"):
            return None
        if 28 <= y < 36:
            c = shade(rgb("#3a2414"), 0.05 if y in (28, 29) else -0.05)          # belt
            if face == "front" and abs(x - w / 2) < 4 and 29 <= y <= 34:
                return metal(trim, x, y, h) if (abs(x - w / 2) > 2.5 or y in (29, 34)) else shade(leather_dark, 0)   # buckle
            return c
        if y >= 36:
            return plate(x, y, w, h, 36, h - 1, shade(base, -0.05))             # tassets
        return None
    box(l2, 16, 16, 8, 12, 4, waist)

    def legging(face, x, y, w, h):
        if face in ("top", "bottom"):
            return None
        if y < 20:
            return plate(x, y, w, h, 0, 19)                                     # thigh plate
        if y < 28:
            c = plate(x, y, w, h, 20, 27, trim)                                 # knee guard
            return shade(accent, 0.1) if face == "front" and 22 <= y <= 25 and abs(x - w / 2) < 2 else c
        return plate(x, y, w, h, 28, h - 1, shade(base, -0.06))
    box(l2, 0, 16, 4, 12, 4, legging)
    return l1, l2

# ---------------------------------------------------------------- 3D models

GUN_DISPLAY = {  # like a normal held item (same as vanilla item/generated), but with depth
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    # third person: held like a loaded crossbow (both arms aim forward). In hand space the vanilla crossbow points
    # along (0.26, 0, -0.97) with +y up; the gun's muzzle is -x and its top +y, so it turns -105 degrees about y.
    # The translation puts the grip (not the middle of the model) in the hand.
    "thirdperson_righthand": {"rotation": [0, -105, 0], "translation": [1.9, 2.1, -2.9], "scale": [0.7, 0.7, 0.7]},
    "firstperson_righthand": {"rotation": [0, -90, 0], "translation": [5.0, 1.5, -3.0], "scale": [0.68, 0.68, 0.68]},
    "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
}


def parts_texture(materials):
    """A 16x16 texture with one 16x4 strip per material (shaded top to bottom, a little grain)."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for i, color in enumerate(materials):
        c = rgb(color)
        for y in range(4):
            for x in range(16):
                put(img, x, i * 4 + y, shade(c, 0.25 - y * 0.15 + (0.05 if (x * 7 + y * 3) % 5 == 0 else 0)))
    return img


def cube(frm, to, mat, rotation=None):
    uv = [0, mat * 4, 16, mat * 4 + 4]
    el = {"from": frm, "to": to,
          "faces": {f: {"uv": uv, "texture": "#parts"} for f in ("north", "east", "south", "west", "up", "down")}}
    if rotation:
        el["rotation"] = rotation
    return el


CANNON_DISPLAY = dict(GUN_DISPLAY)
# A normal held item: +y of the hand points forward, +z up. Muzzle (-x) to +y and top (+y) to +z.
CANNON_DISPLAY["firstperson_righthand"] = {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]}
CANNON_DISPLAY["thirdperson_righthand"] = {"rotation": [90, -90, 0], "translation": [0, 1.6, 3.6], "scale": [0.7, 0.7, 0.7]}


def model_3d(name, materials, elements, display=None):
    """Writes a block-style 3D item model (and its texture) for an item."""
    tex = parts_texture(materials)
    os.makedirs(os.path.join(PACK, "assets", "aquatic", "textures", "item"), exist_ok=True)
    tex.save(os.path.join(PACK, "assets", "aquatic", "textures", "item", name + "_parts.png"))
    write_json(os.path.join(PACK, "assets", "aquatic", "models", "item", name + ".json"), {
        "textures": {"parts": "aquatic:item/" + name + "_parts", "particle": "aquatic:item/" + name + "_parts"},
        "elements": elements,
        "display": display or GUN_DISPLAY,
    })
    write_json(os.path.join(PACK, "assets", "aquatic", "items", name + ".json"), {
        "model": {"type": "minecraft:model", "model": "aquatic:item/" + name},
    })


def flintlock_3d():
    METAL, WOOD, BRASS, DARK = 0, 1, 2, 3
    grip_rot = {"angle": 22.5, "axis": "z", "origin": [13, 8, 8]}
    model_3d("flintlock", ["#9aa1ad", "#9a6232", "#d9a521", "#3a3d42"], [
        cube([1, 10, 7], [11, 12, 9], METAL),                 # barrel (the muzzle points to the left, like the icon)
        cube([0.75, 9.75, 6.75], [2, 12.25, 9.25], BRASS),    # muzzle ring
        cube([2.5, 8.5, 7.1], [11, 10, 8.9], WOOD),           # wooden fore-stock under the barrel
        cube([10, 7.5, 6.75], [13.5, 12, 9.25], WOOD),        # the lock body
        cube([10.5, 9, 6.5], [13, 11, 9.5], BRASS),           # lock plates on both sides
        cube([12, 12, 7.5], [13, 14, 8.5], DARK),             # the cock that holds the flint
        cube([11.5, 2, 7], [14.5, 8, 9], WOOD, grip_rot),     # the grip, angled back
        cube([11.25, 1.5, 6.75], [14.75, 3, 9.25], BRASS, grip_rot),  # butt cap
        cube([9.5, 6, 7.5], [11.5, 6.5, 8.5], BRASS),         # trigger guard
        cube([9.5, 6, 7.5], [10, 7.5, 8.5], BRASS),
        cube([10.5, 6.5, 7.75], [11, 7.5, 8.25], DARK),       # trigger
    ])


def hand_cannon_3d():
    IRON, WOOD, BRASS, DARK = 0, 1, 2, 3
    grip_rot = {"angle": 22.5, "axis": "z", "origin": [13, 8, 8]}
    model_3d("hand_cannon", ["#4a4e55", "#7a4a26", "#c9952a", "#1c1d20"], [
        cube([1, 8.5, 6], [11, 13.5, 10], IRON),              # the heavy barrel
        cube([0, 8, 5.5], [2, 14, 10.5], BRASS),              # flared muzzle
        cube([0.5, 9.5, 6.5], [0.75, 12.5, 9.5], DARK),       # the dark bore
        cube([5, 8.25, 5.75], [6, 13.75, 10.25], BRASS),      # reinforcing bands
        cube([9, 8.25, 5.75], [10, 13.75, 10.25], BRASS),
        cube([11, 8, 6.5], [14, 13, 9.5], WOOD),              # breech block
        cube([12, 13, 7.5], [13, 14.5, 8.5], DARK),           # fuse / touch hole
        cube([11.5, 2, 6.75], [14.5, 8, 9.25], WOOD, grip_rot),       # grip
        cube([11.25, 1.5, 6.5], [14.75, 3, 9.5], BRASS, grip_rot),    # butt cap
        cube([9, 6.5, 7.5], [11.5, 7, 8.5], BRASS),           # trigger guard
        cube([10, 7, 7.75], [10.5, 8, 8.25], DARK),           # trigger
    ], display=CANNON_DISPLAY)
    # an icon-style picture is not needed: the 3D model shows in the inventory too


# ---------------------------------------------------------------- pack

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)


def item(name, image, handheld=False, drawn=False):
    os.makedirs(os.path.join(PACK, "assets", "aquatic", "textures", "item"), exist_ok=True)
    path = os.path.join(PACK, "assets", "aquatic", "textures", "item", name + ".png")
    if not drawn:
        image = [polish(f) for f in image] if isinstance(image, list) else polish(image)
    if isinstance(image, list):  # an animation: frames stacked top to bottom
        strip = Image.new("RGBA", (image[0].width, image[0].height * len(image)), (0, 0, 0, 0))
        for i, frame in enumerate(image):
            strip.paste(frame, (0, i * frame.height))
        strip.save(path)
        write_json(path + ".mcmeta", {"animation": {"frametime": 3, "interpolate": True}})
    else:
        image.save(path)
        if os.path.exists(path + ".mcmeta"):
            os.remove(path + ".mcmeta")
    write_json(os.path.join(PACK, "assets", "aquatic", "models", "item", name + ".json"), {
        "parent": "minecraft:item/handheld" if handheld else "minecraft:item/generated",
        "textures": {"layer0": "aquatic:item/" + name},
    })
    write_json(os.path.join(PACK, "assets", "aquatic", "items", name + ".json"), {
        "model": {"type": "minecraft:model", "model": "aquatic:item/" + name},
    })


def equipment(name, layer_image, leggings_image=None):
    layers = {}
    for kind, image in (("humanoid", layer_image), ("humanoid_leggings", leggings_image)):
        if image is None:
            continue
        path = os.path.join(PACK, "assets", "aquatic", "textures", "entity", "equipment", kind)
        os.makedirs(path, exist_ok=True)
        image.save(os.path.join(path, name + ".png"))
        layers[kind] = [{"texture": "aquatic:" + name}]
    write_json(os.path.join(PACK, "assets", "aquatic", "equipment", name + ".json"), {"layers": layers})


# ---------------------------------------------------------------- effect textures (animated 3D effects)
# White with soft transparent edges; the game tints them in the colour of the move (custom_model_data tint).

def vfx_slash():
    S = 64
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    cx, cy = 32.0, 46.0
    for y in range(S):
        for x in range(S):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            ang = math.degrees(math.atan2(dx, -dy))  # 0 = straight up
            if abs(ang) > 80 or not 14 <= r <= 30:
                continue
            edge = max(0.0, 1 - abs(r - 27) / 4.0)          # bright outer edge
            body = max(0.0, 1 - abs(r - 21) / 8.0) * 0.55     # softer inside
            ends = max(0.0, 1 - (abs(ang) / 80) ** 3)          # fades out at the tips
            a = min(1.0, (edge + body) * ends)
            if a > 0.03:
                img.putpixel((x, y), (255, 255, 255, int(a * 255)))
    return img


def vfx_ring():
    S = 64
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    for y in range(S):
        for x in range(S):
            r = math.hypot(x + 0.5 - 32, y + 0.5 - 32)
            a = math.exp(-((r - 28.5) / 2.2) ** 2) + 0.25 * math.exp(-((r - 24) / 3.5) ** 2)
            if r <= 31.5 and a > 0.03:
                img.putpixel((x, y), (255, 255, 255, int(min(1.0, a) * 255)))
    return img


def vfx_flash():
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    for y in range(S):
        for x in range(S):
            dx, dy = x + 0.5 - 16, y + 0.5 - 16
            r = math.hypot(dx, dy)
            core = max(0.0, 1 - r / 7.0)
            ang = math.atan2(dy, dx)
            rays = max(0.0, math.cos(ang * 4)) ** 8 * max(0.0, 1 - r / 15.5)  # an eight-pointed star
            a = min(1.0, core * 1.2 + rays)
            if a > 0.03:
                img.putpixel((x, y), (255, 255, 255, int(a * 255)))
    return img


def vfx_frames():
    """The effect sprites (pixelart.VFX): every frame is its own model "vfx_<name>_<n>" with a tinted layer
    (the plugin sets the colour through the item's custom model data) and a white core that stays white."""
    tex = os.path.join(PACK, "assets", "aquatic", "textures", "item")
    os.makedirs(tex, exist_ok=True)
    for old in ("vfx_slash", "vfx_ring", "vfx_flash"):          # the soft sprites of 2.11 - 2.19
        for path in (os.path.join(tex, old + ".png"), os.path.join(PACK, "assets", "aquatic", "models", "item", old + ".json"),
                     os.path.join(PACK, "assets", "aquatic", "items", old + ".json")):
            if os.path.exists(path):
                os.remove(path)
    for name, make in pixelart.VFX.items():
        for n, (tint, core) in enumerate(make()):
            key = "vfx_%s_%d" % (name, n)
            tint.save(os.path.join(tex, key + ".png"))
            core.save(os.path.join(tex, key + "_core.png"))
            write_json(os.path.join(PACK, "assets", "aquatic", "models", "item", key + ".json"), {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": "aquatic:item/" + key, "layer1": "aquatic:item/" + key + "_core"},
            })
            write_json(os.path.join(PACK, "assets", "aquatic", "items", key + ".json"), {
                "model": {"type": "minecraft:model", "model": "aquatic:item/" + key,
                          "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": 16777215}]},
            })


# ---------------------------------------------------------------- animated fruits (a preview of their power)

FRUIT_FRAMES = 8


def fruit_anim(main, swirl, leaf="#3f9b3a", outline_col="#2a1626", theme=None):
    """Animation frames of a fruit: the swirl slowly turns, plus an effect that shows its power."""
    frames = []
    for f in range(FRUIT_FRAMES):
        img = fruit(main, swirl, leaf, outline_col, phase=f / FRUIT_FRAMES)
        t = f / FRUIT_FRAMES
        if theme == "frost":  # glittering frost around it
            for k in range(3):
                a = 2 * math.pi * (t + k / 3)
                x, y = int(16 + math.cos(a) * 13), int(18 + math.sin(a) * 12)
                put(img, x, y, WHITE)
                if (f + k) % 2 == 0:
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        put(img, x + dx, y + dy, rgb("#bfe9ff"))
        elif theme == "fire":  # flickering flames on top
            for x in range(11, 21):
                h = 1 + ((x * 7 + f * 3) % 4)
                for k in range(h):
                    put(img, x, 9 - k, rgb("#ffd34d") if k < h - 1 else rgb("#ff6a1a"))
        elif theme == "thunder":  # sparks jumping around it
            if f in (1, 2, 5, 6):
                ox = 4 if f < 4 else 24
                for k in range(5):
                    put(img, ox + (k % 2), 12 + k * 2, rgb("#fff35c"))
                    put(img, ox + 1 - (k % 2), 13 + k * 2, rgb("#fff9b0"))
        elif theme == "light":  # pulsing glow and little rays
            pulse = 0.12 + 0.12 * math.sin(t * 2 * math.pi)
            px = img.load()
            for y in range(N):
                for x in range(N):
                    r, g, b, a = px[x, y]
                    if a:
                        px[x, y] = mix((r, g, b), WHITE, pulse) + (a,)
            for (x, y) in ((3, 18), (28, 18), (16, 30)) if f % 2 == 0 else ((5, 10), (26, 10), (16, 5)):
                put(img, x, y, rgb("#fff6b0"))
        elif theme == "water":  # bubbles rising
            for k in range(3):
                y = int(28 - ((t * 20 + k * 7) % 20))
                x = 5 + k * 10
                put(img, x, y, rgb("#a9dcff"))
                put(img, x + 1, y, rgb("#ffffff"))
        elif theme == "wind":  # streaks of wind blowing past
            for k in range(2):
                y = 12 + k * 9
                x0 = int((t * 32 + k * 14) % 32)
                for x in range(x0, min(N, x0 + 6)):
                    if not opaque(img, x, y):
                        put(img, x, y, rgb("#e0f7fa"))
        elif theme == "venom":  # a drop of venom dripping
            y = 29 + (f % 4) // 2
            put(img, 22, y - 1, rgb("#7dcf3a"))
            put(img, 22, y, rgb("#a6ff5c"))
        elif theme == "sand":  # grains trickling down
            for k in range(4):
                y = int(20 + ((t * 12 + k * 3) % 12))
                put(img, 5 + k * 7, y, rgb("#e2c275"))
        elif theme == "void":  # dark wisps circling
            for k in range(2):
                a = 2 * math.pi * (t + k / 2)
                x, y = int(16 + math.cos(a) * 14), int(18 + math.sin(a) * 13)
                put(img, x, y, rgb("#9d4dff"))
                put(img, x, y + 1, rgb("#3a2a52"))
        elif theme == "bounce":  # the fruit hops on a little spring
            dy = (0, -1, -2, -2, -1, 0, 1, 0)[f]
            hop = new()
            hop.paste(img, (0, dy), img)
            img = hop
            if dy > 0:  # squashed: little motion lines under it
                for x in (10, 13, 19, 22):
                    put(img, x, 31, rgb("#ffc2e3"))
            elif dy < -1:
                for x in (12, 16, 20):
                    put(img, x, 30, rgb("#ffd6ec"), 160)
        elif theme == "ghost":  # fades in and out, with ghostly sparkles
            fade = 0.62 + 0.38 * (0.5 + 0.5 * math.cos(t * 2 * math.pi))
            px = img.load()
            for y in range(N):
                for x in range(N):
                    r, g, b, a = px[x, y]
                    if a:
                        px[x, y] = (r, g, b, int(a * fade))
            for k in range(3):
                a = 2 * math.pi * (t * 0.5 + k / 3)
                x, y = int(16 + math.cos(a) * 12), int(17 - ((t * 10 + k * 4) % 10) + math.sin(a) * 3)
                put(img, x, y, WHITE, 220)
                if (f + k) % 3 == 0:
                    put(img, x + 1, y, rgb("#cfe6ff"), 140)
        elif theme == "dust":  # heavy stomps that kick up dust
            r = (f % 4) * 0.9 + 0.6
            alpha = 220 - (f % 4) * 50
            for cx in (5, 26):
                for y in range(N):
                    for x in range(N):
                        if (x - cx) ** 2 + (y - 28) ** 2 <= r * r and not opaque(img, x, y):
                            put(img, x, y, rgb("#c9a27a") if (x + y) % 2 else rgb("#a07850"), alpha)
        elif theme == "pebble":  # a glint runs over its stony skin, pebbles circle it
            spots = ((12, 14), (18, 13), (21, 18), (15, 21), (11, 19), (19, 23), (14, 17), (20, 15))
            gx, gy = spots[f]
            if opaque(img, gx, gy):
                put(img, gx, gy, WHITE)
                for dx, dy2 in ((1, 0), (0, 1)):
                    if opaque(img, gx + dx, gy + dy2):
                        put(img, gx + dx, gy + dy2, rgb("#dfe7ec"))
            for k in range(2):
                a = 2 * math.pi * (t + k / 2)
                x, y = int(16 + math.cos(a) * 14), int(18 + math.sin(a) * 12)
                put(img, x, y, rgb("#8e9ba3"))
                put(img, x + 1, y, rgb("#5c6b73"))
        frames.append(img)
    return frames


# ---------------------------------------------------------------- animated weapons: a shine sweeps over them

SHINE_FRAMES = 12


def shimmer(img, glow=(), band=3.2, strength=0.6):
    """Frames of a weapon: a band of light sweeps along it (from the handle to the tip), then rests;
    pixels in one of the glow colours (runes, gems) pulse softly."""
    glow = [rgb(c) if isinstance(c, str) else c for c in glow]
    base = img.load()
    edge = set()
    for y in range(N):
        for x in range(N):
            if base[x, y][3] and any(not (0 <= x + dx < N and 0 <= y + dy < N) or not base[x + dx, y + dy][3]
                                     for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                edge.add((x, y))  # the outline: stays dark
    frames = []
    for f in range(SHINE_FRAMES):
        out = img.copy()
        px = out.load()
        centre = -40 + f * 16  # along x - y: the blade runs bottom-left to top-right
        pulse = 0.5 + 0.5 * math.sin(f / SHINE_FRAMES * 2 * math.pi)
        for y in range(N):
            for x in range(N):
                r, g, b, a = base[x, y]
                if not a or (x, y) in edge:
                    continue
                col = (r, g, b)
                if col in glow:
                    col = mix(col, WHITE, 0.45 * pulse)
                d = abs((x - y) - centre)
                if d < band:
                    col = mix(col, WHITE, strength * (1 - d / band))
                px[x, y] = col + (a,)
        frames.append(out)
    return frames


def crate_key(metal, dark, gem, glow):
    """A big ornate key for a crate: a ring bow with a gem, a long shaft and teeth (diagonal like a tool)."""
    img = new()
    metal, dark, gem, glow = rgb(metal), rgb(dark), rgb(gem), rgb(glow)
    origin = (9.5, 22.5)
    for y in range(N):
        for x in range(N):
            s, t = blade_frame(x, y, origin)
            d = math.hypot(s + 4.5, t)                        # the bow: a ring
            if 2.6 <= d <= 5.2:
                put(img, x, y, metal if (s + t) < -4.5 else (shade(metal, -0.25) if d > 4.4 else shade(metal, 0.2)))
            if d < 1.9:
                put(img, x, y, glow if d < 1.0 else gem)
            if -0.5 <= s <= 18 and abs(t) <= 1.0:              # the shaft
                put(img, x, y, shade(metal, 0.25) if t < 0 else dark)
            if 0.5 <= s <= 2.0 and abs(t) <= 1.8:              # collar
                put(img, x, y, shade(metal, -0.1))
            for (a, b_, depth) in ((12.5, 14.5, 3.6), (15.5, 17.5, 2.6), (18, 19.5, 3.2)):  # teeth
                if a <= s <= b_ and 0.8 <= t <= depth:
                    put(img, x, y, metal if t < depth - 0.8 else dark)
    return outline(img, rgb("#1a1208"))


# ---------------------------------------------------------------- crate blocks: 3D treasure chests on nether blocks (the Nether is off)

CRATE_BLOCKS = {  # vanilla block -> (tier, wood, dark wood, metal, dark metal, gem, gem glow)
    "nether_quartz_ore": ("sailor", "#8a6a43", "#4f3a22", "#c3ccd2", "#5f6a72", "#2fb3c9", "#c8fbff"),
    "nether_gold_ore": ("captain", "#6a4326", "#341f0e", "#f2c230", "#8a5c0e", "#c0392b", "#ffc4b8"),
    "shroomlight": ("kraken", "#3d2b57", "#1d1330", "#3fd8c4", "#16665c", "#c47cff", "#f5e6ff"),
}
CHEST_PX = 32  # texture size of the chest parts (16 model units -> 2 pixels each, like the item icons)


def _noise(x, y, seed):
    h = (x * 73856093) ^ (y * 19349663) ^ (seed * 83492791)
    h ^= h >> 13
    return ((h * 0x5bd1e995) & 0xffff) / 65535.0


def chest_wood(wood, dark, seed, vertical=False):
    return forge.planks(wood, dark, seed, vertical, CHEST_PX)


def chest_metal(metal, dark, seed, rivets=True):
    return forge.plate(metal, dark, seed, rivets, CHEST_PX)


def chest_lock(metal, dark, gem, glow, frame):
    return forge.lock(metal, dark, gem, glow, frame, CHEST_PX)


def crate_blocks():
    """Each crate block is a treasure chest in the style of today's Minecraft: 16 px textures with soft, low-contrast
    planks, a metal frame with rivets painted on, a lid rim and a latch with a gem (it pulses) that stand out."""
    tex = os.path.join(PACK, "assets", "aquatic", "textures", "block")
    os.makedirs(tex, exist_ok=True)
    for old in os.listdir(tex):                                 # textures of the older chest designs
        if old.startswith(("chest_", "crate_")):
            os.remove(os.path.join(tex, old))
    for block, (tier, wood, dark, metal, metal_d, gem, glow) in CRATE_BLOCKS.items():
        wood, metal, gem, glow = (rgb(c) for c in (wood, metal, gem, glow))
        seed = len(tier) * 7
        forge.chest_side(wood, metal, seed).save(os.path.join(tex, "chest_%s_side.png" % tier))
        forge.chest_top(wood, metal, seed).save(os.path.join(tex, "chest_%s_top.png" % tier))
        forge.chest_top(wood, metal, seed, bottom=True).save(os.path.join(tex, "chest_%s_bottom.png" % tier))
        strip = Image.new("RGBA", (16, 16 * 8))
        for f in range(8):
            strip.paste(forge.chest_latch(metal, gem, glow, f), (0, f * 16))
        lock_path = os.path.join(tex, "chest_%s_latch.png" % tier)
        strip.save(lock_path)
        write_json(lock_path + ".mcmeta", {"animation": {"frametime": 4, "interpolate": True}})

        def box(frm, to, faces):
            return {"from": frm, "to": to, "faces": {k: ({"texture": "#" + v[0], "uv": v[1]} if isinstance(v, tuple)
                                                         else {"texture": "#" + v}) for k, v in faces.items()}}

        # The body is the whole block: the game does not draw the faces of blocks that touch it.
        el = [box([0, 0, 0], [16, 16, 16], {"north": "side", "south": "side", "east": "side", "west": "side",
                                             "up": "top", "down": "bottom"}),
              # the lid's rim stands out a little, all the way round (rows 5-6 of the side texture)
              box([-0.3, 9.5, -0.3], [16.3, 11.0, 16.3], {f: ("side", [0, 5, 16, 6.5]) for f in ("north", "south", "east", "west")}
                  | {"up": ("side", [0, 5, 16, 6]), "down": ("side", [0, 6, 16, 7])})]
        for z0, z1 in ((-0.7, 0.0), (16.0, 16.7)):               # a latch on the front and the back
            el.append(box([6, 7.5, z0], [10, 12.5, z1], {"north": ("latch", [0, 0, 4, 5]), "south": ("latch", [0, 0, 4, 5]),
                                                         "east": ("latch", [3, 0, 4, 5]), "west": ("latch", [0, 0, 1, 5]),
                                                         "up": ("latch", [0, 0, 4, 1]), "down": ("latch", [0, 4, 4, 5])}))
        t = lambda n: "aquatic:block/chest_%s_%s" % (tier, n)
        write_json(os.path.join(PACK, "assets", "minecraft", "models", "block", block + ".json"), {
            "parent": "minecraft:block/block",
            "textures": {"particle": t("side"), "side": t("side"), "top": t("top"), "bottom": t("bottom"), "latch": t("latch")},
            "elements": el,
            "display": {
                "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
                "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
                "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
                "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
                "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
                "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
            },
        })


def harpoon():
    """A whaler's harpoon: an oak shaft with a rope grip and a barbed steel head."""
    img = new()
    wood, rope, steel, light, dark = rgb("#8b5a2b"), rgb("#d8c39a"), rgb("#aeb8bf"), rgb("#eef6fa"), rgb("#5f6a72")
    origin = (6.5, 25.5)
    for y in range(N):
        for x in range(N):
            s, t = blade_frame(x, y, origin)
            if -3 <= s <= 22 and abs(t) <= 0.9:
                put(img, x, y, shade(wood, 0.2 if t < 0 else -0.2))
            if 2 <= s <= 7 and abs(t) <= 1.3:
                put(img, x, y, shade(rope, 0.15 if int(s * 1.5) % 2 == 0 else -0.2))
            if 20 <= s <= 22.5 and abs(t) <= 1.3:  # steel socket
                put(img, x, y, dark)
            if 22.5 <= s <= 31:  # the head
                w = 2.4 * (31 - s) / 8.5
                if abs(t) <= w:
                    put(img, x, y, light if t < -w * 0.3 else (steel if t < w * 0.4 else dark))
            for side in (-1, 1):  # two barbs pointing back
                bs = 22.5 + (abs(t) - 1) * 0.9
                if 1.2 <= abs(t) <= 3.6 and t * side > 0 and bs - 1.1 <= s <= bs + 0.4:
                    put(img, x, y, steel if side < 0 else dark)
    return outline(img, rgb("#14202a"))


def coral_scimitar():
    """A curved blade of living coral with little polyps along it."""
    def polyps(s_, t_, w_):
        if int(s_ * 1.3) % 4 == 1 and abs(t_) < 0.7:
            return rgb("#fff0e0")
        return None
    return blade("#ffd1b8", "#ff9a76", "#ff6b57", "#b8324a", "#f2c230", "#5a2a1a", "#ff9a76",
                 curve=4.0, width=2.6, tip=6, pattern=polyps, outline_col="#3a0d14")


def abyssal_anchor():
    """A ship's anchor of Abyssal Iron: ring, stock, shank and two curved arms with flukes."""
    img = new()
    iron, light, dark, rune = rgb("#2f8f86"), rgb("#9fe8de"), rgb("#174a45"), rgb("#7dfff0")
    origin = (11.5, 20.5)
    for y in range(N):
        for x in range(N):
            s, t = blade_frame(x, y, origin)
            col = None
            if -7 <= s <= 14 and abs(t) <= 1.3:  # shank
                col = light if t < -0.5 else (iron if t < 0.6 else dark)
                if any(abs(s - r) < 0.6 for r in (0, 5, 10)) and abs(t) < 0.8:
                    col = rune
            if 10.5 <= s <= 12.2 and abs(t) <= 5.5:  # stock (cross bar)
                col = shade(iron, 0.3 if s > 11.4 else -0.2)
            d = math.hypot(s - 16.5, t)
            if 1.3 <= d <= 2.9:  # ring
                col = light if s > 16.5 else iron
            d = math.hypot(s + 1, t)
            if 6.0 <= d <= 7.9 and s <= -1.5:  # curved arms
                col = light if d < 6.7 else dark
            for side in (-1, 1):  # flukes at the end of each arm
                fs, ft = -2.2, 7.6 * side
                if abs(s - fs) + abs(t - ft) * 0.8 <= 2.4:
                    col = shade(iron, 0.25 if side < 0 else -0.15)
            if col is not None:
                put(img, x, y, col)
    return outline(img, rgb("#071c1a"))


def deepcore_pickaxe():
    """A pickaxe with a dark steel head and a glowing deep-sea gem in its centre."""
    img = new()
    wood, band, steel, light, dark = rgb("#5a3a22"), rgb("#9fe8de"), rgb("#5d7d9a"), rgb("#cfe3f2"), rgb("#2b3d52")
    gem, gem_l = rgb("#3fd8ff"), rgb("#c8fbff")
    origin = (8.5, 23.5)
    for y in range(N):
        for x in range(N):
            s, t = blade_frame(x, y, origin)
            if -8 <= s <= 18 and abs(t) <= 1.1:  # handle
                c = shade(wood, 0.2 if t < 0 else -0.2)
                if any(abs(s - b) < 0.6 for b in (-5, 0, 6)):
                    c = band
                put(img, x, y, c)
            arc = 19.0 - 0.075 * t * t  # the head curves down at both ends
            half = 1.9 * (1 - (abs(t) / 11.5) ** 2) + 0.35
            if abs(t) <= 11 and abs(s - arc) <= half:
                put(img, x, y, light if s > arc + half * 0.3 else (steel if s > arc - half * 0.4 else dark))
            dg = (s - 19) ** 2 + t * t
            if dg <= 3.0:
                put(img, x, y, gem_l if dg <= 0.7 else gem)
    return outline(img, rgb("#0b1622"))


# ---------------------------------------------------------------- Tidewatch illagers (vindicator / pillager skin)

# ---------------------------------------------------------------- Tidewatch headgear: 3D hats and faces worn on the head
# Mobs draw what they wear on their head as its item model ("head" view), the model's centre at the middle of the
# head: an illager head (10 px tall) covers about x/z 1.6..14.4 and y 1.6..17.6 here, its face at z = 1.6 (north).
# Every variant is one item model: a hat plus (maybe) a mustache, a beard or an eyepatch.

HEADGEAR_COLOURS = {
    "navy": "#1d3a6b", "navy_dark": "#14284d", "black": "#1b1b20", "gold": "#e2b33c", "white": "#ece8dc",
    "silver": "#c9ccd1", "red": "#b3262b", "leather": "#2a1d14",
    "hair_brown": "#5a3a22", "hair_black": "#1e1a17", "hair_ginger": "#b5531f", "hair_grey": "#8d8a86", "hair_white": "#e6e2da",
}


def headgear_textures():
    tex = os.path.join(PACK, "assets", "aquatic", "textures", "item", "headgear")
    os.makedirs(tex, exist_ok=True)
    for name, col in HEADGEAR_COLOURS.items():
        c = rgb(col)
        img = Image.new("RGBA", (32, 32))
        p = img.load()
        hair = name.startswith("hair")
        for y in range(32):
            for x in range(32):
                if hair:     # strands
                    k = 0.12 * math.sin(x * 1.3 + _noise(x // 3, y, 4) * 3) + (_noise(x, y, 5) - 0.5) * 0.12
                elif name in ("gold", "silver"):
                    k = 0.12 * math.sin((x + y) * 0.6) + (_noise(x, y, 6) - 0.5) * 0.05
                else:        # felt and wool
                    k = (0.03 if (x + y) % 2 == 0 else -0.02) + (_noise(x, y, 7) - 0.5) * 0.09
                edge = min(x, y, 31 - x, 31 - y)
                if edge == 0 and not hair:
                    k -= 0.12
                p[x, y] = shade(c, k) + (255,)
        img.save(os.path.join(tex, name + ".png"))


def _hg(frm, to, t, rot=None):
    e = {"from": frm, "to": to, "faces": {f: {"texture": "#" + t} for f in ("north", "south", "east", "west", "up", "down")}}
    if rot:
        e["rotation"] = rot
    return e


def hat_elements(kind):
    if kind == "shako":       # riflemen: a tall cap with a peak, a badge and a pompom
        return [
            _hg([3.2, 17.4, 3.2], [12.8, 25.4, 12.8], "navy"),
            _hg([3.0, 17.4, 3.0], [13.0, 18.6, 13.0], "white"),
            _hg([3.0, 24.6, 3.0], [13.0, 25.6, 13.0], "white"),
            _hg([3.2, 17.4, 0.8], [12.8, 18.1, 3.2], "leather"),
            _hg([6.8, 20.0, 2.8], [9.2, 23.2, 3.2], "silver"),
            _hg([6.8, 25.6, 6.8], [9.2, 28.0, 9.2], "white"),
        ]
    if kind == "tricorn":     # lieutenants: a tricorn with gold edging
        return [
            _hg([3.6, 17.4, 3.6], [12.4, 21.2, 12.4], "navy_dark"),
            _hg([0.6, 17.0, 0.6], [15.4, 17.8, 15.4], "navy_dark"),
            _hg([0.6, 17.8, 13.8], [15.4, 20.4, 15.4], "navy_dark"),
            _hg([0.6, 17.8, 3.0], [2.2, 20.4, 13.8], "navy_dark"),
            _hg([13.8, 17.8, 3.0], [15.4, 20.4, 13.8], "navy_dark"),
            _hg([2.2, 17.8, 0.6], [13.8, 18.8, 2.2], "navy_dark"),
            _hg([0.6, 20.4, 13.8], [15.4, 20.8, 15.4], "gold"),
            _hg([0.6, 20.4, 3.0], [2.2, 20.8, 13.8], "gold"),
            _hg([13.8, 20.4, 3.0], [15.4, 20.8, 13.8], "gold"),
            _hg([7.0, 18.8, 0.4], [9.0, 20.6, 0.8], "gold"),
        ]
    # the Warden Captain: a wide bicorne with gold edging, a cockade and a white plume
    return [
        _hg([-1.5, 17.0, 6.0], [17.5, 18.6, 10.0], "black"),
        _hg([0.0, 18.6, 6.3], [16.0, 21.0, 9.7], "black"),
        _hg([2.5, 21.0, 6.6], [13.5, 23.0, 9.4], "black"),
        _hg([5.0, 23.0, 6.9], [11.0, 24.0, 9.1], "black"),
        _hg([-1.5, 17.0, 5.7], [17.5, 17.6, 6.0], "gold"),
        _hg([-1.5, 17.0, 10.0], [17.5, 17.6, 10.3], "gold"),
        _hg([7.0, 19.0, 5.6], [9.0, 21.2, 6.0], "red"),
        _hg([7.4, 19.4, 5.4], [8.6, 20.8, 5.6], "gold"),
        _hg([7.0, 24.0, 7.0], [9.0, 27.6, 9.0], "white"),
        _hg([8.0, 26.8, 8.6], [10.4, 28.6, 10.2], "white"),
    ]


def face_elements(kind, colour):
    h = "hair_" + (colour or "black")
    if kind == "mustache":
        return [
            _hg([4.6, 3.9, 1.0], [11.4, 5.3, 1.6], h),
            _hg([3.4, 4.6, 1.0], [4.6, 6.2, 1.6], h),
            _hg([11.4, 4.6, 1.0], [12.6, 6.2, 1.6], h),
        ]
    if kind == "beard":
        return [
            _hg([3.2, 1.0, 0.9], [12.8, 4.6, 1.6], h),
            _hg([5.0, 0.0, 1.0], [11.0, 1.0, 1.6], h),
            _hg([4.4, 4.4, 0.9], [11.6, 5.6, 1.6], h),
            _hg([1.0, 1.6, 1.6], [1.6, 7.0, 7.0], h),
            _hg([14.4, 1.6, 1.6], [15.0, 7.0, 7.0], h),
        ]
    if kind == "eyepatch":
        return [
            _hg([9.4, 7.8, 1.0], [12.4, 10.4, 1.6], "black"),
            _hg([4.0, 10.4, 1.3], [9.6, 11.0, 1.6], "black"),
            _hg([1.3, 10.4, 1.6], [1.6, 11.0, 14.4], "black"),
            _hg([14.4, 10.4, 1.6], [14.7, 11.0, 14.4], "black"),
            _hg([1.3, 10.4, 14.4], [14.7, 11.0, 14.7], "black"),
            _hg([12.4, 9.6, 1.3], [14.4, 10.2, 1.6], "black"),
        ]
    return []


# variant id -> (hat, face, face colour)
HEADGEAR = {
    "tw_shako": ("shako", None, None),
    "tw_shako_mustache_brown": ("shako", "mustache", "brown"),
    "tw_shako_mustache_black": ("shako", "mustache", "black"),
    "tw_shako_mustache_ginger": ("shako", "mustache", "ginger"),
    "tw_shako_beard_brown": ("shako", "beard", "brown"),
    "tw_shako_eyepatch": ("shako", "eyepatch", None),
    "tw_tricorn": ("tricorn", None, None),
    "tw_tricorn_mustache_grey": ("tricorn", "mustache", "grey"),
    "tw_tricorn_beard_black": ("tricorn", "beard", "black"),
    "tw_tricorn_beard_ginger": ("tricorn", "beard", "ginger"),
    "tw_tricorn_eyepatch": ("tricorn", "eyepatch", None),
    "tw_bicorne_mustache_white": ("bicorne", "mustache", "white"),
    "tw_bicorne_beard_grey": ("bicorne", "beard", "grey"),
    "tw_bicorne_eyepatch": ("bicorne", "eyepatch", None),
}


def tidewatch_headgear():
    headgear_textures()
    textures = {n: "aquatic:item/headgear/" + n for n in HEADGEAR_COLOURS}
    textures["particle"] = textures["navy"]
    for vid, (hat, face, colour) in HEADGEAR.items():
        write_json(os.path.join(PACK, "assets", "aquatic", "models", "item", vid + ".json"), {
            "textures": textures,
            "elements": hat_elements(hat) + face_elements(face, colour),
            "display": {
                "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
                "gui": {"rotation": [20, 200, 0], "translation": [0, -6, 0], "scale": [0.55, 0.55, 0.55]},
                "ground": {"rotation": [0, 0, 0], "translation": [0, -2, 0], "scale": [0.3, 0.3, 0.3]},
            },
        })
        write_json(os.path.join(PACK, "assets", "aquatic", "items", vid + ".json"), {
            "model": {"type": "minecraft:model", "model": "aquatic:item/" + vid},
        })


def illager_hd(coat, trim, belts, hat, seed=1):
    """A 128x128 (2x) illager skin in Tidewatch uniform: vanilla illager layout, every box drawn per face with
    light from above (tops brighter, sides and backs darker), fabric texture, folds and the uniform's details."""
    S = 2
    img = Image.new("RGBA", (64 * S, 64 * S), (0, 0, 0, 0))
    px = img.load()
    coat, trim, belts, hat = rgb(coat), rgb(trim), rgb(belts), rgb(hat)
    skin, skin_d, skin_l = rgb("#c49a74"), rgb("#9c7351"), rgb("#ddb48e")
    hair, white, boot = rgb("#3b2a1e"), rgb("#ece8dc"), rgb("#17171a")
    LIGHT = {"top": 0.14, "bottom": -0.3, "front": 0.0, "right": -0.1, "left": -0.1, "back": -0.18}

    def fabric(c, x, y, k=0.0):
        n = _noise(x, y, seed)
        weave = 0.035 if (x + y) % 2 == 0 else -0.02
        return shade(c, k + weave + (n - 0.5) * 0.08)

    def box(u, v, w, h, d, paint):
        """paint(face, fx, fy, fw, fh) -> colour or None, in 2x pixels on that face."""
        faces = {
            "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
            "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
            "left": (u + d + w, v + d, d, h), "back": (u + d + w + d, v + d, w, h),
        }
        for face, (fu, fv, fw, fh) in faces.items():
            for fy in range(fh * S):
                for fx in range(fw * S):
                    c = paint(face, fx, fy, fw * S, fh * S)
                    if c is not None:
                        c = shade(c, LIGHT[face])
                        px[fu * S + fx, fv * S + fy] = c + (255,)

    # ---- head (8x10x8): tricorn at the top, a weathered face, hair at the back
    def head(face, x, y, w, h):
        if face == "top":
            return fabric(hair, x, y, 0.05)                    # short hair (the hats are 3D, worn on top)
        if face == "bottom":
            return skin_d
        if y < 4 or (face != "front" and y < 7):
            return fabric(hair, x, y)
        if face == "back":
            return fabric(hair, x, y, -0.05) if y < 17 else skin_d
        if face in ("right", "left"):
            if y < 13 and ((face == "right" and x < 5) or (face == "left" and x > w - 6)):
                return fabric(hair, x, y)                  # hair at the sides of the back
            return shade(skin, -0.05 + (_noise(x, y, 7) - 0.5) * 0.06)
        # the face
        c = shade(skin, (_noise(x, y, 9) - 0.5) * 0.05)
        if y in (8, 9) and 1 <= x <= 14:
            c = hair if not (6 <= x <= 9) or y == 9 else c   # one heavy brow
        if y in (10, 11):
            if x in (3, 4, 11, 12):
                c = white
            if x in (5, 10):
                c = rgb("#1d2e3d") if y == 11 else rgb("#33516b")
            if x in (2, 13):
                c = skin_d
        if y in (12, 13) and x in (2, 3, 12, 13):
            c = shade(skin, 0.06)                          # cheeks
        if 11 <= y <= 13 and x in (7, 8):
            c = shade(skin, 0.04 if x == 7 else -0.06)     # a small, normal nose
        if y == 13 and x in (6, 9):
            c = shade(skin, -0.12)
        if y == 13 and x in (7, 8):
            c = shade(skin, 0.1)
        if 14 <= y <= 15 and 4 <= x <= 11:
            c = shade(skin, -0.12) if y == 14 else skin_d  # upper lip shadow
        if y == 16 and 5 <= x <= 10:
            c = rgb("#5a3a2a")                             # mouth
        if y >= 17:
            c = shade(skin_d, 0.05) if (x + y) % 3 else skin_d   # stubble
        return c
    box(0, 0, 8, 10, 8, head)

    # ---- no nose box: left transparent, so the big illager nose is invisible (a small one is painted on the face)

    # ---- body (8x12x6): the uniform jacket with crossed belts, buttons and a collar
    def body(face, x, y, w, h):
        if face == "top":
            return trim if (x < 3 or x > w - 4) else fabric(coat, x, y)     # epaulettes on the shoulders
        if face == "bottom":
            return fabric(coat, x, y, -0.1)
        c = fabric(coat, x, y)
        if y < 3:
            c = shade(white, -0.05) if face == "front" and 5 <= x <= 10 else trim   # collar
        if face == "front":
            d1, d2 = abs((x - 1) - y * 0.6), abs((w - 2 - x) - y * 0.6)
            if (d1 < 1.6 or d2 < 1.6) and 3 <= y <= 19:
                c = shade(belts, 0.08 if min(d1, d2) < 0.7 else -0.06)     # crossed belts
            if 9 <= y <= 12 and 6 <= x <= 9:
                c = rgb("#c9ccd1") if (x in (6, 9) or y in (9, 12)) else rgb("#8d939b")   # buckle
            if x in (4, 11) and y % 5 == 1 and y > 3:
                c = shade(trim, 0.3)                       # gold buttons
            if y >= 21:
                c = shade(rgb("#3a2414"), 0.05)            # leather belt
                if 6 <= x <= 9:
                    c = rgb("#c9a227")
        if face == "back" and x in (7, 8) and y > 3:
            c = fabric(coat, x, y, -0.1)                   # a seam
        return c
    box(16, 20, 8, 12, 6, body)

    # ---- the long coat (8x20x6): skirts with a trimmed hem, split at the front and back
    def jacket(face, x, y, w, h):
        if face in ("top", "bottom"):
            return fabric(coat, x, y, -0.05)
        c = fabric(coat, x, y, -0.04 * (y / h))
        if y < 24:
            if face == "front":
                d1, d2 = abs((x - 1) - y * 0.6), abs((w - 2 - x) - y * 0.6)
                if (d1 < 1.6 or d2 < 1.6) and 3 <= y <= 19:
                    c = shade(belts, 0.08)
                if x in (4, 11) and y % 5 == 1 and y > 3:
                    c = shade(trim, 0.3)
            if 20 <= y <= 23:
                c = shade(rgb("#3a2414"), 0.05)
        if y >= 24:
            if face in ("front", "back") and x in (7, 8):
                c = shade(coat, -0.4)                      # the split between the coat tails
            if (y - 24) % 9 == 4:
                c = fabric(coat, x, y, -0.12)              # a fold
        if y >= h - 3:
            c = shade(trim, 0.1 if y == h - 3 else -0.05)  # trimmed hem
        return c
    box(0, 38, 8, 20, 6, jacket)

    # ---- arms (4x12x4, when attacking) and the crossed arms
    def arm(face, x, y, w, h):
        if face == "top":
            return trim                                    # epaulette
        if face == "bottom":
            return skin
        if y < 4:
            c = shade(trim, 0.15 if y < 2 else -0.05)      # epaulette
            if y >= 2 and x % 2 == 0:
                c = shade(trim, -0.2)                      # fringe
            return c
        if y >= h - 5:
            return shade(skin, -0.05 if face != "front" else 0.03)   # hand
        if h - 9 <= y < h - 5:
            return shade(trim, 0.1 if y == h - 9 else -0.05)         # cuff
        return fabric(coat, x, y)
    box(40, 46, 4, 12, 4, arm)
    box(44, 22, 4, 8, 4, lambda f, x, y, w, h: shade(skin, -0.03) if f == "bottom" or (y >= h - 4 and f != "top")
        else (shade(trim, 0.05) if (y >= h - 7 and f != "top") else (trim if f == "top" else fabric(coat, x, y))))
    box(40, 38, 8, 4, 4, lambda f, x, y, w, h: fabric(coat, x, y, -0.03))

    # ---- legs (4x12x4): white trousers with creases, polished boots
    def leg(face, x, y, w, h):
        if face == "bottom":
            return shade(boot, -0.1)
        if face == "top":
            return fabric(white, x, y)
        if y >= h - 9:
            c = boot
            if face == "front" and x in (2, 3) and y < h - 4:
                c = rgb("#4a4a52")                         # shine
            if y >= h - 2:
                c = rgb("#0b0b0d")                         # sole
            if y == h - 9:
                c = rgb("#2e2e33")                         # boot top
            return c
        c = fabric(white, x, y, -0.04)
        if face == "front" and x in (3, 4):
            c = shade(white, -0.1)                         # crease
        return c
    box(0, 22, 4, 12, 4, leg)
    return img

# ---------------------------------------------------------------- the Musket (3D)

MUSKET_DISPLAY = {  # a long gun: bigger in the hand than the pistols
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.6, 0.6, 0.6]},
    "thirdperson_righthand": {"rotation": [0, -105, 0], "translation": [1.9, 0.9, -3.0], "scale": [0.9, 0.9, 0.9]},
    "firstperson_righthand": {"rotation": [0, -90, 0], "translation": [5.0, 1.8, -4.5], "scale": [0.9, 0.9, 0.9]},
    "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
    "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}


def musket_3d():
    METAL, WOOD, BRASS, DARK = 0, 1, 2, 3
    stock_rot = {"angle": 22.5, "axis": "z", "origin": [11, 8, 8]}
    tex = parts_texture(["#8f96a3", "#8b5a2b", "#d9a521", "#2a2c30"])
    tex.save(os.path.join(PACK, "assets", "aquatic", "textures", "item", "musket_parts.png"))
    elements = [
        cube([0, 9.5, 7.5], [11, 10.75, 8.5], METAL),           # long barrel (muzzle to the left)
        cube([0, 9.25, 7.25], [0.75, 11, 8.75], BRASS),         # muzzle ring
        cube([2, 8.5, 7.4], [11, 9.6, 8.6], WOOD),              # long wooden fore-stock
        cube([4, 8.4, 7.3], [4.75, 11, 8.7], BRASS),            # barrel bands
        cube([8, 8.4, 7.3], [8.75, 11, 8.7], BRASS),
        cube([10, 7.5, 7], [13, 10.75, 9], WOOD),               # lock body
        cube([10.5, 8.75, 6.8], [12.5, 10.25, 9.2], BRASS),     # lock plates
        cube([11.5, 10.75, 7.6], [12.4, 12.5, 8.4], DARK),      # cock
        cube([11, 3.5, 7.2], [16, 8.5, 8.8], WOOD, stock_rot),  # shoulder stock
        cube([14.75, 3, 7], [16, 9, 9], BRASS, stock_rot),      # butt plate
        cube([9, 6.75, 7.6], [11, 7.25, 8.4], BRASS),           # trigger guard
        cube([10, 7.25, 7.8], [10.4, 8.5, 8.2], DARK),          # trigger
    ]
    write_json(os.path.join(PACK, "assets", "aquatic", "models", "item", "musket.json"), {
        "textures": {"parts": "aquatic:item/musket_parts", "particle": "aquatic:item/musket_parts"},
        "elements": elements,
        "display": MUSKET_DISPLAY,
    })
    write_json(os.path.join(PACK, "assets", "aquatic", "items", "musket.json"), {
        "model": {"type": "minecraft:model", "model": "aquatic:item/musket"},
    })


FRUITS = {
    "ghostglass": fruit_anim("#e8eef2", "#9aa7ad", outline_col="#3a444a", theme="ghost"),
    "riftshift": fruit_anim("#ff9aa8", "#ff4f6d", theme="void"),
    "hollowvoid": fruit_anim("#3a2a52", "#9d4dff", outline_col="#0d0714", theme="void"),
    "behemoth": fruit_anim("#b8743a", "#5a3418", theme="dust"),
    "galeborn": fruit_anim("#c9f3f7", "#3fb8c9", outline_col="#123a40", theme="wind"),
    "stoneskin": fruit_anim("#8e9ba3", "#5c6b73", outline_col="#1f262a", theme="pebble"),
    "tidecaller": fruit_anim("#8fd8ff", "#f2fbff", outline_col="#1d4f6e", theme="water"),
    "venomfang": fruit_anim("#7dcf3a", "#2f5d1a", leaf="#2f5d1a", outline_col="#13260a", theme="venom"),
    "dunemaw": fruit_anim("#e2c275", "#9c7a3c", outline_col="#3d2b10", theme="sand"),
    "luminara": fruit_anim("#fff6b0", "#ffb703", outline_col="#5a4a10", theme="light"),
    "springcoil": fruit_anim("#e86fb0", "#7a1f6e", theme="bounce"),
    "emberheart": fruit_anim("#ff7a1a", "#c4100c", theme="fire"),
    "rimefrost": fruit_anim("#a7e6ff", "#ffffff", leaf="#bfe9ff", outline_col="#123247", theme="frost"),
    "thunderveil": fruit_anim("#ffe14a", "#8a3cd6", outline_col="#3a2600", theme="thunder"),
}


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else "dev"
    write_json(os.path.join(PACK, "pack.mcmeta"), {
        "pack": {"pack_format": 46, "description": "Aquatic SMP - Sail. Claim. Conquer."},
    })
    item("conqueror_crown", crown())
    equipment("conqueror_crown", crown_worn())
    vfx_frames()
    flintlock_3d()
    musket_3d()
    # the Tidewatch: pillagers (riflemen) and vindicators (officers) in uniform
    ill = os.path.join(PACK, "assets", "minecraft", "textures", "entity", "illager")
    os.makedirs(ill, exist_ok=True)
    # officers (vindicators): dark navy with gold; riflemen (pillagers): blue with white (128x128, twice the detail)
    tidewatch_headgear()
    illager_hd("#14284d", "#e2b33c", "#e8e4d8", "#0f1d38", seed=3).save(os.path.join(ill, "vindicator.png"))
    illager_hd("#1d3a6b", "#e8e4d8", "#e8e4d8", "#16294a", seed=5).save(os.path.join(ill, "pillager.png"))
    hand_cannon_3d()
    # weapons, tools and keys: hand-drawn (pixelart.py), with a band of light that sweeps along them
    models = os.path.join(PACK, "assets", "aquatic", "models", "item")
    for name in forge.WEAPONS:                                  # drawn at 32x32, 3D in the hand
        img, grid, depth = forge.make(name)
        item(name, shimmer(img, strength=0.45), True, drawn=True)
        write_json(os.path.join(models, name + ".json"), forge.model("aquatic:item/" + name, grid, depth))
    for name, (rows, palette, edits, extra) in pixelart.WEAPONS.items():
        if name in forge.WEAPONS:
            continue
        img = pixelart.icon(rows, palette, edits, **extra)
        grid = pixelart.shade.grid
        glow = tuple(palette[k] for k in ("p", "P", "x") if k in palette)
        if name == "abyssal_shackles":
            item(name, img, drawn=True)
        else:
            item(name, shimmer(img, glow=glow, strength=0.5), True, drawn=True)
            write_json(os.path.join(models, name + ".json"), forge.model("aquatic:item/" + name, grid,
                                                                         {"a": 1.6, "b": 1.6, "c": 1.6, "d": 1.6}))
    crate_blocks()
    # drinks, bombs and small items: hand-drawn (pixelart.py)
    for name, (rows, palette, edits) in pixelart.MISC.items():
        item(name, pixelart.icon(rows, palette, edits), drawn=True)
    # tools and drinks
    # armour sets: hand-drawn icons (pixelart.py) and how they look when worn
    for name, (rows, palette) in pixelart.ARMOUR.items():
        item(name, pixelart.armour_icon(rows, palette), drawn=True)
    equipment("tidewarden", *plate_armor_layers("#5c6976", "#8a7045", "#6a9e99"))
    equipment("corsair", *armor_layers("#7e2a25", "#3a2318", "#b8933f", legs="#b49a74", boots="#2a1d14", hat="#2a1d16"))
    # Tidewatch uniforms (worn by the garrison)
    equipment("tidewatch", *armor_layers("#24334f", "#141d31", "#cfc8b4", legs="#d9d3c2", boots="#221c17", hat="#1f2a3d"))
    equipment("tidewatch_officer", *armor_layers("#1a2742", "#0e1628", "#b8933f", legs="#d9d3c2", boots="#221c17", hat="#1f2a3d"))
    equipment("tidewatch_captain", *armor_layers("#e4e0d6", "#8f7a4c", "#b8933f", legs="#d9d3c2", boots="#221c17", hat="#e4e0d6"))
    equipment("captains_coat", armor_layers("#2c2a30", "#7a2420", "#b8933f", hat="#1c1a1e")[0])
    for name, image in FRUITS.items():
        item(name, image)
    crown().resize((128, 128), Image.NEAREST).save(os.path.join(PACK, "pack.png"))

    os.makedirs(DIST, exist_ok=True)
    out = os.path.join(DIST, "AquaticSMP-ResourcePack.zip")
    # a fixed order and date, so an unchanged pack gives the same file (and SHA-1): players keep their copy
    entries = []
    for root, dirs, files in os.walk(PACK):
        dirs.sort()
        for f in sorted(files):
            full = os.path.join(root, f)
            entries.append((os.path.relpath(full, PACK).replace(os.sep, "/"), full))
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, full in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(full, "rb") as fh:
                z.writestr(info, fh.read())
    sha = hashlib.sha1(open(out, "rb").read()).hexdigest()
    # the plugin sends the pack of its own version with this SHA-1 (build the pack before the jar)
    baked = os.path.join(HERE, "..", "aquatic-events", "src", "main", "resources", "aquatic-pack.sha1")
    with open(baked, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(sha + "\n")
    print(out)
    print("sha1", sha)


if __name__ == "__main__":
    main()
