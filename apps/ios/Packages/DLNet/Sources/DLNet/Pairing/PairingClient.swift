import DLModels
import DLSecurity
import Foundation

/// 阶段 4 按 code 本地化；保留 Android friendlyPairError 的主机 hint，415 固定用本机文案。
public enum PairingError: Error, Equatable, Sendable {
    public enum HTTPCode: Equatable, Sendable {
        case invalidCode, conflict, badRequest, tooManyAttempts, hostUnavailable, other
    }
    case qrExpired
    case noLANAddress
    case invalidAddress
    /// 局域网地址的二维码没有指纹或指纹格式不对（I3.4 requirePin），不是“证书变了”。
    case fingerprintRequired
    case certificateChanged
    case http(code: HTTPCode, status: Int, hint: String?)
    case transport(URLError.Code)
    case invalidResponse
    case storage
    case cancelled
}

/// 一个 Attempt 是同一张码的一次逻辑配对；换地址或 SAME_NAME 替换均复用，不自动改名。
public struct PairingClient: Sendable {
    public struct Attempt: Sendable {
        public let qr: PairingQRPayload
        public let deviceName: String
        public let requestId: String
        /// 本地主机记录 id，不是插件下发的手机 deviceId（PLAN I3.3）。
        public let hostId: String

        public init(
            qr: PairingQRPayload, deviceName: String, requestId: String = UUID().uuidString,
            hostId: String = UUID().uuidString
        ) {
            self.qr = qr
            self.deviceName = deviceName
            self.requestId = requestId
            self.hostId = hostId
        }
    }

    public enum Result: Equatable, Sendable {
        case paired(PairedHost)
        case pending(PairedHost, expiresAt: Int?)
        case sameName(ExistingDevice?)
        case failed(PairingError)
    }

    public struct FreshnessPolicy: Sendable {
        /// nil = 不额外按渲染年龄拒绝。插件 TTL 可配置；expiresAt 是码的实际有效期。
        public let maximumIssuedAgeMilliseconds: Int?
        public init(maximumIssuedAgeMilliseconds: Int? = nil) {
            self.maximumIssuedAgeMilliseconds = maximumIssuedAgeMilliseconds
        }
    }

    private let store: HostStore
    private let session: URLSession?
    private let freshness: FreshnessPolicy
    private let clock: @Sendable () -> Int

