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

/**
 * Supported quest tracking types, matching the {@code type} field in quests.yml.
 */
public enum QuestType {

    /** Kill entities (target: entity type name or ANY). */
    KILL,
    /** Break blocks (target: material name or ANY). */
    MINE,
    /** Fish up items (target: material name or ANY). */
    FISH,
    /** Log into the server. */
    LOGIN,
    /** Trade with villagers (merchant inventory closed). */
    TRADE,
    /** Count of other quests whose reward was claimed. */
    COMPLETE
}
