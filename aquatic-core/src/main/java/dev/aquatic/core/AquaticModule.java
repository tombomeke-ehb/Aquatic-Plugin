package dev.aquatic.core;

import dev.aquatic.command.AquaticCommand;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class of every AquaticSMP module. A module owns one config file
 * (plugins/AquaticSMP/modules/&lt;id&gt;.yml, defaults copied from the jar resource
 * modules/&lt;id&gt;.yml), its own data folder, and everything it registers through the
 * helper methods here is cleaned up automatically when the module is disabled or reloaded.
 */
public abstract class AquaticModule {

    private AquaticPlugin plugin;
    private YamlConfiguration config;
    private boolean enabled;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<AquaticCommand> commands = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<Class<?>> services = new ArrayList<>();

    /** Unique lowercase id, e.g. "crews". Also the config file name. */
    public abstract String id();

    /** Ids of modules that must be enabled for this one to work. */
    public List<String> requires() {
        return List.of();
    }

    protected abstract void onEnable();

    protected void onDisable() {
    }

    // ------------------------------------------------------------------ lifecycle (called by core)

    final void init(AquaticPlugin plugin) {
        this.plugin = plugin;
    }

    final void enable() {
        reloadConfig();
        onEnable();
        enabled = true;
    }

    final void disable() {
        try {
            onDisable();
        } catch (Throwable t) {
            logger().log(Level.SEVERE, "Error while disabling module " + id(), t);
        }
        listeners.forEach(HandlerList::unregisterAll);
        listeners.clear();
        commands.forEach(AquaticCommand::unregister);
        commands.clear();
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        services.forEach(c -> plugin.services().unregister(c));
        services.clear();
        enabled = false;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------ accessors

    public final AquaticPlugin plugin() {
        return plugin;
    }

    public final Logger logger() {
        return plugin.getLogger();
    }

    /** The module's configuration (modules/&lt;id&gt;.yml). */
    public final YamlConfiguration config() {
        return config;
    }

    /** Looks up another module's service; null when that module is disabled. Do not cache the result. */
    public final <T> T service(Class<T> type) {
        return plugin.services().get(type);
    }

    // ------------------------------------------------------------------ config & data

    public final File configFile() {
        return new File(plugin.getDataFolder(), "modules/" + id() + ".yml");
    }

    /** (Re)loads modules/&lt;id&gt;.yml, creating it from the bundled default and filling in missing keys. */
    public final void reloadConfig() {
        File file = configFile();
        String resource = "modules/" + id() + ".yml";
        if (!file.exists() && plugin.getResource(resource) != null) {
            plugin.saveResource(resource, false);
        }
        LegacyNames.migrateFile(file, logger());
        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource(resource)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                upgradeConfig(file, defaults);
                config.setDefaults(defaults);
                // A top-level section added in a newer version (e.g. "flag:" in crews.yml) does not exist in a
                // config file written by an older version. Bukkit's defaults do not cover whole missing
                // sections well (getConfigurationSection returns an empty one), so copy them in memory.
                // Keys the owner removed *inside* existing sections stay removed; the file is not rewritten.
                for (String key : defaults.getKeys(false)) {
                    if (!config.contains(key, true)) {
                        config.set(key, defaults.get(key));
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Bundled configs carry "config-version". When the file on disk is older, {@link #migrateConfig} raises
     * the values the owner never changed to the new defaults and adds new sections; the old file is kept as
     * &lt;file&gt;.v&lt;old&gt;.bak.
     */
    private void upgradeConfig(File file, YamlConfiguration defaults) {
        int want = defaults.getInt("config-version", 0);
        int have = config.getInt("config-version", 0);
        if (want <= have || !file.isFile()) {
            return;
        }
        File backup = new File(file.getParentFile(), file.getName() + ".v" + have + ".bak");
        try {
            java.nio.file.Files.copy(file.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            migrateConfig(config, defaults, have);
            config.set("config-version", want);
            config.save(file);
            logger().info("Updated " + file.getName() + " to config version " + want + " (backup: " + backup.getName() + ")");
        } catch (IOException | RuntimeException e) {
            logger().log(Level.WARNING, "Could not upgrade " + file.getName(), e);
        }
    }

    /** Override to upgrade an older config file (see {@link #raise} and {@link #addMissing}). */
    protected void migrateConfig(YamlConfiguration file, YamlConfiguration defaults, int fromVersion) {
    }

    /** Sets path to newValue, but only when the file still has the old default (the owner did not change it). */
    protected static void raise(YamlConfiguration file, String path, Object oldValue, Object newValue) {
        if (file.contains(path, true) && String.valueOf(file.get(path)).equals(String.valueOf(oldValue))) {
            file.set(path, newValue);
        }
    }

    /** Copies a section/value from the bundled defaults when the file does not have it. */
    protected static void addMissing(YamlConfiguration file, YamlConfiguration defaults, String path) {
        if (!file.contains(path, true) && defaults.contains(path, true)) {
            file.set(path, defaults.get(path));
        }
    }

    public final void saveConfig() {
        try {
            config.save(configFile());
        } catch (IOException e) {
            logger().log(Level.SEVERE, "Could not save config of module " + id(), e);
        }
    }

    /** Folder plugins/AquaticSMP/data/&lt;id&gt;/ (created on demand). */
    public final File dataFolder() {
        File dir = new File(plugin.getDataFolder(), "data/" + id());
        dir.mkdirs();
        return dir;
    }

    /** Loads data/&lt;id&gt;/&lt;name&gt;.yml (empty configuration when the file does not exist). */
    public final YamlConfiguration loadData(String name) {
        return YamlConfiguration.loadConfiguration(new File(dataFolder(), name + ".yml"));
    }

    public final void saveData(String name, YamlConfiguration data) {
        // write to a temp file first and then swap it in, so a crash mid-save never leaves a half-written file
        File target = new File(dataFolder(), name + ".yml");
        File temp = new File(dataFolder(), name + ".yml.tmp");
        try {
            data.save(temp);
            try {
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger().log(Level.SEVERE, "Could not save " + name + ".yml of module " + id(), e);
        }
    }

    // ------------------------------------------------------------------ registration helpers

    protected final void registerListener(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    protected final void registerCommand(AquaticCommand command) {
        command.register(plugin);
        commands.add(command);
    }

    protected final <T> void registerService(Class<T> type, T implementation) {
        plugin.services().register(type, implementation);
        services.add(type);
    }

    public final BukkitTask runTimer(Runnable task, long delayTicks, long periodTicks) {
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        pruneTasks();
        tasks.add(t);
        return t;
    }

    public final BukkitTask runLater(Runnable task, long delayTicks) {
        BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        pruneTasks();
        tasks.add(t);
        return t;
    }

    /** Drops finished or cancelled tasks so the list does not grow forever. */
    private void pruneTasks() {
        var scheduler = Bukkit.getScheduler();
        tasks.removeIf(x -> x.isCancelled()
                || (!scheduler.isQueued(x.getTaskId()) && !scheduler.isCurrentlyRunning(x.getTaskId())));
    }

    public final BukkitTask runAsync(Runnable task) {
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    // ------------------------------------------------------------------ messages

    /**
     * Builds a message from the "messages.&lt;key&gt;" entry of this module's config (MiniMessage format),
     * with the global prefix. Placeholders are written as &lt;name&gt; in the config and passed here as
     * name/value pairs: msg("crew-created", "crew", crew.name()). Values are inserted as plain text.
     */
    public final Component msg(String key, Object... placeholders) {
        return plugin.prefix().append(rawMsg(key, placeholders));
    }

    /** Same as {@link #msg} but without the prefix. */
    public final Component rawMsg(String key, Object... placeholders) {
        String raw = config.getString("messages." + key);
        if (raw == null) {
            raw = "<red>Missing message: " + id() + "/messages." + key;
        }
        return Text.mm(raw, placeholders);
    }

    public final void send(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(msg(key, placeholders));
    }
}
