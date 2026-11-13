package dev.aquatic.world;

import dev.aquatic.api.ItemService;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** One loot tier from treasure.yml ("tiers.&lt;id&gt;"). */
final class LootTier {

    /** "min-max" or a single number. */
    record Range(int min, int max) {
        int roll(Random r) {
            return max <= min ? min : min + r.nextInt(max - min + 1);
        }

        static Range parse(Object raw, int def) {
            if (raw == null) {
                return new Range(def, def);
            }
            String s = String.valueOf(raw).trim();
            try {
                int dash = s.indexOf('-', 1);
                if (dash > 0) {
                    int a = Integer.parseInt(s.substring(0, dash).trim()), b = Integer.parseInt(s.substring(dash + 1).trim());
                    return new Range(Math.min(a, b), Math.max(a, b));
                }
                int v = (int) Math.round(Double.parseDouble(s));
                return new Range(v, v);
            } catch (NumberFormatException e) {
                return new Range(def, def);
            }
        }
    }

    record EnchantSpec(Enchantment enchantment, Range level) {
    }

    static final class Entry {
        Material material;
        String customItem;
        /** Any reward spec: "key:sailor:1", "item:rum:2", "DIAMOND:3" (see ItemSpecs). */
        String spec;
        /** "treasure-map: rare": a sealed map that leads to a fresh treasure of that tier. */
        String treasureMap;
        /** "potion: swiftness" for POTION / SPLASH_POTION / LINGERING_POTION / TIPPED_ARROW. */
        org.bukkit.potion.PotionType potion;
        Range amount = new Range(1, 1);
        double weight = 1;
        final List<EnchantSpec> enchants = new ArrayList<>();
        Range randomEnchantLevel;
        boolean treasureEnchants;
        String name;
        List<String> lore = List.of();
        Range doubloons;
    }

    final String id;
    Component displayName;
    String rawDisplayName;
    Material chest = Material.CHEST;
    Range rolls = new Range(3, 5);
    Range doubloons = new Range(0, 0);
    int depth;
    boolean marker;
    double weight;
    boolean broadcast;
    int reputation;
    String vanish = "empty";
    Particle particle;
    boolean beam;
    final List<Entry> loot = new ArrayList<>();
    private double totalWeight;
    /** No entry twice in one chest (Doubloon entries may repeat). */
    boolean uniqueRolls = true;
    /** A small chance at one extra standout item. */
    double jackpotChance;
    final List<Entry> jackpot = new ArrayList<>();
    private double jackpotWeight;
    List<String> jackpotLore = List.of();

    private LootTier(String id) {
        this.id = id;
    }

    static LootTier load(String id, ConfigurationSection s, Logger log) {
        LootTier t = new LootTier(id);
        String ctx = "[treasure] tiers." + id;
        t.rawDisplayName = s.getString("display-name", id);
        t.displayName = Text.mm(t.rawDisplayName);
        Material chest = Material.matchMaterial(s.getString("chest", "CHEST"));
        t.chest = chest == Material.BARREL || chest == Material.TRAPPED_CHEST ? chest : Material.CHEST;
        t.rolls = Range.parse(s.get("rolls"), 4);
        t.doubloons = Range.parse(s.get("doubloons"), 0);
        t.depth = Math.max(0, Math.min(20, s.getInt("depth", 2)));
        t.marker = s.getBoolean("marker", true);
        t.weight = Math.max(0, s.getDouble("weight", 1));
        t.broadcast = s.getBoolean("broadcast", false);
        t.reputation = s.getInt("reputation", 0);
        t.vanish = s.getString("vanish", "empty").toLowerCase(Locale.ROOT);
        String particle = s.getString("particles", "none");
        if (particle != null && !particle.equalsIgnoreCase("none") && !particle.equalsIgnoreCase("false")) {
            try {
                t.particle = Particle.valueOf(particle.toUpperCase(Locale.ROOT));
                if (t.particle.getDataType() != Void.class) {
                    log.warning(ctx + ".particles: " + particle + " needs extra data, use a simple particle");
                    t.particle = null;
                }
            } catch (IllegalArgumentException e) {
                log.warning(ctx + ".particles: unknown particle " + particle);
            }
        }
        t.beam = s.getBoolean("beam", false);
        for (Map<?, ?> raw : s.getMapList("loot")) {
            Entry e = parseEntry(raw, log, ctx);
            if (e != null) {
                t.loot.add(e);
                t.totalWeight += e.weight;
            }
        }
        t.uniqueRolls = s.getBoolean("unique-rolls", true);
        t.jackpotChance = Math.max(0, Math.min(1, s.getDouble("jackpot.chance", 0)));
        t.jackpotLore = s.getStringList("jackpot.lore");
        for (Map<?, ?> raw : s.getMapList("jackpot.loot")) {
            Entry e = parseEntry(raw, log, ctx + ".jackpot");
            if (e != null) {
                t.jackpot.add(e);
                t.jackpotWeight += e.weight;
            }
        }
        return t;
    }

