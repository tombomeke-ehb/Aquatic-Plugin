"""
Builds the AquaticSMP resource pack (Minecraft 1.21.4, pack format 46).

  python resourcepack/build_pack.py [version]

Draws the textures (pixel art defined below), writes the item definitions and models, and zips everything
into resourcepack/dist/AquaticSMP-ResourcePack.zip. It also prints the SHA-1 for server.properties.

Items use the 1.21.4 "item_model" component: items.yml sets item-model: "aquatic:<name>", which loads
assets/aquatic/items/<name>.json. Worn items (the crown) also use "equipment-model", which loads
assets/aquatic/equipment/<name>.json.
"""
import hashlib
import json
import os
import sys
import zipfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(HERE, "pack")
DIST = os.path.join(HERE, "dist")


def hex_rgba(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)


def from_rows(rows, palette, size=16):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != "." and ch in palette:
                img.putpixel((x, y), hex_rgba(palette[ch]))
    return img


# ---------------------------------------------------------------- Crown of the Conqueror (icon)

CROWN_PALETTE = {
    "D": "#6b3f00",  # outline
    "O": "#b9800f",  # shadow gold
    "G": "#f2c230",  # gold
    "L": "#ffe78a",  # light gold
    "W": "#fffbe6",  # shine
    "R": "#c8102e",  # ruby
    "r": "#ff6b7d",  # ruby shine
    "B": "#1f5fd1",  # sapphire
    "b": "#7fb2ff",  # sapphire shine
}
CROWN = [
    "................",
    "................",
    "..D....DD....D..",
    ".DWD..DLWD..DWD.",
    ".DLD..DLLD..DLD.",
    ".DGGD.DGGD.DGGD.",
    ".DGGGDGrRGDGGGD.",
    ".DGbGGGRRGGGbGD.",
    ".DGBGGGGGGGGBGD.",
    ".DLLLLLLLLLLLLD.",
    ".DOGOGOGOGOGOGD.",
    ".DOOOOOOOOOOOOD.",
    "..DDDDDDDDDDDD..",
    "................",
    "................",
    "................",
]


def crown_worn():
    """Helmet layer (64x32): a gold band with spikes and gems around the top of the head."""
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    gold, light, shadow, dark = hex_rgba("#f2c230"), hex_rgba("#ffe78a"), hex_rgba("#b9800f"), hex_rgba("#6b3f00")
    ruby, sapphire = hex_rgba("#c8102e"), hex_rgba("#1f5fd1")
    # side faces of the head: right (0,8), front (8,8), left (16,8), back (24,8); each 8x8
    for face, x0 in enumerate((0, 8, 16, 24)):
        for x in range(8):
            img.putpixel((x0 + x, 11), shadow)       # bottom of the band
            img.putpixel((x0 + x, 10), gold)
            img.putpixel((x0 + x, 9), light if x % 2 == 0 else gold)
        for x in (0, 3, 4, 7):                        # spikes
            img.putpixel((x0 + x, 8), gold if x in (3, 4) else light)
        gem = ruby if face == 1 else sapphire
        img.putpixel((x0 + 3, 10), gem)
        img.putpixel((x0 + 4, 10), gem)
        img.putpixel((x0 + 3, 11), dark)
        img.putpixel((x0 + 4, 11), dark)
    # the top face (8,0)-(16,8): a gold rim so the crown looks round from above
    for i in range(8):
        for (x, y) in ((8 + i, 0), (8 + i, 7), (8, i), (15, i)):
            img.putpixel((x, y), gold)
    return img


# ---------------------------------------------------------------- Stormcaller Saber

SABER_PALETTE = {
    "K": "#14213d",  # outline
    "E": "#eafcff",  # edge shine
    "B": "#8fd8ff",  # blade
    "C": "#3d8fe0",  # blade core
    "Y": "#fff35c",  # lightning
    "G": "#f2c230",  # guard gold
    "g": "#a8740b",  # guard shadow
    "H": "#5a3f8c",  # grip
    "h": "#2e1f4d",  # grip shadow
    "P": "#fff35c",  # pommel gem
}
SABER = [
    ".............KKK",
    "............KEEK",
    "...........KEBCK",
    "..........KEBCK.",
    ".........KEBYK..",
    "........KEBCK...",
    ".......KEBCK....",
    "......KEYCK.....",
    "..KK.KEBCK......",
    "..KGKEBCK.......",
    "...KGGCK........",
    "...KHGGK........",
    "..KHhKGGK.......",
    ".KHhK.KgK.......",
    "KPHK...K........",
    "KKK.............",
]


def storm_saber():
    return from_rows(SABER, SABER_PALETTE)


# ---------------------------------------------------------------- pack

def item(name, image, handheld=False):
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


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else "dev"
    os.makedirs(os.path.join(PACK, "assets", "aquatic", "textures", "item"), exist_ok=True)
    write_json(os.path.join(PACK, "pack.mcmeta"), {
        "pack": {"pack_format": 46, "description": "Aquatic SMP - Sail. Claim. Conquer. (" + version + ")"},
    })
    item("conqueror_crown", from_rows(CROWN, CROWN_PALETTE))
    equipment("conqueror_crown", crown_worn())
    item("storm_saber", storm_saber(), handheld=True)
    # pack icon: the crown, scaled up
    from_rows(CROWN, CROWN_PALETTE).resize((128, 128), Image.NEAREST).save(os.path.join(PACK, "pack.png"))

    os.makedirs(DIST, exist_ok=True)
    out = os.path.join(DIST, "AquaticSMP-ResourcePack.zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(PACK):
            for f in sorted(files):
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, PACK).replace(os.sep, "/"))
    sha1 = hashlib.sha1(open(out, "rb").read()).hexdigest()
    print(out)
    print("sha1", sha1)


if __name__ == "__main__":
    main()
