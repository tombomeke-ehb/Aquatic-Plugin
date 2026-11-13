package dev.aquatic.crews;

import dev.aquatic.api.CrewRank;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.IslandService;
import dev.aquatic.util.Menus;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The crew menu (/crew): everything about your crew by clicking. Pages: the overview (members, bank, home,
 * islands, diplomacy, chat, flag, invites, leave), a page per member (promote, demote, set a rank, make captain,
 * kick), the bank (deposit and withdraw), inviting from a list of players, diplomacy with every other crew, and a
 * confirmation page for anything that can't be undone. Without a crew: found one, browse crews, accept invites.
 * The buttons run the normal crew commands, so all the usual rules and messages apply.
 */
final class CrewMenu implements Listener {

    private static final class Menu implements InventoryHolder {
        final String page;
        final String target;
        Inventory inventory;
        final Map<Integer, String> actions = new HashMap<>();

        Menu(String page, String target) {
            this.page = page;
            this.target = target;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private final CrewsModule module;

    CrewMenu(CrewsModule module) {
        this.module = module;
    }

    // ------------------------------------------------------------------ pages

    void open(Player p) {
        open(p, "main", null);
    }

    private void open(Player p, String page, String target) {
        CrewImpl crew = module.byPlayer.get(p.getUniqueId());
        if (crew == null && !page.equals("browse")) {
            page = "none";
        }
        Menu menu = new Menu(page, target);
        Component title = switch (page) {
            case "none" -> module.rawMsg("menu-title-none");
            case "member" -> module.rawMsg("gui-member-title", "player", target == null ? "?" : target);
            case "bank" -> module.rawMsg("gui-bank-title");
            case "invite" -> module.rawMsg("gui-invite-title");
            case "diplomacy" -> module.rawMsg("gui-diplomacy-title");
            case "crew" -> module.rawMsg("gui-crew-title", "crew", other(target) == null ? Component.text("?") : other(target).displayName());
            case "browse" -> module.rawMsg("gui-browse-title");
            case "confirm" -> module.rawMsg("gui-confirm-title");
            default -> module.rawMsg("menu-title", "crew", crew.displayName());
        };
        Inventory inv = Bukkit.createInventory(menu, 54, title);
        menu.inventory = inv;
        ItemStack pane = Menus.filler(Material.BLUE_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, pane);
        }
        switch (page) {
            case "none" -> fillNoCrew(p, menu);
            case "member" -> fillMember(p, crew, menu);
            case "bank" -> fillBank(p, crew, menu);
            case "invite" -> fillInvite(p, menu);
            case "diplomacy" -> fillDiplomacy(crew, menu);
            case "crew" -> fillCrewRelation(crew, menu);
            case "browse" -> fillBrowse(menu);
            case "confirm" -> fillConfirm(menu);
            default -> fillCrew(p, crew, menu);
        }
        if (!page.equals("main") && !page.equals("none")) {
            back(menu, page.equals("crew") ? "page:diplomacy" : crew == null ? "page:none" : "page:main");
        }
        p.openInventory(inv);
    }

    private void back(Menu menu, String action) {
        menu.inventory.setItem(49, item(Material.ARROW, module.rawMsg("gui-back"), List.of()));
        menu.actions.put(49, action);
    }

    private void button(Menu menu, int slot, ItemStack icon, String action) {
        menu.inventory.setItem(slot, icon);
        if (action != null) {
            menu.actions.put(slot, action);
        }
    }

    private void fillNoCrew(Player p, Menu menu) {
        button(menu, 20, item(Material.WRITABLE_BOOK, module.rawMsg("menu-create"), List.of(module.rawMsg("menu-create-lore"))),
                "suggest:/crew create ");
        button(menu, 22, item(Material.COMPASS, module.rawMsg("menu-browse"), List.of(module.rawMsg("menu-browse-lore"))), "page:browse");
        Map<String, Long> invites = module.invitesOf(p.getUniqueId());
        if (invites == null || invites.isEmpty()) {
            button(menu, 24, item(Material.PAPER, module.rawMsg("menu-invites"), List.of(module.rawMsg("menu-invites-none"))), null);
            return;
        }
        int[] slots = {24, 25, 33, 34, 42, 43};
        int i = 0;
        for (String id : invites.keySet()) {
            CrewImpl c = module.crews.get(id);
            if (c == null || i >= slots.length) {
                continue;
            }
            ItemStack flag = c.flag() == null ? new ItemStack(Material.WHITE_BANNER) : c.flag().clone();
            button(menu, slots[i++], item(flag, module.rawMsg("menu-invites-line", "crew", c.displayName()),
                    List.of(module.rawMsg("gui-invite-accept-lore"))), "cmd:crew accept " + c.name);
        }
    }

