package dev.aquatic.util;

import dev.aquatic.api.CrateService;
import dev.aquatic.api.ItemService;
import dev.aquatic.core.AquaticPlugin;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Item specs used in reward lists across the configs (quests, level rewards, crates, ...):
 * <ul>
 *   <li>{@code MATERIAL[:amount]}: a vanilla item, e.g. {@code DIAMOND:4}</li>
 *   <li>{@code item:<id>[:amount]}: a custom item (items module), e.g. {@code item:rum:2}</li>
 *   <li>{@code key:<crate>[:amount]}: a crate key (crates module), e.g. {@code key:sailor:1}</li>
 * </ul>
 */
public final class ItemSpecs {

    private ItemSpecs() {
    }

    /** @return the stack, or null when the spec can't be understood (or its module is off). */
    public static ItemStack parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] p = spec.trim().split(":");
        try {
            String head = p[0].toLowerCase(Locale.ROOT);
            if ((head.equals("item") || head.equals("key")) && p.length > 1) {
                int amount = p.length > 2 ? Integer.parseInt(p[2]) : 1;
                AquaticPlugin plugin = AquaticPlugin.get();
                if (plugin == null) {
                    return null;
                }
                if (head.equals("item")) {
                    ItemService items = plugin.services().get(ItemService.class);
                    return items == null ? null : items.createItem(p[1], amount);
                }
                CrateService crates = plugin.services().get(CrateService.class);
                return crates == null ? null : crates.key(p[1], amount);
            }
            Material m = Material.matchMaterial(p[0]);
            return m == null || !m.isItem() || m.isAir() ? null : new ItemStack(m, p.length > 1 ? Integer.parseInt(p[1]) : 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A readable name for a spec's item: "2x Pirate Rum". */
    public static String describe(ItemStack it) {
        if (it == null) {
            return "?";
        }
        String name = it.getItemMeta() != null && it.getItemMeta().hasDisplayName()
                ? Text.plain(it.getItemMeta().displayName()) : pretty(it.getType());
        return it.getAmount() + "x " + name;
    }

    public static String pretty(Material m) {
        StringBuilder b = new StringBuilder();
        for (String w : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (!w.isEmpty()) {
                b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return b.toString();
    }
}
