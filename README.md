# HandshakeFix / 握手修复

NeoForge **26.2** 客户端模组：服务端少装某些 mod 时不再把你踢下线，
消掉 `客户端不兼容！请使用 NeoForge 26.2.0.90` 和一堆
`模组的网络通道 "xxx" 连接失败：服务端缺少此客户端需要的网络通道！`。

- 只装客户端；服务端不用装，也不影响服务端。
- 不改任何 mod 的 jar，只在运行时调整 NeoForge 的网络协商数据。
- 构建产物：`build/libs/handshakefix-1.1.0.jar`

---

## 1. 它解决的是什么

26.2 的 NeoForge 在配置阶段会做一次通道协商：

```
客户端  --ModdedNetworkQueryPayload(每个通道 id / version / flow / optional)-->  服务端
服务端  --NetworkComponentNegotiator.negotiate()-->  成功: NetworkPayloadSetup
                                                     失败: ModdedNetworkSetupFailedPayload
```

规则：**客户端标记为必需（`optional=false`）的通道，服务端没有 → 判失败**。
失败时客户端弹 `ModMismatchDisconnectedScreen`，断开原因被统一写成
`客户端不兼容！请使用 NeoForge x.y.z`，所以看着像版本问题，其实不是。

26.2 里每个 payload 默认就是“必需”，模组作者必须主动 `.optional()` 才会变宽松。
本模组做的事就是把这个默认值翻转回来。

## 2. 原理（三步）

| # | 动作 | 目的 |
|---|------|------|
| 1 | 在 `RegisterPayloadHandlersEvent`（`EventPriority.LOWEST`）里，把本客户端所有 `PayloadRegistration` 复制成 `optional=true` | 服务端缺通道时只“禁用”，不再判失败 → 不踢人 |
| 2 | 进服后把服务端没有的通道补进本连接的 `NetworkPayloadSetup` | 否则 mod 一发包，`NetworkRegistry.checkPacket` 直接抛 `UnsupportedOperationException` |
| 3 | 在 Netty 出站 pipeline 上丢弃这些通道的包 | 真发出去服务端会以 `No Channel for <id>` 主动断开（`handleModdedPayload` 里写死的） |

第 1 步是核心；2、3 是保证进服后不会因为“残余发包”再被踢。

### 时序坑（1.0.0 为什么没生效）

26.2 的 `NetworkRegistry.setup()` 是 `CommonModLoader.load()` 的**最后一个任务**：

```
Sided setup → Registration events → Enqueue IMC → Process IMC
           → Complete loading of N mods   (FMLLoadCompleteEvent)
           → Network registry lock        ← NetworkRegistry.setup() 在这里
```

也就是说在 `FMLClientSetupEvent` / `FMLLoadCompleteEvent` 里扫 `PAYLOAD_REGISTRATIONS`
只能扫到空表。1.0.1 起改为在事件内部（LOWEST 优先级）+ 主菜单每 tick 兜底。

## 3. 已做的验证

`tools/Probe.java` 不启动游戏，直接在真实 26.2 jar 上跑完整链路：

```
[probe] before: optional=false version=1
[HandshakeFix] 1 / 1 payload channel(s) downgraded to optional; ...
[probe] after: optional=true
[probe] query component optional=true
[probe] optional vs empty server => success=true
[probe] required vs empty server => success=false
[probe] server-side setup channels=[]
[probe] OK - for the server the mod simply does not exist (no channel, no handler)
```

同一条通道：不处理 → 协商失败（踢人）；降级后 → 协商成功，且服务端最终的
`NetworkPayloadSetup` 里根本没有它。

运行方式见 [`tools/README.md`](tools/README.md)。

## 4. 安装 / 卸载

1. 把 `handshakefix-1.1.0.jar` 放进实例的 `mods` 目录。
2. **完全重启游戏**（不是重连）。
3. 直接连服务器。

日志关键行：

```
[HandshakeFix] loaded (client-side channel downgrade enabled)
[HandshakeFix] NN / NN payload channel(s) downgraded to optional; ...
[HandshakeFix] server is missing N of NN local channel(s); they are disabled and their packets will be dropped ...
```

卸载：删掉 mods 里的 jar（以及 `config/handshakefix-client.toml`），无残留。

## 5. 配置与命令

`config/handshakefix-client.toml`：

```toml
force_optional = true              # 核心开关：所有通道降级为 optional
silently_drop_unsupported = true   # 补通道 + 丢弃服务端没有的包
log_dropped_payloads = false       # 每丢一个包写一行日志（排查用）
verbose_channel_list = false
```

游戏内 `/handshakefix`：打印已降级 / 已禁用通道数与过滤器状态。

## 6. 和 1.12.2 Forge 的对应关系

