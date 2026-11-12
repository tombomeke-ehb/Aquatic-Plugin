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

    @Override
    public String id() {
        return "guide";
    }

    @Override
    protected void onEnable() {
        seen.clear();
        playing.clear();
        for (String raw : loadData("seen").getStringList("players")) {
            try {
                seen.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }
        registerListener(this);
        registerCommands();
    }

    @Override
    protected void onDisable() {
        playing.values().forEach(BukkitTask::cancel);
        playing.clear();
        save();
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (UUID u : seen) {
            list.add(u.toString());
        }
        data.set("players", list);
        saveData("seen", data);
    }

    // ------------------------------------------------------------------ intro

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
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
        List<Map<?, ?>> steps = config().getMapList("intro.steps");
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
        cmd.sub("chapters", (sender, args) -> send(sender, "chapters", "chapters", String.join(", ", chapters())))
                .description("List the guide chapters (/guide <chapter> opens one)");

        cmd.sub("play", (sender, args) -> {
            AquaticCommand.need(args, 1, "/guide play <player>");
            Player target = AquaticCommand.onlinePlayer(args[0]);
            playIntro(target);
            send(sender, "admin-played", "player", target.getName());
        }).usage("<player>").description("Play the intro for a player").permission(ADMIN)
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
}
