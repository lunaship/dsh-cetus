# 视觉规则（不可违反）

面向 DeepLinks Android 客户端（本仓 `deeplinks`）。

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

**2026-09-28 重设计：页面底是灰的，卡片才白（深色反之：底近黑、卡片抬一档）。**
分层靠这一组 tonal 差，不靠描边。

| 语义 | 使用场景 | Token | 浅色 | 深色 |
|---|---|---|---|---|
| Canvas | 页面默认背景 | `bgBase` | `#F5F5F2` | `#121214` |
| Navigation | 侧栏、常驻导航 | `bgSidePanel` / `bgDrawer` | `#F5F5F2` | `#121214` |
| Card | 卡片、面板、分组卡（首页/设置唯一的「白面」） | `bgCard` / `bgSurface` | `#FFFFFF` | `#1C1D21` |
| Tonal container | 用户气泡、胶囊底、选中态 | `bgSubtle` / `bgSelected` / `bgNavSelected` | `#EAEAE5` / `#E7E7E1` | `#2A2B30` / `#26272C` |
| Input | 输入框、搜索框、Composer | `bgInput` | `#F5F5F2` | `#121214` |
| Recessed | 思考轨迹、代码或深层数据区 | `bgRecessed` / `bgCode` | `#F3F3EF` | `#17181B` / `#26272C` |
| Divider | **只用于分隔线**，不做容器描边 | `borderSubtle` | `#EEEEE9` | `#2A2B30` |

对话页整页白（`bgCard` + `bgBase` 同白），首页整页灰（`bgBase` 灰 + `bgCard` 白）。

迁移规则：

- `bgGrouped` 收敛为 `bgBase`；`bgGroupedCard` 收敛为 `bgSubtle`。
- `bgCard` 只表示浮层或需要独立承载的卡片，不再是普通列表行背景。
- `bgSelected` 与 `bgNavSelected` 合并为一个 selection container 角色，token 仍然成对。
  筛选、会话行、分页下划线、菜单勾选和用户气泡都用中性灰：底是 `bgSubtle`，字和图标是 `labelPrimary`。
  进行中写在灰字和图标上。**实心 `brand500` 只留给「需要你动手」的动作**：批准、发送。
  新任务、停止、开关开启态改用**墨色实心**（底 `labelPrimary`、内容 `bgCard`）——
  这是 2026-09-28 重设计相对 2026-09-27 合同的唯一反转，见第六节第 3 条。
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

**2026-09-28 重设计稿的尺寸 → 角色映射**（不新造字号，就近落档）：

| 稿子 | 用途 | 角色 |
|---|---|---|
| 28 / 粗 | 设置页大标题 | `display`（28，设置页专用例外；页面标题默认仍是 `headlineMedium`） |
| 20–22 / 粗 | 首页概况「1 件等你处理」、空状态标题 | `headlineMedium`（20/26）；后半句同尺寸、Medium、`labelSecondary` |
| 16–17 / 粗 | 电脑名、审批卡标题 | `titleLarge`（17/24，配 SemiBold） |
| 15 / Medium | 列表行标题、按钮 | `title`（15/22 Medium） |
| 15 / 常规 | 对话正文、输入文字 | `body`（15/22） |
| 13–14 | 行副标题、说明 | `titleSmall`（13/18）/ `supporting`（14/20） |
| 12 | 分组标签、时间、状态胶囊 | `captionRelaxed`（12/18）/ `captionMedium`（12/18 Medium） |
| 16 / Medium | 悬浮主按钮、设置行标题 | `bodyLarge`（16/26，配 Medium） |
| 12.5 等宽 | 命令、路径、代码 | 现有代码字体角色（`DshCodeSurface.kt`） |

## 五、组件合同（只保留以下共享组件族）

| 类别 | 组件 | 规则 |
|---|---|---|
| 页面 | `DshPageScaffold` | 统一标题、inset、宽度和背景 |
| Section | `DshSectionHeader`、`DshSection`、`DshSectionLabel` | 默认扁平；设置页不用 `tonal`。分区标题只有灰字，不挂计数；`DshSectionLabel` 是 13/Medium/次要色的分组标签 |
| 列表 | `DshListRow`、`DshGroupCard`、`DshCardDivider`、`DshSwitchRow`、`DshSelectRow` | 设置、设备、Sheet、首页分组卡复用同一行骨架；`DshGroupCard` 是 `bgCard` + `composer` 圆角 + 16 内边距的白色分组卡，行间只用 `DshCardDivider` 发丝线 |
| 筛选 | `DshFilterChip` | 工作区、模型、状态筛选统一使用 |
| 状态 | `DshStatusBadge`、`DshStatusChip`、`DshStatusIcon`、`DshBanner` | 等待、运行、成功、错误语义固定；`DshStatusChip` 是收件箱的四种状态胶囊（等你批准 / 等你回答 / 在电脑上处理 / 完成），`DshStatusIcon` 是 32dp 列表行首状态圈 |
| 操作 | `DshPrimaryAction`、`DshPillButton`、`DshFloatingPill`、`DshIconAction` | 一个表面最多一个实心主操作；`DshPillButton` 分 Accent（品牌蓝实心）/ Ink（墨色实心）/ Tonal 三种，视觉 44dp、热区 48dp；`DshFloatingPill` 是页面底部唯一的悬浮主按钮（唯一带阴影的普通按钮） |
| 空态 | `DshEmptyState`、`DshErrorState` | 未配对与空数据用 `DshEmptyState`（标题、说明、文字动作），不挂品牌标志；空会话只留白 |
| 浮层 | `DshSheet`、`DshDialog`、`DshMenu` | 统一 modal 形状、scrim、阴影和关闭按钮 |
| 输入 | `ComposerBar` | 保留专用能力，内部按钮和菜单使用共享原语 |

