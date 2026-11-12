package dev.aquatic.api;

import org.bukkit.inventory.ItemStack;

import java.util.Set;

/** Custom pirate items (devil fruits, weapons, tools). Provided by the "items" module. */
public interface ItemService {

    Set<String> itemIds();

    /** @return a new stack of the custom item, or null when the id is unknown. */
    ItemStack createItem(String id, int amount);

    /** @return the custom item id of the stack, or null when it is a normal item. */
    String idOf(ItemStack stack);
}
