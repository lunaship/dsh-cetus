import DLNet
import Foundation
import Network
import Testing

@Suite struct LocalGatewayAuthTests {
    @Test func correctCredentialReachesTLSAndBadCredentialsDoNot() async throws {
        let gate = LocalGatewayProbe(expectedPassword: "correct")
        let port = try await gate.start()
        defer { Task { await gate.stop() } }

        let accepted = try await request(port: port, password: "correct")
        let acceptedCounts = await gate.counts
        #expect(accepted == NSURLErrorSecureConnectionFailed)
        #expect(acceptedCounts.accepted == 2)
        #expect(acceptedCounts.tls == acceptedCounts.accepted)
        #expect(acceptedCounts.direct == 0)

        let wrong = try await request(port: port, password: "wrong")
        let wrongCounts = await gate.counts
        #expect(wrong == 80)
        #expect(wrongCounts == acceptedCounts)

        let missing = try await request(port: port, password: nil)
        let missingCounts = await gate.counts
        #expect(missing == NSURLErrorTimedOut)
        #expect(missingCounts == acceptedCounts)

        let loopback = try await request(
            port: port, password: "correct", url: URL(string: "https://127.0.0.1/probe")!)
        let loopbackCounts = await gate.counts
        #expect(loopback == NSURLErrorSecureConnectionFailed)
        #expect(loopbackCounts.accepted == acceptedCounts.accepted)
        #expect(loopbackCounts.tls == acceptedCounts.tls)
        #expect(loopbackCounts.direct == 2)
    }

    private func request(
        port: UInt16, password: String?, url: URL = URL(string: "https://10.255.255.1/probe")!
    ) async throws -> Int {
        let configuration = URLSessionConfiguration.ephemeral
        if let password {
            LocalGatewayConfiguration(port: port, username: "device", password: password)
                .apply(to: configuration)
        }
        let session = URLSession(
            configuration: configuration, delegate: GatewayCredentialDelegate(password: password), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3
        do {
            _ = try await session.data(for: request)
            Issue.record("gateway allowed an unauthenticated inner response")
            return 0
        } catch {
            return (error as NSError).code
        }
    }
}

private final class GatewayCredentialDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    let password: String?

    init(password: String?) {
        self.password = password
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.isProxy(), let password else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(
            .useCredential, URLCredential(user: "device", password: password, persistence: .forSession))
    }
}

private struct GatewayCounts: Equatable {
    var accepted: Int
    var tls: Int
    var direct: Int
}

private actor LocalGatewayProbe {
    let expectedPassword: String
    private var listener: NWListener?
    private var directListener: NWListener?
    private(set) var acceptedCount = 0
    private(set) var tlsPrefixCount = 0
    private(set) var directConnectionCount = 0

    var counts: GatewayCounts {
        GatewayCounts(accepted: acceptedCount, tls: tlsPrefixCount, direct: directConnectionCount)
    }

    init(expectedPassword: String) {
        self.expectedPassword = expectedPassword
    }

    func start() async throws -> UInt16 {
        let proxy = try NWListener(using: .tcp, on: .any)
        listener = proxy
        proxy.newConnectionHandler = { [weak self] connection in
            connection.start(queue: .global())
            Task { await self?.serve(connection) }
        }
        let port = try await readyPort(proxy)

        let direct = try NWListener(using: .tcp, on: 443)
        directListener = direct
        direct.newConnectionHandler = { [weak self] connection in
            connection.cancel()
            Task { await self?.markDirect() }
        }
        _ = try await readyPort(direct)
        return port
    }

    func stop() {
        listener?.cancel()
        directListener?.cancel()
    }

    private func markDirect() {
        directConnectionCount += 1
    }

    private func serve(_ connection: NWConnection) async {
        let request = await receive(connection, maximum: 2048)
        let token = Data("device:\(expectedPassword)".utf8).base64EncodedString()
        let text = String(data: request, encoding: .utf8) ?? ""
        guard text.contains("Proxy-Authorization: Basic \(token)") else {
            await send(
                connection,
                "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"gate\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            )
            connection.cancel()
            return
        }
        acceptedCount += 1
        await send(connection, "HTTP/1.1 200 Connection Established\r\n\r\n")
        let next = await receive(connection, maximum: 8)
        if next.starts(with: Data([0x16, 0x03, 0x01])) {
            tlsPrefixCount += 1
        }
        connection.cancel()
    }

    private func receive(_ connection: NWConnection, maximum: Int) async -> Data {
        await withCheckedContinuation { continuation in
            connection.receive(minimumIncompleteLength: 1, maximumLength: maximum) { data, _, _, _ in
                continuation.resume(returning: data ?? Data())
            }
        }
    }

    private func send(_ connection: NWConnection, _ text: String) async {
        await withCheckedContinuation { continuation in
            connection.send(content: Data(text.utf8), completion: .contentProcessed { _ in continuation.resume() })
        }
    }

    private func readyPort(_ listener: NWListener) async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            let once = Once(continuation)
            listener.stateUpdateHandler = { state in
                if case .ready = state { once.finish(.success(())) }
                if case .failed(let error) = state { once.finish(.failure(error)) }
            }
            listener.start(queue: .global())
        }
        return listener.port?.rawValue ?? 0
    }
}

private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var resumed = false
    private let continuation: CheckedContinuation<Void, Error>

    init(_ continuation: CheckedContinuation<Void, Error>) {
        self.continuation = continuation
    }

    func finish(_ result: Result<Void, Error>) {
        lock.lock()
        defer { lock.unlock() }
        guard !resumed else { return }
        resumed = true
        continuation.resume(with: result)
    }
}
