# Cetus 验收基线（P00 / 方案 §4 C00）

本文件是**验收基线**：后续每次截图、录像、测试结果都要能对应到这里的提交与 fixture 版本。
目标是方案 §4 的原话——「不需要询问开发者『这次截图是什么包』」。

- 仓库：`/Volumes/Space/Dev/dsh-cetus`，分支 `cetus/main`
- 记录日期：2026-10-08
- 方案文档：`/Users/wuyanzu/Desktop/Cetus-整体改造方案-2026-10-07.md` §4 / §33.1
- 真机检查记录：`/Users/wuyanzu/Desktop/Cetus-方案附件-2026-10-07/真机检查记录.md`

## 1. 基线提交

| 项 | 值 |
|---|---|
| 基线提交（本文件撰写时） | `30ef32e362a1f2acf962d11e4113525e816ba93b`（`30ef32e3`） |
| 提交标题 | `refactor(cetus): 插件规范名 dsh-links → dsh-cetus` |
| 分支 | `cetus/main` |
| 工作树状态 | **脏**（有并行队友的未提交改动，见 §7） |
| 方案自身基线提交 | `b5b796ee6d0be2458c16c149ca91f31e132c81e2`（2026-10-06，`docs: 合并 iOS 后续记录`）——在仓库里可解析 |

**注意**：本文件撰写期间 `cetus/main` 有多个队友在改（rebrand / stateDir 迁移 /
其他工作包）。因此**产物里的 `-dirty` 后缀是诚实且必要的**：它明确表示该产物
不对应任何干净的提交。要用作正式验收基线，请先提交/清理工作树再重新构建。

> 本文件自 `8d81378f` 起撰写；期间 `30ef32e3`（stateDir 规范名迁移）落地。
> §2 的门禁与 §4 的产物验证均在 `30ef32e3` 工作树上重跑通过。

## 2. 门禁结果（本机实际输出）

本机：macOS / Xcode 27.1 / Apple Silicon / **10 核**。环境性坑见 `docs/cetus/ENV-NOTES.md`。

**负载纪律**：全队并行构建时本机 load average 达 **8–17**（10 核）。带超时的门禁在
高负载下会出现**假失败**。下表的每一项都记录了运行时的 `uptime`；本节结果均为
**低负载窗口下的串行重跑**。

| 门禁 | 命令 | 实际输出 | 运行时 load |
|---|---|---|---|
| 插件 | `npm run prepack` | ✅ **419/419 通过**（tests 419 / pass 419 / fail 0） | 8.01 → 7.93 |
| Android | `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | ✅ **BUILD SUCCESSFUL** | 8.56 → 8.36 |
| iOS（Debug，scheme `Cetus`） | 见下 | ✅ **BUILD SUCCEEDED** | 10.01 → 9.77 |
| 新基线测试 | `node --test test/cetus-baseline.test.mjs` | ✅ **8/8 通过** | 8.01 → 7.93 |
| fixture 场景表自检 | `node scripts/e2e-scenarios.mjs --check` | ✅ 11 个场景，引用全部存在 | 8.01 → 7.93 |
| iOS `swift-format lint --strict`（改动文件） | 见 ENV-NOTES.md | ✅ 无输出，退出 0 | — |
| iOS `xcodebuild test` | — | ⚠️ **本机跑不通**（pre-existing 环境问题，见 §5） | — |

**失败情况说明（遵守「先别改代码」纪律）**：
本次会话中 Android 门禁出现过 3 次失败，**全部为真实编译错误且已修复**，
不是负载偶发失败——每次都给出确定的行号与 `Unresolved reference`，重跑结果稳定：

1. `build.gradle.kts` 用了 `java.time.LocalDate` 全限定名 → 改为顶部 import（真实错误）。
2. `BuildInfo.kt` 缺 `import dev.deeplinks.BuildConfig`（真实错误）。
3. `BuildInfoTest.kt` 缺同一 import（真实错误）。

另有**一次**疑似环境性现象：早期 `node scripts/ios-e2e-host.mjs` 启动失败并报
`SyntaxError: Unexpected token 'import'`，指向 `src/index.js`。**重读文件后内容正常，
属队友写到一半时的瞬态读取**，不是我方或代码缺陷；重跑成功。

**插件测试数从 370 增至 419**，是并行队友新增测试所致（含本任务新增的
`test/cetus-baseline.test.mjs` 8 个）。基线口径以**当前树的全绿**为准，不锁死数字。

**未观察到任何偶发（flaky）失败**：所有失败都能稳定复现、修复后稳定通过。

iOS 构建命令（`destination` 必须用 `generic/platform=iOS Simulator`，
用 `name=` 形式会报假的模块解析错误，详见 ENV-NOTES.md）：

```sh
node scripts/build-metadata.mjs --platform ios   # 新增：生成 BuildMetadata.xcconfig
cd apps/ios
xcodegen generate
xcodebuild -scheme Cetus \
  -destination 'generic/platform=iOS Simulator' \
  -configuration Debug build \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
