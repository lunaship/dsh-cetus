import CryptoKit
import XCTest

@testable import DLSecurity

final class PushRegistrationTests: XCTestCase {
    func testFreeBuildStaysDisabled() {
        var controller = PushRegistrationController(
            capabilities: LocalPushCapabilities(version: 1, apnsBuildEnabled: false), enabled: true)
        XCTAssertNil(controller.setEnabled(true))
        XCTAssertFalse(controller.enabled)
        XCTAssertEqual(controller.state, .unavailable("当前构建没有 APNs"))
    }

    func testRegistrationRequiresSuccessfulResponse() {
        var controller = PushRegistrationController(
            capabilities: LocalPushCapabilities(version: 1, apnsBuildEnabled: true))
        XCTAssertNil(controller.setEnabled(true))
        XCTAssertEqual(controller.state, .disabled)
        controller.noteRegistered()
        XCTAssertEqual(controller.state, .registered)
        controller.registrationFailed(.network)
        XCTAssertEqual(controller.state, .failed("网络失败"))
        XCTAssertFalse(controller.enabled)
        controller.setEnabled(true)
        controller.registrationFailed(.certificateChanged)
        XCTAssertEqual(controller.state, .failed("证书变化"))
        controller.setEnabled(true)
        controller.registrationFailed(.missingCapability)
        XCTAssertEqual(controller.state, .failed("主机没有推送能力"))
        XCTAssertNotEqual(controller.state, .registered)
    }

    func testContentKeyStaysOutOfSealedAndRecord() throws {
        let token = Data(repeating: 0xab, count: 32)
        let key = PushGatewayKey(kid: "gw-2026-10", publicKey: Data(repeating: 0x11, count: 32))
        let content = Data(repeating: 0x42, count: 32)
        let draft = try XCTUnwrap(
            PushRegistrar.prepare(
                token: PushDeviceToken(bytes: token, environment: .sandbox),
                key: key,
                prefs: PushPreferences(approval: true, question: false, completed: false, failed: false),
                contentKey: SymmetricKey(data: content)))
        let sealed = try JSONSerialization.jsonObject(with: JSONEncoder().encode(draft.body.sealed)) as? [String: Any]
        XCTAssertEqual(Set(sealed?.keys.map { String(describing: $0) } ?? []), Set(["v", "kid", "enc", "ct"]))
        let encoded = try JSONEncoder().encode(draft.body)
        let text = String(decoding: encoded, as: UTF8.self)
        XCTAssertFalse(text.contains("contentKey"))
        XCTAssertFalse(text.contains(String(repeating: "42", count: 32)))
        XCTAssertEqual(draft.contentKey, content)
        let record = PushRegistrationRecord(
            gateway: draft.body.gateway, kid: draft.body.kid,
            sealedFingerprint: PushRegistrar.fingerprint(draft.body.sealed),
            tokenFingerprint: draft.tokenFingerprint, prefs: draft.body.prefs)
        let stored = String(decoding: try JSONEncoder().encode(record), as: UTF8.self)
        XCTAssertFalse(stored.contains(String(repeating: "42", count: 32)))
        XCTAssertFalse(stored.contains(token.map { String(format: "%02x", $0) }.joined()))
        XCTAssertFalse(stored.contains(draft.body.sealed.enc))
        XCTAssertFalse(stored.contains(draft.body.sealed.ct))
        XCTAssertNil(
            PushRegistrar.prepare(
                token: PushDeviceToken(bytes: token, environment: .sandbox), key: key, prefs: draft.body.prefs,
                contentKey: SymmetricKey(data: content), liveActivityToken: " "))
    }

