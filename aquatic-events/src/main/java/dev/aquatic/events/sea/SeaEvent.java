package dev.aquatic.events.sea;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.event.BountyClaimEvent;
import dev.aquatic.api.event.CrewDisbandEvent;
import dev.aquatic.api.event.TreasureFoundEvent;
import dev.aquatic.command.CommandFail;
import dev.aquatic.events.EventsModule;
import dev.aquatic.events.Util;
import dev.aquatic.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Base class of a server event. Lifecycle: {@link #begin} (may refuse with CommandFail) -> {@link #onTick}
 * once per second -> {@link #onTimeUp} / {@link #finish} -> {@link #conclude} (results and rewards) ->
 * {@link #cleanup} (always runs exactly once, also when cancelled or when the module is disabled).
 */
public abstract class SeaEvent {

    protected final EventsModule module;
    protected final String type;
    protected final ConfigurationSection cfg;
    protected final Set<UUID> participants = new HashSet<>();
    protected long startedAt;
    protected long endsAt;
    private BossBar bar;
    private boolean over;

    protected SeaEvent(EventsModule module, String type) {
        this.module = module;
        this.type = type;
        ConfigurationSection c = module.config().getConfigurationSection("events." + type);
        this.cfg = c != null ? c : new MemoryConfiguration();
    }

    public String type() {
        return type;
    }

    public Component displayName() {
        return Text.mm(cfg.getString("display-name", type));
    }

    public boolean isOver() {
        return over;
    }

    public long secondsLeft() {
        return Math.max(0, (endsAt - System.currentTimeMillis()) / 1000);
    }

    // ------------------------------------------------------------------ to implement

    /** Prepares the event. Throw {@link CommandFail} to refuse starting (nothing must be left behind then). */
    protected abstract void begin(CommandSender sender, String[] args);

    /** Results, announcements and rewards. timeUp = the timer ran out (as opposed to an objective being reached). */
    protected abstract void conclude(boolean timeUp);

    /** Releases everything the event created in the world. Runs exactly once. */
    protected void cleanup() {
    }

    /** Once per second while running. */
    protected void onTick() {
    }

    /** @return true to finish now; false when the event extended itself (e.g. a sinking countdown). */
    protected boolean onTimeUp() {
        return true;
    }

    /** One line for /seaevent info and the boss bar. */
    protected Component status() {
        return Component.empty();
    }

    /** Where /seaevent join teleports to (null = joining only registers you). */
    protected Location joinLocation() {
        return null;
    }

    // ------------------------------------------------------------------ hooks (forwarded by the module)

    public void onPlayerDeath(PlayerDeathEvent event) {
    }

    public void onEntityDeath(EntityDeathEvent event) {
    }

    public void onDamage(EntityDamageByEntityEvent event) {
    }

    public void onCombust(EntityCombustEvent event) {
    }

    public void onTreasureFound(TreasureFoundEvent event) {
    }

    public void onBountyClaim(BountyClaimEvent event) {
    }

    public void onCrewDisband(CrewDisbandEvent event) {
    }

    // ------------------------------------------------------------------ framework

    public final void start(CommandSender sender, String[] args) {
        long minutes = Math.max(1, cfg.getLong("duration-minutes", 20));
        startedAt = System.currentTimeMillis();
        endsAt = startedAt + minutes * 60_000L;
        begin(sender, args);
        if (cfg.getBoolean("bossbar", true)) {
            bar = BossBar.bossBar(Component.empty(), 1f, color(), BossBar.Overlay.PROGRESS);
            updateBar();
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showBossBar(bar);
            }
        }
        Util.broadcast(module.msg("event-started", "event", displayName(),
                "description", Text.mm(cfg.getString("description", "")), "time", Text.duration(minutes * 60)));
        Util.soundAll(module.config().getString("sounds.start", "item.goat_horn.sound.2"), 1f, 1f);
    }

    private BossBar.Color color() {
        try {
            return BossBar.Color.valueOf(cfg.getString("bossbar-color", "BLUE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.BLUE;
        }
    }

    public final void tick() {
        if (over) {
            return;
        }
        if (System.currentTimeMillis() >= endsAt) {
            if (onTimeUp()) {
                finish(true);
                return;
            }
        }
        onTick();
        if (!over) {
            updateBar();
        }
    }

    protected void updateBar() {
        if (bar == null) {
            return;
        }
        long total = Math.max(1, endsAt - startedAt);
        bar.name(barTitle());
        bar.progress(Math.max(0f, Math.min(1f, (float) (endsAt - System.currentTimeMillis()) / total)));
    }

    protected Component barTitle() {
        return module.rawMsg("bossbar", "event", displayName(), "time", Text.duration(secondsLeft()), "status", status());
    }

    protected final BossBar bar() {
        return bar;
    }

    public final void showBar(Player p) {
        if (bar != null) {
            p.showBossBar(bar);
        }
    }

    /** Ends the event with results. */
    public final void finish(boolean timeUp) {
        if (over) {
            return;
        }
        over = true;
        try {
            conclude(timeUp);
        } catch (RuntimeException e) {
            module.logger().log(java.util.logging.Level.SEVERE, "Error while concluding event " + type, e);
        }
        shutdown();
    }

    /** Ends the event without results (staff stop, module disabled). */
    public final void cancel(boolean announce) {
        if (over) {
            return;
        }
        over = true;
        if (announce) {
            Util.broadcast(module.msg("event-cancelled", "event", displayName()));
        }
        shutdown();
    }

    private void shutdown() {
        try {
            cleanup();
        } catch (RuntimeException e) {
            module.logger().log(java.util.logging.Level.SEVERE, "Error while cleaning up event " + type, e);
        }
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.hideBossBar(bar);
            }
            bar = null;
        }
        module.eventEnded(this);
    }

    public void join(Player p) {
        participants.add(p.getUniqueId());
        Location to = joinLocation();
        if (to != null && cfg.getBoolean("join-teleport", false)) {
            p.teleport(to);
            module.send(p, "joined-teleport", "event", displayName());
        } else {
            module.send(p, "joined", "event", displayName());
        }
    }

    public Component info() {
        return module.rawMsg("info-entry", "event", displayName(), "time", Text.duration(secondsLeft()), "status", status());
    }

    // ------------------------------------------------------------------ helpers

    protected final void announce(String key, Object... placeholders) {
        Util.broadcast(module.msg(key, placeholders));
    }

    protected final ConfigurationSection rewards(String section) {
        return cfg.getConfigurationSection("rewards." + section);
    }

    /** Player reward; "reputation" in the section goes to the player's crew. */
    protected final void rewardPlayer(Player p, String section, double multiplier) {
        ConfigurationSection r = rewards(section);
        if (r == null || p == null) {
            return;
        }
        Util.rewardPlayer(module, r, p, multiplier);
        int rep = r.getInt("reputation", 0);
        CrewService crews = module.service(CrewService.class);
        Crew c = crews == null ? null : crews.crewOf(p.getUniqueId());
        if (rep != 0 && c != null) {
            c.addReputation(rep);
        }
    }

    protected final void rewardCrew(Crew crew, String section) {
        Util.rewardCrew(module, rewards(section), crew);
    }

    protected final CrewService crews() {
        return module.service(CrewService.class);
    }

    protected final Crew crewOf(Player p) {
        CrewService crews = crews();
        return crews == null || p == null ? null : crews.crewOf(p.getUniqueId());
    }

    /** The player responsible for damage (direct hit or projectile). */
    protected static Player attacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    protected final CommandFail fail(String key, Object... placeholders) {
        return new CommandFail(module.rawMsg(key, placeholders));
    }

    protected final String moneyText(double amount) {
        return Util.money(module, amount);
    }
}
