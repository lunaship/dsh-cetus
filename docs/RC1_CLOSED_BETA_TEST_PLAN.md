# cetus RC1 封闭 Beta 测试包

本包面向 3–5 名相互独立的测试者。它是测试执行材料，不是公开中继发布说明。公开支持路径仍是同一可信局域网。远程连接（DLP/1）默认关闭，由电脑面板「外出时也能连」打开。

## 测试前发放清单

维护者逐人发放：

1. 指定版本的官方签名 Android APK、版本号和 SHA-256；测试者不得安装重打包 APK。
2. 已锁定的插件 tag（`v0.1.0-beta.19` 或本轮实际 tag）、`relay/` revision 和 DSH 兼容版本，见 RC1 证据记录；不要使用 moving `main`。
3. 一次性、单人连接信息。连接码、Token、主机私钥不得出现在聊天、截图、录屏或反馈正文。一张连接码同时带局域网字段和可选的 `remote{e,r,s}`；没有第二套云端入口。
4. 本包、反馈模板和事故模板；测试者先阅读「停止条件」。

维护者保留一份逐人发放记录，但不把连接码或任何凭据写入仓库。

## 独立 onboarding（每人从零开始）

1. 在可信局域网中启动 DSH 和插件。确认电脑端「手机连接」只显示一张连接码。远程默认关闭。
2. 安装并校验 APK。用「扫描二维码」或「从相册识别」完成配对。完成一次历史读取、发送消息、接收 SSE 事件和审批操作。
3. 关闭 App、重启 DSH，再打开 App，确认设备仍能连接。在电脑端吊销该手机，确认后续请求为未授权。
4. 如被安排远程私测：在面板打开「外出时也能连」，等远程就绪后再出示同一张连接码。手机每次新建连接自己选路（先探测局域网，不通再走中继）。远程首配必须在电脑上批准。确认没有第二套入口。
5. 记录开始时间、首次成功连接时间、设备/系统版本、测试 revision 和结果；不得记录消息正文或凭据。

目标：每名测试者首次连接在 10 分钟内完成；遇到失败先保存脱敏诊断，再按事故模板报告，不要反复重配导致证据丢失。

## 必做任务脚本

- T1 配对与回收：扫码、从相册识别、重启、移除、重新配对各一次。没有手动输入配对。
- T2 会话边界：打开空历史、至少 30 条历史、长消息、SSE 断网/恢复、App 后台再前台。
- T3 真实工作流：新建会话、继续会话、工具/审批成功与超时、切换工作区；不得把「页面打开」算作成功。
- T4 认证与撤销：过期或错误的连接码、重复配对、电脑端吊销手机、旧连接恢复；确认没有越权或跨主机数据。
- T5 远程重连（仅打开了「外出时也能连」的人员）：电脑和手机分别重启；中继重启后手机自动重连；插件重启后远程重新注册。吊销后远程返回 `UNKNOWN_KEY`，需要重新扫码。同一主机密钥在别处注册导致 `REPLACED` 时，记录面板和手机上的表现。每个结果带时间戳。
- T6 运维演练（维护者执行并邀请观察）：备份/恢复、中继重启、插件重启、吊销和回滚。`tls.json` 丢失时重启会生成新证书，已配对手机必须重新扫码。`tls.json` 损坏时插件拒绝启动，原文件备份为 `tls.json.corrupt-<时间戳>`；删除该备份并重启才会生成新证书，已配对手机同样需要重新扫码。测试者只确认用户可见结果。
- T7 本轮缺陷回归（可在局域网或已授权的远程路径执行，合成任务，不记录正文/凭据）：
  - E02 积累超过 500 事件后恢复：完整补齐或明确快照重建，无静默缺口。
  - E04 DSH 一次提出多个问题：每题可见且答案逐题一致。
  - E05 审批期间断网 5 秒 / 超过宽限：前者仍可处理，后者按策略结束。
  - E06 两手机或桌面同时处理审批：一个终态，其他端及时同步。
  - E07 离线中吊销手机：旧 Token、旧请求、重连均不恢复权限。
  - E08 已决定审批的旧历史页：不显示可重复提交按钮。
  - E09 远程单向 SSE 至少 10 分钟：不被反方向独立 idle 切断。
  - E11 新旧 App/插件组合：缺能力时明确提示，无静默丢失或重连死循环。
  - E12 后台通知增强：未实施，记「渠道待定 / 未验证」，不得与五项修复混称。