    private static Entry parseEntry(Map<?, ?> raw, Logger log, String ctx) {
        Entry e = new Entry();
        Object w = raw.get("weight");
        if (w != null) {
            try {
                e.weight = Double.parseDouble(String.valueOf(w));
            } catch (NumberFormatException ex) {
                e.weight = 1;
            }
        }
        if (e.weight <= 0) {
            return null;
        }
        if (raw.get("custom-item") != null) {
            e.customItem = String.valueOf(raw.get("custom-item"));
        }
        if (raw.get("item") != null) {
            e.spec = String.valueOf(raw.get("item"));
        }
        if (raw.get("material") != null) {
            e.material = Material.matchMaterial(String.valueOf(raw.get("material")));
            if (e.material == null || !e.material.isItem() || e.material.isAir()) {
                log.warning(ctx + ": unknown item material " + raw.get("material"));
                e.material = null;
            }
        }
        if (raw.get("doubloons") != null) {
            e.doubloons = Range.parse(raw.get("doubloons"), 0);
        }
        if (raw.get("treasure-map") != null) {
            e.treasureMap = String.valueOf(raw.get("treasure-map")).toLowerCase(Locale.ROOT);
        }
        if (raw.get("potion") != null) {
            e.potion = Registry.POTION.get(NamespacedKey.minecraft(String.valueOf(raw.get("potion")).toLowerCase(Locale.ROOT)));
            if (e.potion == null) {
                log.warning(ctx + ": unknown potion " + raw.get("potion"));
            }
        }
        if (e.material == null && e.customItem == null && e.spec == null && e.doubloons == null && e.treasureMap == null) {
            return null;
        }
        e.amount = Range.parse(raw.get("amount"), 1);
        if (raw.get("enchants") instanceof List<?> list) {
            for (Object o : list) {
                String[] parts = String.valueOf(o).split(":");
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
                if (ench == null) {
                    log.warning(ctx + ": unknown enchantment " + parts[0]);
                    continue;
                }
                e.enchants.add(new EnchantSpec(ench, parts.length > 1 ? Range.parse(parts[1], 1) : new Range(1, 1)));
            }
        }
        if (raw.get("random-enchant-level") != null) {
            e.randomEnchantLevel = Range.parse(raw.get("random-enchant-level"), 0);
            e.treasureEnchants = Boolean.parseBoolean(String.valueOf(raw.get("treasure-enchants")));
        }
        if (raw.get("name") != null) {
            e.name = String.valueOf(raw.get("name"));
        }
        if (raw.get("lore") instanceof List<?> list) {
            List<String> lore = new ArrayList<>();
            for (Object o : list) {
                lore.add(String.valueOf(o));
            }
            e.lore = lore;
        }
        return e;
    }

    private Entry pick(Random r) {
        return pick(r, loot, totalWeight);
    }

