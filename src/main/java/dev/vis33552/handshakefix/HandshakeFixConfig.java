// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VIS33552 (VIASR)

package dev.vis33552.handshakefix;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class HandshakeFixConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue FORCE_OPTIONAL;
    public static final ModConfigSpec.BooleanValue DROP_UNSUPPORTED;
    public static final ModConfigSpec.BooleanValue LOG_DROPS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        FORCE_OPTIONAL = builder
                .comment(
                        "把本客户端所有 mod 的网络通道标记为 optional。",
                        "服务端没装某个 mod 时，NeoForge 只会禁用那些通道，不会断开连接。",
                        "关掉这个选项本 mod 就基本没用了。")
                .define("force_optional", true);

        DROP_UNSUPPORTED = builder
                .comment(
                        "把服务端没有的通道补进当前连接（避免 checkPacket 抛异常），",
                        "并在发送时把这些包静默丢弃（避免服务端以“未知通道”断开你）。",
                        "如果关掉：对应 mod 主动发包时会直接抛 UnsupportedOperationException。")
                .define("silently_drop_unsupported", true);

        LOG_DROPS = builder
                .comment("每丢弃一个包就在日志里写一行（排查用，平时建议关闭）。")
                .define("log_dropped_payloads", false);

        builder.comment("是否记录被强制 downgrade 的通道清单（只在 DEBUG 级别输出）。")
                .define("verbose_channel_list", false);

        SPEC = builder.build();
    }

    private HandshakeFixConfig() {
    }

    public static boolean forceOptional() {
        return get(FORCE_OPTIONAL, true);
    }

    public static boolean dropUnsupported() {
        return get(DROP_UNSUPPORTED, true);
    }

    public static boolean logDrops() {
        return get(LOG_DROPS, false);
    }

    private static boolean get(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (Throwable ignored) {
            // 配置还没加载完的时候用默认值
            return fallback;
        }
    }

    /** 仅供命令/调试用。 */
    public static List<String> describe() {
        return List.of(
                "force_optional=" + forceOptional(),
                "silently_drop_unsupported=" + dropUnsupported(),
                "log_dropped_payloads=" + logDrops());
    }
}
