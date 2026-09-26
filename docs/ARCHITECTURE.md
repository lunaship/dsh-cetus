# dsh-links 架构总览

一个仓库、三个发布物、一条配对信任链。本文是目录契约与数据流的地图；
字段级契约看 [MOBILE_SYNC_CONTRACT.md](MOBILE_SYNC_CONTRACT.md)，
版本兼容看 [COMPATIBILITY.md](COMPATIBILITY.md)，发布看 [../RELEASING.md](../RELEASING.md)。

## 三个发布物

| 发布物 | 位置 | 工具链 | 作用 |
|---|---|---|---|
| DSH 插件 `dsh-links` | `src/`（根 package.json） | Node ≥ 20 | 手机 HTTPS 接入代理、配对/设备状态机、回环「手机连接」面板 |
| Relay 服务 | `relay/` | Go 1.25 | 跨网络远程配对的信令与转发（内测，接入码准入） |
| Android App | `apps/android/` | Kotlin / Compose | 手机端原生客户端：配对入口、会话工作台、实时流与审批 |

三者同仓不同链：插件测试用 `npm test`，Relay 用 `go vet/build`，App 用
`./gradlew`。CI 按路径分流（`.github/workflows/`），本地门禁见文末。

## 分层

```text
┌──────────────────────────────────────────────────────┐
│ Android App（apps/android，dev.deeplinks）            │
│   devices/ 配对与设备入口                             │
│   native/  工作台屏幕与 Compose 组件                  │
│   core/    跨屏契约：主题/排版 token、locale、布局推导 │
└───────────────▲──────────────────────────────────────┘
                │ HTTPS + 设备 token（配对签发，DLR/1  framing）
┌───────────────┴──────────────────────────────────────┐
│ dsh-links 插件（src/）                                │
│   mobile-api.js  手机 API（/dsh-link/mobile/*）       │
│   index.js       路由注册、配对/吊销状态机、回环 RPC   │
│   module2.js     面板唯一源码（client.js 由它生成）   │
│   relay/         Relay 客户端（agent.js / crypto.js） │
└───────────────▲──────────────────────────────────────┘
                │ 回环 callLocalRpc（仅本机 127.0.0.1）
┌───────────────┴──────────────────────────────────────┐
│ DSH Host：webServer / workspaceChanges / 会话与工具    │
└──────────────────────────────────────────────────────┘
                │ 仅远端模式（持有接入码）
┌───────────────▼──────────────────────────────────────┐
│ Relay（relay/，Go）：信令、加密转发、设备注册           │
└──────────────────────────────────────────────────────┘
```

信任方向只有两条：手机 → 插件（持 token，走上面两层之间的 HTTPS），
插件 → Host（回环 RPC，手机永不远达）。面板 → 插件的回环 POST 同样受
`requireLoopbackSameOrigin` 保护——跨设备吊销、全部吊销、工作区批准这类
高危操作只存在于回环面板，手机 API 刻意不提供。

## 目录契约

| 路径 | 责任 | 约束 |
|---|---|---|
| `src/mobile-api.js` | 手机端 HTTPS API 唯一实现 | 不放 UI；新增端点必须同步 MOBILE_SYNC_CONTRACT.md |
| `src/index.js` | 插件入口：路由注册、配对/设备状态机、Runtime 装配 | 单文件偏大，新增逻辑优先抽模块（如 `workspace-approval.js`） |
| `src/module2.js` → `src/client.js` | 「手机连接」面板唯一源码；client.js 由 `build-client.mjs` 生成 | **勿手改 client.js**；CI 校验生成物与提交一致 |
| `src/relay/` | Relay 客户端协议（DLR/1） | 不 import Host 能力；向量变更双端同步（`scripts/check-dlr1-vectors.mjs`） |
| `relay/` | Go 服务端 | 门禁独立（gofmt/vet/build），发布物随插件文档但独立部署 |
| `apps/android/.../core/` | 跨屏契约：Dsh 主题/排版/颜色 token、AppLocale 目录、DshLayout 布局推导 | 纯函数优先；改 token 先过 DesignTokenUsageTest |
| `apps/android/.../native/` | 工作台屏幕与 Compose 组件 | 巨型文件受 code-hygiene-baseline.txt 预算约束 |
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
- **生成物即契约**：`src/client.js`、DLR/1 向量、截图基线都是生成/校验过的
  工件，CI 用 `git diff --exit-code` 守住「改了源码必须重新生成」。

## 门禁

| 范围 | 命令 | 依赖 |
|---|---|---|
| 插件（全量） | `npm run prepack`（= build-client + 全量测试） | Node |
| 插件（单跑） | `npm test` | Node |
| Relay | `cd relay && gofmt -l . && go vet ./... && go build ./...` | Go |
| Android | `cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | JDK + Android SDK |

冒烟/联调必须用 `stateDir` 隔离，禁止触碰真实设备配对数据——原因与历史事故
见根 [CLAUDE.md](../CLAUDE.md) 红线。
