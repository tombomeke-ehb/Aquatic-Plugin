package dev.aquatic.events.items;

import dev.aquatic.events.ItemsModule;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * Small particle toolkit for abilities: coloured dust, animated rings, spirals, beams, crescents and
 * bursts. Particle amounts are multiplied by "effects.quality" in items.yml (0 = no extra effects,
 * 0.5 = light, 1 = normal, 1.5 = rich).
 */
final class Fx {

    private final ItemsModule module;

    Fx(ItemsModule module) {
        this.module = module;
    }

    double quality() {
        return Math.max(0, Math.min(2, module.config().getDouble("effects.quality", 1.0)));
    }

    private int count(int base) {
        return Math.max(0, (int) Math.round(base * quality()));
    }

    /** "#ff8800" / "ff8800" / "255,136,0" -> colour, else the fallback. */
    static Color color(String raw, Color fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (s.contains(",")) {
                String[] p = s.split(",");
                return Color.fromRGB(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
            }
            return Color.fromRGB(Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    void dust(Location at, Color color, float size, int amount, double spread) {
        World w = at.getWorld();
        int n = count(amount);
        if (w == null || n <= 0) {
            return;
        }
        w.spawnParticle(Particle.DUST, at, n, spread, spread, spread, 0, new Particle.DustOptions(color, size));
    }

    void burst(Location at, Particle particle, int amount, double spread, double speed) {
        World w = at.getWorld();
        int n = count(amount);
        if (w == null || n <= 0 || particle.getDataType() != Void.class) {
            return;
        }
        w.spawnParticle(particle, at, n, spread, spread, spread, speed);
    }

    /** A flat ring of dust at "radius" around the centre. */
    void ring(Location center, double radius, Color color, float size) {
        int points = Math.max(8, count((int) Math.min(90, radius * 10)));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            dust(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius), color, size, 1, 0);
        }
    }

    /** A shockwave: a ring growing from 0 to maxRadius over "ticks" ticks (or shrinking when inward). */
    void wave(Location center, double maxRadius, int ticks, Color color, Particle extra, boolean inward) {
        if (quality() <= 0) {
            return;
        }
        int total = Math.max(1, ticks);
        for (int i = 1; i <= total; i++) {
            int step = i;
            module.runLater(() -> {
                double f = step / (double) total;
                double r = maxRadius * (inward ? 1 - f + 0.1 : f);
                ring(center, r, color, 1.6f);
                if (extra != null && step % 2 == 0) {
                    int pts = Math.max(6, count((int) (r * 3)));
                    for (int p = 0; p < pts; p++) {
                        double a = 2 * Math.PI * p / pts;
                        burst(center.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), extra, 1, 0.05, 0.01);
                    }
                }
            }, step);
        }
    }

    /** A helix around an entity for "ticks" ticks (auras, transformations). */
    void spiral(Entity entity, int ticks, Color color) {
        if (quality() <= 0) {
            return;
        }
        for (int i = 0; i < ticks; i++) {
            int step = i;
            module.runLater(() -> {
                if (!entity.isValid()) {
                    return;
                }
                Location base = entity.getLocation();
                for (int s = 0; s < 2; s++) {
                    double a = step * 0.45 + s * Math.PI;
                    double y = (step % 20) / 20.0 * entity.getHeight();
                    dust(base.clone().add(Math.cos(a) * 0.8, y, Math.sin(a) * 0.8), color, 1.3f, 1, 0);
                }
            }, step);
        }
    }

    /** A straight beam of dust between two points. */
    void beam(Location from, Location to, Color color, float size, double spacing) {
        Vector v = to.toVector().subtract(from.toVector());
        double len = v.length();
        if (len < 0.1 || len > 96) {
            return;
        }
        v.normalize().multiply(spacing);
        Location p = from.clone();
        for (double d = 0; d < len; d += spacing) {
            dust(p, color, size, 1, 0.02);
            p.add(v);
        }
    }

    /** A sword crescent in front of "eye", facing "dir". */
    void crescent(Location eye, Vector dir, double radius, Color color) {
        Vector forward = dir.clone().setY(0);
        if (forward.lengthSquared() < 1e-4) {
            forward = new Vector(1, 0, 0);
        }
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        int points = Math.max(10, count(26));
        for (int i = 0; i <= points; i++) {
            double a = -Math.PI / 2.4 + (Math.PI / 1.2) * i / points;
            Vector off = forward.clone().multiply(Math.cos(a) * radius).add(side.clone().multiply(Math.sin(a) * radius));
            dust(eye.clone().add(off).add(0, -0.3 + 0.25 * Math.sin(a), 0), color, 1.4f, 1, 0);
        }
    }

    /** Lightning-style jagged bolt from above down to the target point. */
    void bolt(Location target, Color color) {
        Location p = target.clone().add(0, 12, 0);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 12; i++) {
            Location next = p.clone().add(r.nextDouble(-0.8, 0.8), -1, r.nextDouble(-0.8, 0.8));
            if (i == 11) {
                next = target.clone();
            }
            beam(p, next, color, 1.2f, 0.25);
            p = next;
        }
    }
}
