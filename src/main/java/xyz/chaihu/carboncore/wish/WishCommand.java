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
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /wish command: opens the menu, draws, or prints history / pool to chat.
 */
public final class WishCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 10;
    private static final List<String> SUBS = List.of("draw", "ten", "history", "pool");

    private final CarbonCorePlugin plugin;
    private final WishModule module;

    public WishCommand(CarbonCorePlugin plugin, WishModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.only-player");
            return true;
        }
        if (!player.hasPermission("carboncore.wish")) {
            plugin.messages().sendPlayer(player, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            if (!player.hasPermission("carboncore.wish.menu")) {
                plugin.messages().sendPlayer(player, "general.no-permission");
                return true;
            }
            module.openMainMenu(player);
            return true;
        }

        WishService service = module.service();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "draw" -> {
                if (!player.hasPermission("carboncore.wish.draw")) {
                    plugin.messages().sendPlayer(player, "general.no-permission");
                    return true;
                }
                service.draw(player, 1);
            }
            case "ten", "10" -> {
                if (!player.hasPermission("carboncore.wish.draw")) {
                    plugin.messages().sendPlayer(player, "general.no-permission");
                    return true;
                }
                service.draw(player, 10);
            }
            case "history" -> {
                if (!player.hasPermission("carboncore.wish.history")) {
                    plugin.messages().sendPlayer(player, "general.no-permission");
                    return true;
                }
                handleHistory(player, service, args);
            }
            case "pool" -> handlePool(player, service);
            default -> plugin.messages().sendPlayer(player, "general.unknown-subcommand", "input", args[0]);
        }
        return true;
    }

    private void handleHistory(Player player, WishService service, String[] args) {
        int page = 1;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                plugin.messages().sendPlayer(player, "general.usage", "usage", "/wish history [page]");
                return;
            }
            if (page < 1) {
                page = 1;
            }
        }
        final int finalPage = page;
        service.history(player.getUniqueId(), finalPage * PAGE_SIZE)
                .thenAcceptAsync(rows -> {
                    int start = (finalPage - 1) * PAGE_SIZE;
                    if (rows.isEmpty() || start >= rows.size()) {
                        plugin.messages().sendPlayer(player, "wish.history-empty");
                        return;
                    }
                    int end = Math.min(start + PAGE_SIZE, rows.size());
                    for (int i = start; i < end; i++) {
                        WishService.HistoryRow row = rows.get(i);
                        plugin.messages().sendPlayer(player, "wish.history-entry",
                                "index", i + 1,
                                "time", WishService.formatTime(row.time()),
                                "result", row.entryName());
                    }
                }, run -> Bukkit.getScheduler().runTask(plugin, run))
                .exceptionally(err -> {
                    plugin.getLogger().warning("Wish history query failed for " + player.getName()
                            + ": " + err.getMessage());
                    return null;
                });
    }

    private void handlePool(Player player, WishService service) {
        if (service.pool().isEmpty()) {
            plugin.messages().sendPlayer(player, "general.empty");
            return;
        }
        for (WishEntry entry : service.pool()) {
            plugin.messages().sendPlayer(player, "wish.gui.pool-entry", "name", entry.display());
            plugin.messages().sendPlayer(player, "wish.gui.pool-entry-lore",
                    "weight", entry.weight(), "srare", String.valueOf(entry.rare()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String sub : SUBS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            return List.of("1", "2", "3");
        }
        return List.of();
    }
}
