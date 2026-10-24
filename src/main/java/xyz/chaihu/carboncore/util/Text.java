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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Small helpers for legacy '&' color codes and placeholder replacement.
 */
public final class Text {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    /** Converts a legacy '&' coded string into a Component. */
    public static Component color(String legacy) {
        if (legacy == null) {
            return Component.empty();
        }
        return AMP.deserialize(legacy);
    }

    /** Replaces {name} placeholders inside a raw string. Pairs: key1,value1,key2,value2... */
    public static String replace(String s, Object... pairs) {
        if (s == null) {
            return "";
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return s;
    }

    /** Converts a legacy '&' coded list into Components. */
    public static List<Component> colorList(List<String> legacyLines) {
        List<Component> out = new ArrayList<>(legacyLines.size());
        for (String line : legacyLines) {
            out.add(color(line));
        }
        return out;
    }

    /** Plain-text strip of '&' codes (used for Bedrock form bodies). */
    public static String plain(String legacy) {
        if (legacy == null) {
            return "";
        }
        return legacy.replaceAll("(?i)&[0-9a-fk-orx]", "");
    }
}
