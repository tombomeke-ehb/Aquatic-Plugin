package dev.aquatic.world;

import dev.aquatic.api.Crew;
import dev.aquatic.api.CrewRank;
import dev.aquatic.api.CrewService;
import dev.aquatic.api.EconomyService;
import dev.aquatic.api.IslandStatus;
import dev.aquatic.api.IslandType;
import dev.aquatic.api.event.IslandClaimEvent;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** /island (aliases /is, /islands): player and admin sub-commands. */
final class IslandCommands {

    private static final String ADMIN = "aquatic.islands.admin";
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private final IslandsModule m;
    private AquaticCommand cmd;

    IslandCommands(IslandsModule module) {
        this.m = module;
    }

    AquaticCommand build() {
        cmd = new AquaticCommand("island", "Islands of the Endless Sea", "is", "islands");

        // ---------------------------------------------------------------- player
        cmd.sub("info", this::info).usage("[island]").description("Show information about an island")
                .playerPermission("aquatic.islands.info").completer((s, a) -> a.length == 1 ? islandNames(a[0]) : List.of());
        cmd.sub("here", (s, a) -> here(AquaticCommand.player(s))).description("Which island am I standing on?")
                .playerPermission("aquatic.islands.info").playerOnly();
        cmd.sub("list", this::list).usage("[page]").description("List all islands")
                .playerPermission("aquatic.islands.list").completer((s, a) -> List.of());
        cmd.sub("home", (s, a) -> m.islandHome(AquaticCommand.player(s), a)).usage("[island]")
                .description("Sail to an island your crew owns (3 s warmup, not in combat)").playerOnly()
                .completer((s, a) -> s instanceof org.bukkit.entity.Player p
                        ? AquaticCommand.filter(m.ownIslandNames(p), a.length == 0 ? "" : a[0]) : java.util.List.of());
        cmd.sub("map", this::near).aliases("near").description("Nearest islands with direction and distance")
                .playerPermission("aquatic.islands.map").playerOnly();
        cmd.sub("discovered", this::discovered).usage("[player]").description("Islands you have discovered")
                .playerPermission("aquatic.islands.discovered")
                .completer((s, a) -> a.length == 1 && s.hasPermission(ADMIN) ? AquaticCommand.playerNames(a[0]) : List.of());
        cmd.sub("top", this::top).description("Crews ranked by islands owned").playerPermission("aquatic.islands.top");
        cmd.sub("claim", (s, a) -> claim(AquaticCommand.player(s))).description("Plant your crew's flag on this unclaimed island")
                .playerPermission("aquatic.islands.claim").playerOnly();
        cmd.sub("unclaim", (s, a) -> unclaim(AquaticCommand.player(s), a)).usage("[confirm]")
                .description("Abandon the island you stand on").playerPermission("aquatic.islands.claim").playerOnly()
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(List.of("confirm"), a[0]) : List.of());
        cmd.sub("flag", (s, a) -> flag(AquaticCommand.player(s))).description("Move/re-place your crew flag to where you stand")
                .playerPermission("aquatic.islands.flag").playerOnly();
        cmd.sub("manage", (s, a) -> {
            Player p = AquaticCommand.player(s);
            IslandImpl is = a.length > 0 ? island(String.join(" ", a)) : standingOn(p);
            m.upgrades().open(p, is);
        }).usage("[island]").description("Your island's stats and upgrades (menu)").playerOnly()
                .playerPermission("aquatic.islands.manage")
                .completer((s, a) -> a.length == 1 ? islandNames(a[0]) : List.of());
        cmd.sub("vault", (s, a) -> {
            Player p = AquaticCommand.player(s);
            IslandImpl is = a.length > 0 ? island(String.join(" ", a)) : standingOn(p);
            m.upgrades().openVault(p, is);
        }).usage("[island]").description("Open your crew's vault on this island (Vault upgrade)").playerOnly()
                .playerPermission("aquatic.islands.manage")
                .completer((s, a) -> a.length == 1 ? islandNames(a[0]) : List.of());
        cmd.sub("upgrade", (s, a) -> {
            AquaticCommand.need(a, 1, "/island upgrade <" + String.join("|", IslandUpgrades.TYPES) + ">");
            Player p = AquaticCommand.player(s);
            m.upgrades().upgrade(p, standingOn(p), a[0]);
        }).usage("<treasury|fortress|watchtower|harbor|barracks|wellspring|vault|granary>").description("Buy the next level of an upgrade for the island you stand on")
                .playerOnly().playerPermission("aquatic.islands.manage")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(IslandUpgrades.TYPES, a[0]) : List.of());

        // ---------------------------------------------------------------- admin
        cmd.sub("admin", (s, a) -> {
            if (a.length == 0) {
                cmd.sendHiddenHelp(s, "island admin");
                return;
            }
            cmd.execute(s, "island", a);
        }).usage("<sub> ...").description("Island admin commands").permission(ADMIN)
                .completer((s, a) -> cmd.completeHidden(s, a));
        cmd.sub("spawn", this::spawn).usage("<type> <radius> [name]").description("Generate an island where you stand")
                .permission(ADMIN).hidden().playerOnly()
                .completer((s, a) -> switch (a.length) {
                    case 1 -> typeNames(a[0], false);
                    case 2 -> AquaticCommand.filter(List.of("50", "100", "250", "500"), a[1]);
                    default -> List.of();
                });
        cmd.sub("spawnrandom", this::spawnRandom).usage("<type|random> <count> [minRadius] [maxRadius]")
                .description("Scatter islands over the ocean area").permission(ADMIN).hidden()
                .completer((s, a) -> switch (a.length) {
                    case 1 -> typeNames(a[0], true);
                    case 2 -> AquaticCommand.filter(List.of("1", "5", "10"), a[1]);
                    case 3 -> AquaticCommand.filter(List.of("50", "100", "200"), a[2]);
                    case 4 -> AquaticCommand.filter(List.of("200", "350", "500"), a[3]);
                    default -> List.of();
                });
        cmd.sub("register", this::register).usage("<name> <type> <radius>")
                .description("Register existing terrain as an island (no generation)").permission(ADMIN).hidden().playerOnly()
                .completer((s, a) -> a.length == 2 ? typeNames(a[1], false) : List.of());
        cmd.sub("delete", this::delete).usage("<island> [restore]").description("Unregister an island, optionally back to ocean")
                .permission(ADMIN).hidden().completer((s, a) -> switch (a.length) {
                    case 1 -> islandNames(a[0]);
                    case 2 -> AquaticCommand.filter(List.of("restore"), a[1]);
                    default -> List.of();
                });
        cmd.sub("setstatus", this::setStatus).usage("<island> <unclaimed|neutral>").description("Change an island's status")
                .permission(ADMIN).hidden().completer((s, a) -> switch (a.length) {
                    case 1 -> islandNames(a[0]);
                    case 2 -> AquaticCommand.filter(List.of("unclaimed", "neutral"), a[1]);
                    default -> List.of();
                });
        cmd.sub("setowner", this::setOwner).usage("<island> <crew|none>").description("Give an island to a crew")
                .permission(ADMIN).hidden().completer((s, a) -> switch (a.length) {
                    case 1 -> islandNames(a[0]);
                    case 2 -> crewNames(a[1]);
                    default -> List.of();
                });
        cmd.sub("rename", this::rename).usage("<island> <new name>").description("Rename an island")
                .permission(ADMIN).hidden().completer((s, a) -> a.length == 1 ? islandNames(a[0]) : List.of());
        cmd.sub("setradius", this::setRadius).usage("<island> <radius>").description("Change an island's protected radius")
                .permission(ADMIN).hidden().completer((s, a) -> a.length == 1 ? islandNames(a[0]) : List.of());
        cmd.sub("tp", this::tp).usage("<island>").description("Teleport to an island").permission(ADMIN).hidden().playerOnly()
                .completer((s, a) -> a.length == 1 ? islandNames(a[0]) : List.of());
        cmd.sub("regen", this::regen).usage("<island> [newseed]").description("Clear and regenerate an island's terrain")
                .permission(ADMIN).hidden().completer((s, a) -> switch (a.length) {
                    case 1 -> islandNames(a[0]);
                    case 2 -> AquaticCommand.filter(List.of("newseed"), a[1]);
                    default -> List.of();
                });
        cmd.sub("removeflag", (s, a) -> {
            AquaticCommand.need(a, 1, "/island admin removeflag <island>");
            IslandImpl is = island(String.join(" ", a));
            if (!is.hasFlag) {
                throw new CommandFail(m.rawMsg("flag-none", "island", is.name));
            }
            m.removeFlagBlocks(is);
            m.send(s, "flag-removed", "island", is.name);
        }).usage("<island>").description("Remove an island's flag (flags can't be broken, only taken over)")
                .permission(ADMIN).hidden()
                .completer((s, a) -> a.length == 1 ? islandNames(a[0]) : List.of());
        cmd.sub("setspawn", (s, a) -> setSpawn(AquaticCommand.player(s))).description("Set the server spawn exactly where you stand (the island becomes a safe zone)")
                .permission(ADMIN).hidden().playerOnly();
        cmd.sub("area", this::area).usage("<radius> [spacing]").description("Set the ocean area for random islands/treasure at your position")
                .permission(ADMIN).hidden().playerOnly()
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(List.of("1500", "3000", "5000"), a[0]) : List.of());
        cmd.sub("cancel", this::cancel).usage("[island]").description("Stop island generation (also a running spawnrandom)")
                .permission(ADMIN).hidden().completer((s, a) -> a.length == 1 ? islandNames(a[0]) : List.of());
        cmd.sub("shield", this::shield).usage("<island> <minutes>").description("Protect an island from conquest for a while")
                .permission(ADMIN).hidden().completer((s, a) -> a.length == 1 ? islandNames(a[0]) : List.of());
        return cmd;
    }

    // ------------------------------------------------------------------ helpers

    private List<String> islandNames(String prefix) {
        List<String> out = new ArrayList<>();
        for (IslandImpl is : m.all()) {
            out.add(is.id);
        }
        return AquaticCommand.filter(out, prefix);
    }

    private static List<String> typeNames(String prefix, boolean withRandom) {
        List<String> out = new ArrayList<>();
        if (withRandom) {
            out.add("random");
        }
        for (IslandType t : IslandType.values()) {
            out.add(t.name().toLowerCase(Locale.ROOT));
        }
        return AquaticCommand.filter(out, prefix);
    }

    private List<String> crewNames(String prefix) {
        List<String> out = new ArrayList<>();
        out.add("none");
        CrewService crews = m.service(CrewService.class);
        if (crews != null) {
            for (Crew c : crews.crews()) {
                out.add(c.name());
            }
        }
        return AquaticCommand.filter(out, prefix);
    }

    private IslandImpl island(String arg) {
        IslandImpl is = m.find(arg);
        if (is == null) {
            throw new CommandFail(m.rawMsg("unknown-island", "island", arg));
        }
        return is;
    }

    private IslandImpl standingOn(Player p) {
        IslandImpl is = m.at(p.getLocation());
        if (is == null) {
            throw new CommandFail(m.rawMsg("not-on-island"));
        }
        return is;
    }

    private static IslandType type(String raw) {
        IslandType t = IslandType.parse(raw);
        if (t == null) {
            throw new CommandFail("<red>Unknown island type <white><type></white>. Types: <gray><list>",
                    "type", raw, "list", String.join(", ", Arrays.stream(IslandType.values()).map(x -> x.name().toLowerCase(Locale.ROOT)).toList()));
        }
        return t;
    }

    private int checkedRadius(String raw) {
        int r = AquaticCommand.integer(raw);
        int min = m.config().getInt("generation.min-radius", 16), max = m.config().getInt("generation.radius-limit", 750);
        if (r < min || r > max) {
            throw new CommandFail(m.rawMsg("bad-radius", "min", min, "max", max));
        }
        return r;
    }

    private static String direction(double dx, double dz, boolean arrow) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north, clockwise
        int idx = (int) Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
        return arrow ? ARROWS[idx] + " " + COMPASS[idx] : COMPASS[idx];
    }

    private CrewService crews() {
        CrewService crews = m.service(CrewService.class);
        if (crews == null) {
            throw new CommandFail(m.rawMsg("crews-disabled"));
        }
        return crews;
    }

    // ------------------------------------------------------------------ player commands

    private void info(CommandSender sender, String[] args) {
        IslandImpl is;
        if (args.length > 0) {
            is = island(String.join(" ", args));
        } else {
            is = standingOn(AquaticCommand.player(sender));
        }
        showInfo(sender, is);
    }

    private void here(Player p) {
        IslandImpl is = m.at(p.getLocation());
        if (is == null) {
            m.send(p, "open-sea");
            return;
        }
        showInfo(p, is);
    }

    private void showInfo(CommandSender sender, IslandImpl is) {
        sender.sendMessage(m.rawMsg("info-header", "island", is.name));
        sender.sendMessage(m.rawMsg("info-type", "type", m.typeName(is.type), "id", is.id));
        sender.sendMessage(m.rawMsg("info-rarity", "rarity", m.rarityName(is.type)));
        if (is.type == dev.aquatic.api.IslandType.TIDEWATCH && is.status != IslandStatus.OCCUPIED && m.garrison() != null) {
            sender.sendMessage(m.garrison().holds(is) ? m.rawMsg("info-tidewatch-held")
                    : m.rawMsg("info-tidewatch-free", "time", Text.duration(m.garrison().liberatedSecondsLeft(is))));
        }
        sender.sendMessage(m.rawMsg("info-owner", "owner", m.ownerLine(is)));
        sender.sendMessage(m.rawMsg("info-location", "x", is.x, "z", is.z, "radius", is.radius, "world", is.world));
        if (is.id.equals(m.spawnIslandId())) {
            sender.sendMessage(m.rawMsg("info-spawn"));
        }
        if (is.status == IslandStatus.OCCUPIED) {
            long now = System.currentTimeMillis();
            if (is.shieldUntil > now) {
                sender.sendMessage(m.rawMsg("info-shield", "time", Text.duration((is.shieldUntil - now) / 1000)));
            }
            if (is.hasFlag) {
                sender.sendMessage(m.rawMsg("info-flag", "x", is.flagX, "y", is.flagY, "z", is.flagZ));
            }
            if (m.conquest().underAttack(is)) {
                sender.sendMessage(m.rawMsg("info-under-attack"));
            }
        }
        double income = m.config().getDouble("income.per-type." + is.type.name(), m.config().getDouble("income.default", 0));
        if (m.config().getBoolean("income.enabled", true) && income > 0 && is.status != IslandStatus.NEUTRAL) {
            EconomyService eco = m.service(EconomyService.class);
            sender.sendMessage(m.rawMsg("info-income", "amount", eco != null ? eco.format(income) : Text.number(income),
                    "minutes", m.config().getInt("income.interval-minutes", 30)));
        }
        if (sender instanceof Player p) {
            sender.sendMessage(m.rawMsg(m.discoveriesOf(p.getUniqueId()).contains(is.id) ? "info-discovered" : "info-undiscovered"));
        }
    }

    private void list(CommandSender sender, String[] args) {
        List<IslandImpl> all = new ArrayList<>(m.all());
        if (all.isEmpty()) {
            m.send(sender, "list-empty");
            return;
        }
        all.sort(Comparator.comparing(is -> is.name.toLowerCase(Locale.ROOT)));
        int perPage = 8;
        int pages = (all.size() + perPage - 1) / perPage;
        int page = args.length > 0 ? AquaticCommand.integer(args[0]) : 1;
        page = Math.max(1, Math.min(pages, page));
        sender.sendMessage(m.rawMsg("list-header", "page", page, "pages", pages, "count", all.size()));
        Location here = sender instanceof Player p ? p.getLocation() : null;
        for (int i = (page - 1) * perPage; i < Math.min(all.size(), page * perPage); i++) {
            IslandImpl is = all.get(i);
            String dist = here != null && here.getWorld() != null && here.getWorld().getName().equals(is.world)
                    ? Text.number(Math.round(is.distance(here.getBlockX(), here.getBlockZ()))) + "m" : "-";
            net.kyori.adventure.text.Component line = m.rawMsg("list-entry", "island", is.name, "id", is.id,
                    "type", m.typeName(is.type), "owner", m.ownerLine(is), "distance", dist, "x", is.x, "z", is.z);
            if (sender.hasPermission(ADMIN) && sender instanceof Player) {
                // admins get a clickable teleport button behind every island
                line = line.append(net.kyori.adventure.text.Component.space()).append(m.rawMsg("list-tp")
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/island admin tp " + is.id))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(m.rawMsg("list-tp-hover", "island", is.name))));
            }
            sender.sendMessage(line);
        }
        if (page < pages) {
            sender.sendMessage(m.rawMsg("list-footer", "next", page + 1));
        }
    }

    private void near(CommandSender sender, String[] args) {
        Player p = AquaticCommand.player(sender);
        Location loc = p.getLocation();
        String world = p.getWorld().getName();
        boolean hide = m.config().getBoolean("map.hide-undiscovered-names", true);
        int count = Math.max(1, m.config().getInt("map.count", 8));
        int rounding = Math.max(1, m.config().getInt("map.distance-rounding", 10));
        Set<String> known = m.discoveriesOf(p.getUniqueId());
        List<IslandImpl> list = new ArrayList<>();
        for (IslandImpl is : m.all()) {
            if (is.world.equals(world)) {
                list.add(is);
            }
        }
        if (list.isEmpty()) {
            m.send(p, "list-empty");
            return;
        }
        list.sort(Comparator.comparingDouble(is -> is.distance(loc.getBlockX(), loc.getBlockZ())));
        p.sendMessage(m.rawMsg("map-header"));
        for (IslandImpl is : list.subList(0, Math.min(count, list.size()))) {
            double dx = is.x - loc.getX(), dz = is.z - loc.getZ();
            double dist = Math.max(0, Math.sqrt(dx * dx + dz * dz) - is.radius);
            long rounded = Math.round(dist / rounding) * rounding;
            boolean onIt = is.contains(loc);
            boolean reveal = !hide || known.contains(is.id) || p.hasPermission(ADMIN);
            p.sendMessage(m.rawMsg(onIt ? "map-entry-here" : "map-entry",
                    "island", reveal ? is.name : Text.plain(m.rawMsg("unknown-island-name")),
                    "type", reveal ? m.typeName(is.type) : m.rawMsg("unknown-island-type"),
                    "owner", reveal ? m.ownerLine(is) : Component.empty(),
                    "distance", Text.number(rounded), "direction", direction(dx, dz, true)));
        }
    }

    private void discovered(CommandSender sender, String[] args) {
        UUID target;
        String name;
        if (args.length > 0 && sender.hasPermission(ADMIN)) {
            // cached lookup only: never block the main thread on a Mojang profile request
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (op == null) {
                throw new dev.aquatic.command.CommandFail("<red>Unknown player: <white><name>", "name", args[0]);
            }
            target = op.getUniqueId();
            name = op.getName() == null ? args[0] : op.getName();
        } else {
            Player p = AquaticCommand.player(sender);
            target = p.getUniqueId();
            name = p.getName();
        }
        Set<String> set = m.discoveriesOf(target);
        List<String> names = new ArrayList<>();
        for (String id : set) {
            IslandImpl is = m.byId(id);
            if (is != null) {
                names.add(is.name);
            }
        }
        sender.sendMessage(m.rawMsg("discovered-header", "player", name, "count", names.size(), "total", m.all().size()));
        if (names.isEmpty()) {
            sender.sendMessage(m.rawMsg("discovered-none"));
        } else {
            sender.sendMessage(m.rawMsg("discovered-list", "islands", String.join(", ", names)));
        }
    }

    private void top(CommandSender sender, String[] args) {
        CrewService crews = crews();
        Map<String, Integer> counts = new HashMap<>();
        for (IslandImpl is : m.all()) {
            if (is.status == IslandStatus.OCCUPIED && is.ownerCrewId != null) {
                counts.merge(is.ownerCrewId, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            m.send(sender, "top-empty");
            return;
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        sender.sendMessage(m.rawMsg("top-header"));
        int rank = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            Crew crew = crews.crewById(e.getKey());
            if (crew == null) {
                continue;
            }
            if (++rank > 10) {
                break;
            }
            sender.sendMessage(m.rawMsg("top-entry", "rank", rank, "crew", crew.displayName(), "count", e.getValue()));
        }
    }

    private Crew officerCrew(Player p, CrewService crews) {
        Crew crew = crews.crewOf(p.getUniqueId());
        if (crew == null) {
            throw new CommandFail(m.rawMsg("no-crew"));
        }
        CrewRank rank = crew.rankOf(p.getUniqueId());
        if (rank == null || !rank.isOfficer()) {
            throw new CommandFail(m.rawMsg("not-officer"));
        }
        return crew;
    }

    /** Checks that a flag pole fits at the player's feet. */
    private Block flagSpot(Player p, IslandImpl is) {
        Block feet = p.getLocation().getBlock();
        if (!is.contains(feet.getLocation())) {
            throw new CommandFail(m.rawMsg("not-on-island"));
        }
        double inner = is.radius - m.config().getDouble("conquest.capture-radius", 6);
        if (is.distance(feet.getX(), feet.getZ()) > Math.max(1, inner)) {
            throw new CommandFail(m.rawMsg("flag-too-close-to-edge"));
        }
        for (int i = 0; i <= m.poleHeight(); i++) {
            Block b = feet.getRelative(0, i, 0);
            if (!b.isEmpty() && !b.isReplaceable()) {
                throw new CommandFail(m.rawMsg("flag-no-space"));
            }
        }
        if (!feet.getRelative(0, -1, 0).getType().isSolid()) {
            throw new CommandFail(m.rawMsg("flag-no-ground"));
        }
        return feet;
    }

    private void claim(Player p) {
        if (!m.config().getBoolean("claiming.enabled", true)) {
            throw new CommandFail(m.rawMsg("claim-disabled"));
        }
        CrewService crews = crews();
        IslandImpl is = standingOn(p);
        if (is.status == IslandStatus.NEUTRAL) {
            throw new CommandFail(m.rawMsg("claim-neutral", "island", is.name));
        }
        if (m.garrison() != null && m.garrison().holds(is)) {
            throw new CommandFail(m.rawMsg("claim-tidewatch", "island", is.name));
        }
        if (is.status == IslandStatus.OCCUPIED) {
            throw new CommandFail(m.rawMsg("claim-occupied", "island", is.name, "crew", m.crewDisplay(is.ownerCrewId)));
        }
        if (m.config().getStringList("claiming.unclaimable-types").stream().anyMatch(t -> t.equalsIgnoreCase(is.type.name()))) {
            throw new CommandFail(m.rawMsg("claim-type-blocked", "type", m.typeName(is.type)));
        }
        Crew crew = officerCrew(p, crews);
        int owned = m.ownedCount(crew.id());
        int max = m.config().getInt("claiming.max-per-crew", 3);
        if (max > 0 && owned >= max) {
            throw new CommandFail(m.rawMsg("claim-max", "max", max));
        }
        int minMembers = m.config().getInt("claiming.min-members", 1);
        if (crew.members().size() < minMembers) {
            throw new CommandFail(m.rawMsg("claim-min-members", "min", minMembers));
        }
        Block spot = flagSpot(p, is);
        double cost = Math.max(0, m.config().getDouble("claiming.cost", 1000) + m.config().getDouble("claiming.cost-per-owned-island", 500) * owned);
        String payFrom = m.config().getString("claiming.pay-from", "bank").toLowerCase(Locale.ROOT);
        EconomyService eco = m.service(EconomyService.class);
        String costText = eco != null ? eco.format(cost) : Text.number(cost);
        if (cost > 0) {
            if (payFrom.equals("player")) {
                if (eco != null && !eco.has(p.getUniqueId(), cost)) {
                    throw new CommandFail(m.rawMsg("claim-cannot-afford", "cost", costText));
                }
            } else if (crew.bankBalance() < cost) {
                throw new CommandFail(m.rawMsg("claim-cannot-afford-bank", "cost", costText));
            }
        }
        IslandClaimEvent event = new IslandClaimEvent(is, crew, p);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            throw new CommandFail(m.rawMsg("claim-cancelled"));
        }
        if (cost > 0) {
            boolean paid = payFrom.equals("player") ? (eco == null || eco.withdraw(p.getUniqueId(), cost)) : crew.withdrawBank(cost);
            if (!paid) {
                throw new CommandFail(m.rawMsg(payFrom.equals("player") ? "claim-cannot-afford" : "claim-cannot-afford-bank", "cost", costText));
            }
        }
        m.claim(is, crew, spot.getX(), spot.getY(), spot.getZ());
        int rep = m.config().getInt("claiming.reputation", 10);
        if (rep != 0) {
            crew.addReputation(rep);
        }
        if (m.config().getBoolean("claiming.announce", true)) {
            Bukkit.broadcast(m.msg("claim-announce", "island", is.name, "crew", crew.displayName(), "player", p.getName()));
        } else {
            crew.broadcast(m.msg("claim-announce", "island", is.name, "crew", crew.displayName(), "player", p.getName()));
        }
        if (cost > 0) {
            m.send(p, "claim-paid", "cost", costText);
        }
    }

    private void unclaim(Player p, String[] args) {
        CrewService crews = crews();
        IslandImpl is = standingOn(p);
        Crew crew = crews.crewOf(p.getUniqueId());
        boolean admin = p.hasPermission(ADMIN);
        if (!admin) {
            crew = officerCrew(p, crews);
            if (!crew.id().equals(is.ownerCrewId)) {
                throw new CommandFail(m.rawMsg("not-your-island"));
            }
        } else if (is.status != IslandStatus.OCCUPIED) {
            throw new CommandFail(m.rawMsg("not-occupied", "island", is.name));
        }
        if (m.conquest().underAttack(is) && !admin) {
            throw new CommandFail(m.rawMsg("under-attack"));
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("confirm")) {
            m.send(p, "unclaim-confirm", "island", is.name);
            return;
        }
        Component owner = m.crewDisplay(is.ownerCrewId);
        Crew ownerCrew = crews.crewById(is.ownerCrewId);
        m.setOwner(is, null);
        if (ownerCrew != null) {
            ownerCrew.broadcast(m.msg("unclaimed", "island", is.name, "crew", owner, "player", p.getName()));
        }
        if (ownerCrew == null || !ownerCrew.members().contains(p.getUniqueId())) {
            m.send(p, "unclaimed", "island", is.name, "crew", owner, "player", p.getName());
        }
    }

    private void flag(Player p) {
        CrewService crews = crews();
        IslandImpl is = standingOn(p);
        boolean bypass = m.hasBypass(p);
        if (is.status != IslandStatus.OCCUPIED || is.ownerCrewId == null) {
            throw new CommandFail(m.rawMsg("not-occupied", "island", is.name));
        }
        if (!bypass) {
            Crew crew = officerCrew(p, crews);
            if (!crew.id().equals(is.ownerCrewId)) {
                throw new CommandFail(m.rawMsg("not-your-island"));
            }
            if (m.conquest().underAttack(is)) {
                throw new CommandFail(m.rawMsg("under-attack"));
            }
        }
        Block spot = flagSpot(p, is);
        m.conquest().forget(is.id);
        m.placeFlag(is, spot.getX(), spot.getY(), spot.getZ());
        m.send(p, "flag-placed", "island", is.name);
    }

    // ------------------------------------------------------------------ admin commands

    private void spawn(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 2, "/island spawn <type> <radius> [name]");
        Player p = AquaticCommand.player(sender);
        IslandType type = type(args[0]);
        int radius = checkedRadius(args[1]);
        String name = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : m.randomName(type, ThreadLocalRandom.current());
        if (!m.nameFree(name)) {
            throw new CommandFail(m.rawMsg("name-taken", "name", name));
        }
        IslandImpl is = m.createIsland(name, type, p.getLocation(), radius, true, sender);
        if (is == null) {
            throw new CommandFail(m.rawMsg("overlap"));
        }
        m.send(sender, "spawn-started", "island", is.name, "type", m.typeName(type), "radius", radius, "queued", m.queuedJobs());
    }

    private void spawnRandom(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 2, "/island spawnrandom <type|random> <count> [minRadius] [maxRadius]");
        IslandType fixed = args[0].equalsIgnoreCase("random") ? null : type(args[0]);
        int count = AquaticCommand.integer(args[1]);
        int maxCount = m.config().getInt("generation.max-spawnrandom", 30);
        if (count < 1 || count > maxCount) {
            throw new CommandFail(m.rawMsg("bad-count", "max", maxCount));
        }
        // no radius given: use the size classes (small ... huge); minR/maxR = 0 marks that
        int minR = args.length > 2 ? checkedRadius(args[2]) : 0;
        int maxR = args.length > 3 ? checkedRadius(args[3]) : (args.length > 2 ? minR : 0);
        if (maxR < minR) {
            int t = minR;
            minR = maxR;
            maxR = t;
        }
        m.send(sender, "spawnrandom-searching", "count", count);
        IslandsModule.SpawnRun run = new IslandsModule.SpawnRun(count);
        m.spawnRuns.add(run);
        spawnRandomNext(run, sender, fixed, count, minR, maxR, 0, new ArrayList<>());
    }

    /** Places the islands one after another; each spot is searched with asynchronously loaded chunks. */
    private void spawnRandomNext(IslandsModule.SpawnRun run, CommandSender sender, IslandType fixed, int count, int minR, int maxR,
                                 int done, List<String> names) {
        if (run.cancelled) {
            return; // /island admin cancel (the cancel message already went out)
        }
        if (done >= count) {
            run.finished = true;
            m.spawnRuns.remove(run);
            finishSpawnRandom(sender, count, names);
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        IslandType type = fixed != null ? fixed : m.randomType(random);
        int radius = minR > 0 ? minR + random.nextInt(Math.max(1, maxR - minR + 1)) : m.randomIslandRadius(random);
        m.findFreeOceanSpotAsync(radius, spot -> {
            if (run.cancelled || run.finished) {
                return;
            }
            if (spot == null) {
                run.finished = true;
                m.spawnRuns.remove(run);
                finishSpawnRandom(sender, count, names);
                return;
            }
            IslandImpl is = m.createIsland(m.randomName(type, ThreadLocalRandom.current()), type, spot, radius, true, sender);
            if (is != null) {
                run.placed++;
                names.add(is.name + " (" + is.x + ", " + is.z + ")");
                m.send(sender, "spawnrandom-progress", "n", run.placed, "total", count, "island", is.name, "x", is.x, "z", is.z);
            }
            spawnRandomNext(run, sender, fixed, count, minR, maxR, done + 1, names);
        });
    }

    private void finishSpawnRandom(CommandSender sender, int count, List<String> names) {
        if (names.isEmpty()) {
            sender.sendMessage(m.msg("no-free-spot"));
            return;
        }
        m.send(sender, "spawnrandom-started", "count", names.size(), "requested", count, "islands", String.join(", ", names));
    }

    private void register(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 3, "/island register <name> <type> <radius>");
        Player p = AquaticCommand.player(sender);
        String name = args[0].replace('_', ' ');
        IslandType type = type(args[1]);
        int radius = AquaticCommand.integer(args[2]);
        if (radius < 4 || radius > 2000) {
            throw new CommandFail(m.rawMsg("bad-radius", "min", 4, "max", 2000));
        }
        if (!m.nameFree(name)) {
            throw new CommandFail(m.rawMsg("name-taken", "name", name));
        }
        IslandImpl is = m.createIsland(name, type, p.getLocation(), radius, false, sender);
        if (is == null) {
            throw new CommandFail(m.rawMsg("overlap"));
        }
        m.send(sender, "registered", "island", is.name, "id", is.id, "radius", radius);
    }

    private void delete(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 1, "/island delete <island> [restore]");
        IslandImpl is = island(args[0]);
        boolean restore = args.length > 1 && args[1].equalsIgnoreCase("restore");
        m.removeIsland(is.id, restore, sender);
        m.send(sender, restore ? "deleted-restore" : "deleted", "island", is.name);
    }

    private void setStatus(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 2, "/island setstatus <island> <unclaimed|neutral>");
        IslandImpl is = island(args[0]);
        IslandStatus status = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "unclaimed" -> IslandStatus.UNCLAIMED;
            case "neutral" -> IslandStatus.NEUTRAL;
            default -> throw new CommandFail("<red>Status must be <white>unclaimed</white> or <white>neutral</white> (use setowner to give it to a crew).");
        };
        m.setStatus(is, status);
        m.send(sender, "status-set", "island", is.name, "status", status.name().toLowerCase(Locale.ROOT));
    }

    private void setOwner(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 2, "/island setowner <island> <crew|none>");
        IslandImpl is = island(args[0]);
        String crewArg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (crewArg.equalsIgnoreCase("none")) {
            m.setOwner(is, null);
            m.send(sender, "owner-cleared", "island", is.name);
            return;
        }
        Crew crew = crews().crewByName(crewArg);
        if (crew == null) {
            throw new CommandFail(m.rawMsg("unknown-crew-name", "crew", crewArg));
        }
        m.setOwner(is, crew.id());
        m.send(sender, "owner-set", "island", is.name, "crew", crew.displayName());
    }

    private void rename(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 2, "/island rename <island> <new name>");
        IslandImpl is = island(args[0]);
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        IslandImpl other = m.findByName(name);
        if (name.isEmpty() || (other != null && other != is)) {
            throw new CommandFail(m.rawMsg("name-taken", "name", name));
        }
        String old = is.name;
        m.rename(is, name);
        m.send(sender, "renamed", "old", old, "island", name);
    }

    private void setRadius(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 2, "/island setradius <island> <radius>");
        IslandImpl is = island(args[0]);
        int radius = AquaticCommand.integer(args[1]);
        if (radius < 4 || radius > 2000) {
            throw new CommandFail(m.rawMsg("bad-radius", "min", 4, "max", 2000));
        }
        if (m.overlaps(is.world, is.x, is.z, radius, is)) {
            throw new CommandFail(m.rawMsg("overlap"));
        }
        m.setRadius(is, radius);
        m.send(sender, "radius-set", "island", is.name, "radius", radius);
    }

    private void tp(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 1, "/island tp <island>");
        Player p = AquaticCommand.player(sender);
        IslandImpl is = island(String.join(" ", args));
        Location spot = m.teleportSpot(is);
        if (spot == null) {
            throw new CommandFail(m.rawMsg("world-missing", "world", is.world));
        }
        p.teleportAsync(spot);
        m.send(p, "teleported", "island", is.name);
    }

    private void regen(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 1, "/island regen <island> [newseed]");
        IslandImpl is = island(args[0]);
        if (is.bukkitWorld() == null) {
            throw new CommandFail(m.rawMsg("world-missing", "world", is.world));
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("newseed")) {
            is.seed = ThreadLocalRandom.current().nextLong();
            m.markDirty();
        }
        m.queueRegeneration(is, sender);
        m.send(sender, "regen-started", "island", is.name, "queued", m.queuedJobs());
    }

    private void setSpawn(Player p) {
        m.setSpawnPoint(p.getLocation());
        IslandImpl is = m.at(p.getLocation());
        if (is != null) {
            m.setSpawnIsland(is); // the island becomes a neutral safe zone
            m.send(p, "spawn-point-set-island", "island", is.name, "x", p.getLocation().getBlockX(),
                    "y", p.getLocation().getBlockY(), "z", p.getLocation().getBlockZ());
        } else {
            m.send(p, "spawn-point-set", "x", p.getLocation().getBlockX(), "y", p.getLocation().getBlockY(), "z", p.getLocation().getBlockZ());
        }
    }

    private void area(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 1, "/island area <radius> [spacing]");
        Player p = AquaticCommand.player(sender);
        int radius = AquaticCommand.integer(args[0]);
        if (radius < 100) {
            throw new CommandFail(m.rawMsg("bad-radius", "min", 100, "max", 1000000));
        }
        World w = p.getWorld();
        m.config().set("ocean-area.world", w.getName());
        m.config().set("ocean-area.center-x", p.getLocation().getBlockX());
        m.config().set("ocean-area.center-z", p.getLocation().getBlockZ());
        m.config().set("ocean-area.radius", radius);
        if (args.length > 1) {
            m.config().set("ocean-area.min-spacing", Math.max(0, AquaticCommand.integer(args[1])));
        }
        m.saveConfig();
        m.send(p, "area-set", "world", w.getName(), "x", p.getLocation().getBlockX(), "z", p.getLocation().getBlockZ(),
                "radius", radius, "spacing", m.config().getInt("ocean-area.min-spacing", 150));
    }

    private void cancel(CommandSender sender, String[] args) {
        String id = args.length > 0 ? island(args[0]).id : null;
        int runs = id == null ? m.cancelSpawnRuns() : 0;
        int removed = m.cancelJobs(id);
        if (runs == 0 && removed == 0) {
            m.send(sender, "nothing-to-cancel");
            return;
        }
        m.send(sender, "generation-stopped", "count", removed, "runs", runs);
    }

    private void shield(CommandSender sender, String[] args) {
        AquaticCommand.need(args, 2, "/island shield <island> <minutes>");
        IslandImpl is = island(args[0]);
        double minutes = AquaticCommand.number(args[1]);
        is.shieldUntil = minutes <= 0 ? 0 : System.currentTimeMillis() + (long) (minutes * 60000);
        m.conquest().forget(is.id);
        m.markDirty();
        m.send(sender, "shield-set", "island", is.name, "time", Text.duration((long) Math.max(0, minutes * 60)));
    }
}
