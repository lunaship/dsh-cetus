# DLP/1 M0 + M1 执行汇报

- 分支 / worktree：`feat/dlp1-m0-m1` @ `/Users/wuyanzu/.codex/worktrees/dlp1-m0-m1/dsh-links`
- 提交列表（不含本汇报提交）：`aedd374` RFC 入库；`304560f` 跨端测试向量；`e711518` Go Relay；`7b530bd` Android 隧道原型
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
| 每 IP / 每 route / 全局资源限额；不同 route 不相互影响 | 部分通过 | `TestClientConnectionAndStreamLimits` 覆盖连接数、打开速率、单 route 与全局限额及关闭码；跨 route 隔离断言未覆盖。 |
| 日志不包含 routeId、hostPub 或 b64u 秘密 | PASS（Relay） | `TestLogsOmitRouteSecrets`；Node Agent 日志检查未完成。 |
| 远程路径拒绝插件证书不符 | 部分通过 | WSS 隧道上的内层 TLS 与证书 pin 成功用例通过；`PinnedSslTest` 覆盖错误 pin 被拒绝，但尚无结合远程隧道的错误 pin 联合用例。 |
| Relay 伪造 UNKNOWN_KEY 时 App 不删除凭据 | SKIP | Android 只实现隧道原型，尚未接入凭据处理流程。 |
| 双手机 SSE/API/大下载并发及慢读设备下 SSE p95 < 500 ms | SKIP | Node Agent 与本机 E2E 未实现。 |
| 客户端停读 60 秒时 Relay RSS 增长 < 16 MB | 部分通过 | `TestRelayBackpressureBoundsHeapWhenClientDoesNotRead` 检查停读时 Relay Go heap 增长小于 8 MiB；尚未按 RFC 的 60 秒窗口测 RSS。 |
| 100 ms RTT 首请求和复用请求时延 | 未验证 | 需要人为注入 RTT；未执行。 |

## 新增依赖

| 包 | 版本 | 用途 | 许可证 |
| --- | --- | --- | --- |
| `github.com/coder/websocket` | `v1.8.15` | Go Relay WebSocket 传输 | ISC（上游 `LICENSE.txt`） |
| `com.squareup.okhttp3:mockwebserver3` | `5.5.0` | Android JVM 测试中的假 Relay | Apache-2.0 |

## RFC 问题清单

本次实现范围内未发现需要按规则 A 临时决策的协议歧义。Relay 开发中发现关闭桥接时过早取消上下文会使对端只看到 EOF；实现已改为等待双方 close handshake，相关关闭码测试通过。

## 未完成 / 受阻

步骤 4 Node Agent 与步骤 5 本机 E2E 因 Corepack 不存在而未实现。执行依赖准备命令 `corepack pnpm@9 install --frozen-lockfile` 三次均失败，完整输出均为 `zsh:1: command not found: corepack`。按任务单规则 B 停止依赖该工具的工作，没有改用 npm 安装或替代 WebSocket 库。

因此 M1 尚未达到 RFC 所写的 §13.1 全项自动化和 §13.2 前两项 E2E 验收；当前提交是可构建、通过本地门禁的 M0 及 Go Relay / Android 隧道原型，不构成 M1 冻结验收。

## 建议

恢复 Corepack/pnpm 环境后完成 Node Agent、拒绝分支“验证前不 dial”测试和本机 E2E，再补齐 §13.1 未覆盖项及 SSE/RSS 测量。CI 可加入共享向量、Go race/fuzz、Android assemble/unit/lint 门禁。M2 前需完成 Node Agent 与 Relay 的协议联调；本次没有进入 M2。

