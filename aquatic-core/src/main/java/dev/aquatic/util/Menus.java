package dev.aquatic.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Shared helpers for chest menus: icons with a name and lore (never italic, no attribute lines). */
public final class Menus {

    private Menus() {
    }

    public static ItemStack icon(Material m, Component name, List<Component> lore) {
        return icon(new ItemStack(m), name, lore);
    }

    /** Names the given stack (changes and returns it). */
    public static ItemStack icon(ItemStack it, Component name, List<Component> lore) {
        ItemMeta meta = it.getItemMeta();
        meta.displayName(plain(name));
        List<Component> clean = new ArrayList<>(lore.size());
        for (Component c : lore) {
            clean.add(plain(c));
        }
        meta.lore(clean);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    /** A filler pane without a name. */
    public static ItemStack filler(Material m) {
        return icon(m, Component.text(" "), List.of());
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
