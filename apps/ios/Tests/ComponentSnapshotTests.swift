import SnapshotTesting
import SwiftUI
import UIKit
import XCTest
@testable import DeepLinks

final class ComponentSnapshotTests: XCTestCase {
    /// Task-local. `setUp` returns before the test method, so it cannot hold record mode.
    override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testInboxRow() {
        assertSwiftUI(ComponentSceneFactory.inbox, height: 160)
    }

    func testStatusSlot() {
        assertSwiftUI(ComponentSceneFactory.status, height: 80)
    }

    func testChip() {
        assertSwiftUI(ComponentSceneFactory.chip, height: 80)
    }

    func testProcessLine() {
        assertSwiftUI(ComponentSceneFactory.process, height: 64)
    }

    func testCodeBlock() {
        assertSwiftUI(ComponentSceneFactory.code, height: 120)
    }

    func testEmptyState() {
        assertSwiftUI(ComponentSceneFactory.empty, height: 320)
    }

    func testBanner() {
        assertSwiftUI(ComponentSceneFactory.banner, height: 120)
    }

    func testComposer() {
        assertUIKit(ComponentSceneFactory.composer, height: 96)
    }

    func testDecisionBar() {
        assertUIKit(ComponentSceneFactory.decision, height: 220)
    }

    private func assertSwiftUI<V: View>(
        _ make: (Bool, Bool) -> V,
        height: CGFloat,
        file: StaticString = #filePath,
        testName: String = #function
    ) {
        let layout = SwiftUISnapshotLayout.fixed(width: 402, height: height)
        let light = UITraitCollection(userInterfaceStyle: .light)
        let dark = UITraitCollection(userInterfaceStyle: .dark)
        assertSnapshot(
            of: make(false, true),
            as: .image(layout: layout, traits: light),
            named: "light",
            file: file,
            testName: testName
        )
        assertSnapshot(
            of: make(false, true),
            as: .image(layout: layout, traits: dark),
            named: "dark",
            file: file,
            testName: testName
        )
        assertSnapshot(
            of: make(true, true),
            as: .image(layout: layout, traits: light),
            named: "long",
            file: file,
            testName: testName
        )
        assertSnapshot(
            of: make(false, false),
            as: .image(layout: layout, traits: light),
            named: "disabled",
            file: file,
            testName: testName
        )
    }

    private func assertUIKit(
        _ make: (Bool, Bool) -> UIView,
        height: CGFloat,
        file: StaticString = #filePath,
        testName: String = #function
    ) {
        let light = UITraitCollection(userInterfaceStyle: .light)
        let dark = UITraitCollection(userInterfaceStyle: .dark)
        render(make(false, true), height: height, traits: light, name: "light", file: file, testName: testName)
        render(make(false, true), height: height, traits: dark, name: "dark", file: file, testName: testName)
        render(make(true, true), height: height, traits: light, name: "long", file: file, testName: testName)
        render(make(false, false), height: height, traits: light, name: "disabled", file: file, testName: testName)
    }

    private func render(
        _ view: UIView,
        height: CGFloat,
        traits: UITraitCollection,
        name: String,
        file: StaticString,
        testName: String
    ) {
        view.frame = CGRect(x: 0, y: 0, width: 402, height: height)
        view.overrideUserInterfaceStyle = traits.userInterfaceStyle
        view.layoutIfNeeded()
        assertSnapshot(
            of: view,
            as: .image(size: CGSize(width: 402, height: height), traits: traits),
            named: name,
            file: file,
            testName: testName
        )
    }
}
