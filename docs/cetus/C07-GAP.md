# C07 差距盘点：首页、搜索、工作区与跨端信息结构

> 方案来源：`Cetus-整体改造方案-2026-10-07.md` 第 286 行起（§11 C07，页面 2.1–2.6，关联 3.x、7.2）。
> 代码基线：`cetus/main`（本文件写作时的 HEAD）。
> 逐条列出：**要求 → 代码位置（文件:行）→ 实际行为 → 判定**。
>
> 判定口径：
> - **已满足**：代码已按方案实现，且有测试或明确代码证据。
> - **部分**：主路径可用，但存在方案明确要求而缺失的细节。
> - **缺失**：未实现。

---

## 11.1 首页层级

### R1 顶栏电脑名可进详情/切换；菜单统一放低频动作；不同时保留多个同义「管理电脑」入口

- 代码：`apps/ios/App/Features/Home/InboxPage.swift`（顶栏组装）、`InboxModel.swift:823`（`computerName`）
- 实际：顶栏显示电脑名与连接状态；既有流程（`computer` / `computers` 路由）承载详情与切换。
- 判定：**已满足**（唯一入口，无重复同义菜单项）。

### R2 「等你处理」显示审批/提问类别、会话标题、工作区与必要摘要；首页直接审批与对话共用同一提交状态

- 代码：`apps/ios/Packages/DLCore/Sources/DLCore/Inbox.swift:395-405`（`waitingApproval` / `waitingAnswer` / `waiting` 三态 + `dot: .wait`）；`InboxModel.swift:789`（审批动作 `phoneAction`）；`InboxCopy.swift:21-23`
- 实际：置顶分组按类别区分审批与提问，行内带工作区名与摘要；审批提交走模型单一状态（`actionsEnabled` 受在线态约束）。
- 判定：**已满足**。

### R3 工作区收起仍显示待处理/运行计数；展开默认预览 3 条，超出显示「显示全部 N 个/收起」

- 代码：`InboxPage.swift:399-400`、`741-742`（`rest.count > 3` → `showAllCount` / `collapseAll`）；`InboxCopy.swift:66-67`
- 实际：收起态显示计数；展开默认 3 条，超出显示「显示全部 N 个 / 收起」，与 Android 合同一致。
- 判定：**已满足**。

### R4 会话行显示真实状态、最近时间、简短当前步骤或结果；有结构化摘要优先用摘要，不解析 Markdown 假装结构化

- 代码：`Inbox.swift:631-635`（`inboxResultPreview` 直取 `lastResult.text` / `.files`）；`Inbox.swift:410-418`（运行中取 `activity` 预览）
- 实际：直接消费结构化字段，**不存在** Markdown 解析。
- 判定：**已满足**。

### R5 长标题允许合理换行/截断，状态与必要动作不被标题挤掉；未知状态显示未知/更新中，不默认完成

- 代码：`Inbox.swift:421-433`
- 实际：

  ```swift
  if let reason = nonBlank(session.stoppedReason) { status = .stopped(...) }
  else if session.lastResult != nil { status = .done }
  else { status = .unknown }   // C07 要求 5：不能默认"完成"
  ```

- 与 `docs/MOBILE_SYNC_CONTRACT.md:272-281` 的关系（**本任务特别要求核对**）：
  - 合同规定 `stoppedReason` **缺失**时语义是「这一轮以 `completed` 结束」；
  - 但同一行若 `lastResult` 也缺失（缓存滞后、刚结束尚未回填），**无法证明**它是 completed。
  - 现有实现取 **`.unknown`**（文案「未知/更新中」），**不是** `.done`。这是**有意为之且正确**：合同给的是"缺键 ⇒ completed"的**插件侧下发语义**，而客户端**没有本地证据**时不应冒充完成。方案 R5 的「不默认完成」正是指这一点。
  - 结论：两者**不冲突**——合同的回退只在客户端确实收到该行数据且无键时适用；`lastResult == nil` 的窗口期按 unknown 处理更安全。
