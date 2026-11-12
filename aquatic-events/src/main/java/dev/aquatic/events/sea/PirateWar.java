package dev.aquatic.events.sea;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewRelation;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.event.CrewDisbandEvent;
import dev.aquatic.events.EventsModule;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** An official, timed war between two crews: the crew with the most kills on the other wins. */
public final class PirateWar extends SeaEvent {

    private String crewA;
    private String crewB;
    private String nameA;
    private String nameB;
    private int killsA;
    private int killsB;
    private String forfeitWinner;

    public PirateWar(EventsModule module) {
        super(module, "pirate_war");
    }

    @Override
    protected void begin(CommandSender sender, String[] args) {
        CrewService crews = crews();
        if (crews == null) {
            throw fail("requires-module", "module", "crews");
        }
        Crew a, b;
        if (args.length >= 2) {
            a = crews.crewByName(args[0]);
            b = crews.crewByName(args[1]);
            if (a == null || b == null) {
                throw fail("unknown-crew", "crew", a == null ? args[0] : args[1]);
            }
            if (a.id().equals(b.id())) {
                throw fail("same-crew");
            }
        } else {
            Crew[] pair = autoPick(crews);
            if (pair == null) {
                throw fail("war-no-pair");
            }
            a = pair[0];
            b = pair[1];
        }
        crewA = a.id();
        crewB = b.id();
        nameA = a.name();
        nameB = b.name();
        announce("war-declared", "a", a.displayName(), "b", b.displayName());
    }

    private Crew[] autoPick(CrewService crews) {
        int minOnline = Math.max(1, cfg.getInt("min-online-per-crew", 1));
        List<Crew> active = new ArrayList<>();
        for (Crew c : crews.crews()) {
            if (c.onlineMembers().size() >= minOnline) {
                active.add(c);
            }
        }
        for (String rel : cfg.getStringList("auto-relations")) {
            CrewRelation wanted;
            try {
                wanted = CrewRelation.valueOf(rel.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            List<Crew[]> pairs = new ArrayList<>();
            for (int i = 0; i < active.size(); i++) {
                for (int j = i + 1; j < active.size(); j++) {
                    if (crews.relation(active.get(i), active.get(j)) == wanted) {
                        pairs.add(new Crew[]{active.get(i), active.get(j)});
                    }
                }
            }
            if (!pairs.isEmpty()) {
                return pairs.get(ThreadLocalRandom.current().nextInt(pairs.size()));
            }
        }
        return null;
    }

    @Override
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        Crew kc = crewOf(killer), vc = crewOf(victim);
        if (kc == null || vc == null || kc.id().equals(vc.id())) {
            return;
        }
        if (kc.id().equals(crewA) && vc.id().equals(crewB)) {
            killsA++;
        } else if (kc.id().equals(crewB) && vc.id().equals(crewA)) {
            killsB++;
        } else {
            return;
        }
        participants.add(killer.getUniqueId());
        participants.add(victim.getUniqueId());
        if (cfg.getBoolean("announce-kills", true)) {
            announce("war-kill", "killer", killer.getName(), "victim", victim.getName(),
                    "a", crewName(crewA, nameA), "b", crewName(crewB, nameB), "kills_a", killsA, "kills_b", killsB);
        }
        int target = cfg.getInt("kill-target", 0);
        if (target > 0 && (killsA >= target || killsB >= target)) {
            finish(false);
        }
    }

    @Override
    public void onCrewDisband(CrewDisbandEvent event) {
        String id = event.crew().id();
        if (id.equals(crewA) || id.equals(crewB)) {
            forfeitWinner = id.equals(crewA) ? crewB : crewA;
            finish(false);
        }
    }

    private Component crewName(String id, String fallback) {
        CrewService crews = crews();
        Crew c = crews == null ? null : crews.crewById(id);
        return c != null ? c.displayName() : Component.text(fallback);
    }

    @Override
    protected void conclude(boolean timeUp) {
        CrewService crews = crews();
        String winnerId = forfeitWinner;
        if (winnerId == null && killsA != killsB) {
            winnerId = killsA > killsB ? crewA : crewB;
        }
        if (winnerId == null) {
            announce("war-draw", "a", crewName(crewA, nameA), "b", crewName(crewB, nameB), "kills_a", killsA, "kills_b", killsB);
            return;
        }
        String loserId = winnerId.equals(crewA) ? crewB : crewA;
        Crew winner = crews == null ? null : crews.crewById(winnerId);
        Crew loser = crews == null ? null : crews.crewById(loserId);
        announce("war-won", "winner", crewName(winnerId, winnerId.equals(crewA) ? nameA : nameB),
                "loser", crewName(loserId, loserId.equals(crewA) ? nameA : nameB), "kills_a", killsA, "kills_b", killsB,
                "a", crewName(crewA, nameA), "b", crewName(crewB, nameB));
        if (winner != null) {
            rewardCrew(winner, "winner");
        }
        if (loser != null) {
            int rep = cfg.getInt("loser-reputation", 0);
            if (rep != 0) {
                loser.addReputation(rep);
            }
        }
    }

    @Override
    protected Component status() {
        return module.rawMsg("war-status", "a", crewName(crewA, nameA), "b", crewName(crewB, nameB), "kills_a", killsA, "kills_b", killsB);
    }
}
