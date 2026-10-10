# cetus 交接文档（给接手的 agent：Codex / Claude Code 等）

更新：2026-10-11（Asia/Shanghai）。写给下一个接手这个仓库的 agent。先读完本文再动手。

## 1. 项目目标与命名

- **产品名 cetus**（界面显示名一律小写 `cetus`；`Cetus` 只用于工程标识：project / target / scheme / test target）。仓库 `lunaship/dsh-cetus`。
- 前身是 **dsh-links / DeepLinks**（DeepSeek Harness 的手机伴侣）。改名规则见 `docs/REBRAND_CETUS.md`：协议常量、持久身份、历史兼容位置**保留旧字串是必要的**，不要求「仓库搜旧名零结果」。
- 组成：DSH 插件（`src/`，Node）、Android App（`apps/android/`）、iOS App（`apps/ios/`，SwiftUI + UIKit，iOS 26 Liquid Glass）、中继（`relay/`）、推送网关（`push/`）。
- 目标：手机上配对电脑上的 DSH，看会话、审批、回答提问、看改动、发新任务；局域网优先，Tailscale / Relay 远程；锁屏 Live Activity。

## 2. 方案红线（不可违反）

1. **不在真实 stateDir 冒烟**：插件测试 / 冒烟一律用临时目录，不碰用户真实 DSH 状态目录。
2. **不吊销真实设备**：不调用真实配对设备的吊销 / 删除。
3. **不全局替换协议常量**：`dsh-link`、`/dsh-link/mobile/...`、`DLPUSH/1`、Bundle ID `dev.deeplinks.*` 等是协议 / 持久身份，改名时只改显示名。
4. **不破坏 v1 协议与持久身份**：不改 v1 请求 / 响应形状、APNs payload（顶层只有 `aps` / `e` / `k`，见 RFC 0002 §5.6）、Live Activity `content-state` 五个字段、配对身份与钥匙串键名。新增能力用新增可选字段 / 新接口。
5. 不放宽截图容差、不跳过测试、不提交本地生成的截图基线（基线只由 CI 生成）。

## 3. 用户原则

- **iOS 视觉与交互以 iOS 设计稿为准**（`apps/ios/docs/design/`，HTML 近似稿，只定布局与层级；取值看 `apps/ios/docs/visual-rules-ios.md`）。**行为语义跨端一致**（协议、状态机、草稿 / 审批 / 提问规则 iOS 与 Android 一样）。
- **合并到 main 前先给用户过目**：PR 可以开、可以推、可以跑 CI，**不要自己合并**，不要直接推 main。
- 以下事项**由用户本人做**，agent 不做、不冒充做过：真机验收、APNs、Relay 部署、Apple 账号 / 签名 / TestFlight、DNS（Cloudflare）、24 小时 soak。
- 用户已拍板、不必再问的设计决定（2026-10-10）：锁屏 / 灵动岛显示任务标题；首页副标题不重复电脑名；深色 BrandFill `#4C66E6`；欢迎页按 iOS 设计稿。
- 与用户沟通用中文。不重命名 GitHub 账号（`lunaship`）。

## 4. 仓库文档索引

| 文件 | 内容 |
|---|---|
| `docs/cetus/DELIVERY.md` | 整体改造进度与交付证据（只写有证据的部分） |
| `docs/cetus/ACCEPTANCE-T01-T46.md` | T01–T46 验收项与状态 |
| `docs/cetus/C06-GAP.md` … `C16-GAP.md` | 各合同（C06–C16）的差距审计：已满足 / 部分 / 缺失 + 文件:行证据 |
| `docs/cetus/C12-CONTRACT.md`、`C12-LIVE-ACTIVITY-WIRING.md` | 推送 / Live Activity 合同与接线 |
| `docs/cetus/N-GAP.md` | N01–N05 三端差距（命名合同等） |
| `docs/cetus/DEVICE-CHECKLIST.md` | 真机验收手册（用户执行） |
| `apps/ios/docs/visual-rules-ios.md` | iOS 视觉规则（先改合同再改实现） |
| `apps/ios/docs/design/` | iOS 设计稿 PNG + README（设计决定、颜色、字号） |
| `apps/ios/docs/page-mapping.md` | 页面 / 组件 / 截图名映射 |
| `docs/rfc/0002-push-gateway.md` | 推送网关 RFC（§5.6 payload 与锁屏策略） |
| `docs/MOBILE_SYNC_CONTRACT.md` | 手机同步合同 |
| `docs/REBRAND_CETUS.md` | 命名与 legacy 白名单 |
| `AGENTS.md`、`apps/ios/AGENTS.md`、`CLAUDE.md` | agent 工作规则 |

## 5. PR 状态

