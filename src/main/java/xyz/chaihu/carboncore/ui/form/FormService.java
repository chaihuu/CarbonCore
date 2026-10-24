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
package xyz.chaihu.carboncore.ui.form;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.geyser.api.GeyserApi;
import xyz.chaihu.carboncore.CarbonCorePlugin;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sends native Bedrock forms (Cumulus) through the official Geyser API.
 * Form response handlers fire on Geyser's network thread, so every callback
 * here is re-dispatched onto the Bukkit main thread before touching game state.
 *
 * Only instantiated when Geyser is present (see CarbonCorePlugin).
 */
public final class FormService {

    private final CarbonCorePlugin plugin;

    public FormService(CarbonCorePlugin plugin) {
        this.plugin = plugin;
    }

    private void onMain(UUID playerUuid, Consumer<Player> action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerUuid);
            if (p != null && p.isOnline()) {
                action.accept(p);
            }
        });
    }

    /** SimpleForm with a list of buttons; onClick receives the clicked button index. */
    public void sendSimple(Player player, String title, String content,
                           List<String> buttons, BiConsumer<Player, Integer> onClick) {
        UUID uuid = player.getUniqueId();
        SimpleForm.Builder builder = SimpleForm.builder().title(title).content(content);
        for (String button : buttons) {
            builder.button(button);
        }
        builder.validResultHandler(response -> {
            int index = response.clickedButtonId();
            onMain(uuid, p -> onClick.accept(p, index));
        });
        send(player, builder.build());
    }

    /** ModalForm with two buttons; onResult receives true when button1 was clicked. */
    public void sendModal(Player player, String title, String content,
                          String button1, String button2, BiConsumer<Player, Boolean> onResult) {
        UUID uuid = player.getUniqueId();
        ModalForm form = ModalForm.builder()
                .title(title)
                .content(content)
                .button1(button1)
                .button2(button2)
                .validResultHandler(response -> {
                    boolean first = response.clickedFirst();
                    onMain(uuid, p -> onResult.accept(p, first));
                })
                .build();
        send(player, form);
    }

    /**
     * CustomForm. The configure callback adds components in order
     * (input / slider / dropdown / toggle / stepSlider); the onValid callback
     * receives the parsed response on the main thread. Avoid label components:
     * they occupy response indices and make parsing error-prone.
     */
    public void sendCustom(Player player, String title,
                           Consumer<CustomForm.Builder> configure,
                           BiConsumer<Player, CustomFormResponse> onValid) {
        UUID uuid = player.getUniqueId();
        CustomForm.Builder builder = CustomForm.builder().title(title);
        configure.accept(builder);
        builder.validResultHandler(response -> onMain(uuid, p -> onValid.accept(p, response)));
        send(player, builder.build());
    }

    /** Informational SimpleForm with a single close button. */
    public void sendNotice(Player player, String title, String content) {
        UUID uuid = player.getUniqueId();
        SimpleForm form = SimpleForm.builder()
                .title(title)
                .content(content)
                .button(plugin.messages().raw("general.close"))
                .validResultHandler(response -> onMain(uuid, p -> p.closeInventory()))
                .build();
        send(player, form);
    }

    private void send(Player player, org.geysermc.cumulus.form.Form form) {
        try {
            GeyserApi api = GeyserApi.api();
            if (api == null) {
                return;
            }
            api.sendForm(player.getUniqueId(), form);
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("Failed to send a Bedrock form to "
                    + player.getName() + ": " + e.getMessage());
        }
    }
}
