# UI 贡献规则（对照 t3code 的工程门禁）

> 目的：让「设计一致性」和「巨型文件」从**约定**变成**可执行的检查**。
> 背景见 `docs/t3code-mobile-comparison.md`；执行进度见 `docs/t3code-improvement-execution-report.md`。
>
> **视觉合同以 `docs/visual-rules.md` 为准**（唯一方向、页面骨架、表面、形状、排版、组件族）。
> 本文件只讲工程门禁怎么把这些合同变成机器检查。

## 1. 设计 token 规则（硬性）

1. **禁止裸字号**：业务代码不得写 `fontSize = N.sp` / `lineHeight = N.sp`。
   - 用语义排版入口 `DshType.*`（`core/DshTypography.kt`），角色面由 `DshTypeScaleTest` 锁死。
   - `DshTheme` 已把 `LocalTextStyle` 设为 `bodyMedium`（15/22），因此
     `fontSize = 15.sp, lineHeight = 22.sp` 是**冗余**的，直接删掉即可。
   - 存量以 `app/src/test/resources/design-token-baseline.txt` 登记为**每文件上限**。
2. **禁止裸色值**：不得写 `Color(0x...)`。颜色一律走 `Dsh.*`（`core/DshTheme.kt`）；
   语法高亮走 `DshSyntaxPalette`。唯一允许字面量的文件是 token 定义文件
   （`DshTheme.kt` / `DshTypography.kt` / `DshSyntaxPalette.kt` / `DswPalette.kt`）。
3. **色源是 DSH**：`DshTheme.kt` 的颜色取自 `Dsw.*`（`core/DswPalette.kt`，DSH 调色板镜像）；
   仍写字面量的行必须带「偏离 DSH：原因」，由 `DshPaletteProvenanceTest` 强制。
4. **间距走刻度**：`padding` / `spacedBy` / `PaddingValues` / `Spacer` 里写 `DshSpace.s2…s32`；
   刻度外存量按 `app/src/test/resources/spacing-baseline.txt` 每文件预算只降不升，由 `DshSpacingUsageTest` 强制。
5. **只允许下调**：以上预算文件只允许把数字改小。若因结构性改动必须一次性上调，
   必须在预算文件里写明原因与下调计划（参见 `SettingsActivity.kt` 的导航迁移）。

由 `DesignTokenUsageTest` 强制：新增违规即让 `testDebugUnitTest` 失败。

## 2. 组件语言门禁（ComponentLanguageTest）

防止「用了 token」但不「用对 token」，也防止页面级重复实现重新长回来：

- 页面文件（`native/ui/` 共享组件库之外）不得直接使用 `DshRadius.group` 等已废弃的
  页面级形状语义；存量按明确预算只降不升，迁移完成后归零。
- `DshLargeTitle`、`DshGroupedPage` 等迁移期兼容包装的调用数量只能下降。
- `HomeChip`、`DeviceTag` 一类只服务单页但语义可复用的组件有清零预算；新页面不得新增。
- `RoundedCornerShape(DshRadius.full)`（pill）只允许出现在共享组件或允许的状态/筛选组件中。
- Settings / Devices 页面不得新增 `CircleShape` 图标底板。
- 一级页面必须使用 `DshPageScaffold`（迁移期按预算收敛）。

新增页面级视觉组件前，先读 `docs/visual-rules.md` 第五节；确有特殊业务语义时，
组件名必须表达业务，而不是视觉形状。

## 3. 文件体积规则

- `app/src/test/resources/code-hygiene-baseline.txt` 登记超大文件的行数预算
  （`WorkspaceActivity` / `DshIcons` / `DevicesActivity` / `SettingsActivity` / `AppLocale`）；
  未登记文件默认上限 1500 行。
- 拆解时**必须把代码移到新文件**才有效——同文件内抽函数不改善该指标。
- 由 `CodeHygieneTest` 强制。

## 4. 门禁命令

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug validateDebugScreenshotTest
```

CI（`.github/workflows/ci.yml`）按顺序跑：DLR 向量检查 → JVM 测试 + lint → **截图校验** → debug APK → 空白检查。

## 5. 截图基线工作流（AGP Compose Preview Screenshot Testing）

- 预览在 `app/src/screenshotTest/kotlin/dev/deeplinks/screenshot/`（`@PreviewTest`）：
  `DesignSystemScreenshotTest.kt`（组件 / token / 设置 / 外壳）、`WorkspaceChangesScreenshotTest.kt`（改动卡与审查面）、
  `ChatFeedScreenshotTest.kt`（一轮回复、流式中、审批卡、提问卡）。
- 数学公式 / Mermaid 走 WebView 异步渲染，不进截图；`L.*`（AppLocale 全局）不跟 `LocalDshStrings` 切换，
  英文预览里个别标签仍是中文属正常。
- **改动 UI 后**：基准图必须在 Linux 上生成，字体才和 CI 一致。不要提交 macOS 上 `updateDebugScreenshotTest` 写出的 PNG。
  流程：把分支推到 GitHub → 手动跑 `Regenerate screenshot baselines`（`.github/workflows/regen-screenshots.yml`，只响应 `workflow_dispatch`）→ 下载 `screenshot-references` 产物 → 覆盖 `app/src/screenshotTestDebug/reference/` → 审图后随 PR 提交。
- 基准图目录：`app/src/screenshotTestDebug/reference/**`（务必提交，否则 CI 会失败）。
- 覆盖矩阵：亮/暗 × 中/英 × 1.0/1.3 字号、412dp 宽。新增屏幕时补一个 `@PreviewTest`。
- 预览刻意不经 `DshTheme`（避开 SharedPreferences 初始化），直接提供
  `LocalDshColors` / `LocalDshStrings`，保证宿主端渲染稳定。
- **禁止在没有人工审图的情况下，仅因 `updateDebugScreenshotTest` 成功就接受变化**：
  先更新，再逐张人工检查 `app/src/screenshotTestDebug/reference` 下的差异，最后
  `validateDebugScreenshotTest`。

## 6. PR 证据

- 任何**可见 UI 变更**：附前后对比图。
- 涉及**动效/时序/交互**：附短视频。
- 证据上传到 PR，不要提交到仓库。
- PR 尽量小、单一关注点；视觉重构按批次拆分提交，每批只含该批文件。

## 7. 参考

| 主题 | 文件 |
|---|---|
| 视觉合同（页面/表面/形状/排版/组件） | `docs/visual-rules.md` |
| t3code 对照与差距 | `docs/t3code-mobile-comparison.md` |
| 改造方案 | `docs/t3code-mobile-improvement-plan.md` |
| 执行进度 | `docs/t3code-improvement-execution-report.md` |
| 能力对照基线 | `docs/ui-parity.md` |
