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

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.ui.menu.Menu;
import xyz.chaihu.carboncore.util.ItemBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Paged chest GUI listing all enabled quests. The quest area occupies slots
 * 11-34 (24 entries per page); navigation and close sit in the bottom row.
 */
public final class QuestMenu extends Menu {

    private static final int FIRST_SLOT = 11;
    private static final int LAST_SLOT = 34;
    private static final int PER_PAGE = LAST_SLOT - FIRST_SLOT + 1;

    private final QuestService service;
    private int page = 0;

    public QuestMenu(CarbonCorePlugin plugin, Player viewer, QuestService service) {
        super(plugin, viewer, 54, plugin.messages().raw("quest.gui.title"));
        this.service = service;
    }

    @Override
    public void build() {
        ItemStack pane = ItemBuilder.pane();
        fillRow(0, pane);
        fillRow(5, pane);

        List<Quest> quests = service.quests();
        int pages = Math.max(1, (quests.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }
        int start = page * PER_PAGE;

        if (quests.isEmpty()) {
            set(22, new ItemBuilder(Material.BOOK)
                    .name(plugin.messages().raw("general.empty"))
                    .build());
        }

        for (int i = 0; i < PER_PAGE && start + i < quests.size(); i++) {
            Quest quest = quests.get(start + i);
            int slot = FIRST_SLOT + i;
            QuestService.State state = service.state(viewer.getUniqueId(), quest);
            boolean claimable = !state.claimed() && state.progress() >= quest.goal();

            ItemBuilder builder = new ItemBuilder(iconFor(quest.type())).name(quest.display());
            if (claimable) {
                builder.glow();
            }
            String progressLine = plugin.messages().format("quest.gui.progress-bar",
                    "bar", service.progressBar(state.progress(), quest.goal()),
                    "progress", state.progress(),
                    "goal", quest.goal());
            String lore = plugin.messages().format("quest.gui.quest-lore",
                    "desc", quest.description(),
                    "type", quest.type().name(),
                    "reset", service.resetText(quest),
                    "progress-line", progressLine,
                    "status", service.statusText(quest, state));
            List<String> loreLines = new ArrayList<>();
            loreLines.add(lore);
            if (claimable) {
                loreLines.add(plugin.messages().raw("quest.gui.claim"));
            } else if (!state.claimed()) {
                loreLines.add(plugin.messages().raw("quest.gui.claim-locked"));
            }
            builder.lore(loreLines);

            set(slot, builder.build(), (event, player, clickedSlot) -> {
                if (claimable) {
                    service.claim(player, quest.id());
                    refresh();
                } else if (!state.claimed() && state.progress() < quest.goal()) {
                    plugin.messages().sendPlayer(player, "quest.not-complete");
                }
            });
        }

        if (page > 0) {
            set(45, new ItemBuilder(Material.ARROW)
                    .name(plugin.messages().raw("general.previous"))
                    .build(), (event, player, slot) -> {
                page--;
                refresh();
            });
        }
        set(49, new ItemBuilder(Material.BARRIER)
                .name(plugin.messages().raw("general.close"))
                .lore(plugin.messages().format("general.page-info",
                        "page", page + 1, "pages", pages))
                .build(), (event, player, slot) -> player.closeInventory());
        if (page < pages - 1) {
            set(53, new ItemBuilder(Material.ARROW)
                    .name(plugin.messages().raw("general.next"))
                    .build(), (event, player, slot) -> {
                page++;
                refresh();
            });
        }
    }

    private static Material iconFor(QuestType type) {
        return switch (type) {
            case KILL -> Material.IRON_SWORD;
            case MINE -> Material.DIAMOND_PICKAXE;
            case FISH -> Material.FISHING_ROD;
            case LOGIN -> Material.OAK_DOOR;
            case TRADE -> Material.EMERALD;
            case COMPLETE -> Material.BOOK;
        };
    }
}
