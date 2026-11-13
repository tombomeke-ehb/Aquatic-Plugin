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

def fruit(main, swirl, leaf="#3f9b3a", outline_col="#2a1626"):
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
            v = (r * 2.3 - ang / (2 * math.pi)) % 1.0  # the abyss swirl: a spiral over the skin
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


# ---------------------------------------------------------------- pack

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)


def item(name, image, handheld=False):
    os.makedirs(os.path.join(PACK, "assets", "aquatic", "textures", "item"), exist_ok=True)
    image.save(os.path.join(PACK, "assets", "aquatic", "textures", "item", name + ".png"))
    write_json(os.path.join(PACK, "assets", "aquatic", "models", "item", name + ".json"), {
        "parent": "minecraft:item/handheld" if handheld else "minecraft:item/generated",
        "textures": {"layer0": "aquatic:item/" + name},
    })
    write_json(os.path.join(PACK, "assets", "aquatic", "items", name + ".json"), {
        "model": {"type": "minecraft:model", "model": "aquatic:item/" + name},
    })


def equipment(name, layer_image):
    path = os.path.join(PACK, "assets", "aquatic", "textures", "entity", "equipment", "humanoid")
    os.makedirs(path, exist_ok=True)
    layer_image.save(os.path.join(path, name + ".png"))
    write_json(os.path.join(PACK, "assets", "aquatic", "equipment", name + ".json"), {
        "layers": {"humanoid": [{"texture": "aquatic:" + name}]},
    })


FRUITS = {
    "springcoil": fruit("#e86fb0", "#7a1f6e"),
    "emberheart": fruit("#ff7a1a", "#c4100c"),
    "rimefrost": fruit("#a7e6ff", "#ffffff", leaf="#bfe9ff", outline_col="#123247"),
    "thunderveil": fruit("#ffe14a", "#8a3cd6", outline_col="#3a2600"),
}


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else "dev"
    write_json(os.path.join(PACK, "pack.mcmeta"), {
        "pack": {"pack_format": 46, "description": "Aquatic SMP - Sail. Claim. Conquer. (" + version + ")"},
    })
    item("conqueror_crown", crown())
    equipment("conqueror_crown", crown_worn())
    item("storm_saber", storm_saber(), handheld=True)
    item("abyssal_baton", abyssal_baton(), handheld=True)
    item("flintlock", flintlock())
    for name, image in FRUITS.items():
        item(name, image)
    crown().resize((128, 128), Image.NEAREST).save(os.path.join(PACK, "pack.png"))

    os.makedirs(DIST, exist_ok=True)
    out = os.path.join(DIST, "AquaticSMP-ResourcePack.zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(PACK):
            for f in sorted(files):
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, PACK).replace(os.sep, "/"))
    print(out)
    print("sha1", hashlib.sha1(open(out, "rb").read()).hexdigest())


if __name__ == "__main__":
    main()
