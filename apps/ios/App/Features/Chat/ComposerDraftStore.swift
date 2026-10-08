import CryptoKit
import DLSecurity
import Foundation

/// 会话级草稿仓库（C02）。
///
/// 与旧实现的两点本质差异：
/// 1. 落盘键是 `(hostID, sessionID | draftID+workspaceID, kind)`，不再只有 hostID，
///    同一台电脑的 A/B 会话草稿不再互相覆盖。
/// 2. 正文进 AES-GCM 密文（沿用 `TranscriptSnapshotBox` 的做法：Keychain 存密钥，
///    沙盒只存密文），不再是明文 txt。
///
/// 仓库只认键与记录，**UI 不拼路径**。附件只存应用持有的受保护引用，
/// 失效附件不会被当成已附上（C02 要求 7）。
public struct ComposerDraftStore: Sendable {
    /// 正文超过这个长度就不落盘（例如粘贴了整本日志）。内存副本仍然保留。
    public static let textByteLimit = 256 * 1024

    var directory: URL
    var keys: any SecureStore
    var fileManager: FileManager
    /// 单测注入固定时钟，生产用系统时钟。
    var now: @Sendable () -> Date

    public init(
        directory: URL,
        keys: any SecureStore = KeychainStore(),
        fileManager: FileManager = .default,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.directory = directory
        self.keys = keys
        self.fileManager = fileManager
        self.now = now
    }

    /// 生产位置：Application Support 下独立目录，密文 + 文件保护。
    public static func live(
        keys: any SecureStore = KeychainStore(), fileManager: FileManager = .default
    ) -> ComposerDraftStore {
        let base =
            fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return ComposerDraftStore(
            directory: base.appendingPathComponent("ComposerDrafts", isDirectory: true),
            keys: keys, fileManager: fileManager)
    }

    // MARK: - 读写

    /// 读不到、解不开、版本不认识都当没有草稿。宁可让用户重写，也不读错字段。
    public func load(_ key: ComposerDraftKey) -> ComposerDraftRecord? {
        let url = file(key)
        guard let raw = try? Data(contentsOf: url), let plaintext = try? decrypt(raw) else { return nil }
        guard var record = try? JSONDecoder().decode(ComposerDraftRecord.self, from: plaintext),
            record.isReadable
        else { return nil }
        // 失效附件不得被当成已附上：读的时候只保留引用还在的那些。
        record.attachments = record.attachments.filter {
            fileManager.fileExists(atPath: attachmentFile(key, bookmark: $0.bookmark).path)
        }
        return record
    }

    public func text(_ key: ComposerDraftKey) -> String {
        load(key)?.text ?? ""
    }

    /// 合并写入的入口。正文为空等于删除该槽位（连附件一起）。
    ///
    /// 返回值让调用方知道要不要提示：写失败时内存副本必须留着，不能当用户已经保存。
    @discardableResult
    public func save(_ key: ComposerDraftKey, text: String) -> ComposerDraftWriteResult {
        var record =
            load(key)
            ?? ComposerDraftRecord(text: "", sessionID: key.sessionID, workspaceID: key.workspaceID)
        record.text = text
        return save(key, record: record)
    }

    @discardableResult
    public func save(_ key: ComposerDraftKey, record: ComposerDraftRecord) -> ComposerDraftWriteResult {
        let url = file(key)
        let trimmed = record.text
        guard !trimmed.isEmpty else {
            remove(key)
            return .persisted
        }
        guard trimmed.utf8.count <= Self.textByteLimit else { return .failed }
        var payload = record
        payload.text = trimmed
        payload.updatedAt = now()
        payload.sessionID = key.sessionID
        payload.workspaceID = key.workspaceID
        guard let plaintext = try? JSONEncoder().encode(payload), let sealed = try? encrypt(plaintext) else {
            return .failed
        }
        do {
            try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try sealed.write(to: url, options: .atomic)
            var attributes: [FileAttributeKey: Any] = [:]
            attributes[.protectionKey] = FileProtectionType.completeUntilFirstUserAuthentication
            try? fileManager.setAttributes(attributes, ofItemAtPath: url.path)
            return .persisted
        } catch {
            return .failed
        }
    }

    /// 只删这一个键的草稿（连它自己的附件引用一起）。
    /// **绝不能**顺手清掉该主机下的其它会话草稿（C02 要求 9）。
    public func remove(_ key: ComposerDraftKey) {
        if let record = load(key) {
            for attachment in record.attachments {
                try? fileManager.removeItem(at: attachmentFile(key, bookmark: attachment.bookmark))
            }
        }
        try? fileManager.removeItem(at: file(key))
    }

    /// 会话删除 / 归档 / 设备解绑时的处置：只动这一个会话自己的三个草稿槽。
    /// 其它会话、其它主机的草稿一律不动。
    public func removeAll(hostID: String, sessionID: String) {
        for kind in ComposerDraftKind.allCases {
            remove(ComposerDraftKey(hostID: hostID, sessionID: sessionID, kind: kind))
        }
    }

    /// 用户从"恢复的草稿"里选了目标会话后，把草稿搬过去并清掉孤儿条目。
    public func adopt(_ orphan: OrphanDraft, to key: ComposerDraftKey) -> ComposerDraftWriteResult {
        let result = save(
            key,
            record: ComposerDraftRecord(
                text: orphan.text, updatedAt: orphan.updatedAt, sessionID: key.sessionID,
                workspaceID: key.workspaceID))
        guard result == .persisted else { return result }
        let legacy = legacyDirectory(hostID: key.hostID).appendingPathComponent("\(orphan.id).txt")
        try? fileManager.removeItem(at: legacy)
        return .persisted
    }

