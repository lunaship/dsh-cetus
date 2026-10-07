# Cetus 实施环境笔记

记录本机（macOS / Xcode 27.1 / Apple Silicon）执行仓库门禁时的**环境性**问题，
与代码改动无关。已确认在 `origin/main` 干净树上同样复现。

## iOS 构建

### 门禁命令（与 CI 一致，通过）

```sh
cd apps/ios
xcodegen generate
xcodebuild -scheme DeepLinks \
  -destination 'generic/platform=iOS Simulator' \
  -configuration Debug build \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
# Release 同样
```

**验证结果：Debug ✅ Release ✅ BUILD SUCCEEDED**

### 注意：destination 必须用 generic

用 `-destination 'platform=iOS Simulator,name=iPhone 17 Pro'` 构建 app target 会报
`Unable to resolve module dependency: 'DLCore' / 'DLNet' ...`。
这是**本机 DerivedData 缓存污染**造成的假失败，不是代码问题：

- 清理 `~/Library/Developer/Xcode/DerivedData/DeepLinks-*` 后用 CI 的
  `generic/platform=iOS Simulator` 命令即通过。
- 单独构建 `-scheme DLNet` 也通过。

### 未解决：`xcodebuild test` 本机失败（pre-existing）

`xcodebuild -scheme DeepLinks ... test` 在本机失败，两类错误：

1. `ld: symbol(s) not found for architecture x86_64`
   — test action 同时编译 arm64 与 x86_64（Rosetta）切片，而本地 Swift Package
   产物只有 arm64，x86_64 链接失败。CI 跑在 `macos-26`，不受影响。
2. `Unable to resolve Swift module dependency to a compatible module: 'DeepLinks'`
   （`Tests/ChatControlsA3Tests.swift`）

**已确认与本轮改动无关**：在 `origin/main` 的干净 worktree 上执行同样的测试命令，
出现完全相同的错误。

**状态**：`xcodebuild test` 走 app 测试 target 时本机仍失败（架构切片问题）。
不过 **Swift Package 自身的单测可以用 iOS Simulator destination 跑通** ——
这是 app-rebrand 在 B4 期间实测的修正结论：

```sh
# macOS 宿主跑不通（'HPKE' is only available in macOS 14.0+，包只声明 .iOS(.v26)）
swift test
# 指定 iOS Simulator destination 可以跑通
xcodebuild -scheme DLSecurity -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test
# → TEST SUCCEEDED 8/8
```

所以本机验证 iOS 改动的可行组合是：**app 用 build（CI 命令）+ swift-format lint，
包级逻辑用 iOS destination 单测**。app 测试 target 的完整测试证据以 CI 为准。

## 磁盘空间（会导致构建莫名失败）

**根卷 `/` 容量紧张**（228Gi，实际可用一度只剩 235Mi）。
`xcodebuild` 已实测因 `No space left on device` 失败 —— 这类失败**看起来像代码问题，其实是磁盘**。

Xcode 的 `DerivedData` 会持续累积多个项目的副本（本次清理前 5.6G，其中
`ModuleCache.noindex` 1.8G + `SDKExplicitPrecompiledModules` 876M + 4 个历史
`DeepLinks-*` / `Cetus-*` 目录）。清理后 `/` 从 235Mi 恢复到 4.4Gi。

```sh
du -sh ~/Library/Developer/Xcode/DerivedData          # 看占用
# 删掉非当前项目的目录（保留正在用的那个）
rm -rf ~/Library/Developer/Xcode/DerivedData/DeepLinks-* \
       ~/Library/Developer/Xcode/DerivedData/ModuleCache.noindex \
       ~/Library/Developer/Xcode/DerivedData/SDKExplicitPrecompiledModules
```

**排查提示**：构建失败时除了看错误信息，也 `df -h /` 确认磁盘余量。

**补充（app-rebrand 实测的更精确位置）**：那次 `No space left on device` 发生在写
**测试结果 bundle** 到 `/var/folders/...` 时，而 `/var/folders` 属于
`/System/Volumes/Data`（与 `/` 是两个不同的卷）。所以：

```sh
df -h /                                   # 系统卷
df -h /var/folders                        # Data 卷（测试结果 bundle 写这里）
```

