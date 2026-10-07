# dsh-cetus 架构总览

一个仓库、三个发布物、一条配对信任链。本文是目录契约与数据流的地图；
字段级契约看 [MOBILE_SYNC_CONTRACT.md](MOBILE_SYNC_CONTRACT.md)，
版本兼容看 [COMPATIBILITY.md](COMPATIBILITY.md)，发布看 [../RELEASING.md](../RELEASING.md)。

## 三个发布物

| 发布物 | 位置 | 工具链 | 作用 |
|---|---|---|---|
| DSH 插件 `dsh-cetus` | `src/`（根 package.json） | Node ≥ 20 | 手机 HTTPS 接入代理、配对/设备状态机、回环「手机连接」面板 |
| 中继 `dlp-relay` | `relay/` | Go 1.25 | 远程连接的哑管道（DLP/1）：只拼接电脑与手机各自向外建立的 WSS，无状态、无账号 |
| Android App | `apps/android/` | Kotlin / Compose | 手机端原生客户端：配对入口、会话工作台、实时流与审批 |

三者同仓不同链：插件测试用 `npm test`，中继用 `go test/vet/build`，App 用
`./gradlew`。CI 按路径分流（`.github/workflows/`），本地门禁见文末。

## 分层

```text
┌──────────────────────────────────────────────────────┐
│ Android App（apps/android，dev.deeplinks）            │
│   devices/ 配对与设备入口                             │
│   native/  工作台屏幕与 Compose 组件                  │
│   core/    跨屏契约：主题/排版 token、locale、布局推导 │
└───────────────▲──────────────────────────────────────┘
                │ HTTPS + 设备 token（局域网直连；或经中继的内层 TLS）
┌───────────────┴──────────────────────────────────────┐
│ dsh-cetus 插件（src/）                                │
│   mobile-api.js  手机 API（/dsh-link/mobile/*）       │
│   index.js       路由注册、配对/吊销状态机、回环 RPC   │
│   panel.js       面板唯一源码（client.js 由它生成）   │
│   remote/        DLP/1 Agent 与远程运行时             │
└───────────────▲──────────────────────────────────────┘
                │ 回环 callLocalRpc（仅本机 127.0.0.1）
┌───────────────┴──────────────────────────────────────┐
│ DSH Host：webServer / workspaceChanges / 会话与工具    │
└──────────────────────────────────────────────────────┘
                │ 远程已开启时：插件 Agent 与手机都只向外连 WSS
┌───────────────▼──────────────────────────────────────┐
│ 中继（relay/，Go）：按 route 拼接两条 WebSocket，只转字节 │
└──────────────────────────────────────────────────────┘
```

信任方向只有两条：手机 → 插件（持 token；远程时 HTTPS 在中继拼好的管道里，仍钉扎插件证书），
插件 → Host（回环 RPC，手机永不远达）。面板 → 插件的回环 POST 同样受
`requireLoopbackSameOrigin` 保护——跨设备吊销、全部吊销、工作区批准这类
高危操作只存在于回环面板，手机 API 刻意不提供。

## 目录契约

