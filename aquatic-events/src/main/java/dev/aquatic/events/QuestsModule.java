package dev.aquatic.events;

import dev.aquatic.api.EconomyService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.api.IslandType;
import dev.aquatic.api.ItemService;
import dev.aquatic.api.LevelService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quests from the village folk (modules/quests.yml). Villagers on neutral village islands (the hub module
 * spawns them, see hub.yml) offer quests: catch fish, hunt mobs or bring materials. Quests are accepted and
 * turned in on a village; rewards are Doubloons, pirate XP and items. Each quest can be done again after a
 * cooldown.
 */
public final class QuestsModule extends AquaticModule implements Listener {

    private record Quest(String id, String giver, String name, String description, String type, String target,
                         int amount, double cooldownHours, double doubloons, long xp, List<String> items) {
    }

    private static final class PlayerQuests {
        final Map<String, Integer> active = new LinkedHashMap<>();
        final Map<String, Long> cooldown = new HashMap<>();
        int done;
    }

    private static final Set<Material> FISH = Set.of(Material.COD, Material.SALMON, Material.PUFFERFISH, Material.TROPICAL_FISH);

    private final Map<String, Quest> quests = new LinkedHashMap<>();
    private final Map<UUID, PlayerQuests> players = new HashMap<>();
    private boolean dirty;

    @Override
    public String id() {
        return "quests";
    }

    @Override
    public List<String> requires() {
        return List.of("economy");
    }

    @Override
    protected void onEnable() {
        loadQuests();
        loadState();
        registerListener(this);
        registerCommands();
        runTimer(() -> {
            if (dirty) {
                saveState();
            }
        }, 1200L, 1200L);
    }

    @Override
    protected void onDisable() {
        saveState();
        players.clear();
        quests.clear();
    }

    // ------------------------------------------------------------------ config and state

    private void loadQuests() {
        quests.clear();
        ConfigurationSection all = config().getConfigurationSection("quests");
        if (all == null) {
            return;
        }
        for (String id : all.getKeys(false)) {
            ConfigurationSection q = all.getConfigurationSection(id);
            if (q == null || !q.getBoolean("enabled", true)) {
                continue;
            }
            String type = q.getString("type", "collect").toLowerCase(Locale.ROOT);
            if (!List.of("collect", "kill", "fish").contains(type)) {
                logger().warning("Quest " + id + ": unknown type " + type + " (collect, kill or fish)");
                continue;
            }
            String target = q.getString("target", "ANY").toUpperCase(Locale.ROOT);
            if (type.equals("collect") && Material.matchMaterial(target) == null) {
                logger().warning("Quest " + id + ": unknown material " + target);
                continue;
            }
            quests.put(id.toLowerCase(Locale.ROOT), new Quest(id.toLowerCase(Locale.ROOT), q.getString("giver", "elder").toLowerCase(Locale.ROOT),
                    q.getString("name", id), q.getString("description", ""), type, target, Math.max(1, q.getInt("amount", 1)),
                    Math.max(0, q.getDouble("cooldown-hours", 20)), Math.max(0, q.getDouble("rewards.doubloons", 0)),
                    Math.max(0, q.getLong("rewards.xp", 0)), q.getStringList("rewards.items")));
        }
    }

    private void loadState() {
        players.clear();
        YamlConfiguration data = loadData("quests");
        ConfigurationSection all = data.getConfigurationSection("players");
        if (all == null) {
            return;
        }
        for (String key : all.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            PlayerQuests pq = new PlayerQuests();
            ConfigurationSection a = all.getConfigurationSection(key + ".active");
            if (a != null) {
                for (String q : a.getKeys(false)) {
                    pq.active.put(q, a.getInt(q));
                }
            }
            ConfigurationSection c = all.getConfigurationSection(key + ".cooldown");
            if (c != null) {
                for (String q : c.getKeys(false)) {
                    pq.cooldown.put(q, c.getLong(q));
                }
            }
            pq.done = all.getInt(key + ".done");
            players.put(id, pq);
        }
    }

