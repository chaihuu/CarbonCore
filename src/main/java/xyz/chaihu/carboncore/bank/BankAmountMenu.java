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
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.ui.menu.Menu;
import xyz.chaihu.carboncore.util.ItemBuilder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Amount chooser screen shared by deposit / withdraw / transfer / admin adjust.
 * Quick amounts come from modules.bank.quick-amounts; deposit, withdraw and
 * transfer additionally offer an "all" button.
 */
public final class BankAmountMenu extends Menu {

    public enum Operation { DEPOSIT, WITHDRAW, TRANSFER, ADMIN_SET, ADMIN_ADD, ADMIN_SUB }

    private final BankModule module;
    private final Operation operation;
    private final UUID targetUuid;
    private final String targetName;

    public BankAmountMenu(CarbonCorePlugin plugin, BankModule module, Player viewer,
                          Operation operation, UUID targetUuid, String targetName) {
        super(plugin, viewer, 27, title(plugin, operation));
        this.module = module;
        this.operation = operation;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
    }

    private static String title(CarbonCorePlugin plugin, Operation op) {
        if (op == Operation.TRANSFER) {
            return plugin.messages().raw("bank.gui.transfer-amount-title");
        }
        return plugin.messages().raw("bank.gui.amount-title");
    }

    private Executor main() {
        return run -> Bukkit.getScheduler().runTask(plugin, run);
    }

    @Override
    public void build() {
        fillRow(0, ItemBuilder.pane());

        List<Double> quick = plugin.configManager().getDoubleList("modules.bank.quick-amounts");
        int slot = 10;
        for (Double amount : quick) {
            if (slot > 14) {
                break;
            }
            if (amount == null || amount <= 0) {
                continue;
            }
            final double value = BankService.round2(amount);
            set(slot++, new ItemBuilder(material())
                            .name("&f" + plugin.economy().format(value))
                            .lore(loreFor(value))
                            .build(),
                    (event, player, s) -> click(value));
        }

        if (operation == Operation.DEPOSIT || operation == Operation.WITHDRAW || operation == Operation.TRANSFER) {
            set(15, new ItemBuilder(Material.ENDER_EYE)
                            .name(plugin.messages().raw("bank.gui.amount-all"))
                            .lore(plugin.messages().raw("bank.gui.amount-all-lore"))
                            .build(),
                    (event, player, s) -> clickAll());
        }

        set(18, new ItemBuilder(Material.ARROW)
                        .name(plugin.messages().raw("general.back"))
                        .build(),
                (event, player, s) -> back());

        set(26, new ItemBuilder(Material.BARRIER)
                        .name(plugin.messages().raw("general.close"))
                        .build(),
                (event, player, s) -> viewer.closeInventory());
    }

    private Material material() {
        return switch (operation) {
            case DEPOSIT -> Material.GOLD_INGOT;
            case WITHDRAW -> Material.REDSTONE;
            case TRANSFER -> Material.PAPER;
            case ADMIN_SET -> Material.DIAMOND;
            case ADMIN_ADD -> Material.EMERALD;
            case ADMIN_SUB -> Material.REDSTONE;
        };
    }

    private String loreFor(double value) {
        String formatted = plugin.economy().format(value);
        if (operation == Operation.TRANSFER) {
            return plugin.messages().format("bank.gui.transfer-amount-lore", "amount", formatted);
        }
        return plugin.messages().format("bank.gui.amount-lore", "amount", formatted);
    }

    private void click(double value) {
        var service = module.service();
        switch (operation) {
            case DEPOSIT -> service.deposit(viewer, value, this::refreshIfOpen);
            case WITHDRAW -> service.withdraw(viewer, value, this::refreshIfOpen);
            case TRANSFER -> service.transfer(viewer, targetName, value, this::refreshIfOpen);
            case ADMIN_SET -> service.adminSet(targetUuid, targetName, value).thenAcceptAsync(bal -> {
                if (viewer.isOnline()) {
                    plugin.messages().sendPlayer(viewer, "bank.admin-set-success",
                            "player", targetName, "amount", plugin.economy().format(value));
                }
                refreshIfOpen();
            }, main());
            case ADMIN_ADD -> service.adminGive(targetUuid, targetName, value).thenAcceptAsync(bal -> {
                if (viewer.isOnline()) {
                    plugin.messages().sendPlayer(viewer, "bank.admin-give-success",
                            "player", targetName,
                            "amount", plugin.economy().format(value),
                            "balance", plugin.economy().format(bal));
                }
                refreshIfOpen();
            }, main());
            case ADMIN_SUB -> service.adminGive(targetUuid, targetName, -value).thenAcceptAsync(bal -> {
                if (viewer.isOnline()) {
                    plugin.messages().sendPlayer(viewer, "bank.admin-give-success",
                            "player", targetName,
                            "amount", plugin.economy().format(-value),
                            "balance", plugin.economy().format(bal));
                }
                refreshIfOpen();
            }, main());
        }
    }

    private void clickAll() {
        var service = module.service();
        switch (operation) {
            case DEPOSIT -> service.deposit(viewer, plugin.economy().getBalance(viewer), this::refreshIfOpen);
            case WITHDRAW -> service.withdrawAll(viewer, this::refreshIfOpen);
            case TRANSFER -> service.transferAll(viewer, targetUuid, targetName, this::refreshIfOpen);
            default -> {
            }
        }
    }

    private void back() {
        switch (operation) {
            case TRANSFER -> new BankPlayerListMenu(plugin, module, viewer,
                    "bank.gui.transfer-title", "bank.gui.transfer-player-lore",
                    (v, target) -> new BankAmountMenu(plugin, module, v,
                            Operation.TRANSFER, target.getUniqueId(), target.getName()).open()).open();
            case ADMIN_SET, ADMIN_ADD, ADMIN_SUB ->
                    new BankAdminActionMenu(plugin, module, viewer, targetUuid, targetName).open();
            default -> new BankMainMenu(plugin, module, viewer).open();
        }
    }

    private void refreshIfOpen() {
        if (!viewer.isOnline()) {
            return;
        }
        if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
            refresh();
        }
    }
}
