# deeplinks

DeepLinks Android 客户端。

- 工程根：本目录（Android Studio 打开这里）
- 应用模块：`app/`
- 配对的电脑插件：仓库根目录 `src/`（插件 `dsh-links`）
- 远程中继：[`../../relay/`](../../relay/)

版本基线、发布状态和已验证组合统一维护在
[兼容矩阵](../../docs/COMPATIBILITY.md)，本目录不维护另一份版本表（当前源码版本见 `app/build.gradle.kts` 的 `versionName`）。

支持边界：公开支持为可信局域网；远程连接走 DLP/1 中继（官方中继或自建，扫同一张配对二维码即可，无需接入码），仍为实验性功能；
自管 Tailscale / Cloudflare Tunnel 仅为实验路径。


```bash
./gradlew :app:assembleDebug
```

真机设备测试走 debug 变体（`applicationIdSuffix = ".debug"`，与签名 release 共存、互不覆盖）：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.deeplinks.debug.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall dev.deeplinks.debug.test; adb uninstall dev.deeplinks.debug
```

HyperOS/MIUI 真机还需在 安全中心 → 应用详情 → 权限管理 → 其他权限 → 后台弹出界面 → 始终允许，否则 Compose UI 测试的 Activity 被拦、进程被冻结强杀（表现为 "Process crashed"）。

正式包只发签名 APK。

## 从源码构建

```bash
./gradlew :app:assembleRelease
```

构建产物位于 `app/build/outputs/apk/release/`。

## 品牌声明

代码以 MIT 许可发布。"DeepLinks" 名称、logo 和应用图标不在 MIT 授权范围内；第三方 fork 请更换名称、图标和 `applicationId` 后再分发。
