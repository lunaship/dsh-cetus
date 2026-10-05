import DLCore
import DLNet
import Foundation
import Testing

@Suite struct PreviewProxyTests {
    @Test func loopbackRejectsTheWrongKeyAndServesTheRightOne() async throws {
        let proxy = PreviewLocalProxy { _ in
            PreviewHTTPResult(status: 200, body: Data("ok".utf8))
        }
        try await proxy.start()
        defer { Task { await proxy.stop() } }
        let port = await proxy.port
        #expect(port != 0)
        let refused = try await text(port: port, path: "/nope/path")
        #expect(refused.status == 404)
        let id = String(repeating: "ef", count: 12)
        let key = await proxy.key
        let ok = try await text(port: port, path: "/\(key)/\(id)/index.html")
        #expect(ok.status == 200)
        #expect(ok.body == "ok")
    }

    private func text(port: UInt16, path: String) async throws -> (status: Int, body: String) {
        let url = URL(string: "http://127.0.0.1:\(port)\(path)")!
        let (data, response) = try await URLSession.shared.data(from: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return (status, String(data: data, encoding: .utf8) ?? "")
    }
}
