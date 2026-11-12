package dev.aquatic.events.sea;

import dev.aquatic.api.BountyService;
import dev.aquatic.api.event.BountyClaimEvent;
import dev.aquatic.events.EventsModule;
import dev.aquatic.events.Util;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Extra event bounties are placed on wanted pirates; the hunter who claims the most wins a bonus. */
public final class BountyHunt extends SeaEvent {

    private final Map<UUID, String> targets = new LinkedHashMap<>();
    private final Map<UUID, Double> hunters = new HashMap<>();
    private final Map<UUID, String> hunterNames = new HashMap<>();
    /** Event bounty still standing on each unclaimed target (removed again when the event ends). */
    private double placedAmount;

    public BountyHunt(EventsModule module) {
        super(module, "bounty_hunt");
    }

    @Override
    protected void begin(CommandSender sender, String[] args) {
        BountyService bounties = module.service(BountyService.class);
        if (bounties == null) {
            throw fail("requires-module", "module", "bounty");
        }
        int count = Math.max(1, cfg.getInt("targets", 3));
        double amount = Math.max(0, cfg.getDouble("bounty-amount", 5000));
        Set<UUID> chosen = new LinkedHashSet<>();
        if (!"random".equalsIgnoreCase(cfg.getString("target-mode", "top"))) {
            for (UUID u : bounties.top(count * 3).keySet()) {
                if (Bukkit.getPlayer(u) != null && chosen.size() < count) {
                    chosen.add(u);
                }
            }
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        Collections.shuffle(online);
        for (Player p : online) {
            if (chosen.size() >= count) {
                break;
            }
            chosen.add(p.getUniqueId());
        }
        if (chosen.size() < Math.min(count, Math.max(1, cfg.getInt("min-targets", 1)))) {
            throw fail("bounty-no-targets");
        }
        String reason = cfg.getString("reason", "Bounty Hunt event");
        placedAmount = amount;
        for (UUID u : chosen) {
            Player p = Bukkit.getPlayer(u);
            targets.put(u, p == null ? u.toString() : p.getName());
            if (amount > 0) {
                bounties.addBounty(u, amount, reason);
            }
        }
        announce("bounty-targets", "targets", String.join(", ", targets.values()), "amount", moneyText(amount));
        for (UUID u : chosen) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                module.send(p, "bounty-you-are-target", "amount", moneyText(amount));
            }
        }
    }

    @Override
    public void onBountyClaim(BountyClaimEvent event) {
        Player killer = event.killer();
        if (killer == null) {
            return;
        }
        hunters.merge(killer.getUniqueId(), event.amount(), Double::sum);
        hunterNames.put(killer.getUniqueId(), killer.getName());
        participants.add(killer.getUniqueId());
        String name = targets.remove(event.victim());
        if (name != null) {
            announce("bounty-target-down", "target", name, "hunter", killer.getName(), "amount", moneyText(event.amount()),
                    "left", targets.size());
            if (targets.isEmpty() && cfg.getBoolean("end-when-all-claimed", true)) {
                finish(false);
            }
        }
    }

    @Override
    protected void conclude(boolean timeUp) {
        if (hunters.isEmpty()) {
            announce("bounty-no-claims");
            return;
        }
        List<Map.Entry<UUID, Double>> ranked = new ArrayList<>(hunters.entrySet());
        ranked.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        announce("bounty-results");
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            Util.broadcast(module.rawMsg("bounty-result-entry", "rank", i + 1, "player", hunterNames.get(ranked.get(i).getKey()),
                    "amount", moneyText(ranked.get(i).getValue())));
        }
        Player best = Bukkit.getPlayer(ranked.get(0).getKey());
        if (best != null) {
            rewardPlayer(best, "winner", 1.0);
            announce("bounty-winner", "player", best.getName());
        }
        for (UUID u : participants) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && !p.equals(best)) {
                rewardPlayer(p, "participant", 1.0);
            }
        }
    }

    /** Takes the event bounty off targets nobody claimed, so it does not linger after the event. */
    @Override
    protected void cleanup() {
        if (placedAmount <= 0 || !cfg.getBoolean("remove-unclaimed-bounties", true)) {
            return;
        }
        BountyService bounties = module.service(BountyService.class);
        if (bounties == null) {
            return;
        }
        for (UUID u : targets.keySet()) {
            bounties.reduceBounty(u, placedAmount);
        }
        targets.clear();
    }

    @Override
    protected Component status() {
        return module.rawMsg("bounty-status", "left", targets.size());
    }
}
