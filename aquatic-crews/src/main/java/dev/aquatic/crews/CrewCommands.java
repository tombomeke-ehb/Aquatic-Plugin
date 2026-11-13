package dev.aquatic.crews;

import dev.aquatic.api.CrewRank;
import dev.aquatic.api.EconomyService;
import dev.aquatic.command.AquaticCommand;
import dev.aquatic.command.CommandFail;
import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Builds /crew and /cc. */
final class CrewCommands {

    private static final String ADMIN = "aquatic.crews.admin";
    private static final List<String> RANK_NAMES = List.of("first_mate", "navigator", "quartermaster", "member");

    private final CrewsModule module;
    private final CrewListener listener;
    private final Map<UUID, Long> disbandConfirm = new HashMap<>();

    CrewCommands(CrewsModule module, CrewListener listener) {
        this.module = module;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ helpers

    private CrewImpl myCrew(Player p) {
        CrewImpl crew = module.byPlayer.get(p.getUniqueId());
        if (crew == null) {
            throw module.fail("not-in-crew");
        }
        return crew;
    }

    private void require(CrewImpl crew, Player p, String action) {
        if (!module.allowed(crew, p.getUniqueId(), action)) {
            throw module.fail("no-crew-permission");
        }
    }

    private CrewImpl crewArg(String raw) {
        CrewImpl crew = module.findByName(raw);
        if (crew == null) {
            crew = module.findByTag(raw);
        }
        if (crew == null) {
            throw module.fail("unknown-crew", "crew", raw);
        }
        return crew;
    }

    /** Charges the configured cost "costs.<key>" from the player's purse (free when the economy is off). */
    private double charge(Player p, String key) {
        double cost = module.cost("costs." + key);
        if (cost <= 0) {
            return 0;
        }
        EconomyService eco = module.economy();
        if (eco == null) {
            return 0;
        }
        if (!eco.withdraw(p.getUniqueId(), cost)) {
            throw module.fail("cannot-afford", "cost", eco.format(cost), "balance", eco.format(eco.balance(p.getUniqueId())));
        }
        return cost;
    }

    private UUID memberArg(CrewImpl crew, String name) {
        UUID id = crew.memberByName(name);
        if (id == null) {
            throw module.fail("not-a-member", "player", name);
        }
        return id;
    }

    private List<String> crewComplete(String prefix) {
        return AquaticCommand.filter(module.crewNames(), prefix);
    }

    private List<String> memberComplete(CommandSender sender, String prefix) {
        if (!(sender instanceof Player p)) {
            return List.of();
        }
        CrewImpl crew = module.byPlayer.get(p.getUniqueId());
        if (crew == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (UUID id : crew.ranks.keySet()) {
            if (!id.equals(p.getUniqueId())) {
                names.add(crew.nameOf(id));
            }
        }
        return AquaticCommand.filter(names, prefix);
    }

    private List<String> otherCrewComplete(CommandSender sender, String prefix) {
        CrewImpl own = sender instanceof Player p ? module.byPlayer.get(p.getUniqueId()) : null;
        List<String> names = new ArrayList<>();
        for (CrewImpl crew : module.crews.values()) {
            if (crew != own) {
                names.add(crew.name);
            }
        }
        return AquaticCommand.filter(names, prefix);
    }

    private static int page(String[] args, int index, int pages) {
        if (args.length <= index) {
            return 1;
        }
        return Math.max(1, Math.min(Math.max(1, pages), AquaticCommand.integer(args[index])));
    }

    // ------------------------------------------------------------------ registration

    List<AquaticCommand> build() {
        Players.ensurePermission("aquatic.crews.chat", PermissionDefault.TRUE);
        AquaticCommand cmd = new AquaticCommand("crew", "Pirate crew commands", "c");
        // /crew alone opens the menu; /crew help still lists the commands
        cmd.defaultExecutor((s, a) -> module.menu.open(AquaticCommand.player(s)));
        cmd.sub("menu", (s, a) -> module.menu.open(AquaticCommand.player(s)))
                .description("Open the crew menu (members, bank, home, diplomacy)").playerOnly();

        cmd.sub("create", (s, a) -> create(AquaticCommand.player(s), a))
                .usage("<name> [tag]").description("Found a new pirate crew").playerOnly()
                .playerPermission("aquatic.crews.create");

        cmd.sub("disband", (s, a) -> disband(AquaticCommand.player(s), a))
                .usage("[confirm]").description("Disband your crew (captain)").playerOnly()
                .playerPermission("aquatic.crews.disband")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(List.of("confirm"), a[0]) : List.of());

        cmd.sub("invite", (s, a) -> invite(AquaticCommand.player(s), a))
                .usage("<player>").description("Invite a player to your crew").playerOnly()
                .playerPermission("aquatic.crews.invite")
                .completer((s, a) -> a.length == 1 ? AquaticCommand.playerNames(a[0]) : List.of());

        cmd.sub("accept", (s, a) -> accept(AquaticCommand.player(s), a))
                .usage("[crew]").description("Accept a crew invitation").playerOnly()
                .playerPermission("aquatic.crews.join")
                .completer((s, a) -> a.length == 1 ? inviteComplete(s, a[0]) : List.of());

        cmd.sub("deny", (s, a) -> deny(AquaticCommand.player(s), a))
                .usage("[crew]").description("Decline a crew invitation").playerOnly()
                .completer((s, a) -> a.length == 1 ? inviteComplete(s, a[0]) : List.of());

        cmd.sub("leave", (s, a) -> leave(AquaticCommand.player(s)))
                .description("Leave your crew").playerOnly();

        cmd.sub("kick", (s, a) -> kick(AquaticCommand.player(s), a))
                .usage("<player>").description("Throw a member overboard").playerOnly()
                .completer((s, a) -> a.length == 1 ? memberComplete(s, a[0]) : List.of());

        cmd.sub("promote", (s, a) -> promote(AquaticCommand.player(s), a, true))
                .usage("<player>").description("Promote a member one rank").playerOnly()
                .completer((s, a) -> a.length == 1 ? memberComplete(s, a[0]) : List.of());

        cmd.sub("demote", (s, a) -> promote(AquaticCommand.player(s), a, false))
                .usage("<player>").description("Demote a member one rank").playerOnly()
                .completer((s, a) -> a.length == 1 ? memberComplete(s, a[0]) : List.of());

        cmd.sub("setrank", (s, a) -> setRank(AquaticCommand.player(s), a))
                .usage("<player> <rank>").description("Give a member a specific rank").playerOnly()
                .completer((s, a) -> a.length == 1 ? memberComplete(s, a[0])
                        : a.length == 2 ? AquaticCommand.filter(RANK_NAMES, a[1]) : List.of());

        cmd.sub("transfer", (s, a) -> transfer(AquaticCommand.player(s), a))
                .usage("<player>").description("Hand the captaincy to another member").playerOnly()
                .completer((s, a) -> a.length == 1 ? memberComplete(s, a[0]) : List.of());

        cmd.sub("info", this::info)
                .usage("[crew|player]").description("Show information about a crew")
                .completer((s, a) -> {
                    if (a.length != 1) {
                        return List.of();
                    }
                    List<String> out = crewComplete(a[0]);
                    out.addAll(AquaticCommand.playerNames(a[0]));
                    return out;
                });

        cmd.sub("list", this::list)
                .usage("[page]").description("List all crews");

        cmd.sub("top", this::top)
                .usage("[page]").description("Most famous crews by reputation");

        cmd.sub("color", (s, a) -> color(AquaticCommand.player(s), a))
                .aliases("colour").usage("<color>").description("Change the crew colour").playerOnly()
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(new ArrayList<>(NamedTextColor.NAMES.keys()), a[0]) : List.of());

        cmd.sub("tag", (s, a) -> tag(AquaticCommand.player(s), a))
                .usage("<tag>").description("Change the crew tag").playerOnly();

        cmd.sub("flag", (s, a) -> {
                    if (a.length > 0 && a[0].equalsIgnoreCase("presets")) {
                        s.sendMessage(module.msg("flag-presets", "presets", String.join(", ", module.flags().presetNames())));
                        return;
                    }
                    flag(AquaticCommand.player(s), a);
                })
                .usage("[set|color|preset|presets|get|reset]").description("Design the crew flag (planted on your islands)")
                .completer((s, a) -> switch (a.length) {
                    case 1 -> AquaticCommand.filter(List.of("set", "color", "preset", "presets", "get", "reset"), a[0]);
                    case 2 -> a[0].equalsIgnoreCase("color") ? AquaticCommand.filter(FlagDesigner.dyeNames(), a[1])
                            : a[0].equalsIgnoreCase("preset") ? AquaticCommand.filter(module.flags().presetNames(), a[1]) : List.of();
                    default -> List.of();
                });

        cmd.sub("home", (s, a) -> home(AquaticCommand.player(s)))
                .description("Sail to the crew home").playerOnly()
                .playerPermission("aquatic.crews.home");

        cmd.sub("sethome", (s, a) -> setHome(AquaticCommand.player(s)))
                .description("Set the crew home where you stand").playerOnly();

        cmd.sub("chat", (s, a) -> toggleChat(AquaticCommand.player(s)))
                .description("Toggle crew chat for your normal messages").playerOnly();

        cmd.sub("friendlyfire", (s, a) -> friendlyFire(AquaticCommand.player(s)))
                .aliases("ff").description("Toggle whether crewmates can hurt each other").playerOnly();

        cmd.sub("bank", (s, a) -> bank(AquaticCommand.player(s), a))
                .usage("<balance|deposit|withdraw> [amount]").description("The crew treasury").playerOnly()
                .completer((s, a) -> a.length == 1 ? AquaticCommand.filter(List.of("balance", "deposit", "withdraw"), a[0])
                        : a.length == 2 && !a[0].equalsIgnoreCase("balance") ? AquaticCommand.filter(List.of("100", "1000", "10000"), a[1]) : List.of());

        cmd.sub("ally", (s, a) -> ally(AquaticCommand.player(s), a))
                .usage("<crew>").description("Propose or accept an alliance").playerOnly()
                .completer((s, a) -> a.length == 1 ? otherCrewComplete(s, a[0]) : List.of());

        cmd.sub("neutral", (s, a) -> neutral(AquaticCommand.player(s), a))
                .usage("<crew>").description("End an alliance or rivalry").playerOnly()
                .completer((s, a) -> a.length == 1 ? otherCrewComplete(s, a[0]) : List.of());

        cmd.sub("rival", (s, a) -> rival(AquaticCommand.player(s), a))
                .usage("<crew>").description("Declare a crew your rival").playerOnly()
                .completer((s, a) -> a.length == 1 ? otherCrewComplete(s, a[0]) : List.of());

        cmd.sub("war", (s, a) -> war(AquaticCommand.player(s), a))
                .usage("<crew>").description("Declare war on a crew").playerOnly()
                .playerPermission("aquatic.crews.war")
                .completer((s, a) -> a.length == 1 ? otherCrewComplete(s, a[0]) : List.of());

        cmd.sub("peace", (s, a) -> peace(AquaticCommand.player(s), a))
                .usage("<crew>").description("Offer or accept peace").playerOnly()
                .completer((s, a) -> a.length == 1 ? otherCrewComplete(s, a[0]) : List.of());

        cmd.sub("admin", this::admin)
                .usage("<disband|setcaptain|setrep|join|kick> ...").description("Crew administration")
                .permission(ADMIN)
                .completer(this::adminComplete);

        AquaticCommand cc = new AquaticCommand("cc", "Talk in crew chat", "crewchat");
        cc.defaultExecutor((s, a) -> {
            Player p = AquaticCommand.player(s);
            if (!p.hasPermission("aquatic.crews.chat") && !p.hasPermission("aquatic.admin")) {
                throw module.fail("no-permission");
            }
            if (a.length == 0) {
                toggleChat(p);
                return;
            }
            myCrew(p);
            module.sendCrewChat(p, Component.text(String.join(" ", a)));
        });
        cc.defaultCompleter((s, a) -> List.of());

        return List.of(cmd, cc);
    }

