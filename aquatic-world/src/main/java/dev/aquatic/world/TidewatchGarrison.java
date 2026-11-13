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
    private final NamespacedKey islandKey, captainKey;
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
                    if (p.getGameMode() != GameMode.SPECTATOR && p.getGameMode() != GameMode.CREATIVE && is.contains(p.getLocation())) {
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

    private Location courtyardSpot(World w, IslandImpl is) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double a = r.nextDouble() * Math.PI * 2, d = 6 + r.nextDouble() * 4;
        int x = is.x + (int) Math.round(Math.cos(a) * d), z = is.z + (int) Math.round(Math.sin(a) * d);
        return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5);
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
        ConfigurationSection mob = cfg().getConfigurationSection("mobs." + template);
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
        le.getPersistentDataContainer().set(islandKey, PersistentDataType.STRING, is.id);
        le.setPersistent(false);
        le.setRemoveWhenFarAway(false);
        if (le instanceof org.bukkit.entity.Ageable ageable) {
            ageable.setAdult();
        }
        if (mob.contains("name")) {
            le.customName(Text.mm(mob.getString("name")));
            le.setCustomNameVisible(true);
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
            eq.setHelmet(item(gear.getString("helmet")));
            eq.setChestplate(item(gear.getString("chestplate")));
            eq.setLeggings(item(gear.getString("leggings")));
            eq.setBoots(item(gear.getString("boots")));
            eq.setItemInMainHand(item(gear.getString("mainhand")));
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

    private static ItemStack item(String spec) {
        if (spec == null) {
            return null;
        }
        Material m = Material.matchMaterial(spec);
        return m == null ? null : new ItemStack(m);
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
            Location box = treasure.spawnTreasure(new Location(w, is.x, keepY(w, is), is.z), cfg().getString("strongbox-tier", "rare"));
            if (box != null) {
                for (Player p : heroes) {
                    p.sendMessage(m.msg("tidewatch-strongbox"));
                }
            }
        }
        save();
    }
}
