# C08 差距盘点：改动与 diff 接入真实数据和真实动作

> 方案来源：`Cetus-整体改造方案-2026-10-07.md` 第 314 行起（§12 C08，页面 6.1、6.2，入口来自 4.x 状态槽/导航与结果）。
> 代码基线：`cetus/main` @ `def80993`（本文件写作时的验证基线）。
> 逐条列出：**要求 → 代码位置（文件:行）→ 实际行为 → 判定**。
>
> 判定口径：
> - **已满足**：代码已按方案实现，且有测试或明确代码证据。
> - **部分**：主路径可用，但存在方案明确要求而缺失的细节。
> - **缺失**：未实现。

---

## 0. 结论摘要

**方案 §12 开头的前提在本基线已不成立**（与 C09 同一情况）：

> 方案原文：「当前 `ChangesPresentation` 传 `files: []`，而截图用 sampleFiles。`ReviewPages.swift` 的轮次/差异段按钮存在空 action。」

实际：

- `ConversationPage.swift:1070-1096` 的 `ChangesPresentation` **已从 `model.changes?.files` 取真实数据**，并接上了 `changesLoading` / `changesError` / `onRetry` / `onOpenDiff` / `onPrevious` / `onNext` / `onAskFile` / `files: model.fileDiffs`。
- `:174` 的 `ReviewScreen.sampleFiles` 只在 `staticSnapshot` 截图路径里（截图壳，不是生产路径）。
- C08 提交 `410e7d73`「改动页接真实数据 —— 不再传空集合」早已在树上。

**但逐条复核后发现 3 个真实缺口**（见 §2、§3），其中两个是**与 Android 的语义不一致**：

| # | 缺口 | 严重度 |
|---|---|---|
| **C8-G1** | **diff 无行号、无 `+`/`-` 符号列** —— Android `DiffRow` 有双列行号（`WorkspaceChanges.kt:189`），iOS `DiffLine` **根本没有行号字段** | 高（方案 R9 明确要求；Android 与 iOS 同 fixture 下呈现不一致） |
| **C8-G2** | **diff 说明行缺失** —— Android 有 `diffNotes`（新建 / 删除 / 两侧相同 / 逐行超时 / 截断，`WorkspaceChanges.kt:321-328`），iOS **无对应实现**；`coarse` / `before` / `after` / `truncated` 解码了但从不呈现 | 高（方案 R4「二进制和超大文件状态都来自响应」；用户会看到无说明的空 diff） |
| **C8-G3** | **「下一个差异段」hunk 导航缺失** —— `ReviewText.previousHunk` / `nextHunk` 与 `review.previousHunk` / `review.nextHunk` 文案**已存在但无人引用**（死字符串）；`ReviewPages.swift:207-208` 注释自述「不再有上/下 hunk 死按钮」 | 中（方案 R6 明确要求；需与 Lead 确认是否有意放弃） |

另有 1 项需与方案/Android 口径对齐：**R10「下一轮改动不替换当前审查轮次」无实现**（见 §3 C8-G4）。

---

## 1. 逐条复核

### R1 建立 Review ViewModel/服务边界，输入真实 hostID、sessionID、选定 turn/seq、能力集；展示模型和服务分开

- 代码：`ConversationModel.swift:48-64`（`ConversationServing` 协议声明 `changesSummary` / `changesDiff`）；`:146-155`（无网络实现用于截图/预览）；`ConversationLiveService.swift:247-261`（真实网络实现，`sessionPath` 拼 `/changes`）；`:267-277`（模型态 `changes` / `changesSeq` / `changesLoading` / `changesError` / `fileDiffs`）
- 实际：模型（`ConversationModel`）与传输（`ConversationServing` 协议 + `ConversationLiveService` 实现）分离；输入是真实 `hostID` / `sessionID`，轮次用 `seq` 选定；能力集经 `ready` 事件进 `absorbStatusEvent`。
- 测试：`ChangesTurnNavigatorTests`；`WorkspaceFileExportTests` 用注入式假服务驱动 `ConversationModel`。
- 判定：**已满足**。

### R2 使用 `workspace_changes` 与 `/changes?seq=` 合同，保持 Host 文件顺序和 index 对比路由关系；不得在 UI 排序后把错误 index 发回服务端

