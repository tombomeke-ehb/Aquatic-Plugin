package dev.aquatic.events;

import dev.aquatic.api.ItemService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.events.items.FruitManager;
import dev.aquatic.events.items.GearListener;
import dev.aquatic.events.items.ItemDef;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Custom pirate items: abyss fruits, Abyssal Iron, legendary swords, guns, tide compass, rum, the crown. */
public final class ItemsModule extends AquaticModule implements ItemService {

    private final Map<String, ItemDef> defs = new LinkedHashMap<>();
    private final List<NamespacedKey> recipes = new ArrayList<>();
    private NamespacedKey idKey;
    private dev.aquatic.events.items.RecipeBook recipeBook;
    private FruitManager fruits;
    private GearListener gear;

    /** True while one of our abilities deals damage (prevents on-hit effects from triggering recursively). */
    public boolean abilityDamage;
    /** Result of the last ability damage: true when another plugin/module cancelled it. */
    public boolean abilityDamageCancelled;
    /** True while a cannon explosion that must only break blocks is being created. */
    public boolean silentExplosion;

    @Override
    public String id() {
        return "items";
    }

    @Override
    protected void onEnable() {
        idKey = new NamespacedKey(plugin(), "item_id");
        abilityDamage = false;
        silentExplosion = false;
        loadDefinitions();
        fruits = new FruitManager(this);
        fruits.load();
        // the "fruits" module switches them on right after this module (see FruitsModule)
        fruits.setEnabled(plugin().module("fruits") != null && plugin().module("fruits").isEnabled());
        gear = new GearListener(this);
        registerListener(fruits);
        registerListener(gear);
        registerService(ItemService.class, this);
        registerRecipes();
        recipeBook = new dev.aquatic.events.items.RecipeBook(this);
        registerListener(recipeBook);
        Bukkit.getOnlinePlayers().forEach(recipeBook::unlock);
        registerCommands();
        long interval = Math.max(5, config().getLong("fruits.passive-interval-ticks", 20));
        runTimer(() -> {
            fruits.tick(interval);
            gear.tick();
        }, interval, interval);
    }

    @Override
    protected void onDisable() {
        if (fruits != null) {
            fruits.save();
        }
        if (gear != null) {
            gear.shutdown();
        }
        for (NamespacedKey key : recipes) {
            Bukkit.removeRecipe(key);
        }
        recipes.clear();
        defs.clear();
    }

    /** Keys of the custom crafting recipes registered by this module. */
    public List<NamespacedKey> recipeKeys() {
        return java.util.Collections.unmodifiableList(recipes);
    }

    public FruitManager fruits() {
        return fruits;
    }

    public GearListener gear() {
        return gear;
    }

    // ------------------------------------------------------------------ definitions

    private void loadDefinitions() {
        defs.clear();
        ConfigurationSection items = config().getConfigurationSection("items");
        if (items == null) {
            return;
        }
        for (String rawId : items.getKeys(false)) {
            ConfigurationSection cfg = items.getConfigurationSection(rawId);
            if (cfg == null) {
                continue;
            }
            String id = rawId.toLowerCase(Locale.ROOT);
            try {
                Material material = Material.matchMaterial(cfg.getString("material", ""));
                if (material == null || !material.isItem() || material.isAir()) {
                    logger().warning("[items] Item '" + id + "' has an invalid material: " + cfg.getString("material"));
                    continue;
                }
                String type = cfg.getString("type", "plain").toLowerCase(Locale.ROOT);
                defs.put(id, new ItemDef(id, type, cfg, build(id, material, cfg)));
            } catch (RuntimeException e) {
                logger().log(Level.WARNING, "[items] Could not load item '" + id + "'", e);
            }
        }
        logger().info("[items] Loaded " + defs.size() + " custom items.");
    }

