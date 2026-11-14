package dev.aquatic.events.items;

import dev.aquatic.events.ItemsModule;
import dev.aquatic.events.Util;
import dev.aquatic.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleSwimEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Abyss fruit powers: who ate what, passives, the active ability, sea weakness and Abyssal Iron. */
public final class FruitManager implements Listener {

    private final ItemsModule module;
    private final Map<UUID, String> fruitOf = new HashMap<>();
    /** "uuid|key" -> time (ms) at which the cooldown ends. Shared with item abilities. */
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> sealed = new HashMap<>();
    private final Map<UUID, Long> lastTrigger = new HashMap<>();
    /** Selected move per player (index into ItemDef.moves()). */
    private final Map<UUID, Integer> selectedMove = new HashMap<>();

    /** The move a player has selected (null when the fruit has none). */
    public ConfigurationSection currentMove(UUID player, ItemDef def) {
        java.util.List<ConfigurationSection> moves = def == null ? java.util.List.of() : def.moves();
        if (moves.isEmpty()) {
            return null;
        }
        int i = Math.floorMod(selectedMove.getOrDefault(player, 0), moves.size());
        return moves.get(i);
    }

    private String moveKey(UUID player, ItemDef def) {
        int n = def == null ? 1 : Math.max(1, def.moves().size());
        return "fruit:" + Math.floorMod(selectedMove.getOrDefault(player, 0), n);
    }

    /** Sneak + scroll the hotbar (with an empty hand) switches between a fruit's moves. */
    @EventHandler(ignoreCancelled = true)
    public void onScroll(org.bukkit.event.player.PlayerItemHeldEvent event) {
        if (!enabled) {
            return;
        }
        Player p = event.getPlayer();
        if (!p.isSneaking() || !module.config().getBoolean("fruits.switch-with-sneak-scroll", true)) {
            return;
        }
        ItemDef def = fruitDef(p);
        if (def == null || def.moves().size() < 2 || !p.getInventory().getItemInMainHand().getType().isAir()) {
            return;
        }
        event.setCancelled(true);
        int prev = event.getPreviousSlot(), next = event.getNewSlot();
        int dir = (next - prev + 9) % 9 <= 4 ? 1 : -1;
        int size = def.moves().size();
        int i = Math.floorMod(selectedMove.getOrDefault(p.getUniqueId(), 0) + dir, size);
        selectedMove.put(p.getUniqueId(), i);
        ConfigurationSection move = def.moves().get(i);
        module.actionBar(p, "move-selected", "ability", Text.mm(move.getString("name", "Move")), "n", i + 1, "total", size);
        Util.sound(p, "ui.button.click", 0.5f, 1.6f);
    }
    private final Set<UUID> inSea = new HashSet<>();
    private final Set<UUID> coolingDown = new HashSet<>();
    private final Map<String, List<PotionEffect>> passiveCache = new HashMap<>();
    /** Mastery XP per player per fruit: "uuid|fruit" -> xp. */
    private final Map<String, Long> mastery = new HashMap<>();
    private boolean masteryDirty;
    /** False while the "fruits" module is off: no eating, no powers, no bonus hearts (data is kept). */
    private boolean enabled = true;
    private Boolean appliedEnabled;
    private long lastSave = System.currentTimeMillis();
    private java.util.Set<ConfigurationSection> fruitMoves;

    // ------------------------------------------------------------------ mastery

    private ConfigurationSection masteryCfg() {
        ConfigurationSection c = module.config().getConfigurationSection("fruits.mastery");
        return c != null ? c : new YamlConfiguration();
    }

    public int maxMastery() {
        return Math.max(1, masteryCfg().getInt("max-level", 20));
    }

    /** Total XP to reach a mastery level (level 1 = 0). */
    public long masteryXpFor(int level) {
        double base = masteryCfg().getDouble("curve-base", 60), exp = masteryCfg().getDouble("curve-exponent", 1.5);
        long total = 0;
        for (int l = 1; l < level; l++) {
            total += Math.round(base * Math.pow(l, exp));
        }
        return total;
    }

    public long masteryXp(UUID player) {
        String f = fruitOf.get(player);
        return f == null ? 0 : mastery.getOrDefault(player + "|" + f, 0L);
    }

