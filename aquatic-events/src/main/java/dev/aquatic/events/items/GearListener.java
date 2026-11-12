package dev.aquatic.events.items;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.events.ItemsModule;
import dev.aquatic.events.Util;
import dev.aquatic.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.Keyed;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Behaviour of non-fruit items: swords, sea prism stone, guns, cannons, log pose, den den mushi, drinks. */
public final class GearListener implements Listener {

    private record Tracked(UUID shooter, String kind, ConfigurationSection cfg, long expires) {
    }

    private final ItemsModule module;
    private final Abilities abilities;
    private final Map<UUID, Tracked> projectiles = new HashMap<>();
    /** Log pose: player -> selected island id. */
    private final Map<UUID, String> logPoseTarget = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public GearListener(ItemsModule module) {
        this.module = module;
        this.abilities = new Abilities(module, this);
        module.runTimer(this::fastTick, 1L, 1L);
    }

    public Abilities abilities() {
        return abilities;
    }

    private void fastTick() {
        abilities.fastTick();
        drawTrails();
        if (module.fruits() != null) {
            module.fruits().fastTick();
        }
    }

    /** Particle trail behind every tracked projectile (fireballs, bullets, cannonballs). */
    private void drawTrails() {
        if (projectiles.isEmpty() || abilities.fx().quality() <= 0) {
            return;
        }
        for (Map.Entry<UUID, Tracked> e : projectiles.entrySet()) {
            Entity ent = Bukkit.getEntity(e.getKey());
            if (ent == null || !ent.isValid()) {
                continue;
            }
            Location at = ent.getLocation();
            switch (e.getValue().kind()) {
                case "blast" -> {
                    abilities.fx().burst(at, org.bukkit.Particle.FLAME, 4, 0.12, 0.01);
                    abilities.fx().burst(at, org.bukkit.Particle.SMOKE, 2, 0.1, 0.01);
                }
                case "bomb" -> abilities.fx().burst(at, org.bukkit.Particle.SMOKE, 2, 0.05, 0.01);
                case "cannon" -> {
                    abilities.fx().burst(at, org.bukkit.Particle.LARGE_SMOKE, 2, 0.1, 0.01);
                    abilities.fx().burst(at, org.bukkit.Particle.FLAME, 1, 0.05, 0.01);
                }
                default -> abilities.fx().dust(at, org.bukkit.Color.fromRGB(0x777777), 0.8f, 1, 0.02);
            }
        }
    }

