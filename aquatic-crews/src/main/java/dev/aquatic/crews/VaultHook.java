package dev.aquatic.crews;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.util.List;
import java.util.UUID;

/**
 * Exposes Doubloons to other plugins through Vault.
 * This class references Vault types, so it must only be touched after checking that Vault is enabled.
 */
final class VaultHook implements Economy {

    private final EconomyModule module;

    private VaultHook(EconomyModule module) {
        this.module = module;
    }

    static Object register(EconomyModule module) {
        VaultHook hook = new VaultHook(module);
        Bukkit.getServicesManager().register(Economy.class, hook, module.plugin(), ServicePriority.Highest);
        return hook;
    }

    static void unregister(Object hook) {
        Bukkit.getServicesManager().unregister(Economy.class, hook);
    }

    private UUID id(String name) {
        return name == null ? null : module.idOf(name);
    }

    private UUID id(OfflinePlayer player) {
        if (player == null) {
            return null;
        }
        module.rememberName(player.getUniqueId(), player.getName());
        return player.getUniqueId();
    }

    private EconomyResponse fail(String message) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, message);
    }

    private EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Doubloons have no Vault banks; use crew banks.");
    }

    private EconomyResponse withdraw(UUID id, double amount) {
        if (id == null) {
            return fail("Unknown player");
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) {
            return fail("Invalid amount");
        }
        if (!module.withdraw(id, amount)) {
            return new EconomyResponse(0, module.balance(id), EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }
        return new EconomyResponse(amount, module.balance(id), EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse deposit(UUID id, double amount) {
        if (id == null) {
            return fail("Unknown player");
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) {
            return fail("Invalid amount");
        }
        if (module.balance(id) + amount > module.maxBalance()) {
            return new EconomyResponse(0, module.balance(id), EconomyResponse.ResponseType.FAILURE, "Balance limit reached");
        }
        module.deposit(id, amount);
        return new EconomyResponse(amount, module.balance(id), EconomyResponse.ResponseType.SUCCESS, null);
    }

    // ------------------------------------------------------------------ general

    @Override
    public boolean isEnabled() {
        return module.isEnabled();
    }

    @Override
    public String getName() {
        return "AquaticSMP";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return module.decimals();
    }

    @Override
    public String format(double amount) {
        return module.format(amount);
    }

    @Override
    public String currencyNamePlural() {
        return module.currencyName();
    }

    @Override
    public String currencyNameSingular() {
        return module.singular();
    }

    // ------------------------------------------------------------------ accounts

    @Override
    public boolean hasAccount(String playerName) {
        UUID id = id(playerName);
        return id != null && module.hasAccount(id);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null && module.hasAccount(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        UUID id = id(playerName);
        return id == null ? 0 : module.balance(id);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return player == null ? 0 : module.balance(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        UUID id = id(playerName);
        return id != null && module.has(id, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return player != null && module.has(player.getUniqueId(), amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdraw(id(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(id(player), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return deposit(id(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return deposit(id(player), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        UUID id = id(playerName);
        if (id == null) {
            return false;
        }
        module.createAccount(id);
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        UUID id = id(player);
        if (id == null) {
            return false;
        }
        module.createAccount(id);
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ------------------------------------------------------------------ banks (not supported)

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
