# DeepLinks 重设计 v4：详细修改方案

> 本文是执行方案。视觉以 `design-v4.html`（63 个页面，浅色 / 深色）为准，规则以 `visual-rules-v4.md` 为准。
> 页面编号（如 4.3）在全文、设计稿、PR 标题中一一对应。
> 基线：`lunaship/dsh-links` main @ `3987c94`。

---

## 0. 目标与完成标准

**目标**：把 Android 客户端的所有页面（含弹层、对话框、二级/三级页、系统通知）迁移到 v4 设计：
实底、Material 3、单一品牌色、内容层和功能层分开；同时删掉省钱/均衡/最强档位，
并把“插件文本 → App 正则反推 UI”改成插件输出结构化字段。

**完成标准（全部可检查）**

1. 设计稿 63 个页面，每一页都有对应的 Compose 实现，且有一张 screenshot test 基线（浅色 + 深色）。
2. 代码里不再存在：`DshGlass`、`DshEdgeFade`、`DshFloatingControls`、`DshCardSurface`、`DshTranslucentBar`、
   `ModelTierBar`、`ModelTiers`、`TierCustomSheet`、`HomeBalanceBanner`、`DshDynamicColor`、任何 `blur(` / `RenderEffect` 调用。
3. App 内硬编码颜色只出现在主题文件里（由 `DesignTokenUsageTest` 强制）。
4. 消息分类以插件下发的 `kind` 为准；App 端 `ContextInjection.kt` 只作为旧插件兼容的兜底，且不再按 `AGENTS.md` / `CLAUDE.md` 出现与否判断。
5. `apps/android/docs/visual-rules.md` 被 v4 替换（≤ 120 行），6 个架构测试按 v4 改写并通过。
6. 门禁全绿：插件 `npm run prepack`；Android `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`；screenshot 校验。
7. 不新增任何功能（见第 2 节“功能冻结”）。

---

## 1. 执行规则（取代 proposal 002 的“全自动执行规则”）

1. **集成分支**：从 main 切 `redesign/v4`。本方案的所有 PR 都以 `redesign/v4` 为目标分支，**唯一例外是阶段 0**（直接对 main）。
2. **可以自行合并到 `redesign/v4`**：CI 全绿后 squash 合并，再开始下一项。
3. **禁止合并到 main**。`redesign/v4 → main` 只由维护者操作。阶段 0 的 PR 也由维护者合并。
4. **必须停下来等人的点**（开 PR、写汇报，然后停）：
   - 阶段 0 的 3 个 PR 开好之后。
   - 阶段 1（规则 + 架构测试）合并到 `redesign/v4` 之前。
   - 每完成一个阶段 3 模块后，在 PR 描述里附该模块的浅色/深色截图，汇报一次（不等批准，继续下一个模块）。
   - 发现设计稿和代码实际数据冲突（例如设计稿要显示的字段插件根本没有）：在 PR 里写明，按“删掉该元素”处理，不要自己发明替代 UI。
5. **一个子项一个分支一个 PR**：分支名 `v4/<编号>-<短名>`，如 `v4/r3.1-chat`。PR 标题 `[v4 R3.1] 对话页（4.1–4.9）`。
6. **失败处理**：同一个门禁连续修 3 次仍失败，停下汇报。不绕过门禁、不删测试、不调高预算。
7. **截图基线**：UI 改动导致 screenshot 校验失败时，用 “Regenerate screenshot baselines” workflow 在 PR 分支上重生成，
   并逐张对照 `design-v4.html` 的对应页面检查；PR 描述里列出“新基线 ↔ 设计稿页面编号”对照表。
8. **不发布**：不改版本号、不打 tag、不建 Release；只在 CHANGELOG “未发布（main）”下记一行。
9. **进度记录**：每合并一个 PR，在本文末“执行记录”表追加一行。
10. **文案**：中英文都要改。新文案放 `AppLocaleZh.kt` / `AppLocaleEn.kt`，key 用 `v4` 前缀以外的正常命名；删除不再使用的 key。

