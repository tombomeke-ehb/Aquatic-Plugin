package dev.aquatic.crews;

import dev.aquatic.api.EconomyService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Doubloons: balances, /pay, /baltop, kill rewards and the optional Vault bridge. */
public final class EconomyModule extends AquaticModule implements EconomyService, Listener {

    private static final String PERM_OTHERS = "aquatic.economy.balance.others";
    private static final String PERM_PAY = "aquatic.economy.pay";
    private static final String PERM_ADMIN = "aquatic.economy.admin";

    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    /** lower-case name -> uuid, maintained on join so admins can target offline players. */
    private final Map<String, UUID> nameToId = new ConcurrentHashMap<>();
    private final Map<UUID, String> idToName = new ConcurrentHashMap<>();
    private final Map<String, Double> mobRewards = new HashMap<>();
    private volatile boolean dirty;
    private Object vaultHook;

    // cached config (also read from other threads by Vault / PlaceholderAPI consumers)
    private volatile double startingBalance;
    private volatile double maxBalance;
    private volatile int decimals;
    private volatile String singular = "Doubloon";
    private volatile String plural = "Doubloons";
    private volatile String symbol = "";
    private volatile String format = "<amount> <name>";

    @Override
    public String id() {
        return "economy";
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onEnable() {
        loadSettings();
        loadBalances();
        for (Player p : Bukkit.getOnlinePlayers()) {
            remember(p);
        }
        registerService(EconomyService.class, this);
        registerListener(this);
        registerCommands();
        long period = Math.max(10, config().getLong("autosave-seconds", 120)) * 20L;
        runTimer(this::saveIfDirty, period, period);

        if (config().getBoolean("vault.enabled", true) && Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            try {
                vaultHook = VaultHook.register(this);
                logger().info("[economy] Registered Doubloons as the Vault economy.");
            } catch (Throwable t) {
                logger().log(Level.WARNING, "[economy] Could not hook into Vault", t);
            }
        }
    }

    @Override
    protected void onDisable() {
        if (vaultHook != null) {
            try {
                VaultHook.unregister(vaultHook);
            } catch (Throwable t) {
                logger().log(Level.WARNING, "[economy] Could not unhook from Vault", t);
            }
            vaultHook = null;
        }
        dirty = true;
        saveIfDirty();
        balances.clear();
        nameToId.clear();
        idToName.clear();
    }

    private void loadSettings() {
        maxBalance = sane(config().getDouble("max-balance", 1_000_000_000_000d), 1_000_000_000_000d);
        if (maxBalance <= 0) {
            maxBalance = 1_000_000_000_000d;
        }
        decimals = Math.max(0, Math.min(4, config().getInt("currency.decimals", 0)));
        singular = config().getString("currency.singular", "Doubloon");
        plural = config().getString("currency.plural", "Doubloons");
        symbol = config().getString("currency.symbol", "");
        format = config().getString("currency.format", "<amount> <name>");
        startingBalance = round(Math.min(sane(config().getDouble("starting-balance", 100), 0), maxBalance));

        mobRewards.clear();
        ConfigurationSection mobs = config().getConfigurationSection("mob-rewards.mobs");
        if (mobs != null) {
            for (String key : mobs.getKeys(false)) {
                mobRewards.put(key.toUpperCase(Locale.ROOT), sane(mobs.getDouble(key), 0));
            }
        }
    }

    private static double sane(double value, double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value) || value < 0 ? fallback : value;
    }

