# cetus iOS 视觉规则（不可违反）

> 适用：cetus iOS（`apps/ios/`）。页面编号与 `docs/redesign-v4/design-v4.html`、`docs/ios/PLAN.md` 附录 A 一致。
> 与设计稿 PNG（`docs/design/`）冲突时以 PLAN 为准；PNG 只定布局与层级，禁止取色、禁止按像素复刻。

## 0. 优先级（冲突时按此顺序，2026-10-07）

1. `docs/redesign-v4/PLAN.md`（2026-10-07 P01 节）→ 信息架构与**合同数值**
2. `docs/REBRAND_CETUS.md` → 命名与 legacy 白名单
3. `docs/ios/PLAN.md` → iOS 平台做法与执行边界
4. `apps/ios/docs/page-mapping.md` → 页面 / 组件 / 截图名映射
5. **本文件** → 视觉取值（颜色、文字、形状、间距、玻璃、动效、辅助功能）
6. 设计稿 PNG（`docs/design/`）→ **只定布局与层级**，禁止取色、禁止按像素复刻

**本文件只回答「长什么样」。** 信息架构、主动作规则、淡化数值、命名问题一律看上面 1–2 条，不在本文件另立一套。
**先改合同，再改实现。** 视觉取值要改，先改本文件再改代码；**不偷偷改数值**。

## 1. 唯一方向

**Apple HIG + 系统组件 + 单一品牌色。** 内容层实色；功能层（导航栏、工具栏、输入区、决策栏、弹层）由系统提供玻璃。

## 2. 颜色

只用系统语义色 + `AccentColor` + `BrandFill`。状态色：`systemOrange`（等待 / 风险）、`systemGreen`（完成 / 增）、`systemRed`（失败 / 删 / 危险）。禁止写死其他色值。

| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `AccentColor` | `#3F5BD6` | `#8B9DFF` | 文字按钮、图标、选中、tint |
| `BrandFill` | `#3F5BD6` | `#4C66E6`（暂定） | 品牌实心按钮底，白字 |

同屏最多一个品牌实心按钮。首页给「新任务」；列表里的「允许一次」用 `.bordered` + accent tint。

## 3. 文字

只用动态字体文字样式：`.largeTitle` `.title3` `.headline` `.body` `.subheadline` `.footnote` `.caption`。代码、路径、命令、模型 ID 用 `.monospaced()`。禁止写死字号。

**界面显示名一律小写 `cetus`**（不写 `Cetus` / `CETUS` / `cetus links`）。`Cetus` 首字母大写只用于内部工程标识（project / target / scheme / test target）。见 `docs/REBRAND_CETUS.md` §1.1。

## 4. 形状

控件用胶囊；容器用同心圆角（`ConcentricRectangle` / `containerShape`）；行内代码与小标签固定 6pt。不自定义其他圆角。

## 5. 间距

系统默认边距优先；自定义间距只用 4 的倍数（4–32）。触控 ≥ 44pt。

## 6. 玻璃规则

**允许**

- 系统导航栏、工具栏、`.sheet`、`Menu`、`alert`、`confirmationDialog`（自动带玻璃，不额外处理）。
- 自定义玻璃只有两处：输入区（`DLComposerView`）与决策栏（`DLDecisionBar`），共用一个容器。
- 浮在相机画面上的关闭按钮。
- 按钮样式：独立浮在内容上的主操作用 `.glassProminent`（BrandFill），次操作用 `.glass`；**放在玻璃容器内部**（输入区、决策栏）的按钮用 `.borderedProminent`（BrandFill）/ `.bordered` 实色，不叠玻璃。
- 同屏最多一个品牌实心按钮：首页给「新任务」，列表里的「允许一次」用 `.bordered` + accent tint。

**禁止**

- 消息气泡、卡片、列表行、代码块、diff、横幅、状态槽使用玻璃。
- 玻璃里再套玻璃。
- 自己写模糊或半透明背景去模仿玻璃。
- 依赖玻璃的具体透明度。必须在以下设置下都清晰可用：降低透明度、增强对比度、iOS 27 玻璃着色滑杆的两端。

## 7. 主动作与淡化（合同数值）

