import XCTest

@testable import DLSecurity

final class PushRegistrationWireTests: XCTestCase {
    func testPluginFieldKIsSeparateFromSealed() throws {
        let content = Data(repeating: 0x42, count: 32)
        let body = PushRegistrationBody(
            gateway: "https://push.dshlinks.com",
            kid: "gw-2026-10",
            sealed: PushTokenEnvelope(v: 1, kid: "gw-2026-10", enc: "abcd", ct: "efgh"),
            prefs: PushPreferences(approval: true, question: true, completed: false, failed: false))
        let json = try PushRegistrationWire.json(body, contentKey: content)
        let object = try JSONSerialization.jsonObject(with: json) as? [String: Any]
        let keys = Set(object?.keys.map { String(describing: $0) } ?? [])
        XCTAssertEqual(keys, Set(["gateway", "kid", "sealed", "k", "prefs"]))
        let sealed = object?["sealed"] as? [String: Any]
        XCTAssertEqual(Set(sealed?.keys.map { String(describing: $0) } ?? []), Set(["v", "kid", "enc", "ct"]))
        XCTAssertEqual(object?["k"] as? String, String(repeating: "42", count: 32))
        let record = PushRegistrationRecord(
            gateway: body.gateway, kid: body.kid, sealedFingerprint: PushRegistrar.fingerprint(body.sealed),
            tokenFingerprint: "fingerprint", prefs: body.prefs)
        let stored = String(decoding: try JSONEncoder().encode(record), as: UTF8.self)
        XCTAssertFalse(stored.contains("\"k\""))
        XCTAssertFalse(stored.contains("\"sealed\""))
        XCTAssertFalse(stored.contains(String(repeating: "42", count: 64)))
        XCTAssertFalse(stored.contains(body.sealed.enc))
        XCTAssertFalse(stored.contains(body.sealed.ct))
    }
}
