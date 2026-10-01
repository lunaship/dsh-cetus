# Proposal 002：专注 DSH 的五期执行方案

> 状态：已批准执行（2026-10）。本文写给执行 PR 的 agent：每期、每个子项都可以独立领取。
> 背景：通用手机终端（如 Moshi）不是我们的赛道。DeepLinks 只服务 DeepSeek Harness（DSH），把“只有专做 DSH
> 才做得到的深度”做透，同时补齐连通性、后台与通知体验。

## 0. 总则（所有 PR 必须遵守）

### 0.1 红线（摘自 `CLAUDE.md` 与 `apps/android/CLAUDE.md`，违反即拒）

- 冒烟 / 联调**必须**用隔离 `stateDir`（`node scripts/dev-isolated-host.mjs`），不得调用设备吊销类操作；对用户真实 `~/.dsh` 数据**只读**。
- 不得改动线上官方中继 `relay.dshlinks.com` 的部署；中继代码改动只能走 PR，部署由维护者决定。
- 绝不对 release 变体跑 `connectedReleaseAndroidTest`。
- 连接池驱逐只经 `HostHttp.evictPools`，主线程不得做网络 I/O。
- 手机 API **不提供**高危操作（跨设备吊销、全部吊销、工作区批准、端口批准等），这些只在回环面板里。
- 截图基线只允许由 `.github/workflows/regen-screenshots.yml` 生成；禁止提交本地生成的基线。

### 0.2 每个 PR 的通用要求

1. **门禁全绿**：插件 `npm run prepack`；Android `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`；改了 `relay/` 就跑 Go 门禁。
2. **体量与尺寸预算只降不升**：现有文件 / 函数体量门禁、`size-baseline.txt`（裸 dp）不许上调；新功能放进新文件，不要往 `WorkspaceActivity.kt` / `WorkspaceSidebar.kt` 里堆。
3. **兼容旧 Host / 旧 App**：插件新增能力一律在 `src/protocol-caps.js` 的 `pluginCapabilities()` 里声明，App 只在声明了能力时展示入口；旧 App 必须能忽略新字段。
4. **契约同步**：改手机 API 就更新 `docs/MOBILE_SYNC_CONTRACT.md`（字段级），版本要求写进 `docs/COMPATIBILITY.md`。
5. **新增 DSH RPC**：先加进 `src/local-rpc.js` 的 `RPC_METHOD_ALLOWLIST`，并写明只读还是写入。
6. **双语**：所有新文案同时加到 `AppLocaleZh.kt` 和 `AppLocaleEn.kt`（以及插件面板的中英文案）。
7. **隐私**：锁屏通知不显示工具名、参数和消息正文（沿用现有规则）；诊断输出不含 token、完整路径或消息内容。
8. **UI 改动**：附截图测试用例；提 PR 后提醒维护者跑一次 “Regenerate screenshot baselines”。
9. **CHANGELOG**：在“未发布（main）”下写一行。
10. 一个子项一个 PR（编号见各期的 “PR 拆分”），PR 标题以 `[P<期>.<序号>]` 开头。

### 0.3 待维护者确认的问题（执行到对应子项前必须有答复）

| 编号 | 问题 | 影响 |
|---|---|---|
| Q1 | DSH 的 `llm.models` / `session.models` 返回里有没有价格字段？请贴一份真实返回（可脱敏） | P2.3 预估花费 |
| Q2 | 三档“省钱 / 均衡 / 最强”分别对应哪个模型和推理强度？是否允许用户自定义 | P2.2 |
| Q3 | `session.list` 每一条是否带 `tokenUsage` / `sessionStats`？ | P2.1 首页是否显示用量 |
| Q4 | DSH 的 `sessions` 服务有没有“全局会话状态变化”事件？ | P3.2 走事件还是轮询 |
| Q5 | `schedule.history` 每一条运行记录有哪些字段（状态、结束时间、关联会话 id、摘要）？ | P3.3 |
| Q6 | dev server 端口是否允许在手机上批准（默认只在电脑面板批准）？ | P5.2 |

执行 agent 如果能在隔离 host 上只读调用接口拿到答案，可以先自行确认，并把结论写进 PR 描述。

