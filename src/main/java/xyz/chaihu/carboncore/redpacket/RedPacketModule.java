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

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.CarbonModule;

/**
 * Red packet module lifecycle: service creation, command registration,
 * async expiry scan and UI routing between the Java chest GUI and Bedrock forms.
 */
public final class RedPacketModule implements CarbonModule {

    private final CarbonCorePlugin plugin;

    private RedPacketService service;
    private RedPacketForm form;
    private BukkitTask expireTask;

    public RedPacketModule(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "redpacket";
    }

    @Override
    public void onEnable() {
        service = new RedPacketService(plugin);
        form = new RedPacketForm(plugin, service);
        service.restoreAsync();

        RedPacketCommand command = new RedPacketCommand(plugin, this);
        plugin.registerCommand("redpacket",
                plugin.configManager().moduleAliases("redpacket"), command, command);

        long period = service.expireCheckTicks();
        expireTask = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, service::scanExpired, period, period);
    }

    @Override
    public void onDisable() {
        if (expireTask != null) {
            expireTask.cancel();
            expireTask = null;
        }
    }

    @Override
    public void openMainMenu(Player player) {
        if (!player.hasPermission("carboncore.redpacket.menu")) {
            plugin.messages().sendPlayer(player, "general.no-permission");
            return;
        }
        if (plugin.platform().isBedrock(player) && plugin.formService() != null) {
            form.openMain(player);
        } else {
            new RedPacketMenu.Main(plugin, service, player).open();
        }
    }

    /** Routes to the claim list page (form on Bedrock, GUI on Java). */
    public void openClaimList(Player player) {
        if (plugin.platform().isBedrock(player) && plugin.formService() != null) {
            form.openClaimList(player);
        } else {
            new RedPacketMenu.ClaimList(plugin, service, player, 0).open();
        }
    }

    /** Routes to the "my packets" page (form on Bedrock, GUI on Java). */
    public void openMyPackets(Player player) {
        if (plugin.platform().isBedrock(player) && plugin.formService() != null) {
            form.openMy(player);
        } else {
            new RedPacketMenu.MyPackets(plugin, service, player, 0).open();
        }
    }

    public RedPacketService service() {
        return service;
    }
}
