import DLSecurity
import Foundation
import Testing

/// Regression test for the C12 end-to-end break fixed on 2026-10-08.
///
/// The APNs payload built by `push/internal/http/gateway.go buildAlertBody`
/// contains exactly `aps`, `e`, and `k` — never `deviceId` or `sessionId`. The
/// notification extension must therefore rebuild the content AAD
/// (`"dlpush/1 content|" + deviceId`) from the shared `kid` -> `deviceId`
/// binding, and recover `sessionId` by opening the ciphertext.
///
/// Before the fix every unit test passed `deviceId` straight into the decrypt
/// call, so nothing exercised this real path and the defect shipped green.
@Suite("Push payload chain (gateway shape -> NSE)")
struct PushPayloadChainTests {
    private static let kid = "gateway-kid-2026-10"
    private static let deviceID = "device-7"

    /// The content vector the plugin and gateway share. The file is flat: the
    /// case carries `ct` directly, and the expected title lives inside
    /// `plaintext_utf8`.
    private struct Vector: Decodable {
        struct Case: Decodable {
            var deviceID: String
            var key: String
            var plaintextUTF8: String
            var ct: String

            enum CodingKeys: String, CodingKey {
                case deviceID = "deviceId"
                case key
                case plaintextUTF8 = "plaintext_utf8"
                case ct
            }

            /// The `title` field the extension is expected to display.
            var expectedTitle: String {
                guard let data = plaintextUTF8.data(using: .utf8),
                    let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                    let title = object["title"] as? String
                else { return "" }
                return title
            }

            /// The `ts` the plaintext was sealed with.
            var timestamp: Int {
                guard let data = plaintextUTF8.data(using: .utf8),
                    let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                    let value = object["ts"] as? Int
                else { return 0 }
                return value
            }

            var expectedSessionID: String {
                guard let data = plaintextUTF8.data(using: .utf8),
                    let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                    let value = object["sessionId"] as? String
                else { return "" }
                return value
            }
        }
        var cases: [Case]
    }

    private static func loadVector() throws -> Vector {
        // apps/ios/Tests -> repo root -> testdata/push/content
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let url = root.appending(path: "testdata/push/content/dlpush-v1-seal-open.json")
        return try JSONDecoder().decode(Vector.self, from: Data(contentsOf: url))
    }

    /// Exactly the shape the gateway sends: `aps` + `e` + `k`, nothing else.
    private static func gatewayPayload(ct: String, kid: String) -> [AnyHashable: Any] {
        [
            "aps": [
                "alert": ["title": "cetus", "body": "有新的任务动态"],
                "mutable-content": 1,
                "thread-id": "",
            ],
            "e": ct,
            "k": kid,
        ]
    }

    /// The shared vector was sealed in 2024, so tests must pin `now` just after
    /// its `ts`; otherwise the 15-minute freshness rule (correctly) rejects it.
    private static func freshNow(for item: Vector.Case) -> Date {
        Date(timeIntervalSince1970: TimeInterval(item.timestamp + 100))
    }

    private static func store(withBinding: Bool) throws -> PushKeyStore {
        let store = PushKeyStore(store: InMemorySecureStore())
        let vector = try PushPayloadChainTests.loadVector()
        try store.save(Data(hexString: vector.cases[0].key))
        if withBinding {
            try store.bind(kid: kid, deviceID: vector.cases[0].deviceID)
        }
        return store
    }

    @Test("真实网关 payload（无 deviceId）也能解出正文")
    func payloadWithoutDeviceIDStillDecrypts() throws {
        let vector = try PushPayloadChainTests.loadVector()
        let item = vector.cases[0]
        let store = try PushPayloadChainTests.store(withBinding: true)
        let info = PushPayloadChainTests.gatewayPayload(ct: item.ct, kid: PushPayloadChainTests.kid)

        // The payload genuinely has no deviceId — that is the point of the test.
        #expect(info["deviceId"] == nil)

        let deviceID = PushPayloadReader.deviceID(in: info, bindings: store)
        #expect(deviceID == item.deviceID)

        let key = try #require(try store.load())
        let title = PushContent.open(
            ciphertext: item.ct, key: key, deviceID: deviceID ?? "", now: PushPayloadChainTests.freshNow(for: item))
        #expect(title == item.expectedTitle)
        #expect(title != PushContent.generic)
    }

