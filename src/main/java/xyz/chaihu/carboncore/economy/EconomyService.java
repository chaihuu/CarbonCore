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
package xyz.chaihu.carboncore.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import xyz.chaihu.carboncore.CarbonCorePlugin;

/**
 * Thin wrapper around the Vault Economy service. All money movement goes
 * through Vault so CarbonCore stays independent of the concrete economy plugin.
 */
public final class EconomyService {

    private final CarbonCorePlugin plugin;
    private Economy economy;

    public EconomyService(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void setup() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            economy = null;
            return;
        }
        RegisteredServiceProvider<Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        economy = rsp == null ? null : rsp.getProvider();
    }

    public boolean hasEconomy() {
        return economy != null;
    }

    public double getBalance(OfflinePlayer player) {
        if (economy == null) {
            return 0;
        }
        return economy.getBalance(player);
    }

    public boolean has(Player player, double amount) {
        if (economy == null) {
            return false;
        }
        return economy.has(player, amount);
    }

    /** Withdraws from the player's wallet. Returns false when funds are insufficient. */
    public boolean withdraw(Player player, double amount) {
        if (economy == null || amount < 0) {
            return false;
        }
        if (!economy.has(player, amount)) {
            return false;
        }
        EconomyResponse resp = economy.withdrawPlayer(player, amount);
        return resp.transactionSuccess();
    }

    /** Deposits into the player's wallet. */
    public boolean deposit(Player player, double amount) {
        if (economy == null || amount < 0) {
            return false;
        }
        EconomyResponse resp = economy.depositPlayer(player, amount);
        return resp.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null || amount < 0) {
            return false;
        }
        EconomyResponse resp = economy.depositPlayer(player, amount);
        return resp.transactionSuccess();
    }

    /** Formats an amount using the economy provider, falling back to the config symbol. */
    public String format(double amount) {
        if (economy != null) {
            try {
                return economy.format(amount);
            } catch (Exception ignored) {
                // some providers throw on formatting; fall through
            }
        }
        String symbol = plugin.configManager().getString("economy.currency-symbol", "$");
        return symbol + String.format("%.2f", amount);
    }
}
