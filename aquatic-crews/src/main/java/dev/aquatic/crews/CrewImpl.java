package dev.aquatic.crews;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewRank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A crew. State is mutated on the main thread only; reads are safe from any thread (chat renderer, placeholders). */
final class CrewImpl implements Crew {

    /** One side of a war: every crew at war holds its own record with its own kill count. */
    static final class War {
        long since;
        int kills;

        War(long since, int kills) {
            this.since = since;
            this.kills = kills;
        }
    }

    private final CrewsModule module;
    final String id;
    volatile String name;
    volatile String tag;
    volatile TextColor color = NamedTextColor.AQUA;
    volatile UUID captain;
    final Map<UUID, CrewRank> ranks = new ConcurrentHashMap<>();
    /** Last known names of the members, so offline members can be listed and targeted. */
    final Map<UUID, String> names = new ConcurrentHashMap<>();
    volatile ItemStack flag;
    volatile int reputation;
    volatile double bank;
    volatile boolean friendlyFire;
    long created;

    // home (stored by world name so an unloaded world does not break loading)
    String homeWorld;
    double homeX, homeY, homeZ;
    float homeYaw, homePitch;

    /** Mutual alliances (crew ids). */
    final Set<String> allies = ConcurrentHashMap.newKeySet();
    /** Crews this crew has declared its rivals (one-sided). */
    final Set<String> rivals = ConcurrentHashMap.newKeySet();
    /** Open wars, keyed by enemy crew id. */
    final Map<String, War> wars = new ConcurrentHashMap<>();

    CrewImpl(CrewsModule module, String id) {
        this.module = module;
        this.id = id;
    }

    // ------------------------------------------------------------------ Crew API

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String tag() {
        return tag;
    }

    @Override
    public TextColor color() {
        return color;
    }

    @Override
    public Component displayName() {
        return Component.text(name, color);
    }

    Component tagComponent() {
        return Component.text(tag, color);
    }

    @Override
    public UUID captain() {
        return captain;
    }

    @Override
    public Set<UUID> members() {
        return Collections.unmodifiableSet(ranks.keySet());
    }

    @Override
    public CrewRank rankOf(UUID player) {
        return player == null ? null : ranks.get(player);
    }

    @Override
    public Collection<Player> onlineMembers() {
        List<Player> out = new ArrayList<>();
        for (UUID id : ranks.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public ItemStack flag() {
        ItemStack current = flag;
        return current != null ? current.clone() : new ItemStack(Material.WHITE_BANNER);
    }

    @Override
    public int reputation() {
        return reputation;
    }

    @Override
    public void addReputation(int amount) {
        if (amount == 0) {
            return;
        }
        long min = module.config().getBoolean("reputation.allow-negative", false) ? Integer.MIN_VALUE : 0;
        reputation = (int) Math.max(min, Math.min(Integer.MAX_VALUE, (long) reputation + amount));
        module.markDirty();
    }

    @Override
    public double bankBalance() {
        return bank;
    }

    @Override
    public void depositBank(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            return;
        }
        bank = Math.min(module.maxBank(), bank + amount);
        module.markDirty();
    }

    @Override
    public boolean withdrawBank(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0 || bank + 1e-9 < amount) {
            return false;
        }
        bank = Math.max(0, bank - amount);
        module.markDirty();
        return true;
    }

    @Override
    public void broadcast(Component message) {
        for (Player p : onlineMembers()) {
            p.sendMessage(message);
        }
    }

    // ------------------------------------------------------------------ internals

    String nameOf(UUID member) {
        return Players.name(member, names.get(member));
    }

    /** Finds a member by (last known) name, case-insensitive. */
    UUID memberByName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && ranks.containsKey(online.getUniqueId())) {
            return online.getUniqueId();
        }
        for (UUID id : ranks.keySet()) {
            if (nameOf(id).equalsIgnoreCase(name)) {
                return id;
            }
        }
        return null;
    }

    int countRank(CrewRank rank) {
        int n = 0;
        for (CrewRank r : ranks.values()) {
            if (r == rank) {
                n++;
            }
        }
        return n;
    }

    /** Members ordered by rank (captain first), then name. */
    List<UUID> sortedMembers() {
        List<UUID> out = new ArrayList<>(ranks.keySet());
        out.sort(Comparator.<UUID>comparingInt(id -> {
            CrewRank r = ranks.get(id);
            return r == null ? 99 : r.ordinal();
        }).thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    boolean hasHome() {
        return homeWorld != null;
    }

    /** @return the home, or null when none is set or its world is not loaded. */
    Location home() {
        if (homeWorld == null) {
            return null;
        }
        World world = Bukkit.getWorld(homeWorld);
        return world == null ? null : new Location(world, homeX, homeY, homeZ, homeYaw, homePitch);
    }

    void setHome(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            homeWorld = null;
            return;
        }
        homeWorld = loc.getWorld().getName();
        homeX = loc.getX();
        homeY = loc.getY();
        homeZ = loc.getZ();
        homeYaw = loc.getYaw();
        homePitch = loc.getPitch();
    }
}
