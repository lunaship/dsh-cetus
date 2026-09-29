# DLP/1 M0 + M1 执行汇报

- 分支 / worktree：
  - 第一轮（M0、Go Relay、Android）：`feat/dlp1-m0-m1` @ `/Users/wuyanzu/.codex/worktrees/dlp1-m0-m1/dsh-links`，已合入本地 `main`（`8cc08fa`）
  - 第二轮（步骤 4、5 与本汇报更新）：`feat/dlp1-m1-agent` @ `../dsh-links-dlp1`，从 `main` 开出
- 提交列表：第一轮 `aedd374` RFC 入库；`304560f` 跨端测试向量；`e711518` Go Relay；`7b530bd` Android 隧道原型；`9ce47ea` Relay 复查修复；`69604e5`、`755c813` 汇报。第二轮 `1fc2a36` Node Agent；`12e59d3` 本机端到端；以及本汇报的更新提交
- 起止时间：第一轮 2026-09-29 CST（起始分钟未记录）；第二轮 2026-09-29 19:00–19:50 CST

> **2026-09-29 第二轮结论**：步骤 4、5 已完成，M1 在本机可验证的范围内全部通过（`npm run test:dlp1-e2e`：15 PASS / 0 FAIL / 5 SKIP，SKIP 均为 M2 / M3 集成项或 RFC 允许 M1 不验的 RTT 项）。第一轮「corepack 不存在」的判断不准确：默认 PATH 下的 node（`~/.local/bin/node`）不带 corepack，但本机 nvm 的 node 24 自带（`~/.nvm/versions/node/v24.21.0/bin/corepack`），任务单 §2 对环境的描述有误。第二轮用它执行了任务单规定的 `corepack pnpm@9 …`，没有改用 npm。

## 第二轮门禁结果（最终，全部重跑）

