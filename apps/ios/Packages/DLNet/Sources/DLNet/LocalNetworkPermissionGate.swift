import Foundation
import Network

#if canImport(UIKit)
    import UIKit
#endif

/// 仅保存“说明页已经展示”，不保存/冒充系统权限状态。UI 与 UserDefaults 访问隔离在 MainActor。
@MainActor
public protocol LocalNetworkPermissionStorage: AnyObject {
    var hasShownExplanation: Bool { get set }
}

@MainActor
public final class UserDefaultsLocalNetworkPermissionStorage: LocalNetworkPermissionStorage {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "dev.deeplinks.localNetwork.explanationShown") {
        self.defaults = defaults
        self.key = key
    }

    public var hasShownExplanation: Bool {
        get { defaults.bool(forKey: key) }
        set { defaults.set(newValue, forKey: key) }
    }
}

/// PLAN I3.7 / I4.1：连接前由 App 判断并展示说明页，展示后再标记；本层不写 SwiftUI。
@MainActor
public struct LocalNetworkPermissionGate {
    private let storage: any LocalNetworkPermissionStorage

    public init(storage: any LocalNetworkPermissionStorage = UserDefaultsLocalNetworkPermissionStorage()) {
        self.storage = storage
    }

    public func requiresExplanation(for address: String) -> Bool {
        !storage.hasShownExplanation && Self.mayRequireLocalNetworkAccess(address)
    }

    public func markExplanationShown() { storage.hasShownExplanation = true }

    public static var settingsURL: URL? {
        #if canImport(UIKit)
            URL(string: UIApplication.openSettingsURLString)
        #else
            nil
        #endif
    }

    /// 地址层的保守预判，不是系统权限查询，也不使用 `PinEvaluation.shouldPin`（钉扎不是权限）。
    /// Apple TN3179：本地网络由 Wi-Fi/以太网等可广播接口决定，VPN 不属于本地网络；
    /// `.local` DNS 查询也需要权限。https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
    ///
    /// 按 Android `isTailnetUrl` 识别 100.64/10、fd7a:115c:a1e0::/48，另识别 MagicDNS *.ts.net：
    /// 假定这些地址走 Tailscale VPN，跳过说明页。普通私网/链路本地地址需说明；回环与公网 IP 不需。
    /// 未解析的普通 DNS 主机可能指向 LAN，保守地先说明；不为判定权限发起 DNS/连接探测。
    /// 公网 IP 恰好配置在本地接口、或非标准路由时，地址预判无法确定实际接口，需真机验证。
    public nonisolated static func mayRequireLocalNetworkAccess(_ address: String) -> Bool {
        guard let host = URLComponents(string: address)?.host?.lowercased() else { return false }
        let name = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]."))
        if name == "localhost" || name.hasSuffix(".ts.net") { return false }
        let literal = String(name.split(separator: "%", maxSplits: 1).first ?? "")
        if let ipv4 = IPv4Address(literal) { return localIPv4(Array(ipv4.rawValue)) }
        if let ipv6 = IPv6Address(literal) {
            let bytes = Array(ipv6.rawValue)
            if bytes.prefix(6) == [0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0] { return false }
            if bytes.prefix(12) == Array(repeating: UInt8(0), count: 10) + [0xff, 0xff] {
                return localIPv4(Array(bytes.suffix(4)))
            }
            return bytes[0] & 0xfe == 0xfc || (bytes[0] == 0xfe && bytes[1] & 0xc0 == 0x80)
        }
        // IP 字面量解析失败不当作 DNS 主机；合法未解析 DNS 保守视为可能是 LAN。
        return !name.isEmpty && !name.contains(":") && name.contains(where: { $0.isLetter })
    }

    /// 从本次连接失败推断拒绝，不是持久的权限状态；说明页展示标记不会影响这个判定。
    /// Apple TN3179 推荐：NWConnection.waiting 的 currentPath.unsatisfiedReason == .localNetworkDenied；
    /// Bonjour 的 NWError.dns(kDNSServiceErr_PolicyDenied = -65570) 也表示策略拒绝。
    /// URLError 仅在 NSUnderlyingErrorKey 链中带上述明确 DNS 信号时推断；-1009、超时、
    /// EACCES/EPERM 或 wifiDenied 单独都不能证明本地网络被拒。不解析私有 _NSURLErrorNWPathKey。
    /// 系统首次弹窗时也可能暂时拒绝连接，故此结果只供诊断提示，不删除凭据、不永久缓存。
    public nonisolated static func isDenialInferred(
        error: any Error,
        for address: String,
        unsatisfiedReason: NWPath.UnsatisfiedReason? = nil
    ) -> Bool {
        guard mayRequireLocalNetworkAccess(address) else { return false }
        if unsatisfiedReason == .localNetworkDenied { return true }
        var current: any Error = error
        // 防御循环 NSError 底层链；无需解析调试字符串或非公开 userInfo 字段。
        for _ in 0..<8 {
            if let networkError = current as? NWError, case .dns(-65570) = networkError { return true }
            guard let underlying = (current as NSError).userInfo[NSUnderlyingErrorKey] as? any Error else {
                return false
            }
            current = underlying
        }
        return false
    }

    private nonisolated static func localIPv4(_ bytes: [UInt8]) -> Bool {
        if bytes[0] == 100 && (64...127).contains(bytes[1]) { return false }
        return bytes[0] == 10 || (bytes[0] == 172 && (16...31).contains(bytes[1]))
            || (bytes[0] == 192 && bytes[1] == 168) || (bytes[0] == 169 && bytes[1] == 254)
    }
}
