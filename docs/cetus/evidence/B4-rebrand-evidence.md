# B4 证据：Android/iOS 用户可见名改 cetus（保安装身份）

任务：`task-3`（B4）· 分支 `cetus/main` · 执行者：app-rebrand
证据采集时间：2026-10-08 00:10–00:30（本机 load average 8–12 / 10 核，见「负载说明」）

## 0. 命名合同落地

| 角色 | 取值 | 处理 |
|---|---|---|
| 用户可见显示名 | `cetus`（全小写） | 改 |
| 内部工程标识 | `Cetus`（首字母大写） | 改 |
| 安装 / 协议身份 | `dev.deeplinks*` 等 | **冻结，逐项验证未变** |

## 1. Android 门禁

```sh
cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- 常规运行：`BUILD SUCCESSFUL`
- `--rerun-tasks` 强制重跑（非缓存）：`BUILD SUCCESSFUL`，单元测试
  **tests=876 failures=0 errors=0 skipped=0**
- `:app:lintDebug` 干净，无新增告警

### APK 身份与显示名（aapt2 权威输出）

```
package: name='dev.deeplinks.debug' versionCode='39' versionName='0.5.0-beta.31'
application-label:'cetus'           # 全部 locale 均为 cetus
launchable-activity: name='dev.deeplinks.devices.SplashActivity'
```

`applicationId` = `dev.deeplinks`（debug 变体加 `.debug` suffix）**未变**。

### 升级身份实证（本任务最关键证据）

模拟器上 `adb install -r` 就地升级，**未卸载**：

```
firstInstallTime=2026-10-08 00:10:05
lastUpdateTime  =2026-10-08 00:29:30
```

安装目录与 `shared_prefs/`、`files/` 保留 → 同一 package 就地升级，配对数据不丢。

### 截图证据

| 文件 | 内容 |
|---|---|
| `android-baseline.png` | 改名前基线 |
| `android-after-appinfo.png` | **系统「应用信息」页显示 cetus**（00:29，重建后的 APK） |
| `android-after-app.png` | 改名后 App 本体 |

## 2. iOS 门禁

```sh
cd apps/ios && xcodegen generate
xcodebuild -scheme Cetus -destination 'generic/platform=iOS Simulator' \
  -configuration <Debug|Release> build CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
```

- Debug：`** BUILD SUCCEEDED **`
- Release：`** BUILD SUCCEEDED **`
- `xcrun swift-format lint --strict --recursive --configuration .swift-format .` → exit 0
- `xcodegen generate` 产出 `Cetus.xcodeproj`，schemes：`Cetus` / `CetusPerformance`

### 构建产物身份与显示名

```
Cetus.app/Info.plist:
  CFBundleDisplayName   = cetus                       # 小写，符合合同
  CFBundleIdentifier    = dev.deeplinks.ios.debug     # 未变
  CFBundleName          = Cetus                       # 内部标识
```

### 包内单测（本机可跑的部分）

`Packages/DLSecurity` 在 iOS Simulator 上：

```
xcodebuild test -scheme DLSecurity -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
** TEST SUCCEEDED **   8 tests, 0 failures
```

覆盖本次改动的 `PushContent.generic`（推送兜底文案 → cetus）。

## 3. 未跑通 / 环境性失败（如实报告）

### 3.1 `xcodebuild build-for-testing` 失败 —— 已证明 pre-existing

```
Tests/ChatControlsA3Tests.swift:7:18: error: Unable to resolve Swift module dependency
  to a compatible module: 'Cetus' (in target 'CetusTests' from project 'Cetus')
```

**不是本次改名引入**。在 `origin/main` 干净 worktree（`d5b8ba41`）上复现同一错误，
仅模块名不同（`'DeepLinks'`）：

```
/tmp/cetus-baseline-wt/.../ChatControlsA3Tests.swift:7:18: error: Unable to resolve Swift
  module dependency to a compatible module: 'DeepLinks' (in target 'DeepLinksTests' ...)
