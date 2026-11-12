package dev.aquatic.command;

import dev.aquatic.core.AquaticPlugin;
import dev.aquatic.util.Text;
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

    private final Map<String, Sub> subs = new LinkedHashMap<>();
    private final Map<String, Sub> lookup = new LinkedHashMap<>();
    private Executor defaultExecutor;
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
        syncCommands();
    }

    public void unregister() {
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
                    sendHelp(sender, label);
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
        sender.sendMessage(Text.mm("<dark_aqua><strikethrough>        </strikethrough> <aqua><bold>/<label></bold> <dark_aqua><strikethrough>        </strikethrough>", "label", label));
        for (Sub sub : subs.values()) {
            if (sub.hidden || !sub.allowed(sender)) {
                continue;
            }
            helpLine(sender, label, sub);
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
        sender.sendMessage(Text.mm("<aqua>/<label> <name><white><usage> <dark_gray>-</dark_gray> <gray><desc>",
                "label", label, "name", sub.name, "usage", sub.usage.isEmpty() ? "" : " " + sub.usage, "desc", sub.description));
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
