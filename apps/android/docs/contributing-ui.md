# UI 贡献规则（工程门禁）

> 目的：让「设计一致性」和「巨型文件」从**约定**变成**可执行的检查**。
>
> **视觉合同以 `docs/visual-rules.md` 为准**（v4：实底、Material 3、单一品牌色）。
> 页面以仓库根目录 `docs/redesign-v4/design-v4.html` 的页面编号为准。
> 本文件只讲工程门禁怎么把这些合同变成机器检查。

## 1. 设计 token 规则（硬性）

1. **禁止裸字号**：业务代码不得写 `fontSize = N.sp` / `lineHeight = N.sp`。
   - 用语义排版入口 `DshType.*`（`core/DshTypography.kt`），角色面由 `DshTypeScaleTest` 锁死。
   - 存量以 `app/src/test/resources/design-token-baseline.txt` 登记为**每文件上限**。
2. **禁止裸色值**：不得写 `Color(0x...)`。颜色一律走 `Dsh.*`。
   `Color(0x…)` 只允许出现在 `core/DshTheme.kt`。迁移期临时允许 `core/DswPalette.kt`，阶段 4 删除。
   字面量的 RGB 必须在 v4 token 表里（`DshPaletteProvenanceTest`），不再要求溯源到 Dsw。
3. **间距**：`padding` / `spacedBy` / `PaddingValues` / `Spacer` 只用 4 的倍数，范围 4–32。
   由 `DshSpacingUsageTest` 强制。
4. **圆角**：只用 8 / 12 / 16 / 28，外加全圆。由 `DshShapeRoleTest` 强制。
5. **实底**：禁止 `blur(`、`RenderEffect`、`graphicsLayer { renderEffect`、`haze`。由 `DshSurfaceRoleTest` 强制。
6. **迁移白名单**：还没改完的文件在 `V4MigrationAllowlist`。迁完一个模块就删对应条目，不要新增。

由 `DesignTokenUsageTest` 强制裸色值和裸字号：新增违规即让 `testDebugUnitTest` 失败。

## 2. 组件语言门禁（ComponentLanguageTest）

页面文件不得直接使用 M3 的 `Card`、`ElevatedCard`，或 `tonalElevation > 0` 的 `Surface`。
用 `native/ui/v4/` 里的组件。v4 组件本身可以包 M3。

另外这些存量门禁仍然有效，只降不升：

- 页面文件不得使用已删除的 `DshRadius.group`。
- `DshLargeTitle`、`DshGroupedPage`、`HomeChip`、`DeviceTag` 不得回潮。
- `RoundedCornerShape(DshRadius.full)` 只允许出现在共享组件或允许的状态/筛选组件中。
- Settings / Devices 页面不得新增 `CircleShape` 图标底板。

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

CI（`.github/workflows/ci-android.yml`）按顺序跑：固定版本 ktlint → JVM 测试 + lint →
**截图校验** → debug APK → release R8 构建（无签名验证，不等于正式安装包）→ Debug emulator smoke。

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
| 截图基线重生成 | `../../.github/workflows/regen-screenshots.yml` |
| 变更记录 | `../../CHANGELOG.md` |