```

> scheme 名说明：截至本文件撰写时实际 scheme 仍是 `DeepLinks`（`xcodebuild -list` 确认）。
> app-rebrand 队友计划改名为 `Cetus`；改名后请用 `xcodebuild -list` 查实际名再构建。

## 3. 构建元数据（方案 §4 第 2、3 条）

### 做了什么

两端都增加**内部**构建元数据：短提交 SHA、构建日期（UTC）、Debug/Release、协议能力版本。
只在**「关于」页**展示，**不在日常首页**。

| 字段 | iOS | Android |
|---|---|---|
| 短提交 SHA | `DLBuildCommit`（Info.plist） | `BuildConfig.BUILD_COMMIT` |
| 构建日期 | `DLBuildDate` | `BuildConfig.BUILD_DATE` |
| 配置 | `DLBuildConfiguration` | `BuildConfig.BUILD_TYPE` |
| 合同版本 | `DLBuildContractVersion` | `BuildConfig.BUILD_CONTRACT_VERSION` |
| 营销版本 | `MARKETING_VERSION` = `1.0`（占位） | `versionName` = `0.5.0-beta.31` |
| 构建号 | `CURRENT_PROJECT_VERSION` = `1` | `versionCode` = `39` |

### 涉及文件

| 文件 | 改动 |
|---|---|
| `scripts/build-metadata.mjs` | 新增：生成两端元数据，`git` 采集，失败回退 `unknown` |
| `apps/ios/BuildMetadata.xcconfig` | 新增：生成物（`DLBuild*` 四个键） |
| `apps/ios/project.yml` | 新增 `configFiles` 指向该 xcconfig |
| `apps/ios/App/Info.plist` | 新增四个 `DLBuild*` 键引用 xcconfig |
| `apps/ios/Packages/DLCore/Sources/DLCore/BuildInfo.swift` | 新增：读回元数据，缺键回退 `unknown` |
| `apps/ios/App/Features/Settings/SettingsPages.swift` | 关于页展示四行（营销版本改为读实时值） |
| `apps/ios/App/Resources/Localizable.xcstrings` | 新增 4 个键的 en / zh-Hans |
| `apps/ios/Tests/BuildInfoTests.swift` | 新增：读回 / 占位符 / 缺键三种情况 |
| `apps/android/app/build.gradle.kts` | `buildConfigField` 注入三项（提交/日期/合同版本） |
| `apps/android/app/src/main/java/dev/deeplinks/core/BuildInfo.kt` | 新增：元数据访问对象 |
| `apps/android/app/src/main/java/dev/deeplinks/native/SettingsRoute.kt` | 关于页新增两行 |
| `apps/android/app/src/main/java/dev/deeplinks/core/AppLocale{,En,Zh}.kt` | 新增 2 个键 |
| `apps/android/app/src/test/java/dev/deeplinks/core/BuildInfoTest.kt` | 新增：4 个断言 |

### 为什么这样解决「1.0 (1) 无法定位提交」

根因不是版本号缺失，而是**产物里根本没有可追溯字段**：
iOS 关于页此前硬编码 `1.0`（`SettingsText` 表的 `versionValue`），
Android 关于页只显示 `VERSION_NAME`。两者都无法回答「这个包是哪个提交」。

现在元数据由构建期生成并**实际写进产物**，已从构建产物验证读回（见 §4）。

**未做（刻意为遵守约束）**：
- 未升 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` / `versionCode` / `versionName`
- 未建任何 git tag
- 未动 Apple 账号 / 证书 / signing

上述均属维护者事项（方案 §4 验收 + `apps/ios/AGENTS.md` 红线）。

## 4. 能对应到提交的证据

### 4.1 构建产物里确实带上了元数据（已实测）

iOS —— 构建产物 `Cetus.app/Info.plist`（scheme 已由队友改名 `Cetus`，见 §7）：

```
"CFBundleShortVersionString" => "1.0"          # 营销版本，未改
"CFBundleVersion"            => "1"            # 构建号，未改
"DLBuildCommit"              => "30ef32e3-dirty"
"DLBuildConfiguration"       => "Debug"
"DLBuildContractVersion"     => "1"
"DLBuildDate"                => "2026-10-07"
```

