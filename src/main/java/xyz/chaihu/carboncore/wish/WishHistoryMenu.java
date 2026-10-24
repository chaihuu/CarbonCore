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

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.ui.menu.Menu;
import xyz.chaihu.carboncore.util.ItemBuilder;

import java.util.List;

/**
 * Paginated wish history (54 slots), newest first. The full row list is loaded
 * once before opening and paged in memory.
 */
public final class WishHistoryMenu extends Menu {

    private static final int PAGE_SIZE = 10;

    private final WishService service;
    private final int page;
    private final List<WishService.HistoryRow> rows;

    public WishHistoryMenu(CarbonCorePlugin plugin, WishService service, Player viewer,
                           int page, List<WishService.HistoryRow> rows) {
        super(plugin, viewer, 54, plugin.messages().raw("wish.gui.history-title"));
        this.service = service;
        this.page = Math.max(0, page);
        this.rows = List.copyOf(rows);
    }

    @Override
    public void build() {
        ItemStack pane = ItemBuilder.pane();
        for (int i = 0; i < size; i++) {
            set(i, pane);
        }

        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int start = Math.min(page * PAGE_SIZE, rows.size());
        int end = Math.min(start + PAGE_SIZE, rows.size());
        if (rows.isEmpty()) {
            set(13, new ItemBuilder(Material.BARRIER)
                    .name(plugin.messages().raw("general.empty"))
                    .build());
        }
        int slot = 10;
        for (int i = start; i < end; i++) {
            WishService.HistoryRow row = rows.get(i);
            ItemStack item = new ItemBuilder(Material.PAPER)
                    .name(row.entryName())
                    .lore(plugin.messages().format("wish.gui.history-lore",
                            "time", WishService.formatTime(row.time()),
                            "result", row.entryName()))
                    .build();
            set(slot++, item);
        }

        set(45, new ItemBuilder(Material.ARROW)
                .name(plugin.messages().raw("general.previous"))
                .build(),
                (event, player, s) -> {
                    if (page > 0) {
                        new WishHistoryMenu(plugin, service, player, page - 1, rows).open();
                    }
                });

        set(51, new ItemBuilder(Material.PAPER)
                .name(plugin.messages().format("general.page-info",
                        "page", page + 1, "pages", pages))
                .build());

        set(49, new ItemBuilder(Material.BARRIER)
                .name(plugin.messages().raw("general.back"))
                .build(),
                (event, player, s) -> new WishMenu(plugin, service, player).open());

        set(53, new ItemBuilder(Material.ARROW)
                .name(plugin.messages().raw("general.next"))
                .build(),
                (event, player, s) -> {
                    if (page + 1 < pages) {
                        new WishHistoryMenu(plugin, service, player, page + 1, rows).open();
                    }
                });
    }
}
