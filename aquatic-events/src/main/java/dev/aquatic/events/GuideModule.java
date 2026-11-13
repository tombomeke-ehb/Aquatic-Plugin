package dev.aquatic.events;

import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * New-player intro and the in-game guide book.
 * <ul>
 *   <li>On a player's first join (or every join, see intro.mode) a short sequence of titles and chat
 *       tips plays, explaining crews, islands, flags, treasure digging, bounties and the Pirate King.</li>
 *   <li>/guide opens a written book with every chapter; /guide &lt;chapter&gt; opens one chapter.</li>
 * </ul>
 * Everything (texts, timing, chapters) lives in modules/guide.yml.
 */
public final class GuideModule extends AquaticModule implements Listener {

    private static final String ADMIN = "aquatic.guide.admin";

    /** Players who already saw the intro. */
    private final Set<UUID> seen = new HashSet<>();
    /** Intro sequences that are playing right now. */
    private final Map<UUID, BukkitTask> playing = new HashMap<>();
    /** Players who already received the starter kit. */
    private final Set<UUID> kitGiven = new HashSet<>();
    /** Players who turned the tips off with /guide tips off. */
    private final Set<UUID> tipsOff = new HashSet<>();
    private int nextTip;

    @Override
    public String id() {
        return "guide";
    }

    @Override
    protected void onEnable() {
        seen.clear();
        playing.clear();
        kitGiven.clear();
        tipsOff.clear();
        YamlConfiguration data = loadData("seen");
        readUuids(data.getStringList("players"), seen);
        readUuids(data.getStringList("kit-given"), kitGiven);
        readUuids(data.getStringList("tips-off"), tipsOff);
        registerListener(this);
        registerDiscord();
        registerCommands();
        long tipTicks = Math.max(1, config().getLong("tips.interval-minutes", 10)) * 60L * 20L;
        runTimer(this::broadcastTips, tipTicks, tipTicks);
    }

    @Override
    protected void onDisable() {
        playing.values().forEach(BukkitTask::cancel);
        playing.clear();
        save();
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("players", writeUuids(seen));
        data.set("kit-given", writeUuids(kitGiven));
        data.set("tips-off", writeUuids(tipsOff));
        saveData("seen", data);
    }

