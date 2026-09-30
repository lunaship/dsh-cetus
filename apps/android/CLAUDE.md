# deeplinks — AI 协作规则

DeepLinks Android 客户端。配对插件在 `../docs/COMPATIBILITY.md`；版本基线以 `../docs/COMPATIBILITY.md` 为准，本仓库不另维护版本表。

连接方式只有两条路：局域网直连与 DLP/1 远程（`core/remote/`）。选路在 `RouteSelector`（每次新建连接探测局域网，按网络变化作废），传输在 `HostHttp`；两条路都钉扎同一张插件证书。中继转来的拒绝码只能用来提示，删除或改写本机凭据只认插件在内层 TLS 上的答复（RFC §7.4）。

## 红线

- **绝不**对 release 变体跑 `connectedReleaseAndroidTest`（AGP 9 也不为 release 构建 androidTest）：AGP 设备测试收尾会卸载被测包，连同用户配对数据——2026-09-12 发生过。设备测试一律走 debug 变体（`applicationIdSuffix = ".debug"`）+ 手动 `adb install` + `am instrument`，跑完手动卸载两个 debug 包。
- HyperOS/MIUI 真机跑 Compose UI 测试前，需给 debug 包授「后台弹出界面 → 始终允许」（安全中心 → 应用详情 → 权限管理 → 其他权限），否则测试 Activity 被拦、uid 被冻结、进程被 OneKeyClean 强杀，表象是 "Process crashed"。
- 正式包只发签名 APK（签名环境自动读取 `~/Library/Application Support/DSH Links Signing/env`）。

## 门禁命令

- 完整门禁：`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
- 尺寸裸 dp 只降不升：`size-baseline.txt` 由 `DshSizeUsageTest` 守护（含零容忍项 `__touch_below_48__`）；新增裸 dp 必须同步基线，不许上调。
- 单元测试：`./gradlew testDebugUnitTest`
- 构建：`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` / `:app:assembleRelease`（自动签名）
- 真机测试完整命令见 `README.md`「真机设备测试」一节。

## 深入文档

| 主题 | 文件 |
|---|---|
| 全仓架构总览（分层 / 目录契约 / 数据流） | `../../docs/ARCHITECTURE.md` |
| 手机同步契约（字段级） | `../../docs/MOBILE_SYNC_CONTRACT.md` |
| 兼容矩阵 / DSH 基线 | `../../docs/COMPATIBILITY.md` |
| 远程连接协议（DLP/1） | `../../docs/rfc/0001-dlp1-remote-pipe.md` |
