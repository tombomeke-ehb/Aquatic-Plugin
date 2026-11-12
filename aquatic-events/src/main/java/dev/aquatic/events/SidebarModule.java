package dev.aquatic.events;

import dev.aquatic.api.BountyService;
import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.api.TournamentService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.events.items.ItemDef;
import dev.aquatic.events.sea.SeaEvent;
import dev.aquatic.util.Text;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The sidebar on the right side of the screen with the player's stats (modules/sidebar.yml).
 * Lines are updated in place (no flicker). While another plugin or the tournament shows its own
 * scoreboard, the sidebar steps aside and comes back afterwards. Players toggle it with /sidebar.
 */
public final class SidebarModule extends AquaticModule implements Listener {

    private static final int MAX_LINES = 15;

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();

    @Override
    public String id() {
        return "sidebar";
    }

    @Override
    protected void onEnable() {
        boards.clear();
        hidden.clear();
        for (String raw : loadData("players").getStringList("hidden")) {
            try {
                hidden.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }
        registerListener(this);
        registerCommands();
        long every = Math.max(10, config().getLong("update-ticks", 20));
        runTimer(this::updateAll, 20L, every);
    }

    @Override
    protected void onDisable() {
        for (UUID id : new ArrayList<>(boards.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                remove(p);
            }
        }
        boards.clear();
        save();
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        hidden.forEach(u -> list.add(u.toString()));
        data.set("hidden", list);
        saveData("players", data);
    }

    // ------------------------------------------------------------------ lifecycle per player

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        runLater(() -> update(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
    }

    private void remove(Player p) {
        Scoreboard board = boards.remove(p.getUniqueId());
        if (board != null && p.getScoreboard() == board) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void updateAll() {
        if (!config().getBoolean("enabled", true)) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            update(p);
        }
    }

    private void update(Player p) {
        if (!p.isOnline() || !config().getBoolean("enabled", true)) {
            return;
        }
        if (hidden.contains(p.getUniqueId())) {
            remove(p);
            return;
        }
        Scoreboard board = boards.get(p.getUniqueId());
        Scoreboard current = p.getScoreboard();
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = board.registerNewObjective("aquatic_side", Criteria.DUMMY, Text.mm(config().getString("title", "Aquatic SMP")));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            obj.numberFormat(NumberFormat.blank());
            boards.put(p.getUniqueId(), board);
        }
        if (current != board) {
            if (current != main) {
                return; // the tournament or another plugin shows its own scoreboard right now
            }
            p.setScoreboard(board);
        }
        Objective obj = board.getObjective("aquatic_side");
        if (obj == null) {
            return;
        }
        obj.displayName(Text.mm(config().getString("title", "Aquatic SMP")));
        List<Component> lines = render(p);
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = "line" + i;
            if (i < lines.size()) {
                Score score = obj.getScore(entry);
                score.setScore(MAX_LINES - i);
                score.customName(lines.get(i));
            } else if (obj.getScore(entry).isScoreSet()) {
                board.resetScores(entry);
            }
        }
    }

    // ------------------------------------------------------------------ content

