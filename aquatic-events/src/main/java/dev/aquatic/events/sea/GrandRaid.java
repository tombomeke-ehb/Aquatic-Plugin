package dev.aquatic.events.sea;

import dev.aquatic.api.EconomyService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.events.EventsModule;
import dev.aquatic.events.Util;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Server-wide PvE raid: waves of buffed Tidewatch soldiers, a High Warden boss in the last wave, rewards by damage dealt. */
public final class GrandRaid extends SeaEvent {

    private Location center;
    private String placeName;
    private List<List<String>> waves;
    private int wave = -1;
    private final Set<UUID> mobs = new HashSet<>();
    private UUID boss;
    private String bossName;
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private long nextWaveAt;
    private boolean victory;

    public GrandRaid(EventsModule module) {
        super(module, "grand_raid");
    }

    public static NamespacedKey tag(EventsModule module) {
        return new NamespacedKey(module.plugin(), "raid_mob");
    }

    @Override
    protected void begin(CommandSender sender, String[] args) {
        if (args.length > 0) {
            IslandService islands = module.service(IslandService.class);
            Island island = islands == null ? null : islands.islandById(args[0].toLowerCase(Locale.ROOT));
            if (island != null) {
                center = island.center().clone();
                placeName = island.name();
            } else {
                center = module.location(type, args[0].toLowerCase(Locale.ROOT));
                placeName = args[0];
            }
            if (center == null) {
                throw fail("raid-unknown-place", "place", args[0]);
            }
        } else {
            center = module.location(type, "default");
            placeName = Util.coords(center == null ? new Location(null, 0, 0, 0) : center);
        }
        if (center == null || center.getWorld() == null) {
            throw fail("raid-no-location");
        }
        waves = new ArrayList<>();
        for (Object o : cfg.getList("waves", List.of())) {
            if (o instanceof List<?> l) {
                List<String> w = new ArrayList<>();
                for (Object s : l) {
                    w.add(String.valueOf(s));
                }
                waves.add(w);
            }
        }
        if (waves.isEmpty()) {
            throw fail("raid-no-waves");
        }
        nextWaveAt = System.currentTimeMillis() + Math.max(0, cfg.getLong("first-wave-delay-seconds", 30)) * 1000;
        announce("raid-start", "place", placeName, "x", center.getBlockX(), "y", center.getBlockY(), "z", center.getBlockZ(),
                "waves", waves.size(), "seconds", (nextWaveAt - System.currentTimeMillis()) / 1000);
    }

