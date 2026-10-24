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
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Bank loans with a simple 300-850 credit score. Loans are issued straight into
 * the bank account, repaid from the wallet, and auto-settled from the bank balance
 * on their due date. Missed payments escalate the interest rate and can default
 * the loan, all mirrored in the player's credit score.
 * Every database access is asynchronous; Vault money movement always runs on the main thread.
 */
public final class LoanService {

    private static final int RESULT_NONE = 0;
    private static final int RESULT_PAID = 1;
    private static final int RESULT_REPAID = 2;
    private static final int RESULT_MISSED = 3;
    private static final int RESULT_DEFAULTED = 4;

    private final CarbonCorePlugin plugin;
    /** Display / placeholder cache only; business logic treats the database as the source of truth. */
    private final Map<UUID, Integer> creditCache = new ConcurrentHashMap<>();
    private final Map<UUID, Double> debtCache = new ConcurrentHashMap<>();
    private BukkitTask loanTask;

    private record CreditRow(UUID uuid, int score) {
    }

    private record DebtRow(UUID uuid, double remaining, double rate, int missed) {
    }

    private record ApplyCheck(int score, int active) {
    }

    private record RepayResult(double paid, double debtAfter) {
    }

    public LoanService(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // config
    // ------------------------------------------------------------------

    public boolean enabled() {
        return plugin.configManager().getBoolean("modules.bank.loan.enabled", true);
    }

    private int initialScore() {
        return plugin.configManager().getInt("modules.bank.loan.initial-score", 600);
    }

    private int minScore() {
        return plugin.configManager().getInt("modules.bank.loan.min-score", 300);
    }

    private int maxScore() {
        return plugin.configManager().getInt("modules.bank.loan.max-score", 850);
    }

    private int onTimeGain() {
        return plugin.configManager().getInt("modules.bank.loan.on-time-gain", 10);
    }

    private int missPenalty() {
        return plugin.configManager().getInt("modules.bank.loan.miss-penalty", 20);
    }

    private int defaultPenalty() {
        return plugin.configManager().getInt("modules.bank.loan.default-penalty", 50);
    }

    private double minLimit() {
        return plugin.configManager().getDouble("modules.bank.loan.min-limit", 500.0);
    }

    private double maxLimit() {
        return plugin.configManager().getDouble("modules.bank.loan.max-limit", 50000.0);
    }

    private double minInterest() {
        return plugin.configManager().getDouble("modules.bank.loan.min-interest", 2.0);
    }

    private double maxInterest() {
        return plugin.configManager().getDouble("modules.bank.loan.max-interest", 15.0);
    }

    public int periodHours() {
        return plugin.configManager().getInt("modules.bank.loan.period-hours", 72);
    }

    public int maxActiveLoans() {
        return plugin.configManager().getInt("modules.bank.loan.max-active-loans", 3);
    }

    public int maxMissed() {
        return plugin.configManager().getInt("modules.bank.loan.max-missed", 3);
    }

    public double lateEscalation() {
        return plugin.configManager().getDouble("modules.bank.loan.late-escalation", 0.10);
    }

    private int checkMinutes() {
        return plugin.configManager().getInt("modules.bank.loan.check-minutes", 5);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static double round2(double value) {
        return BankService.round2(value);
    }

    private String fmt(double value) {
        return plugin.economy().format(value);
    }

    private Executor main() {
        return run -> Bukkit.getScheduler().runTask(plugin, run);
    }

    /** Credit line derived from the score, clamped to the configured band. */
    public double creditLimit(int score) {
        return round2(interpolate(score, minLimit(), maxLimit(), false));
    }

    /** Per-period interest percent derived from the score (higher score, lower rate). */
    public double creditInterest(int score) {
        return round2(interpolate(score, maxInterest(), minInterest(), true));
    }

    private double interpolate(int score, double lo, double hi, boolean inverted) {
        int min = minScore();
        int max = maxScore();
        if (max <= min) {
            return lo;
        }
        double clamped = Math.max(min, Math.min(max, score));
        double t = (clamped - min) / (max - min);
        return lo + t * (hi - lo);
    }

    // ------------------------------------------------------------------
    // cache
    // ------------------------------------------------------------------

    /** Loads every stored credit score and active loan debt into the memory cache (called once on enable). */
    public void loadCache() {
        plugin.database().queryList("SELECT uuid, score FROM player_credit",
                        rs -> new CreditRow(UUID.fromString(rs.getString("uuid")), rs.getInt("score")))
                .thenAcceptAsync(rows -> {
                    for (CreditRow row : rows) {
                        creditCache.put(row.uuid(), row.score());
                    }
                }, main());
        plugin.database().queryList(
                        "SELECT uuid, remaining, interest_rate, missed_payments FROM bank_loans WHERE status = 0",
                        rs -> new DebtRow(UUID.fromString(rs.getString("uuid")), rs.getDouble("remaining"),
                                rs.getDouble("interest_rate"), rs.getInt("missed_payments")))
                .thenAcceptAsync(rows -> {
                    Map<UUID, Double> sums = new HashMap<>();
                    double esc = lateEscalation();
                    for (DebtRow row : rows) {
                        double due = round2(row.remaining() * (1.0 + row.rate() * (1.0 + row.missed() * esc) / 100.0));
                        sums.merge(row.uuid(), due, Double::sum);
                    }
                    for (Map.Entry<UUID, Double> entry : sums.entrySet()) {
                        debtCache.put(entry.getKey(), round2(entry.getValue()));
                    }
                }, main());
    }

    public int cachedCreditScore(UUID uuid) {
        return creditCache.getOrDefault(uuid, initialScore());
    }

    public double cachedDebt(UUID uuid) {
        return debtCache.getOrDefault(uuid, 0.0);
    }

    /** Re-reads one credit score from the database into the cache. */
    public void refreshCredit(UUID uuid) {
        plugin.database().queryOne("SELECT score FROM player_credit WHERE uuid = ?",
                        rs -> rs.getInt(1), uuid.toString())
                .thenAcceptAsync(score -> {
                    if (score != null) {
                        creditCache.put(uuid, score);
                    }
                }, main());
    }

    /** Re-reads the active-loan debt of one player into the cache. */
    public void refreshDebt(UUID uuid) {
        double esc = lateEscalation();
        plugin.database().queryList(
                        "SELECT remaining, interest_rate, missed_payments FROM bank_loans WHERE uuid = ? AND status = 0",
                        rs -> new double[]{rs.getDouble("remaining"), rs.getDouble("interest_rate"),
                                rs.getInt("missed_payments")},
                        uuid.toString())
                .thenAcceptAsync(rows -> {
                    double sum = 0.0;
                    for (double[] row : rows) {
                        sum += round2(row[0] * (1.0 + row[1] * (1.0 + row[2] * esc) / 100.0));
                    }
                    debtCache.put(uuid, round2(sum));
                }, main());
    }

    // ------------------------------------------------------------------
    // credit row
    // ------------------------------------------------------------------

    public CompletableFuture<Void> ensureCredit(UUID uuid) {
        return plugin.database().update(
                        "INSERT OR IGNORE INTO player_credit(uuid,score,on_time,missed,defaults,updated_at) VALUES(?,?,0,0,0,?)",
                        uuid.toString(), initialScore(), System.currentTimeMillis())
                .thenApply(rows -> (Void) null);
    }

    // ------------------------------------------------------------------
    // apply
    // ------------------------------------------------------------------

    /**
     * Issues a loan straight into the bank account (the wallet is untouched).
     * Validation runs against the database; the loan row and the account credit
     * are written in one transaction.
     */
    public void apply(Player player, double amount, Runnable onDone) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        amount = round2(amount);
        if (!enabled()) {
            reject(player, "general.module-disabled", onDone);
            return;
        }
        if (amount < 1.0) {
            reject(player, "general.invalid-amount", amount, onDone);
            return;
        }
        final double amt = amount;
        ensureCredit(uuid)
                .thenCompose(ignored -> plugin.database().queryOne(
                        "SELECT score FROM player_credit WHERE uuid = ?", rs -> rs.getInt(1), uuid.toString()))
                .thenCompose(score -> plugin.database().queryOne(
                        "SELECT COUNT(*) FROM bank_loans WHERE uuid = ? AND status = 0",
                        rs -> rs.getInt(1), uuid.toString())
                        .thenApply(active -> new ApplyCheck(
                                score == null ? initialScore() : score,
                                active == null ? 0 : active)))
                .thenAcceptAsync(check -> {
                    double limit = creditLimit(check.score());
                    if (amt > limit) {
                        if (player.isOnline()) {
                            plugin.messages().sendPlayer(player, "bank.loan.over-limit", "limit", fmt(limit));
                        }
                        if (onDone != null) {
                            onDone.run();
                        }
                        return;
                    }
                    if (check.active() >= maxActiveLoans()) {
                        if (player.isOnline()) {
                            plugin.messages().sendPlayer(player, "bank.loan.too-many", "count", check.active());
                        }
                        if (onDone != null) {
                            onDone.run();
                        }
                        return;
                    }
                    insertLoan(player, uuid, name, amt, check.score(), onDone);
                }, main())
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Loan apply check failed for " + name + ": " + ex);
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

    private void insertLoan(Player player, UUID uuid, String name, double amt, int score, Runnable onDone) {
        long now = System.currentTimeMillis();
        long due = now + periodHours() * 3600_000L;
        double rate = creditInterest(score);
        plugin.database().transaction(c -> {
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT OR IGNORE INTO bank_accounts(uuid,name,balance,last_interest) VALUES(?,?,0,?)")) {
                ins.setString(1, uuid.toString());
                ins.setString(2, name);
                ins.setLong(3, now);
                ins.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bank_loans(uuid,name,principal,remaining,interest_rate,period_hours,"
                            + "issued_at,due_at,missed_payments,status) VALUES(?,?,?,?,?,?,?, ?,0,0)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setDouble(3, amt);
                ps.setDouble(4, amt);
                ps.setDouble(5, rate);
                ps.setInt(6, periodHours());
                ps.setLong(7, now);
                ps.setLong(8, due);
                ps.executeUpdate();
            }
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE bank_accounts SET balance = balance + ? WHERE uuid = ?")) {
                up.setDouble(1, amt);
                up.setString(2, uuid.toString());
                up.executeUpdate();
            }
            return Boolean.TRUE;
        }).thenCompose(ignored -> plugin.database().queryOne(
                "SELECT balance FROM bank_accounts WHERE uuid = ?", rs -> rs.getDouble(1), uuid.toString()))
                .thenAcceptAsync(bal -> {
                    creditCache.put(uuid, score);
                    refreshDebt(uuid);
                    plugin.bankModule().service().refreshCache(uuid);
                    if (player.isOnline()) {
                        plugin.messages().sendPlayer(player, "bank.loan.applied",
                                "amount", fmt(amt), "balance", fmt(bal == null ? 0.0 : round2(bal)));
                    }
                    if (onDone != null) {
                        onDone.run();
                    }
                }, main())
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Loan apply write failed for " + name + ": " + ex);
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

    // ------------------------------------------------------------------
    // repay
    // ------------------------------------------------------------------

    /** Repays from the wallet, offsetting active loans oldest-due first. */
    public void repay(Player player, double amount, Runnable onDone) {
        UUID uuid = player.getUniqueId();
        amount = round2(amount);
        if (!enabled()) {
            reject(player, "general.module-disabled", onDone);
            return;
        }
        if (amount <= 0) {
            reject(player, "general.invalid-amount", amount, onDone);
            return;
        }
        final double want = amount;
        summary(uuid).thenAcceptAsync(loanList -> {
            if (loanList.isEmpty()) {
                if (player.isOnline()) {
                    plugin.messages().sendPlayer(player, "bank.loan.no-active");
                }
                if (onDone != null) {
                    onDone.run();
                }
                return;
            }
            double esc = lateEscalation();
            double totalDue = 0.0;
            for (Loan loan : loanList) {
                totalDue += loan.dueAmount(esc);
            }
            totalDue = round2(totalDue);
            double pay = round2(Math.min(want, totalDue));
            if (pay <= 0) {
                if (player.isOnline()) {
                    plugin.messages().sendPlayer(player, "bank.loan.no-active");
                }
                if (onDone != null) {
                    onDone.run();
                }
                return;
            }
            if (!plugin.economy().withdraw(player, pay)) {
                if (player.isOnline()) {
                    plugin.messages().sendPlayer(player, "general.insufficient-funds", "amount", fmt(pay));
                }
                if (onDone != null) {
                    onDone.run();
                }
                return;
            }
            repayTransaction(uuid, pay).thenAcceptAsync(result -> {
                if (result == null) {
                    plugin.getLogger().severe("Loan repay transaction returned no result for " + player.getName());
                    refundAndFail(player, pay, onDone);
                    return;
                }
                debtCache.put(uuid, result.debtAfter());
                refreshCredit(uuid);
                if (player.isOnline()) {
                    plugin.messages().sendPlayer(player, "bank.loan.repaid",
                            "amount", fmt(result.paid()), "debt", fmt(result.debtAfter()));
                }
                if (onDone != null) {
                    onDone.run();
                }
            }, main()).exceptionally(ex -> {
                plugin.getLogger().severe("Loan repay failed for " + player.getName() + ": " + ex);
                refundAndFail(player, pay, onDone);
                return null;
            });
        }, main()).exceptionally(ex -> {
            plugin.getLogger().severe("Loan repay summary failed for " + player.getName() + ": " + ex);
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

    /** Repays the whole cached debt (used by the "all" actions). */
    public void repayAll(Player player, Runnable onDone) {
        double debt = cachedDebt(player.getUniqueId());
        if (debt <= 0) {
            if (player.isOnline()) {
                plugin.messages().sendPlayer(player, "bank.loan.no-active");
            }
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        repay(player, debt, onDone);
    }

    private void refundAndFail(Player player, double pay, Runnable onDone) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.economy().deposit(player, pay);
            if (player.isOnline()) {
                plugin.messages().sendPlayer(player, "general.economy-missing");
            }
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    /** Offsets the payment over active loans ordered by due date; returns paid amount and remaining debt. */
    private CompletableFuture<RepayResult> repayTransaction(UUID uuid, double pay) {
        long now = System.currentTimeMillis();
        double esc = lateEscalation();
        int gain = onTimeGain();
        int max = maxScore();
        return plugin.database().transaction(c -> {
            List<Loan> loans = new ArrayList<>();
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT * FROM bank_loans WHERE uuid = ? AND status = 0 ORDER BY due_at ASC")) {
                sel.setString(1, uuid.toString());
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) {
                        loans.add(Loan.fromResultSet(rs));
                    }
                }
            }
            double payLeft = pay;
            int completed = 0;
            for (Loan loan : loans) {
                if (payLeft <= 0.005) {
                    break;
                }
                double due = loan.dueAmount(esc);
                double take = Math.min(payLeft, due);
                double newRemaining = round2(loan.remaining() - take);
                if (newRemaining <= 0.005) {
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE bank_loans SET remaining = 0, status = 1, last_paid_at = ? WHERE id = ? AND status = 0")) {
                        up.setLong(1, now);
                        up.setInt(2, loan.id());
                        up.executeUpdate();
                    }
                    completed++;
                } else {
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE bank_loans SET remaining = ?, last_paid_at = ? WHERE id = ? AND status = 0")) {
                        up.setDouble(1, newRemaining);
                        up.setLong(2, now);
                        up.setInt(3, loan.id());
                        up.executeUpdate();
                    }
                }
                payLeft = round2(payLeft - take);
            }
            if (completed > 0) {
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE player_credit SET score = MIN(?, score + ?), on_time = on_time + ?, updated_at = ? WHERE uuid = ?")) {
                    up.setInt(1, max);
                    up.setInt(2, gain * completed);
                    up.setInt(3, completed);
                    up.setLong(4, now);
                    up.setString(5, uuid.toString());
                    up.executeUpdate();
                }
            }
            double debtAfter = 0.0;
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT remaining, interest_rate, missed_payments FROM bank_loans WHERE uuid = ? AND status = 0")) {
                sel.setString(1, uuid.toString());
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) {
                        double remaining = rs.getDouble("remaining");
                        double rate = rs.getDouble("interest_rate");
                        int missed = rs.getInt("missed_payments");
                        debtAfter += round2(remaining * (1.0 + rate * (1.0 + missed * esc) / 100.0));
                    }
                }
            }
            return new RepayResult(round2(pay - payLeft), round2(debtAfter));
        });
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    /** Active loans of a player, oldest-due first. */
    public CompletableFuture<List<Loan>> summary(UUID uuid) {
        return plugin.database().queryList(
                "SELECT * FROM bank_loans WHERE uuid = ? AND status = 0 ORDER BY due_at ASC",
                Loan::fromResultSet, uuid.toString());
    }

    /** Recent loans of any status, newest first. */
    public CompletableFuture<List<Loan>> history(UUID uuid, int limit) {
        return plugin.database().queryList(
                "SELECT * FROM bank_loans WHERE uuid = ? ORDER BY issued_at DESC LIMIT ?",
                Loan::fromResultSet, uuid.toString(), limit);
    }

    // ------------------------------------------------------------------
    // scheduled auto-settlement
    // ------------------------------------------------------------------

    /** Starts the periodic async due-loan settlement task when enabled in config.yml. */
    public void startTask() {
        if (!enabled()) {
            return;
        }
        int minutes = checkMinutes();
        if (minutes <= 0 || periodHours() <= 0) {
            plugin.getLogger().warning("Loan task not started: invalid check-minutes/period-hours.");
            return;
        }
        long periodMs = periodHours() * 3600_000L;
        long ticks = minutes * 60L * 20L;
        loanTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            plugin.database().queryList(
                            "SELECT * FROM bank_loans WHERE status = 0 AND due_at <= ? ORDER BY due_at ASC",
                            Loan::fromResultSet, now)
                    .thenAccept(loanList -> {
                        for (Loan loan : loanList) {
                            settle(loan, now, periodMs);
                        }
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().severe("Loan due scan failed: " + ex);
                        return null;
                    });
        }, ticks, ticks);
    }

    /**
     * Tries to auto-debit one due loan from the bank balance. On success the loan is
     * rolled forward (and repaid when the balance reaches zero); on failure the missed
     * counter, the escalated rate and the credit score move on (DZTradeHub style).
     */
    private void settle(Loan loan, long now, long periodMs) {
        UUID uuid = loan.uuid();
        double due = loan.dueAmount(lateEscalation());
        int newMissed = loan.missedPayments() + 1;
        boolean defaulted = newMissed >= maxMissed();
        plugin.database().transaction(c -> {
            try (PreparedStatement debit = c.prepareStatement(
                    "UPDATE bank_accounts SET balance = balance - ? WHERE uuid = ? AND balance >= ?")) {
                debit.setDouble(1, due);
                debit.setString(2, uuid.toString());
                debit.setDouble(3, due);
                if (debit.executeUpdate() == 1) {
                    double newRemaining = round2(loan.remaining() - due);
                    if (newRemaining <= 0.005) {
                        try (PreparedStatement up = c.prepareStatement(
                                "UPDATE bank_loans SET remaining = 0, status = 1, due_at = ?, last_paid_at = ? WHERE id = ? AND status = 0")) {
                            up.setLong(1, now + periodMs);
                            up.setLong(2, now);
                            up.setInt(3, loan.id());
                            up.executeUpdate();
                        }
                        ensureCreditRow(c, uuid, loan.name(), now);
                        try (PreparedStatement up = c.prepareStatement(
                                "UPDATE player_credit SET score = MIN(?, score + ?), on_time = on_time + 1, updated_at = ? WHERE uuid = ?")) {
                            up.setInt(1, maxScore());
                            up.setInt(2, onTimeGain());
                            up.setLong(3, now);
                            up.setString(4, uuid.toString());
                            up.executeUpdate();
                        }
                        return RESULT_REPAID;
                    }
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE bank_loans SET remaining = ?, due_at = ?, last_paid_at = ? WHERE id = ? AND status = 0")) {
                        up.setDouble(1, newRemaining);
                        up.setLong(2, now + periodMs);
                        up.setLong(3, now);
                        up.setInt(4, loan.id());
                        up.executeUpdate();
                    }
                    return RESULT_PAID;
                }
            }
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE bank_loans SET missed_payments = missed_payments + 1, due_at = ? WHERE id = ? AND status = 0")) {
                up.setLong(1, now + periodMs);
                up.setInt(2, loan.id());
                if (up.executeUpdate() != 1) {
                    return RESULT_NONE;
                }
            }
            if (defaulted) {
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE bank_loans SET status = 2 WHERE id = ? AND status = 0")) {
                    up.setInt(1, loan.id());
                    up.executeUpdate();
                }
            }
            ensureCreditRow(c, uuid, loan.name(), now);
            int penalty = missPenalty() + (defaulted ? defaultPenalty() : 0);
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE player_credit SET score = MAX(?, score - ?), missed = missed + 1, defaults = defaults + ?, updated_at = ? WHERE uuid = ?")) {
                up.setInt(1, minScore());
                up.setInt(2, penalty);
                up.setInt(3, defaulted ? 1 : 0);
                up.setLong(4, now);
                up.setString(5, uuid.toString());
                up.executeUpdate();
            }
            return defaulted ? RESULT_DEFAULTED : RESULT_MISSED;
        }).thenAcceptAsync(result -> {
            if (result == null || result == RESULT_NONE) {
                return;
            }
            plugin.bankModule().service().refreshCache(uuid);
            refreshCredit(uuid);
            refreshDebt(uuid);
            if (result != RESULT_MISSED && result != RESULT_DEFAULTED) {
                return;
            }
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                plugin.messages().sendPlayer(online, "bank.loan.missed",
                        "id", loan.id(), "penalty", missPenalty());
                if (result == RESULT_DEFAULTED) {
                    plugin.messages().sendPlayer(online, "bank.loan.defaulted", "id", loan.id());
                }
            }
        }, main()).exceptionally(ex -> {
            plugin.getLogger().severe("Loan settlement failed for loan #" + loan.id() + ": " + ex);
            return null;
        });
    }

    private void ensureCreditRow(java.sql.Connection c, UUID uuid, String name, long now) throws java.sql.SQLException {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT OR IGNORE INTO player_credit(uuid,score,on_time,missed,defaults,updated_at) VALUES(?,?,0,0,0,?)")) {
            ins.setString(1, uuid.toString());
            ins.setInt(2, initialScore());
            ins.setLong(3, now);
            ins.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void reject(Player player, String key, Runnable onDone) {
        if (player.isOnline()) {
            plugin.messages().sendPlayer(player, key);
        }
        if (onDone != null) {
            onDone.run();
        }
    }

    private void reject(Player player, String key, double amount, Runnable onDone) {
        if (player.isOnline()) {
            plugin.messages().sendPlayer(player, key, "input", fmt(amount));
        }
        if (onDone != null) {
            onDone.run();
        }
    }

    /** Cancels the scheduled tasks owned by this service. */
    public void shutdown() {
        if (loanTask != null) {
            loanTask.cancel();
            loanTask = null;
        }
    }
}