    func testTokenRotationReregisters() throws {
        let key = PushGatewayKey(kid: "gw-2026-10", publicKey: Data(repeating: 0x11, count: 32))
        let prefs = PushPreferences(approval: true, question: false, completed: false, failed: false)
        let first = try XCTUnwrap(
            PushRegistrar.prepare(
                token: PushDeviceToken(bytes: Data(repeating: 0xab, count: 32), environment: .sandbox),
                key: key, prefs: prefs, contentKey: SymmetricKey(data: Data(repeating: 0x42, count: 32))))
        let rotated = try XCTUnwrap(
            PushRegistrar.prepare(
                token: PushDeviceToken(bytes: Data(repeating: 0xcd, count: 32), environment: .sandbox),
                key: key, prefs: prefs, contentKey: SymmetricKey(data: Data(repeating: 0x24, count: 32))))
        let stored = PushRegistrationRecord(
            gateway: first.body.gateway, kid: first.body.kid,
            sealedFingerprint: PushRegistrar.fingerprint(first.body.sealed),
            tokenFingerprint: first.tokenFingerprint, prefs: first.body.prefs)
        let action = PushRegistrationReconciler.action(
            enabled: true, canRegister: true, stored: stored, prepared: rotated.body,
            tokenFingerprint: rotated.tokenFingerprint)
        XCTAssertEqual(action, .register(rotated.body))
        XCTAssertNotEqual(first.tokenFingerprint, rotated.tokenFingerprint)
    }

    func testSharedContentVectorAndPrefixSeparation() throws {
        let file = try load("content/dlpush-v1-seal-open.json", as: ContentVectorFile.self)
        let item = try XCTUnwrap(file.cases.first)
        XCTAssertEqual(file.aadPrefix, PushCrypto.contentAADPrefix)
        let key = try hex(item.key)
        let now = Date(timeIntervalSince1970: TimeInterval(item.plaintextTimestamp + 100))
        let opened = PushContent.openPayload(ciphertext: item.ct, key: key, deviceID: item.deviceID, now: now)
        XCTAssertEqual(opened?.title, "Need approval")
        XCTAssertEqual(opened?.kind, .approval)
        XCTAssertEqual(opened?.sessionID, "sess-1")
        let negative = try load("content/dlpush-v1-negative.json", as: ContentNegativeFile.self)
        XCTAssertEqual(negative.wrongPrefix, PushCrypto.tokenAADPrefix)
        let expired = try XCTUnwrap(negative.cases.first { $0.id == "ts-expired" })
        let stale = Date(timeIntervalSince1970: TimeInterval(expired.now ?? 0))
        XCTAssertEqual(
            PushContent.open(ciphertext: item.ct, key: key, deviceID: item.deviceID, now: stale), PushContent.generic)
        var raw = try XCTUnwrap(Data(base64Encoded: item.ct))
        raw[raw.count - 1] ^= 0x01
        XCTAssertEqual(
            PushContent.open(ciphertext: raw.base64EncodedString(), key: key, deviceID: item.deviceID, now: now),
            PushContent.generic)
        raw = try XCTUnwrap(Data(base64Encoded: item.ct))
        raw[12] ^= 0x01
        XCTAssertEqual(
            PushContent.open(ciphertext: raw.base64EncodedString(), key: key, deviceID: item.deviceID, now: now),
            PushContent.generic)
        XCTAssertNotEqual(PushCrypto.tokenAADPrefix, PushCrypto.contentAADPrefix)
        XCTAssertEqual(String(decoding: PushCrypto.tokenInfo, as: UTF8.self), "dlpush/1 token")
    }

