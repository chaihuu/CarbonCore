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
package xyz.chaihu.carboncore.wish;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.ui.menu.Menu;
import xyz.chaihu.carboncore.util.ItemBuilder;

/**
 * Main wish chest GUI (27 slots): single / ten draw buttons, pool and history
 * screens, a pity statistics item and a close button.
 */
public final class WishMenu extends Menu {

    private final WishService service;

    public WishMenu(CarbonCorePlugin plugin, WishService service, Player viewer) {
        super(plugin, viewer, 27, plugin.messages().raw("wish.gui.title"));
        this.service = service;
    }

    @Override
    public void build() {
        ItemStack pane = ItemBuilder.pane();
        for (int i = 0; i < size; i++) {
            set(i, pane);
        }

        set(11, new ItemBuilder(Material.ENDER_EYE)
                .name(plugin.messages().raw("wish.gui.draw1"))
                .lore(plugin.messages().format("wish.gui.draw1-lore",
                        "cost", plugin.economy().format(service.singleCost())))
                .build(),
                (event, player, slot) -> service.draw(player, 1,
                        results -> new WishResultMenu(plugin, player, results).open()));

        set(15, new ItemBuilder(Material.NETHER_STAR)
                .name(plugin.messages().raw("wish.gui.draw10"))
                .lore(plugin.messages().format("wish.gui.draw10-lore",
                        "cost", plugin.economy().format(service.tenCost())))
                .glow()
                .build(),
                (event, player, slot) -> service.draw(player, 10,
                        results -> new WishResultMenu(plugin, player, results).open()));

        set(13, new ItemBuilder(Material.BOOK)
                .name(plugin.messages().raw("wish.gui.pool"))
                .lore(plugin.messages().raw("wish.gui.pool-lore"))
                .build(),
                (event, player, slot) -> new WishPoolMenu(plugin, service, player, 0).open());

        // Note: wish.gui.history-lore is a duplicate key in messages.yml and resolves
        // to the history-row lore, so the history button intentionally has no lore.
        set(20, new ItemBuilder(Material.CLOCK)
                .name(plugin.messages().raw("wish.gui.history"))
                .build(),
                (event, player, slot) -> service.history(player.getUniqueId(), service.historySize())
                        .thenAcceptAsync(rows -> new WishHistoryMenu(plugin, service, player, 0, rows).open(),
                                run -> Bukkit.getScheduler().runTask(plugin, run))
                        .exceptionally(err -> {
                            plugin.getLogger().warning("Wish history query failed for "
                                    + player.getName() + ": " + err.getMessage());
                            return null;
                        }));

        set(26, new ItemBuilder(Material.BARRIER)
                .name(plugin.messages().raw("general.close"))
                .build(),
                (event, player, slot) -> player.closeInventory());

        var uuid = viewer.getUniqueId();
        ItemBuilder info = new ItemBuilder(Material.EXPERIENCE_BOTTLE)
                .name(plugin.messages().format("wish.total-pulls",
                        "count", service.cachedTotalPulls(uuid)));
        if (service.pityEnabled()) {
            int remaining = Math.max(0, service.pityThreshold() - service.cachedSinceRare(uuid));
            info.lore(plugin.messages().format("wish.pity-info", "remaining", remaining));
        }
        set(4, info.build());
    }
}
