package dev.aquatic.crews;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Friendly fire, reputation from kills, chat formatting, crew chat and the /crew home warmup. */
final class CrewListener implements Listener {

    private record Warmup(BukkitTask task, Location start) {
    }

    private final CrewsModule module;
    private final Map<UUID, Warmup> warmups = new HashMap<>();
    private final Map<UUID, Long> homeCooldowns = new HashMap<>();
    /** "killer:victim" -> time of the last kill that counted for reputation. */
    private final Map<String, Long> recentKills = new HashMap<>();
    private final Map<UUID, Long> lastFriendlyWarning = new HashMap<>();

    CrewListener(CrewsModule module) {
        this.module = module;
    }

    void shutdown() {
        for (Warmup warmup : warmups.values()) {
            warmup.task().cancel();
        }
        warmups.clear();
    }

    void purge(long now) {
        long window = Math.max(0, module.config().getLong("reputation.repeat-kill-cooldown-seconds", 300)) * 1000L;
        recentKills.values().removeIf(time -> now - time > window);
        lastFriendlyWarning.values().removeIf(time -> now - time > 10_000L);
        long cooldown = Math.max(0, module.config().getLong("home.cooldown-seconds", 300)) * 1000L;
        homeCooldowns.values().removeIf(time -> now - time > cooldown);
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        CrewImpl crew = module.byPlayer.get(p.getUniqueId());
        if (crew != null) {
            String old = crew.names.put(p.getUniqueId(), p.getName());
            if (!p.getName().equals(old)) {
                module.markDirty();
            }
        }
        module.updateTab(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Warmup warmup = warmups.remove(id);
        if (warmup != null) {
            warmup.task().cancel();
        }
        module.crewChat.remove(id);
        lastFriendlyWarning.remove(id);
    }

    // ------------------------------------------------------------------ friendly fire

    /** The player responsible for a damaging entity (arrows, tridents, TNT, potions clouds, pets), or null. */
    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) {
            return p;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) {
            return p;
        }
        if (damager instanceof Tameable pet && pet.isTamed() && pet.getOwner() instanceof Player p) {
            return p;
        }
        return null;
    }

    /** True when "attacker" must not be able to hurt "victim" (same crew without friendly fire, or protected ally). */
    private boolean protectedFrom(Player attacker, Player victim) {
        if (attacker.getUniqueId().equals(victim.getUniqueId())) {
            return false;
        }
        CrewImpl a = module.byPlayer.get(attacker.getUniqueId());
        CrewImpl v = module.byPlayer.get(victim.getUniqueId());
        if (a == null || v == null) {
            return false;
        }
        if (a == v) {
            return !a.friendlyFire;
        }
        return module.config().getBoolean("friendly-fire.protect-allies", true) && module.allied(a, v);
    }

    private void warn(Player attacker, Player victim) {
        long now = System.currentTimeMillis();
        Long last = lastFriendlyWarning.get(attacker.getUniqueId());
        if (last != null && now - last < 2000L) {
            return;
        }
        lastFriendlyWarning.put(attacker.getUniqueId(), now);
        attacker.sendActionBar(module.rawMsg("friendly-fire-blocked", "player", victim.getName()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attacker(event.getDamager());
        if (attacker != null && protectedFrom(attacker, victim)) {
            event.setCancelled(true);
            warn(attacker, victim);
        }
    }

    private static boolean harmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            PotionEffectType type = effect.getType();
            if (type.getCategory() == PotionEffectTypeCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player thrower) || !harmful(event.getPotion().getEffects())) {
            return;
        }
        for (LivingEntity hit : event.getAffectedEntities()) {
            if (hit instanceof Player victim && protectedFrom(thrower, victim)) {
                event.setIntensity(victim, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player thrower)) {
            return;
        }
        Collection<PotionEffect> effects = new ArrayList<>(cloud.getCustomEffects());
        PotionType base = cloud.getBasePotionType();
        if (base != null) {
            effects.addAll(base.getPotionEffects());
        }
        if (!harmful(effects)) {
            return;
        }
        event.getAffectedEntities().removeIf(hit -> hit instanceof Player victim && protectedFrom(thrower, victim));
    }

    // ------------------------------------------------------------------ reputation & war score

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        CrewImpl kc = module.byPlayer.get(killer.getUniqueId());
        CrewImpl vc = module.byPlayer.get(victim.getUniqueId());
        if (kc == null && vc == null) {
            return;
        }
        if (kc != null && vc != null && (kc == vc || module.allied(kc, vc))) {
            return; // no fame for killing your own
        }
        boolean war = kc != null && vc != null && module.atWar(kc, vc);

        // the same victim only counts once per cooldown, so reputation cannot be farmed with an alt
        long now = System.currentTimeMillis();
        long window = Math.max(0, module.config().getLong("reputation.repeat-kill-cooldown-seconds", 300)) * 1000L;
        String key = killer.getUniqueId() + ":" + victim.getUniqueId();
        Long last = recentKills.get(key);
        if (window > 0 && last != null && now - last < window) {
            return;
        }
        recentKills.put(key, now);

        if (war) {
            CrewImpl.War record = kc.wars.get(vc.id);
            if (record != null) {
                record.kills++;
                module.markDirty();
            }
        }
        if (kc != null) {
            int gain = Math.max(0, module.config().getInt(vc == null ? "reputation.kill-crewless" : war ? "reputation.war-kill" : "reputation.kill", 0));
            if (gain > 0) {
                kc.addReputation(gain);
                module.send(killer, "reputation-gained", "amount", gain, "victim", victim.getName(), "reputation", kc.reputation());
            }
        }
        if (vc != null) {
            int loss = Math.max(0, module.config().getInt(war ? "reputation.war-death" : "reputation.death", 0));
            if (loss > 0) {
                vc.addReputation(-loss);
                module.send(victim, "reputation-lost", "amount", loss, "killer", killer.getName(), "reputation", vc.reputation());
            }
        }
    }

    // ------------------------------------------------------------------ chat

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCrewChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!module.crewChat.contains(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        Component message = event.message();
        Bukkit.getScheduler().runTask(module.plugin(), () -> module.sendCrewChat(player, message));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPublicChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Component prefix = module.chatPrefix(id);
        Component card = module.playerCard(id);
        Component rank = module.permPrefix(player);
        Component suffix = module.permSuffix(player);
        org.bukkit.configuration.ConfigurationSection cfg = module.config().getConfigurationSection("chat");
        boolean format = cfg != null && cfg.getBoolean("format.enabled", true);
        String line = dev.aquatic.util.Placeholders.apply(player, cfg == null ? "" : cfg.getString("format.line", "<level><prefix><name><dark_gray> » <white><message>"));
        Integer level = module.cachedLevel(id);
        Component badge = level == null || cfg == null || cfg.getString("format.level-badge", "").isEmpty() ? Component.empty()
                : dev.aquatic.util.Text.mm(cfg.getString("format.level-badge"), "level", level);

        // [item]: shows the item in your hand, hover for all its details
        Component item = null;
        String itemTag = cfg == null ? "[item]" : cfg.getString("item-tag", "[item]");
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
        if (itemTag != null && !itemTag.isEmpty() && plain.contains(itemTag)) {
            org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
            if (!hand.getType().isAir()) {
                item = hand.displayName();
            }
        }

        // @mentions: highlight the name and ping that player
        List<String> mentioned = new ArrayList<>();
        boolean mentions = cfg == null || cfg.getBoolean("mentions.enabled", true);
        if (mentions) {
            String lower = plain.toLowerCase(java.util.Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                String n = online.getName().toLowerCase(java.util.Locale.ROOT);
                if (!online.equals(player) && java.util.regex.Pattern.compile("(?<![a-z0-9_])@?" + java.util.regex.Pattern.quote(n)
                        + "(?![a-z0-9_])").matcher(lower).find()) {
                    mentioned.add(online.getName());
                }
            }
        }
        if (!mentioned.isEmpty()) {
            String sound = cfg == null ? "entity.experience_orb.pickup" : cfg.getString("mentions.sound", "entity.experience_orb.pickup");
            List<String> names = new ArrayList<>(mentioned);
            Bukkit.getScheduler().runTask(module.plugin(), () -> {
                for (String n : names) {
                    Player target = Bukkit.getPlayerExact(n);
                    if (target != null) {
                        try {
                            target.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key(sound),
                                    net.kyori.adventure.sound.Sound.Source.PLAYER, 1f, 1.4f));
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            });
        }
        String highlight = cfg == null ? "<yellow>@<name></yellow>" : cfg.getString("mentions.highlight", "<yellow>@<name></yellow>");

        if (prefix == null && card == null && !format && item == null && mentioned.isEmpty()) {
            return;
        }
        ChatRenderer base = event.renderer();
        String name = player.getName();
        Component ranked = PermsHook.name(player);         // the LuckPerms display name or nickname
        Component itemComponent = item;
        event.renderer((source, displayName, message, viewer) -> {
            Component msg = message;
            if (itemComponent != null) {
                msg = msg.replaceText(b -> b.matchLiteral(itemTag).once().replacement(itemComponent));
            }
            for (String n : mentioned) {
                Component hl = dev.aquatic.util.Text.mm(highlight, "name", n);
                msg = msg.replaceText(b -> b.match("(?i)(?<![A-Za-z0-9_])@?" + java.util.regex.Pattern.quote(n) + "(?![A-Za-z0-9_])")
                        .replacement(hl));
            }
            // hover the name for the player card, click it to start a private message
            Component who = ranked == null ? displayName : ranked;
            Component shownName = card == null ? who : who
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(card))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/msg " + name + " "));
            if (format) {
                return dev.aquatic.util.Text.mm(line, "level", badge, "prefix", prefix == null ? Component.empty() : prefix,
                        "name", shownName, "message", msg, "rank", rank, "suffix", suffix);
            }
            Component rendered = base.render(source, shownName, msg, viewer);
            return prefix == null ? rendered : Component.textOfChildren(prefix, rendered);
        });
    }

    // ------------------------------------------------------------------ /crew home warmup

    /** Seconds left before the player may use /crew home again (0 = ready). */
    long homeCooldownLeft(Player player) {
        long cooldown = Math.max(0, module.config().getLong("home.cooldown-seconds", 300)) * 1000L;
        Long last = homeCooldowns.get(player.getUniqueId());
        if (last == null || cooldown == 0) {
            return 0;
        }
        long left = cooldown - (System.currentTimeMillis() - last);
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    boolean isWarmingUp(Player player) {
        return warmups.containsKey(player.getUniqueId());
    }

    /** Starts the teleport to the crew home, honouring the configured warmup. */
    void startHome(Player player, CrewImpl crew) {
        int seconds = Math.max(0, module.config().getInt("home.warmup-seconds", 5));
        if (seconds == 0) {
            teleportHome(player, crew);
            return;
        }
        UUID id = player.getUniqueId();
        BukkitTask task = module.runLater(() -> {
            if (warmups.remove(id) != null && player.isOnline()) {
                teleportHome(player, crew);
            }
        }, seconds * 20L);
        warmups.put(id, new Warmup(task, player.getLocation()));
        module.send(player, "home-warmup", "seconds", seconds);
    }

    private void teleportHome(Player player, CrewImpl crew) {
        // the crew may have been left/disbanded or the home moved during the warmup
        if (module.byPlayer.get(player.getUniqueId()) != crew) {
            module.send(player, "not-in-crew");
            return;
        }
        Location home = crew.home();
        if (home == null) {
            module.send(player, crew.hasHome() ? "home-world-missing" : "home-not-set");
            return;
        }
        homeCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        player.teleportAsync(home, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(success -> {
            if (!player.isOnline()) {
                return;
            }
            if (success) {
                module.send(player, "home-teleported");
            } else {
                homeCooldowns.remove(player.getUniqueId());
                module.send(player, "home-failed");
            }
        });
    }

    /** Cancels a running warmup; "messageKey" (may be null) is sent to the player when one was cancelled. */
    void cancelWarmup(Player player, String messageKey) {
        Warmup warmup = warmups.remove(player.getUniqueId());
        if (warmup != null) {
            warmup.task().cancel();
            if (messageKey != null) {
                module.send(player, messageKey);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (warmups.isEmpty()) {
            return;
        }
        Warmup warmup = warmups.get(event.getPlayer().getUniqueId());
        if (warmup == null) {
            return;
        }
        Location start = warmup.start();
        Location to = event.getTo();
        if (to.getWorld() != start.getWorld() || to.distanceSquared(start) > 0.25) {
            cancelWarmup(event.getPlayer(), "home-cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (warmups.isEmpty() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        cancelWarmup(player, "home-cancelled-damage");
    }
}
