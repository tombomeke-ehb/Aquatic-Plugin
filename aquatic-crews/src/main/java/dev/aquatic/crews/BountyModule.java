package dev.aquatic.crews;

import dev.aquatic.api.BountyService;
import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewRelation;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.event.BountyClaimEvent;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounties, notoriety, tiers and wanted posters. */
public final class BountyModule extends AquaticModule implements BountyService, Listener {

    private static final String ADMIN = "aquatic.bounty.admin";

    /** One pirate's record. */
    private static final class Entry {
        volatile double bounty;
        String name;
        int streak;
        int tier = -1;
    }

    private record Tier(String title, double min) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final List<Tier> tiers = new ArrayList<>();
    private final Map<String, Long> recentKills = new HashMap<>();
    private final Map<UUID, Long> placeCooldowns = new HashMap<>();
    private final Map<UUID, Long> posterCooldowns = new HashMap<>();
    private volatile boolean dirty;

    @Override
    public String id() {
        return "bounty";
    }

    @Override
    public List<String> requires() {
        return List.of("economy");
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onEnable() {
        entries.clear();
        recentKills.clear();
        placeCooldowns.clear();
        posterCooldowns.clear();
        loadTiers();
        load();
        for (Player p : Bukkit.getOnlinePlayers()) {
            remember(p);
        }
        registerService(BountyService.class, this);
        registerListener(this);
        registerCommands();
        long period = Math.max(10, config().getLong("autosave-seconds", 120)) * 20L;
        runTimer(this::saveIfDirty, period, period);
        runTimer(this::purge, 20L * 60, 20L * 60);
    }

    @Override
    protected void onDisable() {
        dirty = true;
        saveIfDirty();
        entries.clear();
    }

    private void loadTiers() {
        tiers.clear();
        for (Map<?, ?> map : config().getMapList("tiers")) {
            Object title = map.get("title");
            Object min = map.get("min");
            if (title == null || !(min instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                logger().warning("[bounty] Ignoring invalid tier entry: " + map);
                continue;
            }
            tiers.add(new Tier(String.valueOf(title), Math.max(0, n.doubleValue())));
        }
        tiers.sort((a, b) -> Double.compare(a.min(), b.min()));
    }

    private void load() {
        ConfigurationSection root = loadData("bounties").getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Entry e = new Entry();
                double b = root.getDouble(key + ".bounty", 0);
                e.bounty = Double.isFinite(b) && b > 0 ? Math.min(b, maxBounty()) : 0;
                e.name = root.getString(key + ".name");
                e.streak = Math.max(0, root.getInt(key + ".streak", 0));
                e.tier = tierIndex(e.bounty);
                entries.put(id, e);
            } catch (IllegalArgumentException ex) {
                logger().warning("[bounty] Skipping invalid entry in bounties.yml: " + key);
            }
        }
    }

