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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.ui.menu.Menu;
import xyz.chaihu.carboncore.util.ItemBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Chest-GUI screens for the loan / credit subsystem. All texts come from
 * messages.yml (bank.loan.*). Bedrock viewers are routed to LoanForm instead.
 */
public final class LoanMenu {

    private LoanMenu() {
    }

    /** Routes the viewer to the Bedrock form or the Java main screen. */
    public static void openMain(CarbonCorePlugin plugin, BankModule module, Player player) {
        LoanForm.openMain(plugin, module, player);
    }

    private static Executor main(CarbonCorePlugin plugin) {
        return run -> Bukkit.getScheduler().runTask(plugin, run);
    }

    /** Five rounded amount steps at 20/40/60/80/100 percent of the base, deduplicated ascending. */
    static List<Double> fractions(double base) {
        TreeSet<Double> set = new TreeSet<>();
        for (double factor : new double[]{0.2, 0.4, 0.6, 0.8, 1.0}) {
            double value = BankService.round2(base * factor);
            if (value >= 1.0) {
                set.add(value);
            }
        }
        return new ArrayList<>(set);
    }

    static String fmtDue(CarbonCorePlugin plugin, long dueAt) {
        long left = dueAt - System.currentTimeMillis();
        if (left <= 0) {
            return plugin.messages().raw("bank.loan.overdue");
        }
        long totalMinutes = left / 60000L;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        sb.append(minutes).append("m");
        return sb.toString();
    }

    static String statusName(CarbonCorePlugin plugin, int status) {
        return switch (status) {
            case Loan.STATUS_REPAID -> plugin.messages().raw("bank.loan.status-repaid");
            case Loan.STATUS_DEFAULTED -> plugin.messages().raw("bank.loan.status-defaulted");
            default -> plugin.messages().raw("bank.loan.status-active");
        };
    }

    // ------------------------------------------------------------------
    // main screen
    // ------------------------------------------------------------------

    /** 54 slots: credit head + apply / repay / detail / history / back / close. */
    public static final class Main extends Menu {

        private final BankModule module;

        public Main(CarbonCorePlugin plugin, BankModule module, Player viewer) {
            super(plugin, viewer, 54, plugin.messages().raw("bank.loan.title"));
            this.module = module;
        }

        @Override
        public void build() {
            fillRow(0, ItemBuilder.pane());
            LoanService loans = module.loans();
            UUID uuid = viewer.getUniqueId();
            int score = loans.cachedCreditScore(uuid);

            set(4, new ItemBuilder(Material.PLAYER_HEAD)
                            .skull(viewer)
                            .name(plugin.messages().format("bank.loan.head-name", "player", viewer.getName()))
                            .lore(
                                    plugin.messages().format("bank.loan.score-info", "score", score),
                                    plugin.messages().format("bank.loan.credit-line",
                                            "limit", plugin.economy().format(loans.creditLimit(score))),
                                    plugin.messages().format("bank.loan.interest-line",
                                            "rate", String.format("%.2f", loans.creditInterest(score))),
                                    plugin.messages().format("bank.loan.debt-line",
                                            "debt", plugin.economy().format(loans.cachedDebt(uuid))))
                            .build());

            set(20, new ItemBuilder(Material.GOLD_INGOT)
                            .name(plugin.messages().raw("bank.loan.btn-apply"))
                            .lore(plugin.messages().raw("bank.loan.btn-apply-lore"))
                            .build(),
                    (event, player, slot) -> new Apply(plugin, module, viewer).open());

            set(22, new ItemBuilder(Material.EMERALD)
                            .name(plugin.messages().raw("bank.loan.btn-repay"))
                            .lore(plugin.messages().raw("bank.loan.btn-repay-lore"))
                            .build(),
                    (event, player, slot) -> new Repay(plugin, module, viewer).open());

            set(24, new ItemBuilder(Material.BOOK)
                            .name(plugin.messages().raw("bank.loan.btn-detail"))
                            .lore(plugin.messages().raw("bank.loan.btn-detail-lore"))
                            .build(),
                    (event, player, slot) -> loans.summary(uuid).thenAcceptAsync(
                            rows -> new Detail(plugin, module, viewer, 0, rows).open(), main(plugin)));

            set(28, new ItemBuilder(Material.CLOCK)
                            .name(plugin.messages().raw("bank.loan.btn-history"))
                            .lore(plugin.messages().raw("bank.loan.btn-history-lore"))
                            .build(),
                    (event, player, slot) -> loans.history(uuid, 45).thenAcceptAsync(
                            rows -> new History(plugin, module, viewer, 0, rows).open(), main(plugin)));

            set(45, new ItemBuilder(Material.ARROW)
                            .name(plugin.messages().raw("general.back"))
                            .build(),
                    (event, player, slot) -> new BankMainMenu(plugin, module, viewer).open());

            set(53, new ItemBuilder(Material.BARRIER)
                            .name(plugin.messages().raw("general.close"))
                            .build(),
                    (event, player, slot) -> viewer.closeInventory());
        }
    }

