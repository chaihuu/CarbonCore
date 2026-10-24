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
package xyz.chaihu.carboncore.quest;

import org.bukkit.Material;

import java.util.List;

/**
 * Immutable definition of one quest parsed from quests.yml.
 */
public final class Quest {

    /** Progress reset cadence; the reset period is part of the DB primary key. */
    public enum Reset {
        DAILY, WEEKLY, NONE
    }

    /** One item reward entry (material + total amount). */
    public record ItemReward(Material material, int amount) {
    }

    private final String id;
    private final boolean enabled;
    private final QuestType type;
    private final String target;
    private final int goal;
    private final Reset reset;
    private final double money;
    private final List<ItemReward> items;
    private final List<String> commands;
    private final String display;
    private final String description;

    public Quest(String id, boolean enabled, QuestType type, String target, int goal,
                 Reset reset, double money, List<ItemReward> items, List<String> commands,
                 String display, String description) {
        this.id = id;
        this.enabled = enabled;
        this.type = type;
        this.target = target;
        this.goal = goal;
        this.reset = reset;
        this.money = money;
        this.items = List.copyOf(items);
        this.commands = List.copyOf(commands);
        this.display = display;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public boolean enabled() {
        return enabled;
    }

    public QuestType type() {
        return type;
    }

    public String target() {
        return target;
    }

    public int goal() {
        return goal;
    }

    public Reset reset() {
        return reset;
    }

    public double money() {
        return money;
    }

    public List<ItemReward> items() {
        return items;
    }

    public List<String> commands() {
        return commands;
    }

    public String display() {
        return display;
    }

    public String description() {
        return description;
    }
}
