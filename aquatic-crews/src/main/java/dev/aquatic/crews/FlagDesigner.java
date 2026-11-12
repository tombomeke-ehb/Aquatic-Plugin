package dev.aquatic.crews;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds crew flags (banners): the default flag in the crew colour, presets from crews.yml
 * ("flag.presets") and recolouring. A preset is a base colour plus a list of "pattern:colour" layers;
 * the colour "crew" means the dye closest to the crew colour.
 */
final class FlagDesigner {

    private final CrewsModule module;

    FlagDesigner(CrewsModule module) {
        this.module = module;
    }

    /** The dye colour closest to a chat colour (used for "crew" in presets). */
    static DyeColor nearestDye(TextColor color) {
        if (color == null) {
            return DyeColor.WHITE;
        }
        DyeColor best = DyeColor.WHITE;
        long bestDist = Long.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            org.bukkit.Color c = dye.getColor();
            long dr = c.getRed() - color.red(), dg = c.getGreen() - color.green(), db = c.getBlue() - color.blue();
            long dist = dr * dr * 3 + dg * dg * 4 + db * db * 2; // rough perceptual weighting
            if (dist < bestDist) {
                bestDist = dist;
                best = dye;
            }
        }
        return best;
    }

    /** @return the dye, or null for an unknown name. */
    static DyeColor parseDye(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return DyeColor.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static List<String> dyeNames() {
        List<String> out = new ArrayList<>();
        for (DyeColor d : DyeColor.values()) {
            out.add(d.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    static Material bannerOf(DyeColor dye) {
        Material m = Material.matchMaterial(dye.name() + "_BANNER");
        return m == null ? Material.WHITE_BANNER : m;
    }

    static DyeColor baseOf(ItemStack banner) {
        String name = banner.getType().name();
        if (!name.endsWith("_BANNER")) {
            return DyeColor.WHITE;
        }
        DyeColor d = parseDye(name.substring(0, name.length() - "_BANNER".length()));
        return d == null ? DyeColor.WHITE : d;
    }

    Set<String> presetNames() {
        ConfigurationSection s = module.config().getConfigurationSection("flag.presets");
        return s == null ? Set.of() : new TreeSet<>(s.getKeys(false));
    }

    boolean hasPreset(String name) {
        return name != null && module.config().isConfigurationSection("flag.presets." + name.toLowerCase(Locale.ROOT));
    }

    /** Builds a banner from a preset; null when the preset does not exist. */
    ItemStack preset(String name, TextColor crewColor) {
        ConfigurationSection s = name == null ? null
                : module.config().getConfigurationSection("flag.presets." + name.toLowerCase(Locale.ROOT));
        if (s == null) {
            return null;
        }
        DyeColor crewDye = nearestDye(crewColor);
        DyeColor base = color(s.getString("base", "crew"), crewDye);
        ItemStack banner = new ItemStack(bannerOf(base));
        List<Pattern> patterns = new ArrayList<>();
        for (String layer : s.getStringList("patterns")) {
            Pattern p = pattern(layer, crewDye, name);
            if (p != null) {
                patterns.add(p);
            }
        }
        BannerMeta meta = (BannerMeta) banner.getItemMeta();
        meta.setPatterns(patterns);
        banner.setItemMeta(meta);
        return banner;
    }

    /** The flag a crew flies until it designs its own: flag.default-preset in the crew colour. */
    ItemStack defaultFlag(TextColor crewColor) {
        ItemStack flag = preset(module.config().getString("flag.default-preset", "crew_colors"), crewColor);
        return flag != null ? flag : new ItemStack(bannerOf(nearestDye(crewColor)));
    }

    /** Same patterns, new base colour. */
    static ItemStack recolor(ItemStack banner, DyeColor base) {
        ItemStack out = new ItemStack(bannerOf(base));
        if (banner != null && banner.getItemMeta() instanceof BannerMeta old) {
            BannerMeta meta = (BannerMeta) out.getItemMeta();
            meta.setPatterns(old.getPatterns());
            out.setItemMeta(meta);
        }
        return out;
    }

    static boolean isBanner(ItemStack item) {
        return item != null && !item.getType().isAir() && Tag.BANNERS.isTagged(item.getType());
    }

    private static DyeColor color(String raw, DyeColor crewDye) {
        if (raw == null || raw.equalsIgnoreCase("crew")) {
            return crewDye;
        }
        DyeColor d = parseDye(raw);
        return d == null ? crewDye : d;
    }

    private Pattern pattern(String layer, DyeColor crewDye, String preset) {
        String[] parts = layer.split(":", 2);
        PatternType type = Registry.BANNER_PATTERN.get(NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
        if (type == null) {
            module.logger().warning("[crews] Unknown banner pattern '" + parts[0] + "' in flag preset " + preset);
            return null;
        }
        return new Pattern(color(parts.length > 1 ? parts[1] : "white", crewDye), type);
    }
}