---

## 2. 功能冻结与去留（执行期间不加任何新功能）

| 现有功能 | 处理 | 落点 |
|---|---|---|
| 审批卡（消息流内） | 移位 | 底部决策栏 DecisionBar（4.3）；首页收件箱可直接批准/拒绝（2.1） |
| 提问卡 | 合并 | 与审批共用 DecisionBar，一次一题（4.4） |
| 目标卡 + 计划清单 | 合并 | 一个 StatusSlot，平时一行，点开展开（4.1 / 4.5） |
| 预览横幅、重连横幅 | 合并 | 进 StatusSlot，按优先级只显示一条：断线 > 待处理 > 目标 > 预览（4.8） |
| “查看改动”标签 | 移位 | 顶栏 diff 角标 + 轮尾文件列表（4.2） |
| 输入区元信息行 | 移位 | 输入框下方两个 chip：`模型 · 推理等级`、`权限`（4.1） |
| 对话 / 轨迹分段控件 | 移位 | 轨迹变成 ⋯ 菜单里的二级页（4.7、4.9） |
| **省钱 / 均衡 / 最强 三档** | **删除** | 连同设置里的档位映射、自定义弹层、偏好存储一起删；只保留“选模型 + 推理等级”（5.2） |
| 首页余额横幅 | 删除 | 低于阈值时作为收件箱里的一条（7.11）；余额只在“模型与余额”页显示（7.7） |
| 设备页 + 设备弹层 | 合并 | “电脑与配对”二级页（7.2） |
| 品牌字体开关、动态取色 | 删除 | 外观只剩主题 + 字号（7.5） |
| 液态玻璃 / 边缘渐隐 / 拟物回退 | 删除 | 全 App 实底 |
| 文本反推 UI | 改造 | 插件输出 `kind`，App 只渲染（R2.3） |

---

## 3. 阶段 0：止血（对 main，3 个 PR，维护者合并）

### R0.1 修复目标轮次崩溃
- **现象**：会话里出现 goal_round 注入时，`MessageItem.kt:526` 执行 `L.goalRoundLabel.format(it)`，
  `it` 是 `goalRoundProgress()` 返回的字符串 `"1/256"`，而文案是 `"第 %d 轮"` / `"Round %d"` → `IllegalFormatConversionException`。
- **改法**：`AppLocaleZh.kt:380` 改成 `"第 %s 轮"`，`AppLocaleEn.kt:380` 改成 `"Round %s"`。
  检查 `goalRounds`（第 724/725 行）的所有调用方传的都是 Int，保持 `%d` 不动。
- **测试**：新增单测，覆盖 `"1/256"`、`"3 / 8"`、`null` 三种 progress，断言不抛异常且输出正确。
  另加一个测试：遍历 `AppLocaleZh` / `AppLocaleEn` 所有含格式符的 key，确认两种语言的格式符类型一致。

### R0.2 修复“提到 CLAUDE.md 就被当成上下文注入”
- **现象**：`src/context-injection.js` 与 `apps/.../native/util/ContextInjection.kt` 里
  `\bAGENTS\.md\b`、`\bCLAUDE\.md\b`、`Instructions from:` 只要出现在文本任意位置就判为注入；
  用户消息“帮我改一下 CLAUDE.md”会被折叠隐藏（`src/history.js:104`、`WorkspaceActivity.kt:1234`、`ChatFeedDerivation.kt:54`、`MessageItem.kt:135`）。
- **改法**（两端同步）：删掉这三条“出现即命中”的规则。只保留**结构性标记**：`<system-reminder>`、`<available_skills>`、
  `<goal_round>` 及其 HTML 转义形式，以及以 `Current runtime context` / `Current DSH file policy` **开头**的文本（用 `^\s*` 锚定）。