---

## 第 1 期：连通性——诊断 + Tailscale 优先

目标：把“连不上”从一类模糊问题，变成一键就能定位的具体问题。

### P1.1 插件诊断接口与面板“一键检查”

**插件**
- 新文件 `src/diagnostics.js`，导出 `runDiagnostics(rt, { scope })`，`scope` 取 `"mobile"`（手机可见）或 `"panel"`（回环面板，可看到更多）。
- 返回统一结构：
  ```json
  { "version": 1, "generatedAt": 1730000000, "checks": [
    { "id": "host.rpc", "status": "ok|warn|fail|skip", "code": "HOST_RPC_TIMEOUT", "detail": { "ms": 15000 } }
  ] }
  ```
  `detail` 只允许数字、布尔和枚举字符串；文案由 App / 面板按 `code` 本地化，插件不返回自由文本。
- 至少包含这些检查：

  | id | 内容 | scope |
  |---|---|---|
  | `host.rpc` | 用只读 RPC（如 `workspace.list`）测回环延迟和成败 | 两者 |
  | `host.services` | `workspaceChanges`、`typertGateway`、`sessions` 等服务是否挂载 | 两者 |
  | `plugin.version` | 插件版本、`PLUGIN_PROTOCOL`、能力列表 | 两者 |
  | `tls.cert` | 证书指纹前 8 位、到期天数（不足 30 天为 warn） | 两者 |
  | `pairing.devices` | 已配对设备数；当前请求设备是否有效 | mobile 只看自己 |
  | `listen.addresses` | 监听地址列表与分类（private / tailnet / other） | panel |
  | `remote.relay` | 远程是否启用、与中继的连接状态、最近一次拒绝码（取自 `src/remote/runtime.js` 现有状态，不新增探测流量） | 两者 |
  | `clock` | Host 当前时间（App 用来算时钟偏差） | 两者 |

- 手机端点：`GET /dsh-link/mobile/diagnostics`（需要设备 token）。面板：在 `src/panel.js` 加“一键检查”按钮，调用回环接口，scope 为 `panel`。
- 在 `pluginCapabilities()` 中加 `diagnostics: { v: 1 }`。
- 测试：`test/diagnostics.test.mjs`，覆盖每个检查的 ok / warn / fail 分支，以及 mobile scope 不泄露 panel 字段、输出中没有 token 或绝对路径（用正则断言）。

**验收**：面板一键检查在 1 秒内出结果；故意关掉中继或让 Host RPC 超时，能看到对应的 fail 和 code。

### P1.2 App“连接诊断”页 + 主机状态点

**App**
- 新文件 `native/ConnectionDiagnostics.kt`（界面）和 `core/DiagnosticsRunner.kt`（逻辑），入口在设置 → 某台电脑 → “连接诊断”。
- App 本地检查，按顺序执行，每步都有结果：
  1. 当前网络类型（Wi-Fi / 蜂窝 / 无网络），复用 `ConnectivitySignals`。
  2. 局域网直连：逐个探测已存的局域网地址（含 P1.3 新增的 Tailscale 地址），复用 `HostHttp.probeLanUrl`，记录耗时和失败原因（超时 / 拒绝 / 证书不匹配）。
  3. 远程：是否已配置，走 `RemoteRoute` 能不能建立隧道。
  4. 证书固定：指纹是否匹配（不匹配时明确提示“电脑证书变了，需要重新配对”，**不得**自动删除凭据）。
  5. 设备 token：调用诊断接口，401 就提示被吊销或过期。
  6. 时钟偏差：用 `RouteSelector.clockOffsetSec`，超过 120 秒为 warn。
  7. 能连上且插件声明了 `diagnostics` 时，拉取 `/dsh-link/mobile/diagnostics` 并一起展示。
- 每个失败项给一句修复建议（双语），例如“电脑和手机不在同一 Wi-Fi，可以改用 Tailscale 或远程连接”。
- “复制诊断结果”按钮：只复制枚举和数字，不带地址和 token（参考 `PrivacySafeDiagnostics` 的做法）。
- 设置里的主机列表加状态点：绿（最近一次连接成功）、黄（只能走远程或有 warn）、红（最近一次失败）、灰（从未连接或很久没连）。只读现有连接结果，不额外发起探测。
- 测试：`DiagnosticsRunnerTest`（用假的 probe 覆盖各分支）；截图测试覆盖诊断页的全通过、部分失败和全失败三种状态，浅色 / 深色各一套。