- T8 远程专项：
  - 蜂窝网络下远程首配，且必须在电脑上批准。
  - Wi‑Fi 与蜂窝来回切换 5 次，每次都能回到可用会话。
  - 后台 10 分钟后回到前台，会话仍在。
  - 回前台 10 秒内发出 13 个以上并发请求，中继日志里不出现 `DEVICE_LIMIT`。
- T9 冷启动：远程路由冷启动 10 次不闪退。记录 logcat 里 `DshStartup` 各阶段耗时。

## 反馈模板

```text
测试者编号：CB-__（不要填姓名/邮箱）
日期/时区：
路径：局域网 / 远程（外出时也能连）
APK 版本与 SHA-256：
插件、中继、DSH 精确 revision/版本：
任务编号：T__
开始/结束时间：
预期结果：
实际结果：
复现次数：__/__
影响：阻断 / 严重 / 一般 / 轻微
脱敏诊断文件：
是否含凭据、消息正文、私钥或连接码：必须为“否”
补充（仅现象，不粘贴正文/Token）：
```

## 事故模板与立即停止条件

```text
事故编号：INC-____
发现时间/路径/任务：
影响范围：单设备 / 单主机 / 多主机 / 未知
最小复现步骤（去掉所有凭据和正文）：
最后一个已知正常时间：
已执行的隔离动作：停止中继 / 吊销手机 / 退出测试
诊断文件：
```

立即停止并通知维护者：看到另一主机或用户的数据、Token 或私钥出现在日志或诊断、吊销后仍可访问、重复配对绕过确认、连接重试造成跨主机串线、消息或文件丢失且无法确认边界、证书指纹不匹配仍建立连接，或任何无法解释的凭据泄露。不要继续扩大测试，不要删除状态，不要把截图发到公开渠道。

## RC1 退出标准

测试窗口至少 7 天，且满足全部硬门槛：

- 至少 4/5 名独立测试者完成 onboarding；每人首次成功连接 <10 分钟。
- 至少 30 次真实远程会话（若远程未开放给该批，则明确记录未满足，不得用模拟数替代）。
- 零越权、跨主机串线、数据丢失、凭据泄露；零重复配对绕过和重连阻断。
- 认证、撤销、重启、`UNKNOWN_KEY` 后重新扫码、`tls.json` 丢失或损坏后的恢复、备份恢复运维演练均有可追溯结果。
- 诊断足够定位失败，且脱敏测试确认不含 Token、连接码、私钥、明文凭据或消息正文。
- 插件与 App CI、本仓 `relay/` Go 门禁、`testdata/dlp1/vectors.json` 三端向量测试和 `npm run test:dlp1-e2e` 全绿；真实 Android→中继→插件、真实公网 CA/TLS、24h soak、容量测量分别有证据，未验证项保持未验证。T8 与 T9 有记录，或明确写成未验证。

任一硬门槛失败，RC1 不能退出封闭测试；修复后重新计数，不得用「总体感觉稳定」覆盖缺失证据。

## 历史

2026-09-29 之前的封闭测试写的是已删除的 DLR/1：接入码、云端二维码、Control、`routeSecret`、Host Enrollment，以及「局域网与中继是两个独立入口」。那些入口已不存在。本文件只描述 DLP/1 的一张连接码。

## iOS 质量核对（阶段 8）

对照 `docs/ios/PLAN.md` 阶段 8 与桌面《cetus iOS · 阶段 8 方案：质量加固》。本节只记录 iOS。上面的 Android / RC1 任务、退出标准和历史不因本节改变。

本表最初写于 `origin/ios/main` 的 `0a3b2bd`（2026-10-05）。截至 2026-10-06，I4.7、I5.1、I6.2、I7.1、I7.2、I7.3 已合入 `ios/main`，但下表记录的真机、读屏、性能和长跑结果没有新增证据，不能把代码合并写成验收通过。

每一行只有两种状态。「有证据」必须指向仓库里真实存在的测试、脚本或文件，并且只覆盖证据实际证明的范围。「未测」不能算通过。模拟器或单测通过不是真机验收。没有 Instruments trace 的项不算性能通过。没有 48 小时记录的长跑不算已做。