| 命令 | 结果 | 摘要 |
| --- | --- | --- |
| `corepack pnpm@9 install --frozen-lockfile` | PASS | 使用 nvm node 24 自带的 corepack。 |
| `corepack pnpm@9 add ws@^8` | PASS | `ws 8.22.0`，同步 `pnpm-lock.yaml` 与 `THIRD_PARTY_NOTICES.md`。 |
| `npm run prepack` | PASS | 310/310（含 `remote-crypto`、`remote-bootstrap`、`remote-agent`）；`src/client.js` 无变化。 |
| `npm run test:dlp1-e2e` | PASS | 15 PASS / 0 FAIL / 5 SKIP，约 1 分 50 秒；临时目录已删除。 |
| `cd relay && gofmt -l .` | PASS | 零输出。 |
| `cd relay && go vet ./... && go build ./...` | PASS | 无诊断。 |
| `cd relay && go test ./internal/dlp/... -race -count=1` | PASS | 约 14 秒。 |
| `cd relay && go test ./internal/dlp -run=^$ -fuzz=FuzzParseControl -fuzztime=20s` | PASS | 未发现 panic。 |
| `cd relay && go test ./... -race -count=1 -timeout 180s` | PASS | DLR/1 与 DLP/1 全部通过。 |
| `cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | PASS | 本轮未改 Android 代码。 |

`remote-agent` / `remote-bootstrap` 连续运行 5 轮均通过，未见不稳定。

## 第二轮 RFC §13.1 / §13.2 逐条结果（`npm run test:dlp1-e2e` 输出）

| 条目 | 结果 | 证据 |
| --- | --- | --- |
| 三端向量一致 | PASS | 端到端内实跑 JS、Go、Kotlin 三个向量测试。 |
| 无 hostKey 不能注册；route A 不能接受 route B 的 sid | PASS | 伪造签名注册 → 4002 且真实 Agent 不被顶替；A 签名的 host_accept 指向 B 的 sid → 该数据连接 4000。 |
| 旧 ch 重放 host_register / host_accept 失败 | PASS | 两者均 4002。 |
| 没有设备 key 不能让 Agent dial | PASS | UNKNOWN_KEY / 4007，假插件 TCP 连接数不变；单测对 §5.5 每个拒绝分支都断言零连接。 |
| MAC 逐字段篡改、REPLAY、CLOCK_SKEW | PASS | v→4001、route→4003、kind→BOOTSTRAP_UNKNOWN、key→UNKNOWN_KEY、ts/nonce→BAD_MAC；同 nonce → REPLAY；ts−61 → CLOCK_SKEW 且带 hostNow；以上均不 dial。 |
| 吊销后 UNKNOWN_KEY，已有流 1 秒内关闭 | PASS | `dropDevice` 后流约 3 ms 关闭。 |
| bootstrap 过期 / 已消费 / 未知错误码 | PASS | 三种码均正确。 |
| bootstrap 来源非 `/pair` 路径 403；bootstrap 配对必为 pending | SKIP | 属 M2（`src/index.js` 集成），M1 不改 `src/index.js`。Agent 侧已提供来源标签（`originOf`）。 |
| device 来源携带他人 token → 403 | SKIP | 同上，属 M2。 |
| Agent 只连 `127.0.0.1:<pluginPort>` | PASS | 唯一的本地 dial 点写死 `127.0.0.1` 与构造参数 `pluginPort`，端到端做了源码断言。 |
| 畸形输入只关闭当前连接，Relay 不 panic | PASS | 非 JSON、重复键、超长、错误长度 b64u、数据阶段文本帧均 4000；之后 healthz 正常、新流可用；另有 Go fuzz。 |
| 资源耗尽关闭码正确，且不影响其他 route | PASS | 每 IP 并发 4005、每 IP 打开速率 4004、每 route 并发 4005、全局 4005；route A 打满时 route B 正常开流（第一轮未覆盖的跨 route 隔离已补）。 |
| 日志不含秘密 | PASS | Relay 与 Agent 日志中不含 36 个秘密值（种子、公钥、routeId、handle、设备 key、bootstrap 种子与 key、MAC、nonce），Relay 日志不含客户端 IP；Agent 另有单测。 |
| App 在远程路径上拒绝插件证书不符 | SKIP | App 侧：隧道内层 TLS 钉扎用例 + `PinnedSslTest` 分别覆盖，联合用例随 M3 接入 `HostHttp` 补。 |
| Relay 伪造 UNKNOWN_KEY 时 App 不删凭据 | SKIP | App 凭据流程属 M3。 |
| §13.2 双手机 SSE + 4 API + 100 MB 下载，慢读时另一台 SSE p95 < 500 ms | PASS | 30 秒窗口：快手机 p95 ≈ 0–1 ms（152 条），100 MB 下载完成；慢读手机按 32 KiB/s 读约 0.9 MB。本机回环，数值不代表公网。 |
| §13.2 停读 60 秒 Relay RSS 增长 < 16 MB | PASS | 两次运行分别为 +(-0.3) MB、+(-6.8) MB（RSS 未增长）。 |
| §13.2 100 ms RTT 时延 | SKIP | 需要 root 注入延迟，RFC 允许 M1 标「未验证」。 |
| §6.6 selfTest（附加） | PASS | 外层 / 会合 / 内层 TLS 三阶段通过；证书不符时在内层阶段失败。 |

## 第一轮门禁结果（历史）



| 命令 | 结果 | 摘要 |
| --- | --- | --- |
| `node --test test/remote-crypto.test.mjs` | PASS | JS 与共享密码学向量一致。 |
| `cd relay && go test ./internal/dlp/... -count=1` | PASS | Go 向量与协议实现测试通过；最终 race 门禁亦通过。 |
| `cd apps/android && ./gradlew :app:testDebugUnitTest --tests dev.deeplinks.core.remote.DlpCryptoTest` | PASS | Kotlin 与共享向量一致。 |
| `cd relay && gofmt -l .` | PASS | 零输出。 |
| `cd relay && go vet ./...` | PASS | 无诊断。 |
| `cd relay && go build ./...` | PASS | 编译通过。 |
| `cd relay && go test ./internal/dlp/... -race -count=1` | PASS | Relay 协议、桥接、限额、背压和日志测试通过。 |
| `cd relay && go test ./internal/dlp -run=^$ -fuzz=FuzzParseControl -fuzztime=20s` | PASS | 约 21.8 秒，解析器未 panic。 |
| `cd relay && go test ./... -race -count=1 -timeout 180s` | PASS | 全仓 DLR/1 与 DLP/1 测试通过。 |
| `cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | PASS | Debug 构建、JVM 单测和 lint 全部通过；新代码无 lint 阻塞问题。 |
| `corepack pnpm@9 install --frozen-lockfile` | SKIP | 三次尝试都得到完整错误：`zsh:1: command not found: corepack`。按 RFC 未改用 npm 或其他包管理器。 |
| `npm run prepack` | SKIP | Node Agent 依赖安装受上项阻塞，步骤 4 依赖代码未实现。 |
| `npm run test:dlp1-e2e` | SKIP | 端到端脚本依赖尚未安装的 `ws` 和未实现的 Node Agent。 |

