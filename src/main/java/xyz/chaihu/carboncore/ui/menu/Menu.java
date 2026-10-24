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
package xyz.chaihu.carboncore.ui.menu;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for chest-GUI screens. Each screen is its own InventoryHolder so
 * the click listener can identify it and route clicks to registered actions.
 */
public abstract class Menu implements InventoryHolder {

    protected final CarbonCorePlugin plugin;
    protected final Player viewer;
    protected final int size;
    protected final String title;
    protected Inventory inventory;

    /** slot -> click action */
    private final Map<Integer, ClickAction> actions = new HashMap<>();

    protected Menu(CarbonCorePlugin plugin, Player viewer, int size, String title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.size = size % 9 == 0 ? Math.min(54, Math.max(9, size)) : 27;
        this.title = title;
    }

    /** Fill the inventory contents and register click actions. */
    public abstract void build();

    /** (Re)builds the inventory and opens it for the viewer. */
    public void open() {
        inventory = Bukkit.createInventory(this, size, xyz.chaihu.carboncore.util.Text.color(title));
        actions.clear();
        build();
        viewer.openInventory(inventory);
    }

    /** Rebuilds contents in place (keeps the window open). */
    public void refresh() {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        actions.clear();
        build();
    }

    protected void set(int slot, ItemStack item, ClickAction action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /** Fills the whole row with panes. */
    protected void fillRow(int row, ItemStack pane) {
        for (int col = 0; col < 9; col++) {
            set(row * 9 + col, pane);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player viewer() {
        return viewer;
    }

    Map<Integer, ClickAction> actions() {
        return actions;
    }

    @FunctionalInterface
    public interface ClickAction {
        void click(InventoryClickEvent event, Player player, int slot);
    }
}
