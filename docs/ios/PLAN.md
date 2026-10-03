# DeepLinks iOS：从 0 到 1 执行方案

> 状态：已采纳 v1.3（2026-10-03）。
> v1.3 变更：`sealed` 定为 JSON 对象；附加数据前缀改为 `dlpush/1 token|` 与 `dlpush/1 content|` 做域分离；I6.2 测试向量路径改为 `testdata/push/hpke/` 与 `testdata/push/content/`。
> v1.2 变更：HPKE 算法组合定为 X25519 / HKDF-SHA256 / ChaCha20-Poly1305 并规定测试向量；补 7.6 对话默认；设计稿去掉“输入配对码”；RFC 0002 先留在 `ios/main`，阶段 6 随插件 PR 一起进 main。
> v1.1 变更：I1.5 改为 HTML 设计稿（I1.5a 已完成）+ 模拟器截图验收（I1.5b）；决策栏按钮改为实色；深色 BrandFill 暂定 `#4C66E6`；推送网关部署到维护者的香港服务器；新增执行规则第 11 条（agent 无法本地编译 iOS）。
> 写给两类读者：维护者（做决定、付费、审核）和执行 PR 的 agent（按编号领取）。
> 基线：`lunaship/dsh-links` main @ `7b9c164`（v4 重设计已合入）。
> 页面编号（1.x–8.x）与 `docs/redesign-v4/design-v4.html` 一一对应；iOS 子项编号用 `I<阶段>.<序号>`。
> iOS 设计稿：`apps/ios/docs/design/`（PNG + `README.md`），与本文冲突时以本文为准并在 PR 中指出。

---

## 目录

- 0. 目标与完成标准
- 1. 已定决策
- 2. 执行规则
- 3. 红线
- 4. 仓库结构与工具链
- 阶段 0：前置验证与环境准备
- 阶段 1：规格文档与设计稿
- 阶段 2：工程地基
- 阶段 3：核心连接层（局域网）
- 阶段 4：界面模块
- 阶段 5：远程连接（DLP/1）
- 阶段 6：推送网关与通知
- 阶段 7：系统层（Live Activity、分享扩展）
- 阶段 8：质量加固
- 阶段 9：分发（开发者账号、TestFlight、App Store）
- 10. 风险与对策
- 11. 执行记录
- 附录 A：v4 页面 ↔ iOS 组件对照表
- 附录 B：玻璃使用规则
- 附录 C：iOS 要实现的手机 API 清单
- 附录 D：维护者需要亲自完成的事项清单

---

## 0. 目标与完成标准

**目标**：做一个原生 iOS 客户端，功能与 Android v4 对齐，外观遵守 Apple HIG 与 Liquid Glass；同时上线一个开源、无状态、端到端加密的推送网关，让 iPhone 在离开 App 后也能收到审批与完成提醒。

**完成标准（全部可检查）**

1. design-v4 的页面在 iOS 上都有对应实现（附录 A 每一行都有落点，或在执行记录里写明“平台差异 / 不做”的原因）。
2. 每个页面有截图基线：浅色 / 深色 × 中文 / 英文 × 默认字号 / 大字号，外加“降低透明度”一套。
3. 局域网、Tailscale、DLP/1 中继三条路都能配对、看会话、发消息、处理审批与提问。
4. 离开 App 后：审批、提问、完成、失败四类事件能在 30 秒内送达通知；锁屏不显示内容；Live Activity 显示进行中的任务。
5. 推送网关不存任何用户数据，看不到通知内容；代码在本仓 `push/` 开源；`PRIVACY.md` 写明边界。
6. 门禁全绿：插件 `npm run prepack`；中继与网关 Go 门禁；iOS 构建 + 单测 + 截图校验 + 合同测试。
7. TestFlight 外部测试通过 Beta 审核，至少 2 台真机（iOS 26 与 iOS 27 各一）完成验收清单。
8. 不影响 Android：Android 门禁保持全绿，旧 App 能忽略插件新增字段。

---

## 1. 已定决策

| 项 | 决定 | 说明 |
|---|---|---|
| 技术栈 | 原生 Swift。SwiftUI 为主，对话流、输入区用 UIKit | 不用 React Native / KMP |
| 共享方式 | 纯 Swift 实现 + 共享测试向量 | 不改 Android 代码结构；行为一致靠 `testdata/` |
| 最低系统 | iOS 26.0 | 用最新 Xcode（iOS 27 SDK）构建 |
| 界面规范 | Apple HIG + 系统组件；沿用 v4 页面编号与信息结构 | 平台差异写进执行记录 |
| 强调色 | 品牌蓝：`AccentColor` 浅 `#3F5BD6` / 深 `#8B9DFF`；深色主按钮填充另取更深的蓝 | 保证按钮白字对比度达标 |
| 首页列表 | plain 列表（参照“邮件”） | 每屏只一个品牌实心按钮（新任务） |
| 搜索 | 底部工具栏（参照 iOS 26 “邮件”） | 工作区筛选用搜索 token |
| 左滑 | 保留：向左滑 归档 / 删除 | 审批不做滑动 |
| 推送 | 自建无状态网关 + 端到端加密，默认关闭 | 不用 OneSignal |
| Live Activity | 做 | 只推不敏感字段 |
| 分发 | 先 TestFlight，再海外 App Store；国区以后再说 | 国区需 App 备案 |
| 付费时机 | 阶段 9 才开通 Apple Developer Program | 之前全部用免费账号 + 模拟器 |
| 参考项目 | Lody iOS（AGPL-3.0）只参考思路 | **不得复制源码** |

---

## 2. 执行规则

1. **集成分支**：从 main 切 `ios/main`。iOS 子项的 PR 都以 `ios/main` 为目标；插件 / 网关 / 文档改动（会影响 Android 或线上的）直接对 main，并由维护者合并。
2. **一个子项一个分支一个 PR**：分支 `ios/i<阶段>.<序号>-<短名>`，PR 标题 `[iOS I3.2] 证书固定与主机存储`。
3. **自行合并**：只对 `ios/main`，CI 全绿后 squash 合并。`ios/main → main` 只由维护者操作。
4. **必须停下等人的点**（开 PR、写汇报，然后停）：
   - 阶段 0 的验证结论出来后（结论决定是否继续）。
   - 阶段 1 的规格文档与设计稿（维护者审核后才能进入阶段 2）。
   - 阶段 5 的 DLP/1 spike 结论（决定走方案 A 还是 B）。
   - 阶段 6 的网关 RFC（安全审查）和插件 PR（安全审查）。
   - 任何需要付费、改签名、改线上中继或网关部署、推 tag、建 Release 的操作。
   - 设计稿要的数据插件里没有：在 PR 里写明，按“删掉该元素”处理，不自行发明替代 UI。
5. **失败处理**：同一个门禁连续修 3 次仍失败，停下汇报；不绕过门禁、不删测试、不调高预算。
6. **截图基线**：只允许由 CI 工作流 `ios-regen-screenshots.yml` 生成；禁止提交本地生成的基线。PR 描述附“新基线 ↔ 页面编号”对照表。
7. **合同先行**：凡是改手机 API，先改 `docs/MOBILE_SYNC_CONTRACT.md` 与 `docs/COMPATIBILITY.md`，再写代码；插件新能力在 `src/protocol-caps.js` 声明，App 只在声明时显示入口。
8. **双语**：所有文案进 String Catalog（`Localizable.xcstrings`），简体中文与英文同时提交。
9. **进度记录**：每合并一个 PR，在本文第 11 节追加一行（子项、PR、结论或偏差）。
10. **不发布**：不改版本号、不打 tag、不建 Release，只在 CHANGELOG“未发布（main）”下记一行。
11. **编译环境**：云端 agent 运行在 Linux，没有 Xcode，无法本地编译、测试或截图 iOS 代码。
    - 以 `ci-ios.yml`（macOS runner）的结果作为唯一门禁；CI 不绿不合并，不得在 PR 里声称“本地已通过”。
    - 每次 push 后等待 CI 结果再继续；失败时读 CI 日志修复，计入第 5 条的 3 次上限。
    - 需要真机或交互调试的事项，写进 PR 的“需维护者在 Mac 上验证”清单，然后停下。

---

## 3. 红线

**沿用现有红线（`AGENTS.md`、proposal 002）**

- 冒烟 / 联调必须用隔离 `stateDir`（`node scripts/dev-isolated-host.mjs`），不得调用设备吊销类操作；对真实 `~/.dsh` 只读。
- 不得改动线上 `relay.dshlinks.com`；中继代码只能走 PR，部署由维护者决定。
- 手机 API 不提供高危操作（跨设备吊销、全部吊销、工作区批准、端口批准）。

**iOS 新增红线**

- 凭据（设备 token、证书指纹、端到端推送密钥）只存 Keychain：`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`，`kSecAttrSynchronizable = false`（不进 iCloud 钥匙串）。
- 不开 `NSAllowsArbitraryLoads`。只允许 `NSAllowsLocalNetworking`；所有主机连接必须通过证书指纹校验。
- 中继转来的错误码可能被伪造：只能提示，不得据此删除或改写本机凭据（与 Android 一致）。
- 通知扩展（NSE）和分享扩展**不联网**、不读设备 token，只能读各自需要的最小数据。
- 渲染不可信内容的 WKWebView：非持久数据存储、禁止访问文件、CSP 只放行随包资源、禁止联网（预览页除外，预览页只放行本机回环代理）。
- 网络图片默认不自动加载（与 Android 一致）。
- 不引入第三方统计、崩溃上报 SDK；崩溃诊断只用系统 MetricKit，本机查看，用户主动导出。
- 不复制 lody-ios 或其他 AGPL / GPL 项目的源码；引入任何依赖前在 PR 里写明许可证。

