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
    func testContentFallback() throws {
        let key = SymmetricKey(size: .bits256)
        let rawKey = key.withUnsafeBytes { Data($0) }
        let plain = Data("{\"title\":\"Need approval\",\"ts\":1728000000}".utf8)
        let aad = Data("dlpush/1 content|device-7".utf8)
        let box = try AES.GCM.seal(plain, using: key, authenticating: aad)
        let encoded = box.combined!.base64EncodedString()
        let now = Date(timeIntervalSince1970: 1_728_000_100)
        XCTAssertEqual(
            PushContent.open(ciphertext: encoded, key: rawKey, deviceID: "device-7", now: now), "Need approval")
        XCTAssertEqual(
            PushContent.open(ciphertext: "broken", key: rawKey, deviceID: "device-7", now: now), "DeepLinks 有新的任务动态")
        let expired = Date(timeIntervalSince1970: 1_728_002_000)
        XCTAssertEqual(
            PushContent.open(ciphertext: encoded, key: rawKey, deviceID: "device-7", now: expired), "DeepLinks 有新的任务动态")
    }
}
