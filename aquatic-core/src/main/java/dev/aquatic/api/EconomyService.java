package dev.aquatic.api;

import java.util.UUID;

/** Doubloons. Provided by the "economy" module. */
public interface EconomyService {

    double balance(UUID player);

    boolean has(UUID player, double amount);

    void deposit(UUID player, double amount);

    /** @return false (and nothing is taken) when the player cannot afford it. */
    boolean withdraw(UUID player, double amount);

    /** e.g. "1,250 Doubloons" */
    String format(double amount);

    /** Plural currency name, e.g. "Doubloons". */
    String currencyName();
}
