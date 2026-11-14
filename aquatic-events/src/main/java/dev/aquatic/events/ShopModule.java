package dev.aquatic.events;

import dev.aquatic.api.EconomyService;
import dev.aquatic.api.ItemService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Harbor Market: a chest-menu shop with categories (modules/shop.yml).
 * Left-click buys, shift-left buys a stack, right-click sells, shift-right sells everything.
 * Admins edit it in-game with /shop admin (add the item in your hand, change prices, categories).
 */
public final class ShopModule extends AquaticModule implements Listener {

    private static final String ADMIN = "aquatic.shop.admin";
    private static final int PAGE_SIZE = 45;

    /** One thing for sale. */
    record Entry(int index, String spec, String stack, int amount, double buy, double sell) {
    }

    @Override
    public String id() {
        return "shop";
    }

    @Override
    public List<String> requires() {
        return List.of("economy");
    }

    @Override
    protected void onEnable() {
        registerListener(this);
        registerCommands();
    }

    // ------------------------------------------------------------------ data

    private ConfigurationSection categories() {
        return config().getConfigurationSection("categories");
    }

    private Set<String> categoryIds() {
        ConfigurationSection c = categories();
        return c == null ? Set.of() : c.getKeys(false);
    }

    private List<Entry> entries(String category) {
        List<Entry> out = new ArrayList<>();
        List<Map<?, ?>> raw = config().getMapList("categories." + category + ".items");
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> m = raw.get(i);
            out.add(new Entry(i, str(m.get("item")), str(m.get("stack")), num(m.get("amount"), 1).intValue(),
                    num(m.get("buy"), 0).doubleValue(), num(m.get("sell"), 0).doubleValue()));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Number num(Object o, Number def) {
        if (o instanceof Number n) {
            return n;
        }
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** The item an entry sells (one bundle), or null when it can't be made (e.g. items module off). */
    private ItemStack create(Entry e) {
        ItemStack item = null;
        if (isTreasureMap(e)) {
            // only the icon: the real map is made when it is bought (see buyTreasureMap)
            String kind = mapKind(e);
            dev.aquatic.api.TreasureService treasure = service(dev.aquatic.api.TreasureService.class);
            int left = treasure == null ? 0 : treasure.treasureMapsAvailable(kind, null);
            item = new ItemStack(org.bukkit.Material.FILLED_MAP);
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            meta.displayName(Text.item(config().getString("treasure-map.names." + kind, "<gold>Treasure Map")));
            meta.lore(Text.itemLore(config().getStringList("treasure-map.lore"), "left", left));
            item.setItemMeta(meta);
            return item;
        }
        if (e.stack() != null) {
            try {
                item = ItemStack.deserializeBytes(Base64.getDecoder().decode(e.stack()));
            } catch (RuntimeException ignored) {
                return null;
            }
        } else if (e.spec() != null) {
            item = Util.parseItem(this, e.spec());
        }
        if (item != null) {
            item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), e.amount())));
        }
        return item;
    }

    /** True when the stack is the same kind of thing as the entry (for selling). */
    private boolean matches(Entry e, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        ItemService items = service(ItemService.class);
        String custom = items == null ? null : items.idOf(stack);
        if (e.spec() != null && e.spec().toLowerCase(Locale.ROOT).startsWith("item:")) {
            String id = e.spec().split(":")[1];
            return id.equalsIgnoreCase(custom);
        }
        ItemStack template = create(e);
        if (template == null || custom != null) {
            return false;
        }
        if (e.stack() != null) {
            return stack.isSimilar(template);
        }
        return stack.getType() == template.getType() && !stack.hasItemMeta() || stack.getType() == template.getType()
                && !stack.getItemMeta().hasDisplayName() && !stack.getItemMeta().hasEnchants();
    }

    // ------------------------------------------------------------------ menus

    private static final class Menu implements InventoryHolder {
        final String category; // null = main menu
        final int page;
        final List<Entry> shown = new ArrayList<>();
        Inventory inventory;

        Menu(String category, int page) {
            this.category = category;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Opens the market from a shopkeeper villager (players have no /shop command): a category or the main page. */
    void openFor(Player p, String category) {
        if (category != null && categoryIds().contains(category.toLowerCase(Locale.ROOT))) {
            openCategory(p, category.toLowerCase(Locale.ROOT), 0);
        } else {
            openMain(p);
        }
    }

    void openMain(Player p) {
        Menu menu = new Menu(null, 0);
        Inventory inv = Bukkit.createInventory(menu, 54, Text.mm(config().getString("title", "<dark_aqua>Harbor Market")));
        menu.inventory = inv;
        ItemStack filler = icon(Material.BLUE_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
        for (String id : categoryIds()) {
            ConfigurationSection c = categories().getConfigurationSection(id);
            Material m = Material.matchMaterial(c.getString("icon", "CHEST"));
            List<Component> lore = new ArrayList<>();
            for (String line : c.getStringList("description")) {
                lore.add(Text.item(line));
            }
            lore.add(Component.empty());
            lore.add(rawMsg("menu-items", "count", entries(id).size()));
            lore.add(rawMsg("menu-open"));
            int slot = Math.max(0, Math.min(44, c.getInt("slot", 0)));
            inv.setItem(slot, icon(m == null ? Material.CHEST : m, Text.item(c.getString("name", id)), lore));
        }
        inv.setItem(49, balanceIcon(p));
        p.openInventory(inv);
    }

    void openCategory(Player p, String category, int page) {
        List<Entry> all = entries(category);
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(pages - 1, page));
        Menu menu = new Menu(category, page);
        ConfigurationSection c = categories().getConfigurationSection(category);
        Inventory inv = Bukkit.createInventory(menu, 54, Text.mm(c == null ? category : c.getString("name", category)));
        menu.inventory = inv;
        EconomyService eco = service(EconomyService.class);
        for (int i = page * PAGE_SIZE; i < Math.min(all.size(), (page + 1) * PAGE_SIZE); i++) {
            Entry e = all.get(i);
            ItemStack shown = create(e);
            if (shown == null) {
                continue;
            }
            ItemMeta meta = shown.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            if (e.buy() > 0) {
                lore.add(noItalic(rawMsg("item-buy", "price", money(eco, e.buy()), "amount", e.amount())));
            }
            if (e.sell() > 0) {
                lore.add(noItalic(rawMsg("item-sell", "price", money(eco, e.sell()), "amount", e.amount())));
            }
            lore.add(noItalic(rawMsg(e.buy() > 0 && e.sell() > 0 ? "item-hint-both" : e.buy() > 0 ? "item-hint-buy" : "item-hint-sell")));
            meta.lore(lore);
            shown.setItemMeta(meta);
            inv.setItem(i - page * PAGE_SIZE, shown);
            menu.shown.add(e);
        }
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler);
        }
        inv.setItem(45, icon(Material.ARROW, rawMsg("menu-back"), List.of()));
        if (page > 0) {
            inv.setItem(48, icon(Material.PAPER, rawMsg("menu-previous", "page", page), List.of()));
        }
        inv.setItem(49, balanceIcon(p));
        if (page < pages - 1) {
            inv.setItem(50, icon(Material.PAPER, rawMsg("menu-next", "page", page + 2), List.of()));
        }
        p.openInventory(inv);
    }

    private ItemStack balanceIcon(Player p) {
        EconomyService eco = service(EconomyService.class);
        return icon(Material.GOLD_INGOT, rawMsg("menu-balance", "balance", money(eco, eco == null ? 0 : eco.balance(p.getUniqueId()))),
                List.of(rawMsg("menu-balance-hint")));
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static ItemStack icon(Material m, Component name, List<Component> lore) {
        return dev.aquatic.util.Menus.icon(m, name, lore);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (menu.category == null) {
            for (String id : categoryIds()) {
                if (categories().getInt(id + ".slot", -1) == slot) {
                    openCategory(p, id, 0);
                    return;
                }
            }
            return;
        }
        if (slot == 45) {
            openMain(p);
            return;
        }
        if (slot == 48) {
            openCategory(p, menu.category, menu.page - 1);
            return;
        }
        if (slot == 50) {
            openCategory(p, menu.category, menu.page + 1);
            return;
        }
        if (slot >= menu.shown.size()) {
            return;
        }
        Entry e = menu.shown.get(slot);
        try {
            ClickType click = event.getClick();
            if (click.isLeftClick()) {
                buy(p, e, click.isShiftClick());
            } else if (click.isRightClick()) {
                sell(p, e, click.isShiftClick());
            }
        } catch (CommandFail fail) {
            p.sendMessage(plugin().prefix().append(fail.component()));
        }
        event.getInventory().setItem(49, balanceIcon(p));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ trading

    private static boolean isTreasureMap(Entry e) {
        return e.spec() != null && e.spec().toLowerCase(Locale.ROOT).startsWith("treasure-map");
    }

    /** "treasure-map" = standard, "treasure-map:epic", "treasure-map:legendary". */
    private static String mapKind(Entry e) {
        int colon = e.spec().indexOf(':');
        return colon < 0 ? "standard" : e.spec().substring(colon + 1).trim().toLowerCase(Locale.ROOT);
    }

    /** A map to a treasure that already exists; refused (and not charged) when there is none. */
    private void buyTreasureMap(Player p, Entry e) {
        EconomyService eco = service(EconomyService.class);
        dev.aquatic.api.TreasureService treasure = service(dev.aquatic.api.TreasureService.class);
        if (eco == null || !eco.has(p.getUniqueId(), e.buy())) {
            throw new CommandFail(rawMsg("no-money", "price", money(eco, e.buy())));
        }
        ItemStack map = treasure == null ? null : treasure.sellTreasureMap(mapKind(e), p.getUniqueId());
        if (map == null) {
            throw new CommandFail(rawMsg("no-treasure-maps"));
        }
        eco.withdraw(p.getUniqueId(), e.buy());
        Util.give(p, map);
        send(p, "bought", "amount", 1, "item", name(map), "price", money(eco, e.buy()));
    }

    private void buy(Player p, Entry e, boolean stack) {
        if (e.buy() <= 0) {
            throw new CommandFail(rawMsg("not-for-sale"));
        }
        if (isTreasureMap(e)) {
            buyTreasureMap(p, e);
            return;
        }
        ItemStack bundle = create(e);
        if (bundle == null) {
            throw new CommandFail(rawMsg("unavailable"));
        }
        int bundles = stack ? Math.max(1, bundle.getMaxStackSize() / Math.max(1, bundle.getAmount())) : 1;
        double price = e.buy() * bundles;
        EconomyService eco = service(EconomyService.class);
        if (eco == null || !eco.withdraw(p.getUniqueId(), price)) {
            throw new CommandFail(rawMsg("no-money", "price", money(eco, price)));
        }
        for (int i = 0; i < bundles; i++) {
            Util.give(p, create(e));
        }
        send(p, "bought", "amount", bundle.getAmount() * bundles, "item", name(bundle), "price", money(eco, price));
    }

    private void sell(Player p, Entry e, boolean all) {
        if (e.sell() <= 0) {
            throw new CommandFail(rawMsg("not-buying"));
        }
        int per = Math.max(1, e.amount());
        int have = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (matches(e, s)) {
                have += s.getAmount();
            }
        }
        int bundles = all ? have / per : (have >= per ? 1 : 0);
        if (bundles <= 0) {
            throw new CommandFail(rawMsg("nothing-to-sell", "amount", per));
        }
        int toTake = bundles * per;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && toTake > 0; i++) {
            ItemStack s = contents[i];
            if (matches(e, s)) {
                int n = Math.min(toTake, s.getAmount());
                s.setAmount(s.getAmount() - n);
                toTake -= n;
                contents[i] = s.getAmount() <= 0 ? null : s;
            }
        }
        p.getInventory().setStorageContents(contents);
        EconomyService eco = service(EconomyService.class);
        double earned = e.sell() * bundles;
        if (eco != null) {
            eco.deposit(p.getUniqueId(), earned);
        }
        ItemStack sample = create(e);
        send(p, "sold", "amount", bundles * per, "item", sample == null ? Component.text("items") : name(sample), "price", money(eco, earned));
    }

    /** /shop sell: sells the stack in your hand to whichever entry buys it. */
    private void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        for (String cat : categoryIds()) {
            for (Entry e : entries(cat)) {
                if (e.sell() > 0 && matches(e, hand)) {
                    sell(p, e, true);
                    return;
                }
            }
        }
        throw new CommandFail(rawMsg("hand-not-sellable"));
    }

    private static Component name(ItemStack item) {
        return item.getItemMeta() != null && item.getItemMeta().hasDisplayName() ? item.getItemMeta().displayName()
                : Component.translatable(item.getType().translationKey());
    }

    private static String money(EconomyService eco, double amount) {
        return eco != null ? eco.format(amount) : Text.number(amount);
    }

    // ------------------------------------------------------------------ admin editing

    private void addHeld(Player p, String category, double buy, double sell, int amount) {
        if (categories() == null || !categories().isConfigurationSection(category)) {
            throw new CommandFail(rawMsg("unknown-category", "category", category, "categories", String.join(", ", categoryIds())));
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            throw new CommandFail(rawMsg("hold-item"));
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        ItemService items = service(ItemService.class);
        String custom = items == null ? null : items.idOf(hand);
        if (custom != null) {
            entry.put("item", "item:" + custom);
        } else if (!hand.hasItemMeta()) {
            entry.put("item", hand.getType().name());
        } else {
            ItemStack one = hand.asOne();
            entry.put("stack", Base64.getEncoder().encodeToString(one.serializeAsBytes()));
        }
        entry.put("amount", amount > 0 ? amount : hand.getAmount());
        entry.put("buy", buy);
        entry.put("sell", sell);
        List<Map<?, ?>> list = new ArrayList<>(config().getMapList("categories." + category + ".items"));
        list.add(entry);
        config().set("categories." + category + ".items", list);
        saveConfig();
        send(p, "admin-added", "item", name(hand), "category", category, "index", list.size());
    }

    private void editEntry(String category, int index, java.util.function.Consumer<Map<String, Object>> edit, boolean remove) {
        List<Map<?, ?>> list = new ArrayList<>(config().getMapList("categories." + category + ".items"));
        if (index < 1 || index > list.size()) {
            throw new CommandFail(rawMsg("bad-index", "max", list.size()));
        }
        if (remove) {
            list.remove(index - 1);
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) list.get(index - 1));
            edit.accept(copy);
            list.set(index - 1, copy);
        }
        config().set("categories." + category + ".items", list);
        saveConfig();
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        AquaticCommand cmd = new AquaticCommand("shop", "The Harbor Market: buy and sell for Doubloons", "market");
        cmd.defaultExecutor((s, a) -> {
            Player p = AquaticCommand.player(s);
            if (a.length > 0 && categoryIds().contains(a[0].toLowerCase(Locale.ROOT))) {
                openCategory(p, a[0].toLowerCase(Locale.ROOT), 0);
            } else {
                openMain(p);
            }
        }).defaultCompleter((s, a) -> AquaticCommand.filter(categoryIds(), a.length == 0 ? "" : a[0]));
        cmd.sub("sell", (s, a) -> {
                    sellHand(AquaticCommand.player(s));
                })
                .description("Sell everything like the item in your hand").playerOnly().playerPermission("aquatic.shop.sell");

        AquaticCommand.Completer cats = (s, a) -> a.length == 2 ? AquaticCommand.filter(categoryIds(), a[1]) : List.of();
        cmd.sub("admin", (s, a) -> {
            AquaticCommand.need(a, 1, "/shop admin <add|remove|price|category> ...");
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "add" -> {
                    AquaticCommand.need(a, 3, "/shop admin add <category> <buy> [sell] [amount]");
                    addHeld(AquaticCommand.player(s), a[1].toLowerCase(Locale.ROOT), AquaticCommand.number(a[2]),
                            a.length > 3 ? AquaticCommand.number(a[3]) : 0, a.length > 4 ? AquaticCommand.integer(a[4]) : 0);
                }
                case "remove" -> {
                    AquaticCommand.need(a, 3, "/shop admin remove <category> <number>");
                    editEntry(a[1].toLowerCase(Locale.ROOT), AquaticCommand.integer(a[2]), m -> {
                    }, true);
                    send(s, "admin-removed", "index", a[2], "category", a[1]);
                }
                case "price" -> {
                    AquaticCommand.need(a, 5, "/shop admin price <category> <number> <buy> <sell>");
                    double buy = AquaticCommand.number(a[3]), sell = AquaticCommand.number(a[4]);
                    editEntry(a[1].toLowerCase(Locale.ROOT), AquaticCommand.integer(a[2]), m -> {
                        m.put("buy", buy);
                        m.put("sell", sell);
                    }, false);
                    send(s, "admin-price", "index", a[2], "category", a[1]);
                }
                case "list" -> {
                    AquaticCommand.need(a, 2, "/shop admin list <category>");
                    for (Entry e : entries(a[1].toLowerCase(Locale.ROOT))) {
                        s.sendMessage(rawMsg("admin-list-entry", "index", e.index() + 1,
                                "item", e.spec() != null ? e.spec() : "(saved item)", "amount", e.amount(), "buy", e.buy(), "sell", e.sell()));
                    }
                }
                case "category" -> {
                    AquaticCommand.need(a, 3, "/shop admin category <create|delete> <id> [icon] [slot]");
                    String id = a[2].toLowerCase(Locale.ROOT);
                    if (a[1].equalsIgnoreCase("create")) {
                        config().set("categories." + id + ".name", "<white>" + id);
                        config().set("categories." + id + ".icon", a.length > 3 ? a[3].toUpperCase(Locale.ROOT) : "CHEST");
                        config().set("categories." + id + ".slot", a.length > 4 ? AquaticCommand.integer(a[4]) : categoryIds().size() + 10);
                        config().set("categories." + id + ".items", new ArrayList<>());
                    } else {
                        config().set("categories." + id, null);
                    }
                    saveConfig();
                    send(s, "admin-category", "category", id);
                }
                default -> throw new CommandFail("<red>Usage: <white>/shop admin <add|remove|price|list|category> ...");
            }
        }).usage("<add|remove|price|list|category> ...").description("Edit the shop in-game (hold the item to add)")
                .permission(ADMIN)
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(List.of("add", "remove", "price", "list", "category"), a[0])
                        : a.length == 2 && a[0].equalsIgnoreCase("category") ? AquaticCommand.filter(List.of("create", "delete"), a[1])
                        : cats.complete(s, a));
        if (!config().getBoolean("command.players-can-open", false)) {
            // the market is opened by talking to the shopkeepers; /shop is for staff only
            cmd.permission(ADMIN);
        }
        registerCommand(cmd);
    }

    @Override
    protected void migrateConfig(org.bukkit.configuration.file.YamlConfiguration file,
                                 org.bukkit.configuration.file.YamlConfiguration defaults, int from) {
        if (from < 1) {
            // 2.4.0: building block categories
            addMissing(file, defaults, "categories.masonry");
            addMissing(file, defaults, "categories.decor");
        }
        if (from < 6) {
            // 2.27.0: the Steel Cutlass and the Boarding Axe in the Pirate Armory
            List<Map<?, ?>> items = new ArrayList<>(file.getMapList("categories.pirate.items"));
            if (file.contains("categories.pirate.items") && items.stream().noneMatch(e -> String.valueOf(e.get("item")).equals("item:steel_cutlass"))) {
                items.add(0, Map.of("item", "item:boarding_axe", "amount", 1, "buy", 750, "sell", 0));
                items.add(0, Map.of("item", "item:steel_cutlass", "amount", 1, "buy", 600, "sell", 0));
                file.set("categories.pirate.items", items);
            }
        }
        if (from < 5) {
            // 2.12.0: the shop is opened at the shopkeepers at spawn, not with /shop
            addMissing(file, defaults, "command");
            addMissing(file, defaults, "messages.use-shopkeepers");
        }
        if (from < 2) {
            // 2.5.1: treasure maps for sale (only to treasures that already exist)
            List<Map<?, ?>> items = new ArrayList<>(file.getMapList("categories.sailing.items"));
            boolean has = items.stream().anyMatch(m -> String.valueOf(m.get("item")).startsWith("treasure-map"));
            if (file.contains("categories.sailing", true) && !has) {
                String[][] maps = {{"treasure-map", "400"}, {"treasure-map:epic", "1500"}, {"treasure-map:legendary", "4000"}};
                for (String[] m : maps) {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("item", m[0]);
                    entry.put("amount", 1);
                    entry.put("buy", Integer.parseInt(m[1]));
                    entry.put("sell", 0);
                    items.add(entry);
                }
                file.set("categories.sailing.items", items);
            }
        }
        if (from < 4) {
            // 2.10.0: the Musket in the Pirate Armory
            List<Map<?, ?>> items = new ArrayList<>(file.getMapList("categories.pirate.items"));
            boolean has = items.stream().anyMatch(m -> "item:musket".equals(String.valueOf(m.get("item"))));
            if (file.contains("categories.pirate", true) && !has) {
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("item", "item:musket");
                entry.put("amount", 1);
                entry.put("buy", 4500);
                entry.put("sell", 0);
                items.add(entry);
                file.set("categories.pirate.items", items);
            }
        }
        if (from < 3) {
            // 2.7.0: the Seafoam Milkshake in the Pirate Armory
            List<Map<?, ?>> items = new ArrayList<>(file.getMapList("categories.pirate.items"));
            boolean has = items.stream().anyMatch(m -> "item:seafoam_milkshake".equals(String.valueOf(m.get("item"))));
            if (file.contains("categories.pirate", true) && !has) {
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("item", "item:seafoam_milkshake");
                entry.put("amount", 1);
                entry.put("buy", 3000);
                entry.put("sell", 0);
                items.add(entry);
                file.set("categories.pirate.items", items);
            }
        }
    }
}