- 已合并：**#192** 换上 Cetus 鲸鱼线稿 logo（`12618a1`）；**#193** README 顶部换横版 logo（`5ec484e`）；**#194** 打开长会话自动贴底 + 性能 CI 失败诊断注解（`c220824`）；**#195** iOS 按设计稿对齐 UI（P0 + 低风险 P1，`3d38e0d`）；**#198** 进入会话显示真实模型名（`02231895`）；**#199** 设置页电脑状态/余额/会话数（`a44785d`，⚠️ 合并时 iOS CI 实为红，见 §8）。
- 待用户确认合并（建议顺序）：**#203** 修 main 的 iOS 编译错误 + 重生成 #199 漏掉的 `testHome` 截图基线（**应最先合并**，后两个 PR 的截图检查依赖它）；**#197** P0 停止按钮（本地全绿，CI 除截图基线外全绿，合并 #203 后重跑即绿）；**#201** 6.2 生产路由接 DiffPage（同上）；**#200** 本 PR（HANDOFF 更新）；**#202** ISSUES.md（全绿，但第 1/2 条的「阻塞中」已过时）。

## 6. CI 经验（踩过的坑）

- **截图基线重生成**：`ios-regen-screenshots.yml` 用 `workflow_dispatch`、`ref=<PR 分支>` 触发，在**分支头**上跑并由 bot 提交新基线；而 PR 的 screenshot check 在 **merge 引用**（PR 分支 + main）上跑。所以分支落后 main 时，基线和对比用的代码不一样。
- **与 main 冲突时的重放法**：PNG 基线冲突不要手工合并。基于新 main 新建提交，只重放**代码文件**（不带 PNG），再 dispatch 重生成基线。
- **bot 提交的 CI 是 `action_required`**：bot 推的基线提交触发的 PR 工作流需要人工批准，用 `POST /repos/lunaship/dsh-cetus/actions/runs/<id>/rerun`（workflow_run_rerun）重跑即可。
- **失败用例注解与失败图**：`ci-ios.yml` 失败时把 xcresult 里的失败用例变成 `::error` 注解；SnapshotTesting 的失败图不在 xcresult 里，在模拟器 App 的 `tmp/<套件>/`，CI 会收集并上传为 artifact `ios-screenshot-failures-<attempt>`。作业日志：`GET /repos/lunaship/dsh-cetus/actions/jobs/<id>/logs`。
- **SnapshotSettle**（`apps/ios/Tests/SnapshotSettle.swift`）：每张截图先用同样内容挂一次窗口拍一张丢掉，再正式拍；等到连续两帧逐字节相同；每帧前 `endEditing`。宽屏套件第一个截图前额外预热一张（进程里第一次挂宽屏窗口时系统会做一次性场景更新，侧栏阴影不同）。
- **main 原有的偶发截图**（不是 #195 引入）：`TrajectorySnapshotTests.testTrace`（`4_7_trace_dark_en.default`，main b92c190 job 113632955894 等，数值完全相同 0.99464035 / 0.9573047）、`ChatSheetSnapshotTests.testGoal`（`5_13_goal_dark_zh.large`，玻璃「保存」按钮差 1 色阶）、`NewTaskSnapshotTests.testWorkspace`（`3_2_workspace` 浅色大字号，系统搜索框清除按钮时有时无；main 6ae1f15 job 113821055282 第 1 次失败重跑通过）。前两个经 SnapshotSettle 后未再出现；第三个在 #195 里把截图路径的 `.searchable` 换成实色静态替身解决。
- **系统玻璃控件不适合整页截图**：`.glassProminent`、系统导航栏 / 底部工具栏、`.searchable` 在截图里可能透明、取色不稳或状态不定。做法：截图路径（`staticSnapshot`）画实色替身，生产路径不变；在 PR 正文「截图路径 ≠ 生产路径」一节登记。
- **`pull_request` 的 `paths` 过滤按整个 PR 的改动判断**，不是按最新一次提交：PR 里只要有 `apps/ios/**` 的改动，哪怕新提交只改文档，iOS 工作流也会在新头上全部重跑（截图检查要重新过）。所以文档类提交尽量和代码一起提交，或放在最后一轮 CI 之前。
- **macOS runner 排队慢**：一轮（build + unit + screenshot + e2e + performance + regen）常要 20–40 分钟，排队可能更久。轮询用后台脚本，单条命令不超过 10 分钟。
- **swift-format**：CI 跑 `swift-format lint --strict --recursive --configuration .swift-format .`（在 `apps/ios/`）。提交前对改动文件先 `format -i` 再 `lint --strict`。
- 本地沙箱没有 Xcode / macOS，无法编译 iOS；只能靠 CI。

## 7. 剩余任务（按优先级）

