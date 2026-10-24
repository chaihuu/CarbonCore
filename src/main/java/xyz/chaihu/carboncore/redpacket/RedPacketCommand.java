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
package xyz.chaihu.carboncore.redpacket;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Command entry for the red packet module: opens the main UI without arguments
 * and exposes create / claim / code / list / my plus the admin force-expire.
 */
public final class RedPacketCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE_CREATE =
            "/redpacket create <amount> <count> [seconds] [message...]";
    private static final String USAGE_CLAIM = "/redpacket claim <id> [code]";
    private static final String USAGE_CODE = "/redpacket code <id> <code>";
    private static final String USAGE_EXPIRE = "/redpacket expire <id>";

    private final CarbonCorePlugin plugin;
    private final RedPacketModule module;

    public RedPacketCommand(CarbonCorePlugin plugin, RedPacketModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.only-player");
            return true;
        }
        if (!player.hasPermission("carboncore.redpacket")) {
            plugin.messages().sendPlayer(player, "general.no-permission");
            return true;
        }
        UUID uuid = player.getUniqueId();
        int cooldown = plugin.configManager().moduleCooldown("redpacket");
        if (!plugin.cooldowns().tryUse(uuid, "redpacket:cmd", cooldown)) {
            plugin.messages().sendPlayer(player, "general.cooldown",
                    "seconds", plugin.cooldowns().remaining(uuid, "redpacket:cmd", cooldown));
            return true;
        }
        if (args.length == 0) {
            module.openMainMenu(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> handleCreate(player, args);
            case "claim" -> handleClaim(player, args);
            case "code" -> handleCode(player, args);
            case "list" -> {
                if (checkPermission(player, "carboncore.redpacket.menu")) {
                    module.openClaimList(player);
                }
            }
            case "my" -> {
                if (checkPermission(player, "carboncore.redpacket.menu")) {
                    module.openMyPackets(player);
                }
            }
            case "expire" -> handleExpire(player, args);
            default -> {
                plugin.messages().sendPlayer(player, "general.unknown-subcommand", "input", args[0]);
                usage(player, "/redpacket [create|claim|code|list|my]");
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // sub commands
    // ------------------------------------------------------------------

    private void handleCreate(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.redpacket.create")) {
            return;
        }
        if (args.length < 3) {
            usage(player, USAGE_CREATE);
            return;
        }
        double total;
        try {
            total = Double.parseDouble(args[1]);
        } catch (NumberFormatException ex) {
            plugin.messages().sendPlayer(player, "general.invalid-amount", "input", args[1]);
            return;
        }
        int count;
        try {
            count = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            usage(player, USAGE_CREATE);
            return;
        }
        long seconds = module.service().defaultDuration();
        int messageStart = 3;
        if (args.length > 3) {
            try {
                seconds = Long.parseLong(args[3]);
                messageStart = 4;
            } catch (NumberFormatException ignored) {
                // args[3] is the beginning of the message, keep the default duration
            }
        }
        String message = null;
        if (messageStart < args.length) {
            message = String.join(" ", java.util.Arrays.copyOfRange(args, messageStart, args.length));
        }
        module.service().create(player, total, count, seconds, message, null);
    }

    private void handleClaim(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.redpacket.claim")) {
            return;
        }
        if (args.length < 2) {
            usage(player, USAGE_CLAIM);
            return;
        }
        int id = parseId(player, args[1], USAGE_CLAIM);
        if (id < 0) {
            return;
        }
        String code = args.length >= 3 ? args[2] : null;
        module.service().claim(player, id, code);
    }

    private void handleCode(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.redpacket.claim")) {
            return;
        }
        if (args.length < 3) {
            usage(player, USAGE_CODE);
            return;
        }
        int id = parseId(player, args[1], USAGE_CODE);
        if (id < 0) {
            return;
        }
        module.service().claim(player, id, args[2]);
    }

    private void handleExpire(Player player, String[] args) {
        if (!checkPermission(player, "carboncore.redpacket.admin")) {
            return;
        }
        if (args.length < 2) {
            usage(player, USAGE_EXPIRE);
            return;
        }
        int id = parseId(player, args[1], USAGE_EXPIRE);
        if (id < 0) {
            return;
        }
        module.service().forceExpire(id, player);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private boolean checkPermission(Player player, String node) {
        if (!player.hasPermission(node)) {
            plugin.messages().sendPlayer(player, "general.no-permission");
            return false;
        }
        return true;
    }

    private void usage(Player player, String usage) {
        plugin.messages().sendPlayer(player, "general.usage", "usage", usage);
    }

    private int parseId(Player player, String input, String usage) {
        try {
            int id = Integer.parseInt(input);
            if (id <= 0) {
                usage(player, usage);
                return -1;
            }
            return id;
        } catch (NumberFormatException ex) {
            usage(player, usage);
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (!player.hasPermission("carboncore.redpacket")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (player.hasPermission("carboncore.redpacket.create")) {
                subs.add("create");
            }
            if (player.hasPermission("carboncore.redpacket.claim")) {
                subs.add("claim");
                subs.add("code");
            }
            if (player.hasPermission("carboncore.redpacket.menu")) {
                subs.add("list");
                subs.add("my");
            }
            if (player.hasPermission("carboncore.redpacket.admin")) {
                subs.add("expire");
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("claim") || sub.equals("code") || sub.equals("expire")) {
                return filter(module.service().activeIds(), args[1]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String low = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(low)) {
                out.add(option);
            }
        }
        return out;
    }
}
