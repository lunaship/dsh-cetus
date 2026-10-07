# cetus 命名合同（N01）

> 状态：已生效。来源：桌面方案 `Cetus-整体改造方案-2026-10-07.md` §21（N01），用户 2026-10-07 确认。
> 本文是**命名与迁移的合同**：设计合同、App 显示名、资源、测试期望值都以本文为准。
> 执行顺序见「§7 发布顺序」；冲突时本文优先于任何历史文档里的旧品牌名。

---

## 1. 已确定的名称（不再提出候选名）

| 对象 | 目标值 | 大小写 / 形式 | 执行约束 |
|---|---|---|---|
| 产品品牌 | `cetus` | **全小写** | 用户已确定；不再讨论候选名 |
| Android / iOS 界面显示名 | `cetus` | **全小写** | 桌面图标、系统设置、通知、分享、关于、演示模式一致 |
| 插件包名与注册名 | `dsh-cetus` | 全小写连字符 | npm metadata、Cordis patch、服务端 `name`、客户端 id 同步 |
| 项目对外仓库 | `lunaship/dsh-cetus` | 全小写连字符 | 迁移前检查同名仓库、依赖与权限；本文不宣称已完成改名 |
| App 产物文件名 | `cetus-<platform>-<version>-<shortSHA>.<apk/ipa>` | 全小写 | 不靠显示名判断安装身份 |
| 推送 / 中继人类可读名 | `cetus push gateway` / `cetus relay` | 全小写 | 与协议名（`dlpush/1`、DLP/1）分开，协议名不改 |

### 1.1 大小写规则（最容易出错的一条）

- **界面显示名、品牌文案、演示文案一律小写 `cetus`。**
- **不要**写成 `Cetus`、`CETUS`，也不要写成 `cetus links` / `Cetus Links` / `cetus 连接`。
- `Cetus`（首字母大写）**只允许**出现在内部工程标识：Xcode scheme / target / 模块 / 测试 target（`CetusTests`、`CetusUITests`）、Swift package target、Gradle module 名。这些是工程标识，不是用户可见品牌。
- 句首位置也不要为了让句子通顺而把 `cetus` 首字母大写。中文句子里直接写 `cetus`。

### 1.2 项目角色描述（可选，用于 About / 仓库简介）

- 中文：`cetus · DSH 随身工作台`
- 英文：`cetus — a mobile companion for DSH`

这只是说明产品作用，不改用户指定的名称，**不暗示由 DeepSeek 官方运营**。

---

## 2. 「整体改名」的三类字段

改名的验收不是「仓库搜索旧名零结果」——那是做不到也不该做的。真正的验收标准是：
**新用户界面里没有意外的旧品牌；旧标识只出现在下面登记的兼容白名单位置。**

| 类型 | 示例 | 决定 |
|---|---|---|
| A. 用户可见品牌 | 页面文案、启动图、图标名、通知、分享标题、下载文案、关于页 | **本轮必须换成 `cetus` / `dsh-cetus`** |
| B. 内部工程标识 | scheme、target、Gradle namespace、模块前缀、二进制名、测试 runner | 分批改；没有持久身份依赖的可以改，有依赖的**先写迁移再改** |
| C. 持久 / 协议身份 | applicationId、bundle ID、Keychain service、App Group、证书、AAD、QR 格式、API 路径、协议名 | **必须保护兼容**；旧值进入「§3 legacy 白名单」后才允许保留 |

**禁止**直接对全仓做全局查找替换。A/B/C 三类必须先分类，再分别处理。

---

## 3. legacy 兼容白名单（必须保持原样）

以下值**本轮不改**，测试与评审不得把它们判为「改名不彻底」。改动其中任何一项都会导致老用户装不上、配对失效或数据丢失。

