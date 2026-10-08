# cetus-android — AI 协作规则

cetus Android 客户端。配对插件源码在仓库根目录 `src/`；版本基线以 `../../docs/COMPATIBILITY.md` 为准，本仓库不另维护版本表。

连接方式只有两条路：局域网直连与 DLP/1 远程（`core/remote/`）。选路在 `RouteSelector`（每次新建连接探测局域网，按网络变化作废），传输在 `HostHttp`；两条路都钉扎同一张插件证书。中继转来的拒绝码只能用来提示，删除或改写本机凭据只认插件在内层 TLS 上的答复（RFC §7.4）。

## 红线

- **绝不**对 release 变体跑 `connectedReleaseAndroidTest`（AGP 9 也不为 release 构建 androidTest）：AGP 设备测试收尾会卸载被测包，连同用户配对数据——2026-09-12 发生过。设备测试一律走 debug 变体（`applicationIdSuffix = ".debug"`）+ 手动 `adb install` + `am instrument`，跑完手动卸载两个 debug 包。
- HyperOS/MIUI 真机跑 Compose UI 测试前，需给 debug 包授「后台弹出界面 → 始终允许」（安全中心 → 应用详情 → 权限管理 → 其他权限），否则测试 Activity 被拦、uid 被冻结、进程被 OneKeyClean 强杀，表象是 "Process crashed"。
- 正式包只发签名 APK（签名环境自动读取 `~/Library/Application Support/DSH Links Signing/env`）。
- 联调/复现用 `node scripts/dev-isolated-host.mjs`（临时 `stateDir` + 避开 18640 端口）；它读取用户真实的 `~/.dsh` 会话数据，**只许读**——不得发送 prompt、新建/归档/删除/重命名会话、注册/删除工作区或改设置。
- **连接池驱逐（`connectionPool.evictAll()`）会写 TLS 关闭帧，是真网络 I/O**：绝不能在主线程直接调用（2026-09-30 真机闪退即此）。统一经 `HostHttp.evictPools`（主线程自动转后台）；`MainThreadNetworkTest` 保证只有 `HostHttp.kt` 能碰连接池。

## 门禁命令

- 完整门禁：`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
- 尺寸裸 dp 只降不升：`size-baseline.txt` 由 `DshSizeUsageTest` 守护（含零容忍项 `__touch_below_48__`）；新增裸 dp 必须同步基线，不许上调。
- 单元测试：`./gradlew testDebugUnitTest`
- 构建：`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` / `:app:assembleRelease`（自动签名）
- 真机测试完整命令见 `README.md`「真机设备测试」一节。

## 截图基线

- Compose 截图基线**只允许**由 `.github/workflows/regen-screenshots.yml` 产出；禁止在开发机上生成后提交基线图片。
- UI 改动影响截图后，先手动触发该 workflow 生成新基线，再把生成的 reference 图片提交到 PR 中。

## 深入文档

| 主题 | 文件 |
|---|---|
| 全仓架构总览（分层 / 目录契约 / 数据流） | `../../docs/ARCHITECTURE.md` |
| 手机同步契约（字段级） | `../../docs/MOBILE_SYNC_CONTRACT.md` |
| 兼容矩阵 / DSH 基线 | `../../docs/COMPATIBILITY.md` |
| 远程连接协议（DLP/1） | `../../docs/rfc/0001-dlp1-remote-pipe.md` |
