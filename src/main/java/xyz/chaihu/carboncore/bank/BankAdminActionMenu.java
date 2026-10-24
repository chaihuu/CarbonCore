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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Admin action chooser for one target account: set / add / subtract / clear.
 */
public final class BankAdminActionMenu extends Menu {

    private final BankModule module;
    private final UUID targetUuid;
    private final String targetName;

    public BankAdminActionMenu(CarbonCorePlugin plugin, BankModule module, Player viewer,
                               UUID targetUuid, String targetName) {
        super(plugin, viewer, 27, plugin.messages().raw("bank.gui.admin-action-title"));
        this.module = module;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
    }

    private Executor main() {
        return run -> Bukkit.getScheduler().runTask(plugin, run);
    }

    @Override
    public void build() {
        fillRow(0, ItemBuilder.pane());

        // Target player displayed as a head in the centre of the top border.
        set(4, SkullTextureManager.playerHead(
                Bukkit.getOfflinePlayer(targetUuid),
                "&f" + targetName,
                List.of(plugin.messages().raw("bank.gui.admin-title"))));

        set(11, new ItemBuilder(Material.DIAMOND)
                        .name(plugin.messages().raw("bank.gui.admin-set"))
                        .lore(plugin.messages().raw("bank.gui.admin-set-lore"))
                        .build(),
                (event, player, slot) -> new BankAmountMenu(plugin, module, viewer,
                        BankAmountMenu.Operation.ADMIN_SET, targetUuid, targetName).open());

        set(13, new ItemBuilder(Material.EMERALD)
                        .name(plugin.messages().raw("bank.gui.admin-add"))
                        .lore(plugin.messages().raw("bank.gui.admin-add-lore"))
                        .build(),
                (event, player, slot) -> new BankAmountMenu(plugin, module, viewer,
                        BankAmountMenu.Operation.ADMIN_ADD, targetUuid, targetName).open());

        set(15, new ItemBuilder(Material.REDSTONE)
                        .name(plugin.messages().raw("bank.gui.admin-sub"))
                        .lore(plugin.messages().raw("bank.gui.admin-sub-lore"))
                        .build(),
                (event, player, slot) -> new BankAmountMenu(plugin, module, viewer,
                        BankAmountMenu.Operation.ADMIN_SUB, targetUuid, targetName).open());

        set(22, new ItemBuilder(Material.TNT)
                        .name(plugin.messages().raw("bank.gui.admin-clear"))
                        .lore(plugin.messages().raw("bank.gui.admin-clear-lore"))
                        .build(),
                (event, player, slot) -> module.service().adminClear(targetUuid, targetName)
                        .thenAcceptAsync(bal -> {
                            if (viewer.isOnline()) {
                                plugin.messages().sendPlayer(viewer, "bank.admin-clear-success", "player", targetName);
                            }
                            refreshIfOpen();
                        }, main()));

        set(18, new ItemBuilder(Material.ARROW)
                        .name(plugin.messages().raw("general.back"))
                        .build(),
                (event, player, slot) -> new BankPlayerListMenu(plugin, module, viewer,
                        "bank.gui.admin-player-title", "bank.gui.admin-lore",
                        (v, target) -> new BankAdminActionMenu(plugin, module, v,
                                target.getUniqueId(), target.getName()).open()).open());

        set(26, new ItemBuilder(Material.BARRIER)
                        .name(plugin.messages().raw("general.close"))
                        .build(),
                (event, player, slot) -> viewer.closeInventory());
    }

    private void refreshIfOpen() {
        if (!viewer.isOnline()) {
            return;
        }
        if (viewer.getOpenInventory().getTopInventory().getHolder() == this) {
            refresh();
        }
    }
}
