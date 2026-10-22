// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VIS33552 (VIASR)

package dev.vis33552.handshakefix;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;

/**
 * 出站过滤器：把“服务端没有的通道”的 payload 在离开客户端前丢掉。
 *
 * <p>为什么必须丢而不是让它发出去：26.2 服务端 {@code NetworkRegistry.handleModdedPayload}
 * 遇到未协商的通道会直接
 * {@code disconnect("... (No Channel for <id>)")}，也就是照样把你踢下线。
 * 这里在 Netty pipeline 最外层拦下来，promise 直接 success，表现就像这个包从来没发过。</p>
 */
public final class PacketDropFilter extends ChannelDuplexHandler {

    public static final String NAME = "handshakefix_drop";

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ServerboundCustomPayloadPacket packet) {
            Identifier id = payloadId(packet);
            if (id != null && GuardState.isBlocked(id)) {
                if (HandshakeFixConfig.logDrops()) {
                    HandshakeFix.LOG.info("[HandshakeFix] dropped payload {} (channel not negotiated)", id);
                }
                promise.setSuccess();
                return;
            }
        }
        ctx.write(msg, promise);
    }

    private static Identifier payloadId(ServerboundCustomPayloadPacket packet) {
        try {
            return packet.payload().type().id();
        } catch (Throwable t) {
            return null;
        }
    }
}
