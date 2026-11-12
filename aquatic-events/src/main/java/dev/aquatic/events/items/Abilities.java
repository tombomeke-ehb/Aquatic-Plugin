package dev.aquatic.events.items;

import dev.aquatic.api.CrewService;
import dev.aquatic.events.ItemsModule;
import dev.aquatic.events.Util;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Tameable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The ability engine used by devil fruits and legendary swords. Every ability is a config section with
 * a "type" and numbers; see the header of modules/items.yml for the list of types and their options.
 */
public final class Abilities {

    private static final class Dash {
        final UUID player;
        final ConfigurationSection cfg;
        final Set<UUID> hit = new HashSet<>();
        int ticksLeft;

        double damage;

        Dash(UUID player, ConfigurationSection cfg, int ticks) {
            this.player = player;
            this.cfg = cfg;
            this.ticksLeft = ticks;
        }
    }

    private final ItemsModule module;
    private final GearListener gear;
    private final List<Dash> dashes = new ArrayList<>();
    /** Players with an active invisibility toggle -> end time (ms). */
    private final Map<UUID, Long> invisible = new HashMap<>();

    private final Fx fx;

    /**
     * Scaling of the move being cast: set by FruitManager (mastery) around execute(); 0 = not a fruit
     * cast, then the item multiplier from items.yml applies (swords and other gear).
     */
    double castDamageScale;
    double castRangeScale = 1;

    private double damageScale() {
        return castDamageScale > 0 ? castDamageScale : module.config().getDouble("abilities.item-damage-multiplier", 1.3);
    }

    Abilities(ItemsModule module, GearListener gear) {
        this.module = module;
        this.gear = gear;
        this.fx = new Fx(module);
    }

    Fx fx() {
        return fx;
    }

    /** Ability colour: "color" in its config section, or a default per ability type. */
    private static org.bukkit.Color colorOf(ConfigurationSection a, String type) {
        org.bukkit.Color def = switch (type) {
            case "fireball", "blast" -> org.bukkit.Color.fromRGB(0xFF6A00);
            case "nova" -> org.bukkit.Color.fromRGB(0x9BE7FF);
            case "lightning" -> org.bukkit.Color.fromRGB(0xFFF35C);
            case "pull" -> org.bukkit.Color.fromRGB(0x3B0A57);
            case "swap" -> org.bukkit.Color.fromRGB(0x7B5CFF);
            case "transform" -> org.bukkit.Color.fromRGB(0x8B4513);
            case "invisibility" -> org.bukkit.Color.fromRGB(0xD9D9D9);
            case "slash", "dash" -> org.bukkit.Color.fromRGB(0xF2F2F2);
            default -> org.bukkit.Color.fromRGB(0xFF3B3B);
        };
        return Fx.color(a.getString("color"), def);
    }

    // ------------------------------------------------------------------ damage rules

    /** PvP rules for abilities: never yourself, crewmates, your pets, creative/spectator players. */
    public boolean canHurt(Player source, Entity target) {
        if (!(target instanceof LivingEntity le) || target.equals(source) || target instanceof ArmorStand
                || le.isDead() || le.isInvulnerable()) {
            return false;
        }
        if (target instanceof Player tp) {
            if (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR) {
                return false;
            }
            if (!target.getWorld().getPVP()) {
                return false;
            }
            CrewService crews = module.service(CrewService.class);
            if (crews != null && crews.sameCrew(source.getUniqueId(), tp.getUniqueId())) {
                return false;
            }
        }
        if (target instanceof Tameable pet && pet.isTamed() && source.getUniqueId().equals(pet.getOwnerUniqueId())) {
            return false;
        }
        return true;
    }

    /**
     * Deals normal Bukkit damage with the player as source, so every protection plugin/module can veto it.
     *
     * @return true when the damage went through
     */
    public boolean hurt(Player source, LivingEntity target, double amount) {
        if (!canHurt(source, target)) {
            return false;
        }
        boolean outerFlag = module.abilityDamage;
        boolean outerCancelled = module.abilityDamageCancelled;
        module.abilityDamage = true;
        module.abilityDamageCancelled = true;
        boolean cancelled;
        try {
            target.setNoDamageTicks(0);
            target.damage(Math.max(0.01, amount), source);
        } finally {
            cancelled = module.abilityDamageCancelled;
            module.abilityDamage = outerFlag;
            module.abilityDamageCancelled = outerCancelled;
        }
        return !cancelled;
    }

