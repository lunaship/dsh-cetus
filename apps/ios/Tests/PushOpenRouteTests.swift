import XCTest

@testable import DLCore

final class PushOpenRouteTests: XCTestCase {
    func testMismatchReturnsHome() {
        let route = PushOpenRouter.route(
            deviceID: "device-7", sessionID: "sess-9", pairedDeviceID: "device-8", knownSessions: ["sess-1"])
        XCTAssertEqual(route, .homeMissing)
    }

    func testMatchOpensSession() {
        let route = PushOpenRouter.route(
            deviceID: "device-7", sessionID: "sess-9", pairedDeviceID: "device-7", knownSessions: ["sess-9"])
        XCTAssertEqual(route, .session("sess-9"))
    }

    func testMissingSessionRefreshesOnce() {
        let first = PushOpenRouter.route(
            deviceID: "device-7", sessionID: "sess-9", pairedDeviceID: "device-7", knownSessions: [])
        XCTAssertEqual(first, .refresh)
        let second = PushOpenRouter.route(
            deviceID: "device-7", sessionID: "sess-9", pairedDeviceID: "device-7", knownSessions: [],
            refreshed: true)
        XCTAssertEqual(second, .homeMissing)
    }
}
