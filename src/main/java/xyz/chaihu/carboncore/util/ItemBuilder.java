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
package xyz.chaihu.carboncore.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for GUI ItemStacks using Adventure components.
 */
public final class ItemBuilder {

    private final ItemStack item;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material);
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(Math.max(1, Math.min(64, amount)));
        return this;
    }

    public ItemBuilder name(String legacyName) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.color(legacyName));
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemBuilder lore(List<String> legacyLines) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            for (String line : legacyLines) {
                for (String part : line.split("\n")) {
                    lore.add(Text.color(part));
                }
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemBuilder lore(String... legacyLines) {
        return lore(List.of(legacyLines));
    }

    public ItemBuilder glow() {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return this;
    }

    /** Player head via the texture cache (see {@link SkullTextureManager}). */
    public ItemBuilder skull(OfflinePlayer owner) {
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            SkullTextureManager.applyPlayerProfile(skull, owner);
            item.setItemMeta(skull);
        }
        return this;
    }

    /** Decorative head rendered from a raw Base64 texture blob. */
    public ItemBuilder skullTexture(String base64Texture) {
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            SkullTextureManager.applyTexture(skull, base64Texture);
            item.setItemMeta(skull);
        }
        return this;
    }

    /** Stores an arbitrary tag so click handlers can identify the entry. */
    public ItemBuilder tag(String key, String value) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("carboncore", key),
                    PersistentDataType.STRING, value);
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemStack build() {
        return item;
    }

    /** A gray filler pane. */
    public static ItemStack pane() {
        return new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name("&r").build();
    }
}
