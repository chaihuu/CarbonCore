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
package xyz.chaihu.carboncore.redpacket;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.config.Messages;
import xyz.chaihu.carboncore.ui.menu.Menu;
import xyz.chaihu.carboncore.util.ItemBuilder;
import xyz.chaihu.carboncore.util.SkullTextureManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Chest-GUI screens for the red packet module (Java Edition route).
 * All screens share the service handle and the small helpers below.
 */
public abstract class RedPacketMenu extends Menu {

    protected static final int PAGE_SIZE = 45;

    protected final RedPacketService service;

    protected RedPacketMenu(CarbonCorePlugin plugin, RedPacketService service,
                             Player viewer, int size, String title) {
        super(plugin, viewer, size, title);
        this.service = service;
    }

    protected Messages messages() {
        return plugin.messages();
    }

    protected String fmt(double amount) {
        return plugin.economy().format(amount);
    }

    /** "123s" style remaining-time label for packet lore. */
    protected String expireLabel(RedPacket rp) {
        long secs = Math.max(0, (rp.expireAt() - System.currentTimeMillis()) / 1000L);
        return secs + "s";
    }

    /** Fills the whole inventory with panes (call before placing buttons). */
    protected void fillPanes() {
        ItemStack pane = ItemBuilder.pane();
        for (int i = 0; i < size; i++) {
            set(i, pane);
        }
    }

    /** Bottom navigation row for the 54-slot paginated lists (slots 45-53). */
    protected void navRow(int page, int pages, Runnable back, IntConsumer openPage) {
        set(45, new ItemBuilder(Material.ARROW)
                .name(messages().raw("general.back"))
                .build(),
                (event, player, slot) -> back.run());
        if (page > 0) {
            set(47, new ItemBuilder(Material.ARROW)
                    .name(messages().raw("general.previous"))
                    .build(),
                    (event, player, slot) -> openPage.accept(page - 1));
        }
        set(49, new ItemBuilder(Material.PAPER)
                .name(messages().format("general.page-info", "page", page + 1, "pages", pages))
                .build());
        if (page < pages - 1) {
            set(51, new ItemBuilder(Material.ARROW)
                    .name(messages().raw("general.next"))
                    .build(),
                    (event, player, slot) -> openPage.accept(page + 1));
        }
        set(53, new ItemBuilder(Material.BARRIER)
                .name(messages().raw("general.close"))
                .build(),
                (event, player, slot) -> player.closeInventory());
    }

    // ------------------------------------------------------------------
    // main menu
    // ------------------------------------------------------------------

    /** 27-slot hub: send / claim / my packets / password hint / close. */
    public static final class Main extends RedPacketMenu {

        public Main(CarbonCorePlugin plugin, RedPacketService service, Player viewer) {
            super(plugin, service, viewer, 27, plugin.messages().raw("redpacket.gui.title"));
        }

        @Override
        public void build() {
            fillPanes();
            set(11, new ItemBuilder(Material.WRITTEN_BOOK)
                    .name(messages().raw("redpacket.gui.create"))
                    .lore(messages().raw("redpacket.gui.create-lore"))
                    .build(),
                    (event, player, slot) -> new CreateAmount(plugin, service, player).open());
            set(13, new ItemBuilder(Material.PAPER)
                    .name(messages().raw("redpacket.gui.list"))
                    .lore(messages().raw("redpacket.gui.list-lore"))
                    .build(),
                    (event, player, slot) -> new ClaimList(plugin, service, player, 0).open());
            set(15, new ItemBuilder(Material.CHEST_MINECART)
                    .name(messages().raw("redpacket.gui.my"))
                    .lore(messages().raw("redpacket.gui.my-lore"))
                    .build(),
                    (event, player, slot) -> new MyPackets(plugin, service, player, 0).open());
            set(22, new ItemBuilder(Material.NAME_TAG)
                    .name(messages().raw("redpacket.gui.code"))
                    .lore(messages().raw("redpacket.gui.code-lore"))
                    .build(),
                    // the chest GUI cannot type a password; point to the command
                    (event, player, slot) -> messages().sendPlayer(player, "redpacket.claim-need-code"));
            set(26, new ItemBuilder(Material.BARRIER)
                    .name(messages().raw("general.close"))
                    .build(),
                    (event, player, slot) -> player.closeInventory());
        }
    }

    // ------------------------------------------------------------------
    // create flow
    // ------------------------------------------------------------------

    /** Step 1: pick the total amount. */
    public static final class CreateAmount extends RedPacketMenu {

        private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

        public CreateAmount(CarbonCorePlugin plugin, RedPacketService service, Player viewer) {
            super(plugin, service, viewer, 27, plugin.messages().raw("redpacket.gui.amount-title"));
        }