- **每状态只有一个主要动作**：空闲发送 / 运行中停止 / 待审批「允许一次」/ 待回答「下一题」或「提交」。清空 `/`「本屏没有品牌实心按钮」这类阶段遗留说法。
- **决策栏出现时消息流淡化 ≈42%**：这是 `docs/redesign-v4/PLAN.md` P01.4 登记的**合同数值，不得私自调整**。先做可读性与辅助功能实验；若妨碍审查上下文，改为**决策区层级强化 + 局部弱化**，**且必须先改合同再改实现**。弱化不得覆盖命令块、权限边界与必要上下文。
- **示例数字**：统计 / 余额 / 进度 / 计数 / 百分比只用真实接口；缺失显示中性说明或省略，**不填演示值**；无真实进度不画 0%。Demo fixture 例外，但必须与生产数据源可区分。

## 8. 组件清单

封装组件（`DLUI`）：`DLStatusSlot` `DLInboxRow` `DLComposerView` `DLDecisionBar` `DLChip` `DLProcessLine` `DLCodeBlock` `DLEmptyState` `DLBanner`。

1.x 配对页面直接使用 `PhotosPicker`、`DataScannerViewController`（不支持时 `AVCaptureSession`）、系统 `alert` 与改名 `Form` sheet；扫描器桥接留在 App，不新增 DLUI 组件。

系统组件直接用：`NavigationStack` `NavigationSplitView` `List` `Form` `.sheet` `.inspector` `confirmationDialog` `alert` `Menu` `Picker` `Toggle` `contextMenu` `swipeActions` `searchable`。新增封装组件先改本文件。

2.x 不新增封装。`DLInboxRow` 支持工作区文件夹行（组头计数、缩进会话、只显示一行标题）与待处理置顶行（跨工作区保留工作区名、内联按钮）；`DLBanner` 增加可选图标。筛选、搜索、分组、长按用系统组件。底栏在系统玻璃里，「新任务」用 `.borderedProminent` + BrandFill，列表里的「允许一次」仍是 `.bordered` + accent tint。**首页不放品牌大标题**（品牌只在 1.1 / 1.2 / 关于）。

4.x 消息流留在 App（`UICollectionView` + `UIHostingConfiguration`），不新增 DLUI 类型。复用 `DLProcessLine`、`DLCodeBlock`、`DLChip`。用户气泡用 `secondarySystemFill` + `ConcentricRectangle`，不用玻璃。助手全宽。`DLCodeBlock` 可传入语义色 `AttributedString`，不传时外观与阶段 2 相同。公式和 Mermaid 的锁死 `WKWebView` 只在 App。输入区和决策栏仍是 `DLComposerView` / `DLDecisionBar`，共用一个玻璃容器；决策面板的主动作见第 7 节。

4.5 / 4.8 复用 `DLStatusSlot`，增加可选展开内容与 SF Symbol，不新增组件、不加玻璃。摘要默认一行；展开后标题换行，系统 `ProgressView` 表示已完成计划项比例，清单完成项用次要色且无删除线。最大辅助字号摘要允许换行；有计划时展开区用系统 `ScrollView` 限高，保留消息流空间；无计划只保留真实标题和阶段。待处理只读，不加入决策按钮。

## 9. 动效

系统弹簧动画；决策栏 / 输入区用 `glassEffectID`（或 UIKit 对应）形变。尊重「减弱动态效果」。

## 10. 辅助功能

触控 ≥ 44pt；VoiceOver 标签齐全；降低透明度、增强对比度、粗体文本、最大辅助字号下无截断与重叠。决策面板的 VoiceOver 阅读顺序**先决策再相关上下文**，不能读到「不可用」但仍可点击的操作。

## 11. 截图矩阵

