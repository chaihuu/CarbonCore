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

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.util.Text;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads messages.yml and resolves player facing text for the configured locale
 * (auto / zh_cn / en_us). Missing keys fall back to the key path itself.
 */
public final class Messages {

    private final CarbonCorePlugin plugin;
    private YamlConfiguration file;
    private String locale; // "zh_cn" or "en_us"

    public Messages(CarbonCorePlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        load(configManager);
    }

    private void load(ConfigManager configManager) {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.file = YamlConfiguration.loadConfiguration(f);
        String setting = configManager == null ? "auto" : configManager.getString("language", "auto");
        this.locale = resolveLocale(setting.toLowerCase(Locale.ROOT));
    }

    public void reload() {
        load(plugin.configManager());
    }

    private String resolveLocale(String setting) {
        if (setting.equals("zh_cn") || setting.equals("en_us")) {
            return setting;
        }
        // auto: follow the server's default locale
        String sys = Locale.getDefault().getLanguage();
        return sys.startsWith("zh") ? "zh_cn" : "en_us";
    }

    public String locale() {
        return locale;
    }

    /** Raw (uncolored) message for a dotted key, or the key itself when missing. */
    public String raw(String key) {
        String path = locale + "." + key;
        if (file.isString(path)) {
            return file.getString(path);
        }
        // fall back to the other locale, then to the key
        String other = locale.equals("zh_cn") ? "en_us" : "zh_cn";
        String alt = other + "." + key;
        if (file.isString(alt)) {
            return file.getString(alt);
        }
        return "&c[missing message: " + key + "]";
    }

    /** Replaces {name} placeholders. Pairs are given as key1, value1, key2, value2... */
    public String format(String key, Object... pairs) {
        String s = raw(key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return s;
    }

    /** Colored Component for a message key. */
    public Component msg(String key, Object... pairs) {
        return Text.color(format(key, pairs));
    }

    /** Sends a prefixed message to a command sender. */
    public void send(CommandSender sender, String key, Object... pairs) {
        sender.sendMessage(Text.color(raw("prefix") + format(key, pairs)));
    }

    /** Sends a prefixed message only to players. */
    public void sendPlayer(org.bukkit.entity.Player player, String key, Object... pairs) {
        player.sendMessage(Text.color(raw("prefix") + format(key, pairs)));
    }

    /** Colored Component list for a string-list key. */
    public List<Component> list(String key, Object... pairs) {
        String path = locale + "." + key;
        List<String> raws = new ArrayList<>();
        if (file.isList(path)) {
            raws = file.getStringList(path);
        }
        List<Component> out = new ArrayList<>();
        for (String line : raws) {
            String replaced = line;
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                replaced = replaced.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
            }
            out.add(Text.color(replaced));
        }
        return out;
    }
}