- **测试**：两端各加用例：`"帮我改一下 CLAUDE.md"`、`"看看 AGENTS.md 里写了什么"` → 不是注入；原有正例全部保持通过。
  插件与 App 共用一份用例清单 `testdata/context-injection-cases.json`，两端测试都读它，防止再次漂移。

### R0.3 流程与交付包（单独小 PR）
- 把本交付包整体提交到仓库 `docs/redesign-v4/`（`design-v4.html`、`screens/`、`PLAN.md`、`visual-rules-v4.md`、`GOAL.md`、`README.md`）。
- `docs/proposals/002-dsh-focus-roadmap.md` 0.4 第 2 条加注：“自 v4 起作废，见 `docs/redesign-v4/PLAN.md` 第 1 节”。
- `CLAUDE.md` 增加一节“UI 改动规则”：任何 UI 改动必须对应 `design-v4.html` 的页面编号；未在设计稿里的 UI 不做。

---

## 4. 阶段 1：合同（1 个 PR → `redesign/v4`，合并前停下等维护者）

### R1.1 视觉规则 v4 + 架构测试
- 用本目录 `visual-rules-v4.md` 替换 `apps/android/docs/visual-rules.md`；`apps/android/docs/contributing-ui.md` 里引用玻璃的段落同步删除。
- 改写 `app/src/test/java/dev/deeplinks/architecture/` 下 6 个测试，使其强制 v4：
  - `DshPaletteProvenanceTest`：颜色只能来自 v4 token 表（见 visual-rules-v4 §2），不再要求 `--dsw-*` 溯源。
  - `DesignTokenUsageTest`：`Color(0x…)` 只允许出现在 `core/DshTheme.kt`（迁移期临时允许 `DswPalette.kt`，阶段 4 删除）。
  - `DshSurfaceRoleTest`：新增断言——禁止 `Modifier.blur`、`RenderEffect`、`graphicsLayer { renderEffect`、`haze`。
  - `DshShapeRoleTest`：圆角只允许 v4 的 4 档（8 / 12 / 16 / 28 + 全圆）。
  - `DshSpacingUsageTest`：间距只允许 4 的倍数（4–32）。
  - `ComponentLanguageTest`：页面文件不得直接使用 M3 原始 `Card`、`ElevatedCard`、`Surface(tonalElevation>0)`；必须用 v4 组件。
- 迁移期为未迁移文件提供白名单（`architecture/V4MigrationAllowlist.kt`），每迁移完一个模块就从白名单里删掉对应文件；阶段 4 白名单必须为空。
- 删除只为玻璃存在的测试：`GlassPolishV3Test.kt`、`OverlayChromeMeasureTest.kt`（若其中有非玻璃断言，挪到对应新测试）。

---

## 5. 阶段 2：地基（4 个 PR → `redesign/v4`）

### R2.1 主题
- `core/DshTheme.kt`：建立 M3 `lightColorScheme` / `darkColorScheme`，取值见 visual-rules-v4 §2；`Dsh.*` 语义色改为从 `MaterialTheme.colorScheme` + 扩展色（wait / ok / err 及其 soft 底）读取。
- 删除 `core/DshDynamicColor.kt` 及设置项；`core/DshTypography.kt` 只保留 5 档字号（26 / 17 / 15 / 13 / 12）+ 等宽，删除品牌字体开关。
- 纯黑深色背景（OLED）开关保留：开启时 `background`/`surface` 改为 `#000000`，其他不变。
- **立即让玻璃失效**：`DshGlass.kt`、`DshTranslucentBar.kt`、`DshEdgeFade.kt` 内部实现改成实底 / 无操作（保持函数签名，调用方不动），
  这样全 App 从这一步起就是实底。文件本身在阶段 4 删除。
- 验收：`DesignSystemScreenshotTest` 重生成基线；任意现有页面截图里不再有模糊/半透明。

