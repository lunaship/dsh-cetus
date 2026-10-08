# C16 差距盘点：发布口径、版本与长期运行记录

> 方案来源：`Cetus-整体改造方案-2026-10-07.md` 第 526 行起（§20 C16）。
> 代码基线：`cetus/main`（写作时 HEAD）。
> 逐条：**要求 → 代码位置 → 实际行为 → 判定**。

---

## R1 对外能力表按「支持 / 实验性 / 未验证 / 未发布」标记；iOS 有源码不等于已 TestFlight/上架

- 代码：`docs/COMPATIBILITY.md:21-24`（Android / iOS / Relay / push 四行）、`:69,91,102,110,119`（五个 `## Capability:` 小节）
- 实际：iOS 行明写「未发布 / 阶段 9 前不宣称真 APNs 已验证。没有 TestFlight 构建，没有 App Store 记录」；push 行同样标未发布。
- 判定：**已满足**。

## R2 插件、Android、iOS、Relay、push 各自记录版本与提交；兼容组合写进 `COMPATIBILITY.md`

- 代码：`docs/COMPATIBILITY.md:38-40`（版本表：Contract version / Marketing version / Build number）；`:21-24` 各平台行带 tag、commit、SHA-256
- 实际：五者均有记录，且**明确声明两平台不共享版本号**（`:28`）。
- 判定：**已满足**。

## R3 诊断导出包含构建、路由阶段、能力和错误摘要；不带 token、二维码凭据、API key、正文或完整敏感路径

- 插件侧：`src/diagnostics.js:361-368`（`PRIVATE_TEXT` + `assertReportPrivate`）、`:129`（在 `runDiagnostics` 末尾强制调用）
  - 正则覆盖：`/`、`\`（路径）、`token`、`secret`、`password`、`bearer`、IPv4
  - 我做了**负向验证**：正常 source 通过（8 checks）；一旦报告里出现绝对路径或 token 字串即抛 `diagnostics report failed privacy check`。
- iOS 侧：`apps/ios/App/Features/Settings/SettingsPages.swift:579-589`（`diagnosticsClipboard` 只输出 `id/status/code/detail`）
- **差距**：iOS 侧**没有任何测试**断言该导出不含敏感串（`apps/ios/Tests/` 无 Diagnostics 测试文件）。
- 判定：**部分**（见 G2）。

## R4 Android 保存正式签名校验与 mapping.txt；正式 APK 覆盖安装保留数据，不能用卸载重装宣称「升级通过」

- 代码：`docs/COMPATIBILITY.md:21`（记录签名 APK 与 SHA-256）、`RELEASING.md`
- 判定：**已满足**（流程与证据要求已写明；本轮无 Android 改动）。

## R5 iOS App 与三个扩展版本/构建号一致；Debug/Release bundle 配置分开，当前 `project.yml` 固定 debug ID 不能直接当生产配置

- **版本一致性**：`apps/ios/project.yml:29-30` 把 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` 放在 `settings.base`，对全部 target 生效。**实测**（模拟器已安装构建）：

  | 产物 | CFBundleShortVersionString | CFBundleVersion |
  |---|---|---|
  | `Cetus.app` | 1.0 | 1 |
  | `NotificationService.appex` | 1.0 | 1 |
  | `LiveActivity.appex` | 1.0 | 1 |
  | `Share.appex` | 1.0 | 1 |

  → **一致，已满足。**
- **Debug/Release 分离**：`project.yml:87,108,123,140,168,187` 六处 `PRODUCT_BUNDLE_IDENTIFIER` **全部硬编码 `.debug`**，且都在各 target 的 `settings.base` 里 —— **没有 Release 变体**。
- 判定：**版本一致 = 已满足**；**Debug/Release 分离 = 缺失**（见 G1，本轮 P0）。

## R6 24 小时隔离运行记录重连、资源增长、任务状态、通知去重与恢复；出现问题可追踪提交和 fixture，不把一次 60 秒 SSE 当 soak

- 现状：`docs/COMPATIBILITY.md` 有冒烟隔离警告；`scripts/e2e-arch-smoke.mjs` 是单次短程验证。
- **缺失**：没有 24 小时 soak 记录或脚本。
- 判定：**缺失**（见 G3；需 24 小时挂机，本轮不实施，登记为待办）。

## R7 使用过程中发现故障，进入同一问题清单：记录复现、实际/预期、范围、证据、修复与验证结果

- 现状：`.local/diagnostics/*/REPORT.md` 有先例格式，但**没有统一的问题清单文件**。
- 判定：**部分**（见 G4，本轮补模板 + 登记已有条目）。

---

## 差距汇总

| 编号 | 要求 | 判定 | 处置 |
|---|---|---|---|
| **G1** | R5 后半：Debug/Release bundle 配置未分开，`.debug` 写死 | 缺失 | **本次实施 P0** |
| **G2** | R3：iOS 诊断导出缺"不含敏感串"的测试 | 缺失 | **本次实施 P0** |
| **G3** | R6：无 24 小时 soak 记录 | 缺失 | 登记待办（需挂机，非本轮） |
| **G4** | R7：无统一问题清单 | 部分 | **本次实施 P1**（模板 + 首条登记） |

### G1 细化

- 现状：debug 与 release 构建产生**同一个 bundle id**（`dev.deeplinks.ios.debug*`），
  无法在同一台设备上并存，且"debug 后缀"会随生产包一起发出去 —— 与 R5「不能直接当生产配置」冲突。
- 目标：bundle id 按配置取值：Debug 保留 `.debug` 后缀（现有 CI/测试依赖它，不能动），
  Release 用不带 `.debug` 的生产 id。**不改任何现有 Debug 行为**，避免打断 CI 与已安装的调试包。
- 落地：`project.yml` 把 `PRODUCT_BUNDLE_IDENTIFIER` 从 `settings.base` 移到 `settings.configs.Debug/Release`。
- **需 lead 决策**：生产 bundle id 字串（例如 `dev.deeplinks.ios`）属对外身份，我按最小假设取"去掉 `.debug` 后缀"，若要别的字串请告知。

### G2 细化

- 目标：iOS 侧断言诊断导出**不含** token / 密钥 / 绝对路径 / IPv4 / 正文。
- 落地：新增测试，用一个**故意含敏感字段**的诊断样本走导出函数，断言输出里找不到这些串；
  再加一条正向用例（正常样本能被正常导出）。不依赖 `Settings/**` 的存根数据，只调用公开的导出函数。

### G4 细化

- 目标：`docs/cetus/ISSUES.md` —— 统一问题清单（复现 / 实际 vs 预期 / 范围 / 证据 / 修复 / 验证）。
- 落地：建模板并把本轮及此前已修的问题登记进去（如推送 `deviceId` 断链、单设备失败拖垮整批）。
