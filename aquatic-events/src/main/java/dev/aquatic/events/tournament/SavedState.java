package dev.aquatic.events.tournament;

import dev.aquatic.events.Util;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

/** Everything taken from a player for a match, restored afterwards (persisted for quitters / restarts). */
public final class SavedState {

    public Location location;
    public GameMode gameMode = GameMode.SURVIVAL;
    public double health = 20;
    public int food = 20;
    public float saturation = 5;
    /** Only stored when a kit replaced the inventory. */
    public ItemStack[] inventory;
    public int level;
    public float exp;

    public static SavedState capture(Player p, boolean withInventory) {
        SavedState s = new SavedState();
        s.location = p.getLocation();
        s.gameMode = p.getGameMode();
        s.health = p.getHealth();
        s.food = p.getFoodLevel();
        s.saturation = p.getSaturation();
        s.level = p.getLevel();
        s.exp = p.getExp();
        if (withInventory) {
            ItemStack[] contents = p.getInventory().getContents();
            s.inventory = new ItemStack[contents.length];
            for (int i = 0; i < contents.length; i++) {
                s.inventory[i] = contents[i] == null ? null : contents[i].clone();
            }
        }
        return s;
    }

    /** @param fallback used when the stored location's world is gone */
    public void restore(Player p, Location fallback) {
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        p.setFireTicks(0);
        p.setFreezeTicks(0);
        p.setGameMode(gameMode);
        if (inventory != null) {
            p.getInventory().clear();
            p.getInventory().setContents(inventory);
            p.setLevel(level);
            p.setExp(exp);
        }
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20 : max.getValue();
        p.setHealth(Math.max(1, Math.min(cap, health)));
        p.setFoodLevel(food);
        p.setSaturation(saturation);
        p.setFallDistance(0);
        Location to = location != null && location.getWorld() != null ? location : fallback;
        if (to != null) {
            p.teleport(to);
        }
    }

    public void save(ConfigurationSection s) {
        s.set("location", Util.locToString(location));
        s.set("gamemode", gameMode.name());
        s.set("health", health);
        s.set("food", food);
        s.set("saturation", saturation);
        s.set("level", level);
        s.set("exp", exp);
        if (inventory != null) {
            List<ItemStack> list = new ArrayList<>();
            for (ItemStack i : inventory) {
                list.add(i);
            }
            s.set("inventory", list);
        }
    }

    @SuppressWarnings("unchecked")
    public static SavedState load(ConfigurationSection s) {
        SavedState st = new SavedState();
        st.location = Util.locFromString(s.getString("location"));
        try {
            st.gameMode = GameMode.valueOf(s.getString("gamemode", "SURVIVAL"));
        } catch (IllegalArgumentException e) {
            st.gameMode = GameMode.SURVIVAL;
        }
        st.health = s.getDouble("health", 20);
        st.food = s.getInt("food", 20);
        st.saturation = (float) s.getDouble("saturation", 5);
        st.level = s.getInt("level");
        st.exp = (float) s.getDouble("exp");
        if (s.isList("inventory")) {
            List<?> list = s.getList("inventory");
            st.inventory = new ItemStack[list.size()];
            for (int i = 0; i < list.size(); i++) {
                st.inventory[i] = list.get(i) instanceof ItemStack is ? is : null;
            }
        }
        return st;
    }
}
