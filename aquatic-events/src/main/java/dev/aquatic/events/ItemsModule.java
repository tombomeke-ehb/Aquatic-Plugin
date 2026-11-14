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
        registerListener(new dev.aquatic.events.items.PackSender(this));
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
            List<String> lore = new ArrayList<>(cfg.getStringList("lore"));
            if ("fruit".equals(cfg.getString("type"))) {             // the rarity, on the first line
                String rarity = config().getString("fruits.rarities." + cfg.getString("fruit.rarity", "rare") + ".name");
                if (rarity != null) {
                    lore.add(0, rarity + " <dark_gray>Abyss Fruit");
                }
            }
            meta.lore(Text.itemLore(lore));
        }
        int cmd = cfg.getInt("custom-model-data", 0);
        if (cmd > 0) {
            meta.setCustomModelData(cmd);
        }
        applyModels(meta, cfg, material);
        loadGun(meta, cfg);
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
        if (id != null && id.toLowerCase(Locale.ROOT).startsWith(RANDOM_FRUIT)) {
            if (fruits == null || !fruits.isEnabled()) {
                return null; // fruits are switched off
            }
            ItemDef fruit = randomFruit(id.length() > RANDOM_FRUIT.length() + 1 ? id.substring(RANDOM_FRUIT.length() + 1) : null);
            return fruit == null ? null : fruit.create(amount);
        }
        ItemDef def = id == null ? null : defs.get(id.toLowerCase(Locale.ROOT));
        return def == null ? null : def.create(amount);
    }

    /** The rarities in order, from common to the rarest (fruits.rarities). */
    public List<String> rarities() {
        ConfigurationSection r = config().getConfigurationSection("fruits.rarities");
        return r == null ? List.of() : new ArrayList<>(r.getKeys(false));
    }

    /** A fruit's rarity settings (fruits.rarities.<its rarity>), or null. */
    public ConfigurationSection rarityOf(String fruitId) {
        ItemDef def = fruitId == null ? null : defs.get(fruitId.toLowerCase(Locale.ROOT));
        if (def == null || !def.isFruit()) {
            return null;
        }
        return config().getConfigurationSection("fruits.rarities." + def.cfg.getString("fruit.rarity", "rare"));
    }

    /** A random fruit: first a rarity by its weight (only "atLeast" or rarer), then one of the fruits of that rarity. */
    ItemDef randomFruit(String atLeast) {
        List<String> order = rarities();
        int from = atLeast == null ? 0 : Math.max(0, order.indexOf(atLeast.toLowerCase(Locale.ROOT)));
        Map<String, List<ItemDef>> byRarity = new LinkedHashMap<>();
        for (ItemDef d : defs.values()) {
            if (d.isFruit()) {
                byRarity.computeIfAbsent(d.cfg.getString("fruit.rarity", "rare").toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(d);
            }
        }
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        double total = 0;
        for (int i = from; i < order.size(); i++) {
            if (byRarity.containsKey(order.get(i))) {
                total += Math.max(0, config().getDouble("fruits.rarities." + order.get(i) + ".weight", 1));
            }
        }
        double roll = r.nextDouble() * total;
        for (int i = from; i < order.size(); i++) {
            List<ItemDef> list = byRarity.get(order.get(i));
            if (list == null) {
                continue;
            }
            roll -= Math.max(0, config().getDouble("fruits.rarities." + order.get(i) + ".weight", 1));
            if (roll < 0) {
                return list.get(r.nextInt(list.size()));
            }
        }
        List<ItemDef> all = defs.values().stream().filter(ItemDef::isFruit).toList();
        return all.isEmpty() ? null : all.get(r.nextInt(all.size()));
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
                changed |= loadGun(meta, def.cfg);             // guns made before 2.22.0
            }
            if (changed) {
                stack.setItemMeta(meta);
            }
        }
    }

    /**
     * Guns are crossbows that always count as loaded: a loaded crossbow is held with both arms aiming forward
     * and fires on right-click without the pulling animation (the shot itself is ours, see GearListener).
     *
     * @return true when the item was changed
     */
    boolean loadGun(org.bukkit.inventory.meta.ItemMeta meta, ConfigurationSection cfg) {
        if (!"gun".equals(cfg.getString("type")) || !(meta instanceof org.bukkit.inventory.meta.CrossbowMeta bow)
                || bow.hasChargedProjectiles()) {
            return false;
        }
        bow.addChargedProjectile(new ItemStack(Material.ARROW));
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);   // no "Projectile: Arrow" line
        return true;
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
        // the enchant glint would hide the custom (animated) textures, so it is only used without the pack
        if (cfg.getBoolean("glint", false)) {
            meta.setEnchantmentGlintOverride(!(on && model != null));
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

    /** Everything /aitem give knows: the custom items and the crate keys (key_<crate>). */
    private List<String> giveIds() {
        List<String> ids = new ArrayList<>(defs.keySet());
        dev.aquatic.api.CrateService crateKeys = service(dev.aquatic.api.CrateService.class);
        if (crateKeys != null) {
            for (String c : crateKeys.crateTypes()) {
                ids.add("key_" + c);
            }
        }
        return ids;
    }

    private void registerCommands() {
        AquaticCommand cmd = new AquaticCommand("aitem", "Custom pirate items", "aquaticitem", "pirateitem");
        cmd.sub("give", (sender, args) -> {
            AquaticCommand.need(args, 2, "/aitem give <player> <id> [amount]");
            Player target = AquaticCommand.onlinePlayer(args[0]);
            ItemDef def = def(args[1]);
            if (def == null) {
                // crate keys: key_sailor, key_captain ... (crates.yml)
                dev.aquatic.api.CrateService crateKeys = service(dev.aquatic.api.CrateService.class);
                String raw = args[1].toLowerCase(java.util.Locale.ROOT);
                String crate = raw.startsWith("key_") || raw.startsWith("key:") ? raw.substring(4) : null;
                if (crateKeys != null && crate != null && crateKeys.crateTypes().contains(crate)) {
                    int n = args.length > 2 ? Math.max(1, Math.min(2304, AquaticCommand.integer(args[2]))) : 1;
                    for (int left = n; left > 0; left -= 64) {
                        Util.give(target, crateKeys.key(crate, Math.min(64, left)));
                    }
                    send(sender, "item-given", "amount", n, "item", crateKeys.key(crate, 1).displayName(), "player", target.getName());
                    return;
                }
                if (args[1].toLowerCase(Locale.ROOT).startsWith(RANDOM_FRUIT)) {        // fruit_random, fruit_random_epic ...
                    ItemStack fruit = createItem(args[1], 1);
                    if (fruit != null) {
                        Util.give(target, fruit);
                        send(sender, "item-given", "amount", 1, "item", fruit.displayName(), "player", target.getName());
                        return;
                    }
                }
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
                        : a.length == 2 ? AquaticCommand.filter(giveIds(), a[1]) : List.of());

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
        ConfigurationSection rar = rarityOf(def.id);
        if (rar != null) {
            sender.sendMessage(Text.mm(" <gray>Rarity: " + rar.getString("name", "?") + " <dark_gray>(" + Math.round(rar.getDouble("power", 1) * 100)
                    + "% power, " + Math.round(rar.getDouble("cooldown", 1) * 100) + "% cooldown)"));
        }
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
        if (from < 12) {
            // 2.26.0: fruit rarities (weaker common fruits, stronger rare ones)
            addMissing(file, defaults, "fruits.rarities");
            ConfigurationSection items = defaults.getConfigurationSection("items");
            if (items != null) {
                for (String id : items.getKeys(false)) {
                    if (defaults.contains("items." + id + ".fruit.rarity") && file.contains("items." + id + ".fruit")) {
                        addMissing(file, defaults, "items." + id + ".fruit.rarity");
                    }
                }
            }
        }
        if (from < 11) {
            // 2.23.0: fruit mastery takes much longer; the Tidecaller is light blue
            raise(file, "fruits.mastery.xp-per-use", 6, 5);
            raise(file, "fruits.mastery.xp-per-hit", 3, 2);
            raise(file, "fruits.mastery.curve-base", 60, 120);
            raise(file, "fruits.mastery.curve-exponent", 1.5, 1.8);
            raise(file, "items.tidecaller.name", "<gradient:#2e86de:#a9dcff><bold>Tidecaller Fruit</bold></gradient>",
                    defaults.getString("items.tidecaller.name"));
            raise(file, "items.tidecaller.ability.color", "#2e86de", "#7fd4ff");
        }
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
        if (from < 3) {
            // 2.7.2: textures for four fruits, the flintlock, the Tide Compass and the Abyssal Iron Baton
            addMissing(file, defaults, "items.springcoil.item-model");
            addMissing(file, defaults, "items.emberheart.item-model");
            addMissing(file, defaults, "items.rimefrost.item-model");
            addMissing(file, defaults, "items.thunderveil.item-model");
            addMissing(file, defaults, "items.flintlock.item-model");
            addMissing(file, defaults, "items.tide_compass.item-model");
            addMissing(file, defaults, "items.abyssal_baton.item-model");
        }
        if (from < 4) {
            // 2.7.3: the Tide Compass keeps the normal compass look, so its needle keeps pointing
            if ("aquatic:tide_compass".equals(file.getString("items.tide_compass.item-model"))) {
                file.set("items.tide_compass.item-model", null);
            }
        }
        if (from < 10) {
            // 2.17.0: fruit moves grow much more with mastery
            raise(file, "fruits.mastery.range-per-level", 0.02, 0.05);
        }
        if (from < 9) {
            // 2.12.1: the plugin sends the resource pack itself (no server.properties lines needed)
            for (String key : java.util.List.of("send", "required", "prompt", "url", "sha1")) {
                addMissing(file, defaults, "resource-pack." + key);
            }
            addMissing(file, defaults, "messages.pack-failed");
            addMissing(file, defaults, "messages.pack-declined");
        }
        if (from < 8) {
            // 2.11.0: the harpoon, coral scimitar, abyssal anchor and deepcore pickaxe
            for (String id : java.util.List.of("harpoon", "coral_scimitar", "abyssal_anchor", "deepcore_pickaxe")) {
                addMissing(file, defaults, "items." + id);
            }
        }
        if (from < 7) {
            // 2.10.0: the Musket
            addMissing(file, defaults, "items.musket");
        }
        if (from < 6) {
            // 2.9.0: four new fruits and the Tidewatch uniforms
            addMissing(file, defaults, "items.tidecaller");
            addMissing(file, defaults, "items.venomfang");
            addMissing(file, defaults, "items.dunemaw");
            addMissing(file, defaults, "items.luminara");
            addMissing(file, defaults, "items.tidewatch_hat");
            addMissing(file, defaults, "items.tidewatch_coat");
            addMissing(file, defaults, "items.tidewatch_trousers");
            addMissing(file, defaults, "items.tidewatch_boots");
            addMissing(file, defaults, "items.tidewatch_officer_coat");
            addMissing(file, defaults, "items.tidewatch_captain_hat");
            addMissing(file, defaults, "items.tidewatch_captain_coat");
        }
        if (from < 5) {
            // 2.8.0: textures for (almost) every custom item, 3D guns, worn armour looks
            addMissing(file, defaults, "items.ghostglass.item-model");
            addMissing(file, defaults, "items.riftshift.item-model");
            addMissing(file, defaults, "items.hollowvoid.item-model");
            addMissing(file, defaults, "items.behemoth.item-model");
            addMissing(file, defaults, "items.galeborn.item-model");
            addMissing(file, defaults, "items.stoneskin.item-model");
            addMissing(file, defaults, "items.tidecleaver.item-model");
            addMissing(file, defaults, "items.nightbloom.item-model");
            addMissing(file, defaults, "items.leviathans_fang.item-model");
            addMissing(file, defaults, "items.tide_rapier.item-model");
            addMissing(file, defaults, "items.abyss_cutlass.item-model");
            addMissing(file, defaults, "items.krakens_maul.item-model");
            addMissing(file, defaults, "items.abyssal_shackles.item-model");
            addMissing(file, defaults, "items.smoke_bomb.item-model");
            addMissing(file, defaults, "items.frost_bomb.item-model");
            addMissing(file, defaults, "items.fire_bomb.item-model");
            addMissing(file, defaults, "items.cannonball.item-model");
            addMissing(file, defaults, "items.echo_conch.item-model");
            addMissing(file, defaults, "items.rum.item-model");
            addMissing(file, defaults, "items.healing_tonic.item-model");
            addMissing(file, defaults, "items.gale_grog.item-model");
            addMissing(file, defaults, "items.seafoam_milkshake.item-model");
            addMissing(file, defaults, "items.hand_cannon.item-model");
            addMissing(file, defaults, "items.tidewarden_helmet.item-model");
            addMissing(file, defaults, "items.tidewarden_chestplate.item-model");
            addMissing(file, defaults, "items.tidewarden_leggings.item-model");
            addMissing(file, defaults, "items.tidewarden_boots.item-model");
            addMissing(file, defaults, "items.corsair_hat.item-model");
            addMissing(file, defaults, "items.corsair_coat.item-model");
            addMissing(file, defaults, "items.corsair_breeches.item-model");
            addMissing(file, defaults, "items.corsair_boots.item-model");
            addMissing(file, defaults, "items.captains_coat.item-model");
            addMissing(file, defaults, "items.tidewarden_helmet.equipment-model");
            addMissing(file, defaults, "items.tidewarden_chestplate.equipment-model");
            addMissing(file, defaults, "items.tidewarden_leggings.equipment-model");
            addMissing(file, defaults, "items.tidewarden_boots.equipment-model");
            addMissing(file, defaults, "items.corsair_hat.equipment-model");
            addMissing(file, defaults, "items.corsair_coat.equipment-model");
            addMissing(file, defaults, "items.corsair_breeches.equipment-model");
            addMissing(file, defaults, "items.corsair_boots.equipment-model");
            addMissing(file, defaults, "items.captains_coat.equipment-model");
        }
    }
}