    // MARK: - 附件引用

    /// 把附件字节写进应用自己的受保护容器，返回只含引用的句柄。
    /// 引用按槽位隔离：`remove` 该槽位时才删，不会误删别的草稿还在用的附件。
    public func writeAttachment(_ key: ComposerDraftKey, mediaType: String, data: Data) -> ComposerDraftAttachment? {
        guard !data.isEmpty else { return nil }
        let bookmark =
            "\(digest(key.storageID))-\(digest(mediaType))-\(SHA256.hash(data: data).prefix(8).map { String(format: "%02x", $0) }.joined())"
        let url = attachmentFile(key, bookmark: bookmark)
        do {
            try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            guard let sealed = try? encrypt(data) else { return nil }
            try sealed.write(to: url, options: .atomic)
            return ComposerDraftAttachment(bookmark: bookmark, mediaType: mediaType, isValid: true)
        } catch {
            return nil
        }
    }

    /// 读回附件字节；引用失效（文件没了）返回 nil，调用方据此不把它当已附上。
    public func readAttachment(_ key: ComposerDraftKey, bookmark: String) -> Data? {
        let url = attachmentFile(key, bookmark: bookmark)
        guard let raw = try? Data(contentsOf: url) else { return nil }
        return try? decrypt(raw)
    }

    // MARK: - 旧草稿迁移

    /// 旧版本只按 hostID 写一个明文 txt，无法推断它属于哪个会话。
    ///
    /// 这里**只列出来给用户选目标**，不自动归到任意会话，更不会自动发送（C02 要求 8）。
    public func orphanDrafts(hostID: String) -> [OrphanDraft] {
        let hostDirectory = legacyDirectory(hostID: hostID)
        guard let names = try? fileManager.contentsOfDirectory(atPath: hostDirectory.path) else { return [] }
        var drafts: [OrphanDraft] = []
        for name in names where name.hasSuffix(".txt") {
            let url = hostDirectory.appendingPathComponent(name)
            guard
                let text = try? String(contentsOf: url, encoding: .utf8),
                !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                let timestamp = (try? fileManager.attributesOfItem(atPath: url.path))?[.modificationDate] as? Date
            else { continue }
            drafts.append(
                OrphanDraft(
                    id: (name as NSString).deletingPathExtension, text: text, updatedAt: timestamp))
        }
        return drafts.sorted { $0.updatedAt > $1.updatedAt }
    }

    // MARK: - 内部

    func file(_ key: ComposerDraftKey) -> URL {
        currentDirectory(hostID: key.hostID)
            .appendingPathComponent("\(digest(key.storageID)).\(key.kind.slug).bin")
    }

    /// 旧实现把明文 txt 直接放在以 host 摘要命名的目录里；新草稿换到 `current/`，
    /// 两边隔离，旧文件不会被当成新草稿，新草稿也不会被当成待认领的孤儿。
    /// 暴露给单测：用来铺一份旧版本遗留的明文草稿。
    func legacyDirectory(hostID: String) -> URL {
        directory
            .appendingPathComponent(digest(hostID), isDirectory: true)
            .appendingPathComponent("legacy", isDirectory: true)
    }

    private func currentDirectory(hostID: String) -> URL {
        directory
            .appendingPathComponent(digest(hostID), isDirectory: true)
            .appendingPathComponent("current", isDirectory: true)
    }

    func attachmentFile(_ key: ComposerDraftKey, bookmark: String) -> URL {
        let safe = bookmark.unicodeScalars.map { scalar -> String in
            CharacterSet.alphanumerics.contains(scalar) ? String(scalar) : "-"
        }.joined()
        // 按槽位隔离：删这个草稿的附件不会碰到别的草稿还在用的引用。
        return
            directory
            .appendingPathComponent("attachments", isDirectory: true)
            .appendingPathComponent("\(safe.isEmpty ? "unnamed" : safe).bin")
    }

    private func encrypt(_ plaintext: Data) throws -> Data {
        let sealed = try AES.GCM.seal(plaintext, using: try loadOrCreateKey())
        guard let combined = sealed.combined else { throw ComposerDraftFailure.seal }
        return combined
    }

    private func decrypt(_ combined: Data) throws -> Data {
        try AES.GCM.open(try AES.GCM.SealedBox(combined: combined), using: try existingKey())
    }

    private func existingKey() throws -> SymmetricKey {
        guard let stored = try keys.data(forKey: keyAccount), stored.count == 32 else {
            throw ComposerDraftFailure.keyStore
        }
        return SymmetricKey(data: stored)
    }

    private func loadOrCreateKey() throws -> SymmetricKey {
        if let stored = try? keys.data(forKey: keyAccount) {
            guard stored.count == 32 else { throw ComposerDraftFailure.keyStore }
            return SymmetricKey(data: stored)
        }
        let key = SymmetricKey(size: .bits256)
        let raw = key.withUnsafeBytes { Data($0) }
        do {
            try keys.setData(raw, forKey: keyAccount)
        } catch {
            throw ComposerDraftFailure.keyStore
        }
        return key
    }

    private var keyAccount: String { "composer.draft.key" }

    private func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

public enum ComposerDraftFailure: Error, Equatable {
    case keyStore
    case seal
}
