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

/** Behaviour of non-fruit items: swords, Abyssal Iron, guns, cannons, tide compass, echo conch, drinks. */
public final class GearListener implements Listener {

    private record Tracked(UUID shooter, String kind, ConfigurationSection cfg, long expires) {
    }

    private final ItemsModule module;
    private final Abilities abilities;
    private final Map<UUID, Tracked> projectiles = new HashMap<>();
    /** Tide compass: player -> selected island id. */
    private final Map<UUID, String> compassTarget = new HashMap<>();
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
                    abilities.fx().glow(at, Abilities.colorOf(e.getValue().cfg(), "blast"), 1.3f, 2, 0.08);
                    abilities.fx().burst(at, org.bukkit.Particle.FLAME, 4, 0.12, 0.01);
                    abilities.fx().burst(at, org.bukkit.Particle.SMOKE, 2, 0.1, 0.01);
                }
                case "bomb" -> abilities.fx().burst(at, org.bukkit.Particle.SMOKE, 2, 0.05, 0.01);
                case "cannon" -> {
                    abilities.fx().burst(at, org.bukkit.Particle.LARGE_SMOKE, 2, 0.1, 0.01);
                    abilities.fx().burst(at, org.bukkit.Particle.FLAME, 1, 0.05, 0.01);
                }
                case "gun" -> { // a bright tracer with a thin smoke line
                    abilities.fx().glow(at, org.bukkit.Color.fromRGB(0xFFD27A), 0.7f, 1, 0.0);
                    abilities.fx().dust(at, org.bukkit.Color.fromRGB(0x8A8A8A), 0.5f, 1, 0.03);
                }
                default -> abilities.fx().dust(at, org.bukkit.Color.fromRGB(0x777777), 0.8f, 1, 0.02);
            }
        }
        thrown.entrySet().removeIf(e -> {
            Entity ent = Bukkit.getEntity(e.getKey());
            if (ent == null || !ent.isValid() || ent.isOnGround()) {
                return ent == null || !ent.isValid();
            }
            abilities.fx().glow(ent.getLocation(), e.getValue(), 1.2f, 2, 0.06);
            abilities.fx().burst(ent.getLocation(), org.bukkit.Particle.BUBBLE, 2, 0.08, 0.01);
            return false;
        });
    }

    /** Custom tridents in flight, with the colour of their trail. */
    private final Map<UUID, org.bukkit.Color> thrown = new HashMap<>();

    @EventHandler(ignoreCancelled = true)
    public void onThrow(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Trident t) || !(t.getShooter() instanceof Player p)) {
            return;
        }
        ItemDef def = module.defOf(t.getItemStack());
        if (def == null) {
            return;
        }
        org.bukkit.Color c = weaponColor(def);
        thrown.put(t.getUniqueId(), c);
        abilities.fx().crescent(p.getEyeLocation(), p.getEyeLocation().getDirection(), 2.0, c);
        abilities.fx().flash(p.getEyeLocation().add(p.getEyeLocation().getDirection()), c, 0.9f);
    }

    /** A custom trident lands: a splash of water and light. */
    private void tridentHit(ProjectileHitEvent event) {
        org.bukkit.Color c = thrown.get(event.getEntity().getUniqueId());
        if (c == null) {
            return;
        }
        Location at = event.getEntity().getLocation();
        abilities.fx().nova(at, c, 1.8);
        abilities.fx().burst(at, org.bukkit.Particle.SPLASH, 30, 0.6, 0.2);
        abilities.fx().burst(at, org.bukkit.Particle.BUBBLE_POP, 12, 0.5, 0.05);
    }

    /** True for hand-held tools and weapons that are not "sword" items (trident, pickaxe ...). */
    private static boolean wieldable(ItemStack item) {
        if (item == null) {
            return false;
        }
        String n = item.getType().name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_HOE") || n.equals("TRIDENT") || n.equals("MACE");
    }

    /** A custom shield (the Marauder's Buckler) stops a hit: a flash, a ring of sparks and a clang. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlock(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player p) || !p.isBlocking()) {
            return;
        }
        ItemStack shield = p.getInventory().getItemInMainHand().getType() == Material.SHIELD
                ? p.getInventory().getItemInMainHand() : p.getInventory().getItemInOffHand();
        ItemDef def = module.defOf(shield);
        if (def == null) {
            return;
        }
        org.bukkit.Color c = weaponColor(def);
        Vector dir = p.getEyeLocation().getDirection();
        Location front = p.getLocation().add(0, 1.1, 0).add(dir.clone().setY(0).normalize().multiply(0.7));
        abilities.fx().shield(front, p.getLocation().getYaw(), c, 2.0f);
        abilities.fx().burst(front, org.bukkit.Particle.ELECTRIC_SPARK, 8, 0.3, 0.2);
        abilities.fx().shockwave(p.getLocation().add(0, 0.1, 0), 1.5, 5, c);
    }

    /** Mining with a custom tool: sparks and light where the block breaks. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(org.bukkit.event.block.BlockBreakEvent event) {
        ItemDef def = module.defOf(event.getPlayer().getInventory().getItemInMainHand());
        if (def == null || abilities.fx().quality() <= 0) {
            return;
        }
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        org.bukkit.Color c = weaponColor(def);
        abilities.fx().flash(at, c, 0.8f);
        abilities.fx().sparkles(at, c, 8, 0.45);
        abilities.fx().burst(at, org.bukkit.Particle.ELECTRIC_SPARK, 6, 0.3, 0.12);
        if (event.getBlock().getType().name().endsWith("_ORE")) {
            abilities.fx().nova(at, c, 1.2);                      // ore: a bigger burst
        }
    }

    /**
     * A fruit you hold (but have not eaten) gives a small preview of its power: a few particles of its
     * first move around your hand (frost for Rimefrost, embers for Emberheart, sparks for Thunderveil, ...).
     */
    private void fruitAura(Player p) {
        if (!module.config().getBoolean("effects.fruit-preview", true) || !module.fruits().isEnabled()) {
            return;
        }
        ItemDef def = module.defOf(p.getInventory().getItemInMainHand());
        if (def == null || !def.isFruit()) {
            return;
        }
        ConfigurationSection a = def.ability();
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) {
            dir = new Vector(1, 0, 0);
        }
        dir.normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        Location hand = p.getLocation().add(0, 1.15, 0).add(dir.clone().multiply(0.45)).add(right.multiply(0.4));
        Fx fx = abilities.fx();
        org.bukkit.Color c = Fx.color(a == null ? null : a.getString("color"), org.bukkit.Color.fromRGB(0xB388FF));
        fx.glow(hand, c, 0.8f, 3, 0.18);
        String particle = a == null ? null : a.getString("particle");
        if (particle != null) {
            try {
                fx.burst(hand, org.bukkit.Particle.valueOf(particle.toUpperCase(java.util.Locale.ROOT)), 2, 0.15, 0.01);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** The weapon's colour: "vfx-color", else its ability's colour, else steel. */
    private org.bukkit.Color weaponColor(ItemDef def) {
        String raw = def.cfg.getString("vfx-color");
        ConfigurationSection a = def.ability();
        if (raw == null && a != null) {
            raw = a.getString("color");
        }
        return Fx.color(raw, org.bukkit.Color.fromRGB(0xDDE8F0));
    }

    /**
     * A fully charged hit with a custom weapon: a glowing slash arc in the weapon's colour, a spark of light on
     * the target and coloured dust; a proc (on-hit effect) adds a shockwave and a bigger flash.
     */
    private void swingFx(Player p, LivingEntity victim, ItemDef def, boolean proc) {
        Fx fx = abilities.fx();
        org.bukkit.Color c = weaponColor(def);
        Location eye = p.getEyeLocation();
        fx.slash(eye, eye.getDirection(), proc ? 3.2 : 2.4, c);
        Location hit = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        fx.impact(hit, c, proc ? 1.5f : 0.9f);
        fx.burst(hit, org.bukkit.Particle.CRIT, proc ? 10 : 5, 0.25, 0.25);
        if (proc) {
            fx.crescent(eye, eye.getDirection(), 2.4, c);
            fx.shockwave(victim.getLocation().add(0, 0.1, 0), 1.8, 7, c);
            fx.spray(hit, org.bukkit.Particle.ENCHANTED_HIT, 10, 0.35);
            if (p.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) {
                fx.crack(victim.getLocation(), c, 2.4);          // mauls and anchors split the ground
            }
            ConfigurationSection onHit = def.cfg.getConfigurationSection("on-hit");
            if (onHit != null) {
                fx.element(hit, Abilities.styleOf(onHit), c, 1.0f);
            }
        }
    }

    /** A weapon ability starts with a swing: a crescent of light and a quick ring at the feet. */
    void castFx(Player p, ItemDef def) {
        Fx fx = abilities.fx();
        org.bukkit.Color c = weaponColor(def);
        fx.crescent(p.getEyeLocation(), p.getEyeLocation().getDirection(), 2.2, c);
        fx.rune(p.getLocation(), c, 1.2, 12);
    }

    /** Muzzle flash, sparks and a puff of smoke in front of the barrel. */
    private void muzzle(Player p, float size) {
        Fx fx = abilities.fx();
        Vector dir = p.getEyeLocation().getDirection();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() > 1e-4) {
            right.normalize();
        }
        Location m = p.getEyeLocation().add(dir.clone().multiply(1.1)).add(right.multiply(0.3)).add(0, -0.2, 0);
        fx.flash(m, org.bukkit.Color.fromRGB(0xFFB347), 0.9f * size);
        fx.burst(m, org.bukkit.Particle.FLAME, 4, 0.05, 0.03);
        fx.burst(m, org.bukkit.Particle.LAVA, 1, 0.02, 0);
        fx.burst(m.clone().add(dir.clone().multiply(0.4)), org.bukkit.Particle.CAMPFIRE_COSY_SMOKE, (int) (3 * size), 0.08, 0.01);
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
        org.bukkit.Color bc = Fx.color(b.getString("color"), org.bukkit.Color.GRAY);
        abilities.fx().wave(at.clone().add(0, 0.2, 0), radius, 6, bc, null, false);
        abilities.fx().nova(at.clone().add(0, 0.8, 0), bc, radius * 0.8);
        abilities.fx().element(at.clone().add(0, 0.6, 0), Abilities.styleOf(b), bc, (float) Math.max(1.6, radius * 0.6));
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

    /** Light periodic task: tide compass action bar and projectile bookkeeping. */
    /**
     * Armour sets: items with "set: <id>" in items.yml. Wearing "armor-sets.<id>.pieces" of them gives
     * the set bonus (effects + extra health) as long as they are worn.
     */
    private void tickSets(Player p) {
        Map<String, Integer> worn = new HashMap<>();
        for (ItemStack piece : p.getInventory().getArmorContents()) {
            ItemDef def = module.defOf(piece);
            String set = def == null ? null : def.cfg.getString("set");
            if (set != null) {
                worn.merge(set, 1, Integer::sum);
            }
        }
        double health = 0;
        ConfigurationSection sets = module.config().getConfigurationSection("armor-sets");
        for (Map.Entry<String, Integer> e : worn.entrySet()) {
            ConfigurationSection set = sets == null ? null : sets.getConfigurationSection(e.getKey());
            if (set == null || e.getValue() < set.getInt("pieces", 4)) {
                continue;
            }
            for (org.bukkit.potion.PotionEffect fx : Util.parseEffects(set.getStringList("effects"), 60)) {
                p.addPotionEffect(fx);
            }
            health += set.getDouble("bonus-health", 0);
            if (abilities.fx().quality() > 0 && p.getTicksLived() % 40 < 20) {
                org.bukkit.Color sc = Fx.color(set.getString("color"), org.bukkit.Color.AQUA);
                abilities.fx().dust(p.getLocation().add(0, 0.2, 0), sc, 1.0f, 3, 0.4);
                if (p.getTicksLived() % 200 < 20) {
                    abilities.fx().orbit(p, 20, sc, 3);                 // every 10 seconds: the set's aura
                }
            }
        }
        dev.aquatic.util.Stats.health(p, "armor_set", health);
        ItemDef head = module.defOf(p.getInventory().getHelmet());
        if (head != null && head.id.equals("conqueror_crown") && abilities.fx().quality() > 0) {
            abilities.fx().sparkles(p.getLocation().add(0, p.getHeight() + 0.25, 0), org.bukkit.Color.fromRGB(0xFFD24A), 3, 0.3);
        }
    }

    public void tick() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            tickSets(online);
            fruitAura(online);
        }
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
            if (main != null && main.type.equals("compass")) {
                showCompass(p, main, p.getInventory().getItemInMainHand());
            } else if (off != null && off.type.equals("compass")) {
                showCompass(p, off, p.getInventory().getItemInOffHand());
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

    // ------------------------------------------------------------------ melee: swords & Abyssal Iron

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
        if (def.type.equals("abyssal") && victim instanceof Player target && module.fruits().fruitOf(target.getUniqueId()) != null) {
            double seconds = def.cfg.getDouble("abyssal.hit-seconds", 6);
            module.fruits().seal(target, seconds);
            module.actionBar(target, "sealed", "seconds", Text.number(seconds));
            module.actionBar(p, "abyssal-hit", "player", target.getName(), "seconds", Text.number(seconds));
            Util.sound(target.getLocation(), "block.beacon.deactivate", 1f, 1.6f);
        }
        if (p.getAttackCooldown() >= 0.9f && (def.type.equals("sword") || def.type.equals("abyssal")
                || wieldable(p.getInventory().getItemInMainHand()))) {
            swingFx(p, victim, def, false);
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
        swingFx(p, victim, def, true);
        String sound = onHit.getString("sound");
        if (sound != null) {
            Util.sound(victim.getLocation(), sound, 1f, 1f);
        }
    }

    /** Abyssal Iron shackles: right-click a abyss fruit user to lock their power away. */
    @EventHandler(ignoreCancelled = true)
    public void onCuff(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) {
            return;
        }
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemDef def = module.defOf(hand);
        if (def == null || !def.type.equals("abyssal")) {
            return;
        }
        double seconds = def.cfg.getDouble("abyssal.cuff-seconds", 0);
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
        module.fruits().setCooldown(p.getUniqueId(), "cuff", def.cfg.getDouble("abyssal.cuff-cooldown", 30));
        module.fruits().seal(target, seconds);
        if (event.getRightClicked() instanceof LivingEntity cuffed) {
            abilities.fx().chain(event.getPlayer().getLocation().add(0, 1.0, 0), cuffed.getLocation().add(0, 1.0, 0));
            abilities.fx().flash(cuffed.getLocation().add(0, 1.0, 0), org.bukkit.Color.fromRGB(0x2F8F86), 1.2f);
            abilities.fx().orbit(cuffed, 30, org.bukkit.Color.fromRGB(0x2F8F86), 4);
        }
        int ticks = (int) (seconds * 20);
        for (PotionEffect e : Util.parseEffects(def.cfg.getStringList("abyssal.cuff-effects"), ticks)) {
            target.addPotionEffect(e);
        }
        module.send(target, "cuffed", "player", p.getName(), "seconds", Text.number(seconds));
        module.send(p, "cuffed-other", "player", target.getName(), "seconds", Text.number(seconds));
        Util.sound(target.getLocation(), "block.chain.place", 1f, 0.8f);
        if (def.cfg.getBoolean("abyssal.consume-cuffs", true) && p.getGameMode() != GameMode.CREATIVE) {
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
                    castFx(p, def);
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
            case "compass" -> {
                event.setCancelled(true);
                if (last != null && now - last < 250) {
                    return;
                }
                lastUse.put(p.getUniqueId(), now);
                cycleCompass(p, def, hand, p.isSneaking());
            }
            case "conch" -> {
                event.setCancelled(true);
                if (last != null && now - last < 250) {
                    return;
                }
                lastUse.put(p.getUniqueId(), now);
                conchCall(p, def);
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
        muzzle(p, def.type.equals("cannon") ? 1.6f : 1.0f);
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
        tridentHit(event);
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
                    abilities.fx().burst(at, org.bukkit.Particle.CRIT, 10, 0.2, 0.3);
                    abilities.fx().burst(at, org.bukkit.Particle.DAMAGE_INDICATOR, 3, 0.2, 0.1);
                } else if (event.getHitBlock() != null) {
                    abilities.particles(t.cfg(), "SMOKE", at, 5, 0.05);
                    abilities.fx().burst(at, org.bukkit.Particle.ELECTRIC_SPARK, 8, 0.1, 0.15); // the ball sparks off the block
                    if (at.getWorld() != null) {
                        at.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, at, 10, 0.15, 0.15, 0.15, 0.1,
                                event.getHitBlock().getBlockData());
                    }
                }
                abilities.fx().flash(at, org.bukkit.Color.fromRGB(0xFFE0A0), 0.7f);
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
                abilities.fx().nova(at.clone().add(0, 0.5, 0), org.bukkit.Color.fromRGB(0xFF8A3D), c.getDouble("radius", 4));
                abilities.fx().shockwave(at.clone().add(0, 0.2, 0), c.getDouble("radius", 4) + 1, 8, org.bukkit.Color.fromRGB(0xB0B0B0));
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ tide compass

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

    private void cycleCompass(Player p, ItemDef def, ItemStack stack, boolean nearest) {
        IslandService islands = module.service(IslandService.class);
        if (islands == null) {
            module.actionBar(p, "compass-unavailable");
            return;
        }
        List<Island> list = sortedIslands(p, islands);
        if (list.isEmpty()) {
            module.actionBar(p, "compass-no-islands");
            return;
        }
        Island here = islands.islandAt(p.getLocation());
        Island next;
        String current = compassTarget.get(p.getUniqueId());
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
        compassTarget.put(p.getUniqueId(), next.id());
        point(stack, next.center());
        if (next.center() != null && next.center().getWorld() == p.getWorld()) {
            // a short trail of light pointing the way
            org.bukkit.util.Vector way = next.center().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (way.lengthSquared() > 1) {
                way.normalize();
                Location from = p.getLocation().add(0, 1.0, 0).add(way.clone().multiply(0.8));
                abilities.fx().beam(from, from.clone().add(way.clone().multiply(3.5)), org.bukkit.Color.fromRGB(0x5FF3FF), 1.2f, 0.25);
                abilities.fx().sparkles(from.clone().add(way.clone().multiply(3.5)), org.bukkit.Color.fromRGB(0x5FF3FF), 8, 0.3);
            }
        }
        Util.sound(p, "item.lodestone_compass.lock", 1f, 1.2f);
        showCompass(p, def, stack);
    }

    private void point(ItemStack stack, Location target) {
        if (stack.getItemMeta() instanceof CompassMeta meta) {
            meta.setLodestone(target.clone());
            meta.setLodestoneTracked(false);
            stack.setItemMeta(meta);
        }
    }

    private void showCompass(Player p, ItemDef def, ItemStack stack) {
        IslandService islands = module.service(IslandService.class);
        if (islands == null) {
            module.actionBar(p, "compass-unavailable");
            return;
        }
        String id = compassTarget.get(p.getUniqueId());
        Island target = id == null ? null : islands.islandById(id);
        if (target == null || target.center() == null || target.center().getWorld() == null
                || !target.center().getWorld().equals(p.getWorld())) {
            List<Island> list = sortedIslands(p, islands);
            if (list.isEmpty()) {
                module.actionBar(p, "compass-no-islands");
                return;
            }
            target = list.get(0);
            compassTarget.put(p.getUniqueId(), target.id());
            point(stack, target.center());
        }
        Island here = islands.islandAt(p.getLocation());
        if (here != null && here.id().equals(target.id())) {
            module.actionBar(p, "compass-arrived", "island", target.name());
            return;
        }
        module.actionBar(p, "compass-bar", "island", target.name(), "type", target.type().name().toLowerCase(Locale.ROOT),
                "distance", (long) Util.flatDistance(target.center(), p.getLocation()));
    }

    // ------------------------------------------------------------------ echo conch

    private void conchCall(Player p, ItemDef def) {
        CrewService crews = module.service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewOf(p.getUniqueId());
        if (crew == null) {
            module.actionBar(p, "conch-no-crew");
            return;
        }
        String key = "item:" + def.id;
        long left = module.fruits().cooldownLeft(p.getUniqueId(), key);
        if (left > 0) {
            module.actionBar(p, "item-cooldown", "item", def.displayName(), "seconds", left);
            return;
        }
        module.fruits().setCooldown(p.getUniqueId(), key, def.cfg.getDouble("conch.cooldown", 30));
        Location l = p.getLocation();
        crew.broadcast(module.msg("conch-call", "player", p.getName(), "x", l.getBlockX(), "y", l.getBlockY(),
                "z", l.getBlockZ(), "world", l.getWorld().getName()));
        for (Player m : crew.onlineMembers()) {
            Util.sound(m, def.cfg.getString("conch.sound", "block.note_block.bell"), 1f, 1.5f);
            abilities.fx().sparkles(m.getLocation().add(0, 2.1, 0), org.bukkit.Color.fromRGB(0xF6D7A7), 10, 0.4);
        }
        abilities.fx().soundRings(p.getLocation().add(0, 0.2, 0), org.bukkit.Color.fromRGB(0xF6D7A7), 4, 7);
        abilities.fx().pillar(p.getLocation(), org.bukkit.Color.fromRGB(0xF6D7A7), 3.5, 14);
        int glow = (int) (def.cfg.getDouble("conch.glow-seconds", 5) * 20);
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
        org.bukkit.Color dc = Fx.color(def.cfg.getString("vfx-color", def.cfg.getString("color")), org.bukkit.Color.fromRGB(0xE84393));
        abilities.fx().spiral(p, 30, dc);
        abilities.fx().sparkles(p.getLocation().add(0, 1.2, 0), dc, 16, 0.5);
        if (heal > 0) {
            abilities.fx().burst(p.getLocation().add(0, 1.6, 0), org.bukkit.Particle.HEART, 3, 0.4, 0);
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
        compassTarget.remove(event.getPlayer().getUniqueId());
        abilities.endInvisibility(event.getPlayer());
    }

    /** Items from before 2.3.1 carry old ids: rewrite them when a player joins or opens a container. */
    @EventHandler
    public void onJoinUpgrade(org.bukkit.event.player.PlayerJoinEvent event) {
        module.upgradeIds(event.getPlayer().getInventory());
        module.upgradeIds(event.getPlayer().getEnderChest());
        if (!module.fruits().isEnabled()) {
            dev.aquatic.util.Stats.clear(event.getPlayer(), "fruit");
        }
    }

    @EventHandler
    public void onOpenUpgrade(org.bukkit.event.inventory.InventoryOpenEvent event) {
        module.upgradeIds(event.getInventory());
    }
}
