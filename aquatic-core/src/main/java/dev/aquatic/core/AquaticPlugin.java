package dev.aquatic.core;

import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Plugin entry point: loads the modules that are switched on in config.yml and owns /aquatic. */
public final class AquaticPlugin extends JavaPlugin {

    /** Load order matters: providers of a service come before their consumers. */
    private static final String[][] MODULES = {
            {"economy", "dev.aquatic.crews.EconomyModule"},
            {"crews", "dev.aquatic.crews.CrewsModule"},
            {"bounty", "dev.aquatic.crews.BountyModule"},
            {"islands", "dev.aquatic.world.IslandsModule"},
            {"treasure", "dev.aquatic.world.TreasureModule"},
            {"items", "dev.aquatic.events.ItemsModule"},
            {"tournament", "dev.aquatic.events.TournamentModule"},
            {"events", "dev.aquatic.events.EventsModule"},
    };

    /** Credit shown on startup and in /aquatic about. */
    public static final String AUTHOR = "tombomeke";
    public static final String REPO = "https://github.com/tombomeke-ehb/Aquatic-Plugin";

    private static AquaticPlugin instance;
    private final Services services = new Services();
    private final Map<String, AquaticModule> modules = new LinkedHashMap<>();
    private Component prefix = Component.empty();

    public static AquaticPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadPrefix();

