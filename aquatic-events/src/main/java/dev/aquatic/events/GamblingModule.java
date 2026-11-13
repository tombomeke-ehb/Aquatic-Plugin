package dev.aquatic.events;

import dev.aquatic.api.EconomyService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.api.IslandType;
import dev.aquatic.api.LevelService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The gambling den (modules/gambling.yml): Doubloons only, at the capital. The Dealer villager (hub.yml) opens
 * /gamble. Games: dice against the house, a wheel of fortune and coin flips between two players. Limits keep it
 * fair: a minimum level, a min and max bet, a daily limit on how much you can bet, a short cooldown, and a log of
 * big wins and losses for staff (plugins/AquaticSMP/data/gambling.log).
 */
public final class GamblingModule extends AquaticModule implements Listener {

    private static final String ADMIN = "aquatic.gambling.admin";

    private record Segment(String label, double multiplier, double weight, Material icon) {
    }

    private record Challenge(UUID from, UUID to, double bet, long expires) {
    }

    private static final class Menu implements InventoryHolder {
        /** null = the main menu, "dice" / "wheel" / "coinflip" = choosing a bet, "players" = picking an opponent. */
        final String game;
        Inventory inventory;
        final Map<Integer, Double> bets = new HashMap<>();
        final Map<Integer, UUID> players = new HashMap<>();
        /** The opponent when the game is "coinflip". */
        UUID opponent;