### R2.2 10 个基础组件（新包 `native/ui/v4/`，前缀 `Dl`）
| 组件 | 设计稿参照 | 要点 |
|---|---|---|
| `DlTopBar` | 2.1、4.1、7.x | 返回/关闭 + 标题 + 副标题 + 最多 2 个动作；可选 diff 角标；无阴影、无底色变化 |
| `DlListRow` | 7.1、7.2 | 前导图标（可选）、标题、副标题、尾部（chevron / 值 / 开关 / 单选 / 复选 / 文字按钮）；`danger` 变体 |
| `DlSectionHeader` | 7.1 | 13sp 600 次要色；可选右侧文字或计数 |
| `DlStatusSlot` | 4.1、4.5、4.8 | 一行摘要 + 展开内容；一次只显示一个状态，优先级见第 2 节 |
| `DlInboxItem` | 2.1 | 状态点 + 工作区 + 时间；标题；命令/问题预览；内联按钮（拒绝 / 允许一次 / 回答） |
| `DlComposer` | 4.1、5.6、4.8 | 输入框在上，下方 `+`、模型 chip、权限 chip、发送/停止/麦克风；附件缩略图在输入框上方；离线态禁用发送 |
| `DlDecisionBar` | 4.3、4.4 | 替换输入区；状态行 + 问题 + 命令块/选项 + 主次按钮（主按钮在右，品牌实心） |
| `DlBottomSheet` | 5.x | 包 M3 `ModalBottomSheet`；标题 + 副标题；不叠第二层 sheet（需要时用 Dialog） |
| `DlDialog` | 5.4、5.10、5.11 | 包 M3 `AlertDialog`；可选图标；危险操作只用红色文字按钮 |
| `DlChip` / `DlSegmented` | 4.2、5.2、7.5 | 包 M3 `AssistChip` / `FilterChip` / `SingleChoiceSegmentedButtonRow` |
- 每个组件：浅色 + 深色 + 长文本 + 禁用态 的 screenshot test，放进 `DesignSystemScreenshotTest`。
- 不迁移任何页面，只新增。

### R2.3 插件输出结构化 `kind`（插件 PR + App PR，可在同一 PR）
- `src/history.js`：每条消息增加 `kind` 字段，取值：
  - `user`（用户亲手输入）
  - `injection`（system-reminder、skills 目录、runtime context；附 `labels: string[]`）
  - `goal_round`（附 `round: number`、`maxRounds: number|null`、`objective: string|null`，由插件解析）
  - `model_changed`（附 `from`、`to`）
  - `system_notice`（其他系统提示）
  - 助手 / 工具 / 推理 / 审批 / 提问 维持现有 `role` / `type`，`kind` 与 `role` 相同即可。
- 保留原有 `role` 字段不变（向后兼容老 App）。
- 更新 `docs/MOBILE_SYNC_CONTRACT.md` 与 `docs/COMPATIBILITY.md`：声明 `kind` 字段与协议版本号 +1。
- App：`MobileApi.kt` 解析 `kind`；`ChatFeedDerivation.kt` / `MessageItem.kt` / `WorkspaceActivity.kt:1234` / `TrajectoryView.kt:81`
  **优先读 `kind`**；只有 `kind` 缺失（旧插件）时才走 `ContextInjection.kt` 兜底。
- `MessageItem` 渲染 `goal_round` 时直接用 `round` / `maxRounds`（Int），彻底消除 R0.1 那类格式问题。
- 测试：插件 `test/` 加 history kind 用例；App 加“有 kind 时不调用正则”的单测。

