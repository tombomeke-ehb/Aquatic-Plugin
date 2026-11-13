package dev.aquatic.events;

import dev.aquatic.api.EconomyService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The smithy: upgrade the item in your hand (+1 .. +5) for Doubloons and materials (modules/smithy.yml).
 * Weapons hit harder, armour protects more, tools mine faster, bows shoot harder. Each level has a success
 * chance; a failed attempt uses the materials and Doubloons but never breaks the item.
 */
public final class SmithyModule extends AquaticModule implements Listener {

    enum Kind { WEAPON, ARMOR, TOOL, BOW }

    private static final class Menu implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int SLOT_ITEM = 11, SLOT_UPGRADE = 15, SLOT_SHOP = 22;
    private NamespacedKey levelKey, damageKey, armorKey, toughKey, baseKey;

    @Override
    public String id() {
        return "smithy";
    }

    @Override
    public List<String> requires() {
        return List.of("economy");
    }

    @Override
    protected void onEnable() {
        levelKey = new NamespacedKey(plugin(), "smithy_level");
        damageKey = new NamespacedKey(plugin(), "smithy_damage");
        armorKey = new NamespacedKey(plugin(), "smithy_armor");
        toughKey = new NamespacedKey(plugin(), "smithy_toughness");
        baseKey = new NamespacedKey(plugin(), "smithy_base_name");
        registerListener(this);
        AquaticCommand cmd = new AquaticCommand("smithy", "Upgrade the item in your hand at the smithy", "forge", "blacksmith");
        cmd.defaultExecutor((s, a) -> open(AquaticCommand.player(s)));
        registerCommand(cmd);
    }

    // ------------------------------------------------------------------ rules

