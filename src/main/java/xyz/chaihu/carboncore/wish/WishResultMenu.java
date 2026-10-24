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

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.ui.menu.Menu;
import xyz.chaihu.carboncore.util.ItemBuilder;

import java.util.List;

/**
 * Shows the drawn entries as a row of icons; clicking any of them closes the GUI.
 */
public final class WishResultMenu extends Menu {

    private final List<WishEntry> results;

    public WishResultMenu(CarbonCorePlugin plugin, Player viewer, List<WishEntry> results) {
        super(plugin, viewer, 27, plugin.messages().raw("wish.gui.result-title"));
        this.results = List.copyOf(results);
    }

    @Override
    public void build() {
        ItemStack pane = ItemBuilder.pane();
        for (int i = 0; i < size; i++) {
            set(i, pane);
        }
        int start = 13 - (results.size() - 1) / 2;
        for (int i = 0; i < results.size(); i++) {
            WishEntry entry = results.get(i);
            ItemStack item = new ItemBuilder(entry.icon())
                    .name(entry.display())
                    .lore(plugin.messages().raw("wish.gui.result-lore"))
                    .build();
            int slot = Math.min(start + i, size - 1);
            set(slot, item, (event, player, s) -> player.closeInventory());
        }
    }
}
