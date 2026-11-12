package dev.aquatic.world;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewRank;
import dev.aquatic.api.CrewRelation;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.IslandStatus;
import dev.aquatic.command.CommandFail;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Island development: upgrades a crew buys for an island from the crew bank ("upgrades" in islands.yml).
 * <ul>
 *   <li><b>treasury</b>: more passive income</li>
 *   <li><b>fortress</b>: enemies need longer to capture the flag</li>
 *   <li><b>watchtower</b>: the crew is warned when enemies land (level 2+: intruders glow)</li>
 *   <li><b>harbor</b>: crewmates swim faster around the island (level 3+: breathe underwater)</li>
 * </ul>
 * Managed in-game with /island manage (chest menu) or /island upgrade &lt;type&gt;.
 */
final class IslandUpgrades implements Listener {

    static final List<String> TYPES = List.of("treasury", "fortress", "watchtower", "harbor", "barracks", "wellspring");

    private final IslandsModule m;
    /** "player:island" -> last watchtower alert (ms), so a crew is not spammed. */
    private final Map<String, Long> lastAlert = new HashMap<>();

    IslandUpgrades(IslandsModule module) {
        this.m = module;
    }

    private ConfigurationSection def(String type) {
        return m.config().getConfigurationSection("upgrades.types." + type);
    }

    boolean enabled() {
        return m.config().getBoolean("upgrades.enabled", true);
    }

    int maxLevel(String type) {
        ConfigurationSection d = def(type);
        return d == null ? 0 : Math.max(0, d.getIntegerList("costs").size());
    }

    /** Cost of the next level, or -1 when maxed. */
    double nextCost(IslandImpl is, String type) {
        ConfigurationSection d = def(type);
        int level = is.upgrade(type);
        List<?> costs = d == null ? List.of() : d.getList("costs", List.of());
        if (level >= costs.size()) {
            return -1;
        }
        Object c = costs.get(level);
        return c instanceof Number num ? num.doubleValue() : Double.parseDouble(String.valueOf(c));
    }

    /** The effect strength of an upgrade: level * per-level (e.g. 0.25 = +25% per level). */
    double value(IslandImpl is, String type) {
        if (!enabled()) {
            return 0;
        }
        ConfigurationSection d = def(type);
        return d == null ? 0 : is.upgrade(type) * d.getDouble("per-level", 0);
    }

    // ------------------------------------------------------------------ buying

