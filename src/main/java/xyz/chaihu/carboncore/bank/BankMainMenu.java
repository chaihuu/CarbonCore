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

import org.bukkit.Material;
import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.ui.menu.Menu;
import xyz.chaihu.carboncore.util.ItemBuilder;

/**
 * Bank main screen (27 slots): deposit / withdraw / balance / transfer / admin / close.
 */
public final class BankMainMenu extends Menu {

    private final BankModule module;

    public BankMainMenu(CarbonCorePlugin plugin, BankModule module, Player viewer) {
        super(plugin, viewer, 27, plugin.messages().raw("bank.menu-title"));
        this.module = module;
    }

    @Override
    public void build() {
        fillRow(0, ItemBuilder.pane());
        fillRow(2, ItemBuilder.pane());

        set(11, new ItemBuilder(Material.GOLD_INGOT)
                        .name(plugin.messages().raw("bank.gui.deposit"))
                        .lore(plugin.messages().raw("bank.gui.deposit-lore"))
                        .build(),
                (event, player, slot) -> new BankAmountMenu(plugin, module, viewer,
                        BankAmountMenu.Operation.DEPOSIT, null, null).open());

        set(13, new ItemBuilder(Material.CLOCK)
                        .name(plugin.messages().raw("bank.gui.balance"))
                        .lore(plugin.messages().raw("bank.gui.balance-lore"))
                        .build(),
                (event, player, slot) -> new BankBalanceMenu(plugin, module, viewer).open());

        set(15, new ItemBuilder(Material.REDSTONE)
                        .name(plugin.messages().raw("bank.gui.withdraw"))
                        .lore(plugin.messages().raw("bank.gui.withdraw-lore"))
                        .build(),
                (event, player, slot) -> new BankAmountMenu(plugin, module, viewer,
                        BankAmountMenu.Operation.WITHDRAW, null, null).open());

        set(19, new ItemBuilder(Material.GOLD_NUGGET)
                        .name(plugin.messages().raw("bank.gui.loan"))
                        .lore(plugin.messages().raw("bank.gui.loan-lore"))
                        .build(),
                (event, player, slot) -> LoanMenu.openMain(plugin, module, viewer));

        set(22, new ItemBuilder(Material.PAPER)
                        .name(plugin.messages().raw("bank.gui.transfer"))
                        .lore(plugin.messages().raw("bank.gui.transfer-lore"))
                        .build(),
                (event, player, slot) -> new BankPlayerListMenu(plugin, module, viewer,
                        "bank.gui.transfer-title", "bank.gui.transfer-player-lore",
                        (v, target) -> new BankAmountMenu(plugin, module, v,
                                BankAmountMenu.Operation.TRANSFER,
                                target.getUniqueId(), target.getName()).open()).open());

        if (viewer.hasPermission("carboncore.bank.admin.give")) {
            set(25, new ItemBuilder(Material.ANVIL)
                            .name(plugin.messages().raw("bank.gui.admin"))
                            .lore(plugin.messages().raw("bank.gui.admin-lore"))
                            .build(),
                    (event, player, slot) -> new BankPlayerListMenu(plugin, module, viewer,
                            "bank.gui.admin-player-title", "bank.gui.admin-lore",
                            (v, target) -> new BankAdminActionMenu(plugin, module, v,
                                    target.getUniqueId(), target.getName()).open()).open());
        }

        set(26, new ItemBuilder(Material.BARRIER)
                        .name(plugin.messages().raw("general.close"))
                        .build(),
                (event, player, slot) -> viewer.closeInventory());
    }
}
