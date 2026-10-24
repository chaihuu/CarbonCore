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
package xyz.chaihu.carboncore;

import org.bukkit.entity.Player;

/**
 * Lifecycle contract implemented by every CarbonCore feature module.
 */
public interface CarbonModule {

    /** Module id, matching the key under {@code modules.} in config.yml. */
    String name();

    /** Whether the module needs a Vault economy to work. */
    default boolean requiresEconomy() {
        return true;
    }

    /** Register commands, listeners and scheduled tasks. */
    void onEnable();

    /** Cancel tasks and flush state. */
    void onDisable();

    /** Open the module's main screen for a player (GUI on Java, form on Bedrock). */
    void openMainMenu(Player player);
}