### 辅助功能

| 项 | 状态 | 证据或未测原因 |
|---|---|---|
| VoiceOver：配对 → 首页 → 对话 → 审批 | 未测 | 没有 VoiceOver 走查，也没有焦点顺序记录。部分控件有 `accessibilityLabel` / `accessibilityHint`（如 `PairingPages`、`InboxPage`、`ConversationPage`、`MessageRowView`、`DLStatusSlot`、`DLComposerView`），标签存在不等于读屏流程走通。 |
| 可点目标至少 44pt | 未测 | 多处按钮写了 `minHeight: 44` 或 `greaterThanOrEqualToConstant: 44`（`PairingPages`、`ReviewPages`、`ChatSurfaces`、`DLChip`、`DLCodeBlock`、`DLComposerView`、`DLGlassBar`）。没有逐页测量，不能当成全部可点目标已达标。 |
| 降低透明度：无截断、无重叠 | 未测 | `PairingSnapshotTests` 写明 SwiftUI 读不到降低透明度，`DLUI` 也没有系统玻璃样式的覆盖开关。I4.3a 至 I4.6 的执行记录都写了「降低透明度截图没做」。 |
| 增强对比度：无截断、无重叠 | 未测 | 截图 trait 只有浅色 / 深色和字号，没有 `UIAccessibilityContrast` 或增强对比度的测试。 |
| 粗体文本：无截断、无重叠 | 未测 | 没有 `legibilityWeight` 或粗体文本的测试与截图。 |
| 最大辅助字号：无截断、无重叠 | 未测 | 部分页面有 `DynamicTypeSize.accessibility3`（`accessibilityExtraLarge`）模拟器快照，不是 AX5，也没有人工确认无截断、无重叠。覆盖范围见下表。设置、诊断、iPad 和新任务、弹层、改动、文件、预览没有这套大字号快照。 |

已有的大字号快照只证明「这个 trait 下模拟器画得出基线」，不证明最大辅助字号验收通过：

| 测试 | 页面 |
|---|---|
| `PairingSnapshotTests` | `testWelcome`、`testLANExplanation`、`testScan`、`testPending`、`testFailureStates` 里的 network。同名、改名、证书失败、提交中、相册识别不在这套矩阵里。 |
| `InboxSnapshotTests` | `testInbox`、`testEmpty`、`testOffline`、`testSearch`、`testContext`。菜单、删除、重命名、归档和加载态只有浅色中文普通字号。 |
| `ChatSnapshotTests` | `testRunning`、`testTail`、`testProcess`。未确认审批和图片场景没有大字号快照。 |
| `StatusSlotSnapshotTests` | `testCollapsed`、`testExpanded`、`testDisconnected`、`testPending`、`testPreview`。 |
| `ComposerSnapshotTests` | `testApproval`、`testQuestion`。 |

没有 VoiceOver 焦点顺序，也没有最大字号的人工截图。

### 性能

| 项 | 状态 | 证据或未测原因 |
|---|---|---|
| 有本地快照时，冷启动到首页 < 1 秒 | 未测 | 没有 Instruments 时间点。I4.3a 执行记录写明 120fps 与 Instruments 未做。`ConversationFlowTests.snapshotThenHistoryReplacesWholePage` 只验证先读快照再换历史，不测量启动时间。 |
| 3000 条消息滚动不掉帧 | 未测 | 没有 3000 条会话，也没有滚动掉帧记录。 |
| 流式输出 10 分钟，内存不持续上涨 | 未测 | 没有 10 分钟流式运行，也没有内存曲线。 |

### 稳定性

五种情况都要有明确提示并且不崩溃。下表把「错误映射有单测」和「用户能看见的提示已测」分开。单测通过不是真机故障演练。