| 类别 | 保留值 | 原因 |
|---|---|---|
| Android applicationId | `dev.deeplinks` | 改了就是另一个 App，覆盖升级装不上 |
| Android debug 包名 | `dev.deeplinks.debug` | 与正式包并存，调试链路依赖 |
| iOS bundle ID | `dev.deeplinks.ios.debug` 及阶段 9 前的既有 id | 同上；正式 id 由维护者定 |
| iOS 扩展 bundle ID | `.notification-service` / `.live-activity` / `.share` 前缀不变 | 与主 App 签名身份绑定 |
| Keychain service / access group | 现有值 | 改了等于所有用户凭据失效，必须重新配对 |
| App Group | `group.dev.deeplinks.ios` | 主 App 与三个扩展共享容器，改了扩展读不到数据 |
| 共享容器路径 / `ShareInbox` | 现有路径 | 扩展与 App 的交接契约 |
| 通知 channel ID | 现有值 | 改了用户已设置的通知偏好失效 |
| SharedPreferences / 数据库文件名 | 现有值 | 改了等于清空用户设置 |
| 加密 AAD / HPKE 域分离前缀 | `dlpush/1 token\|`、`dlpush/1 content\|` | 与已发布客户端逐字节兼容 |
| QR 载荷字段名 | 现有字段 | 扫码配对两端契约 |
| 手机 API 路径 | `/dsh-link/mobile/...` | 已发布 App 硬编码 |
| 协议名 | DLP/1、`dlpush/1` | 协议标识，与人类可读名分开 |
| 深链 scheme | `deeplinks://share/<id>` | 分享扩展与 App 的交接；改动需两端同时发版 |
| 用户工作区名 / 真实历史会话 | 原样保留 | **不是品牌残留缺陷**，见 §5 |
| 许可证、历史提交、CHANGELOG 历史条目 | 原样保留 | 历史记录 |

**规则**：上表新增一行，必须有 PR 说明「为什么不能改」以及「改了会破坏什么」。白名单是合同，不是垃圾桶。

### 3.1 已登记的具体值（2026-10-07，由 B4 执行者冻结）

**A 类 — 显示名，期望值就是小写 `cetus`**

| 位置 | 改后值 |
|---|---|
| iOS `CFBundleDisplayName`（`App/Info.plist`） | `cetus` |
| Android `@string/app_name`（`values/strings.xml`） | `cetus` |
| Android `@string/share_to_dsh`（分享目标标签） | `cetus` |
| iOS `Localizable.xcstrings` / `InboxCopy` / `PairingCopy` / `SharePickerSheet` 里的品牌名 | `cetus` |

iOS 界面文案里出现品牌名的地方（例如 `Send to cetus`、`cetus needs local network access…`）**也一律小写**。句首不大写。

**B 类 — 内部工程标识，允许 `Cetus` 首字母大写，非用户可见**

| 项 | 改后值 | 备注 |
|---|---|---|
| Xcode project name | `Cetus` | 原 `DeepLinks` |
| app target | `Cetus` | 原 `DeepLinks` |
| 扩展 target | `NotificationService` / `LiveActivity` / `Share` | **不变**；改产品名会牵连扩展 bundle 与嵌入逻辑 |
| test targets | `CetusTests` / `CetusUITests` | 原 `DeepLinksTests` / `DeepLinksUITests` |
| schemes | `Cetus` / `CetusPerformance` | CI 同步 `-scheme`、`-only-testing:` |

**C 类 — 冻结身份（测试可直接断言「不变」）**

| 项 | 值 |
|---|---|
| Android applicationId | `dev.deeplinks` |
| Android debug applicationId | `dev.deeplinks.debug`（`applicationIdSuffix`） |
| Android namespace | `dev.deeplinks`（**决定不改**，见下） |
| Android testInstrumentationRunner | `androidx.test.runner.AndroidJUnitRunner` |
| Android FileProvider authority | `dev.deeplinks.fileprovider` |
| iOS bundle ID（app） | `dev.deeplinks.ios.debug` |
| iOS bundle ID（extensions） | `dev.deeplinks.ios.debug.notification-service` / `.live-activity` / `.share` |
| iOS bundle ID（tests） | `dev.deeplinks.ios.debug.tests` / `dev.deeplinks.ios.debug.uitests` |
| iOS Keychain service | `dev.deeplinks.ios` |
| iOS App Group | `group.dev.deeplinks.ios` |
| Android 通知 channel ID / SharedPreferences / DB 名 / AAD | 全部不动 |

**两条经确认的「故意保留」（不是漏改）**

1. **Android namespace 保持 `dev.deeplinks`。** 改 namespace 需同步 R/BuildConfig 全量 import、Java 包目录、Manifest 全限定名与 ProGuard，回归面大，且与 applicationId 不一致会徒增认知负担。执行者评估后**选择不改并单独报告**；因此 `dev.deeplinks.*` 的 import 与包名是**有意保留**。日后要改，须单独 PR + 全量回归。
2. **iOS entitlements 文件名随 `PRODUCT_NAME` 改为 `Cetus.entitlements`，并同步 `PushNotificationAuthorization.swift` 的硬编码查找串。** 理由：`PRODUCT_NAME` 改为 `Cetus` 后，保留 `DeepLinks.entitlements` 会让文件名与产品名不自洽；该查找有 `embedded.mobileprovision` 兜底，失败仅导致 APNs `enabled=false`，不影响构建与其他能力。**不属于 A/B/C 任一类的身份项**，可以改。若后续发现该文件被签名配置或 CI 按名引用，按「新增白名单行 + 说明」处理，不静默回退。

