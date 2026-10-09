# C15 差距盘点：测试覆盖从截图扩展到生产交互

> 方案来源：`Cetus-整体改造方案-2026-10-07.md` 第 498 行起（§19 C15）。
> 代码基线：`cetus/main`（写作时 HEAD）。
> 逐条：**要求 → 代码位置（文件:行）→ 实际行为 → 判定**。

---

## 19.1 四层验证

| 层 | 方案要求 | 现状 | 判定 |
|---|---|---|---|
| 状态/合同单测 | 草稿 revision、请求归并、分页、迁移、失败映射、加密向量 | 已有草稿（`ComposerDraftTests`）、请求归并（`PhoneDecisionTests`）、迁移（`state-migration` Node 侧）、失败映射（`ConversationStatusTests`）、加密向量（`DlpVectorTests` / `PushRegistrationTests`） | **已满足** |
| 生产入口集成/UI 测试 | 从首页进入会话/设置/改动；真实依赖装配；提交和导航 | `InboxFlowTests` 覆盖真实模型装配与提交；`ConversationFlowTests`、`InboxCatalogTests` 覆盖导航与过滤 | **已满足** |
| CI 截图 | 页面层级、文案、颜色、尺寸变体 | `*.SnapshotTests` + `ci-ios.yml` / `ios-regen-screenshots.yml` | **已满足** |
| 真机/隔离 E2E | 手机↔插件↔Relay/push 完整链路 | 插件侧 `scripts/e2e-arch-smoke.mjs`；iOS 真机链路未建（无 Apple 账号） | **部分**（见 G3） |

---

## 19.2 必须审查的截图替代

### R1 列出所有 staticSnapshot / solidSnapshot / 禁动画 / 样例服务 / 宽屏静态布局差异

代码位置（`staticSnapshot` 及同类分支）：

| 文件:行 | 差异 | 影响的生产路径 |
|---|---|---|
| `App/Demo/WelcomeView.swift:5,10,14` | 静态快照 + `disablesAnimations` | 启动/欢迎动画 |
| `App/Features/Home/NewTaskPage.swift:18,56,78,114,186,200` | `.sheet` 置 nil、`solid` 输入框、早退 | 新任务页附件 sheet、提交 |
| `App/Features/Home/InboxPage.swift:80,91,102,191,195,233,256,677` | 静态行、禁动画、`showsComposer: false` | 首页会话 push、输入框 |
| `App/Features/Pairing/PairingPages.swift:12,71` | 静态配对页 | 配对扫码与批准 |

判定：**已满足**（清单已建立，见上表；本文件即该清单）。

### R2 每个被替代的生产路径至少有一个实际入口验证

- `.sheet`：`NewTaskPage` 的附件 sheet 在 `staticSnapshot` 下被置 nil（`NewTaskPage.swift:56`）→ **生产路径未被截图覆盖**。是否有非截图测试进入该 sheet，见 G1。
- `.inspector`、WKWebView、流式消息、键盘、决策切换：见 G1/G2。

判定：**部分**（存在未验证的生产入口，见 G1）。

### R3 测试必须检查按钮结果/请求载荷/导航目标，不能只断言按钮存在

- 正面例子：`InboxFlowTests.approveSendsAllowedOnceAndDoesNothingOffline` 断言**请求载荷与离线行为**；`deleteWaitsForConfirmationAndStaysDeleted` 断言确认与结果。
- 判定：**部分**（首页/审批已达标；部分快照测试仍只断言渲染结果，但它们定位是"视觉"，可接受）。

### R4 UI 测试使用稳定 accessibilityIdentifier；图标同时有可理解 label

- 判定：**部分**（沙盒内可见的 `accessibilityIdentifier` 覆盖不完整，见 G2）。

### R5 现有 snapshot-matrix 脚本检查的是声明，不能代替 PNG 存在或人工看图

- 现状：`scripts/` 下的矩阵脚本校验声明；PNG 实际存在由 `ios-regen-screenshots.yml` 产出。
- 判定：**已满足**（职责边界清楚，脚本未冒充看图）。

### R6 iOS 基线只用 `ios-regen-screenshots.yml`；不批量用本地截图覆盖

- 判定：**已满足**（`apps/ios/AGENTS.md` 已固化该规则）。

---

## 19.3 辅助功能与设备矩阵

### R7 浅/深、中/英、默认/辅助字号矩阵；再覆盖降低透明度、增强对比度、粗体、减弱动态、VoiceOver

- 现状：快照矩阵已覆盖浅/深 × 中/英 × 字号 + `reduceTransparency` / `increaseContrast`（见 `*SnapshotTests` 参数）。
- **缺失**：粗体文字、减弱动态效果、VoiceOver 无覆盖。
- 判定：**部分**（见 G2）。

### R8 最大辅助字号需要实际操作，不只 accessibility3 的图片

- 判定：**部分**（矩阵用 `accessibility3`，未覆盖 AX5 极值 + 实际操作）。

### R9 设备矩阵：iPhone 13 验收键盘/锁屏/网络；基线模拟器验收材质；灵动岛设备验收 8.5；iPad 验收 NavigationSplitView/inspector/外接键盘

