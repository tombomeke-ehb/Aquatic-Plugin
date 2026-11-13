package dev.aquatic.events.items;

import dev.aquatic.events.ItemsModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shows players how to craft the custom items: every custom recipe is unlocked in the vanilla recipe book
 * (green book in the crafting table), and /recipes opens a menu with all of them and their 3x3 layout.
 */
public final class RecipeBook implements Listener {

    /** Marks our menus; page = list page, recipe = shown recipe key (null on the list). */
    private static final class Menu implements InventoryHolder {
        final int page;
        final NamespacedKey recipe;
        final List<NamespacedKey> keys;
        Inventory inventory;

        Menu(int page, NamespacedKey recipe, List<NamespacedKey> keys) {
            this.page = page;
            this.recipe = recipe;
            this.keys = keys;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private final ItemsModule module;

    public RecipeBook(ItemsModule module) {
        this.module = module;
    }

    private List<NamespacedKey> keys() {
        List<NamespacedKey> out = new ArrayList<>();
        for (NamespacedKey key : module.recipeKeys()) {
            if (Bukkit.getRecipe(key) instanceof ShapedRecipe) {
                out.add(key);
            }
        }
        return out;
    }

    /** Puts every custom recipe in the player's recipe book. */
    public void unlock(Player p) {
        if (module.config().getBoolean("recipes.unlock-in-recipe-book", true)) {
            p.discoverRecipes(keys());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        unlock(event.getPlayer());
    }

    // ------------------------------------------------------------------ menus

    public void openList(Player p, int page) {
        List<NamespacedKey> keys = keys();
        int pages = Math.max(1, (keys.size() + 44) / 45);
        page = Math.max(0, Math.min(pages - 1, page));
        Menu menu = new Menu(page, null, keys);
        Inventory inv = Bukkit.createInventory(menu, 54, module.rawMsg("recipes-title", "page", page + 1, "pages", pages));
        menu.inventory = inv;
        for (int i = 0; i < 45 && page * 45 + i < keys.size(); i++) {
            Recipe r = Bukkit.getRecipe(keys.get(page * 45 + i));
            if (r != null) {
                ItemStack icon = r.getResult().clone();
                ItemMeta meta = icon.getItemMeta();
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(Component.empty());
                lore.add(module.rawMsg("recipes-click"));
                meta.lore(lore);
                icon.setItemMeta(meta);
                inv.setItem(i, icon);
            }
        }
        if (page > 0) {
            inv.setItem(45, button(Material.ARROW, module.rawMsg("recipes-previous")));
        }
        if (page < pages - 1) {
            inv.setItem(53, button(Material.ARROW, module.rawMsg("recipes-next")));
        }
        inv.setItem(49, button(Material.KNOWLEDGE_BOOK, module.rawMsg("recipes-help")));
        p.openInventory(inv);
    }

    private void openRecipe(Player p, NamespacedKey key, Menu from) {
        if (!(Bukkit.getRecipe(key) instanceof ShapedRecipe recipe)) {
            return;
        }
        Menu menu = new Menu(from.page, key, from.keys);
        Inventory inv = Bukkit.createInventory(menu, 45, module.rawMsg("recipes-recipe-title"));
        menu.inventory = inv;
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
        for (int i = 0; i < 45; i++) {
            inv.setItem(i, pane);
        }
        for (int s : GRID) {
            inv.setItem(s, null);
        }
        String[] shape = recipe.getShape();
        Map<Character, RecipeChoice> choices = recipe.getChoiceMap();
        for (int row = 0; row < shape.length && row < 3; row++) {
            for (int col = 0; col < shape[row].length() && col < 3; col++) {
                RecipeChoice choice = choices.get(shape[row].charAt(col));
                ItemStack stack = null;
                if (choice instanceof RecipeChoice.ExactChoice exact && !exact.getChoices().isEmpty()) {
                    stack = exact.getChoices().get(0).clone();
                } else if (choice instanceof RecipeChoice.MaterialChoice mat && !mat.getChoices().isEmpty()) {
                    stack = new ItemStack(mat.getChoices().get(0));
                }
                inv.setItem(GRID[row * 3 + col], stack);
            }
        }
        inv.setItem(23, button(Material.CRAFTING_TABLE, module.rawMsg("recipes-arrow")));
        inv.setItem(25, recipe.getResult().clone());
        inv.setItem(36, button(Material.ARROW, module.rawMsg("recipes-back")));
        p.openInventory(inv);
    }

    private static ItemStack button(Material m, Component name) {
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                net.kyori.adventure.text.format.TextDecoration.State.FALSE));
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu) || !(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (menu.recipe != null) {
            if (slot == 36) {
                openList(p, menu.page);
            }
            return;
        }
        if (slot == 45) {
            openList(p, menu.page - 1);
        } else if (slot == 53) {
            openList(p, menu.page + 1);
        } else if (slot < 45 && menu.page * 45 + slot < menu.keys.size()) {
            openRecipe(p, menu.keys.get(menu.page * 45 + slot), menu);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @SuppressWarnings("unused")
    private static Component plain(String s) {
        return Text.mm(s);
    }
}
