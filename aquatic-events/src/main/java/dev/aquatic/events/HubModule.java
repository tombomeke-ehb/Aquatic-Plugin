package dev.aquatic.events;

import dev.aquatic.api.BountyService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.Island;
import dev.aquatic.api.IslandService;
import dev.aquatic.api.IslandType;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The harbour hub at the capital: shopkeeper villagers in the market stalls, the crew registrar, a live
 * bounty board, the founders' monument, a welcome sign and a timed parkour tower (modules/hub.yml).
 * The villagers and signs are not saved with the world: they are (re)placed while players are nearby.
 */
public final class HubModule extends AquaticModule implements Listener {

    /** An NPC or a floating text, at an offset from the capital centre or at a placed location. */
    private static final class Spot {
        final String id;
        final boolean npc;
        final ConfigurationSection cfg;
        /** Founders: the Minecraft name whose head sits on the pedestal (null for NPCs and signs). */
        String head;

        Spot(String id, boolean npc, ConfigurationSection cfg) {
            this.id = id;
            this.npc = npc;
            this.cfg = cfg;
        }
    }

    private NamespacedKey tagKey;
    private final List<Spot> spots = new ArrayList<>();
    /** "spotId@capitalId" (or "spotId@placed") -> the spawned entity. */
    private final Map<String, UUID> live = new HashMap<>();
    private final Map<String, Location> placed = new HashMap<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();
    /** Parkour: player -> start time (ms). */
    private final Map<UUID, Long> runs = new HashMap<>();
    private final Map<UUID, Long> best = new HashMap<>();
    private final Map<UUID, Long> lastReward = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private int tickCount;

    @Override
    public String id() {
        return "hub";
    }

    @Override
    protected void onEnable() {
        tagKey = new NamespacedKey(plugin(), "hub_spot");
        spots.clear();
        ConfigurationSection npcs = config().getConfigurationSection("npcs");
        if (npcs != null) {
            for (String id : npcs.getKeys(false)) {
                ConfigurationSection c = npcs.getConfigurationSection(id);
                if (c != null && c.getBoolean("enabled", true)) {
                    spots.add(new Spot("npc:" + id, true, c));
                }
            }
        }
        ConfigurationSection displays = config().getConfigurationSection("displays");
        if (displays != null) {
            for (String id : displays.getKeys(false)) {
                ConfigurationSection c = displays.getConfigurationSection(id);
                if (c != null && c.getBoolean("enabled", true)) {
                    spots.add(new Spot("text:" + id, false, c));
                }
            }
        }
        ConfigurationSection founders = config().getConfigurationSection("founders");
        if (founders != null && founders.getBoolean("enabled", true)) {
            for (String id : founders.getKeys(false)) {
                ConfigurationSection c = founders.getConfigurationSection(id);
                if (c != null && c.getBoolean("enabled", true)) {
                    Spot s = new Spot("founder:" + id, false, c);
                    s.head = c.getString("player", id);
                    spots.add(s);
                }
            }
        }
        loadState();
        registerListener(this);
        registerCommands();
        runTimer(this::tick, 40L, 10L);
    }

