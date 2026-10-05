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

    func testWelcomeAccessibility() {
        assertWelcome(
            language: "zh-Hans", appearance: .light, large: false,
            reduceTransparency: true, increaseContrast: false,
            named: "reduce-transparency", testName: "Snapshot_1_2_welcome_light_zh")
        assertWelcome(
            language: "zh-Hans", appearance: .light, large: false,
            reduceTransparency: false, increaseContrast: true,
            named: "increase-contrast", testName: "Snapshot_1_2_welcome_light_zh")
        assertWelcome(
            language: "en", appearance: .light, large: true,
            reduceTransparency: false, increaseContrast: false,
            named: "large", testName: "Snapshot_1_2_welcome_a11y_light_en")
        assertWelcome(
            language: "en", appearance: .dark, large: true,
            reduceTransparency: false, increaseContrast: false,
            named: "large", testName: "Snapshot_1_2_welcome_a11y_dark_en")
    }

    private func assertWelcome(
        language: String,
        appearance: UIUserInterfaceStyle,
        large: Bool,
        reduceTransparency: Bool,
        increaseContrast: Bool,
        named: String,
        testName: String
    ) {
        let view = NavigationStack { WelcomeView(staticSnapshot: true) }
            .environment(\.locale, Locale(identifier: language))
            .environment(\.colorScheme, appearance == .dark ? .dark : .light)
            .environment(\.dynamicTypeSize, large ? .accessibility3 : .large)
            .environment(\._accessibilityReduceTransparency, reduceTransparency)
            .transaction { $0.disablesAnimations = true }
        var traits = [
            UITraitCollection(userInterfaceStyle: appearance),
            UITraitCollection(
                preferredContentSizeCategory: large ? .accessibilityExtraLarge : .large),
        ]
        if increaseContrast {
            traits.append(UITraitCollection(accessibilityContrast: .high))
        }
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let previousKey = scene?.windows.first { $0.isKeyWindow }
        let previousStyles = scene?.windows.map { ($0, $0.overrideUserInterfaceStyle) }
        assertSnapshot(
            of: view,
            as: .image(
                layout: .fixed(width: 402, height: 874),
                traits: UITraitCollection(traitsFrom: traits)
            ),
            named: named,
            testName: testName
        )
        // SnapshotTesting leaves its temporary window on the shared scene. Later wide screenshots
        // can draw through it and pick up this test's contrast or color style.
        for window in scene?.windows ?? [] where window !== previousKey {
            window.isHidden = true
            window.rootViewController = nil
            window.windowScene = nil
        }
        for (window, style) in previousStyles ?? [] {
            window.overrideUserInterfaceStyle = style
        }
        previousKey?.makeKey()
    }
}
