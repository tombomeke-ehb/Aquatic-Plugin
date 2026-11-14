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
    /** The lines last sent to each player, to only touch the lines that changed. */
    private final Map<UUID, List<Component>> shown = new HashMap<>();
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
        shown.clear();
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
        shown.remove(event.getPlayer().getUniqueId());
    }

    private void remove(Player p) {
        Scoreboard board = boards.remove(p.getUniqueId());
        shown.remove(p.getUniqueId());
        if (board != null && p.getScoreboard() == board) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void updateAll() {
        if (!config().getBoolean("enabled", true)) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                update(p);
            } catch (Throwable t) {
                lastError = t.toString();
                if (!errorLogged) {                       // one player's problem never stops the others
                    errorLogged = true;
                    logger().log(java.util.logging.Level.WARNING, "[sidebar] Could not draw the sidebar for " + p.getName(), t);
                }
            }
        }
    }

    private boolean errorLogged;
    private String lastError;

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
            Objective obj = board.registerNewObjective("aquatic_side", Criteria.DUMMY, Text.mm(dev.aquatic.util.Placeholders.apply(p, config().getString("title", "Aquatic SMP"))));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            obj.numberFormat(NumberFormat.blank());
            boards.put(p.getUniqueId(), board);
        }
        if (current != board && current != main) {
            // Another plugin gave the player its own scoreboard (nametags, tab prefixes from LuckPerms, ...).
            // During the tournament its board wins; otherwise the sidebar moves onto that board, so both work.
            TournamentService t = service(TournamentService.class);
            if ((t != null && t.tournamentRunning()) || !config().getBoolean("share-scoreboards", true)) {
                return;
            }
            org.bukkit.scoreboard.Objective theirs = current.getObjective(DisplaySlot.SIDEBAR);
            if (theirs != null && !theirs.getName().equals("aquatic_side") && !config().getBoolean("take-sidebar-slot", true)
                    && !current.getEntries().stream().allMatch(e -> !theirs.getScore(e).isScoreSet())) {
                return;                                  // it shows its own sidebar: leave it alone
            }
            Objective o = current.getObjective("aquatic_side");
            if (o == null) {
                o = current.registerNewObjective("aquatic_side", Criteria.DUMMY, Text.mm(dev.aquatic.util.Placeholders.apply(p, config().getString("title", "Aquatic SMP"))));
                o.numberFormat(NumberFormat.blank());
                shown.remove(p.getUniqueId());
            }
            if (o.getDisplaySlot() != DisplaySlot.SIDEBAR) {
                o.setDisplaySlot(DisplaySlot.SIDEBAR);
            }
            board = current;
            boards.put(p.getUniqueId(), board);
        } else if (current != board) {
            p.setScoreboard(board);
        }
        Objective obj = board.getObjective("aquatic_side");
        if (obj == null) {
            return;
        }
        obj.displayName(Text.mm(dev.aquatic.util.Placeholders.apply(p, config().getString("title", "Aquatic SMP"))));
        List<Component> lines = render(p);
        List<Component> before = shown.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = "line" + i;
            boolean set = obj.getScore(entry).isScoreSet();
            if (i < lines.size()) {
                Component line = lines.get(i);
                if (set && i < before.size() && line.equals(before.get(i))) {
                    continue;
                }
                // Some clients (and ViaVersion for other client versions) ignore a changed line text on an existing
                // score, which froze lines like the coordinates. Removing and re-adding the line always shows.
                if (set) {
                    board.resetScores(entry);
                }
                Score score = obj.getScore(entry);
                score.customName(line);
                score.setScore(MAX_LINES - i);
            } else if (set) {
                board.resetScores(entry);
            }
        }
        before.clear();
        before.addAll(lines);
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
        String conqueror = tournament == null ? null : tournament.conquerorName();

        dev.aquatic.api.LevelService levels = service(dev.aquatic.api.LevelService.class);
        Map<String, Object> ph = new HashMap<>();
        ph.put("player", dev.aquatic.util.Ranks.name(p));
        ph.put("prefix", dev.aquatic.util.Ranks.prefix(p));
        ph.put("lp_rank", dev.aquatic.util.Ranks.group(p));
        ph.put("lp_group", String.valueOf(dev.aquatic.util.Ranks.groupId(p)));
        ph.put("suffix", dev.aquatic.util.Ranks.suffix(p));
        ph.put("balance", eco == null ? "-" : eco.format(eco.balance(id)));
        ph.put("bounty", eco == null ? Text.number(bounty) : eco.format(bounty));
        ph.put("crew", crew == null ? rawMsg("none") : crew.displayName());
        ph.put("rank", crew == null || crew.rankOf(id) == null ? "-" : crew.rankOf(id).display());
        ph.put("reputation", crew == null ? "-" : String.valueOf(crew.reputation()));
        ph.put("island", island == null ? rawMsg("open-sea") : Component.text(island.name()));
        ph.put("island_owner", island == null ? Component.empty() : islandOwner(island, crews));
        ph.put("fruit", fruit == null ? rawMsg("none") : Text.mm(fruit));
        ph.put("level", levels == null ? "-" : String.valueOf(levels.level(id)));
        ph.put("fruit_level", plugin().module("items") instanceof ItemsModule im && im.isEnabled() && im.fruits() != null
                ? String.valueOf(im.fruits().masteryLevel(id)) : "-");
        Object[] move = moveInfo(id);
        ph.put("move", move[0]);
        ph.put("move_cd", move[1]);
        ph.put("event", event == null ? Component.empty() : event.displayName());
        ph.put("event_time", event == null ? "" : Text.duration(Math.max(0, event.secondsLeft())));
        ph.put("conqueror", conqueror == null ? rawMsg("none") : Component.text(conqueror));
        ph.put("online", Bukkit.getOnlinePlayers().size());
        // 2.3.0: level bar, bounty title, combat, crew online, island rarity
        double progress = levels == null ? 0 : levels.progress(id);
        int filled = (int) Math.round(progress * 10);
        ph.put("xp_bar", Text.mm("<aqua>" + "▮".repeat(filled) + "<dark_gray>" + "▯".repeat(10 - filled)));
        ph.put("xp_percent", String.valueOf(Math.round(progress * 100)));
        String levelTitle = levels == null ? null : levels.title(id);
        ph.put("level_title", levelTitle == null ? Component.empty() : Text.mm(levelTitle));
        String title = bounties == null ? null : bounties.tierTitle(id);
        ph.put("bounty_title", title == null ? Component.empty() : Text.mm(title));
        dev.aquatic.api.CombatService combat = service(dev.aquatic.api.CombatService.class);
        long combatLeft = combat == null ? 0 : combat.combatSecondsLeft(id);
        ph.put("combat", String.valueOf(combatLeft));
        ph.put("crew_online", crew == null ? "0" : crew.onlineMembers().size() + "/" + crew.members().size());
        ph.put("island_type", island == null ? Component.empty() : Text.mm("<white>" + island.type().name().charAt(0)
                + island.type().name().substring(1).toLowerCase()));
        Component rarity = island == null || islands == null ? null : islands.rarityDisplay(island.type());
        ph.put("island_rarity", rarity == null ? Component.empty() : rarity);
        ph.put("x", p.getLocation().getBlockX());
        ph.put("z", p.getLocation().getBlockZ());
        Object[] flat = new Object[ph.size() * 2];
        int n = 0;
        for (Map.Entry<String, Object> e : ph.entrySet()) {
            flat[n++] = e.getKey();
            flat[n++] = e.getValue();
        }

        List<Component> out = new ArrayList<>();
        // "layout" (2.3.0+) replaces the older "lines"
        for (String raw : config().getStringList("layout")) {
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
                    case "combat" -> combatLeft > 0;
                    case "nofruit" -> fruit == null;
                    case "lprank" -> dev.aquatic.util.Ranks.groupId(p) != null;
                    default -> true;
                };
                if (!show) {
                    continue;
                }
            }
            out.add(Text.mm(dev.aquatic.util.Placeholders.apply(p, line), flat));
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

    /** {move name component, cooldown component} of the player's selected fruit move. */
    private Object[] moveInfo(UUID id) {
        if (!(plugin().module("items") instanceof ItemsModule items) || !items.isEnabled() || items.fruits() == null) {
            return new Object[]{Component.empty(), Component.empty()};
        }
        String fruitId = items.fruits().fruitOf(id);
        ItemDef def = fruitId == null ? null : items.def(fruitId);
        org.bukkit.configuration.ConfigurationSection move = def == null ? null : items.fruits().currentMove(id, def);
        if (move == null) {
            return new Object[]{Component.empty(), Component.empty()};
        }
        int n = Math.max(1, def.moves().size());
        int index = def.moves().indexOf(move);
        long left = items.fruits().cooldownLeft(id, "fruit:" + Math.floorMod(index, n));
        return new Object[]{Text.mm(move.getString("name", "Move")),
                left > 0 ? rawMsg("move-cooldown", "seconds", left) : rawMsg("move-ready")};
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
        cmd.sub("debug", (sender, a) -> {
            Player p = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : AquaticCommand.player(sender);
            if (p == null) {
                throw new dev.aquatic.command.CommandFail("<red>No such player online.");
            }
            Scoreboard current = p.getScoreboard();
            Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            org.bukkit.scoreboard.Objective side = current.getObjective(DisplaySlot.SIDEBAR);
            sender.sendMessage(Text.mm("<aqua>Sidebar of <white><p></white>: enabled <white><e></white>, hidden by the player <white><h></white>",
                    "p", p.getName(), "e", config().getBoolean("enabled", true), "h", hidden.contains(p.getUniqueId())));
            sender.sendMessage(Text.mm("<gray>Scoreboard: <white><b></white>, sidebar slot shows <white><s></white>",
                    "b", current == boards.get(p.getUniqueId()) ? "ours" : current == main ? "the server's main board" : "another plugin's board",
                    "s", side == null ? "nothing" : side.getName()));
            TournamentService t = service(TournamentService.class);
            sender.sendMessage(Text.mm("<gray>Tournament running: <white><t></white>. Last error: <white><err>",
                    "t", t != null && t.tournamentRunning(), "err", lastError == null ? "none" : lastError));
            sender.sendMessage(Text.mm("<gray><r>", "r", dev.aquatic.util.Ranks.describe(p)));
        }).usage("[player]").description("Why the sidebar does (not) show, and what LuckPerms gives").permission("aquatic.admin");
        registerCommand(cmd);
    }

    @Override
    protected void migrateConfig(YamlConfiguration file, YamlConfiguration defaults, int from) {
        if (from < 4) {
            // 2.27.2: the LuckPerms rank under the name; the crew rank is called the Role
            List<String> lines = new ArrayList<>(file.getStringList("layout"));
            int who = -1;
            boolean has = false;
            for (int i = 0; i < lines.size(); i++) {
                has |= lines.get(i).contains("<lp_rank>");
                if (who < 0 && lines.get(i).contains("<player>")) {
                    who = i;
                }
            }
            if (!has && who >= 0) {
                lines.add(who + 1, "?lprank:<gray>Rank: <lp_rank>");
            }
            int crewRank = lines.indexOf("?crew:<gray>Rank: <white><rank>");
            if (crewRank >= 0) {
                lines.set(crewRank, "?crew:<gray>Role: <white><rank>");
            }
            file.set("layout", lines);
        }
        if (from < 3) {
            // 2.23.0: the rank of your bounty stands above your name
            List<String> lines = new ArrayList<>(file.getStringList("layout"));
            lines.remove("?bounty:<dark_gray>» <bounty_title>");
            int who = -1;
            boolean has = false;
            for (int i = 0; i < lines.size(); i++) {
                has |= lines.get(i).contains("<bounty_title>");
                if (who < 0 && lines.get(i).contains("<player>")) {
                    who = i;
                }
            }
            if (!has && who >= 0) {
                lines.add(who, "?bounty:<bounty_title>");
            }
            file.set("layout", lines);
        }
        if (from < 2) {
            // 2.17.0: the level sits next to the XP bar (a narrower sidebar)
            List<String> layout = new ArrayList<>(file.getStringList("layout"));
            int name = layout.indexOf("<white><bold><player></bold> <gray>Lv <aqua><level> <level_title>");
            int bar = layout.indexOf("<xp_bar> <dark_gray><xp_percent>%");
            if (name >= 0 && bar >= 0) {
                layout.set(name, "<white><bold><player></bold> <level_title>");
                layout.set(bar, "<gray>Lv <aqua><level></aqua> <xp_bar> <dark_gray><xp_percent>%");
                file.set("layout", layout);
            }
        }
        if (from < 1) {
            // 2.6.0: level titles next to the level
            List<String> layout = new ArrayList<>(file.getStringList("layout"));
            String old = "<white><bold><player></bold> <gray>Lv <aqua><level>";
            int i = layout.indexOf(old);
            if (i >= 0) {
                layout.set(i, old + " <level_title>");
                file.set("layout", layout);
            }
        }
    }
}