---

## 4. 旧客户端范围（本轮改名不要求兼容旧 App 的界面文案）

- 已安装的旧版 App（旧显示名、旧图标、旧文案）**不会被远程改名**。用户在更新后才看到新名。
- 插件 `dsh-cetus` 必须继续服务旧 App：新增字段是**加法**，旧字段保持。参考 `docs/MOBILE_SYNC_CONTRACT.md` 与 `docs/COMPATIBILITY.md`。
- 旧 App 的旧显示名不构成缺陷，不在验收范围。验收只看「更新后的 App」。
- 推送网关旧话题 / 旧 payload 结构保持可用；APNs 客户端身份不变。

---

## 5. 不改写的东西（红线）

**用户工作区名称与真实历史会话文本不批量改写。**

- 用户电脑上的真实工作区目录名（例如 `dsh-links`）、会话标题、历史消息正文、用户自己起的名字，一律原样保留。
- 历史讨论、issue 引用、执行记录表里出现旧名，属于历史事实，不改。
- 演示模式（Demo）的 fixture 数据**可以**改成 `cetus`，因为它是产品文案，不是用户数据。两者不要混。
- 判定方法：这段文本是**用户输入的**还是**产品提供的**？用户输入的不动。

---

## 6. 源码与资源清单（必查位置）

| 层 | 必查位置 | 修改内容 |
|---|---|---|
| 插件 metadata | `package.json`、lockfile、`cordis.patch.yml` | name、repository/homepage/bugs、bundle id/name；**依赖版本不顺手更新** |
| 插件服务端 | `src/index.js`、配置 schema、state helper | export name、日志品牌、默认路径与迁移 |
| 插件客户端 | `src/panel.js`、`build-client.mjs` | 面板显示名、section/locale/plugin id、安装/升级提示 |
| 生成产物 | `src/client.js` | **只运行 build-client/prepack 更新，不手改** |
| Android | `strings.xml`、locale 文件、Manifest、启动/图标、通知、更新检查、分享标题 | 显示名、品牌图、更新 URL、系统入口文案 |
| Android 工程 | Gradle namespace、源码 package/import、测试 runner、ProGuard | 内部名称迁移单独 PR，保留 applicationId 策略 |
| iOS 壳 | `project.yml`、App 类型/文件、Info.plist、`InfoPlist.strings`、xcstrings、Assets | 显示名、scheme/target、启动图、AppIcon、UsageDescription |
| iOS 扩展 | NSE/LiveActivity/Share 的 Info.plist、entitlements、ShareInbox | 扩展显示名、路径、共享身份兼容、深链 |
| Swift 包 | `Package.swift`、targets/imports | 可保留稳定模块名；若改名，单独机械迁移并更新全依赖，**不混进功能修复** |
| Relay / push | Go module/import、cmd 二进制、README、服务模板 | 人类可读名先改；module 路径与服务路径迁移分别验证 |
| CI | workflow、scheme、app 路径、artifact 名、tag 读取、生成脚本 | 与新工程名匹配；截图测试名是否迁移要明确记录 |
| 文档 | README、AGENTS、SECURITY、PRIVACY、REMOTE_ACCESS、RELEASING、ARCHITECTURE、COMPATIBILITY、RFC、设计目录 | 当前名称与安装路径改；历史记录**追加迁移说明**，不改写历史 |
| 测试 | plugin-package、state 迁移、更新检查、URL 白名单、深链、共享容器、签名升级 | 旧→新用例；**不靠批量替换期望值掩盖不兼容** |

### 6.1 预检命令（生成分类清单，不直接改）

```bash
git grep -n -I -E 'DeepLinks|deeplinks|dsh-links|dshlinks|DSH_LINKS|dlpush' -- . \
  ':!apps/ios/docs/design/**' ':!**/*.png' ':!**/node_modules/**'
```

逐条标注 `品牌 / 内部 / 协议 / 持久化 / 历史`。**不能直接全局 replace。**