    private static void readUuids(List<String> raw, Set<UUID> into) {
        for (String s : raw) {
            try {
                into.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static List<String> writeUuids(Set<UUID> set) {
        List<String> list = new ArrayList<>();
        set.forEach(u -> list.add(u.toString()));
        return list;
    }

    // ------------------------------------------------------------------ starter kit

    /** Gives the starter kit once per player (independent of the intro). */
    private void giveStarterKit(Player p) {
        giveStarterKit(p, false);
    }

    /** @param force give it even when the player already had it (staff command). */
    private void giveStarterKit(Player p, boolean force) {
        if (!config().getBoolean("starter-kit.enabled", true) || (!force && kitGiven.contains(p.getUniqueId()))) {
            return;
        }
        kitGiven.add(p.getUniqueId());
        save();
        for (String spec : config().getStringList("starter-kit.items")) {
            org.bukkit.inventory.ItemStack item = Util.parseItem(this, spec);
            if (item != null) {
                Util.give(p, item);
            }
        }
        double doubloons = config().getDouble("starter-kit.doubloons", 0);
        dev.aquatic.api.EconomyService eco = service(dev.aquatic.api.EconomyService.class);
        if (doubloons > 0 && eco != null) {
            eco.deposit(p.getUniqueId(), doubloons);
        }
        boolean map = false;
        String tier = config().getString("starter-kit.treasure-map-tier", "common");
        dev.aquatic.api.TreasureService treasure = service(dev.aquatic.api.TreasureService.class);
        if (config().getBoolean("starter-kit.treasure-map", true) && treasure != null) {
            org.bukkit.Location spot = treasure.spawnRandomTreasure(tier);
            org.bukkit.inventory.ItemStack mapItem = spot == null ? null : treasure.createTreasureMap(spot, tier);
            if (mapItem != null) {
                Util.give(p, mapItem);
                map = true;
            }
        }
        send(p, map ? "starter-kit-map" : "starter-kit");
    }

    // ------------------------------------------------------------------ tips

    /** Every few minutes: one tip in chat for players who are still new (and did not turn tips off). */
    private void broadcastTips() {
        List<String> tips = config().getStringList("tips.messages");
        if (!config().getBoolean("tips.enabled", true) || tips.isEmpty()) {
            return;
        }
        String tip = tips.get(nextTip++ % tips.size());
        long maxTicks = (long) (config().getDouble("tips.only-first-hours", 5) * 60 * 60 * 20);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (tipsOff.contains(p.getUniqueId())) {
                continue;
            }
            if (maxTicks > 0 && p.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) > maxTicks) {
                continue;
            }
            p.sendMessage(rawMsg("tip-format", "tip", Text.mm(tip, "player", p.getName())));
        }
    }

    // ------------------------------------------------------------------ intro

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPlayedBefore()) {
            runLater(() -> {
                if (p.isOnline()) {
                    giveStarterKit(p);
                }
            }, 40L);
        }
        if (!config().getBoolean("intro.enabled", true)) {
            return;
        }
        boolean everyJoin = "every-join".equalsIgnoreCase(config().getString("intro.mode", "first-join"));
        if (!everyJoin && seen.contains(p.getUniqueId())) {
            return;
        }
        long delay = Math.max(0, config().getLong("intro.start-delay-seconds", 3)) * 20L;
        runLater(() -> {
            if (p.isOnline()) {
                playIntro(p);
            }
        }, Math.max(1, delay));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopIntro(event.getPlayer().getUniqueId());
    }

    /** Plays the intro steps one after another. */
    void playIntro(Player p) {
        stopIntro(p.getUniqueId());
        // "intro.sequence" (2.3.0+) replaces the older "intro.steps", which had titles too long for the screen
        List<Map<?, ?>> steps = config().getMapList("intro.sequence");
        if (steps.isEmpty()) {
            return;
        }
        if (seen.add(p.getUniqueId())) {
            save();
        }
        if (config().getBoolean("intro.show-skip-hint", true)) {
            p.sendMessage(msg("intro-skip-hint"));
        }
        playStep(p, steps, 0);
    }

    private void playStep(Player p, List<Map<?, ?>> steps, int index) {
        if (!p.isOnline()) {
            playing.remove(p.getUniqueId());
            return;
        }
        if (index >= steps.size()) {
            playing.remove(p.getUniqueId());
            finishIntro(p);
            return;
        }
        ConfigurationSection step = new YamlConfiguration().createSection("step", steps.get(index));
        String name = p.getName();
        String title = step.getString("title", "");
        String subtitle = step.getString("subtitle", "");
        if (!title.isEmpty() || !subtitle.isEmpty()) {
            int stay = Math.max(1, step.getInt("title-seconds", config().getInt("intro.title-seconds", 4)));
            p.showTitle(Title.title(Text.mm(title, "player", name), Text.mm(subtitle, "player", name),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(stay), Duration.ofMillis(600))));
        }
        List<String> chat = step.getStringList("chat");
        if (!chat.isEmpty()) {
            p.sendMessage(Component.empty());
            for (String line : chat) {
                p.sendMessage(Text.mm(line, "player", name));
            }
        }
        playSound(p, step.getString("sound", config().getString("intro.sound", "block.note_block.chime")));
        long next = Math.max(1, Math.round(step.getDouble("seconds", 6) * 20));
        playing.put(p.getUniqueId(), runLater(() -> playStep(p, steps, index + 1), next));
    }

    private void finishIntro(Player p) {
        p.sendMessage(msg("intro-finished"));
        if (config().getBoolean("intro.open-book-at-end", true)) {
            openBook(p, null);
        }
    }

    private boolean stopIntro(UUID player) {
        BukkitTask task = playing.remove(player);
        if (task != null) {
            task.cancel();
            return true;
        }
        return false;
    }

    private void playSound(Player p, String sound) {
        if (sound == null || sound.isBlank() || sound.equalsIgnoreCase("none")) {
            return;
        }
        try {
            p.playSound(Sound.sound(Key.key(sound.toLowerCase(Locale.ROOT)), Sound.Source.MASTER, 0.8f, 1.0f));
        } catch (RuntimeException ignored) {
            // invalid sound key in config
        }
    }

    // ------------------------------------------------------------------ guide book

    private Set<String> chapters() {
        ConfigurationSection s = config().getConfigurationSection("book.chapters");
        return s == null ? Set.of() : new LinkedHashSet<>(s.getKeys(false));
    }

    /** Opens the guide book: every chapter, or only the given one. */
    private void openBook(Player p, String chapter) {
        List<Component> pages = new ArrayList<>();
        for (String c : chapter == null ? chapters() : Set.of(chapter)) {
            for (String page : config().getStringList("book.chapters." + c)) {
                pages.add(Text.mm(page, "player", p.getName()));
            }
        }
        if (pages.isEmpty()) {
            throw new CommandFail(rawMsg("book-empty"));
        }
        p.openBook(Book.book(Text.mm(config().getString("book.title", "Pirate's Guide")),
                Text.mm(config().getString("book.author", "AquaticSMP")), pages));
    }

    private String chapterOf(String raw) {
        for (String c : chapters()) {
            if (c.equalsIgnoreCase(raw)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        AquaticCommand cmd = new AquaticCommand("guide", "The pirate's guide to the server", "tutorial", "pirateguide");
        cmd.defaultExecutor((sender, args) -> {
            Player p = AquaticCommand.player(sender);
            if (args.length == 0) {
                openBook(p, null);
                return;
            }
            String chapter = chapterOf(args[0]);
            if (chapter == null) {
                throw new CommandFail(rawMsg("unknown-chapter", "chapter", args[0], "chapters", String.join(", ", chapters())));
            }
            openBook(p, chapter);
        }).defaultCompleter((sender, args) -> args.length <= 1
                ? AquaticCommand.filter(chapters(), args.length == 0 ? "" : args[0]) : List.of());

        cmd.sub("intro", (sender, args) -> playIntro(AquaticCommand.player(sender)))
                .description("Watch the welcome intro again").playerOnly().playerPermission("aquatic.guide.use");
        cmd.sub("skip", (sender, args) -> {
            Player p = AquaticCommand.player(sender);
            if (stopIntro(p.getUniqueId())) {
                p.clearTitle();
                send(p, "intro-skipped");
            } else {
                send(p, "intro-not-playing");
            }
        }).description("Stop the intro").playerOnly();
        cmd.sub("tips", (sender, args) -> {
            Player p = AquaticCommand.player(sender);
            boolean off = args.length > 0 ? args[0].equalsIgnoreCase("off") : !tipsOff.contains(p.getUniqueId());
            if (off) {
                tipsOff.add(p.getUniqueId());
            } else {
                tipsOff.remove(p.getUniqueId());
            }
            save();
            send(p, off ? "tips-off" : "tips-on");
        }).usage("[on|off]").description("Turn the helpful tips in chat on or off").playerOnly()
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(List.of("on", "off"), a[0]) : List.of());
        cmd.sub("chapters", (sender, args) -> send(sender, "chapters", "chapters", String.join(", ", chapters())))
                .description("List the guide chapters (/guide <chapter> opens one)");

        cmd.sub("play", (sender, args) -> {
            AquaticCommand.need(args, 1, "/guide play <player>");
            Player target = AquaticCommand.onlinePlayer(args[0]);
            playIntro(target);
            send(sender, "admin-played", "player", target.getName());
        }).usage("<player>").description("Play the intro for a player").permission(ADMIN)
                .completer((s, a) -> a.length == 1 ? AquaticCommand.playerNames(a[0]) : List.of());
        cmd.sub("kit", (sender, args) -> {
            AquaticCommand.need(args, 1, "/guide kit <player>");
            Player target = AquaticCommand.onlinePlayer(args[0]);
            giveStarterKit(target, true);
            send(sender, "admin-kit", "player", target.getName());
        }).usage("<player>").description("Give a player the starter kit").permission(ADMIN)
                .completer((s, a) -> a.length == 1 ? AquaticCommand.playerNames(a[0]) : List.of());
        cmd.sub("reset", (sender, args) -> {
            AquaticCommand.need(args, 1, "/guide reset <player|all>");
            if (args[0].equalsIgnoreCase("all")) {
                seen.clear();
                save();
                send(sender, "admin-reset-all");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null) {
                throw new CommandFail("<red>Unknown player: <white><name>", "name", args[0]);
            }
            seen.remove(target.getUniqueId());
            save();
            send(sender, "admin-reset", "player", target.getName() == null ? args[0] : target.getName());
        }).usage("<player|all>").description("Show the intro again on the next join").permission(ADMIN)
                .completer((s, a) -> {
                    if (a.length != 1) {
                        return List.of();
                    }
                    List<String> names = new ArrayList<>(AquaticCommand.playerNames(a[0]));
                    names.addAll(AquaticCommand.filter(List.of("all"), a[0]));
                    return names;
                });
        registerCommand(cmd);
    }

    /** /discord: the invite link from guide.yml. */
    private void registerDiscord() {
        if (!config().getBoolean("discord.enabled", true)) {
            return;
        }
        AquaticCommand cmd = new AquaticCommand("discord", "Our Discord server");
        cmd.defaultExecutor((s, a) -> {
            String link = config().getString("discord.link", "");
            for (String line : config().getStringList("discord.message")) {
                s.sendMessage(Text.mm(line.replace("<link>", link)));
            }
        });
        cmd.takeOver(true);
        registerCommand(cmd);
    }
}
