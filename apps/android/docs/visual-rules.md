# 视觉规则（不可违反）

面向 DSH Links Android 客户端（本仓 `deeplinks`）。

## 唯一方向

> **Material 3 的结构、导航、状态和无障碍规则 + DSH Blue 品牌色 + 面向开发工作的克制高密度。**

这一条是全 App 视觉决策的最终依据。任何「对照某个外部产品」的逐项对齐表述均已作废：
DeepSeek Web / lody-iOS / Paseo / t3code 只能作为**问题样例**（说明某处为什么不像原生），
不得作为新增组件、形状或页面语法的直接来源。需要新视觉元素时，先问它属于下面哪一份合同，
合同没有的，先改合同（连同门禁测试），再改页面。

本文件是设计语言统一改造（2026-09-27 方案）的落地合同，由
`DesignTokenUsageTest` / `ComponentLanguageTest` / `DshShapeRoleTest` / `DshSurfaceRoleTest`
共同强制；违反即让 `testDebugUnitTest` 失败。

## 一、页面骨架（DshPageScaffold）

所有一级页面统一为以下结构，页面不得自行决定新的标题、边距和背景：

```text
系统栏
└── 页面导航区
    ├── 返回/设备上下文（可选）
    ├── 页面标题
    └── 搜索/设置等页面动作（可选）
└── 页面内容
    ├── 筛选或上下文（可选）
    ├── Section
    │   ├── Section header
    │   └── 扁平行 / tonal summary
    └── 页面主操作（可选且最多一个实心 CTA）
```

- 一级页面必须使用 `DshPageScaffold`（`native/ui/DshPageScaffold.kt`）：统一标题、
  inset、返回按钮热区与页面背景。
- 页面背景固定 `Dsh.bgBase`；Compact 水平边距 16dp。
- Medium/Expanded 内容最大宽度 720dp；聊天工作区与双栏布局不受此限制。
- 页面标题统一 `DshType.headlineMedium`（`pageTitle` 角色），全 App 一致。
- `DshLargeTitle` 只是迁移期兼容包装，调用清零后删除；新页面不得使用。

## 二、表面层级（五种用途，不以页面来源命名）

| 语义 | 使用场景 | Token |
|---|---|---|
| Canvas | 页面默认背景 | `bgBase` |
| Navigation | 侧栏、常驻导航 | `bgSidePanel` / `bgDrawer` |
| Tonal container | 设置分组摘要、设备摘要、状态面板 | `bgSubtle` |
| Input | 输入框、搜索框、Composer | `bgInput` |
| Recessed | 思考轨迹、代码或深层数据区 | `bgRecessed` / `bgCode` |

迁移规则：

- `bgGrouped` 收敛为 `bgBase`；`bgGroupedCard` 收敛为 `bgSubtle`。
- `bgCard` 只表示浮层或需要独立承载的卡片，不再是普通列表行背景。
- `bgSelected` 与 `bgNavSelected` 合并为一个 selection container 角色，token 仍然成对。
  筛选、会话行、分页下划线、菜单勾选和用户气泡都用中性灰：底是 `bgSubtle`，字和图标是 `labelPrimary`。
  进行中写在灰字和图标上。实心 `brand500` 只留给发送、停止、确认、归档，以及列表里唯一的添加按钮。
  思考轨迹左侧蓝轨保持不变。
- 旧属性在迁移期保留为兼容别名，调用点清零后才删除；由 `DshSurfaceRoleTest` 保证只降不升。

## 三、形状合同（六个用途）

| 角色 | 数值 | 允许场景 |
|---|---:|---|
| `micro` | 2dp | 进度条、小色块 |
| `control` | 8dp | 菜单行、小按钮、缩略图 |
| `container` | 12dp | 普通卡片、状态面板、菜单 |
| `composer` | 22dp | 聊天输入卡与任务入口（少量品牌特征） |
| `modal` | 28dp | Dialog、Bottom sheet |
| `full` | 999dp | Filter chip、状态 pill、圆形按钮、进度轨道 |

- `group = 20dp` 的页面级语义已删除；页面文件不得直接使用。
- `xs/sm/md/lg/xl` 为弃用别名，先映射到新角色，再逐文件替换（`DesignTokenUsageTest` 管只降不升）。
- `DshTileShape` 只允许头像和确实需要方形底板的设备图标。
- 普通列表行禁止额外 clip；按压反馈由父级 Section 或 interaction indication 处理。
- Material `Shapes` 必须映射到上述语义半径（`DshTheme.kt`），标准组件不得回落到另一套形状。

## 四、排版合同

| 内容 | 角色 |
|---|---|
| 页面标题 | `headlineMedium`，全 App 一致 |
| Section 标题 | `titleSmall` |
| 列表主标题 | `bodyLarge` |
| 列表辅助文字 | `supporting` |
| 工作台正文 | `body` |
| 计数、时间、状态 | `captionRelaxed` 或 `label` |
| 仅工具工作台可用的微标签 | `microRelaxed` / `microMedium` |

- `display` / `displayLarge` 不再用于普通应用页面标题。
- `micro*` 不得出现在设置、设备、任务首页的主信息上。
- 页面不得通过 `copy(fontSize = ...)` 制造新字号；角色面由 `DshTypeScaleTest` 锁死。