        @Override
        public void build() {
            fillPanes();
            double min = service.minTotal();
            double max = service.maxTotal();
            Set<Double> amounts = new LinkedHashSet<>();
            amounts.add(round2(min));
            for (double d : new double[]{10, 20, 50, 100, 200, 500}) {
                if (d >= min && d <= max) {
                    amounts.add(d);
                }
            }
            double balance = round2(plugin.economy().getBalance(viewer));
            if (balance >= min && balance <= max) {
                amounts.add(balance);
            }
            int slotIndex = 0;
            for (Double amount : amounts) {
                if (slotIndex >= SLOTS.length) {
                    break;
                }
                final double value = amount;
                set(SLOTS[slotIndex++], new ItemBuilder(Material.GOLD_INGOT)
                        .name(fmt(value))
                        .glow()
                        .build(),
                        (event, player, slot) -> new CreateCount(plugin, service, player, value).open());
            }
            set(18, new ItemBuilder(Material.ARROW)
                    .name(messages().raw("general.back"))
                    .build(),
                    (event, player, slot) -> new Main(plugin, service, player).open());
            set(26, new ItemBuilder(Material.BARRIER)
                    .name(messages().raw("general.close"))
                    .build(),
                    (event, player, slot) -> player.closeInventory());
        }
    }

    /** Step 2: pick the share count. */
    public static final class CreateCount extends RedPacketMenu {

        private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

        private final double amount;

        public CreateCount(CarbonCorePlugin plugin, RedPacketService service, Player viewer, double amount) {
            super(plugin, service, viewer, 27, plugin.messages().raw("redpacket.gui.count-title"));
            this.amount = amount;
        }

        @Override
        public void build() {
            fillPanes();
            int min = service.minCount();
            int max = service.maxCount();
            List<Integer> counts = new ArrayList<>();
            for (int c : new int[]{1, 5, 10, 20, 50, 100}) {
                if (c >= min && c <= max) {
                    counts.add(c);
                }
            }
            if (counts.isEmpty()) {
                counts.add(min);
            }
            int slotIndex = 0;
            for (Integer count : counts) {
                if (slotIndex >= SLOTS.length) {
                    break;
                }
                final int shares = count;
                set(SLOTS[slotIndex++], new ItemBuilder(Material.EMERALD)
                        .name("&f" + shares)
                        .lore(messages().format("redpacket.gui.count-lore", "count", shares))
                        .build(),
                        (event, player, slot) ->
                                new CreateDuration(plugin, service, player, amount, shares).open());
            }
            set(18, new ItemBuilder(Material.ARROW)
                    .name(messages().raw("general.back"))
                    .build(),
                    (event, player, slot) -> new CreateAmount(plugin, service, player).open());
            set(26, new ItemBuilder(Material.BARRIER)
                    .name(messages().raw("general.close"))
                    .build(),
                    (event, player, slot) -> player.closeInventory());
        }
    }

    /** Step 3: pick the validity duration. */
    public static final class CreateDuration extends RedPacketMenu {

        private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

        private final double amount;
        private final int count;

        public CreateDuration(CarbonCorePlugin plugin, RedPacketService service, Player viewer,
                              double amount, int count) {
            super(plugin, service, viewer, 27, plugin.messages().raw("redpacket.gui.duration-title"));
            this.amount = amount;
            this.count = count;
        }

        @Override
        public void build() {
            fillPanes();
            List<Integer> durations = service.durations();
            int slotIndex = 0;
            for (Integer seconds : durations) {
                if (slotIndex >= SLOTS.length) {
                    break;
                }
                final long secs = seconds;
                set(SLOTS[slotIndex++], new ItemBuilder(Material.CLOCK)
                        .name("&f" + secs)
                        .lore(messages().format("redpacket.gui.duration-lore", "seconds", secs))
                        .build(),
                        (event, player, slot) ->
                                new CreateConfirm(plugin, service, player, amount, count, secs).open());
            }
            set(18, new ItemBuilder(Material.ARROW)
                    .name(messages().raw("general.back"))
                    .build(),
                    (event, player, slot) ->
                            new CreateCount(plugin, service, player, amount).open());
            set(26, new ItemBuilder(Material.BARRIER)
                    .name(messages().raw("general.close"))
                    .build(),
                    (event, player, slot) -> player.closeInventory());
        }
    }

    /** Step 4: confirm and send. The GUI flow skips message/code (command only). */
    public static final class CreateConfirm extends RedPacketMenu {

        private final double amount;
        private final int count;
        private final long seconds;

        public CreateConfirm(CarbonCorePlugin plugin, RedPacketService service, Player viewer,
                             double amount, int count, long seconds) {
            super(plugin, service, viewer, 27, plugin.messages().raw("redpacket.gui.confirm-title"));
            this.amount = amount;
            this.count = count;
            this.seconds = seconds;
        }

