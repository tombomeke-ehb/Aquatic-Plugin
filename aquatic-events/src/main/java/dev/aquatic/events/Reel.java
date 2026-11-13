package dev.aquatic.events;

import dev.aquatic.core.AquaticModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A spinning reel in a chest menu (crates, the wheel of fortune): items slide past a pointer, slow down and
 * stop on the winner in the middle. Closing the menu early skips to the result, so nobody loses a prize.
 */
final class Reel implements InventoryHolder {

    private static final int MIDDLE = 13;

    final AquaticModule owner;
    private final Player player;
    private final Inventory inventory;
    private final List<ItemStack> strip = new ArrayList<>();
    private final int steps;
    private final Runnable onDone;
    private int step;
    private boolean done;

    private Reel(AquaticModule owner, Player player, Component title, int steps, Runnable onDone) {
        this.owner = owner;
        this.player = player;
        this.steps = steps;
        this.onDone = onDone;
        this.inventory = Bukkit.createInventory(this, 27, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Spins and calls onDone once at the end (or when the menu is closed early).
     *
     * @param filler random items that pass by
     * @param winner the item it stops on
     */
    static Reel spin(AquaticModule owner, Player player, Component title, Supplier<ItemStack> filler, ItemStack winner,
                     int steps, Runnable onDone) {
        Reel r = new Reel(owner, player, title, Math.max(8, steps), onDone);
        for (int i = 0; i < r.steps + 9; i++) {
            r.strip.add(i == r.steps + 4 ? winner : filler.get());
        }
        ItemStack frame = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) {
            r.inventory.setItem(i, frame);
        }
        r.inventory.setItem(4, pane(Material.LIME_STAINED_GLASS_PANE));
        r.inventory.setItem(22, pane(Material.LIME_STAINED_GLASS_PANE));
        r.draw();
        player.openInventory(r.inventory);
        r.next();
        return r;
    }

    static ItemStack pane(Material m) {
        return dev.aquatic.util.Menus.filler(m);
    }

    private void draw() {
        for (int i = 0; i < 9; i++) {
            inventory.setItem(9 + i, strip.get(step + i));
        }
    }

    private void next() {
        if (done) {
            return;
        }
        if (step >= steps) {
            finish();
            return;
        }
        step++;
        draw();
        float progress = step / (float) steps;
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.8f + progress);
        // fast at first, slowing down towards the end
        long delay = 1 + Math.round(Math.pow(progress, 3) * 9);
        owner.runLater(this::next, delay);
    }

    /** Stops on the winner and pays out (once). */
    void finish() {
        if (done) {
            return;
        }
        done = true;
        step = steps;
        draw();
        inventory.setItem(4, pane(Material.YELLOW_STAINED_GLASS_PANE));
        inventory.setItem(22, pane(Material.YELLOW_STAINED_GLASS_PANE));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        onDone.run();
    }

    boolean done() {
        return done;
    }

    static boolean isReel(Inventory inv) {
        return inv != null && inv.getHolder() instanceof Reel;
    }

    static int middle() {
        return MIDDLE;
    }
}
