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
import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.config.Messages;
import xyz.chaihu.carboncore.util.Text;

import java.util.List;

/**
 * Bedrock form UI for the wish module. All texts are stripped of & color codes
 * via {@link Text#plain(String)} because Bedrock forms do not render them.
 */
public final class WishForm {

    private WishForm() {
    }

    /** Main SimpleForm: single draw / ten draw / pool / history. */
    public static void openMain(CarbonCorePlugin plugin, WishService service, Player player) {
        Messages messages = plugin.messages();
        String title = Text.plain(messages.raw("wish.form.title"));

        StringBuilder content = new StringBuilder();
        content.append(Text.plain(messages.format("wish.total-pulls",
                "count", service.cachedTotalPulls(player.getUniqueId()))));
        if (service.pityEnabled()) {
            int remaining = Math.max(0, service.pityThreshold() - service.cachedSinceRare(player.getUniqueId()));
            content.append("\n").append(Text.plain(messages.format("wish.pity-info",
                    "remaining", remaining)));
        }

        List<String> buttons = List.of(
                Text.plain(messages.format("wish.draw-single",
                        "cost", plugin.economy().format(service.singleCost()))),
                Text.plain(messages.format("wish.draw-ten",
                        "cost", plugin.economy().format(service.tenCost()))),
                Text.plain(messages.raw("wish.form.pool-title")),
                Text.plain(messages.raw("wish.form.history-title")));

        plugin.formService().sendSimple(player, title, content.toString(), buttons, (p, index) -> {
            switch (index) {
                case 0 -> service.draw(p, 1, results -> showResult(plugin, p, results));
                case 1 -> service.draw(p, 10, results -> showResult(plugin, p, results));
                case 2 -> showPool(plugin, p, service);
                default -> showHistory(plugin, p, service);
            }
        });
    }

    /** Draw result notice. */
    public static void showResult(CarbonCorePlugin plugin, Player player, List<WishEntry> results) {
        String title = Text.plain(plugin.messages().raw("wish.form.result-title"));
        String body = Text.plain(WishService.summarize(results));
        plugin.formService().sendNotice(player, title, body);
    }

    /** Prize pool notice: name + weight + rare flag per entry. */
    public static void showPool(CarbonCorePlugin plugin, Player player, WishService service) {
        Messages messages = plugin.messages();
        StringBuilder body = new StringBuilder();
        for (WishEntry entry : service.pool()) {
            if (body.length() > 0) {
                body.append("\n");
            }
            body.append(Text.plain(messages.format("wish.gui.pool-entry", "name", entry.display())))
                    .append("\n")
                    .append(Text.plain(messages.format("wish.gui.pool-entry-lore",
                            "weight", entry.weight(), "srare", String.valueOf(entry.rare()))));
        }
        if (body.length() == 0) {
            body.append(Text.plain(messages.raw("general.empty")));
        }
        plugin.formService().sendNotice(player,
                Text.plain(messages.raw("wish.form.pool-title")), body.toString());
    }

    /** History notice: the 20 most recent pulls, newest first. */
    public static void showHistory(CarbonCorePlugin plugin, Player player, WishService service) {
        Messages messages = plugin.messages();
        String title = Text.plain(messages.raw("wish.form.history-title"));
        service.history(player.getUniqueId(), 20)
                .thenAcceptAsync(rows -> {
                    String body;
                    if (rows.isEmpty()) {
                        body = Text.plain(messages.raw("wish.history-empty"));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < rows.size(); i++) {
                            if (i > 0) {
                                sb.append("\n");
                            }
                            WishService.HistoryRow row = rows.get(i);
                            sb.append(Text.plain(messages.format("wish.history-entry",
                                    "index", i + 1,
                                    "time", WishService.formatTime(row.time()),
                                    "result", row.entryName())));
                        }
                        body = sb.toString();
                    }
                    plugin.formService().sendNotice(player, title, body);
                }, run -> Bukkit.getScheduler().runTask(plugin, run))
                .exceptionally(err -> {
                    plugin.getLogger().warning("Wish history query failed for "
                            + player.getName() + ": " + err.getMessage());
                    return null;
                });
    }
}
