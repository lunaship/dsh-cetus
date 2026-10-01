# 视觉规则（不可违反）

面向 DeepLinks Android 客户端（本仓 `deeplinks`）。

## 唯一方向

> **Material 3 的结构、导航、状态和无障碍规则 + DeepSeek Harness 的色值与节奏 + 面向开发工作的克制高密度。**

这一条是全 App 视觉决策的最终依据。本 App 是 DeepSeek Harness 的客户端，**视觉取值的唯一参照是
DSH Web**（`@deepseek-ai/dsh-client-ui-theme` 的 `--dsw-*` token，见下方「色源」）。

**外部参考的使用边界（2026-10-01 修订）**：lody-iOS 的**玻璃分层与操作反馈**（导航只模糊、
输入区圆角浮岛、边缘高光与轻折射）是本次液态玻璃改造明确允许的材质与交互参考；
Paseo / t3code 只能作为**问题样例**（说明某处为什么不像原生）。组件、形状、色值与页面语法
仍以本合同与 DSH 为唯一来源——参考解决「材质怎么做」，不改变「界面长什么样」。
需要新视觉元素时，先问它属于下面哪一份合同，合同没有的，先改合同（连同门禁测试），再改页面。

本文件是设计语言统一改造（2026-09-27 方案）+ UI 统一与液态玻璃改造（**2026-10-01 v2 方案**）的落地合同，
由 `DesignTokenUsageTest` / `ComponentLanguageTest` / `DshShapeRoleTest` / `DshSurfaceRoleTest` /
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
- 页面背景固定 `Dsh.bgBase`；Compact 水平边距 16dp。**聊天页是唯一写明的页面底色例外**（见第二节）。
- Medium/Expanded 内容最大宽度 720dp；聊天工作区与双栏布局不受此限制。
- **页面标题两档（2026-10-01 R2）**：标准页（设置、设备、二级页）`DshType.headlineMedium`
  （20/26 SemiBold）、导航区最小 64dp；聊天顶栏为紧凑档 `DshType.titleLarge`（17/24 SemiBold）、
  单行 56dp，右侧保留分段控件与「⋯」。同一组件提供两档密度，页面不得自定字号与顶栏高度。
- 首页顶栏是「DeepLinks + 连接状态点」品牌单行（R1）：紧凑档 56dp，不显示电脑名与下拉，
  右侧保留搜索与设置。
- `DshLargeTitle` 只是迁移期兼容包装，调用清零后删除；新页面不得使用。
- 页面导航的返回/关闭行为按实际入口传入（设备页：根页面无返回、从设置进入显示返回、
  凭据失效清栈后重进无返回；Sheet 遵循手势关闭），不得猜测返回栈或伪造上一页。

## 二、表面层级（五种用途，不以页面来源命名）

**2026-09-28 重设计：页面底是灰的，卡片才白（深色反之：底近黑、卡片抬一档）。**
分层靠这一组 tonal 差，不靠描边。

| 语义 | 使用场景 | Token | 浅色 | 深色 |
|---|---|---|---|---|
| Canvas | 页面默认背景 | `bgBase` | `#F3F4F7` | `#121214` |
| Navigation | 侧栏、常驻导航 | `bgSidePanel` / `bgDrawer` | `#F3F4F7` | `#121214` |
| Card | 卡片、面板、分组卡（首页/设置唯一的「白面」） | `bgCard` / `bgSurface` | `#FFFFFF` | `#1C1D21` |
| Tonal container | 用户气泡、胶囊底、选中态 | `bgSubtle` / `bgSelected` / `bgNavSelected` | `#EBEDF1` / `#E5E7EC` | `#2A2B30` / `#26272C` |
| Input | 输入框、搜索框、Composer | `bgInput` | `#F3F4F7` | `#121214` |
| Recessed | 思考轨迹、代码或深层数据区 | `bgRecessed` / `bgCode` | `#F2F3F6` | `#17181B` / `#26272C` |
| Divider | **只用于分隔线**，不做容器描边 | `borderSubtle` | `#ECEEF2` | `#2A2B30` |

**页面底色例外（2026-10-01 R4，唯一写明的一条）**：聊天页（含新任务草稿态）整页白——
浅色 `#FFFFFF` / 深 `#1C1D21`（`bgCard`），助手正文直接排在白底上，用户消息用 `bgSubtle`
中性气泡，代码与思考区域用 recessed 表面，三层在白底上可清楚区分。理由：浅色用户气泡
`#EBEDF1` 与冷灰画布 `#F3F4F7` 几乎无差，灰底会把气泡与思考下沉面糊在一起。
首页、设置、设备及其二级页一律冷灰画布（`bgBase` 灰 + `bgCard` 白卡）。