- 判定：**部分**（基线模拟器已用；其余需真机，归阶段 9）。

---

## 差距汇总

| 编号 | 要求 | 判定 | 处置 |
|---|---|---|---|
| **G1** | §19.2 R2：被 `staticSnapshot` 置 nil 的 `.sheet` 生产入口缺验证 | **部分已补**（见下） | 已核对：sheet 的**决策逻辑**其实早有覆盖（`NewTaskTests` 三个用例走 `workspacePickRows`，那是纯函数层）。真正缺的是**动作结果**，已修 |
| **G2** | §19.2 R4 + §19.3 R7：可访问性标识与「减弱动态/粗体/VoiceOver」矩阵 | **部分已补**（见下 G5） | 核对后**收窄了范围**：粗体与减弱动态在实现层已正确，缺的是**守住前提的测试**，已补 |
| **G3** | §19.1 真机 E2E | 部分 | 不实施：需 Apple 账号与真机，属阶段 9；已标未验证 |

| R3 | §19.2 R3：动作必须检查**结果**，不能只断言按钮存在 | **已补**（见下 G4） | 修复 + 测试 |

### G4（本轮）：`submitWorkspace` 静默吞掉错误

`NewTaskPage.submitWorkspace` 旧实现是 `catch {}`：

```swift
do { switch try await createWorkspace(path) { ... } } catch {}
```

路径非法、无权限、电脑离线 —— **全都静默失败**。用户点了「提交」什么也没发生，
既不知道失败也不知道为什么。这正是 §19.2 R3 点名的「只检查可点击/有页面」缺陷：
按钮在、能点，但结果不可验证。

修复：新增 `submitError` 状态，失败时显示可操作文案（"没能添加这个目录。请检查路径与连接。"），
并**保留用户输入**（不清空 `addPath`）以便直接改。与「已提交，等待电脑确认」区分开 ——
两者对用户的下一步动作不同。

测试（`NewTaskWorkspaceErrorTests`，3 项）：
- 失败文案必须指出**检查方向**（path / connection），不能只说「失败了」
- 失败文案与「等待确认」不是同一句话
- 失败文案 `en` / `zh-Hans` 齐备

### G5（本轮）：辅助功能契约测试

核对后发现 G2 的原始判断需要**收窄** —— 方案点名的五项里，实现层其实已经对了：

| 项 | 现状 | 结论 |
|---|---|---|
| 降低透明度 | 各 `*SnapshotTests` 都有 `reduce-transparency` 变体 | **已有矩阵** |
| 增强对比度 | 同上，`increase-contrast` 变体 | **已有矩阵** |
| 粗体文字 | `DLFont` 全部用**语义化系统字**（`Font.headline` / `Font.body`…），不是硬编码 size/weight —— 系统会自动加粗 | **实现正确，缺测试守** |
| 减弱动态效果 | `MessageStreamView` 按 `!chrome.reduceMotion` 决定是否动画；`MessageRowView` 淡入检查 `reduceMotion` | **实现正确，缺测试守** |
| VoiceOver | 纯图标控件都有 `accessibilityLabel`（省略号菜单、扫码关闭）；相机预览 `accessibilityHidden(true)`；装饰性图标位于带文字的按钮内，读屏读文字 | **实现正确，缺测试守** |

所以真正缺的不是「补矩阵」，而是**守住这三条前提**：一旦有人把 `Font.headline`
换成 `Font.system(size: 17)`，标准设置下截图完全正常，但粗体文字与动态字体同时失效 ——
这类回归矩阵看不出来。

新增 `AccessibilityContractTests`（6 项，放在 `Tests/Contract/`）：
1. `DLFont` 不出现 `.system(size:` 且保留语义化 token
2. 消息行不使用固定 size
3. 数据刷新与淡入都检查 `reduceMotion`
4. 关键交互元素有稳定 `accessibilityIdentifier`（`message-stream` / `load-older` / `privacy-cover`）
5. 纯图标按钮带 `accessibilityLabel`
6. 相机预览对读屏隐藏

**已做反向验证**：把 `Font.headline` 换成 `Font.system(size: 17, weight: .semibold)` 后，
第 1 条确实失败（同时命中「出现固定 size」与「缺少语义化 token」两条断言）。

### 仍未补的矩阵项

- **粗体文字 / 减弱动态的截图变体**：按上面的分析，实现已是语义化的，加截图变体收益低；
  且每个新变体都会让 CI 基线重生成更慢、更容易抖（record 模式一次覆盖不全，
  已在本仓实测过）。**未做，理由记录在此**。
- **VoiceOver 实际朗读顺序**：需要真机 + 辅助功能检查器，本地做不了。
- **最大辅助字号（AX5）实际操作**：矩阵用 `accessibility3`；方案明确要求「不只 accessibility3 的图片」，
  属真机范畴。

### 说明
- 本任务**不追求增加测试数量**（方案原话）。G1/G2/G4/G5 只补方案点名缺失的**生产入口**、
  **动作结果**与**守住辅助功能前提**的测试，不重复已有覆盖。
- 所有改动遵循 §19.2 R6：不生成或提交本地截图基线。
