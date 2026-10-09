# C14 差距盘点：两平台一致性与容易遗漏的产品细节

> 方案来源：`Cetus-整体改造方案-2026-10-07.md` 第 467 行起（§18 C14）。
> 逐条：**要求 → 代码位置 → 实际行为 → 判定**。

---

## 已修复（本轮，均有测试且验证过「测试能抓住回归」）

| # | 方案要求 | 修复前实况 | 修复 |
|---|---|---|---|
| **1** | 删除、归档、重命名后返回栈和当前目标更新，**避免旧页面继续发送** | `hide()`（删除与归档共用）只清 `phoneAction`，**从不动 `path` / `selectedSessionID`**。`path` 直接绑定 `NavigationStack(path:)`，所以归档正在浏览的会话后，用户**停在已归档的会话页上**，那里仍能发消息、仍能点审批 —— 服务端已不把它当活跃会话 | 新增 `closeSession(_:)`：只摘掉被归档/删除的那**一个**会话，保留用户在其之上打开的其他页面（设置/改动页对应主机级目的，仍然有效）；归档**失败**时不动导航（服务端没接受，用户该留在原地看错误） |
| **2** | **错误不是空状态**：权限拒绝、服务不支持、真空数据、加载失败分别表达 | `InboxPresentation.offlineEmpty` 不携带原因：「断网连不上」与「未授权/证书不符」显示**同一句话**。后者重试没有意义，用户会反复点重试 | `offlineEmpty(reason:)` 携带 `InboxOfflineReason`：`.unreachable`（可重试）与 `.rejected`（配对失效，重试无用，需重新配对），两种文案与图标 |

| **3** | RFC §5.7：`CLOCK_SKEW` 时用 `hostNow − 本机时间` 作为偏移量**重试一次** | iOS **只把 `clockOffsetSec` 一路透传到时间戳，但从来没有代码设置它** —— 手机时间偏差 > 60 秒时远程将**永久连不上**。Android 一直实现了该重试（`HostHttp.kt` 的 `clockRetried` 分支），两端行为不一致 | `NWRemoteTunnelTransport.open` 捕获 `CLOCK_SKEW` 并带修正后的偏移重试一次；再失败则原样抛出，由上层提示「手机时间不准」。偏移计算抽成 `public static func clockOffset(hostNow:now:)` 以便单测 |

| **4** | 问题「必填 / 可选」两端语义一致 | 插件与 Android 都把 `required: false` 归一为可选，**iOS 只读 `optional`**。同一道题在 Android 可跳过、在 iOS 被当成必填卡住提交。目前插件在出口处已归一，所以线上未暴露 —— 但这等于把两端一致性押在"上游永远替我们归"上 | `ClarifyingQuestion` 加自定义解码：`optional ?? (required == false ? true : nil)`，显式 `optional` 优先；并补回随之消失的 `encode(to:)` |

| **5** | 改动摘要 `total` / `added` / `deleted` 的取数与回退 | iOS 三处都与 Android 不同，后果都是**用户可见的自相矛盾**：① `total` 无下限 → 出现「Changed 1 files」下面列 3 行；② 顶层 `added`/`deleted` 缺省时取 0（而非逐文件之和）→ 顶部 `+0 -0` 而每行都有真实数字；③ 逐文件负数未按 0 计，会抵消正常行数 | `changeCard` 对齐 `WorkspaceChanges.kt:95-99`：`total = max(总, 文件数)`；`added`/`deleted` 缺省用逐文件之和；逐文件 `max(0, …)`。同时把 `added`/`deleted` 一并从 `changeCard` 返回，让回退逻辑**只有一处**（原先 `TurnTail` 调用点另写了一份 `?? 0`） |

