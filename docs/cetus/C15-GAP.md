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
| **G1** | §19.2 R2：被 `staticSnapshot` 置 nil 的 `.sheet` 生产入口缺验证 | 缺失 | **本次实施**（补生产入口测试） |
| **G2** | §19.2 R4 + §19.3 R7：可访问性标识与「减弱动态/粗体/VoiceOver」矩阵缺失 | 缺失 | **本次实施**（补矩阵 + 标识审计测试） |
| **G3** | §19.1 真机 E2E | 部分 | 不实施：需 Apple 账号与真机，属阶段 9；已标未验证 |

### 说明
- 本任务**不追求增加测试数量**（方案原话）。G1/G2 只补方案点名缺失的**生产入口**与**辅助功能矩阵**，不重复已有覆盖。
- 所有改动遵循 §19.2 R6：不生成或提交本地截图基线。
