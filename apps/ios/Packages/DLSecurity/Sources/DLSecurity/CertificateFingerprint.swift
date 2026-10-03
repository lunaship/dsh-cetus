import CryptoKit
import Foundation

/// 证书指纹的算法与格式（I3.4）。
///
/// 与插件 `src/tls.js` `certFingerprintSha256`（Node `X509Certificate.fingerprint256`
/// 去冒号转小写）、Android `PinnedSsl`（`MessageDigest` SHA-256 + `%02x`）三端一致；
/// 共享向量在仓库根 `testdata/tls-fingerprint/vectors.json`。
public enum CertificateFingerprint {
    /// DER 编码证书的 SHA-256 指纹：小写十六进制、无冒号（64 位）。
    public static func sha256(der: Data) -> String {
        SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }

    /// 规范化：转小写 → 去冒号 → 去空格 → 去首尾空白（与 Android `normalizeFingerprint` 一致）。
    public static func normalize(_ raw: String?) -> String {
        guard let raw else { return "" }
        let lowered = raw.lowercased().replacingOccurrences(of: ":", with: "")
        let noSpaces = lowered.replacingOccurrences(of: " ", with: "")
        return noSpaces.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 指纹格式校验：必须是 64 位小写十六进制（与 Android `requireValidPin` 一致）。
    public static func isValid(_ normalized: String) -> Bool {
        normalized.count == 64 && normalized.allSatisfy { "0123456789abcdef".contains($0) }
    }
}
