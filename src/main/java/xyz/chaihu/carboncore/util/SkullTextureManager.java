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

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Texture-cache driven player heads, modelled on LiteSignIn's SkullManager but
 * built purely on Paper's native profile API (no reflection, no version shims).
 *
 * <p>Strategy: every UUID whose Base64 "textures" property has been resolved is
 * cached and never evicted (quit does not clear it, so skins stay instant
 * across sessions). Heads render from the cache when it hits; on a miss they
 * fall back to the vanilla owning-player head and kick off an async refresh,
 * so the next open shows the correct skin (eventual consistency). Join events
 * prewarm the cache via {@link SkullPrewarmListener}.
 */
public final class SkullTextureManager {

    private static final String TEXTURES = "textures";

    /** UUID -> Base64 "textures" property value. */
    private static final Map<UUID, String> textureCache = new ConcurrentHashMap<>();
    /** UUIDs with a refresh already scheduled, to coalesce duplicate requests. */
    private static final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private SkullTextureManager() {
    }

    /** Cached Base64 texture value for a player, or null when not resolved yet. */
    public static String textureOf(OfflinePlayer player) {
        return player == null ? null : textureCache.get(player.getUniqueId());
    }

    /**
     * Resolves the texture of one player off the main thread and caches it.
     * Duplicate requests for the same UUID are coalesced while one is running.
     */
    public static void refreshAsync(Plugin plugin, UUID uuid, String name) {
        if (plugin == null || uuid == null || !plugin.isEnabled()) {
            return;
        }
        if (!inFlight.add(uuid)) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    fetchAsync(plugin, uuid, name);
                } finally {
                    inFlight.remove(uuid);
                }
            });
        } catch (IllegalStateException ex) {
            // Scheduler rejected the task (plugin disabled / shutdown mid-join).
            inFlight.remove(uuid);
        }
    }

    private static void fetchAsync(Plugin plugin, UUID uuid, String name) {
        try {
            PlayerProfile profile = createLookupProfile(uuid, name);
            String texture = null;
            if (profile.completeFromCache()) {
                texture = extractTexture(profile);
            }
            if (texture == null && profile.complete()) {
                // Network resolution; only ever reached from an async thread.
                texture = extractTexture(profile);
            }
            if (texture != null) {
                textureCache.put(uuid, texture);
            }
        } catch (IllegalStateException ex) {
            // Some Paper builds pin profile completion to the main thread. The
            // local-cache read is cheap there, so hop over and try once more.
            fetchFromMainCache(plugin, uuid, name);
        } catch (Exception ex) {
            // Mojang / session API hiccup: keep whatever we had, retry next open.
        }
    }

    /** Main-thread fallback path: local profile cache only, never network. */
    private static void fetchFromMainCache(Plugin plugin, UUID uuid, String name) {
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    PlayerProfile profile = createLookupProfile(uuid, name);
                    if (profile.completeFromCache()) {
                        String texture = extractTexture(profile);
                        if (texture != null) {
                            textureCache.put(uuid, texture);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (IllegalStateException ignored) {
        }
    }

    /**
     * Player head rendered from the cached skin when available; otherwise the
     * vanilla owning-player head plus a scheduled refresh (final-consistency).
     */
    public static ItemStack playerHead(OfflinePlayer owner, String displayName, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        applyPlayerProfile(meta, owner);
        applyText(meta, displayName, lore);
        head.setItemMeta(meta);
        return head;
    }

    /** Decorative head rendered from a raw Base64 texture blob (no owner involved). */
    public static ItemStack textureHead(String base64Texture, String displayName, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (base64Texture != null && !base64Texture.isEmpty()) {
            applyTexture(meta, base64Texture);
        }
        applyText(meta, displayName, lore);
        head.setItemMeta(meta);
        return head;
    }

    /**
     * Applies a player's head to an existing skull meta: cached-texture
     * injection on a hit, owning-player fallback plus async refresh on a miss.
     */
    public static void applyPlayerProfile(SkullMeta meta, OfflinePlayer owner) {
        if (meta == null || owner == null) {
            return;
        }
        String texture = textureCache.get(owner.getUniqueId());
        if (texture != null) {
            applyTexture(meta, texture);
        } else {
            meta.setOwningPlayer(owner);
            refreshAsync(CarbonCorePlugin.getInstance(), owner.getUniqueId(), owner.getName());
        }
    }

    /** Injects a raw Base64 texture onto an existing skull meta via a synthetic profile. */
    public static void applyTexture(SkullMeta meta, String base64Texture) {
        if (meta == null || base64Texture == null || base64Texture.isEmpty()) {
            return;
        }
        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "CarbonCore");
        profile.setProperty(new ProfileProperty(TEXTURES, base64Texture));
        meta.setPlayerProfile(profile);
    }

    @SuppressWarnings("removal") // setName is the only way to label a lookup profile on this API
    private static PlayerProfile createLookupProfile(UUID uuid, String name) {
        PlayerProfile profile = Bukkit.createProfile(uuid);
        if (name != null && !name.isEmpty()) {
            profile.setName(name);
        }
        return profile;
    }

    private static String extractTexture(PlayerProfile profile) {
        for (ProfileProperty property : profile.getProperties()) {
            if (TEXTURES.equals(property.getName())) {
                return property.getValue();
            }
        }
        return null;
    }

    private static void applyText(SkullMeta meta, String displayName, List<String> lore) {
        if (displayName != null) {
            meta.displayName(Text.color(displayName));
        }
        if (lore != null && !lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String entry : lore) {
                for (String part : entry.split("\n")) {
                    lines.add(Text.color(part));
                }
            }
            meta.lore(lines);
        }
    }
}
