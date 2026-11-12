package dev.aquatic.events.sea;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.api.IslandType;
import dev.aquatic.api.TournamentService;
import dev.aquatic.events.EventsModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** King of the hill at the throne: the crew that holds it longest wins; the king's crew defends. */
public final class KingsChallenge extends SeaEvent {

    private Location hill;
    private double radius;
    private String kingCrewId;
    private String kingName;
    private final Map<String, Integer> held = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private String holder;
    private boolean contested;

    public KingsChallenge(EventsModule module) {
        super(module, "kings_challenge");
    }

    @Override
    protected void begin(CommandSender sender, String[] args) {
        if (crews() == null) {
            throw fail("requires-module", "module", "crews");
        }
        hill = module.location(type, "hill");
        if (hill == null) {
            IslandService islands = module.service(IslandService.class);
            if (islands != null) {
                for (Island i : islands.islands()) {
                    if (i.type() == IslandType.CAPITAL && i.center() != null && i.center().getWorld() != null) {
                        hill = i.center().clone();
                        break;
                    }
                }
            }
        }
        if (hill == null) {
            throw fail("kings-no-hill");
        }
        radius = Math.max(2, cfg.getDouble("radius", 6));
        TournamentService tournament = module.service(TournamentService.class);
        UUID king = tournament == null ? null : tournament.pirateKing();
        if (king == null && cfg.getBoolean("require-king", false)) {
            throw fail("kings-no-king");
        }
        if (king != null) {
            kingName = tournament.pirateKingName();
            Crew kc = crews().crewOf(king);
            kingCrewId = kc == null ? null : kc.id();
            announce("kings-start", "king", kingName == null ? "?" : kingName, "x", hill.getBlockX(), "y", hill.getBlockY(),
                    "z", hill.getBlockZ());
        } else {
            announce("kings-start-empty", "x", hill.getBlockX(), "y", hill.getBlockY(), "z", hill.getBlockZ());
        }
    }

    @Override
    protected void onTick() {
        CrewService crews = crews();
        if (crews == null || hill.getWorld() == null) {
            return;
        }
        Set<String> present = new HashSet<>();
        for (Player p : hill.getWorld().getPlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.getLocation().distanceSquared(hill) > radius * radius
                    || Math.abs(p.getLocation().getY() - hill.getY()) > 8) {
                continue;
            }
            Crew c = crews.crewOf(p.getUniqueId());
            if (c != null) {
                present.add(c.id());
                names.put(c.id(), c.name());
                participants.add(p.getUniqueId());
            }
        }
        contested = present.size() > 1;
        String now = present.size() == 1 ? present.iterator().next() : null;
        if (now != null) {
            int gain = now.equals(kingCrewId) ? Math.max(1, cfg.getInt("king-crew-points-per-second", 1)) : 1;
            held.merge(now, gain, Integer::sum);
        }
        if (now != null && !now.equals(holder)) {
            announce("kings-hill-taken", "crew", crewName(now));
        }
        holder = now;
        ring();
    }

    private void ring() {
        int points = (int) Math.min(48, radius * 6);
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            hill.getWorld().spawnParticle(contested ? Particle.ANGRY_VILLAGER : Particle.END_ROD,
                    hill.clone().add(Math.cos(a) * radius, 0.3, Math.sin(a) * radius), 1, 0, 0, 0, 0);
        }
    }

    private Component crewName(String id) {
        CrewService crews = crews();
        Crew c = crews == null ? null : crews.crewById(id);
        return c != null ? c.displayName() : Component.text(names.getOrDefault(id, id));
    }

    @Override
    protected void conclude(boolean timeUp) {
        String best = null;
        int bestTime = 0;
        for (Map.Entry<String, Integer> e : held.entrySet()) {
            if (e.getValue() > bestTime) {
                best = e.getKey();
                bestTime = e.getValue();
            }
        }
        if (best == null) {
            announce("kings-nobody");
            return;
        }
        CrewService crews = crews();
        Crew winner = crews == null ? null : crews.crewById(best);
        if (best.equals(kingCrewId)) {
            announce("kings-defended", "crew", crewName(best), "king", kingName == null ? "?" : kingName,
                    "time", Text.duration(bestTime));
        } else {
            announce("kings-won", "crew", crewName(best), "time", Text.duration(bestTime),
                    "king", kingName == null ? "-" : kingName);
        }
        if (winner != null) {
            rewardCrew(winner, "winner");
        }
    }

    @Override
    protected Location joinLocation() {
        return hill;
    }

    @Override
    protected Component status() {
        if (contested) {
            return module.rawMsg("kings-status-contested");
        }
        if (holder == null) {
            return module.rawMsg("kings-status-free");
        }
        return module.rawMsg("kings-status-held", "crew", crewName(holder), "time", Text.duration(held.getOrDefault(holder, 0)));
    }
}
