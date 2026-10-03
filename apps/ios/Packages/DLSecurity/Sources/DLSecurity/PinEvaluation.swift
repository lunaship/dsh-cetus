import Foundation

/// 配对指纹校验失败的错误（与 Android `PinnedSsl.CertChangedException` 同义）。
/// 指纹缺失、格式非法或不匹配一律 fail-closed，不得静默回退到系统 PKI。
public enum CertificatePinError: Error, Equatable, Sendable {
    case certificateChanged
}

/// 叶证书指纹的纯函数判定（I3.4）。规则照 Android `PinnedSsl`，三端共享向量见
/// 仓库根 `testdata/tls-fingerprint/`。
public enum PinEvaluation {
    /// 叶证书 DER 与期望指纹的逐位比较结果。
    public enum PinResult: Equatable, Sendable {
        /// 规范化后逐位一致。
        case match
        /// 有期望指纹但与叶证书不一致（含期望指纹格式非法——合法指纹才可能相等）。
        case mismatch(actual: String)
        /// 没有期望指纹：私网 / 回环地址 fail-closed，不静默回退系统 PKI。
        case missingPin
    }

    /// 逐位比较规范化之后的叶证书指纹与期望指纹。
    public static func evaluate(leafDER: Data, expected: String?) -> PinResult {
        let expected = CertificateFingerprint.normalize(expected)
        guard !expected.isEmpty else { return .missingPin }
        let actual = CertificateFingerprint.sha256(der: leafDER)
        return actual == expected ? .match : .mismatch(actual: actual)
    }

    /// 与 Android `PinnedSsl.shouldPin` 相同的**白名单式**判定：
    /// 只有能明确判定为公网单播的 IP 字面量才豁免钉扎（走系统 PKI），其余
    /// （RFC1918、CGNAT 100.64/10、链路本地 169.254/16、基准测试 198.18/15、组播 /
    /// 保留段、IPv6 ULA / 链路本地、`.local`、解析失败的地址）一律钉扎，杜绝漏段
    /// fail-open。DNS 名默认交给系统 PKI（Tailscale MagicDNS 等公网证书场景）。
    public static func shouldPin(_ url: String) -> Bool {
        guard let host = hostString(of: url)?.lowercased() else { return true }
        if host.isEmpty || host == "localhost" || host == "::1" || host.hasSuffix(".local") { return true }
        if host.contains(":") { return !isPublicIpv6(host) }
        if !isIpv4Literal(host) { return false }
        return !isPublicIpv4(host)
    }

    /// 配对门禁（Android `validateLanIdentity` + `requireValidPin`）：
    /// - 指纹为空：私网 / 回环主机直接拒绝——局域网地址没有指纹不允许配对；
    ///   公网主机允许走系统 PKI。
    /// - 指纹非空：必须是 64 位十六进制（规范化后小写），否则拒绝。
    public static func requirePin(url: String, fingerprint: String?) throws {
        let normalized = CertificateFingerprint.normalize(fingerprint)
        if normalized.isEmpty {
            if shouldPin(url) { throw CertificatePinError.certificateChanged }
            return
        }
        guard CertificateFingerprint.isValid(normalized) else { throw CertificatePinError.certificateChanged }
    }

    // MARK: - 主机判定