- 代码：`ConversationLiveService.swift:247-261`（`changesSummary(sessionID:seq:)` → `sessionPath(id, "/changes")` + query `seq`）；`:255-260`（`changesDiff` 用同一个 `index`）；`ConversationPage.swift:1088-1094`（`onOpenDiff: { index in model.loadFileDiff(seq:index:) }`，index 直接来自 `ForEach(Array(files.enumerated()))`）
- 实际：UI **不排序**，直接按响应顺序用数组下标；index 原样回传服务端，路由关系保持。
- 判定：**已满足**。

### R3 本轮/整个会话/未提交三个范围按 Host 实际能力展示；不支持的范围不伪造内容

- 代码：`ChangesSummary`（`Changes.swift:164-170`）只有 `turn` / `total` / `added` / `deleted` / `files`，**无 scope 字段**；`ReviewPages.swift:100-119` 顶栏只有「上一轮 / 下一轮」导航，无范围切换
- 实际：Host **只按轮给改动**，没有「整个会话 / 未提交」两个范围。与 Android R3.5 已记录的偏差一致（`docs/redesign-v4/PLAN.md` R3.5：「6.1 没有『本轮 / 整个会话 / 未提交』三个 tab：Host 只按轮给改动，保留顶栏的上一轮 / 下一轮切换」）。
- 判定：**已满足（按「不支持的范围不伪造内容」处理）**。两平台一致，且未伪造不存在的范围。

### R4 文件摘要、增删统计、二进制和超大文件状态都来自响应。真实无改动、能力不可用、加载失败分别呈现

- 代码：`ReviewPages.swift:131`（`file.binary == true || file.oversized == true` → 显示 `binaryFile` 说明）、`:146-166`（增删统计 `added` / `deleted`）、`:87-93`（`files.isEmpty && error` → `changesUnavailable` + 重试）、`ConversationModel.swift:462-470`（`changesSummary` 返回 `nil`（Host 不支持）→ `changes = nil` + `changesError = true`，注释明确「出空态，不出假数据」）
- 实际：摘要 / 统计 / 二进制 / 超大来自响应；**无改动**（空数组）、**能力不可用**（`nil` → 空态）、**加载失败**（`catch` → 错误态）三态分开。
- **缺口**：`coarse`（逐行超时整文件替换）、`before` / `after`（新建 / 删除）、`truncated`（截断）**在模型里解码了但 UI 从不呈现** —— 见 C8-G2。
- 判定：**部分**（缺 diff 说明行）。

### R5 点文件打开真实 diff；懒加载、取消、错误重试、最大行数与截断说明接入现有合同

- 代码：`ReviewPages.swift:149-157`（`diffOpen` 按钮 → `onOpenDiff(index)`）、`ConversationModel.swift:555-565`（`loadFileDiff` 懒加载，已有则不重复拉）、`Review.swift:34-52`（`diffLines(from:budget:)` 按 `intralineBudgetCells` 预算做行内高亮降级）、`ReviewPages.swift:179-183`（`linesFromDiff` 只认 `kind == .text` 且有 hunk，否则返回 `nil`）
- 实际：点文件懒加载真实 diff；有名额预算（`budget`）控制行内增强开销；非文本返回 `nil`。
- **缺口**：请求**取消**（切换轮次时 `fileDiffs = [:]` 丢弃了旧结果，但未取消在途请求）；**截断说明**未呈现（同 C8-G2，`truncated` 字段未用）。注意 `filesTruncated`（目录条目数截断）**是另外一项且已实现**，不要混淆。
- 判定：**部分**（缺取消、缺 diff 截断说明）。

### R6 上一轮/下一轮改变真实 turn/seq；上一个/下一个差异段滚动到真实 hunk，并给当前位置。边界按钮禁用

- 代码：`ConversationModel.swift:424-443`（`viewChanges(seq:)` / `changesPreviousSeq` / `changesNextSeq` / `viewAdjacentChanges(forward:)`，切换时清空 `fileDiffs`）；`ConversationPage.swift:1078-1084`（`canPrevious` / `canNext` 由 `changesPreviousSeq != nil` / `changesNextSeq != nil` 决定）；`ReviewPages.swift:104-118`（上/下轮按钮，`.disabled(!canPrevious || loading)`）
- 实际：**上一轮 / 下一轮**改变真实 `seq`，边界按钮正确禁用（已满足）。
- **缺口**：**上一个 / 下一个差异段**（hunk 间跳转 + 当前位置提示）**未实现**。`ReviewText.previousHunk` / `nextHunk` 与 `review.previousHunk` / `review.nextHunk` 是**死字符串**；`ReviewPages.swift:207-208` 注释自述「不再有上/下 hunk 死按钮」。见 C8-G3。
- 判定：**部分**（轮次导航已满足；hunk 导航缺失）。