        Menu(String game) {
            this.game = game;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final List<Segment> wheel = new ArrayList<>();
    private double wheelWeight;
    private final Map<UUID, Challenge> challenges = new HashMap<>();
    private final Map<UUID, Long> lastBet = new HashMap<>();
    /** player -> "yyyy-mm-dd;amount wagered that day". */
    private final Map<UUID, String> daily = new HashMap<>();
    private final java.util.Set<UUID> busy = new java.util.HashSet<>();
    private boolean dirty;

    @Override
    public String id() {
        return "gambling";
    }

    @Override
    public List<String> requires() {
        return List.of("economy");
    }

    @Override
    protected void onEnable() {
        wheel.clear();
        wheelWeight = 0;
        for (Map<?, ?> raw : config().getMapList("wheel.segments")) {
            double mult = raw.get("multiplier") instanceof Number n ? n.doubleValue() : 0;
            double weight = raw.get("weight") instanceof Number n ? n.doubleValue() : 1;
            Material icon = Material.matchMaterial(String.valueOf(raw.get("icon")));
            if (weight > 0) {
                wheel.add(new Segment(String.valueOf(raw.get("label") == null ? "x" + mult : raw.get("label")), mult, weight,
                        icon == null ? Material.WHITE_STAINED_GLASS_PANE : icon));
                wheelWeight += weight;
            }
        }
        YamlConfiguration data = loadData("gambling");
        for (String key : data.getKeys(false)) {
            try {
                daily.put(UUID.fromString(key), data.getString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        registerListener(this);
        registerCommands();
        runTimer(() -> {
            long now = System.currentTimeMillis();
            challenges.values().removeIf(c -> c.expires < now);
            if (dirty) {
                save();
            }
        }, 200L, 200L);
    }

    @Override
    protected void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Reel r && r.owner == this) {
                r.finish();
                p.closeInventory();
            }
        }
        save();
        challenges.clear();
        busy.clear();
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        String today = LocalDate.now().toString();
        daily.forEach((id, v) -> {
            if (v.startsWith(today)) {
                data.set(id.toString(), v);
            }
        });
        saveData("gambling", data);
        dirty = false;
    }

    // ------------------------------------------------------------------ rules

    private EconomyService eco() {
        EconomyService eco = service(EconomyService.class);
        if (eco == null) {
            throw new CommandFail(rawMsg("no-economy"));
        }
        return eco;
    }

    private double wageredToday(UUID id) {
        String v = daily.get(id);
        String today = LocalDate.now().toString();
        if (v == null || !v.startsWith(today)) {
            return 0;
        }
        try {
            return Double.parseDouble(v.substring(v.indexOf(';') + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Checks the den's rules and takes the bet. */
    private void takeBet(Player p, double bet) {
        if (!p.hasPermission("aquatic.gambling.anywhere") && !p.hasPermission(ADMIN) && config().getBoolean("only-at-capital", true)) {
            IslandService islands = service(IslandService.class);
            Island is = islands == null ? null : islands.islandAt(p.getLocation());
            if (islands != null && (is == null || is.type() != IslandType.CAPITAL)) {
                throw new CommandFail(rawMsg("only-at-capital"));
            }
        }
        LevelService levels = service(LevelService.class);
        int minLevel = config().getInt("min-level", 5);
        if (levels != null && levels.level(p.getUniqueId()) < minLevel) {
            throw new CommandFail(rawMsg("min-level", "level", minLevel));
        }
        double min = config().getDouble("min-bet", 10), max = config().getDouble("max-bet", 5000);
        if (bet < min || bet > max || Double.isNaN(bet)) {
            throw new CommandFail(rawMsg("bet-range", "min", money(min), "max", money(max)));
        }
        long cooldown = Math.max(0, config().getLong("cooldown-seconds", 3)) * 1000L;
        Long last = lastBet.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < cooldown) {
            throw new CommandFail(rawMsg("cooldown"));
        }
        if (busy.contains(p.getUniqueId())) {
            throw new CommandFail(rawMsg("busy"));
        }
        double limit = config().getDouble("daily-limit", 25000);
        double today = wageredToday(p.getUniqueId());
        if (limit > 0 && today + bet > limit) {
            throw new CommandFail(rawMsg("daily-limit", "limit", money(limit), "left", money(Math.max(0, limit - today))));
        }
        if (!eco().withdraw(p.getUniqueId(), bet)) {
            throw new CommandFail(rawMsg("no-money", "bet", money(bet)));
        }
        lastBet.put(p.getUniqueId(), System.currentTimeMillis());
        daily.put(p.getUniqueId(), LocalDate.now() + ";" + (today + bet));
        dirty = true;
    }

    private String money(double amount) {
        EconomyService eco = service(EconomyService.class);
        return eco == null ? Text.number(amount) : eco.format(amount);
    }

    /** Big wins and losses go to data/gambling.log for staff. */
    private void log(String who, String game, double bet, double payout) {
        double threshold = config().getDouble("log-threshold", 2000);
        if (Math.abs(payout - bet) < threshold) {
            return;
        }
        String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " " + who + " " + game
                + " bet=" + bet + " payout=" + payout + System.lineSeparator();
        runAsync(() -> {
            try {
                File f = new File(dataFolder(), "gambling.log");
                Files.writeString(f.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger().warning("Could not write gambling.log: " + e.getMessage());
            }
        });
    }

    private void payout(Player p, String game, double bet, double payout) {
        if (payout > 0) {
            eco().deposit(p.getUniqueId(), payout);
        }
        log(p.getName(), game, bet, payout);
        if (payout - bet >= config().getDouble("broadcast-win", 10000)) {
            Bukkit.broadcast(msg("big-win", "player", p.getName(), "amount", money(payout), "game", game));
        }
    }

    // ------------------------------------------------------------------ dice

    private static final String[] FACES = {"⚀", "⚁", "⚂", "⚃", "⚄", "⚅"};

    void dice(Player p, double bet) {
        if (!config().getBoolean("dice.enabled", true)) {
            throw new CommandFail(rawMsg("game-off"));
        }
        takeBet(p, bet);
        busy.add(p.getUniqueId());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int a = r.nextInt(6), b = r.nextInt(6), c = r.nextInt(6), d = r.nextInt(6);
        int frames = 8;
        for (int i = 0; i < frames; i++) {
            int frame = i;
            runLater(() -> {
                if (!p.isOnline()) {
                    return;
                }
                p.sendActionBar(rawMsg("dice-rolling", "you", FACES[r.nextInt(6)] + " " + FACES[r.nextInt(6)],
                        "dealer", FACES[r.nextInt(6)] + " " + FACES[r.nextInt(6)]));
                p.playSound(p.getLocation(), Sound.BLOCK_BAMBOO_WOOD_HIT, 0.7f, 0.9f + frame * 0.06f);
            }, i * 3L);
        }
        runLater(() -> {
            busy.remove(p.getUniqueId());
            int you = a + b + 2, dealer = c + d + 2;
            String yourDice = FACES[a] + " " + FACES[b], dealerDice = FACES[c] + " " + FACES[d];
            double payout;
            String result;
            if (you > dealer) {
                payout = bet * config().getDouble("dice.win-multiplier", 2.0);
                result = "dice-win";
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            } else if (you == dealer) {
                String tie = config().getString("dice.tie", "half");
                payout = tie.equalsIgnoreCase("push") ? bet : tie.equalsIgnoreCase("half") ? bet / 2 : 0;
                result = "dice-tie";
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1f);
            } else {
                payout = 0;
                result = "dice-lose";
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            }
            if (!p.isOnline()) {
                payout(p, "dice", bet, payout);
                return;
            }
            p.showTitle(Title.title(Text.mm(yourDice + " <gray>vs</gray> " + dealerDice), rawMsg(result + "-title", "amount", money(payout))));
            payout(p, "dice", bet, payout);
            send(p, result, "you", yourDice + " (" + you + ")", "dealer", dealerDice + " (" + dealer + ")",
                    "bet", money(bet), "amount", money(payout));
        }, frames * 3L + 4);
    }

    // ------------------------------------------------------------------ wheel of fortune

    private Segment rollWheel() {
        double x = ThreadLocalRandom.current().nextDouble() * wheelWeight;
        for (Segment s : wheel) {
            x -= s.weight;
            if (x <= 0) {
                return s;
            }
        }
        return wheel.get(wheel.size() - 1);
    }

    private ItemStack segmentIcon(Segment s) {
        ItemStack it = new ItemStack(s.icon);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(s.label));
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    void wheel(Player p, double bet) {
        if (!config().getBoolean("wheel.enabled", true) || wheel.isEmpty()) {
            throw new CommandFail(rawMsg("game-off"));
        }
        takeBet(p, bet);
        busy.add(p.getUniqueId());
        Segment won = rollWheel();
        Reel.spin(this, p, rawMsg("wheel-title", "bet", money(bet)), () -> segmentIcon(rollWheel()), segmentIcon(won),
                Math.max(12, config().getInt("wheel.steps", 36)), () -> {
                    busy.remove(p.getUniqueId());
                    double payout = Math.floor(bet * won.multiplier);
                    payout(p, "wheel", bet, payout);
                    send(p, payout > bet ? "wheel-win" : payout > 0 ? "wheel-some" : "wheel-lose",
                            "segment", Text.mm(won.label), "bet", money(bet), "amount", money(payout));
                });
    }

    // ------------------------------------------------------------------ coin flip between players

    void challenge(Player p, Player target, double bet) {
        if (!config().getBoolean("coinflip.enabled", true)) {
            throw new CommandFail(rawMsg("game-off"));
        }
        if (target.equals(p)) {
            throw new CommandFail(rawMsg("coinflip-self"));
        }
        if (!eco().has(p.getUniqueId(), bet)) {
            throw new CommandFail(rawMsg("no-money", "bet", money(bet)));
        }
        double min = config().getDouble("min-bet", 10), max = config().getDouble("max-bet", 5000);
        if (bet < min || bet > max) {
            throw new CommandFail(rawMsg("bet-range", "min", money(min), "max", money(max)));
        }
        long expires = System.currentTimeMillis() + Math.max(10, config().getLong("coinflip.expire-seconds", 60)) * 1000L;
        challenges.put(target.getUniqueId(), new Challenge(p.getUniqueId(), target.getUniqueId(), bet, expires));
        send(p, "coinflip-sent", "player", target.getName(), "bet", money(bet));
        send(target, "coinflip-received", "player", p.getName(), "bet", money(bet));
    }

    void accept(Player target) {
        Challenge c = challenges.remove(target.getUniqueId());
        if (c == null || c.expires < System.currentTimeMillis()) {
            throw new CommandFail(rawMsg("coinflip-none"));
        }
        Player from = Bukkit.getPlayer(c.from);
        if (from == null) {
            throw new CommandFail(rawMsg("coinflip-gone"));
        }
        // both bets go in (each with the den's rules); if the second fails, the first is paid back
        takeBet(target, c.bet);
        try {
            takeBet(from, c.bet);
        } catch (CommandFail fail) {
            eco().deposit(target.getUniqueId(), c.bet);
            throw new CommandFail(rawMsg("coinflip-failed", "player", from.getName()));
        }
        boolean fromWins = ThreadLocalRandom.current().nextBoolean();
        Player winner = fromWins ? from : target, loser = fromWins ? target : from;
        double pot = c.bet * 2 * (1 - Math.max(0, Math.min(0.5, config().getDouble("coinflip.house-cut", 0.05))));
        for (int i = 0; i < 6; i++) {
            int frame = i;
            runLater(() -> {
                for (Player p : List.of(from, target)) {
                    if (p.isOnline()) {
                        p.sendActionBar(rawMsg(frame % 2 == 0 ? "coinflip-heads" : "coinflip-tails"));
                        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.2f + frame * 0.05f);
                    }
                }
            }, i * 4L);
        }
        runLater(() -> {
            eco().deposit(winner.getUniqueId(), pot);
            log(winner.getName() + " vs " + loser.getName(), "coinflip", c.bet, pot);
            for (Player p : List.of(from, target)) {
                if (p.isOnline()) {
                    send(p, "coinflip-result", "winner", winner.getName(), "loser", loser.getName(), "amount", money(pot));
                }
            }
            if (winner.isOnline()) {
                winner.playSound(winner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            }
        }, 26L);
    }

    // ------------------------------------------------------------------ menu

    void openMenu(Player p, String game) {
        Menu holder = new Menu(game);
        Inventory inv = Bukkit.createInventory(holder, 27, rawMsg(game == null ? "menu-title" : game.equals("players") ? "pick-title" : "bet-title",
                "game", rawMsg("game-" + (game == null || game.equals("players") ? "dice" : game))));
        holder.inventory = inv;
        ItemStack glass = Reel.pane(Material.GREEN_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, glass);
        }
        if (game == null) {
            inv.setItem(11, button(Material.BONE_BLOCK, "menu-dice", "menu-dice-lore"));
            inv.setItem(13, button(Material.CLOCK, "menu-wheel", "menu-wheel-lore"));
            inv.setItem(15, button(Material.SUNFLOWER, "menu-coinflip", "menu-coinflip-lore"));
            Challenge pending = challenges.get(p.getUniqueId());
            if (pending != null && pending.expires >= System.currentTimeMillis()) {
                Player from = Bukkit.getPlayer(pending.from);
                Object[] ph = {"player", from == null ? "?" : from.getName(), "bet", money(pending.bet)};
                inv.setItem(20, button(Material.LIME_DYE, "menu-accept", "menu-accept-lore", ph));
                inv.setItem(24, button(Material.RED_DYE, "menu-deny", null, ph));
            }
            double limit = config().getDouble("daily-limit", 25000);
            inv.setItem(22, button(Material.PAPER, "menu-limits", null, "min", money(config().getDouble("min-bet", 10)),
                    "max", money(config().getDouble("max-bet", 5000)),
                    "left", limit > 0 ? money(Math.max(0, limit - wageredToday(p.getUniqueId()))) : "-"));
        } else if (game.equals("players")) {
            int slot = 0;
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(p) || slot >= 18) {
                    continue;
                }
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                if (head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
                    sm.setOwningPlayer(other);
                    head.setItemMeta(sm);
                }
                inv.setItem(slot, dev.aquatic.util.Menus.icon(head, Text.mm("<white>" + other.getName()),
                        List.of(rawMsg("pick-player-lore"))));
                holder.players.put(slot++, other.getUniqueId());
            }
            if (slot == 0) {
                inv.setItem(13, button(Material.BARRIER, "pick-nobody", null));
            }
            inv.setItem(22, button(Material.ARROW, "menu-back", null));
        } else {
            List<Double> presets = config().getDoubleList("bet-presets");
            Material[] icons = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.RAW_GOLD, Material.GOLD_BLOCK, Material.RAW_GOLD_BLOCK,
                    Material.DIAMOND, Material.DIAMOND_BLOCK};
            int[] slots = {10, 11, 12, 13, 14, 15, 16};
            for (int i = 0; i < presets.size() && i < slots.length; i++) {
                double bet = presets.get(i);
                ItemStack it = button(icons[i], "bet-button", "bet-button-lore", "bet", money(bet));
                inv.setItem(slots[i], it);
                holder.bets.put(slots[i], bet);
            }
            inv.setItem(22, button(Material.ARROW, "menu-back", null));
        }
        p.openInventory(inv);
    }

