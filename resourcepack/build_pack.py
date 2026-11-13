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
            k = max(-0.62, min(0.45, (lam - 0.5) * 1.2))
            col = shade(main, k)
            r = math.sqrt(d2)
            ang = math.atan2(dy, dx)
            v = (r * 2.3 - ang / (2 * math.pi) + phase) % 1.0  # the abyss swirl: a spiral over the skin
            if 0.12 < r < 0.93 and v < 0.25:
                col = shade(swirl, k * 0.8)
            if r > 0.9:
                col = shade(col, -0.18)  # darker rim
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

def armor_icon(piece, base, trim, accent, style="plate"):
    """Item icons for helmets, chestplates, leggings and boots (style "plate" or "cloth")."""
    img = new()
    base, trim, accent = rgb(base), rgb(trim), rgb(accent)

    def inside(px, py):
        if piece == "helmet":
            if style == "tricorn":
                return (4 <= px <= 28 and 13 <= py <= 18 and abs(px - 16) <= 12 - (py - 13) * 0.3) or \
                       math.hypot(px - 16, (py - 13) * 1.3) <= 8 and py <= 14
            return math.hypot(px - 16, (py - 14) * 1.05) <= 11 and py <= 24 and not (12 <= px <= 20 and 17 <= py <= 24)
        if piece == "chestplate":
            return (6 <= px <= 26 and 6 <= py <= 27 and not (12 <= px <= 20 and py <= 9)) or \
                   (2 <= px <= 30 and 6 <= py <= 14 and not (8 <= px <= 24 and py > 9))
        if piece == "leggings":
            return (8 <= px <= 24 and 5 <= py <= 11) or (8 <= px <= 15 and 11 <= py <= 28) or (17 <= px <= 24 and 11 <= py <= 28)
        return (5 <= px <= 14 and 12 <= py <= 26) or (5 <= px <= 18 and 22 <= py <= 27) or \
               (18 <= px <= 27 and 12 <= py <= 26) or (18 <= px <= 31 and 22 <= py <= 27)

    for y in range(N):
        for x in range(N):
            px, py = x + 0.5, y + 0.5
            if not inside(px, py):
                continue
            k = 0.3 if px < 13 else (-0.25 if px > 20 else 0.05)
            col = shade(base, k)
            edge = not all(inside(px + dx, py + dy) for dx, dy in ((1.5, 0), (-1.5, 0), (0, 1.5), (0, -1.5)))
            if edge:
                col = shade(trim, k * 0.6)
            put(img, x, y, col)
    if piece == "chestplate":
        for (x, y) in ((15, 15), (16, 15), (15, 16), (16, 16), (15, 20), (16, 20), (15, 21), (16, 21)):
            put(img, x, y, accent)  # emblem / buttons
    if piece == "helmet" and style != "tricorn":
        for x in range(10, 23):
            put(img, x, 13, accent)  # visor line
    if piece == "helmet" and style == "tricorn":
        for x in range(7, 26):
            put(img, x, 15, accent)  # gold band
    if piece == "leggings":
        for x in range(8, 25):
            put(img, x, 8, accent)  # belt
    if piece == "boots":
        for x in list(range(5, 15)) + list(range(18, 28)):
            put(img, x, 14, accent)  # cuffs
    return outline(img, rgb("#101418"))


def armor_layers(base, trim, accent, scale=2, legs=None, boots=None):
    """Worn armour textures (humanoid + humanoid_leggings layers) for a set, at 2x resolution."""
    W, H = 64 * scale, 32 * scale
    layer1 = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    layer2 = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    base, trim, accent = rgb(base), rgb(trim), rgb(accent)
    legs_col = rgb(legs) if legs else base
    boots_col = rgb(boots) if boots else base

    def box(img, u, v, w, h, d, band=None, body=None):
        body_col = body if body is not None else base
        """Paints the faces of a model box laid out like vanilla armour (u, v = top-left of its UV area)."""
        faces = [(u + d, v, w, d, 0.3), (u + d + w, v, w, d, -0.3),                 # top, bottom
                 (u, v + d, d, h, -0.1), (u + d, v + d, w, h, 0.12),                 # right, front
                 (u + d + w, v + d, d, h, -0.1), (u + d + w + d, v + d, w, h, -0.2)]  # left, back
        for (fx, fy, fw, fh, k) in faces:
            for yy in range(fy * scale, (fy + fh) * scale):
                for xx in range(fx * scale, (fx + fw) * scale):
                    rel_y = (yy - fy * scale) / max(1, fh * scale - 1)
                    col = shade(body_col, k + 0.15 * (0.5 - rel_y))
                    if xx in (fx * scale, (fx + fw) * scale - 1) or yy in (fy * scale, (fy + fh) * scale - 1):
                        col = shade(trim, k)
                    if band and band[0] <= rel_y <= band[1]:
                        col = shade(accent, k)
                    put(img, xx, yy, col)

    box(layer1, 0, 0, 8, 8, 8, band=(0.55, 0.65))       # helmet
    box(layer1, 16, 16, 8, 12, 4, band=(0.75, 0.82))    # chest
    box(layer1, 40, 16, 4, 12, 4, band=(0.0, 0.08))     # arms
    box(layer1, 0, 16, 4, 12, 4, band=(0.62, 0.68), body=boots_col)     # boots
    box(layer2, 16, 16, 8, 12, 4, band=(0.62, 0.7), body=legs_col)      # leggings: waist with a belt
    box(layer2, 0, 16, 4, 12, 4, band=(0.9, 1.0), body=legs_col)        # leggings: legs
    # an emblem on the chest front
    for yy in range(23 * scale, 26 * scale):
        for xx in range(23 * scale, 25 * scale):
            put(layer1, xx, yy, accent)
    return layer1, layer2