    /** Damage plus the victim side effects of the section: effects, fire-ticks, freeze-ticks. */
    private boolean strike(Player source, LivingEntity target, ConfigurationSection cfg, double damage) {
        if (!hurt(source, target, damage)) {
            return false;
        }
        // impact: sparks, a crit flash and a punchy sound
        Location hitAt = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        fx.burst(hitAt, Particle.CRIT, 12, 0.3, 0.25);
        fx.burst(hitAt, Particle.ENCHANTED_HIT, 6, 0.3, 0.2);
        Util.sound(hitAt, "entity.player.attack.crit", 0.7f, 1.1f);
        if (module.fruits() != null && module.fruits().isFruitMove(cfg)) {
            module.fruits().addMastery(source, module.config().getLong("fruits.mastery.xp-per-hit", 3));
        }
        int defTicks = (int) (cfg.getDouble("effect-seconds", 5) * 20);
        for (PotionEffect e : Util.parseEffects(cfg.getStringList("effects"), defTicks)) {
            target.addPotionEffect(e);
        }
        int fire = cfg.getInt("fire-ticks", 0);
        if (fire > 0) {
            target.setFireTicks(Math.max(target.getFireTicks(), fire));
        }
        int freeze = cfg.getInt("freeze-ticks", 0);
        if (freeze > 0) {
            target.setFreezeTicks(Math.min(target.getMaxFreezeTicks() + freeze, target.getFreezeTicks() + freeze));
        }
        return true;
    }

    /** Area damage around a point (used by novas, fireballs, lightning and cannonballs). */
    public int area(Player source, Location at, double radius, double damage, ConfigurationSection cfg) {
        int hits = 0;
        if (at.getWorld() == null) {
            return 0;
        }
        double knock = cfg.getDouble("knockback", 0);
        for (Entity e : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            if (!(e instanceof LivingEntity le) || e.getLocation().distanceSquared(at) > radius * radius) {
                continue;
            }
            if (strike(source, le, cfg, damage)) {
                hits++;
                if (knock != 0) {
                    Vector dir = le.getLocation().toVector().subtract(at.toVector());
                    if (dir.lengthSquared() < 0.01) {
                        dir = new Vector(0, 1, 0);
                    }
                    push(le, dir.normalize().multiply(knock).setY(Math.abs(knock) * 0.35));
                }
            }
        }
        return hits;
    }

    private static void push(Entity e, Vector v) {
        if (Double.isFinite(v.getX()) && Double.isFinite(v.getY()) && Double.isFinite(v.getZ())) {
            e.setVelocity(v);
        }
    }

