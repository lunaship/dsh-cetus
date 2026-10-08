import DLCore
import Foundation

struct DownloadedWorkspaceFile: Sendable {
    var data: Data
    var filename: String?
    var contentType: String?
}

struct OpenedFile: Equatable, Sendable {
    var path: String
    var text: String?
    var kind: FilePreviewKind = .quickLook
    var url: URL?
    var failed = false
}

/// Each export gets its own protected directory so another download cannot overwrite a shared file.
/// Expiry runs on app launch, never on backgrounding while a share sheet may be using the URL.
enum WorkspaceFileExport {
    /// 缺省上限。插件会在 `files.maxBytes` 里声明自己的上限；拿不到声明时用这个值。
    static let maximumBytes = 8 * 1024 * 1024
    static let inlineTextBytes = 512 * 1024

    /// C09 验收「8MB 边界按协商能力」：把插件声明的上限收敛成一个可用值。
    /// 缺省（nil）或非正数时回退 `maximumBytes`，避免把 0 / 负数当成「什么都不许传」。
    static func effectiveMaximumBytes(declared: Int?) -> Int {
        guard let declared, declared > 0 else { return maximumBytes }
        return declared
    }

    static func prepare(
        _ file: DownloadedWorkspaceFile, path: String,
        maximumBytes: Int = WorkspaceFileExport.maximumBytes,
        root: URL = FileManager.default.temporaryDirectory.appendingPathComponent("WorkspaceExports", isDirectory: true)
    ) throws -> OpenedFile {
        let limit = effectiveMaximumBytes(declared: maximumBytes)
        guard file.data.count <= limit else { throw ConversationServiceError.failed }
        let decoded = file.filename?.removingPercentEncoding ?? file.filename
        let name = URL(fileURLWithPath: decoded ?? path).lastPathComponent
        guard !name.isEmpty, name != ".", name != ".." else { throw ConversationServiceError.failed }
        let directory = root.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true,
            attributes: [
                .posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication,
            ])
        let url = directory.appendingPathComponent(name)
        try file.data.write(to: url, options: .atomic)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: url.path)
        let kind = filePreviewKind(path: name, mime: file.contentType)
        let text =
            kind == .text && file.data.count <= inlineTextBytes && !file.data.contains(0)
            ? String(data: file.data, encoding: .utf8) : nil
        return OpenedFile(path: path, text: text, kind: text == nil ? .quickLook : .text, url: url)
    }

    static func expire(
        root: URL = FileManager.default.temporaryDirectory.appendingPathComponent(
            "WorkspaceExports", isDirectory: true),
        now: Date = Date()
    ) {
        let manager = FileManager.default
        guard
            let directories = try? manager.contentsOfDirectory(
                at: root, includingPropertiesForKeys: [.creationDateKey, .isSymbolicLinkKey])
        else { return }
        for directory in directories {
            guard directory.deletingLastPathComponent().standardizedFileURL.path == root.standardizedFileURL.path,
                let values = try? directory.resourceValues(forKeys: [.creationDateKey, .isSymbolicLinkKey]),
                values.isSymbolicLink != true, let created = values.creationDate,
                now.timeIntervalSince(created) > 24 * 60 * 60
            else { continue }
            try? manager.removeItem(at: directory)
        }
    }
}
