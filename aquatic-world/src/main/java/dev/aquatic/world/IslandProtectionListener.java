package dev.aquatic.world;

import dev.aquatic.api.IslandStatus;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Build/PvP/explosion/fire protection for occupied and neutral islands. */
final class IslandProtectionListener implements Listener {

    private final IslandsModule module;

    IslandProtectionListener(IslandsModule module) {
        this.module = module;
    }

    /** @return true when the action was denied (and the player told why). */
    private boolean denied(Player player, Block block, IslandsModule.Action action) {
        IslandImpl is = module.at(block);
        if (is == null) {
            return false;
        }
        String reason = module.denyReason(player, is, action);
        if (reason == null) {
            return false;
        }
        module.deny(player, is, reason);
        return true;
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (module.isFlagBlock(event.getBlock()) && !module.hasBypass(player)) {
            event.setCancelled(true);
            IslandImpl is = module.at(event.getBlock());
            module.deny(player, is, "deny-flag");
            return;
        }
        if (module.isTreasureDigSpot(event.getBlock())) {
            return; // buried treasure is up for grabs, even on protected land
        }
        if (denied(player, event.getBlock(), IslandsModule.Action.BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (denied(event.getPlayer(), event.getBlockPlaced(), IslandsModule.Action.PLACE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (denied(event.getPlayer(), event.getBlock(), IslandsModule.Action.PLACE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (denied(event.getPlayer(), event.getBlock(), IslandsModule.Action.BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            Material type = block.getType();
            if (type == Material.FARMLAND || type == Material.TURTLE_EGG || type == Material.SNIFFER_EGG) {
                IslandImpl is = module.at(block);
                if (is != null && module.denyReason(event.getPlayer(), is, IslandsModule.Action.BREAK) != null) {
                    event.setCancelled(true);
                }
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        IslandImpl is = module.at(block);
        if (is == null || is.status == IslandStatus.UNCLAIMED) {
            return;
        }
        if (module.isTreasureDigSpot(block)) {
            return; // anyone may open a buried treasure chest
        }
        Material type = block.getType();
        BlockState state = block.getState(false);
        boolean container = state instanceof InventoryHolder
                || type == Material.ANVIL || type == Material.CHIPPED_ANVIL || type == Material.DAMAGED_ANVIL
                || type == Material.JUKEBOX || type == Material.LECTERN || type == Material.CHISELED_BOOKSHELF
                || type == Material.DECORATED_POT || type == Material.FLOWER_POT || type.name().startsWith("POTTED_")
                || type == Material.RESPAWN_ANCHOR || type == Material.CAKE || type.name().endsWith("_CANDLE_CAKE")
                || type == Material.COMPOSTER || type == Material.CAULDRON || type.name().endsWith("_CAULDRON");
        if (container) {
            if (denied(event.getPlayer(), block, IslandsModule.Action.CONTAINER)) {
                event.setCancelled(true);
            }
            return;
        }
        if (is.status == IslandStatus.NEUTRAL && type.isInteractable()
                && !module.config().getBoolean("protection.neutral.allow-interact", true)
                && !module.hasBypass(event.getPlayer())) {
            event.setCancelled(true);
            module.deny(event.getPlayer(), is, "deny-neutral");
        }
    }

    // ------------------------------------------------------------------ entities owned by the island

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player player = playerOf(event.getRemover());
        IslandImpl is = module.at(event.getEntity().getLocation());
        if (is == null) {
            return;
        }
        if (player == null) {
            if (is.status != IslandStatus.UNCLAIMED && !(event.getRemover() instanceof TNTPrimed && is.status == IslandStatus.OCCUPIED)) {
                event.setCancelled(true);
            }
            return;
        }
        String reason = module.denyReason(player, is, IslandsModule.Action.BREAK);
        if (reason != null) {
            event.setCancelled(true);
            module.deny(player, is, reason);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && denied(event.getPlayer(), event.getBlock(), IslandsModule.Action.PLACE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (!(target instanceof Hanging) && !(target instanceof ArmorStand)) {
            return;
        }
        IslandImpl is = module.at(target.getLocation());
        if (is == null) {
            return;
        }
        String reason = module.denyReason(event.getPlayer(), is, IslandsModule.Action.CONTAINER);
        if (reason != null) {
            event.setCancelled(true);
            module.deny(event.getPlayer(), is, reason);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        IslandImpl is = module.at(event.getRightClicked().getLocation());
        if (is == null) {
            return;
        }
        String reason = module.denyReason(event.getPlayer(), is, IslandsModule.Action.CONTAINER);
        if (reason != null) {
            event.setCancelled(true);
            module.deny(event.getPlayer(), is, reason);
        }
    }

    // ------------------------------------------------------------------ PvP

    private static Player playerOf(Entity entity) {
        if (entity instanceof Player p) {
            return p;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        if (entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) {
            return p;
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) {
            return p;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        Player attacker = playerOf(event.getDamager());
        if (victim instanceof Player target) {
            if (attacker == null || attacker.equals(target)) {
                return;
            }
            if (module.config().getBoolean("protection.neutral.pvp", false)) {
                return;
            }
            IslandImpl at = module.at(target.getLocation());
            IslandImpl from = module.at(attacker.getLocation());
            IslandImpl neutral = at != null && at.status == IslandStatus.NEUTRAL ? at
                    : from != null && from.status == IslandStatus.NEUTRAL ? from : null;
            if (neutral != null) {
                event.setCancelled(true);
                module.deny(attacker, neutral, "deny-pvp");
            }
            return;
        }
        if ((victim instanceof ArmorStand || victim instanceof Hanging) && attacker != null) {
            IslandImpl is = module.at(victim.getLocation());
            if (is == null) {
                return;
            }
            String reason = module.denyReason(attacker, is, IslandsModule.Action.BREAK);
            if (reason != null) {
                event.setCancelled(true);
                module.deny(attacker, is, reason);
            }
        }
    }

    // ------------------------------------------------------------------ explosions, mobs, fire, liquids, pistons

    /** Decides per island whether explosions may destroy blocks there. */
    private boolean explosionBlocked(Block block, Map<String, Boolean> cache) {
        if (module.isFlagBlock(block)) {
            return true;
        }
        IslandImpl is = module.at(block);
        if (is == null) {
            return false;
        }
        return cache.computeIfAbsent(is.id, k -> switch (is.status) {
            case NEUTRAL -> true;
            case UNCLAIMED -> !module.config().getBoolean("protection.unclaimed-explosions", true);
            case OCCUPIED -> switch (module.config().getString("protection.occupied.explosions", "raid").toLowerCase(Locale.ROOT)) {
                case "always" -> false;
                case "never" -> true;
                default -> !module.raidableNow(is);
            };
        });
    }

    private void filterExplosion(List<Block> blocks) {
        Map<String, Boolean> cache = new HashMap<>();
        blocks.removeIf(b -> explosionBlocked(b, cache));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        if (module.isFlagBlock(event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        IslandImpl is = module.at(event.getBlock());
        if (is == null) {
            return;
        }
        if (is.status == IslandStatus.NEUTRAL
                || (is.status == IslandStatus.OCCUPIED && !module.config().getBoolean("protection.occupied.mob-griefing", true))) {
            // falling blocks landing are fine, only stop mobs changing terrain
            if (event.getEntity() instanceof org.bukkit.entity.FallingBlock) {
                return;
            }
            event.setCancelled(true);
        }
    }

    private boolean fireProtected(Block block) {
        IslandImpl is = module.at(block);
        if (is == null) {
            return false;
        }
        return switch (is.status) {
            case NEUTRAL -> true;
            case OCCUPIED -> !module.config().getBoolean("protection.occupied.fire-spread", false);
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (fireProtected(event.getBlock()) || module.isFlagBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() == Material.FIRE || event.getSource().getType() == Material.SOUL_FIRE) {
            if (fireProtected(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            if (denied(player, event.getBlock(), IslandsModule.Action.PLACE)) {
                event.setCancelled(true);
            }
            return;
        }
        switch (event.getCause()) {
            case SPREAD, LAVA, LIGHTNING, FIREBALL, EXPLOSION -> {
                if (fireProtected(event.getBlock())) {
                    event.setCancelled(true);
                }
            }
            default -> {
            }
        }
    }

    /** Liquids may not flow from outside into a protected island (stops lava/water griefing across the border). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        IslandImpl target = module.at(to);
        if (target == null || target.status == IslandStatus.UNCLAIMED) {
            return;
        }
        Block from = event.getBlock();
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) {
            return; // flowing straight down never crosses a border
        }
        if (module.at(from) != target) {
            event.setCancelled(true);
        }
    }

    private boolean pistonCrossesBorder(Block piston, List<Block> blocks, BlockFace direction) {
        IslandImpl home = module.at(piston);
        for (Block b : blocks) {
            if (module.isFlagBlock(b)) {
                return true;
            }
            IslandImpl source = module.at(b);
            IslandImpl dest = module.at(b.getRelative(direction));
            if (source != home && source != null && source.status != IslandStatus.UNCLAIMED) {
                return true;
            }
            if (dest != home && dest != null && dest.status != IslandStatus.UNCLAIMED) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        List<Block> blocks = new java.util.ArrayList<>(event.getBlocks());
        blocks.add(event.getBlock().getRelative(event.getDirection()));
        if (pistonCrossesBorder(event.getBlock(), blocks, event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonCrossesBorder(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }
}
