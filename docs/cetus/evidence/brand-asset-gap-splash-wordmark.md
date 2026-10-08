# B10 后续：非字符串品牌面（启动图）缺口清单

发现者：app-rebrand · 发现时间：2026-10-08 17:30
发现方式：把模拟器 locale 切到 `en-US` 后冷启动，**截图实测 splash 阶段**
（只查 `strings.xml` / locale 表 / `aapt2 dump badging` **都发现不了**这条）

> **状态：已修复（Lead 裁决 A 方案）。** 生成脚本 `scripts/generate-brand-wordmark.py`
> 可复现生成 4 个产物；Android 与 iOS 均已冷启动截图验证显示 `cetus`。
> 修复细节与验收证据见文末「修复记录」。

## 为什么 B4 与 B10 都漏了它

`strings.xml` 的 `app_name`、locale 表的 `put(...)`、`application-label` 全是**字符串**。
启动图里的品牌名是**烧进位图/矢量里的文字**，不经过任何资源字符串，所以：

- B4 改了 `app_name` → 启动图不变
- B10 改了 locale 表 → 启动图不变
- 两者都通过各自门禁 → 用户冷启动仍看到 "DeepLinks"

`docs/REBRAND_CETUS.md` 43 行明确把「**启动图**」列入「本轮必须换成 cetus」，
167 行也把 Android 范围写成含「启动/图标」。所以这是**合同内的漏改**，不是可选项。

## 缺口清单（共 4 个文件，2 平台）

### Android

| 文件 | 规格 | 内容 |
|---|---|---|
| `apps/android/app/src/main/res/drawable-nodpi/splash_wordmark.png` | 1024×1024 PNG，17463 B | "DeepLinks" 字标（浅色版） |
| `apps/android/app/src/main/res/drawable-night-nodpi/splash_wordmark.png` | 1024×1024 PNG，17991 B | "DeepLinks" 字标（夜间版） |

引用点（改文件即可，**无需改引用**）：

- `drawable/splash_brand.xml` — API 26–30 启动背景（`<item android:drawable="@drawable/splash_wordmark"/>`，108dp 居中）
- `values-v31/themes.xml` — API 31+ `windowSplashScreenAnimatedIcon`
- `values-night-v31/themes.xml` — 同上，夜间版

**无生成脚本**（全仓 grep 只命中 `.local/` 归档 APK），是手工产物。

### iOS

| 文件 | 规格 | 内容 |
|---|---|---|
| `apps/ios/App/Resources/Assets.xcassets/LaunchBrand.imageset/launch-brand-light.pdf` | 矢量 PDF，6338 B | "DeepLinks" 字标（浅色） |
| `apps/ios/App/Resources/Assets.xcassets/LaunchBrand.imageset/launch-brand-dark.pdf` | 矢量 PDF，6338 B | "DeepLinks" 字标（深色） |

- `Contents.json` 声明 `"preserves-vector-representation": true` → **保持矢量**（不要换成位图）
- 引用点：`App/Info.plist` → `UILaunchScreen` → `UIImageName = LaunchBrand`（声明式，无代码引用）

### 已确认**无需**改动

- `ic_launcher_foreground.png` / `ic_launcher_monochrome.png` / `ic_launcher_background.xml`：
  蓝发角色图形，**不含文字**
- iOS `Assets.xcassets` 全部资产 = 仅上述 2 个 PDF（已全量枚举）
- `ic_stat_dsh.png`：通知小图标，图形无文字

## 尚可讨论（低风险，不建议本轮动）

`Extensions/{Share,NotificationService,LiveActivity}/Info.plist` 的 `CFBundleDisplayName`
分别是 `Share` / `NotificationService` / `LiveActivity`。

- 这些是**扩展**名，且 iOS 分享表单在多数上下文用**宿主 App 名**渲染
- 它们**既不是旧品牌名**（不含 "DeepLinks"），也不违反命名合同
- 故不列为缺口；若将来要统一为 cetus 系命名，属独立决策

