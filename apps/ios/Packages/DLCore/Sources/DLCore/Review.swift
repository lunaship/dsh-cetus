import CryptoKit
import DLModels
import Foundation

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

/// 路径必须是 `/密钥/预览 id/...`。对不上就拒绝，避免回环代理被本机其他页面借用。
public func previewTarget(path: String, key: String) -> String? {
    guard !key.isEmpty else { return nil }
    let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
    guard parts.count >= 3, parts[0].isEmpty, parts[1] == key, !parts[2].isEmpty else { return nil }
    return parts[2]
}

public func previewLoopbackURL(port: Int, key: String, previewID: String) -> String {
    "http://\(previewBindHost):\(port)/\(key)/\(previewID)/"
}
