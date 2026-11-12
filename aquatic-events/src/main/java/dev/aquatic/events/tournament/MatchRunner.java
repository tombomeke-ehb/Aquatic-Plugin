package dev.aquatic.events.tournament;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewService;
import dev.aquatic.events.TournamentModule;
import dev.aquatic.events.Util;
import dev.aquatic.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Runs one arena fight at a time: teleport, freeze countdown, elimination, time limit, restore. */
public final class MatchRunner implements Listener {

    private enum Phase { COUNTDOWN, FIGHT }

    private final TournamentModule module;
    /** Saved states of everyone taken into a match, until restored. Persisted. */
    private final Map<UUID, SavedState> pending = new HashMap<>();

    // ---- active match
    private BracketMatch match;
    private Arena arena;
    private Phase phase;
    private String crewA;
    private String crewB;
    private final Map<UUID, String> side = new LinkedHashMap<>();
    private final Set<UUID> alive = new HashSet<>();
    private int countdown;
    private long endsAt;
    private int game;
    private boolean finale;
    private boolean kitMode;
    private BossBar bar;
    private boolean teleportingInternally;

    public MatchRunner(TournamentModule module) {
        this.module = module;
    }

    public boolean active() {
        return match != null;
    }

    public BracketMatch current() {
        return match;
    }

    public boolean involves(String crewId) {
        return match != null && (crewId.equals(crewA) || crewId.equals(crewB));
    }

    public boolean isFighter(UUID player) {
        return match != null && side.containsKey(player);
    }

    // ------------------------------------------------------------------ persistence of pending restores

