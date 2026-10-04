import CryptoKit
import Foundation

/// 会话快照的独立钥匙串密钥（按主机一把）和沙盒密文。
/// 与配对凭据分开：打不开时只当没有快照，不改 token 或指纹。
public struct TranscriptSnapshotBox: Sendable {
    public enum Failure: Error, Equatable {
        case keyStore
        case unreadable
    }

    private let keys: any SecureStore
    private let directory: URL

    public init(keys: any SecureStore, directory: URL) {
        self.keys = keys
        self.directory = directory
    }

    public static func live() -> TranscriptSnapshotBox {
        let base =
            FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return TranscriptSnapshotBox(
            keys: KeychainStore(), directory: base.appendingPathComponent("TranscriptSnapshots", isDirectory: true))
    }

    public func fileURL(hostID: String, sessionID: String) -> URL {
        directory.appendingPathComponent(digest(hostID), isDirectory: true)
            .appendingPathComponent(digest(sessionID) + ".bin")
    }

    public func write(hostID: String, sessionID: String, plaintext: Data) throws {
        let key = try loadOrCreateKey(hostID: hostID)
        let sealed: AES.GCM.SealedBox
        do {
            sealed = try AES.GCM.seal(plaintext, using: key)
        } catch {
            throw Failure.unreadable
        }
        guard let combined = sealed.combined else { throw Failure.unreadable }
        let url = fileURL(hostID: hostID, sessionID: sessionID)
        do {
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try combined.write(to: url, options: .atomic)
        } catch {
            throw Failure.unreadable
        }
    }

    public func read(hostID: String, sessionID: String) throws -> Data? {
        let url = fileURL(hostID: hostID, sessionID: sessionID)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let combined: Data
        do {
            combined = try Data(contentsOf: url)
        } catch {
            throw Failure.unreadable
        }
        let key: SymmetricKey
        do {
            guard let existing = try existingKey(hostID: hostID) else { throw Failure.unreadable }
            key = existing
        } catch let failure as Failure {
            throw failure
        } catch {
            throw Failure.keyStore
        }
        do {
            let box = try AES.GCM.SealedBox(combined: combined)
            return try AES.GCM.open(box, using: key)
        } catch let failure as Failure {
            throw failure
        } catch {
            throw Failure.unreadable
        }
    }

    private func existingKey(hostID: String) throws -> SymmetricKey? {
        let account = keyAccount(hostID)
        let stored: Data?
        do {
            stored = try keys.data(forKey: account)
        } catch {
            throw Failure.keyStore
        }
        guard let stored else { return nil }
        guard stored.count == 32 else { throw Failure.unreadable }
        return SymmetricKey(data: stored)
    }

    private func loadOrCreateKey(hostID: String) throws -> SymmetricKey {
        let account = keyAccount(hostID)
        let stored: Data?
        do {
            stored = try keys.data(forKey: account)
        } catch {
            throw Failure.keyStore
        }
        if let stored {
            guard stored.count == 32 else { throw Failure.unreadable }
            return SymmetricKey(data: stored)
        }
        let key = SymmetricKey(size: .bits256)
        let raw = key.withUnsafeBytes { Data($0) }
        do {
            try keys.setData(raw, forKey: account)
        } catch {
            throw Failure.keyStore
        }
        return key
    }

    private func keyAccount(_ hostID: String) -> String {
        "transcript.key." + hostID
    }

    private func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
