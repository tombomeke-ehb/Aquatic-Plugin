package dev.aquatic.world;

import dev.aquatic.api.EconomyService;
import dev.aquatic.api.IslandStatus;
import dev.aquatic.api.IslandType;
import dev.aquatic.api.LevelService;
import dev.aquatic.api.TreasureService;
import dev.aquatic.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tidewatch outposts ("tidewatch" in islands.yml): a garrison of Tidewatch soldiers defends every TIDEWATCH
 * island while players are on it, led by a Warden Captain in the keep. Killing the captain liberates the
 * island for a while: the soldiers retreat, every attacker on the island is rewarded, the fort's strongbox
 * appears as a buried treasure and crews may claim the island. Unclaimed forts are garrisoned again later.
 * The soldiers are not saved with the world: they appear while players are there.
 */
final class TidewatchGarrison implements Listener {

    private final IslandsModule m;
    private final NamespacedKey islandKey, captainKey, powerKey;
    /** Garrison members with a fruit power, and when they may use it next (ms). */
    private final Set<UUID> officers = new HashSet<>();
    private final Map<UUID, Long> powerReady = new HashMap<>();
    /** island id -> liberated until (ms) */
    private final Map<String, Long> liberatedUntil = new HashMap<>();
    private final Map<String, Set<UUID>> soldiers = new HashMap<>();
    private final Map<String, UUID> captains = new HashMap<>();
    private final Map<String, Long> lastWave = new HashMap<>();
    private final Set<String> alerted = new HashSet<>();

    TidewatchGarrison(IslandsModule module) {
        this.m = module;
        this.islandKey = new NamespacedKey(module.plugin(), "tidewatch_island");
        this.captainKey = new NamespacedKey(module.plugin(), "tidewatch_captain");
        this.powerKey = new NamespacedKey(module.plugin(), "tidewatch_power");
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = m.config().getConfigurationSection("tidewatch");
        return c != null ? c : new YamlConfiguration();
    }

    // ------------------------------------------------------------------ state