**验收**：在模拟器上断开 Wi-Fi、改错证书、吊销 token，三种情况都能给出正确的诊断结论。

### P1.3 Tailscale 优先

**插件**
- `src/index.js` 的 `classifyUrl`：`100.64.0.0/10` 归为 `"tailnet"`（可选：IPv6 `fd7a:115c:a1e0::/48`）。面板在这类地址旁标注“Tailscale”。
- 推荐顺序不变：仍然优先推荐 private 地址；tailnet 地址作为第二候选，必须出现在二维码的 `urls` 里（现在已经在了，加测试锁住）。

**App**
- 现在配对时只存第一个能连通的地址（`PairClient.firstReachable`）。改为：主地址照旧，另外把二维码里的 tailnet 地址存成“备用直连地址”（`HostStore` 加字段并做数据迁移，旧数据默认为空）。
- `RouteSelector` 的局域网探测：先试主地址，失败再试备用 tailnet 地址，都失败才走 REMOTE。成功的地址记入缓存，网络变化时作废（沿用现有的 generation 机制）。
- 两条直连地址钉扎的是同一张证书，不需要额外处理（`PinnedSsl` 已把 100.64/10 当作私网地址）。
- 测试：`RouteSelectorTest` 增加“主地址失败、tailnet 成功”“两者都失败转远程”“网络变化后缓存作废”三个用例；`HostStore` 迁移测试。

**文档**
- README 首屏的“远程连接”一节改成：推荐 Tailscale（零服务器、直连），中继作为免配置备选。
- `REMOTE_ACCESS.md` 的 Tailscale 一节更新为“配对时自动带上 Tailscale 地址”，不再需要手动填写。
- `docs/COMPATIBILITY.md` 写明需要的插件和 App 版本。

**PR 拆分**：P1.1（插件）→ P1.2（App，依赖 P1.1 的接口，但插件没声明能力时也要能运行）→ P1.3（插件 + App + 文档，可与 P1.2 并行）。

---

## 第 2 期：用量与成本——花费仪表 + 三档切换

目标：让用户在手机上一眼看懂“这个任务花了多少、缓存命中如何、上下文还剩多少、余额够不够”。

现有数据（无需新增 RPC）：
- `GET /dsh-link/mobile/sessions/:id/history` 的 `stats` 字段：`tokenUsage`（`uncachedInputTokens` / `cacheReadTokens` / `outputTokens`）、`sessionStats`（`turns`、`steps`、`llmMs`、`toolMs`、`ttftMs`、`ttftSteps`、`decodeMs`、`decodeTokens`）、`contextPressure`、`contextBreakdown`。App 已在 `MobileApi.kt` 中解析，并显示 `ContextMeter`。
- `GET /dsh-link/mobile/balance`（`account.getBalance`），App 已有解析。

### P2.1 会话用量面板

**App**
- 新文件 `native/UsageSheet.kt`：点 `ContextMeter`（或会话“⋯”菜单 → “用量”）打开底部面板，内容：
  - token 三项：未缓存输入、缓存命中、输出，以及合计；
  - **缓存命中率** = `cacheRead / (cacheRead + uncachedInput)`，分母为 0 时显示 “—”；
  - 轮次、步数、LLM 耗时、工具耗时、平均首字延迟（`ttftMs / ttftSteps`）、输出速度（`decodeTokens / decodeMs` 换算成 tok/s）；
  - 上下文占用：`contextPressure` 加上 `contextBreakdown` 的分项条形图（系统提示、历史、工具结果……按实际字段来）。
- 计算逻辑放在纯函数文件 `native/UsageMath.kt` 里，单测覆盖除零、极大值和缺字段的情况。
- 首页任务卡：只有 Q3 确认 `session.list` 带用量时才显示一行“12.3k tokens · 命中 78%”；否则这一项跳过，不要为它在首页加请求。
- 截图测试：面板的有数据、部分缺字段和旧 Host（`stats` 为 null）三种状态。

