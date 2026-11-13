package dev.aquatic.events;

import dev.aquatic.api.CrateService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.api.IslandType;
import dev.aquatic.api.LevelService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.ItemSpecs;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Crates (modules/crates.yml): any block can be made a crate with /crate set &lt;type&gt; (a chest in a team build,
 * a barrel, a shulker box ...). Right-click it with the matching key to open it: a reel spins and stops on a
 * prize. Without a key (or with a left-click) it shows every prize and its chance. Keys come from quests,
 * level rewards, events and staff ("key:&lt;type&gt;:&lt;amount&gt;" in any reward list).
 */
public final class CratesModule extends AquaticModule implements Listener, CrateService {

    private record Prize(String name, ItemStack icon, double weight, double doubloons, long xp, List<String> items,
                         List<String> commands, boolean broadcast) {
    }

    private record Crate(String id, String name, List<Prize> prizes, double totalWeight) {
        Prize roll() {
            double r = ThreadLocalRandom.current().nextDouble() * totalWeight;
            for (Prize p : prizes) {
                r -= p.weight;
                if (r <= 0) {
                    return p;
                }
            }
            return prizes.get(prizes.size() - 1);
        }
    }

    private static final class Preview implements InventoryHolder {
        final String crate;
        Inventory inventory;

        Preview(String crate) {
            this.crate = crate;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Overview implements InventoryHolder {
        Inventory inventory;
        final List<String> slots = new ArrayList<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Map<String, Crate> crates = new LinkedHashMap<>();
    /** "world;x;y;z" -> crate type. */
    private final Map<String, String> blocks = new LinkedHashMap<>();
    private final Map<String, UUID> holograms = new HashMap<>();
    private final Set<UUID> opening = new LinkedHashSet<>();
    private NamespacedKey keyTag, holoTag;
    private int ticks;

    @Override
    public String id() {
        return "crates";
    }

    @Override
    protected void onEnable() {
        keyTag = new NamespacedKey(plugin(), "crate_key");
        holoTag = new NamespacedKey(plugin(), "crate_hologram");
        loadCrates();
        loadBlocks();
        registerService(CrateService.class, this);
        registerListener(this);
        registerCommands();
        runTimer(this::tick, 40L, 20L);
    }

    @Override
    protected void onDisable() {
        for (UUID id : holograms.values()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        holograms.clear();
        // anyone still watching a reel gets their prize now
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Reel r && r.owner == this) {
                r.finish();
                p.closeInventory();
            }
        }
        opening.clear();
    }

    // ------------------------------------------------------------------ config and data

    private void loadCrates() {
        crates.clear();
        ConfigurationSection all = config().getConfigurationSection("crates");
        if (all == null) {
            return;
        }
        for (String id : all.getKeys(false)) {
            ConfigurationSection c = all.getConfigurationSection(id);
            if (c == null || !c.getBoolean("enabled", true)) {
                continue;
            }
            List<Prize> prizes = new ArrayList<>();
            double total = 0;
            for (Map<?, ?> raw : c.getMapList("prizes")) {
                double weight = raw.get("weight") instanceof Number n ? n.doubleValue() : 1;
                if (weight <= 0) {
                    continue;
                }
                List<String> items = strings(raw.get("items"));
                ItemStack icon = null;
                if (raw.get("icon") != null) {
                    Material m = Material.matchMaterial(String.valueOf(raw.get("icon")));
                    icon = m == null || !m.isItem() ? null : new ItemStack(m);
                }
                if (icon == null && !items.isEmpty()) {
                    icon = ItemSpecs.parse(items.get(0));
                }
                if (icon == null) {
                    icon = new ItemStack(raw.get("doubloons") != null ? Material.GOLD_NUGGET : Material.PAPER);
                }
                prizes.add(new Prize(String.valueOf(raw.get("name") == null ? "<white>Prize" : raw.get("name")), icon, weight,
                        raw.get("doubloons") instanceof Number n ? n.doubleValue() : 0,
                        raw.get("xp") instanceof Number n ? n.longValue() : 0, items, strings(raw.get("commands")),
                        Boolean.TRUE.equals(raw.get("broadcast"))));
                total += weight;
            }
            if (prizes.isEmpty()) {
                logger().warning("Crate " + id + " has no prizes; it is left out.");
                continue;
            }
            crates.put(id.toLowerCase(Locale.ROOT), new Crate(id.toLowerCase(Locale.ROOT), c.getString("name", id), prizes, total));
        }
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    private void loadBlocks() {
        blocks.clear();
        YamlConfiguration data = loadData("crates");
        for (String line : data.getStringList("blocks")) {
            int cut = line.lastIndexOf('=');
            if (cut > 0) {
                blocks.put(line.substring(0, cut), line.substring(cut + 1));
            }
        }
    }

    private void saveBlocks() {
        YamlConfiguration data = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        blocks.forEach((k, v) -> lines.add(k + "=" + v));
        data.set("blocks", lines);
        saveData("crates", data);
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private static Location location(String key) {
        String[] p = key.split(";");
        World w = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
        return w == null ? null : new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }

    private Crate crate(String raw) {
        Crate c = raw == null ? null : crates.get(raw.toLowerCase(Locale.ROOT));
        if (c == null) {
            throw new CommandFail(rawMsg("unknown-crate", "crate", String.valueOf(raw), "crates", String.join(", ", crates.keySet())));
        }
        return c;
    }

    // ------------------------------------------------------------------ keys (CrateService)

    @Override
    public Set<String> crateTypes() {
        return crates.keySet();
    }

    @Override
    public ItemStack key(String crateType, int amount) {
        Crate c = crateType == null ? null : crates.get(crateType.toLowerCase(Locale.ROOT));
        if (c == null) {
            return null;
        }
        ConfigurationSection d = config().getConfigurationSection("crates." + c.id);
        Material m = Material.matchMaterial(d == null ? "TRIPWIRE_HOOK" : d.getString("key-material", "TRIPWIRE_HOOK"));
        ItemStack it = new ItemStack(m == null ? Material.TRIPWIRE_HOOK : m, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(d == null ? c.name + " Key" : d.getString("key-name", c.name + " <white>Key")));
        List<Component> lore = new ArrayList<>();
        for (String line : d == null ? List.<String>of() : d.getStringList("key-lore")) {
            lore.add(Text.item(line));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, c.id);
        String model = d == null ? null : d.getString("key-model");
        if (model != null && !model.isBlank() && packOn()) {
            meta.setItemModel(NamespacedKey.fromString(model));
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    /** Custom key looks only when the server sends the resource pack (items.yml resource-pack.enabled). */
    private boolean packOn() {
        AquaticModule items = plugin().module("items");
        return items != null && items.isEnabled() && items.config().getBoolean("resource-pack.enabled", false);
    }

    private String keyType(ItemStack it) {
        if (it == null || !it.hasItemMeta()) {
            return null;
        }
        return it.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING);
    }

    /** Takes one key for the crate from the hand (or else anywhere in the inventory). */
    private boolean takeKey(Player p, String crate) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (crate.equals(keyType(hand))) {
            hand.setAmount(hand.getAmount() - 1);
            return true;
        }
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (ItemStack it : contents) {
            if (crate.equals(keyType(it))) {
                it.setAmount(it.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    private int countKeys(Player p, String crate) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (crate.equals(keyType(it))) {
                n += it.getAmount();
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ the crate blocks

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Block b = event.getClickedBlock();
        if (b == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String type = blocks.get(key(b));
        if (type == null) {
            return;
        }
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        Player p = event.getPlayer();
        Crate c = crates.get(type);
        if (c == null) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && p.getGameMode() == org.bukkit.GameMode.CREATIVE && p.isSneaking()) {
            return; // let staff in creative do things with sneak + left-click (nothing breaks: see onBreak)
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            preview(p, c);
            return;
        }
        open(p, c, b.getLocation());
    }

    private void open(Player p, Crate c, Location at) {
        if (opening.contains(p.getUniqueId())) {
            return;
        }
        if (!takeKey(p, c.id)) {
            send(p, "need-key", "crate", Text.mm(c.name), "key", Text.mm(config().getString("crates." + c.id + ".key-name", c.name + " Key")));
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.8f, 1f);
            preview(p, c);
            return;
        }
        opening.add(p.getUniqueId());
        Prize won = c.roll();
        p.playSound(at, Sound.BLOCK_CHEST_OPEN, 0.8f, 0.9f);
        Reel.spin(this, p, Text.mm(c.name), () -> icon(c.roll(), c, false), icon(won, c, false),
                Math.max(10, config().getInt("opening.steps", 32)), () -> {
                    opening.remove(p.getUniqueId());
                    give(p, c, won, at);
                });
    }

    private ItemStack icon(Prize prize, Crate c, boolean chance) {
        ItemStack it = prize.icon.clone();
        it.setAmount(Math.max(1, Math.min(64, prize.icon.getAmount())));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(prize.name));
        List<Component> lore = new ArrayList<>();
        if (chance) {
            double pct = prize.weight / c.totalWeight * 100;
            lore.add(rawMsg("preview-chance", "chance", pct >= 1 ? String.format(Locale.ROOT, "%.1f", pct)
                    : String.format(Locale.ROOT, "%.2f", pct)).decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                    net.kyori.adventure.text.format.TextDecoration.State.FALSE));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private void give(Player p, Crate c, Prize prize, Location at) {
        if (!p.isOnline()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        EconomyService eco = service(EconomyService.class);
        if (prize.doubloons > 0 && eco != null) {
            eco.deposit(p.getUniqueId(), prize.doubloons);
            parts.add(eco.format(prize.doubloons));
        }
        LevelService levels = service(LevelService.class);
        if (prize.xp > 0 && levels != null) {
            levels.addXp(p.getUniqueId(), prize.xp, "crate");
            parts.add(prize.xp + " XP");
        }
        for (String spec : prize.items) {
            ItemStack it = ItemSpecs.parse(spec);
            if (it == null) {
                logger().warning("Crate " + c.id + ": prize item not understood: " + spec);
                continue;
            }
            parts.add(ItemSpecs.describe(it));
            p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        for (String cmd : prize.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("<player>", p.getName()));
        }
        send(p, "won", "prize", Text.mm(prize.name), "crate", Text.mm(c.name),
                "details", parts.isEmpty() ? "" : String.join(", ", parts));
        Location fx = at.clone().add(0.5, 1.2, 0.5);
        if (fx.getWorld() != null) {
            fx.getWorld().spawnParticle(prize.broadcast ? Particle.TOTEM_OF_UNDYING : Particle.HAPPY_VILLAGER, fx, prize.broadcast ? 60 : 20,
                    0.3, 0.4, 0.3, prize.broadcast ? 0.3 : 0);
            fx.getWorld().spawnParticle(Particle.FIREWORK, fx, 25, 0.2, 0.2, 0.2, 0.12);
        }
        if (prize.broadcast) {
            Bukkit.broadcast(msg("broadcast", "player", p.getName(), "prize", Text.mm(prize.name), "crate", Text.mm(c.name)));
            for (Player other : Bukkit.getOnlinePlayers()) {
                other.playSound(other.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.3f);
            }
        }
    }

    private void preview(Player p, Crate c) {
        Preview holder = new Preview(c.id);
        int rows = Math.min(6, 1 + (c.prizes.size() + 8) / 9);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, rawMsg("preview-title", "crate", Text.mm(c.name)));
        holder.inventory = inv;
        for (int i = 0; i < c.prizes.size() && i < (rows - 1) * 9; i++) {
            inv.setItem(i, icon(c.prizes.get(i), c, true));
        }
        ItemStack info = key(c.id, 1);
        ItemMeta meta = info.getItemMeta();
        meta.lore(List.of(Text.item(config().getString("messages.preview-keys", "<gray>Your keys: <white><keys>"), "keys", countKeys(p, c.id)),
                Text.item(config().getString("messages.preview-how", "<gray>Right-click the crate with a key to open it."))));
        info.setItemMeta(meta);
        inv.setItem(rows * 9 - 5, info);
        p.openInventory(inv);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blocks.containsKey(key(event.getBlock()))) {
            event.setCancelled(true);
            send(event.getPlayer(), "protected");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> blocks.containsKey(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> blocks.containsKey(key(b)));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder h = event.getInventory().getHolder();
        if (h instanceof Preview || (h instanceof Reel r && r.owner == this)) {
            event.setCancelled(true);
        } else if (h instanceof Overview o) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot >= 0 && slot < o.slots.size() && event.getWhoClicked() instanceof Player p) {
                preview(p, crates.get(o.slots.get(slot)));
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder h = event.getInventory().getHolder();
        if (h instanceof Preview || h instanceof Overview || h instanceof Reel) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Reel r && r.owner == this && !r.done()) {
            r.finish(); // closed early: skip to the prize
        }
    }

    // ------------------------------------------------------------------ holograms and particles

    private void tick() {
        ticks++;
        Set<String> wanted = new java.util.HashSet<>();
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            Location l = location(e.getKey());
            Crate c = crates.get(e.getValue());
            if (l == null || c == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) {
                continue;
            }
            boolean near = false;
            for (Player p : l.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(l) < 40 * 40) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                continue;
            }
            Location mid = l.clone().add(0.5, 1.0, 0.5);
            String particle = config().getString("crates." + c.id + ".particle", "END_ROD");
            try {
                if (!particle.equalsIgnoreCase("none")) {
                    mid.getWorld().spawnParticle(Particle.valueOf(particle.toUpperCase(Locale.ROOT)), mid.clone().add(0, 0.2, 0), 2, 0.3, 0.2, 0.3, 0.01);
                }
            } catch (IllegalArgumentException ignored) {
            }
            if (!config().getBoolean("holograms", true)) {
                continue;
            }
            wanted.add(e.getKey());
            UUID id = holograms.get(e.getKey());
            Entity ent = id == null ? null : Bukkit.getEntity(id);
            if (ent == null || !ent.isValid()) {
                Location at = l.clone().add(0.5, 1.0 + config().getDouble("hologram-height", 0.9), 0.5);
                TextDisplay d = at.getWorld().spawn(at, TextDisplay.class, t -> {
                    t.setPersistent(false);
                    t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                    t.setShadowed(true);
                    t.getPersistentDataContainer().set(holoTag, PersistentDataType.BYTE, (byte) 1);
                });
                holograms.put(e.getKey(), d.getUniqueId());
                ent = d;
            }
            if (ent instanceof TextDisplay d && (ticks % 5 == 1 || d.text().equals(Component.empty()))) {
                List<String> lines = config().getStringList("hologram");
                Component text = Component.empty();
                for (int i = 0; i < lines.size(); i++) {
                    text = text.append(Text.mm(lines.get(i), "crate", Text.mm(c.name))).append(i < lines.size() - 1 ? Component.newline() : Component.empty());
                }
                d.text(text);
            }
        }
        holograms.entrySet().removeIf(e -> {
            if (wanted.contains(e.getKey())) {
                return false;
            }
            Entity ent = Bukkit.getEntity(e.getValue());
            if (ent != null) {
                ent.remove();
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        AquaticCommand cmd = new AquaticCommand("crate", "Crates: see what is inside, open them with keys", "crates");
        cmd.defaultExecutor((s, a) -> overview(AquaticCommand.player(s)));
        cmd.sub("preview", (s, a) -> {
            AquaticCommand.need(a, 1, "/crate preview <crate>");
            preview(AquaticCommand.player(s), crate(a[0]));
        }).usage("<crate>").description("See every prize of a crate and its chance").playerOnly()
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(new ArrayList<>(crates.keySet()), a[0]) : List.of());
        cmd.sub("set", (s, a) -> {
            AquaticCommand.need(a, 1, "/crate set <crate>");
            Player p = AquaticCommand.player(s);
            Crate c = crate(a[0]);
            Block b = p.getTargetBlockExact(6);
            if (b == null || b.getType().isAir()) {
                throw new CommandFail(rawMsg("look-at-block"));
            }
            blocks.put(key(b), c.id);
            saveBlocks();
            send(p, "set", "crate", Text.mm(c.name), "block", ItemSpecs.pretty(b.getType()));
        }).usage("<crate>").description("Make the block you look at a crate (any block: a chest in a build, a barrel ...)")
                .permission("aquatic.crates.admin").playerOnly().hidden()
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(new ArrayList<>(crates.keySet()), a[0]) : List.of());
        cmd.sub("remove", (s, a) -> {
            Player p = AquaticCommand.player(s);
            Block b = p.getTargetBlockExact(6);
            if (b == null || blocks.remove(key(b)) == null) {
                throw new CommandFail(rawMsg("not-a-crate"));
            }
            saveBlocks();
            UUID holo = holograms.remove(key(b));
            Entity e = holo == null ? null : Bukkit.getEntity(holo);
            if (e != null) {
                e.remove();
            }
            send(p, "removed");
        }).description("Stop the block you look at being a crate").permission("aquatic.crates.admin").playerOnly().hidden();
        cmd.sub("list", (s, a) -> {
            send(s, "list-header", "count", blocks.size());
            blocks.forEach((k, v) -> s.sendMessage(rawMsg("list-line", "crate", Text.mm(crates.containsKey(v) ? crates.get(v).name : v),
                    "where", k.replace(";", " "))));
        }).description("All crate blocks").permission("aquatic.crates.admin").hidden();
        cmd.sub("give", (s, a) -> {
            AquaticCommand.need(a, 2, "/crate give <player> <crate> [amount]");
            Player target = AquaticCommand.onlinePlayer(a[0]);
            Crate c = crate(a[1]);
            int amount = a.length > 2 ? (int) AquaticCommand.number(a[2]) : 1;
            giveKeys(target, c, amount);
            send(s, "gave", "player", target.getName(), "amount", amount, "crate", Text.mm(c.name));
        }).usage("<player> <crate> [amount]").description("Give crate keys").permission("aquatic.crates.admin").hidden()
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), a[0])
                        : a.length == 2 ? AquaticCommand.filter(new ArrayList<>(crates.keySet()), a[1]) : List.of());
        cmd.sub("giveall", (s, a) -> {
            AquaticCommand.need(a, 1, "/crate giveall <crate> [amount]");
            Crate c = crate(a[0]);
            int amount = a.length > 1 ? (int) AquaticCommand.number(a[1]) : 1;
            for (Player p : Bukkit.getOnlinePlayers()) {
                giveKeys(p, c, amount);
            }
            Bukkit.broadcast(msg("giveall", "amount", amount, "crate", Text.mm(c.name)));
        }).usage("<crate> [amount]").description("Give every online player crate keys").permission("aquatic.crates.admin").hidden()
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(new ArrayList<>(crates.keySet()), a[0]) : List.of());
        cmd.sub("setup", (s, a) -> setup(AquaticCommand.player(s)))
                .description("Put the default crate stands at the capital (setup-spots in crates.yml)")
                .permission("aquatic.crates.admin").playerOnly().hidden();
        registerCommand(cmd);
    }

