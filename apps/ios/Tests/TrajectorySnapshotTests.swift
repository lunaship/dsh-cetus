import DLModels
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor final class TrajectorySnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testTrace() {
        matrix("4_7_trace") { language in page(language: language) }
    }

    private func page(language: String) -> some View {
        let english = language == "en"
        return NavigationStack {
            TrajectoryPage(messages: [
                HistoryMessage(
                    id: "u", role: "user",
                    text: english ? "Shorten the release notes" : "把发布说明写短一点",
                    time: 1_700_000_000, turn: 1),
                HistoryMessage(
                    id: "t", role: "tool_call", time: 1_700_000_040, name: "read",
                    args: #"{"path":"Notes.md"}"#, turn: 1),
                HistoryMessage(
                    id: "a", role: "assistant",
                    text: english ? "The title can be shorter" : "标题可以再短一点。",
                    time: 1_700_000_080, turn: 1),
            ])
        }
    }

    private func matrix<V: View>(_ scene: String, make: (String) -> V) {
        for language in ["zh-Hans", "en"] {
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                for large in [false, true] {
                    render(scene, appearance: appearance, language: language, large: large) {
                        make(language)
                    }
                }
            }
        }
        render(
            scene, appearance: .light, language: "zh-Hans", large: false, reduceTransparency: true
        ) {
            make("zh-Hans")
        }
        render(
            scene, appearance: .light, language: "zh-Hans", large: false, increaseContrast: true
        ) {
            make("zh-Hans")
        }
    }

    private func render<V: View>(
        _ scene: String,
        appearance: UIUserInterfaceStyle,
        language: String,
        large: Bool,
        reduceTransparency: Bool = false,
        increaseContrast: Bool = false,
        make: () -> V
    ) {
        let content = make()
            .environment(\.locale, Locale(identifier: language))
            .environment(\.colorScheme, appearance == .dark ? .dark : .light)
            .environment(\.dynamicTypeSize, large ? DynamicTypeSize.accessibility3 : DynamicTypeSize.large)
            .environment(\._accessibilityReduceTransparency, reduceTransparency)
            .transaction { $0.disablesAnimations = true }
        let image = render(
            content, appearance: appearance, large: large, increaseContrast: increaseContrast)
        assertSnapshot(
            of: image, as: .image,
            named: variantName(
                large: large, reduceTransparency: reduceTransparency, increaseContrast: increaseContrast),
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func render<V: View>(
        _ view: V, appearance: UIUserInterfaceStyle, large: Bool, increaseContrast: Bool
    ) -> UIImage {
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: view)
        host.overrideUserInterfaceStyle = appearance
        host.traitOverrides.userInterfaceStyle = appearance
        host.traitOverrides.preferredContentSizeCategory =
            large ? .accessibilityExtraLarge : .large
        if increaseContrast {
            host.traitOverrides.accessibilityContrast = .high
        }
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.backgroundColor = .systemBackground
        let scene =
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else { fatalError("trajectory snapshots need a window scene") }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.overrideUserInterfaceStyle = appearance
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            host.view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }
    }

    private func variantName(large: Bool, reduceTransparency: Bool, increaseContrast: Bool) -> String {
        if reduceTransparency { return "reduce-transparency" }
        if increaseContrast { return "increase-contrast" }
        return large ? "large" : "default"
    }

    private func snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {
        let appearanceName = appearance == .dark ? "dark" : "light"
        let languageName = language == "en" ? "en" : "zh"
        return "Snapshot_\(scene)_\(appearanceName)_\(languageName)"
    }
}
