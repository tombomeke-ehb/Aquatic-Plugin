package dev.aquatic.world;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewRelation;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.IslandStatus;
import dev.aquatic.api.event.IslandConquerEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Hold the flag" conquest. Runs once per second: enemy crew members standing within the capture
 * radius of an occupied island's flag build up progress (shown as a boss bar to everybody on the
 * island); defenders inside the radius contest it, and progress decays when nobody is attacking.
 */
final class ConquestManager {

    private static final class Capture {
        String attackerCrewId;
        double progress;
        long lastAlert;
        BossBar bar;
        final Set<UUID> viewers = new HashSet<>();
    }

    private final IslandsModule module;
    private final Map<String, Capture> captures = new HashMap<>();
    private final Map<UUID, Long> lastHint = new HashMap<>();

    ConquestManager(IslandsModule module) {
        this.module = module;
    }

    boolean underAttack(IslandImpl is) {
        Capture c = captures.get(is.id);
        return c != null && c.progress > 0;
    }

    void forget(String islandId) {
        Capture c = captures.remove(islandId);
        if (c != null) {
            hideAll(c);
        }
    }

    void playerQuit(Player player) {
        for (Capture c : captures.values()) {
            if (c.viewers.remove(player.getUniqueId()) && c.bar != null) {
                player.hideBossBar(c.bar);
            }
        }
        lastHint.remove(player.getUniqueId());
    }

    void shutdown() {
        for (Capture c : captures.values()) {
            hideAll(c);
        }
        captures.clear();
        lastHint.clear();
    }

