import Foundation
import Security

/// `KeychainStore` 的错误：携带 Security 框架的原始 `OSStatus`（如 `errSecItemNotFound`）
/// 与出错的操作，便于上层区分处理与诊断。
public struct KeychainError: Error, Equatable, Sendable {
    public enum Operation: String, Sendable {
        case get
        case add
        case update
        case delete
        case list
    }

    public let status: OSStatus
    public let operation: Operation

    public init(status: OSStatus, operation: Operation) {
        self.status = status
        self.operation = operation
    }
}

/// 基于 Security 框架 `kSecClassGenericPassword` 的凭据存储（I3.3）。
///
/// 红线属性固化在 `baseQuery` / `addAttributes` / `updateAttributes` 三个纯函数里：
/// - `kSecAttrAccessible = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
/// - `kSecAttrSynchronizable = false`（不进 iCloud 钥匙串）
/// - 固定的 `service` 名；可选 `accessGroup`（默认 nil，阶段 6 的 NSE 共享时再传入）
///
/// 纯函数是 `public` 的：单测（`HostStoreContractTests`）直接断言红线属性；
/// 本体方法只在运行期调用 SecItem。
public struct KeychainStore: SecureStore {
    /// 本 App 全部凭据共用的固定 service 名；各模块用 key 前缀区分（HostStore 用 `host.`）。
    public static let defaultService = "dev.deeplinks.ios"

    private let service: String
    private let accessGroup: String?

    /// - Parameters:
    ///   - service: 钥匙串条目的 `kSecAttrService`，默认 `defaultService`。
    ///   - accessGroup: 钥匙串访问组；默认 nil（App 默认组），NSE 需要共享时传共享组。
    public init(service: String = KeychainStore.defaultService, accessGroup: String? = nil) {
        self.service = service
        self.accessGroup = accessGroup
    }

    public func data(forKey key: String) throws -> Data? {
        var query = Self.itemQuery(service: service, accessGroup: accessGroup, account: key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            return result as? Data
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainError(status: status, operation: .get)
        }
    }

    public func setData(_ data: Data, forKey key: String) throws {
        // 更新优先，不存在时再新增（PLAN I3.3）。
        let query = Self.itemQuery(service: service, accessGroup: accessGroup, account: key)
        let updateStatus = SecItemUpdate(query as CFDictionary, Self.updateAttributes(data: data) as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw KeychainError(status: updateStatus, operation: .update)
        }
        let addStatus = SecItemAdd(
            Self.addAttributes(service: service, accessGroup: accessGroup, account: key, data: data) as CFDictionary,
            nil)
        guard addStatus == errSecSuccess else {
            throw KeychainError(status: addStatus, operation: .add)
        }
    }

    public func removeData(forKey key: String) throws {
        let query = Self.itemQuery(service: service, accessGroup: accessGroup, account: key)
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError(status: status, operation: .delete)
        }
    }

    public func allKeys() throws -> [String] {
        let query = Self.listQuery(service: service, accessGroup: accessGroup)
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let items = result as? [[String: Any]] else {
                throw KeychainError(status: errSecBadReq, operation: .list)
            }
            return items.compactMap { $0[kSecAttrAccount as String] as? String }.sorted()
        case errSecItemNotFound:
            return []
        default:
            throw KeychainError(status: status, operation: .list)
        }
    }

    // MARK: - 纯函数（可单测；不触碰钥匙串）

    /// 查询与新增共用的属性底座；红线属性在这里固化。
    public static func baseQuery(service: String, accessGroup: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: false,
            // iOS 上数据保护钥匙串本就是唯一实现，该键无害；macOS 上显式开启等价语义。
            kSecUseDataProtectionKeychain as String: true,
        ]
        if let accessGroup {
            query[kSecAttrAccessGroup as String] = accessGroup
        }
        return query
    }

    /// 定位单条条目的查询（get / update / delete 用）。
    public static func itemQuery(service: String, accessGroup: String?, account: String) -> [String: Any] {
        var query = baseQuery(service: service, accessGroup: accessGroup)
        query[kSecAttrAccount as String] = account
        return query
    }

    /// 列出全部条目的查询（allKeys 用）：只取属性、不限条数，同样带上红线属性。
    public static func listQuery(service: String, accessGroup: String?) -> [String: Any] {
        var query = baseQuery(service: service, accessGroup: accessGroup)
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitAll
        return query
    }

    /// `SecItemAdd` 的完整属性：底座 + account + 红线 accessible + 数据。
    public static func addAttributes(service: String, accessGroup: String?, account: String, data: Data) -> [String:
        Any]
    {
        var attributes = baseQuery(service: service, accessGroup: accessGroup)
        attributes[kSecAttrAccount as String] = account
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        attributes[kSecValueData as String] = data
        return attributes
    }

    /// `SecItemUpdate` 的待更新属性。重申 accessible：旧版本写入的弱保护条目在覆盖时被拉回红线要求；
    /// 不含 accessGroup——定位条目由查询负责，更新不应改组。
    public static func updateAttributes(data: Data) -> [String: Any] {
        [
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecAttrSynchronizable as String: false,
            kSecValueData as String: data,
        ]
    }
}
