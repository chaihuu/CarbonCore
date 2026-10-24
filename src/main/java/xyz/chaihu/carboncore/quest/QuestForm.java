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

import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedrock UI: a SimpleForm listing every enabled quest; clicking one opens a
 * ModalForm with details and a claim button. Claim results are sent as chat
 * messages by the service.
 */
public final class QuestForm {

    private final CarbonCorePlugin plugin;
    private final QuestService service;

    public QuestForm(CarbonCorePlugin plugin, QuestService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void open(Player player) {
        List<Quest> quests = service.quests();
        String title = Text.plain(plugin.messages().raw("quest.form.title"));
        if (quests.isEmpty()) {
            plugin.formService().sendNotice(player, title,
                    Text.plain(plugin.messages().raw("general.empty")));
            return;
        }
        List<String> buttons = new ArrayList<>();
        for (Quest quest : quests) {
            QuestService.State state = service.state(player.getUniqueId(), quest);
            buttons.add(Text.plain(quest.display()) + "\n"
                    + Text.plain(plugin.messages().format("quest.progress",
                    "progress", state.progress(), "goal", quest.goal())) + "\n"
                    + Text.plain(service.statusText(quest, state)));
        }
        plugin.formService().sendSimple(player, title, "", buttons, (p, index) -> {
            if (index >= 0 && index < quests.size()) {
                openDetail(p, quests.get(index));
            }
        });
    }

    private void openDetail(Player player, Quest quest) {
        QuestService.State state = service.state(player.getUniqueId(), quest);
        StringBuilder content = new StringBuilder();
        content.append(Text.plain(quest.display())).append('\n');
        if (!quest.description().isEmpty()) {
            content.append(Text.plain(quest.description())).append('\n');
        }
        content.append(Text.plain(plugin.messages().format("quest.progress",
                "progress", state.progress(), "goal", quest.goal()))).append('\n');
        content.append(Text.plain(service.statusText(quest, state)))
                .append(" (").append(Text.plain(service.resetText(quest))).append(")\n");
        if (quest.money() > 0 && plugin.economy().hasEconomy()) {
            content.append(Text.plain(plugin.messages().format("quest.reward-money",
                    "money", plugin.economy().format(quest.money())))).append('\n');
        }
        for (Quest.ItemReward reward : quest.items()) {
            content.append(Text.plain(plugin.messages().format("quest.reward-item",
                    "name", reward.material().name(), "amount", reward.amount()))).append('\n');
        }
        if (!quest.commands().isEmpty()) {
            content.append(Text.plain(plugin.messages().raw("quest.reward-command"))).append('\n');
        }
        boolean claimable = !state.claimed() && state.progress() >= quest.goal();
        if (claimable) {
            content.append(Text.plain(plugin.messages().raw("quest.form.claim-confirm")));
        }
        plugin.formService().sendModal(player,
                Text.plain(plugin.messages().raw("quest.form.detail-title")),
                content.toString(),
                Text.plain(plugin.messages().raw("general.confirm")),
                Text.plain(plugin.messages().raw("general.cancel")),
                (p, confirmed) -> {
                    if (confirmed) {
                        service.claim(p, quest.id());
                    } else {
                        open(p);
                    }
                });
    }
}
