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

import java.util.ArrayList;
import java.util.List;

/**
 * Balance screen: shows the wallet balance, the bank balance and the configured
 * interest rate / settlement period.
 */
public final class BankBalanceMenu extends Menu {

    private final BankModule module;

    public BankBalanceMenu(CarbonCorePlugin plugin, BankModule module, Player viewer) {
        super(plugin, viewer, 27, plugin.messages().raw("bank.menu-title"));
        this.module = module;
    }

    @Override
    public void build() {
        fillRow(0, ItemBuilder.pane());
        fillRow(2, ItemBuilder.pane());

        double wallet = plugin.economy().getBalance(viewer);
        double bank = module.service().cachedBankBalance(viewer.getUniqueId());
        double rate = plugin.configManager().getDouble("modules.bank.interest.rate", 0.01);
        int periodHours = plugin.configManager().getInt("modules.bank.interest.period-hours", 24);

        List<String> lore = new ArrayList<>();
        lore.add(plugin.messages().format("bank.balance", "balance", plugin.economy().format(wallet)));
        lore.add(plugin.messages().format("bank.bank-balance", "balance", plugin.economy().format(bank)));
        lore.add("&f" + BankService.round2(rate * 100.0) + "% / " + periodHours + "h");

        set(13, new ItemBuilder(Material.CLOCK)
                        .name(plugin.messages().raw("bank.gui.balance"))
                        .lore(lore)
                        .build());

        set(18, new ItemBuilder(Material.ARROW)
                        .name(plugin.messages().raw("general.back"))
                        .build(),
                (event, player, slot) -> new BankMainMenu(plugin, module, viewer).open());

        set(26, new ItemBuilder(Material.BARRIER)
                        .name(plugin.messages().raw("general.close"))
                        .build(),
                (event, player, slot) -> viewer.closeInventory());
    }
}