    private List<String> inviteComplete(CommandSender sender, String prefix) {
        if (!(sender instanceof Player p)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String id : module.invitesOf(p.getUniqueId()).keySet()) {
            CrewImpl crew = module.crews.get(id);
            if (crew != null) {
                names.add(crew.name);
            }
        }
        return AquaticCommand.filter(names, prefix);
    }

    // ------------------------------------------------------------------ membership

    private void create(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew create <name> [tag]");
        if (module.byPlayer.containsKey(p.getUniqueId())) {
            throw module.fail("already-in-crew");
        }
        String name = args[0];
        module.validateName(name, null);
        String tag;
        if (args.length > 1) {
            tag = args[1].toUpperCase(Locale.ROOT);
            module.validateTag(tag, null);
        } else {
            tag = module.suggestTag(name);
            if (tag == null) {
                throw module.fail("tag-needed");
            }
        }
        double paid = charge(p, "create");
        CrewImpl crew = module.create(p, name, tag);
        module.send(p, "crew-created", "crew", crew.displayName(), "tag", crew.tagComponent(), "cost", module.money(paid));
        if (module.config().getBoolean("announce-creation", true)) {
            Component message = module.msg("crew-created-broadcast", "crew", crew.displayName(), "player", p.getName());
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online != p) {
                    online.sendMessage(message);
                }
            }
        }
    }

    private void disband(Player p, String[] args) {
        CrewImpl crew = myCrew(p);
        if (!p.getUniqueId().equals(crew.captain)) {
            throw module.fail("captain-only");
        }
        long now = System.currentTimeMillis();
        Long pending = disbandConfirm.get(p.getUniqueId());
        boolean confirmed = args.length > 0 && args[0].equalsIgnoreCase("confirm") && pending != null && pending >= now;
        if (!confirmed) {
            disbandConfirm.put(p.getUniqueId(), now + 30_000L);
            module.send(p, "disband-confirm", "crew", crew.displayName());
            return;
        }
        disbandConfirm.remove(p.getUniqueId());
        Component message = module.msg("crew-disbanded", "crew", crew.displayName());
        crew.broadcast(message);
        module.disband(crew);
        if (module.config().getBoolean("announce-disband", true)) {
            Bukkit.broadcast(module.msg("crew-disbanded-broadcast", "crew", crew.displayName()));
        }
    }

    private void invite(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew invite <player>");
        CrewImpl crew = myCrew(p);
        require(crew, p, "invite");
        Player target = AquaticCommand.onlinePlayer(args[0]);
        if (target == p) {
            throw module.fail("invite-self");
        }
        if (module.byPlayer.containsKey(target.getUniqueId())) {
            throw module.fail("target-in-crew", "player", target.getName());
        }
        if (crew.ranks.size() >= module.maxMembers()) {
            throw module.fail("crew-full", "max", module.maxMembers());
        }
        long seconds = Math.max(10, module.config().getLong("invite-expire-seconds", 120));
        module.invites.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>())
                .put(crew.id, System.currentTimeMillis() + seconds * 1000L);
        crew.broadcast(module.msg("invite-sent", "player", target.getName(), "inviter", p.getName()));
        module.send(target, "invite-received", "crew", crew.displayName(), "inviter", p.getName(), "seconds", seconds);
    }

    private CrewImpl pickInvite(Player p, String[] args) {
        Map<String, Long> invites = module.invitesOf(p.getUniqueId());
        if (invites.isEmpty()) {
            throw module.fail("no-invites");
        }
        if (args.length > 0) {
            CrewImpl crew = crewArg(args[0]);
            if (!invites.containsKey(crew.id)) {
                throw module.fail("no-invite-from", "crew", crew.displayName());
            }
            return crew;
        }
        // most recent invitation
        String best = null;
        long bestExpiry = Long.MIN_VALUE;
        for (Map.Entry<String, Long> e : invites.entrySet()) {
            if (e.getValue() > bestExpiry) {
                best = e.getKey();
                bestExpiry = e.getValue();
            }
        }
        CrewImpl crew = module.crews.get(best);
        if (crew == null) {
            throw module.fail("no-invites");
        }
        return crew;
    }

    private void accept(Player p, String[] args) {
        if (module.byPlayer.containsKey(p.getUniqueId())) {
            throw module.fail("already-in-crew");
        }
        CrewImpl crew = pickInvite(p, args);
        if (crew.ranks.size() >= module.maxMembers()) {
            throw module.fail("crew-full", "max", module.maxMembers());
        }
        module.addMember(crew, p.getUniqueId(), p.getName());
        crew.broadcast(module.msg("member-joined", "player", p.getName(), "crew", crew.displayName()));
    }

    private void deny(Player p, String[] args) {
        CrewImpl crew = pickInvite(p, args);
        Map<String, Long> invites = module.invites.get(p.getUniqueId());
        if (invites != null) {
            invites.remove(crew.id);
        }
        module.send(p, "invite-denied", "crew", crew.displayName());
        crew.broadcast(module.msg("invite-denied-crew", "player", p.getName()));
    }

    private void leave(Player p) {
        CrewImpl crew = myCrew(p);
        if (p.getUniqueId().equals(crew.captain)) {
            if (crew.ranks.size() > 1) {
                throw module.fail("captain-cannot-leave");
            }
            module.send(p, "crew-disbanded", "crew", crew.displayName());
            module.disband(crew);
            return;
        }
        module.removeMember(crew, p.getUniqueId());
        module.send(p, "left-crew", "crew", crew.displayName());
        crew.broadcast(module.msg("member-left", "player", p.getName()));
    }

    private void kick(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew kick <player>");
        CrewImpl crew = myCrew(p);
        require(crew, p, "kick");
        UUID target = memberArg(crew, args[0]);
        if (target.equals(p.getUniqueId())) {
            throw module.fail("kick-self");
        }
        CrewRank own = crew.rankOf(p.getUniqueId());
        CrewRank theirs = crew.rankOf(target);
        if (theirs == CrewRank.CAPTAIN || (own != CrewRank.CAPTAIN && theirs.weight() >= own.weight())) {
            throw module.fail("rank-too-high");
        }
        String name = crew.nameOf(target);
        module.removeMember(crew, target);
        crew.broadcast(module.msg("member-kicked", "player", name, "by", p.getName()));
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            module.send(online, "you-were-kicked", "crew", crew.displayName());
        }
    }

    // ------------------------------------------------------------------ ranks

    private void changeRank(Player actor, CrewImpl crew, UUID target, CrewRank newRank) {
        require(crew, actor, "setrank");
        if (target.equals(actor.getUniqueId())) {
            throw module.fail("rank-self");
        }
        if (newRank == CrewRank.CAPTAIN) {
            throw module.fail("use-transfer");
        }
        CrewRank own = crew.rankOf(actor.getUniqueId());
        CrewRank theirs = crew.rankOf(target);
        if (theirs == CrewRank.CAPTAIN
                || (own != CrewRank.CAPTAIN && (theirs.weight() >= own.weight() || newRank.weight() >= own.weight()))) {
            throw module.fail("rank-too-high");
        }
        if (theirs == newRank) {
            throw module.fail("already-rank", "player", crew.nameOf(target), "rank", module.rankName(newRank));
        }
        int limit = module.rankLimit(newRank);
        if (limit >= 0 && crew.countRank(newRank) >= limit) {
            throw module.fail("rank-full", "rank", module.rankName(newRank), "limit", limit);
        }
        crew.ranks.put(target, newRank);
        module.markDirty();
        crew.broadcast(module.msg("rank-changed", "player", crew.nameOf(target), "rank", module.rankName(newRank), "by", actor.getName()));
    }

    private void promote(Player p, String[] args, boolean up) {
        AquaticCommand.need(args, 1, up ? "/crew promote <player>" : "/crew demote <player>");
        CrewImpl crew = myCrew(p);
        UUID target = memberArg(crew, args[0]);
        CrewRank current = crew.rankOf(target);
        CrewRank next;
        if (up) {
            next = switch (current) {
                case MEMBER -> CrewRank.NAVIGATOR;
                case NAVIGATOR, QUARTERMASTER -> CrewRank.FIRST_MATE;
                default -> null;
            };
        } else {
            next = switch (current) {
                case FIRST_MATE -> CrewRank.NAVIGATOR;
                case NAVIGATOR, QUARTERMASTER -> CrewRank.MEMBER;
                default -> null;
            };
        }
        if (next == null) {
            throw module.fail(up ? "cannot-promote" : "cannot-demote", "player", crew.nameOf(target));
        }
        changeRank(p, crew, target, next);
    }

    private void setRank(Player p, String[] args) {
        AquaticCommand.need(args, 2, "/crew setrank <player> <rank>");
        CrewImpl crew = myCrew(p);
        UUID target = memberArg(crew, args[0]);
        CrewRank rank = CrewsModule.parseRank(args[1]);
        if (rank == null) {
            throw module.fail("unknown-rank", "rank", args[1]);
        }
        changeRank(p, crew, target, rank);
    }

    private void transfer(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew transfer <player>");
        CrewImpl crew = myCrew(p);
        if (!p.getUniqueId().equals(crew.captain)) {
            throw module.fail("captain-only");
        }
        UUID target = memberArg(crew, args[0]);
        if (target.equals(p.getUniqueId())) {
            throw module.fail("rank-self");
        }
        module.setCaptain(crew, target);
        crew.broadcast(module.msg("captain-changed", "player", crew.nameOf(target), "crew", crew.displayName()));
    }

    // ------------------------------------------------------------------ info

    private void info(CommandSender sender, String[] args) {
        CrewImpl crew;
        if (args.length == 0) {
            crew = myCrew(AquaticCommand.player(sender));
        } else {
            crew = module.findByName(args[0]);
            if (crew == null) {
                crew = module.findByTag(args[0]);
            }
            if (crew == null) {
                OfflinePlayer target = Players.resolve(args[0]);
                if (target != null) {
                    crew = module.byPlayer.get(target.getUniqueId());
                    if (crew == null) {
                        throw module.fail("player-no-crew", "player", args[0]);
                    }
                }
            }
            if (crew == null) {
                throw module.fail("unknown-crew", "crew", args[0]);
            }
        }
        JoinConfiguration comma = JoinConfiguration.separator(Component.text(", ", NamedTextColor.DARK_GRAY));
        List<Component> members = new ArrayList<>();
        int online = 0;
        for (UUID id : crew.sortedMembers()) {
            boolean on = Bukkit.getPlayer(id) != null;
            if (on) {
                online++;
            }
            members.add(module.rawMsg(on ? "info-member-online" : "info-member-offline",
                    "rank", module.rankName(crew.rankOf(id)), "player", crew.nameOf(id)));
        }
        List<Component> allies = new ArrayList<>();
        for (String id : crew.allies) {
            CrewImpl other = module.crews.get(id);
            if (other != null) {
                allies.add(other.displayName());
            }
        }
        List<Component> rivals = new ArrayList<>();
        for (String id : crew.rivals) {
            CrewImpl other = module.crews.get(id);
            if (other != null) {
                rivals.add(other.displayName());
            }
        }
        List<Component> wars = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CrewImpl.War> e : crew.wars.entrySet()) {
            CrewImpl enemy = module.crews.get(e.getKey());
            if (enemy == null) {
                continue;
            }
            CrewImpl.War theirs = enemy.wars.get(crew.id);
            wars.add(module.rawMsg("info-war-entry", "crew", enemy.displayName(), "our_kills", e.getValue().kills,
                    "their_kills", theirs == null ? 0 : theirs.kills,
                    "duration", Text.duration(Math.max(0, (now - e.getValue().since) / 1000L))));
        }
        Component none = module.rawMsg("info-none");
        sender.sendMessage(module.rawMsg("info-header", "crew", crew.displayName(), "tag", crew.tagComponent()));
        sender.sendMessage(module.rawMsg("info-captain", "player", crew.nameOf(crew.captain)));
        sender.sendMessage(module.rawMsg("info-members", "count", crew.ranks.size(), "max", module.maxMembers(),
                "online", online, "list", Component.join(comma, members)));
        sender.sendMessage(module.rawMsg("info-reputation", "reputation", crew.reputation));
        sender.sendMessage(module.rawMsg("info-bank", "balance", module.money(crew.bank)));
        sender.sendMessage(module.rawMsg("info-allies", "list", allies.isEmpty() ? none : Component.join(comma, allies)));
        sender.sendMessage(module.rawMsg("info-rivals", "list", rivals.isEmpty() ? none : Component.join(comma, rivals)));
        sender.sendMessage(module.rawMsg("info-wars", "list", wars.isEmpty() ? none : Component.join(comma, wars)));
        sender.sendMessage(module.rawMsg("info-flags", "friendly_fire", crew.friendlyFire ? "on" : "off",
                "home", crew.hasHome() ? "set" : "not set",
                "days", Math.max(0, (now - crew.created) / 86_400_000L)));
    }

    private void list(CommandSender sender, String[] args) {
        List<CrewImpl> all = new ArrayList<>(module.crews.values());
        if (all.isEmpty()) {
            throw module.fail("no-crews");
        }
        all.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        int size = Math.max(1, module.config().getInt("list-page-size", 10));
        int pages = (all.size() + size - 1) / size;
        int page = page(args, 0, pages);
        sender.sendMessage(module.rawMsg("list-header", "page", page, "pages", pages, "count", all.size()));
        for (int i = (page - 1) * size; i < Math.min(all.size(), page * size); i++) {
            CrewImpl crew = all.get(i);
            sender.sendMessage(module.rawMsg("list-line", "crew", crew.displayName(), "tag", crew.tagComponent(),
                    "members", crew.ranks.size(), "online", crew.onlineMembers().size(), "reputation", crew.reputation));
        }
    }

    private void top(CommandSender sender, String[] args) {
        List<CrewImpl> all = module.topCrews();
        if (all.isEmpty()) {
            throw module.fail("no-crews");
        }
        int size = Math.max(1, module.config().getInt("list-page-size", 10));
        int pages = (all.size() + size - 1) / size;
        int page = page(args, 0, pages);
        sender.sendMessage(module.rawMsg("top-header", "page", page, "pages", pages));
        for (int i = (page - 1) * size; i < Math.min(all.size(), page * size); i++) {
            CrewImpl crew = all.get(i);
            sender.sendMessage(module.rawMsg("top-line", "rank", i + 1, "crew", crew.displayName(),
                    "reputation", crew.reputation, "members", crew.ranks.size()));
        }
    }

    // ------------------------------------------------------------------ appearance

    private void color(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew color <color>");
        CrewImpl crew = myCrew(p);
        require(crew, p, "appearance");
        TextColor color = CrewsModule.parseColor(args[0], module.config().getBoolean("allow-hex-colors", true));
        if (color == null) {
            throw module.fail("unknown-color", "color", args[0]);
        }
        for (String blocked : module.config().getStringList("blocked-colors")) {
            TextColor b = CrewsModule.parseColor(blocked, true);
            if (b != null && b.value() == color.value()) {
                throw module.fail("color-blocked");
            }
        }
        double paid = charge(p, "color");
        crew.color = color;
        module.markDirty();
        module.updateTab(crew);
        if (!crew.hasCustomFlag()) {
            module.flagChanged(crew); // the default flag is drawn in the crew colour
        }
        crew.broadcast(module.msg("color-changed", "crew", crew.displayName(), "cost", module.money(paid)));
    }

    private void tag(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew tag <tag>");
        CrewImpl crew = myCrew(p);
        require(crew, p, "appearance");
        String tag = args[0].toUpperCase(Locale.ROOT);
        if (tag.equals(crew.tag)) {
            throw module.fail("tag-taken", "tag", tag);
        }
        module.validateTag(tag, crew);
        double paid = charge(p, "tag");
        crew.tag = tag;
        module.markDirty();
        module.updateTab(crew);
        crew.broadcast(module.msg("tag-changed", "tag", crew.tagComponent(), "cost", module.money(paid)));
    }

    /**
     * /crew flag                 info (or: set, when holding a banner)
     * /crew flag set             the banner in your hand becomes the flag (design it in a loom!)
     * /crew flag color <dye>     new base colour, same patterns
     * /crew flag preset <name>   a ready-made design from crews.yml (flag.presets)
     * /crew flag presets         list the designs
     * /crew flag get             a copy of the flag banner for yourself
     * /crew flag reset           back to the default flag in the crew colour
     */
    private void flag(Player p, String[] args) {
        CrewImpl crew = myCrew(p);
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        FlagDesigner flags = module.flags();
        switch (action) {
            case "" -> {
                if (FlagDesigner.isBanner(p.getInventory().getItemInMainHand())) {
                    setFlagFromHand(p, crew); // old behaviour: /crew flag with a banner in hand
                    return;
                }
                p.sendMessage(module.msg("flag-info", "crew", crew.displayName(),
                        "design", crew.hasCustomFlag() ? module.rawMsg("flag-design-custom") : module.rawMsg("flag-design-default"),
                        "color", FlagDesigner.baseOf(crew.flag()).name().toLowerCase(Locale.ROOT)));
                p.sendMessage(module.rawMsg("flag-help"));
            }
            case "set" -> setFlagFromHand(p, crew);
            case "color", "colour" -> {
                require(crew, p, "appearance");
                AquaticCommand.need(args, 2, "/crew flag color <" + String.join("|", FlagDesigner.dyeNames()) + ">");
                org.bukkit.DyeColor dye = FlagDesigner.parseDye(args[1]);
                if (dye == null) {
                    throw module.fail("flag-unknown-color", "color", args[1]);
                }
                double paid = charge(p, "flag");
                crew.flag = FlagDesigner.recolor(crew.flag(), dye);
                module.flagChanged(crew);
                crew.broadcast(module.msg("flag-changed", "player", p.getName(), "cost", module.money(paid)));
            }
            case "preset" -> {
                require(crew, p, "appearance");
                AquaticCommand.need(args, 2, "/crew flag preset <name>  (see /crew flag presets)");
                if (!flags.hasPreset(args[1])) {
                    throw module.fail("flag-unknown-preset", "preset", args[1]);
                }
                double paid = charge(p, "flag");
                crew.flag = flags.preset(args[1], crew.color);
                module.flagChanged(crew);
                crew.broadcast(module.msg("flag-changed", "player", p.getName(), "cost", module.money(paid)));
            }
            case "presets" -> p.sendMessage(module.msg("flag-presets", "presets", String.join(", ", flags.presetNames())));
            case "get" -> {
                double paid = charge(p, "flag-copy");
                java.util.Map<Integer, ItemStack> left = p.getInventory().addItem(crew.flag());
                left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
                p.sendMessage(module.msg("flag-copy", "cost", module.money(paid)));
            }
            case "reset" -> {
                require(crew, p, "appearance");
                crew.flag = null;
                module.flagChanged(crew);
                crew.broadcast(module.msg("flag-reset", "player", p.getName()));
            }
            default -> throw new CommandFail(module.rawMsg("flag-help"));
        }
    }

    private void setFlagFromHand(Player p, CrewImpl crew) {
        require(crew, p, "appearance");
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!FlagDesigner.isBanner(hand)) {
            throw module.fail("flag-hold-banner");
        }
        double paid = charge(p, "flag");
        crew.flag = hand.asOne();
        module.flagChanged(crew);
        crew.broadcast(module.msg("flag-changed", "player", p.getName(), "cost", module.money(paid)));
    }

    // ------------------------------------------------------------------ home & chat

    private void home(Player p) {
        if (!module.config().getBoolean("home.enabled", true)) {
            throw module.fail("home-disabled");
        }
        CrewImpl crew = myCrew(p);
        if (!crew.hasHome()) {
            throw module.fail("home-not-set");
        }
        if (crew.home() == null) {
            throw module.fail("home-world-missing");
        }
        if (listener.isWarmingUp(p)) {
            throw module.fail("home-already-warming");
        }
        long left = p.hasPermission("aquatic.crews.home.bypass-cooldown") ? 0 : listener.homeCooldownLeft(p);
        if (left > 0) {
            throw module.fail("home-cooldown", "time", Text.duration(left));
        }
        listener.startHome(p, crew);
    }

    private void setHome(Player p) {
        if (!module.config().getBoolean("home.enabled", true)) {
            throw module.fail("home-disabled");
        }
        CrewImpl crew = myCrew(p);
        require(crew, p, "sethome");
        Location loc = p.getLocation();
        if (module.config().getStringList("home.blocked-worlds").contains(loc.getWorld().getName())) {
            throw module.fail("home-world-blocked");
        }
        double paid = charge(p, "sethome");
        crew.setHome(loc);
        module.markDirty();
        crew.broadcast(module.msg("home-set", "player", p.getName(), "cost", module.money(paid)));
    }

    private void toggleChat(Player p) {
        myCrew(p);
        if (module.crewChat.remove(p.getUniqueId())) {
            module.send(p, "crew-chat-off");
        } else {
            module.crewChat.add(p.getUniqueId());
            module.send(p, "crew-chat-on");
        }
    }

    private void friendlyFire(Player p) {
        CrewImpl crew = myCrew(p);
        require(crew, p, "friendlyfire");
        if (!module.config().getBoolean("friendly-fire.allow-toggle", true)) {
            throw module.fail("friendly-fire-locked");
        }
        crew.friendlyFire = !crew.friendlyFire;
        module.markDirty();
        crew.broadcast(module.msg(crew.friendlyFire ? "friendly-fire-on" : "friendly-fire-off", "player", p.getName()));
    }

    // ------------------------------------------------------------------ bank

    private void bank(Player p, String[] args) {
        CrewImpl crew = myCrew(p);
        String action = args.length == 0 ? "balance" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "balance", "bal" -> module.send(p, "bank-balance", "crew", crew.displayName(), "balance", module.money(crew.bank));
            case "deposit", "d" -> {
                AquaticCommand.need(args, 2, "/crew bank deposit <amount>");
                EconomyService eco = module.economy();
                if (eco == null) {
                    throw module.fail("economy-disabled");
                }
                double amount = Players.amount(args[1]);
                if (crew.bank + amount > module.maxBank()) {
                    throw module.fail("bank-full", "max", eco.format(module.maxBank()));
                }
                if (!eco.withdraw(p.getUniqueId(), amount)) {
                    throw module.fail("cannot-afford", "cost", eco.format(amount), "balance", eco.format(eco.balance(p.getUniqueId())));
                }
                crew.depositBank(amount);
                crew.broadcast(module.msg("bank-deposit", "player", p.getName(), "amount", eco.format(amount), "balance", eco.format(crew.bank)));
            }
            case "withdraw", "w" -> {
                AquaticCommand.need(args, 2, "/crew bank withdraw <amount>");
                require(crew, p, "bank-withdraw");
                EconomyService eco = module.economy();
                if (eco == null) {
                    throw module.fail("economy-disabled");
                }
                double amount = Players.amount(args[1]);
                if (!crew.withdrawBank(amount)) {
                    throw module.fail("bank-insufficient", "balance", eco.format(crew.bank));
                }
                eco.deposit(p.getUniqueId(), amount);
                crew.broadcast(module.msg("bank-withdraw", "player", p.getName(), "amount", eco.format(amount), "balance", eco.format(crew.bank)));
            }
            default -> throw new CommandFail("<red>Usage: <white>/crew bank <balance|deposit|withdraw> [amount]");
        }
    }

    // ------------------------------------------------------------------ diplomacy

    private CrewImpl otherCrew(CrewImpl own, String raw) {
        CrewImpl other = crewArg(raw);
        if (other == own) {
            throw module.fail("diplomacy-self");
        }
        return other;
    }

    private void ally(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew ally <crew>");
        CrewImpl crew = myCrew(p);
        require(crew, p, "diplomacy");
        CrewImpl other = otherCrew(crew, args[0]);
        if (module.atWar(crew, other)) {
            throw module.fail("ally-at-war", "crew", other.displayName());
        }
        if (module.allied(crew, other)) {
            throw module.fail("already-allied", "crew", other.displayName());
        }
        int max = module.config().getInt("diplomacy.max-allies", 3);
        if (max >= 0 && (crew.allies.size() >= max || other.allies.size() >= max)) {
            throw module.fail("ally-limit", "max", max);
        }
        if (module.hasRequest(module.allyRequests, other, crew)) {
            module.formAlliance(crew, other);
            return;
        }
        module.allyRequests.put(CrewsModule.requestKey(crew, other), module.requestExpiry());
        crew.broadcast(module.msg("ally-requested", "crew", other.displayName()));
        other.broadcast(module.msg("ally-request-received", "crew", crew.displayName(), "tag", crew.name));
    }

    private void neutral(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew neutral <crew>");
        CrewImpl crew = myCrew(p);
        require(crew, p, "diplomacy");
        CrewImpl other = otherCrew(crew, args[0]);
        if (module.atWar(crew, other)) {
            throw module.fail("neutral-at-war", "crew", other.displayName());
        }
        boolean wasAlly = module.breakAlliance(crew, other);
        boolean wasRival = crew.rivals.remove(other.id);
        module.allyRequests.remove(CrewsModule.requestKey(crew, other));
        if (!wasAlly && !wasRival) {
            throw module.fail("already-neutral", "crew", other.displayName());
        }
        module.markDirty();
        crew.broadcast(module.msg("now-neutral", "crew", other.displayName()));
        if (wasAlly) {
            other.broadcast(module.msg("ally-broken", "crew", crew.displayName()));
        }
    }

    private void rival(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew rival <crew>");
        CrewImpl crew = myCrew(p);
        require(crew, p, "diplomacy");
        CrewImpl other = otherCrew(crew, args[0]);
        if (module.atWar(crew, other)) {
            throw module.fail("rival-at-war", "crew", other.displayName());
        }
        if (crew.rivals.contains(other.id)) {
            throw module.fail("already-rival", "crew", other.displayName());
        }
        boolean wasAlly = module.breakAlliance(crew, other);
        crew.rivals.add(other.id);
        module.allyRequests.remove(CrewsModule.requestKey(crew, other));
        module.markDirty();
        crew.broadcast(module.msg("rival-declared", "crew", other.displayName()));
        other.broadcast(module.msg("rival-declared-against", "crew", crew.displayName()));
        if (wasAlly) {
            other.broadcast(module.msg("ally-broken", "crew", crew.displayName()));
        }
    }

    private void war(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew war <crew>");
        if (!module.config().getBoolean("war.enabled", true)) {
            throw module.fail("war-disabled");
        }
        CrewImpl crew = myCrew(p);
        require(crew, p, "war");
        CrewImpl other = otherCrew(crew, args[0]);
        if (module.atWar(crew, other)) {
            throw module.fail("already-at-war", "crew", other.displayName());
        }
        if (module.config().getBoolean("war.require-rival", false) && !crew.rivals.contains(other.id)) {
            throw module.fail("war-needs-rival", "crew", other.displayName());
        }
        int max = module.config().getInt("war.max-active-wars", 2);
        if (max >= 0 && crew.wars.size() >= max) {
            throw module.fail("war-limit", "max", max);
        }
        int minMembers = module.config().getInt("war.min-members", 1);
        if (crew.ranks.size() < minMembers || other.ranks.size() < minMembers) {
            throw module.fail("war-too-small", "min", minMembers);
        }
        double cost = module.cost("war.cost");
        if (cost > 0 && !crew.withdrawBank(cost)) {
            throw module.fail("war-cannot-afford", "cost", module.money(cost), "balance", module.money(crew.bank));
        }
        module.startWar(crew, other);
    }

    private void peace(Player p, String[] args) {
        AquaticCommand.need(args, 1, "/crew peace <crew>");
        CrewImpl crew = myCrew(p);
        require(crew, p, "war");
        CrewImpl other = otherCrew(crew, args[0]);
        if (!module.atWar(crew, other)) {
            throw module.fail("not-at-war", "crew", other.displayName());
        }
        CrewImpl.War war = crew.wars.get(other.id);
        long minMillis = Math.max(0, module.config().getLong("war.min-duration-minutes", 60)) * 60_000L;
        long elapsed = war == null ? Long.MAX_VALUE : System.currentTimeMillis() - war.since;
        if (elapsed < minMillis) {
            throw module.fail("peace-too-early", "time", Text.duration((minMillis - elapsed + 999) / 1000L));
        }
        if (module.hasRequest(module.peaceRequests, other, crew)) {
            module.endWar(crew, other);
            return;
        }
        if (module.hasRequest(module.peaceRequests, crew, other)) {
            throw module.fail("peace-already-offered", "crew", other.displayName());
        }
        module.peaceRequests.put(CrewsModule.requestKey(crew, other), module.requestExpiry());
        crew.broadcast(module.msg("peace-offered", "crew", other.displayName()));
        other.broadcast(module.msg("peace-offer-received", "crew", crew.displayName(), "tag", crew.name));
    }

    // ------------------------------------------------------------------ admin

    private UUID anyPlayer(String name) {
        OfflinePlayer target = Players.resolve(name);
        if (target != null) {
            return target.getUniqueId();
        }
        for (CrewImpl crew : module.crews.values()) {
            UUID id = crew.memberByName(name);
            if (id != null) {
                return id;
            }
        }
        throw module.fail("unknown-player", "player", name);
    }

    /** Removes a player from whatever crew they are in, handing over or disbanding when they are the captain. */
    private void forceRemove(UUID player) {
        CrewImpl crew = module.byPlayer.get(player);
        if (crew == null) {
            return;
        }
        if (player.equals(crew.captain)) {
            if (crew.ranks.size() <= 1) {
                module.disband(crew);
                return;
            }
            UUID successor = null;
            for (UUID id : crew.sortedMembers()) {
                if (!id.equals(player)) {
                    successor = id;
                    break;
                }
            }
            module.setCaptain(crew, successor);
            crew.broadcast(module.msg("captain-changed", "player", crew.nameOf(successor), "crew", crew.displayName()));
        }
        String name = crew.nameOf(player);
        module.removeMember(crew, player);
        crew.broadcast(module.msg("member-left", "player", name));
    }

    private void admin(CommandSender sender, String[] args) {
        String usage = "/crew admin <disband|setcaptain|setrep|join|kick> ...";
        AquaticCommand.need(args, 1, usage);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "disband" -> {
                AquaticCommand.need(args, 2, "/crew admin disband <crew>");
                CrewImpl crew = crewArg(args[1]);
                crew.broadcast(module.msg("crew-disbanded-admin", "crew", crew.displayName()));
                module.disband(crew);
                module.send(sender, "admin-disbanded", "crew", crew.displayName());
            }
            case "setcaptain" -> {
                AquaticCommand.need(args, 3, "/crew admin setcaptain <crew> <player>");
                CrewImpl crew = crewArg(args[1]);
                UUID target = crew.memberByName(args[2]);
                if (target == null) {
                    target = anyPlayer(args[2]);
                    if (module.byPlayer.get(target) != crew) {
                        forceRemove(target);
                        if (!module.crews.containsKey(crew.id)) {
                            throw module.fail("unknown-crew", "crew", args[1]);
                        }
                        module.addMember(crew, target, args[2]);
                    }
                }
                module.setCaptain(crew, target);
                crew.broadcast(module.msg("captain-changed", "player", crew.nameOf(target), "crew", crew.displayName()));
                module.send(sender, "admin-done");
            }
            case "setrep" -> {
                AquaticCommand.need(args, 3, "/crew admin setrep <crew> <amount>");
                CrewImpl crew = crewArg(args[1]);
                crew.reputation = AquaticCommand.integer(args[2]);
                module.markDirty();
                module.send(sender, "admin-setrep", "crew", crew.displayName(), "reputation", crew.reputation);
            }
            case "join" -> {
                AquaticCommand.need(args, 2, "/crew admin join <crew> [player]");
                CrewImpl crew = crewArg(args[1]);
                UUID target;
                String name;
                if (args.length > 2) {
                    target = anyPlayer(args[2]);
                    name = args[2];
                } else {
                    Player self = AquaticCommand.player(sender);
                    target = self.getUniqueId();
                    name = self.getName();
                }
                if (module.byPlayer.get(target) == crew) {
                    throw module.fail("target-in-crew", "player", name);
                }
                forceRemove(target);
                if (!module.crews.containsKey(crew.id)) {
                    throw module.fail("unknown-crew", "crew", args[1]);
                }
                Player online = Bukkit.getPlayer(target);
                module.addMember(crew, target, online != null ? online.getName() : name);
                crew.broadcast(module.msg("member-joined", "player", crew.nameOf(target), "crew", crew.displayName()));
                module.send(sender, "admin-done");
            }
            case "kick" -> {
                AquaticCommand.need(args, 2, "/crew admin kick <player>");
                UUID target = anyPlayer(args[1]);
                if (!module.byPlayer.containsKey(target)) {
                    throw module.fail("player-no-crew", "player", args[1]);
                }
                forceRemove(target);
                module.send(sender, "admin-done");
            }
            default -> throw new CommandFail("<red>Usage: <white><usage>", "usage", usage);
        }
    }

    private List<String> adminComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return AquaticCommand.filter(List.of("disband", "setcaptain", "setrep", "join", "kick"), args[0]);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return action.equals("kick") ? AquaticCommand.playerNames(args[1]) : crewComplete(args[1]);
        }
        if (args.length == 3) {
            if (action.equals("setrep")) {
                return AquaticCommand.filter(List.of("0", "100", "1000"), args[2]);
            }
            if (action.equals("setcaptain") || action.equals("join")) {
                return AquaticCommand.playerNames(args[2]);
            }
        }
        return List.of();
    }
}