    private LivingEntity target(Player p, double range) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult hit = p.getWorld().rayTraceEntities(eye, dir, range, 0.5, e -> canHurt(p, e));
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity le)) {
            return null;
        }
        RayTraceResult block = p.getWorld().rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        if (block != null && block.getHitPosition().distanceSquared(eye.toVector())
                < hit.getHitPosition().distanceSquared(eye.toVector())) {
            return null;
        }
        return le;
    }

    public void particles(ConfigurationSection cfg, String fallback, Location at, int count, double spread) {
        String name = cfg.getString("particle", fallback);
        if (name == null || name.isEmpty() || name.equalsIgnoreCase("none") || at.getWorld() == null) {
            return;
        }
        try {
            Particle particle = Particle.valueOf(name.toUpperCase(Locale.ROOT));
            if (particle.getDataType() != Void.class) {
                return;
            }
            at.getWorld().spawnParticle(particle, at, count, spread, spread, spread, 0.02);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void sound(ConfigurationSection cfg, String fallback, Location at) {
        Util.sound(at, cfg.getString("sound", fallback), 1.0f, (float) cfg.getDouble("sound-pitch", 1.0));
    }

    // ------------------------------------------------------------------ execution

    /** Turns off a running toggle ability (invisibility). @return true when something was switched off. */
    public boolean toggleOff(Player p, ConfigurationSection a) {
        if (!"invisibility".equalsIgnoreCase(a.getString("type")) || !a.getBoolean("toggle", true)) {
            return false;
        }
        Long until = invisible.get(p.getUniqueId());
        if (until == null || until < System.currentTimeMillis()) {
            return false;
        }
        endInvisibility(p);
        module.actionBar(p, "invisibility-off");
        return true;
    }

    public boolean isInvisible(Player p) {
        Long until = invisible.get(p.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    public void endInvisibility(Player p) {
        if (invisible.remove(p.getUniqueId()) != null) {
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    /**
     * Runs the ability, after an optional visible build-up ("windup-ticks") so opponents can react.
     * A move with a windup always counts as used (the cooldown starts).
     */
    public boolean executeWithWindup(Player p, ConfigurationSection a) {
        int windup = Math.max(0, a.getInt("windup-ticks", 0));
        if (windup <= 0) {
            return execute(p, a);
        }
        String type = a.getString("type", "").toLowerCase(Locale.ROOT);
        org.bukkit.Color c = colorOf(a, type);
        fx.spiral(p, windup, c);
        fx.wave(p.getLocation().add(0, 0.1, 0), 1.6, windup, c, Particle.ENCHANT, true);
        sound(a.getConfigurationSection("windup") != null ? a.getConfigurationSection("windup") : a, "block.beacon.power_select", p.getLocation());
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, windup, 2, true, false, false));
        double ds = castDamageScale, rs = castRangeScale;
        module.runLater(() -> {
            if (p.isOnline() && !p.isDead()) {
                double oldD = castDamageScale, oldR = castRangeScale;
                castDamageScale = ds;
                castRangeScale = rs;
                try {
                    execute(p, a);
                } finally {
                    castDamageScale = oldD;
                    castRangeScale = oldR;
                }
            }
        }, windup);
        return true;
    }

    /**
     * Runs the ability.
     *
     * @return false when it could not be used (no target); no cooldown should be applied then
     */
    public boolean execute(Player p, ConfigurationSection a) {
        String type = a.getString("type", "").toLowerCase(Locale.ROOT);
        double damage = a.getDouble("damage", 0) * damageScale();
        double range = a.getDouble("range", 12) * castRangeScale;
        double radius = a.getDouble("radius", 4) * castRangeScale;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        switch (type) {
            case "punch" -> {
                LivingEntity t = target(p, range);
                if (t != null) {
                    if (!strike(p, t, a, damage)) {
                        return false;
                    }
                    double knock = a.getDouble("knockback", 1.5);
                    push(t, dir.clone().multiply(knock).setY(0.4));
                    line(a, "CRIT", eye, t.getLocation().add(0, 1, 0));
                    org.bukkit.Color c = colorOf(a, type);
                    fx.beam(eye.clone().add(dir.clone().multiply(0.6)), t.getLocation().add(0, 1, 0), c, 1.5f, 0.35);
                    fx.burst(t.getLocation().add(0, 1, 0), Particle.EXPLOSION, 1, 0, 0);
                    fx.wave(t.getLocation().add(0, 0.2, 0), 2.5, 6, c, Particle.CLOUD, false);
                    sound(a, "entity.player.attack.strong", t.getLocation());
                    Util.sound(t.getLocation(), "entity.generic.explode", 0.5f, 1.6f);
                    return true;
                }
                double leap = a.getDouble("leap-power", 0);
                if (leap <= 0) {
                    return false;
                }
                push(p, dir.clone().multiply(leap));
                p.setFallDistance(0);
                particles(a, "CLOUD", p.getLocation(), 15, 0.3);
                fx.wave(p.getLocation().add(0, 0.1, 0), 2.0, 5, colorOf(a, type), Particle.CLOUD, false);
                sound(a, "entity.slime.jump", p.getLocation());
                return true;
            }
            case "fireball" -> {
                SmallFireball ball = p.launchProjectile(SmallFireball.class, dir.clone().multiply(a.getDouble("speed", 1.5)));
                ball.setIsIncendiary(false);
                ball.setYield(0);
                gear.track(ball, p, "blast", a);
                fx.burst(eye.clone().add(dir.clone().multiply(1.2)), Particle.FLAME, 14, 0.15, 0.06);
                sound(a, "entity.blaze.shoot", p.getLocation());
                return true;
            }
            case "nova" -> {
                Location c = p.getLocation().add(0, 1, 0);
                area(p, c, radius, damage, a);
                ring(a, "SNOWFLAKE", p.getLocation().add(0, 0.3, 0), radius);
                fx.wave(p.getLocation().add(0, 0.3, 0), radius, 8, colorOf(a, type), Particle.SNOWFLAKE, false);
                fx.burst(c, Particle.WHITE_ASH, 30, radius / 3, 0.02);
                sound(a, "block.glass.break", c);
                selfEffects(p, a);
                return true;
            }
            case "lightning" -> {
                Location at;
                LivingEntity t = target(p, range);
                if (t != null) {
                    at = t.getLocation();
                } else {
                    RayTraceResult block = p.getWorld().rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
                    if (block == null) {
                        return false;
                    }
                    at = block.getHitPosition().toLocation(p.getWorld());
                }
                p.getWorld().strikeLightningEffect(at);
                fx.bolt(at.clone().add(0, 0.5, 0), colorOf(a, type));
                fx.wave(at.clone().add(0, 0.2, 0), radius, 6, colorOf(a, type), Particle.ELECTRIC_SPARK, false);
                area(p, at, radius, damage, a);
                particles(a, "ELECTRIC_SPARK", at.clone().add(0, 1, 0), 60, radius / 2);
                return true;
            }
            case "invisibility" -> {
                int ticks = (int) (a.getDouble("duration", 20) * 20);
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, ticks, 0, true, false, true));
                invisible.put(p.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
                selfEffects(p, a);
                particles(a, "CLOUD", p.getLocation().add(0, 1, 0), 25, 0.4);
                fx.burst(p.getLocation().add(0, 1, 0), Particle.LARGE_SMOKE, 35, 0.6, 0.03);
                sound(a, "entity.illusioner.mirror_move", p.getLocation());
                return true;
            }
            case "swap" -> {
                LivingEntity t = target(p, range);
                if (t == null || !strike(p, t, a, Math.max(damage, 0.01))) {
                    return false;
                }
                Location mine = p.getLocation();
                Location theirs = t.getLocation();
                Location toMine = theirs.clone();
                toMine.setYaw(mine.getYaw());
                toMine.setPitch(mine.getPitch());
                Location toTheirs = mine.clone();
                toTheirs.setYaw(theirs.getYaw());
                toTheirs.setPitch(theirs.getPitch());
                fx.beam(mine.clone().add(0, 1, 0), theirs.clone().add(0, 1, 0), colorOf(a, type), 1.4f, 0.3);
                p.teleport(toMine);
                t.teleport(toTheirs);
                particles(a, "PORTAL", mine.add(0, 1, 0), 40, 0.5);
                particles(a, "PORTAL", theirs.add(0, 1, 0), 40, 0.5);
                sound(a, "entity.enderman.teleport", toMine);
                return true;
            }
            case "pull" -> {
                Location c = p.getLocation();
                double strength = a.getDouble("pull-strength", 1.2);
                for (Entity e : p.getWorld().getNearbyEntities(c, radius, radius, radius)) {
                    if (e instanceof LivingEntity le && e.getLocation().distanceSquared(c) <= radius * radius
                            && strike(p, le, a, damage)) {
                        Vector v = c.toVector().subtract(le.getLocation().toVector());
                        if (v.lengthSquared() > 1) {
                            push(le, v.normalize().multiply(strength).setY(0.25));
                        }
                    }
                }
                ring(a, "SQUID_INK", c.clone().add(0, 0.5, 0), radius);
                fx.wave(c.clone().add(0, 0.5, 0), radius, 10, colorOf(a, type), Particle.SQUID_INK, true);
                sound(a, "entity.wither.ambient", c);
                selfEffects(p, a);
                return true;
            }
            case "transform" -> {
                selfEffects(p, a);
                fx.spiral(p, Math.min(100, (int) (a.getDouble("duration", 10) * 20)), colorOf(a, type));
                fx.wave(p.getLocation().add(0, 0.1, 0), 3, 6, colorOf(a, type), Particle.CRIT, false);
                particles(a, "ANGRY_VILLAGER", p.getLocation().add(0, 1, 0), 20, 0.6);
                sound(a, "entity.ravager.roar", p.getLocation());
                return true;
            }
            case "dash" -> {
                Vector v = dir.clone().multiply(a.getDouble("power", 1.8));
                v.setY(Math.max(0.15, Math.min(0.6, v.getY())));
                push(p, v);
                p.setFallDistance(0);
                dashes.removeIf(d -> d.player.equals(p.getUniqueId()));
                Dash dash = new Dash(p.getUniqueId(), a, Math.max(1, a.getInt("dash-ticks", 8)));
                dash.damage = damage;
                dashes.add(dash);
                fx.burst(p.getLocation().add(0, 0.5, 0), Particle.CLOUD, 12, 0.3, 0.05);
                sound(a, "entity.player.attack.sweep", p.getLocation());
                selfEffects(p, a);
                return true;
            }
            case "wave" -> {
                // a wave rolling forward over the ground (fire wave, ice path, gust ...)
                Vector flat = dir.clone().setY(0);
                if (flat.lengthSquared() < 1e-4) {
                    flat = new Vector(1, 0, 0);
                }
                flat.normalize();
                Set<UUID> hit = new HashSet<>();
                Location start = p.getLocation().add(0, 0.2, 0);
                int steps = Math.max(2, (int) range);
                double width = a.getDouble("width", 1.6);
                double knock = a.getDouble("knockback", 0.6);
                org.bukkit.Color c = colorOf(a, type);
                String block = a.getString("path-block");
                Vector stepV = flat.clone();
                for (int i = 1; i <= steps; i++) {
                    int n = i;
                    module.runLater(() -> {
                        if (!p.isOnline()) {
                            return;
                        }
                        Location pt = start.clone().add(stepV.clone().multiply(n));
                        fx.dust(pt.clone().add(0, 0.4, 0), c, 1.8f, 6, width / 3);
                        particles(a, "FLAME", pt.clone().add(0, 0.3, 0), 4, width / 3);
                        for (Entity e : p.getWorld().getNearbyEntities(pt, width, 1.6, width)) {
                            if (e instanceof LivingEntity le && hit.add(e.getUniqueId()) && strike(p, le, a, damage)) {
                                push(le, stepV.clone().multiply(knock).setY(0.3));
                            }
                        }
                        if (block != null) {
                            org.bukkit.block.Block below = pt.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
                            org.bukkit.Material m = org.bukkit.Material.matchMaterial(block);
                            if (m != null && below.getType() == org.bukkit.Material.WATER) {
                                below.setType(m); // e.g. FROSTED_ICE: melts again on its own
                            }
                        }
                    }, i);
                }
                sound(a, "entity.blaze.shoot", p.getLocation());
                selfEffects(p, a);
                return true;
            }
            case "beam" -> {
                // an instant ray that hits everything along it (up to "pierce" targets)
                Location from = eye.clone();
                Vector step = dir.clone().normalize().multiply(0.5);
                Location point = from.clone();
                Set<UUID> hit = new HashSet<>();
                int pierce = Math.max(1, a.getInt("pierce", 3));
                for (double d = 0; d < range && hit.size() < pierce; d += 0.5) {
                    point.add(step);
                    if (point.getBlock().getType().isOccluding()) {
                        break;
                    }
                    for (Entity e : p.getWorld().getNearbyEntities(point, 0.7, 0.7, 0.7)) {
                        if (e instanceof LivingEntity le && hit.add(e.getUniqueId())) {
                            strike(p, le, a, damage);
                        }
                    }
                }
                fx.beam(from, point, colorOf(a, type), 1.6f, 0.3);
                particles(a, "END_ROD", point, 12, 0.3);
                sound(a, "entity.warden.sonic_boom", p.getLocation());
                selfEffects(p, a);
                return true;
            }
            case "chain" -> {
                // lightning that jumps from target to target
                LivingEntity current = target(p, range);
                if (current == null) {
                    return false;
                }
                Set<UUID> hit = new HashSet<>();
                Location last = eye.clone();
                int jumps = Math.max(1, a.getInt("jumps", 3));
                double jumpRange = a.getDouble("jump-range", 6);
                double dmg = damage;
                org.bukkit.Color c = colorOf(a, "lightning");
                for (int i = 0; i <= jumps && current != null; i++) {
                    hit.add(current.getUniqueId());
                    Location at = current.getLocation().add(0, 1, 0);
                    fx.beam(last, at, c, 1.3f, 0.25);
                    fx.burst(at, Particle.ELECTRIC_SPARK, 20, 0.4, 0.1);
                    strike(p, current, a, dmg);
                    last = at;
                    dmg *= a.getDouble("falloff", 0.8);
                    LivingEntity next = null;
                    double best = jumpRange * jumpRange;
                    for (Entity e : current.getNearbyEntities(jumpRange, jumpRange, jumpRange)) {
                        if (e instanceof LivingEntity le && !hit.contains(e.getUniqueId()) && canHurt(p, e)) {
                            double ds = e.getLocation().distanceSquared(current.getLocation());
                            if (ds < best) {
                                best = ds;
                                next = le;
                            }
                        }
                    }
                    current = next;
                }
                sound(a, "entity.lightning_bolt.thunder", p.getLocation());
                return true;
            }
            case "slash" -> {
                Set<UUID> hit = new HashSet<>();
                Location point = eye.clone();
                Vector step = dir.clone().normalize();
                for (int i = 0; i < (int) range; i++) {
                    point.add(step);
                    if (point.getBlock().getType().isSolid()) {
                        break;
                    }
                    if (i % 2 == 0) {
                        particles(a, "SWEEP_ATTACK", point, 1, 0);
                    }
                    if (i % 3 == 0) {
                        fx.crescent(point, step, 1.6, colorOf(a, type));
                    }
                    for (Entity e : p.getWorld().getNearbyEntities(point, 1.4, 1.4, 1.4)) {
                        if (e instanceof LivingEntity le && hit.add(e.getUniqueId())) {
                            strike(p, le, a, damage);
                        }
                    }
                }
                sound(a, "entity.player.attack.sweep", p.getLocation());
                selfEffects(p, a);
                return true;
            }
            default -> {
                module.logger().warning("[items] Unknown ability type '" + type + "' in " + a.getCurrentPath());
                return false;
            }
        }
    }

    private void selfEffects(Player p, ConfigurationSection a) {
        int ticks = (int) (a.getDouble("duration", 10) * 20);
        for (PotionEffect e : Util.parseEffects(a.getStringList("self-effects"), ticks)) {
            p.addPotionEffect(e);
        }
    }

    private void line(ConfigurationSection a, String fallback, Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector());
        double len = v.length();
        if (len < 0.5 || len > 64) {
            return;
        }
        v.normalize();
        Location point = from.clone();
        for (double d = 0; d < len; d += 0.75) {
            point.add(v.clone().multiply(0.75));
            particles(a, fallback, point, 1, 0);
        }
    }

    private void ring(ConfigurationSection a, String fallback, Location center, double radius) {
        int points = (int) Math.max(12, Math.min(72, radius * 8));
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            for (double r = radius; r > 0.9; r -= radius / 2.0) {
                particles(a, fallback, center.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r), 2, 0.1);
            }
        }
    }

    /** Called from a projectile hit: area blast of a fireball-type ability. */
    void blast(Player shooter, Location at, ConfigurationSection a) {
        double scale = module.fruits() != null && module.fruits().isFruitMove(a) ? module.fruits().damageScale(shooter.getUniqueId()) : 1;
        area(shooter, at, a.getDouble("radius", 3), a.getDouble("damage", 6) * scale, a);
        particles(a, "FLAME", at, 60, a.getDouble("radius", 3) / 2.5);
        fx.burst(at, Particle.EXPLOSION, 2, 0.4, 0);
        fx.wave(at.clone().add(0, 0.3, 0), a.getDouble("radius", 3) + 0.5, 6, colorOf(a, "blast"), Particle.FLAME, false);
        fx.burst(at, Particle.LAVA, 8, 0.6, 0);
        Util.sound(at, a.getString("hit-sound", "entity.generic.explode"), 1f, 1.2f);
    }

    /** Every tick: dashing players damage what they pass through. */
    void fastTick() {
        if (dashes.isEmpty()) {
            return;
        }
        // iterate a copy: damage below fires events that may end dashes (death, quit, forget())
        for (Dash d : new ArrayList<>(dashes)) {
            Player p = module.plugin().getServer().getPlayer(d.player);
            if (p == null || p.isDead() || d.ticksLeft-- <= 0) {
                dashes.remove(d);
                continue;
            }
            double damage = d.damage;
            for (Entity e : p.getNearbyEntities(1.6, 1.6, 1.6)) {
                if (e instanceof LivingEntity le && d.hit.add(e.getUniqueId())) {
                    strike(p, le, d.cfg, damage);
                }
            }
            particles(d.cfg, "CRIT", p.getLocation().add(0, 1, 0), 4, 0.3);
            fx.dust(p.getLocation().add(0, 1, 0), colorOf(d.cfg, "dash"), 1.6f, 6, 0.3);
        }
    }

    void forget(UUID player) {
        invisible.remove(player);
        dashes.removeIf(d -> d.player.equals(player));
    }
}
