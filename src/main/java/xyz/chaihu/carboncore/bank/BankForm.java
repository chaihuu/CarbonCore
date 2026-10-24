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
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Bedrock (Geyser / Cumulus) form UI for the bank module. Form text is stripped
 * of legacy '&' codes because Bedrock does not render them; chat feedback still
 * goes through the colored Messages API.
 */
public final class BankForm {

    private final CarbonCorePlugin plugin;
    private final BankModule module;

    public BankForm(CarbonCorePlugin plugin, BankModule module) {
        this.plugin = plugin;
        this.module = module;
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

    public void openMain(Player player) {
        List<String> buttons = new ArrayList<>();
        buttons.add(plain("bank.gui.deposit"));
        buttons.add(plain("bank.gui.withdraw"));
        buttons.add(plain("bank.gui.transfer"));
        buttons.add(plain("bank.gui.balance"));
        boolean admin = player.hasPermission("carboncore.bank.admin.give");
        if (admin) {
            buttons.add(plain("bank.gui.admin"));
        }
        String content = plain("bank.balance", "balance", fmt(plugin.economy().getBalance(player)))
                + "\n" + plain("bank.bank-balance", "balance",
                fmt(module.service().cachedBankBalance(player.getUniqueId())));
        plugin.formService().sendSimple(player, plain("bank.form.title"), content, buttons,
                (p, index) -> {
                    switch (index) {
                        case 0 -> depositForm(p);
                        case 1 -> withdrawForm(p);
                        case 2 -> transferForm(p);
                        case 3 -> balanceNotice(p);
                        default -> {
                            if (admin) {
                                adminForm(p);
                            }
                        }
                    }
                });
    }

    private void depositForm(Player player) {
        plugin.formService().sendCustom(player, plain("bank.form.deposit-title"),
                builder -> builder.input(plain("bank.form.deposit-content"), "0", "0"),
                (p, response) -> {
                    double amount = parseAmount(p, response.asInput(0));
                    if (!Double.isNaN(amount)) {
                        module.service().deposit(p, amount);
                    }
                });
    }

    private void withdrawForm(Player player) {
        plugin.formService().sendCustom(player, plain("bank.form.withdraw-title"),
                builder -> builder.input(plain("bank.form.withdraw-content"), "0", "0"),
                (p, response) -> {
                    double amount = parseAmount(p, response.asInput(0));
                    if (!Double.isNaN(amount)) {
                        module.service().withdraw(p, amount);
                    }
                });
    }

    private void transferForm(Player player) {
        plugin.formService().sendCustom(player, plain("bank.form.transfer-title"),
                builder -> builder
                        .input(plain("bank.form.transfer-player-label"), "")
                        .input(plain("bank.form.transfer-amount-label"), "0", "0"),
                (p, response) -> {
                    String name = response.asInput(0) == null ? "" : response.asInput(0).trim();
                    if (name.isEmpty()) {
                        plugin.messages().sendPlayer(p, "general.player-not-found", "player", name);
                        return;
                    }
                    double amount = parseAmount(p, response.asInput(1));
                    if (!Double.isNaN(amount)) {
                        module.service().transfer(p, name, amount);
                    }
                });
    }

    private void balanceNotice(Player player) {
        String content = plain("bank.balance", "balance", fmt(plugin.economy().getBalance(player)))
                + "\n" + plain("bank.bank-balance", "balance",
                fmt(module.service().cachedBankBalance(player.getUniqueId())));
        plugin.formService().sendNotice(player, plain("bank.form.result-title"), content);
    }

    private void adminForm(Player player) {
        List<String> actions = List.of(
                plain("bank.gui.admin-set"),
                plain("bank.gui.admin-add"),
                plain("bank.gui.admin-sub"),
                plain("bank.gui.admin-clear"));
        plugin.formService().sendCustom(player, plain("bank.form.admin-title"),
                builder -> builder
                        .dropdown(plain("bank.form.admin-title"), actions)
                        .input(plain("bank.form.admin-player-label"), "")
                        .input(plain("bank.form.admin-amount-label"), "0", "0"),
                (p, response) -> {
                    if (!p.hasPermission("carboncore.bank.admin.give")) {
                        plugin.messages().sendPlayer(p, "general.no-permission");
                        return;
                    }
                    int action = response.asDropdown(0);
                    String name = response.asInput(1) == null ? "" : response.asInput(1).trim();
                    if (name.isEmpty()) {
                        plugin.messages().sendPlayer(p, "general.player-not-found", "player", name);
                        return;
                    }
                    OfflinePlayer target = module.service().findPlayer(name);
                    if (target == null) {
                        plugin.messages().sendPlayer(p, "general.player-not-found", "player", name);
                        return;
                    }
                    String targetName = target.getName() != null ? target.getName() : name;
                    UUID targetUuid = target.getUniqueId();
                    if (action == 3) {
                        module.service().adminClear(targetUuid, targetName)
                                .thenAcceptAsync(bal -> plugin.messages().sendPlayer(p,
                                        "bank.admin-clear-success", "player", targetName), main());
                        return;
                    }
                    double amount = parseAmount(p, response.asInput(2));
                    if (Double.isNaN(amount)) {
                        return;
                    }
                    switch (action) {
                        case 0 -> module.service().adminSet(targetUuid, targetName, amount)
                                .thenAcceptAsync(bal -> plugin.messages().sendPlayer(p,
                                        "bank.admin-set-success", "player", targetName,
                                        "amount", fmt(amount)), main());
                        case 1 -> module.service().adminGive(targetUuid, targetName, amount)
                                .thenAcceptAsync(bal -> plugin.messages().sendPlayer(p,
                                        "bank.admin-give-success", "player", targetName,
                                        "amount", fmt(amount), "balance", fmt(bal)), main());
                        case 2 -> module.service().adminGive(targetUuid, targetName, -amount)
                                .thenAcceptAsync(bal -> plugin.messages().sendPlayer(p,
                                        "bank.admin-give-success", "player", targetName,
                                        "amount", fmt(-amount), "balance", fmt(bal)), main());
                        default -> {
                        }
                    }
                });
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