    // ------------------------------------------------------------------
    // apply
    // ------------------------------------------------------------------

    /** Amount chooser derived from the credit line. */
    public static final class Apply extends Menu {

        private final BankModule module;

        public Apply(CarbonCorePlugin plugin, BankModule module, Player viewer) {
            super(plugin, viewer, 27, plugin.messages().raw("bank.loan.apply-title"));
            this.module = module;
        }

        @Override
        public void build() {
            fillRow(0, ItemBuilder.pane());

            LoanService loans = module.loans();
            double limit = loans.creditLimit(loans.cachedCreditScore(viewer.getUniqueId()));
            int slot = 10;
            for (Double amount : fractions(limit)) {
                if (slot > 16) {
                    break;
                }
                final double value = amount;
                set(slot++, new ItemBuilder(Material.GOLD_INGOT)
                                .name("&f" + plugin.economy().format(value))
                                .lore(plugin.messages().format("bank.loan.apply-lore",
                                        "amount", plugin.economy().format(value)))
                                .build(),
                        (event, player, s) -> new Confirm(plugin, module, viewer, value, Confirm.Mode.APPLY).open());
            }

            set(18, new ItemBuilder(Material.ARROW)
                            .name(plugin.messages().raw("general.back"))
                            .build(),
                    (event, player, s) -> new Main(plugin, module, viewer).open());

            set(26, new ItemBuilder(Material.BARRIER)
                            .name(plugin.messages().raw("general.close"))
                            .build(),
                    (event, player, s) -> viewer.closeInventory());
        }
    }

    // ------------------------------------------------------------------
    // confirm
    // ------------------------------------------------------------------

    /** GUI counterpart of the Bedrock modal form: confirm or cancel one amount. */
    public static final class Confirm extends Menu {

        public enum Mode { APPLY, REPAY }

        private final BankModule module;
        private final double amount;
        private final Mode mode;

        public Confirm(CarbonCorePlugin plugin, BankModule module, Player viewer, double amount, Mode mode) {
            super(plugin, viewer, 27, plugin.messages().raw(
                    mode == Mode.APPLY ? "bank.loan.apply-title" : "bank.loan.repay-title"));
            this.module = module;
            this.amount = BankService.round2(amount);
            this.mode = mode;
        }

        @Override
        public void build() {
            fillRow(0, ItemBuilder.pane());

            set(13, new ItemBuilder(Material.PAPER)
                            .name(plugin.messages().format("bank.loan.confirm-question",
                                    "amount", plugin.economy().format(amount)))
                            .build());

            set(11, new ItemBuilder(Material.LIME_DYE)
                            .name(plugin.messages().raw("bank.loan.confirm-yes"))
                            .glow()
                            .build(),
                    (event, player, slot) -> {
                        if (mode == Mode.APPLY) {
                            module.loans().apply(viewer, amount, this::refreshIfOpen);
                        } else {
                            module.loans().repay(viewer, amount, this::refreshIfOpen);
                        }
                    });

            set(15, new ItemBuilder(Material.RED_DYE)
                            .name(plugin.messages().raw("bank.loan.confirm-no"))
                            .build(),
                    (event, player, slot) -> {
                        if (mode == Mode.APPLY) {
                            new Apply(plugin, module, viewer).open();
                        } else {
                            new Repay(plugin, module, viewer).open();
                        }
                    });

            set(18, new ItemBuilder(Material.ARROW)
                            .name(plugin.messages().raw("general.back"))
                            .build(),
                    (event, player, slot) -> new Main(plugin, module, viewer).open());

            set(26, new ItemBuilder(Material.BARRIER)
                            .name(plugin.messages().raw("general.close"))
                            .build(),
                    (event, player, slot) -> viewer.closeInventory());
        }