## 五、组件合同（只保留以下共享组件族）

| 类别 | 组件 | 规则 |
|---|---|---|
| 页面 | `DshPageScaffold` | 统一标题、inset、宽度和背景 |
| Section | `DshSectionHeader`、`DshSection` | 默认扁平；设置页不用 `tonal`。分区标题只有灰字，不挂计数 |
| 列表 | `DshListRow`、`DshSwitchRow`、`DshSelectRow` | 设置、设备、Sheet 复用同一行骨架 |
| 筛选 | `DshFilterChip` | 工作区、模型、状态筛选统一使用 |
| 状态 | `DshStatusBadge`、`DshBanner` | 等待、运行、成功、错误语义固定 |
| 操作 | `DshPrimaryAction`、`DshIconAction` | 一个表面最多一个实心主操作 |
| 空态 | `DshEmptyState`、`DshErrorState` | 未配对与空数据用 `DshEmptyState`（标题、说明、文字动作），不挂品牌标志；空会话只留白 |
| 浮层 | `DshSheet`、`DshDialog`、`DshMenu` | 统一 modal 形状、scrim、阴影和关闭按钮 |
| 输入 | `ComposerBar` | 保留专用能力，内部按钮和菜单使用共享原语 |

**密度**：空白留在组与组之间，不在每个元素周围均匀撒。
- 助手消息的复制 / 赞踩 / 时间行只挂在每轮最后一条回复；过程说明靠长按菜单复制。
- 列表的分区标题与行标题对齐同一条左边线；会话行不加行尾 `›`。
- 列表行最小高 48dp（触控下限），不再额外加高。

页面文件不得再新增 `HomeChip`、`DeviceTag` 一类只服务单页、但语义可复用的组件；
确有特殊业务语义时，组件名必须表达业务，而不是视觉形状。

## 六、十条（仍然有效）

1. **色源唯一**：运行时颜色只走 Compose `DshTheme` / `DshColors`（及代理 `Dsh.*`）。XML `values/colors.xml` 与 `values-night/colors.xml` 必须与同一套 DSH 色对齐。**XML 运行时色名一律 `dsh_*`；禁止再引入 `ink_*` 作为第二套色系统。**
2. **DeepSeek 色主轴**：深色画布近黑（`#0E0E10` 族）；唯一高饱和强调色钉死 DeepSeek Blue（`brand500` / `brand400`）。发送键、链接、实心主按钮用这一族。选中态、用户气泡和进行中状态用浅灰与正文色，不把品牌蓝铺进列表和消息流。Material You 动态取色只动表面 / 灰阶文字，**不得**用壁纸色替换品牌 token。
   **发送槽规格（Mic / Send / Stop 同一槽）**：空态语音 = `bgTrack` 圆钮；可发送 / 运行 / 录音 = `brand500` 实心；禁用 = `brand500` 55%；出错 = `error`。状态只换图标；**禁止**给这个槽上墨黑/反白实心。
3. **禁墨色主轴**：禁止墨黑或反白近白当主按钮色。Splash / Devices / Settings / 聊天必须像同一产品。
4. **文字灰阶**：高对比正文（`labelPrimary`）+ muted 次要（`labelSecondary` / `labelTertiary`）；层级靠灰阶与字重，不靠第二套高饱和色。
5. **ThinkingTrace 规格**：面板背景比画布更深一档（`bgRecessed`）；左侧约 2dp DeepSeek Blue 竖条；CoT 正文 dimmed + italic；折叠标题清晰；最终回答 upright 高对比；助手消息无气泡全宽。
6. **轨迹色降噪**：推理用蓝系弱强调（`traceReasoning`）；禁止亮紫与品牌蓝抢同一层级；审批/警告保留语义色但降噪。
7. **单 CTA**：每个表面最多一个实心 accent 主按钮；其余 ghost / outline / 文字按钮。
8. **Chrome 安静**：Devices / Settings / 侧栏少装饰、少抢戏像素风与第二强调色；状态写进操作本身。
9. **插件不在本仓改**：配对插件、协议、SSE、Relay 在 `../dsh-links`；本仓只改 Android App 视觉与客户端体验。
10. **版本与发布边界**：本视觉迭代不升 `versionName` / `versionCode`；正式签名与推送规则见仓库根 `CLAUDE.md`。

## 快速对照

| 角色 | Token | 深色示例 | 浅色示例 |
| --- | --- | --- | --- |
| 画布 | `bgBase` | `#0E0E10` | `#FFFFFF` |
| Tonal 容器 | `bgSubtle` | `#2A2A2F` | `#F1F3F5` |
| 选中容器 | `bgNavSelected` | `#1A2744` | `#E5EDFF` |
| 凹进面板 | `bgRecessed` | `#08080A` | `#F3F4F6` |
| 主强调 | `brand500` | `#4D6BFE` | `#4D6BFE` |
| 链接/次强调 | `brand400` | `#6B86FE` | `#3B5BDB` |

配套阅读：[`contributing-ui.md`](contributing-ui.md)（工程门禁与截图基线工作流）。
