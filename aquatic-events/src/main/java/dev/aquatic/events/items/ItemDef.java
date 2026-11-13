package dev.aquatic.events.items;

import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/** One configured custom item (modules/items.yml -> items.&lt;id&gt;). */
public final class ItemDef {

    public final String id;
    /** fruit | sword | abyssal | gun | cannon | compass | conch | drink | plain */
    public final String type;
    public final ConfigurationSection cfg;
    private final ItemStack template;

    public ItemDef(String id, String type, ConfigurationSection cfg, ItemStack template) {
        this.id = id;
        this.type = dev.aquatic.core.LegacyNames.type(type);
        this.cfg = cfg;
        this.template = template;
    }

    public ItemStack create(int amount) {
        ItemStack copy = template.clone();
        copy.setAmount(Math.max(1, amount));
        return copy;
    }

    public boolean isFruit() {
        return type.equals("fruit");
    }

    public Component displayName() {
        return Text.item(cfg.getString("name", id));
    }

    private java.util.List<ConfigurationSection> moves;

    /**
     * All moves of a fruit: "fruit.ability" (move 1) plus the "fruit.extra-moves" list,
     * switched with sneak + scroll. Empty when the item has no ability.
     */
    public java.util.List<ConfigurationSection> moves() {
        if (moves == null) {
            java.util.List<ConfigurationSection> out = new java.util.ArrayList<>();
            if (ability() != null) {
                out.add(ability()); // move 1
            }
            java.util.List<java.util.Map<?, ?>> list = cfg.getMapList(isFruit() ? "fruit.extra-moves" : "extra-moves");
            for (int i = 0; i < list.size(); i++) {
                out.add(new org.bukkit.configuration.file.YamlConfiguration().createSection("move" + i, list.get(i)));
            }
            moves = out;
        }
        return moves;
    }

    /** The ability section: "fruit.ability" for fruits, "ability" for everything else. May be null. */
    public ConfigurationSection ability() {
        return cfg.getConfigurationSection(isFruit() ? "fruit.ability" : "ability");
    }
}
