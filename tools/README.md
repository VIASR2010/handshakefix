# tools/ — 不启动游戏也能验证补丁

`Probe.java` 直接在真实的 NeoForge 26.2 jar 上跑一遍完整链路，不加载渲染、不登录：

1. 用 `NetworkRegistry.register(...)` 注册一个**必需**通道（和普通 mod 完全一样）；
2. 跑 `PayloadOptionalizer.apply()`（模组里真正执行的那段代码）；
3. 检查注册表里的记录是否变成 `optional=true`；
4. 检查客户端真正发给服务端的 `ModdedNetworkQueryPayload` 里是不是 `optional=true`；
5. 用 `NetworkComponentNegotiator.negotiate()` 对着**没有这个 mod 的服务端**协商：
   - 必需通道 → 失败（这就是踢人的那条路径）；
   - optional 通道 → 成功，并且最终 `NetworkPayloadSetup` 里没有这个通道（= 服务端眼里不存在）。

期望输出：

```
[probe] before: optional=false version=1
[HandshakeFix] 1 / 1 payload channel(s) downgraded to optional; ...
[probe] patch changed 1 registration(s)
[probe] after: optional=true
[probe] query component optional=true
[probe] optional vs empty server => success=true
[probe] required vs empty server => success=false
[probe] server-side setup channels=[]
[probe] OK - for the server the mod simply does not exist (no channel, no handler)
```

## 运行

```powershell
# 先跑一次离线构建，生成 build/classpath.txt
powershell -NoProfile -ExecutionPolicy Bypass -File build-offline.ps1

# 再跑 probe
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-probe.ps1
```

`run-probe.ps1` 依赖 `build/classpath.txt`（由 `build-offline.ps1` 写出），
默认 JDK 路径 `C:\Users\111\tools\jdk25\jdk-25.0.4.1+1`，可用 `-Jdk` 覆盖。

> 注意：`negotiate(required, empty)` 那一步会因为 `ModList.get()` 为 null 抛 NPE
> （只在构造失败原因时才访问 ModList），probe 把它当作“协商失败”处理，属于预期。
