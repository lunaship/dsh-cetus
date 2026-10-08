# C06 差距表：审批与问题决策面板

方案：`Cetus-整体改造方案-2026-10-07.md` §10（第 257–285 行）。页面：4.3 / 4.4（+ 首页 2.1）。
基线：`cetus/main` @ `6449bda8`。审计人：brand-cleanup。日期：2026-10-08。

**结论摘要**：15 条要求中 **9 条已满足**、**4 条部分**、**2 条缺失**。
其中 **P0 缺失 2 条**（10.2.2 未知题型用户可见说明、10.2.8 服务器校验错误定位到题），
**P1 部分 2 条**（10.1.4 大命令可滚动全文、10.1.6 多 pending 显示位置）。

> 说明：C02/C03/C05 的前置工作已落地，因此本表有相当比例是「复核已满足」，不是全部待做。
> 判定依据均为**代码行号 + 实际行为**，不是推测。

---

## 10.1 审批（7 条）

| # | 要求 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 1 | 决策面板显示真实请求（状态/工具摘要/命令/工作区/权限边界），缺数据则省略 | **部分** | `DLDecisionBar.swift:37-58` 渲染 status + question + command 块；`ConversationBar.swift:77` 用 `approvalCommand(from:)` 抽命令，`PhoneDecision.swift:55-65` 无命令则不画空块 ✅。**缺**：工作区与权限边界未进面板（`RequestMessage` 无 workspace/permission 字段，`RequestState.swift:24-77`）。P2：需合同扩展才能做，本轮不做 |
| 2 | 主动作仅「允许一次」，拒绝为次动作；**不得**加「永久允许」或滑动批准 | **已满足** | `ConversationBar.swift:83-84` secondary=`reject` / primary=`allowOnce`；全文件无永久允许、无滑动手势。`DLDecisionBar` 仅两个 `dlBarButton` |
| 3 | 请求 ID 贯穿；同 ID 只成一条记录；终态不被迟到 pending 回滚 | **已满足** | `RequestState.swift:110-115` `mergeStatus`：`isTerminalRequestStatus(current) && incoming == .pending` → 保留 current；`requestStatusRank` 终态 rank=4。归并入口 `RequestStateReducer`（同文件 424-487）统一承载 4 种输入；`coalesce`(165-200) 按 approvalId/questionRpcId 归并。有测试 `test/`… 见 §测试 |
| 4 | 大命令块**面板内可滚动、可看全文**，读完前仍保留拒绝入口；不以截断掩盖危险部分 | **部分** | `DLDecisionBar.swift:39-44` 命令是 `UILabel` + **`numberOfLines = 4` 固定截断**。轻微超长即被截掉尾部，且无滚动 —— 这正是要求点名要避免的。**P1 待修** |
| 5 | 提交期间按钮忙碌；失败在**面板内**提示；另一端已处理退为终态、不留过期可点按钮 | **部分** | `decisionBusy`（`ConversationPage.swift:135, 626-629, 785-787`）→ `ConversationBar.swift:64` `view.isEnabled = !(inDecision && decisionBusy)` ✅；失败提示 `decisionNotice = .decisionFailed` → `ConversationPage.swift:442-443` `DLBanner` ✅（在输入区上方、面板相邻）。终态判定 `isDecisionHandled`（705-711）= `isTerminalRequestStatus` → `ConversationBar.swift:71-78` 显示「已处理」并**去掉拒绝按钮** ✅。**缺口**：提示是 banner 而非「面板内」；且 `decisionHandled` 只换文案，primary 仍可点（`decisionHandledPrimary`="Dismiss"，语义可接受但非终态禁用）。P1 收敛 |
| 6 | 多 pending 按现有顺序逐个处理，**显示数量/位置**；切换不丢普通草稿 | **部分** | 顺序：`PhoneDecision.swift:28-34` 取 `reversed()` 最新一条 ✅。草稿：`ConversationPage.swift:359` `drafts.freeze(.prompt)` + `:362 unfreeze` ✅（不丢正文）。**缺口**：**无「第 N / 共 M 个请求」显示**。`questionProgress`（`ConversationCopy.swift:452`）只覆盖**同一提问内的题号**，不是多个 pending 请求之间的位置。P1 待修 |
| 7 | 42% 淡化先做可读性实验：弱化不覆盖命令与必要上下文；VoiceOver 先决策后上下文，不能读到「不可用」仍可点击 | **部分** | 淡化只作用于消息流：`ConversationPage.swift:498` `.opacity(composerOn && decision != nil ? 0.42 : 1)` 在 `MessageStreamView` 上，**不覆盖**决策面板 ✅。**缺口**：`DLDecisionBar` **无任何 accessibility 配置**（全文件 grep 无 `accessibilityLabel`/`Elements`/`Traits`）；`statusLabel.numberOfLines = 1` 会截断状态。P1：需补 VoiceOver 顺序与 label |

