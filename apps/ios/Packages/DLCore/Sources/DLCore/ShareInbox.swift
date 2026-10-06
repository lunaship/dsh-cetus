import Foundation

/// I7.2 / 8.3. The share extension writes one item; the app only prefills a composer.
public enum ShareAppGroup {
    public static let identifier = "group.dev.deeplinks.ios"
    public static let scheme = "deeplinks"
    /// Decoded image bytes. Base64 growth is checked again by classifyPromptAttachment.
    public static let imageByteLimit = 3 * 1024 * 1024
    public static let recentLimit = 6
    public static let textLimit = 100_000
}

public enum ShareInboxError: Error, Equatable, Sendable {
    case empty
    case tooLarge
    case unsafePath
    case unreadable
}

public struct ShareRecentSession: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var detail: String
    public var updatedAt: Int

    public init(id: String, title: String, detail: String, updatedAt: Int) {
        self.id = id
        self.title = title
        self.detail = detail
        self.updatedAt = updatedAt
    }
}

public struct ShareInboxRecord: Codable, Equatable, Sendable, Identifiable {
    public enum Kind: String, Codable, Equatable, Sendable {
        case text
        case image
    }

    public var id: String
    public var kind: Kind
    public var text: String
    public var imageFilename: String?
    public var createdAt: Date

    public init(
        id: String, kind: Kind, text: String, imageFilename: String? = nil, createdAt: Date
    ) {
        self.id = id
        self.kind = kind
        self.text = text
        self.imageFilename = imageFilename
        self.createdAt = createdAt
    }
}

public enum ShareTarget: Equatable, Sendable {
    case newTask
    case session(String)
}

/// What the chosen composer receives. Nothing here is transmitted.
public struct SharePrefill: Equatable, Sendable {
    public var target: ShareTarget
    public var text: String
    public var images: [PromptImage]

    public init(target: ShareTarget, text: String, images: [PromptImage] = []) {
        self.target = target
        self.text = text
        self.images = images
    }
}

public enum ShareInbox {
    public static func makeID() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    public static func openURL(id: String) -> URL? {
        guard isSafeID(id) else { return nil }
        return URL(string: "\(ShareAppGroup.scheme)://share/\(id)")
    }

    public static func id(from url: URL) -> String? {
        guard url.scheme == ShareAppGroup.scheme, url.host == "share" else { return nil }
        let id = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard isSafeID(id), !id.contains("/") else { return nil }
        return id
    }

    public static func textRecord(text: String, id: String = makeID(), now: Date = Date())
        -> Result<ShareInboxRecord, ShareInboxError>
    {
        let trimmed = String(text.prefix(ShareAppGroup.textLimit))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, isSafeID(id) else { return .failure(.empty) }
        return .success(ShareInboxRecord(id: id, kind: .text, text: trimmed, createdAt: now))
    }

    public static func imageRecord(
        bytes: Data, filename: String? = nil, text: String = "", id: String = makeID(), now: Date = Date()
    ) -> Result<ShareInboxRecord, ShareInboxError> {
        guard isSafeID(id) else { return .failure(.empty) }
        guard !bytes.isEmpty, bytes.count <= ShareAppGroup.imageByteLimit else { return .failure(.tooLarge) }
        let storedName = "\(id).img"
        guard filename == nil || filename == storedName else { return .failure(.unsafePath) }
        let trimmed = String(text.prefix(ShareAppGroup.textLimit))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return .success(
            ShareInboxRecord(
                id: id, kind: .image, text: trimmed, imageFilename: storedName, createdAt: now))
    }

    public static func recentSessions<S>(
        _ sessions: [S], id: (S) -> String?, title: (S) -> String?, detail: (S) -> String,
        updatedAt: (S) -> Int?, limit: Int = ShareAppGroup.recentLimit
    ) -> [ShareRecentSession] {
        sessions.compactMap { session -> ShareRecentSession? in
            guard let rawID = id(session)?.trimmingCharacters(in: .whitespacesAndNewlines),
                isSafeID(rawID),
                let rawTitle = title(session)?.trimmingCharacters(in: .whitespacesAndNewlines),
                !rawTitle.isEmpty
            else { return nil }
            return ShareRecentSession(
                id: rawID, title: rawTitle, detail: detail(session), updatedAt: updatedAt(session) ?? 0)
        }
        .sorted { $0.updatedAt > $1.updatedAt }
        .prefix(limit)
        .map { $0 }
    }