- 设备：iPhone 17 Pro（iOS 26.x）基线。I4.8 宽屏不另开模拟器，仍由这台机器按固定点尺寸渲染：竖屏 768×1024、横屏 1024×768、分屏宽 683 / 512 / 341（高 768）。341 用紧凑宽度，其余用常规宽度。型号与系统版本改动需维护者批准。
- **验收设备边界（P01 冲突 7）**：当前真机 **iPhone 13 没有灵动岛**，只用于验收锁屏 Live Activity（8.4）。**8.5 灵动岛必须另用支持设备或模拟器验收**，不得写「iPhone 13 已通过灵动岛」；验收前 8.5 记为未验收。
- 主页面：浅 / 深 × 中 / 英 × 默认 / 大字号共 8 张。降低透明度与增强对比度按下面的 A2 合同追加，不另开深色、英文或大字号。
- I4.1 其余状态各 1 张（浅色、中文、默认字号）；同名 / 改名各加 1 张英文，完整登记见 page-mapping.md。这些 scene 仍只有原有外观和语言，A2 只追加浅色中文的两张辅助功能变体。
- I4.2 主状态 5 × 8，其余 10 张浅色中文。2.5 / 2.6 的系统菜单和对话框截同文案的静态列表；生产路径仍是 `searchSuggestions`、`contextMenu`、`alert` 和 `confirmationDialog`。浅色中文的 scene 不因此补深色、英文或大字号。
- I4.3a 主状态 `4_1_running`、`4_2_tail`、`4_6_process` 各 8 张；`4_1_unconfirmed`、`4_1_image` 各 1 张浅色中文。截图不挂 `.task`、不连网、不打开 WebView。后两张仍只有浅色中文，A2 只追加两张辅助功能变体。
- I4.3b 状态槽 5 个主状态各 8 张，另外 6 个状态各 1 张浅色中文，共 46 张；scene 名见 page-mapping.md。沿用 `staticSnapshot`，I4.3a fixture 显式不显示状态槽。那 6 个状态不补深色、英文或大字号。
- A2 变体：前 6 类（ChatSheet 5.x、NewTask 3.x、Review 6.x、Settings 7.x、Trajectory 4.7）每个现有 scene 要有浅 / 深 × 中 / 英 × 默认 / 大字号共 8 张，再加浅色中文默认字号的 `reduce-transparency` 与 `increase-contrast`。
- Inbox、Pairing、Chat、Composer、StatusSlot、Wide 只给每个现有 scene 追加这两张浅色中文变体，原有 `default` / `large` 文件名不变。这些类里本来只有浅色中文的 scene 仍然只有浅色中文，不要求补深色、英文或大字号。
- Welcome 例外：保留 `testWelcomeLight.light`，只要求 `Snapshot_1_2_welcome_light_zh.reduce-transparency`、`Snapshot_1_2_welcome_light_zh.increase-contrast`，以及 `Snapshot_1_2_welcome_a11y_light_en.large` 和 `Snapshot_1_2_welcome_a11y_dark_en.large`。不要求 8 张普通的 `Snapshot_1_2_welcome_<appearance>_<language>.<default|large>`。
- Component 每个现有测试保留 `light` / `dark` / `long` / `disabled`，并要求 `en`、`large`、`reduce-transparency`、`increase-contrast`。UIKit 没有公开的降低透明度 trait，`testComposer` 与 `testDecisionBar` 的透明度变体只是文件名覆盖；对比度用 `UITraitCollection(accessibilityContrast: .high)`。
- 可设置的透明度键是 `\._accessibilityReduceTransparency`。两张辅助功能变体都只在浅色中文默认字号，`named` 分别为 `reduce-transparency` 与 `increase-contrast`，`testName` 仍是 `Snapshot_<scene>_light_zh`。颜色同时设 `environment(\.colorScheme)` 与 trait `userInterfaceStyle`；大字号用 `environment(\.dynamicTypeSize, .accessibility3)` 与 `preferredContentSizeCategory: .accessibilityExtraLarge`。
- `apps/ios/scripts/check-snapshot-matrix.mjs` 检查 Swift 声明，不检查 PNG。缺声明时非零退出并列清单。缺的 CI 基线不是这个本地脚本的失败，不要靠创建 PNG 修。
- 基线只由 `ios-regen-screenshots.yml` 生成，只在 CI；禁止提交本地基线。
- Review 与 Wide 使用 `precision: 0.995, perceptualPrecision: 0.99`：玻璃层每次有大量像素差 1–2 个色阶，单靠字节比例会失败；感知比较放过这种色差，缺一行或一列内容仍会失败。

## 12. 禁止清单

自制模糊；玻璃叠玻璃；内容层玻璃；写死字号与色值；从设计稿 PNG 取色或按像素复刻；同屏两个品牌实心按钮；审批做滑动手势；引入统计 / 崩溃 SDK；复制 AGPL / GPL 源码；**界面显示名写成 `Cetus` / `CETUS`；填演示数字冒充真实数据；私自调整 42% 合同数值**。
