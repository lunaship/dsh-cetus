import CryptoKit
import DLModels
import Foundation
import Security

public func fileTitleParts(_ display: String) -> (directory: String, name: String) {
    let trimmed = display.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let slash = trimmed.lastIndex(of: "/") else { return ("", trimmed) }
    let name = String(trimmed[trimmed.index(after: slash)...])
    let directory = String(trimmed[..<slash])
    return (directory, name.isEmpty ? trimmed : name)
}

public func breadcrumbPaths(_ path: String) -> [String] {
    var crumbs = [""]
    var current = ""
    for part in path.split(separator: "/").map(String.init) where !part.isEmpty {
        current = current.isEmpty ? part : "\(current)/\(part)"
        crumbs.append(current)
    }
    return crumbs
}

public func diffLines(from hunks: [DiffHunk], budget: inout Int) -> [DiffLine] {
    var rows: [DiffLine] = []
    for hunk in hunks {
        let header =
            "@@ -\(hunk.oldStart ?? 0),\(hunk.oldLines ?? 0) +\(hunk.newStart ?? 0),\(hunk.newLines ?? 0) @@"
        rows.append(DiffLine(kind: .hunk, text: header))
        for line in hunk.lines ?? [] {
            if line.hasPrefix("+") {
                rows.append(DiffLine(kind: .add, text: String(line.dropFirst())))
            } else if line.hasPrefix("-") {
                rows.append(DiffLine(kind: .delete, text: String(line.dropFirst())))
            } else {
                let body = line.hasPrefix(" ") ? String(line.dropFirst()) : line
                rows.append(DiffLine(kind: .context, text: body))
            }
        }
    }
    return withIntralineEmphasis(rows, budget: &budget)
}

/// 空的期望值表示旧插件没给校验头，照单收下。有值就必须是 SHA-256 的十六进制，大小写不敏感。
public func sha256Matches(expectedHex: String?, bytes: Data) -> Bool {
    let expected = expectedHex?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
    guard !expected.isEmpty else { return true }
    let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    return digest == expected
}

public func acceptedDownload(bytes: Data, sha256 expected: String?) -> Data? {
    sha256Matches(expectedHex: expected, bytes: bytes) ? bytes : nil
}

public let previewBindHost = "127.0.0.1"

public enum FilePreviewKind: Equatable, Sendable {
    case text
    case image
    case quickLook
}

public func filePreviewKind(path: String, mime: String?) -> FilePreviewKind {
    let type = mime?.split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() } ?? ""
    if type.hasPrefix("text/") || type == "application/json" { return .text }
    if type.hasPrefix("image/") { return .image }
    let ext = URL(fileURLWithPath: path).pathExtension.lowercased()
    if ["txt", "md", "swift", "kt", "json", "js", "mjs", "css", "html", "xml", "yml", "yaml"].contains(ext) {
        return .text
    }
    if ["png", "jpg", "jpeg", "gif", "webp", "heic"].contains(ext) { return .image }
    return .quickLook
}

/// 只放行这个回环源。别的主机或端口交给系统浏览器。
public func previewNavigationAllowed(_ url: URL, loopbackPort: Int) -> Bool {
    guard url.scheme == "http" || url.scheme == "https" else { return false }
    guard url.host == previewBindHost, url.port == loopbackPort else { return false }
    return true
}

/// 把 `/密钥/预览id/剩余?查询` 变成插件预览路由。密钥按字节比较，预览 id 必须是 24 位十六进制。
public func mapPreviewPath(key: String, pathAndQuery: String) -> String? {
    if pathAndQuery.hasPrefix("http://") || pathAndQuery.hasPrefix("https://") { return nil }
    let queryStart = pathAndQuery.firstIndex(of: "?")
    let path = queryStart.map { String(pathAndQuery[..<$0]) } ?? pathAndQuery
    let query = queryStart.map { String(pathAndQuery[$0...]) } ?? ""
    let parts = path.split(separator: "/").map(String.init)
    guard parts.count >= 2, constantTimeEqual(parts[0], key) else { return nil }
    let id = parts[1]
    guard id.range(of: "^[a-f0-9]{24}$", options: .regularExpression) != nil else { return nil }
    if parts.dropFirst(2).contains(where: { $0 == "." || $0 == ".." }) { return nil }
    let rest = parts.dropFirst(2).joined(separator: "/")
    let suffix = rest.isEmpty ? "/" : "/\(rest)"
    return "/dsh-link/mobile/preview/\(id)\(suffix)\(query)"
}

public func previewLoopbackURL(port: Int, key: String, previewID: String) -> String {
    "http://\(previewBindHost):\(port)/\(key)/\(previewID)/"
}

public func previewRandomKey() -> String {
    var bytes = [UInt8](repeating: 0, count: 16)
    _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
    return bytes.map { String(format: "%02x", $0) }.joined()
}

private func constantTimeEqual(_ left: String, _ right: String) -> Bool {
    let a = Array(left.utf8)
    let b = Array(right.utf8)
    guard a.count == b.count else { return false }
    var diff: UInt8 = 0
    for index in a.indices { diff |= a[index] ^ b[index] }
    return diff == 0
}