        private void refreshIfOpen() {
            if (!viewer.isOnline()) {
                return;
            }
            if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
                new Main(plugin, module, viewer).open();
            }
        }
    }

    // ------------------------------------------------------------------
    // repay
    // ------------------------------------------------------------------

    /** Amount steps derived from min(wallet, total debt) plus a "settle all" button. */
    public static final class Repay extends Menu {

        private final BankModule module;

        public Repay(CarbonCorePlugin plugin, BankModule module, Player viewer) {
            super(plugin, viewer, 27, plugin.messages().raw("bank.loan.repay-title"));
            this.module = module;
        }

        @Override
        public void build() {
            fillRow(0, ItemBuilder.pane());

            LoanService loans = module.loans();
            double debt = loans.cachedDebt(viewer.getUniqueId());
            if (debt <= 0) {
                set(13, new ItemBuilder(Material.BARRIER)
                        .name(plugin.messages().raw("bank.loan.none-yet"))
                        .build());
            } else {
                double wallet = plugin.economy().getBalance(viewer);
                double cap = Math.min(wallet, debt);
                int slot = 10;
                for (Double amount : fractions(cap)) {
                    if (slot > 14) {
                        break;
                    }
                    final double value = amount;
                    set(slot++, new ItemBuilder(Material.EMERALD)
                                    .name("&f" + plugin.economy().format(value))
                                    .lore(plugin.messages().format("bank.loan.repay-lore",
                                            "amount", plugin.economy().format(value)))
                                    .build(),
                            (event, player, s) -> new Confirm(plugin, module, viewer, value, Confirm.Mode.REPAY).open());
                }
                set(15, new ItemBuilder(Material.ENDER_EYE)
                                .name(plugin.messages().raw("bank.loan.repay-all"))
                                .lore(plugin.messages().raw("bank.loan.repay-all-lore"))
                                .build(),
                        (event, player, s) -> new Confirm(plugin, module, viewer, debt, Confirm.Mode.REPAY).open());
            }

            set(18, new ItemBuilder(Material.ARROW)
                            .name(plugin.messages().raw("general.back"))
                            .build(),
                    (event, player, s) -> new Main(plugin, module, viewer).open());

            set(26, new ItemBuilder(Material.BARRIER)
                            .name(plugin.messages().raw("general.close"))
                            .build(),
                    (event, player, s) -> viewer.closeInventory());
        }
    }

    // ------------------------------------------------------------------
    // detail / history
    // ------------------------------------------------------------------

    /** Paginated active loans, oldest-due first. */
    public static final class Detail extends Menu {

        private static final int PAGE_SIZE = 10;

        private final BankModule module;
        private final int page;
        private final List<Loan> rows;

        public Detail(CarbonCorePlugin plugin, BankModule module, Player viewer, int page, List<Loan> rows) {
            super(plugin, viewer, 54, plugin.messages().raw("bank.loan.detail-title"));
            this.module = module;
            this.page = Math.max(0, page);
            this.rows = List.copyOf(rows);
        }

        @Override
        public void build() {
            ItemStack pane = ItemBuilder.pane();
            for (int i = 0; i < size; i++) {
                set(i, pane);
            }

            int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int start = Math.min(page * PAGE_SIZE, rows.size());
            int end = Math.min(start + PAGE_SIZE, rows.size());
            if (rows.isEmpty()) {
                set(13, new ItemBuilder(Material.BARRIER)
                        .name(plugin.messages().raw("bank.loan.none-yet"))
                        .build());
            }
            double esc = module.loans().lateEscalation();
            int slot = 10;
            for (int i = start; i < end; i++) {
                Loan loan = rows.get(i);
                ItemStack item = new ItemBuilder(Material.BOOK)
                        .name(plugin.messages().format("bank.loan.detail-name", "id", loan.id()))
                        .lore(plugin.messages().format("bank.loan.detail-lore",
                                "principal", plugin.economy().format(loan.principal()),
                                "remaining", plugin.economy().format(loan.remaining()),
                                "rate", String.format("%.2f", loan.currentRate(esc)),
                                "due", plugin.messages().format("bank.loan.due-in",
                                        "time", fmtDue(plugin, loan.dueAt())),
                                "missed", loan.missedPayments()))
                        .build();
                set(slot++, item);
            }

            set(45, new ItemBuilder(Material.ARROW)
                            .name(plugin.messages().raw("general.previous"))
                            .build(),
                    (event, player, s) -> {
                        if (page > 0) {
                            new Detail(plugin, module, player, page - 1, rows).open();
                        }
                    });

            set(51, new ItemBuilder(Material.PAPER)
                    .name(plugin.messages().format("general.page-info",
                            "page", page + 1, "pages", pages))
                    .build());

            set(49, new ItemBuilder(Material.BARRIER)
                            .name(plugin.messages().raw("general.back"))
                            .build(),
                    (event, player, s) -> new Main(plugin, module, player).open());

            set(53, new ItemBuilder(Material.ARROW)
                            .name(plugin.messages().raw("general.next"))
                            .build(),
                    (event, player, s) -> {
                        if (page + 1 < pages) {
                            new Detail(plugin, module, player, page + 1, rows).open();
                        }
                    });
        }
    }

    /** Paginated recent loans of any status, newest first. */
    public static final class History extends Menu {

        private static final int PAGE_SIZE = 10;
        private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        private final BankModule module;
        private final int page;
        private final List<Loan> rows;

        public History(CarbonCorePlugin plugin, BankModule module, Player viewer, int page, List<Loan> rows) {
            super(plugin, viewer, 54, plugin.messages().raw("bank.loan.history-title"));
            this.module = module;
            this.page = Math.max(0, page);
            this.rows = List.copyOf(rows);
        }

        @Override
        public void build() {
            ItemStack pane = ItemBuilder.pane();
            for (int i = 0; i < size; i++) {
                set(i, pane);
            }

            int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int start = Math.min(page * PAGE_SIZE, rows.size());
            int end = Math.min(start + PAGE_SIZE, rows.size());
            if (rows.isEmpty()) {
                set(13, new ItemBuilder(Material.BARRIER)
                        .name(plugin.messages().raw("general.empty"))
                        .build());
            }
            int slot = 10;
            for (int i = start; i < end; i++) {
                Loan loan = rows.get(i);
                ItemStack item = new ItemBuilder(Material.PAPER)
                        .name(plugin.messages().format("bank.loan.history-name",
                                "id", loan.id(), "status", statusName(plugin, loan.status())))
                        .lore(plugin.messages().format("bank.loan.history-lore",
                                "principal", plugin.economy().format(loan.principal()),
                                "issued", DATE_FORMAT.format(new Date(loan.issuedAt()))))
                        .build();
                set(slot++, item);
            }

            set(45, new ItemBuilder(Material.ARROW)
                            .name(plugin.messages().raw("general.previous"))
                            .build(),
                    (event, player, s) -> {
                        if (page > 0) {
                            new History(plugin, module, player, page - 1, rows).open();
                        }
                    });

            set(51, new ItemBuilder(Material.PAPER)
                    .name(plugin.messages().format("general.page-info",
                            "page", page + 1, "pages", pages))
                    .build());

            set(49, new ItemBuilder(Material.BARRIER)
                            .name(plugin.messages().raw("general.back"))
                            .build(),
                    (event, player, s) -> new Main(plugin, module, player).open());

            set(53, new ItemBuilder(Material.ARROW)
                            .name(plugin.messages().raw("general.next"))
                            .build(),
                    (event, player, s) -> {
                        if (page + 1 < pages) {
                            new History(plugin, module, player, page + 1, rows).open();
                        }
                    });
        }
    }
}
