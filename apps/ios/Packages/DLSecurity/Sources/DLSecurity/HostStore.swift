import DLModels
import Foundation

/// 已配对电脑（PLAN I3.3）。`remote` 用 DLModels 的 `DeviceRemoteInfo`（RFC 0001 §6.3）；
/// 持久化时其敏感字段（`relayKey`、`outerCertificatePin`）拆进 SecureStore，见 `HostStore`。
public struct PairedHost: Equatable, Sendable {
    public var hostId: String
    public var name: String
    public var primaryUrl: String
    public var tailnetUrl: String?
    public var certFingerprint: String
    public var remote: DeviceRemoteInfo?
    /// 配对时间（Unix 毫秒，与合同其他时间戳同口径）。
    public var pairedAt: Int

    public init(
        hostId: String,
        name: String,
        primaryUrl: String,
        tailnetUrl: String? = nil,
        certFingerprint: String,
        remote: DeviceRemoteInfo? = nil,
        pairedAt: Int
    ) {
        self.hostId = hostId
        self.name = name
        self.primaryUrl = primaryUrl
        self.tailnetUrl = tailnetUrl
        self.certFingerprint = certFingerprint
        self.remote = remote
        self.pairedAt = pairedAt
    }
}

/// 已配对电脑的本地存储（I3.3）。
///
/// **拆分规则（PLAN 第 3 节红线：凭据只存 Keychain）**
/// - SecureStore（生产实现 = Keychain）：设备 token、证书指纹（主机证书与远程外层证书）、
///   远程会合密钥。key 按 hostId 区分：`host.<hostId>.token` / `.certFingerprint` /
///   `.remote.relayKey` / `.remote.outerCertificatePin`。
/// - App 沙盒 JSON：hostId、name、primaryUrl、tailnetUrl、pairedAt，以及 remote 的路由元数据
///   （endpoint、routeId、deviceHandle——中继用它做查找，真正鉴权靠密钥）。JSON 里不得出现
///   token、指纹、密钥。
///
/// **一致性规则：「凭据缺失 = 未配对」**
/// 初始化时对账：
/// - JSON 条目缺 Keychain 里的 token 或指纹 → 整条按未配对丢弃，并清掉该 hostId 的全部
///   Keychain 条目；
/// - Keychain 有 `host.` 前缀条目但 JSON 里没有对应 hostId（重装后沙盒必清而 Keychain 可能
///   残留）→ 删除这些孤儿条目；
/// - remote 元数据在而会合密钥缺失 → 只作废远程、保留局域网配对（bootstrap 会在下次连接时
///   自动补齐远程能力，RFC 0001 §6.4）；
/// - 沙盒文件存在但读不出（损坏 / 格式不符）→ 同样按未配对处理，重写为空。
///
/// **红线：不得因为远程或中继的错误码删除凭据。** HostStore 没有任何由网络结果触发的删除；
/// 清凭据只有两条路：初始化对账（上面四条），以及用户显式的 `delete(hostId:)`。
public actor HostStore {
    private static let keyPrefix = "host."
    private static let keychainFieldSuffixes = [
        "token",
        "certFingerprint",
        "remote.relayKey",
        "remote.outerCertificatePin",
    ]

    private let fileURL: URL
    private let secureStore: SecureStore
    private var hosts: [PairedHost]

    /// - Parameters:
    ///   - fileURL: 沙盒 JSON 的位置；默认 Application Support 下的 `paired-hosts.json`。
    ///   - secureStore: 敏感字段的存储；默认固定 service 名的 `KeychainStore`。
    /// 初始化即完成加载与一致性对账（规则见类型注释），不会抛错：读不到就按未配对处理。
    public init(fileURL: URL? = nil, secureStore: SecureStore? = nil) {
        let resolvedURL = fileURL ?? Self.defaultFileURL()
        let resolvedStore = secureStore ?? KeychainStore()
        let (loaded, fileDirty) = Self.load(fileURL: resolvedURL, secureStore: resolvedStore)
        if fileDirty {
            // 对账清掉的部分同步写回磁盘；失败不阻断启动，下次 save 再修。
            try? Self.writeFile(records: loaded.map({ Self.record(of: $0) }), at: resolvedURL)
        }
        self.fileURL = resolvedURL
        self.secureStore = resolvedStore
        self.hosts = loaded
    }

    /// 全部已配对电脑。
    public func all() -> [PairedHost] {
        hosts
    }

    /// 按 hostId 取一台；不存在返回 nil。
    public func get(hostId: String) -> PairedHost? {
        hosts.first(where: { $0.hostId == hostId })
    }

    /// 保存一台已配对电脑（同一 hostId 覆盖）。token 是配对响应下发的设备 token。
    public func save(host: PairedHost, token: String) throws {
        try writeSensitiveParts(of: host, token: token)
        if let index = hosts.firstIndex(where: { $0.hostId == host.hostId }) {
            hosts[index] = host
        } else {
            hosts.append(host)
        }
        try Self.writeFile(records: hosts.map({ Self.record(of: $0) }), at: fileURL)
    }

    /// 取设备 token。读取侧同样遵守「凭据缺失 = 未配对」：内存里没有这台主机时，
    /// 即使 Keychain 有残留也不返回 token。
    public func token(for hostId: String) -> String? {
        guard hosts.contains(where: { $0.hostId == hostId }) else { return nil }
        guard let data = try? secureStore.data(forKey: Self.tokenKey(hostId)) else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    /// 显式解除配对：同时清 JSON 条目与 Keychain 条目。
    /// 这是网络结果之外唯一的凭据删除入口；Keychain 删除失败不阻断——JSON/内存照样清，
    /// 残留由下次初始化的对账兜底。
    public func delete(hostId: String) throws {
        Self.removeHostKeys(hostId: hostId, secureStore: secureStore)
        hosts.removeAll(where: { $0.hostId == hostId })
        try Self.writeFile(records: hosts.map({ Self.record(of: $0) }), at: fileURL)
    }

    /// 默认文件位置：Application Support 下的 `paired-hosts.json`。
    public static func defaultFileURL() -> URL {
        let base =
            FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return base.appendingPathComponent("paired-hosts.json")
    }

    // MARK: - 敏感字段与 Keychain 的拆分

    /// 把敏感字段写进 SecureStore。先 Keychain 后 JSON（调用方负责顺序）：
    /// 让「JSON 条目存在 ⇒ 凭据已在 Keychain」尽量成立，中途失败的半态由初始化对账兜底。
    private func writeSensitiveParts(of host: PairedHost, token: String) throws {
        try secureStore.setData(Data(token.utf8), forKey: Self.tokenKey(host.hostId))
        try secureStore.setData(Data(host.certFingerprint.utf8), forKey: Self.certFingerprintKey(host.hostId))
        if let relayKey = host.remote?.relayKey {
            try secureStore.setData(Data(relayKey.utf8), forKey: Self.remoteRelayKeyKey(host.hostId))
        } else {
            try? secureStore.removeData(forKey: Self.remoteRelayKeyKey(host.hostId))
        }
        if let outerPin = host.remote?.outerCertificatePin {
            try secureStore.setData(Data(outerPin.utf8), forKey: Self.remoteOuterPinKey(host.hostId))
        } else {
            try? secureStore.removeData(forKey: Self.remoteOuterPinKey(host.hostId))
        }
    }

    private static func removeHostKeys(hostId: String, secureStore: SecureStore) {
        for key in [tokenKey(hostId), certFingerprintKey(hostId), remoteRelayKeyKey(hostId), remoteOuterPinKey(hostId)]
        {
            try? secureStore.removeData(forKey: key)
        }
    }

    private static func removeRemoteKeys(hostId: String, secureStore: SecureStore) {
        try? secureStore.removeData(forKey: remoteRelayKeyKey(hostId))
        try? secureStore.removeData(forKey: remoteOuterPinKey(hostId))
    }

    private static func tokenKey(_ hostId: String) -> String { keyPrefix + hostId + ".token" }

    private static func certFingerprintKey(_ hostId: String) -> String { keyPrefix + hostId + ".certFingerprint" }

    private static func remoteRelayKeyKey(_ hostId: String) -> String { keyPrefix + hostId + ".remote.relayKey" }

    private static func remoteOuterPinKey(_ hostId: String) -> String {
        keyPrefix + hostId + ".remote.outerCertificatePin"
    }

    /// 从 key 反解 hostId；不认识的 key 返回 nil（不动别人的条目）。
    private static func hostId(inKey key: String) -> String? {
        guard key.hasPrefix(keyPrefix) else { return nil }
        let rest = key.dropFirst(keyPrefix.count)
        for suffix in keychainFieldSuffixes {
            let tail = "." + suffix
            if rest.hasSuffix(tail) {
                return String(rest.dropLast(tail.count))
            }
        }
        return nil
    }

    // MARK: - 沙盒 JSON（只含非敏感字段）

    private static func load(fileURL: URL, secureStore: SecureStore) -> (hosts: [PairedHost], fileDirty: Bool) {
        var hosts: [PairedHost] = []
        var fileDirty = false

        let records = try? readFile(at: fileURL)
        if records == nil, FileManager.default.fileExists(atPath: fileURL.path) {
            // 文件在但读不出（损坏 / 旧格式）：按未配对处理并重写为空。
            fileDirty = true
        }
        for record in records ?? [] {
            guard !hosts.contains(where: { $0.hostId == record.hostId }) else {
                fileDirty = true  // 防御：重复条目只留第一条
                continue
            }
            // 「凭据缺失 = 未配对」：JSON 条目必须同时有 token 与证书指纹两把 Keychain 凭据，
            // 缺任何一把就整条作废并清掉该 hostId 的全部 Keychain 残留。
            let tokenData = try? secureStore.data(forKey: tokenKey(record.hostId))
            guard tokenData != nil,
                let fingerprintData = try? secureStore.data(forKey: certFingerprintKey(record.hostId))
            else {
                removeHostKeys(hostId: record.hostId, secureStore: secureStore)
                fileDirty = true
                continue
            }
            var remote: DeviceRemoteInfo?
            if record.remote != nil, let relayKeyData = try? secureStore.data(forKey: remoteRelayKeyKey(record.hostId))
            {
                remote = DeviceRemoteInfo(
                    endpoint: record.remote?.endpoint,
                    routeId: record.remote?.routeId,
                    deviceHandle: record.remote?.deviceHandle,
                    relayKey: String(decoding: relayKeyData, as: UTF8.self),
                    outerCertificatePin: optionalString(try? secureStore.data(forKey: remoteOuterPinKey(record.hostId)))
                )
            } else {
                if record.remote != nil {
                    // 远程元数据在而会合密钥缺失：只作废远程、保留局域网配对（见类型注释）。
                    fileDirty = true
                }
                removeRemoteKeys(hostId: record.hostId, secureStore: secureStore)
            }
            hosts.append(
                PairedHost(
                    hostId: record.hostId,
                    name: record.name,
                    primaryUrl: record.primaryUrl,
                    tailnetUrl: record.tailnetUrl,
                    certFingerprint: String(decoding: fingerprintData, as: UTF8.self),
                    remote: remote,
                    pairedAt: record.pairedAt
                )
            )
        }

        // Keychain 孤儿（JSON 里没有对应 hostId）：重装后沙盒被清而 Keychain 残留的正是这种。
        let known = Set(hosts.map(\.hostId))
        if let keys = try? secureStore.allKeys() {
            for key in keys {
                guard key.hasPrefix(keyPrefix), let orphanHostId = hostId(inKey: key) else { continue }
                if !known.contains(orphanHostId) {
                    try? secureStore.removeData(forKey: key)
                }
            }
        }

        return (hosts, fileDirty)
    }

    private static func readFile(at url: URL) throws -> [HostRecord] {
        try JSONDecoder().decode([HostRecord].self, from: Data(contentsOf: url))
    }

    private static func writeFile(records: [HostRecord], at url: URL) throws {
        // Application Support 在 iOS 上不保证已建好，先确保父目录存在。
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(records)
        try data.write(to: url, options: writeOptions())
    }

    private static func writeOptions() -> Data.WritingOptions {
        var options: Data.WritingOptions = [.atomic]
        #if os(iOS)
            // 红线补充：沙盒文件开「至首次解锁后」保护；该选项仅 iOS 可用。
            options.insert(.completeFileProtectionUntilFirstUserAuthentication)
        #endif
        return options
    }

    private static func optionalString(_ data: Data?) -> String? {
        data.flatMap { String(decoding: $0, as: UTF8.self) }
    }

    private static func record(of host: PairedHost) -> HostRecord {
        HostRecord(
            hostId: host.hostId,
            name: host.name,
            primaryUrl: host.primaryUrl,
            tailnetUrl: host.tailnetUrl,
            pairedAt: host.pairedAt,
            remote: host.remote.map {
                RemoteMeta(endpoint: $0.endpoint, routeId: $0.routeId, deviceHandle: $0.deviceHandle)
            }
        )
    }
}

/// 沙盒 JSON 里的单条记录：只含非敏感字段。`remote` 只存路由元数据；
/// relayKey 与 outerCertificatePin 在 SecureStore，不会出现在这个文件里。
private struct HostRecord: Codable {
    var hostId: String
    var name: String
    var primaryUrl: String
    var tailnetUrl: String?
    var pairedAt: Int
    var remote: RemoteMeta?
}

private struct RemoteMeta: Codable {
    var endpoint: String?
    var routeId: String?
    var deviceHandle: String?
}