---

## 7. 发布顺序（先合同，后实现）

1. **命名合同（本文）** 先落地，确定目标值、白名单、大小写规则。
2. **设计合同**同步改名：`docs/redesign-v4/`、`docs/ios/`、`apps/ios/docs/` 里的**产品文案与显示名**改 `cetus`；历史执行记录表不改写。
3. **插件改名** `dsh-links` → `dsh-cetus`（B 类内部标识），协议与 API 路径不动。
4. **stateDir 迁移** `~/.dsh/dsh-links` → `~/.dsh/dsh-cetus`，带校验、锁、幂等与冲突停下（单独 PR，风险最高）。
5. **App 显示名**改 `cetus`，安装身份（§3）不动。
6. **仓库 / 下载入口 / 更新器 / 文档 / CI** 跟进。
7. 最后才是版本号、tag 与 Release —— **由维护者处理，执行者不擅自升版本、不打 tag**。

每一步的门禁必须全绿再进下一步。命名迁移**不得**成为「顺手加一批无关功能」的机会。

---

## 8. 回滚限制

- **插件改名可回滚**：package name / patch id 改回即可，前提是 step 4 尚未执行。
- **stateDir 迁移不可简单回滚**：迁移后旧 App / 旧插件会往旧目录**继续写新数据**。回滚必须处理「迁移后新写入」，按方案 §22.4：
  - 回滚前先检测旧目录是否在迁移后产生过新写入（对比迁移时的记录）；
  - 有则先把新数据合并或明确丢弃策略，再切回；
  - 两目录身份冲突（TLS 指纹 / 远程公钥不一致）时**报错停下**，不拼接设备数组、不选较新者、不生成第三把密钥；
  - 迁移失败**不启动线上注册**，避免半迁移身份把 Relay 主机顶掉。
- **App 显示名可回滚**（纯 A 类）；**安装身份不可回滚**（改了 applicationId / bundle ID 等于另起一个 App，用户数据留在旧包）。
- 任何回滚都必须写明「回滚后旧目录里有没有新数据」，不允许只删目录了事。

---

## 9. 给测试的身份常量（可被自动化读取）

测试与截图不得硬编码猜测品牌名，应从本节列出的常量取期望值。执行者把本节内容与代码里的常量保持同步。

| 常量 | 期望值 | 检查点 |
|---|---|---|
| `DISPLAY_NAME` | `cetus` | Android `app_name`、iOS `CFBundleDisplayName`、关于页、通知、分享目标 |
| `DISPLAY_NAME_CASE` | 全小写 | 断言不等于 `Cetus` / `CETUS` |
| `PLUGIN_NAME` | `dsh-cetus` | `package.json` name、`cordis.patch.yml` id、`src/index.js` export name |
| `REPO_SLUG` | `lunaship/dsh-cetus` | repository / homepage / bugs、更新检查白名单 |
| `ARTIFACT_NAME` | `cetus-<platform>-<version>-<shortSHA>` | CI artifact 命名 |
| `ANDROID_APPLICATION_ID` | `dev.deeplinks` | **不变** |
| `IOS_BUNDLE_ID` | 既有值 | **不变** |
| `APP_GROUP` | `group.dev.deeplinks.ios` | **不变** |
| `PROTOCOL_NAMES` | `DLP/1`、`dlpush/1` | **不变** |

**允许旧名出现的位置**：§3 白名单、许可证、历史提交、CHANGELOG 历史条目、用户工作区名与真实历史文本、`apps/ios/docs/design/` 下的历史 PNG 与生成脚本。
**不允许旧名出现的位置**：任何用户可见的界面文案、App 显示名、通知、分享标题、关于页、演示模式文案。

---

## 10. 与其他文档的关系

| 文档 | 关系 |
|---|---|
| `docs/redesign-v4/PLAN.md` | 设计合同；界面文案的品牌名以本文为准 |
| `docs/ios/PLAN.md`、`apps/ios/docs/page-mapping.md` | iOS 实现与页面映射；显示名以本文为准 |
| `apps/ios/docs/visual-rules-ios.md` | 视觉规则；品牌按钮规则引用本文的显示名 |
| `docs/MOBILE_SYNC_CONTRACT.md`、`docs/COMPATIBILITY.md` | 旧客户端兼容；新增字段是加法 |
| 桌面方案 §21–§25 | N01–N05 的完整计划来源；本文是 N01 的可执行合同 |
