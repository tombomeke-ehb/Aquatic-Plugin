package dev.aquatic.crews;

import dev.aquatic.api.CrewRelation;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Crew teleport requests: /tpa, /tpahere, /tpaccept, /tpdeny. Only between crewmates (optionally
 * allies), with a warmup, a cooldown and expiring requests ("teleport" in crews.yml). Other plugins'
 * tpa commands (EssentialsX ...) are blocked so nobody can teleport around the crews.
 */
final class CrewTeleport implements Listener {

    /** A pending request; "here" = the target should come to the requester. */
    private record Request(UUID from, UUID to, boolean here, long expires) {
    }

    private final CrewsModule module;
    /** target -> requests waiting for that target */
    private final Map<UUID, List<Request>> pending = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> warmups = new HashMap<>();

    CrewTeleport(CrewsModule module) {
        this.module = module;
    }

    private org.bukkit.configuration.ConfigurationSection cfg() {
        org.bukkit.configuration.ConfigurationSection c = module.config().getConfigurationSection("teleport");
        return c != null ? c : new org.bukkit.configuration.file.YamlConfiguration();
    }

    // ------------------------------------------------------------------ commands

    List<AquaticCommand> commands() {
        boolean take = cfg().getBoolean("take-over", true);
        List<AquaticCommand> out = new ArrayList<>();
        AquaticCommand tpa = new AquaticCommand("tpa", "Ask a crewmate to teleport to them");
        tpa.defaultExecutor((s, a) -> request(AquaticCommand.player(s), a, false))
                .defaultCompleter((s, a) -> crewmateNames(s, a.length == 0 ? "" : a[0]));
        AquaticCommand here = new AquaticCommand("tpahere", "Ask a crewmate to teleport to you");
        here.defaultExecutor((s, a) -> request(AquaticCommand.player(s), a, true))
                .defaultCompleter((s, a) -> crewmateNames(s, a.length == 0 ? "" : a[0]));
        AquaticCommand accept = new AquaticCommand("tpaccept", "Accept a teleport request", "tpyes");
        accept.defaultExecutor((s, a) -> answer(AquaticCommand.player(s), a, true))
                .defaultCompleter((s, a) -> requesterNames(s, a.length == 0 ? "" : a[0]));
        AquaticCommand deny = new AquaticCommand("tpdeny", "Deny a teleport request", "tpno");
        deny.defaultExecutor((s, a) -> answer(AquaticCommand.player(s), a, false))
                .defaultCompleter((s, a) -> requesterNames(s, a.length == 0 ? "" : a[0]));
        for (AquaticCommand c : List.of(tpa, here, accept, deny)) {
            c.takeOver(take);
            out.add(c);
        }
        return out;
    }

    private List<String> crewmateNames(org.bukkit.command.CommandSender s, String prefix) {
        if (!(s instanceof Player p) || module.crewOf(p.getUniqueId()) == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Player other : module.crewOf(p.getUniqueId()).onlineMembers()) {
            if (!other.equals(p)) {
                names.add(other.getName());
            }
        }
        return AquaticCommand.filter(names, prefix);
    }

    private List<String> requesterNames(org.bukkit.command.CommandSender s, String prefix) {
        if (!(s instanceof Player p)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Request r : pending.getOrDefault(p.getUniqueId(), List.of())) {
            Player from = Bukkit.getPlayer(r.from());
            if (from != null) {
                names.add(from.getName());
            }
        }
        return AquaticCommand.filter(names, prefix);
    }

