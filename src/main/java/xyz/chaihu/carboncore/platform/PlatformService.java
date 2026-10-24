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
package xyz.chaihu.carboncore.platform;

import org.bukkit.entity.Player;
import org.geysermc.geyser.api.GeyserApi;
import xyz.chaihu.carboncore.CarbonCorePlugin;

/**
 * Detects whether the server has Geyser and whether a given player connects
 * through it (Bedrock edition). Used to route UI between chest GUI and forms.
 */
public final class PlatformService {

    private final CarbonCorePlugin plugin;
    private final boolean geyserPresent;

    public PlatformService(CarbonCorePlugin plugin) {
        this.plugin = plugin;
        this.geyserPresent = plugin.getServer().getPluginManager().getPlugin("GeyserMC") != null
                && classPresent("org.geysermc.geyser.api.GeyserApi");
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public boolean isGeyserPresent() {
        return geyserPresent;
    }

    /** True when the player is connected via Geyser (Bedrock edition). */
    public boolean isBedrock(Player player) {
        if (!geyserPresent) {
            return false;
        }
        try {
            GeyserApi api = GeyserApi.api();
            return api != null && api.isBedrockPlayer(player.getUniqueId());
        } catch (Exception | LinkageError e) {
            // Geyser API not ready yet or class wiring issue -> treat as Java
            return false;
        }
    }
}