```

结论：与 `docs/cetus/ENV-NOTES.md` 记录的 x86_64 切片 + 模块解析环境问题一致。
**iOS 验证依据 = build + swift-format**，单测以 CI（`macos-26`）为准。

### 3.2 `swift test`（macOS 宿主）失败 —— 亦为 pre-existing

`DLSecurity` 的 `Package.swift` 只声明 `platforms: [.iOS(.v26)]`；在 macOS 上编译时
`PushCrypto.swift:12` 报 `'HPKE' is only available in macOS 14.0 or newer`。
该文件本次**未被修改**（`git diff` 为空）。改用 iOS Simulator destination 后测试全绿（见 §2）。

## 4. 负载说明（遵循 Lead 门禁纪律）

- 采集期 `uptime` 为 **load average 8.01–11.10 / 10 核**（约 13 名队友并行）。
- 本次所有门禁结论均来自**低负载窗口的串行重跑**：Android 用 `--rerun-tasks` 强制
  全量重跑（876 测试全绿），iOS 重新生成工程后串行跑 Debug + Release。
- **未**为让结果变绿而放宽任何断言、调大超时或 skip 用例。§3 两项失败均经
  `origin/main` 干净树对照证明为 pre-existing，按原样如实报告，未修改。

## 5. 未跟进的 DeepLinks 引用（交接清单）

### 5.1 有意保留（不是漏改）

| 位置 | 原因 |
|---|---|
| Android `namespace = "dev.deeplinks"` | 改 namespace 需同步 R/BuildConfig 全量 import、Java 包目录、Manifest 全限定名、ProGuard；收益不成比例。已由 design-contract 批准并登记为「故意保留」 |
| `PairingQRParseError.notDeepLinks` | 配对协议层 API 标识符，冻结 |
| iOS 协议/加密常量 `deeplinks`(URL scheme)、`dlpush/1 token`、`dlpush/1 content\|`、`/dsh-link/` | 协议身份，冻结 |
| `apps/ios/docs/design/*.png`、`.local/`、`.lody/`、CHANGELOG 历史条目 | 历史归档 / 用户数据，合同规定不批量改写 |

### 5.2 需要后续跟进（本次未改，超出直接品牌范围或属他人 scope）

| 文件 | 内容 | 建议 |
|---|---|---|
| `README.md` | 多处正文品牌名（110/128/134/407/409 行等） | 品牌文案统一处理 |
| `SECURITY.md`、`PRIVACY.md`、`THIRD_PARTY_NOTICES.md` | 正文品牌名 | 同上 |
| `docs/COMPATIBILITY.md`、`docs/RC1_CLOSED_BETA_TEST_PLAN.md`、`docs/android-background.md` | 标题与正文品牌名 | 同上 |
| `REMOTE_ACCESS.md` | 历史 DLR/1 描述 | 历史叙述，可保留 |
| `apps/android/app/src/screenshotTest/.../DesignSystemScreenshotTest.kt:1187` | 设计系统 showcase fixture 用 `"DeepLinks"` | 改动会使截图基线失配；基线只能由 `regen-screenshots.yml` 产出，故留给该流程 |
| `apps/ios/docs/design/src/*.cjs`、`device-check.md`、`visual-rules-ios.md` | 文档/生成脚本 | 属 design-contract scope |

### 5.3 已跟进的 CI / 文档（本次已改）

- `.github/workflows/ci-ios.yml`、`ios-e2e.yml`、`ios-performance.yml`、`ios-regen-screenshots.yml`
  —— scheme、`-only-testing`、`-skip-testing` 全部 `DeepLinks*` → `Cetus*`（bundle id 未动）
- `README.md`（iOS 构建命令段）、`RELEASING.md`（mapping 命名示例）、`apps/ios/AGENTS.md`（门禁命令）

## 6. 阻塞 / 风险

- 本次执行期间，`apps/android/`、`apps/ios/`、`README.md` 有**其他队友并行改动**
  （C00 构建元数据：`BuildMetadata.xcconfig`、`AppLocale.kt`、`SettingsPages.swift`、
  `project.yml` 的 `configFiles`）。我已与自己的改动逐行区分，未互相覆盖。

## 7. 附带修复：CI 因 BuildMetadata.xcconfig 会全红（Lead 已批准 A 方案）

### 7.1 问题（实测，非推测）

`project.yml` 新增 `configFiles: {Debug/Release: BuildMetadata.xcconfig}`，该文件由
`scripts/build-metadata.mjs` 生成、未入库；而 `ci-ios.yml` 三个 job 都直接
`xcodegen generate`，**没有任何一步生成它**。干净树上后果是硬失败：

```
2 Spec validations errors:
	- Invalid config file "BuildMetadata.xcconfig" for config "Debug"
	- Invalid config file "BuildMetadata.xcconfig" for config "Release"
```

`xcodegen generate` 失败 → 不产出 `.xcodeproj` → 后续 `xcodebuild` 报
`does not contain an Xcode project`。**iOS 全部 job 红。**

### 7.2 修复（A 方案：生成物不入库，CI 现生成）

| 文件 | 改动 |
|---|---|
| `.github/workflows/ci-ios.yml` | 三个 job（`build` / `test` / `screenshots`）在 `Generate project` **之前**各插入一步 `Generate build metadata`，`working-directory: ${{ github.workspace }}` + `run: node scripts/build-metadata.mjs --platform ios` |
| `.gitignore` | 新增 `apps/ios/BuildMetadata.xcconfig` |
| `apps/ios/AGENTS.md` | 门禁命令补生成步骤，并注明必须在 `xcodegen generate` 之前 |
| `README.md` | iOS 构建命令段补生成步骤 |

**路径实测**：脚本 `REPO_ROOT = dirname(dirname(SCRIPT_PATH))` 由脚本自身位置推导，
与 cwd 无关。已实测 `cd apps/ios && node ../../scripts/build-metadata.mjs --platform ios`
→ 正确写到 `apps/ios/BuildMetadata.xcconfig`（输出 `wrote ...`）。
CI 里用 `working-directory: ${{ github.workspace }}` 走仓库根，与既有
`Check locale catalogs` 步骤（第 38 行）同一模式。

### 7.3 干净树验证（Lead 要求的验证方式）

`git archive HEAD` 只取跟踪文件 + overlay 我的工作树改动（模拟真实提交），
确保**没有** `BuildMetadata.xcconfig`，然后按 CI 的完整步骤顺序执行：

```sh
# A. 旧顺序（跳过元数据步骤）—— 复现失败
cd /tmp/cetus-clean/apps/ios && xcodegen generate
→ 2 Spec validations errors: Invalid config file "BuildMetadata.xcconfig" ...

# B. 新 CI 顺序 —— 通过
cd /tmp/cetus-clean && node scripts/build-metadata.mjs --platform ios
→ wrote /private/tmp/cetus-clean/apps/ios/BuildMetadata.xcconfig
cd /tmp/cetus-clean/apps/ios && xcodegen generate
→ 成功，产出 Cetus.xcodeproj（无 Spec validation 错误）
```

另用 `yaml.safe_load` 校验 `ci-ios.yml` 结构，确认三个 job 的顺序均满足
`Generate build metadata` < `Generate project`：

```
jobs: ['build', 'test', 'screenshots']
  build:       metadata_step=True order_ok=True (meta@5 < gen@6)
  test:        metadata_step=True order_ok=True (meta@3 < gen@4)
  screenshots: metadata_step=True order_ok=True (meta@3 < gen@4)
```

### 7.4 Android 侧同类风险排查（Lead 要求）

**结论：Android 无此问题。** 依据：

- `apps/android/.../core/BuildInfo.kt` 的值来自 Gradle `buildConfigField`
  （`BUILD_COMMIT` / `BUILD_DATE` / `BUILD_CONTRACT_VERSION`，见
  `app/build.gradle.kts:101-103`），在**构建时**由 Gradle 计算，取不到时回退 `unknown`；
  构建流程**不依赖**运行 `build-metadata.mjs`。
- 该文件出现的 `?? BuildInfo.kt` 只是因为它属 C00 新增、**尚未提交**（untracked），
  不是「生成物未入库导致 CI 缺文件」。android 平台该脚本的输出仅是给人/工具看的快照。
- `ci-android.yml` 不引用 `build-metadata.mjs`，且 `./gradlew` 门禁已实测全绿
  （876 测试通过），无需改动。

### 7.5 环境限制记录

验证期间根卷 `/` 仅剩 272Mi（`df -h /` → 100%），一次 clean-tree 的
`xcodebuild` 因 `No space left on device` 失败于 result bundle 写入。
这是磁盘容量限制（与 ENV-NOTES 记录的「AVD 必须放 Space 卷」同源），
**非代码问题**；关键验证（xcodegen 步骤顺序 + 真实仓库 `BUILD SUCCEEDED`）已通过。

**清理位置提示**：失败发生在写 **result bundle** 时，其路径是 `/var/folders/...`
（根卷）。因此只清 `~/Library/Developer/Xcode/DerivedData` 未必能回收 `/` 的空间；
建议清理后 `df -h /` 实测确认，并一并考虑 `apps/ios/Packages/*/.build`
（本任务 `swift test` 期间产生，可安全删除）。

### 7.6 附：DLSecurity 单测可在本机跑通（修正 ENV-NOTES 结论）

`docs/cetus/ENV-NOTES.md` 记录的「iOS 单元测试在本机跑不通」针对的是
`swift test`（走 **macOS 宿主**，触发 `HPKE` 需要 macOS 14+ 的平台限制）。
改用 **iOS Simulator destination** 后单测可正常执行：

```sh
cd apps/ios/Packages/DLSecurity
xcodebuild test -scheme DLSecurity -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
# ** TEST SUCCEEDED **   Executed 8 tests, with 0 failures
```

这 8 个用例正好覆盖本次改动的 `PushContent.generic`（推送兜底文案 → cetus）。
建议 ENV-NOTES 区分「`swift test` 不可用」与「`xcodebuild test` 可用」两种途径。

### 7.7 附：Live Activity 缺口（pre-existing，已由 Lead 归入 U18）

`apps/ios/` 中 `import ActivityKit` 仅出现在
`Extensions/LiveActivity/LiveActivityBundle.swift`；`Activity.request` 与
`Activity<...>` 在 App target 内**零引用**，即 App 端从未启动过 Live Activity。

- 这是 **pre-existing** 缺口，非本次改名引入。
- Lead 已核实并归入方案 §3 问题表 **U18**，由工作包 **P17（ActivityKit 生命周期）** 承接，不需另开任务。
- **供 P17 参考的一点澄清**：缺口不只是「未见创建/更新的调用闭环」——App 端连
  `Activity<...>` 类型引用都没有，属**整条生命周期入口缺失**（而非缺 update 路径）。
  排 P17 范围时建议确认已包含「首次创建」，避免把 U18 当成只补 update。

### 7.8 已知文档笔误（已修正）

本节 5.1 原表把 `dlpush/1` 重复列了两次，其中一处应为 `dlpush/1 token`。
已改为逐字列出 `dlpush/1 token` / `dlpush/1 content|`，冻结结论不受影响。