### 2.1 液态玻璃（2026-10-02 Lody 简化修订：Control / Floating 两档 + 边缘渐隐）

| 用途 | 规则 | 典型位置 |
|---|---|---|
| 清晰内容面 | 稳定底色，无玻璃、无折射 | 正文、代码、设置行、长菜单、设备信息 |
| Control 档玻璃 | 小尺寸圆角控件专用：折射（lens 12dp/24dp）+ 边缘高光（1dp，浅 0.55 / 深 0.28）+ 柔和阴影（16dp）+ **按压回弹 1.04 与高光增强、无涟漪**；表面浅 White@0.50 / 深 bgCard@0.55，输入胶囊等承载文字的面用 Strong 档（浅 0.72 / 深 0.74，L11 可读性下限：输入文字对比 ≥ 4.5:1，不达标提浓度） | 悬浮胶囊 / 圆钮（返回、⋯、圆形 +、搜索胶囊）、聊天输入胶囊 |
| Floating 档玻璃 | 圆角浮层：边缘高光、轻折射与柔和阴影 | 改动面板等大浮层（首轮无新增用法） |
| 边缘渐隐（`DshEdgeFade`） | v3：API 31+ 有采样源时**渐进模糊**（max 16dp，蒙版从边缘满强度过渡到 0）+ 画布色 @0.96 → 0 渐变；其余情况只有渐变。替代全宽导航条（**L9：导航条下线**）；只在内容滚到边缘下方时显示（200ms 淡入淡出），滚动态顶部向下伸出 `DshEdgeFadeDefaults.overhang`（48dp）；渐隐不进采样源 | 页面顶 / 底部，控件与内容交界处 |

- **全宽导航条不再存在**：顶部 chrome（标题 / 返回 / ⋯）悬浮在内容之上，内容滚动穿过；
  页面滚动容器用 `LocalDshPageTopInset`（骨架实测高度）做顶部避让。

- 材质与回退实现收敛在共用入口（`DshGlass` / `dshTranslucent`），页面不得自带一套效果参数。
- 折射（lens）是共用开关，默认只在输入区外圈开轻档；导航永远不折射。
  实现约束：Backdrop 1.0.6 的 lens 参数单位为 **px**（在效果作用域内由 dp 换算）、仅 Android 13+、
  形状必须为圆角（`CornerBasedShape`），传 `RectangleShape` 会抛异常（R10）。
- 回退阶梯（4.5.5）：API 33+ 全效 → API 31–32 无折射 → API 26–30 / 无采样源 / 预览 0.96 →
  省电 / 关动画 1.0 不缩放。回退不得留透明空洞或读不清的文字；
  省电与动画设置变化要跟手（`rememberDshReduceTransparency`）。

#### 2.1.1 无模糊回退（v3）

- **回退必须按 shape 绘制**（`shape.createOutline` + `drawOutline`），不得 `drawRect`——
  v3 之前的实现忽略 shape，把胶囊、圆钮、输入胶囊在旧机 / 省电 / 预览里画成了方块。
- Control 档用**拟物回退**（借鉴 ChunUI，MIT）：
  - 填充：左上 → 右下对角渐变，底色（浅 `bgCard` / 深 `bgSubtle`）→ 底色混入黑（浅 6% / 深 10%；ChunUI 原值 10%，浅色宽胶囊上显脏）；
  - 描边：1dp 顶光渐变（浅：白 .5 → 黑 .03 → 黑 .07；深：白 .14 → 透明 → 黑 .24）；
  - 阴影：`dropShadow` 黑 .08（深 .30）、radius 8dp、y 2dp。
- Floating / Navigation 回退只画按 shape 裁剪的实色。参数集中在 `DshGlass.kt`，页面不得调。
- 门禁：`GlassPolishV3Test`。

#### 2.1.2 形状身份（v3）

- 同一控件在状态之间变形时**只动圆角 / 尺寸，不换组件**（不在两个 composable 之间切换），
  例如 + 圆钮与输入胶囊、收起与展开的输入区：换组件会重建输入框、丢焦点与 IME。
