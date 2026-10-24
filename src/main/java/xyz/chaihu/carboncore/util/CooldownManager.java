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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-key command cooldown tracker.
 */
public final class CooldownManager {

    private final Map<UUID, Map<String, Long>> usage = new ConcurrentHashMap<>();

    /**
     * Returns true if the action is allowed now (and records it).
     * When seconds <= 0 the cooldown is disabled and the call always succeeds.
     */
    public boolean tryUse(UUID player, String key, int seconds) {
        if (seconds <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Map<String, Long> keys = usage.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        synchronized (keys) {
            Long last = keys.get(key);
            if (last != null && now - last < seconds * 1000L) {
                return false;
            }
            keys.put(key, now);
            return true;
        }
    }

    /** Remaining seconds until the cooldown expires, 0 when available. */
    public int remaining(UUID player, String key, int seconds) {
        if (seconds <= 0) {
            return 0;
        }
        Map<String, Long> keys = usage.get(player);
        if (keys == null) {
            return 0;
        }
        Long last = keys.get(key);
        if (last == null) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - last;
        long left = seconds * 1000L - elapsed;
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    public void clear(UUID player) {
        usage.remove(player);
    }
}
