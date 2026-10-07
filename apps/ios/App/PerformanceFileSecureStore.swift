import DLSecurity
import Foundation

/// File-backed credentials for unsigned simulator runs.
/// Real keychain access returns -34018 there; production launches never use this store.
struct PerformanceFileSecureStore: SecureStore {
    let directory: URL

    func data(forKey key: String) throws -> Data? {
        let url = file(for: key)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try Data(contentsOf: url)
    }

    func setData(_ data: Data, forKey key: String) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: file(for: key), options: .atomic)
    }

    func removeData(forKey key: String) throws {
        let url = file(for: key)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        try FileManager.default.removeItem(at: url)
    }

    func allKeys() throws -> [String] {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .map { $0.deletingPathExtension().lastPathComponent.removingPercentEncoding ?? "" }
            .filter { !$0.isEmpty }
            .sorted()
    }

    private func file(for key: String) -> URL {
        let name = key.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "key"
        return directory.appendingPathComponent(name).appendingPathExtension("bin")
    }
}