- 同一组控件保持同一形状族：悬浮控件一律 `DshControlGlassShape`（全圆），输入胶囊 `composer` 22dp。

#### 2.1.3 动效 token（v3，`DshMotion.kt`）

- 入场揭示 `Modifier.dshReveal(index)`：淡入 + 上移 8dp，`DshEasing.reveal` = cubic(0.22, 0.8, 0.36, 1)、
  `DshDuration.reveal` = 320ms、**无回弹**；列表错峰 `revealStagger` = 70ms，最多累计到第 5 项。
- 只用于「出现」这一刻（建议 chip、空态 / 错误态）；不得挂在长列表条目上，不得做无限循环。
- 按压继续用现有 spring（玻璃控件回弹 1.04 / 卡片 0.97）；reduce-motion 与预览一律直接终态。
- 首轮调参起点（导航 / 浮动）：模糊 16dp / 12dp 起；底色浓度浅 0.84 / 0.88、外圈 0.68 / 0.76 起；
  折射高度 8dp、折射量 12dp 起（6–12 / 8–20 内调）；色散与深度效果关闭；高光、阴影克制。
  参数按角色定义在共用文件中，禁止页面单独调数字。
- 静止状态不做循环浮动、呼吸或折射动画；性能不达标先关折射再降模糊（先减覆盖面、再降强度、最后回退稳定表面）。

迁移规则：

- `bgGrouped` 收敛为 `bgBase`；`bgGroupedCard` 收敛为 `bgSubtle`。
- `bgCard` 只表示浮层或需要独立承载的卡片，不再是普通列表行背景。
- `bgSelected` 与 `bgNavSelected` 合并为一个 selection container 角色，token 仍然成对。
  筛选、会话行、分页下划线、菜单勾选和用户气泡都用中性灰：底是 `bgSubtle`，字和图标是 `labelPrimary`。
  进行中写在灰字和图标上。**实心品牌蓝只留给「需要你动手」的动作**：批准、发送。
  新任务、停止、开关开启态改用 **ink 实心**（底 `inkFill`、内容 `onInk`），见第六节第 3 条。
  禁用一律 `bgSubtle` + `labelDimmed`，不再用品牌色降透明度。
  语义色（警告 / 成功 / 错误）只做 6dp 圆点和文字；容器底保持中性灰或卡片白。不用色条。
  思考轨迹左侧蓝轨保持不变。
- 旧属性在迁移期保留为兼容别名，调用点清零后才删除；由 `DshSurfaceRoleTest` 保证只降不升。

## 三、形状合同（六个用途）

| 角色 | 数值 | 允许场景 |
|---|---:|---|
| `micro` | 2dp | 进度条、小色块 |
| `control` | 8dp | 菜单行、小按钮、缩略图 |
| `container` | 12dp | 普通卡片、状态面板、菜单 |
| `card` | 20dp | 灰底上的白色分组卡（`DshSectionContainer.Card` / `DshCardRows` / `DshGroupCard`，统一走 `dshCardSurface()`：0.6dp 发丝边 浅黑 .04 / 深白 .06 + 浅色一级柔阴影 黑 .07 r8 y3）——只允许共享组件引用 |
| `composer` | 22dp | 聊天输入卡与输入胶囊（少量品牌特征） |
| `modal` | 28dp | Dialog、Bottom sheet |
| `full` | 999dp | Filter chip、状态 pill、圆形按钮、进度轨道 |

- `group` 页面级语义已删除；2026-10-02 以共享组件专用 `card = 20dp` 取代——页面文件出现
  `DshRadius.card` 或显式 `DshSectionContainer.Flat` 即 `ComponentLanguageTest` 失败。
- `xs/sm/md/lg/xl` 为弃用别名，先映射到新角色，再逐文件替换（`DesignTokenUsageTest` 管只降不升）。
- `DshTileShape` 只允许头像和确实需要方形底板的设备图标。
- 普通列表行禁止额外 clip；按压反馈由父级 Section 或 interaction indication 处理。
- Material `Shapes` 必须映射到上述语义半径（`DshTheme.kt`），标准组件不得回落到另一套形状。

## 四、排版合同