    private void fillCrew(Player p, CrewImpl crew, Menu menu) {
        Inventory inv = menu.inventory;
        EconomyService eco = module.service(EconomyService.class);
        CrewRank myRank = crew.rankOf(p.getUniqueId());
        boolean officer = myRank != null && myRank.isOfficer();
        List<Component> info = new ArrayList<>();
        info.add(module.rawMsg("menu-info-tag", "tag", crew.tag()));
        OfflinePlayer cap = Bukkit.getOfflinePlayer(crew.captain());
        info.add(module.rawMsg("menu-info-captain", "player", cap.getName() == null ? "?" : cap.getName()));
        info.add(module.rawMsg("menu-info-members", "online", crew.onlineMembers().size(), "total", crew.members().size()));
        info.add(module.rawMsg("menu-info-reputation", "reputation", crew.reputation()));
        info.add(module.rawMsg("menu-info-bank", "amount", money(eco, crew.bankBalance())));
        IslandService islands = module.service(IslandService.class);
        int owned = islands == null ? 0 : islands.islandsOwnedBy(crew.id()).size();
        info.add(module.rawMsg("menu-info-islands", "count", owned));
        info.add(module.rawMsg("menu-info-rank", "rank", myRank == null ? "-" : myRank.display()));
        ItemStack flag = crew.flag();
        inv.setItem(4, item(flag == null ? new ItemStack(Material.WHITE_BANNER) : flag.clone(), crew.displayName(), info));
        // members (captain first, then by rank, online first): officers click one to manage them
        List<UUID> members = new ArrayList<>(crew.members());
        members.sort(Comparator.<UUID>comparingInt(u -> -weight(crew.rankOf(u)))
                .thenComparing(u -> Bukkit.getPlayer(u) == null)
                .thenComparing(u -> crew.names.getOrDefault(u, "")));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < members.size() && i < slots.length; i++) {
            UUID u = members.get(i);
            CrewRank r = crew.rankOf(u);
            String name = nameOf(crew, u);
            List<Component> lore = new ArrayList<>();
            lore.add(module.rawMsg("menu-member-rank", "rank", r == null ? "-" : r.display()));
            lore.add(module.rawMsg(Bukkit.getPlayer(u) != null ? "menu-member-online" : "menu-member-offline"));
            String action = null;
            if (officer && !u.equals(p.getUniqueId()) && r != CrewRank.CAPTAIN) {
                lore.add(Component.empty());
                lore.add(module.rawMsg("gui-member-click"));
                action = "page:member:" + name;
            }
            button(menu, slots[i], item(head(u), Text.mm("<white>" + name), lore), action);
        }
        // bottom rows
        button(menu, 45, item(Material.GOLD_INGOT, module.rawMsg("menu-bank"), List.of(
                module.rawMsg("menu-bank-balance", "amount", money(eco, crew.bankBalance())), Component.empty(), module.rawMsg("gui-open"))), "page:bank");
        button(menu, 46, item(Material.RED_BED, module.rawMsg("menu-home"), List.of(module.rawMsg("menu-home-lore"),
                officer ? module.rawMsg("gui-sethome-lore") : Component.empty())), "home");
        button(menu, 47, item(Material.OAK_BOAT, module.rawMsg("menu-islands"), List.of(module.rawMsg("menu-islands-lore", "count", owned))),
                "close:island home");
        List<Component> diplo = new ArrayList<>();
        diplo.add(module.rawMsg("menu-allies", "list", names(crew.allies)));
        diplo.add(module.rawMsg("menu-rivals", "list", names(crew.rivals)));
        diplo.add(module.rawMsg("menu-wars", "list", names(crew.wars.keySet())));
        diplo.add(Component.empty());
        diplo.add(module.rawMsg("gui-open"));
        button(menu, 48, item(Material.SHIELD, module.rawMsg("menu-diplomacy"), diplo), "page:diplomacy");
        button(menu, 49, item(Material.WHITE_BANNER, module.rawMsg("gui-flag"), List.of(module.rawMsg("gui-flag-lore"))), "close:crew flag");
        button(menu, 50, item(Material.OAK_SIGN, module.rawMsg("menu-chat"), List.of(module.rawMsg(
                module.crewChat.contains(p.getUniqueId()) ? "menu-chat-on" : "menu-chat-off"), module.rawMsg("menu-chat-lore"))), "cmd:crew chat");
        if (officer) {
            button(menu, 51, item(Material.WRITABLE_BOOK, module.rawMsg("menu-invite"), List.of(module.rawMsg("gui-invite-lore"))), "page:invite");
            button(menu, 52, item(Material.IRON_SWORD, module.rawMsg("gui-friendlyfire"), List.of(module.rawMsg("gui-friendlyfire-lore"))),
                    "cmd:crew friendlyfire");
        }
        if (myRank == CrewRank.CAPTAIN) {
            button(menu, 53, item(Material.TNT, module.rawMsg("gui-disband"), List.of(module.rawMsg("gui-disband-lore"))),
                    "confirm:crew disband;crew disband confirm|" + Text.plain(module.rawMsg("gui-disband")));
        } else {
            button(menu, 53, item(Material.BARRIER, module.rawMsg("menu-leave"), List.of(module.rawMsg("gui-leave-lore"))),
                    "confirm:crew leave|" + Text.plain(module.rawMsg("menu-leave")));
        }
    }