| 项 | 状态 | 证据或未测原因 |
|---|---|---|
| 断网 | 有证据 | 首页：`InboxFlowTests.refreshFailureKeepsCacheAndSuccessReplacesIt` 在 `.offline` 后保留缓存，且 `deletes() == 0`；`approveSendsAllowedOnceAndDoesNothingOffline` 断网时不发送审批。横幅文案在 `InboxPage.offlineText` / `InboxCopy.offlineTitle`。对话：`ConversationStatusFlowTests.liveStreamDisconnectRecoveryAndGoalUpdates` 把重连显示为 `.disconnected`，目标不丢；`ConversationPage` 在 `loadFailed` 时显示 `ConversationCopy.loadFailed`。`SSEClientContractTests` 的「心跳超时：无行到达判定断线并按退避重连」覆盖断线重连。这些都不是拔网线的真机结果。 |
| 证书变化 | 未测 | 已配对后的证书变化还没有一条覆盖「明确提示且不崩溃」的测试。已有的只是零件：`TLSFingerprintContractTests.evaluateReportsMismatchWithActualFingerprint` 与 `requirePinRejectsLanAddressWithoutValidFingerprint` 拒绝不匹配或缺失的指纹；`HostClientContractTests` 的「证书变更映射：钉扎失败 → certificateChanged（优先于 transport）」；`PinnedSessionDelegate` 只取消认证，不删凭据；`PairingFailureTests.everyErrorHasThreeSuggestionsAndStableReason` 只覆盖重新配对。首页虽然会把 `InboxServiceError.certificate` 放进横幅，但没有 `InboxFlowTests` 断言。对话收成 `ConversationServiceError.certificate` 后仍只显示通用的 `loadFailed` / `statusFailed`。 |
| token 被吊销 | 有证据 | `HostClientContractTests` 的「401 → unauthorized」；`SSEClientContractTests` 的「401：终止且不再重连」；`InboxFlowTests.unauthorizedKeepsSessionsAndDoesNotDropTheHost` 保留缓存、`missingHost == false`、`deletes() == 0`；`ConversationFlowTests.unauthorizedKeepsSnapshot` 保留快照且 `approvalsSubmittable == false`。首页横幅是 `InboxCopy.unauthorized`：「This phone is no longer authorized. Pair again.」。这只证明 App 收到 401 后的行为。没有在隔离环境里做一次吊销，再观察旧请求和重连。 |
| 电脑重启 | 未测 | 没有电脑重启用例。选路只在注入时钟下测过：`RouteSelectorContractTests` 的「Android: network change drops cached decisions」。 |
| 插件升级 | 未测 | `HostClientContractTests` 的「404 → capabilityMissing」只映射错误。已配对界面没有插件升级或能力缺失的专用提示。配对页的 `PairingCopy.badRequest` 只用于配对请求格式不被支持。 |

断网和 401 的单测没有删除凭据。证书钉扎失败只取消连接。没有真机确认这些路径在崩溃、后台恢复或电脑重启之后仍然不删错凭据。

### 隐私

| 项 | 状态 | 证据或未测原因 |
|---|---|---|
| 网络图片默认不加载 | 有证据 | `TranscriptTests.imagesAllowOnlyHTTPS`：https 才 `.allowed`，http、data、javascript 都是 `.blocked`。`ConversationFlowTests.actionsDoNotSend` 对 http 图片得到 `.blocked`。`MessageRowView` 在没有 `.loaded` 时只显示「Load image」按钮，要点击才调用 `loadImage`。这不是「所有远程内容都不出网」：公式和 Mermaid 仍会进随包资源的 `WKWebView`，预览代理会访问已配对电脑。 |
| Release 日志不打印 token、路径、消息正文 | 未测 | 没有 Release 运行日志采样。`0a3b2bd` 的 `apps/ios` 生产 Swift 源码里搜不到 `print`、`debugPrint`、`NSLog`、`os_log` 或 `Logger(`；`import os` 只用于 `OSAllocatedUnfairLock`（`PinnedSessionDelegate`、`SSEClient`、`NetworkPathObserver`）。`HostStoreContractTests.jsonFileNeverContainsSensitiveValues` 只断言沙盒 `hosts.json` 不含 token、证书指纹和远程密钥，不是日志脱敏。源码检索不能代替 Release 日志验收。 |
| 多任务切换时敏感页面模糊 | 未测 | 没有 `scenePhase == .inactive` 的模糊遮罩，也没有 `UIBlurEffect`。现有 `scenePhase` 只驱动连接：`InboxPage`、`ConversationPage`、`PairingFlowView`。`SSEClientContractTests` 的「前台门控：background 主动断开，active 用已提交游标重连，inactive 不动连接」明确保持连接，不盖模糊。 |
| `PrivacyInfo.xcprivacy` 补齐需说明理由的 API | 未测 | `apps/ios/App/PrivacyInfo.xcprivacy` 只登记了 `NSPrivacyAccessedAPICategoryUserDefaults`，理由 `CA92.1`。三个扩展没有自己的隐私清单。`RouteSelector` 的默认时钟是 `ProcessInfo.processInfo.systemUptime`；是否还要登记其他需说明理由的 API，尚未按当前二进制核对。 |