    func testSharedTokenVectorRejectsContentPrefix() throws {
        let file = try load("hpke/dlpush-v1-seal-open.json", as: TokenVectorFile.self)
        let item = try XCTUnwrap(file.cases.first)
        XCTAssertEqual(file.info, String(decoding: PushCrypto.tokenInfo, as: UTF8.self))
        XCTAssertEqual(file.aadPrefix, PushCrypto.tokenAADPrefix)
        let sealed = PushSealedToken(v: item.sealed.v, kid: item.sealed.kid, enc: item.sealed.enc, ct: item.sealed.ct)
        let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hex(item.skRm))
        let opened = try PushCrypto.openToken(
            sealed: sealed, recipientKey: key, info: PushCrypto.tokenInfo, aad: PushCrypto.tokenAAD(kid: item.kid))
        XCTAssertEqual(opened, Data(item.plaintext.utf8))
        XCTAssertThrowsError(
            try PushCrypto.openToken(
                sealed: sealed, recipientKey: key, info: PushCrypto.tokenInfo,
                aad: Data((PushCrypto.contentAADPrefix + item.kid).utf8)))
        var flipped = item.sealed
        let enc = try XCTUnwrap(Data(base64URLEncoded: flipped.enc))
        var bad = enc
        bad[0] ^= 0x01
        flipped.enc = bad.base64URLEncodedString()
        XCTAssertThrowsError(
            try PushCrypto.openToken(
                sealed: PushSealedToken(v: flipped.v, kid: flipped.kid, enc: flipped.enc, ct: flipped.ct),
                recipientKey: key, info: PushCrypto.tokenInfo, aad: PushCrypto.tokenAAD(kid: item.kid)))
    }

    func testNotificationTapRoutesWithoutApproving() {
        let request = PushPayloadReader.openRequest(in: [
            "deviceId": " device-7 ",
            "sessionId": "sess-9",
            "action": "approve",
            "e": "ciphertext",
        ])
        XCTAssertEqual(request, PushOpenRequest(deviceID: "device-7", sessionID: "sess-9"))
        XCTAssertNil(PushPayloadReader.openRequest(in: ["deviceId": "device-7"]))
        XCTAssertNil(PushPayloadReader.openRequest(in: ["deviceId": " ", "sessionId": "sess-9"]))
    }

    func testContentKeyLivesOnlyInSecureStore() throws {
        let store = InMemorySecureStore()
        let keys = PushKeyStore(store: store)
        let key = Data(repeating: 0x42, count: 32)
        XCTAssertTrue(try keys.install(key))
        XCTAssertFalse(try keys.install(key))
        XCTAssertEqual(try keys.load(), key)
        try keys.remove()
        XCTAssertNil(try keys.load())
    }

    private func load<T: Decodable>(_ relative: String, as type: T.Type) throws -> T {
        let url = repoRoot().appendingPathComponent("testdata/push").appendingPathComponent(relative)
        return try JSONDecoder().decode(type, from: Data(contentsOf: url))
    }

    private func repoRoot() -> URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<6 { url.deleteLastPathComponent() }
        return url
    }

    private func hex(_ value: String) throws -> Data {
        var out = Data()
        var buffer = ""
        for character in value {
            buffer.append(character)
            if buffer.count == 2 {
                guard let byte = UInt8(buffer, radix: 16) else { throw TestError.hex }
                out.append(byte)
                buffer = ""
            }
        }
        guard buffer.isEmpty else { throw TestError.hex }
        return out
    }
}

private enum TestError: Error { case hex }

private struct TokenVectorFile: Decodable {
    var info: String
    var aadPrefix: String
    var cases: [TokenVectorCase]

    enum CodingKeys: String, CodingKey {
        case info = "info_utf8"
        case aadPrefix = "aad_prefix_utf8"
        case cases
    }
}

private struct TokenVectorCase: Decodable {
    var kid: String
    var skRm: String
    var plaintext: String
    var sealed: TokenSealed

    enum CodingKeys: String, CodingKey {
        case kid
        case skRm
        case plaintext = "plaintext_utf8"
        case sealed
    }
}

private struct TokenSealed: Decodable {
    var v: Int
    var kid: String
    var enc: String
    var ct: String
}

private struct ContentVectorFile: Decodable {
    var aadPrefix: String
    var cases: [ContentVectorCase]

    enum CodingKeys: String, CodingKey {
        case aadPrefix = "aad_prefix_utf8"
        case cases
    }
}

private struct ContentVectorCase: Decodable {
    var deviceID: String
    var key: String
    var ct: String
    var plaintext: String

    var plaintextTimestamp: Int {
        guard let data = plaintext.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let value = object["ts"] as? Int
        else { return 0 }
        return value
    }

    enum CodingKeys: String, CodingKey {
        case deviceID = "deviceId"
        case key
        case ct
        case plaintext = "plaintext_utf8"
    }
}

private struct ContentNegativeFile: Decodable {
    var wrongPrefix: String
    var cases: [ContentNegativeCase]

    enum CodingKeys: String, CodingKey {
        case wrongPrefix = "wrong_aad_prefix_utf8"
        case cases
    }
}

private struct ContentNegativeCase: Decodable {
    var id: String
    var now: Int?
}

extension Data {
    fileprivate init?(base64URLEncoded value: String) {
        var converted = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = converted.count % 4
        if remainder > 0 { converted.append(String(repeating: "=", count: 4 - remainder)) }
        self.init(base64Encoded: converted)
    }

    fileprivate func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
