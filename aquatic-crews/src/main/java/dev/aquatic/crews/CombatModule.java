package dev.aquatic.crews;

import dev.aquatic.api.TournamentService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fair PvP rules, all configurable in modules/combat.yml:
 * <ul>
 *   <li><b>Combat tag</b>: hitting or being hit by a player tags you for a few seconds. While tagged you
 *       cannot use escape commands (/crew home, /spawn, ...) and logging out kills you (items drop).</li>
 *   <li><b>Respawn protection</b>: a few seconds of PvP immunity after respawning (no spawn camping).</li>
 *   <li><b>New-player protection</b>: no PvP during the first minutes of playtime; /pvp on ends it early.</li>
 * </ul>
 * Tournament matches are left alone (the tournament handles its own deaths and logouts).
 */
public final class CombatModule extends AquaticModule implements Listener {

    private static final String BYPASS = "aquatic.combat.bypass";
    private static final String ADMIN = "aquatic.combat.admin";

    /** Tagged player -> tag end time (ms). */
    private final Map<UUID, Long> tagged = new HashMap<>();
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    /** Respawn protection end time (ms). */
    private final Map<UUID, Long> respawnShield = new HashMap<>();
    /** Players who gave up their new-player protection with /pvp on. */
    private final Set<UUID> optedOut = new HashSet<>();
    /** Player being punished for combat logging right now (their death must not re-tag anyone). */
    private UUID punishing;

    @Override
    public String id() {
        return "combat";
    }