---

## 4. 仓库结构与工具链

### 4.1 目录

```text
dsh-links/
├── apps/
│   ├── android/                    # 不动
│   └── ios/                        # 新增
│       ├── project.yml             # XcodeGen 工程描述（.xcodeproj 不入库，避免 agent 冲突）
│       ├── App/                    # App target：入口、路由、页面
│       │   ├── DeepLinksApp.swift
│       │   ├── Routing/
│       │   ├── Features/
│       │   │   ├── Pairing/        # 1.x
│       │   │   ├── Home/           # 2.x
│       │   │   ├── NewTask/        # 3.x
│       │   │   ├── Chat/           # 4.x（UIKit 消息流 + 输入区在这里）
│       │   │   ├── Sheets/         # 5.x
│       │   │   ├── Changes/        # 6.x
│       │   │   └── Settings/       # 7.x
│       │   ├── Debug/              # 仅 Debug 构建：固定数据场景
│       │   ├── Demo/               # 审核用演示模式（Release 也带，数据离线）
│       │   └── Resources/          # Assets（AccentColor、BrandFill）、Localizable.xcstrings
│       ├── Packages/               # 本地 Swift Package，按依赖方向从下到上
│       │   ├── DLModels/           # 合同里的数据结构（Codable），零依赖
│       │   ├── DLCore/             # 归并逻辑：请求状态、SSE 游标、消息分类兜底、词级 diff
│       │   ├── DLSecurity/         # Keychain、证书指纹、推送端到端密钥
│       │   ├── DLNet/              # HTTP 客户端、SSE、选路、配对
│       │   ├── DLRemote/           # DLP/1
│       │   └── DLUI/               # 共享 UI 组件（SwiftUI + UIKit）
│       ├── Extensions/
│       │   ├── NotificationService/  # NSE：解密推送
│       │   ├── LiveActivity/         # Widget extension
│       │   └── Share/                # 分享扩展
│       ├── Tests/
│       │   ├── Contract/           # 读仓库 testdata/ 的合同测试
│       │   └── Snapshots/          # 截图测试与基线
│       ├── docs/
│       │   ├── visual-rules-ios.md
│       │   └── page-mapping.md
│       ├── AGENTS.md
│       └── README.md
├── push/                           # 新增：推送网关（Go）
├── relay/                          # 不动（阶段 5 只读）
├── src/                            # 插件：阶段 6 新增 push-sink
└── testdata/                       # 扩充：合同 fixtures、推送加密向量
```

依赖方向只能从上往下：`App → DLUI → DLNet/DLRemote → DLSecurity → DLCore → DLModels`。由测试 `ArchitectureTests` 检查 import 方向。

### 4.2 工具与依赖

| 用途 | 选择 | 许可证 | 备注 |
|---|---|---|---|
| 工程生成 | XcodeGen | MIT | `xcodegen generate` 生成 `.xcodeproj` |
| 单测 | Swift Testing（系统自带） | — | |
| 截图测试 | swift-snapshot-testing（Point-Free） | MIT | |
| 格式化 | swift-format（Apple） | Apache-2.0 | CI 检查 |
| 远程（方案 B 才用） | SwiftNIO、NIOSSL、NIOWebSocket、NIOTransportServices | Apache-2.0 | 阶段 5 视 spike 结论引入 |
| Markdown | MarkdownView / Litext（Lakr233） | **引入前核对** | 若不合适，退回自研：swift-markdown（Apple，Apache-2.0）解析 + TextKit 2 渲染 |
| 公式与图 | KaTeX、Mermaid（随包） | MIT | 复用 Android 已打包的版本与 THIRD_PARTY_NOTICES |
| 代码高亮 | 复用 Android 的高亮规则表，Swift 重写 | — | 配色走 token |

不引入：任何统计 SDK、OneSignal、Firebase、Alamofire（URLSession 足够）。

### 4.3 CI（GitHub Actions）

| 工作流 | 触发 | 内容 |
|---|---|---|
| `ci-ios.yml` | PR 到 `ios/main`、main | `xcodegen` → `xcodebuild build`（Debug + Release）→ 单测 → 合同测试 → 截图校验 → swift-format 检查 |
| `ios-regen-screenshots.yml` | 手动 / PR 标题含 `[regen-ios-baselines]` | 在固定模拟器型号与系统版本上重生成基线，提交回 PR 分支 |
| `ci-push.yml` | 改了 `push/` | `gofmt`、`go vet`、`go test -race`、模糊测试 |

macOS runner 计费分钟是 Linux 的 10 倍：截图校验只在改了 `apps/ios/` 时跑；或者把维护者的 Mac 注册成 self-hosted runner（推荐，基线也更稳定）。

固定模拟器：iPhone 17 Pro（iOS 26.x）一台用于基线；iPad 一台用于宽屏基线。型号与系统版本写进 `apps/ios/docs/visual-rules-ios.md`，改动需维护者批准。

---

## 阶段 0：前置验证与环境准备

### I0.1 验证审批钩子（最先做，决定是否继续）

**为什么**：兼容矩阵记录了插件挂在 `approval/request`、`user-questions/request` 上的钩子在 DSH `0.1.5-rc.3` 上从未被调用；`0.1.7-alpha.1` 未验证。钩子不触发，手机就不能接管审批，iOS 的核心价值不成立。

**步骤**

1. 用 `node scripts/dev-isolated-host.mjs` 起一个隔离实例（独立 `--profile`、临时 `stateDir`，插件 `link:` 到工作树）。
2. 在两个钩子入口各写一个探针：被调用时向临时文件追加一行时间戳与请求类型。
3. 用 Android debug 包配对隔离实例，进入一个会话，让 agent 执行一个需要审批的命令（例如写文件），再触发一次澄清提问。
4. 观察 100 秒：探针文件是否有记录；`rt.requests` 是否非空；手机上是否出现可操作的决策栏。
5. 在手机上点“允许一次”，确认 DSH 继续执行。

**产出**：在 `docs/COMPATIBILITY.md` 的「审批瀑布」一节追加 `0.1.7-alpha.1` 的结论（触发 / 不触发、证据、版本、日期）。

**分支**

- 触发：继续阶段 1。
- 不触发：停。向上游确认插件钩子的正确挂载方式；在此之前 iOS 只能做“只看不批”，是否继续由维护者决定。

### I0.2 开发环境（维护者，一次性）

1. Mac 安装最新正式版 Xcode（带 iOS 27 SDK）与 iOS 26 模拟器运行时。
2. `brew install xcodegen swift-format`。
3. Xcode → Settings → Accounts 登录**免费** Apple ID（个人团队），用于真机安装。
4. iPhone 打开“开发者模式”（设置 → 隐私与安全性 → 开发者模式）。
5. 记录两台测试机的型号与系统版本（一台 iOS 26、一台 iOS 27，若只有一台先用它）。

### I0.3 下载设计资源（维护者）

1. （可选）从 Apple Design Resources 下载 iOS 27 设计套件，仅作对照；本项目设计稿不在 Figma 上画（见 I1.5）。
2. 下载 SF Symbols App。
3. 安装 Icon Composer（随 Xcode 提供）。

---

## 阶段 1：规格文档与设计稿（全部是文档，停下等审核）

### I1.1 `apps/ios/docs/visual-rules-ios.md`（≤ 120 行）

必须包含以下几节，取值见本文附录：

1. **唯一方向**：Apple HIG + 系统组件 + 单一品牌色；内容层实色，功能层由系统提供玻璃。
2. **颜色**：只用系统语义色 + `AccentColor` + `BrandFill`；状态色 systemOrange（等待 / 风险）、systemGreen（完成 / 增）、systemRed（失败 / 删 / 危险）。禁止写死其他色值。
3. **文字**：只用动态字体的文字样式（`.largeTitle` `.title3` `.headline` `.body` `.subheadline` `.footnote` `.caption`），代码、路径、命令、模型 ID 用等宽（`.monospaced()`）。禁止写死字号。
4. **形状**：控件用胶囊；容器用同心圆角（`ConcentricRectangle` / `containerShape`）；行内代码与小标签固定 6pt。不自定义其他圆角。
5. **间距**：系统默认边距优先；自定义间距只用 4 的倍数（4–32）。
6. **玻璃规则**：见附录 B，全文照抄。
7. **组件清单**：只用附录 A 第二张表里的组件；新增组件先改本文件。
8. **动效**：系统弹簧动画；`glassEffectID` 形变；尊重“减弱动态效果”。
9. **辅助功能**：触控 ≥ 44pt；VoiceOver 标签；降低透明度、增强对比度、粗体文本下可用。
10. **截图矩阵**：设备型号、系统版本、外观、语言、字号、降低透明度。
11. **禁止清单**：自制模糊、玻璃叠玻璃、内容层玻璃、写死字号与色值、同屏两个品牌实心按钮、审批做滑动手势。

### I1.2 `apps/ios/docs/page-mapping.md`

把附录 A 扩展成完整对照表：v4 每个页面编号 → iOS 页面 / 组件 / 系统 API → 与 Android 的差异 → 截图测试名。

### I1.3 `docs/rfc/0002-push-gateway.md`

推送网关的逐字节合同，内容见阶段 6。格式参照 `docs/rfc/0001-dlp1-remote-pipe.md`。

### I1.4 `apps/ios/AGENTS.md`

iOS 协作规则：目录契约、依赖方向、门禁命令、红线（第 3 节）、截图规则、“UI 改动必须对应页面编号”。

### I1.5 设计稿（HTML 近似稿 + 模拟器验收）

Figma 无法由 agent 操作，改为两步：

**I1.5a HTML 近似稿（已完成，维护者验收）**