**密度**：空白留在组与组之间，不在每个元素周围均匀撒。
- 助手消息的复制 / 赞踩 / 时间行只挂在每轮最后一条回复；过程说明靠长按菜单复制。
- 列表的分区标题与行标题对齐同一条左边线；会话行不加行尾 `›`。
- 任务首页：「任务」页面标题 + 当前电脑一行小字；标题、筛选文字、分区标题、会话标题都落在 `DrawerTextStart`，
  选中底色向外多伸出一截。会话时间放在标题行尾，副标题只写项目和状态。
- 新会话：从首页浮起「新任务」底部面板（继续上次的任务 / 工作区胶囊 / 输入卡 / 模型与模式座），对话页不再有草稿起始区；系统分享进来的文本与图片直接落在这个面板里。
- 列表行最小高 **52dp**（2026-09-28 重设计把原先的 48 提到 52，设置行与列表行同一规格；方案 2.3）。
  触控下限仍是 48，52 是行本身的视觉高度。
- `DshListRow` 的图标槽默认 22dp（设置 / 设备行）；收件箱行传 `iconSlot = 32.dp`，
  行首是 32dp 状态圈，分隔线缩进随之变成 16+32+12=60，与设计稿一致。

页面文件不得再新增 `HomeChip`、`DeviceTag` 一类只服务单页、但语义可复用的组件；
确有特殊业务语义时，组件名必须表达业务，而不是视觉形状。

## 六、十条（仍然有效）

1. **色源唯一**：运行时颜色只走 Compose `DshTheme` / `DshColors`（及代理 `Dsh.*`）。XML `values/colors.xml` 与 `values-night/colors.xml` 必须与同一套 DSH 色对齐。**XML 运行时色名一律 `dsh_*`；禁止再引入 `ink_*` 作为第二套色系统。**
2. **DSH 色主轴**：默认取 DSH neutral-bluish 色阶（深色画布 `#151517`）。
   **2026-09-28 重设计起，本端改用自有中性色板**（浅色画布 `#F5F5F2`、深色画布 `#121214`，
   见第二节），`DshTheme.kt` 里对应行逐行标注「偏离 DSH」和原因，由 `DshPaletteProvenanceTest` 守住。
   全 App 唯一高饱和强调色仍是 deepseek 蓝族（`brand500`：`#3F5BD6` / `#7C93FF`）；批准、发送、链接用这一族。
   选中态、用户气泡和进行中状态用浅灰与正文色，不把品牌蓝铺进列表和消息流。
   Material You 动态取色只动表面 / 灰阶文字，**不得**用壁纸色替换品牌 token。
   **发送槽规格（Mic / Send / Stop 同一槽）**：空态语音 = `bgTrack` 圆钮；可发送 / 运行 / 录音 = `brand500` 实心；
   停止 = `labelPrimary` 墨色实心（重设计稿）；禁用 = `brand500` 55%；出错 = `error`。状态只换图标。
3. **墨色只用于「过程控制」，不用于「需要你动手」**：这条取代 2026-09-27 的「禁墨色主轴」。
   - **品牌蓝实心**只给需要用户决策的动作：批准、发送、确认。
   - **墨色实心**（底 `labelPrimary`、内容 `bgCard`）给过程控制：新任务悬浮按钮、停止、开关开启态。
   - 其余动作一律 tonal（底 `bgSubtle`）或文字按钮。
   理由是手机上要一眼分开「主操作」与「打断操作」；深色主题下墨色自动变成浅底深字，对比度由角色保证。
   Splash / Devices / Settings / 聊天必须像同一产品。
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
| **2026-09-28 重设计稿的整套表面 / 文字 / 强调角色** | 见第二节与第六节第 2 条：设计稿用的是本端自有中性色板，与 DSH neutral-bluish 不同族 |
| 浅色 `bgSubtle`（稿 `#F1F1ED` → 本端 `#EAEAE5`） | 稿值与页面底 `#F5F5F2` 只差 1.04:1，过不了 `DshContrastTest` 的 1.05 分层下限，下压一档 |
| `warnContainer` / `warnLabel` | DSH 没有 warning 容器档（只有 `state-success-tertiary` 这类）；「等你批准」胶囊需要暖色容器 + 内容配对 |
| 深色 `onBrand` | 稿子深色强调底 `#7C93FF` 上白字只有 2.8:1，`DshPaletteProvenanceTest` 要求 ≥3:1，改为深色 `#121214` |
| `systemAccent` / `toolsAccent` / `trace*` | DSH 没有对应角色（Android 专有的轨迹语义色） |
| `pureBlack()` 几档 | OLED 纯黑模式是本端独有 |

