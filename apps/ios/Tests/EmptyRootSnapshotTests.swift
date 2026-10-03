import SnapshotTesting
import SwiftUI
import XCTest
@testable import DeepLinks

final class EmptyRootSnapshotTests: XCTestCase {
    override func setUp() {
        super.setUp()
        isRecording = ProcessInfo.processInfo.environment["RECORD_SNAPSHOTS"] == "1"
    }

    func testEmptyRootLight() {
        let view = RootView()
        assertSnapshot(
            of: view,
            as: .image(layout: .fixed(width: 402, height: 874)),
            named: "light"
        )
    }
}
