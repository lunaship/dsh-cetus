# 视觉规则（不可违反）

面向 DSH Links Android 客户端（本仓 `deeplinks`）。

## 唯一方向

> **Material 3 的结构、导航、状态和无障碍规则 + DeepSeek Harness 的色值与节奏 + 面向开发工作的克制高密度。**

这一条是全 App 视觉决策的最终依据。本 App 是 DeepSeek Harness 的客户端，**视觉取值的唯一参照是
DSH Web**（`@deepseek-ai/dsh-client-ui-theme` 的 `--dsw-*` token，见下方「色源」）。
lody-iOS / Paseo / t3code 只能作为**问题样例**（说明某处为什么不像原生），
不得作为新增组件、形状、色值或页面语法的直接来源。需要新视觉元素时，先问它属于下面哪一份合同，
合同没有的，先改合同（连同门禁测试），再改页面。

本文件是设计语言统一改造（2026-09-27 方案）的落地合同，由
`DesignTokenUsageTest` / `ComponentLanguageTest` / `DshShapeRoleTest` / `DshSurfaceRoleTest` /
`DshPaletteProvenanceTest` / `DshSpacingUsageTest` 共同强制；违反即让 `testDebugUnitTest` 失败。

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
- 任务首页：「任务」页面标题 + 当前电脑一行小字；标题、筛选文字、分区标题、会话标题都落在 `DrawerTextStart`，
  选中底色向外多伸出一截。会话时间放在标题行尾，副标题只写项目和状态。
- 新会话：输入框上方一块起始区（标题 + 电脑名，工作区 / 智能体预设竖排），顶栏不重复标题。
- 列表行最小高 48dp（触控下限），不再额外加高。

页面文件不得再新增 `HomeChip`、`DeviceTag` 一类只服务单页、但语义可复用的组件；
确有特殊业务语义时，组件名必须表达业务，而不是视觉形状。

## 六、十条（仍然有效）

1. **色源唯一**：运行时颜色只走 Compose `DshTheme` / `DshColors`（及代理 `Dsh.*`）。XML `values/colors.xml` 与 `values-night/colors.xml` 必须与同一套 DSH 色对齐。**XML 运行时色名一律 `dsh_*`；禁止再引入 `ink_*` 作为第二套色系统。**
2. **DSH 色主轴**：画布和层级取 DSH neutral-bluish 色阶（深色画布 `#151517`）；唯一高饱和强调色钉死 DSH deepseek 蓝（`brand500` / `brand400`）。发送键、链接、实心主按钮用这一族。选中态、用户气泡和进行中状态用浅灰与正文色，不把品牌蓝铺进列表和消息流。Material You 动态取色只动表面 / 灰阶文字，**不得**用壁纸色替换品牌 token。
   **发送槽规格（Mic / Send / Stop 同一槽）**：空态语音 = `bgTrack` 圆钮；可发送 / 运行 / 录音 = `brand500` 实心；禁用 = `brand500` 55%；出错 = `error`。状态只换图标；**禁止**给这个槽上墨黑/反白实心。
3. **禁墨色主轴**：禁止墨黑或反白近白当主按钮色。这是**有意偏离 DSH**（DSH 的 `button-primary-fill` 是墨色）：
   手机上单手操作，蓝色主操作更容易一眼找到（2026-09-27 用户确认）。Splash / Devices / Settings / 聊天必须像同一产品。
4. **文字灰阶**：高对比正文（`labelPrimary`）+ muted 次要（`labelSecondary` / `labelTertiary`）；层级靠灰阶与字重，不靠第二套高饱和色。
5. **ThinkingTrace 规格**：面板背景比画布更深一档（`bgRecessed`）；左侧约 2dp 品牌蓝竖条；CoT 正文 dimmed + italic；折叠标题清晰；最终回答 upright 高对比；助手消息无气泡全宽。
6. **轨迹色降噪**：推理用蓝系弱强调（`traceReasoning`）；禁止亮紫与品牌蓝抢同一层级；审批/警告保留语义色但降噪。
7. **单 CTA**：每个表面最多一个实心 accent 主按钮；其余 ghost / outline / 文字按钮。
8. **Chrome 安静**：Devices / Settings / 侧栏少装饰、少抢戏像素风与第二强调色；状态写进操作本身。
9. **插件不在本仓改**：配对插件、协议、SSE、Relay 在 `../dsh-links`；本仓只改 Android App 视觉与客户端体验。
10. **版本与发布边界**：本视觉迭代不升 `versionName` / `versionCode`；正式签名与推送规则见仓库根 `CLAUDE.md`。

