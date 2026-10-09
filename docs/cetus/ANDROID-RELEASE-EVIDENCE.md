# Android 正式签名 Release 构建与升级验收证据

- 日期：2026-10-08
- 仓库：`/Volumes/Space/Dev/dsh-cetus`
- HEAD：`3728131d`（`feat(android): 离线禁写、发送失败分场景文案、问题提交去重 (C14)`）
- 构建时工作树：**脏**（`BUILD_COMMIT` = `3728131d-dirty`）—— 当时 `app-rebrand` 队友正在替换
  `splash_wordmark.png`。因此**本次产物不对应任何干净提交**，仅用于预备验收；正式发版前需在干净树重建。
- 相关规范：`RELEASING.md`「App 发版流程」、`apps/android/AGENTS.md`

> 本文只记录**已验证事实**。未发布、未打 tag、未上传 Release 的事项一律标「未做」。

---

## 0. 当前权威产物（2026-10-09 复核补充）

> **§1–§4 记录的是 2026-10-08 的 `3728131d` 脏树产物**，用于当时的预备验收。
> 之后在**干净树**上重建了一份，**以本节为准**；§1 那条已被取代，保留仅为历史对照。

| 项 | 值 |
|---|---|
| 构建提交 | **`0c76eed7`** |
| 工作树 | **干净**（**无** `-dirty`，实测） |
| 产物路径 | `.local/release-builds/0.5.0-beta.31-0c76eed7/cetus-android-0.5.0-beta.31-0c76eed7.apk` |
| APK SHA-256 | `053c0a76940247049372afa125d74ac1b616a13d2326f095c9ba1d015ac32fe0` |
| APK 大小 | `6 991 187` bytes |
| mapping.txt SHA-256 | `0b9fed3e5cacb5478daf4d0c4d432319ae27f4db4ab80bca291ef99a7d916952` |
| 签名证书 SHA-256 | `38f71adf8b67d81042c99a3ec0dfdafb4303dd31e3fc491068ccd534cb482a47` |
| 签名方案 | **v2 + v3**（v1 关闭，`minSdk=26` 无需） |
| versionName / versionCode | `0.5.0-beta.31` / `39` |
| applicationId | `dev.deeplinks`（保持 legacy） |

**已被取代的两份旧产物**（保留仅作历史对照）：

| 目录 | APK SHA-256 | 说明 |
|---|---|---|
| `0.5.0-beta.31-3728131d` | `f3916ec9…00ca8` | `BUILD_COMMIT=3728131d-dirty`（脏树） |
| `0.5.0-beta.31-a91562ae` | `7e971310…39779` | 干净树，但**只有 v2 签名** |

### 0.0 本轮为什么重建（两件事一起做）

1. **启用 v3 签名**（见 §0.2）：旧产物只有 v2，缺密钥轮换能力。
2. **验证发布流水线仍可复现**：改名/迁移工作落地后，`assembleRelease` 从未重新跑过。
   本轮从干净树跑通（`BUILD SUCCESSFUL in 5m 59s`），并跑过
   `assembleDebug + testDebugUnitTest + lintDebug`（全绿）。

**交叉验证「只改了签名、没改代码」**：新旧 `mapping.txt` **逐字节相同**
（同为 `0b9fed3e…`）。APK 增大 4 096 bytes，正是 v3 签名块的大小。
指纹与公钥也与旧产物完全一致（`f535062f…`），**升级身份未变**。

### 0.1 溯源是可复现的（不靠记忆）

APK 里嵌了 `BuildConfig.BUILD_COMMIT`，直接从产物读出来即可核对：

```sh
APK=.local/release-builds/0.5.0-beta.31-0c76eed7/cetus-android-0.5.0-beta.31-0c76eed7.apk
unzip -p "$APK" classes.dex | strings | grep -c 0c76eed7
# → 2   （BUILD_COMMIT 已按当前提交写入）
git status --porcelain | wc -l    # → 0（构建时工作树干净）
```