> 改名前的 `DeepLinks.app` 上同样验证过（当时 `DLBuildCommit` = `8d81378f-dirty`），
> 说明元数据链**不依赖 scheme 名**，改名后依然有效。

Android —— 生成物 `BuildConfig.java`（`:app:assembleDebug` 后）：

```
public static final int    VERSION_CODE            = 39;
public static final String VERSION_NAME            = "0.5.0-beta.31";
public static final String BUILD_COMMIT            = "8d81378f-dirty";
public static final String BUILD_DATE              = "2026-10-07";
public static final String BUILD_CONTRACT_VERSION  = "1";
```

原始输出留存：`docs/cetus/evidence/build-metadata.txt`
（该文件记录的是首次采集，提交 SHA 为当时的 `8d81378f-dirty`；
字段名与结构在 `30ef32e3` 上不变，仅 SHA 随提交前进。）

Android debug APK 身份：

| 项 | 值 |
|---|---|
| APK 路径 | `apps/android/app/build/outputs/apk/debug/app-debug.apk` |
| SHA-256 | `06918027f40a503d533966ef67ac90c72240bbc83f539662b4f60d99420c588e` |
| 签名 | `CN=Android Debug`（debug 密钥，**非**发布签名） |
| 签名证书 SHA-256 | `7768bf27367ce0786a1f46c8b41721a83753818cf3aa22ac17b1a64c4b68fe18` |
| 设备端版本（`emulator-5554`） | `dev.deeplinks.debug`，`0.5.0-beta.31` (39)，`apkSigningVersion=2` |

Android 单元测试实际执行证据（非仅编译）：
`app/build/test-results/testDebugUnitTest/TEST-dev.deeplinks.core.BuildInfoTest.xml`
→ `tests="4" failures="0" errors="0"`，4 个用例名齐全。

### 4.2 fixture 场景集（方案 §4 第 4 条）

`scripts/e2e-scenarios.mjs`：登记 11 个场景，**复用**已有脚本，未另造假 Host。
场景表引用的 fixture 常量在 `scripts/ios-e2e-fixture.mjs` 里**逐一校验存在**
（`--check`，已接入 `test/cetus-baseline.test.mjs`，常量改名会立刻失败）。

场景集版本：**v1**

| ID | 场景 | 宿主 | 依据的已有 fixture |
|---|---|---|---|
| S01 | 未配对 | 无宿主 | — |
| S02 | 等待批准 | live-host | `pair-settings` 的 `requireConfirm` |
| S03 | 在线首页 | live-host | `WORKSPACE_ROW`、`SESSION_ROWS` |
| S04 | 离线缓存 | live-host | `/control/shutdown` + 上述行 |
| S05 | 流式会话 | live-host | `SESSION_LOGIN`、`loginEvents`、`SESSION_STOPPED`、`stoppedEvents` |
| S06 | 审批 | live-host | `/control/approval`、`loginEvents` |
| S07 | 自由回答 | live-host | `/control/question`、`SESSION_LOGIN` |
| S08 | 改动 | live-host | `SESSION_REFACTOR`、`refactorEvents`、`CHANGES_DIFFS`、`CHANGES_SUMMARIES`、`changesService` |
| S09 | 文件 | live-host | `createWorkspaceTree`、`WORKSPACE_ROW` |
| S10 | 预览 | live-host | `SESSION_REPORT`、`reportEvents`（5199 端口探测） |
| S11 | 设置 | live-host | `CREDENTIALS`、`PROVIDER_SETTINGS`、`MODEL_CATALOG` |

宿主张成：
- `scripts/ios-e2e-host.mjs` —— 真插件宿主，stateDir 为 `os.tmpdir()` 下 `mkdtemp`，
  **不碰 `~/.dsh`**；控制面只听 `127.0.0.1`。已验证本次运行确实隔离（见 §7）。
- `scripts/export-contract-fixtures.mjs` —— 离线合同快照 `testdata/mobile-contract`，
  两端引用同一批。

场景期望明细：`node scripts/e2e-scenarios.mjs`（Markdown）/ `--json`。

### 4.3 已有截图基线

| 证据 | 路径 |
|---|---|
| Android 基线截图 | `docs/cetus/evidence/android-baseline.png` |
| 构建元数据原始输出 | `docs/cetus/evidence/build-metadata.txt` |

> 说明：`docs/cetus/evidence/` 下另有多张队友产出的 `android-after-*.png`（rebrand 后
> 界面）。本次会话中 Android 模拟器上实测确认 App 可启动并处于**未配对状态**
> （对应场景 S01），但**未能**在模拟器上截到「关于」页：未配对时设置入口不可达，
> 而配对需要走真实 QR 配对流程（`deeplinks://pair` scheme 未在该 debug 包注册，
> 深链直启失败）。元数据的可见性证据因此**取自构建产物**（§4.1）而非截图。