## 七、间距刻度

DSH 没有把间距做成 token；刻度取自 DSH Web 实际写下的 padding / gap 分布：

| Token | 值 | 典型用途 |
|---|---:|---|
| `DshSpace.s2` | 2dp | 图标与文字的微调、细条间隙 |
| `DshSpace.s4` | 4dp | 行内元素之间 |
| `DshSpace.s6` | 6dp | chip 内边距、紧凑行内间距 |
| `DshSpace.s8` | 8dp | 组内元素之间 |
| `DshSpace.s12` | 12dp | 行内边距、卡片内边距 |
| `DshSpace.s16` | 16dp | 页面水平边距（`pageGutter`）、卡片外边距 |
| `DshSpace.s20` | 20dp | 本端补的一档：大卡片内边距 |
| `DshSpace.s24` | 24dp | 组与组之间（`sectionGap`） |
| `DshSpace.s32` | 32dp | 页面级留白、空态上下 |

- `padding` / `spacedBy` / `PaddingValues` / `Spacer` 里只写 `DshSpace.*`。
- 刻度外的存量（10 / 14 / 3 / 5 / 1.5 …）登记在 `spacing-baseline.txt`，只降不升；
  把它们落到相邻一档是逐处的视觉决定，要看截图，不做批量替换。
- 尺寸（图标、触控区、头像）、描边、阴影里的 dp 不归这张表管。

## 色源

**DSH 调色板镜像**：`core/DswPalette.kt`（`object Dsw`）逐字镜像 DSH 的 `--dsw-static-*`
和半透明 `--dsw-alias-*`，勿手改。DSH 升级后重新生成：从已安装的
`@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-theme/lib/client.js`
抽出 `--dsw-*` 声明，按原名转 camelCase（例如 `--dsw-static-neutral-bluish-950` → `Dsw.neutralBluish950`）。

**角色映射**：`DshTheme.kt` 的每个 `DshColors` 角色取自 `Dsw.*`，行尾注释写对应的 DSH alias。
写字面量 `Color(0x…)` 的行必须在同一行写「偏离 DSH」和原因，由 `DshPaletteProvenanceTest` 强制。
新增或调整颜色时，先在 DSH 的 alias 里找同语义的，找不到再偏离。

当前的偏离（完整清单：`grep -n '偏离 DSH' app/src/main/java/dev/deeplinks/core/DshTheme.kt`）：

| 角色 | 原因 |
|---|---|
| 浅色 `labelTertiary` / `labelSecondary` | DSH tertiary 白底 3.7:1 不达 AA；tertiary 下压后 secondary 也下压一档拉开层级 |
| 浅色 `brand400` | DSH `alias-link`（deepseek-500）白底 4.2:1，改用色阶下一档 deepseek-600（仍在 DSH 色板内） |
| 浅色 `error` / `errorBg` / `warnLabel` / `successContent` | DSH 红色过刺，琥珀和绿色族白底不达 AA |
| 深色 `successContent`、`shadowCard` | DSH 没有深底绿字；阴影不分深浅，深色上看不见 |
| `systemAccent` / `toolsAccent` / `trace*` | DSH 没有对应角色（Android 专有的轨迹语义色） |
| `pureBlack()` 几档 | OLED 纯黑模式是本端独有 |
| 主按钮保持实心蓝 | 见第六节第 3 条 |

## 快速对照

| 角色 | Token | DSH 来源 | 深色 | 浅色 |
| --- | --- | --- | --- | --- |
| 画布 | `bgBase` | `alias-bg-base` | `#151517` | `#FFFFFF` |
| 卡片 | `bgCard` | `alias-bg-layer-1` | `#232324` | `#FFFFFF` |
| Tonal 容器 | `bgSubtle` | `alias-interactive-bg-hover-solid` | `#353638` | `#F1F3F5` |
| 选中容器 | `bgNavSelected` | `specific-sidebar-nav-item-active-accent` | `#353638` | `#E4EDFD` |
| 凹进面板 | `bgRecessed` | 色阶最深一档 / `neutral-bluish-60` | `#0F1115` | `#F5F6F7` |
| 主强调 | `brand500` | `alias-brand-primary-new-color` | `#5686FE` | `#4176E6` |
| 链接/次强调 | `brand400` | `alias-link`（浅色偏离，见上） | `#7AAAFF` | `#4868B2` |

配套阅读：[`contributing-ui.md`](contributing-ui.md)（工程门禁与截图基线工作流）。