> 2026-09-27 那批「浅色 tertiary/secondary 下压、brand400 取 deepseek-600、error/warn/success 白底不达 AA」的偏离，
> 已被 2026-09-28 重设计稿的整套取值取代（见上表第一行）。

## 快速对照

2026-09-28 重设计稿落地后的角色取值（改色只看这张表 + `DshTheme.kt`）：

| 角色 | 浅色 | 深色 | 说明 |
| --- | --- | --- | --- |
| `bgBase` 画布 | `#F5F5F2` | `#121214` | 首页 / 设置页面底 |
| `bgCard` 卡片 | `#FFFFFF` | `#1C1D21` | 卡片、面板；对话页整页白 |
| `bgSubtle` tonal | `#EAEAE5` | `#2A2B30` | 用户气泡、胶囊底、tonal 按钮 |
| `bgSelected` / `bgNavSelected` | `#E7E7E1` | `#26272C` | 选中态（中性灰，非品牌蓝） |
| `bgInput` 输入 | `#F5F5F2` | `#121214` | 输入条、搜索框 |
| `bgCode` / `bgRecessed` | `#F3F3EF` | `#26272C` / `#17181B` | 代码块、思考轨迹 |
| `borderSubtle` | `#EEEEE9` | `#2A2B30` | **只用于分隔线** |
| `labelPrimary` | `#16171A` | `#EDEDEF` | 主文字；也是墨色按钮的底 |
| `labelSecondary` | `#5B5F66` | `#A3A7AE` | 次要文字（浅色 5.9:1） |
| `labelTertiary` | `#6E7278` | `#8B8F96` | 箭头、占位、第三级 |
| `brand500` 强调 | `#3F5BD6` | `#7C93FF` | 只给批准 / 发送；`onBrand` 分别取白 / `#121214` |
| `success` 在线点 | `#1F9D55` | `#3BC476` | 在线状态点 |
| `successContainer` / `successContent` | `#E6F4EC` / `#17753F` | `#16301F` / `#5CC38A` | 完成图标圈 |
| `warnContainer` / `warnLabel` | `#FFF1DE` / `#8A4B00` | `#3A2A12` / `#F0B86A` | 等你批准 / 等你回答胶囊 |
| `error` | `#B42318` | `#FF8A7E` | 危险文字（解除配对） |
| `bgOverlay` | `rgba(22,23,26,.42)` | `rgba(0,0,0,.6)` | 底部面板遮罩 |
| `bgTrack` | `#E6E6E1` | `#3A3B41` | 进行中转圈轨道 |

`Dsw` 镜像（`core/DswPalette.kt`）继续按 DSH 原样保留：偏离只发生在 `DshTheme.kt` 的角色映射层，
DSH 升级后重新生成镜像的流程不变。

### 文档没规定、由实现定的取值

重设计方案 2.2 的角色表没有覆盖这几个角色，设计源文件里也没有对应元素。
它们是本端定的值，改色时不要当成 DSH 或设计稿的值：

| 角色 | 浅色 | 深色 | 定的理由 |
| --- | --- | --- | --- |
| `brand400` 链接 / 次强调 | `#3F5BD6` | `#7C93FF` | 设计源只有一族强调蓝，链接与主强调同族，不引入第二个蓝 |
| `bgSelected` / `bgNavSelected` | `#E7E7E1` | `#26272C` | 方案 2.1「选中态用墨色/中性色」；设计源没有选中行底色，取比 `bgSubtle` 深一档的中性灰 |
| `bgRecessed` 深色 | — | `#17181B` | 设计源只有浅色凹进面（`#F3F3EF`）；深色取比卡片（`#1C1D21`）暗、比画布（`#121214`）亮的一档 |
| `errorBg` 浅色 | `error` 同色相 10% 叠加 | — | 沿用改造前的既有写法（`error` 已按设计稿换值） |

另外两处**有意偏离设计稿**（都在 `DshTheme.kt` 行尾写了原因）：

| 角色 | 稿值 | 实际 | 原因 |
| --- | --- | --- | --- |
| 浅色 `bgSubtle` | `#F1F1ED` | `#EAEAE5` | 稿值与页面底 `#F5F5F2` 只差 1.04:1，过不了 `DshContrastTest` 的 1.05 分层下限；不放宽门禁的前提下下压一档 |
| 深色 `onBrand` | `#FFFFFF` | `#121214` | 白字在 `#7C93FF` 上只有 2.8:1，低于 `DshPaletteProvenanceTest` 的 3:1 |

配套阅读：[`contributing-ui.md`](contributing-ui.md)（工程门禁与截图基线工作流）。
