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
package xyz.chaihu.carboncore.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.util.Locale;

/**
 * PlaceholderAPI expansion. All values are served from in-memory caches kept
 * by the module services, so placeholder requests never hit the database.
 */
public final class CarbonCoreExpansion extends PlaceholderExpansion {

    private final CarbonCorePlugin plugin;

    public CarbonCoreExpansion(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "carboncore";
    }

    @Override
    public String getAuthor() {
        return "chaihu";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return plugin.messages().raw("placeholder.not-available");
        }
        String p = params.toLowerCase(Locale.ROOT);
        switch (p) {
            case "wallet" -> {
                return plugin.economy().format(plugin.economy().getBalance(player));
            }
            case "bank" -> {
                if (plugin.bankModule() == null || !plugin.configManager().isModuleEnabled("bank")) {
                    return plugin.messages().raw("placeholder.not-available");
                }
                return plugin.economy().format(plugin.bankModule().service().cachedBankBalance(player.getUniqueId()));
            }
            case "credit" -> {
                if (plugin.bankModule() == null || plugin.bankModule().loans() == null
                        || !plugin.configManager().isModuleEnabled("bank")) {
                    return plugin.messages().raw("placeholder.not-available");
                }
                return String.valueOf(plugin.bankModule().loans().cachedCreditScore(player.getUniqueId()));
            }
            case "debt" -> {
                if (plugin.bankModule() == null || plugin.bankModule().loans() == null
                        || !plugin.configManager().isModuleEnabled("bank")) {
                    return plugin.messages().raw("placeholder.not-available");
                }
                return plugin.economy().format(plugin.bankModule().loans().cachedDebt(player.getUniqueId()));
            }
            case "wish_total" -> {
                if (plugin.wishModule() == null || !plugin.configManager().isModuleEnabled("wish")) {
                    return plugin.messages().raw("placeholder.not-available");
                }
                return String.valueOf(plugin.wishModule().service().cachedTotalPulls(player.getUniqueId()));
            }
            case "wish_pity" -> {
                if (plugin.wishModule() == null || !plugin.configManager().isModuleEnabled("wish")) {
                    return plugin.messages().raw("placeholder.not-available");
                }
                return String.valueOf(plugin.wishModule().service().cachedSinceRare(player.getUniqueId()));
            }
            case "quest_done" -> {
                if (plugin.questModule() == null || !plugin.configManager().isModuleEnabled("quest")) {
                    return plugin.messages().raw("placeholder.not-available");
                }
                return String.valueOf(plugin.questModule().service().completedCount(player.getUniqueId()));
            }
            case "redpacket_open" -> {
                if (plugin.redPacketModule() == null || !plugin.configManager().isModuleEnabled("redpacket")) {
                    return plugin.messages().raw("placeholder.not-available");
                }
                return String.valueOf(plugin.redPacketModule().service().claimableCount(player.getUniqueId()));
            }
            default -> {
                return null;
            }
        }
    }
}
