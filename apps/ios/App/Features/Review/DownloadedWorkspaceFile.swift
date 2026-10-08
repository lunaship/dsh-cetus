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
    static let maximumBytes = 8 * 1024 * 1024
    static let inlineTextBytes = 512 * 1024

    static func prepare(
        _ file: DownloadedWorkspaceFile, path: String,
        root: URL = FileManager.default.temporaryDirectory.appendingPathComponent("WorkspaceExports", isDirectory: true)
    ) throws -> OpenedFile {
        guard file.data.count <= maximumBytes else { throw ConversationServiceError.failed }
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
