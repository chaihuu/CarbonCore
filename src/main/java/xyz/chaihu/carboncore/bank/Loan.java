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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * In-memory model of one row of the {@code bank_loans} table.
 * Interest escalates with every missed payment (DZTradeHub style):
 * the effective rate is {@code interest_rate * (1 + missed_payments * escalation)}.
 */
public final class Loan {

    public static final int STATUS_ACTIVE = 0;
    public static final int STATUS_REPAID = 1;
    public static final int STATUS_DEFAULTED = 2;

    private final int id;
    private final UUID uuid;
    private final String name;
    private final double principal;
    private final double remaining;
    private final double interestRate;
    private final int periodHours;
    private final long issuedAt;
    private final long dueAt;
    private final int missedPayments;
    private final int status;
    private final long lastPaidAt;

    public Loan(int id, UUID uuid, String name, double principal, double remaining, double interestRate,
                int periodHours, long issuedAt, long dueAt, int missedPayments, int status, long lastPaidAt) {
        this.id = id;
        this.uuid = uuid;
        this.name = name;
        this.principal = principal;
        this.remaining = remaining;
        this.interestRate = interestRate;
        this.periodHours = periodHours;
        this.issuedAt = issuedAt;
        this.dueAt = dueAt;
        this.missedPayments = missedPayments;
        this.status = status;
        this.lastPaidAt = lastPaidAt;
    }

    /** Maps a {@code bank_loans} row. Column order follows the CREATE TABLE schema. */
    public static Loan fromResultSet(ResultSet rs) throws SQLException {
        return new Loan(
                rs.getInt("id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getDouble("principal"),
                rs.getDouble("remaining"),
                rs.getDouble("interest_rate"),
                rs.getInt("period_hours"),
                rs.getLong("issued_at"),
                rs.getLong("due_at"),
                rs.getInt("missed_payments"),
                rs.getInt("status"),
                rs.getLong("last_paid_at"));
    }

    public int id() { return id; }
    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public double principal() { return principal; }
    public double remaining() { return remaining; }
    public double interestRate() { return interestRate; }
    public int periodHours() { return periodHours; }
    public long issuedAt() { return issuedAt; }
    public long dueAt() { return dueAt; }
    public int missedPayments() { return missedPayments; }
    public int status() { return status; }
    public long lastPaidAt() { return lastPaidAt; }

    /** Still open for repayment / auto-settlement. */
    public boolean isActive() {
        return status == STATUS_ACTIVE;
    }

    /** Effective per-period rate after late escalation, in percent. */
    public double currentRate(double escalation) {
        return interestRate * (1.0 + missedPayments * escalation);
    }

    /** Principal plus interest owed for the current period. */
    public double dueAmount(double escalation) {
        return BankService.round2(remaining * (1.0 + currentRate(escalation) / 100.0));
    }
}
