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
package xyz.chaihu.carboncore.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.util.List;

/**
 * Typed accessor over the plugin's config.yml.
 */
public final class ConfigManager {

    private final CarbonCorePlugin plugin;

    public ConfigManager(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    public void reload() {
        // plugin.reloadConfig() is invoked by CarbonCorePlugin.reloadAll()
    }

    public String getString(String path, String def) {
        return config().getString(path, def);
    }

    public int getInt(String path, int def) {
        return config().getInt(path, def);
    }

    public double getDouble(String path, double def) {
        return config().getDouble(path, def);
    }

    public boolean getBoolean(String path, boolean def) {
        return config().getBoolean(path, def);
    }

    public List<String> getStringList(String path) {
        return config().getStringList(path);
    }

    public List<Double> getDoubleList(String path) {
        return config().getDoubleList(path);
    }

    public List<Integer> getIntegerList(String path) {
        return config().getIntegerList(path);
    }

    public ConfigurationSection getSection(String path) {
        return config().getConfigurationSection(path);
    }

    public boolean isModuleEnabled(String moduleKey) {
        return config().getBoolean("modules." + moduleKey + ".enabled", true);
    }

    public List<String> moduleAliases(String moduleKey) {
        return config().getStringList("modules." + moduleKey + ".command-aliases");
    }

    public int moduleCooldown(String moduleKey) {
        return config().getInt("modules." + moduleKey + ".cooldown-seconds", 0);
    }
}