    private static Entry pick(Random r, List<Entry> list, double total) {
        if (list.isEmpty()) {
            return null;
        }
        double roll = r.nextDouble() * total;
        for (Entry e : list) {
            roll -= e.weight;
            if (roll < 0) {
                return e;
            }
        }
        return list.get(list.size() - 1);
    }

    /** Result of a roll: items plus extra Doubloons from doubloon entries and the tier base. */
    /** maps: tiers of sealed treasure maps to add; jackpot: whether the jackpot hit. */
    record Roll(List<ItemStack> items, double doubloons, List<String> maps, boolean jackpot) {
    }

    Roll roll(Random r, Supplier<ItemService> items) {
        List<ItemStack> out = new ArrayList<>();
        List<String> maps = new ArrayList<>();
        double money = doubloons.roll(r);
        int count = rolls.roll(r);
        java.util.Set<Entry> used = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            Entry e = pick(r);
            for (int retry = 0; uniqueRolls && e != null && e.doubloons == null && used.contains(e) && retry < 12; retry++) {
                e = pick(r);
            }
            if (e == null) {
                break;
            }
            used.add(e);
            money += add(e, r, items, out, maps);
        }
        boolean hit = !jackpot.isEmpty() && r.nextDouble() < jackpotChance;
        if (hit) {
            Entry e = pick(r, jackpot, jackpotWeight);
            int before = out.size();
            money += add(e, r, items, out, maps);
            if (!jackpotLore.isEmpty()) {
                for (int i = before; i < out.size(); i++) {
                    ItemStack stack = out.get(i);
                    ItemMeta meta = stack.getItemMeta();
                    if (meta != null) {
                        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                        lore.addAll(Text.itemLore(jackpotLore));
                        meta.lore(lore);
                        stack.setItemMeta(meta);
                    }
                }
            }
        }
        return new Roll(out, Math.max(0, money), maps, hit);
    }

    private static double add(Entry e, Random r, Supplier<ItemService> items, List<ItemStack> out, List<String> maps) {
        if (e.treasureMap != null) {
            maps.add(e.treasureMap);
        }
        ItemStack stack = build(e, r, items);
        if (stack != null) {
            out.add(stack);
        }
        return e.doubloons != null ? e.doubloons.roll(r) : 0;
    }

    private static ItemStack build(Entry e, Random r, Supplier<ItemService> items) {
        int amount = Math.max(1, e.amount.roll(r));
        ItemStack stack = null;
        if (e.spec != null) {
            return dev.aquatic.util.ItemSpecs.parse(e.spec); // null when its module is off: just skipped
        }
        if (e.customItem != null) {
            ItemService service = items.get();
            if (service != null) {
                stack = service.createItem(e.customItem, amount);
            }
            if (stack == null) {
                return null; // silently skipped when the items module is off or the id is unknown
            }
        } else if (e.material != null) {
            stack = new ItemStack(e.material, Math.min(amount, e.material.getMaxStackSize()));
        }
        if (stack == null) {
            return null;
        }
        if (e.randomEnchantLevel != null) {
            int levels = Math.max(1, e.randomEnchantLevel.roll(r));
            if (stack.getType() == Material.BOOK) {
                stack = new ItemStack(Material.BOOK);
            }
            stack = Bukkit.getItemFactory().enchantWithLevels(stack, levels, e.treasureEnchants, r);
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        for (EnchantSpec spec : e.enchants) {
            int level = Math.max(1, spec.level().roll(r));
            if (meta instanceof EnchantmentStorageMeta book) {
                book.addStoredEnchant(spec.enchantment(), level, true);
            } else {
                meta.addEnchant(spec.enchantment(), level, true);
            }
        }
        if (e.potion != null && meta instanceof org.bukkit.inventory.meta.PotionMeta pm) {
            pm.setBasePotionType(e.potion);
        }
        if (e.name != null) {
            meta.displayName(Text.item(e.name));
        }
        if (!e.lore.isEmpty()) {
            meta.lore(Text.itemLore(e.lore));
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
