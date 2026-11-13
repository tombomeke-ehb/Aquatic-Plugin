package dev.aquatic.api;

import org.bukkit.inventory.ItemStack;

import java.util.Set;

/** Crates and their keys. Provided by the "crates" module. */
public interface CrateService {

    /** Ids of the crate types (modules/crates.yml). */
    Set<String> crateTypes();

    /** @return keys for a crate type, or null when the type is unknown. */
    ItemStack key(String crateType, int amount);
}
