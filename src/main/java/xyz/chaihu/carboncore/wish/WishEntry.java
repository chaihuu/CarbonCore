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

import java.util.List;

/**
 * Immutable weighted prize-pool entry parsed from {@code modules.wish.pool}
 * in config.yml.
 */
public final class WishEntry {

    public enum RewardType { ITEM, MONEY, COMMAND }

    /** One material stack inside an ITEM reward. */
    public record ItemReward(Material material, int amount) {
    }

    private final String id;
    private final String name;
    private final int weight;
    private final boolean rare;
    private final RewardType rewardType;
    private final List<ItemReward> items;
    private final double money;
    private final List<String> commands;

    public WishEntry(String id, String name, int weight, boolean rare, RewardType rewardType,
                     List<ItemReward> items, double money, List<String> commands) {
        this.id = id;
        this.name = name;
        this.weight = weight;
        this.rare = rare;
        this.rewardType = rewardType;
        this.items = List.copyOf(items);
        this.money = money;
        this.commands = List.copyOf(commands);
    }

    /** Display name of the entry (already contains & color codes). */
    public String display() {
        return name;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int weight() {
        return weight;
    }

    public boolean rare() {
        return rare;
    }

    public RewardType rewardType() {
        return rewardType;
    }

    public List<ItemReward> items() {
        return items;
    }

    public double money() {
        return money;
    }

    public List<String> commands() {
        return commands;
    }

    /** Representative GUI icon: first reward material / gold ingot / name tag. */
    public Material icon() {
        return switch (rewardType) {
            case ITEM -> items.isEmpty() ? Material.PAPER : items.get(0).material();
            case MONEY -> Material.GOLD_INGOT;
            case COMMAND -> Material.NAME_TAG;
        };
    }
}