    public int masteryLevel(UUID player) {
        if (!masteryCfg().getBoolean("enabled", true)) {
            return 1;
        }
        long xp = masteryXp(player);
        int level = 1;
        while (level < maxMastery() && xp >= masteryXpFor(level + 1)) {
            level++;
        }
        return level;
    }

    public void setMasteryLevel(UUID player, int level) {
        String f = fruitOf.get(player);
        if (f != null) {
            mastery.put(player + "|" + f, masteryXpFor(Math.max(1, Math.min(maxMastery(), level))));
            masteryDirty = true;
        }
    }

    /** Training: XP for the player's current fruit; announces level-ups. */
    public void addMastery(Player p, long amount) {
        String f = fruitOf.get(p.getUniqueId());
        if (f == null || amount <= 0 || !masteryCfg().getBoolean("enabled", true)) {
            return;
        }
        int before = masteryLevel(p.getUniqueId());
        mastery.merge(p.getUniqueId() + "|" + f, amount, Long::sum);
        masteryDirty = true;
        int after = masteryLevel(p.getUniqueId());
        if (after > before) {
            ItemDef def = module.def(f);
            p.showTitle(net.kyori.adventure.title.Title.title(module.rawMsg("mastery-title", "level", after),
                    module.rawMsg("mastery-subtitle", "fruit", def == null ? net.kyori.adventure.text.Component.text(f) : def.displayName())));
            module.send(p, "mastery-levelup", "level", after, "damage", Math.round((damageScale(p.getUniqueId()) - 1) * 100),
                    "hearts", Text.number(bonusHealth(p.getUniqueId()) / 2.0));
            p.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, p.getLocation().add(0, 1, 0), 60, 0.6, 1, 0.6, 0.15);
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
        }
    }

    /** Damage multiplier of fruit moves: the global fruit multiplier x mastery. */
    public double damageScale(UUID player) {
        double global = module.config().getDouble("abilities.fruit-damage-multiplier", 1.5);
        return global * rarity(player, "power") * (1 + (masteryLevel(player) - 1) * masteryCfg().getDouble("damage-per-level", 0.04));
    }

    public double rangeScale(UUID player) {
        return 1 + (masteryLevel(player) - 1) * masteryCfg().getDouble("range-per-level", 0.02);
    }

    public double cooldownScale(UUID player) {
        return rarity(player, "cooldown") * Math.max(masteryCfg().getDouble("min-cooldown", 0.6),
                1 - (masteryLevel(player) - 1) * masteryCfg().getDouble("cooldown-per-level", 0.015));
    }

    /** A setting of the rarity of the player's fruit (power, cooldown), 1 when it has none. */
    double rarity(UUID player, String key) {
        org.bukkit.configuration.ConfigurationSection r = module.rarityOf(fruitOf.get(player));
        return r == null ? 1.0 : r.getDouble(key, 1.0);
    }

    /** Extra max health (half-hearts) from having a fruit: a base amount plus a heart every few mastery levels. */
    public double bonusHealth(UUID player) {
        if (fruitOf.get(player) == null) {
            return 0;
        }
        int every = Math.max(1, masteryCfg().getInt("heart-every", 4));
        org.bukkit.configuration.ConfigurationSection r = module.rarityOf(fruitOf.get(player));
        return module.config().getDouble("fruits.bonus-health", 6) + (r == null ? 0 : r.getDouble("health", 0))
                + (masteryLevel(player) / every) * 2.0;
    }

    /** True when the section is one of the configured fruit moves (identity check). */
    public boolean isFruitMove(ConfigurationSection section) {
        if (fruitMoves == null) {
            java.util.Set<ConfigurationSection> set = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (String id : module.itemIds()) {
                ItemDef def = module.def(id);
                if (def != null && def.isFruit()) {
                    set.addAll(def.moves());
                }
            }
            fruitMoves = set;
        }
        return section != null && fruitMoves.contains(section);
    }

    public FruitManager(ItemsModule module) {
        this.module = module;
    }

    // ------------------------------------------------------------------ persistence

    public void load() {
        fruitOf.clear();
        ConfigurationSection players = module.loadData("fruits").getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                fruitOf.put(UUID.fromString(key), dev.aquatic.core.LegacyNames.id(players.getString(key)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        loadMastery();
    }

    private void loadMastery() {
        mastery.clear();
        ConfigurationSection m = module.loadData("fruits").getConfigurationSection("mastery");
        if (m != null) {
            for (String player : m.getKeys(false)) {
                ConfigurationSection per = m.getConfigurationSection(player);
                if (per != null) {
                    for (String fruit : per.getKeys(false)) {
                        mastery.merge(player + "|" + dev.aquatic.core.LegacyNames.id(fruit), per.getLong(fruit), Long::sum);
                    }
                }
            }
        }
    }

    public void save() {
        YamlConfiguration data = new YamlConfiguration();
        fruitOf.forEach((uuid, fruit) -> data.set("players." + uuid, fruit));
        mastery.forEach((key, xp) -> {
            String[] parts = key.split("\\|", 2);
            data.set("mastery." + parts[0] + "." + parts[1], xp);
        });
        masteryDirty = false;
        lastSave = System.currentTimeMillis();
        module.saveData("fruits", data);
    }

    // ------------------------------------------------------------------ state

    public String fruitOf(UUID player) {
        return enabled ? fruitOf.get(player) : null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Turned on/off by the "fruits" module; the bonus hearts follow on the next tick. */
    public void setEnabled(boolean on) {
        enabled = on;
    }

    public void setFruit(UUID player, String fruitId) {
        if (fruitId == null) {
            fruitOf.remove(player);
        } else {
            fruitOf.put(player, fruitId);
        }
        save();
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            dev.aquatic.util.Stats.health(p, "fruit", bonusHealth(player));
        }
    }

    private ItemDef fruitDef(Player p) {
        if (!enabled) {
            return null;
        }
        String id = fruitOf.get(p.getUniqueId());
        ItemDef def = id == null ? null : module.def(id);
        return def != null && def.isFruit() ? def : null;
    }

    /** Remaining cooldown in whole seconds (0 = ready). */
    public long cooldownLeft(UUID player, String key) {
        Long until = cooldowns.get(player + "|" + key);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    public void setCooldown(UUID player, String key, double seconds) {
        if (seconds > 0) {
            cooldowns.put(player + "|" + key, System.currentTimeMillis() + (long) (seconds * 1000));
        }
    }

    public boolean isSealed(UUID player) {
        Long until = sealed.get(player);
        return until != null && until > System.currentTimeMillis();
    }

    /** Abyssal Iron: disables the player's fruit for a while. */
    public void seal(Player target, double seconds) {
        long until = System.currentTimeMillis() + (long) (seconds * 1000);
        sealed.merge(target.getUniqueId(), until, Math::max);
        module.gear().abilities().endInvisibility(target);
    }

    private boolean seaWeak(Player p) {
        ConfigurationSection sea = module.config().getConfigurationSection("fruits.sea-weakness");
        if (sea == null || !sea.getBoolean("enabled", true)) {
            return false;
        }
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        return sea.getBoolean("rain", false) ? p.isInWaterOrRain() : p.isInWater();
    }

    // ------------------------------------------------------------------ periodic work

    /** Light task (default once per second): passives, sea weakness, cooldown bar. */
    public void tick(long interval) {
        long now = System.currentTimeMillis();
        cooldowns.values().removeIf(until -> until < now);
        if (appliedEnabled == null || appliedEnabled != enabled) {
            appliedEnabled = enabled; // fruits module switched: add or remove the fruit hearts
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (enabled) {
                    dev.aquatic.util.Stats.health(p, "fruit", bonusHealth(p.getUniqueId()));
                } else {
                    dev.aquatic.util.Stats.clear(p, "fruit");
                }
            }
        }
        if (!enabled) {
            return;
        }
        sealed.values().removeIf(until -> until < now);
        if (masteryDirty && now - lastSave >= 60_000) {
            save(); // mastery XP, once a minute
        }
        if (fruitOf.isEmpty()) {
            return;
        }
        ConfigurationSection sea = module.config().getConfigurationSection("fruits.sea-weakness");
        boolean showBar = module.config().getBoolean("fruits.show-cooldown-bar", true);
        int ticks = (int) interval + 40;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemDef def = fruitDef(p);
            if (def == null) {
                continue;
            }
            UUID uuid = p.getUniqueId();
            dev.aquatic.util.Stats.health(p, "fruit", bonusHealth(uuid));
            boolean wet = seaWeak(p);
            if (wet) {
                if (inSea.add(uuid)) {
                    module.actionBar(p, "sea-weakness");
                }
                if (sea != null) {
                    for (PotionEffect e : Util.parseEffects(sea.getStringList("effects"), ticks)) {
                        p.addPotionEffect(e);
                    }
                }
            } else {
                inSea.remove(uuid);
            }
            boolean suppressed = isSealed(uuid) || (wet && sea != null && sea.getBoolean("disable-passives", true));
            if (!suppressed) {
                for (PotionEffect e : passives(def, ticks)) {
                    p.addPotionEffect(e);
                }
            }
            long left = cooldownLeft(uuid, moveKey(uuid, def));
            ConfigurationSection ability = currentMove(uuid, def);
            if (ability != null && showBar) {
                if (left > 0) {
                    coolingDown.add(uuid);
                    module.actionBar(p, "cooldown-bar", "ability", Text.mm(ability.getString("name", "Ability")), "seconds", left);
                } else if (coolingDown.remove(uuid)) {
                    module.actionBar(p, "ability-ready", "ability", Text.mm(ability.getString("name", "Ability")));
                }
            }
        }
    }

    private List<PotionEffect> passives(ItemDef def, int ticks) {
        return passiveCache.computeIfAbsent(def.id, id -> {
            List<PotionEffect> list = Util.parseEffects(def.cfg.getStringList("fruit.passives"), ticks);
            list.replaceAll(e -> e.getType().equals(PotionEffectType.NIGHT_VISION) ? e.withDuration(Math.max(ticks, 320)) : e);
            return list;
        });
    }

    /** Every tick: fruit users in water cannot swim and sink like a hammer. */
    public void fastTick() {
        if (inSea.isEmpty()) {
            return;
        }
        ConfigurationSection sea = module.config().getConfigurationSection("fruits.sea-weakness");
        if (sea == null || !sea.getBoolean("sink", true)) {
            return;
        }
        double speed = Math.abs(sea.getDouble("sink-speed", 0.12));
        for (UUID uuid : inSea) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isInWater() || p.isInsideVehicle() || p.isDead()) {
                continue;
            }
            if (p.isSwimming()) {
                p.setSwimming(false);
            }
            Vector v = p.getVelocity();
            if (v.getY() > -speed) {
                v.setY(-speed);
                v.setX(v.getX() * 0.7);
                v.setZ(v.getZ() * 0.7);
                p.setVelocity(v);
            }
        }
    }

    // ------------------------------------------------------------------ eating

    /** The Seafoam Milkshake (type "cleanse"): removes the player's fruit power (mastery is kept). */
    @EventHandler(ignoreCancelled = true)
    public void onCleanse(PlayerItemConsumeEvent event) {
        ItemDef def = module.defOf(event.getItem());
        if (def == null || !"cleanse".equals(def.type)) {
            return;
        }
        Player p = event.getPlayer();
        String current = fruitOf.get(p.getUniqueId());
        if (current == null) {
            event.setCancelled(true);
            module.actionBar(p, "cleanse-nothing");
            return;
        }
        ItemDef old = module.def(current);
        setFruit(p.getUniqueId(), null);
        selectedMove.remove(p.getUniqueId());
        p.sendMessage(module.msg("cleanse-done", "fruit", old == null ? net.kyori.adventure.text.Component.text(current)
                : dev.aquatic.util.Text.mm(old.cfg.getString("name", current))));
        p.getWorld().spawnParticle(org.bukkit.Particle.SPLASH, p.getLocation().add(0, 1.2, 0), 40, 0.4, 0.5, 0.4, 0.1);
        Fx fx = module.gear().abilities().fx();       // the power is washed out: a white burst and a fading glow
        org.bukkit.Color foam = org.bukkit.Color.fromRGB(0xEAF7FF);
        fx.nova(p.getLocation().add(0, 1, 0), foam, 2.5);
        fx.spiral(p, 30, foam);
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_DRINK, 1f, 0.8f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemDef def = module.defOf(event.getItem());
        if (def == null || !def.isFruit()) {
            return;
        }
        if (!enabled) {
            event.setCancelled(true);
            module.actionBar(event.getPlayer(), "fruits-disabled");
            return;
        }
        Player p = event.getPlayer();
        ConfigurationSection cfg = module.config().getConfigurationSection("fruits");
        String current = fruitOf.get(p.getUniqueId());
        boolean onePer = cfg == null || cfg.getBoolean("one-per-player", true);
        if (current != null && onePer) {
            String mode = cfg == null ? "refuse" : cfg.getString("second-fruit", "refuse");
            if (!"lethal".equalsIgnoreCase(mode)) {
                event.setCancelled(true);
                module.send(p, "fruit-already");
                return;
            }
            module.send(p, "fruit-second-lethal");
            if (cfg.getBoolean("announce", true)) {
                Util.broadcast(module.msg("fruit-second-broadcast", "player", p.getName()));
            }
            p.getWorld().strikeLightningEffect(p.getLocation());
            module.runLater(() -> {
                if (p.isOnline() && !p.isDead()) {
                    p.setHealth(0);
                }
            }, 1L);
            return;
        }
        if (cfg != null && cfg.getBoolean("unique-fruits", false) && fruitOf.containsValue(def.id)) {
            event.setCancelled(true);
            module.send(p, "fruit-taken", "fruit", def.displayName());
            return;
        }
        setFruit(p.getUniqueId(), def.id);
        module.send(p, "fruit-eaten", "fruit", def.displayName(), "description", def.cfg.getString("fruit.description", ""));
        if (cfg == null || cfg.getBoolean("announce", true)) {
            Util.broadcast(module.msg("fruit-eaten-broadcast", "player", p.getName(), "fruit", def.displayName()));
        }
        p.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.5, 0.8, 0.5, 0.2);
        ConfigurationSection first = def.ability();
        org.bukkit.Color fc = Fx.color(first == null ? null : first.getString("color"), org.bukkit.Color.fromRGB(0xB388FF));
        Fx fx = module.gear().abilities().fx();
        fx.pillar(p.getLocation(), fc, 4.5, 18);
        fx.rune(p.getLocation(), fc, 3.2, 60);
        fx.nova(p.getLocation().add(0, 1, 0), fc, 3.0);
        fx.orbit(p, 60, fc, 4);
        Util.sound(p.getLocation(), "entity.elder_guardian.curse", 1f, 1.4f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0, true, false, false));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled) {
            return;
        }
        Player p = event.getEntity();
        inSea.remove(p.getUniqueId());
        ItemDef def = fruitDef(p);
        if (def == null || !module.config().getBoolean("fruits.lose-on-death", false)) {
            return;
        }
        setFruit(p.getUniqueId(), null);
        module.send(p, "fruit-lost", "fruit", def.displayName());
        if (module.config().getBoolean("fruits.reincarnate-drop", true)) {
            if (event.getKeepInventory()) {
                p.getWorld().dropItemNaturally(p.getLocation(), def.create(1));
            } else {
                event.getDrops().add(def.create(1));
            }
        }
    }

    // ------------------------------------------------------------------ passives implemented by events

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!enabled) {
            return;
        }
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        ItemDef def = fruitDef(p);
        if (def == null) {
            return;
        }
        List<String> immunities = def.cfg.getStringList("fruit.immunities");
        if (immunities.isEmpty() || isSealed(p.getUniqueId()) || inSea.contains(p.getUniqueId())) {
            return;
        }
        String cause = event.getCause().name();
        for (String immunity : immunities) {
            if (immunity.equalsIgnoreCase(cause)) {
                event.setCancelled(true);
                if (cause.startsWith("FIRE") || cause.equals("LAVA") || cause.equals("HOT_FLOOR")) {
                    p.setFireTicks(0);
                }
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwim(EntityToggleSwimEvent event) {
        if (!enabled) {
            return;
        }
        if (event.isSwimming() && event.getEntity() instanceof Player p && inSea.contains(p.getUniqueId())
                && module.config().getBoolean("fruits.sea-weakness.sink", true)) {
            event.setCancelled(true);
        }
    }

    /** Ice-type fruits freeze the water they walk on. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!enabled) {
            return;
        }
        if (fruitOf.isEmpty() || !event.hasChangedBlock()) {
            return;
        }
        Player p = event.getPlayer();
        String id = fruitOf.get(p.getUniqueId());
        if (id == null) {
            return;
        }
        ItemDef def = module.def(id);
        int radius = def == null ? 0 : Math.min(5, def.cfg.getInt("fruit.frost-walker-radius", 0));
        if (radius <= 0 || p.isFlying() || p.isInsideVehicle() || p.isInWater() || isSealed(p.getUniqueId())
                || p.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Block base = event.getTo().getBlock().getRelative(BlockFace.DOWN);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                Block b = base.getRelative(dx, 0, dz);
                if (b.getType() != Material.WATER || !(b.getBlockData() instanceof Levelled lv) || lv.getLevel() != 0
                        || !b.getRelative(BlockFace.UP).getType().isAir()) {
                    continue;
                }
                BlockState state = b.getState();
                state.setType(Material.FROSTED_ICE);
                EntityBlockFormEvent form = new EntityBlockFormEvent(p, b, state);
                Bukkit.getPluginManager().callEvent(form);
                if (!form.isCancelled()) {
                    state.update(true);
                }
            }
        }
    }

    // ------------------------------------------------------------------ ability triggers

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL) {
            return;
        }
        Player p = event.getPlayer();
        if (!p.isSneaking() || !fruitOf.containsKey(p.getUniqueId())
                || !p.getInventory().getItemInMainHand().getType().isAir()) {
            return;
        }
        boolean left = event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK;
        if (trigger(left ? "sneak-left-click" : "sneak-right-click")) {
            use(p);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!enabled) {
            return;
        }
        Player p = event.getPlayer();
        if (!fruitOf.containsKey(p.getUniqueId())) {
            return;
        }
        if (trigger("swap-hands") || (p.isSneaking() && trigger("sneak-swap-hands"))) {
            event.setCancelled(true);
            use(p);
        }
    }

    private boolean trigger(String name) {
        for (String t : module.config().getStringList("fruits.triggers")) {
            if (t.toLowerCase(Locale.ROOT).replace('_', '-').equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void use(Player p) {
        ItemDef def = fruitDef(p);
        UUID uuid = p.getUniqueId();
        ConfigurationSection ability = currentMove(uuid, def);
        if (ability == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastTrigger.put(uuid, now);
        if (last != null && now - last < 200) {
            return;
        }
        Abilities abilities = module.gear().abilities();
        if (abilities.toggleOff(p, ability)) {
            return;
        }
        Object name = Text.mm(ability.getString("name", "Ability"));
        if (isSealed(uuid)) {
            module.actionBar(p, "ability-abyssal");
            return;
        }
        if (seaWeak(p) && module.config().getBoolean("fruits.sea-weakness.disable-abilities", true)) {
            module.actionBar(p, "ability-sea");
            return;
        }
        String key = moveKey(uuid, def);
        long left = cooldownLeft(uuid, key);
        if (left > 0) {
            module.actionBar(p, "ability-cooldown", "ability", name, "seconds", left);
            return;
        }
        boolean ok;
        abilities.castDamageScale = damageScale(uuid);
        abilities.castRangeScale = rangeScale(uuid);
        try {
            ok = abilities.executeWithWindup(p, ability);
        } finally {
            abilities.castDamageScale = 0;
            abilities.castRangeScale = 1;
        }
        if (ok) {
            Fx fx = module.gear().abilities().fx();
            org.bukkit.Color mc = Fx.color(ability.getString("color"), org.bukkit.Color.fromRGB(0xB388FF));
            org.bukkit.Location hand = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(0.8)).add(0, -0.3, 0);
            fx.flash(hand, mc, 1.0f);
            fx.sparkles(hand, mc, 6, 0.3);
            fx.rune(p.getLocation(), mc, 1.5, 16);
            addMastery(p, masteryCfg().getLong("xp-per-use", 6));
            setCooldown(uuid, key, ability.getDouble("cooldown", 10) * cooldownScale(uuid));
            module.actionBar(p, "ability-used", "ability", name);
        } else {
            module.actionBar(p, "ability-no-target", "ability", name);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        inSea.remove(uuid);
        coolingDown.remove(uuid);
        lastTrigger.remove(uuid);
        selectedMove.remove(uuid);
        if (masteryDirty) {
            save();
        }
        module.gear().abilities().forget(uuid);
    }
}