1. 已交付到 `apps/ios/docs/design/`：1.2 欢迎、1.3 扫码、2.1 首页、4.1 运行中、4.3 待审批、4.4 回答问题、6.1 改动、6.2 diff、7.1 设置、8.4 锁屏 Live Activity，浅色 / 深色各一张，1206×2622（iPhone 17 Pro @3x）；另有 8.5 灵动岛状态评审图、总览图与 `brandfill-candidates--dark.png`。生成脚本在 `design/src/`。
2. 维护者把 PNG 传到 iPhone 用“照片”全屏查看：字号、层级、信息量。
3. 维护者在深色模式下比较 `brandfill-candidates--dark.png`，定深色 `BrandFill`（候选 `#4F6AEB` / `#4C66E6` / `#4A63E0` / `#3F5BD6`，白色 17pt 中粗体对比度均 ≥ 4.5:1）。未定之前用 `#4C66E6`。
4. 用法约束（写进 `apps/ios/AGENTS.md`）：PNG 只定布局与层级；实现一律用系统组件、SF Symbols、动态字体与附录色板 token；**禁止从 PNG 取色或按像素复刻**。玻璃效果在 PNG 中是近似，以 I1.5b 为准。

**I1.5b 模拟器截图验收（阶段 2 结束时，维护者验收）**

1. 阶段 2 骨架完成后，由 CI（`ios-regen-screenshots.yml`）用演示数据为上面 10 个页面生成模拟器截图：浅色、深色、降低透明度、最大动态字体各一套。
2. 截图与 I1.5a 并排放进 PR，逐页列出差异；差异只允许来自系统组件的真实表现。
3. 维护者在真机（免费签名安装）上抽查玻璃可读性，确认后才进入阶段 3。

**停下**：维护者审核 I1.1–I1.4 与 I1.5a，通过后进入阶段 2。

---

## 阶段 2：工程地基

### I2.1 工程骨架

- `project.yml`：App target（bundle id 先用 `dev.deeplinks.ios.debug`，阶段 9 再定正式 id）、三个扩展 target 先占位、本地 Packages 引用。
- `DeepLinksApp.swift`：`@main`，根视图是空的 `NavigationStack`；`scenePhase` 监听。
- `Info.plist`：
  - `NSLocalNetworkUsageDescription`（中英文）：“用于连接你电脑上的 DeepSeek Harness。”
  - `NSCameraUsageDescription`：“用于扫描电脑上的配对二维码。”
  - `NSAppTransportSecurity → NSAllowsLocalNetworking = YES`。
- `PrivacyInfo.xcprivacy`：先登记 `UserDefaults`（理由 `CA92.1`）；后续用到其他“需说明理由的 API”时补齐。
- 验收：`xcodegen generate && xcodebuild -scheme DeepLinks build` 在 CI 通过。

### I2.2 CI

- 按 4.3 节建三条工作流。截图基线工作流先跑通空页面。
- 验收：PR 上能看到 iOS 构建、单测、截图校验三个检查。

### I2.3 主题与 token（`DLUI/Theme`）

- Assets：`AccentColor`（浅 `#3F5BD6` / 深 `#8B9DFF`）、`BrandFill`（浅 `#3F5BD6` / 深 `#4C66E6`，暂定，维护者在 I1.5a 定值）。
- `DLColor`：只暴露语义名（`label`、`secondaryLabel`、`tertiaryLabel`、`background`、`groupedBackground`、`fill`、`wait`、`ok`、`err`、`accent`、`brandFill`），内部映射到系统色。
- `DLFont`：`title`、`headline`、`body`、`meta`、`caption`、`mono(_:)`，全部基于动态字体文字样式。
- 架构测试 `TokenUsageTests`：扫描 `App/` 与 `DLUI/` 源码，出现 `Color(red:`、`UIColor(red:`、`.font(.system(size:` 即失败（`DLUI/Theme` 目录除外）。

### I2.4 基础组件（`DLUI/Components`）

| 组件 | 实现 | 对应 Android |
|---|---|---|
| `DLInboxRow` | SwiftUI | `DlInboxItem` |
| `DLStatusSlot` | SwiftUI，放在导航栏下的 `safeAreaBar(edge: .top)` 或内容顶部（以 spike 结论为准） | `DlStatusSlot` |
| `DLComposerView` | **UIKit**，贴 `keyboardLayoutGuide`，外层 `UIGlassEffect` | `DlComposer` |
| `DLDecisionBar` | 与 `DLComposerView` 同一个玻璃容器，内容切换 + 形变动画 | `DlDecisionBar` |
| `DLChip` | SwiftUI 胶囊按钮，`.buttonStyle(.glass)` 或 `.bordered` | `DlChip` |
| `DLProcessLine` | 一行灰字过程摘要（“思考 6 秒 · 读了 4 个文件 ›”） | 过程行 |
| `DLCodeBlock` | 等宽、实色底、横向滚动、复制按钮 | 代码块 |
| `DLEmptyState` | 包一层 `ContentUnavailableView` | 空态 |
| `DLBanner` | 内容层中性横幅（离线、余额提醒），不用玻璃 | 横幅 |

系统组件直接用、不再封装：`NavigationStack`、`List`、`Form`、`.sheet`、`confirmationDialog`、`alert`、`Menu`、`Picker`、`Toggle`、`contextMenu`、`swipeActions`、`searchable`。

验收：每个组件在 Debug 场景里有浅色 / 深色 / 长文本 / 禁用态四张截图。

### I2.5 Debug 场景与演示数据

- `App/Debug/`：一个仅 Debug 构建可见的入口（设置页底部），列出所有固定数据场景；每个场景用生产组件 + 注入数据，不联网、不登录。
- `App/Demo/`：审核与首次体验用的演示模式，数据来自 `Demo/fixtures/*.json`，Release 也带。入口在欢迎页“先看看演示”。
- 截图测试全部基于这些场景。
- 验收：断网状态下能进入所有场景；截图测试不需要任何网络。

### I2.6 本地化

- `Localizable.xcstrings`，开发语言英文，加简体中文。key 用语义名（如 `home.section.waiting`）。
- 脚本 `scripts/check-ios-locales.mjs`：检查两种语言 key 一致、格式符类型一致（吸取 R0.1 的教训）。进 CI。

---

## 阶段 3：核心连接层（局域网）

> 每一项都必须先写合同测试（读 `testdata/`），再写实现。

### I3.1 数据模型（`DLModels`）

- 按附录 C 的接口清单，把 `docs/MOBILE_SYNC_CONTRACT.md` 里的每个响应写成 `Codable` 结构。
- 规则：未知字段忽略；可选字段全部 `Optional`；枚举带 `unknown` 兜底，不因新取值解码失败。
- 消息以 `kind` 为准（`user` / `injection` / `goal_round` / `model_changed` / 其他等于 `role`）；`kind` 缺失时走 `DLCore` 的文本兜底，规则读 `testdata/context-injection-cases.json`。

### I3.2 合同 fixtures（插件侧 + iOS 侧，对 main）

- 插件新增脚本 `scripts/export-contract-fixtures.mjs`：用测试里的假 Host 生成每个手机接口的真实响应样本，写入 `testdata/mobile-contract/*.json`。
- iOS `Tests/Contract` 逐个解码这些文件；Android 可以后续接入同一批文件。
- 验收：插件改了响应结构但没更新 fixtures 时，插件测试失败；iOS 解码失败时 iOS 测试失败。

### I3.3 Keychain 与主机存储（`DLSecurity`）

- `KeychainStore`：增删改查，属性见第 3 节红线；access group 预留给 NSE（阶段 6 用）。
- `HostStore`：保存已配对电脑 `{hostId, name, primaryUrl, tailnetUrl?, certFingerprint, remote?, pairedAt}`。敏感字段（token、指纹、远程密钥）进 Keychain，其余进 App 沙盒 JSON。
- 单测：读写、覆盖、删除、卸载后不可恢复（模拟器手动验证）。

### I3.4 证书固定（`DLSecurity` + `DLNet`）

- `URLSessionDelegate.urlSession(_:didReceive:completionHandler:)` 里取服务器叶子证书，计算指纹，与 `HostStore` 里的指纹逐位比对；不一致直接取消，并抛出 `certificateChanged` 错误（界面提示“电脑证书变了，需要重新配对”，**不自动删除凭据**）。
- 指纹算法、格式必须与插件 `src/tls.js`、Android `PinnedSsl` 完全一致：新增共享向量 `testdata/tls-fingerprint/*.json`（证书 DER + 期望指纹），三端都跑。
- 首次配对时指纹来自二维码（与 Android 一致），没有指纹的局域网地址拒绝配对。

### I3.5 HTTP 客户端与 SSE（`DLNet`）

- `HostClient`：基于 `URLSession`，统一加设备 token、超时、错误映射（401 / 403 / 409 `session_busy` / 404 能力缺失）。
- `SSEClient`：用 `URLSession.bytes(for:)` 逐行解析；支持 `Last-Event-ID`；处理 `resync-required`（丢弃不可证的增量，拉快照后从快照游标继续）；心跳超时 35 秒判定断线；断线 30 秒内指数退避重连。
- 游标：“已提交游标”与“接收游标”分开；重连用已提交游标（与合同一致）。
- 只在前台连接：`scenePhase` 变为 background 时主动断开；回到前台时重连并补发。
- 单测：用本地假服务器覆盖续传、缺口、`resync-required`、心跳超时、401 中断。

### I3.6 请求状态归并（`DLCore`）

- 状态：`pending` / `resolved` / `cancelled` / `expired` / `unknown`。终态不得被 pending 回滚。
- 同 `approvalId` 的审批、同 `rpcId` 的提问各归并为一条；实时流、重连快照、`GET .../requests`、提交响应走同一个 reducer。
- 用例直接从 Android 单测翻译，放进 `testdata/request-state-cases.json` 供两端共用。