    private void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            if (entry.bounty <= 0 && entry.streak <= 0) {
                continue;
            }
            String path = "players." + e.getKey() + ".";
            data.set(path + "bounty", entry.bounty);
            data.set(path + "streak", entry.streak);
            if (entry.name != null) {
                data.set(path + "name", entry.name);
            }
        }
        saveData("bounties", data);
    }

    private void purge() {
        long now = System.currentTimeMillis();
        long window = Math.max(0, config().getLong("notoriety.repeat-kill-cooldown-seconds", 300)) * 1000L;
        recentKills.values().removeIf(t -> now - t > window);
        placeCooldowns.values().removeIf(t -> t < now);
        posterCooldowns.values().removeIf(t -> t < now);
    }

    // ------------------------------------------------------------------ helpers

    private double maxBounty() {
        double max = config().getDouble("max-bounty", 1_000_000_000_000d);
        return Double.isFinite(max) && max > 0 ? max : 1_000_000_000_000d;
    }

    private double num(String path, double def) {
        double v = config().getDouble(path, def);
        return Double.isFinite(v) && v >= 0 ? v : def;
    }

    private Entry entry(UUID id) {
        return entries.computeIfAbsent(id, k -> new Entry());
    }

    private void remember(Player p) {
        Entry e = entries.get(p.getUniqueId());
        if (e != null && !p.getName().equals(e.name)) {
            e.name = p.getName();
            dirty = true;
        }
    }

    private String nameOf(UUID id) {
        Entry e = entries.get(id);
        return Players.name(id, e == null ? null : e.name);
    }

    private String money(double amount) {
        EconomyService eco = service(EconomyService.class);
        return eco != null ? eco.format(amount) : Text.number(amount);
    }

    private int tierIndex(double bounty) {
        int index = -1;
        for (int i = 0; i < tiers.size(); i++) {
            if (bounty > 0 && bounty >= tiers.get(i).min()) {
                index = i;
            }
        }
        return index;
    }

    private Component tierTitle(double bounty) {
        int i = tierIndex(bounty);
        return i < 0 ? rawMsg("no-tier") : Text.mm(tiers.get(i).title());
    }

    private CommandFail fail(String key, Object... placeholders) {
        return new CommandFail(rawMsg(key, placeholders));
    }

    private UUID target(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (name.equalsIgnoreCase(e.getValue().name)) {
                return e.getKey();
            }
        }
        OfflinePlayer cached = Players.resolve(name);
        if (cached == null) {
            throw fail("unknown-player", "player", name);
        }
        return cached.getUniqueId();
    }

    private void setName(UUID id, String fallback) {
        Entry e = entry(id);
        Player online = Bukkit.getPlayer(id);
        String name = online != null ? online.getName() : fallback;
        if (name != null && !name.equals(e.name)) {
            e.name = name;
        }
    }

    /** Sets a bounty (clamped) and handles tier changes. */
    private void setBounty(UUID id, double amount) {
        Entry e = entry(id);
        double old = e.bounty;
        e.bounty = Math.max(0, Math.min(maxBounty(), amount));
        dirty = true;
        int newTier = tierIndex(e.bounty);
        int oldTier = e.tier;
        e.tier = newTier;
        if (newTier > oldTier && newTier >= 0 && e.bounty > old && config().getBoolean("announce-tier-up", true)) {
            Bukkit.broadcast(msg("tier-reached", "player", nameOf(id), "tier", Text.mm(tiers.get(newTier).title()),
                    "bounty", money(e.bounty)));
        }
    }

    // ------------------------------------------------------------------ BountyService

    @Override
    public double bountyOf(UUID player) {
        Entry e = player == null ? null : entries.get(player);
        return e == null ? 0 : e.bounty;
    }

    @Override
    public void addBounty(UUID target, double amount, String reason) {
        if (target == null || !Double.isFinite(amount) || amount <= 0) {
            return;
        }
        setName(target, null);
        setBounty(target, bountyOf(target) + amount);
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            send(online, "bounty-raised", "amount", money(amount), "bounty", money(bountyOf(target)),
                    "reason", reason == null ? "" : reason);
        }
    }

    @Override
    public void reduceBounty(UUID target, double amount) {
        if (target == null || !Double.isFinite(amount) || amount <= 0 || bountyOf(target) <= 0) {
            return;
        }
        setBounty(target, bountyOf(target) - amount);
    }

    @Override
    public void clearBounty(UUID target) {
        Entry e = target == null ? null : entries.get(target);
        if (e != null && e.bounty > 0) {
            e.bounty = 0;
            e.tier = -1;
            dirty = true;
        }
    }

    @Override
    public Map<UUID, Double> top(int limit) {
        List<Map.Entry<UUID, Entry>> list = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().bounty > 0) {
                list.add(e);
            }
        }
        list.sort((a, b) -> Double.compare(b.getValue().bounty, a.getValue().bounty));
        Map<UUID, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(Math.max(0, limit), list.size()); i++) {
            out.put(list.get(i).getKey(), list.get(i).getValue().bounty);
        }
        return out;
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        AquaticCommand cmd = new AquaticCommand("bounty", "Bounties on pirate heads", "bounties");
        cmd.sub("set", (s, a) -> place(AquaticCommand.player(s), a))
                .aliases("place", "add").usage("<player> <amount>").description("Put a price on a pirate's head")
                .playerOnly().playerPermission("aquatic.bounty.set")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.playerNames(a[0])
                        : a.length == 2 ? AquaticCommand.filter(List.of("100", "1000", "10000"), a[1]) : List.of());
        cmd.sub("info", this::info)
                .usage("[player]").description("Show a pirate's bounty and title")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.playerNames(a[0]) : List.of());
        cmd.sub("top", (s, a) -> list(s, a, true))
                .usage("[page]").description("Most wanted pirates");
        cmd.sub("list", (s, a) -> list(s, a, false))
                .usage("[page]").description("All bounties, online pirates first");
        cmd.sub("admin", this::admin)
                .usage("<add|remove|clear> <player> [amount]").description("Manage bounties")
                .permission(ADMIN)
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(List.of("add", "remove", "clear"), a[0])
                        : a.length == 2 ? AquaticCommand.playerNames(a[1])
                        : a.length == 3 ? AquaticCommand.filter(List.of("100", "1000", "10000"), a[2]) : List.of());
        registerCommand(cmd);

        Players.ensurePermission("aquatic.bounty.wanted", org.bukkit.permissions.PermissionDefault.TRUE);
        AquaticCommand wanted = new AquaticCommand("wanted", "Get a wanted poster");
        wanted.defaultExecutor(this::wanted);
        wanted.defaultCompleter((s, a) -> a.length <= 1 ? AquaticCommand.playerNames(a.length == 0 ? "" : a[0]) : List.of());
        registerCommand(wanted);
    }

    private void place(Player p, String[] args) {
        AquaticCommand.need(args, 2, "/bounty set <player> <amount>");
        EconomyService eco = service(EconomyService.class);
        if (eco == null) {
            throw fail("economy-disabled");
        }
        UUID target = target(args[0]);
        if (target.equals(p.getUniqueId()) && !config().getBoolean("placing.allow-self", false)) {
            throw fail("place-self");
        }
        if (!config().getBoolean("placing.allow-crewmates", false)) {
            CrewService crews = service(CrewService.class);
            if (crews != null) {
                CrewRelation rel = crews.relation(p.getUniqueId(), target);
                if (rel == CrewRelation.SAME && !target.equals(p.getUniqueId())) {
                    throw fail("place-crewmate");
                }
            }
        }
        double amount = Players.amount(args[1]);
        double min = num("placing.min-amount", 100);
        if (amount < min) {
            throw fail("place-min", "min", money(min));
        }
        if (bountyOf(target) + amount > maxBounty()) {
            throw fail("place-max", "max", money(maxBounty()));
        }
        Long until = placeCooldowns.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (until != null && until > now && !p.hasPermission(ADMIN)) {
            throw fail("place-cooldown", "time", Text.duration((until - now + 999) / 1000));
        }
        double tax = Math.ceil(amount * Math.min(1000, num("placing.tax-percent", 10)) / 100d);
        double total = amount + tax;
        if (!eco.withdraw(p.getUniqueId(), total)) {
            throw fail("cannot-afford", "cost", eco.format(total), "balance", eco.format(eco.balance(p.getUniqueId())));
        }
        long cd = Math.max(0, config().getLong("placing.cooldown-seconds", 30)) * 1000L;
        if (cd > 0) {
            placeCooldowns.put(p.getUniqueId(), now + cd);
        }
        setName(target, args[0]);
        setBounty(target, bountyOf(target) + amount);
        send(p, "placed", "player", nameOf(target), "amount", eco.format(amount), "tax", eco.format(tax),
                "bounty", eco.format(bountyOf(target)));
        if (config().getBoolean("announce-placed", true)) {
            Component message = msg("placed-broadcast", "player", nameOf(target), "amount", eco.format(amount),
                    "bounty", eco.format(bountyOf(target)), "placer",
                    config().getBoolean("placing.anonymous", true) ? rawMsg("anonymous") : Component.text(p.getName()));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online != p) {
                    online.sendMessage(message);
                }
            }
        }
        Player online = Bukkit.getPlayer(target);
        if (online != null && online != p) {
            send(online, "placed-on-you", "amount", eco.format(amount), "bounty", eco.format(bountyOf(target)));
        }
    }

    private void info(CommandSender sender, String[] args) {
        UUID id;
        if (args.length == 0) {
            id = AquaticCommand.player(sender).getUniqueId();
        } else {
            id = target(args[0]);
        }
        Entry e = entries.get(id);
        double bounty = e == null ? 0 : e.bounty;
        int i = tierIndex(bounty);
        Component next = i + 1 < tiers.size()
                ? rawMsg("info-next", "tier", Text.mm(tiers.get(i + 1).title()), "needed", money(tiers.get(i + 1).min() - bounty))
                : Component.empty();
        sender.sendMessage(rawMsg("info-header", "player", nameOf(id)));
        sender.sendMessage(rawMsg("info-bounty", "bounty", money(bounty)));
        sender.sendMessage(rawMsg("info-tier", "tier", tierTitle(bounty), "next", next));
        sender.sendMessage(rawMsg("info-streak", "streak", e == null ? 0 : e.streak));
        CrewService crews = service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewOf(id);
        if (crew != null) {
            sender.sendMessage(rawMsg("info-crew", "crew", crew.displayName()));
        }
    }

    private void list(CommandSender sender, String[] args, boolean topOnly) {
        List<UUID> ids = new ArrayList<>(top(Integer.MAX_VALUE).keySet());
        if (!topOnly) {
            // online pirates first, each group sorted by bounty
            List<UUID> online = new ArrayList<>();
            List<UUID> offline = new ArrayList<>();
            for (UUID id : ids) {
                (Bukkit.getPlayer(id) != null ? online : offline).add(id);
            }
            ids = online;
            ids.addAll(offline);
        }
        if (ids.isEmpty()) {
            throw fail("no-bounties");
        }
        int size = Math.max(1, config().getInt("page-size", 10));
        int pages = (ids.size() + size - 1) / size;
        int page = args.length > 0 ? Math.max(1, Math.min(pages, AquaticCommand.integer(args[0]))) : 1;
        sender.sendMessage(rawMsg(topOnly ? "top-header" : "list-header", "page", page, "pages", pages));
        for (int i = (page - 1) * size; i < Math.min(ids.size(), page * size); i++) {
            UUID id = ids.get(i);
            double bounty = bountyOf(id);
            sender.sendMessage(rawMsg("list-line", "rank", i + 1, "player", nameOf(id), "bounty", money(bounty),
                    "tier", tierTitle(bounty), "status", rawMsg(Bukkit.getPlayer(id) != null ? "status-online" : "status-offline")));
        }
    }

    private void admin(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 2, "/bounty admin <add|remove|clear> <player> [amount]");
        UUID id = target(args[1]);
        setName(id, args[1]);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                AquaticCommand.need(args, 3, "/bounty admin add <player> <amount>");
                addBounty(id, Players.amount(args[2]), config().getString("reasons.admin", "Admin"));
            }
            case "remove" -> {
                AquaticCommand.need(args, 3, "/bounty admin remove <player> <amount>");
                setBounty(id, bountyOf(id) - Players.amount(args[2]));
                if (bountyOf(id) <= 0) {
                    clearBounty(id);
                }
            }
            case "clear" -> clearBounty(id);
            default -> throw new CommandFail("<red>Usage: <white>/bounty admin <add|remove|clear> <player> [amount]");
        }
        send(sender, "admin-done", "player", nameOf(id), "bounty", money(bountyOf(id)));
    }

    // ------------------------------------------------------------------ wanted poster

    private ItemStack poster(UUID id) {
        double bounty = bountyOf(id);
        Material material = Material.matchMaterial(config().getString("poster.material", "PAPER"));
        ItemStack item = new ItemStack(material == null || !material.isItem() ? Material.PAPER : material);
        CrewService crews = service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewOf(id);
        Object[] placeholders = {"player", nameOf(id), "bounty", money(bounty), "tier", tierTitle(bounty),
                "crew", crew == null ? rawMsg("no-crew") : crew.displayName()};
        item.editMeta(meta -> {
            meta.displayName(Text.item(config().getString("poster.name", "<gold><bold>WANTED"), placeholders));
            meta.lore(Text.itemLore(config().getStringList("poster.lore"), placeholders));
            if (config().getBoolean("poster.glint", true)) {
                meta.setEnchantmentGlintOverride(true);
            }
            int model = config().getInt("poster.custom-model-data", 0);
            if (model > 0) {
                applyModel(meta, model);
            }
        });
        return item;
    }

    @SuppressWarnings("deprecation")
    private static void applyModel(ItemMeta meta, int model) {
        meta.setCustomModelData(model);
    }

    private void wanted(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aquatic.bounty.wanted") && !sender.hasPermission("aquatic.admin")) {
            throw fail("no-permission");
        }
        UUID id = args.length > 0 ? target(args[0]) : AquaticCommand.player(sender).getUniqueId();
        double bounty = bountyOf(id);
        if (bounty <= 0 && !config().getBoolean("poster.allow-without-bounty", false)) {
            throw fail("no-bounty", "player", nameOf(id));
        }
        // chat version for the console or when giving posters is off
        if (!(sender instanceof Player p) || !config().getBoolean("poster.give-item", true)) {
            for (String line : config().getStringList("poster.chat")) {
                sender.sendMessage(Text.mm(line, "player", nameOf(id), "bounty", money(bounty), "tier", tierTitle(bounty)));
            }
            return;
        }
        long now = System.currentTimeMillis();
        Long until = posterCooldowns.get(p.getUniqueId());
        if (until != null && until > now && !p.hasPermission(ADMIN)) {
            throw fail("poster-cooldown", "time", Text.duration((until - now + 999) / 1000));
        }
        double cost = num("poster.cost", 0);
        EconomyService eco = service(EconomyService.class);
        if (cost > 0 && eco != null && !eco.withdraw(p.getUniqueId(), cost)) {
            throw fail("cannot-afford", "cost", eco.format(cost), "balance", eco.format(eco.balance(p.getUniqueId())));
        }
        long cd = Math.max(0, config().getLong("poster.cooldown-seconds", 30)) * 1000L;
        if (cd > 0) {
            posterCooldowns.put(p.getUniqueId(), now + cd);
        }
        for (ItemStack leftover : p.getInventory().addItem(poster(id)).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
        }
        send(p, "poster-given", "player", nameOf(id));
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        remember(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID vid = victim.getUniqueId();
        Entry ve = entries.get(vid);
        if (ve != null && ve.streak > 0) {
            ve.streak = 0;
            dirty = true;
        }
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(vid)) {
            return;
        }
        UUID kid = killer.getUniqueId();
        CrewService crews = service(CrewService.class);
        CrewRelation relation = crews == null ? CrewRelation.NEUTRAL : crews.relation(kid, vid);
        boolean friendly = relation == CrewRelation.SAME || relation == CrewRelation.ALLY;
        double bounty = bountyOf(vid);

        // claim the bounty
        if (bounty > 0) {
            boolean blocked = (relation == CrewRelation.SAME && !config().getBoolean("claiming.crewmates-can-claim", false))
                    || (relation == CrewRelation.ALLY && !config().getBoolean("claiming.allies-can-claim", false));
            EconomyService eco = service(EconomyService.class);
            if (blocked) {
                send(killer, "claim-blocked", "player", victim.getName());
            } else if (eco != null) {
                clearBounty(vid);
                eco.deposit(kid, bounty);
                Component message = msg("claimed-broadcast", "killer", killer.getName(), "player", victim.getName(),
                        "bounty", eco.format(bounty));
                if (config().getBoolean("announce-claimed", true)) {
                    Bukkit.broadcast(message);
                } else {
                    killer.sendMessage(message);
                }
                Bukkit.getPluginManager().callEvent(new BountyClaimEvent(killer, vid, bounty));
            }
        }

        // notoriety for the killer
        if (!config().getBoolean("notoriety.enabled", true)) {
            return;
        }
        if (friendly && config().getBoolean("notoriety.ignore-friendly-kills", true)) {
            return;
        }
        long now = System.currentTimeMillis();
        long window = Math.max(0, config().getLong("notoriety.repeat-kill-cooldown-seconds", 300)) * 1000L;
        String key = kid + ":" + vid;
        Long last = recentKills.get(key);
        if (window > 0 && last != null && now - last < window) {
            return;
        }
        recentKills.put(key, now);

        Entry ke = entry(kid);
        ke.name = killer.getName();
        ke.streak++;
        dirty = true;
        double base = num("notoriety.per-kill", 50);
        String reason = config().getString("reasons.kill", "Murder on the high seas");
        if (bounty <= 0) {
            base += num("notoriety.innocent-kill", 100);
            reason = config().getString("reasons.innocent", "Killing an innocent");
        }
        int from = Math.max(1, config().getInt("notoriety.streak-start", 2));
        double multiplier = 1;
        if (ke.streak >= from) {
            multiplier = Math.min(num("notoriety.max-multiplier", 3), 1 + (ke.streak - from + 1) * num("notoriety.streak-multiplier", 0.25));
            multiplier = Math.max(1, multiplier);
        }
        double gain = Math.floor(base * multiplier);
        if (gain > 0) {
            addBounty(kid, gain, reason);
        }
        int announceAt = config().getInt("notoriety.announce-streak-every", 5);
        if (announceAt > 0 && ke.streak % announceAt == 0) {
            Bukkit.broadcast(msg("streak-broadcast", "player", killer.getName(), "streak", ke.streak,
                    "bounty", money(bountyOf(kid))));
        }
    }
}
