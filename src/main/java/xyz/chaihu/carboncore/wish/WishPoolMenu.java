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
 * Paginated prize pool overview (54 slots): every entry with its icon,
 * weight and rare flag.
 */
public final class WishPoolMenu extends Menu {

    private static final int PAGE_SIZE = 27;

    private final WishService service;
    private final int page;

    public WishPoolMenu(CarbonCorePlugin plugin, WishService service, Player viewer, int page) {
        super(plugin, viewer, 54, plugin.messages().raw("wish.gui.pool"));
        this.service = service;
        this.page = Math.max(0, page);
    }

    @Override
    public void build() {
        ItemStack pane = ItemBuilder.pane();
        for (int i = 0; i < size; i++) {
            set(i, pane);
        }

        List<WishEntry> pool = service.pool();
        int pages = Math.max(1, (pool.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int start = Math.min(page * PAGE_SIZE, pool.size());
        int end = Math.min(start + PAGE_SIZE, pool.size());
        int slot = 9;
        for (int i = start; i < end; i++) {
            WishEntry entry = pool.get(i);
            ItemStack item = new ItemBuilder(entry.icon())
                    .name(plugin.messages().format("wish.gui.pool-entry", "name", entry.display()))
                    .lore(plugin.messages().format("wish.gui.pool-entry-lore",
                            "weight", entry.weight(), "srare", String.valueOf(entry.rare())))
                    .build();
            set(slot++, item);
        }

        set(45, new ItemBuilder(Material.ARROW)
                .name(plugin.messages().raw("general.previous"))
                .build(),
                (event, player, s) -> {
                    if (page > 0) {
                        new WishPoolMenu(plugin, service, player, page - 1).open();
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
                        new WishPoolMenu(plugin, service, player, page + 1).open();
                    }
                });
    }
}
