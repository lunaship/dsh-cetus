# C11 远程连接闭环（DLP/1）

> 方案 §15（C11）。协议依据 `docs/rfc/0001-dlp1-remote-pipe.md`。
> 本文记录**运输准入的实测结论**、据此确定的设计、已完成与未完成部分。

---

## 1. 已就绪的部分

| 组件 | 位置 | 状态 |
|---|---|---|
| DLP/1 帧、签名、密码学向量 | `DLRemote/` | ✅ 有向量测试 |
| 会合（hello → client_open → ready） | `NWRemoteTunnelTransport.rendezvous` | ✅ 含 `CLOCK_SKEW` 自动重试一次（RFC §5.7） |
| 外层 WSS 钉扎 | `NWRemoteTunnelTransport.installPin` | ✅ |
| 内层 TLS 通道 + 钉扎 | `InnerTLSChannel.open` + `installPin` | ✅ 7 例测试（`RemoteInnerTLSTests`） |
| 回环字节桥 | `LoopbackTunnelBridge` | ✅ 只绑 `127.0.0.1`、随机端口、随隧道关闭 |
| HTTP/1.1 字节手写与解析 | `HTTP1Wire` / `HTTP1StreamParser` | ✅ 有测试 |
| 隧道池与并发额度 | `RemoteTunnelPool` | ✅ |
| 传输抽象 | `HostTransport`（`HostClient` 注入） | ✅ 已就位，`HostClient(transport:)` 可注入 |
| 路由构造 | `RemoteRouteBuilder.deviceRoute` | ✅ 含 `clockOffsetSec` 透传 |
| 选路器 | `RouteSelector` | ✅ 含远程候选 |

## 2. 关键结论：回环桥 + 钉扎 URLSession **可行**（此前结论是错的）

RFC §15.1 第 2 条的字面要求是：

> 优先验证回环字节桥：外层 WSS/DLP；**内层由 URLSession 做 TLS 和固定证书校验**。
> HTTP、SSE 共用现有 HostClient。

**第一版 spike 用 `URLSession(configuration: .ephemeral)`（没有钉扎 delegate）直连桥，
必然以 `NSURLErrorDomain -1202`（证书无效）失败。** 当时的结论是「URLSession 路线不可行，
只能用 `InnerTLSChannel` 手写 HTTP/1.1」。**这个结论建立在错误的实验设计上** ——
未钉扎的 session 拒绝自签证书只能说明「默认 session 不接受自签证书」，
不能说明「带钉扎 delegate 的 session 也不行」。

2026-10 重测（`RemoteLoopbackAdmissionSpikeTests`），换成**真实的 `PinnedSessionDelegate`**：

```
SPIKE-A2 pinnedURLSessionStatus=200 error=none pinFailures=0 pluginSawRequests=1
```

**完整 TLS + HTTP 往返成功**，且：

| 断言 | 结果 |
|---|---|
| 拿到 HTTP 200 | ✅ |
| 钉扎失败次数 = 0（指纹匹配 fixture 叶证书） | ✅ |
| 插件侧收到 1 次请求（字节真的送达） | ✅ |
| **可重复**：连做两次、各配一条桥，两次都 200 | ✅（`repeatedRequestsEachGetTheirOwnBridge`） |
| 桥只绑回环，非回环地址连不上 | ✅（`bridgeBindsLoopbackOnly`） |

这三条断言是**本轮补上的**。此前 spike 只断言 `bridgePort != 0`，结论只打在日志里 ——
等于「跑完不判定」，无法在 CI 上防止这条路悄悄坏掉。

### 2.1 为什么这很重要

按 §15.1 第 2 条，**URLSession 是方案指定的内层实现**。既然它可行，就**不需要**
自写 HTTP/1.1 编解码去跑生产流量 —— URLSession 免费提供分块传输、重定向、
字节流式读取（SSE）、连接复用、超时与取消语义。手写解析器要覆盖 §15.1 第 5 条列的
双向背压、分块、EOF/半关闭、取消、重连等全部情形，测试面大得多。

### 2.2 准入相关的其它核对