### R2.4 删除三档与余额横幅
- 删除文件：`native/ModelTierBar.kt`、`core/ModelTiers.kt`、`test/.../core/ModelTiersTest.kt`、`native/HomeBalanceBanner.kt`。
- `native/CostSettings.kt`：删除 `ModelTierSettings`、`TierCustomSheet`；保留 `BalanceAlertSettings` / `BalanceAlertSheet`（7.11）。
- `native/ModelPicker.kt:140`：删除 `ModelTierBar(...)` 调用及相关状态；`native/ModelsSettings.kt` 删除“三档”分组入口。
- `native/WorkspaceSidebar.kt:318`：删除 `HomeBalanceBanner(...)`。余额低于阈值时改由收件箱数据源产出一条 “余额不足” 收件箱条目（复用 `core/BalanceAlert.kt` 的判断）。
- `core/CostPrefs.kt`：删除 tier 相关 key；读取到旧 key 时忽略（不崩溃），首次启动清理。
- 同步删除 `AppLocaleZh/En` 中 tier 相关文案；更新 `CostScreenshotTest`。
- 插件侧如有仅供三档使用的字段/接口，一并删除并在 `COMPATIBILITY.md` 注明。

---

## 6. 阶段 3：按模块迁移（每模块 1 个 PR → `redesign/v4`）

顺序固定：R3.1 对话 → R3.2 首页 → R3.3 新任务 → R3.4 弹层 → R3.5 改动/文件/预览 → R3.6 设置 → R3.7 启动与配对 → R3.8 系统层。
每个 PR：只动本模块文件；用 R2.2 组件；从 `V4MigrationAllowlist` 删除本模块文件；补齐本模块所有页面的 screenshot test（浅色 + 深色）。

### R3.1 对话（4.1–4.9）
- 文件：`WorkspaceActivity.kt`、`WorkspaceChrome.kt`、`WorkspaceChromeState.kt`、`OverlayChrome.kt`、`ChatFeed.kt`、`ChatFeedDerivation.kt`、
  `MessageItem.kt`、`ThinkingTrace.kt`、`StreamingText.kt`、`ComposerBar.kt`、`ComposerEditField.kt`、`ApprovalCard.kt`、`QuestionCard.kt`、
  `QuestionAnswers.kt`、`GoalCard.kt`、`PlanChecklist.kt`、`PreviewDetectBanner.kt`、`WorkspaceChangesCard.kt`、`TrajectoryView.kt`、`WorkspaceMenu.kt`、`SessionControlUi.kt`。
- 4.1 运行中：顶栏 = 返回 + 标题 + 副标题（`工作区 · 状态 · 第 N 步`）+ diff 角标 + ⋯。StatusSlot 一行。用户消息右侧浅灰气泡；助手消息无气泡全宽正文。
  工具/思考过程收成**灰色单行**（`思考 6 秒 · 读了 4 个文件 ›`、`运行了 3 个命令 · 改了 2 个文件 ›`）；正在运行的那一步用转圈 + 命令等宽。运行中发送键变停止键。
- 4.2 一轮结束：轮尾依次 = 文件改动卡（最多 3 行 + “查看全部改动”）→ 元信息灰字（模型 · 思考 · 令牌 · 耗时）→ 文字按钮（复制 / 重新生成 / 分享）→ 建议 chip。
- 4.3 待审批：DecisionBar 替换输入区，不在消息流里插卡片；消息流整体变淡。主按钮“允许一次”在右。
- 4.4 回答问题：同一 DecisionBar；单选 + “自己写答案”；跳过 / 下一题。
- 4.5 展开目标：StatusSlot 展开为目标标题 + 进度条 + 计划清单（完成项灰字，不加删除线）+ 暂停目标 / 编辑。
- 4.6 展开工具过程：一条细竖线串起步骤，失败步骤红色，**不做成卡片**。
- 4.7 轨迹：二级页，搜索 + 筛选 chip + 按轮分组，组头右侧时间用次要色。删除原对话/轨迹分段控件。
- 4.8 断线重连：StatusSlot 显示重连（优先级最高）；输入区可编辑但不可发送；发送时 Snackbar 提示“已放回输入框”。
- 4.9 会话 ⋯ 菜单：BottomSheet，上组“查看”（改动、文件、轨迹、子代理、用量、预览），下组“操作”（目标、定时任务、重命名、分叉、分享）。归档 / 删除只在首页长按菜单。
- 验收：对话页文件里不再引用 `DshGlass`、`DshFloatingControls`、`DshEdgeFade`、`DshTranslucentBar`；`ChatFeedScreenshotTest`、`GoalCardScreenshotTest`、`PlanChecklistScreenshotTest` 基线对照 4.1–4.9。

