package dev.aquatic.events.items;

import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/** One configured custom item (modules/items.yml -> items.&lt;id&gt;). */
public final class ItemDef {

    public final String id;
    /** fruit | sword | seastone | gun | cannon | logpose | denden | drink | plain */
    public final String type;
    public final ConfigurationSection cfg;
    private final ItemStack template;

    public ItemDef(String id, String type, ConfigurationSection cfg, ItemStack template) {
        this.id = id;
        this.type = type;
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

    /** The ability section: "fruit.ability" for fruits, "ability" for everything else. May be null. */
    public ConfigurationSection ability() {
        return cfg.getConfigurationSection(isFruit() ? "fruit.ability" : "ability");
    }
}
