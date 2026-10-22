// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VIS33552 (VIASR)

package dev.vis33552.handshakefix;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkChannel;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接建立后：找出“本地有、服务端协商结果里没有”的通道。
 *
 * <ul>
 *   <li>把它们补进 {@link NetworkPayloadSetup}，否则 mod 一发包
 *       {@code NetworkRegistry.checkPacket} 会直接抛 UnsupportedOperationException；</li>
 *   <li>同时登记到 {@link GuardState}，由 {@link PacketDropFilter} 在 pipeline 里把
 *       这些包丢掉 —— 不能真发出去，服务端收到未知通道会以
 *       “No Channel for …” 主动断开。</li>
 * </ul>
 */
public final class ConnectionPatcher {

    private ConnectionPatcher() {
    }

    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            patch(event.getConnection());
        } catch (Throwable t) {
            HandshakeFix.LOG.warn("[HandshakeFix] failed to patch connection: {}", t.toString());
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        GuardState.resetConnection();
    }

    private static void patch(Connection connection) {
        if (connection == null) {
            return;
        }

        NetworkPayloadSetup setup = ChannelAttributes.getPayloadSetup(connection);
        if (setup == null) {
            // 没有协商结果（对面不是 NeoForge，或者连接阶段就失败了）
            return;
        }

        Map<Identifier, String> local = GuardState.localRegistrations();
        if (local.isEmpty()) {
            return;
        }

        Map<ConnectionProtocol, Map<Identifier, NetworkChannel>> original = setup.channels();
        List<Identifier> missing = new ArrayList<>();
        for (Identifier id : local.keySet()) {
            if (!containsAnywhere(original, id)) {
                missing.add(id);
            }
        }

        if (missing.isEmpty()) {
            HandshakeFix.LOG.debug("[HandshakeFix] all {} local channel(s) negotiated with the server", local.size());
            return;
        }

        if (!HandshakeFixConfig.dropUnsupported()) {
            HandshakeFix.LOG.warn(
                    "[HandshakeFix] server has no channel for {} local payload(s) and "
                            + "'silently_drop_unsupported' is off; those mods may throw when sending: {}",
                    missing.size(), missing);
            GuardState.block(missing);
            return;
        }

        // 1) 扩充 setup，让 checkPacket 认为是“已协商”的通道
        Map<ConnectionProtocol, Map<Identifier, NetworkChannel>> merged = new LinkedHashMap<>();
        for (Map.Entry<ConnectionProtocol, Map<Identifier, NetworkChannel>> entry : original.entrySet()) {
            merged.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        for (Identifier id : missing) {
            String version = local.getOrDefault(id, "1");
            for (Map<Identifier, NetworkChannel> channels : merged.values()) {
                channels.putIfAbsent(id, new NetworkChannel(id, version));
            }
        }
        ChannelAttributes.setPayloadSetup(connection, new NetworkPayloadSetup(merged));

        // 2) 登记为“要丢弃的包”并装 pipeline 过滤器
        GuardState.block(missing);
        installFilter(connection);

        HandshakeFix.LOG.info(
                "[HandshakeFix] server is missing {} of {} local channel(s); "
                        + "they are disabled and their packets will be dropped instead of kicking you: {}",
                missing.size(), local.size(), missing);
    }

    private static boolean containsAnywhere(Map<ConnectionProtocol, Map<Identifier, NetworkChannel>> setup, Identifier id) {
        for (Map<Identifier, NetworkChannel> channels : setup.values()) {
            if (channels.containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    private static void installFilter(Connection connection) {
        Channel channel = connection.channel();
        if (channel == null) {
            HandshakeFix.LOG.warn("[HandshakeFix] connection has no netty channel yet, cannot drop packets");
            return;
        }
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(PacketDropFilter.NAME) == null) {
                    channel.pipeline().addLast(PacketDropFilter.NAME, new PacketDropFilter());
                }
                GuardState.markFilterInstalled();
            } catch (Throwable t) {
                HandshakeFix.LOG.warn("[HandshakeFix] cannot install pipeline filter: {}", t.toString());
            }
        });
    }
}
