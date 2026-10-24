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
package xyz.chaihu.carboncore;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.chaihu.carboncore.bank.BankModule;
import xyz.chaihu.carboncore.config.ConfigManager;
import xyz.chaihu.carboncore.config.Messages;
import xyz.chaihu.carboncore.database.DatabaseManager;
import xyz.chaihu.carboncore.economy.EconomyService;
import xyz.chaihu.carboncore.placeholder.CarbonCoreExpansion;
import xyz.chaihu.carboncore.platform.PlatformService;
import xyz.chaihu.carboncore.quest.QuestModule;
import xyz.chaihu.carboncore.redpacket.RedPacketModule;
import xyz.chaihu.carboncore.ui.form.FormService;
import xyz.chaihu.carboncore.ui.menu.MenuListener;
import xyz.chaihu.carboncore.util.CooldownManager;
import xyz.chaihu.carboncore.util.SkullPrewarmListener;
import xyz.chaihu.carboncore.wish.WishModule;

import java.io.File;
import java.util.List;
import java.util.logging.Level;

/**
 * CarbonCore - Bank / RedPacket / Wish / Quest with dual Java GUI and Bedrock form UI.
 */
public final class CarbonCorePlugin extends JavaPlugin {

    private static CarbonCorePlugin instance;

    private ConfigManager configManager;
    private Messages messages;
    private DatabaseManager database;
    private EconomyService economy;
    private PlatformService platform;
    private FormService formService; // null when Geyser is not installed
    private CooldownManager cooldowns;

    private BankModule bankModule;
    private RedPacketModule redPacketModule;
    private WishModule wishModule;
    private QuestModule questModule;

    public static CarbonCorePlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("quests.yml", false);

        configManager = new ConfigManager(this);
        messages = new Messages(this, configManager);

        database = new DatabaseManager();
        try {
            File dbFile = new File(getDataFolder(),
                    configManager.getString("database.file", "carboncore.db"));
            database.init(dbFile);
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Failed to initialise the SQLite database, disabling CarbonCore.", ex);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        economy = new EconomyService(this);
        economy.setup();
        if (!economy.hasEconomy()) {
            getLogger().warning("No Vault economy plugin found. Economy-dependent modules "
                    + "(bank / redpacket / wish / quest money rewards) will be disabled.");
        }

        platform = new PlatformService(this);
        if (platform.isGeyserPresent()) {
            formService = new FormService(this);
            getLogger().info("Geyser detected: Bedrock players will receive native forms.");
        } else {
            getLogger().info("Geyser not present: all players will use the chest GUI.");
        }

        cooldowns = new CooldownManager();

        bankModule = new BankModule(this);
        redPacketModule = new RedPacketModule(this);
        wishModule = new WishModule(this);
        questModule = new QuestModule(this);

        Bukkit.getPluginManager().registerEvents(new MenuListener(), this);
        Bukkit.getPluginManager().registerEvents(new SkullPrewarmListener(this), this);

        enableModule(bankModule, "bank");
        enableModule(redPacketModule, "redpacket");
        enableModule(wishModule, "wish");
        enableModule(questModule, "quest");

        if (configManager.getBoolean("placeholders.enabled", true)
                && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new CarbonCoreExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        getLogger().info("CarbonCore enabled.");
    }

    /**
     * Enables a module when its config switch is on. Modules that require an economy
     * are skipped when no Vault provider is available.
     */
    private void enableModule(CarbonModule module, String configKey) {
        if (!configManager.isModuleEnabled(configKey)) {
            getLogger().info("Module '" + module.name() + "' is disabled in config.yml.");
            return;
        }
        if (module.requiresEconomy() && !economy.hasEconomy()) {
            getLogger().warning("Module '" + module.name() + "' requires a Vault economy but none was found; skipping.");
            return;
        }
        try {
            module.onEnable();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Module '" + module.name() + "' failed to enable.", ex);
        }
    }

    /**
     * Registers a command's executor / tab completer and applies aliases from config.yml.
     */
    public void registerCommand(String name, List<String> aliases, CommandExecutor executor, TabCompleter tabCompleter) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '/" + name + "' is missing from plugin.yml!");
            return;
        }
        command.setExecutor(executor);
        if (tabCompleter != null) {
            command.setTabCompleter(tabCompleter);
        }
        if (aliases != null && !aliases.isEmpty()) {
            command.setAliases(aliases);
        }
    }

    @Override
    public void onDisable() {
        if (bankModule != null) bankModule.onDisable();
        if (redPacketModule != null) redPacketModule.onDisable();
        if (wishModule != null) wishModule.onDisable();
        if (questModule != null) questModule.onDisable();
        if (database != null) database.shutdown();
        getLogger().info("CarbonCore disabled.");
    }

    /** Reloads config.yml and messages.yml. Quest definitions are reloaded by the quest module. */
    public void reloadAll() {
        reloadConfig();
        configManager.reload();
        messages.reload();
    }

    public ConfigManager configManager() { return configManager; }
    public Messages messages() { return messages; }
    public DatabaseManager database() { return database; }
    public EconomyService economy() { return economy; }
    public PlatformService platform() { return platform; }
    public FormService formService() { return formService; }
    public CooldownManager cooldowns() { return cooldowns; }
    public BankModule bankModule() { return bankModule; }
    public RedPacketModule redPacketModule() { return redPacketModule; }
    public WishModule wishModule() { return wishModule; }
    public QuestModule questModule() { return questModule; }
}
