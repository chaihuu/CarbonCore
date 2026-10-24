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
import xyz.chaihu.carboncore.CarbonModule;

/**
 * Bank module: save private money in a bank account, withdraw, transfer between
 * players and earn periodic interest. Java players get a chest GUI, Bedrock
 * players (Geyser) get native forms.
 */
public class BankModule implements CarbonModule {

    private final CarbonCorePlugin plugin;
    private BankService service;
    private LoanService loanService;
    private BankCommand command;
    private BankListener listener;
    private BankForm form;

    public BankModule(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "bank";
    }

    @Override
    public void onEnable() {
        service = new BankService(plugin);
        service.loadCache();
        loanService = new LoanService(plugin);
        if (loanService.enabled()) {
            loanService.loadCache();
            for (Player online : Bukkit.getOnlinePlayers()) {
                loanService.ensureCredit(online.getUniqueId());
            }
            loanService.startTask();
        }
        command = new BankCommand(plugin, this);
        listener = new BankListener(service);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        form = new BankForm(plugin, this);
        service.startInterestTask();
        plugin.registerCommand("bank", plugin.configManager().moduleAliases("bank"), command, command);
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.shutdown();
        }
        if (loanService != null) {
            loanService.shutdown();
        }
    }

    @Override
    public void openMainMenu(Player player) {
        if (plugin.platform().isBedrock(player) && plugin.formService() != null) {
            form.openMain(player);
        } else {
            new BankMainMenu(plugin, this, player).open();
        }
    }

    public BankService service() {
        return service;
    }

    public LoanService loans() {
        return loanService;
    }
}