## 5. 拿不到的证据（如实写未知）

以下**明确未知**，不编造：

1. **两台现有真机安装包的构建提交：未知。**
   - Android 真机 `dev.deeplinks` `0.5.0-beta.31` (39)：该包在元数据机制存在**之前**构建，
     包内无提交字段 → **无法反推提交**。
   - iOS 真机 `dev.deeplinks.ios.debug` `1.0` (1)：占位版本，同样无提交字段 → **未知**。
   - 结论与方案 §4 一致：旧包拿不到提交，只能明确记未知。**新包**从本基线起可追溯。
   - 注：真机上的包**未取回**，无法用本次新增的元数据字段去比对（字段在旧包里不存在）。

2. **两台真机的配对数据/签名渠道未能核实。**
   本次只在**隔离 fixture host** 上做读操作，未连真机、未读真机配对状态。
   `真机检查记录.md` 记录的是 2026-10-07 的观察，本次未复核。
   （`~/.dsh/dsh-cetus/state.json` 的 `deviceCount: 2` 来自迁移记录，本次未逐项核对设备详情。）

3. **Android 正式（release）签名包的验证：未做。**
   本机存在 `~/Library/Application Support/DSH Links Signing/env` 时才可签 release；
   本次只构建了 debug 变体（见 §4.1 的 debug 签名）。release APK 的签名渠道未验证。
   真机上的 release 包（`dev.deeplinks`）也未取回比对。

4. **iOS 单元测试本机未跑通。**
   `xcodebuild test` 在本机有两类 pre-existing 失败（x86_64 切片链接、模块解析），
   与 `origin/main` 干净树同样复现，详见 ENV-NOTES.md。因此
   **`BuildInfoTests.swift` 的依据是编译通过 + 代码审查，未在本机 XCTest 层面跑过**。
   CI（`macos-26`）是 iOS 测试的权威依据。
   Android 侧 `BuildInfoTest.kt` 已随 `testDebugUnitTest` 真实执行（4/4 通过，见 §4.1）。

5. **场景集已在真机/模拟器逐个跑通的证据：未完成。**
   本任务交付的是**场景集定义 + 自检**（fixture 引用存在性）。十一个场景的逐项
   执行属后续工作包，本次未产出逐场景录像/截图。
   已做到的：Android 模拟器上确认 App 可启动并处于**未配对状态（S01 前置）**。

6. **`xcodegen generate` 输出的三行提示**（`No "base" settings found` 等）：
   为 xcodegen 对 `configFiles` 的常规提示，构建成功；未深究其影响。

7. ~~iOS scheme 改名影响：未知。~~ **已解决**：队友把 scheme 改名为 `Cetus`，
   本任务按预案探测实际名后构建通过，元数据链不受影响（§4.1、§8）。

8. **未做端到端真机验收。** 本任务只建立基线，未在真机上跑任何场景；
   真机验证属后续工作包。**未验证**的事项一律标未知，不用推测填补。

9. **负载下的门禁稳定性：仅覆盖一次低负载窗口。**
   §2 结果为 load ≈ 8 的串行重跑。全队负载曾达 17+；在更高负载下未重复验证，
   因此不能断言这些门禁在任何负载下都稳定。**未观察到 flaky 失败**，但样本有限。

10. **模拟器上的 UI 截图证据缺口。**
    `adb exec-out screencap` 可截 debug 包（无 FLAG_SECURE），本次也截到了未配对首页；
    但「关于」页需要配对后才可达，未配对状态下无法在模拟器上截到构建元数据行。
    因此元数据的**运行时可见性**只在代码层与产物层验证，**未做**端到端 UI 截图验证。

## 6. stateDir 迁移（B2，2026-10-08 00:19）

这是「能定位问题的基线」的一部分：插件的 canonical state 目录换了名字。

| 项 | 值 |
|---|---|
| 旧路径（回滚点，**保留**） | `~/.dsh/dsh-links/` |
| 新 canonical | `~/.dsh/dsh-cetus/` |
| 迁移时间 | 2026-10-08 **00:19**（`migration.json` 的 `at` = `1791389980811`） |
| 状态 | `migration.json` 记 `status: migrated`，`deviceCount: 2` |
| 源目录处置 | **原样保留**为受保护备份（`AGENTS.md` 明确：不要手动删除） |

**独立复核（本任务自己重算哈希，不是转述他人结论）**：

