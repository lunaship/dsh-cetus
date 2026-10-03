import Foundation

/// 按 key 存取二进制凭据的最小接口（I3.3）。
/// 生产实现是 `KeychainStore`（Security 框架）；`InMemorySecureStore` 供单测与预览使用。
/// CI 的测试宿主未签名，模拟器上访问真 Keychain 会报 -34018，因此合同测试一律注入内存实现。
public protocol SecureStore: Sendable {
    /// 读取 key 对应的数据；不存在时返回 nil。
    func data(forKey key: String) throws -> Data?

    /// 写入 key 对应的数据（已存在时覆盖）。
    func setData(_ data: Data, forKey key: String) throws

    /// 删除 key；key 不存在时同样视为成功（幂等）。
    func removeData(forKey key: String) throws

    /// 列出本 store 内的全部 key（不含值）。HostStore 据此做孤儿清理。
    func allKeys() throws -> [String]
}

/// 线程安全的内存实现：单测与 SwiftUI 预览用。
public final class InMemorySecureStore: SecureStore, @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [String: Data] = [:]

    public init() {}

    public func data(forKey key: String) throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return storage[key]
    }

    public func setData(_ data: Data, forKey key: String) throws {
        lock.lock()
        defer { lock.unlock() }
        storage[key] = data
    }

    public func removeData(forKey key: String) throws {
        lock.lock()
        defer { lock.unlock() }
        storage.removeValue(forKey: key)
    }

    public func allKeys() throws -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return storage.keys.sorted()
    }
}