- 判定：**已满足**（含刻意与合同字面不同的安全回退，已在上文说明理由）。

### R6 同名工作区显示必要父路径；分组优先用注册表 `sessionIds`，仅未就绪时按 cwd 回退；未归属放未分组

- 代码：`Inbox.swift:280-292`（分组）；`InboxModel.swift:474`（`registryReady: !workspaces.isEmpty`）；**`Inbox.swift:183-205`（`inboxWorkspaceLabels` 消歧）**；`InboxPage.swift:425-426`（消费）
- 实际：
  - 注册表优先：`accounts.first { $0.sessionIDs.contains(id) }`；
  - 仅 `registryReady == false` 时用 `session.cwd` 回退（`Inbox.swift:283`）；
  - 未归属进 `InboxWorkspaceFolder(path: nil, ...)`（未分组）；
  - **同名消歧已实现**：`inboxWorkspaceLabels` 从末段起逐级加长后缀，直到该后缀在**当前可见集合内唯一**。实测：

    ```text
    /Users/a/proj/api → "proj/api"
    /Users/b/work/api → "work/api"
    /Users/c/sole     → "sole"      ← 唯一时不加父路径，无噪音
    ```
- 判定：**已满足**。（初稿曾误判为"缺失"，经核对该函数已存在并正确工作，已更正。）


### R7 空工作区可以从该工作区新建；新建目标明确

- 代码：`InboxPage.swift:369-371`（`workspaceEmpty` 状态）、`405` / `413`（`newHere` → `openNewTask(workspace: path)`）；`InboxCopy.swift:63,68`
- 实际：空工作区显示「尚无任务」并给「在此新建」，目标路径随上下文传入。
- 判定：**已满足**。

### R8 删除工作区延用确认流程，不自动删除其中会话

- 代码：`InboxPage.swift:148-156`（确认弹窗）、`414-415`（入口）、`InboxModel.swift:236`、`InboxLiveService.swift:235`
- 实际：破坏性操作走 `confirmationDialog`（`role: .destructive`），提示文案含具体路径；只删工作区注册，不删会话。
- 判定：**已满足**。

### R9 收起状态按主机保存；刷新和从会话返回不全部折叠；后台恢复也不改变用户展开选择

- 代码：`InboxModel.swift:186-192`（按 hostID 存 `UserDefaults`）、`404-405`（**仅在 `init` 恢复**）、`425-426`（用户切换时写回）
- 实际：恢复只发生在初始化；`refresh()` / `reload()` **不重置** `collapsedFolders`、`expandedPreviews`。
- 判定：**已满足**。

---

## 11.2 搜索与离线

### R10 搜索按 2.4 合同；工作区 chip 保留；iOS 用系统 `searchable` 与建议

- 代码：`InboxPage.swift:106-114`、`237-246`（`.searchable` + `.searchSuggestions`）、`373`（工作区 chip）
- 判定：**已满足**。

### R11 输入中文、粘贴关键词、清空、切换工作区、取消搜索后能回到原位置

- 代码：`InboxPage.swift`（`searchable` 绑定 `model.query`）
- 实际：输入/粘贴/清空/切换工作区均可用（`query` 为普通 `String`，中文无特殊处理即正确）。**但「取消搜索后回到原位置」没有实现**：没有任何滚动位置保存/恢复（全文件 grep `scroll`/`position` 无命中）。
- 判定：**部分**（见 G2）。

### R12 区分「没有结果」「搜索还在加载」「只搜索本机缓存」「电脑不可达」；不要全写成空列表

- 代码：`InboxCopy.swift:17-20`（`searchEmpty` / `searchFailed` / `searchDegraded` / `searching`）；`InboxModel.swift:379-380, 458-462`；`466`（`offlineEmpty`）
- 实际：四态各有独立文案与分支，未合并为空列表。
- 判定：**已满足**。

