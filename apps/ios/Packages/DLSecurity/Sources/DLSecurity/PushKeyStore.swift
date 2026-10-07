import CryptoKit
import Foundation

/// Content key storage shared by the app and the notification extension.
///
/// The 32-byte key lives only in a SecureStore. The production store is one
/// Keychain item in the shared access group. Metadata never contains the key.
public struct PushKeyStore: Sendable {
    public static let service = "dev.deeplinks.ios.push"
    public static let accessGroup = "group.dev.deeplinks.ios"
    public static let keyAccount = "content-key"

    private let store: any SecureStore

    public init(store: any SecureStore) {
        self.store = store
    }

    public static func live() -> PushKeyStore {
        PushKeyStore(store: KeychainStore(service: service, accessGroup: accessGroup))
    }

    public func save(_ key: Data) throws {
        guard key.count == 32 else { throw PushKeyStoreError.invalidKey }
        try store.setData(key, forKey: Self.keyAccount)
    }

    public func load() throws -> Data? {
        guard let key = try store.data(forKey: Self.keyAccount) else { return nil }
        guard key.count == 32 else {
            try? store.removeData(forKey: Self.keyAccount)
            return nil
        }
        return key
    }

    public func remove() throws {
        try store.removeData(forKey: Self.keyAccount)
    }

    /// Returns whether the stored bytes changed.
    public func install(_ key: Data) throws -> Bool {
        if try load() == key { return false }
        try save(key)
        return true
    }
}

public enum PushKeyStoreError: Error, Equatable {
    case invalidKey
}

public enum PushContentKey {
    public static func generate() -> Data {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
    }
}