    /// Choosing a row builds a draft. The caller must not send it.
    public static func prefill(
        record: ShareInboxRecord, image: PromptImage?, target: ShareTarget
    ) -> SharePrefill {
        SharePrefill(
            target: target, text: record.text,
            images: record.kind == .image ? (image.map { [$0] } ?? []) : [])
    }

    public static func isSafeID(_ id: String) -> Bool {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789")
        return (8...64).contains(id.count) && id.unicodeScalars.allSatisfy { allowed.contains($0) }
    }
}

/// File container shared by the extension and the app. Tests pass a temporary root.
public struct ShareGroupStore: Sendable {
    public var root: URL

    public init(root: URL) {
        self.root = root
    }

    public static func live(fileManager: FileManager = .default) -> ShareGroupStore? {
        guard
            let root = fileManager.containerURL(
                forSecurityApplicationGroupIdentifier: ShareAppGroup.identifier)
        else { return nil }
        return ShareGroupStore(root: root)
    }

    public func writeRecord(_ record: ShareInboxRecord, image: Data? = nil) throws {
        guard ShareInbox.isSafeID(record.id) else { throw ShareInboxError.unsafePath }
        if record.kind == .image {
            guard let image, image.count <= ShareAppGroup.imageByteLimit,
                record.imageFilename == "\(record.id).img"
            else { throw ShareInboxError.tooLarge }
            try image.write(to: try file(named: record.imageFilename ?? ""), options: .atomic)
        }
        let data = try JSONEncoder().encode(record)
        try data.write(to: try file(named: "\(record.id).json"), options: .atomic)
        try replaceSnapshot(record)
    }

    public func readRecord(id: String) -> ShareInboxRecord? {
        guard ShareInbox.isSafeID(id), let data = try? Data(contentsOf: fileURL(named: "\(id).json")) else {
            return nil
        }
        return try? JSONDecoder().decode(ShareInboxRecord.self, from: data)
    }

    public func imageData(for record: ShareInboxRecord) -> Data? {
        guard record.kind == .image, let name = record.imageFilename, name == "\(record.id).img" else {
            return nil
        }
        return try? Data(contentsOf: fileURL(named: name))
    }

    public func writeRecents(_ sessions: [ShareRecentSession]) throws {
        let data = try JSONEncoder().encode(Array(sessions.prefix(ShareAppGroup.recentLimit)))
        try data.write(to: try file(named: "recent-sessions.json"), options: .atomic)
    }

    public func readRecents() -> [ShareRecentSession] {
        guard let data = try? Data(contentsOf: fileURL(named: "recent-sessions.json")),
            let sessions = try? JSONDecoder().decode([ShareRecentSession].self, from: data)
        else { return [] }
        return Array(sessions.prefix(ShareAppGroup.recentLimit))
    }

    public func consume(id: String) {
        guard let record = readRecord(id: id) else { return }
        if let name = record.imageFilename { try? FileManager.default.removeItem(at: fileURL(named: name)) }
        try? FileManager.default.removeItem(at: fileURL(named: "\(id).json"))
    }

    private func replaceSnapshot(_ record: ShareInboxRecord) throws {
        let data = try JSONEncoder().encode(record)
        try data.write(to: try file(named: "latest.json"), options: .atomic)
    }

    private func file(named name: String) throws -> URL {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return fileURL(named: name)
    }

    private func fileURL(named name: String) -> URL {
        let safe = name.split(separator: "/").count == 1 && !name.hasPrefix(".") ? name : "rejected"
        return root.appendingPathComponent(safe, isDirectory: false)
    }
}
