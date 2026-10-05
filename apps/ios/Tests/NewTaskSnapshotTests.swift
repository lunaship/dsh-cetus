import DLModels
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor final class NewTaskSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testDraft() { matrix("3_1_draft") { language in page(sheet: nil, language: language) } }
    func testWorkspace() {
        matrix("3_2_workspace") { language in page(sheet: .workspaces, query: "/tmp/notes", language: language) }
    }
    func testAdd() { matrix("3_3_add_ws") { language in page(sheet: .add, pending: true, language: language) } }
    func testPreset() { matrix("3_4_preset") { language in page(sheet: .presets, language: language) } }

    func testDraftReduceTransparency() {
        accessibility("3_1_draft", reduceTransparency: true) { page(sheet: nil, language: "zh-Hans") }
    }

    func testWorkspaceReduceTransparency() {
        accessibility("3_2_workspace", reduceTransparency: true) {
            page(sheet: .workspaces, query: "/tmp/notes", language: "zh-Hans")
        }
    }

    func testAddReduceTransparency() {
        accessibility("3_3_add_ws", reduceTransparency: true) {
            page(sheet: .add, pending: true, language: "zh-Hans")
        }
    }

    func testPresetReduceTransparency() {
        accessibility("3_4_preset", reduceTransparency: true) { page(sheet: .presets, language: "zh-Hans") }
    }

    func testDraftIncreaseContrast() {
        accessibility("3_1_draft", increaseContrast: true) { page(sheet: nil, language: "zh-Hans") }
    }

    func testWorkspaceIncreaseContrast() {
        accessibility("3_2_workspace", increaseContrast: true) {
            page(sheet: .workspaces, query: "/tmp/notes", language: "zh-Hans")
        }
    }

    func testAddIncreaseContrast() {
        accessibility("3_3_add_ws", increaseContrast: true) {
            page(sheet: .add, pending: true, language: "zh-Hans")
        }
    }

    func testPresetIncreaseContrast() {
        accessibility("3_4_preset", increaseContrast: true) { page(sheet: .presets, language: "zh-Hans") }
    }

    private func page(
        sheet: NewTaskSheet?,
        query: String = "",
        pending: Bool = false,
        language: String
    ) -> some View {
        let english = language.hasPrefix("en")
        return NavigationStack {
            NewTaskPage(
                hostID: "snapshot",
                starter: english ? "Shorten the release notes" : "把发布说明写短一点",
                workspaces: [WorkspaceInfo(workspaceId: "app", path: "/src/app", title: "app")],
                presets: [
                    AgentPreset(
                        id: "coder",
                        name: english ? "Coding" : "编码",
                        description: english ? "Edit code" : "改代码",
                        isDefault: true)
                ],
                staticSnapshot: true,
                snapshotSheet: sheet,
                snapshotQuery: query,
                snapshotPending: pending)
        }
    }

    private func matrix<V: View>(_ scene: String, make: (String) -> V) {
        for language in ["zh-Hans", "en"] {
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                for large in [false, true] {
                    render(
                        scene, appearance: appearance, language: language, large: large,
                        reduceTransparency: false, increaseContrast: false, make: { make(language) })
                }
            }
        }
    }

    private func accessibility<V: View>(
        _ scene: String,
        reduceTransparency: Bool = false,
        increaseContrast: Bool = false,
        make: () -> V
    ) {
        render(
            scene, appearance: .light, language: "zh-Hans", large: false,
            reduceTransparency: reduceTransparency, increaseContrast: increaseContrast, make: make)
    }

    private func render<V: View>(
        _ scene: String,
        appearance: UIUserInterfaceStyle,
        language: String,
        large: Bool,
        reduceTransparency: Bool,
        increaseContrast: Bool,
        make: () -> V
    ) {
        let image = renderImage(
            make(), appearance: appearance, large: large, reduceTransparency: reduceTransparency,
            increaseContrast: increaseContrast, language: language)
        assertSnapshot(
            of: image, as: .image,
            named: variantName(
                large: large, reduceTransparency: reduceTransparency, increaseContrast: increaseContrast),
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func renderImage<V: View>(
        _ view: V,
        appearance: UIUserInterfaceStyle,
        large: Bool,
        reduceTransparency: Bool,
        increaseContrast: Bool,
        language: String
    ) -> UIImage {
        let content =
            view
            .environment(\.locale, Locale(identifier: language))
            .environment(\.colorScheme, appearance == .dark ? .dark : .light)
            .environment(\.dynamicTypeSize, large ? DynamicTypeSize.accessibility3 : DynamicTypeSize.large)
            .environment(\._accessibilityReduceTransparency, reduceTransparency)
            .transaction { $0.disablesAnimations = true }
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: content)
        host.overrideUserInterfaceStyle = appearance
        host.traitOverrides.userInterfaceStyle = appearance
        host.traitOverrides.preferredContentSizeCategory =
            large ? .accessibilityExtraLarge : .large
        if increaseContrast {
            host.traitOverrides.accessibilityContrast = .high
        }
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.backgroundColor = .systemBackground
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else { fatalError("new task snapshots need a window scene") }
        let previousKey = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = appearance
        window.frame = CGRect(origin: .zero, size: size)
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

    private func variantName(large: Bool, reduceTransparency: Bool, increaseContrast: Bool) -> String {
        if reduceTransparency { return "reduce-transparency" }
        if increaseContrast { return "increase-contrast" }
        return large ? "large" : "default"
    }

    private func snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {
        "Snapshot_\(scene)_\(appearance == .dark ? "dark" : "light")_\(language == "en" ? "en" : "zh")"
    }
}