| §15.1 要求 | 核对结果 |
|---|---|
| 第 3 条 只绑回环、严禁暴露 LAN | ✅ `LoopbackTunnelBridge` 的 `requiredLocalEndpoint` 固定 ipv4 loopback；有测试实测非回环地址连不上 |
| 第 4 条 不得声称桥能检查 TLS 内 HTTP 头 | ✅ 未做该声称；桥是**透明**字节泵（`LoopbackTunnelBridge` 文档开篇已写明） |
| 第 5 条 Host 头 / SNI | ✅ 插件不校验 `Host`（`src/index.js` 无 `req.headers.host` 读取）；Node 单证书 `https` 服务不依赖 SNI，用 `127.0.0.1` 也能拿到正确证书并通过钉扎。**`InnerTLSChannel` 会设 SNI 为真实主机名**，URLSession 路线需显式把 `Host` 头改回真实主机（见第 4 节待办） |
| 第 6 条 不达准入要给原因与替代 spike | ✅ 已给：回环桥**达到**准入；若将来 URLSession 路线失效，替代方案是 `InnerTLSChannel` + 手写 HTTP/1.1（`RemoteHostTransport` 已有可编译实现，见第 4 节） |
| 第 7 条 60 秒稳定是**最小**门槛 | ⏳ 未做：需隔离 Relay + 假 host |
| 第 1 条 一个 WSS 一条 flow | ✅ 未把多路复用写进 v1：池按 `host+kind` 限额，数据连接每流一条（§5.4.3） |

## 3. 当前缺口（用户可见后果）

**`RouteSelector` 已能返回 `.remote`，但没有任何调用点消费它。**

`InboxLiveService.swift:52`（以及另外 4 处）写的是：

```swift
guard case .direct(let address) = selection, let base = URL(string: address) else {
    await routes.forget(key: hostID)
    throw InboxServiceError.offline
}
```

即：**局域网与 Tailscale 都不可达时，直接抛「离线」** —— 即便该主机已经配对好远程、
手机也能连上 Relay。也就是说**远程能力目前对用户完全不可见**，
`ConversationLiveService.connect()` 同样只接受 `.direct`（方案 §15 原文点名的缺口）。

## 3.1 曾卡住的坑：`URLSession.bytes(for:)` 的任务级证书挑战（已修）

按第 2 节的结论实现 URLSession 版传输时，**一次性请求全部通过，SSE 一律失败**，
报 `NSURLErrorServerCertificateUntrusted (-1202)`，且 `PinnedSessionDelegate.pinFailureCount == 0`
—— 说明**钉扎回调根本没被调用**。

做成决定性对照实验（`RemoteLoopbackAdmissionSpikeTests.pinnedURLSessionBytesAPIOnSameBridge`）：
**同一个桥、同一个假插件、同一张指纹、同一个 delegate**，只把 `dataTask` 换成
`bytes(for:)`：

```
修好后：  SPIKE-A3 bytesStatus=200 received=25 pinFailures=0
修好前：  SPIKE-A3 bytesFAILED=-1202  pinFailures=0
```

### 根因

**`URLSession.bytes(for:)`（`AsyncBytes`，SSE 用）把 server-trust 挑战投递到
*任务级* 回调**（`urlSession(_:task:didReceive:completionHandler:)`），
而 `data(for:)` 走 *会话级*。原来的 `PinnedSessionDelegate` 只声明了
`URLSessionDelegate` 且只实现会话级方法，于是：

- `data(for:)` → 会话级方法被调用 → 钉扎生效 ✅
- `bytes(for:)` → 任务级方法不存在 → 落到**系统默认校验** → 自签证书被拒 ❌

第二个必要条件是 **Swift 的 ObjC 暴露**：即使写了任务级方法，类也必须
**声明 `URLSessionTaskDelegate` 遵循**，否则该方法不会暴露给 ObjC 运行时；
运行时找不到选择器，现象与"根本没实现"一模一样。
本轮实测：只加方法、不改遵循声明时 SSE **仍然失败**。

### 修法

```swift
public final class PinnedSessionDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable
```

并让会话级与任务级两个入口共用同一个私有 `evaluate(_:_:)`，保证两条路钉扎语义一致。

### 为什么值得单独记一笔

