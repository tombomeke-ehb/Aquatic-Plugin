package dev.aquatic.events.items;

import dev.aquatic.core.AquaticPlugin;
import dev.aquatic.events.ItemsModule;
import dev.aquatic.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Sends the AquaticSMP resource pack to every player who joins, so server.properties needs no
 * resource-pack lines. By default it is the pack that belongs to this plugin version (its download link on
 * the GitHub release, with the SHA-1 that was built into the jar), so updating the jar updates the pack.
 * Players only download it again when the pack really changed (same SHA-1 = their saved copy is used).
 */
public final class PackSender implements Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("aquatic-smp-pack".getBytes(StandardCharsets.UTF_8));

    private final ItemsModule module;
    private String url;
    private byte[] hash;

    public PackSender(ItemsModule module) {
        this.module = module;
        load();
    }

    private boolean active() {
        return module.config().getBoolean("resource-pack.enabled", false) && module.config().getBoolean("resource-pack.send", true);
    }

    private void load() {
        url = null;
        hash = null;
        if (!active()) {
            return;
        }
        String customUrl = module.config().getString("resource-pack.url", "").trim();
        String sha = module.config().getString("resource-pack.sha1", "").trim();
        if (customUrl.isEmpty()) {
            String version = module.plugin().getPluginMeta().getVersion();
            customUrl = AquaticPlugin.REPO + "/releases/download/v" + version + "/AquaticSMP-ResourcePack.zip";
            sha = builtInSha();
        }
        if (sha == null || !sha.matches("[0-9a-fA-F]{40}")) {
            module.logger().warning("Resource pack: no valid SHA-1 (this jar was not built with the pack, or resource-pack.sha1 is wrong). "
                    + "The pack is not sent; set resource-pack.url and resource-pack.sha1 in items.yml.");
            return;
        }
        url = customUrl;
        hash = HexFormat.of().parseHex(sha.toLowerCase());
        String fromProperties = Bukkit.getServer().getResourcePack();
        if (fromProperties != null && !fromProperties.isBlank()) {
            module.logger().warning("Resource pack: the plugin sends the pack now. Remove resource-pack= and resource-pack-sha1= "
                    + "from server.properties, or players get two copies (one of them possibly outdated).");
        }
        module.logger().info("Resource pack: sending " + url);
    }

    private String builtInSha() {
        try (InputStream in = module.plugin().getResource("aquatic-pack.sha1")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    public void send(Player p) {
        if (url == null || hash == null) {
            return;
        }
        boolean required = module.config().getBoolean("resource-pack.required", true);
        p.setResourcePack(PACK_ID, url, hash, Text.mm(module.config().getString("resource-pack.prompt", "")), required);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        module.runLater(() -> {
            if (p.isOnline()) {
                send(p);
            }
        }, 10L);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) {
            return;
        }
        switch (event.getStatus()) {
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                module.logger().warning("Resource pack: " + event.getPlayer().getName() + " could not load it (" + event.getStatus() + ")");
                event.getPlayer().sendMessage(module.msg("pack-failed"));
            }
            case DECLINED -> {
                if (!module.config().getBoolean("resource-pack.required", true)) {
                    event.getPlayer().sendMessage(module.msg("pack-declined"));
                }
            }
            default -> {
            }
        }
    }
}