| 路径 | 责任 | 约束 |
|---|---|---|
| `src/mobile-api.js` | 手机端 HTTPS API 唯一实现 | 不放 UI；新增端点必须同步 MOBILE_SYNC_CONTRACT.md |
| `src/index.js` | 插件入口：路由注册、配对/设备状态机、Runtime 装配 | 单文件偏大，新增逻辑优先抽模块（如 `workspace-approval.js`） |
| `src/panel.js` → `src/client.js` | 「手机连接」面板唯一源码；client.js 由 `build-client.mjs` 生成 | **勿手改 client.js**；CI 校验生成物与提交一致 |
| `src/remote/` | DLP/1：`agent.js`（控制连接、会合验证、接本机端口）、`runtime.js`（启停、面板状态、二维码种子、设备远程能力）、`state.js`（state.remote 纯函数）、`crypto.js` / `wire.js` / `bootstrap.js` | 不 import Host 能力；密码学常量由 `testdata/dlp1/vectors.json` 三端锁定 |
| `relay/` | 中继服务端（`cmd/dlp-relay` + `internal/dlp`），`deploy/` 为 systemd + Caddy | 门禁独立（gofmt/vet/build/race），不随插件包发布 |
| `apps/android/.../core/` | 跨屏契约：Dsh 主题/排版/颜色 token、AppLocale 目录、DshLayout 布局推导 | 纯函数优先；改 token 先过 DesignTokenUsageTest |
| `apps/android/.../native/` | 工作台屏幕与 Compose 组件 | 巨型文件受 code-hygiene-baseline.txt 预算约束 |
| `apps/android/.../core/remote/` | DLP/1 客户端：`RouteSelector`（自动选路）、`WebSocketTunnelSocketFactory`（隧道裸 socket）、`RemoteRoute`、`DlpCrypto` | 选路是纯逻辑，时钟与探测注入；中继的拒绝码只做提示，不改凭据 |
| `apps/android/.../devices/` | 配对/扫码/设备管理入口 | 兼容壳薄，逻辑在 MainActivity NavHost |
| `apps/android/app/src/test/resources/*-baseline.txt` | 代码卫生基线 | **只降不升** |

## 一条请求的生命线

```text
扫码/配对码 ──POST /dsh-link/pair──▶ 状态机签发 deviceToken
   │                                    （同名冲突 → SAME_NAME 显式替换）
   ▼
手机持 token ──HTTPS──▶ /dsh-link/mobile/* ──回环 RPC──▶ DSH Host
   │                        │
   │                        ├─ 高危操作（跨设备吊销等）→ 403，仅回环面板可做
   │                        └─ 绝对路径注册工作区 → 202 挂起，面板批准后才 create
   ▼
会话事件/审批/提问 ──SSE──▶ 手机（断线 30s 内重连，cursor 续传）
```

两条值得记住的设计线：

- **审批式工作区注册**：手机只能提交已存在目录的 realpath，真正调用
  `workspace.create` 的永远是回环面板（`src/workspace-approval.js` 队列，
  TTL 跟随 `pairingTtlSeconds`）。手机 API 与宿主写操作之间永远隔一个「人」。
- **生成物即契约**：`src/client.js`、DLP/1 向量、截图基线都是生成/校验过的
  工件，CI 用 `git diff --exit-code` 守住「改了源码必须重新生成」。

## 首页收件箱的数据流（2026-09-28 重设计）

首页要回答「有没有在等我、在跑什么、最近干完了什么」，三块数据来源不同：

| 首页元素 | 数据来源 | 缺失时的回退 |
|---|---|---|
| `awaitingInput` 分区与「在电脑上处理」行 | 插件钩子计数（`src/awaiting-input.js`），随会话摘要下发 | 不分区 |
| 进行中行的「正在运行 `命令` · 第 N 步」 | **插件新增 `activity`**：`src/mobile-session-activity.js` 从 `session.history` 推导最近一次未结束的工具调用（只对最近 20 个会话算，按 `sessionId+updatedAt` 缓存，并发 4） | 写「运行中」，副标题退回目标摘要 |
| 最近行的「改了 N 个文件，<一句话>」 | **插件新增 `lastResult`**：同上文件，取最后一条助手回复去 Markdown 的首段（≤60 字）+ 本轮 `workspace/changes` 的统计 | 写「已完成」 |
| 顶栏「● 在线 · 远程」 | App 侧探测循环 `native/util/HostReachability.runReachabilityLoop`（成功 30s 后复查；失败按 3/5/10/30s 退避）；通道取最近一次成功请求实际走的路（`HostHttp.isViaRemote`）。回前台 / 网络变化 / 「重试」经 `core/ConnectivitySignals` 立刻唤醒 | 连续 2 次失败才判离线（从未在线过则第一次即离线） |
| 顶栏「离线 · N 分钟前在线」 | **App 本地记录** `core/LastOnlineStore`（Host 里只有设备级 `lastSeenAt`，没有电脑级） | 只写「离线」 |
| 首页内联审批卡 | 仅当前打开会话的 `/requests` 快照（`WorkspaceViewModel.answerApproval` 提交） | 该行降级为「在电脑上处理」 |