# ---------------------------------------------------------------- 3D models

GUN_DISPLAY = {  # like a normal held item (same as vanilla item/generated), but with depth
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
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


def model_3d(name, materials, elements):
    """Writes a block-style 3D item model (and its texture) for an item."""
    tex = parts_texture(materials)
    os.makedirs(os.path.join(PACK, "assets", "aquatic", "textures", "item"), exist_ok=True)
    tex.save(os.path.join(PACK, "assets", "aquatic", "textures", "item", name + "_parts.png"))
    write_json(os.path.join(PACK, "assets", "aquatic", "models", "item", name + ".json"), {
        "textures": {"parts": "aquatic:item/" + name + "_parts", "particle": "aquatic:item/" + name + "_parts"},
        "elements": elements,
        "display": GUN_DISPLAY,
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
    ])
    # an icon-style picture is not needed: the 3D model shows in the inventory too


# ---------------------------------------------------------------- pack

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)


def item(name, image, handheld=False):
    os.makedirs(os.path.join(PACK, "assets", "aquatic", "textures", "item"), exist_ok=True)
    path = os.path.join(PACK, "assets", "aquatic", "textures", "item", name + ".png")
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


def vfx_item(name, image):
    """An effect model whose colour is set by the plugin (tint from the item's custom model data)."""
    os.makedirs(os.path.join(PACK, "assets", "aquatic", "textures", "item"), exist_ok=True)
    image.save(os.path.join(PACK, "assets", "aquatic", "textures", "item", name + ".png"))
    write_json(os.path.join(PACK, "assets", "aquatic", "models", "item", name + ".json"), {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "aquatic:item/" + name},
    })
    write_json(os.path.join(PACK, "assets", "aquatic", "items", name + ".json"), {
        "model": {"type": "minecraft:model", "model": "aquatic:item/" + name,
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

def illager_skin(coat, trim, belts, hat_band):
    """A 64x64 illager texture (vanilla layout) dressed in a Tidewatch uniform."""
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    coat, trim, belts, band = rgb(coat), rgb(trim), rgb(belts), rgb(hat_band)
    skin, skin_d, hair = rgb("#c99a72"), rgb("#a87a55"), rgb("#3b2a1e")
    white, boot = rgb("#e8e4d8"), rgb("#1a1a1a")

    def rect(x, y, w, h, col):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                c = col(xx - x, yy - y) if callable(col) else col
                put(img, xx, yy, c)

    def cloth(base):
        return lambda x, y: shade(base, 0.06 if (x * 7 + y * 13) % 17 == 0 else (-0.05 if (x * 5 + y * 3) % 19 == 0 else 0))

    # head (8x10x8 at 0,0): a bicorne on top, a face in front, hair at the back
    rect(8, 0, 8, 8, lambda x, y: band if x in (0, 7) or y in (0, 7) else coat)      # top of the hat
    rect(16, 0, 8, 8, skin_d)                                                        # under the chin
    for fx in (0, 8, 16, 24):                                                        # the four sides
        rect(fx, 8, 8, 10, lambda x, y: coat if y < 2 else (band if y == 2 else skin))
    rect(24, 11, 8, 7, hair)                                                         # back of the head
    for (x, y, c) in ((9, 13, WHITE), (10, 13, rgb("#2b2b2b")), (13, 13, rgb("#2b2b2b")), (14, 13, WHITE),
                      (9, 12, hair), (10, 12, hair), (13, 12, hair), (14, 12, hair),
                      (10, 16, skin_d), (11, 16, skin_d), (12, 16, skin_d), (13, 16, skin_d)):
        put(img, x, y, c)                                                            # eyes, brows, mouth
    rect(24, 0, 8, 6, skin)                                                          # nose
    # body (8x12x6 at 16,20): coat with crossed belts, buttons and epaulettes
    rect(22, 20, 8, 6, lambda x, y: trim if x in (0, 7) else coat)                   # shoulders (epaulettes)
    rect(30, 20, 8, 6, coat)
    rect(16, 26, 6, 12, cloth(coat))
    rect(30, 26, 6, 12, cloth(coat))
    rect(36, 26, 8, 12, cloth(coat))
    rect(22, 26, 8, 12, cloth(coat))
    for k in range(8):                                                               # white cross belts
        put(img, 22 + k, 26 + k, belts)
        put(img, 29 - k, 26 + k, belts)
    for y in (28, 31, 34, 37):
        put(img, 25, y, trim)                                                        # gold buttons
    for x in range(22, 30):
        put(img, x, 37, trim)                                                        # belt line
    # long coat (jacket, 8x20x6 at 0,38): coat tails with a trimmed hem
    rect(0, 38, 28, 26, lambda x, y: (trim if y >= 24 else cloth(coat)(x, y)) if y >= 6 else coat)
    rect(9, 56, 2, 8, white)                                                         # trousers showing between the tails
    # legs (4x12x4 at 0,22): white trousers, black boots
    rect(0, 22, 16, 16, lambda x, y: boot if y >= 12 else (white if y >= 4 else white))
    rect(4, 22, 4, 4, white)
    # arms (4x12x4 at 40,46): sleeves with a cuff and hands
    rect(40, 46, 16, 16, lambda x, y: skin if y >= 14 else (trim if y == 13 else cloth(coat)(x, y)))
    rect(48, 46, 4, 4, skin)
    # crossed arms (44,22 and 40,38)
    rect(44, 22, 16, 12, lambda x, y: skin if y >= 10 else (trim if y == 9 else cloth(coat)(x, y)))
    rect(40, 38, 24, 8, cloth(coat))
    rect(47, 42, 2, 4, skin)
    rect(55, 42, 2, 4, skin)
    return img


# ---------------------------------------------------------------- the Musket (3D)

MUSKET_DISPLAY = {  # a long gun: bigger in the hand than the pistols
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.6, 0.6, 0.6]},
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.85, 0.85, 0.85]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.9, 0.9, 0.9]},
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
    "tidecaller": fruit_anim("#2e86de", "#a9dcff", outline_col="#0b2a4a", theme="water"),
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
    item("storm_saber", shimmer(storm_saber(), glow=("#fff35c",)), handheld=True)
    item("abyssal_baton", shimmer(abyssal_baton(), glow=("#7dfff0",)), handheld=True)
    vfx_item("vfx_slash", vfx_slash())
    vfx_item("vfx_ring", vfx_ring())
    vfx_item("vfx_flash", vfx_flash())
    flintlock_3d()
    musket_3d()
    # the Tidewatch: pillagers (riflemen) and vindicators (officers) in uniform
    ill = os.path.join(PACK, "assets", "minecraft", "textures", "entity", "illager")
    os.makedirs(ill, exist_ok=True)
    illager_skin("#14284d", "#e2b33c", "#e8e4d8", "#e2b33c").save(os.path.join(ill, "vindicator.png"))
    illager_skin("#1d3a6b", "#e8e4d8", "#e8e4d8", "#e8e4d8").save(os.path.join(ill, "pillager.png"))
    hand_cannon_3d()
    # swords and weapons
    item("tidecleaver", shimmer(blade("#ffffff", "#dfe6ea", "#aeb8bf", "#7f8a91", "#b0b7bd", "#2d4a6b", "#9fe3ff", width=2.8, tip=5), glow=("#9fe3ff",)), True)
    item("nightbloom", shimmer(blade("#f6c1d6", "#a35d9c", "#5a2a7a", "#2c1240", "#d76d77", "#1d0f2b", "#ff8fb1",
                             pattern=lambda s_, t_, w_: rgb("#ff9ec4") if int(s_) % 5 == 2 and abs(t_) < 0.6 else None), glow=("#ff9ec4", "#ff8fb1")), True)
    item("leviathans_fang", shimmer(blade("#ff7a6b", "#5a1a1a", "#2a0b0b", "#120404", "#3a3a3a", "#4a0f0f", "#c0392b",
                                  serrate=True, outline_col="#050101"), glow=("#c0392b",), strength=0.45), True)
    item("tide_rapier", shimmer(blade("#e8fffb", "#8fe6d6", "#43cea2", "#185a9d", "#d9a521", "#185a9d", "#43cea2",
                              width=1.2, length=27, tip=5, guard_half=4.2), glow=("#43cea2",)), True)
    item("abyss_cutlass", shimmer(blade("#9ff5e6", "#3fa79a", "#1c4f4a", "#0c2422", "#00c9a7", "#232526", "#00c9a7",
                                curve=3.0, width=2.4), glow=("#00c9a7",)), True)
    item("krakens_maul", shimmer(krakens_maul(), glow=("#c47cff",), strength=0.45), True)
    item("harpoon", shimmer(harpoon()), True)
    item("key_sailor", shimmer(crate_key("#d7e3ea", "#7d8b96", "#3fb8c9", "#bff6ff"), glow=("#3fb8c9", "#bff6ff")), True)
    item("key_captain", shimmer(crate_key("#f2c230", "#9c6b12", "#c0392b", "#ffb3a6"), glow=("#c0392b", "#ffb3a6")), True)
    item("key_kraken", shimmer(crate_key("#5d4a7a", "#2b1f3d", "#43e0c8", "#c9fff5"), glow=("#43e0c8", "#c9fff5")), True)
    item("coral_scimitar", shimmer(coral_scimitar(), glow=("#fff0e0",)), True)
    item("abyssal_anchor", shimmer(abyssal_anchor(), glow=("#7dfff0",), band=4.0), True)
    item("deepcore_pickaxe", shimmer(deepcore_pickaxe(), glow=("#3fd8ff", "#c8fbff", "#9fe8de")), True)
    item("abyssal_shackles", shackles())
    # bombs and shot
    item("smoke_bomb", bomb("#6f757b", "#3b3f43", "#ffe680", smoke_mark))
    item("frost_bomb", bomb("#7fd3ff", "#e6f7ff", "#bff0ff", frost_mark))
    item("fire_bomb", bomb("#a3241a", "#3b2a20", "#ffcf3d", fire_mark))
    item("cannonball", cannonball())
    # tools and drinks
    item("echo_conch", echo_conch())
    item("rum", bottle("#7a3a12", shape="tall", label="#e8d2a0"))
    item("healing_tonic", bottle("#e84393"))
    item("gale_grog", bottle("#4dd0e1", cork="#6b4a2b"))
    item("seafoam_milkshake", milkshake())
    # armour sets: icons and how they look when worn
    for piece in ("helmet", "chestplate", "leggings", "boots"):
        item("tidewarden_" + piece, armor_icon(piece, "#2a6fb5", "#123a66", "#5ff3ff"))
    equipment("tidewarden", *armor_layers("#2a6fb5", "#123a66", "#5ff3ff"))
    item("corsair_hat", armor_icon("helmet", "#2a1d16", "#120c09", "#e2b33c", style="tricorn"))
    item("corsair_coat", armor_icon("chestplate", "#9b2c2c", "#3a1a12", "#e2b33c"))
    item("corsair_breeches", armor_icon("leggings", "#c9a77a", "#5a4026", "#3a1a12"))
    item("corsair_boots", armor_icon("boots", "#2a1d16", "#120c09", "#e2b33c"))
    equipment("corsair", *armor_layers("#9b2c2c", "#3a1a12", "#e2b33c"))
    item("captains_coat", armor_icon("chestplate", "#2b2b33", "#c0392b", "#e2b33c"))
    # Tidewatch uniforms (worn by the garrison)
    item("tidewatch_hat", armor_icon("helmet", "#1d3a6b", "#0d1f3d", "#e8e4d8", style="tricorn"))
    item("tidewatch_coat", armor_icon("chestplate", "#1d3a6b", "#0d1f3d", "#e8e4d8"))
    item("tidewatch_trousers", armor_icon("leggings", "#e8e4d8", "#9a968c", "#1d3a6b"))
    item("tidewatch_boots", armor_icon("boots", "#1a1a1a", "#080808", "#e8e4d8"))
    item("tidewatch_officer_coat", armor_icon("chestplate", "#14284d", "#0a1530", "#e2b33c"))
    item("tidewatch_captain_hat", armor_icon("helmet", "#f2f2f2", "#b8a26a", "#d4a017", style="tricorn"))
    item("tidewatch_captain_coat", armor_icon("chestplate", "#f2f2f2", "#b8a26a", "#d4a017"))
    equipment("tidewatch", *armor_layers("#1d3a6b", "#0d1f3d", "#e8e4d8", legs="#e8e4d8", boots="#1a1a1a"))
    equipment("tidewatch_officer", *armor_layers("#14284d", "#0a1530", "#e2b33c", legs="#e8e4d8", boots="#1a1a1a"))
    equipment("tidewatch_captain", *armor_layers("#f2f2f2", "#b8a26a", "#d4a017", legs="#e8e4d8", boots="#1a1a1a"))
    equipment("captains_coat", armor_layers("#2b2b33", "#c0392b", "#e2b33c")[0])
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