### R3.2 首页收件箱（2.1–2.6）
- 文件：`HomeHub.kt`、`native/ui/DshInbox.kt`、`WorkspaceSidebar.kt`、`WorkspaceSidebarItems.kt`、`SidebarSessionFilter.kt`、`native/util/HomeSections.kt`、`ArchivedSessionsSheet.kt`。
- 2.1：大标题 “DeepLinks” + 下方状态行（`● MacBook Pro · 在线 ⌄`，点开 2.5）；右上 搜索 / 设置。分组：等你处理 / 进行中 / 最近，组头带计数。待处理条目可直接 拒绝 / 允许一次 / 回答。右下 FAB “新任务”。
- 2.2 空态：图标 + 一句话 + “从一件事开始”3 条普通列表行（去掉星形图标和白卡）。
- 2.3 离线：一条中性底色横幅（只有图标红色），带 重试 / 连接诊断；列表保留最后状态，批准按钮禁用。
- 2.4 搜索：顶部搜索框 + 工作区筛选 chip；结果按“标题匹配 / 内容匹配”分组，命中词品牌色。
- 2.5 电脑与工作区弹层：上组“电脑”（当前电脑 + 连接状态，进入 7.2），下组“工作区”单选 + 添加工作区。
- 2.6 长按：BottomSheet：重命名 / 分叉为新会话 / 分享对话 / 归档 / 删除（红色，最后）。

### R3.3 新任务（3.1–3.4）
- 文件：`NewTaskDraftCanvas.kt`、`ComposerDraftPersistence.kt`、`native/util/ComposerSuggestions.kt`。
- 3.1 草稿页：顶栏“新任务”+ 工作区选择；DlComposer 置于底部；上方显示“智能体预设”chip。3.2 选择工作区 sheet；3.3 添加工作区（路径输入 + 最近目录）；3.4 智能体预设 sheet（单选 + 说明）。

### R3.4 对话里的弹层与对话框（5.1–5.14）
- 文件：`CommandPalette.kt`、`ModelPicker.kt`、`PermissionSheets.kt`、`ImageAttach.kt`、`ImageCropSheet.kt`、`UsageSheet.kt`、`UsageMath.kt`、`SubagentTree.kt`、
  `ConversationShare.kt`、`ShareCardRenderer.kt`、`WorkspaceDialogs.kt`、`WorkspaceSheets.kt`、`MessageSelectText.kt`、`GoalCard.kt`（编辑目标）、定时任务所在文件（`WorkspaceMenu.kt` / `WorkspaceViewModel.kt` 中的 schedule 部分）。
- 5.1 指令面板：输入 `/` 后在输入区上方弹出，按“智能体 / 会话”分组，边输入边过滤。
- 5.2 模型与推理：模型单选（名称 + 供应商 · 上下文）+ 推理等级三段 + “上下文已用 46% · 只影响这个会话”。**无档位**。
- 5.3 权限：三项单选；“完全权限”用橙色图标和文字。5.4 选完全权限 → Dialog 二次确认。
- 5.5 附件 sheet：拍照 / 相册 / 文件 三个大按钮 + 最近图片。5.6 带附件输入区。
- 5.7 用量：一个大数字 + 上下文进度条 + 键值行。5.8 子代理：名字正文色粗体，状态只用转圈 / “完成”标签。
- 5.9 分享：预览卡 + 分享为图片 / 导出为文本 / 复制链接到电脑。
- 5.10 重命名、5.11 删除（Dialog，危险按钮红色文字）、5.12 定时任务（本会话 / 全部会话 tab + 开关列表）、5.13 编辑目标（Dialog，“清除目标”红色在左）、5.14 选择文字（全屏）。