| 内容 | 角色 |
|---|---|
| 页面标题 | 标准页 `headlineMedium`（20/26）；聊天顶栏紧凑档 `titleLarge`（17/24） |
| Section 标题 | `titleSmall` |
| 列表主标题 | `listTitle`（16/24 Normal，2026-10-02 Lody 简化：Medium 只留给页面标题） |
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
| 20–22 / 粗 | 空状态标题 | `headlineMedium`（20/26）；后半句同尺寸、Medium、`labelSecondary`。**设置页大标题已并入此档（2026-10-01 删除 28sp 专用例外，与代码现状一致）** |
| 16–17 / 粗 | 电脑名、审批卡标题 | `titleLarge`（17/24，配 SemiBold） |
| 15 / Medium | 列表行标题、按钮 | `title`（15/22 Medium） |
| 15 / 常规 | 输入文字、列表副标题 | `body`（15/22） |
| 稿 15.5 / 26 | 对话页回答正文 | `bodyLarge`（**16/26**）：稿值 15.5 不在 M3 字阶表内，且 26/15.5 = 1.677 超过 `DshTypeScaleTest` 的行高比上限 1.65，因此就近取 16/26；`InlineMarkdownText` 的段落默认样式本来就是它，无需改动 |
| 13–14 | 行副标题、说明 | `titleSmall`（13/18）/ `supporting`（14/20） |
| 12 | 分组标签、时间、状态胶囊 | `captionRelaxed`（12/18）/ `captionMedium`（12/18 Medium） |
| 16 / Medium | 悬浮主按钮、设置行标题 | `bodyLarge`（16/26，配 Medium） |
| 12.5 等宽 | 命令、路径、代码 | 现有代码字体角色（`DshCodeSurface.kt`） |

## 五、组件合同（只保留以下共享组件族）

| 类别 | 组件 | 规则 |
|---|---|---|
| 页面 | `DshPageScaffold` | 统一标题、inset、宽度和背景 |
| Section | `DshSectionHeader`、`DshSection`、`DshSectionLabel`、`DshCardRows` | **页面内容面一律 `container = Card`**（2026-10-02：bgCard + card 20dp，首页分区 / 设置分组 / 设备信息同一承载面）；Flat 留给弹层内部、Tonal 留给警告 / 摘要；分区标题只有灰字，不挂计数；页脚长说明折叠为一行灰字摘要、点按展开 |
| 列表 | `DshListRow`、`DshGroupCard`、`DshCardDivider`、`DshSwitchRow`、`DshSelectRow` | 设置、设备、Sheet、首页分组卡复用同一行骨架；`DshGroupCard` 是 `bgCard` + `card` 20dp 圆角 + 16 内边距；行首图标默认 `accentIcon`（危险行 error）；`dshSwitchColors` 开启轨 = `switchOnTrack`、拇指浅 bgCard / 深 onInk |
| 筛选 | `DshFilterChip` | 工作区、模型、状态筛选统一使用 |
| 状态 | `DshStatusBadge`、`DshStatusChip`、`DshStatusIcon`、`DshBanner` | 等待、运行、成功、错误语义固定；`DshStatusChip` 是收件箱的四种状态胶囊（等你批准 / 等你回答 / 在电脑上处理 / 完成），`DshStatusIcon` 是 32dp 列表行首状态圈 |
| 操作 | `DshPrimaryAction`、`DshPillButton`、`DshIconAction`、`DshGlassCapsule`、`DshGlassCircle` | 一个表面最多一个实心主操作；`DshPillButton` 分 Accent（品牌蓝实心）/ Ink（墨色实心）/ Tonal 三种，视觉 44dp、热区 48dp；**`DshGlassCapsule` / `DshGlassCircle` 是顶栏操作、首页底部操作、聊天返回 / ⋯、输入区「+」的唯一形态**（Control 档玻璃、48dp 热区、按压回弹、无涟漪；`DshFloatingPill` 已删除） |
| 空态 | `DshEmptyState`、`DshErrorState` | 未配对与空数据用 `DshEmptyState`（标题、说明、文字动作），不挂品牌标志；空会话只留白 |
| 浮层 | `DshSheet`、`DshDialog`、`DshMenu` | 统一 modal 形状、scrim、阴影和关闭按钮；浮层阴影 4dp（对话框 16dp 保留） |
| 输入 | `ComposerBar` | 保留专用能力，内部按钮和菜单使用共享原语 |