### I3.7 选路（`DLNet`）

- 顺序：主地址（局域网）→ 备用 Tailscale 地址 → 中继（阶段 5 接入前跳过）。
- 成功的地址缓存，`NWPathMonitor` 报告网络变化时作废。
- 本地网络权限：首次连局域网前展示说明页；被拒时诊断页给出“去设置打开”的入口（`UIApplication.openSettingsURLString`）。

### I3.8 配对（`DLNet` + `Features/Pairing` 的逻辑部分）

- 解析二维码载荷：`urls`、证书指纹、`issuedAt` / `expiresAt`、`remote`（阶段 5 才用）。
- 本机时间超过 `expiresAt` 直接提示“请刷新电脑上的二维码”，不提交。
- `POST /dsh-link/pair`：
  - 200：保存凭据，进入首页。
  - 409 `SAME_NAME`：弹窗“替换它 / 换个名字”；替换时用同一张码带 `replace: true` 重发。
  - pending：进入 1.5 等待页，每 2 秒请求 `/dsh-link/mobile/sessions`（200 = 批准，403 + pending = 继续，401 = 拒绝或超时）。
- 单测覆盖以上每个分支。

### I3.9 局域网冒烟（真机）

- 隔离 host + 免费账号真机：配对、拉会话列表、打开会话、看到流式输出、发一条消息、处理一次审批。
- 证据写进执行记录（日期、DSH 版本、插件 commit、iOS 版本、结果）。

---

## 阶段 4：界面模块（每个模块一个或多个 PR）

> 顺序固定：I4.1 配对 → I4.2 首页 → I4.3 对话 → I4.4 新任务 → I4.5 弹层 → I4.6 改动 / 文件 / 预览 → I4.7 设置 → I4.8 iPad。
> 每个 PR：只动本模块；只用阶段 2 的组件和系统组件；补齐本模块全部截图；PR 描述附与设计稿的逐页对照。

### I4.1 启动与配对（1.1–1.6）

- 1.1 启动：只用系统 Launch Screen（纯背景 + 品牌标）。
- 1.2 欢迎：大号 SF Symbol（`terminal` 或品牌标）+ 标题 + 三步说明；底部主按钮“扫码配对”（`.glassProminent` + BrandFill），次按钮“从相册识别”（`PhotosPicker` → Vision 识别二维码）；文字按钮“先看看演示”。
- 本地网络说明页：在第一次连局域网之前出现，一句话说明 + “继续”。
- 1.3 扫码：`DataScannerViewController`（只识别二维码），相机画面上只有玻璃关闭按钮与一段提示；设备不支持时退回 `AVCaptureSession`。
- 1.5 等待电脑批准：显示本机名称与连接方式，可取消（取消即删除本机这条记录）。
- 1.6 配对失败：标题 + 原因 + 三条建议 + “返回”。
- 1.4 输入配对码：不做（沿用 v4 决定）。

### I4.2 首页收件箱（2.1–2.6）

- 2.1：
  - `NavigationStack` + 大标题“DeepLinks”；`.navigationSubtitle` 显示“● 电脑名 · 在线 / 离线 / 远程”；`toolbarTitleMenu` 放 2.5 的电脑与工作区菜单；右上设置。
  - 列表 plain 样式，三组：等你处理 / 进行中 / 最近，组头吸顶带计数。
  - 行结构（`DLInboxRow`）：第一行 footnote 次要色“● 工作区 · 状态”，右侧时间；第二行标题（body 中粗）；第三行预览（最多 2 行，次要色）；“等你处理”且手机可处理时，显示“拒绝”（`.bordered`）与“允许一次”（`.bordered` + accent tint）。
  - 状态点只给“等你处理”（wait）与“进行中”（accent）。
  - 底部工具栏：左“筛选”`Menu`，中搜索（`DefaultToolbarItem(kind: .search, placement: .bottomBar)`），右“新任务”（唯一品牌实心按钮）。
  - `.refreshable` 下拉刷新。
- 2.2 空态：`DLEmptyState` + “从一件事开始”三条普通行。
- 2.3 离线：列表顶部 `DLBanner`（中性底，只有图标红色）+ “重试 / 连接诊断”；列表保留最后状态，批准按钮禁用。
- 2.4 搜索：`.searchable(text:tokens:)`，工作区作为 token；`.searchSuggestions` 显示最近搜索；结果分“标题匹配 / 内容匹配”，命中词 accent 色。
- 2.5 电脑与工作区：`toolbarTitleMenu` 内两组：“电脑”（当前电脑 + 状态，进入 7.2）、“工作区”（单选 + 添加工作区 + 已归档）。
- 2.6 长按：`contextMenu`：重命名 / 分叉为新会话 / 分享对话 / 归档 / 删除（`.destructive`，二次确认）。
- 左滑：`swipeActions(edge: .trailing)`：归档（灰）、删除（红，二次确认）。**审批不提供滑动。**

### I4.3 对话（4.1–4.9），拆 4 个 PR

**I4.3a 消息流与顶栏**

- 消息流：`UICollectionView` + diffable data source + 自适应高度缓存，包成 `UIViewControllerRepresentable`。
- 消息样式：用户消息靠右浅灰气泡（`secondarySystemFill`）；助手消息全宽无气泡；过程收成 `DLProcessLine`，点开展开为细线串起的步骤（4.6）。
- 流式渲染：增量按帧合并（`CADisplayLink`），新到文字逐段淡入；长 Markdown 分段懒渲染。
- Markdown：标题、列表、表格、引用、代码块（`DLCodeBlock`，高亮）、行内代码；公式与 Mermaid 进锁死的 WKWebView（随包 KaTeX / Mermaid，非持久存储，CSP）。
- 网络图片默认不加载，点按单张才加载，且只允许 HTTPS。
- 顶栏：系统导航栏；标题 + `.navigationSubtitle`（“工作区 · 运行中 · 第 N 步”）；右侧 diff 角标按钮 + ⋯ `Menu`（同一胶囊组，必要时 `ToolbarSpacer`）。
- 轮尾（4.2）：改动卡（最多 3 行 + 查看全部）→ 元信息灰字（模型 · 思考 · 令牌 · 耗时）→ 文字按钮（复制 / 重新生成 / 分享）→ 建议 chip。
- 本地快照秒开：打开会话先显示上次的加密快照（独立 Keychain 密钥，按主机隔离），网络结果回来后整体替换；快照里未结束的审批只显示“状态待确认”，不可提交。
- 性能验收：3000 条消息的会话滚动保持 120fps（ProMotion 机型）/ 60fps；流式输出时 CPU 主线程占用不超过 50%（Instruments 记录附在 PR）。

**I4.3b 状态槽**

- `DLStatusSlot`：断线 > 待处理 > 目标 > 预览，一次只显示一个；平时一行，点开展开（目标标题 + 进度条 + 计划清单，完成项灰字不加删除线）。
- 位置：导航栏下方；内容滚到下面时用 `scrollEdgeEffectStyle(.soft, for: .top)` 过渡；状态槽本身不加玻璃。
- 断线时不禁用发送（发送走 HTTP，与 SSE 无关），与 Android R3.1b 的决定一致。

**I4.3c 输入区与决策栏**

- `DLComposerView`（UIKit）：贴 `keyboardLayoutGuide`；结构：附件缩略图（输入框上方）→ 输入框 → 下方一行（“+”、模型 · 推理 chip、权限 chip、发送 / 停止 / 麦克风）。
- 外层一个玻璃容器（`UIGlassEffect`，或 SwiftUI `GlassEffectContainer` 包装，以 spike 结论为准）；发送键用品牌色实心。
- 草稿按主机落盘；发送途中被系统回收的消息回来后回填，不自动重发。
- `DLDecisionBar`：与输入区同一个容器，用形变动画（`glassEffectID` / UIKit 对应动画）切换；内容：状态行 + 问题 + 命令块（实色底等宽）+ 按钮（左“拒绝”灰色填充 `.bordered`，右“允许一次”`.borderedProminent` + BrandFill；决策栏本身是玻璃，内部元素一律实色，不叠玻璃）；对话内容在决策栏出现时降到约 42% 不透明度；提问：单选 / 多选 / 自己写答案，“上一题 / 跳过（仅可选题）/ 下一题”。
- 只处理手机能处理的最新一条（与 Android R3.1c 规则一致）；批准时 `.sensoryFeedback(.success)`。
- 消息流在决策栏出现时整体降低不透明度（content layer dim），与 4.3 一致。

**I4.3d 轨迹与菜单**

- 4.7 轨迹：push 新页面；搜索 + 筛选 chip + 按轮分组；组头右侧时间次要色。
- 4.9 ⋯ 菜单：`Menu` 两组：查看（改动、文件、轨迹、子代理、用量、预览）/ 操作（目标、定时任务、重命名、分叉、分享）。归档 / 删除只在首页。

### I4.4 新任务（3.1–3.4）

- 3.1 草稿页：push 一个对话页的草稿态；顶部“继续上次的任务”、工作区胶囊（行尾“更多”）、智能体预设 chip；底部复用 `DLComposerView`。
- 3.2 选择工作区：`.sheet` + 搜索；输入绝对路径时多一行“使用 X”。
- 3.3 添加工作区：路径输入 + 最近目录；提交后提示“已提交，等电脑批准”（202 pending 流程）。
- 3.4 智能体预设：`.sheet` 单选 + 说明。

### I4.5 对话里的弹层与对话框（5.1–5.14）

