import DLRemote
import Foundation
import os

/// DLP/1 远程隧道的会合参数（RFC 0001 §5.4.3）。
///
/// 与 Android `WebSocketTunnelSocketFactory` 的构造参数一一对应：外层 WSS 地址、`routeId`、
/// 客户类型、`key`（`device` 用 `relayHandle`，`bootstrap` 用 `bootstrapId`）与 MAC 密钥。
public struct RemoteTunnelRoute: Sendable, Equatable {
    /// 外层 WSS 入口，例如 `wss://relay.example/ws`。`ws://` 仅 debug / 测试允许。
    public var endpoint: String
    /// `routeId`（16 B）。
    public var routeId: Data
    /// `device` 或 `bootstrap`。
    public var kind: DlpCrypto.ClientKind
    /// `client_open.key`（16 B）。
    public var keyId: Data
    /// `client_open.mac` 的 HMAC 密钥（32 B）。
    public var key: Data
    /// 可选外层证书钉扎（叶证书 DER 的 SHA-256，64 位小写 hex）。空 = 走系统 CA。
    public var outerPin: String
    /// 已记录的时钟偏移（秒），叠加到本机 Unix 秒上。
    public var clockOffsetSec: Int

    public init(
        endpoint: String,
        routeId: Data,
        kind: DlpCrypto.ClientKind,
        keyId: Data,
        key: Data,
        outerPin: String = "",
        clockOffsetSec: Int = 0
    ) {
        self.endpoint = endpoint
        self.routeId = routeId
        self.kind = kind
        self.keyId = keyId
        self.key = key
        self.outerPin = outerPin
        self.clockOffsetSec = clockOffsetSec
    }
}

/// 会合阶段的类型化失败（对应 Android 的 `RouteConnectException` 家族）。
///
/// RFC §7.2 第 7 条：只有在 `ready` 之前失败才允许换路径重试，因此这些错误
/// 都表示「隧道没建成」，不表示请求已经写出。
public enum RemoteTunnelError: Error, Equatable, Sendable {
    /// 外层 WSS 连不上中继（DNS / TCP / TLS / 升级任一步）。→「连不上中继服务器」
    case relayUnreachable
    /// `4003 ROUTE_OFFLINE`：route 无在线控制连接。→「电脑不在线」
    case routeOffline
    /// `4006 OPEN_TIMEOUT`：10 秒内 Agent 未接受。
    case openTimeout
    /// `4004 RATE_LIMITED`。
    case rateLimited
    /// `4005 SERVER_BUSY`。
    case serverBusy
    /// 电脑侧容量满（`DEVICE_LIMIT` / 单 bootstrap 流满）。
    case busy(code: String)
    /// Agent 明确拒绝（`4007`），`hostNow` 仅 `CLOCK_SKEW` 时存在。
    case rejected(code: String, hostNow: Int?)
    /// 协议违规 / 关闭码无法映射。
    case protocolViolation
    /// 隧道已建好但后续读写失败。
    case transport(String)
    /// 调用方取消。
    case cancelled

    /// RFC §5.7 / §7.5 的用户可见语义分类。
    public var isHardStop: Bool {
        switch self {
        case .rejected(let code, _):
            return code == DlpWire.Reject.badMac || code == DlpWire.Reject.unknownKey
        default:
            return false
        }
    }
}

/// 一条已建立的字节隧道：上行 `write`，下行 `incoming`。
///
/// 语义对齐 Android `TunnelSocket`：任一方向关闭即整条隧道关闭；没有半关闭
/// （TLS 的 `close_notify` 在内层数据里自行传递，RFC §5.4.4）。
public protocol RemoteTunnel: Sendable {
    /// 下行字节流。终止即表示隧道关闭；`for await` 结束或有错误都代表对端/中继已断。
    var incoming: AsyncThrowingStream<Data, any Error> { get }
    /// 写一段内层字节（已按 ≤ 64 KiB 切块，RFC §5.4.4）。
    func write(_ bytes: Data) async throws
    /// 关闭隧道（幂等）。
    func close() async
}

/// 建立一条隧道（`connect()` 语义：返回时 `ready` 已收到）。
public protocol RemoteTunnelTransport: Sendable {
    func open(_ route: RemoteTunnelRoute) async throws -> any RemoteTunnel
}

/// 隧道之上的字节通道：HTTP/1.1 直接读写这里。
///
/// 实现是 `InnerTLSChannel`（内层 TLS，RFC §4.2 第 6 步）。单独抽出协议，
/// 是为了让请求组装 / 响应解析不依赖具体的 TLS 细节。
public protocol TunnelByteChannel: Sendable {
    /// 下行明文字节（已由内层 TLS 解密）。
    var incoming: AsyncThrowingStream<Data, any Error> { get }
    /// 上行明文字节（由内层 TLS 加密后进隧道）。
    func write(_ bytes: Data) async throws
    /// 关闭通道（幂等），并连同底下的回环桥一起关。
    func close() async
}
