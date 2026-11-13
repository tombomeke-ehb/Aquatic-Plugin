package dev.aquatic.crews;

import dev.aquatic.api.CrewRank;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /crew (without arguments) opens this menu: crew info, members with their rank, bank deposit/withdraw,
 * crew home, island home, diplomacy, crew chat and leaving. Players without a crew get buttons to found
 * one, browse crews and accept invites. Every button runs the normal /crew command, so all rules apply.
 */
final class CrewMenu implements Listener {

    private static final class Menu implements InventoryHolder {
        /** slot -> command to run (without /), or a special action */
        final Map<Integer, String> actions = new HashMap<>();
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final CrewsModule module;
    /** players who clicked "leave" once (confirm within a few seconds) */
    private final Map<UUID, Long> leaveConfirm = new HashMap<>();

    CrewMenu(CrewsModule module) {
        this.module = module;
    }

    void open(Player p) {
        Menu menu = new Menu();
        CrewImpl crew = module.byPlayer.get(p.getUniqueId());
        Inventory inv = Bukkit.createInventory(menu, 54, crew == null ? module.rawMsg("menu-title-none")
                : module.rawMsg("menu-title", "crew", crew.displayName()));
        menu.inventory = inv;
        ItemStack pane = item(Material.BLUE_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, pane);
        }
        if (crew == null) {
            fillNoCrew(p, menu);
        } else {
            fillCrew(p, crew, menu);
        }
        p.openInventory(inv);
    }

    private void fillNoCrew(Player p, Menu menu) {
        Inventory inv = menu.inventory;
        inv.setItem(20, item(Material.WRITABLE_BOOK, module.rawMsg("menu-create"), List.of(module.rawMsg("menu-create-lore"))));
        menu.actions.put(20, "suggest:/crew create ");
        inv.setItem(22, item(Material.COMPASS, module.rawMsg("menu-browse"), List.of(module.rawMsg("menu-browse-lore"))));
        menu.actions.put(22, "crew list");
        Map<String, Long> invites = module.invitesOf(p.getUniqueId());
        List<Component> lore = new ArrayList<>();
        if (invites == null || invites.isEmpty()) {
            lore.add(module.rawMsg("menu-invites-none"));
        } else {
            for (String id : invites.keySet()) {
                CrewImpl c = module.crews.get(id);
                if (c != null) {
                    lore.add(module.rawMsg("menu-invites-line", "crew", c.displayName()));
                }
            }
            lore.add(Component.empty());
            lore.add(module.rawMsg("menu-invites-click"));
            menu.actions.put(24, "crew accept");
        }
        inv.setItem(24, item(Material.PAPER, module.rawMsg("menu-invites"), lore));
    }