### 本地化

| 项 | 状态 | 证据或未测原因 |
|---|---|---|
| 简体中文和英文逐页人工看一遍 | 未测 | 没有逐页人工记录。部分模拟器快照同时有 `en` 和 `zh-Hans`（配对、首页、对话、状态槽、输入区的矩阵测试），新任务、弹层、轨迹、改动、文件和预览多数只有浅色中文。快照不是人工验收。 |
| `scripts/check-ios-locales.mjs` | 有证据 | 脚本检查 `sourceLanguage` 为 `en`、英文与 `zh-Hans` 的 key 和变体都存在、值非空、格式符类型一致。`scripts/check-ios-locales.test.mjs` 覆盖位置格式符、长度修饰、无法解析的格式符、`%d` 对 `%@`，以及缺语言和空值。`.github/workflows/ci-ios.yml` 的 iOS build 会运行 `node scripts/check-ios-locales.mjs` 和 `node --test scripts/check-ios-locales.test.mjs`。2026-10-05 在 `0a3b2bd` 上这两条命令通过。脚本自己写明还不比对 `substitutions`。 |

### 真机验收

下列次数都没有完整真机记录。模拟器单测、截图和 CI 都不能填进「结果」。2026-10-06 只在一台 iPhone（iOS 27.0.1）上完成了一次隔离局域网配对，并看到首页在线和会话列表；这不计入 10 次冷启动。远程首配、推送送达和 Live Activity 真机更新仍未验收。

| 项 | 要求 | 状态 | 未测原因 |
|---|---|---|---|
| 局域网冷启动 | 10/10 | 未测 | 2026-10-06 一台 iPhone（iOS 27.0.1）完成一次隔离配对并显示首页，但没有 10 次冷启动记录，仍是 0/10。 |
| 前后台切换 | 10/10 | 未测 | 没有真机前后台记录，0/10。单测里的 `PairingFlowTests.backgroundKeepsRecordAndForegroundRestartsPolling` 和 `SSEClientContractTests` 前台门控不是这 10 次。 |
| Wi-Fi ↔ 蜂窝 | 5/5 | 未测 | 没有真机切换记录，0/5。`RouteSelectorContractTests` 的网络代切换是注入事件。 |
| 远程首配 | 1 次 | 未测 | I5.3 未合入。`PairingContractTests.remoteIsParsedButNeverUsed` 只保存 `remote`，不连中继。 |
| 审批 | 1 次 | 未测 | 没有真机审批记录。`InboxFlowTests.approveSendsAllowedOnceAndDoesNothingOffline` 和 `PhoneDecisionTests` 是注入服务。 |
| 提问 | 1 次 | 未测 | 没有真机提问记录。`ComposerSnapshotTests.testQuestion` 只是快照；`PhoneDecisionTests.skipsQuestionAfterALaterUserMessage` 只测选择规则。 |
| 推送四类：审批、提问、完成、失败 | 各送到 | 未测 | I6.2 只有本地网关测试。没有真实 APNs 送达，也没有四类通知的真机记录。 |
| Live Activity 更新 | 1 次 | 未测 | I7.1 已合入，但没有真机上的 Live Activity 更新记录。 |
| 吊销之后的行为 | 1 次 | 未测 | 没有隔离环境中的吊销记录。不要用共享 state 做这次观察。上表「token 被吊销」只覆盖 App 收到 401 后的单测。 |
| 48 小时长跑 | 48 小时 | 未测 | 没有开始，也没有结束记录。做的时候必须使用隔离的 state 目录，并且不得吊销正在使用的真机配对。 |

真机表在补齐次数、设备与系统版本、构建 revision 和时间之前，整列保持未测。
