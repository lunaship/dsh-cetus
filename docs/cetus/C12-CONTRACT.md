# C12 合同核对：RFC 0002 逐条对照实现

> 方案来源：`Cetus-整体改造方案-2026-10-07.md` 第 410 行起（§16 C12）。
> 合同依据：`docs/rfc/0002-push-gateway.md`。
> 逐条：**RFC 条款 → 代码位置 → 实际行为 → 判定**。

---

## 本轮修复

| # | RFC 条款 | 问题 | 修复 |
|---|---|---|---|
| **1** | §5.6「**payload 顶层只有 `aps` / `e` / `k`**（硬性）。**不得**加入 `deviceId`、`sessionId`、`tool`、标题原文或任何明文业务字段」 | RFC 把这条定为硬性，但**没有任何测试盯着它** —— 谁顺手加个字段都不会被发现，而后果是扩宽锁屏可见面（把设备标识交给 APNs 与通知中心） | 新增 `TestAlertPayloadTopLevelIsExactlyApsEK`：按**允许清单**断言顶层字段集合，多一个即失败并给出「必须显式更新本条测试与 RFC」的提示；同时钉住 `mutable-content == 1`（否则 NSE 不会被拉起）与通用锁屏文案 |

## 已核对一致（本轮逐条验证）

| RFC 条款 | 实现 | 判定 |
|---|---|---|
| §5.6 payload 顶层 `aps`/`e`/`k` | `push/internal/http/gateway.go:179` `buildAlertBody` | **一致**，且现有测试兜住 |
| §5.6 回退文案为通用文案 | `title: "cetus"` / `body: "有新的任务动态"` | **一致**（与 iOS `PushContent.generic` 同一口径） |
| §5.4 `deviceId`/`sessionId` 由接收端本地解析 | NSE 调 `deviceID(in:bindings:)`，按 `kid` 查 Keychain 绑定 | **一致**（此项此前被误报为缺失，见下） |
| §5.6 `content-state` **不得**携带 `title` | `ActivityKitLiveActivityAdapter.swift:14-27` 恰好是 `state/step/startedAt/waitingCount/sessionRef` 五字段 | **一致** |
| §16.4.3 `staleDate` = 最后一次更新 + 15 分钟 | `LiveActivityController.threshold = 15 * 60`，`start`/`update` 都传入 | **一致** |
| §16.4.3 过期显示「已离线 · 最后更新」，禁止无限递增计时 | `LiveActivityBundle.swift:50-56` 用 `context.isStale` + `lastUpdatedText`，**不使用** `Text(timerInterval:)` | **一致** |
| §16.1.3 无进度总量时不生成百分比 | 全仓 LA 相关视图无 `ProgressView`、无百分比计算 | **一致** |
| §5.7 限流键 = `sha256(sealed)` | `gateway.go:100` `g.Limiter.Allow(req.Sealed.Hash())` | **一致** |
| §5.7 默认限流 10/分钟、120/小时 | `cmd/dlpush/main.go:59` `limit.New(intOr(env, 10), intOr(env, 120), nil)` | **一致** |
| §5.7 日志只记计数/状态/耗时/kind，**不记** `sealed`/`ct`/token/`deviceId`/明文 | 全文件仅两处 `log.Printf`（`gateway.go:143` 与 `:227`），字段为 kind/status/elapsed/method/path | **一致**，另有 `TestPushLogsOmitSecrets` |
| §5.7 APNs 410 / `BadDeviceToken` 原样回 410 | 已有 `TestPushGonePassthrough` / `TestFakeAPNsGone` | **一致** |
| §16.2.1 token 更新处理 | `PushTokenBridge.didRegister` 返回是否变化；变化时 post 通知触发重新注册 | **一致**（本轮之前已完成） |
| §16.2.2 NSE 只读内容密钥、不联网、失败保留通用通知 | `NotificationService.didReceive` 无网络调用；任何失败由 `PushContent.open` 回退到 generic | **一致** |
| §16.3.5 禁通知/移除配对时停止订阅 | 设置页 `notifyMaster` 关闭与 `unpair()` 成功都调 `push.service().unregister()` | **一致** |
| §16.2.5 前台 SSE 活跃时去重通知 | `src/push-sink.js:113` `hasForegroundSse(deviceId)` → `{sent:false, reason:"foreground"}` | **一致** |