字段级约定（结构、出现时机、回退语义、边界）见
[`MOBILE_SYNC_CONTRACT.md`](MOBILE_SYNC_CONTRACT.md) 的「会话『当前步骤』与『结果一句话』」。

### 冷启动与崩溃记录（2026-09-30 第三轮）

- **会话列表本地缓存**：`native/util/SessionListCache.kt` 复用 `LocalCacheCrypto` 加密落盘，按 `host.slotKey`
  隔离，最多 200 条会话 + 工作区目录 + 归档集合；冷启动先铺缓存再被网络数据整体替换，解除配对 / token
  失效时清除（`HostStore.remove` / `onAuthExpired`）。
- **冷启动去重**：`bootstrap` + `getWorkspaces` 完成后 10 秒内回前台不再重复刷新会话 / 工作区 / 设置
  （`native/util/WorkspaceLists.kt::shouldSkipResumeRefresh`）。
- **首页不预加载**：手机布局停在首页时不为当前会话加载历史 / 模型、也不建推送流（`chatVisible`，
  `native/WorkspaceChromeState.kt`）；进入对话页再开始。
- **崩溃记录**：`core/CrashRecorder.kt` 把未捕获异常（脱敏、最多 3 份、64KB）写进
  `noBackupFilesDir/crash/`，设置页可查看 / 复制 / 分享 / 清除；启动各阶段耗时经 `core/StartupTrace.kt`
  写 logcat（`DshStartup`）与面包屑。

## 远程连接（DLP/1）

```text
面板「外出时也能连」──▶ runtime.start() ──▶ Agent 向中继注册（Ed25519 主机密钥，route = H(公钥)）
二维码 = 局域网字段 + remote{e, r, s}   （s 是一次性 bootstrap 种子，只随二维码图片离开本机）
手机扫码 ──▶ 并行探测局域网 ──通──▶ 局域网配对
                         └─不通─▶ 经中继 kind=bootstrap 会合 ──▶ 同一 /dsh-link/pair（强制本机批准）
配对 / bootstrap 响应附 remote{e, r, h, k}（设备 handle 与会合密钥），存进 App 的 Host
之后每次新建连接：RouteSelector 探测局域网（缓存 30s / 15s，网络变化作废）──▶ 局域网或隧道
```

- Agent 只在验证过会合 MAC、收到中继 `ready` 之后才连 `127.0.0.1:<port>`，并按本地端口登记来源；
  插件据此把 bootstrap 来源限定在 `/pair`、把 device 来源绑定到同一 deviceId 的 token。
- 吊销设备 = 删除设备记录 + `agent.dropDevice()` 关掉它所有远程流。
- 中继可能伪造错误码：App 只据此提示，删除凭据只认插件在内层 TLS 上的答复。

**多路订阅的边界**：手机只在打开某个会话时才订阅它的 SSE，插件也只在有手机订阅时
才接管该会话的审批（`src/index.js` 的 `approval/request`）；因此首页能内联批准的行
至多一行，其余审批仍要到电脑端或进对话页处理。这是方案 D1 选定的诚实降级，不是缺陷。

## 门禁

| 范围 | 命令 | 依赖 |
|---|---|---|
| 插件（全量） | `npm run prepack`（= build-client + 全量测试） | Node |
| 插件（单跑） | `npm test` | Node |
| 中继 | `cd relay && gofmt -l . && go vet ./... && go build ./... && go test ./... -race` | Go |
| 真中继端到端（不进 `npm test`） | `npm run test:dlp1-e2e` | Node + Go |
| Android | `cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | JDK + Android SDK |

冒烟/联调必须用 `stateDir` 隔离，禁止触碰真实设备配对数据——原因与历史事故
见根 [AGENTS.md](../AGENTS.md) 红线。