        for (String[] def : MODULES) {
            try {
                AquaticModule module = (AquaticModule) Class.forName(def[1]).getDeclaredConstructor().newInstance();
                module.init(this);
                modules.put(def[0], module);
            } catch (ClassNotFoundException e) {
                getLogger().warning("Module '" + def[0] + "' is not part of this build.");
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Could not create module '" + def[0] + "'", t);
            }
        }
        for (String id : modules.keySet()) {
            if (getConfig().getBoolean("modules." + id, true)) {
                enableModule(id);
            }
        }
        registerAdminCommand();
        getLogger().info("AquaticSMP enabled with modules: " + enabledModuleIds());
        printBanner();
    }

    @Override
    public void onDisable() {
        List<AquaticModule> reversed = new ArrayList<>(modules.values());
        java.util.Collections.reverse(reversed);
        for (AquaticModule module : reversed) {
            if (module.isEnabled()) {
                module.disable();
            }
        }
    }

    // ------------------------------------------------------------------ public API

    public Services services() {
        return services;
    }

    /** Global chat prefix from config.yml. */
    public Component prefix() {
        return prefix;
    }

    public AquaticModule module(String id) {
        return modules.get(id);
    }

    public boolean isModuleEnabled(String id) {
        AquaticModule m = modules.get(id);
        return m != null && m.isEnabled();
    }

    public List<String> enabledModuleIds() {
        return modules.entrySet().stream().filter(e -> e.getValue().isEnabled()).map(Map.Entry::getKey).toList();
    }

    // ------------------------------------------------------------------ module management

    private boolean enableModule(String id) {
        AquaticModule module = modules.get(id);
        if (module == null || module.isEnabled()) {
            return false;
        }
        for (String dep : module.requires()) {
            if (!isModuleEnabled(dep)) {
                getLogger().warning("Module '" + id + "' needs module '" + dep + "', which is disabled. Skipping.");
                return false;
            }
        }
        try {
            module.enable();
            return true;
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Could not enable module '" + id + "'", t);
            module.disable();
            return false;
        }
    }

    private void disableModule(String id) {
        // disable dependants first
        for (AquaticModule other : modules.values()) {
            if (other.isEnabled() && other.requires().contains(id)) {
                disableModule(other.id());
            }
        }
        AquaticModule module = modules.get(id);
        if (module != null && module.isEnabled()) {
            module.disable();
        }
    }

    private void printBanner() {
        String version = getPluginMeta().getVersion();
        getLogger().info("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
        getLogger().info("  AquaticSMP v" + version + "  -  sail the Grand Line");
        getLogger().info("  Developed by " + AUTHOR);
        getLogger().info("  " + REPO);
        getLogger().info("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
    }

    /** The credit card shown by /aquatic about (and /aquatic for players). */
    private void sendAbout(CommandSender sender) {
        sender.sendMessage(Text.mm("<dark_aqua><strikethrough>                </strikethrough> <gradient:#1fa2ff:#12d8fa><bold>AquaticSMP</bold></gradient> <dark_aqua><strikethrough>                </strikethrough>"));
        sender.sendMessage(Text.mm(" <gray>Version <white><version>", "version", getPluginMeta().getVersion()));
        sender.sendMessage(Text.mm(" <gray>Developed by <gold><bold><author></bold>", "author", AUTHOR));
        sender.sendMessage(Text.mm(" <gray>Pirate crews, islands, treasure, Devil Fruits and the Pirate King."));
        sender.sendMessage(Text.mm(" <aqua><click:open_url:'" + REPO + "'><hover:show_text:'<gray>Open the project page'>" + REPO + "</hover></click>"));
    }

    private void loadPrefix() {
        prefix = Text.mm(getConfig().getString("prefix", ""));
    }

    // ------------------------------------------------------------------ /aquatic

    private void registerAdminCommand() {
        AquaticCommand cmd = new AquaticCommand("aquatic", "AquaticSMP: about and administration", "aq", "aquaticsmp");
        // /aquatic itself is open to everyone (credits); the management sub-commands are admin-only.
        cmd.defaultExecutor((sender, args) -> {
            sendAbout(sender);
            if (sender.hasPermission("aquatic.admin")) {
                cmd.sendHelp(sender, "aquatic");
            }
        });

        cmd.sub("about", (sender, args) -> sendAbout(sender))
                .aliases("credits", "version").description("Who made AquaticSMP");

        cmd.sub("modules", (sender, args) -> {
            sender.sendMessage(prefix.append(Text.mm("<aqua>Modules:")));
            for (Map.Entry<String, AquaticModule> e : modules.entrySet()) {
                boolean on = e.getValue().isEnabled();
                sender.sendMessage(Text.mm(" <dark_gray>- " + (on ? "<green>" : "<red>") + "<id> <gray>(<state>)",
                        "id", e.getKey(), "state", on ? "enabled" : "disabled"));
            }
        }).permission("aquatic.admin").description("List all modules and their state");

        cmd.sub("enable", (sender, args) -> toggle(sender, args, true))
                .usage("<module>").permission("aquatic.admin").description("Enable a module")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(modules.keySet(), a[0]) : List.of());

        cmd.sub("disable", (sender, args) -> toggle(sender, args, false))
                .usage("<module>").permission("aquatic.admin").description("Disable a module")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(modules.keySet(), a[0]) : List.of());

        cmd.sub("reload", (sender, args) -> {
            reloadConfig();
            loadPrefix();
            List<String> targets = args.length > 0 ? List.of(args[0].toLowerCase()) : new ArrayList<>(modules.keySet());
            if (args.length > 0 && !modules.containsKey(targets.get(0))) {
                throw new CommandFail("<red>Unknown module: " + args[0]);
            }
            List<String> wasEnabled = new ArrayList<>(enabledModuleIds());
            List<String> rev = new ArrayList<>(wasEnabled);
            java.util.Collections.reverse(rev);
            for (String id : rev) {
                if (targets.contains(id) || args.length == 0) {
                    disableModule(id);
                }
            }
            for (String id : modules.keySet()) {
                boolean shouldRun = args.length == 0 ? getConfig().getBoolean("modules." + id, true) : wasEnabled.contains(id);
                if (shouldRun) {
                    enableModule(id);
                }
            }
            sender.sendMessage(prefix.append(Text.mm("<green>Reloaded. Active modules: <white><list>",
                    "list", String.join(", ", enabledModuleIds()))));
        }).usage("[module]").permission("aquatic.admin").description("Reload configs (all modules or one)")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(modules.keySet(), a[0]) : List.of());

        cmd.register(this);
    }

    private void toggle(CommandSender sender, String[] args, boolean on) {
        if (args.length < 1 || !modules.containsKey(args[0].toLowerCase())) {
            throw new CommandFail("<red>Unknown module. Use <white>/aquatic modules</white>.");
        }
        String id = args[0].toLowerCase();
        getConfig().set("modules." + id, on);
        saveConfig();
        if (on) {
            boolean ok = enableModule(id) || isModuleEnabled(id);
            if (ok) {
                // bring back modules that were switched off only because they depend on this one
                for (Map.Entry<String, AquaticModule> e : modules.entrySet()) {
                    if (!e.getValue().isEnabled() && e.getValue().requires().contains(id)
                            && getConfig().getBoolean("modules." + e.getKey(), true) && enableModule(e.getKey())) {
                        sender.sendMessage(prefix.append(Text.mm("<green>Module <white><id></white> re-enabled (depends on <dep>).",
                                "id", e.getKey(), "dep", id)));
                    }
                }
            }
            sender.sendMessage(prefix.append(Text.mm(ok ? "<green>Module <white><id></white> enabled."
                    : "<red>Module <white><id></white> could not be enabled (see console).", "id", id)));
        } else {
            disableModule(id);
            sender.sendMessage(prefix.append(Text.mm("<yellow>Module <white><id></white> disabled.", "id", id)));
        }
    }
}