症状极具误导性：*同一个 session、同一张证书、一次性请求成功而流式请求失败*，
错误信息只说"证书无效"，完全指不到 delegate。它会**静默地让远程 SSE 永远连不上** ——
而 §15.2 末条要求「只有 bootstrap/SSE 真正可用才显示电脑在线」，
所以 SSE 不通等于远程整体不可用。

**潜在影响面**：局域网路径当前只用 `data(for:)`，因此没被触发；
但将来任何地方改用 `bytes(for:)`（一个很自然的选择）都会重新踩上。

### 验证

| 证据 | 结果 |
|---|---|
| 同一桥对照实验（`SPIKE-A3`） | 修好后 `bytesStatus=200 received=25 pinFailures=0` |
| `LoopbackURLSessionTransportTests` | **10/10**：SSE 逐行与空行分帧、状态码、`Host` 头还原、钉扎硬停止、fail-closed、拒绝码只诊断、取消语义、URL 保路径 |
| **反向验证**：遵循声明改回 `URLSessionDelegate` | SSE 这一条**确实失败**（9/10）→ 修复是承重的 |
| 回归面 | `RemoteInnerTLSTests` / `PreviewProxyTests` / `RemoteRouteBuilderTests` / spike 共 **40 例全过** |

## 4. 待办（按顺序）

1. **把回环端口暴露给 URLSession**：给 `NWRemoteTunnelTransport` 加一个
   `openLoopback(over:host:expectedFingerprint:)`，内部做
   `bridge.listen()` → 后台跑 `acceptAndPump()` → 返回端口与关闭句柄。
   （`InnerTLSChannel.open` 的编排顺序可直接复用：**先 listen 拿端口、再发起连接、
   最后等桥接受**，顺序反了会互等。）
2. ~~实现 URLSession 版传输~~ ✅ **已完成**（`LoopbackURLSessionTransport`，10 例测试；
   根因见 §3.1）。保留原设计要求备查：
   - `send`：`URLSession.data(for:)` 指向 `https://127.0.0.1:<port><path>`，
     用与局域网**同一个** `PinnedSessionDelegate`（保证两条路径钉扎同源，§15.2 第 2 条）；
   - `stream`：`URLSession.bytes(for:)` → `SSELineSplitter` → 行流，**不攒 body**；
   - 把 `Host` 头改回真实插件地址（保持插件侧语义与日志可读）；
   - 错误映射：钉扎失败 → `.certificateChanged`（硬停止，§7.2 第 8 条）；
     远程拒绝码只提示、**不删凭据**（§7.4）。
   - 每请求一条桥 + 一个 lease；`Connection` 语义交给 URLSession。
3. **接选路**：`.direct` 失败且 `RemoteRouteBuilder.hasRemoteCapability(host)` 时，
   用远程传输重试一次；5 个调用点（`InboxLiveService`、`ConversationLiveService`、
   `SettingsModelsService`、`SettingsAccountService`、`PushSettingsRegistration`）
   通过一个共享工厂取传输，避免各自实现。
4. **在线态文案**：只有 bootstrap/SSE 真正可用才显示「在线 · 远程」；
   只连上 Relay **不得**显示电脑在线（§15.2 末条）。
5. **隔离 Relay + 假 host 的 60 秒稳定测试**（§15.1 第 7 条），再做长连接、
   失败与切网矩阵。
6. `RemoteHostTransport.swift` 的**手写 HTTP/1.1 版本**目前在 `/tmp/sm-c11-wip/`
   （已确认可编译），作为第 2 步的**替代方案**保留 —— 若 URLSession 路线在真机
   （尤其在运营商网络上）出现 §15.1 第 5 条列的问题，可切回。**不删，但也不先接线**：
   按实验证据选 URLSession，而不是按"哪个写得早"。

## 5. 未验证（不得宣传为已支持）

- 真实 Relay 上的端到端远程连接（本环境无法起隔离 Relay + 真实中继）
- 真实网络切换矩阵（五次 Wi-Fi/蜂窝）
- 长连接 60 秒以上的稳定性
- 真机上的内层 TLS 行为（运营商网络、代理、MTU 差异）
- `.remote` 选路后的完整业务链路（发送、SSE、审批、提问）

**当前支持声明**：远程**协议层**已就绪并有单测；**生产选路未接线**，
因此用户实际无法通过远程使用本 App。这与 `DELIVERY.md` 的「远程未验证」一致。