    private void loadBalances() {
        balances.clear();
        nameToId.clear();
        idToName.clear();
        YamlConfiguration data = loadData("balances");
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                double balance = sane(players.getDouble(key + ".balance", startingBalance), startingBalance);
                balances.put(id, round(Math.min(balance, maxBalance)));
                String name = players.getString(key + ".name");
                if (name != null && !name.isEmpty()) {
                    idToName.put(id, name);
                    nameToId.put(name.toLowerCase(Locale.ROOT), id);
                }
            } catch (IllegalArgumentException e) {
                logger().warning("[economy] Skipping invalid entry in balances.yml: " + key);
            }
        }
    }

    private void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : balances.entrySet()) {
            String path = "players." + e.getKey();
            data.set(path + ".balance", e.getValue());
            String name = idToName.get(e.getKey());
            if (name != null) {
                data.set(path + ".name", name);
            }
        }
        saveData("balances", data);
    }

    // ------------------------------------------------------------------ accounts

    private void remember(Player p) {
        UUID id = p.getUniqueId();
        String old = idToName.put(id, p.getName());
        if (old != null && !old.equalsIgnoreCase(p.getName())) {
            nameToId.remove(old.toLowerCase(Locale.ROOT), id);
        }
        nameToId.put(p.getName().toLowerCase(Locale.ROOT), id);
        if (balances.putIfAbsent(id, startingBalance) == null || old == null || !old.equals(p.getName())) {
            dirty = true;
        }
    }

    /** Remembers the name of an offline player that another plugin (Vault) handed to us. */
    void rememberName(UUID id, String name) {
        if (name != null && !name.isEmpty() && idToName.putIfAbsent(id, name) == null) {
            nameToId.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
            dirty = true;
        }
    }

    boolean hasAccount(UUID id) {
        return balances.containsKey(id);
    }

    void createAccount(UUID id) {
        if (balances.putIfAbsent(id, startingBalance) == null) {
            dirty = true;
        }
    }

    /** Name -> UUID for anyone who joined while this module was active (plus the user cache of the server). */
    UUID idOf(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        UUID known = nameToId.get(name.toLowerCase(Locale.ROOT));
        if (known != null) {
            return known;
        }
        OfflinePlayer cached = Players.resolve(name);
        return cached != null ? cached.getUniqueId() : null;
    }

    String nameOf(UUID id) {
        return Players.name(id, idToName.get(id));
    }

    double round(double value) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    int decimals() {
        return decimals;
    }

    String singular() {
        return singular;
    }

    double maxBalance() {
        return maxBalance;
    }

    /** Sets a balance directly (clamped to 0..max-balance). */
    void setBalance(UUID player, double amount) {
        if (Double.isNaN(amount)) {
            return;
        }
        balances.put(player, round(Math.max(0, Math.min(maxBalance, amount))));
        dirty = true;
    }

    private static boolean invalid(double amount) {
        return Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0;
    }

    // ------------------------------------------------------------------ EconomyService

    @Override
    public double balance(UUID player) {
        Double value = balances.get(player);
        return value != null ? value : startingBalance;
    }

    @Override
    public boolean has(UUID player, double amount) {
        return !invalid(amount) && balance(player) + 1e-9 >= round(amount);
    }

    @Override
    public void deposit(UUID player, double amount) {
        if (invalid(amount) || amount == 0) {
            return;
        }
        double add = round(amount);
        balances.compute(player, (id, current) ->
                round(Math.min(maxBalance, (current != null ? current : startingBalance) + add)));
        dirty = true;
    }

    @Override
    public boolean withdraw(UUID player, double amount) {
        if (invalid(amount)) {
            return false;
        }
        // compute() keeps check-and-take atomic even when Vault consumers call in from other threads
        double take = round(amount);
        boolean[] ok = {false};
        balances.compute(player, (id, current) -> {
            double now = current != null ? current : startingBalance;
            if (now + 1e-9 < take) {
                return current;
            }
            ok[0] = true;
            return round(Math.max(0, now - take));
        });
        if (ok[0]) {
            dirty = true;
        }
        return ok[0];
    }

    @Override
    public String format(double amount) {
        double rounded = round(amount);
        String number = decimals == 0 || rounded == Math.rint(rounded)
                ? String.format(Locale.US, "%,d", (long) rounded)
                : String.format(Locale.US, "%,." + decimals + "f", rounded);
        return format.replace("<symbol>", symbol)
                .replace("<amount>", number)
                .replace("<name>", Math.abs(rounded - 1) < 1e-9 ? singular : plural)
                .trim();
    }

    @Override
    public String currencyName() {
        return plural;
    }

    // ------------------------------------------------------------------ commands

    private CommandFail fail(String key, Object... placeholders) {
        return new CommandFail(rawMsg(key, placeholders));
    }

    private double parse(String raw) {
        double amount = round(Players.amount(raw));
        if (amount <= 0) {
            throw fail("invalid-amount", "amount", raw);
        }
        return amount;
    }

    private UUID target(String name) {
        UUID id = idOf(name);
        if (id == null) {
            throw fail("unknown-player", "player", name);
        }
        return id;
    }

    private List<String> knownNames(String prefix) {
        List<String> out = AquaticCommand.playerNames(prefix);
        if (out.isEmpty() && prefix.length() >= 2) {
            String p = prefix.toLowerCase(Locale.ROOT);
            for (String name : idToName.values()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(p)) {
                    out.add(name);
                    if (out.size() >= 20) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private void registerCommands() {
        Players.ensurePermission(PERM_OTHERS, PermissionDefault.TRUE);
        Players.ensurePermission(PERM_PAY, PermissionDefault.TRUE);

        // /doubloons [player]  +  admin give|take|set
        AquaticCommand bal = new AquaticCommand("doubloons", "Show your Doubloons", "balance", "bal", "money");
        bal.defaultExecutor((sender, args) -> {
            if (args.length == 0) {
                Player p = AquaticCommand.player(sender);
                send(p, "balance-self", "balance", format(balance(p.getUniqueId())));
                return;
            }
            if (!sender.hasPermission(PERM_OTHERS) && !sender.hasPermission("aquatic.admin")) {
                throw fail("no-permission");
            }
            UUID id = target(args[0]);
            send(sender, "balance-other", "player", nameOf(id), "balance", format(balance(id)));
        });
        bal.defaultCompleter((s, a) -> a.length <= 1 ? AquaticCommand.playerNames(a.length == 0 ? "" : a[0]) : List.of());

        bal.sub("give", (sender, args) -> {
            AquaticCommand.need(args, 2, "/doubloons give <player> <amount>");
            UUID id = target(args[0]);
            double amount = parse(args[1]);
            deposit(id, amount);
            send(sender, "admin-give", "player", nameOf(id), "amount", format(amount), "balance", format(balance(id)));
            notifyTarget(sender, id, "admin-give-notify", amount);
        }).usage("<player> <amount>").description("Give Doubloons to a player (works offline)")
                .permission(PERM_ADMIN).completer(this::adminComplete);

        bal.sub("take", (sender, args) -> {
            AquaticCommand.need(args, 2, "/doubloons take <player> <amount>");
            UUID id = target(args[0]);
            double amount = Math.min(parse(args[1]), balance(id));
            setBalance(id, balance(id) - amount);
            send(sender, "admin-take", "player", nameOf(id), "amount", format(amount), "balance", format(balance(id)));
            notifyTarget(sender, id, "admin-take-notify", amount);
        }).usage("<player> <amount>").description("Take Doubloons from a player (works offline)")
                .permission(PERM_ADMIN).completer(this::adminComplete);

        bal.sub("set", (sender, args) -> {
            AquaticCommand.need(args, 2, "/doubloons set <player> <amount>");
            UUID id = target(args[0]);
            double amount = AquaticCommand.number(args[1].replace(",", ""));
            if (amount < 0) {
                throw fail("invalid-amount", "amount", args[1]);
            }
            setBalance(id, amount);
            send(sender, "admin-set", "player", nameOf(id), "balance", format(balance(id)));
            notifyTarget(sender, id, "admin-set-notify", balance(id));
        }).usage("<player> <amount>").description("Set the Doubloons of a player (works offline)")
                .permission(PERM_ADMIN).completer(this::adminComplete);
        registerCommand(bal);

        // /pay <player> <amount>
        AquaticCommand pay = new AquaticCommand("pay", "Pay Doubloons to another pirate");
        pay.defaultExecutor((sender, args) -> pay(AquaticCommand.player(sender), args));
        pay.defaultCompleter((s, a) -> a.length <= 1 ? AquaticCommand.playerNames(a.length == 0 ? "" : a[0])
                : a.length == 2 ? AquaticCommand.filter(List.of("10", "100", "1000"), a[1]) : List.of());
        registerCommand(pay);

        // /baltop [page]
        AquaticCommand top = new AquaticCommand("baltop", "The richest pirates", "doubloonstop", "richest");
        top.defaultExecutor(this::baltop);
        top.defaultCompleter((s, a) -> a.length <= 1 ? List.of("1", "2", "3") : List.of());
        registerCommand(top);
    }

    private List<String> adminComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return knownNames(args[0]);
        }
        return args.length == 2 ? AquaticCommand.filter(List.of("100", "1000", "10000"), args[1]) : List.of();
    }

    private void notifyTarget(CommandSender by, UUID id, String key, double amount) {
        Player online = Bukkit.getPlayer(id);
        if (online != null && online != by && config().getBoolean("notify-admin-changes", true)) {
            send(online, key, "amount", format(amount), "balance", format(balance(id)));
        }
    }

    private void pay(Player from, String[] args) {
        if (!config().getBoolean("pay.enabled", true)) {
            throw fail("pay-disabled");
        }
        if (!from.hasPermission(PERM_PAY) && !from.hasPermission("aquatic.admin")) {
            throw fail("no-permission");
        }
        AquaticCommand.need(args, 2, "/pay <player> <amount>");
        UUID to;
        Player online = Bukkit.getPlayerExact(args[0]);
        if (online != null) {
            to = online.getUniqueId();
        } else if (config().getBoolean("pay.allow-offline", true)) {
            to = target(args[0]);
        } else {
            throw fail("player-offline", "player", args[0]);
        }
        if (to.equals(from.getUniqueId())) {
            throw fail("pay-self");
        }
        double amount = parse(args[1]);
        double min = sane(config().getDouble("pay.min-amount", 1), 0);
        if (amount < min) {
            throw fail("pay-min", "min", format(min));
        }
        double taxPercent = Math.min(100, sane(config().getDouble("pay.tax-percent", 0), 0));
        double tax = round(amount * taxPercent / 100d);
        double received = round(amount - tax);
        if (received <= 0) {
            throw fail("invalid-amount", "amount", args[1]);
        }
        if (balance(to) + received > maxBalance) {
            throw fail("balance-max", "player", nameOf(to));
        }
        if (!withdraw(from.getUniqueId(), amount)) {
            throw fail("not-enough", "balance", format(balance(from.getUniqueId())), "amount", format(amount));
        }
        deposit(to, received);
        send(from, "pay-sent", "player", nameOf(to), "amount", format(amount), "received", format(received), "tax", format(tax));
        if (online != null) {
            send(online, "pay-received", "player", from.getName(), "amount", format(received));
        }
    }

    private void baltop(CommandSender sender, String[] args) {
        int perPage = Math.max(1, config().getInt("baltop.page-size", 10));
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(balances.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        if (sorted.isEmpty()) {
            throw fail("baltop-empty");
        }
        int pages = (sorted.size() + perPage - 1) / perPage;
        int page = args.length > 0 ? Math.max(1, Math.min(pages, AquaticCommand.integer(args[0]))) : 1;
        sender.sendMessage(rawMsg("baltop-header", "page", page, "pages", pages));
        for (int i = (page - 1) * perPage; i < Math.min(sorted.size(), page * perPage); i++) {
            Map.Entry<UUID, Double> e = sorted.get(i);
            sender.sendMessage(rawMsg("baltop-line", "rank", i + 1, "player", nameOf(e.getKey()), "balance", format(e.getValue())));
        }
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        remember(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        if (mob instanceof Player || !config().getBoolean("mob-rewards.enabled", true)) {
            return;
        }
        Player killer = mob.getKiller();
        if (killer == null) {
            return;
        }
        if (!config().getBoolean("mob-rewards.spawner-mobs", false)
                && mob.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        Double configured = mobRewards.get(mob.getType().name());
        double reward = round(configured != null ? configured : sane(config().getDouble("mob-rewards.default", 0), 0));
        if (reward <= 0) {
            return;
        }
        deposit(killer.getUniqueId(), reward);
        if (config().getBoolean("mob-rewards.action-bar", true)) {
            killer.sendActionBar(rawMsg("mob-reward", "amount", format(reward), "mob", pretty(mob.getType().name())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        double percent = Math.min(100, sane(config().getDouble("pvp-death.lose-percent", 0), 0));
        if (percent <= 0) {
            return;
        }
        double lost = balance(victim.getUniqueId()) * percent / 100d;
        double cap = config().getDouble("pvp-death.max-loss", -1);
        if (cap >= 0) {
            lost = Math.min(lost, cap);
        }
        lost = round(lost);
        if (lost <= 0 || !withdraw(victim.getUniqueId(), lost)) {
            return;
        }
        double share = Math.min(100, sane(config().getDouble("pvp-death.killer-share-percent", 100), 100));
        double gained = round(lost * share / 100d);
        send(victim, "pvp-lost", "amount", format(lost), "killer", killer.getName());
        if (gained > 0) {
            deposit(killer.getUniqueId(), gained);
            send(killer, "pvp-gained", "amount", format(gained), "victim", victim.getName());
        }
    }

    private static String pretty(String enumName) {
        StringBuilder sb = new StringBuilder();
        for (String part : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