| 文件 | 新 vs 旧 SHA-256 | 结果 |
|---|---|---|
| `state.json` | `a300bfd8…f014f` vs `a300bfd8…f014f` | ✅ 一致 |
| `tls.json` | `d887f9aa…14800` vs `d887f9aa…14800` | ✅ 一致 |
| `state.json.bak-before-dlp-20260929-235210` | `122f68fa…ff802` vs 同 | ✅ 一致 |

权限：新目录 `drwx------`、`state.json` / `tls.json` 均 `-rw-------`（0700 / 0600 保持）。
`tlsFingerprint` = `11c4672ae86a60830e783fa129deb1cd049de1caad90620d50dc0768db52460f`。

**对本基线的影响**：此后所有真机/联调的证据要注明跑在新 canonical 目录下；
`stateDir` 隔离的写法也随之改为 `- id: dsh-cetus, config: {stateDir: ...}`。

## 7. 隔离与安全复核

- 用户真实配对 state：**未改动**。
  - 新 canonical `~/.dsh/dsh-cetus/state.json` mtime 仍为 `00:19:40`（迁移时间），
    本次会话未写入。
  - 旧目录 `~/.dsh/dsh-links/state.json` mtime 为 `2026-10-07 23:30`（早于本次会话）。
- 本次 fixture host 的 stateDir：`/tmp/dsh-ios-e2e-*`（`mkdtemp`），已确认隔离；
  会话结束后已 `pkill` 自己的 host 进程。
- 未执行任何设备吊销类操作；未对真机写入。
- 未建 tag、未升版本号、未动签名与 Apple 配置。

## 8. 工作树并发状态（撰写时）

`cetus/main` 上有并行队友的改动（**非本任务产出**）：

- rebrand：`SettingsRoute.kt`（标题改 `cetus`）、`strings.xml`、`AppLocale*.kt`、
  `docs/REBRAND_CETUS.md`，以及 **iOS scheme / target 改名 `DeepLinks` → `Cetus`**
- stateDir 迁移：`src/state-migration.js`、`src/index.js`、`src/client.js`、`src/panel.js`
  （已在 `30ef32e3` 提交）
- 其他：`build-client.mjs`、`docs/ios/PLAN.md`、`docs/redesign-v4/*`、多个 Android 测试文件

**本任务改动集中在**：`docs/cetus/`、`scripts/`、`docs/COMPATIBILITY.md`、`test/`，
以及 `apps/ios`（BuildInfo 链）、`apps/android`（构建元数据链）。
与 rebrand 队友在 `SettingsRoute.kt` / `AppLocale*.kt` 上有**文件重叠**；
本任务的改动是**增量小改**（关于页两行 + 两个 locale 键），未改动其重命名内容。

**iOS scheme 改名已实测确认**：撰写期间队友把 `project.yml` 的 `name` 与 target
改为 `Cetus`，`xcodebuild -scheme DeepLinks` 随即报
`The project named "Cetus" does not contain a scheme named "DeepLinks"`。
本任务按预案改用 `xcodebuild -list` 查实际名（`Cetus`）后构建成功，
**未改队友的文件**。构建元数据链在改名后依然有效（见 §4.1）。

撰写期间曾读到 `src/index.js` 处于**写到一半**的状态（import 行被拼接）导致
fixture host 启动失败；重读后文件正常，属瞬态竞争，非文件损坏。

## 9. 复现步骤

```sh
cd /Volumes/Space/Dev/dsh-cetus

# 0. 记录负载（判断失败是否环境性）
uptime

# 1. 插件门禁（当前树 419/419）
npm run prepack

# 2. 新增基线测试（8/8）+ 场景表自检
node --test test/cetus-baseline.test.mjs
node scripts/e2e-scenarios.mjs --check

# 3. Android 门禁
cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# 4. iOS 构建（先生成元数据！scheme 名以 xcodebuild -list 为准）
cd /Volumes/Space/Dev/dsh-cetus
node scripts/build-metadata.mjs --platform ios
cd apps/ios && xcodegen generate
xcodebuild -list                      # 确认当前 scheme 名（可能已被改名为 Cetus）
xcodebuild -scheme Cetus -destination 'generic/platform=iOS Simulator' \
  -configuration Debug build CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO

# 5. 验证元数据确实进了产物
plutil -p ~/Library/Developer/Xcode/DerivedData/Cetus-*/Build/Products/Debug-iphonesimulator/Cetus.app/Info.plist | grep DLBuild

# 6. 起隔离 fixture host（不碰用户 state）
node scripts/ios-e2e-host.mjs --qr /tmp/qr.json --log /tmp/host.log
```

新包验收时请在截图/录像里附上「关于」页，使提交与画面互相印证。