`BUILD_COMMIT` 由 `build-metadata.mjs` 从 `git rev-parse` 注入，工作树脏时带 `-dirty`；
本产物实测**不含** `-dirty`。

### 0.2 签名方案：**只有 v2**（发现，未修）

`apksigner verify --verbose` 实测：

| 方案 | 结果 | 影响 |
|---|---|---|
| v1（JAR） | **false** | **无影响** —— `minSdk = 26 ≥ 24`，v1 非必需 |
| v2 | **true** | 正常 |
| v3 / v3.1 | 旧产物 **false** → 新产物 **true** | 旧产物缺**密钥轮换**能力：签名密钥若丢失或需更换，v3 是轮换前提；没有它只能让用户**卸载重装**（丢掉已配对凭据与状态）。**本轮已修** |

**已修**（2026-10-09）：在 `signingConfigs.release` 显式 `enableV3Signing = true` 并重建。
`apksigner verify` 实测新产物 `v2=true, v3=true`，公钥与旧产物一致（升级不受影响）。
v1 仍**有意关闭** —— `minSdk = 26 ≥ 24`，v1 无意义。

## 1. 产物清单

| 项 | 值 |
|---|---|
| versionName | `0.5.0-beta.31` |
| versionCode | `39` |
| applicationId | `dev.deeplinks`（**保持 legacy，未跟随更名改动**） |
| minSdk / targetSdk | `26` / `36` |
| compileSdk | `37` |
| 应用显示名 | `cetus`（`aapt2 dump badging` → `application-label:'cetus'`） |
| 启动 Activity | `dev.deeplinks.devices.SplashActivity` |
| APK SHA-256 | `f3916ec9c8c2742d021c333210ff2caf3e0d232a56191b495fd69ab817e00ca8` |
| APK 大小 | `6 987 091` bytes |
| APK 路径（不入库） | `.local/release-builds/0.5.0-beta.31-3728131d/app-release.apk` |
| 校验和文件 | 同目录 `app-release.apk.sha256` |
| 混淆映射表 | 同目录 `mapping.txt`（`80 212 506` bytes，SHA-256 `6d21ccae3c7beb8cd0ad0209819cbf07ae56a6b0244c1c04d1c9b727ce3e2f26`） |

`.local/` 已被 `.gitignore:28` 忽略，APK 与 mapping **不入库**（符合 `RELEASING.md`）。

**构造命令**（在 `apps/android/`）：

```sh
./gradlew :app:assembleRelease        # 自动读取本机签名环境，无需手动传参
```

签名环境：`~/Library/Application Support/DSH Links Signing/env`（4 个变量齐全，权限 `0600`）。
**本文不记录任何密钥内容**，只记录 `apksigner` 输出的公开证书指纹。

## 2. 签名验证（`apksigner verify --print-certs`）

```
Signer #1 certificate DN: CN=DSH Links, OU=lunaship, O=lunaship, C=CN
Signer #1 certificate SHA-256 digest: 38f71adf8b67d81042c99a3ec0dfdafb4303dd31e3fc491068ccd534cb482a47
Signer #1 certificate SHA-1 digest:   8b5330f5f4c2501757d175acdf18363f4a35fdb5
Number of signers: 1
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v1 / v3 / v3.1 / v4 scheme:          false
```

- **证书 SHA-256 与 `README.md:398` 公布的正式签名指纹逐位一致** ✅
- 与上一版内部构建 `.local/release-builds/0.5.0-beta.28-local-46d79f38/build-info.json`
  记录的 `certificateSha256` 一致 ✅ → 同一把发布密钥，可持续覆盖升级
- 仅 v2 方案：`minSdk 26` 下 v2 已足够（v1 仅 Android ≤ 6 需要）。**这是构建配置的既有行为，未改动。**

## 3. 构建元数据（可追溯到提交）

`app/build/generated/source/buildConfig/release/dev/deeplinks/BuildConfig.java`：