### P2.2 余额提醒 + 省钱 / 均衡 / 最强三档

**余额**
- 设置里加“余额提醒阈值”（默认关闭）。首页在余额低于阈值时显示一条不可关闭的提示条，点击进入设置；余额接口返回 `signed-out` / `failed` / `unavailable` 时不提示。
- 余额获取频率：首页进入时最多 5 分钟一次，不要轮询。

**三档**（依赖 Q2）
- 在 `ModelPicker` 顶部加三段式选择：省钱 / 均衡 / 最强，下面保留现有的完整模型和推理强度选择。
- 三档的映射**不要在代码里写死模型 id**：按 Q2 的答复写成默认配置（`core/ModelTiers.kt`），并且只从当前 Host 可用的模型列表（`/dsh-link/mobile/models`）中解析；配置的模型不可用就把该档置灰，并说明原因。
- 允许在设置里自定义三档对应的模型和推理强度（若 Q2 同意）。
- 切换走现有的 `session.selectModel` 和推理强度设置，不新增 RPC。
- 测试：`ModelTiersTest`（解析、缺模型时置灰、自定义覆盖）。

### P2.3 预估花费（依赖 Q1）

- **价格来源**按优先级取：① DSH 模型接口返回的价格字段（如果有）；② 插件内置价格表 `src/pricing.js`（按模型 id 列出缓存命中输入、未命中输入、输出每百万 token 的价格和币种，并写明数据日期）；③ 都没有就不显示金额，只显示 token。
- 价格放在插件侧，随插件发版更新，App 不内置价格。插件在 `history` 的 `stats` 里新增 `estimatedCost: { amount, currency, priceDate, source: "host"|"builtin" }`，旧 App 会忽略这个字段。
- App 在用量面板显示“≈ ¥0.42（估算，价格日期 2026-10-01）”。始终带“估算”二字，不和余额混在一起算。
- 测试：`test/pricing.test.mjs`（缓存命中和未命中分开计价、未知模型返回 null）。

**PR 拆分**：P2.1 → P2.2（余额与三档可拆成两个 PR）→ P2.3（等 Q1）。

---

## 第 3 期：离开电脑时——进度通知 + 后台监听 + 定时任务推送

现状：`SessionBackgroundMonitorService`（前台服务，类型 `remoteMessaging`，设置开关默认关闭）**只监听一个会话**的 SSE；`DshNotifier` 已有审批（带操作按钮，由 `ApprovalActionReceiver` 处理）、完成和失败三类通知，以及任务、审批、常驻监听三个通知渠道。

### P3.1 常驻通知改为进度样式

- `DshNotifier.taskMonitorNotification` 改成进度通知：标题是会话名；正文写状态（执行中 / 等你审批 / 等你回答 / 已完成）；进度取 `todos` 完成数 / 总数（没有 todos 就用不确定进度条），另外显示第几步和已用时间。
- Android 16（API 36）以上使用 `ProgressStyle` 并申请提升为进行中通知（`setRequestPromotedOngoing`）；先确认 `androidx.core` 1.19 中 `NotificationCompat.ProgressStyle` 是否可用，不可用就用平台 API 并加 SDK 版本判断。低版本退回 `setProgress`。
- 更新节流：同一条通知 2 秒内最多更新一次；进程在后台时不要每个流式片段都刷新通知。
- 隐私：锁屏版本（`setPublicVersion`）只显示“DeepLinks · 任务执行中”。
- 测试：把通知内容的拼装抽成纯函数 `TaskProgressContent`，单测覆盖各状态、无 todos、todos 全部完成三种情况。

### P3.2 后台监听扩展到“整台电脑”（方案 A）

**插件**（依赖 Q4）
- 新增主机级事件流：`GET /dsh-link/mobile/events`（SSE，需要设备 token），只推送会话级的状态变化：
  ```json
  { "type": "session/state", "sessionId": "…", "state": "running|awaitingApproval|awaitingInput|completed|failed|stopped",
    "title": "…", "origin": "user|subagent|schedule", "seq": 123 }
  ```
  不推送消息内容和工具参数。事件来源：DSH 有全局事件就订阅；没有就在插件里每 5 秒调用一次 `session.list` 做差分（只有在有订阅者时才轮询）。
