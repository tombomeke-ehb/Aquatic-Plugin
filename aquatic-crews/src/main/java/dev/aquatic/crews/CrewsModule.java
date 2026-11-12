package dev.aquatic.crews;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewRank;
import dev.aquatic.api.CrewRelation;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.TournamentService;
import dev.aquatic.api.event.CrewCreateEvent;
import dev.aquatic.api.event.CrewDisbandEvent;
import dev.aquatic.api.event.CrewJoinEvent;
import dev.aquatic.api.event.CrewLeaveEvent;
import dev.aquatic.api.event.CrewWarDeclareEvent;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.core.AquaticModule;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Pirate crews: membership, ranks, diplomacy, wars, reputation, crew chat, bank and home. */
public final class CrewsModule extends AquaticModule implements CrewService {

    final Map<String, CrewImpl> crews = new ConcurrentHashMap<>();
    final Map<UUID, CrewImpl> byPlayer = new ConcurrentHashMap<>();
    /** invitee -> (crew id -> expiry millis) */
    final Map<UUID, Map<String, Long>> invites = new HashMap<>();
    /** "fromCrewId>toCrewId" -> expiry millis */
    final Map<String, Long> allyRequests = new HashMap<>();
    final Map<String, Long> peaceRequests = new HashMap<>();
    /** Players whose normal chat currently goes to crew chat. */
    final Set<UUID> crewChat = ConcurrentHashMap.newKeySet();

    private volatile boolean dirty;
    private Object papiHook;
    private CrewListener listener;

    // settings cached for the async chat renderer
    private volatile boolean chatPrefixEnabled;
    private volatile String chatPrefixFormat = "";
    private volatile String kingPrefix = "";
    private volatile boolean tabEnabled;
    private volatile String tabFormat = "";

    @Override
    public String id() {
        return "crews";
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onEnable() {
        crews.clear();
        byPlayer.clear();
        invites.clear();
        allyRequests.clear();
        peaceRequests.clear();
        crewChat.clear();

        chatPrefixEnabled = config().getBoolean("chat.prefix-enabled", true);
        chatPrefixFormat = config().getString("chat.prefix-format", "<dark_gray>[<tag><dark_gray>] ");
        kingPrefix = config().getString("chat.pirate-king-prefix", "");
        tabEnabled = config().getBoolean("tablist.enabled", true);
        tabFormat = config().getString("tablist.format", "<dark_gray>[<tag><dark_gray>] <white><player>");

        loadCrews();
        registerService(CrewService.class, this);
        listener = new CrewListener(this);
        registerListener(listener);
        for (AquaticCommand command : new CrewCommands(this, listener).build()) {
            registerCommand(command);
        }

        long period = Math.max(10, config().getLong("autosave-seconds", 120)) * 20L;
        runTimer(this::saveIfDirty, period, period);
        runTimer(this::purgeExpired, 20L * 60, 20L * 60);

        for (Player p : Bukkit.getOnlinePlayers()) {
            CrewImpl crew = byPlayer.get(p.getUniqueId());
            if (crew != null) {
                crew.names.put(p.getUniqueId(), p.getName());
            }
            updateTab(p);
        }

        if (config().getBoolean("placeholderapi.enabled", true) && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                papiHook = PapiHook.register(this);
                logger().info("[crews] Registered PlaceholderAPI expansion 'aquatic'.");
            } catch (Throwable t) {
                logger().log(Level.WARNING, "[crews] Could not hook into PlaceholderAPI", t);
            }
        }
    }

