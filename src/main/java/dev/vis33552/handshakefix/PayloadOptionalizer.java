// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VIS33552 (VIASR)

package dev.vis33552.handshakefix;

import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 核心：把 NeoForge 里“本客户端注册的所有 payload”统一改成 optional。
 *
 * <p>26.2 的协商流程是：客户端把 {@code PayloadRegistration.optional()} 打进
 * {@code ModdedNetworkQueryPayload} 发给服务端，服务端
 * {@code NetworkComponentNegotiator} 里对 optional 的缺席通道只做 disable，
 * 对非 optional 的缺席通道直接判失败 → 客户端收到
 * {@code ModdedNetworkSetupFailedPayload} → 弹出 ModMismatch 屏幕并断开。
 * 所以把 optional 置 true 就能从根上消掉那句“客户端不兼容！请使用 NeoForge …”。</p>
 */
public final class PayloadOptionalizer {

    private static final String REGISTRY = "net.neoforged.neoforge.network.registration.NetworkRegistry";
    private static final String FIELD = "PAYLOAD_REGISTRATIONS";

    private static boolean warned;
    private static int attempts;

    private PayloadOptionalizer() {
    }

    /**
     * 幂等：可以重复调用。
     *
     * @return 本次被改成 optional 的通道数量
     */
    public static synchronized int apply() {
        Map<?, ?> outer;
        try {
            Class<?> registry = Class.forName(REGISTRY);
            Field field = registry.getDeclaredField(FIELD);
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof Map<?, ?> map)) {
                warnOnce("PAYLOAD_REGISTRATIONS is not a Map: " + value);
                return 0;
            }
            outer = map;
        } catch (Throwable t) {
            warnOnce("cannot reach NetworkRegistry.PAYLOAD_REGISTRATIONS: " + t);
            return 0;
        }

        Map<Identifier, String> local = new LinkedHashMap<>();
        int changed = 0;
        int total = 0;

        for (Object innerObject : outer.values()) {
            if (!(innerObject instanceof Map<?, ?> inner)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<Identifier, PayloadRegistration<?>> registrations = (Map<Identifier, PayloadRegistration<?>>) inner;

            for (Map.Entry<Identifier, PayloadRegistration<?>> entry : new ArrayList<>(registrations.entrySet())) {
                PayloadRegistration<?> registration = entry.getValue();
                if (registration == null) {
                    continue;
                }
                total++;
                local.put(entry.getKey(), registration.version());

                if (registration.optional()) {
                    continue;
                }
                if (!forceOptional()) {
                    continue;
                }

                registrations.put(entry.getKey(), asOptional(registration));
                changed++;
            }
        }

        GuardState.setLocalRegistrations(local);
        GuardState.addOptionalized(changed);
        GuardState.setSeenTotal(total);
        if (total > 0) {
            GuardState.markPatchDone();
        }

        if (changed > 0) {
            HandshakeFix.LOG.info(
                    "[HandshakeFix] {} / {} payload channel(s) downgraded to optional; "
                            + "a server that misses these mods will no longer disconnect you.",
                    changed, total);
        } else if (total > 0) {
            HandshakeFix.LOG.debug("[HandshakeFix] {} payload channel(s) already optional", total);
        } else {
            HandshakeFix.LOG.debug("[HandshakeFix] payload registrations are still empty "
                    + "(NetworkRegistry.setup() has not run yet)");
        }
        return changed;
    }

    /**
     * 主菜单每 tick 调用的兜底：直到真正看到注册表内容为止。
     * 正常情况下 {@link RegisterPayloadHandlersEvent} 那一下就已经打完了，这里不会做任何事。
     */
    public static void applyIfNeeded() {
        if (GuardState.patchDone()) {
            return;
        }
        apply();
        if (!GuardState.patchDone() && ++attempts == 200) {
            warnOnce("still no payload registrations after 200 attempts; the patch is NOT active");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PayloadRegistration<?> asOptional(PayloadRegistration<?> registration) {
        // record 的规范构造器是 public 的，直接复制一份并把 optional 置 true，
        // 不去改 record 的 final 字段（更稳）。
        return new PayloadRegistration(
                registration.type(),
                registration.codec(),
                registration.protocols(),
                registration.flow(),
                registration.version(),
                true);
    }

    private static boolean forceOptional() {
        try {
            return HandshakeFixConfig.forceOptional();
        } catch (Throwable ignored) {
            // 配置类还没初始化（例如独立测试环境）时按默认值处理
            return true;
        }
    }

    private static void warnOnce(String message) {
        if (!warned) {
            warned = true;
            HandshakeFix.LOG.warn("[HandshakeFix] {} (this NeoForge build may differ from 26.2)", message);
        }
    }
}