    @Test("未注册过 kid 时回退通用文案，不猜 AAD")
    func unknownKidFallsBackToGeneric() throws {
        let vector = try PushPayloadChainTests.loadVector()
        let info = PushPayloadChainTests.gatewayPayload(ct: vector.cases[0].ct, kid: "unknown-kid")

        // A build that never registered has no binding to look up.
        let empty = PushKeyStore(store: InMemorySecureStore())
        #expect(PushPayloadReader.deviceID(in: info, bindings: empty) == nil)

        let key = Data(hexString: vector.cases[0].key)
        let title = PushContent.open(
            ciphertext: vector.cases[0].ct, key: key, deviceID: "")
        #expect(title == PushContent.generic)
    }

    @Test("kid 绑定按多台电脑各自保存，互不覆盖")
    func bindingsArePerKid() throws {
        let store = PushKeyStore(store: InMemorySecureStore())
        try store.bind(kid: "kid-a", deviceID: "device-a")
        try store.bind(kid: "kid-b", deviceID: "device-b")

        #expect(try store.deviceID(forKid: "kid-a") == "device-a")
        #expect(try store.deviceID(forKid: "kid-b") == "device-b")
        #expect(try store.deviceID(forKid: "kid-c") == nil)

        // Re-binding one key must not disturb the other.
        try store.bind(kid: "kid-a", deviceID: "device-a2")
        #expect(try store.deviceID(forKid: "kid-a") == "device-a2")
        #expect(try store.deviceID(forKid: "kid-b") == "device-b")
    }

    @Test("点击通知能从 payload 恢复 sessionId（payload 里没有该字段）")
    func tapRecoversSessionIDFromCiphertext() throws {
        let vector = try PushPayloadChainTests.loadVector()
        let item = vector.cases[0]
        let store = try PushPayloadChainTests.store(withBinding: true)
        let info = PushPayloadChainTests.gatewayPayload(ct: item.ct, kid: PushPayloadChainTests.kid)

        #expect(info["sessionId"] == nil)

        let request = try #require(
            PushPayloadReader.openRequest(
                in: info, bindings: store, now: PushPayloadChainTests.freshNow(for: item)))
        #expect(request.deviceID == item.deviceID)

        let opened = try #require(
            PushContent.openPayload(
                ciphertext: item.ct, key: Data(hexString: item.key), deviceID: item.deviceID,
                now: PushPayloadChainTests.freshNow(for: item)))
        #expect(request.sessionID == opened.sessionID)
        #expect(request.sessionID == item.expectedSessionID)
    }

    @Test("篡改密文后点击不回退到可导航的会话")
    func tamperedPayloadDoesNotRoute() throws {
        let vector = try PushPayloadChainTests.loadVector()
        let store = try PushPayloadChainTests.store(withBinding: true)
        var ct = Array(vector.cases[0].ct)
        ct[10] = ct[10] == "A" ? "B" : "A"
        let info = PushPayloadChainTests.gatewayPayload(ct: String(ct), kid: PushPayloadChainTests.kid)

        #expect(
            PushPayloadReader.openRequest(
                in: info, bindings: store, now: PushPayloadChainTests.freshNow(for: vector.cases[0]))
                == nil)
    }
}

extension Data {
    /// Convenience for the fixed hex vectors shared with the Go gateway.
    init(hexString: String) {
        var bytes: [UInt8] = []
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2, limitedBy: hexString.endIndex) ?? hexString.endIndex
            bytes.append(UInt8(hexString[index..<next], radix: 16) ?? 0)
            index = next
        }
        self.init(bytes)
    }
}
