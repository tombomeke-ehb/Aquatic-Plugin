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
        if (particle == Particle.EXPLOSION) {
            flash(at, Color.fromRGB(0xFFF1C1), 2.2f); // impacts get a bright flash
        }
    }

    /** Dust that fades from a lighter shade to the colour (looks like glowing energy). */
    void glow(Location at, Color color, float size, int amount, double spread) {
        World w = at.getWorld();
        int n = count(amount);
        if (w == null || n <= 0) {
            return;
        }
        Color light = Color.fromRGB(Math.min(255, color.getRed() + 90), Math.min(255, color.getGreen() + 90),
                Math.min(255, color.getBlue() + 90));
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, n, spread, spread, spread, 0,
                new Particle.DustTransition(light, color, size));
    }

    /** A flat ring of dust at "radius" around the centre. */
    void ring(Location center, double radius, Color color, float size) {
        int points = Math.max(8, count((int) Math.min(90, radius * 10)));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            glow(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius), color, size, 1, 0);
        }
    }

    // ------------------------------------------------------------------ animated 3D effects (resource pack)

    /** Display-entity effects need the resource pack (they use its effect models). */
    private boolean displays() {
        return quality() > 0 && module.config().getBoolean("resource-pack.enabled", false)
                && module.config().getBoolean("effects.animated", true);
    }

    /** A glowing effect model (vfx_slash, vfx_ring, vfx_flash) tinted in the given colour. */
    private org.bukkit.entity.ItemDisplay vfx(Location at, String model, Color color) {
        org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(org.bukkit.NamespacedKey.fromString("aquatic:" + model));
        org.bukkit.inventory.meta.components.CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setColors(java.util.List.of(color));
        meta.setCustomModelDataComponent(cmd);
        stack.setItemMeta(meta);
        return at.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setPersistent(false);
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            d.setShadowRadius(0);
        });
    }

    /** Sets the start pose, then moves smoothly to the end pose over "ticks" and removes it after "life". */
    private void animate(org.bukkit.entity.ItemDisplay d, org.bukkit.util.Transformation start,
                         org.bukkit.util.Transformation end, int ticks, int life) {
        d.setTransformation(start);
        module.runLater(() -> {
            if (d.isValid()) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(ticks);
                d.setTransformation(end);
            }
        }, 2L);
        module.runLater(d::remove, life);
    }

    private static org.bukkit.util.Transformation pose(org.joml.Vector3f move, org.joml.Quaternionf rot, float scale) {
        return new org.bukkit.util.Transformation(move, rot, new org.joml.Vector3f(scale, scale, scale), new org.joml.Quaternionf());
    }

    /** A bright star-burst that pops and vanishes (impacts). */
    void flash(Location at, Color color, float size) {
        if (!displays() || at.getWorld() == null) {
            return;
        }
        org.bukkit.entity.ItemDisplay d = vfx(at, "vfx_flash", color);
        d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        animate(d, pose(new org.joml.Vector3f(), new org.joml.Quaternionf(), 0.3f),
                pose(new org.joml.Vector3f(), new org.joml.Quaternionf().rotateZ(0.6f), size), 3, 6);
    }

    /** A glowing shockwave ring that sweeps over the ground. */
    void shockwave(Location center, double radius, int ticks, Color color) {
        if (!displays() || center.getWorld() == null) {
            return;
        }
        org.bukkit.entity.ItemDisplay d = vfx(center, "vfx_ring", color);
        org.joml.Quaternionf flat = new org.joml.Quaternionf().rotateX((float) Math.toRadians(90));
        animate(d, pose(new org.joml.Vector3f(), flat, 0.4f),
                pose(new org.joml.Vector3f(), new org.joml.Quaternionf(flat).rotateZ(1.2f), (float) (radius * 2.2)),
                Math.max(3, ticks), Math.max(3, ticks) + 3);
    }

    /** A glowing crescent that sweeps forward from the eyes (slashes). */
    void slash(Location eye, Vector dir, double reach, Color color) {
        if (!displays() || eye.getWorld() == null) {
            return;
        }
        Vector f = dir.clone().normalize();
        Location start = eye.clone().add(f.clone().multiply(1.2)).add(0, -0.2, 0);
        start.setYaw(0);
        start.setPitch(0);
        org.bukkit.entity.ItemDisplay d = vfx(start, "vfx_slash", color);
        org.joml.Quaternionf face = new org.joml.Quaternionf().rotateY((float) Math.toRadians(-eye.getYaw()))
                .rotateX((float) Math.toRadians(eye.getPitch())).rotateZ(-0.35f);
        Vector move = f.clone().multiply(Math.max(1, reach * 0.7));
        animate(d, pose(new org.joml.Vector3f(), face, 0.6f),
                pose(new org.joml.Vector3f((float) move.getX(), (float) move.getY(), (float) move.getZ()),
                        new org.joml.Quaternionf(face).rotateZ(0.7f), 3.2f), 5, 7);
    }

    /** A shockwave: a ring growing from 0 to maxRadius over "ticks" ticks (or shrinking when inward). */
    void wave(Location center, double maxRadius, int ticks, Color color, Particle extra, boolean inward) {
        if (quality() <= 0) {
            return;
        }
        int total = Math.max(1, ticks);
        if (!inward) {
            shockwave(center, maxRadius, total, color);
            org.bukkit.block.Block ground = center.clone().add(0, -0.6, 0).getBlock();
            if (ground.getType().isSolid() && center.getWorld() != null && count(18) > 0) {
                center.getWorld().spawnParticle(Particle.BLOCK, center, count(18), maxRadius * 0.35, 0.1, maxRadius * 0.35, 0.15,
                        ground.getBlockData()); // debris kicked up from the ground
            }
        }
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
        Vector unit = v.clone().normalize();
        v.normalize().multiply(spacing);
        Location p = from.clone();
        boolean rich = size >= 1.5f && quality() >= 0.75;
        Vector side = unit.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1e-4) {
            side = new Vector(1, 0, 0);
        }
        side.normalize();
        Vector up = side.clone().crossProduct(unit).normalize();
        int i = 0;
        for (double d = 0; d < len; d += spacing, i++) {
            glow(p, color, size, 1, 0.02);
            if (rich) { // two strands spiralling around a glowing core
                double a = d * 3.2;
                for (int strand = 0; strand < 2; strand++) {
                    double aa = a + strand * Math.PI;
                    Vector off = side.clone().multiply(Math.cos(aa) * 0.28).add(up.clone().multiply(Math.sin(aa) * 0.28));
                    dust(p.clone().add(off), color, size * 0.55f, 1, 0);
                }
                if (i % 4 == 0) {
                    burst(p, Particle.END_ROD, 1, 0.02, 0);
                }
            }
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
            if ((i == 4 || i == 7) && quality() >= 0.75) { // a short side branch
                Location b = p.clone();
                for (int k = 0; k < 3; k++) {
                    Location nb = b.clone().add(r.nextDouble(-1.2, 1.2), -0.8, r.nextDouble(-1.2, 1.2));
                    beam(b, nb, color, 0.9f, 0.3);
                    b = nb;
                }
            }
            p = next;
        }
        flash(target.clone().add(0, 0.5, 0), color, 2.6f);
        burst(target, Particle.ELECTRIC_SPARK, 25, 0.6, 0.2);
    }
}
