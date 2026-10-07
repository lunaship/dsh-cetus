import DLRemote
import DLSecurity
import Foundation
import Network
import Security
import os

/// `NWConnection` 版 DLP/1 传输：外层 WSS 会合 → 内层 TLS → HTTP 字节通道。
///
/// 这是 RFC §4.2 第 6 步「手机在管道上做内层 TLS（钉扎插件证书）」的实现。
///
/// 两段 TLS 都用 `sec_protocol_options_set_verify_block`：
/// - **外层**：仅在给了 `outerPin` 时钉扎（RFC §5.1「用户显式提供 outerPin 时，
///   改为只校验该指纹、跳过 CA 与主机名」）；没给就走系统 CA。
/// - **内层**：**始终**钉扎插件叶证书指纹（§4.3）。指纹为空一律 fail-closed。
///
/// 判定逻辑一律走 `PinEvaluation` / `CertificateFingerprint`，不重写指纹算法。
public struct NWRemoteTunnelTransport: RemoteTunnelTransport {
    /// 会合总超时；RFC §5.8 规定流打开超时 10 秒。
    public static let openTimeout: TimeInterval = 10

    /// 内层 TLS 握手超时。
    public static let innerTLSTimeout: TimeInterval = 10

    public init() {}

    public func open(_ route: RemoteTunnelRoute) async throws -> any RemoteTunnel {
        try Self.validate(route)

        // 1) 外层 WSS：NWProtocolWebSocket + 可选 outerPin。
        let outerPin = CertificateFingerprint.normalize(route.outerPin.isEmpty ? nil : route.outerPin)
        let wsOptions = NWProtocolWebSocket.Options()
        // RFC §5.4.4：Relay 每 25 秒发协议层 ping，对端库自动回 pong。
        wsOptions.autoReplyPing = true
        // RFC §5.1：显式关闭 permessage-deflate。NWProtocolWebSocket 不协商压缩扩展；
        // 这里不做任何开启动作，即等价于关闭。

        let outerTLSOptions = NWProtocolTLS.Options()
        if CertificateFingerprint.isValid(outerPin) {
            Self.installPin(outerTLSOptions, expected: outerPin)
        }
        let parameters = NWParameters(tls: outerTLSOptions, tcp: .init())
        parameters.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

        guard let endpoint = Self.endpoint(for: route.endpoint) else {
            throw RemoteTunnelError.relayUnreachable
        }
        let connection = NWConnection(to: endpoint, using: parameters)
        let queue = DispatchQueue(label: "dev.deeplinks.remote.outer")
        connection.start(queue: queue)

        // 2) 等 hello → 发 client_open → 等 ready（§5.4.3）。
        do {
            try await Self.rendezvous(connection: connection, route: route)
        } catch {
            connection.cancel()
            throw error
        }
        return NWRemoteTunnel(connection: connection)
    }

    // MARK: - 内层 TLS

    /// 在已就绪的隧道上叠内层 TLS，返回可在其上跑 HTTP/1.1 的通道。
    ///
    /// RFC §4.2 第 6 步。`expectedFingerprint` 是配对时记录的插件叶证书指纹，
    /// 为空或非法一律拒绝（`PinEvaluation` 的 fail-closed 语义）。
    public static func openInnerTLS(
        over tunnel: any RemoteTunnel,
        host: String,
        expectedFingerprint: String?
    ) async throws -> any TunnelByteChannel {
        let pin = CertificateFingerprint.normalize(expectedFingerprint)
        guard CertificateFingerprint.isValid(pin) else {
            // 没有合法指纹：不允许建立内层 TLS，也不能回退系统 PKI。
            throw CertificatePinError.certificateChanged
        }
        return try await InnerTLSChannel.open(tunnel: tunnel, host: host, expectedFingerprint: pin)
    }

    /// 装一个「只认这个叶证书指纹」的 verify block。
    ///
    /// 这完全替换系统校验：不匹配就 `complete(false)`，握手失败，不会回退到 CA。
    ///
    /// - Parameter onReject: 指纹不符时同步回调一次。
    ///   **这是必要的**：实测（macOS 探针）`complete(false)` 之后
    ///   `NWConnection` **不会**进入 `.failed`，也不发任何 state 回调，
    ///   连接就那样挂着直到调用方超时。若只依赖 `.failed` 判定，
    ///   「指纹不符」会被伪装成「握手超时」——语义完全错误（前者是硬停止，
    ///   后者可能被上层当成可重试）。所以拒绝必须在这里显式记下来。
    static func installPin(
        _ options: NWProtocolTLS.Options,
        expected: String,
        onReject: (@Sendable () -> Void)? = nil
    ) {
        sec_protocol_options_set_verify_block(
            options.securityProtocolOptions,
            { _, secTrust, complete in
                let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
                guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
                    let leaf = chain.first,
                    let leafDER = SecCertificateCopyData(leaf) as Data?
                else {
                    onReject?()
                    complete(false)
                    return
                }
                switch PinEvaluation.evaluate(leafDER: leafDER, expected: expected) {
                case .match:
                    complete(true)
                case .mismatch, .missingPin:
                    onReject?()
                    complete(false)
                }
            },
            DispatchQueue(label: "dev.deeplinks.remote.verify")
        )
    }

    // MARK: - 会合

