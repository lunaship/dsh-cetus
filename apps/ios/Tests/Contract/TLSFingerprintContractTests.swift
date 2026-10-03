import DLSecurity
import Foundation
import Security
import Testing

/// I3.4 合同测试：仓库根 `testdata/tls-fingerprint/vectors.json` 的共享向量
/// （插件 `certFingerprintSha256` / Android `PinnedSsl` / iOS `DLSecurity` 三端一致），
/// 以及 `PinEvaluation` 的匹配 / 不匹配 / 缺指纹、`shouldPin` 与 `requirePin` 的钉扎规则
/// （地址用例照搬 Android `PinnedSslTest`）。
struct TLSFingerprintContractTests {
    private struct VectorFile: Decodable {
        var vectors: [CertificateVector]
        var normalizeCases: [NormalizeCase]
    }

    private struct CertificateVector: Decodable {
        var name: String
        var certDerBase64: String
        var fingerprint: String
    }

    private struct NormalizeCase: Decodable {
        var name: String
        var input: String?
        var expected: String
    }

    // MARK: - 共享向量

    @Test func certificateVectorsMatchSharedFingerprints() throws {
        let vectors = try loadVectorFile().vectors
        #expect(vectors.count >= 3, "向量清单太短，可能没读到文件")
        for vector in vectors {
            let der = try #require(Data(base64Encoded: vector.certDerBase64), "vector \(vector.name)")
            #expect(
                CertificateFingerprint.sha256(der: der) == vector.fingerprint,
                "vector \(vector.name): 指纹应与三端共享向量一致"
            )
        }
    }

    @Test func normalizeCasesMatchAndroidRule() throws {
        for item in try loadVectorFile().normalizeCases {
            #expect(CertificateFingerprint.normalize(item.input) == item.expected, "normalize case \(item.name)")
        }
    }

    // MARK: - PinEvaluation

    @Test func evaluateMatchesAfterNormalization() throws {
        let der = try derOfVector(named: "rsa-2048")
        let fingerprint = CertificateFingerprint.sha256(der: der)
        #expect(PinEvaluation.evaluate(leafDER: der, expected: fingerprint) == .match)
        // 规范化后再比较：大写 + 冒号 + 首尾空格的同一指纹同样 match
        let pretty = fingerprint.uppercased().chunked(2).joined(separator: ":")
        #expect(PinEvaluation.evaluate(leafDER: der, expected: pretty) == .match)
        #expect(PinEvaluation.evaluate(leafDER: der, expected: " \(pretty) ") == .match)
    }

    @Test func evaluateReportsMismatchWithActualFingerprint() throws {
        let rsa = try derOfVector(named: "rsa-2048")
        let rsaFingerprint = CertificateFingerprint.sha256(der: rsa)
        let ec = try derOfVector(named: "ec-p256")
        let ecFingerprint = CertificateFingerprint.sha256(der: ec)
        #expect(PinEvaluation.evaluate(leafDER: ec, expected: rsaFingerprint) == .mismatch(actual: ecFingerprint))
        // 期望指纹格式非法：合法指纹才可能相等，同样判 mismatch（fail-closed）
        let garbage = PinEvaluation.evaluate(leafDER: rsa, expected: "not-a-fingerprint")
        guard case .mismatch(let actual) = garbage else {
            Issue.record("格式非法的期望指纹应判 mismatch")
            return
        }
        #expect(actual == rsaFingerprint)
    }

    @Test func evaluateWithoutPinIsMissingPin() throws {
        let der = try derOfVector(named: "ec-p256")
        #expect(PinEvaluation.evaluate(leafDER: der, expected: nil) == .missingPin)
        #expect(PinEvaluation.evaluate(leafDER: der, expected: "") == .missingPin)
        #expect(PinEvaluation.evaluate(leafDER: der, expected: "  \n ") == .missingPin)
    }

    // MARK: - shouldPin（地址用例照搬 Android PinnedSslTest）

    @Test func shouldPinCoversPrivateAndLoopbackHosts() {
        for url in [
            "https://192.168.1.8:18640", "https://10.0.0.2:18640", "https://127.0.0.1:18640",
            "https://172.16.0.1:18640", "https://100.64.0.1:18640", "https://100.127.255.254:18640",
            "https://localhost:18640",
        ] {
            #expect(PinEvaluation.shouldPin(url), "URL \(url)")
        }
        for url in ["https://example.com", "https://8.8.8.8", "https://172.15.0.1", "https://100.128.0.1"] {
            #expect(!PinEvaluation.shouldPin(url), "URL \(url)")
        }
    }

    @Test func shouldPinDefaultsToPinningOnReservedAndNonPublicAddresses() {
        // 白名单式判定：以下地址此前会 fail-open 到系统 PKI
        for url in [
            "https://169.254.10.20:18640", "https://198.18.0.1:18640", "https://198.19.255.254:18640",
            "https://0.0.0.0:18640", "https://224.0.0.1:18640", "https://240.0.0.1:18640",
            "https://192.0.2.10:18640", "https://203.0.113.9:18640", "https://dsh-host.local:18640",
            // IPv6：ULA / 链路本地 / 回环 / 映射私网地址一律钉扎
            "https://[fd00::1]:18640", "https://[fe80::1]:18640", "https://[::1]:18640",
            "https://[::ffff:192.168.1.5]:18640", "https://[2001:db8::1]:18640",
        ] {
            #expect(PinEvaluation.shouldPin(url), "URL \(url)")
        }
        // 公网单播 IPv6 走系统 PKI
        #expect(!PinEvaluation.shouldPin("https://[2606:4700:4700::1111]:18640"))
    }

    // MARK: - requirePin（配对门禁：局域网地址没有指纹拒绝配对）

    @Test func requirePinRejectsLanAddressWithoutValidFingerprint() throws {
        let url = "https://192.168.1.8:18640"
        #expect(throws: CertificatePinError.certificateChanged) {
            try PinEvaluation.requirePin(url: url, fingerprint: nil)
        }
        #expect(throws: CertificatePinError.certificateChanged) {
            try PinEvaluation.requirePin(url: url, fingerprint: "")
        }
        #expect(throws: CertificatePinError.certificateChanged) {
            try PinEvaluation.requirePin(url: url, fingerprint: "not-a-fingerprint")
        }
        #expect(throws: CertificatePinError.certificateChanged) {
            try PinEvaluation.requirePin(url: url, fingerprint: String(repeating: "g", count: 64))
        }
        #expect(throws: CertificatePinError.certificateChanged) {
            try PinEvaluation.requirePin(url: url, fingerprint: String(repeating: "ab", count: 31))
        }
        // 合法 64 位指纹（含大写 + 冒号的带格式写法）通过
        try PinEvaluation.requirePin(url: url, fingerprint: String(repeating: "ab", count: 32))
        let formatted = String(repeating: "ab", count: 32).uppercased().chunked(2).joined(separator: ":")
        try PinEvaluation.requirePin(url: url, fingerprint: formatted)
        // 公网主机空指纹：允许走系统 PKI
        try PinEvaluation.requirePin(url: "https://example.com", fingerprint: nil)
        try PinEvaluation.requirePin(url: "https://8.8.8.8", fingerprint: "")
    }

    // MARK: - SecCertificate 往返

    @Test func secCertificateRoundTripKeepsFingerprint() throws {
        for vector in try loadVectorFile().vectors {
            let der = try #require(Data(base64Encoded: vector.certDerBase64), "vector \(vector.name)")
            let cert = try #require(SecCertificateCreateWithData(nil, der as CFData), "vector \(vector.name)")
            let copy = try #require(SecCertificateCopyData(cert) as Data?, "vector \(vector.name)")
            #expect(
                CertificateFingerprint.sha256(der: copy) == vector.fingerprint,
                "vector \(vector.name): SecCertificate 取回的 DER 指纹应一致"
            )
            #expect(
                PinEvaluation.evaluate(leafDER: copy, expected: vector.fingerprint) == .match,
                "vector \(vector.name): 以向量指纹为期望应 match"
            )
        }
    }

    // MARK: - 助手

    private func derOfVector(named name: String) throws -> Data {
        let vector = try #require(loadVectorFile().vectors.first { $0.name == name }, "缺少向量 \(name)")
        return try #require(Data(base64Encoded: vector.certDerBase64), "vector \(name)")
    }

    private func loadVectorFile() throws -> VectorFile {
        let url = repoRoot()
            .appendingPathComponent("testdata")
            .appendingPathComponent("tls-fingerprint")
            .appendingPathComponent("vectors.json")
        return try JSONDecoder().decode(VectorFile.self, from: Data(contentsOf: url))
    }

    private func repoRoot() -> URL {
        // …/apps/ios/Tests/Contract/<file>.swift 逐级上溯 5 层到仓库根
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}

extension String {
    /// 每 size 位切一段（测试里把指纹格式化成 AA:BB:… 用）。
    fileprivate func chunked(_ size: Int) -> [String] {
        stride(from: 0, to: count, by: size).map { offset in
            let start = index(startIndex, offsetBy: offset)
            let end = index(start, offsetBy: size, limitedBy: endIndex) ?? endIndex
            return String(self[start..<end])
        }
    }
}