**密度**：空白留在组与组之间，不在每个元素周围均匀撒。
- 助手消息的轮尾只留一行灰字元信息「[模型名 ·] 时间 · 耗时」（2026-10-02：模型名只认本轮
  切换标记；复制 / 分支 / 赞踩收进行尾 ⋯ 菜单）。对话视图的过程收成**一行活动摘要**
  （`activityLine`：思考时长 → 命令 → 阅读 → 编辑 → 搜索 → 获取，最多 3 类、其余并「+N」），
  点按直达轨迹；轨迹视图组头同一口径（L7：编辑写「编辑 N 次」，文件数只认改动卡）。
- 列表的分区标题与行标题对齐同一条左边线；会话行不加行尾 `›`。
- 任务首页（2026-10-02 Lody 简化）：顶栏左「DeepLinks + 状态点」展示胶囊、右「筛选 + 设置」
  合并胶囊（`DshGlassCapsule`）；底部操作行 = 搜索胶囊 + `accentIcon` 圆形 +
  （黑色 FAB 已删除）；分区内容进白色分组卡；已筛选工作区以列表顶部灰字行 × 清除。
  会话行两层文字：元信息行（6dp 状态点仅执行中脉冲 / 等你批准 + 工作区 · 状态 · 步数，时间右对齐）
  + Normal 标题（最多 2 行）+ 结果一句话；前导状态圈不再使用。
- 新会话（2026-09-30 N1）：点「+ 新任务」直接进对话页**草稿态**，复用对话页输入栏；草稿画布贴底三块
  （继续上次的任务 / 工作区胶囊 / 智能体预设），不放标语和品牌标志；系统分享进来的文本与图片直接落在草稿态输入框。
  旧的「新任务」底部面板已删除。工作区胶囊的「更多」入口常驻行尾、不随横向滚动滚走；同名末级目录在胶囊上
  带父级尾段区分（选择器里仍显示完整路径）。「智能体预设」与输入栏的「访问模式」是两个入口：前者决定智能体
  按哪种方式工作，后者决定它能访问什么（2026-09-30 界面设计审查后统一术语）。
- 列表行最小高 **52dp**（2026-09-28 重设计把原先的 48 提到 52，设置行与列表行同一规格；方案 2.3）。
  触控下限仍是 48，52 是行本身的视觉高度。
- `DshListRow` 的图标槽默认 22dp（设置 / 设备行）；收件箱行传 `iconSlot = 32.dp`，
  行首是 32dp 状态圈，分隔线缩进随之变成 16+32+12=60，与设计稿一致。

页面文件不得再新增 `HomeChip`、`DeviceTag` 一类只服务单页、但语义可复用的组件；
确有特殊业务语义时，组件名必须表达业务，而不是视觉形状。

## 六、十条（仍然有效）

1. **色源唯一**：运行时颜色只走 Compose `DshTheme` / `DshColors`（及代理 `Dsh.*`）。XML `values/colors.xml` 与 `values-night/colors.xml` 必须与同一套 DSH 色对齐。**XML 运行时色名一律 `dsh_*`；禁止再引入 `ink_*` 作为第二套色系统。**
2. **DSH 色主轴**：默认取 DSH neutral-bluish 色阶（深色画布 `#151517`）。
   **2026-09-30 第四轮起，浅色灰阶跟品牌蓝同一色相**（画布 `#F3F4F7`、深色画布仍 `#121214`，
   见第二节）。冷暖统一：灰阶不得偏暖。`DshTheme.kt` 里对应行逐行标注「偏离 DSH」和原因，由 `DshPaletteProvenanceTest` 守住。
   全 App 唯一高饱和强调色仍是品牌蓝（浅 `#3F5BD6` / 深 `#8B9DFF`）；批准、发送、链接用这一族。
   选中态、用户气泡和进行中状态用浅灰与正文色，不把品牌蓝铺进列表和消息流。
   Material You 动态取色只动表面 / 灰阶文字，**不得**用壁纸色替换品牌 token。
   **发送槽规格（Mic / Send / Stop 同一槽，2026-10-02 改 accentIcon 圆底）**：空态语音 = `bgTrack` 圆钮；
   可发送 / 运行 / 录音 = `accentIcon` 实心（= brand400）；停止 = `inkFill` 实心、内部方块 `onInk`；
   禁用 = `bgSubtle` + `labelDimmed`；出错 = `error`。状态只换图标。
   **2026-10-02 L4 强调范围放宽**：线性图标与主入口可用品牌蓝（`accentIcon`：设置分组图标、
   圆形 +、发送钮底；开关开启轨 `switchOnTrack`）；用户气泡改 `userBubble`
   （brand400 10%/14% 叠 bgCard 的预合成色，L5）；不用品牌蓝色块铺底，审批「允许一次」仍为描边。