    @Override
    protected void onDisable() {
        if (papiHook != null) {
            try {
                PapiHook.unregister(papiHook);
            } catch (Throwable t) {
                logger().log(Level.WARNING, "[crews] Could not unhook from PlaceholderAPI", t);
            }
            papiHook = null;
        }
        if (listener != null) {
            listener.shutdown();
            listener = null;
        }
        if (tabEnabled) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (byPlayer.containsKey(p.getUniqueId())) {
                    p.playerListName(null);
                }
            }
        }
        dirty = true;
        saveIfDirty();
        crews.clear();
        byPlayer.clear();
        invites.clear();
        allyRequests.clear();
        peaceRequests.clear();
        crewChat.clear();
    }

    void markDirty() {
        dirty = true;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        allyRequests.values().removeIf(expiry -> expiry < now);
        peaceRequests.values().removeIf(expiry -> expiry < now);
        Iterator<Map<String, Long>> it = invites.values().iterator();
        while (it.hasNext()) {
            Map<String, Long> map = it.next();
            map.values().removeIf(expiry -> expiry < now);
            if (map.isEmpty()) {
                it.remove();
            }
        }
        if (listener != null) {
            listener.purge(now);
        }
    }

    // ------------------------------------------------------------------ persistence

    private void loadCrews() {
        YamlConfiguration data = loadData("crews");
        ConfigurationSection root = data.getConfigurationSection("crews");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            try {
                CrewImpl crew = new CrewImpl(this, id);
                crew.name = s.getString("name", id);
                crew.tag = s.getString("tag", crew.name.substring(0, Math.min(3, crew.name.length())).toUpperCase(Locale.ROOT));
                TextColor color = parseColor(s.getString("color", ""), true);
                crew.color = color != null ? color : defaultColor();
                crew.created = s.getLong("created", System.currentTimeMillis());
                crew.reputation = s.getInt("reputation", 0);
                double bank = s.getDouble("bank", 0);
                crew.bank = Double.isNaN(bank) || Double.isInfinite(bank) || bank < 0 ? 0 : bank;
                crew.friendlyFire = s.getBoolean("friendly-fire", false);
                String flag = s.getString("flag");
                if (flag != null && !flag.isEmpty()) {
                    try {
                        crew.flag = ItemStack.deserializeBytes(Base64.getDecoder().decode(flag));
                    } catch (Throwable t) {
                        logger().warning("[crews] Could not read the flag of crew " + crew.name + ", using a white banner.");
                    }
                }
                if (s.isConfigurationSection("home")) {
                    crew.homeWorld = s.getString("home.world");
                    crew.homeX = s.getDouble("home.x");
                    crew.homeY = s.getDouble("home.y");
                    crew.homeZ = s.getDouble("home.z");
                    crew.homeYaw = (float) s.getDouble("home.yaw");
                    crew.homePitch = (float) s.getDouble("home.pitch");
                }
                ConfigurationSection members = s.getConfigurationSection("members");
                if (members != null) {
                    for (String key : members.getKeys(false)) {
                        UUID uuid = UUID.fromString(key);
                        if (byPlayer.containsKey(uuid)) {
                            continue; // corrupted file: a player can only be in one crew
                        }
                        CrewRank rank = parseRank(members.getString(key + ".rank", "MEMBER"));
                        crew.ranks.put(uuid, rank == null || rank == CrewRank.CAPTAIN ? CrewRank.MEMBER : rank);
                        String name = members.getString(key + ".name");
                        if (name != null) {
                            crew.names.put(uuid, name);
                        }
                    }
                }
                if (crew.ranks.isEmpty()) {
                    logger().warning("[crews] Dropping crew " + crew.name + ": it has no members.");
                    continue;
                }
                UUID captain = null;
                try {
                    captain = UUID.fromString(s.getString("captain", ""));
                } catch (IllegalArgumentException ignored) {
                }
                if (captain == null || !crew.ranks.containsKey(captain)) {
                    captain = crew.sortedMembers().get(0);
                    logger().warning("[crews] Crew " + crew.name + " had no valid captain; promoted " + crew.nameOf(captain) + ".");
                }
                crew.captain = captain;
                crew.ranks.put(captain, CrewRank.CAPTAIN);
                crew.allies.addAll(s.getStringList("allies"));
                crew.rivals.addAll(s.getStringList("rivals"));
                ConfigurationSection wars = s.getConfigurationSection("wars");
                if (wars != null) {
                    for (String enemy : wars.getKeys(false)) {
                        crew.wars.put(enemy, new CrewImpl.War(wars.getLong(enemy + ".since"), wars.getInt(enemy + ".kills")));
                    }
                }
                crews.put(id, crew);
                for (UUID member : crew.ranks.keySet()) {
                    byPlayer.put(member, crew);
                }
            } catch (Exception e) {
                logger().log(Level.WARNING, "[crews] Skipping broken crew entry '" + id + "' in crews.yml", e);
            }
        }
        // repair relations: drop references to crews that no longer exist and keep them symmetric
        for (CrewImpl crew : crews.values()) {
            crew.allies.removeIf(other -> other.equals(crew.id) || !crews.containsKey(other) || !crews.get(other).allies.contains(crew.id));
            crew.rivals.removeIf(other -> other.equals(crew.id) || !crews.containsKey(other));
            crew.wars.keySet().removeIf(other -> other.equals(crew.id) || !crews.containsKey(other));
        }
        for (CrewImpl crew : crews.values()) {
            for (Map.Entry<String, CrewImpl.War> war : crew.wars.entrySet()) {
                CrewImpl enemy = crews.get(war.getKey());
                enemy.wars.putIfAbsent(crew.id, new CrewImpl.War(war.getValue().since, 0));
                enemy.allies.remove(crew.id);
                crew.allies.remove(enemy.id);
            }
        }
    }

    private void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        YamlConfiguration data = new YamlConfiguration();
        for (CrewImpl crew : crews.values()) {
            String path = "crews." + crew.id + ".";
            data.set(path + "name", crew.name);
            data.set(path + "tag", crew.tag);
            data.set(path + "color", crew.color.asHexString());
            data.set(path + "captain", String.valueOf(crew.captain));
            data.set(path + "created", crew.created);
            data.set(path + "reputation", crew.reputation);
            data.set(path + "bank", crew.bank);
            data.set(path + "friendly-fire", crew.friendlyFire);
            ItemStack flag = crew.flag;
            if (flag != null) {
                try {
                    data.set(path + "flag", Base64.getEncoder().encodeToString(flag.serializeAsBytes()));
                } catch (Throwable t) {
                    logger().log(Level.WARNING, "[crews] Could not save the flag of crew " + crew.name, t);
                }
            }
            if (crew.hasHome()) {
                data.set(path + "home.world", crew.homeWorld);
                data.set(path + "home.x", crew.homeX);
                data.set(path + "home.y", crew.homeY);
                data.set(path + "home.z", crew.homeZ);
                data.set(path + "home.yaw", crew.homeYaw);
                data.set(path + "home.pitch", crew.homePitch);
            }
            for (Map.Entry<UUID, CrewRank> member : crew.ranks.entrySet()) {
                String mp = path + "members." + member.getKey() + ".";
                data.set(mp + "rank", member.getValue().name());
                String name = crew.names.get(member.getKey());
                if (name != null) {
                    data.set(mp + "name", name);
                }
            }
            data.set(path + "allies", new ArrayList<>(crew.allies));
            data.set(path + "rivals", new ArrayList<>(crew.rivals));
            for (Map.Entry<String, CrewImpl.War> war : crew.wars.entrySet()) {
                data.set(path + "wars." + war.getKey() + ".since", war.getValue().since);
                data.set(path + "wars." + war.getKey() + ".kills", war.getValue().kills);
            }
        }
        saveData("crews", data);
    }

    // ------------------------------------------------------------------ CrewService

    @Override
    public Crew crewOf(UUID player) {
        return player == null ? null : byPlayer.get(player);
    }

    @Override
    public Crew crewById(String id) {
        return id == null ? null : crews.get(id);
    }

    @Override
    public Crew crewByName(String name) {
        return findByName(name);
    }

    @Override
    public Collection<Crew> crews() {
        return Collections.unmodifiableCollection(new ArrayList<Crew>(crews.values()));
    }

    @Override
    public boolean sameCrew(UUID a, UUID b) {
        if (a == null || b == null) {
            return false;
        }
        CrewImpl crew = byPlayer.get(a);
        return crew != null && crew == byPlayer.get(b);
    }

    @Override
    public CrewRelation relation(Crew a, Crew b) {
        if (a == null || b == null) {
            return CrewRelation.NEUTRAL;
        }
        if (a.id().equals(b.id())) {
            return CrewRelation.SAME;
        }
        CrewImpl x = crews.get(a.id());
        CrewImpl y = crews.get(b.id());
        if (x == null || y == null) {
            return CrewRelation.NEUTRAL;
        }
        if (x.wars.containsKey(y.id) || y.wars.containsKey(x.id)) {
            return CrewRelation.WAR;
        }
        if (x.allies.contains(y.id) && y.allies.contains(x.id)) {
            return CrewRelation.ALLY;
        }
        if (x.rivals.contains(y.id) || y.rivals.contains(x.id)) {
            return CrewRelation.RIVAL;
        }
        return CrewRelation.NEUTRAL;
    }

    @Override
    public CrewRelation relation(UUID a, UUID b) {
        if (a == null || b == null) {
            return CrewRelation.NEUTRAL;
        }
        return relation(byPlayer.get(a), byPlayer.get(b));
    }

    @Override
    public int maxMembers() {
        return Math.max(1, config().getInt("max-members", 6));
    }

    // ------------------------------------------------------------------ lookups & settings

    CrewImpl findByName(String name) {
        if (name == null) {
            return null;
        }
        for (CrewImpl crew : crews.values()) {
            if (crew.name.equalsIgnoreCase(name)) {
                return crew;
            }
        }
        return null;
    }

    CrewImpl findByTag(String tag) {
        if (tag == null) {
            return null;
        }
        for (CrewImpl crew : crews.values()) {
            if (crew.tag.equalsIgnoreCase(tag)) {
                return crew;
            }
        }
        return null;
    }

    EconomyService economy() {
        return service(EconomyService.class);
    }

    /** Formats money with the economy module when it is enabled, plain number otherwise. */
    String money(double amount) {
        EconomyService eco = economy();
        return eco != null ? eco.format(amount) : Text.number(amount);
    }

    double maxBank() {
        double max = config().getDouble("bank.max-balance", 1_000_000_000d);
        return Double.isNaN(max) || max <= 0 ? 1_000_000_000d : max;
    }

    double cost(String path) {
        double value = config().getDouble(path, 0);
        return Double.isNaN(value) || Double.isInfinite(value) || value < 0 ? 0 : value;
    }

    String rankName(CrewRank rank) {
        return config().getString("rank-names." + rank.name(), rank.display());
    }

    /** Maximum number of members holding a rank; -1 = unlimited. */
    int rankLimit(CrewRank rank) {
        if (rank == CrewRank.CAPTAIN) {
            return 1;
        }
        if (rank == CrewRank.MEMBER) {
            return -1;
        }
        return config().getInt("rank-limits." + rank.name(), -1);
    }

    /** Whether the member may perform an action listed under "permissions:" in the config. Captains always may. */
    boolean allowed(CrewImpl crew, UUID player, String action) {
        CrewRank rank = crew.rankOf(player);
        if (rank == null) {
            return false;
        }
        if (rank == CrewRank.CAPTAIN) {
            return true;
        }
        for (String name : config().getStringList("permissions." + action)) {
            if (name.equalsIgnoreCase(rank.name())) {
                return true;
            }
        }
        return false;
    }

    static CrewRank parseRank(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (key) {
            case "FIRSTMATE", "MATE" -> key = "FIRST_MATE";
            case "CREW_MEMBER", "CREWMEMBER" -> key = "MEMBER";
            default -> {
            }
        }
        try {
            return CrewRank.valueOf(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    TextColor defaultColor() {
        TextColor color = parseColor(config().getString("default-color", "aqua"), true);
        return color != null ? color : NamedTextColor.AQUA;
    }

    /** @return the colour, or null when the input is not a known colour name / hex code. */
    static TextColor parseColor(String raw, boolean allowHex) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        NamedTextColor named = NamedTextColor.NAMES.value(key);
        if (named != null) {
            return named;
        }
        if (allowHex && key.matches("#?[0-9a-f]{6}")) {
            return TextColor.fromHexString(key.startsWith("#") ? key : "#" + key);
        }
        return null;
    }

    CommandFail fail(String key, Object... placeholders) {
        return new CommandFail(rawMsg(key, placeholders));
    }

    private boolean matches(String path, String fallback, String value) {
        String regex = config().getString(path, fallback);
        try {
            return Pattern.compile(regex).matcher(value).matches();
        } catch (PatternSyntaxException e) {
            logger().warning("[crews] Invalid regular expression at " + path + ": " + regex);
            return Pattern.compile(fallback).matcher(value).matches();
        }
    }

    /** Checks length, charset, blocked words and uniqueness. "self" is the crew being renamed (or null). */
    void validateName(String name, CrewImpl self) {
        int min = Math.max(1, config().getInt("name.min-length", 3));
        int max = Math.max(min, config().getInt("name.max-length", 16));
        if (name.length() < min || name.length() > max) {
            throw fail("name-length", "min", min, "max", max);
        }
        if (!matches("name.allowed-characters", "[A-Za-z0-9_]+", name)) {
            throw fail("name-invalid");
        }
        if (blocked(name)) {
            throw fail("name-blocked");
        }
        CrewImpl byName = findByName(name);
        CrewImpl byTag = findByTag(name);
        if ((byName != null && byName != self) || (byTag != null && byTag != self)) {
            throw fail("name-taken", "name", name);
        }
    }

    void validateTag(String tag, CrewImpl self) {
        int min = Math.max(1, config().getInt("tag.min-length", 2));
        int max = Math.max(min, config().getInt("tag.max-length", 4));
        if (tag.length() < min || tag.length() > max) {
            throw fail("tag-length", "min", min, "max", max);
        }
        if (!matches("tag.allowed-characters", "[A-Za-z0-9]+", tag)) {
            throw fail("tag-invalid");
        }
        if (blocked(tag)) {
            throw fail("name-blocked");
        }
        CrewImpl byTag = findByTag(tag);
        CrewImpl byName = findByName(tag);
        if ((byTag != null && byTag != self) || (byName != null && byName != self)) {
            throw fail("tag-taken", "tag", tag);
        }
    }

    private boolean blocked(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : config().getStringList("name.blocked-words")) {
            if (!word.isEmpty() && lower.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Derives a free tag from a crew name, or null when every candidate is taken. */
    String suggestTag(String name) {
        int max = Math.max(1, config().getInt("tag.max-length", 4));
        int min = Math.max(1, config().getInt("tag.min-length", 2));
        String letters = name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        for (int len = Math.min(Math.min(3, max), letters.length()); len <= Math.min(max, letters.length()); len++) {
            if (len < min) {
                continue;
            }
            String candidate = letters.substring(0, len);
            try {
                validateTag(candidate, null);
                return candidate;
            } catch (CommandFail ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ membership

    CrewImpl create(Player founder, String name, String tag) {
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, 8);
        } while (crews.containsKey(id));
        CrewImpl crew = new CrewImpl(this, id);
        crew.name = name;
        crew.tag = tag;
        crew.color = defaultColor();
        crew.captain = founder.getUniqueId();
        crew.created = System.currentTimeMillis();
        crew.reputation = Math.max(0, config().getInt("reputation.starting", 0));
        crew.friendlyFire = config().getBoolean("friendly-fire.default", false);
        crew.ranks.put(founder.getUniqueId(), CrewRank.CAPTAIN);
        crew.names.put(founder.getUniqueId(), founder.getName());
        crews.put(id, crew);
        byPlayer.put(founder.getUniqueId(), crew);
        invites.remove(founder.getUniqueId());
        markDirty();
        updateTab(founder);
        Bukkit.getPluginManager().callEvent(new CrewCreateEvent(crew, founder));
        return crew;
    }

    void addMember(CrewImpl crew, UUID player, String name) {
        crew.ranks.put(player, CrewRank.MEMBER);
        if (name != null) {
            crew.names.put(player, name);
        }
        byPlayer.put(player, crew);
        invites.remove(player);
        markDirty();
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            updateTab(online);
        }
        Bukkit.getPluginManager().callEvent(new CrewJoinEvent(crew, player));
    }

    /** Removes a non-captain member (leave or kick). */
    void removeMember(CrewImpl crew, UUID player) {
        if (crew.ranks.remove(player) == null) {
            return;
        }
        crew.names.remove(player);
        byPlayer.remove(player, crew);
        crewChat.remove(player);
        markDirty();
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            if (listener != null) {
                listener.cancelWarmup(online, null);
            }
            updateTab(online);
        }
        Bukkit.getPluginManager().callEvent(new CrewLeaveEvent(crew, player));
    }

    /** Hands the captaincy to another member; the old captain becomes the given rank. */
    void setCaptain(CrewImpl crew, UUID newCaptain) {
        UUID old = crew.captain;
        if (old != null && !old.equals(newCaptain) && crew.ranks.containsKey(old)) {
            CrewRank fallback = CrewRank.FIRST_MATE;
            int limit = rankLimit(fallback);
            // the new captain may be vacating the First Mate slot
            int used = crew.countRank(fallback) - (crew.rankOf(newCaptain) == fallback ? 1 : 0);
            crew.ranks.put(old, limit >= 0 && used >= limit ? CrewRank.MEMBER : fallback);
        }
        crew.ranks.put(newCaptain, CrewRank.CAPTAIN);
        crew.captain = newCaptain;
        markDirty();
    }

    /**
     * Disbands a crew: ends its wars and alliances, refunds the bank to the captain (config) and
     * fires CrewDisbandEvent. The event still sees the member list of the disbanded crew.
     */
    void disband(CrewImpl crew) {
        if (crews.remove(crew.id) == null) {
            return;
        }
        for (String enemyId : new ArrayList<>(crew.wars.keySet())) {
            CrewImpl enemy = crews.get(enemyId);
            if (enemy != null) {
                enemy.wars.remove(crew.id);
                enemy.broadcast(msg("war-ended-disband", "crew", crew.displayName()));
            }
        }
        for (CrewImpl other : crews.values()) {
            if (other.allies.remove(crew.id)) {
                other.broadcast(msg("ally-ended-disband", "crew", crew.displayName()));
            }
            other.rivals.remove(crew.id);
        }
        allyRequests.keySet().removeIf(key -> involves(key, crew.id));
        peaceRequests.keySet().removeIf(key -> involves(key, crew.id));
        for (Map<String, Long> map : invites.values()) {
            map.remove(crew.id);
        }

        double bank = crew.bank;
        EconomyService eco = economy();
        if (bank > 0 && eco != null && config().getBoolean("bank.refund-on-disband", true)) {
            eco.deposit(crew.captain, bank);
            crew.bank = 0;
            Player captain = Bukkit.getPlayer(crew.captain);
            if (captain != null) {
                send(captain, "bank-refunded", "amount", eco.format(bank));
            }
        }

        for (UUID member : crew.ranks.keySet()) {
            byPlayer.remove(member, crew);
            crewChat.remove(member);
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                if (listener != null) {
                    listener.cancelWarmup(online, null);
                }
                updateTab(online);
            }
        }
        markDirty();
        Bukkit.getPluginManager().callEvent(new CrewDisbandEvent(crew));
    }

    private static boolean involves(String requestKey, String crewId) {
        return requestKey.startsWith(crewId + ">") || requestKey.endsWith(">" + crewId);
    }

    // ------------------------------------------------------------------ diplomacy

    static String requestKey(CrewImpl from, CrewImpl to) {
        return from.id + ">" + to.id;
    }

    boolean hasRequest(Map<String, Long> requests, CrewImpl from, CrewImpl to) {
        Long expiry = requests.get(requestKey(from, to));
        return expiry != null && expiry >= System.currentTimeMillis();
    }

    long requestExpiry() {
        return System.currentTimeMillis() + Math.max(10, config().getLong("diplomacy.request-expire-seconds", 300)) * 1000L;
    }

    boolean atWar(CrewImpl a, CrewImpl b) {
        return a.wars.containsKey(b.id) || b.wars.containsKey(a.id);
    }

    boolean allied(CrewImpl a, CrewImpl b) {
        return a.allies.contains(b.id) && b.allies.contains(a.id);
    }

    void formAlliance(CrewImpl a, CrewImpl b) {
        a.allies.add(b.id);
        b.allies.add(a.id);
        a.rivals.remove(b.id);
        b.rivals.remove(a.id);
        allyRequests.remove(requestKey(a, b));
        allyRequests.remove(requestKey(b, a));
        markDirty();
        a.broadcast(msg("ally-formed", "crew", b.displayName()));
        b.broadcast(msg("ally-formed", "crew", a.displayName()));
    }

    /** @return true when an alliance existed and was broken. */
    boolean breakAlliance(CrewImpl a, CrewImpl b) {
        boolean had = a.allies.remove(b.id) | b.allies.remove(a.id);
        if (had) {
            markDirty();
        }
        return had;
    }

    void startWar(CrewImpl attacker, CrewImpl defender) {
        long now = System.currentTimeMillis();
        attacker.wars.put(defender.id, new CrewImpl.War(now, 0));
        defender.wars.put(attacker.id, new CrewImpl.War(now, 0));
        breakAlliance(attacker, defender);
        allyRequests.remove(requestKey(attacker, defender));
        allyRequests.remove(requestKey(defender, attacker));
        peaceRequests.remove(requestKey(attacker, defender));
        peaceRequests.remove(requestKey(defender, attacker));
        markDirty();
        if (config().getBoolean("war.announce", true)) {
            Bukkit.broadcast(msg("war-declared", "attacker", attacker.displayName(), "defender", defender.displayName()));
        } else {
            Component message = msg("war-declared", "attacker", attacker.displayName(), "defender", defender.displayName());
            attacker.broadcast(message);
            defender.broadcast(message);
        }
        Bukkit.getPluginManager().callEvent(new CrewWarDeclareEvent(attacker, defender));
    }

    void endWar(CrewImpl a, CrewImpl b) {
        CrewImpl.War wa = a.wars.remove(b.id);
        CrewImpl.War wb = b.wars.remove(a.id);
        peaceRequests.remove(requestKey(a, b));
        peaceRequests.remove(requestKey(b, a));
        markDirty();
        Component message = msg("war-ended", "a", a.displayName(), "b", b.displayName(),
                "a_kills", wa == null ? 0 : wa.kills, "b_kills", wb == null ? 0 : wb.kills);
        if (config().getBoolean("war.announce", true)) {
            Bukkit.broadcast(message);
        } else {
            a.broadcast(message);
            b.broadcast(message);
        }
    }

    // ------------------------------------------------------------------ chat & tab list

    /** Prefix for public chat (Pirate King crown + crew tag), or null when there is nothing to add. Thread-safe. */
    Component chatPrefix(UUID player) {
        if (!chatPrefixEnabled) {
            return null;
        }
        Component prefix = null;
        String crown = kingPrefix;
        if (crown != null && !crown.isEmpty()) {
            TournamentService tournament = service(TournamentService.class);
            if (tournament != null && tournament.isPirateKing(player)) {
                prefix = Text.mm(crown);
            }
        }
        CrewImpl crew = byPlayer.get(player);
        if (crew != null) {
            Component tag = Text.mm(chatPrefixFormat, "tag", crew.tagComponent(), "crew", crew.displayName());
            prefix = prefix == null ? tag : Component.textOfChildren(prefix, tag);
        }
        return prefix;
    }

    void updateTab(Player player) {
        if (!tabEnabled) {
            return;
        }
        CrewImpl crew = byPlayer.get(player.getUniqueId());
        if (crew == null) {
            player.playerListName(null);
        } else {
            player.playerListName(Text.mm(tabFormat, "tag", crew.tagComponent(), "crew", crew.displayName(), "player", player.getName()));
        }
    }

    void updateTab(CrewImpl crew) {
        for (Player p : crew.onlineMembers()) {
            updateTab(p);
        }
    }

    /** Sends a crew chat line on behalf of a player (main thread). */
    void sendCrewChat(Player player, Component message) {
        if (!isEnabled() || !player.isOnline()) {
            return;
        }
        CrewImpl crew = byPlayer.get(player.getUniqueId());
        if (crew == null) {
            crewChat.remove(player.getUniqueId());
            send(player, "not-in-crew");
            return;
        }
        CrewRank rank = crew.rankOf(player.getUniqueId());
        Component line = Text.mm(config().getString("chat.crew-format", "<dark_aqua>[Crew] <gray><rank> <white><player><dark_gray>: <aqua><message>"),
                "crew", crew.displayName(), "tag", crew.tagComponent(), "rank", rank == null ? "" : rankName(rank),
                "player", player.getName(), "message", message);
        crew.broadcast(line);
        if (config().getBoolean("chat.log-crew-chat", true)) {
            Bukkit.getConsoleSender().sendMessage(Component.text("[" + crew.name + "] ").append(line));
        }
    }

    // ------------------------------------------------------------------ misc helpers for commands

    List<String> crewNames() {
        List<String> out = new ArrayList<>();
        for (CrewImpl crew : crews.values()) {
            out.add(crew.name);
        }
        return out;
    }

    List<CrewImpl> topCrews() {
        List<CrewImpl> out = new ArrayList<>(crews.values());
        out.sort((a, b) -> a.reputation != b.reputation ? Integer.compare(b.reputation, a.reputation)
                : a.name.compareToIgnoreCase(b.name));
        return out;
    }

    Map<String, Long> invitesOf(UUID player) {
        Map<String, Long> map = invites.get(player);
        if (map == null) {
            return new HashMap<>();
        }
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> e.getValue() < now || !crews.containsKey(e.getKey()));
        return map;
    }
}
