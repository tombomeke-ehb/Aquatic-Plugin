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

/** Devil fruit powers: who ate what, passives, the active ability, sea weakness and sea prism stone. */
public final class FruitManager implements Listener {

    private final ItemsModule module;
    private final Map<UUID, String> fruitOf = new HashMap<>();
    /** "uuid|key" -> time (ms) at which the cooldown ends. Shared with item abilities. */
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> seastoned = new HashMap<>();
    private final Map<UUID, Long> lastTrigger = new HashMap<>();
    private final Set<UUID> inSea = new HashSet<>();
    private final Set<UUID> coolingDown = new HashSet<>();
    private final Map<String, List<PotionEffect>> passiveCache = new HashMap<>();

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
                fruitOf.put(UUID.fromString(key), players.getString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration data = new YamlConfiguration();
        fruitOf.forEach((uuid, fruit) -> data.set("players." + uuid, fruit));
        module.saveData("fruits", data);
    }

    // ------------------------------------------------------------------ state

    public String fruitOf(UUID player) {
        return fruitOf.get(player);
    }

    public void setFruit(UUID player, String fruitId) {
        if (fruitId == null) {
            fruitOf.remove(player);
        } else {
            fruitOf.put(player, fruitId);
        }
        save();
    }

    private ItemDef fruitDef(Player p) {
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

    public boolean isSeastoned(UUID player) {
        Long until = seastoned.get(player);
        return until != null && until > System.currentTimeMillis();
    }

    /** Sea prism stone: disables the player's fruit for a while. */
    public void seastone(Player target, double seconds) {
        long until = System.currentTimeMillis() + (long) (seconds * 1000);
        seastoned.merge(target.getUniqueId(), until, Math::max);
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
        seastoned.values().removeIf(until -> until < now);
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
            boolean suppressed = isSeastoned(uuid) || (wet && sea != null && sea.getBoolean("disable-passives", true));
            if (!suppressed) {
                for (PotionEffect e : passives(def, ticks)) {
                    p.addPotionEffect(e);
                }
            }
            long left = cooldownLeft(uuid, "fruit");
            ConfigurationSection ability = def.ability();
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

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemDef def = module.defOf(event.getItem());
        if (def == null || !def.isFruit()) {
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
        Util.sound(p.getLocation(), "entity.elder_guardian.curse", 1f, 1.4f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0, true, false, false));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
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
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        ItemDef def = fruitDef(p);
        if (def == null) {
            return;
        }
        List<String> immunities = def.cfg.getStringList("fruit.immunities");
        if (immunities.isEmpty() || isSeastoned(p.getUniqueId()) || inSea.contains(p.getUniqueId())) {
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
        if (event.isSwimming() && event.getEntity() instanceof Player p && inSea.contains(p.getUniqueId())
                && module.config().getBoolean("fruits.sea-weakness.sink", true)) {
            event.setCancelled(true);
        }
    }

    /** Ice-type fruits freeze the water they walk on. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
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
        if (radius <= 0 || p.isFlying() || p.isInsideVehicle() || p.isInWater() || isSeastoned(p.getUniqueId())
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
        ConfigurationSection ability = def == null ? null : def.ability();
        if (ability == null) {
            return;
        }
        UUID uuid = p.getUniqueId();
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
        if (isSeastoned(uuid)) {
            module.actionBar(p, "ability-seastone");
            return;
        }
        if (seaWeak(p) && module.config().getBoolean("fruits.sea-weakness.disable-abilities", true)) {
            module.actionBar(p, "ability-sea");
            return;
        }
        long left = cooldownLeft(uuid, "fruit");
        if (left > 0) {
            module.actionBar(p, "ability-cooldown", "ability", name, "seconds", left);
            return;
        }
        if (abilities.execute(p, ability)) {
            setCooldown(uuid, "fruit", ability.getDouble("cooldown", 10));
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
        module.gear().abilities().forget(uuid);
    }
}