    static Kind kind(Material m) {
        String n = m.name();
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || m == Material.TRIDENT || m == Material.MACE) {
            return Kind.WEAPON;
        }
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")) {
            return Kind.ARMOR;
        }
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")) {
            return Kind.TOOL;
        }
        if (m == Material.BOW || m == Material.CROSSBOW) {
            return Kind.BOW;
        }
        return null;
    }

    int maxLevel() {
        return Math.max(1, config().getMapList("levels").size());
    }

    int levelOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        Integer l = item.getItemMeta().getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        return l == null ? 0 : l;
    }

    /** The settings of the next level (1-based index into "levels"), or null when maxed. */
    private java.util.Map<?, ?> step(int level) {
        List<java.util.Map<?, ?>> levels = config().getMapList("levels");
        return level < 1 || level > levels.size() ? null : levels.get(level - 1);
    }

    private static double num(Object o, double def) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Materials for a level: "IRON_INGOT:8" entries from "materials" (per kind or "all"). */
    private List<ItemStack> materials(java.util.Map<?, ?> step, Kind kind) {
        List<ItemStack> out = new ArrayList<>();
        Object raw = step.get("materials");
        List<?> list = raw instanceof java.util.Map<?, ?> byKind
                ? (List<?>) (byKind.get(kind.name().toLowerCase(Locale.ROOT)) != null ? byKind.get(kind.name().toLowerCase(Locale.ROOT)) : byKind.get("all"))
                : raw instanceof List<?> l ? l : List.of();
        if (list == null) {
            return out;
        }
        for (Object o : list) {
            String[] p = String.valueOf(o).split(":");
            Material m = Material.matchMaterial(p[0].trim());
            if (m != null) {
                out.add(new ItemStack(m, p.length > 1 ? Math.max(1, (int) num(p[1].trim(), 1)) : 1));
            }
        }
        return out;
    }

    private static boolean has(Player p, ItemStack need) {
        return p.getInventory().containsAtLeast(new ItemStack(need.getType()), need.getAmount());
    }

    // ------------------------------------------------------------------ menu

    private void open(Player p) {
        Menu menu = new Menu();
        Inventory inv = Bukkit.createInventory(menu, 27, rawMsg("title"));
        menu.inventory = inv;
        fill(inv, p);
        p.openInventory(inv);
    }

    private void fill(Inventory inv, Player p) {
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, pane);
        }
        inv.setItem(SLOT_SHOP, button(Material.IRON_PICKAXE, rawMsg("shop-button"), List.of(rawMsg("shop-button-lore"))));
        ItemStack hand = p.getInventory().getItemInMainHand();
        Kind kind = hand.getType().isAir() ? null : kind(hand.getType());
        if (kind == null) {
            inv.setItem(SLOT_ITEM, button(Material.BARRIER, rawMsg("no-item"), List.of(rawMsg("no-item-lore"))));
            inv.setItem(SLOT_UPGRADE, button(Material.ANVIL, rawMsg("upgrade-button"), List.of(rawMsg("no-item-lore"))));
            return;
        }
        inv.setItem(SLOT_ITEM, hand.clone());
        int level = levelOf(hand);
        java.util.Map<?, ?> next = step(level + 1);
        List<Component> lore = new ArrayList<>();
        lore.add(rawMsg("current-level", "level", level, "max", maxLevel()));
        if (next == null) {
            lore.add(rawMsg("maxed"));
        } else {
            lore.add(rawMsg("effect-" + kind.name().toLowerCase(Locale.ROOT), "value", Text.number(effect(kind) * (level + 1))));
            lore.add(Component.empty());
            lore.add(rawMsg("cost-header"));
            EconomyService eco = service(EconomyService.class);
            double cost = num(next.get("doubloons"), 0);
            if (cost > 0) {
                boolean ok = eco != null && eco.has(p.getUniqueId(), cost);
                lore.add(rawMsg(ok ? "cost-line-ok" : "cost-line-missing", "amount", eco == null ? Text.number(cost) : eco.format(cost)));
            }
            for (ItemStack m : materials(next, kind)) {
                lore.add(rawMsg(has(p, m) ? "cost-line-ok" : "cost-line-missing", "amount",
                        m.getAmount() + "x " + Text.plain(Component.translatable(m.getType().translationKey()))));
            }
            lore.add(Component.empty());
            lore.add(rawMsg("chance", "chance", (int) Math.round(num(next.get("chance"), 100))));
            lore.add(rawMsg("fail-note"));
            lore.add(Component.empty());
            lore.add(rawMsg("click"));
        }
        inv.setItem(SLOT_UPGRADE, button(Material.ANVIL, rawMsg("upgrade-button"), lore));
    }

    private static ItemStack button(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        List<Component> clean = new ArrayList<>();
        for (Component c : lore) {
            clean.add(c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(clean);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu) || !(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (event.getSlot() == SLOT_SHOP) {
            p.closeInventory();
            p.performCommand(config().getString("shop-command", "shop tools"));
            return;
        }
        if (event.getSlot() == SLOT_UPGRADE) {
            try {
                upgrade(p);
            } catch (CommandFail fail) {
                p.sendMessage(plugin().prefix().append(fail.component()));
            }
            fill(event.getInventory(), p);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ upgrading

    private double effect(Kind kind) {
        return switch (kind) {
            case WEAPON -> config().getDouble("effects.weapon-damage", 1.0);
            case ARMOR -> config().getDouble("effects.armor", 1.0);
            case TOOL -> config().getDouble("effects.tool-efficiency", 1);
            case BOW -> config().getDouble("effects.bow-power", 1);
        };
    }

    private void upgrade(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        Kind kind = hand.getType().isAir() ? null : kind(hand.getType());
        if (kind == null || hand.getAmount() != 1) {
            throw new CommandFail(rawMsg("no-item"));
        }
        int level = levelOf(hand);
        java.util.Map<?, ?> next = step(level + 1);
        if (next == null) {
            throw new CommandFail(rawMsg("maxed"));
        }
        EconomyService eco = service(EconomyService.class);
        double cost = num(next.get("doubloons"), 0);
        List<ItemStack> mats = materials(next, kind);
        if (cost > 0 && (eco == null || !eco.has(p.getUniqueId(), cost))) {
            throw new CommandFail(rawMsg("missing", "what", eco == null ? Text.number(cost) : eco.format(cost)));
        }
        for (ItemStack m : mats) {
            if (!has(p, m)) {
                throw new CommandFail(rawMsg("missing", "what", m.getAmount() + "x " + Text.plain(Component.translatable(m.getType().translationKey()))));
            }
        }
        if (cost > 0) {
            eco.withdraw(p.getUniqueId(), cost);
        }
        for (ItemStack m : mats) {
            p.getInventory().removeItem(new ItemStack(m.getType(), m.getAmount()));
        }
        double chance = num(next.get("chance"), 100) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 0.8f, 1f);
            send(p, "failed", "level", level + 1);
            return;
        }
        apply(hand, kind, level + 1);
        p.getInventory().setItemInMainHand(hand);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
        p.getWorld().spawnParticle(org.bukkit.Particle.FLAME, p.getLocation().add(0, 1.2, 0), 20, 0.3, 0.3, 0.3, 0.02);
        send(p, "success", "level", level + 1);
        if (level + 1 >= maxLevel()) {
            Bukkit.broadcast(msg("broadcast-max", "player", p.getName(), "item", name(hand)));
        }
    }

    private static Component name(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? meta.displayName() : Component.translatable(item.getType().translationKey());
    }

    /** Writes the level on the item: attribute bonus / enchantment, name suffix and a lore line. */
    void apply(ItemStack item, Kind kind, int level) {
        ItemMeta meta = item.getItemMeta();
        int previous = levelOf(item);
        double per = effect(kind);
        switch (kind) {
            case WEAPON -> setModifier(item, meta, Attribute.ATTACK_DAMAGE, damageKey, per * level, EquipmentSlotGroup.MAINHAND);
            case ARMOR -> {
                EquipmentSlot slot = item.getType().getEquipmentSlot();
                setModifier(item, meta, Attribute.ARMOR, armorKey, per * level, slot.getGroup());
                setModifier(item, meta, Attribute.ARMOR_TOUGHNESS, toughKey, config().getDouble("effects.armor-toughness", 0.5) * level, slot.getGroup());
            }
            case TOOL -> raiseEnchant(meta, Enchantment.EFFICIENCY, (int) Math.round(per * (level - previous)));
            case BOW -> raiseEnchant(meta, item.getType() == Material.CROSSBOW ? Enchantment.QUICK_CHARGE : Enchantment.POWER,
                    (int) Math.round(per * (level - previous)));
        }
        meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        // "+N" after the original name (kept on the item, so the suffix never stacks)
        String stored = meta.getPersistentDataContainer().get(baseKey, PersistentDataType.STRING);
        if (stored == null) {
            stored = meta.hasDisplayName()
                    ? net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(meta.displayName()) : "";
            meta.getPersistentDataContainer().set(baseKey, PersistentDataType.STRING, stored);
        }
        Component base = stored.isEmpty()
                ? Component.translatable(item.getType().translationKey()).decoration(TextDecoration.ITALIC, false)
                : net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(stored);
        meta.displayName(base.append(Text.item(config().getString("name-suffix", " <gold>+<level>"), "level", level)));
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        String loreLine = config().getString("lore-line", "<gold>★ Smithed +<level>");
        String old = Text.plain(Text.item(loreLine, "level", previous));
        lore.removeIf(c -> Text.plain(c).equals(old));
        lore.add(Text.item(loreLine, "level", level));
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private static void raiseEnchant(ItemMeta meta, Enchantment ench, int by) {
        if (by <= 0) {
            return;
        }
        int now = meta.getEnchantLevel(ench);
        meta.addEnchant(ench, Math.min(255, now + by), true);
    }

    /** Replaces our modifier on the item, keeping the item's normal (default) attributes. */
    private static void setModifier(ItemStack item, ItemMeta meta, Attribute attribute, NamespacedKey key, double amount, EquipmentSlotGroup group) {
        if (!meta.hasAttributeModifiers()) {
            // an item without its own modifiers uses the defaults; copy them first or they would vanish
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try {
                    item.getType().getDefaultAttributeModifiers(slot).forEach(meta::addAttributeModifier);
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (meta.getAttributeModifiers(attribute) != null) {
            for (AttributeModifier mod : new ArrayList<>(meta.getAttributeModifiers(attribute))) {
                if (mod.getKey().equals(key)) {
                    meta.removeAttributeModifier(attribute, mod);
                }
            }
        }
        meta.addAttributeModifier(attribute, new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER, group));
    }

    @SuppressWarnings("unused")
    private static Enchantment ench(String name) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name));
    }

    @SuppressWarnings("unused")
    private ConfigurationSection section(String path) {
        return config().getConfigurationSection(path);
    }
}
