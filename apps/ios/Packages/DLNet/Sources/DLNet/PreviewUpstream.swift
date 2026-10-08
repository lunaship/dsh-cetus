import Foundation
import Network
import Security

/// Opens the host side of an approved preview upgrade. The token never enters the loopback request.
public enum PreviewUpstream {
    public static func open(
        baseURL: URL, fingerprint: String, token: String, path: String,
        key: String, protocols: String?
    ) async throws -> NWConnection {
        guard baseURL.scheme == "https", let host = baseURL.host,
            let portNumber = UInt16(exactly: baseURL.port ?? 443), let port = NWEndpoint.Port(rawValue: portNumber),
            path.hasPrefix("/dsh-link/mobile/preview/"), !path.contains("\r"), !path.contains("\n"),
            !token.contains("\r"), !token.contains("\n"),
            !key.contains("\r"), !key.contains("\n")
        else { throw URLError(.badURL) }
        let tls = NWProtocolTLS.Options()
        let rejection = PreviewPinRejection()
        NWRemoteTunnelTransport.installPin(tls, expected: fingerprint) { rejection.reject() }
        sec_protocol_options_set_tls_server_name(tls.securityProtocolOptions, host)
        let connection = NWConnection(
            host: NWEndpoint.Host(host), port: port, using: NWParameters(tls: tls, tcp: .init()))
        var delivered = false
        defer { if !delivered { connection.cancel() } }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let completion = PreviewCompletion(continuation)
                rejection.install {
                    completion.finish(.failure(HostClientError.certificateChanged))
                    connection.cancel()
                }
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready: completion.finish(.success(()))
                    case .failed(let error): completion.finish(.failure(error))
                    case .cancelled: completion.finish(.failure(CancellationError()))
                    default: break
                    }
                }
                DispatchQueue.global().asyncAfter(deadline: .now() + 12) {
                    if completion.finish(.failure(URLError(.timedOut))) { connection.cancel() }
                }
                connection.start(queue: DispatchQueue(label: "dev.deeplinks.preview.upstream"))
            }
            var request =
                "GET \(path) HTTP/1.1\r\nHost: \(host):\(port.rawValue)\r\n"
                + "Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: \(key)\r\nx-dsh-link-token: \(token)\r\n"
            if let protocols, !protocols.contains("\r"), !protocols.contains("\n"), !protocols.isEmpty {
                request += "Sec-WebSocket-Protocol: \(protocols)\r\n"
            }
            request += "\r\n"
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                connection.send(
                    content: Data(request.utf8),
                    completion: .contentProcessed { error in
                        if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                    })
            }
            delivered = true
            return connection
        } onCancel: {
            connection.cancel()
        }
    }
}

private final class PreviewPinRejection: @unchecked Sendable {
    private let lock = NSLock()
    private var handler: (@Sendable () -> Void)?
    func install(_ handler: @escaping @Sendable () -> Void) {
        lock.lock()
        self.handler = handler
        lock.unlock()
    }
    func reject() {
        lock.lock()
        let handler = handler
        lock.unlock()
        handler?()
    }
}

final class PreviewCompletion<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?

    init(_ continuation: CheckedContinuation<Value, Error>) { self.continuation = continuation }

    @discardableResult func finish(_ result: Result<Value, Error>) -> Bool {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        guard let pending else { return false }
        pending.resume(with: result)
        return true
    }
}
