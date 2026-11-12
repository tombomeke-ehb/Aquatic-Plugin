package dev.aquatic.events.sea;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewService;
import dev.aquatic.events.EventsModule;
import dev.aquatic.events.Util;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Crews fight at sea: kills on the water (or in the battle zone) score points for the killer's crew. */
public final class NavalBattle extends SeaEvent {

    private Location zone;
    private double zoneRadius;
    private final Map<String, Integer> points = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<UUID, Integer> playerKills = new HashMap<>();

    public NavalBattle(EventsModule module) {
        super(module, "naval_battle");
    }

    @Override
    protected void begin(CommandSender sender, String[] args) {
        CrewService crews = crews();
        if (crews == null) {
            throw fail("requires-module", "module", "crews");
        }
        int active = 0;
        for (Crew c : crews.crews()) {
            if (!c.onlineMembers().isEmpty()) {
                active++;
            }
        }
        int min = Math.max(2, cfg.getInt("min-crews", 2));
        if (active < min) {
            throw fail("naval-not-enough-crews", "min", min);
        }
        zoneRadius = Math.max(0, cfg.getDouble("zone-radius", 0));
        zone = zoneRadius > 0 ? module.location(type, "default") : null;
        if (zoneRadius > 0 && zone == null) {
            throw fail("naval-no-zone");
        }
        if (zone != null) {
            announce("naval-start-zone", "x", zone.getBlockX(), "z", zone.getBlockZ(), "radius", (long) zoneRadius);
        } else {
            announce("naval-start");
        }
        List<String> give = cfg.getStringList("give-items");
        if (!give.isEmpty()) {
            boolean skipped = false;
            for (Crew c : crews.crews()) {
                for (Player p : c.onlineMembers()) {
                    for (String spec : give) {
                        ItemStack item = Util.parseItem(module, spec);
                        if (item == null) {
                            skipped = true;
                        } else {
                            Util.give(p, item);
                        }
                    }
                }
            }
            if (skipped) {
                module.logger().info("[events] naval_battle: some start items were skipped (unknown item or items module disabled).");
            }
        }
    }

    private boolean atSea(Player p) {
        return (p.getVehicle() instanceof Boat) || p.isInWater();
    }

    private boolean inZone(Player p) {
        return zone != null && Util.flatDistance(zone, p.getLocation()) <= zoneRadius;
    }

    @Override
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        Crew kc = crewOf(killer), vc = crewOf(victim);
        if (kc == null || killer.equals(victim) || (vc != null && vc.id().equals(kc.id()))) {
            return;
        }
        if (zone != null && !inZone(victim) && !inZone(killer)) {
            return;
        }
        if (cfg.getBoolean("sea-only", true) && !atSea(killer) && !atSea(victim)) {
            return;
        }
        int pts = Math.max(1, cfg.getInt("points-per-kill", 1));
        if (killer.getVehicle() instanceof Boat && victim.getVehicle() instanceof Boat) {
            pts += Math.max(0, cfg.getInt("boat-to-boat-bonus", 1));
        }
        points.merge(kc.id(), pts, Integer::sum);
        names.put(kc.id(), kc.name());
        playerKills.merge(killer.getUniqueId(), 1, Integer::sum);
        participants.add(killer.getUniqueId());
        participants.add(victim.getUniqueId());
        if (cfg.getBoolean("announce-kills", true)) {
            announce("naval-kill", "killer", killer.getName(), "victim", victim.getName(), "crew", kc.displayName(),
                    "points", points.get(kc.id()));
        }
    }

    private String leader() {
        String best = null;
        int bp = 0;
        for (Map.Entry<String, Integer> e : points.entrySet()) {
            if (e.getValue() > bp) {
                bp = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private Component crewName(String id) {
        CrewService crews = crews();
        Crew c = crews == null ? null : crews.crewById(id);
        return c != null ? c.displayName() : Component.text(names.getOrDefault(id, id));
    }

    @Override
    protected void conclude(boolean timeUp) {
        String best = leader();
        if (best == null) {
            announce("naval-no-winner");
            return;
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(points.entrySet());
        ranked.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        if (ranked.size() > 1 && ranked.get(0).getValue().equals(ranked.get(1).getValue())) {
            announce("naval-draw", "a", crewName(ranked.get(0).getKey()), "b", crewName(ranked.get(1).getKey()),
                    "points", ranked.get(0).getValue());
            return;
        }
        announce("naval-won", "crew", crewName(best), "points", points.get(best));
        CrewService crews = crews();
        Crew c = crews == null ? null : crews.crewById(best);
        if (c != null) {
            rewardCrew(c, "winner");
        }
        for (UUID u : playerKills.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                rewardPlayer(p, "participant", 1.0);
            }
        }
    }

    @Override
    protected Location joinLocation() {
        return zone;
    }

    @Override
    protected Component status() {
        String best = leader();
        if (best == null) {
            return module.rawMsg("naval-status-none");
        }
        return module.rawMsg("naval-status", "crew", crewName(best), "points", points.get(best));
    }
}
