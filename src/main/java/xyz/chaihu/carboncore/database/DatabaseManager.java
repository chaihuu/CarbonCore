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
package xyz.chaihu.carboncore.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite + HikariCP data access with a single-threaded async executor.
 * Every query/update runs off the main thread; callers receive CompletableFutures.
 */
public final class DatabaseManager {

    private HikariDataSource dataSource;
    private ExecutorService executor;

    public void init(File dbFile) throws SQLException {
        dbFile.getParentFile().mkdirs();
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        // SQLite allows only one writer; a pool of one keeps transactions serialised.
        cfg.setMaximumPoolSize(1);
        cfg.setPoolName("CarbonCore-SQLite");
        cfg.setConnectionInitSql("PRAGMA journal_mode=WAL;");
        dataSource = new HikariDataSource(cfg);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CarbonCore-DB");
            t.setDaemon(true);
            return t;
        });
        createSchema();
    }

    private void createSchema() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bank_accounts (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        balance REAL NOT NULL DEFAULT 0,
                        last_interest INTEGER NOT NULL DEFAULT 0
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_credit (
                        uuid TEXT PRIMARY KEY,
                        score INTEGER NOT NULL,
                        on_time INTEGER NOT NULL DEFAULT 0,
                        missed INTEGER NOT NULL DEFAULT 0,
                        defaults INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bank_loans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        name TEXT NOT NULL,
                        principal REAL NOT NULL,
                        remaining REAL NOT NULL,
                        interest_rate REAL NOT NULL,
                        period_hours INTEGER NOT NULL,
                        issued_at INTEGER NOT NULL,
                        due_at INTEGER NOT NULL,
                        missed_payments INTEGER NOT NULL DEFAULT 0,
                        status INTEGER NOT NULL DEFAULT 0,
                        last_paid_at INTEGER
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS redpackets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        sender_uuid TEXT NOT NULL,
                        sender_name TEXT NOT NULL,
                        total_amount REAL NOT NULL,
                        total_count INTEGER NOT NULL,
                        remaining_amount REAL NOT NULL,
                        remaining_count INTEGER NOT NULL,
                        message TEXT,
                        code TEXT,
                        created_at INTEGER NOT NULL,
                        expire_at INTEGER NOT NULL,
                        status INTEGER NOT NULL DEFAULT 0
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS redpacket_claims (
                        packet_id INTEGER NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        amount REAL NOT NULL,
                        claimed_at INTEGER NOT NULL,
                        PRIMARY KEY (packet_id, player_uuid)
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS wish_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        pull_time INTEGER NOT NULL,
                        entry_id TEXT NOT NULL,
                        entry_name TEXT NOT NULL,
                        result_type TEXT NOT NULL
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS wish_pity (
                        uuid TEXT PRIMARY KEY,
                        since_rare INTEGER NOT NULL DEFAULT 0,
                        total_pulls INTEGER NOT NULL DEFAULT 0
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS quest_progress (
                        uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        period TEXT NOT NULL,
                        progress INTEGER NOT NULL DEFAULT 0,
                        claimed INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (uuid, quest_id, period)
                    )""");
        }
    }

    public boolean isAvailable() {
        return dataSource != null && !dataSource.isClosed();
    }

    // ------------------------------------------------------------------
    // Async helpers
    // ------------------------------------------------------------------

    public CompletableFuture<Integer> update(String sql, Object... params) {
        return supply(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    public <T> CompletableFuture<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return supply(() -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapper.map(rs) : null;
                }
            }
        });
    }

    public <T> CompletableFuture<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return supply(() -> {
            List<T> out = new ArrayList<>();
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                }
            }
            return out;
        });
    }

    /** Runs a block of statements inside one transaction on the DB thread. */
    public <T> CompletableFuture<T> transaction(TransactionWork<T> work) {
        return supply(() -> {
            try (Connection c = dataSource.getConnection()) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    T result = work.run(c);
                    c.commit();
                    return result;
                } catch (SQLException | RuntimeException ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(auto);
                }
            }
        });
    }

    private <T> CompletableFuture<T> supply(SqlWork<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                future.complete(work.run());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ------------------------------------------------------------------
    // Functional interfaces
    // ------------------------------------------------------------------

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface TransactionWork<T> {
        T run(Connection c) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }
}