3. **过程按钮用 ink，不用于「需要你动手」**：这条取代 2026-09-27 的「禁墨色主轴」，并在 2026-09-30 把深色 ink 从白实心改成深灰。
   - **品牌蓝实心**只给需要用户决策的动作：批准、发送、确认。
   - **ink 实心**（底 `inkFill`、内容 `onInk`）给过程控制：停止。浅色 `#15171C` / 白字；深色 `#3A3C43` / `#ECEDF0`。
     （2026-10-02：开关开启态改 `switchOnTrack` 品牌蓝轨；「+ 新任务」悬浮按钮删除。）
   - 禁用一律 `bgSubtle` + `labelDimmed`，不用品牌色降透明度。
   - 其余动作一律 tonal（底 `bgSubtle`）或文字按钮。
   - **语义色只做 6dp 圆点和文字**（警告 / 成功 / 错误）。容器底保持 `bgSubtle` 或 `bgCard`。不用侧边色条，也不再用 `warnContainer` / `successContainer`。
   理由是手机上要一眼分开「主操作」与「打断操作」；深色里批准蓝应是最亮的实心色。
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
| **2026-09-30 第四轮的整套表面 / 文字 / 强调角色** | 见第二节与第六节第 2 条：浅色灰阶跟品牌蓝同色相（冷灰），深色中性灰不动 |
| 浅色 `bgSubtle` `#EBEDF1` | 对画布 `#F3F4F7` 约 1.07，过 `DshContrastTest` 的 1.05 分层下限 |
| `warn` / `warnLabel` | DSH 没有 warning 容器档。语义色只做点和字，不再有 `warnContainer` |
| `inkFill` / `onInk` | 过程按钮。深色不用白实心，避免抢过卡片 |
| 深色 `onBrand` | 白字在深色品牌蓝上对比不足，`DshPaletteProvenanceTest` 要求 ≥3:1，改为 `#121214` |
| 深色 `brand400` / `brand500` `#8B9DFF` | 与浅色品牌蓝同一色相；推理轨 `traceReasoning` 与之同值 |
| `systemAccent` / `toolsAccent` / `trace*` | DSH 没有对应角色（Android 专有的轨迹语义色）；上下文与工具条改中性灰 |
| `pureBlack()` 几档 | OLED 纯黑模式是本端独有 |

> 2026-09-27 那批「浅色 tertiary/secondary 下压、brand400 取 deepseek-600、error/warn/success 白底不达 AA」的偏离，
> 已被 2026-09-28 重设计稿的整套取值取代（见上表第一行）。

## 八、尺寸 token

视觉尺寸统一走共享 token，不得在页面文件里新造裸 dp。

| 类别 | Token | 值 | 用途 |
|---|---:|---|
| 图标 | `DshIconSize.xs` | 12dp | 辅助说明、徽标、紧凑行 |
| 图标 | `DshIconSize.sm` | 16dp | 标准图标（列表、菜单、按钮） |
| 图标 | `DshIconSize.md` | 20dp | 中等图标（概览卡片、状态标识） |
| 图标 | `DshIconSize.lg` | 24dp | 大图标（顶栏、空态、Featured 区块） |
| 热区 | `DshTouch.min` | 48dp | 按钮、FAB、Chip、菜单项 |
| 热区 | `DshTouch.compact` | 40dp | 紧凑列表里可接受的下限（必须配合 `minInteractiveTouchTargetSize` 或 padding 补足） |
| 行高 | `DshRowHeight.compact` | 40dp | 侧栏筛选、上下文菜单 |
| 行高 | `DshRowHeight.default` | 48dp | 会话列表、工作区列表 |
| 行高 | `DshRowHeight.expanded` | 56dp | 带操作按钮的列表项 |

- 行高只控制内容区高度；触控热区另由 [DshTouch] 控制。
- 裸 dp 数量记录在 `size-baseline.txt`，由 `DshSizeUsageTest` 监控只降不升；存量历史债务留在 baseline 里，新增必须同步更新。
- `0.dp` 可裸用。

## 快速对照

2026-09-30 第四轮落地后的角色取值（改色只看这张表 + `DshTheme.kt`）：

