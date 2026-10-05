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
        assertSnapshot(
            of: styled(make(false, true), language: "en"),
            as: .image(layout: layout, traits: componentTraits(appearance: .light, language: "en")),
            named: "en",
            file: file,
            testName: testName
        )
        assertSnapshot(
            of: styled(make(false, true), language: "zh-Hans", large: true),
            as: .image(
                layout: layout,
                traits: componentTraits(appearance: .light, language: "zh-Hans", large: true)),
            named: "large",
            file: file,
            testName: testName
        )
        assertSnapshot(
            of: styled(make(false, true), language: "zh-Hans", reduceTransparency: true),
            as: .image(
                layout: layout, traits: componentTraits(appearance: .light, language: "zh-Hans")),
            named: "reduce-transparency",
            file: file,
            testName: testName
        )
        assertSnapshot(
            of: styled(make(false, true), language: "zh-Hans", increaseContrast: true),
            as: .image(
                layout: layout,
                traits: componentTraits(
                    appearance: .light, language: "zh-Hans", increaseContrast: true)),
            named: "increase-contrast",
            file: file,
            testName: testName
        )
    }

    private func styled<V: View>(
        _ view: V,
        language: String,
        appearance: UIUserInterfaceStyle = .light,
        large: Bool = false,
        reduceTransparency: Bool = false,
        increaseContrast: Bool = false
    ) -> some View {
        view
            .environment(\.locale, Locale(identifier: language))
            .environment(\.colorScheme, appearance == .dark ? .dark : .light)
            .environment(\.dynamicTypeSize, large ? .accessibility3 : .large)
            .environment(\._accessibilityReduceTransparency, reduceTransparency)
            .transaction { $0.disablesAnimations = true }
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
        render(
            make(false, false), height: height, traits: light, name: "disabled", file: file, testName: testName)
        render(
            make(false, true), height: height,
            traits: componentTraits(appearance: .light, language: "en"), language: "en", name: "en",
            file: file, testName: testName)
        render(
            make(false, true), height: height,
            traits: componentTraits(appearance: .light, language: "zh-Hans", large: true),
            language: "zh-Hans", name: "large", file: file, testName: testName)
        render(
            make(false, true), height: height,
            traits: componentTraits(appearance: .light, language: "zh-Hans"), language: "zh-Hans",
            name: "reduce-transparency", file: file, testName: testName)
        render(
            make(false, true), height: height,
            traits: componentTraits(appearance: .light, language: "zh-Hans", increaseContrast: true),
            language: "zh-Hans", name: "increase-contrast", file: file, testName: testName)
    }

    private func componentTraits(
        appearance: UIUserInterfaceStyle,
        language: String,
        large: Bool = false,
        increaseContrast: Bool = false
    ) -> UITraitCollection {
        var traits = [
            UITraitCollection(userInterfaceStyle: appearance),
            UITraitCollection(
                preferredContentSizeCategory: large ? .accessibilityExtraLarge : .large),
            UITraitCollection(typesettingLanguage: Locale.Language(identifier: language == "en" ? "en" : "zh-Hans")),
        ]
        if increaseContrast {
            traits.append(UITraitCollection(accessibilityContrast: .high))
        }
        return UITraitCollection(traitsFrom: traits)
    }

    private func render(
        _ view: UIView,
        height: CGFloat,
        traits: UITraitCollection,
        language: String? = nil,
        name: String,
        file: StaticString,
        testName: String
    ) {
        view.frame = CGRect(x: 0, y: 0, width: 402, height: height)
        view.overrideUserInterfaceStyle = traits.userInterfaceStyle
        if let language {
            view.semanticContentAttribute = language == "en" ? .forceLeftToRight : .unspecified
            if #available(iOS 17.0, *) {
                view.traitOverrides.userInterfaceStyle = traits.userInterfaceStyle
                view.traitOverrides.preferredContentSizeCategory = traits.preferredContentSizeCategory
                view.traitOverrides.typesettingLanguage = Locale.Language(
                    identifier: language == "en" ? "en" : "zh-Hans")
                view.traitOverrides.accessibilityContrast = traits.accessibilityContrast
            }
        }
        UIView.performWithoutAnimation {
            view.layoutIfNeeded()
        }
        assertSnapshot(
            of: view,
            as: .image(size: CGSize(width: 402, height: height), traits: traits),
            named: name,
            file: file,
            testName: testName
        )
    }
}