### R7 「就这些改动提问/就这段提问」先生成包含真实文件/范围的草稿引用，回到会话编辑器供用户发送；不能点击后无反应，也不默认发送

- 代码：`ConversationModel.swift:446-451`（`changesAskReference(index:)` → `ChangesTurnNavigator.askReference(paths:)`）；`ConversationPage.swift:1077`（`onAsk: { onAsk(model.changesAskReference()) }`）、`:1079`（`onAskFile`）；`ConversationPage.swift` 的 `onAsk` 闭包（`draft = ChangesTurnNavigator.appending(reference, to: draft)` + `drafts.update(reference, kind: .reference)`）
- 实际：生成含真实文件路径的引用，**并入草稿但绝不自动发送**（注释明确「只预填，不自动发送」）。
- 测试：`ChangesTurnNavigatorTests`。
- 判定：**已满足**。

### R8 返回改动列表保留范围、滚动位置和所选文件；iPad inspector 与 iPhone push 使用相同状态源

- 代码：`ConversationPage.swift:1086-1096`（`regular` → `.inspector`；否则 `navigationDestination`，**两者共用同一个 `page` 实例**）；`ConversationPage.swift:1007` 附近（`showChanges` 状态）
- 实际：iPad inspector 与 iPhone push 渲染**同一个 `ChangesPage` 构造**，状态源相同（满足）。
- **缺口**：「返回改动列表保留**滚动位置和所选文件**」未见专门实现（无 `ScrollViewReader` 锚点或选中项恢复，对比 C09 `filesReturnAnchor` 有做）。`filesPath` 那套返回锚点属于 6.3 文件浏览，不是 6.1 改动列表。
- 判定：**部分**（同源已满足；滚动位置 / 所选文件保留未实现）。

### R9 颜色之外还有增删符号/行号；横向长代码滚动不妨碍返回手势。选中与复制遵循系统

- 代码：`ReviewPages.swift:219-224`（`lineBackground` 仅颜色）、`:227-246`（`lineText` 仅整行文本 + 行内加粗）、`Packages/DLCore/Sources/DLCore/IntralineDiff.swift:27-37`（`DiffLine` 只有 `kind` / `text` / `emphasis`，**无行号字段**）
- 实际：**只有颜色 + 行内加粗，没有 `+`/`-` 符号列，也没有行号。**
- **对照 Android**：`WorkspaceChanges.kt:189-203` 的 `DiffRow` 明确「**带双列行号**」，`oldNo` / `newNo` 逐行推进（`:301-317`：`+` → `newNo++`，`-` → `oldNo++`，上下文 → 两者都 `++`）。**iOS 缺这一整层。**
- 判定：**缺失**（C8-G1）。这是本次最重要的发现：与 Android 语义不一致，且方案 R9 明确要求。

### R10 会话发生下一轮改动时，不替换用户当前正在审查的历史轮次；提示可切换到新轮次

- 代码：`changesTurnSeqs` / `changesPreviousSeq` / `changesNextSeq` 存在（可用于计算「是否有更新轮次」），但**没有**任何「新轮次到来时不替换当前审查 + 提示切换」的逻辑
- 实际：SSE 收到新改动时不会主动替换用户正在看的 `seq`（因为 `changesSeq` 只由用户操作或 `viewChanges` 改变，这一点是安全的）；**但也没有「有新轮次」的提示**。
- 判定：**部分**（不替换是安全的；提示缺失 → C8-G4）。

---

## 2. 与 Android 的语义对齐表

