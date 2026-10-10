import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import Cetus

@MainActor final class ReviewSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testChanges() { matrix("6_1_changes") { page(.changes, language: $0) } }
    func testDiff() { matrix("6_2_diff") { page(.diff, language: $0) } }
    func testFiles() { matrix("6_3_files") { page(.files, language: $0) } }
    func testFile() { matrix("6_4_file") { page(.file, language: $0) } }
    func testPreview() { matrix("6_5_preview") { page(.preview, language: $0) } }
    func testPreviewEmpty() { matrix("6_6_preview_empty") { page(.previewEmpty, language: $0) } }

    private func page(_ surface: ReviewSurface, language: String) -> some View {
        NavigationStack { ReviewScreen(surface: surface) }
            .environment(\.locale, Locale(identifier: language))
            .transaction { $0.disablesAnimations = true }
    }

    private func matrix<V: View>(_ scene: String, make: (String) -> V) {
        for language in ["zh-Hans", "en"] {
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                for large in [false, true] {
                    shot(
                        scene, appearance: appearance, language: language, large: large,
                        named: large ? "large" : "default"
                    ) { make(language) }
                }
            }
        }
        shot(
            scene, appearance: .light, language: "zh-Hans", large: false, reduceTransparency: true,
            named: "reduce-transparency"
        ) { make("zh-Hans") }
        shot(
            scene, appearance: .light, language: "zh-Hans", large: false, increaseContrast: true,
            named: "increase-contrast"
        ) { make("zh-Hans") }
    }

    private func shot<V: View>(
        _ scene: String, appearance: UIUserInterfaceStyle, language: String, large: Bool,
        reduceTransparency: Bool = false, increaseContrast: Bool = false, named: String, make: () -> V
    ) {
        let scheme: ColorScheme = appearance == .dark ? .dark : .light
        let content = make()
            .environment(\.colorScheme, scheme)
            .environment(
                \.dynamicTypeSize, large ? DynamicTypeSize.accessibility3 : DynamicTypeSize.large
            )
            .environment(\._accessibilityReduceTransparency, reduceTransparency)
        let image = render(content, appearance: appearance, large: large, increaseContrast: increaseContrast)
        // 玻璃导航栏每次有约 5 万个像素差 1–2 个色阶，字节精度会低于 0.995。
        // 感知精度 0.99 放过这种色差；像素精度仍要求 0.995，缺一行内容会失败。
        assertSnapshot(
            of: image, as: .image(precision: 0.995, perceptualPrecision: 0.99), named: named,
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func render<V: View>(
        _ view: V, appearance: UIUserInterfaceStyle, large: Bool, increaseContrast: Bool
    ) -> UIImage {
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: view)
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.backgroundColor = .systemBackground
        var parts = [
            UITraitCollection(userInterfaceStyle: appearance),
            UITraitCollection(preferredContentSizeCategory: large ? .accessibilityExtraLarge : .large),
        ]
        if increaseContrast {
            parts.append(UITraitCollection(accessibilityContrast: .high))
        }
        let traits = UITraitCollection(traitsFrom: parts)
        apply(traits, to: host, increaseContrast: increaseContrast)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else { fatalError("review snapshots need a window scene") }
        let previousKey = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        apply(traits, to: window, increaseContrast: increaseContrast)
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
        host.view.layoutIfNeeded()
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        format.opaque = true
        let image = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            host.view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }
        window.isHidden = true
        window.rootViewController = nil
        window.windowScene = nil
        previousKey?.makeKey()
        return image
    }

    private func apply(
        _ traits: UITraitCollection, to object: UITraitEnvironment & NSObject, increaseContrast: Bool
    ) {
        if let host = object as? UIViewController {
            host.overrideUserInterfaceStyle = traits.userInterfaceStyle
            host.traitOverrides.userInterfaceStyle = traits.userInterfaceStyle
            host.traitOverrides.preferredContentSizeCategory = traits.preferredContentSizeCategory
            if increaseContrast { host.traitOverrides.accessibilityContrast = .high }
        } else if let window = object as? UIWindow {
            window.overrideUserInterfaceStyle = traits.userInterfaceStyle
            window.traitOverrides.userInterfaceStyle = traits.userInterfaceStyle
            window.traitOverrides.preferredContentSizeCategory = traits.preferredContentSizeCategory
            if increaseContrast { window.traitOverrides.accessibilityContrast = .high }
        }
    }

    private func snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {
        "Snapshot_\(scene)_\(appearance == .dark ? "dark" : "light")_\(language == "en" ? "en" : "zh")"
    }
}