### R3.5 改动、文件与预览（6.1–6.6）
- 文件：`WorkspaceChanges.kt`、`WorkspaceChangesPanel.kt`、`WorkspaceChangesText.kt`、`IntralineDiff.kt`、`CodeHighlight.kt`、`WorkspaceFileBrowser.kt`、`ProducedFiles.kt`、`PreviewScreen.kt`、`WebViewSecurity.kt`。
- 6.1 改动：tab 本轮 / 整个会话 / 未提交；文件行 = 文件名等宽 + 目录次要色 + 右侧 `+n −m`；底部“就这些改动提问”。
- 6.2 diff：统一视图，增删只用浅色底；底部 上一个 / 下一个 / 就这段提问。
- 6.3 文件浏览：面包屑 + 列表（不做树）。6.4 文件预览：底部 复制路径 / 分享 / 引用到对话。
- 6.5 预览全屏：顶栏关闭 + 地址等宽 + “经电脑转发 · 已在电脑上批准” + 刷新 + ⋯。6.6 预览空态。

### R3.6 设置（7.1–7.15）
- 文件：`SettingsActivity.kt`、`SettingsRoute.kt`、`SettingsViewModel.kt`、`ModelsSettings.kt`、`CostSettings.kt`、`ConnectionDiagnostics.kt`、
  `CrashReportSheet.kt`、`ArchivedSessionsSheet.kt`、`devices/DevicesActivity.kt`、`devices/DeviceCard.kt`、`native/ui/DshGroupedList.kt`。
- 7.1 设置首页：平铺列表 + 分组标题；当前电脑是唯一一块容器色块。分组：通用（语言 / 通知 / 外观）、智能体（对话默认 / 模型与余额）、其他（会话记录 / 关于）。
- 7.2 电脑与配对：合并原设备页与设备弹层；连接方式（局域网 / Tailscale / 远程中继，各自状态）+ 这台电脑（连接诊断 / 修改名称 / 更换电脑）+ 解除配对（红）。
- 7.3 连接诊断：每项一行，图标表示状态，副标题给原因或建议；复制结果在顶栏。
- 7.4 通知、7.5 外观（主题三段 + 纯黑背景 + 字号滑杆 + 预览；删除品牌字体 / 动态取色）、7.6 对话默认。
- 7.7 模型与余额：余额大数字 + 余额提醒入口 + 供应商列表 + 添加供应商（**无“三档”分组**）。
- 7.8 供应商详情、7.9 更换 API 密钥（空输入框 + 占位提示，不显示旧密钥掩码）、7.10 获取模型、7.11 余额提醒、7.12 会话记录（已归档 / 已删除 tab，恢复）、7.13 关于、7.14 法律文档、7.15 上次崩溃。

### R3.7 启动与配对（1.1–1.6）
- 文件：`devices/SplashActivity.kt`、`devices/PairingPanel.kt`、`devices/PairQrFlow.kt`、`devices/PairingQr.kt`、`devices/ScanActivity.kt`、`core/StartupRouting.kt`。
- 1.1 启动（仅品牌标）、1.2 欢迎/未配对（三步说明 + 扫码主按钮 + 输入配对码次按钮）、1.3 扫码（深色取景，提示文字对比度 ≥ 4.5:1）、1.4 输入配对码、1.5 等电脑批准、1.6 配对失败（列出尝试过的地址 + 建议）。

### R3.8 系统层（8.1–8.3）
- 文件：`core/DshNotifier.kt`、`core/CompletionNotifier.kt`、`core/CompletionNotice.kt`、`core/AppLocaleNotifications.kt`、`core/ApprovalActionReceiver.kt`、
  `SessionBackgroundMonitorService.kt`、`ShareCatcherActivity.kt`、`native/util/IncomingShare.kt`。
