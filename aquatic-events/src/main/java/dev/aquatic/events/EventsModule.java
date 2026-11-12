package dev.aquatic.events;

import dev.aquatic.api.IslandService;
import dev.aquatic.api.event.BountyClaimEvent;
import dev.aquatic.api.event.CrewDisbandEvent;
import dev.aquatic.api.event.TreasureFoundEvent;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.events.sea.BountyHunt;
import dev.aquatic.events.sea.GrandRaid;
import dev.aquatic.events.sea.KingsChallenge;
import dev.aquatic.events.sea.LostIsland;
import dev.aquatic.events.sea.NavalBattle;
import dev.aquatic.events.sea.PirateWar;
import dev.aquatic.events.sea.SeaEvent;
import dev.aquatic.events.sea.TreasureHunt;
import dev.aquatic.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/** The seven server events, started by staff or by the optional auto-scheduler. */
public final class EventsModule extends AquaticModule implements Listener {

    private static final Map<String, Function<EventsModule, SeaEvent>> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("treasure_hunt", TreasureHunt::new);
        TYPES.put("pirate_war", PirateWar::new);
        TYPES.put("bounty_hunt", BountyHunt::new);
        TYPES.put("lost_island", LostIsland::new);
        TYPES.put("kings_challenge", KingsChallenge::new);
        TYPES.put("grand_raid", GrandRaid::new);
        TYPES.put("naval_battle", NavalBattle::new);
    }

    private final List<SeaEvent> running = new ArrayList<>();
    private YamlConfiguration locations;
    private YamlConfiguration state;
    private long nextAutoAt;

    @Override
    public String id() {
        return "events";
    }

    @Override
    protected void onEnable() {
        running.clear();
        locations = loadData("locations");
        state = loadData("state");
        cleanupLeftovers();
        registerListener(this);
        registerCommands();
        resetSchedule();
        runTimer(this::tick, 20L, 20L);
    }

    @Override
    protected void onDisable() {
        for (SeaEvent e : new ArrayList<>(running)) {
            e.cancel(true);
        }
        running.clear();
        saveData("state", state);
        saveData("locations", locations);
    }

    // ------------------------------------------------------------------ leftovers from a crash/restart

    private void cleanupLeftovers() {
        List<String> islands = new ArrayList<>(state.getStringList("temporary-islands"));
        IslandService is = service(IslandService.class);
        if (!islands.isEmpty() && is != null) {
            for (String id : islands) {
                if (is.removeIsland(id, true)) {
                    logger().info("[events] Removed leftover event island '" + id + "'.");
                }
            }
            state.set("temporary-islands", List.of());
            saveData("state", state);
        }
        NamespacedKey tag = GrandRaid.tag(this);
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            for (LivingEntity e : w.getLivingEntities()) {
                if (e.getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) {
                    e.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            logger().info("[events] Removed " + removed + " leftover raid mobs.");
        }
    }

    public void trackTemporaryIsland(String id, boolean temporary) {
        if (!temporary) {
            return;
        }
        List<String> list = new ArrayList<>(state.getStringList("temporary-islands"));
        if (!list.contains(id)) {
            list.add(id);
        }
        state.set("temporary-islands", list);
        saveData("state", state);
    }

    public void untrackTemporaryIsland(String id, boolean handled) {
        if (!handled) {
            return;
        }
        List<String> list = new ArrayList<>(state.getStringList("temporary-islands"));
        if (list.remove(id)) {
            state.set("temporary-islands", list);
            saveData("state", state);
        }
    }

    // ------------------------------------------------------------------ locations

    /** A location set with /seaevent setlocation &lt;event&gt; [name], or null. */
    public Location location(String event, String name) {
        return Util.locFromString(locations.getString(event + "." + name.toLowerCase(Locale.ROOT)));
    }

    // ------------------------------------------------------------------ running events

    public List<SeaEvent> running() {
        return running;
    }

    public void eventEnded(SeaEvent event) {
        running.remove(event);
    }

    private SeaEvent runningOf(String type) {
        for (SeaEvent e : running) {
            if (e.type().equals(type)) {
                return e;
            }
        }
        return null;
    }

    /** Starts an event; throws CommandFail with a clear reason when it cannot start. */
    public SeaEvent start(CommandSender sender, String type, String[] args, boolean ignoreMinPlayers) {
        type = type.toLowerCase(Locale.ROOT);
        Function<EventsModule, SeaEvent> factory = TYPES.get(type);
        if (factory == null) {
            throw new CommandFail(rawMsg("unknown-type", "type", type, "types", String.join(", ", TYPES.keySet())));
        }
        if (!config().getBoolean("events." + type + ".enabled", true)) {
            throw new CommandFail(rawMsg("type-disabled", "type", type));
        }
        if (runningOf(type) != null) {
            throw new CommandFail(rawMsg("already-running", "type", type));
        }
        int max = Math.max(1, config().getInt("max-concurrent", 1));
        if (running.size() >= max) {
            throw new CommandFail(rawMsg("too-many", "max", max));
        }
        int minPlayers = config().getInt("events." + type + ".min-players", 1);
        if (!ignoreMinPlayers && Bukkit.getOnlinePlayers().size() < minPlayers) {
            throw new CommandFail(rawMsg("not-enough-players", "min", minPlayers));
        }
        SeaEvent event = factory.apply(this);
        event.start(sender, args);
        running.add(event);
        return event;
    }

    private void tick() {
        for (SeaEvent e : new ArrayList<>(running)) {
            try {
                e.tick();
            } catch (RuntimeException ex) {
                logger().log(java.util.logging.Level.SEVERE, "[events] Error in event " + e.type() + ", stopping it.", ex);
                e.cancel(true);
            }
        }
        if (schedulerOn() && System.currentTimeMillis() >= nextAutoAt) {
            resetSchedule();
            autoStart();
        }
    }

    private boolean schedulerOn() {
        if (state.contains("scheduler-enabled")) {
            return state.getBoolean("scheduler-enabled");
        }
        return config().getBoolean("scheduler.enabled", false);
    }

    private void resetSchedule() {
        long minutes = Math.max(1, config().getLong("scheduler.interval-minutes", 90));
        nextAutoAt = System.currentTimeMillis() + minutes * 60_000L;
    }

    private void autoStart() {
        int online = Bukkit.getOnlinePlayers().size();
        if (online < config().getInt("scheduler.min-players", 5) || running.size() >= Math.max(1, config().getInt("max-concurrent", 1))) {
            return;
        }
        ConfigurationSection weights = config().getConfigurationSection("scheduler.weights");
        Map<String, Integer> pool = new LinkedHashMap<>();
        for (String type : TYPES.keySet()) {
            int w = weights == null ? 1 : weights.getInt(type, 0);
            if (w > 0 && config().getBoolean("events." + type + ".enabled", true) && runningOf(type) == null
                    && online >= config().getInt("events." + type + ".min-players", 1)) {
                pool.put(type, w);
            }
        }
        while (!pool.isEmpty()) {
            int total = pool.values().stream().mapToInt(Integer::intValue).sum();
            int roll = ThreadLocalRandom.current().nextInt(total);
            String pick = null;
            for (Map.Entry<String, Integer> e : pool.entrySet()) {
                roll -= e.getValue();
                if (roll < 0) {
                    pick = e.getKey();
                    break;
                }
            }
            pool.remove(pick);
            try {
                start(Bukkit.getConsoleSender(), pick, new String[0], false);
                logger().info("[events] Scheduler started event " + pick);
                return;
            } catch (CommandFail fail) {
                logger().info("[events] Scheduler skipped " + pick + ": " + fail.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ dispatch to running events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        for (SeaEvent e : new ArrayList<>(running)) {
            e.onPlayerDeath(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event instanceof PlayerDeathEvent) {
            return;
        }
        for (SeaEvent e : new ArrayList<>(running)) {
            e.onEntityDeath(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        for (SeaEvent e : new ArrayList<>(running)) {
            e.onDamage(event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        for (SeaEvent e : new ArrayList<>(running)) {
            e.onCombust(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTreasure(TreasureFoundEvent event) {
        for (SeaEvent e : new ArrayList<>(running)) {
            e.onTreasureFound(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBounty(BountyClaimEvent event) {
        for (SeaEvent e : new ArrayList<>(running)) {
            e.onBountyClaim(event);
        }
    }

    @EventHandler
    public void onDisband(CrewDisbandEvent event) {
        for (SeaEvent e : new ArrayList<>(running)) {
            e.onCrewDisband(event);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        for (SeaEvent e : running) {
            e.showBar(event.getPlayer());
        }
        if (!running.isEmpty()) {
            Player p = event.getPlayer();
            runLater(() -> {
                if (p.isOnline() && !running.isEmpty()) {
                    send(p, "join-notice", "count", running.size());
                }
            }, 60L);
        }
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        String admin = "aquatic.events.admin";
        AquaticCommand cmd = new AquaticCommand("seaevent", "Server events", "event", "sevent");

        cmd.sub("start", (s, a) -> {
            AquaticCommand.need(a, 1, "/seaevent start <type> [args]");
            String[] rest = java.util.Arrays.copyOfRange(a, 1, a.length);
            SeaEvent e = start(s, a[0], rest, true);
            send(s, "started", "event", e.displayName());
        }).usage("<type> [args]").description("Start an event").permission(admin)
                .completer((s, a) -> {
                    if (a.length == 1) {
                        return AquaticCommand.filter(TYPES.keySet(), a[0]);
                    }
                    String t = a[0].toLowerCase(Locale.ROOT);
                    if (t.equals("treasure_hunt") && a.length == 2) {
                        dev.aquatic.api.TreasureService ts = service(dev.aquatic.api.TreasureService.class);
                        return ts == null ? List.of() : AquaticCommand.filter(ts.tiers(), a[1]);
                    }
                    if (t.equals("pirate_war") && a.length <= 3) {
                        dev.aquatic.api.CrewService cs = service(dev.aquatic.api.CrewService.class);
                        List<String> names = new ArrayList<>();
                        if (cs != null) {
                            cs.crews().forEach(c -> names.add(c.name()));
                        }
                        return AquaticCommand.filter(names, a[a.length - 1]);
                    }
                    if (t.equals("grand_raid") && a.length == 2) {
                        List<String> opts = new ArrayList<>();
                        ConfigurationSection sec = locations.getConfigurationSection("grand_raid");
                        if (sec != null) {
                            opts.addAll(sec.getKeys(false));
                        }
                        IslandService is = service(IslandService.class);
                        if (is != null) {
                            is.islands().forEach(i -> opts.add(i.id()));
                        }
                        return AquaticCommand.filter(opts, a[1]);
                    }
                    return List.of();
                });

        cmd.sub("stop", (s, a) -> {
            if (running.isEmpty()) {
                throw new CommandFail(rawMsg("none-running"));
            }
            if (a.length == 0) {
                for (SeaEvent e : new ArrayList<>(running)) {
                    e.cancel(true);
                }
                send(s, "stopped-all");
                return;
            }
            SeaEvent e = runningOf(a[0].toLowerCase(Locale.ROOT));
            if (e == null) {
                throw new CommandFail(rawMsg("not-running", "type", a[0]));
            }
            if (a.length > 1 && a[1].equalsIgnoreCase("finish")) {
                e.finish(false);
            } else {
                e.cancel(true);
            }
            send(s, "stopped", "event", e.displayName());
        }).usage("[type] [finish]").description("Stop events (finish = end with results)").permission(admin)
                .completer((s, a) -> {
                    if (a.length == 1) {
                        return AquaticCommand.filter(running.stream().map(SeaEvent::type).toList(), a[0]);
                    }
                    return a.length == 2 ? AquaticCommand.filter(List.of("finish"), a[1]) : List.of();
                });

        cmd.sub("list", (s, a) -> {
            send(s, "list-header");
            for (String type : TYPES.keySet()) {
                boolean enabled = config().getBoolean("events." + type + ".enabled", true);
                boolean on = runningOf(type) != null;
                s.sendMessage(rawMsg("list-entry", "type", type,
                        "name", Text.mm(config().getString("events." + type + ".display-name", type)),
                        "state", Text.plain(rawMsg(on ? "state-running" : enabled ? "state-ready" : "state-disabled")),
                        "min", config().getInt("events." + type + ".min-players", 1)));
            }
            s.sendMessage(rawMsg("list-scheduler", "state", Text.plain(rawMsg(schedulerOn() ? "state-on" : "state-off")),
                    "time", Text.duration(Math.max(0, (nextAutoAt - System.currentTimeMillis()) / 1000))));
        }).description("List event types").permission(admin);

        cmd.sub("info", (s, a) -> {
            if (running.isEmpty()) {
                send(s, "none-running");
                return;
            }
            send(s, "info-header", "count", running.size());
            for (SeaEvent e : running) {
                s.sendMessage(e.info());
            }
        }).description("Show running events").playerPermission("aquatic.events.info");

        cmd.sub("join", (s, a) -> {
            Player p = AquaticCommand.player(s);
            if (running.isEmpty()) {
                throw new CommandFail(rawMsg("none-running"));
            }
            SeaEvent e = a.length > 0 ? runningOf(a[0].toLowerCase(Locale.ROOT)) : running.get(0);
            if (e == null) {
                throw new CommandFail(rawMsg("not-running", "type", a[0]));
            }
            e.join(p);
        }).usage("[type]").description("Join a running event").playerPermission("aquatic.events.join").playerOnly()
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(running.stream().map(SeaEvent::type).toList(), a[0]) : List.of());

        cmd.sub("schedule", (s, a) -> {
            AquaticCommand.need(a, 1, "/seaevent schedule <on|off>");
            boolean on = a[0].equalsIgnoreCase("on");
            if (!on && !a[0].equalsIgnoreCase("off")) {
                throw new CommandFail("<red>Usage: <white>/seaevent schedule <on|off>");
            }
            state.set("scheduler-enabled", on);
            saveData("state", state);
            resetSchedule();
            send(s, on ? "scheduler-on" : "scheduler-off",
                    "minutes", config().getLong("scheduler.interval-minutes", 90));
        }).usage("<on|off>").description("Toggle the automatic event scheduler").permission(admin)
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(List.of("on", "off"), a[0]) : List.of());

        cmd.sub("setlocation", (s, a) -> {
            Player p = AquaticCommand.player(s);
            AquaticCommand.need(a, 1, "/seaevent setlocation <event> [name]");
            String type = a[0].toLowerCase(Locale.ROOT);
            if (!TYPES.containsKey(type)) {
                throw new CommandFail(rawMsg("unknown-type", "type", type, "types", String.join(", ", TYPES.keySet())));
            }
            String name = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : type.equals("kings_challenge") ? "hill" : "default";
            if (!name.matches("[a-z0-9_-]{1,32}")) {
                throw new CommandFail(rawMsg("invalid-name"));
            }
            if (a.length > 2 && a[2].equalsIgnoreCase("remove")) {
                locations.set(type + "." + name, null);
                saveData("locations", locations);
                send(s, "location-removed", "event", type, "name", name);
                return;
            }
            locations.set(type + "." + name, Util.locToString(p.getLocation()));
            saveData("locations", locations);
            send(s, "location-set", "event", type, "name", name, "coords", Util.coords(p.getLocation()));
        }).usage("<event> [name] [remove]").description("Store a location used by an event").permission(admin).playerOnly()
                .completer((s, a) -> {
                    if (a.length == 1) {
                        return AquaticCommand.filter(TYPES.keySet(), a[0]);
                    }
                    if (a.length == 2) {
                        List<String> opts = new ArrayList<>(List.of("default", "hill"));
                        ConfigurationSection sec = locations.getConfigurationSection(a[0].toLowerCase(Locale.ROOT));
                        if (sec != null) {
                            opts.addAll(sec.getKeys(false));
                        }
                        return AquaticCommand.filter(opts, a[1]);
                    }
                    return a.length == 3 ? AquaticCommand.filter(List.of("remove"), a[2]) : List.of();
                });
        registerCommand(cmd);
    }
}
