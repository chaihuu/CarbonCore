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

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.entity.Item;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Bridges gameplay events into {@link QuestService#advance}. All handlers run
 * on the main thread, which is exactly what the service expects.
 */
public final class QuestListener implements Listener {

    private final QuestService service;

    public QuestListener(QuestService service) {
        this.service = service;
    }

    @EventHandler
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        // Player-vs-player kills do not count.
        if (event.getEntity() instanceof Player) {
            return;
        }
        service.advance(killer, QuestType.KILL, event.getEntity().getType().name(), 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMine(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        service.advance(player, QuestType.MINE, event.getBlock().getType().name(), 1);
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (event.getCaught() instanceof Item caught) {
            service.advance(event.getPlayer(), QuestType.FISH, caught.getItemStack().getType().name(), 1);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.advance(event.getPlayer(), QuestType.LOGIN, "ANY", 1);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getType() != InventoryType.MERCHANT) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            service.advance(player, QuestType.TRADE, "ANY", 1);
        }
    }
}
