package dev.aquatic.crews;

import dev.aquatic.api.BountyService;
import dev.aquatic.api.CrewRank;
import dev.aquatic.api.EconomyService;
import dev.aquatic.util.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * PlaceholderAPI expansion "aquatic". Only touched after checking that PlaceholderAPI is enabled.
 * Placeholders: %aquatic_crew%, %aquatic_crew_tag%, %aquatic_crew_rank%, %aquatic_crew_reputation%,
 * %aquatic_balance%, %aquatic_balance_formatted%, %aquatic_bounty%, %aquatic_bounty_formatted%.
 */
final class PapiHook extends PlaceholderExpansion {

    private final CrewsModule module;

    private PapiHook(CrewsModule module) {
        this.module = module;
    }

    static Object register(CrewsModule module) {
        PapiHook hook = new PapiHook(module);
        hook.register();
        return hook;
    }

    static void unregister(Object hook) {
        ((PapiHook) hook).unregister();
    }

    @Override
    public String getIdentifier() {
        return "aquatic";
    }

    @Override
    public String getAuthor() {
        return "AquaticSMP";
    }

    @Override
    public String getVersion() {
        return module.plugin().getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        String none = module.config().getString("placeholderapi.no-crew", "");
        CrewImpl crew = module.byPlayer.get(player.getUniqueId());
        switch (params.toLowerCase(Locale.ROOT)) {
            case "crew":
                return crew == null ? none : crew.name;
            case "crew_tag":
                return crew == null ? none : crew.tag;
            case "crew_tag_colored":
                return crew == null ? none : Text.legacy(crew.tagComponent());
            case "crew_rank": {
                CrewRank rank = crew == null ? null : crew.rankOf(player.getUniqueId());
                return rank == null ? none : module.rankName(rank);
            }
            case "crew_reputation":
                return crew == null ? "0" : String.valueOf(crew.reputation);
            case "crew_members":
                return crew == null ? "0" : String.valueOf(crew.ranks.size());
            case "balance": {
                EconomyService eco = module.service(EconomyService.class);
                return eco == null ? "0" : Text.number(eco.balance(player.getUniqueId()));
            }
            case "balance_formatted": {
                EconomyService eco = module.service(EconomyService.class);
                return eco == null ? "" : eco.format(eco.balance(player.getUniqueId()));
            }
            case "bounty": {
                BountyService bounty = module.service(BountyService.class);
                return bounty == null ? "0" : Text.number(bounty.bountyOf(player.getUniqueId()));
            }
            case "bounty_formatted": {
                BountyService bounty = module.service(BountyService.class);
                EconomyService eco = module.service(EconomyService.class);
                double amount = bounty == null ? 0 : bounty.bountyOf(player.getUniqueId());
                return eco == null ? Text.number(amount) : eco.format(amount);
            }
            default:
                return null;
        }
    }
}