### R13 离线可查看有缓存的会话和历史；新建、发送、批准等需要网络的动作禁用并解释

- 代码：`InboxModel.swift:419`（`actionsEnabled = link.isOnline`）、`789` / `911`（审批与动作前置 `link.isOnline` 守卫）；`InboxCopy.swift:52-54`（离线标题与解释）
- 判定：**已满足**。

### R14 缓存状态显示更新时间；连接恢复后刷新，避免旧等待态长时间继续可提交

- 代码：`InboxModel.swift:202`（`lastOnline(hostID:)`）、`823-824`（`lastOnlineAt`）、`InboxCopy.swift:163-165`（按时间的离线文案）
- 实际：显示"这是 %@ 时的状态"；连接恢复经 `consumeNetwork()` 触发刷新；离线时 `actionsEnabled == false`，旧等待态不会继续可提交。
- 判定：**已满足**。

### R15 删除/归档后列表和搜索结果一并更新；列表重复、子会话混入等沿现有过滤合同回归

- 代码：`InboxModel.swift:434`（`effectiveArchived`）、`437-438`（`visibleSessions` 同时过滤归档与删除）、`840`（搜索候选取自 `visibleSessions`，**同一过滤器**）
- 实际：搜索结果复用 `visibleSessions`，因此归档/删除即时生效；去重与子会话过滤沿用既有 `inboxVisibleSessions` 合同。
- 判定：**已满足**。

---

## 差距汇总（需实施）

| 编号 | 要求 | 判定 | 影响 |
|---|---|---|---|
| **G1** | §11.2 R11 后半：取消搜索后回到原位置 | ✅ **已满足**（本文档此前判为「缺失」，属**陈旧结论**，本轮复核修正） | 实现：`InboxPage.swift:333-345` 的 `searchReturnAnchor` + `searchRestoreToken`（token 变化才触发 `onChange`，保证每次搜索只滚动一次）；锚点选择与列表置顶一致（等待处理的行优先）。测试 `InboxSearchAnchorTests` 6 条：未搜索不发信号、进入搜索记锚点、待处理行优先、结束搜索发一次恢复信号且锚点保留、连续两次搜索各发一次、纯空白不算进入搜索 |

> 初稿曾把 §11.1 R6 的「同名工作区显示父路径」列为 G1 缺失。经复核这是**误判**：
> `inboxWorkspaceLabels`（`Inbox.swift:183`）已实现"最短可区分后缀"，且唯一路径不加父段。
> 已从差距中移除，对应条目标为**已满足**。

### G1 细化（P0）

- 现状：无任何滚动位置记忆（`InboxPage.swift` 内 grep `ScrollViewReader`/`scrollTo`/`scrollPosition` 均无命中）。搜索取消或清空后列表回到顶部。
- 目标：取消/清空搜索后恢复搜索前的阅读位置。
- 落地：在 `InboxModel` 记录进入搜索时的锚点（首个可见行的 `sessionId`，或所在工作区路径），`InboxPage` 用系统 `ScrollViewReader` 滚回该锚点。iOS 用系统能力，不照搬 Android 控件。


---

## 未纳入本次实施（说明理由）

- **§11.1 R1/R6/R7/R8/R9、§11.2 R10/R12/R13/R14/R15**：已满足，本次不改动，避免回归。
  （R6 的"同名工作区显示父路径"经复核已由 `inboxWorkspaceLabels` 实现。）
- **同名工作区的服务器侧去重**：方案只要求"显示必要父路径"，未要求改注册表；保持现状。
- **`ConversationPage` / `ConversationModel` 相关**（方案 §11.1 提到"首页直接审批与对话使用同一提交状态"）：该口径已由 `InboxModel.phoneAction` + 共享 `ConversationStatus` 满足；若需进一步改动属他人写入范围，按 lead 要求**以 diff 形式提交**，不在本文件内直接改。
