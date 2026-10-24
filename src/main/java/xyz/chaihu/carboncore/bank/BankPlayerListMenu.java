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
package xyz.chaihu.carboncore.bank;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.ui.menu.Menu;
import xyz.chaihu.carboncore.util.ItemBuilder;
import xyz.chaihu.carboncore.util.SkullTextureManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Paginated online-player picker shared by the transfer and admin flows.
 * 54-slot layout in the LiteSignIn style: gray glass-pane borders on rows 0
 * and 5, a calendar-style head grid (28 per page) on rows 1-4, and the
 * navigation strip on the bottom row.
 */
public final class BankPlayerListMenu extends Menu {

    public interface PlayerPick {
        void pick(Player viewer, Player target);
    }

    /** Rows 1-4, columns 1-7: calendar grid, 28 heads per page. */
    private static final int[] GRID_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int PAGE_SIZE = GRID_SLOTS.length;

    private final BankModule module;
    private final String loreKey;
    private final PlayerPick pick;
    private int page = 0;

    public BankPlayerListMenu(CarbonCorePlugin plugin, BankModule module, Player viewer,
                              String titleKey, String loreKey, PlayerPick pick) {
        super(plugin, viewer, 54, plugin.messages().raw(titleKey));
        this.module = module;
        this.loreKey = loreKey;
        this.pick = pick;
    }

    @Override
    public void build() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (players.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }

        fillRow(0, ItemBuilder.pane());
        fillRow(5, ItemBuilder.pane());

        if (players.isEmpty()) {
            set(22, new ItemBuilder(Material.BARRIER)
                    .name(plugin.messages().raw("general.empty")).build());
        } else {
            int from = page * PAGE_SIZE;
            int to = Math.min(players.size(), from + PAGE_SIZE);
            for (int i = from; i < to; i++) {
                final Player target = players.get(i);
                List<String> lore = new ArrayList<>();
                lore.add(plugin.messages().raw(loreKey));
                double balance = module.service().cachedBankBalance(target.getUniqueId());
                lore.add("&7银行余额: &f" + plugin.economy().format(balance));
                set(GRID_SLOTS[i - from],
                        SkullTextureManager.playerHead(target, "&f" + target.getName(), lore),
                        (event, player, slot) -> pick.pick(viewer, target));
            }
        }

        set(45, new ItemBuilder(Material.ARROW)
                        .name(plugin.messages().raw("general.back"))
                        .build(),
                (event, player, slot) -> new BankMainMenu(plugin, module, viewer).open());

        if (page > 0) {
            set(47, new ItemBuilder(Material.ARROW)
                            .name(plugin.messages().raw("general.previous"))
                            .build(),
                    (event, player, slot) -> {
                        page--;
                        refresh();
                    });
        }

        set(49, new ItemBuilder(Material.PAPER)
                .name(plugin.messages().format("general.page-info",
                        "page", page + 1, "pages", pages)).build());

        if (page < pages - 1) {
            set(51, new ItemBuilder(Material.ARROW)
                            .name(plugin.messages().raw("general.next"))
                            .build(),
                    (event, player, slot) -> {
                        page++;
                        refresh();
                    });
        }

        set(53, new ItemBuilder(Material.BARRIER)
                        .name(plugin.messages().raw("general.close"))
                        .build(),
                (event, player, slot) -> viewer.closeInventory());
    }
}
