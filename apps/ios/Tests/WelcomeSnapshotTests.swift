import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

final class WelcomeSnapshotTests: XCTestCase {
    override func setUp() {
        super.setUp()
        isRecording = ProcessInfo.processInfo.environment["RECORD_SNAPSHOTS"] == "1"
    }

    func testWelcomeLight() {
        let view = NavigationStack { WelcomeView(staticSnapshot: true) }
            .environment(\.locale, Locale(identifier: "en"))
            .environment(\.dynamicTypeSize, .large)
            .transaction { $0.disablesAnimations = true }
        let traits = UITraitCollection(traitsFrom: [
            UITraitCollection(userInterfaceStyle: .light),
            UITraitCollection(preferredContentSizeCategory: .large),
        ])
        assertSnapshot(
            of: view,
            as: .image(layout: .fixed(width: 402, height: 874), traits: traits),
            named: "light"
        )
    }
}