## 复现命令（供验收）

```sh
export ANDROID_HOME=/Users/wuyanzu/Library/Android/sdk
ADB=$ANDROID_HOME/platform-tools/adb
# 切 en-US（需 adb root）
$ADB root; $ADB shell "setprop persist.sys.locale en-US"; $ADB shell "stop && start"
sleep 20
$ADB shell am force-stop dev.deeplinks.debug
$ADB shell am start -n dev.deeplinks.debug/dev.deeplinks.devices.SplashActivity
sleep 2   # splash 阶段很短，要抢在 MainActivity 之前截
$ADB exec-out screencap -p > splash.png
```

**证据**：`docs/cetus/evidence/android-home-en-after-locale-fix.png`（splash 显示 "DeepLinks"）

iOS 侧渲染检查：

```sh
sips -s format png --out /tmp/lb.png \
  apps/ios/App/Resources/Assets.xcassets/LaunchBrand.imageset/launch-brand-light.pdf
```

## 方法论建议

**只扫描字符串资源永远发现不了"图里烧了文字"的品牌面。**
B4/B10 的验收都停在字符串层，所以都通过了却都漏了这条。
建议把「渲染/像素层验证」补进品牌类改动的验收清单：

1. 字符串层：`strings.xml`、locale 表、Info.plist
2. 清单层：`aapt2 dump badging`、`PlistBuddy`（← B4 停在这里）
3. **渲染层：模拟器/真机逐屏截图，含启动图与首页**（← 本轮补上）
4. 资产层：全量 raster/vector 资产渲染过一遍，确认无烧入文字

---

# 修复记录（2026-10-08 17:45–17:54）

Lead 裁决：**A 方案**——由 app-rebrand 直接生成字标（无独立美术）。
要求：沿用现有配色 / 尺寸 / 安全区 / 字重观感，字样小写 `cetus`；脚本入 `scripts/` 以便复现。

## 设计取值怎么定的（测量，不是猜）

对**改动前的原始资产**做像素测量：

| 项 | 浅色 | 夜间 |
|---|---|---|
| Android 背景 | `#FFFFFF` | `#313234` |
| Android 字色 | `#2563D8` | `#EBEFF7` |
| 画布 | 1024×1024 **不透明** | 同 |
| cap height | 102 px（D 字形 447..548） | 同 |
| 墨迹垂直位置 | **光学居中**（上 446 / 下 447） | 同 |

**字重判定**：对 5 个候选字体做定量比对（`stem/cap` 与 `W/cap`）：

| 字体 | stem/cap | W/cap | 与原始差异 |
|---|---|---|---|
| **Helvetica Neue Medium** | **0.136** | **5.281** | **Δstem 0.002 / ΔW 0.098 ← 最优** |
| Avenir Next DemiBold | 0.128 | 4.799 | Δstem 0.010 / ΔW 0.384 |
| Helvetica Regular | 0.103 | 4.961 | Δstem 0.034 / ΔW 0.222 |
| Helvetica Neue Bold | 0.178 | 5.528 | Δstem 0.041 / ΔW 0.345 |
| Avenir Next Medium | 0.100 | 4.753 | Δstem 0.037 / ΔW 0.430 |

原始资产：`stem/cap = 0.137`、`W/cap = 5.183`。

> **一个被测量纠正的误判**：我最初从 iOS PDF 的低分辨率渲染图看，以为 iOS 用更重的字重
> （粗看像 Bold）。改用 PDF 的**实际 alpha 通道**测量后，iOS 是 `stem/cap = 0.141`、
> `W/cap = 5.094`，与 Android 的 0.137 / 5.183 基本一致 —— **两平台其实是同一字重**，
> 之前的"更重"是渲染 padding 造成的错觉。因此两平台统一用 Helvetica Neue Medium。

## 产物（路径 / 格式 / 尺寸与原有资产完全一致）