    private void hideAll(Capture c) {
        if (c.bar == null) {
            return;
        }
        for (UUID id : c.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(c.bar);
            }
        }
        c.viewers.clear();
    }

    private String cfg(String path, String def) {
        return module.config().getString("conquest." + path, def);
    }

    /** Called every second. */
    void tick() {
        if (!module.config().getBoolean("conquest.enabled", true)) {
            if (!captures.isEmpty()) {
                shutdown();
            }
            return;
        }
        CrewService crews = module.service(CrewService.class);
        if (crews == null) {
            if (!captures.isEmpty()) {
                shutdown();
            }
            return;
        }
        // group online players by the island they stand on: O(players)
        Map<String, List<Player>> onIsland = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            IslandImpl is = module.at(p.getLocation());
            if (is != null) {
                onIsland.computeIfAbsent(is.id, k -> new ArrayList<>()).add(p);
            }
        }
        Set<String> candidates = new HashSet<>(captures.keySet());
        candidates.addAll(onIsland.keySet());
        for (String id : candidates) {
            IslandImpl is = module.byId(id);
            if (is == null || is.status != IslandStatus.OCCUPIED || is.ownerCrewId == null || !is.hasFlag) {
                forget(id);
                continue;
            }
            tickIsland(is, onIsland.getOrDefault(id, List.of()), crews);
        }
    }

    private void tickIsland(IslandImpl is, List<Player> present, CrewService crews) {
        Crew owner = crews.crewById(is.ownerCrewId);
        if (owner == null) {
            forget(is.id);
            return;
        }
        Location flag = is.flagLocation();
        double radius = Math.max(1, module.config().getDouble("conquest.capture-radius", 6));
        double vertical = Math.max(2, module.config().getDouble("conquest.capture-height", 6));
        boolean alliesDefend = module.config().getBoolean("conquest.allies-defend", true);
        String mode = cfg("mode", "war-only");

        int defenders = 0;
        Map<String, List<Player>> attackersByCrew = new HashMap<>();
        Map<String, Crew> crewById = new HashMap<>();
        for (Player p : present) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || flag == null || !p.getWorld().equals(flag.getWorld())) {
                continue;
            }
            Location l = p.getLocation();
            double dx = l.getX() - (flag.getBlockX() + 0.5), dz = l.getZ() - (flag.getBlockZ() + 0.5);
            if (dx * dx + dz * dz > radius * radius || Math.abs(l.getY() - flag.getY()) > vertical) {
                continue;
            }
            Crew crew = crews.crewOf(p.getUniqueId());
            if (crew == null) {
                continue;
            }
            CrewRelation rel = crews.relation(crew, owner);
            if (rel == CrewRelation.SAME || (rel == CrewRelation.ALLY && alliesDefend)) {
                defenders++;
            } else if (IslandsModule.modeAllows(mode, rel)) {
                attackersByCrew.computeIfAbsent(crew.id(), k -> new ArrayList<>()).add(p);
                crewById.put(crew.id(), crew);
            }
        }

        Capture c = captures.get(is.id);
        String attackerId = null;
        if (c != null && attackersByCrew.containsKey(c.attackerCrewId)) {
            attackerId = c.attackerCrewId;
        } else {
            int best = 0;
            for (Map.Entry<String, List<Player>> e : attackersByCrew.entrySet()) {
                if (e.getValue().size() > best) {
                    best = e.getValue().size();
                    attackerId = e.getKey();
                }
            }
        }
        List<Player> attackers = attackerId == null ? List.of() : attackersByCrew.get(attackerId);

        // conditions that block conquest entirely
        String blocked = null;
        if (module.offlineProtected(is)) {
            blocked = "deny-offline";
        } else if (module.shielded(is)) {
            blocked = "deny-shield";
        } else if (attackers.size() < Math.max(1, module.config().getInt("conquest.min-attackers", 1))) {
            blocked = attackers.isEmpty() ? null : "conquest-need-more";
        }
        if (blocked != null && !attackers.isEmpty()) {
            hint(attackers, is, blocked);
        }

        double captureSeconds = Math.max(5, module.config().getDouble("conquest.capture-seconds", 120));
        double decay = Math.max(0, module.config().getDouble("conquest.decay-per-second", 1.0));
        boolean contested = defenders > 0 && !attackers.isEmpty();

        if (c == null) {
            if (attackers.isEmpty() || blocked != null || contested) {
                return;
            }
            c = new Capture();
            c.attackerCrewId = attackerId;
            captures.put(is.id, c);
        }

        if (c.attackerCrewId.equals(attackerId) && blocked == null && !contested) {
            c.progress = Math.min(captureSeconds, c.progress + 1);
            alert(is, c, owner, crewById.get(attackerId));
        } else if (!contested || blocked != null) {
            c.progress = Math.max(0, c.progress - decay);
            if (c.progress <= 0) {
                if (attackerId != null && blocked == null && !contested) {
                    c.attackerCrewId = attackerId; // a different crew takes over the capture
                } else {
                    forget(is.id);
                    return;
                }
            }
        }

        if (c.progress >= captureSeconds) {
            Crew winner = crews.crewById(c.attackerCrewId);
            forget(is.id);
            if (winner != null) {
                succeed(is, owner, winner, crews);
            }
            return;
        }
        updateBar(is, c, present, crews, captureSeconds, contested);
    }

    private void hint(List<Player> players, IslandImpl is, String key) {
        long now = System.currentTimeMillis();
        for (Player p : players) {
            Long last = lastHint.get(p.getUniqueId());
            if (last == null || now - last > 5000) {
                lastHint.put(p.getUniqueId(), now);
                p.sendActionBar(module.rawMsg(key, "island", is.name, "crew", module.crewDisplay(is.ownerCrewId),
                        "time", dev.aquatic.util.Text.duration(Math.max(0, (is.shieldUntil - now) / 1000)),
                        "min", module.config().getInt("conquest.min-attackers", 1)));
            }
        }
    }

    private void alert(IslandImpl is, Capture c, Crew owner, Crew attacker) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(5, module.config().getLong("conquest.alert-cooldown-seconds", 60)) * 1000L;
        if (now - c.lastAlert < cooldown) {
            return;
        }
        c.lastAlert = now;
        Component attackerName = attacker == null ? module.rawMsg("unknown-crew") : attacker.displayName();
        owner.broadcast(module.msg("conquest-alert", "island", is.name, "crew", attackerName,
                "x", is.flagX, "y", is.flagY, "z", is.flagZ));
        playSound(owner.onlineMembers(), module.config().getString("conquest.alert-sound", "block.bell.use"));
        if (attacker != null && module.config().getBoolean("conquest.announce-start", false)) {
            Bukkit.broadcast(module.msg("conquest-started", "island", is.name, "crew", attackerName, "owner", owner.displayName()));
        }
    }

    private void updateBar(IslandImpl is, Capture c, List<Player> present, CrewService crews, double captureSeconds, boolean contested) {
        Crew attacker = crews.crewById(c.attackerCrewId);
        Component name = attacker == null ? module.rawMsg("unknown-crew") : attacker.displayName();
        float progress = (float) Math.max(0, Math.min(1, c.progress / captureSeconds));
        Component title = module.rawMsg(contested ? "conquest-bar-contested" : "conquest-bar",
                "island", is.name, "crew", name, "percent", (int) Math.floor(progress * 100),
                "time", dev.aquatic.util.Text.duration((long) Math.ceil(captureSeconds - c.progress)));
        BossBar.Color color = parseColor(cfg(contested ? "bossbar.contested-color" : "bossbar.color", contested ? "YELLOW" : "RED"));
        if (c.bar == null) {
            c.bar = BossBar.bossBar(title, progress, color, parseOverlay(cfg("bossbar.overlay", "NOTCHED_10")));
        } else {
            c.bar.name(title);
            c.bar.progress(progress);
            c.bar.color(color);
        }
        Set<UUID> now = new HashSet<>();
        for (Player p : present) {
            now.add(p.getUniqueId());
            if (c.viewers.add(p.getUniqueId())) {
                p.showBossBar(c.bar);
            }
        }
        for (Iterator<UUID> it = c.viewers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!now.contains(id)) {
                it.remove();
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.hideBossBar(c.bar);
                }
            }
        }
    }

    private void succeed(IslandImpl is, Crew previous, Crew winner, CrewService crews) {
        String previousId = previous.id();
        module.conquer(is, winner);
        Bukkit.getPluginManager().callEvent(new IslandConquerEvent(is, previousId, winner));
        int steal = Math.max(0, module.config().getInt("conquest.reputation.steal", 50));
        int transfer = Math.min(steal, Math.max(0, previous.reputation()));
        if (transfer > 0) {
            previous.addReputation(-transfer);
        }
        int bonus = Math.max(0, module.config().getInt("conquest.reputation.bonus", 25));
        if (transfer + bonus > 0) {
            winner.addReputation(transfer + bonus);
        }
        Bukkit.broadcast(module.msg("conquest-success", "island", is.name, "crew", winner.displayName(),
                "owner", previous.displayName(), "reputation", transfer + bonus));
        previous.broadcast(module.msg("conquest-lost", "island", is.name, "crew", winner.displayName()));
        playSound(module.playersOn(is), module.config().getString("conquest.success-sound", "ui.toast.challenge_complete"));
    }

    private void playSound(Iterable<? extends Player> players, String sound) {
        if (sound == null || sound.isBlank() || sound.equalsIgnoreCase("none")) {
            return;
        }
        Sound s;
        try {
            s = Sound.sound(Key.key(sound.toLowerCase(Locale.ROOT)), Sound.Source.MASTER, 1f, 1f);
        } catch (RuntimeException e) {
            return;
        }
        for (Player p : players) {
            p.playSound(s);
        }
    }

    private static BossBar.Color parseColor(String raw) {
        try {
            return BossBar.Color.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return BossBar.Color.RED;
        }
    }

    private static BossBar.Overlay parseOverlay(String raw) {
        try {
            return BossBar.Overlay.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return BossBar.Overlay.PROGRESS;
        }
    }
}
