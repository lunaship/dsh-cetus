import DLModels
import DLNet
import DLRemote
import DLSecurity
import Foundation

/// 把已落盘的主机凭据变成 DLP/1 会合参数（RFC 0001 §5.4.3、§5.2）。
///
/// ## 为什么单独一个类型
///
/// `RemoteTunnelRoute` 需要的是**原始字节**（`routeId`、`keyId`、`key`），而 `HostStore`
/// 里存的是 base64url 字符串。转换看起来只有三行，但每一步都有 RFC 明确定义的
/// 长度与语义约束，任何一个错了都是「连不上」或更糟的「连到别人」：
///
/// - `routeId` 必须 16 B；`keyId` 16 B；MAC 密钥 32 B（§5.1「长度必须精确等于表中长度」）
/// - `device` 档：`keyId = relayHandle`，MAC 密钥 = `relayKey`（配对时由插件下发）
/// - `bootstrap` 档：`keyId = bootstrapId`，MAC 密钥 = `bootstrapKey`，两者都由
///   QR 里的 `bootstrapSeed` 经 HKDF-SHA256 派生（§5.2 末行），**不落盘**
///
/// 集中在这里是为了让「选路」与「凭据 → 路线」两件事各自可测，
/// 也避免 4 个调用点各写一遍解码逻辑而其中一处漏了长度校验。
public enum RemoteRouteBuilder {
    /// `device` 档：已配对设备走远程。
    ///
    /// - Returns: 凭据齐全且合法时返回路线；字段缺失/长度不符返回 nil。
    ///   调用方据此回退到 LAN，**不得**据此删除凭据（§7.4）。
    /// - Throws: `RouteBuildError.invalidOuterPin` —— 配了钉扎但格式非法（fail-closed）。
    ///
    /// 注意这里**不接收**内层插件证书指纹：内层 TLS 的钉扎在 `InnerTLSChannel` /
    /// `RemoteTunnelPool` 那一层做（§4.3），路由只负责会合参数。把指纹塞进这里
    /// 会造成「两处都以为自己在钉扎」的假象。
    public static func deviceRoute(
        info: DeviceRemoteInfo?,
        clockOffsetSec: Int = 0
    ) throws -> RemoteTunnelRoute? {
        guard let info, let endpoint = normalizedEndpoint(info.endpoint) else { return nil }
        guard let routeId = decode(info.routeId, bytes: DlpWire.routeBytes),
            let handle = decode(info.deviceHandle, bytes: DlpWire.keyBytes),
            let key = decode(info.relayKey, bytes: 32)
        else { return nil }
        return RemoteTunnelRoute(
            endpoint: endpoint,
            routeId: routeId,
            kind: .device,
            keyId: handle,
            key: key,
            outerPin: try normalizedPin(info.outerCertificatePin),
            clockOffsetSec: clockOffsetSec)
    }

    /// `bootstrap` 档：二维码首配走远程（§7.6 第 3 步）。
    ///
    /// `bootstrapSeed` 只在配对流程的内存里存在，因此本方法**接收 seed 而不是从 store 读**，
    /// 避免把一次性凭据写进持久层。
    public static func bootstrapRoute(
        endpoint: String?,
        routeId: String?,
        bootstrapSeed: Data,
        outerPin: String?,
        clockOffsetSec: Int = 0
    ) throws -> RemoteTunnelRoute? {
        guard let endpoint = normalizedEndpoint(endpoint),
            let routeIdBytes = decode(routeId, bytes: DlpWire.routeBytes),
            bootstrapSeed.count == DlpWire.keyBytes
        else { return nil }
        guard
            let material = try? DlpCrypto.bootstrapKeys(seed: bootstrapSeed, route: routeIdBytes)
        else { return nil }
        return RemoteTunnelRoute(
            endpoint: endpoint,
            routeId: routeIdBytes,
            kind: .bootstrap,
            keyId: material.bootstrapId,
            key: material.bootstrapKey,
            outerPin: try normalizedPin(outerPin),
            clockOffsetSec: clockOffsetSec)
    }

    /// 该主机是否具备远程能力（§7.2 第 5 条「主机有远程能力 → 走远程」）。
    ///
    /// 只回答「凭据看起来齐全吗」，**不建立任何连接**，也不代表中继上真的在线。
    /// 「在线 · 远程」的显示必须等到 bootstrap/SSE 真正可用（§15.2 验收）。
    /// 钉扎非法时按「不可远程」处理 —— 这条路径只用于显示与选路判断，
    /// 真正建连时 `deviceRoute` 会抛出 `invalidOuterPin` 让用户看见。
    public static func hasRemoteCapability(_ host: PairedHost) -> Bool {
        ((try? deviceRoute(info: host.remote)) ?? nil) != nil
    }

    /// 解析 base64url 并校验精确长度；不合法返回 nil（不抛，调用方按「不可远程」处理）。
    private static func decode(_ value: String?, bytes: Int) -> Data? {
        guard let value, !value.isEmpty else { return nil }
        return try? DlpCrypto.base64URLDecode(value, expectedLength: bytes)
    }

    /// 中继入口规范化（§5.1：唯一入口是完整 URL；`ws://` 仅 debug / 测试允许）。
    ///
    /// 生产必须 `wss://`。这里**显式拒绝** `ws://`：Debug 构建的放行放在调用方，
    /// 避免「测试开关」渗进这条会被生产复用的路径。
    private static func normalizedEndpoint(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = URL(string: trimmed),
            let scheme = url.scheme?.lowercased()
        else { return nil }
        #if DEBUG
            guard scheme == "wss" || scheme == "ws" else { return nil }
        #else
            guard scheme == "wss" else { return nil }
        #endif
        guard url.host != nil else { return nil }
        // 不允许凭据或锚点（与插件侧 normalizeEndpoint 一致）。
        guard url.user == nil, url.password == nil, url.fragment == nil else { return nil }
        return trimmed
    }

    /// 外层钉扎：空 = 走系统 CA；否则必须是 64 位小写 hex（§5.1）。
    ///
    /// **非法值一律整体拒绝（fail-closed），不是「当作没有钉扎」**：用户显式配了 `outerPin`
    /// 就是要求钉扎；若因为格式问题静默退回系统 CA，等于把一次「拒绝连接」降级成
    /// 「连到任何持有合法 CA 证书的主机」，正是 §4.3 要消除的降级攻击面。
    /// 插件侧 `normalizeOuterPin` 同样是抛错而不是忽略。
    private static func normalizedPin(_ raw: String?) throws -> String {
        let trimmed = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        let hex = trimmed.replacingOccurrences(of: ":", with: "").lowercased()
        guard hex.count == 64, hex.allSatisfy({ $0.isHexDigit }) else {
            throw RouteBuildError.invalidOuterPin
        }
        return hex
    }
}

/// 凭据 → 路线的类型化失败。
///
/// 与「凭据不齐 → nil」区分开：`nil` 表示该主机**没有**远程能力（正常回退 LAN）；
/// 这里的错误表示**有**远程配置但配置本身非法 —— 属于必须让用户看见的问题，
/// 不能悄悄走 LAN 或悄悄降级。
public enum RouteBuildError: Error, Equatable, Sendable {
    /// `outerPin` 存在但不是 64 位 hex。
    case invalidOuterPin
}
