package dev.aquatic.events.sea;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.api.TreasureService;
import dev.aquatic.api.event.TreasureFoundEvent;
import dev.aquatic.events.EventsModule;
import dev.aquatic.events.Util;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A treasure is buried somewhere; hints narrow down its position until someone digs it up. */
public final class TreasureHunt extends SeaEvent {

    private Location treasure;
    private String tier;
    private Player finder;
    private int hintIndex;
    private long nextHintAt;

    public TreasureHunt(EventsModule module) {
        super(module, "treasure_hunt");
    }

    @Override
    protected void begin(CommandSender sender, String[] args) {
        TreasureService treasures = module.service(TreasureService.class);
        if (treasures == null) {
            throw fail("requires-module", "module", "treasure");
        }
        tier = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : cfg.getString("tier", "legendary");
        if (!treasures.tiers().contains(tier)) {
            throw fail("unknown-tier", "tier", tier, "tiers", String.join(", ", treasures.tiers()));
        }
        treasure = treasures.spawnRandomTreasure(tier);
        if (treasure == null) {
            throw fail("treasure-failed");
        }
        hintIndex = 0;
        nextHintAt = System.currentTimeMillis() + 3000;
        handOutMaps(treasures);
    }

    private void handOutMaps(TreasureService treasures) {
        int maps = cfg.getInt("treasure-maps", 2);
        CrewService crews = crews();
        if (maps <= 0 || crews == null) {
            return;
        }
        List<Crew> candidates = new ArrayList<>();
        for (Crew c : crews.crews()) {
            if (!c.onlineMembers().isEmpty()) {
                candidates.add(c);
            }
        }
        Collections.shuffle(candidates);
        for (int i = 0; i < Math.min(maps, candidates.size()); i++) {
            Crew c = candidates.get(i);
            List<Player> online = new ArrayList<>(c.onlineMembers());
            Player holder = null;
            for (Player p : online) {
                if (p.getUniqueId().equals(c.captain())) {
                    holder = p;
                }
            }
            if (holder == null) {
                holder = online.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(online.size()));
            }
            ItemStack map = treasures.createTreasureMap(treasure, tier);
            if (map != null) {
                Util.give(holder, map);
                c.broadcast(module.msg("treasure-map-received", "player", holder.getName()));
            }
        }
    }

    @Override
    protected void onTick() {
        long interval = Math.max(10, cfg.getLong("hint-interval-seconds", 120)) * 1000;
        if (System.currentTimeMillis() < nextHintAt) {
            return;
        }
        nextHintAt = System.currentTimeMillis() + interval;
        List<Integer> precision = cfg.getIntegerList("hint-precision");
        if (precision.isEmpty()) {
            precision = List.of(0, 500, 200, 75, 25);
        }
        int p = precision.get(Math.min(hintIndex, precision.size() - 1));
        hintIndex++;
        if (p <= 0) {
            IslandService islands = module.service(IslandService.class);
            Island island = islands == null ? null : islands.islandAt(treasure);
            if (island != null) {
                announce("treasure-hint-island", "island", island.name());
            } else {
                announce("treasure-hint-world", "world", treasure.getWorld().getName());
            }
            return;
        }
        long x = Math.round(treasure.getX() / p) * p;
        long z = Math.round(treasure.getZ() / p) * p;
        announce("treasure-hint-coords", "x", x, "z", z, "precision", p);
    }

    @Override
    public void onTreasureFound(TreasureFoundEvent event) {
        Location l = event.location();
        if (finder != null || l == null || l.getWorld() == null || !l.getWorld().equals(treasure.getWorld())
                || l.distanceSquared(treasure) > 4) {
            return;
        }
        finder = event.player();
        finish(false);
    }

    @Override
    protected void conclude(boolean timeUp) {
        if (finder != null) {
            announce("treasure-found", "player", finder.getName());
            rewardPlayer(finder, "winner", 1.0);
            Util.firework(finder.getLocation());
        } else {
            announce("treasure-lost");
        }
    }

    @Override
    protected void cleanup() {
        if (finder == null && treasure != null) {
            TreasureService treasures = module.service(TreasureService.class);
            if (treasures != null) {
                treasures.removeTreasure(treasure);
            }
        }
    }

    @Override
    protected Component status() {
        return module.rawMsg("treasure-status", "tier", tier == null ? "?" : tier);
    }
}