### P0
1. **停止当前轮次（4.1「停止」按钮）**。✅ **已完成**（PR #197，已修好待合并）。
   - 验收：运行中输入区主按钮变「停止」，点了之后当前轮次停止、会话显示 `interrupted / stopped`，读屏「停止 / Stop」；不影响排队消息；iOS 与 Android 语义一致；单测 + e2e。
   - **`session.cancel` 语义已核实：只中断当前轮次，不取消整个会话**。依据是 DSH host 源码 `@deepseek-ai/dsh-api-session-controller`（本机 `/Applications/DeepSeek Harness.app` 内 app.asar）`lib/index.js` `cancel(request)`：取消的是「该会话当前活跃的 Agent turn」，`agent.cancel({ kind: "user" }, { keepInbox: true })`，排队消息保留。
   - 实现：`ConversationLiveService.cancelTurn` POST `/dsh-link/mobile/sessions/:id/cancel`；`DLComposerView` 运行中主按钮变「■ 停止」（读屏 `stopTitle`）；`reduceStoppedReason` 从 `turn/end` 的 `reason.kind` 提取停止原因（与插件 `deriveStoppedReason` 对齐）。
   - 之前阻塞的根因（两个都已修）：① 巨大的 SwiftUI body 加上新增 `.alert` 后超出 Swift 类型检查预算（CI 报 `unable to type-check in reasonable time`）——把 `ComposerInset` 内容抽成 `composerOverlay`、草稿恢复抽成 `restoreDraftsOnAppear`；② `ConversationBar` 调用处 `isRunning/onStop` 实参顺序与 memberwise 声明不符。

### P1
2. **显示真实模型**（4.1 模型胶囊打开面板前只显示「模型」）。✅ **已完成**（PR #198，2026-10-10 合并）。
   - 验收：进入会话即显示当前模型名；切换后即时更新；拿不到时显示中性说明，不填假值。
   - 实现：`ConversationModel.serviceCurrentModel()` 取 `GET /dsh-link/mobile/models` 的 `current`；`ConversationPage` 在 `.task` 中自动填入胶囊（手动选择保留）。
3. **设置页电脑状态 / 余额 / 会话数**（7.1 右侧值）。✅ **已完成**（PR #199，2026-10-10 合并）。
   - 验收：电脑行一行「● 在线 · 局域网」/「○ 离线」（拿不到路线不显示）；「模型与余额」显示余额；「会话记录」显示归档会话数；拿不到就不显示值。
   - 实现：`computerStatusLine()` 单行格式；`SettingsModelsModel.loadBalance(locale:)` 轻量取余额；`archivedCount` 由首页传入。
   - 注：Tailscale 备用状态首页拿不到，未显示（原验收中的「Tailscale 备用」暂略）。
4. **6.1 范围分段 / 6.2 生产路由段级提问**。6.2 部分：✅ **已完成**（PR #201，已修好待合并）；6.1 部分仍阻塞（见 ISSUES.md 第 3 条）。
   - 验收：6.1 分段控件「本轮 / 整个会话 / 未提交」有真实数据才显示；6.2 生产路径接上 `DiffPage` 路由，「就这段提问」引用具体段。
   - 6.2 实现：`FileDiffDetailView`（`ConversationPage.swift`）在导航栈里用 `DiffPage` 呈现单文件 diff，`onAskHunk(文件下标, 段下标)` 把「就这段提问」引用写进草稿；文件列表 → 详情由 `diffFileIndex` binding 驱动。之前阻塞的根因只是 swift-format 缩进（已修）。
   - 6.1 阻塞原因：DSH Host 的 `workspaceChanges` 只存按轮次的数据，插件无「整个会话聚合」与「未提交（git）」数据源，需要 Host 侧新增可选接口后才能做。
5. **Live Activity 剩余排版与点击**（8.4 / 8.5）。
   - 验收：按设计稿：App 图标 + 名称 + 状态胶囊、标题、`工作区 · 第 N 步`、计时、进度条（只在有真实进度时画）；灵动岛紧凑 / 最小 / 展开态；展开态「打开 App 审批」；点击深链到对应会话；任何位置都没有「允许」按钮；锁屏文案中英文。8.5 必须用有灵动岛的设备或模拟器验收（iPhone 13 没有灵动岛）。
   - 线索：#195 已把标题放进 `ActivityAttributes.title`（启动时固定）；`content-state` 不能加字段；锁屏文案目前在扩展里写死中文；`LiveActivityBundle.swift` 没有 `widgetURL`。
6. **C16 24h soak 脚本与记录模板**。
   - 验收：仓库有可重复的 soak 脚本（临时 stateDir、不碰真实设备）和记录模板（重连、资源增长、任务状态、通知去重与恢复、提交与 fixture 可追踪）；**实际 24h 运行由用户做**。
   - 线索：`docs/cetus/C16-GAP.md` R6 / G3。