    @Override
    protected void onTick() {
        mobs.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || e.isDead() || !e.isValid();
        });
        if (!mobs.isEmpty()) {
            if (cfg.getBoolean("leash", true)) {
                double max = cfg.getDouble("leash-radius", 60);
                for (UUID id : mobs) {
                    Entity e = Bukkit.getEntity(id);
                    if (e != null && (!e.getWorld().equals(center.getWorld()) || e.getLocation().distanceSquared(center) > max * max)) {
                        e.teleport(spawnPoint());
                    }
                }
            }
            return;
        }
        if (wave >= 0 && nextWaveAt == 0) {
            if (wave + 1 >= waves.size()) {
                victory = true;
                finish(false);
                return;
            }
            announce("raid-wave-cleared", "wave", wave + 1, "waves", waves.size());
            nextWaveAt = System.currentTimeMillis() + Math.max(0, cfg.getLong("wave-delay-seconds", 15)) * 1000;
        }
        if (nextWaveAt > 0 && System.currentTimeMillis() >= nextWaveAt) {
            nextWaveAt = 0;
            spawnWave(++wave);
        }
    }

    private Location spawnPoint() {
        double r = Math.max(2, cfg.getDouble("spawn-radius", 12));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World w = center.getWorld();
        for (int tries = 0; tries < 10; tries++) {
            double a = rnd.nextDouble(Math.PI * 2), d = rnd.nextDouble(r);
            int x = (int) Math.floor(center.getX() + Math.cos(a) * d);
            int z = (int) Math.floor(center.getZ() + Math.sin(a) * d);
            int y = w.getHighestBlockYAt(x, z);
            if (Math.abs(y - center.getY()) <= 12 && !w.getBlockAt(x, y, z).isLiquid()) {
                return new Location(w, x + 0.5, y + 1, z + 0.5);
            }
        }
        return center.clone().add(0, 1, 0);
    }

    private void spawnWave(int index) {
        List<String> specs = waves.get(index);
        boolean last = index == waves.size() - 1;
        double scale = 1.0 + Math.max(0, cfg.getDouble("scale-per-player", 0.1)) * Math.max(0, Bukkit.getOnlinePlayers().size() - 1);
        scale = Math.min(scale, Math.max(1, cfg.getDouble("max-scale", 3)));
        for (String spec : specs) {
            String[] p = spec.split(":");
            ConfigurationSection mob = cfg.getConfigurationSection("mobs." + p[0]);
            if (mob == null) {
                module.logger().warning("[events] grand_raid: unknown mob template '" + p[0] + "'");
                continue;
            }
            int count = 1;
            try {
                count = p.length > 1 ? Integer.parseInt(p[1]) : 1;
            } catch (NumberFormatException ignored) {
            }
            boolean isBoss = mob.getBoolean("boss", false);
            int total = isBoss ? count : (int) Math.ceil(count * scale);
            for (int i = 0; i < total; i++) {
                LivingEntity e = spawn(mob, isBoss ? scale : 1.0);
                if (e != null && isBoss && boss == null) {
                    boss = e.getUniqueId();
                    bossName = Text.plain(Text.mm(mob.getString("name", "High Warden")));
                }
            }
        }
        if (last && boss != null) {
            announce("raid-boss", "boss", bossName);
            Util.soundAll("entity.wither.spawn", 0.7f, 0.8f);
        } else {
            announce("raid-wave", "wave", index + 1, "waves", waves.size(), "count", mobs.size());
        }
    }

    private LivingEntity spawn(ConfigurationSection mob, double healthScale) {
        EntityType type;
        try {
            type = EntityType.valueOf(mob.getString("type", "ZOMBIE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            module.logger().warning("[events] grand_raid: invalid entity type in " + mob.getCurrentPath());
            return null;
        }
        if (type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(type.getEntityClass()) || !type.isSpawnable()) {
            return null;
        }
        Location at = spawnPoint();
        Entity spawned = at.getWorld().spawnEntity(at, type);
        if (!(spawned instanceof LivingEntity le)) {
            spawned.remove();
            return null;
        }
        le.getPersistentDataContainer().set(tag(module), PersistentDataType.BYTE, (byte) 1);
        le.setPersistent(false);
        le.setRemoveWhenFarAway(false);
        if (mob.contains("name")) {
            le.customName(Text.mm(mob.getString("name")));
            le.setCustomNameVisible(true);
        }
        AttributeInstance hp = le.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            double base = mob.contains("health") ? mob.getDouble("health") : hp.getBaseValue() * mob.getDouble("health-multiplier", 1.0);
            hp.setBaseValue(Math.max(1, Math.min(1024, base * healthScale)));
            le.setHealth(hp.getValue());
        }
        AttributeInstance dmg = le.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) {
            dmg.setBaseValue(dmg.getBaseValue() * mob.getDouble("damage-multiplier", 1.0));
        }
        AttributeInstance speed = le.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() * mob.getDouble("speed-multiplier", 1.0));
        }
        EntityEquipment eq = le.getEquipment();
        ConfigurationSection gear = mob.getConfigurationSection("equipment");
        if (eq != null && gear != null) {
            eq.setHelmet(item(gear.getString("helmet")));
            eq.setChestplate(item(gear.getString("chestplate")));
            eq.setLeggings(item(gear.getString("leggings")));
            eq.setBoots(item(gear.getString("boots")));
            eq.setItemInMainHand(item(gear.getString("mainhand")));
            eq.setItemInOffHand(item(gear.getString("offhand")));
            float chance = (float) mob.getDouble("equipment-drop-chance", 0);
            eq.setHelmetDropChance(chance);
            eq.setChestplateDropChance(chance);
            eq.setLeggingsDropChance(chance);
            eq.setBootsDropChance(chance);
            eq.setItemInMainHandDropChance(chance);
            eq.setItemInOffHandDropChance(chance);
        }
        for (PotionEffect e : Util.parseEffects(mob.getStringList("effects"), Integer.MAX_VALUE)) {
            le.addPotionEffect(e);
        }
        if (le instanceof Mob m) {
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (Player p : at.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(at);
                if (d < best && d < 64 * 64 && !p.getGameMode().name().equals("SPECTATOR") && !p.getGameMode().name().equals("CREATIVE")) {
                    best = d;
                    nearest = p;
                }
            }
            if (nearest != null) {
                m.setTarget(nearest);
            }
        }
        mobs.add(le.getUniqueId());
        return le;
    }

    private ItemStack item(String spec) {
        ItemStack s = Util.parseItem(module, spec);
        return s == null ? new ItemStack(org.bukkit.Material.AIR) : s;
    }

    @Override
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!mobs.contains(event.getEntity().getUniqueId())) {
            return;
        }
        Player p = attacker(event.getDamager());
        if (p == null || !(event.getEntity() instanceof LivingEntity le)) {
            return;
        }
        double dealt = Math.min(le.getHealth(), event.getFinalDamage());
        damage.merge(p.getUniqueId(), dealt, Double::sum);
        participants.add(p.getUniqueId());
    }

    @Override
    public void onEntityDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (!mobs.remove(id)) {
            return;
        }
        if (!cfg.getBoolean("drop-loot", false)) {
            event.getDrops().clear();
        }
        event.setDroppedExp(cfg.getInt("exp-per-kill", 10));
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
        }
        if (id.equals(boss)) {
            announce("raid-boss-down", "boss", bossName, "player", killer == null ? "?" : killer.getName());
            boss = null;
        }
    }

    @Override
    public void onCombust(EntityCombustEvent event) {
        if (!(event instanceof EntityCombustByEntityEvent) && mobs.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @Override
    protected void updateBar() {
        if (bar() == null) {
            return;
        }
        Entity b = boss == null ? null : Bukkit.getEntity(boss);
        if (b instanceof LivingEntity le && !le.isDead()) {
            AttributeInstance max = le.getAttribute(Attribute.MAX_HEALTH);
            double m = max == null ? 20 : max.getValue();
            bar().name(module.rawMsg("raid-boss-bar", "boss", bossName, "health", (long) Math.ceil(le.getHealth()), "max", (long) m,
                    "time", Text.duration(secondsLeft())));
            bar().progress((float) Math.max(0, Math.min(1, le.getHealth() / m)));
            return;
        }
        super.updateBar();
    }

    @Override
    protected void conclude(boolean timeUp) {
        if (!victory) {
            announce("raid-failed", "wave", Math.max(1, wave + 1), "waves", waves == null ? 0 : waves.size());
            return;
        }
        double totalDamage = damage.values().stream().mapToDouble(Double::doubleValue).sum();
        double pool = Math.max(0, cfg.getDouble("rewards.pool-doubloons", 0));
        EconomyService eco = module.service(EconomyService.class);
        UUID mvp = null;
        double best = -1;
        for (Map.Entry<UUID, Double> e : damage.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                mvp = e.getKey();
            }
        }
        announce("raid-victory", "players", damage.size());
        for (Map.Entry<UUID, Double> e : damage.entrySet()) {
            double share = totalDamage <= 0 ? 0 : e.getValue() / totalDamage;
            if (pool > 0 && eco != null) {
                eco.deposit(e.getKey(), pool * share);
            }
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                module.send(p, "raid-share", "percent", Math.round(share * 100), "amount", moneyText(pool * share),
                        "kills", kills.getOrDefault(e.getKey(), 0));
                rewardPlayer(p, "participant", 1.0);
            }
        }
        if (mvp != null) {
            Player p = Bukkit.getPlayer(mvp);
            String name = p != null ? p.getName() : Bukkit.getOfflinePlayer(mvp).getName();
            announce("raid-mvp", "player", name == null ? "?" : name, "damage", (long) best);
            if (p != null) {
                rewardPlayer(p, "mvp", 1.0);
            }
        }
    }

    @Override
    protected void cleanup() {
        for (UUID id : mobs) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        mobs.clear();
    }

    @Override
    protected Location joinLocation() {
        return center;
    }

    @Override
    protected Component status() {
        if (wave < 0) {
            return module.rawMsg("raid-status-waiting", "seconds", Math.max(0, (nextWaveAt - System.currentTimeMillis()) / 1000));
        }
        return module.rawMsg("raid-status", "wave", wave + 1, "waves", waves.size(), "left", mobs.size());
    }
}