| **6** | 单文件对比必须校验「返回的是这份文件」（§4.4） | Android 有 `diffMatchesFile`（`WorkspaceChanges.kt:53`）：`path` 不一致 → 判为不可用且**不写缓存**。**iOS 没有这条校验**，把 Host 返回的任何内容按**下标**直接写进 `fileDiffs`。摘要在「列出文件」与「请求对比」之间可能变化（新一轮改动 / SSE 增量 / 重新拉取），下标一旦错位就会把**别的文件**的差异显示在这个文件名下；更糟的是它进了缓存（`guard fileDiffs[index] == nil`），错的内容会一直留着 | `loadFileDiff` 请求前记下该下标的 `path`，返回体 `path` 不一致 → 记入 `unavailableDiffs`、**不入缓存、不重试**；`ChangesPage` 对该行显示「对比不可用」而不是一直空着。取不到期望 path 或缺返回 path 时**不误判**（保持旧行为） |

配套测试：`InboxDeleteNavigationTests`（4）、`InboxOfflineReasonTests`（3）、
`RemoteClockSkewRetryTests`（4，覆盖手机落后/超前/已对齐/补偿后对齐四种情形）、
`RequiredFieldNormalizationTests`（4）、`ChangeCardConsistencyTests`（6）、
`DiffPathValidationTests`（5）。
**并且逐条验证过这些测试是 load-bearing 的**：临时把修复注释掉后，对应的用例确实失败
（`archivingOpenSessionPopsIt` / `archivingKeepsOtherDestinations` /
`unauthorizedIsRejectedNotUnreachable`），确认不是永远为真的空断言。

## 已对照核验的两端一致性（本轮逐项 diff 过）

| 维度 | 结论 | 依据 |
|---|---|---|
| 状态归并 | **一致** | 两端都识别 `stoppedReason` 的同一组取值；错误分类映射逐条对齐 |
| 停止原因取值 | **一致** | iOS `inboxStopKind`（`Inbox.swift:637`）与 Android `stoppedReasonLabel`（`MessageItem.kt:871`）覆盖完全相同的六个值：`interrupted` / `stopped` / `error` / `maxtokens`+`max_tokens` / `aborted` / `timeout` |
| awaiting 语义 | **一致** | 两端都把 `awaitingApproval`/`awaitingInput` 归为「运行中且等你处理」，并从 running 计数中排除 |
| diff 行号推进 | **一致** | iOS `Review.swift:42-59` 与 Android `WorkspaceChanges.kt:304-311` 规则逐条相同：hunk 头无行号，`+` 只推进新侧，`-` 只推进旧侧，上下文两侧都推进；缺失 `oldStart`/`newStart` 时都回退 0 |
| 离线最后更新时间 | **一致（iOS 已实现）** | iOS 用 `lastOnlineAt` + `inboxTime` 渲染「最后更新 …」；无记录时退回「这是最后一次保存的内容」文案 |
| 审批 outcome 取值 | **一致** | 两端都发 `allowed-once` / `rejected`，无第三种写法 |
| 请求状态归并 | **一致** | `approvalUiStatus` 与 `mergeRequestStatus` 的输入输出逐条对齐：`allowed-once`/`rejected`→resolved、`cancelled`→cancelled、`unavailable`→expired、其余→unknown、缺省→pending；终态判定与优先级（终态 4 / unknown 2 / pending 1 / 无 0）也相同 |
| 草稿按会话隔离 | **一致** | iOS 落盘键 `(hostID, sessionID, kind)`；Android 用 `slotKey`（每主机一条）+ 会话 `ownerKey`；两端都按主机与会话两级隔离 |
| 问题必填语义 | **修后一致**（见下表 #4） | 插件 `question-answers.js:70` 与 Android `QuestionAnswers.kt:63` 都把 `required === false` 当作可选；**iOS 之前只看 `optional`** |

## 配对维度对照结论（本轮补完）

