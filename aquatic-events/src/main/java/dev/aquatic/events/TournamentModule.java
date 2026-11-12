package dev.aquatic.events;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.ItemService;
import dev.aquatic.api.TournamentService;
import dev.aquatic.api.event.BountyClaimEvent;
import dev.aquatic.api.event.CrewDisbandEvent;
import dev.aquatic.api.event.CrewLeaveEvent;
import dev.aquatic.api.event.IslandClaimEvent;
import dev.aquatic.api.event.IslandConquerEvent;
import dev.aquatic.api.event.PirateKingCrownedEvent;
import dev.aquatic.api.event.TreasureFoundEvent;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.events.tournament.Arena;
import dev.aquatic.events.tournament.BracketMatch;
import dev.aquatic.events.tournament.Entry;
import dev.aquatic.events.tournament.MatchRunner;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** The Pirate King tournament: registration, qualifiers, elimination bracket, grand finale, coronation. */
public final class TournamentModule extends AquaticModule implements TournamentService, Listener {

    public enum Stage { NONE, REGISTRATION, QUALIFIERS, BRACKET }

    private Stage stage = Stage.NONE;
    private long stageEndsAt;
    private int season = 1;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<List<BracketMatch>> rounds = new ArrayList<>();
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private Location lobby;

    private volatile UUID king;
    private volatile String kingName;
    private UUID previousKing;
    private UUID pendingCrown;
    private final List<Map<String, Object>> hall = new ArrayList<>();

    private MatchRunner runner;
    private BukkitTask nextTask;
    private Scoreboard board;
    private final Set<UUID> boardViewers = new HashSet<>();
    private long tickCount;

    @Override
    public String id() {
        return "tournament";
    }

