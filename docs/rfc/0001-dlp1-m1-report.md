# DLP/1 M0 + M1 执行汇报

- 分支 / worktree：`feat/dlp1-m0-m1` @ `/Users/wuyanzu/.codex/worktrees/dlp1-m0-m1/dsh-links`
- 提交列表（不含本汇报提交）：`aedd374` RFC 入库；`304560f` 跨端测试向量；`e711518` Go Relay；`7b530bd` Android 隧道原型；`9ce47ea` Relay 复查修复
- 起止时间：2026-09-29 CST（起始分钟未记录）；结束于最终门禁完成后

## 门禁结果

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

## RFC §13.1 / §13.2 逐条结果

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

## RFC 问题清单

本次实现范围内未发现需要按规则 A 临时决策的协议歧义。Relay 开发中发现关闭桥接时过早取消上下文会使对端只看到 EOF；实现已改为等待双方 close handshake，相关关闭码测试通过。步骤 7 复查发现 RFC §10.2 的环境变量列表遗漏了 §5.8 要求独立配置的主机连接上限，已补充 `DLP_IP_MAX_HOST_CONNS`（默认 256、下限 64）。

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

步骤 4 Node Agent 与步骤 5 本机 E2E 因 Corepack 不存在而未实现。执行依赖准备命令 `corepack pnpm@9 install --frozen-lockfile` 三次均失败，完整输出均为 `zsh:1: command not found: corepack`。按任务单规则 B 停止依赖该工具的工作，没有改用 npm 安装或替代 WebSocket 库。

因此 M1 尚未达到 RFC 所写的 §13.1 全项自动化和 §13.2 前两项 E2E 验收；当前提交是可构建、通过本地门禁的 M0 及 Go Relay / Android 隧道原型，不构成 M1 冻结验收。

## 建议

恢复 Corepack/pnpm 环境后完成 Node Agent、拒绝分支“验证前不 dial”测试和本机 E2E，再补齐 §13.1 未覆盖项及 SSE/RSS 测量。CI 可加入共享向量、Go race/fuzz、Android assemble/unit/lint 门禁。M2 前需完成 Node Agent 与 Relay 的协议联调；本次没有进入 M2。
