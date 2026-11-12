package dev.aquatic.command;

import dev.aquatic.core.AquaticPlugin;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * A root command with sub-commands, registered at runtime (no plugin.yml entry needed).
 *
 * <pre>
 * AquaticCommand cmd = new AquaticCommand("crew", "Crew commands", "c");
 * cmd.sub("create", (sender, args) -> { Player p = AquaticCommand.player(sender); ... })
 *    .usage("&lt;name&gt;").description("Create a crew").playerOnly();
 * cmd.sub("forcedisband", ...).permission("aquatic.crew.admin");   // op-only by default
 * registerCommand(cmd);   // inside an AquaticModule
 * </pre>
 * "args" passed to a sub-command do NOT contain the sub-command name itself.
 * Running the root command without arguments shows an auto-generated help page
 * (or calls {@link #defaultExecutor}).
 */
public class AquaticCommand extends Command {

    @FunctionalInterface
    public interface Executor {
        void run(CommandSender sender, String[] args);
    }

    @FunctionalInterface
    public interface Completer {
        List<String> complete(CommandSender sender, String[] args);
    }

    public static final class Sub {
        private final String name;
        private final Executor executor;
        private String[] aliases = new String[0];
        private String usage = "";
        private String description = "";
        private String permission;
        private boolean playerOnly;
        private boolean hidden;
        private Completer completer = (s, a) -> List.of();

        private Sub(String name, Executor executor) {
            this.name = name;
            this.executor = executor;
        }

        public Sub aliases(String... aliases) {
            this.aliases = aliases;
            return this;
        }

        /** Argument synopsis shown in help, e.g. "&lt;player&gt; [amount]". */
        public Sub usage(String usage) {
            this.usage = usage;
            return this;
        }

        public Sub description(String description) {
            this.description = description;
            return this;
        }

        /** Admin permission: only ops (or players granted the node) may use this sub-command. */
        public Sub permission(String node) {
            this.permission = node;
            ensurePermission(node, PermissionDefault.OP);
            return this;
        }

        /** Player permission: everybody has it by default, but it can be revoked with a permission plugin. */
        public Sub playerPermission(String node) {
            this.permission = node;
            ensurePermission(node, PermissionDefault.TRUE);
            return this;
        }

        /**
         * Keeps the sub-command working but leaves it out of the main help and first-argument tab completion.
         * Use it for a group shown under another sub-command, see {@link #sendHiddenHelp} and {@link #completeHidden}.
         */
        public Sub hidden() {
            this.hidden = true;
            return this;
        }

        public Sub playerOnly() {
            this.playerOnly = true;
            return this;
        }

        public Sub completer(Completer completer) {
            this.completer = completer;
            return this;
        }

        public String name() {
            return name;
        }

        boolean allowed(CommandSender sender) {
            return permission == null || sender.hasPermission(permission) || sender.hasPermission("aquatic.admin");
        }
    }

    /** Every registered AquaticSMP root command (for /aquatic commands). */
    private static final Map<String, AquaticCommand> REGISTERED = new java.util.TreeMap<>();
    private static final int HELP_PAGE_SIZE = 8;

    public static java.util.Collection<AquaticCommand> registered() {
        return java.util.Collections.unmodifiableCollection(REGISTERED.values());
    }

    private final Map<String, Sub> subs = new LinkedHashMap<>();
    private final Map<String, Sub> lookup = new LinkedHashMap<>();
    private Executor defaultExecutor;
    /** Claim the command names even when another plugin (e.g. EssentialsX) registered them too. */
    private boolean takeOver;

    /**
     * Makes this command win its names (and aliases) over other plugins, also when they load later.
     * The other plugin's version stays reachable as /plugin:name (e.g. /essentials:pay).
     */
    public AquaticCommand takeOver(boolean on) {
        this.takeOver = on;
        return this;
    }

    private void claimLabels() {
        try {
            Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
            List<String> labels = new ArrayList<>();
            labels.add(getName().toLowerCase());
            for (String alias : getAliases()) {
                labels.add(alias.toLowerCase());
            }
            for (String label : labels) {
                if (known.get(label) != this) {
                    known.put(label, this);
                }
            }
        } catch (Throwable t) {
            AquaticPlugin.get().getLogger().log(Level.WARNING, "Could not claim the name /" + getName(), t);
        }
    }
    private Completer defaultCompleter;

    public AquaticCommand(String name, String description, String... aliases) {
        super(name, description, "/" + name, Arrays.asList(aliases));
    }

    /** Restricts the whole command (admin, op default). */
    public AquaticCommand permission(String node) {
        ensurePermission(node, PermissionDefault.OP);
        setPermission(node);
        return this;
    }

    public Sub sub(String name, Executor executor) {
        Sub sub = new Sub(name.toLowerCase(), executor);
        subs.put(sub.name, sub);
        return sub;
    }

    /** Called when the command is run without a (known) sub-command; receives all arguments. */
    public AquaticCommand defaultExecutor(Executor executor) {
        this.defaultExecutor = executor;
        return this;
    }

    /** Extra first-argument suggestions used together with {@link #defaultExecutor}. */
    public AquaticCommand defaultCompleter(Completer completer) {
        this.defaultCompleter = completer;
        return this;
    }

    // ------------------------------------------------------------------ registration

    public void register(AquaticPlugin plugin) {
        lookup.clear();
        for (Sub sub : subs.values()) {
            lookup.put(sub.name, sub);
            for (String alias : sub.aliases) {
                lookup.put(alias.toLowerCase(), sub);
            }
        }
        Bukkit.getCommandMap().register("aquatic", this);
        REGISTERED.put(getName(), this);
        if (takeOver) {
            claimLabels();
            // plugins that load after us (EssentialsX ...) may grab the names again: claim them once more
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (REGISTERED.get(getName()) == this) {
                    claimLabels();
                    syncCommands();
                }
            }, 40L);
        }
        syncCommands();
    }

    public void unregister() {
        REGISTERED.remove(getName(), this);
        CommandMap map = Bukkit.getCommandMap();
        try {
            // Paper's known-commands map is a Brigadier-backed view: its iterators do not support remove(),
            // so collect the labels first and remove them one by one.
            Map<String, Command> known = map.getKnownCommands();
            List<String> labels = new ArrayList<>();
            for (Map.Entry<String, Command> e : known.entrySet()) {
                if (e.getValue() == this) {
                    labels.add(e.getKey());
                }
            }
            for (String label : labels) {
                known.remove(label);
            }
        } catch (Throwable t) {
            AquaticPlugin.get().getLogger().log(Level.WARNING, "Could not fully unregister /" + getName(), t);
        }
        unregister(map);
        syncCommands();
    }

    private static void syncCommands() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.updateCommands();
        }
    }

    private static void ensurePermission(String node, PermissionDefault def) {
        if (Bukkit.getPluginManager().getPermission(node) == null) {
            try {
                Bukkit.getPluginManager().addPermission(new Permission(node, def));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ execution

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (getPermission() != null && !sender.hasPermission(getPermission()) && !sender.hasPermission("aquatic.admin")) {
            sender.sendMessage(Text.mm("<red>You don't have permission to do that."));
            return true;
        }
        try {
            Sub sub = args.length > 0 ? lookup.get(args[0].toLowerCase()) : null;
            if (sub == null) {
                if (defaultExecutor != null && (args.length == 0 || !args[0].equalsIgnoreCase("help"))) {
                    defaultExecutor.run(sender, args);
                } else {
                    int page = 1;
                    if (args.length > 1) {
                        try {
                            page = Integer.parseInt(args[1]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    sendHelp(sender, label, page);
                }
                return true;
            }
            if (!sub.allowed(sender)) {
                throw new CommandFail("<red>You don't have permission to do that.");
            }
            if (sub.playerOnly && !(sender instanceof Player)) {
                throw new CommandFail("<red>Only players can use this command.");
            }
            sub.executor.run(sender, Arrays.copyOfRange(args, 1, args.length));
        } catch (CommandFail fail) {
            sender.sendMessage(AquaticPlugin.get().prefix().append(fail.component()));
        } catch (Throwable t) {
            sender.sendMessage(Text.mm("<red>Something went wrong while running that command."));
            AquaticPlugin.get().getLogger().log(Level.SEVERE, "Error in /" + label + " " + String.join(" ", args), t);
        }
        return true;
    }

    public void sendHelp(CommandSender sender, String label) {
        sendHelp(sender, label, 1);
    }

    /** Paged help: hover a line for details, click it to put the command in your chat bar. */
    public void sendHelp(CommandSender sender, String label, int page) {
        List<Sub> visible = new ArrayList<>();
        for (Sub sub : subs.values()) {
            if (!sub.hidden && sub.allowed(sender)) {
                visible.add(sub);
            }
        }
        int pages = Math.max(1, (visible.size() + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE);
        page = Math.max(1, Math.min(pages, page));
        sender.sendMessage(Text.mm("<dark_aqua><strikethrough>          </strikethrough> <aqua><bold>/<label></bold> <gray><desc> "
                        + "<dark_gray>(<page>/<pages>)</dark_gray> <dark_aqua><strikethrough>          </strikethrough>",
                "label", label, "desc", getDescription(), "page", page, "pages", pages));
        for (int i = (page - 1) * HELP_PAGE_SIZE; i < Math.min(visible.size(), page * HELP_PAGE_SIZE); i++) {
            helpLine(sender, label, visible.get(i));
        }
        if (visible.isEmpty()) {
            sender.sendMessage(Text.mm("<gray>No sub-commands you can use."));
        }
        if (pages > 1) {
            Component nav = Component.empty();
            if (page > 1) {
                nav = nav.append(Text.mm("<aqua>« Previous</aqua>")
                        .clickEvent(ClickEvent.runCommand("/" + label + " help " + (page - 1)))
                        .hoverEvent(HoverEvent.showText(Text.mm("<gray>Page " + (page - 1)))));
            }
            nav = nav.append(Text.mm("  <dark_gray>|</dark_gray>  "));
            if (page < pages) {
                nav = nav.append(Text.mm("<aqua>Next »</aqua>")
                        .clickEvent(ClickEvent.runCommand("/" + label + " help " + (page + 1)))
                        .hoverEvent(HoverEvent.showText(Text.mm("<gray>Page " + (page + 1)))));
            }
            sender.sendMessage(nav);
        }
        if (sender instanceof Player) {
            sender.sendMessage(Text.mm("<dark_gray>Hover a command for details, click to use it."));
        }
    }

    /** Help page listing only the hidden sub-commands, e.g. sendHiddenHelp(sender, "island admin"). */
    public void sendHiddenHelp(CommandSender sender, String label) {
        sender.sendMessage(Text.mm("<dark_aqua><strikethrough>        </strikethrough> <aqua><bold>/<label></bold> <dark_aqua><strikethrough>        </strikethrough>", "label", label));
        for (Sub sub : subs.values()) {
            if (sub.hidden && sub.allowed(sender)) {
                helpLine(sender, label, sub);
            }
        }
    }

    private static void helpLine(CommandSender sender, String label, Sub sub) {
        String full = "/" + label + " " + sub.name + (sub.usage.isEmpty() ? "" : " " + sub.usage);
        StringBuilder hover = new StringBuilder("<aqua><bold>").append(escape(full)).append("</bold></aqua><newline><gray>")
                .append(escape(sub.description));
        if (sub.aliases.length > 0) {
            hover.append("<newline><dark_gray>Aliases: ").append(escape(String.join(", ", sub.aliases)));
        }
        if (!sub.usage.isEmpty()) {
            hover.append("<newline><dark_gray>\\<arg> = needed, [arg] = optional");
        }
        if (sub.permission != null && sender.hasPermission("aquatic.admin")) {
            hover.append("<newline><dark_gray>Permission: ").append(sub.permission);
        }
        if (sub.playerOnly) {
            hover.append("<newline><dark_gray>Players only");
        }
        hover.append("<newline><newline><yellow>Click to type it in chat");
        sender.sendMessage(Text.mm("<dark_gray>▪</dark_gray> <aqua>/<label> <name></aqua><white><usage></white> <dark_gray>-</dark_gray> <gray><desc>",
                        "label", label, "name", sub.name, "usage", sub.usage.isEmpty() ? "" : " " + sub.usage, "desc", sub.description)
                .hoverEvent(HoverEvent.showText(Text.mm(hover.toString())))
                .clickEvent(ClickEvent.suggestCommand("/" + label + " " + sub.name + " ")));
    }

    /** Escapes MiniMessage tags in plain text (usages like "<player>"). */
    private static String escape(String raw) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(raw);
    }

    /** One line for the /aquatic commands overview: "/crew - Crew commands", clickable. */
    public Component overviewLine(CommandSender sender) {
        int count = 0;
        for (Sub sub : subs.values()) {
            if (!sub.hidden && sub.allowed(sender)) {
                count++;
            }
        }
        String aliases = getAliases().isEmpty() ? "" : "<newline><dark_gray>Aliases: /" + escape(String.join(", /", getAliases()));
        return Text.mm("<dark_gray>▪</dark_gray> <aqua>/<name></aqua> <dark_gray>-</dark_gray> <gray><desc>", "name", getName(), "desc", getDescription())
                .hoverEvent(HoverEvent.showText(Text.mm("<aqua><bold>/" + escape(getName()) + "</bold><newline><gray>"
                        + escape(getDescription()) + "<newline><dark_gray>" + count + " sub-command(s)" + aliases
                        + "<newline><newline><yellow>Click for its help page")))
                .clickEvent(ClickEvent.runCommand("/" + getName() + " help"));
    }

    /** True when the sender may use the root command at all. */
    public boolean usableBy(CommandSender sender) {
        return getPermission() == null || sender.hasPermission(getPermission()) || sender.hasPermission("aquatic.admin");
    }

    /** Tab completion over the hidden sub-commands only (for a group like "/island admin ..."). */
    public List<String> completeHidden(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            List<String> out = new ArrayList<>();
            for (Sub sub : subs.values()) {
                if (sub.hidden && sub.allowed(sender)) {
                    out.add(sub.name);
                }
            }
            return filter(out, args.length == 0 ? "" : args[0]);
        }
        Sub sub = lookup.get(args[0].toLowerCase());
        if (sub == null || !sub.hidden || !sub.allowed(sender)) {
            return List.of();
        }
        List<String> result = sub.completer.complete(sender, Arrays.copyOfRange(args, 1, args.length));
        return result == null ? List.of() : result;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        try {
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0];
                List<String> out = new ArrayList<>();
                for (Sub sub : subs.values()) {
                    if (!sub.hidden && sub.allowed(sender)) {
                        out.add(sub.name);
                    }
                }
                if (defaultCompleter != null) {
                    out.addAll(defaultCompleter.complete(sender, args));
                }
                return filter(out, prefix);
            }
            Sub sub = lookup.get(args[0].toLowerCase());
            if (sub != null && sub.allowed(sender)) {
                List<String> result = sub.completer.complete(sender, Arrays.copyOfRange(args, 1, args.length));
                return result == null ? List.of() : result;
            }
            if (sub == null && defaultCompleter != null) {
                List<String> result = defaultCompleter.complete(sender, args);
                return result == null ? List.of() : result;
            }
        } catch (Throwable ignored) {
        }
        return List.of();
    }

    // ------------------------------------------------------------------ helpers for executors

    /** @return the sender as Player, or aborts with a message. */
    public static Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        throw new CommandFail("<red>Only players can use this command.");
    }

    /** Aborts with a usage message when fewer than "min" arguments were given. */
    public static void need(String[] args, int min, String usage) {
        if (args.length < min) {
            throw new CommandFail("<red>Usage: <white><usage>", "usage", usage);
        }
    }

    public static Player onlinePlayer(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            throw new CommandFail("<red>Player <white><name></white> is not online.", "name", name);
        }
        return p;
    }

    public static double number(String raw) {
        try {
            double d = Double.parseDouble(raw);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException();
            }
            return d;
        } catch (NumberFormatException e) {
            throw new CommandFail("<red><raw> is not a valid number.", "raw", raw);
        }
    }

    public static int integer(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new CommandFail("<red><raw> is not a valid whole number.", "raw", raw);
        }
    }

    /** Case-insensitive prefix filter for tab completion. */
    public static List<String> filter(Collection<String> options, String prefix) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase().startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    public static List<String> playerNames(String prefix) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return filter(names, prefix);
    }
}
