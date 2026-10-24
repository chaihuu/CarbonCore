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
 * /quest [list | claim &lt;id&gt; | reload]
 */
public final class QuestCommand implements CommandExecutor, TabCompleter {

    private final CarbonCorePlugin plugin;
    private final QuestModule module;

    public QuestCommand(CarbonCorePlugin plugin, QuestModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.only-player");
            return true;
        }
        if (!player.hasPermission("carboncore.quest")) {
            plugin.messages().sendPlayer(player, "general.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                if (!player.hasPermission("carboncore.quest.menu")) {
                    plugin.messages().sendPlayer(player, "general.no-permission");
                    return true;
                }
                int cooldown = plugin.configManager().moduleCooldown("quest");
                if (!plugin.cooldowns().tryUse(player.getUniqueId(), "quest-menu", cooldown)) {
                    plugin.messages().sendPlayer(player, "general.cooldown",
                            "seconds", plugin.cooldowns().remaining(player.getUniqueId(), "quest-menu", cooldown));
                    return true;
                }
                module.openMainMenu(player);
            }
            case "claim" -> {
                if (!player.hasPermission("carboncore.quest.claim")) {
                    plugin.messages().sendPlayer(player, "general.no-permission");
                    return true;
                }
                if (args.length < 2) {
                    plugin.messages().sendPlayer(player, "general.usage",
                            "usage", "/" + label + " claim <id>");
                    return true;
                }
                module.service().claim(player, args[1]);
            }
            case "reload" -> {
                if (!player.hasPermission("carboncore.quest.admin")) {
                    plugin.messages().sendPlayer(player, "general.no-permission");
                    return true;
                }
                int count = module.reloadQuests();
                plugin.messages().sendPlayer(player, "admin.quests-reloaded", "count", count);
            }
            default -> plugin.messages().sendPlayer(player, "general.unknown-subcommand", "input", args[0]);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("list", "claim"));
            if (player.hasPermission("carboncore.quest.admin")) {
                subs.add("reload");
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("claim")
                && player.hasPermission("carboncore.quest.claim")) {
            List<String> ids = new ArrayList<>();
            for (Quest q : module.service().quests()) {
                ids.add(q.id());
            }
            return filter(ids, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(c);
            }
        }
        return out;
    }
}