    /** One member: promote, demote, any rank, make captain (captain only), kick. */
    private void fillMember(Player p, CrewImpl crew, Menu menu) {
        String name = menu.target;
        UUID u = null;
        for (UUID m : crew.members()) {
            if (nameOf(crew, m).equalsIgnoreCase(name)) {
                u = m;
            }
        }
        if (u == null) {
            return;
        }
        CrewRank r = crew.rankOf(u);
        button(menu, 4, item(head(u), Text.mm("<white>" + name), List.of(module.rawMsg("menu-member-rank", "rank", r == null ? "-" : r.display()))), null);
        button(menu, 20, item(Material.LIME_DYE, module.rawMsg("gui-promote"), List.of()), "cmd:crew promote " + name);
        button(menu, 24, item(Material.ORANGE_DYE, module.rawMsg("gui-demote"), List.of()), "cmd:crew demote " + name);
        int slot = 29;
        for (CrewRank rank : CrewRank.values()) {
            if (rank == CrewRank.CAPTAIN) {
                continue;
            }
            button(menu, slot++, item(rank == r ? Material.ENCHANTED_BOOK : Material.BOOK, module.rawMsg("gui-setrank", "rank", rank.display()),
                    List.of(module.rawMsg(rank == r ? "gui-current" : "gui-click"))), "cmd:crew setrank " + name + " " + rank.name().toLowerCase());
        }
        if (crew.rankOf(p.getUniqueId()) == CrewRank.CAPTAIN) {
            button(menu, 40, item(Material.GOLDEN_HELMET, module.rawMsg("gui-transfer"), List.of(module.rawMsg("gui-transfer-lore"))),
                    "confirm:crew transfer " + name + "|" + Text.plain(module.rawMsg("gui-transfer")));
        }
        button(menu, 44, item(Material.BARRIER, module.rawMsg("gui-kick"), List.of()),
                "confirm:crew kick " + name + "|" + Text.plain(module.rawMsg("gui-kick")) + " " + name);
    }

