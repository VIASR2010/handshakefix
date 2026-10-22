# Changelog

## 1.1.0

更名版本：模组标识从 `channelguard` 改为 `handshakefix`，显示名 **HandshakeFix**。

### Changed

- `modId`：`channelguard` → `handshakefix`（FML 校验规则是 `^[a-z][a-z0-9_.-]{1,63}$`，ID 必须全小写）。
- 显示名：Channel Guard / 通道守卫 → **HandshakeFix / 握手修复**。
- Java 包：`dev.vis33552.channelguard` → `dev.vis33552.handshakefix`；主类 `ChannelGuard` → `HandshakeFix`。
- 客户端命令：`/channelguard` → `/handshakefix`。
- 配置文件：`config/channelguard-client.toml` → `config/handshakefix-client.toml`（旧的可以删掉）。
- 仓库 / 发布链接：`github.com/VIASR/handshakefix`。

## 1.0.1

修复两个真正导致“装了没用 / 装了反而打不开”的问题。

### Fixed

- **补丁时机错误（1.0.0 完全没生效）**
  26.2 的 `NetworkRegistry.setup()` 是 `CommonModLoader.load()` 的最后一个任务
  （“Network registry lock”），比 `FMLClientSetupEvent` / `FMLLoadCompleteEvent` 都晚。
  1.0.0 在这两个事件里扫描 `PAYLOAD_REGISTRATIONS`，只能扫到空表，
  日志是 `no payload registrations found yet`，等于什么都没做。
  现在改为在 `RegisterPayloadHandlersEvent` + `EventPriority.LOWEST` 里打补丁，
  并用主菜单 `ClientTickEvent.Post` 每 tick 兜底重试。

- **`neoforge.mods.toml` 带 UTF-8 BOM 导致 FML 拒绝加载**
  报错：`Invalid bare key: '?modLoader'` → `File mods/... is not a valid mod file`
  → 游戏直接启动失败。现在 `build-offline.ps1` 会在打包前检查 BOM 并直接报错。

### Added

- `/handshakefix` 客户端命令：查看已降级 / 已禁用的通道数与过滤器状态。
- `config/handshakefix-client.toml`：`force_optional`、`silently_drop_unsupported`、
  `log_dropped_payloads`、`verbose_channel_list`。
- `tools/Probe.java`：不启动游戏就能验证补丁（真实 26.2 jar 上跑协商流程）。
- GitHub Actions 构建工作流。

### Changed

- 许可证从 MIT 改为 **GPL-3.0-only**（`LICENSE` 换成 GPL-3.0 全文，源码加 SPDX 头）。
- 作者/链接指向 `VIS33552 (VIASR)` 与 <https://github.com/VIASR/handshakefix>。

## 1.0.0

- 首个版本：把客户端所有 payload 通道降级为 optional；进服后补齐通道并在出站丢弃服务端没有的包。