```
BUILD_TYPE              = "release"
VERSION_CODE            = 39
VERSION_NAME            = "0.5.0-beta.31"
BUILD_COMMIT            = "3728131d-dirty"
BUILD_DATE              = "2026-10-08"
BUILD_CONTRACT_VERSION  = "1"
```

「关于」页会显示这些值（C00 建立，见 `docs/cetus/BASELINE.md`）。
`-dirty` 后缀是**有意保留的诚实标记**：该产物不对应干净提交。

## 4. 模拟器升级验收（AVD）

- AVD：`cetus_test`（`ANDROID_AVD_HOME=/Volumes/Space/Dev/.cetus-avd`），`emulator-5554`
- **未连接用户真实 host，未做任何配对** ✅

### 4.1 先装旧正式版，再覆盖安装新版

「先装一个正式版本」用仓库里已有的**同密钥签名 release**：
`.local/release-builds/0.5.0-beta.28-local-46d79f38/app-release.apk`（versionCode 36）。

> 说明：`RELEASING.md` 的正式发布渠道是 GitHub Releases。仓库当前**没有** `app-v0.5.0-beta.31`
> tag（最新已发布 tag 为 `app-v0.5.0-beta.27` = versionCode 35）；而 `.local` 里现成的 beta.28
> 与本次产物**同证书、同包名**，可真实检验覆盖升级与数据保留，故采用它。

```sh
adb install            .../0.5.0-beta.28-local-46d79f38/app-release.apk   # 干净安装旧版
adb shell am start -n dev.deeplinks/dev.deeplinks.devices.SplashActivity  # 旧版可正常启动
adb install -r         .../0.5.0-beta.31-3728131d/app-release.apk         # 覆盖安装（不卸载）
```

### 4.2 前后对比（`dumpsys package dev.deeplinks`）

| 项 | 升级前（beta.28） | 升级后（beta.31） | 结论 |
|---|---|---|---|
| `versionCode` | 36 | **39** | 已换版 |
| `versionName` | `0.5.0-beta.28` | `0.5.0-beta.31` | 已换版 |
| `codePath` | `…/dev.deeplinks-Q67BrGGy9tlpTtMv8GmmMA==` | `…/dev.deeplinks-ampiAV4PtxSQ829b4wz48A==` | APK 确实被替换 |
| `dataDir` | `/data/user/0/dev.deeplinks` | **不变** | 同一应用身份 |
| `firstInstallTime` | 18:05:26 | **18:05:26（不变）** | **数据目录保留，非重装** |
| `lastUpdateTime` | 18:05:26 | 18:05:38 | 确为「更新」而非「安装」 |

**`firstInstallTime` 不变 + `codePath` 变化** 是覆盖升级且数据保留的决定性证据：
若数据被清或发生卸载重装，`firstInstallTime` 必然刷新。

### 4.3 启动与界面

| 验收项 | 结果 |
|---|---|
| 启动 | ✅ 进程存活（`pidof dev.deeplinks` = 4414） |
| 崩溃 | ✅ `logcat` 中 `FATAL EXCEPTION` 计数 **0** |
| 显示名 cetus | ✅ 设备界面与 `aapt2 dump badging` 均为 `cetus` |
| 进入配对页 | ✅ 落地「设备与配对」页 |
| 配对页可交互 | ✅ `uiautomator dump`：「扫码配对」`clickable="true" enabled="true"`、「从相册识别」同样可点 |
| 配对状态 | ✅ **未配对**（未连真实 host） |

设备上的实际文案（`uiautomator dump`，非截图）：

```
设备与配对
把电脑上的 cetus 放进口袋
审批、回答、看进度，不用守在电脑前。
1 在电脑上打开 dsh / 运行 dsh，进入「手机连接」面板
2 扫描面板上的二维码
3 在电脑上点「批准」
扫码配对 / 从相册识别
```

### 4.4 关于截图：正式包截不到画面（预期行为）