    private void fillBank(Player p, CrewImpl crew, Menu menu) {
        EconomyService eco = module.service(EconomyService.class);
        button(menu, 4, item(Material.GOLD_BLOCK, module.rawMsg("menu-bank"), List.of(module.rawMsg("menu-bank-balance", "amount", money(eco, crew.bankBalance())),
                module.rawMsg("gui-purse", "amount", eco == null ? "-" : money(eco, eco.balance(p.getUniqueId()))))), null);
        int[] amounts = {100, 1000, 10000};
        Material[] icons = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK};
        for (int i = 0; i < amounts.length; i++) {
            String amt = money(eco, amounts[i]);
            button(menu, 19 + i, item(icons[i], module.rawMsg("gui-deposit", "amount", amt), List.of()), "cmd:crew bank deposit " + amounts[i]);
            button(menu, 23 + i, item(icons[i], module.rawMsg("gui-withdraw", "amount", amt), List.of()), "cmd:crew bank withdraw " + amounts[i]);
        }
        if (eco != null) {
            long all = (long) Math.floor(eco.balance(p.getUniqueId()));
            if (all > 0) {
                button(menu, 31, item(Material.CHEST, module.rawMsg("gui-deposit-all", "amount", money(eco, all)), List.of()), "cmd:crew bank deposit " + all);
            }
        }
    }

    private void fillInvite(Player p, Menu menu) {
        int i = 0;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p) || module.byPlayer.containsKey(other.getUniqueId()) || i >= GRID.length) {
                continue;
            }
            button(menu, GRID[i++], item(head(other.getUniqueId()), Text.mm("<white>" + other.getName()),
                    List.of(module.rawMsg("gui-invite-player-lore"))), "cmd:crew invite " + other.getName());
        }
        if (i == 0) {
            button(menu, 22, item(Material.BARRIER, module.rawMsg("gui-invite-nobody"), List.of()), null);
        }
    }

    private String relation(CrewImpl crew, CrewImpl other) {
        if (crew.wars.containsKey(other.id())) {
            return "war";
        }
        if (crew.allies.contains(other.id())) {
            return "ally";
        }
        if (crew.rivals.contains(other.id())) {
            return "rival";
        }
        return "neutral";
    }

    private void fillDiplomacy(CrewImpl crew, Menu menu) {
        int i = 0;
        for (CrewImpl other : module.crews.values()) {
            if (other == crew || i >= GRID.length) {
                continue;
            }
            ItemStack flag = other.flag() == null ? new ItemStack(Material.WHITE_BANNER) : other.flag().clone();
            button(menu, GRID[i++], item(flag, other.displayName(), List.of(
                    module.rawMsg("gui-relation", "relation", module.rawMsg("gui-relation-" + relation(crew, other))),
                    module.rawMsg("menu-info-members", "online", other.onlineMembers().size(), "total", other.members().size()),
                    Component.empty(), module.rawMsg("gui-crew-click"))), "page:crew:" + other.id());
        }
        if (i == 0) {
            button(menu, 22, item(Material.BARRIER, module.rawMsg("gui-no-crews"), List.of()), null);
        }
    }

    private CrewImpl other(String id) {
        return id == null ? null : module.crews.get(id);
    }

    private void fillCrewRelation(CrewImpl crew, Menu menu) {
        CrewImpl other = other(menu.target);
        if (other == null) {
            return;
        }
        String rel = relation(crew, other);
        ItemStack flag = other.flag() == null ? new ItemStack(Material.WHITE_BANNER) : other.flag().clone();
        button(menu, 4, item(flag, other.displayName(), List.of(module.rawMsg("gui-relation", "relation", module.rawMsg("gui-relation-" + rel)))), null);
        String n = other.name;
        button(menu, 20, item(Material.EMERALD, module.rawMsg("gui-ally"), List.of(module.rawMsg("gui-ally-lore"))), "cmd:crew ally " + n);
        button(menu, 21, item(Material.PAPER, module.rawMsg("gui-neutral"), List.of(module.rawMsg("gui-neutral-lore"))), "cmd:crew neutral " + n);
        button(menu, 22, item(Material.ORANGE_BANNER, module.rawMsg("gui-rival"), List.of(module.rawMsg("gui-rival-lore"))), "cmd:crew rival " + n);
        button(menu, 23, item(Material.NETHERITE_SWORD, module.rawMsg("gui-war"), List.of(module.rawMsg("gui-war-lore"))),
                "confirm:crew war " + n + "|" + Text.plain(module.rawMsg("gui-war")));
        button(menu, 24, item(Material.WHITE_BANNER, module.rawMsg("gui-peace"), List.of(module.rawMsg("gui-peace-lore"))), "cmd:crew peace " + n);
    }

    private void fillBrowse(Menu menu) {
        List<CrewImpl> all = new ArrayList<>(module.crews.values());
        all.sort(Comparator.comparingInt(c -> -c.reputation()));
        for (int i = 0; i < all.size() && i < GRID.length; i++) {
            CrewImpl c = all.get(i);
            OfflinePlayer cap = Bukkit.getOfflinePlayer(c.captain());
            ItemStack flag = c.flag() == null ? new ItemStack(Material.WHITE_BANNER) : c.flag().clone();
            button(menu, GRID[i], item(flag, c.displayName(), List.of(
                    module.rawMsg("menu-info-captain", "player", cap.getName() == null ? "?" : cap.getName()),
                    module.rawMsg("menu-info-members", "online", c.onlineMembers().size(), "total", c.members().size()),
                    module.rawMsg("menu-info-reputation", "reputation", c.reputation()))), null);
        }
    }

    /** target = "<command>|<what it does>". */
    private void fillConfirm(Menu menu) {
        String[] parts = menu.target == null ? new String[]{"", ""} : menu.target.split("\\|", 2);
        String what = parts.length > 1 ? parts[1] : parts[0];
        button(menu, 20, item(Material.LIME_CONCRETE, module.rawMsg("gui-confirm-yes", "what", what), List.of()), "close:" + parts[0]);
        button(menu, 24, item(Material.RED_CONCRETE, module.rawMsg("gui-confirm-no"), List.of()), "page:main");
    }

    // ------------------------------------------------------------------ helpers

    private ItemStack head(UUID u) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        if (skull.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(u));
            skull.setItemMeta(sm);
        }
        return skull;
    }

    private static String nameOf(CrewImpl crew, UUID u) {
        String n = crew.names.get(u);
        if (n != null) {
            return n;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() == null ? "?" : op.getName();
    }

    private static int weight(CrewRank r) {
        return r == null ? 0 : r.ordinal() == 0 ? 10 : 5 - r.ordinal();
    }

    private String names(java.util.Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            CrewImpl c = module.crews.get(id);
            if (c != null) {
                out.add(c.name());
            }
        }
        return out.isEmpty() ? "-" : String.join(", ", out);
    }

    private static String money(EconomyService eco, double v) {
        return eco == null ? Text.number(v) : eco.format(v);
    }

    private static ItemStack item(Material m, Component name, List<Component> lore) {
        return Menus.icon(m, name, lore);
    }

    private static ItemStack item(ItemStack it, Component name, List<Component> lore) {
        return Menus.icon(it, name, lore);
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu) || !(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        String action = menu.actions.get(event.getSlot());
        if (action == null) {
            return;
        }
        if (action.startsWith("page:")) {
            String[] parts = action.substring(5).split(":", 2);
            open(p, parts[0], parts.length > 1 ? parts[1] : null);
        } else if (action.startsWith("confirm:")) {
            open(p, "confirm", action.substring(8));
        } else if (action.startsWith("suggest:")) {
            p.closeInventory();
            String cmd = action.substring(8);
            p.sendMessage(module.rawMsg("menu-suggest", "command", cmd.trim())
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(cmd)));
        } else if (action.startsWith("close:")) {
            p.closeInventory();
            for (String cmd : action.substring(6).split(";")) {
                p.performCommand(cmd.trim()); // e.g. disband asks first, then "disband confirm"
            }
        } else if (action.equals("home")) {
            // left: sail home; right (officers): set the crew home here
            p.closeInventory();
            p.performCommand(event.isRightClick() ? "crew sethome" : "crew home");
        } else if (action.startsWith("cmd:")) {
            p.performCommand(action.substring(4));
            String page = menu.page, target = menu.target;
            module.runLater(() -> {
                if (p.isOnline()) {
                    open(p, page, target); // show the change
                }
            }, 2L);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