| 能力 | Android | iOS 现状 | 判定 |
|---|---|---|---|
| 双列行号（`oldNo` / `newNo`） | ✅ `DiffRow.oldNo/newNo`（`WorkspaceChanges.kt:191-192`、`:301-317`） | ❌ 无行号字段 | **不一致（C8-G1）** |
| diff 说明（新建 / 删除 / 相同 / 超时 / 截断） | ✅ `diffNotes`（`:321-328`） | ❌ 无 | **不一致（C8-G2）** |
| 上下文折叠（`CONTEXT_FOLD_MIN` / 展开） | ✅ `DiffRow.FOLD` + `hiddenCount` + `foldStart` | ❌ 无 | 不一致（方案未在 R 里点名；Android 有 iOS 无） |
| 悬挂缩进续行 | ✅ `hangingIndentRanges` | ❌ 无 | 不一致（同上，属呈现细节） |
| 行内变化高亮 | ✅ `emphasis` | ✅ `emphasis`（`DiffLine`） | 一致 |
| 按轮次切换 | ✅ | ✅ `viewAdjacentChanges` | 一致 |
| 引用到草稿 | ✅ | ✅ `changesAskReference` | 一致 |
| 三范围 tab | ❌（Host 只按轮） | ❌ | 一致（都不做） |

> 行号与 diffNotes 是我判定「应补」的两项，因为方案 R9 / R4 **明确写了**，且 Android 已有实现（同 fixture 下两端呈现必须一致，这是 §12 验收口径）。

---

## 3. 缺口清单

| # | 要求 | 现状 | 建议 |
|---|---|---|---|
| **C8-G1** | R9：颜色之外还有增删符号 / 行号 | `DiffLine` 无行号字段；`DiffPage` 不画符号列 | 在 `DLCore` 给 `DiffLine` 加 `oldLineNumber` / `newLineNumber`（可选 Int），在 `diffLines(from:)` 里按 Android 同规则推进（`+`→new，`-`→old，上下文→both）；`DiffPage` 左侧加单色等宽符号列（`+` / `-` / 空格）+ 双列行号。**用既有 `ReviewText.added/deleted`；不新增服务端字段。** |
| **C8-G2** | R4：二进制 / 超大 / 无改动状态来自响应 | `coarse` / `before` / `after` / `truncated` 解码了但不呈现 | 加 iOS 版 `diffNotes` 纯函数（对齐 Android `DiffNote`），在 `DiffPage` 顶部/底部显示说明行（新建 / 删除 / 两侧相同 / 逐行超时 / 截断） |
| **C8-G3** | R6：上一个 / 下一个差异段 + 当前位置 | 未实现；`previousHunk` / `nextHunk` 文案已是死字符串 | 需要 Lead 决策：**补 hunk 导航**（`DiffPage` 加 `ScrollViewReader` + hunk 下标锚点，复用已有文案），或**明确记录为有意不做并从文案表删除死字符串**。仓库规则「未在设计稿里的 UI 不做」——6.2 设计稿底部确有「上一个 / 下一个」（`docs/redesign-v4/PLAN.md` R3.5），故我倾向补。 |
| **C8-G4** | R10：下一轮改动时提示可切换 | 不替换（安全）但无提示 | 低优先级；用 `changesTurnSeqs` 与当前 `changesSeq` 比较，有新轮次时显示一条可点击提示 |
| **C8-G5** | R8：返回改动列表保留滚动位置与所选文件 | 未实现（inspector 与 push 同源已满足） | 若 Lead 认为必要，按 C09 `filesReturnAnchor` 的既有做法补 |
| **C8-G6** | R5：切换轮次时取消在途 diff 请求 | 只清空结果，不取消请求 | 加 `Task` 句柄取消（与 C09 `filesTask?.cancel()` 同样做法） |

## 4. 未决问题（需 Lead 定）

1. **C8-G3（hunk 导航）做不做？** 方案 R6 要求、设计稿 6.2 有该按钮、文案已存在，但上一轮实现者**主动注释掉**并写「属后续」。若不做，建议把 `previousHunk` / `nextHunk` 及两条 `review.*` 文案删掉，避免留死字符串。
2. **C8-G1 / C8-G2 是否本轮做？** 这两项是**跨平台一致性**问题（Android 有、iOS 无），影响 §12 的「与 Android 同 fixture 下一致」验收。我倾向都做。
3. **C8-G4 / C8-G5** 优先级低，可延后。

确认后我按 C09-G3 的方式实施：worktree + 门禁 + 文件清单，不造第二套数据通路，不动 `ConversationPage.swift` 以外他人持有的文件（如需接线会先发 diff）。