    private Menu openBetMenu(Player p, String game) {
        openMenu(p, game);
        return (Menu) p.getOpenInventory().getTopInventory().getHolder();
    }

    void deny(Player p) {
        Challenge c = challenges.remove(p.getUniqueId());
        if (c == null) {
            throw new CommandFail(rawMsg("coinflip-none"));
        }
        send(p, "coinflip-denied");
        Player from = Bukkit.getPlayer(c.from);
        if (from != null) {
            send(from, "coinflip-denied-by", "player", p.getName());
        }
    }

    private ItemStack button(Material m, String nameKey, String loreKey, Object... ph) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(config().getString("messages." + nameKey, nameKey), ph));
        if (loreKey != null) {
            List<Component> lore = new ArrayList<>();
            for (String line : config().getStringList("messages." + loreKey)) {
                lore.add(Text.item(line, ph));
            }
            meta.lore(lore);
        }
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder h = event.getInventory().getHolder();
        if (h instanceof Reel r && r.owner == this) {
            event.setCancelled(true);
            return;
        }
        if (!(h instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getSlot();
        try {
            if (menu.game == null) {
                switch (slot) {
                    case 11 -> openMenu(p, "dice");
                    case 13 -> openMenu(p, "wheel");
                    case 15 -> openMenu(p, "players");
                    case 20 -> {
                        p.closeInventory();
                        accept(p);
                    }
                    case 24 -> {
                        p.closeInventory();
                        deny(p);
                    }
                    default -> {
                    }
                }
                return;
            }
            if (menu.game.equals("players")) {
                UUID target = menu.players.get(slot);
                if (slot == 22) {
                    openMenu(p, null);
                } else if (target != null) {
                    Menu next = openBetMenu(p, "coinflip");
                    next.opponent = target;
                }
                return;
            }
            if (slot == 22) {
                openMenu(p, null);
                return;
            }
            Double bet = menu.bets.get(slot);
            if (bet == null) {
                return;
            }
            if (menu.game.equals("dice")) {
                p.closeInventory();
                dice(p, bet);
            } else if (menu.game.equals("coinflip")) {
                p.closeInventory();
                Player target = menu.opponent == null ? null : Bukkit.getPlayer(menu.opponent);
                if (target == null) {
                    throw new CommandFail(rawMsg("coinflip-gone"));
                }
                challenge(p, target, bet);
            } else {
                wheel(p, bet);
            }
        } catch (CommandFail fail) {
            p.closeInventory();
            p.sendMessage(plugin().prefix().append(fail.component()));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder h = event.getInventory().getHolder();
        if (h instanceof Menu || (h instanceof Reel r && r.owner == this)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Reel r && r.owner == this && !r.done()) {
            r.finish();
        }
    }

    // ------------------------------------------------------------------ commands

    private double bet(String raw) {
        double v = AquaticCommand.number(raw);
        return Math.floor(v);
    }

    private void registerCommands() {
        // Players play through the Dealer's menu only. Staff keep commands to test the den anywhere.
        AquaticCommand gamble = new AquaticCommand("gamble", "The gambling den's menu (staff; players talk to the Dealer)", "casino");
        gamble.defaultExecutor((s, a) -> openMenu(AquaticCommand.player(s), null));
        gamble.permission(ADMIN);
        registerCommand(gamble);
    }

    @Override
    protected void migrateConfig(YamlConfiguration file, YamlConfiguration defaults, int from) {
        if (from < 2) {
            // 2.16.0: everything through the Dealer's menu (no player commands)
            raise(file, "messages.menu-coinflip-lore", List.of("<gray>Against another player:", "<white>/coinflip <player> <bet>"),
                    defaults.getStringList("messages.menu-coinflip-lore"));
            file.set("messages.coinflip-received", defaults.getString("messages.coinflip-received"));
            for (String key : List.of("menu-accept", "menu-accept-lore", "menu-deny", "pick-title", "pick-player-lore", "pick-nobody", "game-coinflip")) {
                addMissing(file, defaults, "messages." + key);
            }
        }
    }
}