    @Override
    protected void onEnable() {
        tagged.clear();
        lastAttacker.clear();
        bars.clear();
        respawnShield.clear();
        optedOut.clear();
        for (String raw : loadData("players").getStringList("pvp-enabled")) {
            try {
                optedOut.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }
        registerListener(this);
        runTimer(this::tick, 10L, 10L);
        registerCommands();
    }

    @Override
    protected void onDisable() {
        for (UUID id : new ArrayList<>(bars.keySet())) {
            hideBar(id);
        }
        tagged.clear();
        save();
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        optedOut.forEach(u -> list.add(u.toString()));
        data.set("pvp-enabled", list);
        saveData("players", data);
    }

    // ------------------------------------------------------------------ state helpers

    public boolean isTagged(UUID player) {
        Long until = tagged.get(player);
        return until != null && until > System.currentTimeMillis();
    }

    private long secondsLeft(Long until) {
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    /** Minutes of new-player protection left (0 = not protected). */
    long newbieMinutesLeft(Player p) {
        int limit = config().getInt("new-player-protection.minutes", 30);
        if (!config().getBoolean("new-player-protection.enabled", true) || limit <= 0 || optedOut.contains(p.getUniqueId())) {
            return 0;
        }
        long playedMinutes = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L; // the statistic counts ticks
        return Math.max(0, limit - playedMinutes);
    }

    private boolean respawnProtected(Player p) {
        Long until = respawnShield.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private boolean inMatch(Player p) {
        TournamentService t = service(TournamentService.class);
        return t != null && t.isInMatch(p.getUniqueId());
    }

    private static Player playerSource(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }
        if (damager instanceof Tameable pet && pet.isTamed() && pet.getOwner() instanceof Player p) {
            return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ protection (runs before other rules)

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProtect(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = playerSource(event.getDamager());
        if (attacker == null || attacker.equals(victim) || inMatch(victim)) {
            return;
        }
        // the attacker gives up their own respawn protection by attacking
        respawnShield.remove(attacker.getUniqueId());

        long attackerNewbie = newbieMinutesLeft(attacker);
        if (attackerNewbie > 0) {
            event.setCancelled(true);
            attacker.sendActionBar(rawMsg("you-are-protected", "minutes", attackerNewbie));
            return;
        }
        long victimNewbie = newbieMinutesLeft(victim);
        if (victimNewbie > 0) {
            event.setCancelled(true);
            attacker.sendActionBar(rawMsg("target-new-player", "player", victim.getName(), "minutes", victimNewbie));
            return;
        }
        if (respawnProtected(victim)) {
            event.setCancelled(true);
            attacker.sendActionBar(rawMsg("target-respawn-protected", "player", victim.getName(),
                    "seconds", secondsLeft(respawnShield.get(victim.getUniqueId()))));
        }
    }

    // ------------------------------------------------------------------ combat tag

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!config().getBoolean("combat-tag.enabled", true) || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = playerSource(event.getDamager());
        if (attacker == null || attacker.equals(victim) || inMatch(victim) || victim.getUniqueId().equals(punishing)) {
            return;
        }
        tag(victim, attacker);
        tag(attacker, victim);
    }

    private void tag(Player p, Player opponent) {
        if (p.hasPermission(BYPASS) || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        boolean wasTagged = isTagged(p.getUniqueId());
        long seconds = Math.max(1, config().getLong("combat-tag.seconds", 15));
        tagged.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000);
        lastAttacker.put(p.getUniqueId(), opponent.getUniqueId());
        if (!wasTagged) {
            send(p, "tagged", "player", opponent.getName(), "seconds", seconds);
        }
        updateBar(p);
    }

    private void untag(UUID id, boolean announce) {
        if (tagged.remove(id) != null) {
            lastAttacker.remove(id);
            hideBar(id);
            Player p = Bukkit.getPlayer(id);
            if (announce && p != null) {
                send(p, "untagged");
            }
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(tagged.keySet())) {
            if (tagged.get(id) <= now) {
                untag(id, true);
            } else {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    updateBar(p);
                }
            }
        }
        respawnShield.values().removeIf(until -> until <= now);
    }

    private void updateBar(Player p) {
        if (!config().getBoolean("combat-tag.bossbar", true)) {
            return;
        }
        long total = Math.max(1, config().getLong("combat-tag.seconds", 15));
        long left = secondsLeft(tagged.get(p.getUniqueId()));
        float progress = Math.max(0f, Math.min(1f, left / (float) total));
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(rawMsg("bossbar", "seconds", left), progress, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(rawMsg("bossbar", "seconds", left));
            bar.progress(progress);
        }
    }

    private void hideBar(UUID id) {
        BossBar bar = bars.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (bar != null && p != null) {
            p.hideBossBar(bar);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!isTagged(p.getUniqueId()) || p.hasPermission(BYPASS)) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) {
            label = label.substring(label.indexOf(':') + 1); // aquatic:crew -> crew
        }
        String full = label + (event.getMessage().contains(" ") ? " " + event.getMessage().split(" ", 2)[1].toLowerCase(Locale.ROOT) : "");
        for (String blocked : config().getStringList("combat-tag.blocked-commands")) {
            String b = blocked.toLowerCase(Locale.ROOT).trim();
            if (b.startsWith("/")) {
                b = b.substring(1);
            }
            if (full.equals(b) || full.startsWith(b + " ") || (!b.contains(" ") && label.equals(b))) {
                event.setCancelled(true);
                send(p, "command-blocked", "seconds", secondsLeft(tagged.get(p.getUniqueId())));
                return;
            }
        }
    }

    /** Ender pearls and chorus fruit stay allowed; teleports by plugins/commands are blocked while tagged. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player p = event.getPlayer();
        if (!config().getBoolean("combat-tag.block-teleports", true) || !isTagged(p.getUniqueId()) || p.hasPermission(BYPASS)) {
            return;
        }
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.COMMAND || cause == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            if (inMatch(p)) {
                return;
            }
            event.setCancelled(true);
            send(p, "teleport-blocked", "seconds", secondsLeft(tagged.get(p.getUniqueId())));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        boolean logged = isTagged(id) && !inMatch(p) && !p.isDead();
        UUID attacker = lastAttacker.get(id);
        untag(id, false);
        respawnShield.remove(id);
        if (!logged || !"kill".equalsIgnoreCase(config().getString("combat-tag.logout-punishment", "kill"))) {
            return;
        }
        Player killer = attacker == null ? null : Bukkit.getPlayer(attacker);
        punishing = id;
        try {
            if (killer != null) {
                p.damage(Math.max(1000, p.getHealth() * 10), killer); // the attacker gets the kill (and any bounty)
            }
            if (!p.isDead()) {
                p.setHealth(0);
            }
        } finally {
            punishing = null;
        }
        untag(id, false);
        if (config().getBoolean("combat-tag.announce-logout", true)) {
            Bukkit.broadcast(msg("combat-logged", "player", p.getName()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        untag(event.getEntity().getUniqueId(), false);
        // the killer is no longer "in combat" with someone who is dead
        Player killer = event.getEntity().getKiller();
        if (killer != null && event.getEntity().getUniqueId().equals(lastAttacker.get(killer.getUniqueId()))
                && config().getBoolean("combat-tag.untag-killer", true)) {
            untag(killer.getUniqueId(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        long seconds = config().getLong("respawn-protection.seconds", 10);
        if (!config().getBoolean("respawn-protection.enabled", true) || seconds <= 0) {
            return;
        }
        Player p = event.getPlayer();
        respawnShield.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000);
        runLater(() -> {
            if (p.isOnline()) {
                send(p, "respawn-protected", "seconds", seconds);
            }
        }, 2L);
    }

    // ------------------------------------------------------------------ /pvp

    private void registerCommands() {
        AquaticCommand cmd = new AquaticCommand("pvp", "Your PvP protection and combat status", "combat");
        cmd.defaultExecutor((sender, args) -> status(sender, AquaticCommand.player(sender)));
        cmd.sub("status", (s, a) -> status(s, a.length > 0 && s.hasPermission(ADMIN) ? AquaticCommand.onlinePlayer(a[0])
                        : AquaticCommand.player(s)))
                .usage("[player]").description("Show your protection and combat tag");
        cmd.sub("on", (s, a) -> {
            Player p = AquaticCommand.player(s);
            if (newbieMinutesLeft(p) <= 0) {
                throw new CommandFail(rawMsg("already-pvp"));
            }
            if (a.length == 0 || !a[0].equalsIgnoreCase("confirm")) {
                send(p, "pvp-on-confirm", "minutes", newbieMinutesLeft(p));
                return;
            }
            optedOut.add(p.getUniqueId());
            save();
            send(p, "pvp-on");
        }).usage("[confirm]").description("End your new-player protection early").playerOnly()
                .playerPermission("aquatic.combat.optout")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(List.of("confirm"), a[0]) : List.of());
        cmd.sub("untag", (s, a) -> {
            AquaticCommand.need(a, 1, "/pvp untag <player>");
            Player target = AquaticCommand.onlinePlayer(a[0]);
            untag(target.getUniqueId(), true);
            send(s, "admin-untagged", "player", target.getName());
        }).usage("<player>").description("Remove a combat tag").permission(ADMIN)
                .completer((s, a) -> a.length == 1 ? AquaticCommand.playerNames(a[0]) : List.of());
        cmd.sub("protect", (s, a) -> {
            AquaticCommand.need(a, 1, "/pvp protect <player>");
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(a[0]);
            if (target == null) {
                throw new CommandFail("<red>Unknown player: <white><name>", "name", a[0]);
            }
            optedOut.remove(target.getUniqueId());
            save();
            send(s, "admin-protect", "player", target.getName() == null ? a[0] : target.getName());
        }).usage("<player>").description("Give back new-player protection (if within the playtime limit)").permission(ADMIN)
                .completer((s, a) -> a.length == 1 ? AquaticCommand.playerNames(a[0]) : List.of());
        registerCommand(cmd);
    }

    private void status(org.bukkit.command.CommandSender to, Player p) {
        to.sendMessage(msg("status-header", "player", p.getName()));
        long newbie = newbieMinutesLeft(p);
        to.sendMessage(newbie > 0 ? rawMsg("status-newbie", "minutes", newbie) : rawMsg("status-newbie-off"));
        to.sendMessage(isTagged(p.getUniqueId())
                ? rawMsg("status-tagged", "seconds", secondsLeft(tagged.get(p.getUniqueId())))
                : rawMsg("status-not-tagged"));
        if (respawnProtected(p)) {
            to.sendMessage(rawMsg("status-respawn", "seconds", secondsLeft(respawnShield.get(p.getUniqueId()))));
        }
        to.sendMessage(Text.mm(" "));
    }
}