7. **`docs/cetus/ISSUES.md`**：已知问题登记。✅ 已建（PR #202，全绿待合并）；注意其中第 1、2 条写的「#197/#201 阻塞中，等 CI 日志」已过时（两 PR 均已修好），第 1 条的编译根因见本节第 1 项。

### P2
8. **README 安装与发布说明**：验收：iOS / Android 安装（TestFlight / APK 签名校验）、插件安装、发布流程与 `RELEASING.md` 一致。线索：README「快速开始」「发布」两节。
9. **仓库描述去掉 DeepLinks**：当前 GitHub 描述「Android companion for DeepSeek Harness … DeepLinks Relay (private testing).」。验收：描述与 topics 用 cetus 命名、提到 iOS。改仓库设置前先问用户。
10. **iOS 版本号 / 构建号可追溯**：现在 `apps/ios/project.yml` 写死 `MARKETING_VERSION 1.0`、`CURRENT_PROJECT_VERSION 1`（1.0(1)）。验收：构建号来自 CI（如 run number 或提交数），关于页能对上提交；与 Android 版本方案一致；不破坏 Bundle ID。
11. **发布与维护者材料**：验收：CHANGELOG、隐私说明、App Store / TestFlight 文案与截图清单、维护者检查表齐全；签名 / 上传由用户做。

## 8. 当前状态快照（2026-10-11 凌晨，用户 Mac 本地执行后更新）

- `main` = `a44785d`：**iOS CI 实际是红的**。#199 squash 合并把 `InboxPage.swift:920` 调用处 `SettingsHomePage(...)` 的 `archivedCount:` 放在了 `push:` 前（memberwise 参数顺序错误），iOS build / 单测 / 截图 / e2e / performance 全挂；Node / Go / DLP/1 e2e 绿。修复在 **PR #203**（只交换实参顺序），并顺带由 CI 重生成 #199 漏掉的 `testHome` 基线（10 张，电脑行从「地址+在线」改为「● 在线」是有意视觉变化）。
- PR #197（P0 停止按钮）：已 rebase 到 #203 之上（含新基线），修好两个根因（见 §7 第 1 项），本地全绿（Debug/Release 构建、全部单测含新用例、e2e `testStopTurn` 真跑通过、performance 无回归）；**GitHub CI 全绿**（经历两次基础设施偶发重跑：`WideSnapshotTests testLandscape`、已知偶发 `testGoal large`）。
- PR #201（6.2 DiffPage 路由）：已 rebase 到 #203 之上，修好 swift-format，本地全绿（同上）；**GitHub CI 全绿**（performance 经历两次 runner 偶发重跑：App 启动超时 / `Received unexpected number of metrics`，均非回归）。
- 建议合并顺序：**#203 → #197 → #201**，然后 #200 / #202（四者 GitHub CI 均已全绿；若日后重跑遇 `testGoal large` / performance runner 偶发，重跑失败作业即可）。
- **CI iOS e2e 作业是空转的（假绿）**：`ios-e2e.yml` 用 GITHUB_ENV 注入 `E2E_QR_PATH/E2E_CONTROL_URL`，但 xcodebuild 不把这些变量传进测试 runner（需 `TEST_RUNNER_` 前缀），7 个 E2E 用例全部被 XCTSkip。本地真跑发现更深一层：配对码一次性 + App 每次启动用随机 UUID `requestId` 重新配对，整套 `E2ELaunchTests` 第 2 个用例起必然失败（首例 `testPairAndInbox` 真跑通过；#197 新增的 `testStopTurn` 单独跑也通过）。修工作流需要先定设计（host 每用例刷新二维码 / 确定性 requestId），未擅自改。
- 本地环境限制（Xcode 27.1 + iOS 26.5 模拟器，CI 是 Xcode 26）：① 截图对比全部失配——字体排版换行位置整体漂移，属环境差异非回归，**截图结论只能以 CI 为准**；② 未签名二进制（`CODE_SIGNING_ALLOWED=NO`）在模拟器写不了 Keychain，e2e 配对报「无法在本机保存配对记录」，本地真跑需 `CODE_SIGN_IDENTITY=-` ad-hoc 签名 + `TEST_RUNNER_` 前缀传环境变量。
- 遗留小 nit：`apps/ios/Packages/DLUI/Sources/DLUI/Theme/DLColor.swift:15` 注释还写着「provisional token」，文档已改「用户 2026-10-10 定」；不影响功能，下个 iOS 任务顺手改。
- 本地工作副本：用户 Mac 的 `/Volumes/Space/Dev/dsh-cetus`（本轮回在本地执行并验证，非云端沙箱）。
- 锁屏 / 灵动岛没有截图测试（扩展不在截图矩阵里），标题显示只有单测和代码审查，真机 / 模拟器上看效果由用户做（8.5 需要有灵动岛的设备）。
