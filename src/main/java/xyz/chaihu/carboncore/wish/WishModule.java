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

import org.bukkit.Material;
import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.CarbonModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wish (gacha) module: parses the prize pool from config.yml, registers the
 * /wish command and routes the main screen between the Java chest GUI and the
 * Bedrock form.
 */
public final class WishModule implements CarbonModule {

    private final CarbonCorePlugin plugin;
    private WishService service;
    private WishCommand command;

    public WishModule(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "wish";
    }

    @Override
    public void onEnable() {
        List<WishEntry> pool = loadPool();
        service = new WishService(plugin, pool);
        service.loadPityCache();
        command = new WishCommand(plugin, this);
        plugin.registerCommand("wish", plugin.configManager().moduleAliases("wish"), command, command);
    }

    @Override
    public void onDisable() {
        // The wish module schedules no repeating Bukkit tasks and holds no async
        // handles of its own: all database work runs on the shared DatabaseManager
        // executor, which CarbonCorePlugin shuts down during its own onDisable.
    }

    @Override
    public void openMainMenu(Player player) {
        if (plugin.platform().isBedrock(player) && plugin.formService() != null) {
            WishForm.openMain(plugin, service, player);
        } else {
            new WishMenu(plugin, service, player).open();
        }
    }

    public WishService service() {
        return service;
    }

    // ------------------------------------------------------------------
    // Config parsing
    // ------------------------------------------------------------------

    private List<WishEntry> loadPool() {
        List<WishEntry> out = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("modules.wish.pool")) {
            WishEntry entry = parseEntry(raw);
            if (entry != null) {
                out.add(entry);
            }
        }
        if (out.isEmpty()) {
            plugin.getLogger().severe("modules.wish.pool is empty or invalid; the wish module cannot draw.");
        } else {
            plugin.getLogger().info("Wish pool loaded: " + out.size() + " entries.");
        }
        return out;
    }

    private WishEntry parseEntry(Map<?, ?> map) {
        String id = str(map.get("id"));
        String name = str(map.get("name"));
        if (id == null || id.isEmpty() || name == null || name.isEmpty()) {
            plugin.getLogger().warning("Wish pool entry with missing id/name skipped: " + map);
            return null;
        }
        int weight = intOf(map.get("weight"), 0);
        if (weight <= 0) {
            plugin.getLogger().warning("Wish pool entry '" + id + "' has an invalid weight, skipped.");
            return null;
        }
        boolean rare = Boolean.parseBoolean(str(map.get("rare")));

        Object rewardObj = map.get("reward");
        if (!(rewardObj instanceof Map<?, ?> reward)) {
            plugin.getLogger().warning("Wish pool entry '" + id + "' has no reward section, skipped.");
            return null;
        }
        String type = str(reward.get("type"));
        WishEntry.RewardType rewardType;
        try {
            rewardType = WishEntry.RewardType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            plugin.getLogger().warning("Wish pool entry '" + id + "' has unknown reward type '" + type + "', skipped.");
            return null;
        }

        List<WishEntry.ItemReward> items = new ArrayList<>();
        double money = 0;
        List<String> commands = new ArrayList<>();

        switch (rewardType) {
            case ITEM -> {
                Object itemsObj = reward.get("items");
                if (!(itemsObj instanceof List<?> list) || list.isEmpty()) {
                    plugin.getLogger().warning("Wish pool entry '" + id + "' has an empty item reward, skipped.");
                    return null;
                }
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> im)) {
                        plugin.getLogger().warning("Wish pool entry '" + id + "' has a malformed item, skipped.");
                        return null;
                    }
                    String materialName = str(im.get("material"));
                    Material material = materialName == null ? null : Material.matchMaterial(materialName);
                    if (material == null || !material.isItem()) {
                        plugin.getLogger().warning("Wish pool entry '" + id + "' has unknown material '"
                                + materialName + "', skipped.");
                        return null;
                    }
                    int amount = intOf(im.get("amount"), 1);
                    if (amount <= 0) {
                        plugin.getLogger().warning("Wish pool entry '" + id + "' has a non-positive amount, skipped.");
                        return null;
                    }
                    items.add(new WishEntry.ItemReward(material, amount));
                }
            }
            case MONEY -> {
                money = doubleOf(reward.get("money"), 0);
                if (money <= 0) {
                    plugin.getLogger().warning("Wish pool entry '" + id + "' has an invalid money reward, skipped.");
                    return null;
                }
            }
            case COMMAND -> {
                Object cmdsObj = reward.get("commands");
                if (!(cmdsObj instanceof List<?> list) || list.isEmpty()) {
                    plugin.getLogger().warning("Wish pool entry '" + id + "' has no commands, skipped.");
                    return null;
                }
                for (Object o : list) {
                    String cmd = str(o);
                    if (cmd != null && !cmd.isEmpty()) {
                        commands.add(cmd);
                    }
                }
                if (commands.isEmpty()) {
                    plugin.getLogger().warning("Wish pool entry '" + id + "' has only blank commands, skipped.");
                    return null;
                }
            }
        }
        return new WishEntry(id, name, weight, rare, rewardType, items, money, commands);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private static double doubleOf(Object o, double def) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }
}
