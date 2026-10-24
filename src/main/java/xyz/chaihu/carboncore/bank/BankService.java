/*
 * This file is part of CarbonCore.
 *
 * Copyright (C) 2026 Chaihu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package xyz.chaihu.carboncore.bank;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Bank account operations backed by the shared SQLite database.
 * Every database access is asynchronous; Vault money movement always runs on the main thread.
 */
public final class BankService {

    private final CarbonCorePlugin plugin;
    /** Display / placeholder cache only; business logic treats the database as the source of truth. */
    private final Map<UUID, Double> balanceCache = new ConcurrentHashMap<>();
    private BukkitTask interestTask;

    private record AccountRow(UUID uuid, String name, double balance) {
    }

    public BankService(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String fmt(double value) {
        return plugin.economy().format(value);
    }

    private Executor main() {
        return run -> Bukkit.getScheduler().runTask(plugin, run);
    }

    private double minAmount() {
        return plugin.configManager().getDouble("modules.bank.min-amount", 0.01);
    }

    private double maxAmount() {
        return plugin.configManager().getDouble("modules.bank.max-amount", 1000000.0);
    }

    /** Resolves a name to an online player first, then to the offline player cache. */
    public OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    // ------------------------------------------------------------------
    // cache
    // ------------------------------------------------------------------

    /** Loads every stored balance into the memory cache (called once on enable). */
    public void loadCache() {
        plugin.database().queryList("SELECT uuid, balance FROM bank_accounts",
                        rs -> new AccountRow(UUID.fromString(rs.getString("uuid")), null, rs.getDouble("balance")))
                .thenAcceptAsync(rows -> {
                    for (AccountRow row : rows) {
                        balanceCache.put(row.uuid(), round2(row.balance()));
                    }
                }, main());
    }

    public double cachedBankBalance(UUID uuid) {
        return balanceCache.getOrDefault(uuid, 0.0);
    }

    /** Re-reads one account from the database into the cache. */
    public void refreshCache(UUID uuid) {
        plugin.database().queryOne("SELECT balance FROM bank_accounts WHERE uuid = ?",
                        rs -> rs.getDouble(1), uuid.toString())
                .thenAcceptAsync(bal -> {
                    if (bal != null) {
                        balanceCache.put(uuid, round2(bal));
                    }
                }, main());
    }

    // ------------------------------------------------------------------
    // account
    // ------------------------------------------------------------------

    public CompletableFuture<Void> ensureAccount(UUID uuid, String name) {
        return plugin.database().update(
                        "INSERT OR IGNORE INTO bank_accounts(uuid,name,balance,last_interest) VALUES(?,?,0,?)",
                        uuid.toString(), name, System.currentTimeMillis())
                .thenApply(rows -> (Void) null);
    }

    // ------------------------------------------------------------------
    // deposit
    // ------------------------------------------------------------------

    public void deposit(Player player, double amount) {
        deposit(player, amount, null);
    }

    /**
     * Deposits wallet money into the bank. Validation and the Vault withdrawal happen
     * on the calling (main) thread; the database write and the result message are async.
     */
    public void deposit(Player player, double amount, Runnable onDone) {
        UUID uuid = player.getUniqueId();
        amount = round2(amount);
        if (amount <= 0) {
            plugin.messages().sendPlayer(player, "bank.deposit-zero");
            return;
        }
        if (amount < minAmount()) {
            plugin.messages().sendPlayer(player, "general.amount-too-low", "min", fmt(minAmount()));
            return;
        }
        if (amount > maxAmount()) {
            plugin.messages().sendPlayer(player, "general.amount-too-high", "max", fmt(maxAmount()));
            return;
        }
        if (!plugin.economy().withdraw(player, amount)) {
            plugin.messages().sendPlayer(player, "general.insufficient-funds", "amount", fmt(amount));
            return;
        }
        final double amt = amount;
        final String name = player.getName();
        ensureAccount(uuid, name)
                .thenCompose(ignored -> plugin.database().update(
                        "UPDATE bank_accounts SET balance = balance + ? WHERE uuid = ?", amt, uuid.toString()))
                .thenCompose(ignored -> plugin.database().queryOne(
                        "SELECT balance FROM bank_accounts WHERE uuid = ?", rs -> rs.getDouble(1), uuid.toString()))
                .thenAcceptAsync(bal -> {
                    double newBalance = bal == null ? 0.0 : round2(bal);
                    balanceCache.put(uuid, newBalance);
                    if (player.isOnline()) {
                        plugin.messages().sendPlayer(player, "bank.deposit-success",
                                "amount", fmt(amt), "balance", fmt(newBalance));
                    }
                    if (onDone != null) {
                        onDone.run();
                    }
                }, main())
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Bank deposit write failed for " + name + ", refunding wallet: " + ex);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.economy().deposit(player, amt);
                        if (player.isOnline()) {
                            plugin.messages().sendPlayer(player, "general.economy-missing");
                        }
                        if (onDone != null) {
                            onDone.run();
                        }
                    });
                    return null;
                });
    }

    // ------------------------------------------------------------------
    // withdraw
    // ------------------------------------------------------------------

    public void withdraw(Player player, double amount) {
        withdraw(player, amount, null);
    }

    /**
     * Withdraws bank money into the wallet using an atomic compare-and-set update;
     * the Vault deposit only runs when the debit actually took effect.
     */
    public void withdraw(Player player, double amount, Runnable onDone) {
        UUID uuid = player.getUniqueId();
        amount = round2(amount);
        if (amount <= 0 || cachedBankBalance(uuid) <= 0) {
            plugin.messages().sendPlayer(player, "bank.withdraw-zero");
            return;
        }
        withdrawCore(player, amount, onDone);
    }

    private void withdrawCore(Player player, double amount, Runnable onDone) {
        UUID uuid = player.getUniqueId();
        if (amount < minAmount()) {
            plugin.messages().sendPlayer(player, "general.amount-too-low", "min", fmt(minAmount()));
            return;
        }
        final double amt = amount;
        plugin.database().update(
                        "UPDATE bank_accounts SET balance = balance - ? WHERE uuid = ? AND balance >= ?",
                        amt, uuid.toString(), amt)
                .thenAcceptAsync(rows -> {
                    if (rows == 1) {
                        if (plugin.economy().deposit(player, amt)) {
                            plugin.database().queryOne(
                                            "SELECT balance FROM bank_accounts WHERE uuid = ?",
                                            rs -> rs.getDouble(1), uuid.toString())
                                    .thenAcceptAsync(bal -> {
                                        double newBalance = bal == null ? 0.0 : round2(bal);
                                        balanceCache.put(uuid, newBalance);
                                        if (player.isOnline()) {
                                            plugin.messages().sendPlayer(player, "bank.withdraw-success",
                                                    "amount", fmt(amt), "balance", fmt(newBalance));
                                        }
                                        if (onDone != null) {
                                            onDone.run();
                                        }
                                    }, main());
                        } else {
                            plugin.database().update(
                                            "UPDATE bank_accounts SET balance = balance + ? WHERE uuid = ?",
                                            amt, uuid.toString())
                                    .thenAcceptAsync(ignored -> {
                                        plugin.getLogger().warning("Vault deposit failed while withdrawing " + amt
                                                + " for " + player.getName() + ", bank balance rolled back.");
                                        refreshCache(uuid);
                                        if (player.isOnline()) {
                                            plugin.messages().sendPlayer(player, "general.economy-missing");
                                        }
                                        if (onDone != null) {
                                            onDone.run();
                                        }
                                    }, main());
                        }
                    } else {
                        if (player.isOnline()) {
                            plugin.messages().sendPlayer(player, "general.insufficient-funds", "amount", fmt(amt));
                        }
                        if (onDone != null) {
                            onDone.run();
                        }
                    }
                }, main())
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Bank withdraw failed for " + player.getName() + ": " + ex);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            plugin.messages().sendPlayer(player, "general.economy-missing");
                        }
                        if (onDone != null) {
                            onDone.run();
                        }
                    });
                    return null;
                });
    }

    /** Withdraws the exact stored bank balance (used by the "all" actions). */
    public void withdrawAll(Player player, Runnable onDone) {
        plugin.database().queryOne("SELECT balance FROM bank_accounts WHERE uuid = ?",
                        rs -> rs.getDouble(1), player.getUniqueId().toString())
                .thenAcceptAsync(bal -> {
                    double amount = bal == null ? 0.0 : round2(bal);
                    if (amount <= 0) {
                        plugin.messages().sendPlayer(player, "bank.withdraw-zero");
                        if (onDone != null) {
                            onDone.run();
                        }
                        return;
                    }
                    withdrawCore(player, amount, onDone);
                }, main());
    }

    // ------------------------------------------------------------------
    // transfer
    // ------------------------------------------------------------------

    public void transfer(Player from, String toName, double amount) {
        transfer(from, toName, amount, null);
    }

    public void transfer(Player from, String toName, double amount, Runnable onDone) {
        UUID fromUuid = from.getUniqueId();
        amount = round2(amount);
        if (amount <= 0) {
            plugin.messages().sendPlayer(from, "general.invalid-amount", "input", fmt(amount));
            return;
        }
        if (amount < minAmount()) {
            plugin.messages().sendPlayer(from, "general.amount-too-low", "min", fmt(minAmount()));
            return;
        }
        if (amount > maxAmount()) {
            plugin.messages().sendPlayer(from, "general.amount-too-high", "max", fmt(maxAmount()));
            return;
        }
        if (toName == null || toName.isBlank()) {
            plugin.messages().sendPlayer(from, "general.player-not-found", "player", String.valueOf(toName));
            return;
        }
        if (toName.equalsIgnoreCase(from.getName())) {
            plugin.messages().sendPlayer(from, "bank.cant-transfer-self");
            return;
        }
        OfflinePlayer target = findPlayer(toName);
        if (target == null) {
            plugin.messages().sendPlayer(from, "general.player-not-found", "player", toName);
            return;
        }
        if (target.getUniqueId().equals(fromUuid)) {
            plugin.messages().sendPlayer(from, "bank.cant-transfer-self");
            return;
        }
        String resolvedName = target.getName() != null ? target.getName() : toName;
        executeTransfer(from, target.getUniqueId(), resolvedName, amount, onDone);
    }

    /** Transfers the exact stored bank balance (used by the "all" actions). */
    public void transferAll(Player from, UUID toUuid, String toName, Runnable onDone) {
        plugin.database().queryOne("SELECT balance FROM bank_accounts WHERE uuid = ?",
                        rs -> rs.getDouble(1), from.getUniqueId().toString())
                .thenAcceptAsync(bal -> {
                    double b = bal == null ? 0.0 : round2(bal);
                    if (b <= 0) {
                        plugin.messages().sendPlayer(from, "bank.withdraw-zero");
                        if (onDone != null) {
                            onDone.run();
                        }
                        return;
                    }
                    executeTransfer(from, toUuid, toName, b, onDone);
                }, main());
    }

    private void executeTransfer(Player from, UUID toUuid, String toName, double amount, Runnable onDone) {
        String fromUuid = from.getUniqueId().toString();
        plugin.database().transaction(c -> {
            try (PreparedStatement debit = c.prepareStatement(
                    "UPDATE bank_accounts SET balance = balance - ? WHERE uuid = ? AND balance >= ?")) {
                debit.setDouble(1, amount);
                debit.setString(2, fromUuid);
                debit.setDouble(3, amount);
                if (debit.executeUpdate() != 1) {
                    return Boolean.FALSE;
                }
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT OR IGNORE INTO bank_accounts(uuid,name,balance,last_interest) VALUES(?,?,0,?)")) {
                ins.setString(1, toUuid.toString());
                ins.setString(2, toName);
                ins.setLong(3, System.currentTimeMillis());
                ins.executeUpdate();
            }
            try (PreparedStatement credit = c.prepareStatement(
                    "UPDATE bank_accounts SET balance = balance + ? WHERE uuid = ?")) {
                credit.setDouble(1, amount);
                credit.setString(2, toUuid.toString());
                credit.executeUpdate();
            }
            return Boolean.TRUE;
        }).thenAcceptAsync(success -> {
            if (Boolean.TRUE.equals(success)) {
                plugin.database().queryList(
                                "SELECT uuid, balance FROM bank_accounts WHERE uuid IN (?, ?)",
                                rs -> new AccountRow(UUID.fromString(rs.getString("uuid")), null, rs.getDouble("balance")),
                                fromUuid, toUuid.toString())
                        .thenAcceptAsync(rows -> {
                            double fromBalance = 0.0;
                            double toBalance = 0.0;
                            for (AccountRow row : rows) {
                                if (row.uuid().toString().equals(fromUuid)) {
                                    fromBalance = round2(row.balance());
                                } else {
                                    toBalance = round2(row.balance());
                                }
                            }
                            balanceCache.put(from.getUniqueId(), fromBalance);
                            balanceCache.put(toUuid, toBalance);
                            if (from.isOnline()) {
                                plugin.messages().sendPlayer(from, "bank.transfer-success",
                                        "amount", fmt(amount), "to", toName, "balance", fmt(fromBalance));
                            }
                            Player onlineTarget = Bukkit.getPlayer(toUuid);
                            if (onlineTarget != null) {
                                plugin.messages().sendPlayer(onlineTarget, "bank.transfer-received",
                                        "amount", fmt(amount), "from", from.getName());
                            }
                            if (onDone != null) {
                                onDone.run();
                            }
                        }, main());
            } else {
                if (from.isOnline()) {
                    plugin.messages().sendPlayer(from, "general.insufficient-funds", "amount", fmt(amount));
                }
                if (onDone != null) {
                    onDone.run();
                }
            }
        }, main()).exceptionally(ex -> {
            plugin.getLogger().severe("Bank transfer failed for " + from.getName() + ": " + ex);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (from.isOnline()) {
                    plugin.messages().sendPlayer(from, "general.economy-missing");
                }
                if (onDone != null) {
                    onDone.run();
                }
            });
            return null;
        });
    }

    // ------------------------------------------------------------------
    // admin operations
    // ------------------------------------------------------------------

    /** Sets the stored bank balance of a player, clamped to zero or above. */
    public CompletableFuture<Double> adminSet(UUID uuid, String name, double amount) {
        double value = round2(Math.max(0.0, amount));
        return ensureAccount(uuid, name)
                .thenCompose(ignored -> plugin.database().update(
                        "UPDATE bank_accounts SET balance = ? WHERE uuid = ?", value, uuid.toString()))
                .thenApply(rows -> {
                    balanceCache.put(uuid, value);
                    return value;
                });
    }

    /** Adds (or subtracts, for a negative delta) from the stored balance, clamped to zero or above. */
    public CompletableFuture<Double> adminGive(UUID uuid, String name, double delta) {
        double d = round2(delta);
        return ensureAccount(uuid, name)
                .thenCompose(ignored -> plugin.database().queryOne(
                        "SELECT balance FROM bank_accounts WHERE uuid = ?", rs -> rs.getDouble(1), uuid.toString()))
                .thenCompose(bal -> {
                    double current = bal == null ? 0.0 : bal;
                    double newValue = Math.max(0.0, round2(current + d));
                    return plugin.database().update(
                                    "UPDATE bank_accounts SET balance = ? WHERE uuid = ?", newValue, uuid.toString())
                            .thenApply(rows -> {
                                balanceCache.put(uuid, newValue);
                                return newValue;
                            });
                });
    }

    /** Resets the stored balance of a player to zero. */
    public CompletableFuture<Double> adminClear(UUID uuid, String name) {
        return ensureAccount(uuid, name)
                .thenCompose(ignored -> plugin.database().update(
                        "UPDATE bank_accounts SET balance = 0 WHERE uuid = ?", uuid.toString()))
                .thenApply(rows -> {
                    balanceCache.put(uuid, 0.0);
                    return 0.0;
                });
    }

    // ------------------------------------------------------------------
    // interest
    // ------------------------------------------------------------------

    /** Starts the periodic async interest settlement task when enabled in config.yml. */
    public void startInterestTask() {
        var cfg = plugin.configManager();
        if (!cfg.getBoolean("modules.bank.interest.enabled", false)) {
            return;
        }
        double rate = cfg.getDouble("modules.bank.interest.rate", 0.01);
        int periodHours = cfg.getInt("modules.bank.interest.period-hours", 24);
        int checkMinutes = cfg.getInt("modules.bank.interest.check-minutes", 5);
        if (rate <= 0 || periodHours <= 0 || checkMinutes <= 0) {
            plugin.getLogger().warning("Bank interest task not started: invalid rate/period/check-minutes.");
            return;
        }
        long periodMs = periodHours * 3600_000L;
        long ticks = checkMinutes * 60L * 20L;
        interestTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            plugin.database().queryList(
                            "SELECT uuid, name, balance FROM bank_accounts WHERE last_interest + ? <= ? AND balance > 0",
                            rs -> new AccountRow(UUID.fromString(rs.getString("uuid")),
                                    rs.getString("name"), rs.getDouble("balance")),
                            periodMs, now)
                    .thenAccept(rows -> {
                        for (AccountRow row : rows) {
                            double interest = round2(row.balance() * rate);
                            if (interest <= 0) {
                                continue;
                            }
                            plugin.database().update(
                                            "UPDATE bank_accounts SET balance = balance + ?, last_interest = ? WHERE uuid = ?",
                                            interest, now, row.uuid().toString())
                                    .thenAcceptAsync(ignored -> {
                                        double newBalance = round2(row.balance() + interest);
                                        balanceCache.put(row.uuid(), newBalance);
                                        Player online = Bukkit.getPlayer(row.uuid());
                                        if (online != null) {
                                            plugin.messages().sendPlayer(online, "bank.interest-credited",
                                                    "amount", fmt(interest), "balance", fmt(newBalance));
                                        }
                                    }, main());
                        }
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().severe("Bank interest settlement failed: " + ex);
                        return null;
                    });
        }, ticks, ticks);
    }

    /** Cancels the scheduled tasks owned by this service. */
    public void shutdown() {
        if (interestTask != null) {
            interestTask.cancel();
            interestTask = null;
        }
    }
}
