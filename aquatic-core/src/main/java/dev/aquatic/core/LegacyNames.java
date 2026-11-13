package dev.aquatic.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names that were renamed in 2.3.1 (old One Piece style ids -> Aquatic names). Old config files are rewritten
 * on load (a .bak copy is kept), and old item ids on items and in saved data are mapped to the new ones.
 */
public final class LegacyNames {

    /** Old word -> new word, longest first (a "word" is letters, digits and underscores). */
    private static final List<Map.Entry<String, String>> RENAMES = List.of(
            Map.entry("ushi_ushi_bison", "behemoth"),
            Map.entry("gomu_gomu", "springcoil"),
            Map.entry("mera_mera", "emberheart"),
            Map.entry("hie_hie", "rimefrost"),
            Map.entry("goro_goro", "thunderveil"),
            Map.entry("suke_suke", "ghostglass"),
            Map.entry("ope_ope", "riftshift"),
            Map.entry("yami_yami", "hollowvoid"),
            Map.entry("sea_prism_blade", "abyssal_baton"),
            Map.entry("seastone_cuffs", "abyssal_shackles"),
            Map.entry("wado_ichimonji", "tidecleaver"),
            Map.entry("shusui", "nightbloom"),
            Map.entry("yoru", "leviathans_fang"),
            Map.entry("den_den_mushi", "echo_conch"),
            Map.entry("log_pose", "tide_compass"),
            Map.entry("pirate_king_crown", "conqueror_crown"),
            Map.entry("devil_fruit_random", "fruit_random"),
            Map.entry("marine_rifleman", "tidewatch_rifleman"),
            Map.entry("marine_officer", "tidewatch_lieutenant"),
            Map.entry("marine", "tidewatch_recruit"),
            Map.entry("vice_admiral", "warden_captain"),
            Map.entry("admiral", "high_warden"),
            Map.entry("seastoned", "sealed"),
            Map.entry("seastone", "abyssal"),
            Map.entry("logpose", "compass"),
            Map.entry("denden", "conch"),
            Map.entry("pirate-king-prefix", "conqueror-prefix"),
            Map.entry("pirate-king", "conqueror"),
            Map.entry("pirateking", "conqueror")
    );

    private static final Map<String, String> TYPES = Map.of("seastone", "abyssal", "logpose", "compass", "denden", "conch");

    private LegacyNames() {
    }

    /** Maps an old item id to its new id (other ids unchanged, null stays null). */
    public static String id(String id) {
        if (id == null) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : RENAMES) {
            if (e.getKey().equals(lower)) {
                return e.getValue();
            }
        }
        return id;
    }

    /** Maps an old item type ("seastone", "logpose", "denden") to the new one. */
    public static String type(String type) {
        return type == null ? null : TYPES.getOrDefault(type.toLowerCase(Locale.ROOT), type);
    }

    /** Applies the renames to a config text. */
    public static String migrate(String text) {
        String out = text;
        for (Map.Entry<String, String> e : RENAMES) {
            Matcher m = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(e.getKey()) + "(?![A-Za-z0-9_])").matcher(out);
            out = m.replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }

    /** Rewrites a config file that still uses old names (keeps &lt;file&gt;.pre-2.3.1.bak). */
    static void migrateFile(File file, Logger logger) {
        if (!file.isFile()) {
            return;
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String migrated = migrate(text);
            if (migrated.equals(text)) {
                return;
            }
            File backup = new File(file.getParentFile(), file.getName() + ".pre-2.3.1.bak");
            if (!backup.exists()) {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file.toPath(), migrated, StandardCharsets.UTF_8);
            logger.info("Updated old item and mob names in " + file.getName() + " (backup: " + backup.getName() + ")");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not update old names in " + file.getName(), e);
        }
    }
}
