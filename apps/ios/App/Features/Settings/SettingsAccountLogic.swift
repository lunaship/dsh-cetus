import DLModels
import DLNet
import Foundation

struct SettingsCrashReport: Equatable, Sendable {
    var raw: String?
}

struct SettingsCrashExport: Equatable, Sendable {
    var text: String
}

enum SettingsCrashStore {
    static func export(from report: SettingsCrashReport?) -> SettingsCrashExport? {
        guard let text = crashShareText(report?.raw) else { return nil }
        return SettingsCrashExport(text: text)
    }
}

protocol SettingsAccountServing: Sendable {
    func loadComputer() async -> ComputerAccountSnapshot
    func renameComputer(_ draft: String) async -> ComputerRename?
    func unpair() async -> ComputerUnpairOutcome
    func diagnostics() async throws -> DiagnosticsReport
}

/// POST /dsh-link/mobile/revoke 只带合同里的 deviceId 或 name，并且只指向本机。
enum SelfRevokeBody: Equatable, Sendable {
    case device(String)
    case name(String)

    var encoded: RevokeRequestBody {
        switch self {
        case .device(let id): RevokeRequestBody(deviceId: id, name: nil)
        case .name(let name): RevokeRequestBody(deviceId: nil, name: name)
        }
    }
}

struct RevokeRequestBody: Encodable, Equatable, Sendable {
    var deviceId: String?
    var name: String?
}

/// 优先用设备列表确认过的本机 deviceId。没有时才用配对时记下的手机名。
/// 不传电脑名、本机别名或其他设备标识。
func selfRevokeBody(deviceID: String?, pairedPhoneName: String?) -> SelfRevokeBody? {
    let id = deviceID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if !id.isEmpty { return .device(id) }
    let name = pairedPhoneName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if !name.isEmpty { return .name(name) }
    return nil
}

/// 设备列表里找本机。名字去空白后全等；同名不止一台时不猜。
func selfDeviceID(in devices: [DeviceRow], pairedPhoneName: String) -> String? {
    let wanted = pairedPhoneName.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !wanted.isEmpty else { return nil }
    let matches = devices.filter {
        ($0.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines) == wanted
    }
    guard matches.count == 1,
        let id = matches[0].deviceId?.trimmingCharacters(in: .whitespacesAndNewlines),
        !id.isEmpty
    else { return nil }
    return id
}

enum ComputerUnpairOutcome: Equatable, Sendable {
    /// 插件已吊销本机，本地凭据已删。
    case revoked
    /// 插件明确返回 401。凭据已经无效，删掉。
    case alreadyUnauthorized
    /// 连不上、403/404/409/5xx 或本地写失败。凭据原样留着。
    case kept(ComputerUnpairFailure)
}

enum ComputerUnpairFailure: Equatable, Sendable {
    case unavailable
    case forbidden
    case notFound
    case conflict
    case transport
    case storage
}

func shouldDeleteCredentials(after outcome: ComputerUnpairOutcome) -> Bool {
    switch outcome {
    case .revoked, .alreadyUnauthorized: true
    case .kept: false
    }
}

func unpairFailure(for error: HostClientError) -> ComputerUnpairFailure {
    switch error {
    case .unauthorized: .transport
    case .forbidden: .forbidden
    case .capabilityMissing: .notFound
    case .conflict, .sessionBusy: .conflict
    case .transport, .certificateChanged: .transport
    case .server(let status, _) where status == 404: .notFound
    case .server(let status, _) where status == 409: .conflict
    case .server, .decoding: .transport
    }
}

/// 改名只写本机。空名和超过 64 个字符都拒绝，不截断，不发网络。
func localComputerName(_ raw: String) -> String? {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty, trimmed.count <= 64 else { return nil }
    return trimmed
}

struct ComputerRename: Equatable, Sendable {
    var displayName: String
    /// 与配对原名相同时为 nil，表示清掉本机别名。
    var storedAlias: String?
}

func renameComputerLocally(alias: String?, originalName: String, draft: String) -> ComputerRename? {
    guard let next = localComputerName(draft) else { return nil }
    let original = originalName.trimmingCharacters(in: .whitespacesAndNewlines)
    if next == original {
        return ComputerRename(displayName: original.isEmpty ? next : original, storedAlias: nil)
    }
    return ComputerRename(displayName: next, storedAlias: next)
}

func computerDisplayName(alias: String?, originalName: String, hostID: String) -> String {
    let stored = alias?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if !stored.isEmpty { return stored }
    let original = originalName.trimmingCharacters(in: .whitespacesAndNewlines)
    if !original.isEmpty { return original }
    return hostID
}

/// GET /dsh-link/mobile/diagnostics 的展示行。副标题只按稳定 code 本地化。
struct DiagnosticDisplayRow: Equatable, Sendable, Identifiable {
    var id: String
    var status: DiagnosticsStatus
    var code: String
    var title: String
    var subtitle: String
}