只清 `~/Library/Developer/Xcode/DerivedData` **不一定**能救回 Data 卷。
另外 `swift test` / `swift build` 会在 `apps/ios/Packages/*/.build` 留下产物（本次 81M+），
可以用 `rm -rf apps/ios/Packages/*/.build` 清理。

## swift-format

本机 `swift-format` 不在 PATH，用 xcrun 调用：

```sh
cd apps/ios
xcrun swift-format lint --strict --recursive --configuration .swift-format .
```

## Android 模拟器

SDK 齐全（`~/Library/Android/sdk`，system-image `android-36/google_apis/arm64-v8a`，
platforms `android-36` / `android-37.0`），但**尚无 AVD**。Android 门禁需要先建 AVD。

## Android 模拟器（已就绪 ✅）

AVD 建在 `/Volumes/Space/Dev/.cetus-avd`（**不能放主目录**，`/` 只剩 6.5GB，
emulator 需要 7.4GB 建 userdata，会 FATAL 退出）。

```sh
export ANDROID_HOME=/Users/wuyanzu/Library/Android/sdk
export ANDROID_AVD_HOME=/Volumes/Space/Dev/.cetus-avd
$ANDROID_HOME/emulator/emulator -avd cetus_test \
  -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect &
```

- AVD：`cetus_test` = system-images;android-36;google_apis;arm64-v8a，device `pixel_7`
- 启动约 29 秒；`emulator-5554`
- Gradle 门禁 `assembleDebug + testDebugUnitTest + lintDebug` **BUILD SUCCESSFUL**
- debug APK 安装成功，launcher 是 `dev.deeplinks.devices.SplashActivity`
  （**不是** `MainActivity`；Splash 会转到 `dev.deeplinks.native.MainActivity`）
- debug 包无 FLAG_SECURE，`adb exec-out screencap -p` 可截图
  → 证据 `docs/cetus/evidence/android-baseline.png`

## 门禁基线（2026-10-08，cetus/main）

| 门禁 | 结果 |
|---|---|
| `npm run prepack`（插件） | ✅ 370/370 通过 |
| relay：gofmt/vet/build/test | ✅ 通过 |
| Android gradle 三件套 | ✅ BUILD SUCCESSFUL |
| iOS build Debug+Release（CI 命令） | ✅ BUILD SUCCEEDED |
| iOS `xcodebuild test` | ⚠️ 本机环境问题，见上 |
| `swift-format lint --strict` | ✅ 通过 |

## e2e-arch-smoke 的间歇性失败（已定性）

`node scripts/e2e-arch-smoke.mjs` 偶发失败，模式为：
`/dsh-link/mobile/bootstrap|sessions|llm-models|workspaces` 返回 `undefined`
（不是超时，是 mobile API 端点没起来）。

**已定性为间歇性，不是代码回归。** Lead 取证过程：

| 运行 | 条件 | 结果 |
|---|---|---|
| 1–3 | 主工作树，自动选端口，load 3.4–6.4 | **27/32，同一组 5 项失败**（确定性） |
| 4 | worktree `d5cb2419` + 已修脚本 | 34/35（mobile API 全过） |
| 5 | worktree `3e6068ff` + 已修脚本 | 34/35（mobile API 全过） |
| 6 | 主工作树 + 显式 `WEB_PORT=3081 MOBILE_PORT=18641` | **35/35** |
| 7–9 | 主工作树，自动端口，load ~5 | **35/35 × 3 次** |

结论：
- **不是 B8（3e6068ff）引入的** —— 干净 worktree 上 mobile API 全过
- **不是磁盘/端口占用** —— 已排除；18640 被 desktop host 占用属正常，脚本用随机端口
- 根因是 loopback RPC（`callLocalRpc("session.list")`）的既有脆弱性 +
  `mobile-api.js` catch-all 曾把错误吞进 `console.error`（**已由 task-9 修为走宿主 logger**，
  现在排障能看到真因）
- 失败模式会成组出现（一类端点全挂），不要误判为单点回归

**跑这个脚本时**：先 `uptime`，低负载下连跑 2–3 次取一致结果；
若失败，看 scratch 目录里的 `host.log`（修 logger 后真因可见）。