    private ItemStack build(String id, Material material, ConfigurationSection cfg) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (cfg.contains("name")) {
            meta.displayName(Text.item(cfg.getString("name")));
        }
        if (cfg.contains("lore")) {
            meta.lore(Text.itemLore(cfg.getStringList("lore")));
        }
        int cmd = cfg.getInt("custom-model-data", 0);
        if (cmd > 0) {
            meta.setCustomModelData(cmd);
        }
        applyModels(meta, cfg, material);
        if (cfg.getBoolean("glint", false)) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (cfg.getBoolean("unbreakable", false)) {
            meta.setUnbreakable(true);
        }
        int maxStack = cfg.getInt("max-stack", 0);
        if (maxStack >= 1 && maxStack <= 99) {
            meta.setMaxStackSize(maxStack);
        }
        ConfigurationSection enchants = cfg.getConfigurationSection("enchants");
        if (enchants != null) {
            for (String name : enchants.getKeys(false)) {
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
                if (ench == null) {
                    logger().warning("[items] Unknown enchantment '" + name + "' on item " + id);
                    continue;
                }
                meta.addEnchant(ench, enchants.getInt(name, 1), true);
            }
        }
        int index = 0;
        for (Map<?, ?> raw : cfg.getMapList("attributes")) {
            try {
                String attrName = String.valueOf(raw.get("attribute")).toLowerCase(Locale.ROOT);
                Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(attrName));
                if (attribute == null) {
                    logger().warning("[items] Unknown attribute '" + attrName + "' on item " + id);
                    continue;
                }
                double amount = Double.parseDouble(String.valueOf(raw.get("amount")));
                Object op = raw.get("operation");
                AttributeModifier.Operation operation = op == null ? AttributeModifier.Operation.ADD_NUMBER
                        : AttributeModifier.Operation.valueOf(String.valueOf(op).toUpperCase(Locale.ROOT));
                Object slotRaw = raw.get("slot");
                EquipmentSlotGroup slot = slotRaw == null ? null : EquipmentSlotGroup.getByName(String.valueOf(slotRaw).toLowerCase(Locale.ROOT));
                if (slot == null) {
                    slot = EquipmentSlotGroup.ANY;
                }
                meta.addAttributeModifier(attribute, new AttributeModifier(
                        new NamespacedKey(plugin(), "item_" + id + "_" + index++), amount, operation, slot));
            } catch (RuntimeException e) {
                logger().warning("[items] Invalid attribute entry on item " + id + ": " + raw);
            }
        }
        for (String flag : cfg.getStringList("flags")) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                logger().warning("[items] Unknown item flag '" + flag + "' on item " + id);
            }
        }
        String color = cfg.getString("color");
        if (color != null) {
            try {
                Color c = Color.fromRGB(Integer.parseInt(color.replace("#", ""), 16));
                if (meta instanceof PotionMeta potion) {
                    potion.setColor(c);
                } else if (meta instanceof LeatherArmorMeta leather) {
                    leather.setColor(c);
                }
            } catch (IllegalArgumentException e) {
                logger().warning("[items] Invalid color '" + color + "' on item " + id);
            }
        }
        if (material.isEdible() && cfg.getBoolean("always-edible", false)) {
            FoodComponent food = meta.getFood();
            food.setCanAlwaysEat(true);
            food.setNutrition(cfg.getInt("nutrition", 4));
            food.setSaturation((float) cfg.getDouble("saturation", 2.4));
            meta.setFood(food);
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    private void registerRecipes() {
        for (ItemDef def : defs.values()) {
            ConfigurationSection r = def.cfg.getConfigurationSection("recipe");
            if (r == null || !r.getBoolean("enabled", false) || def.isFruit()) {
                continue;
            }
            try {
                NamespacedKey key = new NamespacedKey(plugin(), "aquatic_item_" + def.id);
                ItemStack result = def.create(Math.max(1, r.getInt("amount", 1)));
                ShapedRecipe recipe = new ShapedRecipe(key, result);
                List<String> shape = r.getStringList("shape");
                recipe.shape(shape.toArray(new String[0]));
                ConfigurationSection ing = r.getConfigurationSection("ingredients");
                if (ing == null) {
                    continue;
                }
                boolean ok = true;
                for (String symbol : ing.getKeys(false)) {
                    String spec = ing.getString(symbol, "");
                    if (spec.toLowerCase(Locale.ROOT).startsWith("item:")) {
                        ItemDef other = defs.get(spec.substring(5).toLowerCase(Locale.ROOT));
                        if (other == null) {
                            ok = false;
                            break;
                        }
                        recipe.setIngredient(symbol.charAt(0), new RecipeChoice.ExactChoice(other.create(1)));
                    } else {
                        Material m = Material.matchMaterial(spec);
                        if (m == null) {
                            ok = false;
                            break;
                        }
                        recipe.setIngredient(symbol.charAt(0), m);
                    }
                }
                if (!ok) {
                    logger().warning("[items] Recipe of '" + def.id + "' has an unknown ingredient, skipped.");
                    continue;
                }
                Bukkit.removeRecipe(key);
                if (Bukkit.addRecipe(recipe)) {
                    recipes.add(key);
                }
            } catch (RuntimeException e) {
                logger().log(Level.WARNING, "[items] Invalid recipe for item '" + def.id + "'", e);
            }
        }
    }

    // ------------------------------------------------------------------ ItemService

    @Override
    public Set<String> itemIds() {
        Set<String> ids = new java.util.LinkedHashSet<>(defs.keySet());
        ids.add(RANDOM_FRUIT);
        return Collections.unmodifiableSet(ids);
    }

    /** Pseudo item id: createItem returns a random abyss fruit (used by treasure loot and event rewards). */
    public static final String RANDOM_FRUIT = "fruit_random";

    @Override
    public ItemStack createItem(String id, int amount) {
        if (id != null && id.equalsIgnoreCase(RANDOM_FRUIT)) {
            if (fruits == null || !fruits.isEnabled()) {
                return null; // fruits are switched off
            }
            java.util.List<ItemDef> fruits = defs.values().stream().filter(ItemDef::isFruit).toList();
            return fruits.isEmpty() ? null
                    : fruits.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(fruits.size())).create(amount);
        }
        ItemDef def = id == null ? null : defs.get(id.toLowerCase(Locale.ROOT));
        return def == null ? null : def.create(amount);
    }

    @Override
    public String idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        // items made before 2.3.1 can carry an old id (e.g. "gomu_gomu"); they map to the new one
        return dev.aquatic.core.LegacyNames.id(stack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    /** Rewrites old item ids (from before 2.3.1) stored on the stacks to the new ids. */
    public void upgradeIds(org.bukkit.inventory.Inventory inventory) {
        if (inventory == null) {
            return;
        }
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
                continue;
            }
            org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
            String raw = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            String now = dev.aquatic.core.LegacyNames.id(raw);
            boolean changed = false;
            if (raw != null && !raw.equals(now)) {
                meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, now);
                changed = true;
            }
            // keep the custom texture in line with the resource pack setting (also for items made earlier)
            ItemDef def = now == null ? null : defs.get(now);
            if (def != null) {
                NamespacedKey before = meta.hasItemModel() ? meta.getItemModel() : null;
                applyModels(meta, def.cfg, stack.getType());
                NamespacedKey after = meta.hasItemModel() ? meta.getItemModel() : null;
                changed |= !java.util.Objects.equals(before, after);
            }
            if (changed) {
                stack.setItemMeta(meta);
            }
        }
    }

    /**
     * Custom textures from the resource pack: "item-model" (the item's look) and "equipment-model" (how a worn
     * item looks). Only when resource-pack.enabled is on, otherwise players without the pack would see a
     * missing texture; turning it off removes them again.
     */
    void applyModels(org.bukkit.inventory.meta.ItemMeta meta, ConfigurationSection cfg, Material material) {
        boolean on = config().getBoolean("resource-pack.enabled", false);
        String model = cfg.getString("item-model");
        if (on && model != null) {
            meta.setItemModel(NamespacedKey.fromString(model));
        } else if (meta.hasItemModel() && "aquatic".equals(meta.getItemModel().getNamespace())) {
            meta.setItemModel(null);
        }
        String worn = cfg.getString("equipment-model");
        if (worn != null && on) {
            org.bukkit.inventory.meta.components.EquippableComponent eq = meta.getEquippable();
            org.bukkit.inventory.EquipmentSlot slot = material.getEquipmentSlot();
            eq.setSlot(slot == org.bukkit.inventory.EquipmentSlot.HAND ? org.bukkit.inventory.EquipmentSlot.HEAD : slot);
            eq.setModel(NamespacedKey.fromString(worn));
            meta.setEquippable(eq);
        } else if (meta.hasEquippable() && meta.getEquippable().getModel() != null
                && "aquatic".equals(meta.getEquippable().getModel().getNamespace())) {
            meta.setEquippable(null); // back to the item's normal worn look
        }
    }

    /** @return the definition of the stack's custom item, or null. */
    public ItemDef defOf(ItemStack stack) {
        String id = idOf(stack);
        return id == null ? null : defs.get(id);
    }

    public ItemDef def(String id) {
        return id == null ? null : defs.get(id.toLowerCase(Locale.ROOT));
    }

    public List<String> fruitIds() {
        List<String> out = new ArrayList<>();
        for (ItemDef def : defs.values()) {
            if (def.isFruit()) {
                out.add(def.id);
            }
        }
        return out;
    }

    public void actionBar(Player player, String key, Object... placeholders) {
        player.sendActionBar(rawMsg(key, placeholders));
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        AquaticCommand cmd = new AquaticCommand("aitem", "Custom pirate items", "aquaticitem", "pirateitem");
        cmd.sub("give", (sender, args) -> {
            AquaticCommand.need(args, 2, "/aitem give <player> <id> [amount]");
            Player target = AquaticCommand.onlinePlayer(args[0]);
            ItemDef def = def(args[1]);
            if (def == null) {
                throw new CommandFail(rawMsg("unknown-item", "id", args[1]));
            }
            int amount = args.length > 2 ? Math.max(1, Math.min(2304, AquaticCommand.integer(args[2]))) : 1;
            int left = amount;
            int max = Math.max(1, def.create(1).getMaxStackSize());
            while (left > 0) {
                int n = Math.min(max, left);
                Util.give(target, def.create(n));
                left -= n;
            }
            send(sender, "item-given", "amount", amount, "item", def.displayName(), "player", target.getName());
        }).usage("<player> <id> [amount]").description("Give a custom item").permission("aquatic.items.admin")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.playerNames(a[0])
                        : a.length == 2 ? AquaticCommand.filter(defs.keySet(), a[1]) : List.of());

        cmd.sub("list", (sender, args) -> {
            send(sender, "list-header", "count", defs.size());
            for (ItemDef def : defs.values()) {
                sender.sendMessage(rawMsg("list-entry", "id", def.id, "type", def.type, "item", def.displayName()));
            }
        }).description("List all custom items").permission("aquatic.items.admin");

        cmd.sub("fruit", (sender, args) -> {
            AquaticCommand.need(args, 1, "/aitem fruit <set|info> ...");
            if (args[0].equalsIgnoreCase("set")) {
                AquaticCommand.need(args, 3, "/aitem fruit set <player> <fruit|none>");
                OfflinePlayer target = offline(args[1]);
                if (args[2].equalsIgnoreCase("none")) {
                    fruits.setFruit(target.getUniqueId(), null);
                    send(sender, "fruit-cleared", "player", name(target));
                    return;
                }
                ItemDef def = def(args[2]);
                if (def == null || !def.isFruit()) {
                    throw new CommandFail(rawMsg("unknown-fruit", "id", args[2]));
                }
                fruits.setFruit(target.getUniqueId(), def.id);
                send(sender, "fruit-set", "player", name(target), "fruit", def.displayName());
                Player online = target.getPlayer();
                if (online != null) {
                    send(online, "fruit-eaten", "fruit", def.displayName(), "description", def.cfg.getString("fruit.description", ""));
                }
            } else if (args[0].equalsIgnoreCase("level")) {
                AquaticCommand.need(args, 3, "/aitem fruit level <player> <level>");
                OfflinePlayer target = offline(args[1]);
                fruits.setMasteryLevel(target.getUniqueId(), AquaticCommand.integer(args[2]));
                fruits.save();
                sender.sendMessage(plugin().prefix().append(dev.aquatic.util.Text.mm("<green>Fruit mastery of <white><p></white> set to <white><l></white>.",
                        "p", name(target), "l", fruits.masteryLevel(target.getUniqueId()))));
            } else if (args[0].equalsIgnoreCase("info")) {
                OfflinePlayer target = args.length > 1 ? offline(args[1]) : AquaticCommand.player(sender);
                fruitInfo(sender, target.getUniqueId(), name(target));
            } else {
                throw new CommandFail("<red>Usage: <white>/aitem fruit <set|level|info> ...");
            }
        }).usage("<set <player> <fruit|none> | level <player> <level> | info [player]>").description("Manage abyss fruit powers")
                .permission("aquatic.items.admin")
                .completer((s, a) -> {
                    if (a.length == 1) {
                        return AquaticCommand.filter(List.of("set", "info", "level"), a[0]);
                    }
                    if (a.length == 2) {
                        return AquaticCommand.playerNames(a[1]);
                    }
                    if (a.length == 3 && a[0].equalsIgnoreCase("set")) {
                        List<String> options = fruitIds();
                        options.add("none");
                        return AquaticCommand.filter(options, a[2]);
                    }
                    return List.of();
                });
        registerCommand(cmd);

        AquaticCommand recipesCmd = new AquaticCommand("recipes", "How to craft the custom items", "customrecipes");
        recipesCmd.defaultExecutor((s, a) -> recipeBook.openList(AquaticCommand.player(s), 0));
        registerCommand(recipesCmd);
        AquaticCommand fruit = new AquaticCommand("fruit", "Shows your abyss fruit power", "abyssfruit");
        fruit.sub("info", (sender, args) -> {
            Player p = AquaticCommand.player(sender);
            fruitInfo(sender, p.getUniqueId(), p.getName());
        }).description("Show your abyss fruit power and cooldown").playerPermission("aquatic.items.fruit").playerOnly();
        fruit.defaultExecutor((sender, args) -> {
            Player p = AquaticCommand.player(sender);
            if (!p.hasPermission("aquatic.items.fruit")) {
                throw new CommandFail("<red>You don't have permission to do that.");
            }
            fruitInfo(sender, p.getUniqueId(), p.getName());
        });
        registerCommand(fruit);
    }

    private void fruitInfo(CommandSender sender, UUID uuid, String name) {
        ItemDef def = def(fruits.fruitOf(uuid));
        if (def == null) {
            send(sender, "fruit-none", "player", name);
            return;
        }
        ConfigurationSection ability = def.ability();
        long cd = fruits.cooldownLeft(uuid, "fruit");
        send(sender, "fruit-info-header", "player", name, "fruit", def.displayName());
        sender.sendMessage(rawMsg("fruit-info-description", "description", def.cfg.getString("fruit.description", "-")));
        if (ability != null) {
            sender.sendMessage(rawMsg("fruit-info-ability", "ability", Text.mm(ability.getString("name", "Ability")),
                    "cooldown", Text.duration(ability.getLong("cooldown", 0)),
                    "status", cd > 0 ? Text.duration(cd) : Text.plain(rawMsg("ready"))));
        }
        int lvl = fruits.masteryLevel(uuid);
        long next = lvl >= fruits.maxMastery() ? 0 : fruits.masteryXpFor(lvl + 1) - fruits.masteryXp(uuid);
        sender.sendMessage(rawMsg("fruit-info-mastery", "level", lvl, "max", fruits.maxMastery(),
                "next", next, "damage", Math.round((fruits.damageScale(uuid) - 1) * 100),
                "range", Math.round((fruits.rangeScale(uuid) - 1) * 100),
                "cooldown", Math.round((1 - fruits.cooldownScale(uuid)) * 100),
                "hearts", Text.number(fruits.bonusHealth(uuid) / 2.0)));
        sender.sendMessage(rawMsg("fruit-info-trigger"));
    }

    private static OfflinePlayer offline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null) {
            throw new CommandFail("<red>Player <white><name></white> was never seen on this server.", "name", name);
        }
        return cached;
    }

    private static String name(OfflinePlayer p) {
        return p.getName() == null ? p.getUniqueId().toString() : p.getName();
    }

    public Component prefixless(String key, Object... placeholders) {
        return rawMsg(key, placeholders);
    }

    @Override
    protected void migrateConfig(org.bukkit.configuration.file.YamlConfiguration file,
                                 org.bukkit.configuration.file.YamlConfiguration defaults, int from) {
        if (from < 1) {
            // 2.7.0: the Seafoam Milkshake (removes your fruit power)
            addMissing(file, defaults, "items.seafoam_milkshake");
        }
        if (from < 2) {
            // 2.7.1: custom textures (resource pack)
            addMissing(file, defaults, "resource-pack");
            addMissing(file, defaults, "items.conqueror_crown.item-model");
            addMissing(file, defaults, "items.conqueror_crown.equipment-model");
            addMissing(file, defaults, "items.storm_saber.item-model");
        }
    }
}
