// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VIS33552 (VIASR)

package dev.vis33552.handshakefix;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

/**
 * HandshakeFix — 客户端专用。
 *
 * <p>作用：把本客户端所有 mod 的 payload 通道在“协商阶段”降级成 optional，
 * 这样服务端少装某个 mod 时，NeoForge 只会禁用那几条通道，而不是直接断开连接
 * 并弹出“客户端不兼容！请使用 NeoForge x.y.z”。</p>
 *
 * <p>时序（26.2 实测）：{@code NetworkRegistry.setup()} 在
 * {@code CommonModLoader.load()} 的最后一个任务 “Network registry lock” 里执行，
 * 比 {@code FMLClientSetupEvent}、{@code FMLLoadCompleteEvent} 都晚 ——
 * 在这两个事件里扫注册表只会扫到空表（第一版就是踩了这个坑）。
 * 可靠的注入点是 {@link RegisterPayloadHandlersEvent}（LOWEST 优先级，在所有 mod
 * 注册完之后、事件返回之前），以及主菜单每 tick 的一次兜底重试。</p>
 */
@Mod(value = HandshakeFix.MODID, dist = Dist.CLIENT)
public final class HandshakeFix {

    public static final String MODID = "handshakefix";
    public static final Logger LOG = LogUtils.getLogger();

    public HandshakeFix(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, HandshakeFixConfig.SPEC);

        modBus.addListener(this::onClientSetup);
        // 关键：所有 mod 注册完 payload 之后立刻打补丁（此时 PAYLOAD_REGISTRATIONS 才有内容）
        modBus.addListener(EventPriority.LOWEST,
                (RegisterPayloadHandlersEvent event) -> PayloadOptionalizer.apply());

        // 兜底：万一优先级被别的 mod 抢了，主菜单每 tick 重试，直到成功为止
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> PayloadOptionalizer.applyIfNeeded());

        NeoForge.EVENT_BUS.addListener(ConnectionPatcher::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(ConnectionPatcher::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(HandshakeFixCommand::onRegisterCommands);

        LOG.info("[HandshakeFix] loaded (client-side channel downgrade enabled)");
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // 此时通道通常还没注册，只是无害尝试；真正的注入点在上面那个事件。
        event.enqueueWork(PayloadOptionalizer::applyIfNeeded);
    }
}