    void upgrade(Player p, IslandImpl is, String type) {
        if (!enabled()) {
            throw new CommandFail(m.rawMsg("upgrades-disabled"));
        }
        type = type.toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type) || def(type) == null) {
            throw new CommandFail(m.rawMsg("upgrade-unknown", "type", type, "types", String.join(", ", TYPES)));
        }
        Crew crew = ownCrew(p, is);
        CrewRank rank = crew.rankOf(p.getUniqueId());
        if (rank == null || !rank.isOfficer()) {
            throw new CommandFail(m.rawMsg("upgrade-officer"));
        }
        double cost = nextCost(is, type);
        if (cost < 0) {
            throw new CommandFail(m.rawMsg("upgrade-maxed", "upgrade", name(type)));
        }
        if (!crew.withdrawBank(cost)) {
            throw new CommandFail(m.rawMsg("upgrade-no-money", "cost", money(cost), "bank", money(crew.bankBalance())));
        }
        is.upgrades.merge(type, 1, Integer::sum);
        m.markDirty();
        crew.broadcast(m.msg("upgrade-bought", "player", p.getName(), "upgrade", name(type), "level", is.upgrade(type),
                "island", is.name, "cost", money(cost)));
    }

    private Crew ownCrew(Player p, IslandImpl is) {
        CrewService crews = m.service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewOf(p.getUniqueId());
        if (crew == null || is.status != IslandStatus.OCCUPIED || !crew.id().equals(is.ownerCrewId)) {
            throw new CommandFail(m.rawMsg("manage-not-yours", "island", is.name));
        }
        return crew;
    }

    /** What happens to upgrades when the island changes hands: keep | lose-one | reset. */
    void onConquered(IslandImpl is) {
        String mode = m.config().getString("upgrades.on-conquest", "lose-one").toLowerCase(Locale.ROOT);
        switch (mode) {
            case "keep" -> {
            }
            case "reset" -> is.upgrades.clear();
            default -> is.upgrades.replaceAll((k, v) -> Math.max(0, v - 1));
        }
    }

    // ------------------------------------------------------------------ effects

    /** Watchtower: called when a player enters an island. */
    void onEnter(Player p, IslandImpl is) {
        int level = enabled() ? is.upgrade("watchtower") : 0;
        if (level <= 0 || is.status != IslandStatus.OCCUPIED || is.ownerCrewId == null) {
            return;
        }
        CrewService crews = m.service(CrewService.class);
        Crew owner = crews == null ? null : crews.crewById(is.ownerCrewId);
        if (owner == null) {
            return;
        }
        Crew theirs = crews.crewOf(p.getUniqueId());
        CrewRelation rel = crews.relation(theirs, owner);
        if (rel == CrewRelation.SAME || rel == CrewRelation.ALLY || p.hasPermission("aquatic.islands.bypass")) {
            return;
        }
        String key = p.getUniqueId() + ":" + is.id;
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, m.config().getLong("upgrades.types.watchtower.alert-cooldown-seconds", 60)) * 1000L;
        Long last = lastAlert.get(key);
        if (last != null && now - last < cooldown) {
            return;
        }
        lastAlert.put(key, now);
        owner.broadcast(m.msg("watchtower-alert", "player", p.getName(), "island", is.name,
                "crew", theirs == null ? m.rawMsg("watchtower-no-crew") : theirs.displayName()));
        if (level >= 2) {
            int seconds = Math.max(1, m.config().getInt("upgrades.types.watchtower.glow-seconds", 15)) * (level - 1);
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, seconds * 20, 0, true, false, true));
        }
    }

    /** Harbor: every few seconds, crewmates in the water around their island get swim buffs. */
    void tickHarbor(Map<UUID, String> presence) {
        if (!enabled()) {
            return;
        }
        CrewService crews = m.service(CrewService.class);
        if (crews == null) {
            return;
        }
        for (Map.Entry<UUID, String> e : presence.entrySet()) {
            IslandImpl is = m.islandImpl(e.getValue());
            int level = is == null ? 0 : is.upgrade("harbor");
            int well = is == null ? 0 : is.upgrade("wellspring");
            if (is == null || (level <= 0 && well <= 0) || is.ownerCrewId == null) {
                continue;
            }
            Player p = Bukkit.getPlayer(e.getKey());
            Crew crew = p == null ? null : crews.crewOf(p.getUniqueId());
            if (crew == null || !crew.id().equals(is.ownerCrewId)) {
                continue;
            }
            if (well > 0) {
                // Wellspring: crewmates heal on their own island
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, Math.min(2, well - 1), true, false, true));
            }
            if (level <= 0 || !p.isInWater()) {
                continue;
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 100, Math.min(2, level - 1), true, false, true));
            if (level >= 3) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.CONDUIT_POWER, 100, 0, true, false, true));
            }
        }
    }

    /** Barracks: crewmates without a bed respawn on the crew island nearest to where they died. */
    org.bukkit.Location barracksSpawn(Player p) {
        if (!enabled()) {
            return null;
        }
        CrewService crews = m.service(CrewService.class);
        Crew crew = crews == null ? null : crews.crewOf(p.getUniqueId());
        if (crew == null) {
            return null;
        }
        org.bukkit.Location died = p.getLocation();
        IslandImpl best = null;
        double bestDist = Double.MAX_VALUE;
        for (dev.aquatic.api.Island island : m.islandsOwnedBy(crew.id())) {
            IslandImpl is = m.islandImpl(island.id());
            if (is == null || is.upgrade("barracks") <= 0 || !is.world.equals(died.getWorld().getName())) {
                continue;
            }
            double d = is.distance(died.getBlockX(), died.getBlockZ());
            if (d < bestDist) {
                bestDist = d;
                best = is;
            }
        }
        return best == null ? null : m.islandSpawnSpot(best);
    }

    // ------------------------------------------------------------------ menu

    private static final int SLOT_INFO = 4;
    private static final int[] SLOT_UPGRADES = {19, 21, 23, 25, 29, 33};
    private static final int SLOT_ACCESS = 31;

    private static final class Menu implements InventoryHolder {
        final String islandId;
        Inventory inventory;

        Menu(String islandId) {
            this.islandId = islandId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    void open(Player p, IslandImpl is) {
        Menu holder = new Menu(is.id);
        Inventory inv = Bukkit.createInventory(holder, 45, m.rawMsg("manage-title", "island", is.name));
        holder.inventory = inv;
        fill(inv, is, p);
        p.openInventory(inv);
    }

    private void fill(Inventory inv, IslandImpl is, Player viewer) {
        ItemStack glass = item(Material.BLUE_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, glass);
        }
        CrewService crews = m.service(CrewService.class);
        Crew owner = crews == null || is.ownerCrewId == null ? null : crews.crewById(is.ownerCrewId);
        double income = m.incomeOf(is);
        List<Component> info = new ArrayList<>();
        info.add(m.rawMsg("manage-info-type", "type", m.typeName(is.type)));
        info.add(m.rawMsg("manage-info-size", "radius", is.radius, "width", is.radius * 2));
        info.add(m.rawMsg("manage-info-owner", "owner", owner == null ? Component.text("-") : owner.displayName()));
        info.add(m.rawMsg("manage-info-income", "amount", money(income),
                "minutes", m.config().getLong("income.interval-minutes", 30)));
        if (owner != null) {
            info.add(m.rawMsg("manage-info-bank", "amount", money(owner.bankBalance())));
        }
        inv.setItem(SLOT_INFO, item(Material.FILLED_MAP, m.rawMsg("manage-info-name", "island", is.name), info));
        for (int i = 0; i < TYPES.size(); i++) {
            String type = TYPES.get(i);
            ConfigurationSection d = def(type);
            if (d == null) {
                continue;
            }
            int level = is.upgrade(type), max = maxLevel(type);
            double cost = nextCost(is, type);
            List<Component> lore = new ArrayList<>();
            for (String line : d.getStringList("description")) {
                lore.add(Text.item(line));
            }
            lore.add(Component.empty());
            lore.add(m.rawMsg("manage-level", "level", level, "max", max));
            lore.add(cost < 0 ? m.rawMsg("manage-maxed") : m.rawMsg("manage-cost", "cost", money(cost)));
            lore.add(cost < 0 ? Component.empty() : m.rawMsg("manage-click"));
            Material icon = Material.matchMaterial(d.getString("icon", "CHEST"));
            ItemStack it = item(icon == null ? Material.CHEST : icon, Text.item(d.getString("display-name", type)), lore);
            it.setAmount(Math.max(1, Math.min(64, level)));
            inv.setItem(SLOT_UPGRADES[i], it);
        }
        inv.setItem(SLOT_ACCESS, item(is.officersOnly ? Material.IRON_DOOR : Material.OAK_DOOR,
                m.rawMsg(is.officersOnly ? "manage-access-officers" : "manage-access-all"),
                List.of(m.rawMsg("manage-access-hint"), m.rawMsg("manage-access-click"))));
    }

    private static ItemStack item(Material type, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(type);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                net.kyori.adventure.text.format.TextDecoration.State.FALSE));
        List<Component> clean = new ArrayList<>();
        for (Component c : lore) {
            clean.add(c.decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                    net.kyori.adventure.text.format.TextDecoration.State.FALSE));
        }
        meta.lore(clean);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
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
        IslandImpl is = m.islandImpl(menu.islandId);
        if (is == null) {
            p.closeInventory();
            return;
        }
        if (event.getSlot() == SLOT_ACCESS) {
            try {
                Crew crew = ownCrew(p, is);
                CrewRank rank = crew.rankOf(p.getUniqueId());
                if (rank == null || !rank.isOfficer()) {
                    throw new CommandFail(m.rawMsg("upgrade-officer"));
                }
                is.officersOnly = !is.officersOnly;
                m.markDirty();
                crew.broadcast(m.msg(is.officersOnly ? "access-officers" : "access-all", "island", is.name, "player", p.getName()));
            } catch (CommandFail fail) {
                p.sendMessage(m.plugin().prefix().append(fail.component()));
            }
            fill(event.getInventory(), is, p);
            return;
        }
        for (int i = 0; i < SLOT_UPGRADES.length && i < TYPES.size(); i++) {
            if (event.getSlot() == SLOT_UPGRADES[i]) {
                try {
                    upgrade(p, is, TYPES.get(i));
                } catch (CommandFail fail) {
                    p.sendMessage(m.plugin().prefix().append(fail.component()));
                }
                fill(event.getInventory(), is, p);
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    String name(String type) {
        ConfigurationSection d = def(type);
        return Text.plain(Text.mm(d == null ? type : d.getString("display-name", type)));
    }

    private String money(double amount) {
        EconomyService eco = m.service(EconomyService.class);
        return eco != null ? eco.format(amount) : Text.number(amount);
    }
}