        @Override
        public void build() {
            fillPanes();
            set(13, new ItemBuilder(Material.WRITTEN_BOOK)
                    .name(messages().raw("redpacket.gui.confirm-title"))
                    .lore(messages().format("redpacket.gui.confirm-lore",
                            "amount", fmt(amount), "count", count, "seconds", seconds))
                    .glow()
                    .build());
            set(11, new ItemBuilder(Material.GREEN_DYE)
                    .name(messages().raw("general.confirm"))
                    .build(),
                    (event, player, slot) -> {
                        service.create(player, amount, count, seconds, null, null);
                        player.closeInventory();
                    });
            set(15, new ItemBuilder(Material.ARROW)
                    .name(messages().raw("general.back"))
                    .build(),
                    (event, player, slot) ->
                            new CreateDuration(plugin, service, player, amount, count).open());
            set(20, new ItemBuilder(Material.RED_DYE)
                    .name(messages().raw("general.cancel"))
                    .build(),
                    (event, player, slot) -> player.closeInventory());
            set(26, new ItemBuilder(Material.BARRIER)
                    .name(messages().raw("general.close"))
                    .build(),
                    (event, player, slot) -> player.closeInventory());
        }
    }

    // ------------------------------------------------------------------
    // lists
    // ------------------------------------------------------------------

    /** 54-slot paginated list of packets the viewer can still claim. */
    public static final class ClaimList extends RedPacketMenu {

        private final int page;

        public ClaimList(CarbonCorePlugin plugin, RedPacketService service, Player viewer, int page) {
            super(plugin, service, viewer, 54, plugin.messages().raw("redpacket.gui.list"));
            this.page = page;
        }

        @Override
        public void build() {
            fillPanes();
            List<RedPacket> list = service.claimableFor(viewer.getUniqueId());
            int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int page = Math.min(Math.max(0, this.page), pages - 1);
            if (list.isEmpty()) {
                set(22, new ItemBuilder(Material.BARRIER)
                        .name(messages().raw("general.empty"))
                        .build());
            }
            int start = page * PAGE_SIZE;
            int end = Math.min(list.size(), start + PAGE_SIZE);
            int slot = 0;
            for (int i = start; i < end; i++) {
                RedPacket rp = list.get(i);
                List<String> lore = new ArrayList<>();
                lore.add(messages().format("redpacket.gui.packet-lore",
                        "remaining", rp.remainingCount(),
                        "total", rp.totalCount(),
                        "amount", fmt(rp.remainingAmount()),
                        "expire", expireLabel(rp),
                        "message", rp.message() == null ? "-" : rp.message()));
                if (rp.needsCode()) {
                    lore.add(messages().raw("redpacket.gui.packet-code"));
                }
                final int packetId = rp.id();
                final boolean needsCode = rp.needsCode();
                set(slot++, SkullTextureManager.playerHead(
                        Bukkit.getOfflinePlayer(rp.senderUuid()),
                        messages().format("redpacket.gui.packet-name", "sender", rp.senderName()),
                        lore),
                        (event, player, clickSlot) -> {
                            if (needsCode) {
                                messages().sendPlayer(player, "redpacket.claim-need-code");
                                return;
                            }
                            service.claim(player, packetId, null);
                            // the claim result lands asynchronously; redraw shortly after
                            Bukkit.getScheduler().runTaskLater(plugin, this::refresh, 20L);
                        });
            }
            navRow(page, pages,
                    () -> new Main(plugin, service, viewer).open(),
                    target -> new ClaimList(plugin, service, viewer, target).open());
        }
    }

    /** 54-slot paginated list of packets the viewer has sent. */
    public static final class MyPackets extends RedPacketMenu {

        private final int page;

        public MyPackets(CarbonCorePlugin plugin, RedPacketService service, Player viewer, int page) {
            super(plugin, service, viewer, 54, plugin.messages().raw("redpacket.gui.my"));
            this.page = page;
        }

        @Override
        public void build() {
            fillPanes();
            List<RedPacket> list = service.packetsBy(viewer.getUniqueId());
            int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int page = Math.min(Math.max(0, this.page), pages - 1);
            if (list.isEmpty()) {
                set(22, new ItemBuilder(Material.BARRIER)
                        .name(messages().raw("general.empty"))
                        .build());
            }
            int start = page * PAGE_SIZE;
            int end = Math.min(list.size(), start + PAGE_SIZE);
            int slot = 0;
            for (int i = start; i < end; i++) {
                RedPacket rp = list.get(i);
                final int packetId = rp.id();
                set(slot++, SkullTextureManager.playerHead(
                        Bukkit.getOfflinePlayer(rp.senderUuid()),
                        messages().format("redpacket.gui.packet-name", "sender", rp.senderName()),
                        List.of(messages().format("redpacket.gui.my-packet-lore",
                                "remaining", rp.remainingCount(), "total", rp.totalCount()))),
                        (event, player, clickSlot) -> {
                            RedPacket current = service.activePacket(packetId);
                            if (current == null) {
                                messages().sendPlayer(player, "redpacket.claim-empty");
                                return;
                            }
                            messages().sendPlayer(player, "redpacket.info",
                                    "sender", current.senderName(),
                                    "remaining", current.remainingCount(),
                                    "total", current.totalCount(),
                                    "amount", fmt(current.remainingAmount()));
                        });
            }
            navRow(page, pages,
                    () -> new Main(plugin, service, viewer).open(),
                    target -> new MyPackets(plugin, service, viewer, target).open());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