## 第一轮 RFC §13.1 / §13.2 逐条结果（历史，已被第二轮取代）

| 条目 | 结果 | 证据 |
| --- | --- | --- |
| 三端 routeId、签名、MAC、HKDF、设备 key 向量一致 | PASS | JS、Go、Kotlin 向量测试。 |
| 无有效 host 签名不能注册；route A 不接收 route B 的 sid | PASS | `TestRegisterAuthenticationAndReplacement`、`TestHostAcceptRejectsUnknownCrossRouteBadSignatureAndOldChallenge`。 |
| 旧 ch 重放 host_register / host_accept 失败 | PASS | Go 注册认证与 host_accept 负向场景测试。 |
| 无设备 key 时 Agent 不会 dial 本地端口 | SKIP | Node Agent 未实现。 |
| MAC 字段篡改、nonce 重放、CLOCK_SKEW | SKIP | Node Agent 验证路径未实现。 |
| 吊销后拒绝新流并在 1 秒内关闭已有流 | SKIP | Node Agent 吊销路径未实现。 |
| bootstrap 过期、消费、未知、路径限制及 pending 配对 | SKIP | Node bootstrap 模块未实现。 |
| device 来源携带其他设备 token 时返回 403 | SKIP | Node Agent 鉴权路径未实现。 |
| Agent 只能连接配置的本地插件端口 | SKIP | Node Agent 未实现。 |
| 畸形控制输入关闭当前连接、文本数据关闭、Relay 不 panic、解析器 fuzz | PASS | `TestParseControlRejectsOversizeAndNestedDuplicate`、`TestTextFrameInDataStageClosesPair`、20 秒 fuzz 门禁；错误长度与未知 sid 等 host_accept 场景也有测试。 |
| 每 IP / 每 route / 全局资源限额；不同 route 不相互影响 | 部分通过 | `TestClientConnectionAndStreamLimits` 覆盖客户端连接数、打开速率、单 route 与全局限额；`TestConnectionAccountingUsesConfiguredHostLimitAndReclaimsIPs` 覆盖独立主机上限和 IP 计数回收；跨 route 隔离断言未覆盖。步骤 7 复查另补日流量 quota。 |
| 日志不包含 routeId、hostPub 或 b64u 秘密 | PASS（Relay） | `TestLogsOmitRouteSecrets`；Node Agent 日志检查未完成。 |
| 远程路径拒绝插件证书不符 | 部分通过 | WSS 隧道上的内层 TLS 与证书 pin 成功用例通过；`PinnedSslTest` 覆盖错误 pin 被拒绝，但尚无结合远程隧道的错误 pin 联合用例。 |
| Relay 伪造 UNKNOWN_KEY 时 App 不删除凭据 | SKIP | Android 只实现隧道原型，尚未接入凭据处理流程。 |
| 双手机 SSE/API/大下载并发及慢读设备下 SSE p95 < 500 ms | SKIP | Node Agent 与本机 E2E 未实现。 |
| 客户端停读 60 秒时 Relay RSS 增长 < 16 MB | 部分通过 | `TestRelayBackpressureBoundsHeapWhenClientDoesNotRead` 在启动写入前采集 heap 基线，尝试写入 20 MiB 并确认发生背压，检查 Go heap 增长小于 8 MiB；尚未按 RFC 的 60 秒窗口测 RSS。 |
| 100 ms RTT 首请求和复用请求时延 | 未验证 | 需要人为注入 RTT；未执行。 |