```
apps/android/app/src/main/res/drawable-nodpi/splash_wordmark.png         1024×1024 PNG（不透明）
apps/android/app/src/main/res/drawable-night-nodpi/splash_wordmark.png   1024×1024 PNG
apps/ios/.../LaunchBrand.imageset/launch-brand-light.pdf                 矢量 PDF（字形转轮廓）
apps/ios/.../LaunchBrand.imageset/launch-brand-dark.pdf                  矢量 PDF
```

生成脚本：`scripts/generate-brand-wordmark.py`（Python + Pillow + fontTools）

**iOS PDF 保持矢量契约**：内容流里**没有** `BT`/`Tj`/`TJ` 文本算子，**没有** `/Font` 或
`FontFile`，只有 `m`/`l`/`h`/`f` 路径算子 —— 与原始 PDF 结构一致，
`Contents.json` 的 `preserves-vector-representation` 依然有效。
`MediaBox [0 0 119.0493 36.20354]` 也沿用原值，视觉大小不变。

**可复现性**：重跑脚本 4 个产物 **SHA-256 逐字节一致**（已验证），
即 `--check` 语义可依赖。

**踩到并修掉的一个真 bug**：TrueType 的 `qCurveTo` 可带多个点（隐式 on-curve 点）。
我第一版把它们当直线处理，`u` 字形被画歪。改用 fontTools 的
`decomposeQuadraticSegment` 正确展开后才对。
**验证方式**：把 PDF 渲染出的字形间距与 Pillow 独立渲染同一字体的结果比对 ——
间距 14.5/7.7/17.1/18.8 vs 参考 14/8/18/19，吻合。

## 验收证据

### Android（冷启动截图，en-US）
`docs/cetus/evidence/android-splash-after-wordmark-fix-en.png`
→ splash 中央白圆内显示 **cetus**，蓝色 `#2563D8`，位置/大小与改名前的 "DeepLinks" 一致。

### iOS（模拟器冷启动截图）
`docs/cetus/evidence/ios-launch-frame1.png`
→ 启动屏显示 **cetus**，黑色矢量字标，位置与光学大小不变。

### 门禁
- Android：`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --rerun-tasks`
  → `BUILD SUCCESSFUL`，单元测试 **890 / 0 failures / 0 errors / 0 skipped**（负载 19.34 下通过）
- iOS：`xcodebuild -scheme Cetus -destination 'generic/platform=iOS Simulator' ... build`
  → `** BUILD SUCCEEDED **`；`swift-format lint --strict` → exit 0
- `LaunchBrand` 已编入 `Assets.car`（`assetutil --info` 可见 `LaunchBrand` 1x/3x）

### iOS 资产全量渲染检查（Lead 要求）
`App/` + `Extensions/` 下**全部**资产目录清点完毕：

| 资产 | 类型 | 含文字? |
|---|---|---|
| `LaunchBrand.imageset` | 2 个矢量 PDF | 是 → **已替换为 cetus** |
| `AccentColor.colorset` | 颜色 | 否 |
| `BrandFill.colorset` | 颜色（`#3F5BD6` / dark `#4C66E6`） | 否 |
| `LaunchBackground.colorset` | 颜色 | 否 |

**结论：除已修复的 `LaunchBrand`，iOS 侧不存在其他会烧入品牌文字的图片资产。**
（`LaunchBrand` 是 `App/`+`Extensions/` 下**唯一**的 image asset。）

## 对截图基线的影响（只报告，未本地更新）

启动图变化**不影响**现有快照测试基线：快照测试渲染的是 SwiftUI 视图
（`__Snapshots__/`），不经过 `UILaunchScreen` 的启动图。Android 的
`DesignSystemScreenshotTest` 同理不含 splash。

**未在本地生成或更新任何基线**（遵守 `apps/android/AGENTS.md` 与
`apps/ios/AGENTS.md`：基线只允许由指定 CI workflow 产出）。
