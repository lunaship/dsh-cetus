import Foundation

/// 草稿按主机落盘。发送成功才清空。进程在发送途中被回收后，下次打开回填，不自动重发。
struct ComposerDraftStore: Sendable {
    var directory: URL

    func load(hostID: String) -> String {
        guard let url = file(hostID) else { return "" }
        return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
    }

    func save(hostID: String, text: String) {
        guard let url = file(hostID) else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if text.isEmpty {
            try? FileManager.default.removeItem(at: url)
            return
        }
        try? text.write(to: url, atomically: true, encoding: .utf8)
    }

    private func file(_ hostID: String) -> URL? {
        let name = hostID.unicodeScalars.map { scalar -> String in
            CharacterSet.alphanumerics.contains(scalar) ? String(scalar) : "_"
        }.joined()
        guard !name.isEmpty else { return nil }
        return directory.appendingPathComponent(name).appendingPathExtension("txt")
    }
}