    static func rendezvous(connection: NWConnection, route: RemoteTunnelRoute) async throws {
        let deadline = Date().addingTimeInterval(openTimeout)
        var helloSeen = false

        while true {
            let remaining = deadline.timeIntervalSinceNow
            if remaining <= 0 { throw RemoteTunnelError.openTimeout }
            let message = try await receiveText(connection: connection, timeout: remaining)
            let fields = try parseControl(message)
            let type = fields["t"]?.string ?? ""

            if !helloSeen {
                guard type == "hello", fields["v"]?.int == DlpWire.version,
                    let challenge = fields["ch"]?.string,
                    (try? DlpCrypto.base64URLDecode(challenge, expectedLength: DlpWire.challengeBytes)) != nil
                else {
                    throw RemoteTunnelError.protocolViolation
                }
                helloSeen = true
                try await sendClientOpen(connection: connection, route: route)
                continue
            }
            switch type {
            case "ready": return
            case "error":
                throw mapError(code: fields["code"]?.string, hostNow: fields["hostNow"]?.int)
            default:
                throw RemoteTunnelError.protocolViolation
            }
        }
    }

    private static func sendClientOpen(connection: NWConnection, route: RemoteTunnelRoute) async throws {
        var nonce = Data(count: DlpWire.nonceBytes)
        let status = nonce.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, DlpWire.nonceBytes, $0.baseAddress!)
        }
        guard status == errSecSuccess else { throw RemoteTunnelError.transport("random source unavailable") }

        let timestamp = Int(Date().timeIntervalSince1970) + route.clockOffsetSec
        let transcript = try DlpCrypto.clientTranscript(
            route: route.routeId, kind: route.kind, key: route.keyId, timestamp: timestamp, nonce: nonce)
        let mac = try DlpCrypto.clientMac(key: route.key, transcript: transcript)

        let frame = DlpWire.encodeControl([
            DlpJSON.Field("t", .string("client_open")),
            DlpJSON.Field("v", .number(String(DlpWire.version))),
            DlpJSON.Field("route", .string(DlpCrypto.base64URL(route.routeId))),
            DlpJSON.Field("kind", .string(route.kind.rawValue)),
            DlpJSON.Field("key", .string(DlpCrypto.base64URL(route.keyId))),
            DlpJSON.Field("ts", .number(String(timestamp))),
            DlpJSON.Field("nonce", .string(DlpCrypto.base64URL(nonce))),
            DlpJSON.Field("mac", .string(DlpCrypto.base64URL(mac))),
        ])

        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "dlp1-control", metadata: [metadata])
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
            connection.send(
                content: Data(frame.utf8), contentContext: context, isComplete: true,
                completion: .contentProcessed { error in
                    if error != nil {
                        continuation.resume(throwing: RemoteTunnelError.relayUnreachable)
                    } else {
                        continuation.resume()
                    }
                })
        }
    }

    private static func receiveText(connection: NWConnection, timeout: TimeInterval) async throws -> String {
        try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, any Error>) in
                    connection.receiveMessage { data, context, _, error in
                        if let error {
                            continuation.resume(throwing: NWRemoteTunnel.mapNWError(error))
                            return
                        }
                        let metadata =
                            context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
                            as? NWProtocolWebSocket.Metadata
                        if let metadata, metadata.opcode == .binary {
                            continuation.resume(throwing: RemoteTunnelError.protocolViolation)
                            return
                        }
                        guard let data, data.count <= DlpWire.maxControlBytes,
                            let text = String(data: data, encoding: .utf8)
                        else {
                            continuation.resume(throwing: RemoteTunnelError.protocolViolation)
                            return
                        }
                        continuation.resume(returning: text)
                    }
                }
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(max(0, timeout) * 1_000_000_000))
                throw RemoteTunnelError.openTimeout
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else { throw RemoteTunnelError.openTimeout }
            return first
        }
    }

    private static func parseControl(_ text: String) throws -> [String: DlpJSON] {
        guard let fields = DlpWire.parseControlFrame(text) else { throw RemoteTunnelError.protocolViolation }
        return fields
    }

    // MARK: - 错误映射（RFC §5.7）

    static func mapError(code: String?, hostNow: Int?) -> RemoteTunnelError {
        switch code {
        case "ROUTE_OFFLINE": return .routeOffline
        case "OPEN_TIMEOUT": return .openTimeout
        case "RATE_LIMITED": return .rateLimited
        case "SERVER_BUSY": return .serverBusy
        case DlpWire.Reject.deviceLimit: return .busy(code: DlpWire.Reject.deviceLimit)
        case "PROTOCOL_ERROR", "UNSUPPORTED_VERSION", "AUTH_FAILED": return .protocolViolation
        default: return .rejected(code: DlpWire.safeCode(code), hostNow: hostNow)
        }
    }

    // MARK: - 工具

    /// `wss://host[:port]/path` → `NWEndpoint`。
    static func endpoint(for raw: String) -> NWEndpoint? {
        guard let url = URL(string: raw), let host = url.host else { return nil }
        let scheme = url.scheme?.lowercased()
        let port = url.port ?? (scheme == "wss" ? 443 : 80)
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { return nil }
        return .hostPort(host: NWEndpoint.Host(host), port: nwPort)
    }

    private static func validate(_ route: RemoteTunnelRoute) throws {
        guard route.routeId.count == DlpWire.routeBytes,
            route.keyId.count == DlpWire.keyBytes,
            route.key.count == 32
        else { throw RemoteTunnelError.protocolViolation }
        let endpoint = route.endpoint.lowercased()
        #if DEBUG
            let allowed = endpoint.hasPrefix("wss://") || endpoint.hasPrefix("ws://")
        #else
            let allowed = endpoint.hasPrefix("wss://")
        #endif
        guard allowed else { throw RemoteTunnelError.protocolViolation }
    }
}