    public void loadPending() {
        pending.clear();
        YamlConfiguration data = module.loadData("restore");
        for (String key : data.getKeys(false)) {
            ConfigurationSection s = data.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            try {
                pending.put(UUID.fromString(key), SavedState.load(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            restore(p);
        }
    }

    private void savePending() {
        YamlConfiguration data = new YamlConfiguration();
        pending.forEach((uuid, st) -> st.save(data.createSection(uuid.toString())));
        module.saveData("restore", data);
    }

    // ------------------------------------------------------------------ fighters

    /** Online fighters a crew can field right now (roster first, optionally filled with other online members). */
    public List<Player> availableFighters(Entry entry) {
        List<Player> out = new ArrayList<>();
        CrewService crews = module.service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewById(entry.crewId);
        if (crew == null) {
            return out;
        }
        int size = Math.max(1, module.config().getInt("teams.size", 3));
        for (UUID id : entry.roster) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !p.isDead() && crew.members().contains(id) && out.size() < size) {
                out.add(p);
            }
        }
        if (module.config().getBoolean("teams.fill-missing", true)) {
            for (Player p : crew.onlineMembers()) {
                if (out.size() >= size) {
                    break;
                }
                if (!out.contains(p) && !p.isDead()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ start

    public void start(BracketMatch m, Arena arena, Entry a, Entry b, List<Player> fightersA, List<Player> fightersB,
                      boolean finale, int game) {
        this.match = m;
        this.arena = arena;
        this.crewA = a.crewId;
        this.crewB = b.crewId;
        this.finale = finale;
        this.game = game;
        this.phase = Phase.COUNTDOWN;
        this.countdown = Math.max(0, module.config().getInt("match.countdown-seconds", 5));
        this.kitMode = "kit".equalsIgnoreCase(module.config().getString("match.gear", "own-gear"));
        side.clear();
        alive.clear();
        bar = BossBar.bossBar(Component.empty(), 1f, finale ? BossBar.Color.YELLOW : BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        for (Player p : fightersA) {
            enter(p, crewA, arena.spawn1);
        }
        for (Player p : fightersB) {
            enter(p, crewB, arena.spawn2);
        }
        savePending();
        updateBar();
        if (countdown == 0) {
            beginFight();
        }
    }

    private void enter(Player p, String crewId, Location spawn) {
        if (!pending.containsKey(p.getUniqueId())) {
            pending.put(p.getUniqueId(), SavedState.capture(p, kitMode));
        }
        side.put(p.getUniqueId(), crewId);
        alive.add(p.getUniqueId());
        if (p.isInsideVehicle()) {
            p.leaveVehicle();
        }
        teleportingInternally = true;
        try {
            p.teleport(spawn);
        } finally {
            teleportingInternally = false;
        }
        p.setGameMode(GameMode.SURVIVAL);
        heal(p);
        if (kitMode) {
            giveKit(p);
        }
        p.showBossBar(bar);
    }

    private void heal(Player p) {
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(10);
        p.setFireTicks(0);
        p.setFallDistance(0);
        if (module.config().getBoolean("match.clear-effects", true)) {
            for (PotionEffect e : p.getActivePotionEffects()) {
                p.removePotionEffect(e.getType());
            }
        }
    }

    private void giveKit(Player p) {
        ConfigurationSection kit = module.config().getConfigurationSection("match.kit");
        p.getInventory().clear();
        p.setLevel(0);
        p.setExp(0);
        if (kit == null) {
            return;
        }
        for (String spec : kit.getStringList("items")) {
            ItemStack item = Util.parseItem(module, spec);
            if (item != null) {
                p.getInventory().addItem(item);
            }
        }
        ConfigurationSection armor = kit.getConfigurationSection("armor");
        if (armor != null) {
            p.getInventory().setItem(EquipmentSlot.HEAD, orAir(Util.parseItem(module, armor.getString("helmet"))));
            p.getInventory().setItem(EquipmentSlot.CHEST, orAir(Util.parseItem(module, armor.getString("chestplate"))));
            p.getInventory().setItem(EquipmentSlot.LEGS, orAir(Util.parseItem(module, armor.getString("leggings"))));
            p.getInventory().setItem(EquipmentSlot.FEET, orAir(Util.parseItem(module, armor.getString("boots"))));
            p.getInventory().setItem(EquipmentSlot.OFF_HAND, orAir(Util.parseItem(module, armor.getString("offhand"))));
        }
        int ticks = Integer.MAX_VALUE;
        for (PotionEffect e : Util.parseEffects(kit.getStringList("effects"), ticks)) {
            p.addPotionEffect(e);
        }
    }

    private static ItemStack orAir(ItemStack s) {
        return s == null ? new ItemStack(Material.AIR) : s;
    }

    private void beginFight() {
        phase = Phase.FIGHT;
        endsAt = System.currentTimeMillis() + Math.max(10, module.config().getLong("match.time-limit-seconds", 300)) * 1000L;
        for (UUID id : side.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.showTitle(Title.title(module.rawMsg("fight-title"), Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(300))));
                Util.sound(p, "entity.ender_dragon.growl", 0.7f, 1.2f);
            }
        }
        checkWin();
    }

    // ------------------------------------------------------------------ ticking

    /** Called once per second. */
    public void tick() {
        if (match == null) {
            return;
        }
        if (phase == Phase.COUNTDOWN) {
            for (UUID id : side.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && countdown > 0) {
                    p.showTitle(Title.title(module.rawMsg("countdown-title", "seconds", countdown), module.rawMsg("countdown-subtitle"),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
                    Util.sound(p, "block.note_block.hat", 1f, 1f);
                }
            }
            if (--countdown <= 0) {
                beginFight();
            }
            updateBar();
            return;
        }
        updateBar();
        if (System.currentTimeMillis() >= endsAt) {
            suddenDeath();
        }
    }

    private void updateBar() {
        if (bar == null) {
            return;
        }
        long total = Math.max(10, module.config().getLong("match.time-limit-seconds", 300));
        long left = phase == Phase.FIGHT ? Math.max(0, (endsAt - System.currentTimeMillis()) / 1000) : total;
        bar.name(module.rawMsg(finale ? "bossbar-finale" : "bossbar-match",
                "a", module.crewName(crewA), "b", module.crewName(crewB),
                "alive_a", count(crewA), "alive_b", count(crewB), "time", Text.duration(left), "game", game));
        bar.progress(Math.max(0f, Math.min(1f, (float) left / total)));
    }

    private int count(String crewId) {
        int n = 0;
        for (UUID id : alive) {
            if (crewId.equals(side.get(id))) {
                n++;
            }
        }
        return n;
    }

    private double health(String crewId) {
        double h = 0;
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && crewId.equals(side.get(id))) {
                h += p.getHealth();
            }
        }
        return h;
    }

    private void suddenDeath() {
        int a = count(crewA), b = count(crewB);
        String winner;
        if (a != b) {
            winner = a > b ? crewA : crewB;
        } else {
            double ha = health(crewA), hb = health(crewB);
            if (Math.abs(ha - hb) > 0.01) {
                winner = ha > hb ? crewA : crewB;
            } else {
                winner = ThreadLocalRandom.current().nextBoolean() ? crewA : crewB;
            }
        }
        finish(winner, "time-up");
    }

    private void checkWin() {
        if (match == null || phase != Phase.FIGHT) {
            return;
        }
        int a = count(crewA), b = count(crewB);
        if (a == 0 && b == 0) {
            finish(ThreadLocalRandom.current().nextBoolean() ? crewA : crewB, "both-eliminated");
        } else if (a == 0) {
            finish(crewB, "eliminated");
        } else if (b == 0) {
            finish(crewA, "eliminated");
        }
    }

    // ------------------------------------------------------------------ elimination

    private void eliminate(Player p, Player killer, boolean quit) {
        if (!alive.remove(p.getUniqueId())) {
            return;
        }
        if (killer != null) {
            module.broadcastMatch("fighter-eliminated-by", "player", p.getName(), "killer", killer.getName());
        } else {
            module.broadcastMatch(quit ? "fighter-quit" : "fighter-eliminated", "player", p.getName());
        }
        if (!quit && p.isOnline()) {
            heal(p);
            p.setGameMode(GameMode.SPECTATOR);
            Location watch = arena.watchPoint();
            if (watch != null && !p.isDead()) {
                teleportingInternally = true;
                try {
                    p.teleport(watch);
                } finally {
                    teleportingInternally = false;
                }
            }
        }
        if (phase == Phase.COUNTDOWN) {
            // keep counting; win is decided when the fight begins
            return;
        }
        checkWin();
    }

    /** Ends the match with a result. */
    public void finish(String winner, String reason) {
        if (match == null) {
            return;
        }
        BracketMatch m = match;
        restoreAll();
        module.matchFinished(m, winner, reason);
    }

    /** Aborts the match without a result (cancel, shutdown, reload). Everyone is restored. */
    public void abort() {
        if (match != null) {
            restoreAll();
        }
    }

    public void crewGone(String crewId) {
        if (involves(crewId)) {
            finish(crewId.equals(crewA) ? crewB : crewA, "disbanded");
        }
    }

    private void restoreAll() {
        for (UUID id : side.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(bar);
                if (!p.isDead()) {
                    restore(p);
                }
            }
        }
        side.clear();
        alive.clear();
        match = null;
        arena = null;
        bar = null;
        savePending();
    }

    /** Gives back whatever was taken for a match (no-op when nothing is pending). */
    public void restore(Player p) {
        SavedState st = pending.remove(p.getUniqueId());
        if (st == null) {
            return;
        }
        Location fallback = p.getWorld().getSpawnLocation();
        if ("lobby".equalsIgnoreCase(module.config().getString("match.return-to", "previous")) && module.lobby() != null) {
            st.location = module.lobby();
        }
        teleportingInternally = true;
        try {
            st.restore(p, fallback);
        } finally {
            teleportingInternally = false;
        }
        savePending();
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (match == null || !(event.getEntity() instanceof Player victim) || !side.containsKey(victim.getUniqueId())) {
            return;
        }
        if (phase != Phase.FIGHT || !alive.contains(victim.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        Player attacker = null;
        if (event instanceof EntityDamageByEntityEvent by) {
            Entity d = by.getDamager();
            if (d instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
                attacker = shooter;
            } else if (d instanceof Player pd) {
                attacker = pd;
            }
            if (attacker != null) {
                String as = side.get(attacker.getUniqueId());
                if (as == null || !alive.contains(attacker.getUniqueId()) || as.equals(side.get(victim.getUniqueId()))) {
                    event.setCancelled(true);
                    return;
                }
                // a legitimate tournament hit: override protections (safe zones, crew relations)
                event.setCancelled(false);
            }
        }
        if (event.isCancelled()) {
            return;
        }
        if (event.getFinalDamage() >= victim.getHealth()) {
            event.setCancelled(true);
            Util.sound(victim.getLocation(), "entity.player.death", 1f, 1f);
            eliminate(victim, attacker, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (match == null || !side.containsKey(p.getUniqueId())) {
            if (pending.containsKey(p.getUniqueId())) {
                keep(event);
            }
            return;
        }
        keep(event);
        eliminate(p, p.getKiller(), false);
    }

    private static void keep(PlayerDeathEvent event) {
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        if (match != null && side.containsKey(p.getUniqueId()) && arena != null && arena.watchPoint() != null) {
            event.setRespawnLocation(arena.watchPoint());
            module.runLater(() -> {
                if (p.isOnline() && match != null && side.containsKey(p.getUniqueId())) {
                    p.setGameMode(GameMode.SPECTATOR);
                }
            }, 1L);
        } else if (pending.containsKey(p.getUniqueId())) {
            module.runLater(() -> {
                if (p.isOnline() && !isFighter(p.getUniqueId())) {
                    restore(p);
                }
            }, 1L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (match == null || phase != Phase.COUNTDOWN || !alive.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom(), to = event.getTo();
        if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            event.setTo(back);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (match == null || teleportingInternally || !alive.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT
                || cause == PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            if (event.getTo().getWorld() == null || !event.getTo().getWorld().equals(arena.spawn1.getWorld())
                    || event.getTo().distanceSquared(arena.spawn1) > 200 * 200) {
                event.setCancelled(true);
            }
            return;
        }
        if (module.config().getBoolean("match.block-teleports", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (match == null || !side.containsKey(p.getUniqueId()) || p.hasPermission("aquatic.tournament.admin")
                || !module.config().getBoolean("match.block-commands", true)) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) {
            label = label.substring(label.indexOf(':') + 1);
        }
        for (String allowed : module.config().getStringList("match.allowed-commands")) {
            if (allowed.equalsIgnoreCase(label)) {
                return;
            }
        }
        event.setCancelled(true);
        module.send(p, "command-blocked");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (match == null || !side.containsKey(p.getUniqueId())) {
            return;
        }
        if (bar != null) {
            p.hideBossBar(bar);
        }
        boolean wasAlive = alive.remove(p.getUniqueId());
        side.remove(p.getUniqueId());
        if (wasAlive) {
            module.broadcastMatch("fighter-quit", "player", p.getName());
            if (phase == Phase.FIGHT) {
                checkWin();
            }
        }
        // the saved state stays pending and is restored when the player comes back
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (pending.containsKey(p.getUniqueId())) {
            module.runLater(() -> {
                if (p.isOnline() && !isFighter(p.getUniqueId())) {
                    restore(p);
                }
            }, 5L);
        }
    }
}