    private void fillCrew(Player p, CrewImpl crew, Menu menu) {
        Inventory inv = menu.inventory;
        EconomyService eco = module.service(EconomyService.class);
        CrewRank myRank = crew.rankOf(p.getUniqueId());
        // info
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
        ItemStack head = flag == null ? new ItemStack(Material.WHITE_BANNER) : flag.clone();
        inv.setItem(4, item(head, crew.displayName(), info));
        // members (captain first, then by rank, online first)
        List<UUID> members = new ArrayList<>(crew.members());
        members.sort(Comparator.<UUID>comparingInt(u -> -weight(crew.rankOf(u)))
                .thenComparing(u -> Bukkit.getPlayer(u) == null)
                .thenComparing(u -> crew.names.getOrDefault(u, "")));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < members.size() && i < slots.length; i++) {
            UUID u = members.get(i);
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            if (skull.getItemMeta() instanceof SkullMeta sm) {
                sm.setOwningPlayer(op);
                skull.setItemMeta(sm);
            }
            CrewRank r = crew.rankOf(u);
            boolean online = Bukkit.getPlayer(u) != null;
            List<Component> lore = new ArrayList<>();
            lore.add(module.rawMsg("menu-member-rank", "rank", r == null ? "-" : r.display()));
            lore.add(module.rawMsg(online ? "menu-member-online" : "menu-member-offline"));
            if (myRank != null && myRank.isOfficer() && !u.equals(p.getUniqueId()) && r != CrewRank.CAPTAIN) {
                lore.add(Component.empty());
                lore.add(module.rawMsg("menu-member-manage"));
                String name = crew.names.getOrDefault(u, op.getName() == null ? "?" : op.getName());
                menu.actions.put(slots[i], "member:" + name);
            }
            inv.setItem(slots[i], item(skull, Text.mm("<white>" + crew.names.getOrDefault(u, op.getName() == null ? "?" : op.getName())), lore));
        }
        // bottom row: bank, home, islands, diplomacy, chat, invite, leave
        inv.setItem(45, item(Material.GOLD_INGOT, module.rawMsg("menu-bank"), List.of(
                module.rawMsg("menu-bank-balance", "amount", money(eco, crew.bankBalance())),
                Component.empty(), module.rawMsg("menu-bank-lore"))));
        menu.actions.put(45, "bank");
        inv.setItem(46, item(Material.RED_BED, module.rawMsg("menu-home"), List.of(module.rawMsg("menu-home-lore"))));
        menu.actions.put(46, "crew home");
        inv.setItem(47, item(Material.OAK_BOAT, module.rawMsg("menu-islands"), List.of(module.rawMsg("menu-islands-lore", "count", owned))));
        menu.actions.put(47, "island home");
        List<Component> diplo = new ArrayList<>();
        diplo.add(module.rawMsg("menu-allies", "list", names(crew.allies)));
        diplo.add(module.rawMsg("menu-rivals", "list", names(crew.rivals)));
        diplo.add(module.rawMsg("menu-wars", "list", names(crew.wars.keySet())));
        inv.setItem(49, item(Material.SHIELD, module.rawMsg("menu-diplomacy"), diplo));
        inv.setItem(51, item(Material.OAK_SIGN, module.rawMsg("menu-chat"), List.of(module.rawMsg(
                module.crewChat.contains(p.getUniqueId()) ? "menu-chat-on" : "menu-chat-off"), module.rawMsg("menu-chat-lore"))));
        menu.actions.put(51, "crew chat");
        if (myRank != null && myRank.isOfficer()) {
            inv.setItem(52, item(Material.WRITABLE_BOOK, module.rawMsg("menu-invite"), List.of(module.rawMsg("menu-invite-lore"))));
            menu.actions.put(52, "suggest:/crew invite ");
        }
        inv.setItem(53, item(Material.BARRIER, module.rawMsg("menu-leave"), List.of(module.rawMsg(
                myRank == CrewRank.CAPTAIN ? "menu-leave-captain" : "menu-leave-lore"))));
        menu.actions.put(53, "leave");
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
        return item(new ItemStack(m), name, lore);
    }

    private static ItemStack item(ItemStack it, Component name, List<Component> lore) {
        return dev.aquatic.util.Menus.icon(it, name, lore);
    }

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
        ClickType click = event.getClick();
        switch (action) {
            case "bank" -> {
                // left: deposit 100, shift-left: 1000; right: withdraw 100, shift-right: 1000
                int amount = click.isShiftClick() ? 1000 : 100;
                p.performCommand("crew bank " + (click.isRightClick() ? "withdraw " : "deposit ") + amount);
                open(p);
            }
            case "leave" -> {
                Long first = leaveConfirm.get(p.getUniqueId());
                if (first != null && System.currentTimeMillis() - first < 5000) {
                    leaveConfirm.remove(p.getUniqueId());
                    p.closeInventory();
                    p.performCommand("crew leave");
                } else {
                    leaveConfirm.put(p.getUniqueId(), System.currentTimeMillis());
                    module.send(p, "menu-leave-confirm");
                }
            }
            default -> {
                if (action.startsWith("suggest:")) {
                    p.closeInventory();
                    String cmd = action.substring(8);
                    p.sendMessage(module.rawMsg("menu-suggest", "command", cmd.trim())
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(cmd)));
                } else if (action.startsWith("member:")) {
                    // officers: left = promote, right = demote, shift-right = kick
                    String name = action.substring(7);
                    String sub = click == ClickType.SHIFT_RIGHT ? "kick" : click.isRightClick() ? "demote" : "promote";
                    p.performCommand("crew " + sub + " " + name);
                    open(p);
                } else {
                    p.closeInventory();
                    p.performCommand(action);
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @SuppressWarnings("unused")
    private static List<Island> none() {
        return List.of();
    }
}