`adb exec-out screencap` 对**正式包**返回**全黑**图像。原因**不是**故障：
`app/src/main/java/dev/deeplinks/core/SecureWindow.kt:21` 明确 `if (BuildConfig.DEBUG) return`，
即 **release 变体启用 `FLAG_SECURE`**，禁止截屏与录屏（`README.md:384` 亦说明敏感界面启用
`FLAG_SECURE`）。这与 `docs/cetus/ENV-NOTES.md` 记录的现象一致（debug 包无 `FLAG_SECURE`，故当初
debug 截图可用）。

因此：
- **删除了那张全黑图**，避免被误当成"界面渲染失败"的证据；
- 本次 release 界面证据改用 `uiautomator dump`（不受 `FLAG_SECURE` 影响）；
- 这条本身也是**正式包安全加固生效**的正面证据。

## 5. §18 三维度补充审计（配对 / 通知 / 更名）

### 5.1 配对 — ✅ 已实现

| §18 要求 | 实现 | 证据 |
|---|---|---|
| 等待 / 批准 / 拒绝 / 未知阶段 | 4 态枚举 | `core/PairClient.kt:183` `enum Approval { Approved, Pending, Rejected, Unknown }`；判定 `approvalFromResponse:207-212`（200→Approved、403+pending→Pending、401→Rejected、其余→Unknown 继续等） |
| 轮询与超时 | 轮询器 | `devices/PairStatusScreens.kt:69` `PairApprovalPoller`；网络抖动按 `Unknown` 继续轮询而非误判失败 |
| 屏幕阶段 | 等待/失败屏 | `PairStatusScreens.kt:90` `PairWaitingScreen`、`:130` `PairFailedScreen` |
| 导航 | 首页 → 扫码 → 状态 | 本次 AVD 实测落地「设备与配对」页 |
| 测试 | ✅ | `test/.../core/PairApprovalTest.kt`、`PairClientTest.kt`、`PairingQrTest.kt`、`QrImageDecoderTest.kt` |

平台差异（CameraX / 系统扫码）属 §18 表格允许的「实现可不同」，未改动。

### 5.2 通知 — ✅ 已实现（补一条建议）

| §18 要求 | 实现 | 证据 |
|---|---|---|
| 状态可信 | 审批/完成/失败分别建通知 | `core/DshNotifier.kt` 各 `postNotification` |
| 去重 | 按 `(host, sessionId, kind)` 生成**稳定**通知 id | `DshNotifier.kt:416` `notificationId(host, sessionId, kind)`；同会话同类只更新同一条 |
| 重复点击审批 | 4 秒窗口内幂等 | `DshNotifier.kt:255-273` `markApprovalAnswered`：`answeredApprovalUntil[id]` 置位 + 4s 后自动撤销 |
| 权限 | 显式检查 | `DshNotifier.kt:382-384`：查 `POST_NOTIFICATIONS` 且 `areNotificationsEnabled()` |
| 打开后刷新 | 深链带 `sessionId` | `DshNotifier.kt:228` `deepLinkAction(sessionId=…)` → `WorkspaceActivity` `initialSessionId` → `selectSession` + `showPhoneChat()`（`WorkspaceActivity.kt:611-615`） |
| 到已删除/已处理会话 | 不崩 | `cancelForSession`（`:375-377`）按 id 取消，不做无界重试 |

**建议（未改，P2）**：`answeredApprovalUntil` 的 4 秒幂等窗口没有测试覆盖。
逻辑本身正确，但属"防重复提交"的关键路径，建议后续补一条单测。

### 5.3 更名 — ⚠️ 发现并修复 1 处遗漏

| §18 要求 | 状态 |
|---|---|
| 用户界面 cetus | ✅ `res/values/strings.xml:2` `app_name = cetus`；设备实测显示 `cetus` |
| 插件 dsh-cetus | ✅ `package.json:2` `"name": "dsh-cetus"` |
| 升级不丢数据 | ✅ 本次 AVD 覆盖升级实测 `firstInstallTime` 不变 |
| 安装身份可保留 legacy | ✅ `build.gradle.kts:88/92` 仍为 `dev.deeplinks` |
| 重命名后不引导到失效地址 | ✅ `UpdateCheck.kt:22` 已指向 `lunaship/dsh-cetus`；host 白名单有测试（`UpdateCheckTest.kt:47`） |