| 阶段 | 契约 | iOS 实现 | 判定 |
|---|---|---|---|
| 等待 | `POST /dsh-link/pair` 返回 pending 时停留在等待页并轮询 | `PairingFlowModel` 的 `.pending` 分支 → `page = .pending` + `startPolling()` | **一致** |
| 批准 | 轮询到 `approved` 才保存并进入首页 | 轮询终态 `.approved` → `finish(host)`；`.rejected` 走失败页 | **一致** |
| 保存 | 非机密检查点先写、可跨重启恢复 | `pending-pairing.json` 检查点 + `restore()` 恢复 pending 页 | **一致** |
| 导航 | 批准后进首页；拒绝/超时留失败页 | `finish` / `showFailure` 两条出口 | **一致** |
| 错误阶段 | 各错误可区分且可操作 | `PairingFailure` 分类 + 文案 | **一致** |
| 同名冲突（`SAME_NAME`） | 弹对话框「[替换它] [换个名字]」 | `conflictPresented` + `replace()` / `chooseNewName()`；`replace()` 复用原 requestId 而不是新建请求 | **一致** |
| `replacing` 字段 | 「面板/**App** 用它提示『批准即替换』」 | iOS **解码并测试**（`DeviceRow.replacing`、`MobileModelsContractTests`），但**没有设备列表 UI 去渲染它** | **按设计**：设备列表由插件面板提供（`src/panel.js:297` 已渲染该提示）；方案与设计稿都没有要求 App 内建设备管理页。iOS 拉 `/mobile/devices` 只用于解绑时识别本机设备 |

> 平台可不同的部分（CameraX vs 系统扫码）按方案允许保留差异，未做统一。

## 已满足（核对确认，未改）

| 要求 | 证据 |
|---|---|
| 忙碌状态仍可阅读和编辑；不用全屏 loading 封死会话 | `ConversationPage` 无全屏 loading 遮罩；忙碌只体现为局部 `loadingOlder` / `filesLoading` / `changesLoading` 与决策按钮禁用，正文与输入区始终可读可编辑 |
| 当前模型和权限必须真实：切换失败不可继续显示新值 | `SettingsModelsModel.save()` 只在服务端返回后 `merged(...)` 更新展示值；失败仅设 `state.error`，**不动**已显示的值。另有 `guard settingsWritable` 与 `catalogContains` 前置校验，不合法直接 `rejected` 而不发请求 |
| 服务拒绝/只读来源不能显示保存成功 | 同上：`settingsWritable == false` 时 `state.error = .notWritable`，不发送、不更新 |
| 搜索的失败 / 空 / 降级三态分别表达 | `searchResults(...)` 按 `failed` / `degraded` / 空分别渲染，已有 `searchFailed` / `searchEmpty` / `searchDegraded` 文案 |
| 通知到已删除/已处理会话是正常边界 | `PushOpenRouter` 三态（`session` / `refresh` / `homeMissing`），未知会话先重取再退回首页提示，不崩溃不无限重试 |
| 首次使用演示与真实配对模式隔离 | `PerformanceLaunchFixture` / Demo fixture 由启动参数显式启用，生产路径不经过 |

## 仍未验证

| 要求 | 状态 |
|---|---|
| 列表排序、时间格式与任务来源**两端一致** | 未逐项对照 Android 实现；需要同一 fixture 下比对两端输出 |
| 设备时钟偏差不应改变请求归并 | **部分**：远程路径的 `CLOCK_SKEW` 自愈已补齐（上表 #3）。但「时钟偏差不影响请求归并」本身仍未构造场景验证 |
| 小屏 / 横屏 / 单手返回 / 键盘手势 / VoiceOver / 大字号**随模块验收** | 部分：截图矩阵覆盖大字号与无障碍变体；横屏、键盘手势、VoiceOver 实际朗读顺序未在真机验证 |
| 无网络 / 低电量 / 低内存 / 长会话 / 巨大代码块 | **未做**：属于性能与真机场景，本地无法构造（模拟器在本环境也起不来） |
| 自动更新入口、安装说明、隐私文档重命名后不指向失效地址 | 插件侧白名单已过渡期兼容新旧仓库（`dfaa13c5`）；文档域名迁移未启动 |
| 配对流程的阶段语义 | **已对照**（本轮，见下节） |
| 文件与改动的路径/范围/统计/权限 | **统计与对比归属已对齐**（见下表 #5、#6）；路径规范化形式与权限差异仍**未对照** |
| 更名后两端行为 | **部分**：插件白名单过渡期已兼容；两端文案与安装身份未逐项比 |