func diagnosticRows(_ checks: [DiagnosticCheck], locale: Locale) -> [DiagnosticDisplayRow] {
    checks.compactMap { check in
        guard let id = check.id, !id.isEmpty, let status = check.status, let code = check.code, !code.isEmpty else {
            return nil
        }
        return DiagnosticDisplayRow(
            id: id, status: status, code: code,
            title: diagnosticTitle(id, locale: locale),
            subtitle: diagnosticSubtitle(code, detail: check.detail ?? [:], locale: locale))
    }
}

func diagnosticTitle(_ id: String, locale: Locale) -> String {
    switch id {
    case "host.rpc": text("rpc", locale, "Host connection")
    case "host.services": text("services", locale, "Host services")
    case "plugin.version": text("plugin", locale, "Plugin")
    case "tls.cert": text("certificate", locale, "Certificate")
    case "pairing.devices": text("pairing", locale, "This phone")
    case "remote.relay": text("relay", locale, "Relay")
    case "clock": text("clock", locale, "Clock")
    default: id
    }
}

func diagnosticSubtitle(_ code: String, detail: [String: DiagnosticDetailValue], locale: Locale) -> String {
    let english = !isChinese(locale)
    switch code {
    case "HOST_RPC_OK":
        return number("ms", detail).map { english ? "Responded in \(Int($0)) ms" : "\(Int($0)) 毫秒内有响应" }
            ?? (english ? "The computer answered." : "电脑有响应。")
    case "HOST_RPC_SLOW":
        return number("ms", detail).map { english ? "Slow: \(Int($0)) ms" : "偏慢：\(Int($0)) 毫秒" }
            ?? (english ? "The computer answered slowly." : "电脑响应偏慢。")
    case "HOST_RPC_TIMEOUT": return english ? "The computer did not answer in time." : "电脑没有及时响应。"
    case "HOST_RPC_FAILED": return english ? "The computer did not answer." : "电脑没有响应。"
    case "HOST_RPC_UNAVAILABLE": return english ? "This computer cannot run this check." : "这台电脑做不了这项检查。"
    case "HOST_SERVICES_OK": return english ? "Required services are available." : "需要的服务都在。"
    case "HOST_SERVICES_PARTIAL": return english ? "Change summaries are unavailable." : "改动摘要不可用。"
    case "HOST_SERVICES_MISSING": return english ? "A required service is missing." : "缺少必要服务。"
    case "HOST_SERVICES_UNAVAILABLE": return english ? "Services could not be read." : "读不到服务状态。"
    case "PLUGIN_VERSION_OK":
        return textDetail("version", detail).map { english ? "Plugin \($0)" : "插件 \($0)" }
            ?? (english ? "Plugin version is available." : "插件版本可用。")
    case "PLUGIN_VERSION_UNKNOWN": return english ? "Plugin version was not reported." : "插件没有报告版本。"
    case "PLUGIN_VERSION_INVALID": return english ? "Plugin version is not usable." : "插件版本不可用。"
    case "TLS_CERT_OK": return certificateLine(detail, english: english, warning: false)
    case "TLS_CERT_EXPIRING": return certificateLine(detail, english: english, warning: true)
    case "TLS_CERT_EXPIRED": return english ? "The certificate has expired." : "证书已过期。"
    case "TLS_CERT_UNKNOWN_EXPIRY": return english ? "The certificate expiry is unknown." : "证书到期时间未知。"
    case "TLS_CERT_MISSING": return english ? "No certificate is available." : "没有证书。"
    case "PAIRING_SELF_OK": return english ? "This phone is paired." : "这台手机已配对。"
    case "PAIRING_SELF_LEGACY": return english ? "This phone uses an older pairing record." : "这台手机的配对记录较旧。"
    case "PAIRING_SELF_INVALID": return english ? "This phone is not paired on the computer." : "电脑上没有这台手机的配对。"
    case "REMOTE_READY": return english ? "Relay is ready." : "中继已就绪。"
    case "REMOTE_CONNECTING": return english ? "Relay is connecting." : "中继正在连接。"
    case "REMOTE_DISABLED": return english ? "Relay is off." : "中继没有开启。"
    case "REMOTE_REPLACED": return english ? "Another computer replaced this relay registration." : "另一台电脑顶掉了这个中继登记。"
    case "REMOTE_REJECTED":
        return textDetail("lastCode", detail).map { english ? "Relay refused: \($0)" : "中继拒绝：\($0)" }
            ?? (english ? "Relay refused the connection." : "中继拒绝了连接。")
    case "REMOTE_DOWN": return english ? "Relay is not connected." : "中继没有连上。"
    case "CLOCK_OK": return english ? "The computer clock looks usable." : "电脑时钟可用。"
    case "CLOCK_UNREASONABLE": return english ? "The computer clock is outside the expected range." : "电脑时钟不在合理范围。"
    case "CLOCK_INVALID": return english ? "The computer clock could not be read." : "读不到电脑时钟。"
    default: return code
    }
}

