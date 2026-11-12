package dev.aquatic.events;

import dev.aquatic.api.Crew;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.ItemService;
import dev.aquatic.core.AquaticModule;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Small helpers shared by the items, tournament and events modules. */
public final class Util {

    private Util() {
    }

    // ------------------------------------------------------------------ locations

    public static String locToString(Location l) {
        if (l == null || l.getWorld() == null) {
            return null;
        }
        return l.getWorld().getName() + ";" + round(l.getX()) + ";" + round(l.getY()) + ";" + round(l.getZ())
                + ";" + round(l.getYaw()) + ";" + round(l.getPitch());
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /** @return the location, or null when the text is invalid or the world is not loaded. */
    public static Location locFromString(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        String[] p = s.split(";");
        if (p.length < 4) {
            return null;
        }
        World w = Bukkit.getWorld(p[0]);
        if (w == null) {
            return null;
        }
        try {
            float yaw = p.length > 4 ? Float.parseFloat(p[4]) : 0f;
            float pitch = p.length > 5 ? Float.parseFloat(p[5]) : 0f;
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String coords(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    /** Horizontal distance, or Double.MAX_VALUE when the worlds differ. */
    public static double flatDistance(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------------ potion effects

    /** Parses "type:level[:seconds]" (level starts at 1), e.g. "speed:2:10". Null when the type is unknown. */
    public static PotionEffect parseEffect(String spec, int defaultTicks) {
        if (spec == null) {
            return null;
        }
        String[] p = spec.trim().toLowerCase(Locale.ROOT).split(":");
        PotionEffectType type;
        try {
            type = Registry.EFFECT.get(NamespacedKey.minecraft(p[0]));
        } catch (IllegalArgumentException e) {
            type = null;
        }
        if (type == null) {
            return null;
        }
        int level = 1;
        int ticks = defaultTicks;
        try {
            if (p.length > 1) {
                level = Math.max(1, Integer.parseInt(p[1]));
            }
            if (p.length > 2) {
                ticks = (int) (Double.parseDouble(p[2]) * 20);
            }
        } catch (NumberFormatException ignored) {
        }
        return new PotionEffect(type, ticks, level - 1, true, false, true);
    }

    public static List<PotionEffect> parseEffects(List<String> specs, int defaultTicks) {
        List<PotionEffect> out = new ArrayList<>();
        for (String s : specs) {
            PotionEffect e = parseEffect(s, defaultTicks);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ items

    /**
     * Parses "MATERIAL[:amount]" or "item:&lt;custom id&gt;[:amount]". Returns null when the material is
     * unknown, or when a custom item is requested while the items module is disabled.
     */
    public static ItemStack parseItem(AquaticModule module, String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] p = spec.trim().split(":");
        try {
            if (p[0].equalsIgnoreCase("item")) {
                if (p.length < 2) {
                    return null;
                }
                ItemService items = module.service(ItemService.class);
                if (items == null) {
                    return null;
                }
                int amount = p.length > 2 ? Integer.parseInt(p[2]) : 1;
                return items.createItem(p[1].toLowerCase(Locale.ROOT), Math.max(1, amount));
            }
            Material mat = Material.matchMaterial(p[0]);
            if (mat == null || !mat.isItem() || mat.isAir()) {
                return null;
            }
            int amount = p.length > 1 ? Integer.parseInt(p[1]) : 1;
            return new ItemStack(mat, Math.max(1, amount));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Adds the item to the inventory; whatever does not fit is dropped at the player's feet. */
    public static void give(Player player, ItemStack stack) {
        if (stack == null) {
            return;
        }
        for (ItemStack rest : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------------ sounds & effects

    public static void sound(Player player, String key, float volume, float pitch) {
        Sound s = sound(key, volume, pitch);
        if (s != null) {
            player.playSound(s);
        }
    }

    public static void sound(Location at, String key, float volume, float pitch) {
        Sound s = sound(key, volume, pitch);
        if (s != null && at.getWorld() != null) {
            at.getWorld().playSound(s, at.getX(), at.getY(), at.getZ());
        }
    }

    public static void soundAll(String key, float volume, float pitch) {
        Sound s = sound(key, volume, pitch);
        if (s != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(s);
            }
        }
    }

    @SuppressWarnings("PatternValidation")
    private static Sound sound(String key, float volume, float pitch) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return Sound.sound(Key.key(key.toLowerCase(Locale.ROOT)), Sound.Source.MASTER, volume, pitch);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void firework(Location at) {
        if (at.getWorld() == null) {
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Color[] colors = {Color.YELLOW, Color.ORANGE, Color.RED, Color.AQUA, Color.WHITE, Color.FUCHSIA};
        at.getWorld().spawn(at, Firework.class, fw -> {
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.values()[r.nextInt(FireworkEffect.Type.values().length)])
                    .withColor(colors[r.nextInt(colors.length)], colors[r.nextInt(colors.length)])
                    .withFade(Color.WHITE).trail(true).flicker(r.nextBoolean()).build());
            meta.setPower(1 + r.nextInt(2));
            fw.setFireworkMeta(meta);
        });
    }

    public static void broadcast(Component message) {
        Bukkit.getServer().sendMessage(message);
    }

    // ------------------------------------------------------------------ rewards

    /** Runs console commands, replacing {key} placeholders given as key/value pairs. */
    public static void runCommands(List<String> commands, String... placeholders) {
        for (String raw : commands) {
            String cmd = raw.startsWith("/") ? raw.substring(1) : raw;
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                cmd = cmd.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
            }
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException e) {
                Bukkit.getLogger().warning("[AquaticSMP] Reward command failed: " + cmd + " (" + e.getMessage() + ")");
            }
        }
    }

    public static String money(AquaticModule module, double amount) {
        EconomyService eco = module.service(EconomyService.class);
        return eco != null ? eco.format(amount) : dev.aquatic.util.Text.number(amount) + " Doubloons";
    }

    /**
     * Gives a reward section to one player. Understood keys: doubloons, items (list of item specs),
     * commands (console, {player}). "multiplier" scales the doubloons only.
     * Parts whose module is disabled are skipped silently.
     *
     * @return the doubloons actually paid
     */
    public static double rewardPlayer(AquaticModule module, ConfigurationSection rewards, Player player, double multiplier) {
        if (rewards == null || player == null) {
            return 0;
        }
        double paid = 0;
        double doubloons = rewards.getDouble("doubloons", 0) * multiplier;
        EconomyService eco = module.service(EconomyService.class);
        if (doubloons > 0 && eco != null) {
            eco.deposit(player.getUniqueId(), doubloons);
            paid = doubloons;
        }
        for (String spec : rewards.getStringList("items")) {
            give(player, parseItem(module, spec));
        }
        runCommands(rewards.getStringList("commands"), "player", player.getName());
        return paid;
    }

    /**
     * Rewards a crew: "crew-bank" doubloons into the bank and "reputation" once for the crew, and the
     * per-player part (see {@link #rewardPlayer}) for every online member.
     */
    public static void rewardCrew(AquaticModule module, ConfigurationSection rewards, Crew crew) {
        if (rewards == null || crew == null) {
            return;
        }
        double bank = rewards.getDouble("crew-bank", 0);
        if (bank > 0) {
            crew.depositBank(bank);
        }
        int rep = rewards.getInt("reputation", 0);
        if (rep != 0) {
            crew.addReputation(rep);
        }
        for (Player member : crew.onlineMembers()) {
            rewardPlayer(module, rewards, member, 1.0);
        }
    }
}