    @Override
    public List<String> requires() {
        return List.of("crews");
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onEnable() {
        entries.clear();
        rounds.clear();
        arenas.clear();
        hall.clear();
        boardViewers.clear();
        board = null;
        nextTask = null;
        tickCount = 0;
        loadArenas();
        loadHall();
        loadState();
        runner = new MatchRunner(this);
        runner.loadPending();
        registerListener(this);
        registerListener(runner);
        registerService(TournamentService.class, this);
        registerCommands();
        runTimer(this::secondTick, 20L, 20L);
        runTimer(this::perkTick, 10L, 10L);
        if (stage == Stage.BRACKET) {
            Util.broadcast(msg("resumed-bracket"));
            scheduleNext();
        } else if (stage != Stage.NONE) {
            Util.broadcast(msg("resumed", "stage", stageName()));
        }
    }

    @Override
    protected void onDisable() {
        if (runner != null) {
            runner.abort();
        }
        clearBoard();
        if (king != null) {
            Player k = Bukkit.getPlayer(king);
            if (k != null && config().getBoolean("king-perks.glow", true)) {
                k.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
        saveState();
        saveHall();
        saveArenas();
    }

    // ------------------------------------------------------------------ persistence

    private void loadState() {
        YamlConfiguration d = loadData("state");
        try {
            stage = Stage.valueOf(d.getString("stage", "NONE"));
        } catch (IllegalArgumentException e) {
            stage = Stage.NONE;
        }
        stageEndsAt = d.getLong("ends-at", 0);
        season = Math.max(1, d.getInt("season", 1));
        ConfigurationSection es = d.getConfigurationSection("entries");
        if (es != null) {
            for (String id : es.getKeys(false)) {
                ConfigurationSection s = es.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                Entry e = new Entry(id, s.getString("name", id));
                e.points = s.getInt("points");
                e.disbanded = s.getBoolean("disbanded");
                for (String u : s.getStringList("roster")) {
                    try {
                        e.roster.add(UUID.fromString(u));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                entries.put(id, e);
            }
        }
        for (Object r : d.getList("rounds", List.of())) {
            if (!(r instanceof List<?> list)) {
                continue;
            }
            List<BracketMatch> round = new ArrayList<>();
            for (Object m : list) {
                if (m instanceof Map<?, ?> map) {
                    round.add(BracketMatch.deserialize(map));
                }
            }
            rounds.add(round);
        }
        if (stage == Stage.BRACKET && rounds.isEmpty()) {
            stage = Stage.NONE;
        }
    }

    public void saveState() {
        YamlConfiguration d = new YamlConfiguration();
        d.set("stage", stage.name());
        d.set("ends-at", stageEndsAt);
        d.set("season", season);
        for (Entry e : entries.values()) {
            ConfigurationSection s = d.createSection("entries." + e.crewId);
            s.set("name", e.name);
            s.set("points", e.points);
            s.set("disbanded", e.disbanded);
            s.set("roster", e.roster.stream().map(UUID::toString).toList());
        }
        List<List<Map<String, Object>>> rs = new ArrayList<>();
        for (List<BracketMatch> round : rounds) {
            rs.add(round.stream().map(BracketMatch::serialize).toList());
        }
        d.set("rounds", rs);
        saveData("state", d);
    }

    private void loadArenas() {
        YamlConfiguration d = loadData("arenas");
        lobby = Util.locFromString(d.getString("lobby"));
        ConfigurationSection as = d.getConfigurationSection("arenas");
        if (as != null) {
            for (String name : as.getKeys(false)) {
                ConfigurationSection s = as.getConfigurationSection(name);
                if (s != null) {
                    arenas.put(name.toLowerCase(Locale.ROOT), Arena.load(name.toLowerCase(Locale.ROOT), s));
                }
            }
        }
    }

    private void saveArenas() {
        YamlConfiguration d = new YamlConfiguration();
        d.set("lobby", Util.locToString(lobby));
        for (Arena a : arenas.values()) {
            a.save(d.createSection("arenas." + a.name));
        }
        saveData("arenas", d);
    }

    private void loadHall() {
        YamlConfiguration d = loadData("hall");
        king = parseUuid(d.getString("king"));
        kingName = d.getString("king-name");
        pendingCrown = parseUuid(d.getString("pending-crown"));
        for (Map<?, ?> m : d.getMapList("kings")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            hall.add(copy);
        }
    }

    private void saveHall() {
        YamlConfiguration d = new YamlConfiguration();
        d.set("king", king == null ? null : king.toString());
        d.set("king-name", kingName);
        d.set("pending-crown", pendingCrown == null ? null : pendingCrown.toString());
        d.set("kings", hall);
        saveData("hall", d);
    }

    private static UUID parseUuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ TournamentService

    @Override
    public UUID pirateKing() {
        return king;
    }

    @Override
    public String pirateKingName() {
        return kingName;
    }

    @Override
    public boolean isPirateKing(UUID player) {
        return king != null && king.equals(player);
    }

    @Override
    public boolean tournamentRunning() {
        return stage != Stage.NONE;
    }

    @Override
    public boolean isInMatch(UUID player) {
        return runner != null && runner.isFighter(player);
    }

    // ------------------------------------------------------------------ helpers used by the match runner

    public Location lobby() {
        return lobby;
    }

    public Component crewName(String crewId) {
        CrewService crews = service(CrewService.class);
        Crew c = crews == null || crewId == null ? null : crews.crewById(crewId);
        if (c != null) {
            return c.displayName();
        }
        Entry e = crewId == null ? null : entries.get(crewId);
        return Component.text(e != null ? e.name : String.valueOf(crewId));
    }

    public void broadcastMatch(String key, Object... placeholders) {
        Util.broadcast(msg(key, placeholders));
    }

    private void announce(String key, Object... placeholders) {
        Util.broadcast(msg(key, placeholders));
    }

    private String stageName() {
        return Text.plain(rawMsg("stage-" + stage.name().toLowerCase(Locale.ROOT)));
    }

    // ------------------------------------------------------------------ stage flow

    private void open(long minutes) {
        if (stage != Stage.NONE) {
            throw new CommandFail(rawMsg("already-running"));
        }
        entries.clear();
        rounds.clear();
        stage = Stage.REGISTRATION;
        stageEndsAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        saveState();
        announce("registration-open", "season", season,
                "time", minutes > 0 ? Text.duration(minutes * 60) : Text.plain(rawMsg("manual")),
                "size", config().getInt("teams.size", 3));
        Util.soundAll(config().getString("sounds.announce", "item.goat_horn.sound.0"), 1f, 1f);
    }

    /** Forces the next stage. */
    private void advance() {
        switch (stage) {
            case NONE -> throw new CommandFail(rawMsg("not-running"));
            case REGISTRATION -> endRegistration();
            case QUALIFIERS -> endQualifiers();
            case BRACKET -> startNext();
        }
    }

    private int minCrews() {
        return Math.max(2, config().getInt("registration.min-crews", 2));
    }

    private void endRegistration() {
        pruneEntries();
        if (entries.size() < minCrews()) {
            announce("cancelled-not-enough", "min", minCrews());
            reset();
            return;
        }
        if (config().getBoolean("qualifiers.enabled", true)) {
            stage = Stage.QUALIFIERS;
            long minutes = Math.max(1, config().getLong("qualifiers.duration-minutes", 60));
            stageEndsAt = System.currentTimeMillis() + minutes * 60_000L;
            for (Entry e : entries.values()) {
                e.points = 0;
            }
            saveState();
            announce("qualifiers-start", "time", Text.duration(minutes * 60), "count", entries.size(),
                    "top", qualifierSlots());
            Util.soundAll(config().getString("sounds.announce", "item.goat_horn.sound.0"), 1f, 1f);
        } else {
            CrewService crews = service(CrewService.class);
            for (Entry e : entries.values()) {
                Crew c = crews == null ? null : crews.crewById(e.crewId);
                e.points = c == null ? 0 : c.reputation();
            }
            startBracket();
        }
    }

    /** Drops entries whose crew no longer exists. */
    private void pruneEntries() {
        CrewService crews = service(CrewService.class);
        if (crews == null) {
            return;
        }
        entries.values().removeIf(e -> e.disbanded || crews.crewById(e.crewId) == null);
        saveState();
    }

    private int qualifierSlots() {
        int top = Math.max(2, config().getInt("qualifiers.advance", 8));
        int pow = 1;
        while (pow * 2 <= top) {
            pow *= 2;
        }
        return Math.min(pow, entries.size());
    }

    private void endQualifiers() {
        clearBoard();
        pruneEntries();
        if (entries.size() < 2) {
            announce("cancelled-not-enough", "min", 2);
            reset();
            return;
        }
        announce("qualifiers-end");
        sendStandings(Bukkit.getConsoleSender(), true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendStandings(p, false);
        }
        startBracket();
    }

    private List<Entry> ranked() {
        List<Entry> list = new ArrayList<>(entries.values());
        list.removeIf(e -> e.disbanded);
        list.sort(Comparator.comparingInt((Entry e) -> e.points).reversed());
        return list;
    }

    private void startBracket() {
        List<Entry> ranked = ranked();
        int slots = qualifierSlots();
        List<Entry> qualified = new ArrayList<>(ranked.subList(0, Math.min(slots, ranked.size())));
        if (qualified.size() < 2) {
            announce("cancelled-not-enough", "min", 2);
            reset();
            return;
        }
        int size = 1;
        while (size < qualified.size()) {
            size *= 2;
        }
        List<Integer> order = new ArrayList<>(List.of(1, 2));
        while (order.size() < size) {
            int n = order.size() * 2 + 1;
            List<Integer> next = new ArrayList<>();
            for (int s : order) {
                next.add(s);
                next.add(n - s);
            }
            order = next;
        }
        List<BracketMatch> first = new ArrayList<>();
        for (int i = 0; i < order.size(); i += 2) {
            int sa = order.get(i), sb = order.get(i + 1);
            String a = sa <= qualified.size() ? qualified.get(sa - 1).crewId : null;
            String b = sb <= qualified.size() ? qualified.get(sb - 1).crewId : null;
            if (a == null) {
                a = b;
                b = null;
            }
            BracketMatch m = new BracketMatch(a, b);
            if (b == null) {
                m.winner = a;
            }
            first.add(m);
        }
        rounds.clear();
        rounds.add(first);
        stage = Stage.BRACKET;
        stageEndsAt = 0;
        saveState();
        StringBuilder names = new StringBuilder();
        for (Entry e : qualified) {
            names.append(names.length() == 0 ? "" : ", ").append(e.name);
        }
        announce("bracket-start", "count", qualified.size(), "crews", names.toString());
        for (BracketMatch m : first) {
            if (m.isBye()) {
                announce("bye", "crew", crewName(m.a));
            }
        }
        announceRound(first);
        scheduleNext();
    }

    private void announceRound(List<BracketMatch> round) {
        announce(round.size() == 1 ? "finale-announce" : "round-start", "round", roundName(round.size()),
                "bestof", bestOf());
        if (round.size() == 1) {
            Component title = rawMsg("finale-title");
            Component sub = rawMsg("finale-subtitle", "a", crewName(round.get(0).a), "b", crewName(round.get(0).b));
            Title t = Title.title(title, sub, Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1)));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(t);
            }
            Util.soundAll(config().getString("sounds.finale", "entity.wither.spawn"), 0.6f, 1f);
        }
    }

    private String roundName(int matches) {
        String custom = config().getString("bracket.round-names." + matches);
        if (custom != null) {
            return custom;
        }
        return "Round of " + (matches * 2);
    }

    private int bestOf() {
        int n = Math.max(1, config().getInt("finale.best-of", 3));
        return n % 2 == 0 ? n + 1 : n;
    }

    private void scheduleNext() {
        if (nextTask != null) {
            nextTask.cancel();
            nextTask = null;
        }
        if (stage != Stage.BRACKET) {
            return;
        }
        long delay = config().getLong("bracket.auto-next-delay-seconds", 30);
        if (delay < 0) {
            BracketMatch next = peekNext();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("aquatic.tournament.admin") || p.hasPermission("aquatic.admin")) {
                    send(p, "staff-next-hint", "a", next == null ? Component.text("-") : crewName(next.a),
                            "b", next == null ? Component.text("-") : crewName(next.b));
                }
            }
            return;
        }
        BracketMatch next = peekNext();
        if (next != null) {
            announce("next-match-in", "a", crewName(next.a), "b", crewName(next.b), "seconds", delay);
        }
        nextTask = runLater(() -> {
            nextTask = null;
            try {
                if (stage == Stage.BRACKET && !runner.active()) {
                    startNext();
                }
            } catch (CommandFail fail) {
                announceStaff(fail.component());
            }
        }, Math.max(1, delay * 20));
    }