| 角色 | 浅色 | 深色 | 说明 |
| --- | --- | --- | --- |
| `bgBase` 画布 | `#F3F4F7` | `#121214` | 首页 / 设置页面底（浅色冷灰） |
| `bgCard` 卡片 | `#FFFFFF` | `#1C1D21` | 卡片、面板；对话页整页白 |
| `bgSubtle` tonal | `#EBEDF1` | `#2A2B30` | 用户气泡、胶囊底、tonal 按钮、禁用底 |
| `bgSelected` / `bgNavSelected` | `#E5E7EC` | `#26272C` | 选中态（中性灰，非品牌蓝） |
| `bgInput` 输入 | `#F3F4F7` | `#121214` | 输入条、搜索框 |
| `bgCode` / `bgRecessed` | `#F2F3F6` | `#26272C` / `#17181B` | 代码块、思考轨迹 |
| `borderSubtle` | `#ECEEF2` | `#2A2B30` | **只用于分隔线** |
| `labelPrimary` | `#15171C` | `#EDEDEF` | 主文字 |
| `labelSecondary` | `#5A5F69` | `#A3A7AE` | 次要文字 |
| `labelTertiary` | `#686D77` | `#8B8F96` | 箭头、占位、第三级 |
| `labelDimmed` | `#B3B7BF` | `#4A4D53` | 禁用文字 |
| `inkFill` / `onInk` | `#15171C` / `#FFFFFF` | `#3A3C43` / `#ECEDF0` | 新任务、停止 |
| `brand500` 强调 | `#3F5BD6` | `#8B9DFF` | 只给批准 / 发送；`onBrand` 分别取白 / `#121214` |
| `success` 在线点 | `#1F9D55` | `#3BC476` | 在线状态点 |
| `successContent` | `#17753F` | `#5CC38A` | 完成图标与文字（无色底容器） |
| `warn` / `warnLabel` | `#C4801A` / `#8A4B00` | amber500 / `#F0B86A` | 6dp 点与文字，容器底中性 |
| `error` | `#B42318` | `#FF8A7E` | 危险文字（解除配对） |
| `bgOverlay` | `rgba(21,23,28,.25)` | `rgba(0,0,0,.45)` | 底部面板遮罩 |
| 按压反馈 | 单层中性 6% | 单层中性 8% | 水波纹 / 按压底色都用 `labelPrimary`，不用品牌蓝 |
| `bgTrack` | `#E4E6EB` | `#2E3036` | 进行中转圈轨道 |

`Dsw` 镜像（`core/DswPalette.kt`）继续按 DSH 原样保留：偏离只发生在 `DshTheme.kt` 的角色映射层，
DSH 升级后重新生成镜像的流程不变。

### 文档没规定、由实现定的取值

重设计方案 2.2 的角色表没有覆盖这几个角色，设计源文件里也没有对应元素。
它们是本端定的值，改色时不要当成 DSH 或设计稿的值：

| 角色 | 浅色 | 深色 | 定的理由 |
| --- | --- | --- | --- |
| `brand400` 链接 / 次强调 | `#3F5BD6` | `#8B9DFF` | 设计源只有一族强调蓝，链接与主强调同族，不引入第二个蓝 |
| `bgSelected` / `bgNavSelected` | `#E5E7EC` | `#26272C` | 方案 2.1「选中态用墨色/中性色」；取比 `bgSubtle` 深一档的中性灰 |
| `bgRecessed` 深色 | — | `#17181B` | 深色取比卡片（`#1C1D21`）暗、比画布（`#121214`）亮的一档 |
| `errorBg` 浅色 | `error` 同色相 10% 叠加 | — | 沿用改造前的既有写法（`error` 已按设计稿换值） |

另外两处**有意偏离设计稿**（都在 `DshTheme.kt` 行尾写了原因）：

| 角色 | 稿值 | 实际 | 原因 |
| --- | --- | --- | --- |
| 浅色 `bgSubtle` | 稿 `#F1F1ED` | `#EBEDF1` | 冷灰画布上仍要过 `DshContrastTest` 的 1.05 分层下限 |
| 深色 `onBrand` | `#FFFFFF` | `#121214` | 白字在深色品牌蓝上低于 `DshPaletteProvenanceTest` 的 3:1 |
| 深色过程按钮 | 白实心 | `inkFill` `#3A3C43` | 2026-09-30：深色最亮实心留给批准蓝 |

配套阅读：[`contributing-ui.md`](contributing-ui.md)（工程门禁与截图基线工作流）。
