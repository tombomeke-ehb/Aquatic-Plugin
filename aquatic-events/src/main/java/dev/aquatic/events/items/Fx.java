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

    /** A flat ring of dust at "radius" around the centre (only without the resource pack). */
    private void dustRing(Location center, double radius, Color color, float size) {
        int points = Math.max(8, count((int) Math.min(40, radius * 5)));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            glow(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius), color, size, 1, 0);
        }
    }

    /** Particles thrown outwards from a point: every one flies its own way at "speed". */
    void spray(Location at, Particle particle, int amount, double speed) {
        World w = at.getWorld();
        int n = count(amount);
        if (w == null || n <= 0 || particle.getDataType() != Void.class) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double u = Math.random() * 2 - 1, t = Math.random() * Math.PI * 2, r = Math.sqrt(1 - u * u);
            w.spawnParticle(particle, at, 0, r * Math.cos(t), u * 0.6 + 0.15, r * Math.sin(t), speed);
        }
    }

    // ------------------------------------------------------------------ sprite effects (resource pack)
    // Hand-drawn frames (resourcepack/pixelart.py): slash 6, burst 5, ring 6, spark 4, beam 3, bolt 3, rune 2,
    // flame 6, shard 5, puff 6, splash 5, swirl 6, crack 4, claw 5, shield 4, lines 4.
    // Every frame is the model "aquatic:vfx_<name>_<n>": a layer tinted in the effect's colour plus a white core.

    /** Sprite effects need the resource pack. */
    private boolean displays() {
        return quality() > 0 && module.config().getBoolean("resource-pack.enabled", false)
                && module.config().getBoolean("effects.animated", true);
    }

    private static org.bukkit.inventory.ItemStack frame(String sprite, int n, Color color) {
        org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(org.bukkit.NamespacedKey.fromString("aquatic:vfx_" + sprite + "_" + n));
        org.bukkit.inventory.meta.components.CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setColors(java.util.List.of(color));
        meta.setCustomModelDataComponent(cmd);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Shows a sprite and plays its frames (order = the frame numbers to show, one every "tpf" ticks), then removes
     * it. The display starts in pose "start" and glides to "end" while it plays (end may be null).
     */
    private org.bukkit.entity.ItemDisplay play(Location at, String sprite, int[] order, int tpf, Color color,
                                               org.bukkit.entity.Display.Billboard billboard,
                                               org.bukkit.util.Transformation start, org.bukkit.util.Transformation end) {
        Location spot = at.clone();
        spot.setYaw(0);
        spot.setPitch(0);
        org.bukkit.entity.ItemDisplay d = spot.getWorld().spawn(spot, org.bukkit.entity.ItemDisplay.class, e -> {
            e.setItemStack(frame(sprite, order[0], color));
            e.setPersistent(false);
            e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            e.setShadowRadius(0);
            e.setBillboard(billboard);
            e.setTransformation(start);
        });
        int life = order.length * tpf;
        for (int i = 1; i < order.length; i++) {
            int n = order[i];
            module.runLater(() -> {
                if (d.isValid()) {
                    d.setItemStack(frame(sprite, n, color));
                }
            }, (long) i * tpf);
        }
        if (end != null) {
            module.runLater(() -> {
                if (d.isValid()) {
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(Math.max(1, life - 2));
                    d.setTransformation(end);
                }
            }, 2L);
        }
        module.runLater(d::remove, life);
        return d;
    }

    private static int[] upTo(int frames) {
        int[] order = new int[frames];
        for (int i = 0; i < frames; i++) {
            order[i] = i;
        }
        return order;
    }

    private static org.bukkit.util.Transformation pose(org.joml.Vector3f move, org.joml.Quaternionf rot, float scale) {
        return pose(move, rot, scale, scale, scale);
    }

    private static org.bukkit.util.Transformation pose(org.joml.Vector3f move, org.joml.Quaternionf rot, float sx, float sy, float sz) {
        return new org.bukkit.util.Transformation(move, rot, new org.joml.Vector3f(sx, sy, sz), new org.joml.Quaternionf());
    }

    private static final org.bukkit.entity.Display.Billboard FIXED = org.bukkit.entity.Display.Billboard.FIXED;
    private static final org.bukkit.entity.Display.Billboard FACING = org.bukkit.entity.Display.Billboard.CENTER;

    /** A twinkle: a four-pointed star that opens and closes (hits, pick-ups, casts). */
    void flash(Location at, Color color, float size) {
        if (!displays() || at.getWorld() == null) {
            return;
        }
        float s = Math.max(0.5f, size * 1.5f);
        play(at, "spark", upTo(4), 2, color, FACING, pose(new org.joml.Vector3f(), new org.joml.Quaternionf(), s * 0.7f),
                pose(new org.joml.Vector3f(), new org.joml.Quaternionf(), s));
    }

    /** An impact star: a white pop with spikes shooting out. */
    void impact(Location at, Color color, float size) {
        if (!displays() || at.getWorld() == null) {
            return;
        }
        play(at, "burst", upTo(5), 2, color, FACING, pose(new org.joml.Vector3f(), new org.joml.Quaternionf(), size * 0.8f),
                pose(new org.joml.Vector3f(), new org.joml.Quaternionf(), size * 1.25f));
    }

    private static final org.bukkit.entity.Display.Billboard UPRIGHT = org.bukkit.entity.Display.Billboard.VERTICAL;

    /** A magic circle on the ground that turns and grows while it shows (casting a move). */
    void rune(Location ground, Color color, double radius, int ticks) {
        if (!displays() || ground.getWorld() == null) {
            return;
        }
        org.joml.Quaternionf flat = new org.joml.Quaternionf().rotateX((float) Math.toRadians(90));
        int[] order = new int[Math.max(2, ticks / 4)];
        for (int i = 0; i < order.length; i++) {
            order[i] = i % 2;
        }
        float s = (float) (radius * 2);
        play(ground.clone().add(0, 0.07, 0), "rune", order, 4, color, FIXED, pose(new org.joml.Vector3f(), flat, s * 0.55f),
                pose(new org.joml.Vector3f(), new org.joml.Quaternionf(flat).rotateZ(2.4f), s));
    }

    /**
     * The sprite of an element where a move lands: fire = a tongue of flame, ice = shards, water = a splash,
     * smoke = a puff, void / wind = a vortex, beast = claw marks, anything else = an impact star.
     */
    void element(Location at, String style, Color color, float size) {
        if (!displays() || at.getWorld() == null) {
            return;
        }
        org.joml.Quaternionf none = new org.joml.Quaternionf();
        switch (style == null ? "" : style) {
            case "fire" -> play(at.clone().add(0, size * 0.55, 0), "flame", upTo(6), 2, color, UPRIGHT,
                    pose(new org.joml.Vector3f(), none, size * 1.5f), pose(new org.joml.Vector3f(0, size * 0.25f, 0), none, size * 1.9f));
            case "ice" -> play(at, "shard", upTo(5), 2, color, FACING, pose(new org.joml.Vector3f(), none, size * 1.2f),
                    pose(new org.joml.Vector3f(), none, size * 2.0f));
            case "water" -> play(at.clone().add(0, size * 0.4, 0), "splash", upTo(5), 2, color, UPRIGHT,
                    pose(new org.joml.Vector3f(), none, size * 1.8f), pose(new org.joml.Vector3f(), none, size * 2.2f));
            case "smoke" -> play(at, "puff", upTo(6), 2, color, FACING, pose(new org.joml.Vector3f(), none, size * 1.4f),
                    pose(new org.joml.Vector3f(0, size * 0.4f, 0), none, size * 2.3f));
            case "void", "wind" -> play(at, "swirl", upTo(6), 2, color, FACING, pose(new org.joml.Vector3f(), none, size * 1.9f),
                    pose(new org.joml.Vector3f(), none, size * 1.3f));
            case "beast" -> play(at, "claw", upTo(5), 2, color, FACING, pose(new org.joml.Vector3f(), new org.joml.Quaternionf().rotateZ(0.6f), size * 1.6f),
                    pose(new org.joml.Vector3f(), new org.joml.Quaternionf().rotateZ(0.6f), size * 2.0f));
            default -> impact(at, color, size);
        }
    }

    /** Glowing cracks that shoot over the ground from a heavy hit. */
    void crack(Location ground, Color color, double radius) {
        if (!displays() || ground.getWorld() == null) {
            return;
        }
        org.joml.Quaternionf flat = new org.joml.Quaternionf().rotateX((float) Math.toRadians(90))
                .rotateZ((float) (Math.random() * Math.PI * 2));
        float s = (float) (radius * 2);
        play(ground.clone().add(0, 0.05, 0), "crack", new int[]{0, 1, 2, 2, 2, 3}, 3, color, FIXED,
                pose(new org.joml.Vector3f(), flat, s * 0.9f), pose(new org.joml.Vector3f(), flat, s));
    }

    /** A vortex that keeps turning for "ticks" ticks: lying on the ground (flat) or facing the viewer. */
    void vortex(Location at, Color color, double radius, int ticks, boolean flat) {
        if (!displays() || at.getWorld() == null) {
            return;
        }
        int[] order = new int[Math.max(6, ticks / 2)];
        for (int i = 0; i < order.length; i++) {
            order[i] = i >= order.length - 2 ? 4 + (i - (order.length - 2)) : i % 4;   // spin, then fade out
        }
        org.joml.Quaternionf rot = flat ? new org.joml.Quaternionf().rotateX((float) Math.toRadians(90)) : new org.joml.Quaternionf();
        float s = (float) (radius * 2);
        play(flat ? at.clone().add(0, 0.08, 0) : at, "swirl", order, 2, color, flat ? FIXED : FACING,
                pose(new org.joml.Vector3f(), rot, s), pose(new org.joml.Vector3f(), new org.joml.Quaternionf(rot).rotateZ(-2.0f), s * 0.75f));
    }

    /** Three claw marks ripped through the air in front of the eyes. */
    void claw(Location eye, Vector dir, Color color, float size) {
        if (!displays() || eye.getWorld() == null) {
            return;
        }
        Vector f = dir.clone().normalize();
        org.joml.Quaternionf face = new org.joml.Quaternionf().rotateY((float) Math.toRadians(-eye.getYaw()))
                .rotateX((float) Math.toRadians(eye.getPitch())).rotateZ(0.7f);
        play(eye.clone().add(f.clone().multiply(1.8)).add(0, -0.2, 0), "claw", upTo(5), 2, color, FIXED,
                pose(new org.joml.Vector3f(), face, size * 0.85f), pose(new org.joml.Vector3f(), face, size));
    }

    /** A shield that flashes up in front of someone (blocks). */
    void shield(Location front, float yaw, Color color, float size) {
        if (!displays() || front.getWorld() == null) {
            return;
        }
        org.joml.Quaternionf face = new org.joml.Quaternionf().rotateY((float) Math.toRadians(-yaw));
        play(front, "shield", upTo(4), 2, color, FIXED, pose(new org.joml.Vector3f(), face, size * 0.8f),
                pose(new org.joml.Vector3f(), face, size));
    }

    /** Speed lines streaking past behind someone who shoots forward (dashes, leaps). */
    void speedLines(Location body, Vector dir, Color color) {
        if (!displays() || body.getWorld() == null || dir.lengthSquared() < 1e-6) {
            return;
        }
        Vector f = dir.clone().normalize();
        Location mid = body.clone().add(0, 1.0, 0).subtract(f.clone().multiply(0.6));
        org.joml.Quaternionf along = new org.joml.Quaternionf().rotationTo(1, 0, 0, (float) f.getX(), (float) f.getY(), (float) f.getZ());
        Vector move = f.clone().multiply(1.5);
        org.joml.Vector3f shift = new org.joml.Vector3f((float) move.getX(), (float) move.getY(), (float) move.getZ());
        for (int k = 0; k < 2; k++) {
            org.joml.Quaternionf rot = k == 0 ? along : new org.joml.Quaternionf(along).rotateX((float) Math.toRadians(90));
            play(mid, "lines", upTo(4), 2, color, FIXED, pose(new org.joml.Vector3f(), rot, 3.2f, 1.9f, 1f), pose(shift, rot, 3.2f, 1.9f, 1f));
        }
    }

    /** A shockwave ring that grows over the ground to "radius" in about "ticks" ticks. */
    void shockwave(Location center, double radius, int ticks, Color color) {
        if (center.getWorld() == null || quality() <= 0) {
            return;
        }
        if (!displays()) {
            dustRing(center, radius, color, 1.4f);
            return;
        }
        org.joml.Quaternionf flat = new org.joml.Quaternionf().rotateX((float) Math.toRadians(90));
        float s = (float) (radius * 2.1);
        int tpf = Math.max(1, Math.round(Math.max(6, ticks) / 6f));
        play(center.clone().add(0, 0.06, 0), "ring", upTo(6), tpf, color, FIXED,
                pose(new org.joml.Vector3f(), flat, s * 0.8f), pose(new org.joml.Vector3f(), flat, s));
    }

    /** A ring that closes in on the centre (pulls, implosions). */
    private void implode(Location center, double radius, int ticks, Color color) {
        if (!displays()) {
            dustRing(center, radius * 0.5, color, 1.4f);
            return;
        }
        org.joml.Quaternionf flat = new org.joml.Quaternionf().rotateX((float) Math.toRadians(90));
        float s = (float) (radius * 2.1);
        int tpf = Math.max(1, Math.round(Math.max(6, ticks) / 6f));
        play(center.clone().add(0, 0.06, 0), "ring", new int[]{5, 4, 3, 2, 1, 0}, tpf, color, FIXED,
                pose(new org.joml.Vector3f(), flat, s), pose(new org.joml.Vector3f(), flat, s * 0.8f));
    }

    /** A sword sweep in front of the eyes: a diagonal arc that shoots across and breaks up. */
    void slash(Location eye, Vector dir, double reach, Color color) {
        if (!displays() || eye.getWorld() == null) {
            return;
        }
        Vector f = dir.clone().normalize();
        Location at = eye.clone().add(f.clone().multiply(1.7)).add(0, -0.25, 0);
        boolean left = java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
        org.joml.Quaternionf face = new org.joml.Quaternionf().rotateY((float) Math.toRadians(-eye.getYaw()))
                .rotateX((float) Math.toRadians(eye.getPitch())).rotateZ(left ? 0.75f : -0.75f);
        float s = (float) Math.max(1.8, Math.min(4.2, reach * 1.05));
        Vector move = f.clone().multiply(0.5);
        play(at, "slash", upTo(6), 1, color, FIXED, pose(new org.joml.Vector3f(), face, s * 0.85f),
                pose(new org.joml.Vector3f((float) move.getX(), (float) move.getY(), (float) move.getZ()), face, s));
    }

    /** A wide flat sweep at chest height that bulges forward (ability swings, cleaves). */
    void crescent(Location eye, Vector dir, double radius, Color color) {
        if (eye.getWorld() == null || quality() <= 0) {
            return;
        }
        Vector forward = dir.clone().setY(0);
        if (forward.lengthSquared() < 1e-4) {
            forward = new Vector(1, 0, 0);
        }
        forward.normalize();
        if (!displays()) {
            Vector side = new Vector(-forward.getZ(), 0, forward.getX());
            int points = Math.max(8, count(16));
            for (int i = 0; i <= points; i++) {
                double a = -Math.PI / 2.4 + (Math.PI / 1.2) * i / points;
                Vector off = forward.clone().multiply(Math.cos(a) * radius).add(side.clone().multiply(Math.sin(a) * radius));
                dust(eye.clone().add(off).add(0, -0.3, 0), color, 1.4f, 1, 0);
            }
            return;
        }
        Location at = eye.clone().add(forward.clone().multiply(radius * 0.45)).add(0, -0.45, 0);
        org.joml.Quaternionf lie = new org.joml.Quaternionf().rotateY((float) Math.toRadians(-eye.getYaw()))
                .rotateX((float) Math.toRadians(78));
        float s = (float) (radius * 1.9);
        Vector move = forward.clone().multiply(radius * 0.35);
        play(at, "slash", upTo(6), 1, color, FIXED, pose(new org.joml.Vector3f(), lie, s * 0.8f),
                pose(new org.joml.Vector3f((float) move.getX(), 0, (float) move.getZ()), lie, s));
    }

    /** A shockwave over the ground with debris; "extra" particles are thrown along with it. */
    void wave(Location center, double maxRadius, int ticks, Color color, Particle extra, boolean inward) {
        if (quality() <= 0 || center.getWorld() == null) {
            return;
        }
        int total = Math.max(1, ticks);
        if (inward) {
            implode(center, maxRadius, total, color);
        } else {
            shockwave(center, maxRadius, total, color);
            org.bukkit.block.Block ground = center.clone().add(0, -0.6, 0).getBlock();
            if (ground.getType().isSolid() && count(14) > 0) {
                center.getWorld().spawnParticle(Particle.BLOCK, center, count(14), maxRadius * 0.35, 0.1, maxRadius * 0.35, 0.15,
                        ground.getBlockData()); // debris kicked up from the ground
            }
        }
        if (extra == null || extra.getDataType() != Void.class) {
            return;
        }
        int pts = Math.max(6, count((int) Math.min(28, maxRadius * 5)));
        double speed = Math.min(0.45, maxRadius / Math.max(4.0, total) * 0.5);
        for (int p = 0; p < pts; p++) {
            double a = 2 * Math.PI * p / pts;
            double r = inward ? maxRadius : 0.4;
            double sign = inward ? -1 : 1;
            center.getWorld().spawnParticle(extra, center.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r), 0,
                    Math.cos(a) * sign, 0.05, Math.sin(a) * sign, speed);
        }
    }

    /** Coloured swirls rising around an entity for "ticks" ticks (auras, transformations). */
    void spiral(Entity entity, int ticks, Color color) {
        if (quality() <= 0) {
            return;
        }
        for (int i = 0; i < ticks; i += 2) {
            int step = i;
            module.runLater(() -> {
                if (!entity.isValid() || entity.getWorld() == null) {
                    return;
                }
                Location base = entity.getLocation();
                double a = step * 0.5;
                double y = (step % 24) / 24.0 * entity.getHeight();
                for (int s = 0; s < 2; s++) {
                    Location at = base.clone().add(Math.cos(a + s * Math.PI) * 0.75, y, Math.sin(a + s * Math.PI) * 0.75);
                    entity.getWorld().spawnParticle(Particle.ENTITY_EFFECT, at, 1, 0, 0, 0, 0, color);
                }
            }, step);
        }
        module.runLater(() -> {
            if (entity.isValid()) {
                flash(entity.getLocation().add(0, entity.getHeight() + 0.3, 0), color, 0.8f);
            }
        }, ticks);
    }

    /** Two crossed sprites stretched from one point to another (so the beam shows from every side). */
    private void stretch(Location from, Location to, String sprite, int[] order, int tpf, Color color, float thickness) {
        Vector v = to.toVector().subtract(from.toVector());
        float len = (float) v.length();
        if (len < 0.05f) {
            return;
        }
        Location mid = from.clone().add(v.clone().multiply(0.5));
        org.joml.Quaternionf along = new org.joml.Quaternionf().rotationTo(1, 0, 0, (float) v.getX(), (float) v.getY(), (float) v.getZ());
        play(mid, sprite, order, tpf, color, FIXED, pose(new org.joml.Vector3f(), along, len, thickness, 1f), null);
        org.joml.Quaternionf crossed = new org.joml.Quaternionf(along).rotateX((float) Math.toRadians(90));
        play(mid, sprite, order, tpf, color, FIXED, pose(new org.joml.Vector3f(), crossed, len, thickness, 1f), null);
    }

    /** A beam of energy between two points. */
    void beam(Location from, Location to, Color color, float size, double spacing) {
        if (from.getWorld() == null || quality() <= 0) {
            return;
        }
        Vector v = to.toVector().subtract(from.toVector());
        double len = v.length();
        if (len < 0.1 || len > 96) {
            return;
        }
        if (displays()) {
            stretch(from, to, "beam", new int[]{0, 1, 2, 1}, 2, color, Math.max(0.5f, size * 0.7f));
            return;
        }
        v.normalize().multiply(Math.max(spacing, 0.4));
        Location p = from.clone();
        for (double d = 0; d < len; d += Math.max(spacing, 0.4)) {
            glow(p, color, size, 1, 0.02);
            p.add(v);
        }
    }

    /** A jagged arc of lightning from one point to another (chain lightning, shocks). */
    void zap(Location from, Location to, Color color) {
        if (from.getWorld() == null || quality() <= 0) {
            return;
        }
        if (displays()) {
            stretch(from, to, "bolt", new int[]{0, 1, 2, 1}, 2, color, 1.8f);
        } else {
            beam(from, to, color, 1.2f, 0.4);
        }
    }

    /** A lightning bolt from the sky down to the target. */
    void bolt(Location target, Color color) {
        if (target.getWorld() == null || quality() <= 0) {
            return;
        }
        Location top = target.clone().add(0, 13, 0);
        if (displays()) {
            stretch(top, target, "bolt", new int[]{0, 1, 2, 0}, 2, color, 4.5f);
            impact(target.clone().add(0, 0.6, 0), color, 2.6f);
        } else {
            beam(top, target, color, 1.4f, 0.4);
        }
        shockwave(target.clone().add(0, 0.1, 0), 2.6, 6, color);
        burst(target, Particle.ELECTRIC_SPARK, 25, 0.6, 0.2);
        spray(target.clone().add(0, 0.3, 0), Particle.END_ROD, 8, 0.25);
    }

    /** A column of light rising from the ground, with a ring at its foot and a twinkle on top. */
    void pillar(Location base, Color color, double height, int ticks) {
        if (quality() <= 0 || base.getWorld() == null) {
            return;
        }
        if (displays()) {
            float h = (float) height;
            int[] order = new int[Math.max(4, ticks / 2)];
            for (int i = 0; i < order.length; i++) {
                order[i] = i % 3;
            }
            for (int k = 0; k < 2; k++) {
                org.joml.Quaternionf up = new org.joml.Quaternionf().rotateZ((float) Math.toRadians(90));
                if (k == 1) {
                    up.rotateX((float) Math.toRadians(90));
                }
                play(base, "beam", order, 2, color, FIXED,
                        pose(new org.joml.Vector3f(0, 0.3f, 0), up, 0.6f, 1.3f, 1f),
                        pose(new org.joml.Vector3f(0, h / 2, 0), up, h, 0.9f, 1f));
            }
        }
        shockwave(base.clone().add(0, 0.1, 0), 1.6, 6, color);
        World w = base.getWorld();
        for (int i = 0; i < count(6); i++) {
            w.spawnParticle(Particle.END_ROD, base.clone().add((Math.random() - 0.5) * 0.8, 0.2, (Math.random() - 0.5) * 0.8),
                    0, 0, 1, 0, 0.08 + Math.random() * 0.12);
        }
        module.runLater(() -> flash(base.clone().add(0, height + 0.2, 0), color, 1.3f), Math.max(2, ticks - 2));
    }

    /** Rings of light circling an entity for a while (auras, power-ups). */
    void orbit(Entity entity, int ticks, Color color, int rings) {
        if (quality() <= 0) {
            return;
        }
        for (int i = 0; i < ticks; i += 2) {
            int step = i;
            module.runLater(() -> {
                if (!entity.isValid()) {
                    return;
                }
                Location base = entity.getLocation();
                for (int k = 0; k < rings; k++) {
                    double a = step * 0.35 + k * Math.PI * 2 / rings;
                    double y = entity.getHeight() * (0.25 + 0.5 * (k % 2)) + 0.15 * Math.sin(step * 0.3 + k);
                    glow(base.clone().add(Math.cos(a) * 0.9, y, Math.sin(a) * 0.9), color, 1.3f, 1, 0);
                }
            }, step);
        }
    }

    /** A burst in every direction: an impact star, a shockwave and sparks flying out. */
    void nova(Location center, Color color, double radius) {
        if (quality() <= 0 || center.getWorld() == null) {
            return;
        }
        impact(center, color, (float) Math.max(1.6, radius * 1.1));
        shockwave(center.clone().add(0, -0.4, 0), radius, 7, color);
        spray(center, Particle.END_ROD, 10, Math.min(0.5, 0.1 + radius * 0.06));
        spray(center, Particle.FIREWORK, 8, Math.min(0.4, 0.08 + radius * 0.05));
        if (!displays()) {
            dust(center, color, 1.6f, 14, radius * 0.4);
        }
    }

    /** A few twinkles popping one after another, with sparks drifting up. */
    void sparkles(Location at, Color color, int amount, double spread) {
        if (quality() <= 0 || at.getWorld() == null) {
            return;
        }
        int stars = Math.max(1, Math.min(4, count(amount) / 3));
        for (int i = 0; i < stars; i++) {
            Location p = at.clone().add((Math.random() - 0.5) * spread * 2, Math.random() * spread, (Math.random() - 0.5) * spread * 2);
            float size = 0.3f + (float) Math.random() * 0.3f;
            module.runLater(() -> flash(p, color, size), i * 2L);
        }
        if (!displays()) {
            glow(at, color, 0.9f, amount, spread);
        }
        World w = at.getWorld();
        for (int i = 0; i < Math.max(1, count(amount / 4)); i++) {
            w.spawnParticle(Particle.END_ROD, at.clone().add((Math.random() - 0.5) * spread, 0, (Math.random() - 0.5) * spread),
                    0, 0, 1, 0, 0.04);
        }
    }

    /** Sound waves: rings that grow out from a point one after another (horns, bells, calls). */
    void soundRings(Location center, Color color, int rings, double maxRadius) {
        if (quality() <= 0) {
            return;
        }
        for (int k = 0; k < rings; k++) {
            int n = k;
            module.runLater(() -> shockwave(center, maxRadius * (n + 1) / rings, 8, color), n * 5L);
        }
    }

    /** Chain links between two points (shackles). */
    void chain(Location from, Location to) {
        if (quality() <= 0 || from.getWorld() == null) {
            return;
        }
        Vector v = to.toVector().subtract(from.toVector());
        double len = v.length();
        if (len < 0.1) {
            return;
        }
        v.normalize();
        for (double d = 0; d <= len; d += 0.25) {
            Location at = from.clone().add(v.clone().multiply(d));
            boolean link = ((int) (d * 4)) % 2 == 0;
            dust(at, link ? Color.fromRGB(0x5B6470) : Color.fromRGB(0x9AA3AD), link ? 0.9f : 0.6f, 1, 0);
        }
    }
}