## 新增依赖

| 包 | 版本 | 用途 | 许可证 |
| --- | --- | --- | --- |
| `github.com/coder/websocket` | `v1.8.15` | Go Relay WebSocket 传输 | ISC（上游 `LICENSE.txt`） |
| `com.squareup.okhttp3:mockwebserver3` | `5.5.0` | Android JVM 测试中的假 Relay | Apache-2.0 |
| `ws` | `^8`（锁定 `8.22.0`） | Node Agent 的 WebSocket 客户端与测试里的假 Relay | MIT |

## RFC 问题清单

第一轮：本次实现范围内未发现需要按规则 A 临时决策的协议歧义。Relay 开发中发现关闭桥接时过早取消上下文会使对端只看到 EOF；实现已改为等待双方 close handshake，相关关闭码测试通过。步骤 7 复查发现 RFC §10.2 的环境变量列表遗漏了 §5.8 要求独立配置的主机连接上限，已补充 `DLP_IP_MAX_HOST_CONNS`（默认 256、下限 64）。

### 第二轮新增（按规则 A 处理）

1. **§5.5 第 1、2 步没有拒绝码。** 位置：§5.5、§5.7。问题：格式错误与 route 不符都要求「回 reject 且不 dial」，但拒绝码表里没有对应码，规则 A 又禁止新增。临时处理：格式错误回 `BAD_MAC`，route 不符回 `UNKNOWN_KEY`（App 对两者都不重试、不删凭据），代码中标 `RFC 待定`。建议定稿：明确这两步复用哪个码，或在 v1 冻结前补一个码。实际上 Relay 已先校验格式，这两条只在 Relay 有缺陷时出现。
2. **预热空闲 60 秒与首条消息 5 秒超时冲突。** 位置：§10.3 第 5 条 vs §5.4 / §5.8。问题：预热连接收到 hello 后不发消息，Relay 5 秒就以 4000 关闭，预热活不过 5 秒。临时处理：`prewarm` 默认 0（接口里的默认值是 2），显式开启时预热空闲上限压到 4 秒内，并有单测。建议定稿：要么删掉预热，要么给 Relay 增加「预热角色」或对 host 连接放宽首条消息超时（属协议变更，需升级或在冻结前决定）。
3. **限额关闭码。** 位置：§5.7。问题：表中 4004 写「IP / route 限流」，4005 写「全局容量满」；Relay 对每 IP 并发连接、每 route 并发流用 4005，只有打开速率用 4004（与第一轮 Go 测试一致）。临时处理：端到端按 Relay 现状断言。建议定稿：明确「速率超限 → 4004，并发 / 容量超限 → 4005」。
4. **单次写超时 30 秒在 Agent 侧的含义。** 位置：§5.8。临时处理：Agent 每 5 秒检查一次两个方向的待写字节，30 秒没有进展就关闭这一对。停读 60 秒的端到端中数据停在内核缓冲、用户态没有待写字节，所以没有触发；Relay 同样没有增长。建议在 RFC 里写明判定口径。
5. **outerPin 的比对时机。** 位置：§10.3 第 2 条。实现：在 `secureConnect` 阶段比对叶证书指纹，不符立即销毁；HTTP Upgrade 请求在 TLS 握手后才写出，且不含任何秘密。无需修改 RFC，仅作记录。

## 步骤 7 后续复查修复（2026-09-29）

用户要求在步骤 7 汇报后再次检查遗漏，并先修复发现的问题。修复提交：`9ce47ea`。

