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

    /// Per-gateway-key binding of `kid` -> the device id this computer's
    /// payloads are encrypted for.
    ///
    /// RFC 0002 keeps `deviceId` out of the APNs payload, so the extension
    /// cannot read it from `userInfo`. It rebuilds the content AAD
    /// (`"dlpush/1 content|" + deviceId`) from this map instead, keyed by the
    /// `kid` that does travel in the payload. One entry per paired computer.
    /// Nothing stored here is a credential.
    public static let deviceBindingAccount = "content-device-bindings"

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

    // MARK: - kid -> deviceId bindings (shared with the notification extension)

    /// Binds `kid` to `deviceID`, keeping any other computer's entry.
    public func bind(kid: String, deviceID: String) throws {
        let trimmedKid = kid.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedDevice = deviceID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKid.isEmpty, !trimmedDevice.isEmpty else { return }
        var map = try loadBindings()
        map[trimmedKid] = trimmedDevice
        try store.setData(try JSONEncoder().encode(map), forKey: Self.deviceBindingAccount)
    }

    /// The device id bound to `kid`, or nil when this build never registered it.
    /// A missing binding must fall back to the generic notification rather than
    /// guess an AAD.
    public func deviceID(forKid kid: String) throws -> String? {
        let trimmed = kid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return try loadBindings()[trimmed]
    }

    public func removeBindings() throws {
        try store.removeData(forKey: Self.deviceBindingAccount)
    }

    private func loadBindings() throws -> [String: String] {
        guard let data = try store.data(forKey: Self.deviceBindingAccount) else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
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