    private void request(Player p, String[] args, boolean here) {
        if (!cfg().getBoolean("enabled", true)) {
            throw new CommandFail(module.rawMsg("tp-disabled"));
        }
        AquaticCommand.need(args, 1, here ? "/tpahere <crewmate>" : "/tpa <crewmate>");
        Player target = AquaticCommand.onlinePlayer(args[0]);
        if (target.equals(p)) {
            throw new CommandFail(module.rawMsg("tp-self"));
        }
        CrewRelation rel = module.relation(p.getUniqueId(), target.getUniqueId());
        boolean allowed = rel == CrewRelation.SAME || (rel == CrewRelation.ALLY && cfg().getBoolean("allow-allies", false));
        if (!allowed && !p.hasPermission("aquatic.crews.admin")) {
            throw new CommandFail(module.rawMsg("tp-not-crew", "player", target.getName()));
        }
        long cooldown = Math.max(0, cfg().getLong("cooldown-seconds", 60)) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < cooldown && !p.hasPermission("aquatic.crews.admin")) {
            throw new CommandFail(module.rawMsg("tp-cooldown", "seconds", (cooldown - (System.currentTimeMillis() - last) + 999) / 1000));
        }
        long expires = System.currentTimeMillis() + Math.max(5, cfg().getLong("expire-seconds", 60)) * 1000L;
        List<Request> list = pending.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>());
        list.removeIf(r -> r.from().equals(p.getUniqueId()));
        list.add(new Request(p.getUniqueId(), target.getUniqueId(), here, expires));
        module.send(p, "tp-sent", "player", target.getName());
        Component buttons = Component.textOfChildren(
                module.rawMsg("tp-accept-button").clickEvent(ClickEvent.runCommand("/tpaccept " + p.getName()))
                        .hoverEvent(HoverEvent.showText(module.rawMsg("tp-accept-hover"))),
                Component.text("  "),
                module.rawMsg("tp-deny-button").clickEvent(ClickEvent.runCommand("/tpdeny " + p.getName()))
                        .hoverEvent(HoverEvent.showText(module.rawMsg("tp-deny-hover"))));
        target.sendMessage(module.msg(here ? "tp-request-here" : "tp-request", "player", p.getName(),
                "seconds", cfg().getLong("expire-seconds", 60)));
        target.sendMessage(buttons);
    }

    private void answer(Player p, String[] args, boolean accept) {
        List<Request> list = pending.getOrDefault(p.getUniqueId(), new ArrayList<>());
        list.removeIf(r -> r.expires() < System.currentTimeMillis());
        Request req = null;
        if (args.length > 0) {
            for (Request r : list) {
                Player from = Bukkit.getPlayer(r.from());
                if (from != null && from.getName().equalsIgnoreCase(args[0])) {
                    req = r;
                }
            }
        } else if (!list.isEmpty()) {
            req = list.get(list.size() - 1);
        }
        if (req == null) {
            throw new CommandFail(module.rawMsg("tp-no-request"));
        }
        list.remove(req);
        Player from = Bukkit.getPlayer(req.from());
        if (from == null) {
            throw new CommandFail(module.rawMsg("tp-offline"));
        }
        if (!accept) {
            module.send(p, "tp-denied-you");
            module.send(from, "tp-denied", "player", p.getName());
            return;
        }
        Player traveller = req.here() ? p : from;
        Player destination = req.here() ? from : p;
        module.send(from, "tp-accepted", "player", p.getName());
        cooldowns.put(from.getUniqueId(), System.currentTimeMillis());
        startWarmup(traveller, destination);
    }

    private void startWarmup(Player traveller, Player destination) {
        int seconds = Math.max(0, cfg().getInt("warmup-seconds", 3));
        UUID id = traveller.getUniqueId();
        BukkitTask old = warmups.remove(id);
        if (old != null) {
            old.cancel();
        }
        if (seconds == 0) {
            go(traveller, destination);
            return;
        }
        module.send(traveller, "tp-warmup", "seconds", seconds, "player", destination.getName());
        Location start = traveller.getLocation();
        double health = traveller.getHealth();
        int[] ticks = {0};
        warmups.put(id, module.runTimer(() -> {
            if (!traveller.isOnline() || !destination.isOnline()) {
                cancel(id, traveller.isOnline() ? traveller : null);
                return;
            }
            if (traveller.getWorld() != start.getWorld() || traveller.getLocation().distanceSquared(start) > 1.0
                    || traveller.getHealth() < health) {
                cancel(id, traveller);
                return;
            }
            traveller.getWorld().spawnParticle(Particle.PORTAL, traveller.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.2);
            ticks[0] += 5;
            if (ticks[0] >= seconds * 20) {
                BukkitTask t = warmups.remove(id);
                if (t != null) {
                    t.cancel();
                }
                go(traveller, destination);
            }
        }, 5L, 5L));
    }

    private void go(Player traveller, Player destination) {
        traveller.teleportAsync(destination.getLocation()).thenAccept(ok -> {
            if (ok) {
                module.send(traveller, "tp-arrived", "player", destination.getName());
            }
        });
    }

    private void cancel(UUID id, Player tell) {
        BukkitTask t = warmups.remove(id);
        if (t != null) {
            t.cancel();
        }
        if (tell != null) {
            module.send(tell, "tp-cancelled");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        cancel(id, null);
    }

    /** Blocks other plugins' teleport-request commands (EssentialsX /tpa, /call, ...). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event.getPlayer().hasPermission("aquatic.crews.admin")) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        for (String blocked : cfg().getStringList("blocked-commands")) {
            if (label.equals(blocked.toLowerCase(Locale.ROOT).replace("/", "").trim())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(module.msg("tp-blocked-command"));
                return;
            }
        }
    }

    @SuppressWarnings("unused")
    private static String plain(Component c) {
        return Text.plain(c);
    }
}