    /// 仅公网单播 IPv4 返回 true；私网、CGNAT、链路本地、组播、保留段与非法写法一律 false。
    private static func isPublicIpv4(_ name: String) -> Bool {
        let octets = name.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4 else { return false }
        var values: [Int] = []
        for octet in octets {
            guard let value = Int(octet), (0...255).contains(value) else { return false }
            values.append(value)
        }
        let a = values[0]
        let b = values[1]
        if a == 0 { return false }  // 0.0.0.0/8
        if a == 10 { return false }  // 10.0.0.0/8
        if a == 100 && (64...127).contains(b) { return false }  // 100.64.0.0/10 CGNAT（含 Tailscale）
        if a == 127 { return false }  // 127.0.0.0/8
        if a == 169 && b == 254 { return false }  // 169.254.0.0/16 链路本地
        if a == 172 && (16...31).contains(b) { return false }  // 172.16.0.0/12
        if a == 192 && b == 168 { return false }  // 192.168.0.0/16
        if a == 192 && b == 0 { return false }  // 192.0.0.0/24、192.0.2.0/24
        if a == 192 && b == 88 { return false }  // 192.88.99.0/24
        if a == 198 && (18...19).contains(b) { return false }  // 198.18.0.0/15 基准测试
        if a == 198 && b == 51 { return false }  // 198.51.100.0/24
        if a == 203 && b == 0 { return false }  // 203.0.113.0/24
        if a >= 224 { return false }  // 组播 224/4 与保留 240/4
        return true
    }

    /// 仅全局单播 IPv6（2000::/3）返回 true；ULA、链路本地、回环、映射地址按内嵌 IPv4 判定。
    private static func isPublicIpv6(_ name: String) -> Bool {
        if name.hasPrefix("::ffff:") {
            let embedded = String(name.dropFirst("::ffff:".count))
            return embedded.contains(".") && isPublicIpv4(embedded)
        }
        // 与 Kotlin `split(":")` 一致：保留空组，让 `::1` 这类缩写首组解析失败 → 按钉扎处理。
        let groups = name.split(separator: ":", omittingEmptySubsequences: false)
        guard let first = groups.first.flatMap({ Int($0, radix: 16) }) else { return false }
        if !(0x2000...0x3FFF).contains(first) { return false }
        if first == 0x2001, groups.count > 1, Int(groups[1], radix: 16) == 0x0DB8 { return false }  // 文档地址
        return true
    }

    /// 整串必须是 1–3 位 ASCII 数字的四段点分形式（等价 Kotlin `\d{1,3}(\.\d{1,3}){3}` 全匹配）。
    private static func isIpv4Literal(_ name: String) -> Bool {
        let octets = name.split(separator: ".", omittingEmptySubsequences: false)
        return octets.count == 4
            && octets.allSatisfy { octet in
                (1...3).contains(octet.count) && octet.allSatisfy { "0123456789".contains($0) }
            }
    }

    /// 从 URL 提取 host 字符串。不依赖 URLComponents——它会把 `[::ffff:192.168.1.5]`
    /// 规范化成十六进制形式，丢失「内嵌 IPv4」的判定依据。解析失败返回 nil → 按钉扎处理。
    static func hostString(of urlString: String) -> String? {
        let normalized = normalizeUrl(urlString)
        guard let schemeEnd = normalized.range(of: "://") else { return nil }
        var authority = normalized[schemeEnd.upperBound...]
        if let end = authority.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) {
            authority = authority[..<end]
        }
        if let at = authority.lastIndex(of: "@") {
            authority = authority[authority.index(after: at)...]
        }
        if authority.hasPrefix("[") {
            guard let close = authority.firstIndex(of: "]") else { return nil }
            return String(authority[authority.index(after: authority.startIndex)..<close])
        }
        if let colon = authority.firstIndex(of: ":") {
            authority = authority[..<colon]
        }
        // DNS 主机名只可能是 unreserved 字符；带其他字符说明解析已不可信 → nil（钉扎）。
        let allDnsSafe = authority.allSatisfy {
            $0.isASCII && "-._0123456789abcdefghijklmnopqrstuvwxyz".contains($0.lowercased())
        }
        return allDnsSafe ? String(authority) : nil
    }

    /// http 升级 https、无 scheme 补 https（与 Android `PinnedSsl.normalizeUrl` 一致）。
    static func normalizeUrl(_ baseUrl: String) -> String {
        let trimmed = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowered = trimmed.lowercased()
        if lowered.hasPrefix("https://") { return trimmed }
        if lowered.hasPrefix("http://") { return "https://" + trimmed.dropFirst(7) }
        return "https://" + trimmed
    }
}