- 心跳 25 秒一次；带 `Last-Event-ID` 续传，复用现有 SSE 续传与重同步的模式（参考 `docs/ARCHITECTURE.md` 的 SSE 续传一节）。
- 能力声明：`events: { host: true }`。
- 测试：差分逻辑的单测；断线续传；没有订阅者时不轮询。

**App**
- 监听服务改为每台已配对电脑只保持**一条**主机级事件连接；在有正在运行或等待中的会话时才保持连接，全部结束后 5 分钟自动停止（常驻通知随之消失）。
- 收到 `awaitingApproval` 就发审批通知：只有手机正在接管的会话才带操作按钮（沿用现有规则），其他会话只提示“请在电脑上处理”或点开进入 App。
- 原有的单会话 SSE 只在打开会话页时使用，不再放在后台。
- 设置开关文案更新为“离开 App 后继续接收通知”，默认值不变（关闭）。打开时引导用户把 App 加入电池优化白名单，并给小米 / 华为 / OPPO / vivo 的设置路径说明，写在 `docs/` 里，App 里链接过去。
- 测试：服务状态机抽成纯 Kotlin 类，单测覆盖启动、全部完成后自动停止、网络切换后重连、多台电脑同时监听。
- 方案 B（UnifiedPush）和方案 C（中继离线唤醒队列）**不在本期范围**；如果以后要做 C，必须同时补数据流说明文档（原规划第 3 项）。

### P3.3 定时任务和长任务完成推送（依赖 Q5）

- 主机级事件里 `origin: "schedule"` 的会话结束时，发通知“定时任务「名称」已完成 / 失败”，点开直接进入那次运行的会话。
- 普通任务运行超过 N 分钟（设置项，默认 3 分钟）才在完成时通知，避免短任务刷屏。
- 摘要：只显示状态和耗时，**不显示回复正文**。可以加一个设置项“通知里显示回复首行”，默认关闭，开启后锁屏仍不显示。
- 测试：通知决策纯函数（来源、耗时、设置组合）。

**PR 拆分**：P3.1 → P3.2 插件 → P3.2 App → P3.3。

---

## 第 4 期：DSH 原生体验——计划 / 目标 / 子代理

现有数据：`history` 投影里的 `goal`（含 id、revision、phase，App 已支持暂停 / 继续 / 编辑 / 清除）、`todos`（`ChatFeed.kt` 已有 `TodoProgress`）；会话摘要中的 `origin: "subagent"`、`parentSessionId`、`subagentCount`（见 `src/mobile-session-summary.js`）。

### P4.1 目标卡

- 会话页顶部（顶栏下方，可收起）显示目标卡：目标文本（最多两行）、阶段（按 `phase` 枚举本地化）、操作按钮（暂停 / 继续 / 编辑 / 清除，复用现有 `SessionControl`）。
- 目标变化时（revision 变了）卡片有轻微的高亮过渡，使用 `DshMotion` 的令牌，遵守“减少动态效果”设置。
- 新文件 `native/GoalCard.kt`。

### P4.2 计划清单

- 把现在的 `TodoProgress` 升级为可展开的计划清单：逐项显示状态（待办 / 进行中 / 完成），进行中那项高亮，收起时只显示“3/7 · 正在：……”。
- 位置：目标卡下方，或者没有目标时单独显示；在宽屏上可以放进右侧面板。
- 新文件 `native/PlanChecklist.kt`；数据解析放进 `MobileApi.kt` 现有的 todos 解析，不重复解析。

### P4.3 子代理树

- 会话页“⋯”菜单 → “子代理”：按 `parentSessionId` 组成树，显示每个子代理的状态和标题，点进去是只读的子会话视图（复用现有子代理视图）。
- 主会话列表里子代理继续隐藏（与 Web UI 的 `rowVisible` 一致），只在父会话的卡片上显示“3 个子代理运行中”。
- 如果 Host 不返回 `parentSessionId`，这一项整体隐藏。

**通用要求**：三项都要截图测试（浅色 / 深色、中文 / 英文、普通 / 大字号），以及长文本和空状态；新文件进入体量门禁。