**已修 P1**：用户可见文案仍出现旧插件名 ——
`AppLocaleEn.kt:637` / `AppLocaleZh.kt:636` 的 `pluginTooOld`：
「The **dsh-links** plugin on the computer is outdated…」→ 改为 `dsh-cetus`（中英同步）。
这是全仓改名时的遗漏：其余用户可见串（`unofficialNotice`、`taskProgressPublic`、
`completionPublic`、`shareNeedsPairing` 等）都已是 `cetus`，只有这一对漏了。

**新增守护测试** `app/src/test/java/dev/deeplinks/core/ProductNamingTest.kt`（2 例）：
- `userVisibleCopyDoesNotUseTheOldProductName`：扫描两个 locale 表的 `put("key","文案")`，
  文案中出现 `dsh-links` / `DeepLinks` / `deeplinks` 即失败（排除 `package` 行、路径类数据）
- `installIdentityStaysLegacySoUpgradesKeepData`：断言 `applicationId` / `namespace` 仍为
  `dev.deeplinks`，防止有人"顺手"把身份也改掉导致升级丢数据

**已验证该测试有效**：临时把 `AppLocaleZh.kt` 改回 `dsh-links` → 测试立即 FAILED
（`userVisibleCopyDoesNotUseTheOldProductName`）；随后原样还原（**非 git 操作**，用备份文件回写），
`git diff` 确认只剩预期的 1 行改动。

> 未改：`src/tls.js:42` 的证书 CN 仍是 `dsh-links`。它**不是**用户可见文案，且改动会破坏
> 已配对设备的 TLS 证书指纹固定（`AGENTS.md` 红线：主机连接必须指纹校验）。留待维护者决策。

## 6. 门禁

```sh
cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# BUILD SUCCESSFUL（load ~7.6，18:01）
```

另：`:app:assembleRelease`（含 `lintVitalRelease`）**BUILD SUCCESSFUL**。

新增/受影响测试：`ProductNamingTest` 2/2 通过。

## 7. 未做 / 需维护者处理

1. **未打 tag、未建 GitHub Release、未上传 APK** —— `RELEASING.md` 规定正式发布由维护者执行。
   （注：§0 的 `a91562ae` 产物**已是干净树构建**，`-dirty` 的限制只适用于 §1 的旧产物。）
2. **未在真机验证** —— 本次只在 AVD 上做安装/升级/启动/配对页验收。`RELEASING.md` 要求的
   真机项（配对、会话/SSE、审批、吊销、重启重连、蜂窝/Wi‑Fi 切换）**均未做**，属「未验证」。
3. **未把 APK 放到桌面/Release** —— APK 只在 `.local/`（gitignored）。
4. **未更新 `docs/COMPATIBILITY.md`** —— 因未发布，按 `RELEASING.md`「只记录已核对过的提交与版本」，
   发布时应由维护者写入组合。
5. **mapping.txt 未按版本存档到签名目录** —— `RELEASING.md` 要求按版本命名存档
   （如 `cetus-0.5.0-beta.31-mapping.txt`）。本次已留在 `.local/`，**归档动作请维护者执行**
   （签名目录为受保护位置，未擅自写入）。
6. **`answeredApprovalUntil` 幂等窗口缺测试**（见 5.2），建议补。
7. **`src/tls.js` 证书 CN 仍为 `dsh-links`**（见 5.3），需维护者决策。
8. ~~工作树脏~~ —— **已解决**：§0 的 `a91562ae` 产物在**干净树**上构建，实测无 `-dirty`。
9. ~~签名方案只含 v2~~ —— **已修**（§0.2）：`enableV3Signing = true`，新产物 v2+v3 双签名。
