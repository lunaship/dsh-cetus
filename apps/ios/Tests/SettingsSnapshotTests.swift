import DLCore
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import Cetus

@MainActor final class SettingsSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testHome() { matrix("7_1_settings") { language in home(language) } }
    func testComputer() { matrix("7_2_computer") { language in detail(.computer, language: language) } }
    func testDiagnostics() { matrix("7_3_diagnostics") { language in detail(.diagnostics, language: language) } }
    func testNotifications() { matrix("7_4_notifications") { language in detail(.notifications, language: language) } }
    func testAppearance() { matrix("7_5_appearance") { language in detail(.appearance, language: language) } }
    func testAbout() { matrix("7_13_about") { language in detail(.about, language: language) } }
    func testCrash() { matrix("7_15_crash") { language in detail(.crash, language: language) } }

    private func detail(_ page: SettingsPage, language: String) -> some View {
        NavigationStack {
            SettingsDetailPage(
                page: page,
                // 「关于」页会渲染真实构建元数据（commit/date/configuration）。
                // 基线必须用固定值，否则每次提交、每天都会漂移，基线永远追不上。
                buildInfo: Self.fixedBuildInfo)
        }
        .environment(\.locale, Locale(identifier: language))
        .transaction { $0.disablesAnimations = true }
    }

    /// 固定的构建元数据，只为截图稳定；不代表任何真实构建。
    private static let fixedBuildInfo = BuildInfo(
        commit: "f85155b6",
        date: "2026-10-08",
        configuration: "Debug",
        contractVersion: "1",
        marketingVersion: "1.0",
        buildNumber: "1")

    private func home(_ language: String) -> some View {
        NavigationStack {
            SettingsHomePage(computerName: "MacBook Pro", computerAddress: "192.0.2.10:18640", online: true)
        }
        .environment(\.locale, Locale(identifier: language))
        .transaction { $0.disablesAnimations = true }
    }

    private func matrix<V: View>(_ scene: String, make: (String) -> V) {
        for language in ["zh-Hans", "en"] {
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                for large in [false, true] {
                    shot(scene, appearance: appearance, language: language, large: large, make: { make(language) })
                }
            }
        }
        shot(
            scene, appearance: .light, language: "zh-Hans", large: false, accessibility: .reduceTransparency,
            make: { make("zh-Hans") })
        shot(
            scene, appearance: .light, language: "zh-Hans", large: false, accessibility: .increaseContrast,
            make: { make("zh-Hans") })
    }

    private func shot<V: View>(
        _ scene: String, appearance: UIUserInterfaceStyle, language: String, large: Bool,
        accessibility: SettingsSnapshotAccessibility = .standard, make: () -> V
    ) {
        let image = render(
            make(), appearance: appearance, language: language, large: large, accessibility: accessibility)
        assertSnapshot(
            of: image, as: .image(precision: 0.995), named: snapshotNamed(large: large, accessibility: accessibility),
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func render<V: View>(
        _ view: V, appearance: UIUserInterfaceStyle, language: String, large: Bool,
        accessibility: SettingsSnapshotAccessibility
    ) -> UIImage {
        let styled =
            view
            .environment(\.colorScheme, appearance == .dark ? .dark : .light)
            .environment(
                \.dynamicTypeSize,
                large ? DynamicTypeSize.accessibility3 : DynamicTypeSize.large
            )
            .environment(
                \._accessibilityReduceTransparency, accessibility == .reduceTransparency)
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: styled)
        host.overrideUserInterfaceStyle = appearance
        host.traitOverrides.userInterfaceStyle = appearance
        host.traitOverrides.preferredContentSizeCategory =
            large ? .accessibilityExtraLarge : .large
        if accessibility == .increaseContrast {
            host.traitOverrides.accessibilityContrast = .high
        }
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.backgroundColor = .systemBackground
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else { fatalError("settings snapshots need a window scene") }
        let previousKey = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.overrideUserInterfaceStyle = appearance
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
        host.view.layoutIfNeeded()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            window.windowScene = nil
            previousKey?.makeKey()
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            host.view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }
    }

    private func snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {
        "Snapshot_\(scene)_\(appearance == .dark ? "dark" : "light")_\(language == "en" ? "en" : "zh")"
    }

    private func snapshotNamed(large: Bool, accessibility: SettingsSnapshotAccessibility) -> String {
        switch accessibility {
        case .standard: large ? "large" : "default"
        case .reduceTransparency: "reduce-transparency"
        case .increaseContrast: "increase-contrast"
        }
    }
}

private enum SettingsSnapshotAccessibility {
    case standard
    case reduceTransparency
    case increaseContrast
}
