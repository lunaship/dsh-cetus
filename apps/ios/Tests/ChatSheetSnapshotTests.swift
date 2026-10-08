import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import Cetus

@MainActor final class ChatSheetSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testPalette() { matrix("5_1_palette") { screen(.palette, language: $0) } }
    func testModel() { matrix("5_2_model") { screen(.model, language: $0) } }
    func testPermission() { matrix("5_3_permission") { screen(.permission, language: $0) } }
    func testFullAccess() { matrix("5_4_full_access") { screen(.confirmFull, language: $0) } }
    func testAttach() { matrix("5_5_attach") { screen(.attach, language: $0) } }
    func testUsage() { matrix("5_7_usage") { screen(.usage, language: $0) } }
    func testAgents() { matrix("5_8_agents") { screen(.agents, language: $0) } }
    func testShare() { matrix("5_9_share") { screen(.share, language: $0) } }
    func testRename() { matrix("5_10_rename") { screen(.rename, language: $0) } }
    func testDelete() { matrix("5_11_delete") { screen(.delete, language: $0) } }
    func testSchedule() { matrix("5_12_schedule") { screen(.schedule, language: $0) } }
    func testGoal() { matrix("5_13_goal") { screen(.goal, language: $0) } }
    func testSelectText() { matrix("5_14_select") { screen(.selectText, language: $0) } }

    private func screen(_ surface: ChatSurface, language: String) -> some View {
        NavigationStack { ChatSurfaceScreen(surface: surface) }
            .environment(\.locale, Locale(identifier: language))
            .transaction { $0.disablesAnimations = true }
    }

    private func matrix<V: View>(_ scene: String, make: (String) -> V) {
        for language in ["zh-Hans", "en"] {
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                for large in [false, true] {
                    shot(scene, appearance: appearance, language: language, large: large) { make(language) }
                }
            }
        }
        shot(scene, appearance: .light, language: "zh-Hans", large: false, reduceTransparency: true) {
            make("zh-Hans")
        }
        shot(scene, appearance: .light, language: "zh-Hans", large: false, increaseContrast: true) {
            make("zh-Hans")
        }
    }

    private func shot<V: View>(
        _ scene: String, appearance: UIUserInterfaceStyle, language: String, large: Bool,
        reduceTransparency: Bool = false, increaseContrast: Bool = false, make: () -> V
    ) {
        let content = make()
            .environment(\.colorScheme, appearance == .dark ? .dark : .light)
            .environment(\.dynamicTypeSize, large ? .accessibility3 : .large)
            .environment(\._accessibilityReduceTransparency, reduceTransparency)
            .transaction { $0.disablesAnimations = true }
        let image = render(
            content, appearance: appearance, large: large, increaseContrast: increaseContrast)
        let name = variantName(
            large: large, reduceTransparency: reduceTransparency, increaseContrast: increaseContrast)
        // 与其他截图套件（SettingsSnapshotTests / ReviewSnapshotTests / WideSnapshotTests）
        // 用同一档容差。此前这里是裸 `.image`（**逐像素全等**），在
        // `accessibility3` 大字号下渲染不稳定：CI 自己重生成的基线，下次 CI 又判定不匹配，
        // 于是「重生成 → 仍失败」反复循环。容差与其它套件对齐后可复现。
        assertSnapshot(
            of: image, as: .image(precision: 0.995, perceptualPrecision: 0.99), named: name,
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func render<V: View>(
        _ view: V, appearance: UIUserInterfaceStyle, large: Bool, increaseContrast: Bool
    ) -> UIImage {
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: view)
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.backgroundColor = .systemBackground
        host.overrideUserInterfaceStyle = appearance
        host.traitOverrides.userInterfaceStyle = appearance
        host.traitOverrides.preferredContentSizeCategory = large ? .accessibilityExtraLarge : .large
        if increaseContrast {
            host.traitOverrides.accessibilityContrast = .high
        }
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else { fatalError("sheet snapshots need a window scene") }
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

    private func variantName(large: Bool, reduceTransparency: Bool, increaseContrast: Bool) -> String {
        if reduceTransparency { return "reduce-transparency" }
        if increaseContrast { return "increase-contrast" }
        return large ? "large" : "default"
    }
}
