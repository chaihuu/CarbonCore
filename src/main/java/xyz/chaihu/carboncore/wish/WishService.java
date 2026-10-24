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
package xyz.chaihu.carboncore.wish;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.util.ItemBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Core wish (gacha) logic: weighted draws with an optional pity guarantee,
 * reward granting, history persistence and an in-memory pity cache.
 *
 * Threading: {@link #draw(Player, int)} must be called from the main thread.
 * All SQL runs on the shared DatabaseManager executor; results are handed back
 * to the main thread with {@code thenAcceptAsync(..., run -> Bukkit.getScheduler().runTask(plugin, run))}.
 */
public final class WishService {

    /** One player's pity state as kept in the in-memory cache. */
    private record PityState(int sinceRare, int totalPulls) {
    }

    /** Outcome of one draw batch, computed atomically inside a DB transaction. */
    private record DrawOutcome(List<WishEntry> results, int sinceRare, int totalPulls) {
    }

    /** A single stored wish history row. */
    public static final class HistoryRow {
        private final long time;
        private final String entryName;
        private final String resultType;

        public HistoryRow(long time, String entryName, String resultType) {
            this.time = time;
            this.entryName = entryName;
            this.resultType = resultType;
        }

        /** Epoch millis of the pull. */
        public long time() {
            return time;
        }

        /** Entry display name (contains & color codes). */
        public String entryName() {
            return entryName;
        }

        /** Reward type: item / money / command. */
        public String resultType() {
            return resultType;
        }
    }

    private final CarbonCorePlugin plugin;
    private final List<WishEntry> pool;
    private final List<WishEntry> rarePool;
    private final double singleCost;
    private final double tenCost;
    private final boolean pityEnabled;
    private final int pityThreshold;
    private final int historySize;

    private final Map<UUID, PityState> cache = new ConcurrentHashMap<>();

    public WishService(CarbonCorePlugin plugin, List<WishEntry> pool) {
        this.plugin = plugin;
        this.pool = List.copyOf(pool);
        List<WishEntry> rare = new ArrayList<>();
        for (WishEntry e : pool) {
            if (e.rare()) {
                rare.add(e);
            }
        }
        this.rarePool = List.copyOf(rare);
        var cfg = plugin.configManager();
        this.singleCost = cfg.getDouble("modules.wish.single-cost", 100.0);
        this.tenCost = cfg.getDouble("modules.wish.ten-cost", 900.0);
        this.pityEnabled = cfg.getBoolean("modules.wish.pity.enabled", true);
        this.pityThreshold = Math.max(1, cfg.getInt("modules.wish.pity.threshold", 50));
        this.historySize = Math.max(1, cfg.getInt("modules.wish.history-size", 200));
    }

    /** Loads all pity rows into the memory cache (runs on the DB thread). */
    public void loadPityCache() {
        plugin.database().queryList(
                "SELECT uuid, since_rare, total_pulls FROM wish_pity",
                rs -> new PityRow(UUID.fromString(rs.getString("uuid")),
                        Math.max(0, rs.getInt("since_rare")),
                        Math.max(0, rs.getInt("total_pulls"))))
                .thenAccept(rows -> {
                    for (PityRow row : rows) {
                        cache.put(row.uuid(), new PityState(row.sinceRare(), row.totalPulls()));
                    }
                    plugin.getLogger().info("Wish pity cache loaded: " + rows.size() + " players.");
                })
                .exceptionally(err -> {
                    plugin.getLogger().severe("Failed to load the wish pity cache: " + err.getMessage());
                    return null;
                });
    }

    /** Internal row shape used only by the cache loader mapper. */
    private record PityRow(UUID uuid, int sinceRare, int totalPulls) {
    }

    public List<WishEntry> pool() {
        return pool;
    }

    public double singleCost() {
        return singleCost;
    }

    public double tenCost() {
        return tenCost;
    }

    public boolean pityEnabled() {
        return pityEnabled;
    }

    public int pityThreshold() {
        return pityThreshold;
    }

    /** Max history rows kept per player (modules.wish.history-size). */
    public int historySize() {
        return historySize;
    }

    public int cachedTotalPulls(UUID uuid) {
        PityState s = cache.get(uuid);
        return s == null ? 0 : s.totalPulls();
    }

    public int cachedSinceRare(UUID uuid) {
        PityState s = cache.get(uuid);
        return s == null ? 0 : s.sinceRare();
    }

    /** Contract entry point: draws without a GUI callback. */
    public void draw(Player player, int times) {
        draw(player, times, null);
    }

    /**
     * Draws {@code times} (1 or 10) pulls after charging the wallet. Must run on
     * the main thread. When {@code onResult} is non-null it receives the drawn
     * entries on the main thread after the rewards were granted.
     */
    public void draw(Player player, int times, Consumer<List<WishEntry>> onResult) {
        UUID uuid = player.getUniqueId();
        int cooldown = plugin.configManager().moduleCooldown("wish");
        if (!plugin.cooldowns().tryUse(uuid, "wish:draw", cooldown)) {
            plugin.messages().sendPlayer(player, "general.cooldown",
                    "seconds", plugin.cooldowns().remaining(uuid, "wish:draw", cooldown));
            return;
        }
        if (pool.isEmpty()) {
            plugin.getLogger().warning("Wish pool is empty; ignoring draw for " + player.getName());
            return;
        }
        double cost = times >= 10 ? tenCost : singleCost;
        if (!plugin.economy().withdraw(player, cost)) {
            plugin.messages().sendPlayer(player, "wish.no-money", "cost", plugin.economy().format(cost));
            return;
        }
        final int pullCount = Math.max(1, Math.min(10, times));
        final double charged = cost;
        plugin.database().transaction(c -> runDraw(c, uuid, pullCount))
                .whenCompleteAsync((outcome, err) -> {
                    if (err != null) {
                        plugin.getLogger().severe("Wish draw failed for " + player.getName()
                                + ", refunded " + charged + ": " + err.getMessage());
                        plugin.economy().deposit(player, charged);
                        return;
                    }
                    applyOutcome(player, outcome, onResult);
                }, run -> Bukkit.getScheduler().runTask(plugin, run));
    }

    /** Runs on the DB thread inside one transaction: pity read, draws, history, pity upsert. */
    private DrawOutcome runDraw(Connection c, UUID uuid, int times) throws SQLException {
        int sinceRare = 0;
        int totalPulls = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT since_rare, total_pulls FROM wish_pity WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sinceRare = Math.max(0, rs.getInt("since_rare"));
                    totalPulls = Math.max(0, rs.getInt("total_pulls"));
                }
            }
        }

        List<WishEntry> results = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            boolean forceRare = pityEnabled && sinceRare + 1 >= pityThreshold && !rarePool.isEmpty();
            WishEntry picked = forceRare ? pickWeighted(rarePool) : pickWeighted(pool);
            results.add(picked);
            if (picked.rare()) {
                sinceRare = 0;
            } else {
                sinceRare++;
            }
        }

        long now = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wish_history (uuid, pull_time, entry_id, entry_name, result_type) VALUES (?,?,?,?,?)")) {
            for (WishEntry e : results) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, now);
                ps.setString(3, e.id());
                ps.setString(4, e.name());
                ps.setString(5, e.rewardType().name().toLowerCase(Locale.ROOT));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM wish_history WHERE uuid = ? AND id NOT IN "
                        + "(SELECT id FROM wish_history WHERE uuid = ? ORDER BY id DESC LIMIT ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            ps.setInt(3, historySize);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wish_pity (uuid, since_rare, total_pulls) VALUES (?,?,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET since_rare = ?, total_pulls = total_pulls + ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, sinceRare);
            ps.setInt(3, times);
            ps.setInt(4, sinceRare);
            ps.setInt(5, times);
            ps.executeUpdate();
        }

        return new DrawOutcome(results, sinceRare, totalPulls + times);
    }

    /** Cumulative-weight random pick. */
    private static WishEntry pickWeighted(List<WishEntry> list) {
        int total = 0;
        for (WishEntry e : list) {
            total += e.weight();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        for (WishEntry e : list) {
            acc += e.weight();
            if (roll < acc) {
                return e;
            }
        }
        return list.get(list.size() - 1);
    }

    /** Main thread: grant rewards, announce the result, refresh the cache. */
    private void applyOutcome(Player player, DrawOutcome outcome, Consumer<List<WishEntry>> onResult) {
        for (WishEntry e : outcome.results()) {
            grant(player, e);
        }
        plugin.messages().sendPlayer(player, "wish.drawn", "result", summarize(outcome.results()));
        cache.put(player.getUniqueId(), new PityState(outcome.sinceRare(), outcome.totalPulls()));
        if (onResult != null) {
            onResult.accept(outcome.results());
        }
    }

    private void grant(Player player, WishEntry entry) {
        switch (entry.rewardType()) {
            case ITEM -> {
                List<ItemStack> stacks = new ArrayList<>();
                for (WishEntry.ItemReward ir : entry.items()) {
                    int remaining = ir.amount();
                    while (remaining > 0) {
                        int n = Math.min(64, remaining);
                        stacks.add(new ItemBuilder(ir.material()).amount(n).build());
                        remaining -= n;
                    }
                }
                Map<Integer, ItemStack> left =
                        player.getInventory().addItem(stacks.toArray(new ItemStack[0]));
                if (!left.isEmpty()) {
                    for (ItemStack s : left.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), s);
                    }
                    plugin.messages().sendPlayer(player, "general.item-could-not-fit");
                }
                for (WishEntry.ItemReward ir : entry.items()) {
                    plugin.messages().sendPlayer(player, "wish.item-received",
                            "name", ir.material().name(), "amount", ir.amount());
                }
            }
            case MONEY -> {
                plugin.economy().deposit(player, entry.money());
                plugin.messages().sendPlayer(player, "wish.money-received",
                        "amount", plugin.economy().format(entry.money()));
            }
            case COMMAND -> {
                for (String cmd : entry.commands()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            cmd.replace("{player}", player.getName()));
                }
                plugin.messages().sendPlayer(player, "wish.command-received");
            }
        }
    }

    /** Single draw shows the name; ten draws show a numbered multi-line list. */
    public static String summarize(List<WishEntry> results) {
        if (results.size() == 1) {
            return results.get(0).display();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(i + 1).append(". ").append(results.get(i).display());
        }
        return sb.toString();
    }

    /** Most recent history rows, newest first. */
    public CompletableFuture<List<HistoryRow>> history(UUID uuid, int limit) {
        return plugin.database().queryList(
                "SELECT pull_time, entry_name, result_type FROM wish_history WHERE uuid = ? ORDER BY id DESC LIMIT ?",
                rs -> new HistoryRow(rs.getLong("pull_time"),
                        rs.getString("entry_name"),
                        rs.getString("result_type")),
                uuid.toString(), Math.max(1, limit));
    }

    /** Formats an epoch-millis timestamp as yyyy-MM-dd HH:mm:ss. */
    public static String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(millis));
    }
}
