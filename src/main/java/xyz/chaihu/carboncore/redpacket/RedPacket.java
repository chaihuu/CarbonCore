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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory model of one row of the {@code redpackets} table.
 * The {@link #claims()} set mirrors {@code redpacket_claims} for this packet
 * and is kept synchronised by {@link RedPacketService}.
 */
public final class RedPacket {

    private final int id;
    private final UUID senderUuid;
    private final String senderName;
    private final double totalAmount;
    private final int totalCount;
    private double remainingAmount;
    private int remainingCount;
    private final String message;
    private final String code;
    private final long createdAt;
    private final long expireAt;
    private int status;

    /** UUIDs of players who already claimed this packet (thread-safe: placeholders read it async). */
    private final Set<UUID> claims = Collections.synchronizedSet(new HashSet<>());

    public RedPacket(int id, UUID senderUuid, String senderName, double totalAmount, int totalCount,
                     double remainingAmount, int remainingCount, String message, String code,
                     long createdAt, long expireAt, int status) {
        this.id = id;
        this.senderUuid = senderUuid;
        this.senderName = senderName;
        this.totalAmount = totalAmount;
        this.totalCount = totalCount;
        this.remainingAmount = remainingAmount;
        this.remainingCount = remainingCount;
        this.message = message;
        this.code = code;
        this.createdAt = createdAt;
        this.expireAt = expireAt;
        this.status = status;
    }

    /** Maps a {@code redpackets} row. Column order follows the CREATE TABLE schema. */
    public static RedPacket fromResultSet(ResultSet rs) throws SQLException {
        return new RedPacket(
                rs.getInt("id"),
                UUID.fromString(rs.getString("sender_uuid")),
                rs.getString("sender_name"),
                rs.getDouble("total_amount"),
                rs.getInt("total_count"),
                rs.getDouble("remaining_amount"),
                rs.getInt("remaining_count"),
                rs.getString("message"),
                rs.getString("code"),
                rs.getLong("created_at"),
                rs.getLong("expire_at"),
                rs.getInt("status"));
    }

    public int id() { return id; }
    public UUID senderUuid() { return senderUuid; }
    public String senderName() { return senderName; }
    public double totalAmount() { return totalAmount; }
    public int totalCount() { return totalCount; }
    public double remainingAmount() { return remainingAmount; }
    public int remainingCount() { return remainingCount; }
    public String message() { return message; }
    public String code() { return code; }
    public long createdAt() { return createdAt; }
    public long expireAt() { return expireAt; }
    public int status() { return status; }
    public Set<UUID> claims() { return claims; }

    public void setRemaining(double remainingAmount, int remainingCount) {
        this.remainingAmount = remainingAmount;
        this.remainingCount = remainingCount;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    /** Still open: status 0, shares left and not yet expired. */
    public boolean isOpen() {
        return status == 0 && remainingCount > 0 && expireAt > System.currentTimeMillis();
    }

    /** Whether this packet requires a password (code) to claim. */
    public boolean needsCode() {
        return code != null && !code.trim().isEmpty();
    }
}