    private void announceStaff(Component c) {
        Bukkit.getConsoleSender().sendMessage(c);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("aquatic.tournament.admin") || p.hasPermission("aquatic.admin")) {
                p.sendMessage(prefixed(c));
            }
        }
    }

    private Component prefixed(Component c) {
        return plugin().prefix().append(c);
    }

    /** The next unfinished match of the current round, or null when the round is complete. */
    private BracketMatch peekNext() {
        if (rounds.isEmpty()) {
            return null;
        }
        for (BracketMatch m : rounds.get(rounds.size() - 1)) {
            if (!m.done()) {
                return m;
            }
        }
        return null;
    }

    private void startNext() {
        if (stage != Stage.BRACKET) {
            throw new CommandFail(rawMsg("not-bracket"));
        }
        if (runner.active()) {
            throw new CommandFail(rawMsg("match-running"));
        }
        if (nextTask != null) {
            nextTask.cancel();
            nextTask = null;
        }
        List<BracketMatch> round = rounds.get(rounds.size() - 1);
        BracketMatch m = peekNext();
        if (m == null) {
            if (round.size() == 1) {
                crown(round.get(0).winner);
                return;
            }
            List<BracketMatch> next = new ArrayList<>();
            for (int i = 0; i + 1 < round.size(); i += 2) {
                next.add(new BracketMatch(round.get(i).winner, round.get(i + 1).winner));
            }
            for (BracketMatch bm : next) {
                if (bm.a == null || bm.b == null) {
                    bm.winner = bm.a != null ? bm.a : bm.b;
                    if (bm.a == null) {
                        bm.a = bm.b;
                        bm.b = null;
                    }
                }
            }
            rounds.add(next);
            saveState();
            announceRound(next);
            if (next.size() == 1 && next.get(0).done()) {
                crown(next.get(0).winner);
                return;
            }
            scheduleNext();
            return;
        }
        boolean finale = round.size() == 1;
        Entry ea = entries.get(m.a), eb = entries.get(m.b);
        CrewService crews = service(CrewService.class);
        boolean aOk = ea != null && !ea.disbanded && crews != null && crews.crewById(m.a) != null;
        boolean bOk = eb != null && !eb.disbanded && crews != null && crews.crewById(m.b) != null;
        if (!aOk || !bOk) {
            String winner = aOk ? m.a : bOk ? m.b : m.a;
            m.winner = winner;
            saveState();
            announce("forfeit-disbanded", "crew", crewName(winner));
            scheduleNext();
            return;
        }
        List<Player> fa = runner.availableFighters(ea), fb = runner.availableFighters(eb);
        if (fa.isEmpty() || fb.isEmpty()) {
            if (fa.isEmpty() && fb.isEmpty()) {
                throw new CommandFail(rawMsg("nobody-online", "a", crewName(m.a), "b", crewName(m.b)));
            }
            String winner = fa.isEmpty() ? m.b : m.a;
            if (finale) {
                if (winner.equals(m.a)) {
                    m.winsA = bestOf() / 2 + 1;
                } else {
                    m.winsB = bestOf() / 2 + 1;
                }
            }
            m.winner = winner;
            saveState();
            announce("forfeit-offline", "crew", crewName(winner), "loser", crewName(winner.equals(m.a) ? m.b : m.a));
            scheduleNext();
            return;
        }
        List<Arena> usable = arenas.values().stream().filter(Arena::complete).toList();
        if (usable.isEmpty()) {
            throw new CommandFail(rawMsg("no-arena"));
        }
        Arena arena = usable.get(ThreadLocalRandom.current().nextInt(usable.size()));
        int game = m.winsA + m.winsB + 1;
        if (finale) {
            announce("finale-game-start", "a", crewName(m.a), "b", crewName(m.b), "game", game, "bestof", bestOf(),
                    "wins_a", m.winsA, "wins_b", m.winsB, "arena", arena.name);
        } else {
            announce("match-start", "a", crewName(m.a), "b", crewName(m.b), "arena", arena.name,
                    "round", roundName(round.size()));
        }
        runner.start(m, arena, ea, eb, fa, fb, finale, game);
    }

    /** Called by the match runner after everyone was restored. */
    public void matchFinished(BracketMatch m, String winner, String reason) {
        if (stage != Stage.BRACKET || rounds.isEmpty()) {
            return;
        }
        boolean finale = rounds.get(rounds.size() - 1).size() == 1 && rounds.get(rounds.size() - 1).contains(m);
        String loser = winner.equals(m.a) ? m.b : m.a;
        if (finale && !"disbanded".equals(reason)) {
            if (winner.equals(m.a)) {
                m.winsA++;
            } else {
                m.winsB++;
            }
            int needed = bestOf() / 2 + 1;
            announce("finale-game-won", "crew", crewName(winner), "wins_a", m.winsA, "wins_b", m.winsB,
                    "a", crewName(m.a), "b", crewName(m.b), "reason", Text.plain(rawMsg("reason-" + reason)));
            if (m.winsA < needed && m.winsB < needed) {
                saveState();
                scheduleNext();
                return;
            }
        }
        m.winner = winner;
        saveState();
        announce("match-won", "crew", crewName(winner), "loser", crewName(loser), "reason", Text.plain(rawMsg("reason-" + reason)));
        Util.soundAll(config().getString("sounds.match-won", "ui.toast.challenge_complete"), 0.6f, 1f);
        if (finale) {
            crown(winner);
            return;
        }
        scheduleNext();
    }

    // ------------------------------------------------------------------ coronation

    private void crown(String crewId) {
        CrewService crews = service(CrewService.class);
        Crew crew = crews == null || crewId == null ? null : crews.crewById(crewId);
        if (crew == null) {
            announce("no-winner");
            reset();
            return;
        }
        UUID captain = crew.captain();
        OfflinePlayer op = Bukkit.getOfflinePlayer(captain);
        String name = op.getName() == null ? captain.toString() : op.getName();
        setKing(captain, name);
        Bukkit.getPluginManager().callEvent(new PirateKingCrownedEvent(captain, name, crew));

        Title title = Title.title(rawMsg("crowned-title", "player", name),
                rawMsg("crowned-subtitle", "crew", crew.displayName(), "season", season),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(6), Duration.ofSeconds(2)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
        }
        announce("crowned", "player", name, "crew", crew.displayName(), "season", season);
        Util.soundAll(config().getString("sounds.coronation", "ui.toast.challenge_complete"), 1f, 1f);
        Util.soundAll("entity.player.levelup", 1f, 0.6f);

        Player k = Bukkit.getPlayer(captain);
        if (k != null) {
            int shows = Math.max(0, config().getInt("coronation.fireworks", 10));
            for (int i = 0; i < shows; i++) {
                runLater(() -> {
                    Player kk = Bukkit.getPlayer(captain);
                    if (kk != null) {
                        ThreadLocalRandom r = ThreadLocalRandom.current();
                        Util.firework(kk.getLocation().add(r.nextDouble(-4, 4), 1, r.nextDouble(-4, 4)));
                    }
                }, 10L * i + 1);
            }
        }
        // crown item
        if (config().getBoolean("coronation.give-crown", true)) {
            if (k != null) {
                if (!giveCrown(k)) {
                    send(k, "crown-skipped");
                }
            } else {
                pendingCrown = captain;
            }
        }
        // money, reputation, commands
        ConfigurationSection prize = config().getConfigurationSection("coronation.prize");
        if (prize != null) {
            double bank = prize.getDouble("crew-bank", 0);
            if (bank > 0) {
                crew.depositBank(bank);
            }
            int rep = prize.getInt("reputation", 0);
            if (rep != 0) {
                crew.addReputation(rep);
            }
            double each = prize.getDouble("per-fighter", 0);
            EconomyService eco = service(EconomyService.class);
            Entry entry = entries.get(crewId);
            if (each > 0 && eco != null) {
                Set<UUID> paid = new HashSet<>(entry != null ? entry.roster : List.of(captain));
                paid.add(captain);
                for (UUID u : paid) {
                    eco.deposit(u, each);
                }
            }
            double kingBonus = prize.getDouble("king", 0);
            if (kingBonus > 0 && eco != null) {
                eco.deposit(captain, kingBonus);
            }
            if (eco != null && (bank > 0 || each > 0 || kingBonus > 0)) {
                announce("prize", "crew", crew.displayName(), "bank", eco.format(bank), "each", eco.format(each),
                        "king", eco.format(kingBonus), "reputation", rep);
            }
            Util.runCommands(prize.getStringList("commands"), "player", name, "crew", crew.name(),
                    "season", String.valueOf(season), "uuid", captain.toString());
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("season", season);
        rec.put("uuid", captain.toString());
        rec.put("name", name);
        rec.put("crew", crew.name());
        rec.put("crew-id", crew.id());
        rec.put("date", LocalDate.now().toString());
        hall.add(rec);
        season++;
        saveHall();
        reset();
    }

    private boolean giveCrown(Player p) {
        ItemService items = service(ItemService.class);
        if (items == null) {
            return false;
        }
        var crown = items.createItem(config().getString("coronation.crown-item", "pirate_king_crown"), 1);
        if (crown == null) {
            return false;
        }
        Util.give(p, crown);
        return true;
    }

    private void setKing(UUID uuid, String name) {
        if (king != null && !king.equals(uuid)) {
            previousKing = king;
        }
        king = uuid;
        kingName = name;
        saveHall();
    }

    private void reset() {
        if (nextTask != null) {
            nextTask.cancel();
            nextTask = null;
        }
        if (runner != null) {
            runner.abort();
        }
        clearBoard();
        stage = Stage.NONE;
        stageEndsAt = 0;
        entries.clear();
        rounds.clear();
        saveState();
    }

    private void cancel() {
        if (stage == Stage.NONE) {
            throw new CommandFail(rawMsg("not-running"));
        }
        reset();
        announce("cancelled");
    }

    // ------------------------------------------------------------------ periodic

    private void secondTick() {
        tickCount++;
        runner.tick();
        if ((stage == Stage.REGISTRATION || stage == Stage.QUALIFIERS) && stageEndsAt > 0) {
            long left = (stageEndsAt - System.currentTimeMillis()) / 1000;
            if (left <= 0) {
                try {
                    advance();
                } catch (CommandFail fail) {
                    announceStaff(fail.component());
                }
                return;
            }
            if (left == 600 || left == 300 || left == 60 || left == 30 || left == 10) {
                announce(stage == Stage.REGISTRATION ? "registration-reminder" : "qualifiers-reminder",
                        "time", Text.duration(left));
            }
        }
        if (stage == Stage.QUALIFIERS) {
            long interval = config().getLong("qualifiers.broadcast-interval-seconds", 300);
            if (interval > 0 && tickCount % interval == 0) {
                List<Entry> r = ranked();
                announce("standings-broadcast-header");
                for (int i = 0; i < Math.min(3, r.size()); i++) {
                    Util.broadcast(rawMsg("standings-entry", "rank", i + 1, "crew", crewName(r.get(i).crewId), "points", r.get(i).points));
                }
            }
            if (config().getBoolean("qualifiers.sidebar", true) && tickCount % 5 == 0) {
                updateBoard();
            }
        }
    }

    private void updateBoard() {
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
        }
        Objective old = board.getObjective("aq_tourney");
        if (old != null) {
            old.unregister();
        }
        long left = Math.max(0, (stageEndsAt - System.currentTimeMillis()) / 1000);
        Objective o = board.registerNewObjective("aq_tourney", Criteria.DUMMY, rawMsg("sidebar-title", "time", Text.duration(left)));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        List<Entry> r = ranked();
        for (int i = 0; i < Math.min(12, r.size()); i++) {
            String line = (i + 1) + ". " + r.get(i).name;
            if (line.length() > 40) {
                line = line.substring(0, 40);
            }
            o.getScore(line).setScore(r.get(i).points);
        }
        Set<UUID> want = new HashSet<>();
        boolean everyone = config().getBoolean("qualifiers.sidebar-everyone", false);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (everyone || isEntrant(p.getUniqueId())) {
                want.add(p.getUniqueId());
                if (p.getScoreboard() != board) {
                    p.setScoreboard(board);
                }
            }
        }
        for (UUID u : boardViewers) {
            if (!want.contains(u)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.getScoreboard() == board) {
                    p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                }
            }
        }
        boardViewers.clear();
        boardViewers.addAll(want);
    }

    private void clearBoard() {
        if (board == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getScoreboard() == board) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boardViewers.clear();
        board = null;
    }

    private boolean isEntrant(UUID player) {
        CrewService crews = service(CrewService.class);
        Crew c = crews == null ? null : crews.crewOf(player);
        return c != null && entries.containsKey(c.id());
    }

    private void perkTick() {
        if (previousKing != null) {
            Player old = Bukkit.getPlayer(previousKing);
            if (old != null) {
                old.removePotionEffect(PotionEffectType.GLOWING);
                previousKing = null;
            }
        }
        if (king == null) {
            return;
        }
        Player k = Bukkit.getPlayer(king);
        if (k == null || k.isDead()) {
            return;
        }
        if (config().getBoolean("king-perks.glow", true) && !runner.isFighter(king)) {
            PotionEffect cur = k.getPotionEffect(PotionEffectType.GLOWING);
            if (cur == null || cur.getDuration() < 30) {
                k.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, true, false, false));
            }
        }
        String particle = config().getString("king-perks.particle", "END_ROD");
        if (particle != null && !particle.equalsIgnoreCase("none") && !k.isInvisible()
                && k.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && !k.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            try {
                Particle pt = Particle.valueOf(particle.toUpperCase(Locale.ROOT));
                if (pt.getDataType() == Void.class) {
                    k.getWorld().spawnParticle(pt, k.getLocation().add(0, 0.2, 0), 3, 0.25, 0.1, 0.25, 0.01);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (isPirateKing(p.getUniqueId())) {
            if (!p.getName().equals(kingName)) {
                kingName = p.getName();
                saveHall();
            }
            if (config().getBoolean("king-perks.join-broadcast", true)) {
                runLater(() -> {
                    if (p.isOnline()) {
                        announce("king-joined", "player", p.getName());
                        Util.soundAll(config().getString("sounds.king-join", "item.goat_horn.sound.1"), 0.8f, 1f);
                    }
                }, 20L);
            }
            if (p.getUniqueId().equals(pendingCrown)) {
                runLater(() -> {
                    if (p.isOnline() && giveCrown(p)) {
                        pendingCrown = null;
                        saveHall();
                        send(p, "crown-received");
                    }
                }, 40L);
            }
        }
        if (stage == Stage.REGISTRATION) {
            runLater(() -> {
                if (p.isOnline()) {
                    send(p, "join-reminder-registration");
                }
            }, 60L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boardViewers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDisband(CrewDisbandEvent event) {
        Entry e = entries.get(event.crew().id());
        if (e == null) {
            return;
        }
        e.name = event.crew().name();
        if (stage == Stage.BRACKET) {
            e.disbanded = true;
            saveState();
            announce("crew-disbanded-bracket", "crew", event.crew().name());
            runLater(() -> runner.crewGone(e.crewId), 1L);
        } else {
            entries.remove(e.crewId);
            saveState();
            announce("crew-disbanded-removed", "crew", event.crew().name());
        }
    }

    @EventHandler
    public void onLeave(CrewLeaveEvent event) {
        Entry e = entries.get(event.crew().id());
        if (e != null && e.roster.remove(event.player())) {
            saveState();
        }
    }

    private Entry entryOf(UUID player) {
        CrewService crews = service(CrewService.class);
        Crew c = crews == null ? null : crews.crewOf(player);
        Entry e = c == null ? null : entries.get(c.id());
        return e == null || e.disbanded ? null : e;
    }

    private void addPoints(Entry e, int points, String reasonKey, Player who) {
        if (e == null || points == 0) {
            return;
        }
        e.points += points;
        if (who != null && config().getBoolean("qualifiers.notify-points", true)) {
            who.sendActionBar(rawMsg("points-gained", "points", points, "reason", Text.plain(rawMsg("points-" + reasonKey)), "total", e.points));
        }
        if (tickCount % 10 == 0) {
            saveState();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKill(PlayerDeathEvent event) {
        if (stage != Stage.QUALIFIERS) {
            return;
        }
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            return;
        }
        Entry ke = entryOf(killer.getUniqueId());
        Entry ve = entryOf(victim.getUniqueId());
        if (ke == null || ke == ve) {
            return;
        }
        if (ve != null) {
            addPoints(ke, config().getInt("qualifiers.points.pvp-kill-entrant", 10), "pvp", killer);
        } else {
            addPoints(ke, config().getInt("qualifiers.points.pvp-kill-other", 0), "pvp", killer);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobKill(EntityDeathEvent event) {
        if (stage != Stage.QUALIFIERS || event.getEntity() instanceof Player) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        Entry e = entryOf(killer.getUniqueId());
        if (e == null) {
            return;
        }
        EntityType type = event.getEntityType();
        ConfigurationSection mobs = config().getConfigurationSection("qualifiers.points.mob-kills");
        if (mobs == null) {
            return;
        }
        int pts = mobs.getInt(type.name(), mobs.getInt(type.name().toLowerCase(Locale.ROOT), mobs.getInt("default", 0)));
        addPoints(e, pts, "mob", killer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTreasure(TreasureFoundEvent event) {
        if (stage != Stage.QUALIFIERS) {
            return;
        }
        Entry e = entryOf(event.player().getUniqueId());
        ConfigurationSection t = config().getConfigurationSection("qualifiers.points.treasure");
        if (e == null || t == null) {
            return;
        }
        String tier = event.tier() == null ? "default" : event.tier().toLowerCase(Locale.ROOT);
        addPoints(e, t.getInt(tier, t.getInt("default", 5)), "treasure", event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaim(IslandClaimEvent event) {
        if (stage != Stage.QUALIFIERS || event.crew() == null) {
            return;
        }
        Entry e = entries.get(event.crew().id());
        if (e != null && !e.disbanded) {
            addPoints(e, config().getInt("qualifiers.points.island-claim", 15), "claim", event.player());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConquer(IslandConquerEvent event) {
        if (stage != Stage.QUALIFIERS || event.conqueror() == null) {
            return;
        }
        Entry e = entries.get(event.conqueror().id());
        if (e != null && !e.disbanded) {
            addPoints(e, config().getInt("qualifiers.points.island-conquer", 30), "conquer", null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBounty(BountyClaimEvent event) {
        if (stage != Stage.QUALIFIERS || event.killer() == null) {
            return;
        }
        Entry e = entryOf(event.killer().getUniqueId());
        if (e == null) {
            return;
        }
        int pts = config().getInt("qualifiers.points.bounty-claim", 5)
                + (int) Math.floor(event.amount() * config().getDouble("qualifiers.points.bounty-per-doubloon", 0.01));
        addPoints(e, pts, "bounty", event.killer());
    }

    // ------------------------------------------------------------------ commands

    private Crew crewOrFail(CommandSender sender) {
        Player p = AquaticCommand.player(sender);
        CrewService crews = service(CrewService.class);
        Crew c = crews == null ? null : crews.crewOf(p.getUniqueId());
        if (c == null) {
            throw new CommandFail(rawMsg("no-crew"));
        }
        return c;
    }

    private void requireOfficer(Player p, Crew c) {
        var rank = c.rankOf(p.getUniqueId());
        if (rank == null || !rank.isOfficer()) {
            throw new CommandFail(rawMsg("not-officer"));
        }
    }

    private boolean rosterEditable(Entry e) {
        return !runner.involves(e.crewId);
    }

    private void register(CommandSender sender) {
        Player p = AquaticCommand.player(sender);
        Crew c = crewOrFail(sender);
        requireOfficer(p, c);
        if (stage != Stage.REGISTRATION) {
            throw new CommandFail(rawMsg("registration-closed"));
        }
        if (entries.containsKey(c.id())) {
            throw new CommandFail(rawMsg("already-registered"));
        }
        int max = config().getInt("registration.max-crews", 0);
        if (max > 0 && entries.size() >= max) {
            throw new CommandFail(rawMsg("registration-full", "max", max));
        }
        int minMembers = config().getInt("registration.min-members", 1);
        if (c.members().size() < minMembers) {
            throw new CommandFail(rawMsg("too-few-members", "min", minMembers));
        }
        double fee = config().getDouble("registration.entry-fee", 0);
        if (fee > 0 && !c.withdrawBank(fee)) {
            throw new CommandFail(rawMsg("fee-missing", "fee", Util.money(this, fee)));
        }
        Entry e = new Entry(c.id(), c.name());
        int size = Math.max(1, config().getInt("teams.size", 3));
        e.roster.add(c.captain());
        for (Player online : c.onlineMembers()) {
            if (e.roster.size() >= size) {
                break;
            }
            if (!e.roster.contains(online.getUniqueId())) {
                e.roster.add(online.getUniqueId());
            }
        }
        for (UUID m : c.members()) {
            if (e.roster.size() >= size) {
                break;
            }
            if (!e.roster.contains(m)) {
                e.roster.add(m);
            }
        }
        entries.put(c.id(), e);
        saveState();
        announce("registered", "crew", c.displayName(), "count", entries.size());
        c.broadcast(msg("roster-show", "roster", rosterNames(e), "size", size));
    }

    private void unregister(CommandSender sender) {
        Player p = AquaticCommand.player(sender);
        Crew c = crewOrFail(sender);
        requireOfficer(p, c);
        if (stage != Stage.REGISTRATION) {
            throw new CommandFail(rawMsg("unregister-closed"));
        }
        if (entries.remove(c.id()) == null) {
            throw new CommandFail(rawMsg("not-registered"));
        }
        double fee = config().getDouble("registration.entry-fee", 0);
        if (fee > 0 && config().getBoolean("registration.refund-on-unregister", true)) {
            c.depositBank(fee);
        }
        saveState();
        announce("unregistered", "crew", c.displayName());
    }

    private String rosterNames(Entry e) {
        List<String> names = new ArrayList<>();
        for (UUID u : e.roster) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            names.add(op.getName() == null ? u.toString().substring(0, 8) : op.getName());
        }
        return names.isEmpty() ? "-" : String.join(", ", names);
    }

    private void roster(CommandSender sender, String[] args) {
        Player p = AquaticCommand.player(sender);
        Crew c = crewOrFail(sender);
        Entry e = entries.get(c.id());
        if (e == null) {
            throw new CommandFail(rawMsg("not-registered"));
        }
        int size = Math.max(1, config().getInt("teams.size", 3));
        if (args.length == 0) {
            send(sender, "roster-show", "roster", rosterNames(e), "size", size);
            return;
        }
        requireOfficer(p, c);
        if (!rosterEditable(e)) {
            throw new CommandFail(rawMsg("roster-locked"));
        }
        AquaticCommand.need(args, 2, "/tournament roster <add|remove> <member>");
        UUID target = null;
        for (UUID m : c.members()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(m);
            if (op.getName() != null && op.getName().equalsIgnoreCase(args[1])) {
                target = m;
            }
        }
        if (target == null) {
            throw new CommandFail(rawMsg("not-member", "player", args[1]));
        }
        if (args[0].equalsIgnoreCase("add")) {
            if (e.roster.contains(target)) {
                throw new CommandFail(rawMsg("already-in-roster", "player", args[1]));
            }
            if (e.roster.size() >= size) {
                throw new CommandFail(rawMsg("roster-full", "size", size));
            }
            e.roster.add(target);
        } else if (args[0].equalsIgnoreCase("remove")) {
            if (!e.roster.remove(target)) {
                throw new CommandFail(rawMsg("not-in-roster", "player", args[1]));
            }
        } else {
            throw new CommandFail("<red>Usage: <white>/tournament roster <add|remove> <member>");
        }
        saveState();
        c.broadcast(msg("roster-show", "roster", rosterNames(e), "size", size));
    }

    private void sendStandings(CommandSender to, boolean all) {
        List<Entry> r = ranked();
        to.sendMessage(msg("standings-header", "stage", stageName()));
        if (r.isEmpty()) {
            to.sendMessage(rawMsg("standings-empty"));
            return;
        }
        int limit = all ? r.size() : Math.min(10, r.size());
        int slots = qualifierSlots();
        for (int i = 0; i < limit; i++) {
            to.sendMessage(rawMsg(i < slots ? "standings-entry-qualified" : "standings-entry",
                    "rank", i + 1, "crew", crewName(r.get(i).crewId), "points", r.get(i).points));
        }
    }

    private void sendBracket(CommandSender to) {
        if (rounds.isEmpty()) {
            throw new CommandFail(rawMsg("no-bracket"));
        }
        to.sendMessage(msg("bracket-header", "season", season));
        for (List<BracketMatch> round : rounds) {
            to.sendMessage(rawMsg("bracket-round", "round", roundName(round.size())));
            for (BracketMatch m : round) {
                if (m.isBye()) {
                    to.sendMessage(rawMsg("bracket-bye", "a", crewName(m.a)));
                } else if (m.done()) {
                    to.sendMessage(rawMsg("bracket-done", "a", crewName(m.a), "b", crewName(m.b), "winner", crewName(m.winner),
                            "score", round.size() == 1 ? " (" + m.winsA + "-" + m.winsB + ")" : ""));
                } else {
                    boolean live = runner.current() == m;
                    to.sendMessage(rawMsg(live ? "bracket-live" : "bracket-pending", "a", crewName(m.a), "b", crewName(m.b),
                            "score", round.size() == 1 ? " (" + m.winsA + "-" + m.winsB + ")" : ""));
                }
            }
        }
    }

    private void status(CommandSender to) {
        to.sendMessage(msg("status-header", "season", season, "stage", stageName()));
        if (stageEndsAt > 0) {
            to.sendMessage(rawMsg("status-time", "time", Text.duration(Math.max(0, (stageEndsAt - System.currentTimeMillis()) / 1000))));
        }
        to.sendMessage(rawMsg("status-entries", "count", entries.size()));
        to.sendMessage(rawMsg("status-king", "king", kingName == null ? "-" : kingName));
        to.sendMessage(rawMsg("status-arenas", "count", arenas.values().stream().filter(Arena::complete).count(), "total", arenas.size()));
        if (runner.active()) {
            to.sendMessage(rawMsg("status-match", "a", crewName(runner.current().a), "b", crewName(runner.current().b)));
        }
    }

    private void forceWin(String crewName) {
        if (stage != Stage.BRACKET) {
            throw new CommandFail(rawMsg("not-bracket"));
        }
        CrewService crews = service(CrewService.class);
        Crew c = crews == null ? null : crews.crewByName(crewName);
        String id = c != null ? c.id() : null;
        if (id == null) {
            for (Entry e : entries.values()) {
                if (e.name.equalsIgnoreCase(crewName)) {
                    id = e.crewId;
                }
            }
        }
        if (id == null) {
            throw new CommandFail(rawMsg("unknown-crew", "crew", crewName));
        }
        if (runner.involves(id)) {
            runner.finish(id, "forced");
            return;
        }
        BracketMatch m = peekNext();
        if (m == null || !(id.equals(m.a) || id.equals(m.b))) {
            throw new CommandFail(rawMsg("not-in-next-match", "crew", crewName));
        }
        if (rounds.get(rounds.size() - 1).size() == 1) {
            if (id.equals(m.a)) {
                m.winsA = bestOf() / 2 + 1;
            } else {
                m.winsB = bestOf() / 2 + 1;
            }
        }
        m.winner = id;
        saveState();
        announce("match-won", "crew", crewName(id), "loser", crewName(id.equals(m.a) ? m.b : m.a),
                "reason", Text.plain(rawMsg("reason-forced")));
        if (rounds.get(rounds.size() - 1).size() == 1) {
            crown(id);
        } else {
            scheduleNext();
        }
    }

    private List<String> crewNames(String prefix) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            out.add(e.name);
        }
        return AquaticCommand.filter(out, prefix);
    }

    private Arena arena(String name) {
        Arena a = arenas.get(name.toLowerCase(Locale.ROOT));
        if (a == null) {
            throw new CommandFail(rawMsg("unknown-arena", "arena", name));
        }
        return a;
    }

    private void arenaCommand(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 1, "/tournament arena <create|setspawn|setspectator|setlobby|delete|list> ...");
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                send(sender, "arena-list-header", "count", arenas.size());
                for (Arena a : arenas.values()) {
                    sender.sendMessage(rawMsg("arena-list-entry", "arena", a.name,
                            "state", Text.plain(rawMsg(a.complete() ? "arena-ready" : "arena-incomplete"))));
                }
                sender.sendMessage(rawMsg("arena-lobby", "lobby", lobby == null ? "-" : Util.coords(lobby)));
            }
            case "create" -> {
                AquaticCommand.need(args, 2, "/tournament arena create <name>");
                String name = args[1].toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9_-]{1,32}")) {
                    throw new CommandFail(rawMsg("invalid-name"));
                }
                if (arenas.containsKey(name)) {
                    throw new CommandFail(rawMsg("arena-exists", "arena", name));
                }
                arenas.put(name, new Arena(name));
                saveArenas();
                send(sender, "arena-created", "arena", name);
            }
            case "setspawn" -> {
                Player p = AquaticCommand.player(sender);
                AquaticCommand.need(args, 3, "/tournament arena setspawn <name> <1|2>");
                Arena a = arena(args[1]);
                if (args[2].equals("1")) {
                    a.spawn1 = p.getLocation();
                } else if (args[2].equals("2")) {
                    a.spawn2 = p.getLocation();
                } else {
                    throw new CommandFail("<red>Spawn must be 1 or 2.");
                }
                saveArenas();
                send(sender, "arena-spawn-set", "arena", a.name, "spawn", args[2]);
            }
            case "setspectator" -> {
                Player p = AquaticCommand.player(sender);
                AquaticCommand.need(args, 2, "/tournament arena setspectator <name>");
                Arena a = arena(args[1]);
                a.spectator = p.getLocation();
                saveArenas();
                send(sender, "arena-spectator-set", "arena", a.name);
            }
            case "setlobby" -> setLobby(sender);
            case "delete" -> {
                AquaticCommand.need(args, 2, "/tournament arena delete <name>");
                Arena a = arena(args[1]);
                if (runner.active()) {
                    throw new CommandFail(rawMsg("match-running"));
                }
                arenas.remove(a.name);
                saveArenas();
                send(sender, "arena-deleted", "arena", a.name);
            }
            default -> throw new CommandFail("<red>Usage: <white>/tournament arena <create|setspawn|setspectator|setlobby|delete|list>");
        }
    }

    private void setLobby(CommandSender sender) {
        lobby = AquaticCommand.player(sender).getLocation();
        saveArenas();
        send(sender, "lobby-set");
    }

    private void registerCommands() {
        String admin = "aquatic.tournament.admin";
        AquaticCommand cmd = new AquaticCommand("tournament", "The Sea Sovereign tournament", "tourney", "pkt");

        // ---- players
        cmd.sub("register", (s, a) -> register(s)).description("Register your crew (officers)")
                .playerPermission("aquatic.tournament.register").playerOnly();
        cmd.sub("unregister", (s, a) -> unregister(s)).description("Withdraw your crew (officers)")
                .playerPermission("aquatic.tournament.register").playerOnly();
        cmd.sub("roster", this::roster).usage("[add|remove <member>]").description("Show or edit your crew's fighters")
                .playerPermission("aquatic.tournament.register").playerOnly()
                .completer((s, a) -> {
                    if (a.length == 1) {
                        return AquaticCommand.filter(List.of("add", "remove"), a[0]);
                    }
                    if (a.length == 2 && s instanceof Player p) {
                        CrewService crews = service(CrewService.class);
                        Crew c = crews == null ? null : crews.crewOf(p.getUniqueId());
                        List<String> names = new ArrayList<>();
                        if (c != null) {
                            for (UUID m : c.members()) {
                                String n = Bukkit.getOfflinePlayer(m).getName();
                                if (n != null) {
                                    names.add(n);
                                }
                            }
                        }
                        return AquaticCommand.filter(names, a[1]);
                    }
                    return List.of();
                });
        cmd.sub("standings", (s, a) -> sendStandings(s, false)).description("Qualifier points")
                .playerPermission("aquatic.tournament.view");
        cmd.sub("bracket", (s, a) -> sendBracket(s)).description("Show the elimination bracket")
                .playerPermission("aquatic.tournament.view");
        cmd.sub("info", (s, a) -> {
            send(s, "info", "stage", stageName(), "season", season, "king", kingName == null ? "-" : kingName);
            if (stageEndsAt > 0) {
                s.sendMessage(rawMsg("status-time", "time", Text.duration(Math.max(0, (stageEndsAt - System.currentTimeMillis()) / 1000))));
            }
        }).description("Tournament stage and the current Sea Sovereign").playerPermission("aquatic.tournament.view");

        // ---- staff
        cmd.sub("open", (s, a) -> open(a.length > 0 ? Math.max(0, AquaticCommand.integer(a[0]))
                        : config().getLong("registration.default-minutes", 30)))
                .usage("[minutes]").description("Open registration (0 = until /tournament start)").permission(admin)
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(List.of("0", "15", "30", "60"), a[0]) : List.of());
        cmd.sub("start", (s, a) -> advance()).description("Force the next stage").permission(admin);
        cmd.sub("next", (s, a) -> startNext()).description("Start the next bracket match").permission(admin);
        cmd.sub("cancel", (s, a) -> cancel()).description("Cancel the tournament").permission(admin);
        cmd.sub("status", (s, a) -> status(s)).description("Detailed tournament status").permission(admin);
        cmd.sub("forcewin", (s, a) -> {
            AquaticCommand.need(a, 1, "/tournament forcewin <crew>");
            forceWin(String.join(" ", a));
        }).usage("<crew>").description("Award the current/next match to a crew").permission(admin)
                .completer((s, a) -> a.length == 1 ? crewNames(a[0]) : List.of());
        cmd.sub("arena", this::arenaCommand).usage("<create|setspawn|setspectator|setlobby|delete|list> [name] [1|2]")
                .description("Arena setup").permission(admin)
                .completer((s, a) -> {
                    if (a.length == 1) {
                        return AquaticCommand.filter(List.of("create", "setspawn", "setspectator", "setlobby", "delete", "list"), a[0]);
                    }
                    if (a.length == 2 && !a[0].equalsIgnoreCase("create") && !a[0].equalsIgnoreCase("list")) {
                        return AquaticCommand.filter(arenas.keySet(), a[1]);
                    }
                    if (a.length == 3 && a[0].equalsIgnoreCase("setspawn")) {
                        return AquaticCommand.filter(List.of("1", "2"), a[2]);
                    }
                    return List.of();
                });
        cmd.sub("setlobby", (s, a) -> setLobby(s)).description("Set the tournament lobby").permission(admin).playerOnly();
        cmd.sub("admin", (s, a) -> {
            AquaticCommand.need(a, 2, "/tournament admin setking <player|none>");
            if (!a[0].equalsIgnoreCase("setking")) {
                throw new CommandFail("<red>Usage: <white>/tournament admin setking <player|none>");
            }
            if (a[1].equalsIgnoreCase("none")) {
                if (king != null) {
                    previousKing = king;
                }
                king = null;
                kingName = null;
                saveHall();
                send(s, "king-cleared");
                announce("throne-empty");
                return;
            }
            Player online = Bukkit.getPlayerExact(a[1]);
            OfflinePlayer op = online != null ? online : Bukkit.getOfflinePlayerIfCached(a[1]);
            if (op == null) {
                throw new CommandFail("<red>Player <white><name></white> was never seen on this server.", "name", a[1]);
            }
            String name = op.getName() == null ? a[1] : op.getName();
            setKing(op.getUniqueId(), name);
            CrewService crews = service(CrewService.class);
            Crew crew = crews == null ? null : crews.crewOf(op.getUniqueId());
            Bukkit.getPluginManager().callEvent(new PirateKingCrownedEvent(op.getUniqueId(), name, crew));
            send(s, "king-set", "player", name);
            announce("king-appointed", "player", name);
        }).usage("setking <player|none>").description("Set or clear the Sea Sovereign").permission(admin)
                .completer((s, a) -> {
                    if (a.length == 1) {
                        return AquaticCommand.filter(List.of("setking"), a[0]);
                    }
                    if (a.length == 2) {
                        List<String> names = AquaticCommand.playerNames(a[1]);
                        if ("none".startsWith(a[1].toLowerCase(Locale.ROOT))) {
                            names.add("none");
                        }
                        return names;
                    }
                    return List.of();
                });
        registerCommand(cmd);

        AquaticCommand hallCmd = new AquaticCommand("hallofkings", "Past Sea Sovereigns", "kings");
        hallCmd.defaultExecutor((s, a) -> {
            if (!s.hasPermission("aquatic.tournament.view") && !s.hasPermission("aquatic.admin")) {
                throw new CommandFail("<red>You don't have permission to do that.");
            }
            showHall(s);
        });
        hallCmd.sub("list", (s, a) -> showHall(s)).description("List all past Sea Sovereigns").playerPermission("aquatic.tournament.view");
        registerCommand(hallCmd);
    }

    private void showHall(CommandSender s) {
        s.sendMessage(msg("hall-header", "count", hall.size()));
        if (hall.isEmpty()) {
            s.sendMessage(rawMsg("hall-empty"));
        }
        for (Map<String, Object> rec : hall) {
            s.sendMessage(rawMsg("hall-entry", "season", rec.getOrDefault("season", "?"), "player", rec.getOrDefault("name", "?"),
                    "crew", rec.getOrDefault("crew", "?"), "date", rec.getOrDefault("date", "?")));
        }
        s.sendMessage(rawMsg("hall-current", "king", kingName == null ? "-" : kingName));
    }
}
