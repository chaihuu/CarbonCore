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

import org.bukkit.entity.Player;
import xyz.chaihu.carboncore.CarbonCorePlugin;
import xyz.chaihu.carboncore.config.Messages;
import xyz.chaihu.carboncore.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedrock form UI for the red packet module. All texts are stripped of & color
 * codes via {@link Text#plain(String)} because Bedrock forms do not render them.
 * Component order in custom forms defines the response indices.
 */
public final class RedPacketForm {

    private final CarbonCorePlugin plugin;
    private final RedPacketService service;

    public RedPacketForm(CarbonCorePlugin plugin, RedPacketService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private Messages messages() {
        return plugin.messages();
    }

    /** Main SimpleForm: create / claim list / password claim / my packets. */
    public void openMain(Player player) {
        Messages m = messages();
        List<String> buttons = List.of(
                Text.plain(m.raw("redpacket.gui.create")),
                Text.plain(m.raw("redpacket.gui.list")),
                Text.plain(m.raw("redpacket.gui.code")),
                Text.plain(m.raw("redpacket.gui.my")));
        plugin.formService().sendSimple(player,
                Text.plain(m.raw("redpacket.form.title")), "", buttons, (p, index) -> {
                    switch (index) {
                        case 0 -> openCreate(p);
                        case 1 -> openClaimList(p);
                        case 2 -> openCode(p);
                        default -> openMy(p);
                    }
                });
    }

    /** Claimable packets as buttons; each opens a confirm modal before claiming. */
    public void openClaimList(Player player) {
        Messages m = messages();
        String resultTitle = Text.plain(m.raw("redpacket.form.result-title"));
        String claimTitle = Text.plain(m.raw("redpacket.form.claim-title"));
        List<RedPacket> list = service.claimableFor(player.getUniqueId());
        if (list.isEmpty()) {
            plugin.formService().sendNotice(player, resultTitle,
                    Text.plain(m.raw("redpacket.list-empty")));
            return;
        }
        List<String> buttons = new ArrayList<>();
        for (RedPacket rp : list) {
            buttons.add(infoLine(rp));
        }
        plugin.formService().sendSimple(player, claimTitle, "", buttons, (p, index) -> {
            if (index < 0 || index >= list.size()) {
                return;
            }
            RedPacket rp = list.get(index);
            plugin.formService().sendModal(p, claimTitle, infoLine(rp),
                    Text.plain(m.raw("general.confirm")),
                    Text.plain(m.raw("general.cancel")),
                    (q, ok) -> {
                        if (ok) {
                            service.claim(q, rp.id(), null);
                        }
                    });
        });
    }

    /** Create form: amount input, count slider, duration dropdown, message + code inputs. */
    public void openCreate(Player player) {
        Messages m = messages();
        List<Integer> durations = service.durations();
        List<String> durationOptions = new ArrayList<>();
        for (Integer seconds : durations) {
            durationOptions.add(Text.plain(m.format("redpacket.gui.duration-lore", "seconds", seconds)));
        }
        int defaultIndex = durations.indexOf((int) Math.min(service.defaultDuration(), Integer.MAX_VALUE));
        if (defaultIndex < 0) {
            defaultIndex = 0;
        }
        final int durationDefault = defaultIndex;
        float minCount = Math.max(1, service.minCount());
        float maxCount = Math.max(minCount + 1, service.maxCount());
        float countDefault = Math.min(Math.max(5, minCount), maxCount);

        plugin.formService().sendCustom(player,
                Text.plain(m.raw("redpacket.form.create-title")),
                builder -> builder
                        .input(Text.plain(m.raw("redpacket.form.create-amount-label")), "10", "10")
                        .slider(Text.plain(m.raw("redpacket.form.create-count-label")),
                                minCount, maxCount, 1f, countDefault)
                        .dropdown(Text.plain(m.raw("redpacket.form.create-duration-label")),
                                durationOptions, durationDefault)
                        .input(Text.plain(m.raw("redpacket.form.create-message-label")), "", "")
                        .input(Text.plain(m.raw("redpacket.form.create-code-label")), "", ""),
                (p, response) -> {
                    String amountInput = response.asInput(0);
                    double total;
                    try {
                        total = Double.parseDouble(amountInput == null ? "" : amountInput.trim());
                    } catch (NumberFormatException ex) {
                        messages().sendPlayer(p, "general.invalid-amount",
                                "input", amountInput == null ? "" : amountInput);
                        return;
                    }
                    int count = (int) Math.round(response.asSlider(1));
                    int option = response.asDropdown(2);
                    long seconds = (option >= 0 && option < durations.size())
                            ? durations.get(option) : service.defaultDuration();
                    String message = response.asInput(3);
                    String code = response.asInput(4);
                    service.create(p, total, count, seconds, message, code);
                });
    }

    /** Password claim form: packet id + code inputs. */
    public void openCode(Player player) {
        Messages m = messages();
        plugin.formService().sendCustom(player,
                Text.plain(m.raw("redpacket.form.code-title")),
                builder -> builder
                        .input(Text.plain(m.raw("redpacket.form.code-id-label")), "", "")
                        .input(Text.plain(m.raw("redpacket.form.code-label")), "", ""),
                (p, response) -> {
                    String idInput = response.asInput(0);
                    int id;
                    try {
                        id = Integer.parseInt(idInput == null ? "" : idInput.trim());
                    } catch (NumberFormatException ex) {
                        messages().sendPlayer(p, "general.invalid-amount",
                                "input", idInput == null ? "" : idInput);
                        return;
                    }
                    service.claim(p, id, response.asInput(1));
                });
    }

    /** My packets notice: one info line per open packet sent by the viewer. */
    public void openMy(Player player) {
        Messages m = messages();
        List<RedPacket> list = service.packetsBy(player.getUniqueId());
        StringBuilder body = new StringBuilder();
        for (RedPacket rp : list) {
            if (body.length() > 0) {
                body.append("\n");
            }
            body.append(infoLine(rp));
        }
        if (body.length() == 0) {
            body.append(Text.plain(m.raw("redpacket.list-empty")));
        }
        plugin.formService().sendNotice(player,
                Text.plain(m.raw("redpacket.form.result-title")), body.toString());
    }

    /** Plain "sender / remaining / total / amount" line shared by list buttons. */
    private String infoLine(RedPacket rp) {
        return Text.plain(messages().format("redpacket.info",
                "sender", rp.senderName(),
                "remaining", rp.remainingCount(),
                "total", rp.totalCount(),
                "amount", plugin.economy().format(rp.remainingAmount())));
    }
}