/// 本机 Unix 秒减去诊断里的主机 Unix 秒。正数表示手机比电脑快。
func diagnosticClockSkew(generatedAt: Int?, now: Date) -> Int? {
    guard let generatedAt else { return nil }
    return Int(now.timeIntervalSince1970) - generatedAt
}

/// 7.15 导出前的脱敏：去掉绝对路径、URL 查询、token 和引号里的消息正文。
func redactCrashExport(_ text: String) -> String {
    var out = text
    out = replacing(out, #"(?:file|content|assets-library)://\S+"#, "<path>")
    out = replacingPaths(out)
    out = replacing(
        out, #"https?://[^\s\"'<>()\[\]{}]+"#,
        { raw in
            if let cut = raw.firstIndex(where: { $0 == "?" || $0 == "#" }) {
                return String(raw[..<cut])
            }
            return raw
        })
    out = replacing(out, #"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+"#, "Bearer <redacted>")
    out = replacing(out, #"(?i)\bauthorization\b[\"']?\s*[:=]\s*[\"']?[^\s\"',;]+"#, "Authorization=<redacted>")
    out = replacing(
        out,
        #"(?i)\b(token|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\b\s*[:=]\s*[\"']?[^\s&\"'<>]+"#,
        { raw in
            let key = raw.split(whereSeparator: { $0 == ":" || $0 == "=" }).first.map(String.init) ?? "token"
            return "\(key)=<redacted>"
        })
    out = replacing(
        out, #"(?i)\"(?:content|text|message|body|prompt|answer|reply)\"\s*:\s*\"(?:\\\\.|[^\"\\\\])*\""#,
        { raw in
            let key = raw.split(separator: "\"").first.map(String.init) ?? "message"
            return "\"\(key)\":<redacted>"
        })
    out = replacing(out, #"\b[0-9a-fA-F]{32,}\b"#, "<redacted>")
    out = replacing(out, #"\b[A-Za-z0-9+/_-]{32,}={0,2}\b"#, "<redacted>")
    return out
}

/// 交给系统分享表的本机文本。空报告不分享。
func crashShareText(_ raw: String?) -> String? {
    guard let raw else { return nil }
    let redacted = redactCrashExport(raw)
    let trimmed = redacted.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : redacted
}

private func certificateLine(
    _ detail: [String: DiagnosticDetailValue], english: Bool, warning: Bool
) -> String {
    let days = number("daysRemaining", detail).map(Int.init)
    let prefix = textDetail("fingerprintPrefix", detail)
    if warning {
        if let days { return english ? "Expires in \(days) days." : "还有 \(days) 天到期。" }
        return english ? "The certificate expires soon." : "证书快到期。"
    }
    if let days, let prefix {
        return english ? "Valid for \(days) days (\(prefix))." : "还有 \(days) 天有效（\(prefix)）。"
    }
    if let days { return english ? "Valid for \(days) days." : "还有 \(days) 天有效。" }
    return english ? "The certificate is valid." : "证书有效。"
}

private func number(_ key: String, _ detail: [String: DiagnosticDetailValue]) -> Double? {
    if case .number(let value) = detail[key] { return value }
    return nil
}

private func textDetail(_ key: String, _ detail: [String: DiagnosticDetailValue]) -> String? {
    if case .text(let value) = detail[key], !value.isEmpty { return value }
    return nil
}

private func isChinese(_ locale: Locale) -> Bool {
    locale.language.languageCode?.identifier == "zh"
}

private func text(_ key: String, _ locale: Locale, _ english: String) -> String {
    let chinese = [
        "rpc": "主机连接",
        "services": "主机服务",
        "plugin": "插件",
        "certificate": "证书",
        "pairing": "这台手机",
        "relay": "中继",
        "clock": "时钟",
    ]
    return isChinese(locale) ? (chinese[key] ?? english) : english
}

private func replacing(_ text: String, _ pattern: String, _ replacement: String) -> String {
    replacing(text, pattern) { _ in replacement }
}

private func replacing(_ text: String, _ pattern: String, _ replacement: (String) -> String) -> String {
    guard let expression = try? NSRegularExpression(pattern: pattern) else { return text }
    let range = NSRange(text.startIndex..<text.endIndex, in: text)
    let matches = expression.matches(in: text, range: range)
    var result = text
    for match in matches.reversed() {
        guard let swiftRange = Range(match.range, in: result) else { continue }
        result.replaceSubrange(swiftRange, with: replacement(String(result[swiftRange])))
    }
    return result
}

private func replacingPaths(_ text: String) -> String {
    guard
        let expression = try? NSRegularExpression(
            pattern: #"(?:^|[\s\"'(\[{])((?:/|~/)(?:[^\s\"')\]}>]+/)+[^\s\"')\]}>]+)"#
        )
    else { return text }
    let range = NSRange(text.startIndex..<text.endIndex, in: text)
    let matches = expression.matches(in: text, range: range)
    var result = text
    for match in matches.reversed() {
        guard match.numberOfRanges > 1, let pathRange = Range(match.range(at: 1), in: result) else { continue }
        result.replaceSubrange(pathRange, with: "<path>")
    }
    return result
}