| 复查发现 | 修复与证据 |
| --- | --- |
| 离线 route 和限流器 IP 窗口没有回收 | 路由断开、pending 清理和 bridge 结束时清理无活动路由；当天已使用的日 quota 记录保留到 UTC 日切后的下一次清理触发，随后回收。限流窗口每分钟清理 10 分钟未更新的 IP；最后一个 IP 连接退出后删除计数项。`TestDailyByteReservationsAreAtomicAndOfflineRoutesAreSweptSafely`、`TestLimiterPrunesInactiveIPWindows`、`TestConnectionAccountingUsesConfiguredHostLimitAndReclaimsIPs`。 |
| 日流量只在整帧转发后检查，可能超额转发 | 改为每 16 KiB 预留 route 日 quota 后再写出，双向共用同一原子计数；耗尽时关闭流并回收未写出的预留。`TestDailyByteReservationsAreAtomicAndOfflineRoutesAreSweptSafely`、`TestDailyByteQuotaStopsForwardingBeforeLimitIsExceeded`。 |
| 主机并发上限硬编码，不能独立配置 | 新增 `DLP_IP_MAX_HOST_CONNS`，默认 256、最小 64；客户端上限继续由 `DLP_IP_MAX_CONNS` 独立控制。`TestHostConnectionLimitConfiguration`、`TestConnectionAccountingUsesConfiguredHostLimitAndReclaimsIPs`；RFC §10.2 配置列表已同步。 |
| 主进程未处理 SIGTERM，且 Hub 未跟踪所有 WebSocket | `main` 接收 SIGINT/SIGTERM；Hub 记录所有连接，停止接入、唤醒等待中的 open，并用 1001 关闭连接，随后执行 HTTP Shutdown。`TestHubCloseClosesControlAndDataConnections` 覆盖控制、活动数据、活动客户端和 pending 客户端。 |
| 背压测试的 heap 基线在写入 goroutine 启动后采集，未确认写入真的被阻塞 | 基线移到启动前；测试对不读取的客户端发起 20 MiB 写入，要求观察到背压且写入有进展，并检查 heap 增长小于 8 MiB。 |
| 日志测试仅直接调用哈希函数，未覆盖真实握手；错误码可携带控制字符进入日志 | 日志测试改为跑完 host 注册和 client 拒绝流程；日志仅记录经过安全过滤的错误码，测试断言 route、密钥、nonce、MAC、消息和 IP 均不出现，并覆盖控制字符过滤。 |

复查门禁结果：`cd relay && go test ./internal/dlp/... -race -count=1`、`go vet ./...`、`go build ./...`、`gofmt -l .`、`git diff --check` 均通过；`go test ./... -race -count=1 -timeout 180s` 在日志码过滤的小幅追加前通过，追加后 DLP 包 race 测试重新通过；`go test ./internal/dlp -run=^$ -fuzz=FuzzParseControl -fuzztime=20s` 通过，约 191,691 次执行，未发现 panic。

## 未完成 / 受阻

- 第一轮记录的「步骤 4、5 因 Corepack 不存在而未实现」已在第二轮解决（见文首结论）。
- 仍然 SKIP 的 5 项都不在 M1 范围内：2 项属 M2 插件集成，2 项属 M3 App 集成，RTT 项 RFC 允许 M1 不验。
- `relay/deploy/dlp/`（RFC §10.1 列出的部署文件）属 M4，本轮未做。

## 建议

第二轮新增：

- **M2 集成要点**：`isLocalReady` 接插件 readiness；`lookupDevice` 必须排除已吊销、以及过期的 pending 设备；配对成功的同步临界区里调用 `bootstrap.consume`；`replaced` 事件要落到面板提示（§6.7）；`originOf(req.socket.remotePort)` 只在对端是回环地址时使用。
- **CI**：`npm run test:dlp1-e2e` 需要 Go 工具链、耗时约 2 分钟，建议放进单独的定时或手动触发 job，并设 `DLP1_E2E_SKIP_ANDROID=1`（Kotlin 向量已由 Android job 覆盖）。本轮未改 `.github/workflows/`。
- `THIRD_PARTY_NOTICES.md` 里 `@deepseek-ai/schemastery` 写的是 3.18.1，实际依赖是 3.18.2（历史遗留，本轮未改）。
- 冻结 DLP/1 v1 前先对上面「RFC 问题清单 · 第二轮新增」的 1–3 条定稿。

第一轮：


恢复 Corepack/pnpm 环境后完成 Node Agent、拒绝分支“验证前不 dial”测试和本机 E2E，再补齐 §13.1 未覆盖项及 SSE/RSS 测量。CI 可加入共享向量、Go race/fuzz、Android assemble/unit/lint 门禁。M2 前需完成 Node Agent 与 Relay 的协议联调；本次没有进入 M2。