| 1.12.2 Forge | NeoForge 26.2 | 本模组 |
|---|---|---|
| 通道要双方都注册才启用（交集），客户端多出来的通道服务端直接忽略 | 每个 payload 默认 required，缺了就踢 | 全部翻成 optional |
| 不写 `NetworkCheckHandler` 就不校验 mod 一致性 | 协商失败 → `ModdedNetworkSetupFailedPayload` | optional 缺席只 disable，不判失败 |
| 服务端收到未知通道**静默丢弃** | `handleModdedPayload` 遇到未协商通道会 `disconnect(...)` | 客户端出站处丢弃，模拟“静默忽略” |
| 客户端 mod 的物品 / JEI 分页还在，服务端却没有它 | 完全一样 | 不用处理 |

字面意义地“把通道从查询里删掉”反而更差：查询是握手一开始单向发的，客户端此时
不知道服务端有什么；若服务端其实装了该 mod 并标为必需，服务器会反过来以
“客户端缺少 mod”拒绝你。optional 是唯一“服务端看不见 + 双向安全”的形式。

## 7. 已知限制

- 只解决“进不去”。服务端没有的 mod，其**联机功能**依然不可用（例如
  Effortless Building 的 undo/redo、AE2 数量同步），纯客户端功能不受影响。
- 如果某个 mod 在 **configuration 阶段**就发包、而通道又被禁用，可能仍会报错；
  这种情况只能删掉那个 mod。
- 服务端装了同 mod 但版本不同，会被降级成“禁用”而不是报错。

## 8. 构建

### A. 离线（用本机已装好的 26.2 实例，不需要网络和 Gradle）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build-offline.ps1
```

脚本流程：解析实例的版本 json → 拼运行期 classpath（并补上 NeoForge universal +
patched client jar）→ `javac --release 21` → 检查 mods.toml 无 BOM → 打 jar。

需要 JDK 25（26.2 的 class 文件版本是 69），默认路径
`C:\Users\111\tools\jdk25\jdk-25.0.4.1+1`，可用 `-Jdk` 覆盖。

### B. Gradle / CI

```powershell
gradle build
```

`.github/workflows/build.yml` 会在 push / PR / tag 时用 JDK 25 + Gradle 构建，
并把 jar 作为 artifact 上传。`gradle.properties` 固定了
`minecraft_version=26.2`、`neo_version=26.2.0.90`；
若 ModDevGradle 版本不支持当时的 26.x，把 `build.gradle` 里的
`net.neoforged.moddev` 换成最新版即可。

## 9. 仓库结构

```
handshakefix/
  src/main/java/dev/vis33552/handshakefix/
    HandshakeFix.java          入口（@Mod, dist=CLIENT）
    HandshakeFixConfig.java    CLIENT 配置
    PayloadOptionalizer.java   核心：把 PAYLOAD_REGISTRATIONS 全部改成 optional
    ConnectionPatcher.java     进服后算“服务端缺哪些通道”，补 setup + 装过滤器
    PacketDropFilter.java      Netty 出站丢弃未协商通道的包
    GuardState.java            运行状态（命令 / 日志用）
    HandshakeFixCommand.java   /handshakefix
  src/main/resources/META-INF/neoforge.mods.toml
  tools/
    Probe.java                 无头验证（见第 3 节）
    run-probe.ps1
    README.md
  build-offline.ps1            离线构建（Windows）
  build.gradle / settings.gradle / gradle.properties
  .github/workflows/build.yml  CI
  CHANGELOG.md  LICENSE  README.md
```

反射点只有一处：`NetworkRegistry.PAYLOAD_REGISTRATIONS`
（`Map<ConnectionProtocol, Map<Identifier, PayloadRegistration<?>>>`，内层是 `HashMap`，
可直接替换 value）。如果以后 NeoForge 改名，日志会打印
`cannot reach NetworkRegistry.PAYLOAD_REGISTRATIONS`，改 `PayloadOptionalizer.FIELD` 即可。

## 10. 发布到 GitHub

```bash
cd handshakefix
git init
git add .
git commit -m "HandshakeFix 1.1.0"
git branch -M main
git remote add origin https://github.com/VIASR/handshakefix.git
git push -u origin main
```

发版建议：

```bash
git tag v1.1.0
git push origin v1.1.0
```

然后在 GitHub 的 Releases 页面新建 `v1.1.0`，附件上传
`build/libs/handshakefix-1.1.0.jar`，正文可以直接贴 `CHANGELOG.md` 的 1.1.0 段。

`.gitignore` 已经排除 `build/`、`tools/out/`、`*.jar`，仓库里只留源码。

## 11. 版本历史

见 [CHANGELOG.md](CHANGELOG.md)。

## License

[GNU General Public License v3.0](LICENSE)（`GPL-3.0-only`）

Copyright (C) 2026 VIS33552 (GitHub: [VIASR](https://github.com/VIASR))

- 允许使用、修改、再分发，**但分发修改版必须同样以 GPL-3.0 开源并提供源码**。
- 如果只是想改一下自用、不发布，GPL 不会约束你。
- 与 Mojang / NeoForge 无关；这是非官方客户端补丁。
