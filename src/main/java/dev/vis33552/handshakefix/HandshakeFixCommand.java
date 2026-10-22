// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VIS33552 (VIASR)

package dev.vis33552.handshakefix;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.Set;

public final class HandshakeFixCommand {

    private HandshakeFixCommand() {
    }

    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("handshakefix")
                .executes(context -> status(context.getSource())));
    }

    private static int status(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "[HandshakeFix] optional=" + GuardState.optionalized()
                        + " blocked=" + GuardState.blockedCount()
                        + " filter=" + GuardState.filterInstalled()
                        + " " + String.join(" ", HandshakeFixConfig.describe())), false);

        Set<Identifier> blocked = GuardState.blockedIds();
        if (!blocked.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[HandshakeFix] disabled channels: " + blocked), false);
        }
        return 1;
    }
}
