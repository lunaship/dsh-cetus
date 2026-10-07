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

**状态**：iOS 单元测试在本机需另行解决（例如固定 `ARCHS=arm64`、调整 CI runner
架构，或改用可用的 x86_64 依赖缓存）。**尚未在本机跑通**，因此本轮 iOS 改动的
验证依据是 build + swift-format lint，测试证据以 CI 为准。

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
