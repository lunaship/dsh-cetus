# C10 差距盘点：设置、诊断与偏好从页面接到服务

> 方案来源：`Cetus-整体改造方案-2026-10-07.md` 第 361 行起（§14 C10），页面 7.1–7.15。
> 逐条：**要求 → 代码位置 → 实际行为 → 判定**。

---

## 已修复（本轮，均有测试）

| # | 方案要求 | 修复前实况 | 修复 |
|---|---|---|---|
| **1** | 在生产依赖根装配 `SettingsAccountService` | `InboxPage` 构造 `SettingsHomePage` 时**没有 `account:`**；`loadAccount` 第一行 `guard let account else { return }` → 7.2「电脑」与 7.3「诊断」整块不工作（重命名、解绑、诊断全都不可用） | 新增 `SettingsAccountService.live(hostID:)`，在 `InboxDestinationPage.settings` 注入；复用首页同一套选路与 `probe`（把 `probe` 从 private 提为 internal，避免第二份实现） |
| **2** | 移除生产默认 `sampleChecks` | `displayedChecks = loadedChecks ?? checks`，而 `checks` 默认是**样例**。查询失败时 `loadedChecks` 仍为 nil → 用户在「诊断不可用」提示旁边看到**伪造的 OK/WARN 结论** | 生产默认改为空；失败时显式置空；新增空态文案 `settings.diagnosticsEmpty`；`sampleChecks` 从生产文件删除，改由截图测试注入固定账号 |
| **3** | 电脑地址显示实际选路与连接信息，**不能用 hostID 代替地址** | `computerAddress: model.hostID` —— 用户看到的是 `host-1a2b` 这种内部标识；且「电脑」页三条路线里两条写死「未连接」、一条写死「已连接」 | `InboxPayload` 新增 `hostAddress`（直连取实际 `host:port`，远程取 relay 端点）；新增 `SettingsRouteKind` 与 `model.settingsRoute`，三条路线按真实路线显示 |
| **5** | 外观在根层应用 | `@AppStorage("settings.theme")` **只写不读** —— 用户选深色/浅色完全没反应，静默失效 | 新增 `ThemePreference`（键与字符串→`ColorScheme` 换算收在一处）；`RootView` 加 `.preferredColorScheme`；「跟随系统」返回 nil 不加覆盖 |

配套测试：`SettingsRouteDisplayTests`（4）、`ThemePreferenceTests`（4）、
`SettingsDiagnosticsHonestyTests`（1，盯文件事实防样例回流）、以及既有
`SettingsTests` / `SettingsAccountTests`，共 34 项通过。

## 已满足（核对确认，未改）

| 要求 | 证据 |
|---|---|
| 4 重命名/解绑/清理缓存分别处理；解绑只删本机关联 | `SettingsAccountLogic.swift` 的 `shouldDeleteCredentials(after:)` 与 `SelfRevokeBody`（按 `deviceId` 吊销，不按名字） |
| 5 相机扫码保持深色是独立页面规则 | 未受根层 `preferredColorScheme` 影响（该页自带配色） |
| 7 写电脑设置遵守 allowlist；拒绝不显示成功 | `models.settingsWritable` 控制可写；`SettingsModelsService` 只在服务端接受后更新 |
| 9 余额保持十进制字符串；失败分别显示 | `SettingsModelsModel` 用字符串余额，不用 PNG 的 `¥42.10` 兜底 |
| 10 关于页显示 cetus/版本/构建/许可证 | `SettingsPages.swift` about 分支；构建元数据本轮改为可注入 |
| 11 MetricKit 只本地查看与导出 | `SettingsCrashStore.export`；无报告时文案为「暂无诊断」，不伪造崩溃记录 |

## 仍未验证 / 未完成

| # | 项 | 原因 |
|---|---|---|
| 6 | 语言在当前层立即生效 | **未逐项验证**：`@AppStorage` 语言键的切换覆盖范围需要真机走一遍新增文案，本地未做 |
| 6 | 本机设置 vs 电脑 profile 设置分清 | 部分：`settingsWritable` 已区分可写性，但未逐条核对每个偏好归属 |
| 8 | 通知设置三维度（系统授权 / App 偏好 / 网关可用性） | **部分**：`pushAvailable` 覆盖了网关可用性，系统授权状态未单独呈现；需真机授权流程验证 |
| — | 诊断「显示测试时间」 | 未做：`DiagnosticsReport.generatedAt` 已解码但界面未呈现 |
| — | 外观覆盖 WebView 与 sheet | 根层 `.preferredColorScheme` 理论上覆盖，但**未在真机上验证** sheet / WKWebView 内的实际效果 |

## 验收对照（方案原文）

> 外观切换并打开 sheet 后一致，重启保留；语言切换覆盖新增文案；诊断成功/失败有区别；
> 设置拒绝不假成功；两个电脑分别显示真实能力；解绑用假 host 测，不碰用户现有配对。

- 「诊断成功/失败有区别」：**已满足**，且失败不再伴随伪造结果（本轮修复 #2）
- 「外观切换…重启保留」：**代码已具备**（`@AppStorage` 持久化 + 根层应用），真机未验证
- 「解绑用假 host 测」：`SettingsAccountTests` 用假 transport 覆盖，未碰真实配对
- 「两个电脑分别显示真实能力」：`hostAddress` + `route` 已按主机独立，但**未做双主机真机验证**