    /** Throws a bomb item (type: bomb): it bursts where it lands, see bombHit. */
    private void throwBomb(Player p, ItemDef def, ItemStack hand) {
        ConfigurationSection b = def.cfg.getConfigurationSection("bomb");
        if (b == null || hand == null) {
            return;
        }
        String key = "item:" + def.id;
        long left = module.fruits().cooldownLeft(p.getUniqueId(), key);
        if (left > 0) {
            module.actionBar(p, "ability-cooldown", "ability", def.displayName(), "seconds", left);
            return;
        }
        module.fruits().setCooldown(p.getUniqueId(), key, b.getDouble("cooldown", 2));
        org.bukkit.entity.Snowball ball = p.launchProjectile(org.bukkit.entity.Snowball.class,
                p.getEyeLocation().getDirection().multiply(b.getDouble("speed", 1.3)));
        ball.setItem(hand.asOne());
        track(ball, p, "bomb", b);
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
        }
        Util.sound(p.getLocation(), b.getString("throw-sound", "entity.snowball.throw"), 1f, 0.7f);
    }

    /** Bomb impact: area damage/effects (PvP rules apply), a big burst, and an optional lingering cloud. */
    private void bombHit(Player shooter, Location at, ConfigurationSection b) {
        double radius = b.getDouble("radius", 4);
        abilities.area(shooter, at, radius, b.getDouble("damage", 0), b);
        String particle = b.getString("cloud", "CAMPFIRE_COSY_SMOKE");
        try {
            org.bukkit.Particle cloud = org.bukkit.Particle.valueOf(particle.toUpperCase(java.util.Locale.ROOT));
            abilities.fx().burst(at.clone().add(0, 0.5, 0), cloud, (int) (radius * 30), radius / 2, 0.02);
        } catch (IllegalArgumentException ignored) {
        }
        abilities.fx().wave(at.clone().add(0, 0.2, 0), radius, 6, Fx.color(b.getString("color"), org.bukkit.Color.GRAY), null, false);
        Util.sound(at, b.getString("hit-sound", "entity.generic.explode"), 0.8f, 1.4f);
        int linger = Math.max(0, b.getInt("linger-seconds", 0));
        for (int i = 1; i <= linger; i++) {
            module.runLater(() -> {
                if (!shooter.isOnline()) {
                    return;
                }
                abilities.area(shooter, at, radius, 0, b); // effects only
                try {
                    org.bukkit.Particle cloud = org.bukkit.Particle.valueOf(particle.toUpperCase(java.util.Locale.ROOT));
                    abilities.fx().burst(at.clone().add(0, 0.6, 0), cloud, (int) (radius * 12), radius / 2, 0.01);
                } catch (IllegalArgumentException ignored) {
                }
            }, 20L * i);
        }
    }

    void track(Projectile projectile, Player shooter, String kind, ConfigurationSection cfg) {
        projectiles.put(projectile.getUniqueId(), new Tracked(shooter.getUniqueId(), kind, cfg, System.currentTimeMillis() + 15_000));
    }

    /** Light periodic task: log pose action bar and projectile bookkeeping. */
    public void tick() {
        long now = System.currentTimeMillis();
        projectiles.entrySet().removeIf(e -> {
            if (e.getValue().expires() >= now) {
                return false;
            }
            Entity ent = Bukkit.getEntity(e.getKey());
            if (ent != null) {
                ent.remove();
            }
            return true;
        });
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemDef main = module.defOf(p.getInventory().getItemInMainHand());
            ItemDef off = module.defOf(p.getInventory().getItemInOffHand());
            if (main != null && main.type.equals("logpose")) {
                showLogPose(p, main, p.getInventory().getItemInMainHand());
            } else if (off != null && off.type.equals("logpose")) {
                showLogPose(p, off, p.getInventory().getItemInOffHand());
            }
        }
    }

    public void shutdown() {
        for (UUID id : projectiles.keySet()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        projectiles.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            abilities.endInvisibility(p);
        }
    }

    // ------------------------------------------------------------------ damage bookkeeping

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbilityDamageResult(EntityDamageByEntityEvent event) {
        if (module.abilityDamage) {
            module.abilityDamageCancelled = event.isCancelled();
        }
    }

    /** Cannon explosions that break blocks must not hurt entities directly (damage is applied with PvP rules). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onExplosionDamage(EntityDamageEvent event) {
        if (module.silentExplosion && (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ melee: swords & sea prism stone

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (module.abilityDamage || !(event.getDamager() instanceof Player p) || !(event.getEntity() instanceof LivingEntity victim)
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        ItemDef def = module.defOf(p.getInventory().getItemInMainHand());
        if (def == null) {
            return;
        }
        if (def.type.equals("seastone") && victim instanceof Player target && module.fruits().fruitOf(target.getUniqueId()) != null) {
            double seconds = def.cfg.getDouble("seastone.hit-seconds", 6);
            module.fruits().seastone(target, seconds);
            module.actionBar(target, "seastoned", "seconds", Text.number(seconds));
            module.actionBar(p, "seastone-hit", "player", target.getName(), "seconds", Text.number(seconds));
            Util.sound(target.getLocation(), "block.beacon.deactivate", 1f, 1.6f);
        }
        ConfigurationSection onHit = def.cfg.getConfigurationSection("on-hit");
        if (onHit == null || p.getAttackCooldown() < 0.9f || !abilities.canHurt(p, victim)) {
            return;
        }
        String cdKey = "onhit:" + def.id;
        if (module.fruits().cooldownLeft(p.getUniqueId(), cdKey) > 0) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= onHit.getDouble("chance", 1.0)) {
            return;
        }
        module.fruits().setCooldown(p.getUniqueId(), cdKey, onHit.getDouble("cooldown", 0));
        event.setDamage(event.getDamage() + onHit.getDouble("extra-damage", 0));
        int ticks = (int) (onHit.getDouble("effect-seconds", 4) * 20);
        for (PotionEffect e : Util.parseEffects(onHit.getStringList("effects"), ticks)) {
            victim.addPotionEffect(e);
        }
        for (PotionEffect e : Util.parseEffects(onHit.getStringList("self-effects"), ticks)) {
            p.addPotionEffect(e);
        }
        int fire = onHit.getInt("fire-ticks", 0);
        if (fire > 0) {
            victim.setFireTicks(Math.max(victim.getFireTicks(), fire));
        }
        double lifesteal = onHit.getDouble("lifesteal", 0);
        if (lifesteal > 0) {
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            double cap = max == null ? 20 : max.getValue();
            p.setHealth(Math.min(cap, p.getHealth() + event.getFinalDamage() * lifesteal));
        }
        if (onHit.getBoolean("lightning", false)) {
            victim.getWorld().strikeLightningEffect(victim.getLocation());
        }
        abilities.particles(onHit, "CRIT", victim.getLocation().add(0, 1, 0), 15, 0.4);
        String sound = onHit.getString("sound");
        if (sound != null) {
            Util.sound(victim.getLocation(), sound, 1f, 1f);
        }
    }

    /** Sea prism handcuffs: right-click a devil fruit user to lock their power away. */
    @EventHandler(ignoreCancelled = true)
    public void onCuff(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) {
            return;
        }
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemDef def = module.defOf(hand);
        if (def == null || !def.type.equals("seastone")) {
            return;
        }
        double seconds = def.cfg.getDouble("seastone.cuff-seconds", 0);
        if (seconds <= 0) {
            return;
        }
        event.setCancelled(true);
        if (!abilities.canHurt(p, target)) {
            return;
        }
        if (module.fruits().cooldownLeft(p.getUniqueId(), "cuff") > 0) {
            module.actionBar(p, "item-cooldown", "item", def.displayName(), "seconds", module.fruits().cooldownLeft(p.getUniqueId(), "cuff"));
            return;
        }
        module.fruits().setCooldown(p.getUniqueId(), "cuff", def.cfg.getDouble("seastone.cuff-cooldown", 30));
        module.fruits().seastone(target, seconds);
        int ticks = (int) (seconds * 20);
        for (PotionEffect e : Util.parseEffects(def.cfg.getStringList("seastone.cuff-effects"), ticks)) {
            target.addPotionEffect(e);
        }
        module.send(target, "cuffed", "player", p.getName(), "seconds", Text.number(seconds));
        module.send(p, "cuffed-other", "player", target.getName(), "seconds", Text.number(seconds));
        Util.sound(target.getLocation(), "block.chain.place", 1f, 0.8f);
        if (def.cfg.getBoolean("seastone.consume-cuffs", true) && p.getGameMode() != GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
        }
    }

    // ------------------------------------------------------------------ right-click items

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemDef def = module.defOf(hand);
        if (def == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        switch (def.type) {
            case "sword" -> {
                ConfigurationSection a = def.ability();
                if (a == null) {
                    return;
                }
                boolean needSneak = "sneak-right-click".equalsIgnoreCase(a.getString("trigger", "right-click"));
                if (needSneak != p.isSneaking() && needSneak) {
                    return;
                }
                if (last != null && now - last < 200) {
                    return;
                }
                lastUse.put(p.getUniqueId(), now);
                String key = "item:" + def.id;
                Object name = Text.mm(a.getString("name", "Ability"));
                long left = module.fruits().cooldownLeft(p.getUniqueId(), key);
                if (left > 0) {
                    module.actionBar(p, "ability-cooldown", "ability", name, "seconds", left);
                    return;
                }
                if (abilities.execute(p, a)) {
                    module.fruits().setCooldown(p.getUniqueId(), key, a.getDouble("cooldown", 15));
                    module.actionBar(p, "ability-used", "ability", name);
                } else {
                    module.actionBar(p, "ability-no-target", "ability", name);
                }
            }
            case "bomb" -> {
                event.setCancelled(true);
                if (last != null && now - last < 400) {
                    return;
                }
                lastUse.put(p.getUniqueId(), now);
                throwBomb(p, def, event.getItem());
            }
            case "gun", "cannon" -> {
                event.setCancelled(true);
                if (last != null && now - last < 150) {
                    return;
                }
                lastUse.put(p.getUniqueId(), now);
                fire(p, def);
            }
            case "logpose" -> {
                event.setCancelled(true);
                if (last != null && now - last < 250) {
                    return;
                }
                lastUse.put(p.getUniqueId(), now);
                cycleLogPose(p, def, hand, p.isSneaking());
            }
            case "denden" -> {
                event.setCancelled(true);
                if (last != null && now - last < 250) {
                    return;
                }
                lastUse.put(p.getUniqueId(), now);
                denDen(p, def);
            }
            default -> {
            }
        }
    }

    private void fire(Player p, ItemDef def) {
        ConfigurationSection g = def.cfg.getConfigurationSection(def.type);
        if (g == null) {
            return;
        }
        String key = "item:" + def.id;
        long left = module.fruits().cooldownLeft(p.getUniqueId(), key);
        if (left > 0) {
            module.actionBar(p, "item-cooldown", "item", def.displayName(), "seconds", left);
            return;
        }
        String ammo = g.getString("ammo", "");
        int perShot = Math.max(0, g.getInt("ammo-per-shot", 1));
        if (p.getGameMode() != GameMode.CREATIVE && !ammo.isEmpty() && perShot > 0) {
            if (!takeAmmo(p.getInventory(), ammo, perShot)) {
                module.actionBar(p, "no-ammo", "ammo", ammoName(ammo), "amount", perShot);
                Util.sound(p, "block.dispenser.fail", 1f, 1.2f);
                return;
            }
        }
        module.fruits().setCooldown(p.getUniqueId(), key, g.getDouble("cooldown", 1.5));
        Vector dir = p.getEyeLocation().getDirection();
        double spread = g.getDouble("spread", 0);
        if (spread > 0) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            dir.add(new Vector(r.nextGaussian() * spread, r.nextGaussian() * spread, r.nextGaussian() * spread)).normalize();
        }
        Snowball ball = p.launchProjectile(Snowball.class, dir.multiply(g.getDouble("speed", 3.5)));
        Material look = Material.matchMaterial(g.getString("projectile-item", def.type.equals("gun") ? "IRON_NUGGET" : "COAL_BLOCK"));
        if (look != null && look.isItem()) {
            ball.setItem(new ItemStack(look));
        }
        ball.setGravity(g.getBoolean("gravity", !def.type.equals("gun")));
        track(ball, p, def.type, g);
        Util.sound(p.getLocation(), g.getString("sound", def.type.equals("gun") ? "entity.firework_rocket.blast" : "entity.generic.explode"),
                1f, (float) g.getDouble("sound-pitch", def.type.equals("gun") ? 0.6 : 1.4));
        abilities.particles(g, "SMOKE", p.getEyeLocation().add(p.getEyeLocation().getDirection()), 8, 0.1);
        double recoil = g.getDouble("recoil", 0);
        if (recoil > 0) {
            p.setVelocity(p.getVelocity().add(p.getEyeLocation().getDirection().multiply(-recoil)));
        }
    }

    private String ammoName(String spec) {
        if (spec.toLowerCase(Locale.ROOT).startsWith("item:")) {
            ItemDef d = module.def(spec.substring(5));
            return d == null ? spec.substring(5) : Text.plain(d.displayName());
        }
        return spec.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private boolean matchesAmmo(ItemStack stack, String spec) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (spec.toLowerCase(Locale.ROOT).startsWith("item:")) {
            return spec.substring(5).equalsIgnoreCase(module.idOf(stack));
        }
        Material m = Material.matchMaterial(spec);
        return m != null && stack.getType() == m && module.idOf(stack) == null;
    }

    private boolean takeAmmo(PlayerInventory inv, String spec, int amount) {
        int have = 0;
        ItemStack[] contents = inv.getStorageContents();
        for (ItemStack s : contents) {
            if (matchesAmmo(s, spec)) {
                have += s.getAmount();
            }
        }
        ItemStack off = inv.getItemInOffHand();
        if (matchesAmmo(off, spec)) {
            have += off.getAmount();
        }
        if (have < amount) {
            return false;
        }
        int left = amount;
        if (matchesAmmo(off, spec)) {
            int n = Math.min(left, off.getAmount());
            off.setAmount(off.getAmount() - n);
            left -= n;
        }
        for (ItemStack s : contents) {
            if (left <= 0) {
                break;
            }
            if (matchesAmmo(s, spec)) {
                int n = Math.min(left, s.getAmount());
                s.setAmount(s.getAmount() - n);
                left -= n;
            }
        }
        return true;
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile proj = event.getEntity();
        Tracked t = projectiles.remove(proj.getUniqueId());
        if (t == null) {
            return;
        }
        event.setCancelled(true);
        proj.remove();
        Player shooter = Bukkit.getPlayer(t.shooter());
        if (shooter == null) {
            return;
        }
        Location at = proj.getLocation();
        switch (t.kind()) {
            case "blast" -> abilities.blast(shooter, at, t.cfg());
            case "bomb" -> bombHit(shooter, at, t.cfg());
            case "gun" -> {
                if (event.getHitEntity() instanceof LivingEntity le) {
                    boolean head = event.getHitEntity() instanceof Player
                            && at.getY() > le.getLocation().getY() + le.getHeight() * 0.8;
                    double dmg = t.cfg().getDouble("damage", 8) * (head ? t.cfg().getDouble("headshot-multiplier", 1.5) : 1.0);
                    if (abilities.hurt(shooter, le, dmg) && head) {
                        module.actionBar(shooter, "headshot");
                    }
                } else if (event.getHitBlock() != null) {
                    abilities.particles(t.cfg(), "SMOKE", at, 5, 0.05);
                }
            }
            case "cannon" -> {
                ConfigurationSection c = t.cfg();
                float power = (float) c.getDouble("block-damage-power", 0);
                if (power > 0 && at.getWorld() != null) {
                    module.silentExplosion = true;
                    try {
                        at.getWorld().createExplosion(at, power, c.getBoolean("fire", false), true, shooter);
                    } finally {
                        module.silentExplosion = false;
                    }
                } else if (at.getWorld() != null) {
                    at.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION_EMITTER, at, 1);
                    Util.sound(at, "entity.generic.explode", 2f, 0.9f);
                }
                abilities.area(shooter, at, c.getDouble("radius", 4), c.getDouble("damage", 10), c);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ log pose

    private List<Island> sortedIslands(Player p, IslandService islands) {
        List<Island> list = new ArrayList<>();
        Location loc = p.getLocation();
        for (Island i : islands.islands()) {
            if (i.center() != null && i.center().getWorld() != null && i.center().getWorld().equals(loc.getWorld())) {
                list.add(i);
            }
        }
        list.sort(Comparator.comparingDouble(i -> Util.flatDistance(i.center(), loc)));
        return list;
    }

    private void cycleLogPose(Player p, ItemDef def, ItemStack stack, boolean nearest) {
        IslandService islands = module.service(IslandService.class);
        if (islands == null) {
            module.actionBar(p, "logpose-unavailable");
            return;
        }
        List<Island> list = sortedIslands(p, islands);
        if (list.isEmpty()) {
            module.actionBar(p, "logpose-no-islands");
            return;
        }
        Island here = islands.islandAt(p.getLocation());
        Island next;
        String current = logPoseTarget.get(p.getUniqueId());
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(current)) {
                idx = i;
            }
        }
        if (nearest || idx < 0) {
            next = list.get(0);
            if (here != null && next.id().equals(here.id()) && list.size() > 1) {
                next = list.get(1);
            }
        } else {
            next = list.get((idx + 1) % list.size());
        }
        logPoseTarget.put(p.getUniqueId(), next.id());
        point(stack, next.center());
        Util.sound(p, "item.lodestone_compass.lock", 1f, 1.2f);
        showLogPose(p, def, stack);
    }

    private void point(ItemStack stack, Location target) {
        if (stack.getItemMeta() instanceof CompassMeta meta) {
            meta.setLodestone(target.clone());
            meta.setLodestoneTracked(false);
            stack.setItemMeta(meta);
        }
    }

    private void showLogPose(Player p, ItemDef def, ItemStack stack) {
        IslandService islands = module.service(IslandService.class);
        if (islands == null) {
            module.actionBar(p, "logpose-unavailable");
            return;
        }
        String id = logPoseTarget.get(p.getUniqueId());
        Island target = id == null ? null : islands.islandById(id);
        if (target == null || target.center() == null || target.center().getWorld() == null
                || !target.center().getWorld().equals(p.getWorld())) {
            List<Island> list = sortedIslands(p, islands);
            if (list.isEmpty()) {
                module.actionBar(p, "logpose-no-islands");
                return;
            }
            target = list.get(0);
            logPoseTarget.put(p.getUniqueId(), target.id());
            point(stack, target.center());
        }
        Island here = islands.islandAt(p.getLocation());
        if (here != null && here.id().equals(target.id())) {
            module.actionBar(p, "logpose-arrived", "island", target.name());
            return;
        }
        module.actionBar(p, "logpose-bar", "island", target.name(), "type", target.type().name().toLowerCase(Locale.ROOT),
                "distance", (long) Util.flatDistance(target.center(), p.getLocation()));
    }

    // ------------------------------------------------------------------ den den mushi

    private void denDen(Player p, ItemDef def) {
        CrewService crews = module.service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewOf(p.getUniqueId());
        if (crew == null) {
            module.actionBar(p, "denden-no-crew");
            return;
        }
        String key = "item:" + def.id;
        long left = module.fruits().cooldownLeft(p.getUniqueId(), key);
        if (left > 0) {
            module.actionBar(p, "item-cooldown", "item", def.displayName(), "seconds", left);
            return;
        }
        module.fruits().setCooldown(p.getUniqueId(), key, def.cfg.getDouble("denden.cooldown", 30));
        Location l = p.getLocation();
        crew.broadcast(module.msg("denden-call", "player", p.getName(), "x", l.getBlockX(), "y", l.getBlockY(),
                "z", l.getBlockZ(), "world", l.getWorld().getName()));
        for (Player m : crew.onlineMembers()) {
            Util.sound(m, def.cfg.getString("denden.sound", "block.note_block.bell"), 1f, 1.5f);
        }
        int glow = (int) (def.cfg.getDouble("denden.glow-seconds", 5) * 20);
        if (glow > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glow, 0, true, false, false));
        }
    }

    // ------------------------------------------------------------------ drinks

    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        ItemDef def = module.defOf(event.getItem());
        if (def == null || !def.type.equals("drink")) {
            return;
        }
        Player p = event.getPlayer();
        int ticks = (int) (def.cfg.getDouble("drink.duration", 30) * 20);
        for (PotionEffect e : Util.parseEffects(def.cfg.getStringList("drink.effects"), ticks)) {
            p.addPotionEffect(e);
        }
        double heal = def.cfg.getDouble("drink.heal", 0);
        if (heal > 0) {
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(Math.min(max == null ? 20 : max.getValue(), p.getHealth() + heal));
        }
        String msg = def.cfg.getString("drink.message");
        if (msg != null && !msg.isEmpty()) {
            p.sendActionBar(Text.mm(msg));
        }
    }

    /** Custom items must not be used as their vanilla material in vanilla recipes. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null) {
            return;
        }
        if (recipe instanceof Keyed keyed && keyed.getKey().getNamespace().equals(module.plugin().getName().toLowerCase(Locale.ROOT))) {
            return;
        }
        for (ItemStack s : event.getInventory().getMatrix()) {
            if (module.idOf(s) != null) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
        logPoseTarget.remove(event.getPlayer().getUniqueId());
        abilities.endInvisibility(event.getPlayer());
    }
}