## RFC 0001（DLP/1）拒绝码 —— 顺带核对

| code | RFC 要求的 App 行为 | 实现 | 判定 |
|---|---|---|---|
| `CLOCK_SKEW` | 用 `hostNow − 本机时间` 作为偏移重试**一次** | 上轮已补：`NWRemoteTunnelTransport.open` 捕获后带修正偏移重试一次 | **已一致**（此前 iOS 缺该重试，Android 有） |
| `REPLAY` | 生成新 nonce **立即重试一次** | `sendClientOpen` **每次调用**都用 `SecRandomCopyBytes` 生成新 nonce；`CLOCK_SKEW` 重试路径会重新走一遍 `openOnce`，因此天然满足 | **一致（结构性满足）** |
| `DEVICE_LIMIT` | 退避 1–3 秒后重试 | 映射为 `.busy(code:)`，由 `RemoteTunnelPool` 的额度与退避逻辑处理 | **一致** |
| `SERVER_BUSY` | 退避重试 | `mapError` → `.serverBusy`，池层退避 | **一致** |
| `BAD_MAC` / `UNKNOWN_KEY` | **不重试**、**不删凭据** | 映射为 `.rejected`，`isHardStop` 明确区分硬停止 | **一致** |

> 注：`CLOCK_SKEW` 的实际重试需要真实 Relay + Agent 才能端到端验证；
> 本轮只验证了**偏移量算术**（`RemoteClockSkewRetryTests`）与**触发条件**（`mapError` 映射）。
> 端到端仍属未验证。

## 本条曾被误报（已澄清，勿重复排查）

**「APNs payload 缺 `deviceId`，NSE 永远无法解密」 —— 不成立。**

原判断只读了 payload 路径，漏了 NSE 实际传了 Keychain 绑定：
`NotificationService.swift:26` 调的是 `deviceID(in: info, bindings: keys)`，
`PushRegistration.swift:136-143` 在 payload 取不到时按 `kid` 查绑定。
RFC 0002 刻意不把 `deviceId` 放进 payload，正是靠这条本机绑定恢复。
覆盖证据：`PushPayloadChainTests` 用**真实网关 payload 形状**（只有 `aps`/`e`/`k`）
走 `openRequest` → `PushContent.open`，含未知 kid 回退与篡改密文负例。

## 仍未验证（如实）

| 项 | 原因 |
|---|---|
| **真实 APNs 送达**（sandbox / production） | 无 Apple 付费账号，无法申请 APNs 密钥与推送证书 |
| **真实锁屏与灵动岛外观** | 需真机 + 真实推送；模拟器 `simctl push` 在本机**不会拉起 NSE**（已实测：通知送达但 `processImagePath CONTAINS "NotificationService"` 始终为空） |
| §16.4.5 灵动岛紧凑/最小/展开、多活动竞争、长标题截断 | 需支持灵动岛的真机 |
| §16.1.1 维护者安全审查 | 属流程事项，非代码可验证 |
| 崩溃/重启后 Live Activity 恢复 | `restore()` 有单测覆盖，但真机冷启动重连未验证 |

## 验收分级对照（方案原文）

> 本地向量/假 APNs → App/NSE 集成 → 真实 sandbox APNs → 实际分发渠道 production APNs → 锁屏打开恢复。
> 每级单独记录；没有账号/密钥时仍完成可做的代码与假环境测试，真实送达标待验证。

| 级别 | 状态 |
|---|---|
| 本地向量 / 假 APNs | **已完成**：Go 侧 `TestFakeAPNs*`、iOS 侧 `PushPayloadChainTests` 用真实 payload 形状 |
| App / NSE 集成 | **部分**：解密链路有单测；NSE 实际拉起**未能**在模拟器验证（本机限制） |
| 真实 sandbox APNs | **未做**（无账号） |
| production APNs | **未做** |
| 锁屏打开恢复 | **未做**（需真机） |