| 编号 | iOS 做法 |
|---|---|
| 5.1 指令面板 | 输入 `/` 后在输入区上方展开列表（同一玻璃容器内），按“智能体 / 会话”分组，边输入边过滤 |
| 5.2 模型与推理 | `.sheet`（`.medium` / `.large`）：模型单选 + 推理等级 `Picker(.segmented)` + “上下文已用 46% · 只影响这个会话” |
| 5.3 / 5.4 权限 | `.sheet` 三项单选；“完全权限”橙色图标与文字；选中时 `confirmationDialog` 二次确认 |
| 5.5 / 5.6 附件 | `.sheet`：拍照（`UIImagePickerController` 相机）/ 相册（`PhotosPicker`）；没有“文件”（协议不支持，与 Android 一致） |
| 5.7 用量 | `.sheet`：大数字 + 上下文进度条 + 键值行 |
| 5.8 子代理 | push 页面：树状列表，状态只用转圈 / “完成”标签 |
| 5.9 分享 | `.sheet`：预览卡 + “分享为图片”（`ImageRenderer` → `ShareLink`）/ “导出为文本” |
| 5.10 重命名 | `alert` + 文本框 |
| 5.11 删除 | `confirmationDialog`，`.destructive` |
| 5.12 定时任务 | push 页面：本会话 / 全部会话 分段；行尾箭头进入编辑（插件没有暂停接口，不做开关） |
| 5.13 编辑目标 | `.sheet` 小表单；“清除目标”红色文字在左下 |
| 5.14 选择文字 | 全屏页面，系统文本选择 |

### I4.6 改动、文件与预览（6.1–6.6）

- 6.1 改动列表：push 页面（从改动卡用 zoom 转场进入：`matchedTransitionSource` + `.navigationTransition(.zoom)`）；顶栏上一轮 / 下一轮；文件行：文件名等宽 + 目录次要色 + 右侧 `+n −m`；底部“就这些改动提问”。
- 6.2 diff：统一视图，增删只用浅色底（ok / err 的 soft 色），词级高亮（`DLCore` 实现，用例与 Android `IntralineDiff` 共用 `testdata/intraline-diff-cases.json`）；底部“上一个 / 下一个 / 就这个文件提问”。
- 6.3 文件浏览：push 页面，面包屑 + 列表（不做树）。
- 6.4 文件预览：图片与文本就地预览，其余用 `QLPreviewController`；底部“复制路径 / 分享（`ShareLink`）/ 引用到对话”。下载文件校验 SHA-256，不一致即丢弃。
- 6.5 预览：手机本机回环代理（`NWListener` 只监听 `127.0.0.1`，路径带随机密钥）把请求经 `HostClient` 转给插件的预览路由；WKWebView 只放行这个回环源，外部链接交给 Safari，退出时清空存储。预览只在前台使用，所以不受后台挂起影响；WebSocket（热更新）由代理逐帧转发。
- 6.6 预览空态：`DLEmptyState`。

### I4.7 设置（7.1–7.15）

- 全部用 `Form`（insetGrouped），照系统“设置”。
- 7.1 首页分组：当前电脑（唯一突出行）/ 通用（语言、通知、外观）/ 智能体（对话默认、模型与余额）/ 其他（会话记录、关于）。
- 7.2 电脑与配对：连接方式三行（局域网 / Tailscale / 中继，各自状态）+ 这台电脑（连接诊断 / 修改名称 / 更换电脑）+ 解除配对（红）。
- 7.3 连接诊断：每项一行，SF Symbol 表示状态，副标题给原因或建议；顶栏“复制结果”（只复制枚举与数字）。
- 7.4 通知：总开关、“需要审批时”、“任务完成时”、Live Activity 开关、“通过官方网关推送”说明行（阶段 6）。
- 7.5 外观：主题（跟随系统 / 浅色 / 深色）。字号跟随系统动态字体，不做 App 内滑杆（平台差异）。
- 7.6 对话默认：新会话的智能体预设、权限、默认模型；运行中再次发送的方式（排队发送 / 引导 / 插话等，选项以插件声明为准）。接口与 Android 相同。（v1.1 补：原文漏写）
- 7.7–7.11 模型与余额、供应商、更换 API 密钥（空输入框，不显示旧密钥）、获取模型、余额提醒：接口与 Android 相同。
- 7.12 会话记录、7.13 关于（版本号、开源许可）、7.14 法律文档、7.15 上次崩溃（MetricKit 诊断，本机查看、用户主动导出）。

### I4.8 iPad 与宽屏

- `NavigationSplitView`：侧栏 = 首页列表；详情 = 对话；改动审查面用 `.inspector`。
- 键盘快捷键：⌘N 新任务、⌘↩ 发送、⌘F 搜索、Esc 关闭弹层。
- 横竖屏、分屏（1/3、1/2、2/3）截图基线。

---

## 阶段 5：远程连接（DLP/1）

### I5.1 帧编解码（`DLRemote`）

- 按 `docs/rfc/0001-dlp1-remote-pipe.md` 实现控制帧与数据帧的编解码、会合密钥与 MAC 校验。
- 跑通 `testdata/dlp1/` 的全部向量（与 JS / Go / Kotlin 同一份）。
- 模糊测试：随机字节输入不崩溃。

### I5.2 Spike：选定实现方案（停下等结论）

两种方案，先各做最小验证：

**方案 A：本地回环桥（优先尝试）**

- 外层：`URLSessionWebSocketTask` 连中继（WSS，外层 TLS 由系统处理，可选外层指纹）。
- 桥：`NWListener` 在 `127.0.0.1:<随机端口>` 监听；每来一条 TCP 连接，就开一条 DLP/1 流，把字节原样双向搬运。
- 内层 TLS：**交给 URLSession 自己做**——App 把远程主机当成 `https://127.0.0.1:<端口>`，证书固定逻辑（I3.4）完全复用。
- 优点：不引入 SwiftNIO；HTTP、SSE、证书固定代码局域网与远程共用一套。
- 待核实：RFC 0001 是否支持一条 WSS 上多条并发流；不支持时需要连接池或改为方案 B。

**方案 B：SwiftNIO 双层管道**

- 一条 NIO 管道：外层 TLS（NIOTransportServices）→ WebSocket 帧 → DLP/1 帧 → 内层 TLS（NIOSSL，固定指纹）→ HTTP/1.1 编解码。
- 需要自己写一个最小 HTTP 客户端与 SSE 解析，不能复用 URLSession。

**Spike 验收**：手机经官方中继连上隔离 host，完成一次 `GET /dsh-link/mobile/bootstrap` 与一次 SSE 订阅 60 秒不断。PR 写明选 A 还是 B 及理由，停下等维护者确认。

### I5.3 远程首配与日常连接

- 二维码 `remote: { e, r, s, p? }`：经中继首配一律 pending，进入 1.5 等待页。
- `bootstrap` 里 `remote` 为对象 = 更新远程能力；`null` = 只清远程字段，保留局域网配对；缺键 = 不动。
- 中继错误码（`UNKNOWN_KEY`、`BAD_MAC` 等）只做提示，不删凭据；删凭据只认插件在内层 TLS 上的明确答复。

### I5.4 选路接入中继

- 主地址 → Tailscale → 中继；`NWPathMonitor` 网络变化时作废缓存；Wi-Fi ↔ 蜂窝切换后 10 秒内恢复。
- 验收（真机）：五次 Wi-Fi ↔ 蜂窝切换都能恢复为“在线 · 远程”；断网再恢复能续传。

---

## 阶段 6：推送网关与通知

### 6.0 总体设计

```text
插件（用户电脑）──① 加密内容 + 封装 token──▶ 推送网关（官方，开源）──② HTTP/2 + JWT──▶ APNs ──③──▶ iPhone（NSE 解密）
手机 ──④ 批准 / 回复（原来的局域网 / Tailscale / 中继）──▶ 插件
```

- 推送通道只有下行；网关没有任何路径能向电脑发命令。
- 网关被攻破的最坏结果：通知丢失或延迟、泄露“某设备在某时刻收到推送”的元数据；看不到内容，伪造不了通知。

### I6.1 RFC 0002（停下做安全审查）

RFC 必须写清以下内容：

**密钥**

| 密钥 | 生成方 | 存放 | 用途 |
|---|---|---|---|
| 网关 HPKE 密钥对（X25519），带 `kid` | 维护者 | 私钥只在网关；公钥在 `GET /v1/keys` 公开，并编进 App | 手机把 APNs token 封装给网关 |
| 端到端内容密钥 `K`（32 字节） | 手机，每台设备一把 | 手机 Keychain（与 NSE 共享 access group）；插件 state（随设备吊销删除） | 加密通知内容 |
| APNs `.p8` | 维护者在 Apple 后台生成 | 只在网关的环境变量 / 0600 文件 | 网关向 APNs 签 JWT |

**封装 token**