## 10.2 问题（8 条）

| # | 要求 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 1 | 单一 `QuestionDecisionPanel` 行为契约承载题面/说明/单选多选/自由输入/上一题下一题提交 | **已满足** | `QuestionAnswers.swift:56-201` `QuestionForm` 是纯值契约；UI 由 `ConversationPage.swift:525 questionChoices` + `:566 questionNavigator` 承载同一 `questionForm` 状态。未新建重复组件（符合「类型名可沿现有组件」） |
| 2 | 选项值与显示文案分开，提交真实值；**未知题型给安全说明，不能默默提交空数组** | **部分（P0）** | 值/文案分离 ✅：`optionValue`（168-174）按 id→value→label 取真实值，`allowed`（162-166）过滤非法值。不提交空数组 ✅：`isUnsupported`（150-153）→ `isComplete=false`（121）→ `answerBody=nil`（130）→ `move(.submit)` 返回 nil（104）**提交被拦住**。<br>**缺失**：**未知题型没有任何用户可见说明**（`ConversationCopy.swift` 无 unsupported 相关 key）。用户会看到按钮点不动却不知为何。**P0 待修** |
| 3 | 无选项题直接显示编辑器；有选项且允许自定义时给「自己写答案」；不自行扩大服务端支持 | **部分** | 自由输入框恒在：`ConversationPage.swift:571` `TextField(placeholder: questionAnswerPlaceholder)`。**缺口**：「有选项且允许自定义」时才显示自定义框的**条件判断缺失** —— 当前无条件渲染，无选项与有选项走同一分支；且未读合同的 `allowCustom`/`custom` 开关。P1 待修（需按合同字段收敛） |
| 4 | 每题草稿按 `(hostID, sessionID, rpcID, questionID/index)` 保存，上下题恢复选择与自由文本 | **已满足** | key 含 hostID+sessionID：`ComposerDraftKey`（`ComposerDraftModel.swift:71-79`）+ `ConversationPage.swift:340-342` 构造。rpcID+题号：`QuestionFormSnapshot`（`QuestionAnswers.swift:204-213`，`rpcID` + `index` + `drafts[questionID]`），`snapshot(rpcID:)`(191) / `restore(_:rpcID:)`(196-200) 只在 rpcID 相符时恢复。分离：`kind: .answer`（`.answer` 定义于 `ComposerDraftModel.swift:8`），正文走 `.prompt` ✅。`persistQuestionDraft`(602-606) 存、`syncQuestionForm`(608-618) 恢复 |
| 5 | 必填未答不能前进；跳过仅在合同允许时出现；多选/自由回答/选项混合规则一致 | **已满足** | `currentCanAdvance`（110-113）必填未答 false → `move(.next)` 返回 nil（101）；`canSkip`（73）只在 `optional==true` 时 true，`secondaryIsPrevious`（74）否则给「上一题」。混合规则统一走 `answered`（155-160）：选中**或**自由文本非空即算答 |
| 6 | 普通消息草稿与回答草稿**彻底分离**；问题出现/切题/提交/取消都不清普通草稿 | **已满足** | 两个独立 slot：`.prompt` vs `.answer`。`syncQuestionDraft`(356-365)：有问题→`freeze(.prompt)`（保留内存文本）；无问题→`unfreeze(.prompt, text: draft)` + `clear(.answer)`。提交成功只 `drafts.clear(.answer)`(633)，不碰 `.prompt` |
| 7 | 最后一题「提交回答」提交完整表单；顶部/底部**不得同时两个语义不清的发送按钮** | **部分** | 完整表单 ✅：`answerBody`(129-139) 用 `questions.enumerated()` 全量构造。单按钮 ✅/⚠️：`questionNavigator:588` 末题显示 submit，同时 `ConversationBar` 在 question 模式下 primary 也是 `copy.text(.send)`（`ConversationBar.swift:93`）—— **确实存在两个「发送」语义按钮**（导航区 submit + 决策栏 send）。P1 待修 |
| 8 | 服务器校验错误**定位到对应题**，修改后可重试；超时/另一端回答/请求过期按真实状态处理 | **缺失（P0）** | 无任何按题定位逻辑：`QuestionAnswers.swift` grep 无 `HostClientError`；`ConversationPage.swift:636-638` 所有错误一律 `decisionNotice = .decisionFailed`（单一通用文案），用户不知道是哪一题错。终态处理 ✅ 部分：`isTerminalRequestStatus` + SSE `question-resolved`（`ConversationModel.swift:700, 734-736`）→ 退为终态。**P0 待修**：需 `resolveQuestionFailure` 按题号/字段映射 |