    void load() {
        liberatedUntil.clear();
        YamlConfiguration data = m.loadData("tidewatch");
        ConfigurationSection s = data.getConfigurationSection("liberated");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                liberatedUntil.put(id, s.getLong(id));
            }
        }
    }

    void save() {
        YamlConfiguration data = new YamlConfiguration();
        long now = System.currentTimeMillis();
        liberatedUntil.forEach((id, until) -> {
            if (until > now) {
                data.set("liberated." + id, until);
            }
        });
        m.saveData("tidewatch", data);
    }

    void shutdown() {
        for (String id : new ArrayList<>(soldiers.keySet())) {
            despawn(id);
        }
        save();
    }

    boolean liberated(IslandImpl is) {
        return liberatedUntil.getOrDefault(is.id, 0L) > System.currentTimeMillis();
    }

    long liberatedSecondsLeft(IslandImpl is) {
        return Math.max(0, (liberatedUntil.getOrDefault(is.id, 0L) - System.currentTimeMillis()) / 1000);
    }

    /** A new or regenerated island: the garrison is back, whatever happened there before. */
    void reset(String islandId) {
        despawn(islandId);
        alerted.remove(islandId);
        lastWave.remove(islandId);
        if (liberatedUntil.remove(islandId) != null) {
            save();
        }
    }

    /** True while the Tidewatch holds the island (it can't be claimed then). */
    boolean holds(IslandImpl is) {
        return is.type == IslandType.TIDEWATCH && is.status != IslandStatus.OCCUPIED && cfg().getBoolean("enabled", true) && !liberated(is);
    }

    // ------------------------------------------------------------------ garrison

    void tick() {
        long now = System.currentTimeMillis();
        for (IslandImpl is : m.islandImpls()) {
            if (is.type != IslandType.TIDEWATCH) {
                continue;
            }
            World w = is.bukkitWorld();
            List<Player> present = new ArrayList<>();
            if (w != null) {
                for (Player p : w.getPlayers()) {
                    // creative players (staff building or testing) see the garrison too; it only hunts the others
                    if (p.getGameMode() != GameMode.SPECTATOR && is.contains(p.getLocation())) {
                        present.add(p);
                    }
                }
            }
            cleanup(is.id);
            if (present.isEmpty() || !holds(is)) {
                if (present.isEmpty()) {
                    despawn(is.id);
                    alerted.remove(is.id);
                }
                continue;
            }
            if (alerted.add(is.id)) {
                for (Player p : present) {
                    p.sendMessage(m.msg("tidewatch-alarm", "island", is.name));
                    p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
                }
            }
            Entity captain = captains.containsKey(is.id) ? Bukkit.getEntity(captains.get(is.id)) : null;
            if (captain == null || !captain.isValid()) {
                LivingEntity c = spawn(is, "captain", new Location(w, is.x + 0.5, keepY(w, is), is.z + 0.5), present);
                if (c != null) {
                    c.getPersistentDataContainer().set(captainKey, PersistentDataType.BYTE, (byte) 1);
                    captains.put(is.id, c.getUniqueId());
                }
            }
            Set<UUID> alive = soldiers.computeIfAbsent(is.id, k -> new HashSet<>());
            int want = Math.min(cfg().getInt("max-soldiers", 12),
                    cfg().getInt("base-soldiers", 4) + cfg().getInt("per-player", 2) * (present.size() - 1));
            long gap = Math.max(5, cfg().getLong("reinforce-seconds", 25)) * 1000L;
            if (alive.size() < want && now - lastWave.getOrDefault(is.id, 0L) >= gap) {
                lastWave.put(is.id, now);
                int spawnNow = Math.min(4, want - alive.size());
                for (int i = 0; i < spawnNow; i++) {
                    LivingEntity s = spawn(is, pickTemplate(), courtyardSpot(w, is), present);
                    if (s != null) {
                        alive.add(s.getUniqueId());
                    }
                }
            }
        }
    }

    private int keepY(World w, IslandImpl is) {
        // the keep's ground floor: the first open spot from the plaza up
        for (int y = w.getSeaLevel(); y < w.getSeaLevel() + 40; y++) {
            if (w.getBlockAt(is.x, y, is.z).getType().isSolid() && w.getBlockAt(is.x, y + 1, is.z).isPassable()
                    && w.getBlockAt(is.x, y + 2, is.z).isPassable()) {
                return y + 1;
            }
        }
        return w.getHighestBlockYAt(is.x, is.z) + 1;
    }

    /**
     * Where the fort's strongbox is buried: in the open yard in front of the keep (every fort keeps it free and open
     * to the sky), never under a roof and never on or next to a crew flag.
     */
    private Location strongboxSpot(World w, IslandImpl is) {
        int floor = keepY(w, is);
        int[][] tries = {{3, 8}, {-3, 8}, {0, 9}, {4, 10}, {-4, 10}, {2, 7}, {-2, 7}, {0, 11}};
        for (int[] t : tries) {
            int x = is.x + t[0], z = is.z + t[1];
            if (is.hasFlag && Math.abs(is.flagX - x) <= 2 && Math.abs(is.flagZ - z) <= 2) {
                continue;
            }
            int sky = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (Math.abs(sky - floor) <= 1) {
                return new Location(w, x, sky, z);       // open ground at the yard's level
            }
        }
        return new Location(w, is.x + 3, floor, is.z + 8);
    }

    /** A spot on open courtyard ground (not on a roof, a wall or a tower) for a soldier to appear. */
    private Location courtyardSpot(World w, IslandImpl is) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int floor = keepY(w, is);
        for (int i = 0; i < 16; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 6 + r.nextDouble() * 4;
            int x = is.x + (int) Math.round(Math.cos(a) * d), z = is.z + (int) Math.round(Math.sin(a) * d);
            int sky = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (Math.abs(sky - floor) <= 1) {
                return new Location(w, x + 0.5, sky, z + 0.5);
            }
        }
        return new Location(w, is.x + 0.5, floor, is.z + 8.5);
    }

    private String pickTemplate() {
        List<String> list = cfg().getStringList("soldiers");
        double total = 0;
        List<String[]> parsed = new ArrayList<>();
        for (String s : list) {
            String[] p = s.split(":");
            parsed.add(p);
            total += p.length > 1 ? Double.parseDouble(p[1]) : 1;
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (String[] p : parsed) {
            roll -= p.length > 1 ? Double.parseDouble(p[1]) : 1;
            if (roll < 0) {
                return p[0];
            }
        }
        return parsed.isEmpty() ? "recruit" : parsed.get(0)[0];
    }

    private void cleanup(String id) {
        Set<UUID> set = soldiers.get(id);
        if (set != null) {
            set.removeIf(u -> {
                Entity e = Bukkit.getEntity(u);
                return e == null || !e.isValid();
            });
        }
    }

    private void despawn(String id) {
        Set<UUID> set = soldiers.remove(id);
        if (set != null) {
            for (UUID u : set) {
                Entity e = Bukkit.getEntity(u);
                if (e != null) {
                    e.remove();
                }
            }
        }
        UUID cap = captains.remove(id);
        Entity c = cap == null ? null : Bukkit.getEntity(cap);
        if (c != null) {
            c.remove();
        }
    }

    private LivingEntity spawn(IslandImpl is, String template, Location at, List<Player> targets) {
        List<Player> hunted = new ArrayList<>(targets);
        hunted.removeIf(p -> p.getGameMode() == GameMode.CREATIVE);
        LivingEntity le = spawnFrom(cfg().getConfigurationSection("mobs." + template), at, hunted);
        if (le != null) {
            le.getPersistentDataContainer().set(islandKey, PersistentDataType.STRING, is.id);
        }
        return le;
    }

    /** Spawns a soldier from a mob template (also used for the crews of ships). */
    LivingEntity spawnFrom(ConfigurationSection mob, Location at, List<Player> targets) {
        if (mob == null || at.getWorld() == null) {
            return null;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(mob.getString("type", "ZOMBIE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(type.getEntityClass()) || !type.isSpawnable()) {
            return null;
        }
        Entity spawned = at.getWorld().spawnEntity(at, type);
        if (!(spawned instanceof LivingEntity le)) {
            spawned.remove();
            return null;
        }
        le.setPersistent(false);
        le.setRemoveWhenFarAway(false);
        le.setCanPickupItems(false);
        if (le instanceof org.bukkit.entity.Zombie zombie) {
            zombie.setShouldBurnInDay(false);
            zombie.setConversionTime(-1);
        }
        if (le instanceof org.bukkit.entity.AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(false);
        }
        // officers with an Abyss Fruit power (picked at random from the template's list)
        List<String> powers = mob.getStringList("powers");
        double chance = mob.getDouble("power-chance", cfg().getDouble("power-chance", 0.2));
        String power = powers.isEmpty() || ThreadLocalRandom.current().nextDouble() >= chance ? null
                : powers.get(ThreadLocalRandom.current().nextInt(powers.size())).toLowerCase(Locale.ROOT);
        if (le instanceof org.bukkit.entity.Ageable ageable) {
            ageable.setAdult();
        }
        if (mob.contains("name")) {
            String suffix = power == null ? "" : " " + cfg().getString("power-names." + power, "");
            le.customName(Text.mm(mob.getString("name") + suffix));
            le.setCustomNameVisible(true);
        }
        if (power != null) {
            le.getPersistentDataContainer().set(powerKey, PersistentDataType.STRING, power);
            officers.add(le.getUniqueId());
        }
        AttributeInstance hp = le.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            double base = mob.contains("health") ? mob.getDouble("health") : hp.getBaseValue() * mob.getDouble("health-multiplier", 1.0);
            hp.setBaseValue(Math.max(1, Math.min(1024, base)));
            le.setHealth(hp.getValue());
        }
        AttributeInstance dmg = le.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) {
            dmg.setBaseValue(dmg.getBaseValue() * mob.getDouble("damage-multiplier", 1.0));
        }
        EntityEquipment eq = le.getEquipment();
        ConfigurationSection gear = mob.getConfigurationSection("equipment");
        if (eq != null && gear != null) {
            eq.setHelmet(item(pick(gear, "helmet")));
            eq.setChestplate(item(pick(gear, "chestplate")));
            eq.setLeggings(item(pick(gear, "leggings")));
            eq.setBoots(item(pick(gear, "boots")));
            ItemStack hand = item(pick(gear, "mainhand"));
            if (hand != null && hand.getType() == Material.CROSSBOW && !(le instanceof org.bukkit.entity.Pillager)
                    && !(le instanceof org.bukkit.entity.Piglin)) {
                hand = item("item:steel_cutlass");         // only pillagers can fire a gun; the rest get a blade
            }
            eq.setItemInMainHand(hand);
            eq.setHelmetDropChance(0);
            eq.setChestplateDropChance(0);
            eq.setLeggingsDropChance(0);
            eq.setBootsDropChance(0);
            eq.setItemInMainHandDropChance(0);
        }
        for (String eff : mob.getStringList("effects")) {
            String[] p = eff.split(":");
            PotionEffectType pt = org.bukkit.Registry.EFFECT.get(NamespacedKey.minecraft(p[0].toLowerCase(Locale.ROOT)));
            if (pt != null) {
                le.addPotionEffect(new PotionEffect(pt, PotionEffect.INFINITE_DURATION, p.length > 1 ? Integer.parseInt(p[1]) - 1 : 0, true, false));
            }
        }
        if (le instanceof Mob mobEntity && !targets.isEmpty()) {
            Player nearest = targets.get(0);
            for (Player p : targets) {
                if (p.getLocation().distanceSquared(at) < nearest.getLocation().distanceSquared(at)) {
                    nearest = p;
                }
            }
            mobEntity.setTarget(nearest);
        }
        return le;
    }

    /** One value, or a random one from a list (so every soldier can look a bit different). */
    private static String pick(ConfigurationSection gear, String key) {
        if (gear.isList(key)) {
            List<String> options = gear.getStringList(key);
            return options.isEmpty() ? null : options.get(ThreadLocalRandom.current().nextInt(options.size()));
        }
        return gear.getString(key);
    }

    /** Headgear looks need the resource pack (items.yml resource-pack.enabled). */
    private boolean packOn() {
        dev.aquatic.core.AquaticModule items = m.plugin().module("items");
        return items != null && items.isEnabled() && items.config().getBoolean("resource-pack.enabled", false);
    }

    /**
     * "MATERIAL", "item:<custom id>" (a custom item, e.g. a Tidewatch uniform piece) or "look:<id>": a 3D hat (and
     * face) from the resource pack, worn on the head (aquatic:item/&lt;id&gt;; nothing without the pack).
     */
    private ItemStack item(String spec) {
        if (spec == null) {
            return null;
        }
        if (spec.toLowerCase(Locale.ROOT).startsWith("look:")) {
            if (!packOn()) {
                return null;
            }
            ItemStack look = new ItemStack(Material.PAPER);
            org.bukkit.inventory.meta.ItemMeta meta = look.getItemMeta();
            meta.setItemModel(NamespacedKey.fromString("aquatic:" + spec.substring(5).toLowerCase(Locale.ROOT)));
            look.setItemMeta(meta);
            return look;
        }
        if (spec.toLowerCase(Locale.ROOT).startsWith("item:")) {
            dev.aquatic.api.ItemService items = m.service(dev.aquatic.api.ItemService.class);
            ItemStack custom = items == null ? null : items.createItem(spec.substring(5), 1);
            if (custom != null) {
                return custom;
            }
            String fallback = cfg().getString("uniform-fallback." + spec.substring(5));
            spec = fallback != null ? fallback : "LEATHER_CHESTPLATE";
        }
        Material mat = Material.matchMaterial(spec);
        return mat == null ? null : new ItemStack(mat);
    }

    // ------------------------------------------------------------------ officer powers

    /** Every second: officers with a fruit power use it on their target (after a short, visible warning). */
    void tickPowers() {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(3, cfg().getLong("power-cooldown-seconds", 9)) * 1000L;
        officers.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Mob mob) || !mob.isValid()) {
                powerReady.remove(id);
                return true;
            }
            LivingEntity target = mob.getTarget();
            if (target == null || !target.isValid() || target.getWorld() != mob.getWorld()
                    || target.getLocation().distanceSquared(mob.getLocation()) > 18 * 18) {
                return false;
            }
            if (now < powerReady.getOrDefault(id, now + 2000L)) {
                powerReady.putIfAbsent(id, now + 2000L);
                return false;
            }
            powerReady.put(id, now + cooldown);
            String power = mob.getPersistentDataContainer().get(powerKey, PersistentDataType.STRING);
            telegraph(mob, target, power);
            m.runLater(() -> {
                if (mob.isValid() && target.isValid() && !mob.isDead()) {
                    usePower(mob, target, power);
                }
            }, 20L);
            return false;
        });
    }

    private double powerDamage(Mob mob) {
        double base = cfg().getDouble("power-damage", 6);
        return mob.getPersistentDataContainer().has(captainKey, PersistentDataType.BYTE) ? base * 1.4 : base;
    }

    /** A warning a second before the power hits, so players can dodge. */
    private void telegraph(Mob mob, LivingEntity target, String power) {
        Location at = "thunder".equals(power) ? target.getLocation() : mob.getLocation();
        Particle p = switch (power == null ? "" : power) {
            case "ember" -> Particle.FLAME;
            case "frost" -> Particle.SNOWFLAKE;
            case "thunder" -> Particle.ELECTRIC_SPARK;
            default -> Particle.CLOUD;
        };
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            at.getWorld().spawnParticle(p, at.clone().add(Math.cos(a) * 2, 0.2, Math.sin(a) * 2), 1, 0, 0, 0, 0);
        }
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1f, 1.2f);
    }

    private void usePower(Mob mob, LivingEntity target, String power) {
        double dmg = powerDamage(mob);
        World w = mob.getWorld();
        switch (power == null ? "" : power) {
            case "ember" -> { // Emberheart: a burst of fireballs
                for (int i = 0; i < 3; i++) {
                    org.bukkit.util.Vector dir = target.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector()).normalize()
                            .add(new org.bukkit.util.Vector((i - 1) * 0.12, 0, (i - 1) * 0.12));
                    org.bukkit.entity.SmallFireball ball = mob.launchProjectile(org.bukkit.entity.SmallFireball.class, dir.multiply(1.2));
                    ball.setIsIncendiary(false);
                }
                w.playSound(mob.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 0.9f);
            }
            case "frost" -> { // Rimefrost: a freezing nova
                w.spawnParticle(Particle.SNOWFLAKE, mob.getLocation().add(0, 1, 0), 120, 2.5, 0.6, 2.5, 0.05);
                w.playSound(mob.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
                for (Player p : w.getPlayers()) {
                    if (p.getLocation().distanceSquared(mob.getLocation()) <= 25 && p.getGameMode() == GameMode.SURVIVAL) {
                        p.damage(dmg, mob);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
                        p.setFreezeTicks(Math.max(p.getFreezeTicks(), 140));
                    }
                }
            }
            case "thunder" -> { // Thunderveil: lightning where the warning was
                Location at = target.getLocation();
                w.strikeLightningEffect(at);
                for (Player p : w.getPlayers()) {
                    if (p.getLocation().distanceSquared(at) <= 6.25 && p.getGameMode() == GameMode.SURVIVAL) {
                        p.damage(dmg * 1.2, mob);
                    }
                }
            }
            default -> { // Galeborn: a gust that throws everyone back
                w.spawnParticle(Particle.CLOUD, mob.getLocation().add(0, 1, 0), 80, 2, 0.5, 2, 0.15);
                w.playSound(mob.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1f);
                for (Player p : w.getPlayers()) {
                    double d2 = p.getLocation().distanceSquared(mob.getLocation());
                    if (d2 <= 36 && p.getGameMode() == GameMode.SURVIVAL) {
                        org.bukkit.util.Vector push = p.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
                        if (push.lengthSquared() < 0.01) {
                            push = new org.bukkit.util.Vector(1, 0, 0);
                        }
                        p.setVelocity(push.normalize().multiply(1.6).setY(0.6));
                        p.damage(dmg * 0.5, mob);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onSun(EntityCombustEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(islandKey, PersistentDataType.STRING)
                && !(event instanceof org.bukkit.event.entity.EntityCombustByEntityEvent)) {
            event.setCancelled(true); // the garrison doesn't burn in daylight
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        String id = dead.getPersistentDataContainer().get(islandKey, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        event.getDrops().clear();
        IslandImpl is = m.islandImpl(id);
        Player killer = dead.getKiller();
        EconomyService eco = m.service(EconomyService.class);
        if (!dead.getPersistentDataContainer().has(captainKey, PersistentDataType.BYTE)) {
            double reward = cfg().getDouble("soldier-reward", 35);
            if (killer != null && eco != null && reward > 0) {
                eco.deposit(killer.getUniqueId(), reward);
                killer.sendActionBar(m.rawMsg("tidewatch-soldier-reward", "amount", eco.format(reward)));
            }
            return;
        }
        // the captain fell: the island is liberated
        captains.remove(id);
        if (is == null) {
            return;
        }
        long hours = Math.max(1, cfg().getLong("liberated-hours", 12));
        liberatedUntil.put(id, System.currentTimeMillis() + hours * 3600_000L);
        Set<UUID> set = soldiers.remove(id);
        if (set != null) {
            for (UUID u : set) {
                Entity e = Bukkit.getEntity(u);
                if (e != null) {
                    e.getWorld().spawnParticle(Particle.CLOUD, e.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
                    e.remove(); // the rest of the garrison retreats
                }
            }
        }
        double reward = cfg().getDouble("captain-reward", 1500);
        long xp = cfg().getLong("captain-xp", 400);
        LevelService levels = m.service(LevelService.class);
        World w = is.bukkitWorld();
        List<Player> heroes = new ArrayList<>();
        if (w != null) {
            for (Player p : w.getPlayers()) {
                if (is.contains(p.getLocation())) {
                    heroes.add(p);
                }
            }
        }
        for (Player p : heroes) {
            if (eco != null && reward > 0) {
                eco.deposit(p.getUniqueId(), reward);
            }
            if (levels != null && xp > 0) {
                levels.addXp(p.getUniqueId(), xp, "Tidewatch outpost");
            }
            p.sendMessage(m.msg("tidewatch-liberated-you", "island", is.name, "amount", eco == null ? Text.number(reward) : eco.format(reward),
                    "hours", hours));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        String hero = killer != null ? killer.getName() : heroes.isEmpty() ? null : heroes.get(0).getName();
        Bukkit.broadcast(m.msg("tidewatch-liberated", "island", is.name, "player", hero == null ? Text.plain(m.rawMsg("tidewatch-someone")) : hero));
        TreasureService treasure = m.service(TreasureService.class);
        if (treasure != null && w != null) {
            Location box = treasure.spawnTreasure(strongboxSpot(w, is), cfg().getString("strongbox-tier", "rare"));
            if (box != null) {
                for (Player p : heroes) {
                    p.sendMessage(m.msg("tidewatch-strongbox"));
                }
            }
        }
        save();
    }
}
