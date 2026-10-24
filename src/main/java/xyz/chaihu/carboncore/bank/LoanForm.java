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
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.util.Text;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Bedrock (Geyser / Cumulus) form UI for the loan / credit subsystem. Form text
 * is stripped of legacy '&' codes because Bedrock does not render them; chat
 * feedback still goes through the colored Messages API.
 */
public final class LoanForm {

    private final CarbonCorePlugin plugin;
    private final BankModule module;

    public LoanForm(CarbonCorePlugin plugin, BankModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    /** Routes the viewer to the native form (Bedrock) or the chest GUI (Java). */
    public static void openMain(CarbonCorePlugin plugin, BankModule module, Player player) {
        if (plugin.platform().isBedrock(player) && plugin.formService() != null) {
            new LoanForm(plugin, module).openMainForm(player);
        } else {
            new LoanMenu.Main(plugin, module, player).open();
        }
    }

    private String plain(String key, Object... pairs) {
        return Text.plain(plugin.messages().format(key, pairs));
    }

    private String fmt(double value) {
        return plugin.economy().format(value);
    }

    private Executor main() {
        return run -> Bukkit.getScheduler().runTask(plugin, run);
    }

    private String creditSummary(Player player) {
        LoanService loans = module.loans();
        UUID uuid = player.getUniqueId();
        int score = loans.cachedCreditScore(uuid);
        return plain("bank.loan.score-info", "score", score)
                + "\n" + plain("bank.loan.credit-line", "limit", fmt(loans.creditLimit(score)))
                + "\n" + plain("bank.loan.interest-line", "rate", String.format("%.2f", loans.creditInterest(score)))
                + "\n" + plain("bank.loan.debt-line", "debt", fmt(loans.cachedDebt(uuid)));
    }

    private void openMainForm(Player player) {
        List<String> buttons = List.of(
                plain("bank.loan.btn-apply"),
                plain("bank.loan.btn-repay"),
                plain("bank.loan.btn-detail"),
                plain("bank.loan.btn-history"));
        plugin.formService().sendSimple(player, plain("bank.loan.form-title"),
                creditSummary(player), buttons, (p, index) -> {
                    switch (index) {
                        case 0 -> applyForm(p);
                        case 1 -> repayForm(p);
                        case 2 -> detailForm(p);
                        default -> historyForm(p);
                    }
                });
    }

    private void applyForm(Player player) {
        plugin.formService().sendCustom(player, plain("bank.loan.apply-title"),
                builder -> builder.input(plain("bank.loan.form-amount-label"), "0", "0"),
                (p, response) -> {
                    double amount = parseAmount(p, response.asInput(0));
                    if (!Double.isNaN(amount)) {
                        module.loans().apply(p, amount, () -> openMainForm(p));
                    }
                });
    }

    private void repayForm(Player player) {
        double debt = module.loans().cachedDebt(player.getUniqueId());
        if (debt <= 0) {
            plugin.messages().sendPlayer(player, "bank.loan.no-active");
            openMainForm(player);
            return;
        }
        plugin.formService().sendModal(player, plain("bank.loan.repay-title"),
                plain("bank.loan.repay-question", "debt", fmt(debt)),
                plain("bank.loan.confirm-yes"), plain("bank.loan.confirm-no"),
                (p, confirmed) -> {
                    if (confirmed) {
                        module.loans().repayAll(p, () -> openMainForm(p));
                    } else {
                        openMainForm(p);
                    }
                });
    }

    private void detailForm(Player player) {
        UUID uuid = player.getUniqueId();
        double esc = module.loans().lateEscalation();
        module.loans().summary(uuid).thenAcceptAsync(loans -> {
            Player viewer = Bukkit.getPlayer(uuid);
            if (viewer == null) {
                return;
            }
            StringBuilder content = new StringBuilder(creditSummary(viewer)).append("\n");
            if (loans.isEmpty()) {
                content.append(plain("bank.loan.none-yet"));
            }
            for (Loan loan : loans) {
                content.append("\n").append(plain("bank.loan.detail-lore",
                        "principal", fmt(loan.principal()),
                        "remaining", fmt(loan.remaining()),
                        "rate", String.format("%.2f", loan.currentRate(esc)),
                        "due", plain("bank.loan.due-in", "time", LoanMenu.fmtDue(plugin, loan.dueAt())),
                        "missed", loan.missedPayments()));
            }
            plugin.formService().sendSimple(viewer, plain("bank.loan.detail-title"),
                    content.toString(), List.of(plain("general.back")),
                    (p, index) -> openMainForm(p));
        }, main());
    }

    private void historyForm(Player player) {
        UUID uuid = player.getUniqueId();
        module.loans().history(uuid, 45).thenAcceptAsync(loans -> {
            Player viewer = Bukkit.getPlayer(uuid);
            if (viewer == null) {
                return;
            }
            StringBuilder content = new StringBuilder();
            if (loans.isEmpty()) {
                content.append(plain("general.empty"));
            }
            for (Loan loan : loans) {
                if (content.length() > 0) {
                    content.append("\n");
                }
                content.append(plain("bank.loan.history-name",
                        "id", loan.id(), "status", LoanMenu.statusName(plugin, loan.status())))
                        .append(" | ").append(plain("bank.loan.history-lore",
                        "principal", fmt(loan.principal()),
                        "issued", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                                .format(new java.util.Date(loan.issuedAt()))));
            }
            plugin.formService().sendSimple(viewer, plain("bank.loan.history-title"),
                    content.toString(), List.of(plain("general.back")),
                    (p, index) -> openMainForm(p));
        }, main());
    }

    private double parseAmount(Player player, String input) {
        if (input == null || input.isBlank()) {
            plugin.messages().sendPlayer(player, "general.invalid-amount", "input", String.valueOf(input));
            return Double.NaN;
        }
        try {
            return BankService.round2(Double.parseDouble(input.trim()));
        } catch (NumberFormatException ex) {
            plugin.messages().sendPlayer(player, "general.invalid-amount", "input", input);
            return Double.NaN;
        }
    }
}