- 8.1 锁屏通知（不显示内容）、8.2 通知栏（标题 = 会话名，正文 = 状态；“允许在通知栏直接批准”默认关）、8.3 系统分享进来（选择工作区 + 预填输入框）。
- 通知图标统一单色；通知渠道名称与 7.4 文案一致。

---

## 7. 阶段 4：收尾（1–2 个 PR → `redesign/v4`，然后交维护者合 main）

- 删除文件：`native/ui/DshGlass.kt`、`DshEdgeFade.kt`、`DshFloatingControls.kt`、`DshCardSurface.kt`、`native/DshTranslucentBar.kt`、`OverlayChrome.kt`（若已无引用）、
  `core/DswPalette.kt`（色值已并入 DshTheme）、`core/DshDynamicColor.kt`（R2.1 已删则跳过），以及所有不再被引用的旧 `Dsh*` 组件。
- `V4MigrationAllowlist` 必须为空并删除该文件。
- `rg -n "blur\(|RenderEffect|Glass|EdgeFade|Translucent|ModelTier|HomeBalanceBanner" apps/android/app/src/main` 结果为空。
- 全量重生成截图基线；PR 描述附 63 页对照表。
- README 截图换成新界面；`apps/android/THIRD_PARTY_NOTICES.md` 删掉玻璃相关依赖说明（如有）。
- CHANGELOG “未发布（main）”写一段：v4 重设计、删除三档、删除余额横幅、修复两个 bug、插件新增 `kind`。
- 汇报并停止，等维护者把 `redesign/v4` 合入 main。

---

## 8. 每个 PR 的通用检查清单

- [ ] PR 标题带编号；描述列出覆盖的设计稿页面编号。
- [ ] 只改本子项范围内的文件。
- [ ] 无新增硬编码颜色、圆角、间距（架构测试通过）。
- [ ] 浅色 / 深色 / 大字号（1.3x）三种截图都看过；无文字截断、重叠、对比度不足。
- [ ] TalkBack：可点击元素有 contentDescription；触控区域 ≥ 48dp。
- [ ] 中英文文案都已更新；删除了不再使用的文案 key。
- [ ] 门禁全绿；截图基线与设计稿逐张对照。
- [ ] 执行记录表已追加一行。

---

## 9. 风险与对策

| 风险 | 对策 |
|---|---|
| 迁移期新旧组件混用，界面不一致 | R2.1 先让玻璃全部失效（实底）；每模块一次迁完，不做半个页面 |
| 插件 `kind` 与旧 App 不兼容 | 保留 `role`；App 无 `kind` 时走兜底；协议版本 +1 写进 COMPATIBILITY |
| 删三档后用户旧偏好残留 | `CostPrefs` 读取旧 key 时忽略并清理，不崩溃 |
| 截图基线大量变化，难以审查 | PR 描述必须附“基线 ↔ 页面编号”对照表；一次只迁一个模块 |
| 智能体为“让测试过”而改测试 | 规则第 6 条；架构测试只允许在 R1.1 改写，之后只允许从白名单删除条目 |

---

## 10. 执行记录

| 子项 | PR | 结论 / 偏差 |
|---|---|---|
| R0.1 / R0.2 | #41 / #42（对 main，待维护者合并） | 无偏差 |
| R0.3 | #43 | 无偏差 |
| R1.1 | #44 | 合并时截图校验红 23 张，均为 main 上 #39 / #40 遗留，R2.1 重生成基线修复 |
| R2.1 | #45 | `DshContrastTest` 两处取样随 v4 色表调整；`Dsh.*` 与 `ColorScheme` 同源推导而非读 `colorScheme`；纯黑容器色按 visual-rules 取 #141416；聊天页画布暂为 surface1，R3.1 改正 |
