package dev.aquatic.events;

import dev.aquatic.core.AquaticModule;

import java.util.List;

/**
 * Abyss fruits as their own module, so they can be switched off without touching the other custom items:
 * "fruits: false" in config.yml or /aquatic disable fruits. While off, nobody can eat a fruit, fruit powers
 * and passives stop, the bonus hearts go away and treasure no longer drops random fruits. Who ate what and
 * the mastery are kept, so turning it back on restores everything. The fruit settings stay in items.yml.
 */
public final class FruitsModule extends AquaticModule {

    @Override
    public String id() {
        return "fruits";
    }

    @Override
    public List<String> requires() {
        return List.of("items");
    }

    @Override
    protected void onEnable() {
        set(true);
    }

    @Override
    protected void onDisable() {
        set(false);
    }

    private void set(boolean on) {
        if (plugin().module("items") instanceof ItemsModule items && items.isEnabled() && items.fruits() != null) {
            items.fruits().setEnabled(on);
        }
    }
}