    /// session 只供测试注入；生产默认沿用 HostClient 的 PinnedSessionDelegate。
    public init(
        store: HostStore, session: URLSession? = nil, freshness: FreshnessPolicy = .init(),
        clock: @escaping @Sendable () -> Int = { Int(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.store = store
        self.session = session
        self.freshness = freshness
        self.clock = clock
    }

    public func pair(_ attempt: Attempt, replacing: Bool = false) async -> Result {
        if expired(attempt.qr) { return .failed(.qrExpired) }
        guard !attempt.qr.urls.isEmpty else { return .failed(.noLANAddress) }
        let body = RequestBody(
            code: attempt.qr.code, deviceName: attempt.deviceName, requestId: attempt.requestId,
            replace: replacing ? true : nil)
        guard let encoded = try? JSONEncoder().encode(body) else { return .failed(.invalidResponse) }
        var lastError: PairingError = .noLANAddress
        // Android pairWithQr 的纯 LAN 分支：原 urls 顺序，逐一尝试；I3.8 忽略 remote 路由。
        for address in attempt.qr.urls {
            if Task.isCancelled { return .failed(.cancelled) }
            if expired(attempt.qr) { return .failed(.qrExpired) }
            let normalized = Self.normalize(address)
            guard let url = URL(string: normalized), url.scheme?.lowercased() == "https", url.host != nil else {
                lastError = .invalidAddress
                continue
            }
            do {
                try PinEvaluation.requirePin(url: normalized, fingerprint: attempt.qr.certFingerprint)
            } catch {
                lastError = .fingerprintRequired
                continue
            }
            let client = PairingTransport.makeClient(
                baseURL: url, token: "", fingerprint: attempt.qr.certFingerprint,
                requestSeconds: 8, session: session)
            defer { if session == nil { client.session.finishTasksAndInvalidate() } }
            let response: (status: Int, body: Data)
            do {
                response = try await PairingTransport(client: client).send(
                    method: "POST", path: "/dsh-link/pair", body: encoded)
            } catch is CancellationError {
                return .failed(.cancelled)
            } catch let error as HostClientError {
                switch error {
                case .certificateChanged: lastError = .certificateChanged
                case .transport(let error): lastError = .transport(error.code)
                default: lastError = .transport(.unknown)
                }
                continue
            } catch {
                lastError = .transport(.unknown)
                continue
            }
            if response.status == 409,
                let conflict = try? JSONDecoder().decode(PairConflictResponse.self, from: response.body),
                conflict.code == "SAME_NAME"
            {
                return .sameName(conflict.existing)
            }
            guard response.status == 200 else {
                let error = Self.httpError(status: response.status, body: response.body)
                // 插件的明确答复（码错 / 过期 / 限流 / 冲突）不换地址重试：换地址只会多耗限流次数，
                // 后一条地址的网络错误还会盖掉真正原因（Android pairWithQr 注释第 3 条）。5xx 仍试下一条。
                if (400...499).contains(response.status) { return .failed(error) }
                lastError = error
                continue
            }
            guard let paired = Self.successResponse(response.body),
                let token = paired.token, !token.isEmpty
            else {
                lastError = .invalidResponse
                continue
            }
            // hostFromPair 使用 QR 的电脑名，响应 name 是手机名；主地址取实际成功地址。
            // 响应 deviceId 是手机设备，不能当电脑身份。插件电脑身份在二维码 deviceId（state.deviceId）。
            let host = PairedHost(
                hostId: attempt.hostId, name: attempt.qr.name, primaryUrl: normalized,
                tailnetUrl: Self.tailnetSpare(urls: attempt.qr.urls, primary: normalized),
                certFingerprint: attempt.qr.certFingerprint, remote: paired.remote, pairedAt: clock(),
                pluginHostId: attempt.qr.pluginHostId)
            do {
                // save 按插件 hostId 或证书指纹替换同一台电脑，并沿用旧的本地 hostId。
                try await store.save(host: host, token: token)
                let saved =
                    await store.existingHost(
                        certFingerprint: host.certFingerprint, hostId: host.hostId,
                        pluginHostId: host.pluginHostId) ?? host
                return paired.pending == true ? .pending(saved, expiresAt: paired.pendingExpiresAt) : .paired(saved)
            } catch {
                // 请求已成功，保存失败不得再尝试别的地址创建记录。
                return .failed(.storage)
            }
        }
        return .failed(lastError)
    }

    /// Android tailnetSpare：只认 CGNAT / Tailscale IPv6，取第一条；若就是主地址则没有备用。
    public static func tailnetSpare(urls: [String], primary: String) -> String? {
        guard let tail = urls.map(normalize).first(where: isTailnet) else { return nil }
        let identity: (String) -> String = { $0.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() }
        return identity(tail) == identity(normalize(primary)) ? nil : tail
    }

    static func normalize(_ address: String) -> String {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.lowercased().hasPrefix("https://") { return trimmed }
        if trimmed.lowercased().hasPrefix("http://") { return "https://" + trimmed.dropFirst(7) }
        return "https://" + trimmed
    }

    private static func isTailnet(_ address: String) -> Bool {
        guard let name = URLComponents(string: address)?.host else { return false }
        let host = name.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if host.hasPrefix("fd7a:115c:a1e0:") { return true }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4, let a = Int(parts[0]), let b = Int(parts[1]), Int(parts[2]) != nil, Int(parts[3]) != nil
        else { return false }
        return a == 100 && (64...127).contains(b)
    }

    private func expired(_ qr: PairingQRPayload) -> Bool {
        let now = clock()
        if let expiresAt = qr.expiresAt, now > expiresAt { return true }
        if let issuedAt = qr.issuedAt, let maximumAge = freshness.maximumIssuedAgeMilliseconds {
            return Double(now) - Double(issuedAt) > Double(maximumAge)
        }
        return false
    }

    private static func successResponse(_ data: Data) -> PairResponse? {
        guard var object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let rawRemote = object.removeValue(forKey: "remote")
        guard let coreData = try? JSONSerialization.data(withJSONObject: object),
            var paired = try? JSONDecoder().decode(PairResponse.self, from: coreData)
        else { return nil }
        if let rawRemote,
            let remoteData = try? JSONSerialization.data(withJSONObject: rawRemote, options: .fragmentsAllowed)
        {
            paired.remote = PairingQRPayload.validatedDeviceRemote(
                try? JSONDecoder().decode(DeviceRemoteInfo.self, from: remoteData))
        }
        return paired
    }

    private struct RequestBody: Encodable {
        let code: String
        let deviceName: String
        let via = "lan"
        let requestId: String
        let replace: Bool?
    }

    private struct ErrorBody: Decodable { let error: String? }

    private static func httpError(status: Int, body: Data) -> PairingError {
        let rawHint = (try? JSONDecoder().decode(ErrorBody.self, from: body))?.error
        let hint = rawHint?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ? rawHint : nil
        let code: PairingError.HTTPCode =
            switch status {
            case 401: .invalidCode
            case 409: .conflict
            case 415: .badRequest
            case 429: .tooManyAttempts
            case 500...599: .hostUnavailable
            default: .other
            }
        return .http(code: code, status: status, hint: status == 415 ? nil : hint)
    }
}