    private void saveState() {
        YamlConfiguration data = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PlayerQuests> e : players.entrySet()) {
            String base = "players." + e.getKey();
            PlayerQuests pq = e.getValue();
            pq.active.forEach((q, n) -> data.set(base + ".active." + q, n));
            pq.cooldown.forEach((q, until) -> {
                if (until > now) {
                    data.set(base + ".cooldown." + q, until);
                }
            });
            data.set(base + ".done", pq.done);
        }
        saveData("quests", data);
        dirty = false;
    }

    private PlayerQuests of(Player p) {
        return players.computeIfAbsent(p.getUniqueId(), k -> new PlayerQuests());
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        AquaticCommand cmd = new AquaticCommand("quests", "Your quests from the village folk", "quest");
        cmd.defaultExecutor((s, a) -> list(AquaticCommand.player(s)));
        cmd.sub("talk", (s, a) -> {
            AquaticCommand.need(a, 1, "/quests talk <villager>");
            talk(AquaticCommand.player(s), a[0].toLowerCase(Locale.ROOT));
        }).hidden().playerOnly();
        cmd.sub("accept", (s, a) -> {
            AquaticCommand.need(a, 1, "/quests accept <quest>");
            accept(AquaticCommand.player(s), quest(a[0]));
        }).usage("<quest>").description("Accept a quest (on a village)").playerOnly();
        cmd.sub("turnin", (s, a) -> {
            AquaticCommand.need(a, 1, "/quests turnin <quest>");
            turnIn(AquaticCommand.player(s), quest(a[0]));
        }).usage("<quest>").description("Hand in a finished quest (on a village)").playerOnly()
                .completer((s, a) -> s instanceof Player p ? AquaticCommand.filter(new ArrayList<>(of(p).active.keySet()), a.length == 0 ? "" : a[0]) : List.of());
        cmd.sub("abandon", (s, a) -> {
            AquaticCommand.need(a, 1, "/quests abandon <quest>");
            Player p = AquaticCommand.player(s);
            Quest q = quest(a[0]);
            if (of(p).active.remove(q.id) == null) {
                throw new CommandFail(rawMsg("not-active", "quest", Text.mm(q.name)));
            }
            dirty = true;
            send(p, "abandoned", "quest", Text.mm(q.name));
        }).usage("<quest>").description("Give up a quest").playerOnly()
                .completer((s, a) -> s instanceof Player p ? AquaticCommand.filter(new ArrayList<>(of(p).active.keySet()), a.length == 0 ? "" : a[0]) : List.of());
        registerCommand(cmd);
    }

    private Quest quest(String raw) {
        Quest q = quests.get(raw.toLowerCase(Locale.ROOT));
        if (q == null) {
            throw new CommandFail(rawMsg("unknown-quest", "quest", raw));
        }
        return q;
    }

    /** True on a village island (or when require-village is off, or the islands module is not running). */
    private boolean atVillage(Player p) {
        if (!config().getBoolean("require-village", true)) {
            return true;
        }
        IslandService islands = service(IslandService.class);
        if (islands == null) {
            return true;
        }
        Island is = islands.islandAt(p.getLocation());
        return is != null && is.type() == IslandType.VILLAGE;
    }

    private Component giverName(String giver) {
        return Text.mm(config().getString("givers." + giver + ".name", giver));
    }

    /** A villager was clicked: greeting plus that villager's quests, each with a button. */
    private void talk(Player p, String giver) {
        if (!atVillage(p)) {
            throw new CommandFail(rawMsg("not-at-village"));
        }
        p.sendMessage(Component.empty());
        p.sendMessage(Text.mm(config().getString("messages.greeting-line", "<name> <dark_gray>» <gray><greeting>"),
                "name", giverName(giver), "greeting", Text.mm(config().getString("givers." + giver + ".greeting", "Ahoy."))));
        PlayerQuests pq = of(p);
        long now = System.currentTimeMillis();
        boolean any = false;
        for (Quest q : quests.values()) {
            if (!q.giver.equals(giver)) {
                continue;
            }
            any = true;
            p.sendMessage(line(p, pq, q, now));
        }
        if (!any) {
            p.sendMessage(rawMsg("no-quests-here"));
        }
    }

    private Component line(Player p, PlayerQuests pq, Quest q, long now) {
        String key;
        Integer progress = pq.active.get(q.id);
        if (progress != null) {
            key = progressOf(p, q, progress) >= q.amount ? "line-ready" : "line-active";
        } else if (pq.cooldown.getOrDefault(q.id, 0L) > now) {
            key = "line-cooldown";
        } else {
            key = "line-available";
        }
        String raw = config().getString("messages." + key, key).replace("<id>", q.id);
        long left = pq.cooldown.getOrDefault(q.id, 0L) - now;
        return Text.mm(raw, "name", Text.mm(q.name), "description", q.description, "reward", rewardText(q),
                "progress", progress == null ? 0 : Math.min(q.amount, progressOf(p, q, progress)), "amount", q.amount,
                "time", time(left), "giver", giverName(q.giver));
    }

    private void list(Player p) {
        PlayerQuests pq = of(p);
        if (pq.active.isEmpty()) {
            send(p, "none-active");
            return;
        }
        send(p, "list-header", "count", pq.active.size(), "max", maxActive());
        long now = System.currentTimeMillis();
        for (String id : pq.active.keySet()) {
            Quest q = quests.get(id);
            if (q != null) {
                p.sendMessage(line(p, pq, q, now));
            }
        }
    }

    private int maxActive() {
        return Math.max(1, config().getInt("max-active", 3));
    }

    private void accept(Player p, Quest q) {
        if (!atVillage(p)) {
            throw new CommandFail(rawMsg("not-at-village"));
        }
        PlayerQuests pq = of(p);
        if (pq.active.containsKey(q.id)) {
            throw new CommandFail(rawMsg("already-active", "quest", Text.mm(q.name)));
        }
        long left = pq.cooldown.getOrDefault(q.id, 0L) - System.currentTimeMillis();
        if (left > 0) {
            throw new CommandFail(rawMsg("on-cooldown", "quest", Text.mm(q.name), "time", time(left)));
        }
        if (pq.active.size() >= maxActive()) {
            throw new CommandFail(rawMsg("too-many", "max", maxActive()));
        }
        pq.active.put(q.id, 0);
        dirty = true;
        send(p, "accepted", "quest", Text.mm(q.name), "description", q.description, "giver", giverName(q.giver));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    private void turnIn(Player p, Quest q) {
        if (!atVillage(p)) {
            throw new CommandFail(rawMsg("not-at-village"));
        }
        PlayerQuests pq = of(p);
        Integer progress = pq.active.get(q.id);
        if (progress == null) {
            throw new CommandFail(rawMsg("not-active", "quest", Text.mm(q.name)));
        }
        if (progressOf(p, q, progress) < q.amount) {
            throw new CommandFail(rawMsg("not-done", "quest", Text.mm(q.name), "progress", progressOf(p, q, progress), "amount", q.amount));
        }
        if (q.type.equals("collect")) {
            take(p, Material.matchMaterial(q.target), q.amount);
        }
        pq.active.remove(q.id);
        pq.cooldown.put(q.id, System.currentTimeMillis() + (long) (q.cooldownHours * 3_600_000L));
        pq.done++;
        dirty = true;
        EconomyService eco = service(EconomyService.class);
        if (eco != null && q.doubloons > 0) {
            eco.deposit(p.getUniqueId(), q.doubloons);
        }
        LevelService levels = service(LevelService.class);
        if (levels != null && q.xp > 0) {
            levels.addXp(p.getUniqueId(), q.xp, "quest");
        }
        for (String spec : q.items) {
            ItemStack stack = stack(spec);
            if (stack != null) {
                p.getInventory().addItem(stack).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            }
        }
        send(p, "completed", "quest", Text.mm(q.name), "reward", rewardText(q), "giver", giverName(q.giver));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------------ progress

    /** Collect quests count what you carry; kill and fish quests count what you did. */
    private int progressOf(Player p, Quest q, int stored) {
        if (q.type.equals("collect")) {
            return count(p, Material.matchMaterial(q.target));
        }
        return stored;
    }

    /** Plain items only: custom items (that use a vanilla material) never count. */
    private boolean plain(ItemStack it, Material m) {
        if (it == null || it.getType() != m) {
            return false;
        }
        ItemService items = service(ItemService.class);
        return items == null || items.idOf(it) == null;
    }

    private int count(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (plain(it, m)) {
                n += it.getAmount();
            }
        }
        return n;
    }

    private void take(Player p, Material m, int amount) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack it = contents[i];
            if (!plain(it, m)) {
                continue;
            }
            int use = Math.min(amount, it.getAmount());
            amount -= use;
            if (use == it.getAmount()) {
                contents[i] = null;
            } else {
                it.setAmount(it.getAmount() - use);
            }
        }
        p.getInventory().setStorageContents(contents);
    }

    private void progress(Player p, String type, String what, boolean fish) {
        PlayerQuests pq = players.get(p.getUniqueId());
        if (pq == null || pq.active.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> e : pq.active.entrySet()) {
            Quest q = quests.get(e.getKey());
            if (q == null || !q.type.equals(type) || e.getValue() >= q.amount) {
                continue;
            }
            boolean match = q.target.equals(what) || (q.target.equals("ANY") && (!type.equals("fish") || fish));
            if (!match) {
                continue;
            }
            int now = e.getValue() + 1;
            e.setValue(now);
            dirty = true;
            if (now >= q.amount) {
                send(p, "ready", "quest", Text.mm(q.name), "giver", giverName(q.giver));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
            } else {
                p.sendActionBar(rawMsg("progress", "quest", Text.mm(q.name), "progress", now, "amount", q.amount));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            progress(killer, "kill", event.getEntityType().name(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item item) {
            Material m = item.getItemStack().getType();
            progress(event.getPlayer(), "fish", m.name(), FISH.contains(m));
        }
    }

    // ------------------------------------------------------------------ rewards

    /** A reward item: "MATERIAL:n", "item:<id>:n" or "key:<crate>:n" (see ItemSpecs). */
    private ItemStack stack(String spec) {
        ItemStack it = dev.aquatic.util.ItemSpecs.parse(spec);
        if (it == null) {
            logger().warning("Quest reward item not understood: " + spec);
        }
        return it;
    }

    private String rewardText(Quest q) {
        List<String> parts = new ArrayList<>();
        EconomyService eco = service(EconomyService.class);
        if (q.doubloons > 0) {
            parts.add(eco != null ? eco.format(q.doubloons) : Text.number(q.doubloons) + " Doubloons");
        }
        if (q.xp > 0) {
            parts.add(q.xp + " XP");
        }
        for (String spec : q.items) {
            ItemStack it = stack(spec);
            if (it != null) {
                parts.add(dev.aquatic.util.ItemSpecs.describe(it));
            }
        }
        return String.join(", ", parts);
    }

    private static String time(long ms) {
        long min = Math.max(1, ms / 60_000L);
        return min >= 60 ? (min / 60) + "h " + (min % 60) + "m" : min + "m";
    }
}