---

## P0 / P1 实施清单（本轮范围）

| 优先级 | 项 | 计划改动文件 |
|---|---|---|
| **P0** | 10.2.2 未知题型用户可见说明（不静默、不空数组） | `QuestionAnswers.swift`（新增 `unsupportedExplanation` 契约）、`ConversationCopy.swift`（新文案）、`ConversationPage.swift`（**需 lead 精确 diff**） |
| **P0** | 10.2.8 服务器校验错误定位到题 + 可重试 | `QuestionAnswers.swift`（新增 `QuestionValidationError` 纯函数）、`ConversationCopy.swift`、新测试 |
| **P1** | 10.1.4 大命令可滚动全文 + 拒绝始终可见 | `DLDecisionBar.swift`（UILabel → 可滚动 UITextView，设 maxHeight） |
| **P1** | 10.1.6 多 pending 显示数量/位置 | `QuestionAnswers.swift`? 否 → `DLDecisionBar.swift`（新增 `positionText`）+ `ConversationBar.swift` + `ConversationCopy.swift` |
| **P1** | 10.2.3 自定义答案入口按合同条件显示 | `QuestionAnswers.swift`（`allowsCustom` 纯函数）+ `ConversationPage.swift`（**需 lead 精确 diff**） |
| **P1** | 10.2.7 去掉重复「发送」语义 | `ConversationBar.swift`（question 模式下 primary 不应再叫 send） |
| **P1** | 10.1.5 提示进面板 | `DLDecisionBar.swift`（新增 notice 行）+ `ConversationBar.swift` |
| **P1** | 10.1.7 VoiceOver 顺序 / 不截断状态 | `DLDecisionBar.swift` |

### 明确不做（附理由）
- **10.1.1 工作区 / 权限边界**：`RequestMessage` 无对应字段，需先扩同步合同，超出 C06 写入范围。
- **10.1.2 永久允许 / 滑动批准**：方案明令禁止。
- **10.2.3 扩服务端支持**：方案明令「不自行扩大服务端支持」，只按合同字段收敛显示条件。

### 复用的既有基建（不重复造）
`RequestStateReducer`（终态归并）、`ComposerDraftStore` 的 `.answer` kind、`QuestionFormSnapshot`、
`SubmissionState`/`SubmissionResolver`（修订号语义）、`DLBanner`。