- 手机：`sealed = HPKE.Seal(gatewayPub[kid], info="dlpush/1 token", plaintext={apnsToken, env: "sandbox"|"production", bundleId, laToken?})`，用 CryptoKit 的 HPKE（iOS 17+ 可用）。
- 插件只保存 `sealed`，打不开它。
- 网关：用 `kid` 对应的私钥打开；Go 端用 Cloudflare `circl/hpke`（BSD-3）。
- **算法组合（已定，v1）**：RFC 9180 base 模式（`mode_base`，0x00），
  - KEM：DHKEM(X25519, HKDF-SHA256)，`0x0020`
  - KDF：HKDF-SHA256，`0x0001`
  - AEAD：ChaCha20-Poly1305，`0x0003`
  - CryptoKit：`HPKE.Ciphersuite.Curve25519_SHA256_ChachaPoly`（系统预置组合，不自拼）；Go：`hpke.NewSuite(hpke.KEM_X25519_HKDF_SHA256, hpke.KDF_HKDF_SHA256, hpke.AEAD_ChaCha20Poly1305)`。
  - `info` = UTF-8 `"dlpush/1 token"`；`aad` = UTF-8 `"dlpush/1 token|" + kid`；每个上下文只封装一条消息（序号 0）。
  - 网关请求里的 `sealed` 字段是 **JSON 对象**（即下面的线上格式），不是把对象序列化后的字符串；网关遇到字符串直接返回 400。
  - 线上格式：`{"v":1,"kid":"…","enc":"<base64url，32 字节>","ct":"<base64url>"}`，无填充 base64url。
  - 算法组合与 `kid` 绑定：将来换组合（如 CryptoKit 的 X-Wing 后量子组合）只能发新 `kid`，不在同一 `kid` 下协商。网关遇到未知 `kid` 返回 400，不尝试其他组合。
  - 选择理由：两端都有现成实现、RFC 9180 附录 A.2 有该组合的官方测试向量、ChaCha20 在所有 CPU 上都是常数时间。
- **共享测试数据** `testdata/push/hpke/`：
  1. `rfc9180-a2-base.json`：RFC 9180 附录 A.2.1 的官方向量。Go 端完整验证（含固定临时密钥的 seal）；CryptoKit 无法注入临时密钥，只用 `skRm + enc` 验证 open。
  2. `dlpush-v1-*.json`：用本项目的 `info / aad / 线上格式` 由 Go 生成的向量，iOS 端验证 open 得到相同明文。
  3. 反向用例：CI 中 iOS 单测 seal 一条，写入产物，由 Go 测试 open（或在 iOS 测试里内嵌 Go 预先 open 的期望结果）；以及篡改 `enc / ct / aad / kid` 必须失败的负例。

**内容加密**

- 插件：`ct = AES-256-GCM(K, nonce=随机 12 字节, aad="dlpush/1 content|" + deviceId, plaintext=JSON)`。
- 附加数据的域分离：token 封装用 `"dlpush/1 token|"`，内容加密用 `"dlpush/1 content|"`，两者前缀不同，不得共用同一个构造函数；两端各有一条负例测试：用另一种前缀解密必须失败。
- 明文 JSON：`{ v:1, type: "approval"|"question"|"completed"|"failed"|"stopped", sessionId, title, tool?, ts }`。`tool` 只给审批，且锁屏不显示。
- NSE：解密成功 → 替换标题与正文；`ts` 超过 15 分钟的视为过期，显示通用文案；解密失败 → 显示通用文案“DeepLinks 有新的任务动态”。

**网关接口**

- `POST /v1/push`：`{ kid, sealed, kind: "alert"|"la-update"|"la-start"|"la-end", ct, collapseId, priority: "high"|"normal", expiresIn }` → 200 / 400 / 410（token 失效）/ 429（限流）。
- `GET /v1/keys`：当前与上一把公钥（轮换期两把并存）。
- `GET /healthz`：只返回 ok 与版本。
- 无注册接口、无数据库。

**APNs 请求（网关构造）**

- `alert`：`apns-push-type: alert`，`apns-priority: 10`（审批、提问）/ `5`（完成），`apns-collapse-id`，`apns-expiration`；payload：
  `{"aps":{"alert":{"title":"DeepLinks","body":"有新的任务动态"},"mutable-content":1,"thread-id":"<会话哈希>","interruption-level":"time-sensitive"},"e":"<ct base64>","k":"<kid>"}`
  （`time-sensitive` 只给审批与提问，需要 App 开 Time Sensitive Notifications 能力。）
- `la-*`：`apns-push-type: liveactivity`，topic `<bundleId>.push-type.liveactivity`；content-state 只含 `{state, step, startedAt, waitingCount, sessionRef}`，`sessionRef` 是不透明序号，标题由 Widget 从 App Group 本地查。

**防滥用与隐私**

- 网关按 `sha256(sealed)` 在内存里限流：每分钟 10 条、每小时 120 条（可配置）。
- 日志只记计数、状态码、耗时；不记 `sealed`、`ct`、APNs token、IP。
- APNs 返回 410 / `BadDeviceToken`：网关原样回 410，插件删掉该设备的 `sealed`。

**轮换**

- 网关新增密钥对 → `GET /v1/keys` 同时返回新旧两把 → 新版 App 用新 `kid` 封装 → 旧 `kid` 保留至少 90 天再下线。
- `.p8` 怀疑泄露：Apple 后台吊销并重建，网关换环境变量重启，关闭旧连接。

**可自建**

- fork 用自己的 bundle id、`.p8`、网关地址与公钥；App 的“高级”设置允许填自定义网关地址与公钥（Debug / 自编译构建默认可见，官方构建隐藏）。

### I6.2 网关实现（`push/`，Go）

- 依赖：`sideshow/apns2`（MIT，token 认证）、`cloudflare/circl`（HPKE）。
- 结构：`cmd/dlpush`（入口）、`internal/hpke`、`internal/apns`、`internal/limit`、`internal/http`。
- 配置（环境变量）：`DLPUSH_LISTEN`、`DLPUSH_HPKE_KEYS`（kid → 私钥文件路径）、`APNS_KEY_P8_PATH`、`APNS_KEY_ID`、`APNS_TEAM_ID`、`APNS_BUNDLE_ID`、`DLPUSH_RATE_*`。
- 测试：HPKE 向量（与 iOS 共用 `testdata/push/hpke/`）、内容加密向量（`testdata/push/content/`）、限流、APNs 假服务器（HTTP/2）、410 透传、模糊测试。
- 门禁：`gofmt -l . && go vet ./... && go build ./... && go test ./... -race`。

### I6.3 插件推送出口（对 main，维护者安全审查后合并）

- 新文件 `src/push-sink.js`：
  - 订阅插件已有的主机事件差分（P3.2 的 `session/state`）。
  - 某设备**没有**活跃的前台 SSE 连接时，才为它发推送（避免与 App 内通知重复）。
  - 同一会话合并：`collapseId = deviceId + sessionId + type`；同一会话 30 秒内最多一条完成类通知。
  - 失败重试：网络错误指数退避 3 次；410 删除 `sealed`；429 退避。
- 新接口：
  - `POST /dsh-link/mobile/push/register`：`{ gateway, kid, sealed, k, prefs }`（`k` 是端到端密钥，只在固定证书的 HTTPS 上传输）。
  - `DELETE /dsh-link/mobile/push/register`：注销。
  - 写入经设备变更闸门，吊销设备时同步删除该设备的推送数据。
- `pluginCapabilities()` 增加 `push: { v: 1 }`。
- 面板：“手机连接”里每台设备显示“推送：已开启 / 未开启”，可一键关闭。
- 测试：`test/push-sink.test.mjs`（前台抑制、合并、410 清理、吊销清理、密文向量与 iOS 一致、日志不含密钥和 token）。
- 文档：`MOBILE_SYNC_CONTRACT.md` 新增「推送」一节；`COMPATIBILITY.md` 写明需要的插件 / App 版本；`PRIVACY.md` 写明网关能看到与看不到什么。

### I6.4 iOS 推送接入

- App：`registerForRemoteNotifications`；拿到 token 后取网关公钥 → HPKE 封装 → 生成 `K` 存 Keychain → 调插件注册接口。
- 设置 7.4：总开关默认关；打开前一页说明“通知会经过官方推送网关转发，内容端到端加密，网关看不到”。
- 通知分类：`approval`、`question`、`completed`、`failed`；只有“打开”动作，不提供在通知上直接批准。
- 点击：打开对应会话；会话不在本机列表时先刷新再打开，找不到就停在首页并提示。
- 锁屏隐私：`hiddenPreviewsBodyPlaceholder` 设为“DeepLinks · 有新的任务动态”。
- NSE（`Extensions/NotificationService`）：读共享 Keychain 里的 `K` → 解密 → 改写标题正文；不联网；内存与耗时控制在系统限制内。

### I6.5 推送测试（不需要付费）

- 模拟器：`xcrun simctl push <设备> <bundle id> payload.apns` 投递带密文的 payload，验证 NSE 解密、过期处理、解密失败兜底、点击跳转。
- 网关：本地起网关 + APNs 假服务器，插件 → 网关 → 假 APNs 全链路。
- 真实 APNs 送达放到阶段 9 开通账号后验证。

### I6.6 部署（维护者，阶段 9 开通账号后）

1. 部署在维护者的香港服务器（大陆延迟低、可与 APNs 保持 HTTP/2 长连接、无需 ICP 备案）；域名 `push.dshlinks.com`；Caddy 自动签 TLS，反代到 `127.0.0.1:<端口>`。
   - 若中继 `relay.dshlinks.com` 也在这台机器：网关是独立进程与独立 systemd 服务，各自设 `MemoryMax`，健康检查分开，任何一方崩溃不影响另一方。
   - 上线前从大陆家宽与移动网络各测一次 `mtr push.dshlinks.com`。
   - 备选：网关核心逻辑保持“无状态 + 可替换 APNs 发送层”，日后可增加 Cloudflare Workers 版本作为第二地址；现阶段不做。插件发送失败只重试与退避，最终静默放弃，不影响主流程。
2. systemd 服务；`.p8` 与 HPKE 私钥放 `/etc/dlpush/`，权限 0600，属主为服务用户。
3. 监控：`/healthz` 外部探活；APNs 失败率、429 次数告警。
4. 备份：只需要备份 `.p8` 与 HPKE 私钥（离线保存）；网关本身无数据。

---

## 阶段 7：系统层

### I7.1 Live Activity（Widget extension）