    private void giveKeys(Player p, Crate c, int amount) {
        int left = Math.max(1, Math.min(2304, amount));
        while (left > 0) {
            int n = Math.min(64, left);
            p.getInventory().addItem(key(c.id, n)).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
            left -= n;
        }
        send(p, "got-keys", "amount", amount, "crate", Text.mm(c.name));
    }

    private void overview(Player p) {
        Overview holder = new Overview();
        int rows = Math.max(1, Math.min(6, (crates.size() + 8) / 9));
        Inventory inv = Bukkit.createInventory(holder, rows * 9, rawMsg("overview-title"));
        holder.inventory = inv;
        int i = 0;
        for (Crate c : crates.values()) {
            if (i >= rows * 9) {
                break;
            }
            ItemStack it = key(c.id, 1);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Text.item(c.name));
            meta.lore(List.of(Text.item(config().getString("messages.preview-keys", "<gray>Your keys: <white><keys>"), "keys", countKeys(p, c.id)),
                    Text.item(config().getString("messages.overview-click", "<yellow>Click to see the prizes"))));
            it.setItemMeta(meta);
            inv.setItem(i++, it);
            holder.slots.add(c.id);
        }
        p.openInventory(inv);
    }

    /** Puts a block for every setup-spot at the capital and makes it a crate. */
    private void setup(Player p) {
        IslandService islands = service(IslandService.class);
        Island capital = null;
        if (islands != null) {
            Island here = islands.islandAt(p.getLocation());   // the capital you stand on, else the first one
            if (here != null && here.type() == IslandType.CAPITAL) {
                capital = here;
            }
        }
        if (islands != null && capital == null) {
            for (Island is : islands.islands()) {
                if (is.type() == IslandType.CAPITAL && is.center() != null) {
                    capital = is;
                    break;
                }
            }
        }
        if (capital == null) {
            throw new CommandFail(rawMsg("setup-no-capital"));
        }
        World w = capital.center().getWorld();
        int placed = 0;
        for (Map<?, ?> raw : config().getMapList("setup-spots")) {
            Crate c = crates.get(String.valueOf(raw.get("crate")).toLowerCase(Locale.ROOT));
            List<?> off = raw.get("offset") instanceof List<?> l ? l : List.of();
            if (c == null || off.size() < 2) {
                continue;
            }
            int x = capital.center().getBlockX() + ((Number) off.get(0)).intValue();
            int z = capital.center().getBlockZ() + ((Number) off.get(1)).intValue();
            int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            Material m = Material.matchMaterial(String.valueOf(raw.get("block") == null ? "CHEST" : raw.get("block")));
            Block b = w.getBlockAt(x, y, z);
            b.setType(m == null || !m.isBlock() ? Material.CHEST : m, false);
            blocks.put(key(b), c.id);
            placed++;
        }
        saveBlocks();
        send(p, "setup-done", "count", placed);
    }

    @Override
    protected void migrateConfig(YamlConfiguration file, YamlConfiguration defaults, int from) {
        // config-version 1 is the first version of crates.yml
    }
}
