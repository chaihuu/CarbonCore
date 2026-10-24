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
package xyz.chaihu.carboncore.redpacket;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.config.Messages;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Core red packet logic: creation (wallet withdraw), claiming (authoritative DB
 * transaction with random shares), expiry refund and startup restore.
 *
 * Threading rules: money movement and player messaging happen on the main thread;
 * every SQL statement runs on the single-threaded DB executor via {@code plugin.database()}.
 */
public final class RedPacketService {

    /** Hard cap for packet validity: 7 days. */
    public static final long MAX_DURATION_SECONDS = 7L * 24 * 60 * 60;

    private final CarbonCorePlugin plugin;
    /** Open (status 0) packets kept in memory; removed on refund / full claim. */
    private final Map<Integer, RedPacket> active = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public RedPacketService(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // configuration
    // ------------------------------------------------------------------

    public double minTotal() {
        return plugin.configManager().getDouble("modules.redpacket.min-total", 1.0);
    }

    public double maxTotal() {
        return plugin.configManager().getDouble("modules.redpacket.max-total", 100000.0);
    }

    public int minCount() {
        return plugin.configManager().getInt("modules.redpacket.min-count", 1);
    }

    public int maxCount() {
        return plugin.configManager().getInt("modules.redpacket.max-count", 100);
    }

    public double minShare() {
        return plugin.configManager().getDouble("modules.redpacket.min-share", 0.01);
    }

    public int maxOpenPackets() {
        return plugin.configManager().getInt("modules.redpacket.max-open-packets", 50);
    }

    public int expireCheckSeconds() {
        return plugin.configManager().getInt("modules.redpacket.expire-check-seconds", 10);
    }

    public long expireCheckTicks() {
        return Math.max(1, expireCheckSeconds()) * 20L;
    }

    public List<Integer> durations() {
        List<Integer> d = plugin.configManager().getIntegerList("modules.redpacket.durations-seconds");
        return d.isEmpty() ? List.of(60, 300, 600, 3600, 86400) : d;
    }

    public long defaultDuration() {
        return plugin.configManager().getInt("modules.redpacket.default-duration-seconds", 86400);
    }

    // ------------------------------------------------------------------
    // in-memory views
    // ------------------------------------------------------------------

    public RedPacket activePacket(int id) {
        return active.get(id);
    }

    /** Claimable count for placeholders: non-code packets the viewer has not claimed yet. */
    public int claimableCount(UUID viewer) {
        int n = 0;
        for (RedPacket rp : active.values()) {
            if (rp.needsCode() || !rp.isOpen()) {
                continue;
            }
            if (!rp.claims().contains(viewer)) {
                n++;
            }
        }
        return n;
    }

    /** All open packets the viewer may still claim (code packets included, sorted by id). */
    public List<RedPacket> claimableFor(UUID viewer) {
        List<RedPacket> out = new ArrayList<>();
        for (RedPacket rp : active.values()) {
            if (!rp.isOpen()) {
                continue;
            }
            if (!rp.claims().contains(viewer)) {
                out.add(rp);
            }
        }
        out.sort(Comparator.comparingInt(RedPacket::id));
        return out;
    }

    /** Open packets sent by the given player, sorted by id. */
    public List<RedPacket> packetsBy(UUID sender) {
        List<RedPacket> out = new ArrayList<>();
        for (RedPacket rp : active.values()) {
            if (rp.senderUuid().equals(sender) && rp.status() == 0) {
                out.add(rp);
            }
        }
        out.sort(Comparator.comparingInt(RedPacket::id));
        return out;
    }

    /** Active packet ids as strings (tab completion). */
    public List<String> activeIds() {
        List<String> ids = new ArrayList<>();
        for (Integer id : active.keySet()) {
            ids.add(String.valueOf(id));
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    // ------------------------------------------------------------------
    // startup restore
    // ------------------------------------------------------------------

    /**
     * Loads status=0 rows plus their claim sets. Packets that expired while the
     * server was down are refunded immediately through the same CAS path.
     */
    public CompletableFuture<Void> restoreAsync() {
        return plugin.database()
                .queryList("SELECT * FROM redpackets WHERE status = 0", RedPacket::fromResultSet)
                .thenCombine(
                        plugin.database().queryList(
                                "SELECT packet_id, player_uuid FROM redpacket_claims",
                                rs -> new ClaimRef(rs.getInt("packet_id"),
                                        UUID.fromString(rs.getString("player_uuid")))),
                        (packets, refs) -> {
                            Map<Integer, RedPacket> byId = new HashMap<>();
                            for (RedPacket rp : packets) {
                                byId.put(rp.id(), rp);
                            }
                            for (ClaimRef ref : refs) {
                                RedPacket rp = byId.get(ref.packetId());
                                if (rp != null) {
                                    rp.claims().add(ref.player());
                                }
                            }
                            return packets;
                        })
                .thenAcceptAsync(packets -> {
                    long now = System.currentTimeMillis();
                    int restored = 0;
                    for (RedPacket rp : packets) {
                        if (rp.expireAt() <= now) {
                            refundExpired(rp);
                        } else {
                            active.put(rp.id(), rp);
                            restored++;
                        }
                    }
                    plugin.getLogger().info("RedPacket: restored " + restored
                            + " open packet(s) from the database.");
                }, main())
                .exceptionally(t -> {
                    plugin.getLogger().severe("RedPacket restore failed: " + t.getMessage());
                    return null;
                });
    }

    // ------------------------------------------------------------------
    // expiry scan / refund
    // ------------------------------------------------------------------

    /** Runs on the async scheduler: finds expired open packets and refunds them. */
    public void scanExpired() {
        long now = System.currentTimeMillis();
        plugin.database()
                .queryList("SELECT * FROM redpackets WHERE status = 0 AND expire_at <= ?",
                        RedPacket::fromResultSet, now)
                .thenAccept(list -> {
                    for (RedPacket rp : list) {
                        refundExpired(rp);
                    }
                })
                .exceptionally(t -> {
                    plugin.getLogger().warning("RedPacket expiry scan failed: " + t.getMessage());
                    return null;
                });
    }

    /**
     * Marks the packet closed with a CAS update; only the winner (rows == 1)
     * deposits the leftover money back to the sender, so no double refunds.
     */
    private void refundExpired(RedPacket rp) {
        active.remove(rp.id());
        double amount = rp.remainingAmount();
        plugin.database().update("UPDATE redpackets SET status = 1 WHERE id = ? AND status = 0", rp.id())
                .thenAccept(rows -> {
                    if (rows == null || rows != 1 || amount <= 0) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        OfflinePlayer sender = Bukkit.getOfflinePlayer(rp.senderUuid());
                        plugin.economy().deposit(sender, amount);
                        Player online = Bukkit.getPlayer(rp.senderUuid());
                        if (online != null) {
                            plugin.messages().sendPlayer(online, "redpacket.refunded",
                                    "amount", fmt(amount));
                        }
                    });
                })
                .exceptionally(t -> {
                    plugin.getLogger().severe("RedPacket refund for packet #" + rp.id()
                            + " failed: " + t.getMessage());
                    return null;
                });
    }

    /** Admin force-expire: closes the packet now and refunds the leftover amount. */
    public void forceExpire(int packetId, CommandSender admin) {
        Messages m = plugin.messages();
        RedPacket rp = active.get(packetId);
        if (rp != null) {
            refundExpired(rp);
            m.send(admin, "general.success");
            return;
        }
        plugin.database().queryOne("SELECT * FROM redpackets WHERE id = ?",
                        RedPacket::fromResultSet, packetId)
                .thenAcceptAsync(row -> {
                    if (row == null || row.status() != 0) {
                        m.send(admin, "redpacket.list-empty");
                        return;
                    }
                    refundExpired(row);
                    m.send(admin, "general.success");
                }, main())
                .exceptionally(t -> {
                    plugin.getLogger().warning("RedPacket lookup #" + packetId + " failed: " + t.getMessage());
                    return null;
                });
    }

    // ------------------------------------------------------------------
    // creation
    // ------------------------------------------------------------------

    /**
     * Validates on the main thread, withdraws from the wallet, then inserts the
     * packet row. The new packet lands in {@code active} only after the insert
     * succeeded; a failed insert refunds the withdrawn money.
     */
    public void create(Player sender, double total, int count, long seconds, String message, String code) {
        Messages m = plugin.messages();
        double minTotal = minTotal();
        double maxTotal = maxTotal();
        if (Double.isNaN(total) || Double.isInfinite(total) || total < minTotal) {
            m.sendPlayer(sender, "general.amount-too-low", "min", fmt(minTotal));
            return;
        }
        if (total > maxTotal) {
            m.sendPlayer(sender, "general.amount-too-high", "max", fmt(maxTotal));
            return;
        }
        if (count < minCount()) {
            m.sendPlayer(sender, "general.count-too-low", "min", minCount());
            return;
        }
        if (count > maxCount()) {
            m.sendPlayer(sender, "general.count-too-high", "max", maxCount());
            return;
        }
        if (seconds <= 0 || seconds > MAX_DURATION_SECONDS) {
            m.sendPlayer(sender, "general.usage", "usage",
                    "/redpacket create <amount> <count> [seconds] [message...]");
            return;
        }
        double need = round2(minShare() * count);
        if (total + 1e-9 < need) {
            m.sendPlayer(sender, "general.amount-too-low", "min", fmt(need));
            return;
        }
        long open = active.values().stream()
                .filter(rp -> rp.senderUuid().equals(sender.getUniqueId()))
                .count();
        if (open >= maxOpenPackets()) {
            m.sendPlayer(sender, "redpacket.too-many-open");
            return;
        }
        if (!plugin.economy().has(sender, total) || !plugin.economy().withdraw(sender, total)) {
            m.sendPlayer(sender, "general.insufficient-funds", "amount", fmt(total));
            return;
        }

        final String msg = (message == null || message.trim().isEmpty()) ? null : message.trim();
        final String cd = (code == null || code.trim().isEmpty()) ? null : code.trim();
        final long now = System.currentTimeMillis();
        final long expireAt = now + seconds * 1000L;
        final UUID uuid = sender.getUniqueId();
        final String name = sender.getName();

        CompletableFuture<Integer> insert = plugin.database().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO redpackets (sender_uuid, sender_name, total_amount, total_count,"
                            + " remaining_amount, remaining_count, message, code, created_at, expire_at, status)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setDouble(3, total);
                ps.setInt(4, count);
                ps.setDouble(5, total);
                ps.setInt(6, count);
                ps.setString(7, msg);
                ps.setString(8, cd);
                ps.setLong(9, now);
                ps.setLong(10, expireAt);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) {
                        throw new SQLException("No generated red packet id");
                    }
                    return rs.getInt(1);
                }
            }
        });
        insert.thenAcceptAsync(id -> {
            RedPacket rp = new RedPacket(id, uuid, name, total, count, total, count,
                    msg, cd, now, expireAt, 0);
            active.put(id, rp);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                m.sendPlayer(p, "redpacket.created",
                        "amount", fmt(total), "count", count, "expire", seconds);
            }
        }, main());
        insert.exceptionally(t -> {
            plugin.getLogger().severe("RedPacket insert failed for " + name + ": " + t.getMessage());
            // money was already withdrawn; hand it back on the main thread
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.economy().deposit(Bukkit.getOfflinePlayer(uuid), total));
            return null;
        });
    }

    // ------------------------------------------------------------------
    // claiming
    // ------------------------------------------------------------------

    /**
     * Claims a packet for {@code claimer}. Fast checks run on the main thread
     * against the in-memory copy; the authoritative check + payout bookkeeping
     * runs inside one DB transaction (the single-threaded pool serialises it).
     */
    public void claim(Player claimer, int packetId, String inputCode) {
        Messages m = plugin.messages();
        RedPacket rp = active.get(packetId);
        if (rp == null) {
            // Not in memory: may have just expired/been refunded, or was created
            // concurrently. Consult the DB, then let the transaction decide.
            plugin.database().queryOne("SELECT * FROM redpackets WHERE id = ?",
                            RedPacket::fromResultSet, packetId)
                    .thenAcceptAsync(row -> {
                        if (row == null) {
                            m.sendPlayer(claimer, "redpacket.list-empty");
                            return;
                        }
                        if (row.status() != 0 || row.remainingCount() <= 0) {
                            m.sendPlayer(claimer, "redpacket.claim-empty");
                            return;
                        }
                        if (row.expireAt() <= System.currentTimeMillis()) {
                            m.sendPlayer(claimer, "redpacket.claim-expired");
                            return;
                        }
                        if (row.needsCode()) {
                            if (inputCode == null || inputCode.trim().isEmpty()) {
                                m.sendPlayer(claimer, "redpacket.claim-need-code");
                                return;
                            }
                            if (!row.code().trim().equals(inputCode.trim())) {
                                m.sendPlayer(claimer, "redpacket.claim-wrong-code");
                                return;
                            }
                        }
                        runClaim(claimer, packetId, inputCode);
                    }, main())
                    .exceptionally(t -> {
                        plugin.getLogger().warning("RedPacket lookup #" + packetId
                                + " failed: " + t.getMessage());
                        return null;
                    });
            return;
        }
        if (rp.needsCode()) {
            if (inputCode == null || inputCode.trim().isEmpty()) {
                m.sendPlayer(claimer, "redpacket.claim-need-code");
                return;
            }
            if (!rp.code().trim().equals(inputCode.trim())) {
                m.sendPlayer(claimer, "redpacket.claim-wrong-code");
                return;
            }
        }
        if (rp.claims().contains(claimer.getUniqueId())) {
            m.sendPlayer(claimer, "redpacket.claim-duplicate");
            return;
        }
        if (rp.status() != 0 || rp.remainingCount() <= 0) {
            m.sendPlayer(claimer, "redpacket.claim-empty");
            return;
        }
        if (rp.expireAt() <= System.currentTimeMillis()) {
            m.sendPlayer(claimer, "redpacket.claim-expired");
            refundExpired(rp);
            return;
        }
        runClaim(claimer, packetId, inputCode);
    }

    private void runClaim(Player claimer, int packetId, String inputCode) {
        Messages m = plugin.messages();
        final UUID uuid = claimer.getUniqueId();
        final String claimerName = claimer.getName();
        final String trimmed = inputCode == null ? null : inputCode.trim();

        plugin.database().transaction(c -> {
            double remainingAmount;
            int remainingCount;
            int status;
            long expireAt;
            String code;
            String senderName;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT remaining_amount, remaining_count, status, expire_at, code, sender_name"
                            + " FROM redpackets WHERE id = ?")) {
                ps.setInt(1, packetId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new ClaimOutcome(ClaimStatus.EMPTY, 0, 0, 0, null);
                    }
                    remainingAmount = rs.getDouble(1);
                    remainingCount = rs.getInt(2);
                    status = rs.getInt(3);
                    expireAt = rs.getLong(4);
                    code = rs.getString(5);
                    senderName = rs.getString(6);
                }
            }
            if (status != 0 || remainingCount <= 0) {
                return new ClaimOutcome(ClaimStatus.EMPTY, 0, 0, 0, senderName);
            }
            if (expireAt <= System.currentTimeMillis()) {
                return new ClaimOutcome(ClaimStatus.EXPIRED, 0, 0, 0, senderName);
            }
            if (code != null && !code.trim().isEmpty()) {
                if (trimmed == null || trimmed.isEmpty()) {
                    return new ClaimOutcome(ClaimStatus.NEED_CODE, 0, 0, 0, senderName);
                }
                if (!code.trim().equals(trimmed)) {
                    return new ClaimOutcome(ClaimStatus.WRONG_CODE, 0, 0, 0, senderName);
                }
            }
            double amount = computeShare(remainingCount, remainingAmount);
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO redpacket_claims (packet_id, player_uuid, player_name, amount, claimed_at)"
                            + " VALUES (?,?,?,?,?)")) {
                ins.setInt(1, packetId);
                ins.setString(2, uuid.toString());
                ins.setString(3, claimerName);
                ins.setDouble(4, amount);
                ins.setLong(5, System.currentTimeMillis());
                ins.executeUpdate();
            } catch (SQLException dup) {
                // composite primary key (packet_id, player_uuid) conflict
                return new ClaimOutcome(ClaimStatus.DUPLICATE, 0, 0, 0, senderName);
            }
            double newAmount = Math.max(0.0, round2(remainingAmount - amount));
            int newCount = remainingCount - 1;
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE redpackets SET remaining_amount = ?, remaining_count = ? WHERE id = ?")) {
                up.setDouble(1, newAmount);
                up.setInt(2, newCount);
                up.setInt(3, packetId);
                up.executeUpdate();
            }
            if (newCount == 0) {
                try (PreparedStatement st = c.prepareStatement(
                        "UPDATE redpackets SET status = 1 WHERE id = ?")) {
                    st.setInt(1, packetId);
                    st.executeUpdate();
                }
            }
            return new ClaimOutcome(ClaimStatus.SUCCESS, amount, newAmount, newCount, senderName);
        }).thenAcceptAsync(out -> {
            switch (out.status()) {
                case SUCCESS -> {
                    plugin.economy().deposit(Bukkit.getOfflinePlayer(uuid), out.amount());
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null) {
                        m.sendPlayer(online, "redpacket.claim-success",
                                "amount", fmt(out.amount()), "sender", out.senderName());
                    }
                    RedPacket rp = active.get(packetId);
                    if (rp != null) {
                        rp.claims().add(uuid);
                        rp.setRemaining(out.newAmount(), out.newCount());
                        if (out.newCount() <= 0) {
                            rp.setStatus(1);
                            active.remove(packetId);
                        }
                    }
                }
                case DUPLICATE -> m.sendPlayer(claimer, "redpacket.claim-duplicate");
                case EMPTY -> m.sendPlayer(claimer, "redpacket.claim-empty");
                case EXPIRED -> {
                    m.sendPlayer(claimer, "redpacket.claim-expired");
                    RedPacket rp = active.get(packetId);
                    if (rp != null) {
                        refundExpired(rp);
                    }
                }
                case NEED_CODE -> m.sendPlayer(claimer, "redpacket.claim-need-code");
                case WRONG_CODE -> m.sendPlayer(claimer, "redpacket.claim-wrong-code");
            }
        }, main()).exceptionally(t -> {
            plugin.getLogger().severe("RedPacket claim on #" + packetId + " failed: " + t.getMessage());
            return null;
        });
    }

    // ------------------------------------------------------------------
    // random share
    // ------------------------------------------------------------------

    /**
     * Random share with a guaranteed minimum. Invariant kept by callers:
     * remaining >= minShare * n, so maxShare >= m and the floor is always payable.
     */
    private double computeShare(int n, double remaining) {
        double m = minShare();
        if (n <= 1) {
            return round2(remaining);
        }
        double maxShare = remaining - m * (n - 1);
        double lo = m;
        double hi = Math.max(lo, Math.min(maxShare, remaining / n * 2.0));
        double raw = lo + (hi - lo) * random.nextDouble();
        double amount = round2(Math.min(raw, maxShare));
        if (amount > maxShare) {
            amount = Math.floor(maxShare * 100.0 + 1e-6) / 100.0;
        }
        if (amount < m) {
            amount = m;
        }
        return amount;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String fmt(double v) {
        return plugin.economy().format(v);
    }

    private Executor main() {
        return run -> Bukkit.getScheduler().runTask(plugin, run);
    }

    // ------------------------------------------------------------------
    // internal types
    // ------------------------------------------------------------------

    private enum ClaimStatus {
        SUCCESS, DUPLICATE, EMPTY, EXPIRED, NEED_CODE, WRONG_CODE
    }

    private record ClaimOutcome(ClaimStatus status, double amount, double newAmount,
                                int newCount, String senderName) {
    }

    private record ClaimRef(int packetId, UUID player) {
    }
}