- `ActivityAttributes`：静态部分 `{hostRef}`；content-state `{state, step, startedAt, waitingCount, sessionRef}`。
- 锁屏：会话名（从 App Group 本地查 `sessionRef`）、状态、第几步、计时器（`Text(timerInterval:)`）。
- 灵动岛：收起时左图标、右状态；展开时显示“需要审批 · 在 App 中处理”。
- 生命周期：App 在前台时本地更新；离开 App 后由插件 → 网关推送更新（`la-update`）；全部结束后结束活动，锁屏保留 60 秒。
- 远程启动（`la-start`，push-to-start token）：只在出现审批 / 提问且没有现成活动时使用。
- 设置 7.4 有 Live Activity 开关；关掉时结束所有活动并注销 push-to-start token。

### I7.2 分享扩展（8.3）

- 从系统分享进来的文本与图片：扩展只写一份“收件条目”到 App Group，然后用 `deeplinks://share/<id>` 唤起 App。
- App 弹 `SharePickerSheet`（新任务 + 最近 6 个会话），选择后预填输入框，**不发送**。
- 扩展不联网、不读 Keychain；App 定期把“最近会话标题列表”写入 App Group 快照（不含凭据）。

### I7.3 前后台策略

- 前台：SSE 实时；后台：立即断开 SSE，靠推送。
- 用户点了发送后切后台：申请一小段后台时间（`beginBackgroundTask`）等发送请求完成；不做保活，不用 `BGContinuedProcessingTask`。
- 回到前台：先显示本地快照，再重连补发。

---

## 阶段 8：质量加固

- **辅助功能**：VoiceOver 全流程（配对 → 首页 → 对话 → 审批）；触控 ≥ 44pt；降低透明度、增强对比度、粗体文本、最大辅助字号下无截断与重叠。
- **性能**：冷启动到首页 < 1 秒（有快照）；3000 条消息会话滚动不掉帧；流式输出 10 分钟内存不持续上涨。
- **稳定性**：断网、证书变化、token 吊销、电脑重启、插件升级五种情况都有明确提示且不崩溃。
- **隐私核对**：网络图片默认不加载；日志脱敏（Release 不打印 token、路径、消息正文）；敏感页面在多任务切换时模糊（`scenePhase` inactive 时覆盖一层）。
- **本地化**：中英文逐页人工过一遍；格式符检查脚本通过。
- **隐私清单**：补齐 `PrivacyInfo.xcprivacy` 的全部“需说明理由的 API”。
- **真机验收清单**（写进 `docs/RC1_CLOSED_BETA_TEST_PLAN.md` 的 iOS 部分）：局域网冷启动 10/10、前后台切换 10/10、Wi-Fi ↔ 蜂窝 5/5、远程首配、审批、提问、推送四类送达、Live Activity、吊销后行为、48 小时长跑。

---

## 阶段 9：分发

### I9.1 开通 Apple Developer Program（维护者）

1. 用个人 Apple ID 在 developer.apple.com 加入（个人，99 美元 / 年，国区 688 元 / 年）。需要开启双重认证。
2. 确定正式 bundle id，例如 `dev.deeplinks.ios`；扩展分别为 `.notification-service`、`.live-activity`、`.share`。
3. 在 Certificates, Identifiers & Profiles 创建上述 App ID，打开能力：Push Notifications、App Groups（`group.dev.deeplinks.ios`）、Time Sensitive Notifications、Keychain Sharing（access group）。
4. Keys → 新建 APNs 认证密钥（.p8），记录 Key ID 与 Team ID；**.p8 只能下载一次**，立即离线备份。
5. Xcode 使用自动签名，选择付费团队。

### I9.2 上线网关

- 按 I6.6 部署；用真机验证：审批 / 提问 / 完成 / 失败四类推送送达、NSE 解密、点击跳转、Live Activity 更新与远程启动、注销与吊销后不再收到。

### I9.3 App Store Connect

1. 新建 App 记录：名称“DeepLinks”（**名称与副标题不出现 DeepSeek**），主语言简体中文，加英文。
2. 隐私营养标签：如实填写（不收集数据；推送 token 仅用于发送通知）。
3. 出口合规：在问卷中如实回答（使用系统 HTTPS 与 CryptoKit 标准算法）；结论以问卷结果为准，必要时在 `Info.plist` 设置 `ITSAppUsesNonExemptEncryption`。
4. 隐私政策 URL：指向仓库 `PRIVACY.md` 的网页版本。

### I9.4 TestFlight

1. 内部测试（最多 100 人，无需审核）：维护者与核心测试者先用 1–2 周。
2. 外部测试（需 Beta 审核）：
   - 审核备注写明：这是 DeepSeek Harness 的配套客户端；需要用户自己的电脑；提供“先看看演示”入口与演示视频链接；说明它是原生 API 客户端，不镜像屏幕，不属于 4.2.7 远程桌面类。
   - 公开链接放到 README（“iOS 测试版”）。
3. CI：推到 main 且 iOS 版本号变化时，由维护者手动触发上传 TestFlight 的工作流（签名用 App Store Connect API Key，存 GitHub Secrets）。

### I9.5 App Store 上架（海外）

- 截图：6.9 英寸与 6.5 英寸 iPhone、13 英寸 iPad；用演示数据生成，浅色 / 深色各一套。
- 描述：可写“兼容 DeepSeek Harness”，但不用对方商标做标题或图标元素。
- 国区：需要先完成 App 备案，另行立项。

### I9.6 文档与发布

- `RELEASING.md` 增加 iOS 一节（版本号规则、TestFlight 流程、核对清单）。
- `COMPATIBILITY.md` 增加 iOS 列与网关版本。
- README 增加 iOS 截图与 TestFlight 链接。

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| 审批钩子在当前 DSH 不触发 | 阶段 0 先验证；不通过就停 |
| DLP/1 在 iOS 上实现成本过高 | 阶段 5 先 spike，方案 A（回环桥）优先；失败再方案 B |
| 两端行为漂移 | 共享测试向量与合同 fixtures；改手机 API 必须先改合同 |
| Android v4 还在收尾，iOS 跟着返工 | v4 功能冻结；iOS 只对齐已合入 main 的页面；差异写执行记录 |
| `safeAreaBar` 里输入框焦点问题 | 输入区用 UIKit + `keyboardLayoutGuide` |
| iOS 27 玻璃外观变化 | 只用系统组件与少量 `glassEffect`；截图矩阵覆盖 iOS 26 与 27 |
| 审核员没有电脑可连 | 演示模式 + 演示视频 + 审核备注 |
| 推送网关成为中心化风险 | 无状态、端到端加密、开源、默认关闭、可自建；PRIVACY.md 写清边界 |
| `.p8` 泄露 | 离线备份、只放网关、吊销重建流程写进 RFC |
| macOS CI 成本 | self-hosted runner；只在改动 iOS 时跑 |
| 许可证污染（AGPL） | 红线禁止复制；新依赖 PR 写明许可证 |

---

## 11. 执行记录

| 子项 | PR | 结论 / 偏差 |
|---|---|---|
| I0.0 | #65 | 方案原文放入 `docs/ios/PLAN.md`，顶部状态改为已采纳（2026-10-03），其余未改。PR 目标 `ios/main`。Node gates、Go gates、DLP/1 end to end 全绿；Android 工作流不触发。squash 合并为 `1cd0b5d`。 |
| I0.1 | #66（合入 main） | **触发**。DSH `0.1.7-alpha.1` 上 `approval/request` 与 `user-questions/request` 均被调用；手机可提交「允许一次」与澄清答案。默认 `workspace-write` 下工作区写入约 45 秒无审批；`read-only` 下 `write` 稳定触发。结论写入 main 的 `docs/COMPATIBILITY.md`。 |
| I0.0b | #67 | PLAN 升级到已采纳的 v1.1；保留 I0.0 记录并写入 I0.1 结论。 |
| I1.5a | #68 | 设计稿原样入库 `apps/ios/docs/design/`（PNG + README + src）。已知冲突：1.2/1.3 设计稿有「输入配对码」，以 PLAN 为准去掉。 |
| I1.1 | #69 | 新增 `apps/ios/docs/visual-rules-ios.md`（72 行）。 |
| I1.2 | #70 | 新增 `apps/ios/docs/page-mapping.md`；写明去掉 1.2/1.3「输入配对码」。 |
| I1.3 | #71 | 新增 `docs/rfc/0002-push-gateway.md`（DLPUSH/1 草案，待安全审查）。 |
| I1.4 | #72 | 新增 `apps/ios/AGENTS.md`（含 I1.5a 设计稿用法约束）。阶段 1 文档完成，停下等审核。 |
| — | #73（合入 main） | PLAN 升为已采纳 v1.2；设计稿整体替换（去掉输入配对码）；随后 merge 进 `ios/main`（`4e6cf1f`）。 |
| I1.3 | #74 | 按 PLAN v1.2 修订 RFC 0002：锁定 HPKE 套件与线上格式；新增 `testdata/push/hpke/` 占位与说明。 |
| I1.2 | #75 | `page-mapping.md` 补 7.6 对话默认；冲突清单标注输入配对码已与设计稿对齐。停下等审核四份文档与 BrandFill。 |
| I1.3 | #76 | PLAN 升为已采纳 v1.3；RFC 0002：`sealed` 仅对象、AAD 域分离 `token|`/`content|`；新增 `testdata/push/content/` 占位。 |
| I2.1 | #77 | 工程骨架：`project.yml`、空 `NavigationStack`、说明文案与隐私清单、三个扩展占位、六个本地包。CI `iOS build`（macos-26，Debug）通过。 |
| I2.2 | #78 | 三条工作流：`ci-ios.yml`（Debug+Release 构建、单测、空页面截图校验、swift-format）、`ios-regen-screenshots.yml`（空页面基线）、`ci-push.yml`（仅 `push/`）。PR 上三个检查为 iOS build、iOS unit tests、iOS screenshot check。squash `37692d3`。 |
| I2.3 | #79 | 主题 token：`AccentColor`、`BrandFill`（深色暂定 `#4C66E6`，只在该色集）、`DLColor`、`DLFont`、`TokenUsageTests`。三个 iOS 检查通过。squash `403a646`。 |
| I2.4 | #80 | 基础组件：收件箱行、状态槽、输入区、决策栏（同一玻璃容器切换）、胶囊、过程行、代码块、空态、横幅；各有浅色 / 深色 / 长文本 / 禁用截图。三个 iOS 检查通过。squash `afb9646`。 |
| I2.5 | #81 | Debug 场景目录在设置占位底部；欢迎页 “Try the demo” 进入离线演示。数据在 `App/Demo/fixtures/components.json`，Release 也带。截图与场景共用工厂。squash `b750725`。 |
| I2.6 | #82 | `Localizable.xcstrings`（英文 + 简体中文，语义 key）与 `scripts/check-ios-locales.mjs`（key 对齐、格式符类型一致）。三个 iOS 检查通过。squash `8a6c8a2`。 |
| I3.1 | #83 | DLModels：按附录 C 写各响应的 `Codable` 类型（15 个主题文件），未知字段忽略、枚举 `unknown` 兜底、`remote` 三态；DLCore 文本兜底（DLCore → DLModels）；合同测试 `ContextInjectionContractTests`、`MobileModelsContractTests`。偏差：部分字段按 `src/` 推断（见 PR）；`relay: null` 不建模；history `goal` 未区分缺失与 null。三个 iOS 检查通过。squash `8e212cf`。 |
| I3.2 | #84、#85（合入 main）、#86 | 插件侧：`scripts/export-contract-fixtures.mjs` 用假 Host 跑真实插件，生成 29 个 `testdata/mobile-contract/*.json`；`test/contract-fixtures.test.mjs` 在响应结构变了而 fixtures 没更新时失败。#85 修正假 Host 里透传投影的形状（stats / goal / todos 按 Android 解析）。main 两次 merge 进 `ios/main`（`dd56ff8`、`9a3fe06`）。iOS 侧：`MobileContractFixturesTests` 逐个解码，文件集合必须与对照表一致；`SessionGoal` 改按真实的嵌套形状解码。维护者授权自行审核合并。squash `1a372ed` / `4905b45`（main），`addd120`（ios/main）。 |


