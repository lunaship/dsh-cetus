import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

final class WelcomeSnapshotTests: XCTestCase {
    /// Task-local. `setUp` returns before the test method, so it cannot hold record mode.
    override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
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