    @Override
    protected void onDisable() {
        for (UUID id : live.values()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        live.clear();
        spots.clear();
        runs.clear();
        saveState();
    }

    // ------------------------------------------------------------------ state

    private void loadState() {
        placed.clear();
        best.clear();
        lastReward.clear();
        names.clear();
        YamlConfiguration data = loadData("hub");
        ConfigurationSection p = data.getConfigurationSection("placed");
        if (p != null) {
            for (String id : p.getKeys(false)) {
                Location l = p.getLocation(id);
                if (l != null) {
                    placed.put(id.replace("__", ":"), l);
                }
            }
        }
        ConfigurationSection pk = data.getConfigurationSection("parkour");
        if (pk != null) {
            for (String key : pk.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    ConfigurationSection e = pk.getConfigurationSection(key);
                    if (e == null) {
                        continue;
                    }
                    if (e.getLong("best") > 0) {
                        best.put(id, e.getLong("best"));
                    }
                    lastReward.put(id, e.getLong("reward"));
                    names.put(id, e.getString("name", "?"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveState() {
        YamlConfiguration data = new YamlConfiguration();
        placed.forEach((id, l) -> data.set("placed." + id.replace(":", "__"), l));
        for (UUID id : best.keySet()) {
            data.set("parkour." + id + ".best", best.get(id));
            data.set("parkour." + id + ".reward", lastReward.getOrDefault(id, 0L));
            data.set("parkour." + id + ".name", names.getOrDefault(id, "?"));
        }
        saveData("hub", data);
    }

    // ------------------------------------------------------------------ placing NPCs and texts

    /** Every capital gets the hub (normally there is one). */
    private List<Island> capitals() {
        return islandsOf(IslandType.CAPITAL);
    }

    private List<Island> islandsOf(IslandType type) {
        IslandService islands = service(IslandService.class);
        List<Island> out = new ArrayList<>();
        if (islands != null) {
            for (Island is : islands.islands()) {
                if (is.type() == type) {
                    out.add(is);
                }
            }
        }
        return out;
    }

    /** The island type a spot belongs to: "island-type" (default CAPITAL; VILLAGE for the village folk). */
    private static IslandType typeOf(Spot s) {
        IslandType t = IslandType.parse(s.cfg.getString("island-type", "CAPITAL"));
        return t == null ? IslandType.CAPITAL : t;
    }

    /** First spot from sea level up where a player could stand (solid below, two free blocks above). */
    private static int standY(World w, int x, int z) {
        for (int y = Math.max(w.getMinHeight() + 1, w.getSeaLevel() - 2); y < w.getMaxHeight() - 3; y++) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid() && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable()) {
                return y + 1;
            }
        }
        return w.getHighestBlockYAt(x, z) + 1;
    }

    /** Where a spot goes, or null (no capital and not placed). Only resolved when its chunk is loaded. */
    private Location locate(Spot s, Island capital) {
        if (capital == null || capital.center() == null || capital.center().getWorld() == null) {
            return null;
        }
        List<Integer> off = s.cfg.getIntegerList("offset");
        if (off.size() < 2) {
            return null;
        }
        World w = capital.center().getWorld();
        int x = capital.center().getBlockX() + off.get(0), z = capital.center().getBlockZ() + off.get(1);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        if (s.head != null) {
            Block top = w.getHighestBlockAt(x, z);
            Block headBlock = isHead(top) ? top : top.getRelative(0, 1, 0);
            Location hl = headBlock.getLocation().add(0.5, s.cfg.getDouble("height", 0.7), 0.5);
            Vector to = capital.center().toVector().setY(hl.getY()).subtract(hl.toVector());
            if (to.lengthSquared() > 0.01) {
                hl.setDirection(to);
            }
            return hl;
        }
        Location l = new Location(w, x + 0.5, standY(w, x, z), z + 0.5);
        if (!s.npc) {
            l.add(0, s.cfg.getDouble("height", 2.5), 0);
        } else {
            // face the throne
            Vector to = capital.center().toVector().setY(l.getY()).subtract(l.toVector());
            if (to.lengthSquared() > 0.01) {
                l.setDirection(to);
            }
        }
        return l;
    }

    private void tick() {
        tickCount++;
        tickParkour();
        if (tickCount % 4 != 0) {
            return; // NPCs and texts every 2 seconds
        }
        Map<IslandType, List<Island>> byType = new java.util.EnumMap<>(IslandType.class);
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Spot s : spots) {
            Location set = placed.get(s.id);
            if (set != null) {
                if (set.getWorld() != null) {
                    keep(s, s.id + "@placed", set.clone(), wanted);
                }
                continue;
            }
            for (Island capital : byType.computeIfAbsent(typeOf(s), this::islandsOf)) {
                Location l = locate(s, capital);
                if (l != null) {
                    keep(s, s.id + "@" + capital.id(), l, wanted);
                }
            }
        }
        // NPCs and signs nobody is near anymore (or that were moved) go away
        live.entrySet().removeIf(e -> {
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

    /** Makes sure the spot's entity exists at l while players are near. */
    private void keep(Spot s, String key, Location l, java.util.Set<String> wanted) {
        if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4) || !playersNear(l, 64)) {
            return;
        }
        wanted.add(key);
        UUID id = live.get(key);
        Entity e = id == null ? null : Bukkit.getEntity(id);
        if (e == null || !e.isValid()) {
            e = s.npc ? spawnNpc(s, l) : spawnText(s, l);
            if (e != null) {
                live.put(key, e.getUniqueId());
            }
        } else if (e.getLocation().distanceSquared(l) > 0.5) {
            e.teleport(l);
        }
        if (s.head != null && tickCount % 8 == 0) {
            placeHead(s, l);
        }
        if (e instanceof TextDisplay d) {
            d.text(textOf(s));                         // every pass (2 seconds): boards are always current
        }
    }

    // ------------------------------------------------------------------ the founders' heads

    /** Resolved skins by player name (looked up once, off the main thread). */
    private final Map<String, com.destroystokyo.paper.profile.PlayerProfile> profiles = new HashMap<>();
    private final java.util.Set<String> resolving = new java.util.HashSet<>();

    private static boolean isHead(Block b) {
        return b.getType() == Material.PLAYER_HEAD || b.getType() == Material.PLAYER_WALL_HEAD;
    }

    /**
     * Keeps the founder's head on the block under its name sign: a player head facing the way the sign
     * faces (towards the throne, or where the admin looked when placing it), with the founder's skin.
     */
    private void placeHead(Spot s, Location sign) {
        Block b = sign.getBlock();
        if (!b.getType().isAir() && !isHead(b)) {
            return; // something else is in the way; never break blocks
        }
        String name = s.head;
        com.destroystokyo.paper.profile.PlayerProfile profile = profiles.get(name.toLowerCase(Locale.ROOT));
        if (profile == null && resolving.add(name.toLowerCase(Locale.ROOT))) {
            com.destroystokyo.paper.profile.PlayerProfile p = Bukkit.createProfile(name);
            Bukkit.getScheduler().runTaskAsynchronously(plugin(), () -> {
                try {
                    p.complete(true);
                } catch (Exception ignored) {
                    // offline or unknown name: the head shows the default skin with the name
                }
                Bukkit.getScheduler().runTask(plugin(), () -> profiles.put(name.toLowerCase(Locale.ROOT), p));
            });
        }
        org.bukkit.block.BlockFace face = faceOf(sign.getYaw() + 180f);   // a skull's rotation is where its back points
        boolean fresh = b.getType() != Material.PLAYER_HEAD;
        if (fresh) {
            b.setType(Material.PLAYER_HEAD, false);
        }
        if (b.getBlockData() instanceof org.bukkit.block.data.Rotatable r && r.getRotation() != face) {
            r.setRotation(face);
            b.setBlockData(r, false);
        }
        if (profile != null && b.getState() instanceof org.bukkit.block.Skull skull) {
            com.destroystokyo.paper.profile.PlayerProfile now = skull.getPlayerProfile();
            boolean same = now != null && name.equalsIgnoreCase(now.getName())
                    && (now.hasTextures() || !profile.hasTextures());
            if (!same) {
                skull.setPlayerProfile(profile);
                skull.update(true, false);
            }
        }
    }

    /** The 16-way block face for a yaw (where a sign looks). */
    private static org.bukkit.block.BlockFace faceOf(float yaw) {
        org.bukkit.block.BlockFace[] faces = {
                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.SOUTH_SOUTH_WEST, org.bukkit.block.BlockFace.SOUTH_WEST,
                org.bukkit.block.BlockFace.WEST_SOUTH_WEST, org.bukkit.block.BlockFace.WEST, org.bukkit.block.BlockFace.WEST_NORTH_WEST,
                org.bukkit.block.BlockFace.NORTH_WEST, org.bukkit.block.BlockFace.NORTH_NORTH_WEST, org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.NORTH_NORTH_EAST, org.bukkit.block.BlockFace.NORTH_EAST, org.bukkit.block.BlockFace.EAST_NORTH_EAST,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.EAST_SOUTH_EAST, org.bukkit.block.BlockFace.SOUTH_EAST,
                org.bukkit.block.BlockFace.SOUTH_SOUTH_EAST};
        int i = Math.floorMod(Math.round(yaw / 22.5f), 16);
        return faces[i];
    }

    private static boolean playersNear(Location l, double radius) {
        for (Player p : l.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(l) <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    private Entity spawnNpc(Spot s, Location l) {
        return l.getWorld().spawn(l, Villager.class, v -> {
            v.setPersistent(false);
            v.setRemoveWhenFarAway(false);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCollidable(false);
            v.setAdult();
            v.setVillagerLevel(5);
            Villager.Profession prof = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(
                    s.cfg.getString("profession", "none").toLowerCase(Locale.ROOT)));
            if (prof != null) {
                v.setProfession(prof);
            }
            Villager.Type type = Registry.VILLAGER_TYPE.get(NamespacedKey.minecraft(s.cfg.getString("type", "plains").toLowerCase(Locale.ROOT)));
            if (type != null) {
                v.setVillagerType(type);
            }
            v.customName(Text.mm(s.cfg.getString("name", s.id)));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, s.id);
        });
    }

    private Entity spawnText(Spot s, Location l) {
        return l.getWorld().spawn(l, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setShadowed(true);
            d.setLineWidth(260);
            d.text(textOf(s));
            d.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, s.id);
        });
    }

    /** The text of a floating sign: fixed lines, the bounty board or the parkour records. */
    private Component textOf(Spot s) {
        List<String> lines = new ArrayList<>(s.cfg.getStringList("header"));
        String kind = s.cfg.getString("type", "text").toLowerCase(Locale.ROOT);
        int max = Math.max(1, s.cfg.getInt("lines", 5));
        if (kind.equals("bounties")) {
            BountyService bounties = service(BountyService.class);
            EconomyService eco = service(EconomyService.class);
            Map<UUID, Double> top = bounties == null ? Map.of() : bounties.top(max);
            int rank = 1;
            for (Map.Entry<UUID, Double> e : top.entrySet()) {
                String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
                lines.add(fill(s.cfg.getString("line", "<rank>. <player> <bounty>"), "rank", rank++, "player", name == null ? "?" : name,
                        "bounty", eco == null ? Text.number(e.getValue()) : eco.format(e.getValue())));
            }
            if (top.isEmpty()) {
                lines.add(s.cfg.getString("empty", "<gray>No bounties yet."));
            }
        } else if (kind.equals("parkour")) {
            List<Map.Entry<UUID, Long>> top = new ArrayList<>(best.entrySet());
            top.sort(Map.Entry.comparingByValue());
            for (int i = 0; i < Math.min(max, top.size()); i++) {
                lines.add(fill(s.cfg.getString("line", "<rank>. <player> <time>"), "rank", i + 1,
                        "player", names.getOrDefault(top.get(i).getKey(), "?"), "time", time(top.get(i).getValue())));
            }
            if (top.isEmpty()) {
                lines.add(s.cfg.getString("empty", "<gray>No times yet."));
            }
        }
        if (s.head != null) {
            lines.add(s.cfg.getString("name", s.head));
            String role = s.cfg.getString("role", "");
            if (!role.isEmpty()) {
                lines.add(role);
            }
        }
        lines.addAll(s.cfg.getStringList("lines-below"));
        lines.addAll(s.cfg.getStringList("text"));
        return Text.mm(String.join("\n", lines));
    }

    /** Fills &lt;name&gt; placeholders as plain text inside a MiniMessage line. */
    private static String fill(String line, Object... ph) {
        String out = line;
        for (int i = 0; i + 1 < ph.length; i += 2) {
            String v = String.valueOf(ph[i + 1]).replace("<", "\\<");
            out = out.replace("<" + ph[i] + ">", v);
        }
        return out;
    }

    private static String time(long ms) {
        return String.format(Locale.ROOT, "%d:%02d.%02d", ms / 60000, (ms / 1000) % 60, (ms / 10) % 100);
    }

    // ------------------------------------------------------------------ NPC clicks

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEntityEvent event) {
        String id = event.getRightClicked().getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event instanceof PlayerInteractAtEntityEvent) && event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
            clicked(event.getPlayer(), id);
        }
    }

    private void clicked(Player p, String id) {
        long now = System.currentTimeMillis();
        Long last = clickCooldown.get(p.getUniqueId());
        if (last != null && now - last < 600) {
            return;
        }
        clickCooldown.put(p.getUniqueId(), now);
        for (Spot s : spots) {
            if (!s.id.equals(id)) {
                continue;
            }
            for (String action : s.cfg.getStringList("actions")) {
                if (openMenu(p, action)) {
                    continue;
                }
                if (action.startsWith("cmd:")) {
                    p.performCommand(action.substring(4).trim().replace("<player>", p.getName()));
                } else if (action.startsWith("msg:")) {
                    p.sendMessage(Text.mm(action.substring(4).trim(), "player", p.getName()));
                }
            }
        }
    }

    /**
     * Menus a villager opens directly, so players need no command for them: "shop:&lt;category&gt;" (or "shop"),
     * "gamble". The old "cmd:shop ..." and "cmd:gamble" actions are understood too.
     */
    private boolean openMenu(Player p, String action) {
        String a = action.trim();
        if (a.startsWith("cmd:shop") || a.startsWith("cmd:market")) {
            String[] parts = a.substring(4).trim().split("\\s+");
            a = "shop:" + (parts.length > 1 ? parts[1] : "");
        } else if (a.equals("cmd:gamble") || a.equals("cmd:casino")) {
            a = "gamble";
        }
        if (a.equals("shop") || a.startsWith("shop:")) {
            if (plugin().module("shop") instanceof ShopModule shop && shop.isEnabled()) {
                shop.openFor(p, a.startsWith("shop:") ? a.substring(5) : null);
            }
            return true;
        }
        if (a.equals("gamble")) {
            if (plugin().module("gambling") instanceof GamblingModule den && den.isEnabled()) {
                den.openMenu(p, null);
            }
            return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(tagKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    /** Zombies (and every other mob) leave the hub's villagers alone. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        if (event.getTarget() != null && event.getTarget().getPersistentDataContainer().has(tagKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(tagKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ parkour

    private boolean atCapital(Location l) {
        IslandService islands = service(IslandService.class);
        Island is = islands == null ? null : islands.islandAt(l);
        return is != null && is.type() == IslandType.CAPITAL;
    }

    @EventHandler
    public void onPlate(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null
                || !config().getBoolean("parkour.enabled", true)) {
            return;
        }
        Material m = event.getClickedBlock().getType();
        Player p = event.getPlayer();
        if (m != Material.LIGHT_WEIGHTED_PRESSURE_PLATE && m != Material.HEAVY_WEIGHTED_PRESSURE_PLATE) {
            return;
        }
        if (!atCapital(event.getClickedBlock().getLocation())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (m == Material.LIGHT_WEIGHTED_PRESSURE_PLATE) {
            Long started = runs.get(p.getUniqueId());
            if (started == null || now - started > 1500) {
                runs.put(p.getUniqueId(), now);
                p.sendActionBar(rawMsg("parkour-start"));
            }
            return;
        }
        Long started = runs.remove(p.getUniqueId());
        if (started == null) {
            return;
        }
        long ms = now - started;
        if (ms < Math.max(0, config().getLong("parkour.min-seconds", 6)) * 1000L) {
            send(p, "parkour-no-record");
            return;
        }
        if (p.isFlying() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            send(p, "parkour-no-record");
            return;
        }
        Long prev = best.get(p.getUniqueId());
        boolean record = prev == null || ms < prev;
        names.put(p.getUniqueId(), p.getName());
        if (record) {
            best.put(p.getUniqueId(), ms);
        }
        send(p, record ? "parkour-finish-best" : "parkour-finish", "time", time(ms), "best", time(record ? ms : prev));
        saveState();                                   // records survive a crash
        for (Spot s : spots) {                         // and the boards show the run at once
            if (s.npc || !"parkour".equalsIgnoreCase(s.cfg.getString("type"))) {
                continue;
            }
            for (Map.Entry<String, UUID> live : this.live.entrySet()) {
                if (live.getKey().startsWith(s.id + "@") && Bukkit.getEntity(live.getValue()) instanceof TextDisplay d) {
                    d.text(textOf(s));
                }
            }
        }
        double reward = config().getDouble("parkour.reward", 250);
        long cooldown = Math.max(0, config().getLong("parkour.reward-cooldown-hours", 20)) * 3600_000L;
        EconomyService eco = service(EconomyService.class);
        if (reward > 0 && eco != null && now - lastReward.getOrDefault(p.getUniqueId(), 0L) >= cooldown) {
            eco.deposit(p.getUniqueId(), reward);
            lastReward.put(p.getUniqueId(), now);
            send(p, "parkour-reward", "amount", eco.format(reward));
        }
        saveState();
    }

    private void tickParkour() {
        if (runs.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long max = Math.max(30, config().getLong("parkour.max-seconds", 600)) * 1000L;
        runs.entrySet().removeIf(e -> {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || now - e.getValue() > max || !atCapital(p.getLocation())) {
                if (p != null) {
                    p.sendActionBar(rawMsg("parkour-cancelled"));
                }
                return true;
            }
            p.sendActionBar(rawMsg("parkour-timer", "time", time(now - e.getValue())));
            return false;
        });
    }

    /** Teleporting (commands, pearls, ...) ends a parkour run. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (runs.remove(event.getPlayer().getUniqueId()) != null) {
            event.getPlayer().sendActionBar(rawMsg("parkour-cancelled"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runs.remove(event.getPlayer().getUniqueId());
        clickCooldown.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        AquaticCommand parkour = new AquaticCommand("parkour", "Your best parkour time and the top runners");
        parkour.defaultExecutor((s, a) -> {
            Player p = AquaticCommand.player(s);
            Long mine = best.get(p.getUniqueId());
            send(p, "parkour-best", "time", mine == null ? "-" : time(mine));
            List<Map.Entry<UUID, Long>> top = new ArrayList<>(best.entrySet());
            top.sort(Map.Entry.comparingByValue());
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                p.sendMessage(rawMsg("parkour-top-line", "rank", i + 1, "player", names.getOrDefault(top.get(i).getKey(), "?"),
                        "time", time(top.get(i).getValue())));
            }
        });
        registerCommand(parkour);

        AquaticCommand admin = new AquaticCommand("hubadmin", "Place the hub's shopkeepers and signs");
        admin.permission("aquatic.hub.admin");
        admin.sub("list", (s, a) -> {
            List<String> names = new ArrayList<>();
            capitals().forEach(c -> names.add(c.name()));
            send(s, "admin-capital", "capital", names.isEmpty() ? "-" : String.join(", ", names));
            for (Spot spot : spots) {
                boolean alive = live.keySet().stream().anyMatch(k -> k.startsWith(spot.id + "@"));
                s.sendMessage(rawMsg("admin-line", "id", spot.id, "where", placed.containsKey(spot.id) ? "placed" : "offset "
                        + spot.cfg.getIntegerList("offset"), "alive", alive ? "yes" : "no"));
            }
        }).description("All NPCs and signs");
        admin.sub("place", (s, a) -> {
            Player p = AquaticCommand.player(s);
            AquaticCommand.need(a, 1, "/hubadmin place <id>");
            Spot spot = find(a[0]);
            Location l = p.getLocation().clone();
            if (spot.head != null) {
                Location at = l.getBlock().getLocation().add(0.5, spot.cfg.getDouble("height", 0.7), 0.5);
                at.setYaw(l.getYaw());
                l = at;
            } else if (!spot.npc) {
                l.add(0, 1.5, 0);
            }
            placed.put(spot.id, l);
            respawn(spot);
            saveState();
            send(s, "admin-placed", "id", spot.id);
        }).usage("<id>").description("Move an NPC or sign to where you stand")
                .completer((s, a) -> AquaticCommand.filter(ids(), a.length == 0 ? "" : a[0]));
        admin.sub("reset", (s, a) -> {
            AquaticCommand.need(a, 1, "/hubadmin reset <id>");
            Spot spot = find(a[0]);
            placed.remove(spot.id);
            respawn(spot);
            saveState();
            send(s, "admin-reset", "id", spot.id);
        }).usage("<id>").description("Put an NPC or sign back at its default spot")
                .completer((s, a) -> AquaticCommand.filter(ids(), a.length == 0 ? "" : a[0]));
        admin.sub("refresh", (s, a) -> {
            spots.forEach(this::respawn);
            send(s, "admin-refreshed");
        }).description("Respawn all NPCs and signs");
        registerCommand(admin);
    }

    private List<String> ids() {
        List<String> out = new ArrayList<>();
        spots.forEach(s -> out.add(s.id));
        return out;
    }

    private Spot find(String id) {
        String want = id.toLowerCase(Locale.ROOT);
        for (Spot s : spots) {
            if (s.id.equals(want) || s.id.substring(s.id.indexOf(':') + 1).equals(want)) {
                return s;
            }
        }
        throw new CommandFail(rawMsg("admin-unknown", "id", id));
    }

    private void respawn(Spot s) {
        live.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith(s.id + "@")) {
                return false;
            }
            Entity ent = Bukkit.getEntity(e.getValue());
            if (ent != null) {
                ent.remove();
            }
            return true;
        });
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Override
    protected void migrateConfig(YamlConfiguration file, YamlConfiguration defaults, int from) {
        if (from < 9) {
            // 2.25.1: founders' roles update
            raise(file, "founders.tombomeke.role", "<gray>Left Hand Man", "<gray>Main Developer");
            raise(file, "founders.devvv.role", "<gray>Right Hand Man", "<gray>Developer");
            raise(file, "founders.crimson.role", "<gray>Builder", "<gray>Developer/Builder");
            raise(file, "founders.crimson.player", "Crimson", "crimsonEvaz");
            List<String> text = file.getStringList("displays.founders.text");
            if (text != null && !text.isEmpty()) {
                boolean modified = false;
                List<String> updated = new ArrayList<>();
                for (String line : text) {
                    if (line.contains("(your names here)")) {
                        updated.add(line.replace("(your names here)", "MegaAquatic"));
                        modified = true;
                    } else {
                        updated.add(line);
                    }
                }
                if (modified) {
                    file.set("displays.founders.text", updated);
                }
            }
        }
        if (from < 8) {
            // 2.23.0: the founders' real usernames
            raise(file, "founders.devvv.player", "Devvv_", "devv_");
            raise(file, "founders.devvv.name", "<#badc58><bold>Devvv_", "<#badc58><bold>Devv_");
            raise(file, "founders.tombomeke.player", "tombomeke", "tombomekeMS");
            raise(file, "founders.crimson.player", "Crimson", "crimsonEvaz");
        }
        if (from < 7) {
            // 2.19.0: the founders' shrine (heads on pedestals), a /discord sign under the welcome sign, a relics hint
            addMissing(file, defaults, "founders");
            addMissing(file, defaults, "displays.discord");
            addMissing(file, defaults, "displays.relics");
            raise(file, "displays.welcome.height", 2.4, 3.0);
            raise(file, "displays.founders.offset", List.of(27, 0), List.of(28, 0));
            raise(file, "displays.founders.height", 1.4, 4.6);
            raise(file, "displays.founders.text", List.of("<gold><bold>Founders of Aquatic SMP</bold>",
                    "<white>Owner & idea: <yellow>(your names here)", "<white>Developed by <aqua>tombomeke",
                    "<gray>Thank you to everyone who helped build this sea!"), defaults.getStringList("displays.founders.text"));
            raise(file, "displays.founders.text", List.of("<gold><bold>Founders of Aquatic SMP</bold>",
                    "<white>Owner & idea: <yellow>MegaAquatic", "<white>Developed by <aqua>tombomeke",
                    "<gray>Thank you to everyone who helped build this sea!"), defaults.getStringList("displays.founders.text"));
        }
        if (from < 2) {
            // 2.6.0: the Blacksmith opens the smithy (which links to the tools shop)
            raise(file, "npcs.blacksmith.actions", List.of("cmd:shop tools"), List.of("cmd:smithy"));
            raise(file, "npcs.blacksmith.name", "<gold><bold>Blacksmith</bold> <gray>(tools & weapons)",
                    "<gold><bold>Blacksmith</bold> <gray>(smithy & tools)");
        }
        if (from < 6) {
            // 2.17.0: the dealer stands behind the counter of the generated gambling den
            raise(file, "npcs.dealer.offset", List.of(6, 30), List.of(6, 32));
        }
        if (from < 5) {
            // 2.16.0: the shopkeepers and the dealer open their menus directly (players have no /shop or /gamble)
            org.bukkit.configuration.ConfigurationSection npcs = file.getConfigurationSection("npcs");
            if (npcs != null) {
                for (String id : npcs.getKeys(false)) {
                    List<String> actions = npcs.getStringList(id + ".actions");
                    List<String> fixed = new ArrayList<>();
                    for (String act : actions) {
                        fixed.add(act.startsWith("cmd:shop ") ? "shop:" + act.substring(9).trim()
                                : act.equals("cmd:gamble") ? "gamble" : act);
                    }
                    if (!fixed.equals(actions)) {
                        npcs.set(id + ".actions", fixed);
                    }
                }
            }
        }
        if (from < 4) {
            // 2.15.0: the dealer of the gambling den
            addMissing(file, defaults, "npcs.dealer");
        }
        if (from < 3) {
            // 2.12.0: the village folk who give quests
            for (String id : List.of("village_elder", "village_fisher", "village_smith", "village_hunter")) {
                addMissing(file, defaults, "npcs." + id);
            }
        }
    }
}