---

## 附录 A：v4 页面 ↔ iOS 组件对照表

### A.1 页面

| v4 | 页面 | iOS 实现 | 与 Android 的差异 |
|---|---|---|---|
| 1.1 | 启动 | 系统 Launch Screen | — |
| 1.2 | 欢迎 / 未配对 | 全屏页 + `.glassProminent` 主按钮 + `PhotosPicker` | 多“先看看演示” |
| 1.3 | 扫码 | `DataScannerViewController` | — |
| 1.4 | 输入配对码 | 不做 | 同 Android |
| 1.5 | 等电脑批准 | push 页面，轮询 | — |
| 1.6 | 配对失败 | push 页面 | — |
| 2.1 | 首页收件箱 | `NavigationStack` + plain `List` + 底部工具栏 | 无 FAB；搜索在底部 |
| 2.2 | 空态 | `ContentUnavailableView` | — |
| 2.3 | 离线 | 内容层 `DLBanner` | — |
| 2.4 | 搜索 | `.searchable` + token + 建议 | 工作区用 token |
| 2.5 | 电脑与工作区 | `toolbarTitleMenu` | 菜单代替弹层 |
| 2.6 | 长按 | `contextMenu` + 左滑 | 多左滑 |
| 3.1–3.4 | 新任务 | 草稿态对话页 + `.sheet` | — |
| 4.1–4.2 | 对话 | `UICollectionView` + 系统导航栏 | — |
| 4.3–4.4 | 审批 / 提问 | `DLDecisionBar` 玻璃形变 | — |
| 4.5 / 4.8 | 状态槽 | `DLStatusSlot` + 滚动边缘效果 | — |
| 4.6 | 工具过程 | `DLProcessLine` 展开 | — |
| 4.7 | 轨迹 | push 页面 | — |
| 4.9 | ⋯ 菜单 | `Menu` | 菜单代替弹层 |
| 5.1–5.14 | 弹层与对话框 | 见 I4.5 表 | — |
| 6.1–6.6 | 改动 / 文件 / 预览 | push 页面 + zoom 转场 + QuickLook | — |
| 7.1–7.15 | 设置 | `Form` | 字号跟随系统，无滑杆 |
| 8.1–8.2 | 通知 | 通知分类 + NSE + Live Activity | 多 Live Activity；无通知栏批准 |
| 8.3 | 分享进来 | 分享扩展 + `SharePickerSheet` | — |

### A.2 组件

| Android（`native/ui/v4/`） | iOS |
|---|---|
| `DlTopBar` | 系统导航栏 + `.navigationSubtitle` + toolbar |
| `DlListRow` | `List` / `Form` 行（系统） |
| `DlSectionHeader` | `Section` 标题（系统） |
| `DlStatusSlot` | `DLStatusSlot` |
| `DlInboxItem` | `DLInboxRow` |
| `DlComposer` | `DLComposerView`（UIKit） |
| `DlDecisionBar` | `DLDecisionBar` |
| `DlBottomSheet` | `.sheet` + `presentationDetents` |
| `DlDialog` | `alert` / `confirmationDialog` |
| `DlChip` / `DlSegmented` | `DLChip` / `Picker(.segmented)` |

---

## 附录 B：玻璃使用规则

**允许**

- 系统导航栏、工具栏、`.sheet`、`Menu`、`alert`、`confirmationDialog`（自动带玻璃，不额外处理）。
- 自定义玻璃只有两处：输入区（`DLComposerView`）与决策栏（`DLDecisionBar`），共用一个容器。
- 浮在相机画面上的关闭按钮。
- 按钮样式：独立浮在内容上的主操作用 `.glassProminent`（BrandFill），次操作用 `.glass`；**放在玻璃容器内部**（输入区、决策栏）的按钮用 `.borderedProminent`（BrandFill）/ `.bordered` 实色，不叠玻璃。
- 同屏最多一个品牌实心按钮：首页给“新任务”，列表里的“允许一次”用 `.bordered` + accent tint。

**禁止**

- 消息气泡、卡片、列表行、代码块、diff、横幅、状态槽使用玻璃。
- 玻璃里再套玻璃。
- 自己写模糊或半透明背景去模仿玻璃。
- 依赖玻璃的具体透明度。必须在以下设置下都清晰可用：降低透明度、增强对比度、iOS 27 玻璃着色滑杆的两端。

---

## 附录 C：iOS 要实现的手机 API 清单

以 `docs/MOBILE_SYNC_CONTRACT.md` 为准，iOS 首版需要：

| 接口 | 用途 | 阶段 |
|---|---|---|
| `POST /dsh-link/pair` | 配对、替换、pending | I3.8 |
| `GET /dsh-link/mobile/bootstrap` | 能力协商、会话、归档集合、远程能力 | I3.5 |
| `GET /dsh-link/mobile/sessions`、`/sessions/search` | 首页、搜索 | I4.2 |
| `GET /dsh-link/mobile/sessions/:id/history` | 历史快照（含 `kind`、`stats`） | I4.3a |
| 会话 SSE（含 `caps=sync2,multiQuestion,requestState`） | 实时流、续传、`resync-required` | I3.5 |
| `GET /dsh-link/mobile/events` | 主机事件（前台首页状态） | I4.2 |
| `POST .../prompt`、取消、排队编辑 | 发送与控制 | I4.3c |
| `GET .../requests`、审批与提问提交 | 决策栏 | I4.3c |
| `GET .../changes`、`/changes/diff` | 改动与 diff | I4.6 |
| `GET .../file`、`/tree` | 文件浏览与下载 | I4.6 |
| `GET /dsh-link/mobile/previews`、`/preview/:id/*`、`/preview-detections` | 预览 | I4.6 |
| `POST /dsh-link/mobile/workspaces` | 添加工作区（202 pending） | I4.4 |
| `GET /dsh-link/mobile/balance`、`/providers` 及写接口 | 模型与余额 | I4.7 |
| `GET /dsh-link/mobile/diagnostics` | 连接诊断 | I4.7 |
| `GET /dsh-link/mobile/devices`、`POST /dsh-link/mobile/revoke` | 本机设备与解除配对 | I4.7 |
| `POST/DELETE /dsh-link/mobile/push/register` | 推送（新增） | I6.3 |

---

## 附录 D：维护者需要亲自完成的事项清单

| 时间点 | 事项 |
|---|---|
| 阶段 0 | 确认 I0.1 结论；装好 Xcode、XcodeGen；免费 Apple ID 登录；iPhone 开开发者模式 |
| 阶段 1 | 审核 4 份文档；在 iPhone 上全屏看 I1.5a 设计稿；定深色 BrandFill 色值 |
| 阶段 2 | I1.5b：对照模拟器截图，真机抽查玻璃可读性；（可选）把 Mac 注册为 self-hosted runner |
| 阶段 5 | 确认 DLP/1 spike 选 A 还是 B |
| 阶段 6 | 审核 RFC 0002 与插件推送 PR；生成网关 HPKE 密钥对 |
| 阶段 9 | 开通开发者账号；建 App ID 与能力；生成 `.p8` 并离线备份；部署网关；建 App Store Connect 记录；提交 TestFlight 与审核 |
| 全程 | 合并 `ios/main → main`；处理所有“停下等人”的点 |
