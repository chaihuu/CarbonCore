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
package xyz.chaihu.carboncore.quest;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.CarbonModule;

/**
 * Quest module: tracks player progress (kill / mine / fish / login / trade /
 * complete), persists it per reset period in the quest_progress table and
 * grants rewards through a chest GUI (Java) or native forms (Bedrock).
 */
public final class QuestModule implements CarbonModule {

    private final CarbonCorePlugin plugin;
    private QuestService service;

    public QuestModule(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "quest";
    }

    /**
     * Quests work without Vault: only money rewards are skipped, item and
     * command rewards still apply (see CarbonCorePlugin's startup warning).
     */
    @Override
    public boolean requiresEconomy() {
        return false;
    }

    @Override
    public void onEnable() {
        service = new QuestService(plugin);
        int count = service.reload();
        plugin.getLogger().info("Quest module loaded " + count + " enabled quest(s).");
        Bukkit.getPluginManager().registerEvents(new QuestListener(service), plugin);
        QuestCommand command = new QuestCommand(plugin, this);
        plugin.registerCommand("quest", plugin.configManager().moduleAliases("quest"), command, command);
    }

    @Override
    public void onDisable() {
        // The quest module runs no repeating scheduler tasks; in-flight async
        // database writes are drained by DatabaseManager#shutdown() afterwards.
    }

    @Override
    public void openMainMenu(Player player) {
        if (plugin.platform().isBedrock(player) && plugin.formService() != null) {
            new QuestForm(plugin, service).open(player);
        } else {
            new QuestMenu(plugin, player, service).open();
        }
    }

    public QuestService service() {
        return service;
    }

    /** Re-reads quests.yml; returns the number of enabled quests. */
    public int reloadQuests() {
        return service.reload();
    }
}
