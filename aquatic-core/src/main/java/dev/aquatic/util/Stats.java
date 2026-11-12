package dev.aquatic.util;

import dev.aquatic.core.AquaticPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;

/**
 * Stat bonuses from different sources (fruit, bounty, level, armour sets) as attribute modifiers.
 * Each source has its own modifier per attribute, so bonuses stack cleanly and can be updated or
 * removed without touching each other or vanilla modifiers.
 */
public final class Stats {

    private Stats() {
    }

    private static NamespacedKey key(String source, Attribute attribute) {
        String attr = attribute.getKey().getKey().replace('.', '_');
        return new NamespacedKey(AquaticPlugin.get(), (source + "_" + attr).toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Sets the bonus of one source (0 removes it).
     *
     * @param operation ADD_NUMBER for flat values (health in half-hearts), ADD_SCALAR for percentages (0.1 = +10%)
     */
    public static void set(Player player, String source, Attribute attribute, double amount, AttributeModifier.Operation operation) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) {
            return;
        }
        NamespacedKey key = key(source, attribute);
        AttributeModifier old = inst.getModifier(key);
        if (old != null) {
            if (Math.abs(old.getAmount() - amount) < 1e-9 && old.getOperation() == operation) {
                return;
            }
            inst.removeModifier(key);
        }
        if (amount != 0 && Double.isFinite(amount)) {
            inst.addModifier(new AttributeModifier(key, amount, operation, EquipmentSlotGroup.ANY));
        }
        if (attribute == Attribute.MAX_HEALTH && player.getHealth() > inst.getValue()) {
            player.setHealth(Math.max(1, inst.getValue()));
        }
    }

    /** Extra max health in half-hearts (2 = one heart). */
    public static void health(Player player, String source, double halfHearts) {
        set(player, source, Attribute.MAX_HEALTH, halfHearts, AttributeModifier.Operation.ADD_NUMBER);
    }

    /** Extra melee damage as a fraction (0.1 = +10%). */
    public static void damage(Player player, String source, double fraction) {
        set(player, source, Attribute.ATTACK_DAMAGE, fraction, AttributeModifier.Operation.ADD_SCALAR);
    }

    /** Removes every bonus of a source. */
    public static void clear(Player player, String source) {
        health(player, source, 0);
        damage(player, source, 0);
    }
}