    /**
     * Builds the lines from sidebar.yml. A line starting with "?crew:", "?island:", "?fruit:", "?event:",
     * "?bounty:" or "?conqueror:" is only shown when that applies to the player.
     */
    private List<Component> render(Player p) {
        UUID id = p.getUniqueId();
        EconomyService eco = service(EconomyService.class);
        BountyService bounties = service(BountyService.class);
        CrewService crews = service(CrewService.class);
        IslandService islands = service(IslandService.class);
        TournamentService tournament = service(TournamentService.class);

        Crew crew = crews == null ? null : crews.crewOf(id);
        Island island = islands == null ? null : islands.islandAt(p.getLocation());
        double bounty = bounties == null ? 0 : bounties.bountyOf(id);
        String fruit = fruitName(id);
        SeaEvent event = runningEvent();
        String conqueror = tournament == null ? null : tournament.pirateKingName();

        Map<String, Object> ph = new HashMap<>();
        ph.put("player", p.getName());
        ph.put("balance", eco == null ? "-" : eco.format(eco.balance(id)));
        ph.put("bounty", eco == null ? Text.number(bounty) : eco.format(bounty));
        ph.put("crew", crew == null ? rawMsg("none") : crew.displayName());
        ph.put("rank", crew == null || crew.rankOf(id) == null ? "-" : crew.rankOf(id).display());
        ph.put("reputation", crew == null ? "-" : String.valueOf(crew.reputation()));
        ph.put("island", island == null ? rawMsg("open-sea") : Component.text(island.name()));
        ph.put("island_owner", island == null ? Component.empty() : islandOwner(island, crews));
        ph.put("fruit", fruit == null ? rawMsg("none") : Text.mm(fruit));
        ph.put("event", event == null ? Component.empty() : event.displayName());
        ph.put("event_time", event == null ? "" : Text.duration(Math.max(0, event.secondsLeft())));
        ph.put("conqueror", conqueror == null ? rawMsg("none") : Component.text(conqueror));
        ph.put("online", Bukkit.getOnlinePlayers().size());
        ph.put("x", p.getLocation().getBlockX());
        ph.put("z", p.getLocation().getBlockZ());
        Object[] flat = new Object[ph.size() * 2];
        int n = 0;
        for (Map.Entry<String, Object> e : ph.entrySet()) {
            flat[n++] = e.getKey();
            flat[n++] = e.getValue();
        }

        List<Component> out = new ArrayList<>();
        for (String raw : config().getStringList("lines")) {
            String line = raw;
            if (line.startsWith("?")) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String cond = line.substring(1, colon).trim().toLowerCase();
                line = line.substring(colon + 1);
                boolean show = switch (cond) {
                    case "crew" -> crew != null;
                    case "nocrew" -> crew == null;
                    case "island" -> island != null;
                    case "sea" -> island == null;
                    case "fruit" -> fruit != null;
                    case "event" -> event != null;
                    case "bounty" -> bounty > 0;
                    case "conqueror" -> conqueror != null;
                    default -> true;
                };
                if (!show) {
                    continue;
                }
            }
            out.add(Text.mm(line, flat));
            if (out.size() >= MAX_LINES) {
                break;
            }
        }
        return out;
    }

    private Component islandOwner(Island island, CrewService crews) {
        return switch (island.status()) {
            case NEUTRAL -> rawMsg("island-neutral");
            case UNCLAIMED -> rawMsg("island-unclaimed");
            case OCCUPIED -> {
                Crew owner = crews == null || island.ownerCrewId() == null ? null : crews.crewById(island.ownerCrewId());
                yield owner == null ? rawMsg("island-unclaimed") : owner.displayName();
            }
        };
    }

    /** MiniMessage name of the player's fruit power, or null. */
    private String fruitName(UUID id) {
        if (!(plugin().module("items") instanceof ItemsModule items) || !items.isEnabled() || items.fruits() == null) {
            return null;
        }
        String fruitId = items.fruits().fruitOf(id);
        ItemDef def = fruitId == null ? null : items.def(fruitId);
        return def == null ? null : def.cfg.getString("name", fruitId);
    }

    private SeaEvent runningEvent() {
        if (!(plugin().module("events") instanceof EventsModule events) || !events.isEnabled()) {
            return null;
        }
        List<SeaEvent> running = events.running();
        return running.isEmpty() ? null : running.get(0);
    }

    // ------------------------------------------------------------------ /sidebar

    private void registerCommands() {
        AquaticCommand cmd = new AquaticCommand("sidebar", "Show or hide your stats sidebar", "sb");
        cmd.defaultExecutor((sender, args) -> {
            Player p = AquaticCommand.player(sender);
            boolean hide = args.length > 0 ? args[0].equalsIgnoreCase("off") : !hidden.contains(p.getUniqueId());
            if (hide) {
                hidden.add(p.getUniqueId());
                remove(p);
                send(p, "hidden");
            } else {
                hidden.remove(p.getUniqueId());
                update(p);
                send(p, "shown");
            }
            save();
        }).defaultCompleter((s, a) -> AquaticCommand.filter(List.of("on", "off"), a.length == 0 ? "" : a[0]));
        registerCommand(cmd);
    }
}
