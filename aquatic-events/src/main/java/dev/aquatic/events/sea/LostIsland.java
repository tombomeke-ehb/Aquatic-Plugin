package dev.aquatic.events.sea;

import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.api.IslandType;
import dev.aquatic.api.TreasureService;
import dev.aquatic.api.event.TreasureFoundEvent;
import dev.aquatic.events.EventsModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** A temporary island rises from the sea with treasure on it, and sinks again when the event ends. */
public final class LostIsland extends SeaEvent {

    private String islandId;
    private String islandName;
    private Location center;
    private final List<Location> treasures = new ArrayList<>();
    private boolean sinking;
    private boolean keep;
    private int found;

    public LostIsland(EventsModule module) {
        super(module, "lost_island");
    }

    @Override
    protected void begin(CommandSender sender, String[] args) {
        IslandService islands = module.service(IslandService.class);
        if (islands == null) {
            throw fail("requires-module", "module", "islands");
        }
        int radius = Math.max(10, cfg.getInt("radius", 35));
        keep = cfg.getBoolean("keep-island", false);
        IslandType type = IslandType.parse(cfg.getString("island-type", "random"));
        if (type == null || type == IslandType.CAPITAL || type == IslandType.VILLAGE) {
            List<IslandType> options = new ArrayList<>(List.of(IslandType.values()));
            options.remove(IslandType.CAPITAL);
            options.remove(IslandType.VILLAGE);
            type = options.get(ThreadLocalRandom.current().nextInt(options.size()));
        }
        Location spot = islands.findFreeOceanSpot(radius);
        if (spot == null) {
            throw fail("lost-island-no-spot");
        }
        List<String> names = new ArrayList<>(cfg.getStringList("names"));
        if (names.isEmpty()) {
            names.add("Lost Island");
        }
        java.util.Collections.shuffle(names);
        if (args.length > 0) {
            names.add(0, String.join(" ", args));
        }
        Island island = null;
        for (String n : names) {
            island = islands.createIsland(n, type, spot, radius, true);
            if (island != null) {
                break;
            }
        }
        if (island == null) {
            throw fail("lost-island-failed");
        }
        islandId = island.id();
        islandName = island.name();
        center = island.center();
        module.trackTemporaryIsland(islandId, !keep);
        long delay = Math.max(1, cfg.getLong("treasure-delay-seconds", 15)) * 20;
        module.runLater(this::spawnTreasures, delay);
        announce("lost-island-risen", "island", islandName, "type", type.name().toLowerCase(Locale.ROOT),
                "x", center.getBlockX(), "z", center.getBlockZ(), "world", center.getWorld().getName());
    }

    private void spawnTreasures() {
        if (isOver()) {
            return;
        }
        TreasureService ts = module.service(TreasureService.class);
        if (ts == null) {
            announce("lost-island-no-treasure");
            return;
        }
        Collection<String> available = ts.tiers();
        List<String> tiers = new ArrayList<>();
        for (String t : cfg.getStringList("treasure-tiers")) {
            if (available.contains(t.toLowerCase(Locale.ROOT))) {
                tiers.add(t.toLowerCase(Locale.ROOT));
            }
        }
        if (tiers.isEmpty() && !available.isEmpty()) {
            tiers.addAll(available);
        }
        if (tiers.isEmpty()) {
            return;
        }
        int count = Math.max(0, cfg.getInt("treasures", 3));
        int radius = Math.max(10, cfg.getInt("radius", 35));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double angle = r.nextDouble(Math.PI * 2);
            double dist = r.nextDouble(radius * 0.6);
            Location near = center.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            Location placed = ts.spawnTreasure(near, tiers.get(r.nextInt(tiers.size())));
            if (placed != null) {
                treasures.add(placed);
            }
        }
        if (!treasures.isEmpty()) {
            announce("lost-island-treasures", "count", treasures.size(), "island", islandName);
        }
    }

    @Override
    public void onTreasureFound(TreasureFoundEvent event) {
        Location l = event.location();
        if (l == null) {
            return;
        }
        Iterator<Location> it = treasures.iterator();
        while (it.hasNext()) {
            Location t = it.next();
            if (t.getWorld() != null && t.getWorld().equals(l.getWorld()) && t.distanceSquared(l) <= 4) {
                it.remove();
                found++;
                Player p = event.player();
                participants.add(p.getUniqueId());
                announce("lost-island-treasure-found", "player", p.getName(), "left", treasures.size());
                rewardPlayer(p, "finder", 1.0);
                if (treasures.isEmpty() && cfg.getBoolean("end-when-looted", true) && !sinking) {
                    endsAt = System.currentTimeMillis();
                }
                return;
            }
        }
    }

    @Override
    protected boolean onTimeUp() {
        if (keep || sinking) {
            return true;
        }
        long warning = Math.max(0, cfg.getLong("sink-warning-seconds", 60));
        if (warning == 0) {
            return true;
        }
        sinking = true;
        endsAt = System.currentTimeMillis() + warning * 1000;
        startedAt = System.currentTimeMillis();
        announce("lost-island-sink-warning", "island", islandName, "seconds", warning);
        return false;
    }

    @Override
    protected void onTick() {
        if (sinking) {
            long left = secondsLeft();
            if (left == 30 || left == 10 || left == 5) {
                announce("lost-island-sink-warning", "island", islandName, "seconds", left);
            }
        }
    }

    @Override
    protected void conclude(boolean timeUp) {
        announce(keep ? "lost-island-remains" : "lost-island-sunk", "island", islandName, "found", found);
    }

    @Override
    protected void cleanup() {
        TreasureService ts = module.service(TreasureService.class);
        if (ts != null) {
            for (Location t : treasures) {
                ts.removeTreasure(t);
            }
        }
        treasures.clear();
        if (islandId != null && !keep) {
            IslandService islands = module.service(IslandService.class);
            if (islands != null) {
                islands.removeIsland(islandId, true);
            }
        }
        if (islandId != null) {
            module.untrackTemporaryIsland(islandId, keep || module.service(IslandService.class) != null);
        }
    }

    @Override
    protected Component status() {
        if (sinking) {
            return module.rawMsg("lost-island-status-sinking", "island", islandName == null ? "?" : islandName);
        }
        return module.rawMsg("lost-island-status", "island", islandName == null ? "?" : islandName,
                "x", center == null ? 0 : center.getBlockX(), "z", center == null ? 0 : center.getBlockZ(), "left", treasures.size());
    }
}
