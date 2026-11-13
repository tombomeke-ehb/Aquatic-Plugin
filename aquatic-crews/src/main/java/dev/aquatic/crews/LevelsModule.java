package dev.aquatic.crews;

import dev.aquatic.api.LevelService;
import dev.aquatic.api.event.BountyClaimEvent;
import dev.aquatic.api.event.IslandDiscoverEvent;
import dev.aquatic.api.event.TreasureFoundEvent;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Stats;
import dev.aquatic.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pirate levels (modules/levels.yml): XP from PvP kills, monsters, treasure, discoveries and bounties.
 * Every level gives a little extra damage, every few levels an extra heart.
 */
public final class LevelsModule extends AquaticModule implements LevelService, Listener {

    private static final String SOURCE = "level";

    private final Map<UUID, Long> xp = new HashMap<>();
    /** player -> highest level whose reward was handed out */
    private final Map<UUID, Integer> rewarded = new HashMap<>();
    /** victim -> killer -> last time (ms), so farming the same player gives nothing. */
    private final Map<String, Long> lastKill = new HashMap<>();
    private boolean dirty;

    @Override
    public String id() {
        return "levels";
    }

    @Override
    protected void onEnable() {
        xp.clear();
        YamlConfiguration data = loadData("levels");
        for (String key : data.getKeys(false)) {
            try {
                xp.put(UUID.fromString(key), data.getLong(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        rewarded.clear();
        YamlConfiguration rw = loadData("rewards");
        for (String key : rw.getKeys(false)) {
            try {
                rewarded.put(UUID.fromString(key), rw.getInt(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        registerService(LevelService.class, this);
        registerListener(this);
        registerCommands();
        runTimer(this::saveIfDirty, 1200L, 1200L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyPerks(p);
        }
    }

    @Override
    protected void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Stats.clear(p, SOURCE);
        }
        dirty = true;
        saveIfDirty();
    }

    private void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        YamlConfiguration data = new YamlConfiguration();
        xp.forEach((u, v) -> data.set(u.toString(), v));
        saveData("levels", data);
        YamlConfiguration rw = new YamlConfiguration();
        rewarded.forEach((u, v) -> rw.set(u.toString(), v));
        saveData("rewards", rw);
    }

    // ------------------------------------------------------------------ levels

    private int maxLevel() {
        return Math.max(1, config().getInt("max-level", 50));
    }

    /** Total XP needed to reach a level (level 1 = 0 XP). */
    long xpForLevel(int level) {
        double base = config().getDouble("curve.base", 100), exp = config().getDouble("curve.exponent", 1.45);
        long total = 0;
        for (int l = 1; l < level; l++) {
            total += Math.round(base * Math.pow(l, exp));
        }
        return total;
    }

    @Override
    public int level(UUID player) {
        long have = xp.getOrDefault(player, 0L);
        int level = 1;
        while (level < maxLevel() && have >= xpForLevel(level + 1)) {
            level++;
        }
        return level;
    }

    @Override
    public long xp(UUID player) {
        return xp.getOrDefault(player, 0L);
    }

    @Override
    public double progress(UUID player) {
        int level = level(player);
        if (level >= maxLevel()) {
            return 1;
        }
        long from = xpForLevel(level), to = xpForLevel(level + 1);
        return to <= from ? 1 : Math.max(0, Math.min(1, (xp(player) - from) / (double) (to - from)));
    }

    @Override
    public long xpToNext(UUID player) {
        int level = level(player);
        return level >= maxLevel() ? 0 : Math.max(0, xpForLevel(level + 1) - xp(player));
    }

    @Override
    public void addXp(UUID player, long amount, String reason) {
        if (amount <= 0) {
            return;
        }
        int before = level(player);
        xp.merge(player, amount, Long::sum);
        dirty = true;
        int after = level(player);
        Player p = Bukkit.getPlayer(player);
        if (p == null) {
            return;
        }
        if (config().getBoolean("show-xp-gains", true)) {
            p.sendActionBar(rawMsg("xp-gain", "amount", amount, "reason", reason == null ? "" : reason));
        }
        if (after > before) {
            levelUp(p, after);
        }
    }

    private void levelUp(Player p, int level) {
        p.showTitle(Title.title(rawMsg("levelup-title", "level", level), rawMsg("levelup-subtitle", "level", level),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        send(p, "levelup", "level", level, "damage", Text.number(Math.round(damageBonus(level) * 1000) / 10.0),
                "hearts", Text.number(healthBonus(level) / 2.0));
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.3);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        applyPerks(p);
        grantRewards(p);
        if (level % Math.max(1, config().getInt("announce-every", 10)) == 0) {
            Bukkit.broadcast(msg("levelup-broadcast", "player", p.getName(), "level", level));
        }
    }

    double damageBonus(int level) {
        return Math.min(config().getDouble("perks.max-damage", 0.25), (level - 1) * config().getDouble("perks.damage-per-level", 0.005));
    }

    double healthBonus(int level) {
        int every = Math.max(1, config().getInt("perks.heart-every", 5));
        return Math.min(config().getDouble("perks.max-health", 20), (level / every) * 2.0);
    }

    private void applyPerks(Player p) {
        int level = level(p.getUniqueId());
        Stats.health(p, SOURCE, healthBonus(level));
        Stats.damage(p, SOURCE, damageBonus(level));
    }

    // ------------------------------------------------------------------ XP sources

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyPerks(event.getPlayer());
        Player p = event.getPlayer();
        runLater(() -> {
            if (p.isOnline()) {
                grantRewards(p); // rewards earned before this feature existed, or missed
            }
        }, 60L);
    }

    // ------------------------------------------------------------------ level rewards

    /** Reward levels from the config, lowest first. */
    private List<Integer> rewardLevels() {
        List<Integer> out = new ArrayList<>();
        org.bukkit.configuration.ConfigurationSection sec = config().getConfigurationSection("rewards.levels");
        if (sec != null && config().getBoolean("rewards.enabled", true)) {
            for (String k : sec.getKeys(false)) {
                try {
                    out.add(Integer.parseInt(k));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        out.sort(Integer::compareTo);
        return out;
    }

    /** Hands out every reward between the last rewarded level and the current level (once each). */
    void grantRewards(Player p) {
        UUID id = p.getUniqueId();
        int level = level(id);
        int done = rewarded.getOrDefault(id, 0);
        if (level <= done) {
            return;
        }
        for (int l : rewardLevels()) {
            if (l > done && l <= level) {
                giveReward(p, l);
            }
        }
        rewarded.put(id, level);
        dirty = true;
    }

    private void giveReward(Player p, int level) {
        org.bukkit.configuration.ConfigurationSection r = config().getConfigurationSection("rewards.levels." + level);
        if (r == null) {
            return;
        }
        double money = r.getDouble("doubloons", 0);
        dev.aquatic.api.EconomyService eco = service(dev.aquatic.api.EconomyService.class);
        if (money > 0 && eco != null) {
            eco.deposit(p.getUniqueId(), money);
        }
        List<String> got = new ArrayList<>();
        if (money > 0) {
            got.add(eco != null ? eco.format(money) : Text.number(money) + " Doubloons");
        }
        for (String spec : r.getStringList("items")) {
            org.bukkit.inventory.ItemStack item = parseItem(spec);
            if (item == null) {
                logger().warning("[levels] Unknown reward item '" + spec + "' at level " + level);
                continue;
            }
            got.add(item.getAmount() + "x " + Text.plain(item.getItemMeta() != null && item.getItemMeta().hasDisplayName()
                    ? item.getItemMeta().displayName() : net.kyori.adventure.text.Component.translatable(item.getType().translationKey())));
            for (org.bukkit.inventory.ItemStack left : p.getInventory().addItem(item).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        for (String cmd : r.getStringList("commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
        }
        send(p, "reward", "level", level, "rewards", got.isEmpty() ? "-" : String.join(", ", got));
        String title = r.getString("title");
        if (title != null) {
            p.sendMessage(rawMsg("reward-title", "title", Text.mm(title)));
        }
    }

    /** "item:<custom id>[:amount]" or "MATERIAL[:amount]". */
    private org.bukkit.inventory.ItemStack parseItem(String spec) {
        String[] p = spec.trim().split(":");
        try {
            if (p[0].equalsIgnoreCase("item") && p.length > 1) {
                dev.aquatic.api.ItemService items = service(dev.aquatic.api.ItemService.class);
                return items == null ? null : items.createItem(p[1], p.length > 2 ? Integer.parseInt(p[2]) : 1);
            }
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(p[0]);
            return m == null || !m.isItem() ? null : new org.bukkit.inventory.ItemStack(m, p.length > 1 ? Integer.parseInt(p[1]) : 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String title(UUID player) {
        int level = level(player);
        String best = null;
        for (int l : rewardLevels()) {
            String t = config().getString("rewards.levels." + l + ".title");
            if (l <= level && t != null) {
                best = t;
            }
        }
        return best;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || killer.equals(dead)) {
            return;
        }
        if (dead instanceof Player victim) {
            String key = victim.getUniqueId() + ">" + killer.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastKill.get(key);
            if (last != null && now - last < config().getLong("pvp-cooldown-minutes", 10) * 60000L) {
                return; // no farming the same player
            }
            lastKill.put(key, now);
            long base = config().getLong("xp.pvp-kill", 60);
            // killing someone of a higher level is worth more
            int diff = level(victim.getUniqueId()) - level(killer.getUniqueId());
            addXp(killer.getUniqueId(), Math.max(10, base + diff * 4L), Text.plain(rawMsg("reason-pvp")));
            return;
        }
        String type = dead.getType().name().toLowerCase(Locale.ROOT);
        long amount = config().getLong("xp.mobs." + type, config().getLong("xp.mobs.default", dead instanceof org.bukkit.entity.Monster ? 6 : 1));
        if (dead.getEntitySpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER) {
            amount = Math.round(amount * config().getDouble("xp.spawner-multiplier", 0.2));
        }
        addXp(killer.getUniqueId(), amount, null);
    }

    @EventHandler
    public void onTreasure(TreasureFoundEvent event) {
        addXp(event.player().getUniqueId(), config().getLong("xp.treasure." + event.tier(), config().getLong("xp.treasure.default", 40)),
                Text.plain(rawMsg("reason-treasure")));
    }

    @EventHandler
    public void onDiscover(IslandDiscoverEvent event) {
        addXp(event.player().getUniqueId(), config().getLong("xp.discover-island", 30), Text.plain(rawMsg("reason-discover")));
    }

    @EventHandler
    public void onBounty(BountyClaimEvent event) {
        long amount = Math.round(event.amount() * config().getDouble("xp.bounty-per-doubloon", 0.05));
        addXp(event.killer().getUniqueId(), Math.max(10, amount), Text.plain(rawMsg("reason-bounty")));
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        AquaticCommand cmd = new AquaticCommand("level", "Your pirate level", "lvl", "levels");
        cmd.defaultExecutor((s, a) -> {
            if (a.length > 0) {
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(a[0]);
                if (target == null) {
                    throw new CommandFail("<red>Unknown player: <white><name>", "name", a[0]);
                }
                show(s, target.getUniqueId(), target.getName() == null ? a[0] : target.getName());
            } else {
                Player p = AquaticCommand.player(s);
                show(s, p.getUniqueId(), p.getName());
            }
        }).defaultCompleter((s, a) -> a.length <= 1 ? AquaticCommand.playerNames(a.length == 0 ? "" : a[0]) : List.of());
        cmd.sub("rewards", (s, a) -> {
            Player p = AquaticCommand.player(s);
            int level = level(p.getUniqueId());
            s.sendMessage(msg("rewards-header"));
            for (int l : rewardLevels()) {
                org.bukkit.configuration.ConfigurationSection r = config().getConfigurationSection("rewards.levels." + l);
                List<String> parts = new ArrayList<>();
                if (r.getDouble("doubloons", 0) > 0) {
                    parts.add(Text.number(r.getDouble("doubloons")) + " Doubloons");
                }
                for (String spec : r.getStringList("items")) {
                    org.bukkit.inventory.ItemStack item = parseItem(spec);
                    if (item != null) {
                        parts.add(item.getAmount() + "x " + Text.plain(item.getItemMeta() != null && item.getItemMeta().hasDisplayName()
                                ? item.getItemMeta().displayName() : net.kyori.adventure.text.Component.translatable(item.getType().translationKey())));
                    }
                }
                String title = r.getString("title");
                s.sendMessage(rawMsg(l <= level ? "rewards-line-got" : "rewards-line-open", "level", l,
                        "rewards", String.join(", ", parts), "title", title == null ? net.kyori.adventure.text.Component.empty() : Text.mm(title)));
            }
        }).description("What each level gives you").playerOnly();
        cmd.sub("top", (s, a) -> {
            List<Map.Entry<UUID, Long>> list = new ArrayList<>(xp.entrySet());
            list.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());
            s.sendMessage(msg("top-header"));
            for (int i = 0; i < Math.min(10, list.size()); i++) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(list.get(i).getKey());
                s.sendMessage(rawMsg("top-entry", "rank", i + 1, "player", op.getName() == null ? "?" : op.getName(),
                        "level", level(list.get(i).getKey())));
            }
        }).description("The highest-level pirates");
        cmd.sub("give", (s, a) -> {
            AquaticCommand.need(a, 2, "/level give <player> <xp>");
            Player target = AquaticCommand.onlinePlayer(a[0]);
            addXp(target.getUniqueId(), AquaticCommand.integer(a[1]), "admin");
            send(s, "admin-given", "player", target.getName(), "amount", a[1]);
        }).usage("<player> <xp>").description("Give XP").permission("aquatic.levels.admin")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.playerNames(a[0]) : List.of());
        cmd.sub("set", (s, a) -> {
            AquaticCommand.need(a, 2, "/level set <player> <level>");
            Player target = AquaticCommand.onlinePlayer(a[0]);
            int lvl = Math.max(1, Math.min(maxLevel(), AquaticCommand.integer(a[1])));
            xp.put(target.getUniqueId(), xpForLevel(lvl));
            dirty = true;
            applyPerks(target);
            send(s, "admin-set", "player", target.getName(), "level", lvl);
        }).usage("<player> <level>").description("Set a level").permission("aquatic.levels.admin")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.playerNames(a[0]) : List.of());
        registerCommand(cmd);
    }

    private void show(org.bukkit.command.CommandSender to, UUID id, String name) {
        int level = level(id);
        long next = xpToNext(id);
        to.sendMessage(msg("info", "player", name, "level", level, "max", maxLevel()));
        to.sendMessage(rawMsg(level >= maxLevel() ? "info-max" : "info-next", "xp", Text.number(xp(id)), "next", Text.number(next)));
        to.sendMessage(rawMsg("info-perks", "damage", Text.number(Math.round(damageBonus(level) * 1000) / 10.0),
                "hearts", Text.number(healthBonus(level) / 2.0)));
        String title = title(id);
        if (title != null) {
            to.sendMessage(rawMsg("info-title", "title", Text.mm(title)));
        }
    }

    /** Unused entity import guard. */
    @SuppressWarnings("unused")
    private static boolean isPlayer(Entity e) {
        return e instanceof Player;
    }
}