**PR 拆分**：P4.1、P4.2、P4.3 各一个 PR，可以并行。

---

## 第 5 期：dev server 预览

目标：DSH 在电脑上启动的 dev server（如 `localhost:5173`），手机能在 App 里直接打开，而且不能变成任意内网代理。

### 安全边界（不可协商）

1. 只代理到 **127.0.0.1 / ::1**，不得转发到任何其他地址。
2. 只代理**已批准**的端口。批准记录 `{ port, label, approvedAt, expiresAt }` 存在插件 state 里，默认 2 小时过期，可在面板随时撤销。
3. 端口黑名单（即使批准也拒绝）：22、3306、5432、6379、11211、27017、9200，插件自身端口（18640 / 配置端口）和 DSH Host 端口。
4. 手机 API 不提供“批准端口”（除非 Q6 同意，并且只限 P5.2 中“本会话检测到的端口”这一种情况，还要另设默认关闭的开关）。
5. App 内预览使用独立 WebView：关闭 JS 桥、禁用文件访问、只允许访问本地代理的源，外部链接交给系统浏览器；退出预览后清空 Cookie 和存储。

### P5.1 手动批准端口 + 预览（一期）

**插件**
- 新文件 `src/preview-proxy.js`：路由 `/dsh-link/mobile/preview/:previewId/*`，需要设备 token。`previewId` 是随机 id，对应一条批准记录。
- 支持 HTTP 和 WebSocket 升级（热更新要用）；改写 `Host` / `Origin` 为 `localhost:<port>`；去掉 hop-by-hop 头；设单次响应上限（例如 50 MB）和空闲超时；记录访问计数，但不记录路径和内容。
- 列表接口 `GET /dsh-link/mobile/previews`：返回已批准且未过期的预览 `{ previewId, label, port, expiresAt }`。
- 面板：新增“预览端口”一栏，可以添加（输入端口和名称）、看到剩余时间、撤销。
- 能力声明：`preview: { v: 1 }`。
- 测试：只连回环地址、黑名单端口、过期、撤销后立即断开、WebSocket 透传、超限截断。

**App**
- 新文件 `core/PreviewLocalProxy.kt`：在手机上监听 `127.0.0.1:<随机端口>`，路径带随机密钥；把请求经 `HostHttp`（同样的证书固定和选路，局域网 / 远程都可以）转发到插件的预览路由，并加上设备 token；支持 WebSocket。
- 新文件 `native/PreviewScreen.kt`：顶栏显示名称、刷新、在系统浏览器中打开（只提示复制地址，不暴露 token），以及退出。入口在会话“⋯”菜单 → “预览”，列出可用的预览。
- 已知限制写进文档：页面里写死的 `http://localhost:xxxx` 绝对地址不会被改写；Vite / Next 的热更新默认使用当前页面的 host，可以正常工作。
- 测试：本地代理的单测（密钥校验、只监听回环、WebSocket 帧转发）；设备测试只走 debug 变体。

### P5.2 自动识别 dev server（二期）

- 插件从会话的工具输出事件里，用正则识别 `http://(localhost|127.0.0.1|0.0.0.0):(\d+)`，生成“检测到的端口”列表（只记端口和会话 id，不存输出内容）。
- 面板对检测到的端口显示一键批准；App 在会话里显示“检测到 dev server :5173 — 请在电脑上批准”。若 Q6 同意，可在设置里打开“允许手机批准本会话检测到的端口”（默认关闭）。
- 测试：识别正则（含误报用例，如日志里的示例地址）、去重、会话结束后清理。

**PR 拆分**：P5.1 插件 → P5.1 App → P5.2。P5.1 插件 PR 需要维护者单独做安全审查。

---

## 附录：不做的事（避免执行时跑偏）

- 不做通用 SSH 终端，不支持其他 agent。
- 不做 Wear OS、桌面小组件、快捷设置磁贴（原规划第 6、8 项）。
- 数据流说明页、按 DSH 版本分级的能力表（原规划第 3、4 项）延后；但第 3 期如果改动中继推送，必须同时补数据流说明。
- 不做竞品对比页（原规划第 14 项）。
