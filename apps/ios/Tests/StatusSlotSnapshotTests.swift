import DLCore
import DLModels
import DLSecurity
import DLUI
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import Cetus

@MainActor final class StatusSlotSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testCollapsed() { matrix("4_5_status_collapsed") { page(.collapsed, language: $0) } }
    func testExpanded() { matrix("4_5_status_expanded") { page(.expanded, language: $0) } }
    func testDisconnected() { matrix("4_8_status_disconnected") { page(.disconnected, language: $0) } }
    func testPending() { matrix("4_5_status_pending") { page(.pending, language: $0) } }
    func testPreview() { matrix("4_5_status_preview") { page(.preview, language: $0) } }
    func testOtherStates() {
        for scene in [StatusSlotScene.connecting, .failed, .planOnly, .noPlan, .completed, .empty] {
            oneScene(scene.snapshotScene) { page(scene, language: "zh-Hans") }
        }
    }

    private func page(_ scene: StatusSlotScene, language: String) -> some View {
        NavigationStack {
            ConversationPage(
                model: StatusSlotScenes.model(scene, locale: Locale(identifier: language)),
                staticSnapshot: true, statusExpanded: [.expanded, .planOnly, .noPlan, .completed].contains(scene))
        }
    }

    private func matrix<V: View>(_ scene: String, make: (String) -> V) {
        for language in ["zh-Hans", "en"] {
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                for large in [false, true] {
                    render(scene, appearance: appearance, language: language, large: large, make: { make(language) })
                }
            }
        }
        renderAccessibility(scene) { make("zh-Hans") }
    }

    private func oneScene<V: View>(_ scene: String, make: () -> V) {
        render(scene, appearance: .light, language: "zh-Hans", large: false, make: make)
        renderAccessibility(scene, make: make)
    }

    private func renderAccessibility<V: View>(_ scene: String, make: () -> V) {
        render(
            scene, appearance: .light, language: "zh-Hans", large: false, reduceTransparency: true, make: make)
        render(scene, appearance: .light, language: "zh-Hans", large: false, increaseContrast: true, make: make)
    }

    private func render<V: View>(
        _ scene: String, appearance: UIUserInterfaceStyle, language: String, large: Bool,
        reduceTransparency: Bool = false, increaseContrast: Bool = false, make: () -> V
    ) {
        let content = make()
            .environment(\.locale, Locale(identifier: language))
            .environment(\.colorScheme, appearance == .dark ? .dark : .light)
            .environment(\.dynamicTypeSize, large ? DynamicTypeSize.accessibility3 : DynamicTypeSize.large)
            .environment(\._accessibilityReduceTransparency, reduceTransparency)
            .tint(DLColor.accent)
            .transaction { $0.disablesAnimations = true }
        let image = chatImage(
            content, appearance: appearance, large: large, increaseContrast: increaseContrast)
        assertSnapshot(
            of: image, as: .image,
            named: variantName(
                large: large, reduceTransparency: reduceTransparency, increaseContrast: increaseContrast),
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func variantName(large: Bool, reduceTransparency: Bool, increaseContrast: Bool) -> String {
        if reduceTransparency { return "reduce-transparency" }
        if increaseContrast { return "increase-contrast" }
        return large ? "large" : "default"
    }

    /// The library's SwiftUI image strategy calls `layer.render` before the collection view has a
    /// canvas, so hosting cells and the light-mode bar never paint. Lay the stream out at the
    /// snapshot size, then draw the hierarchy.
    private func chatImage<V: View>(
        _ view: V, appearance: UIUserInterfaceStyle, large: Bool, increaseContrast: Bool = false
    ) -> UIImage {
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: view)
        host.view.backgroundColor = .systemBackground
        host.overrideUserInterfaceStyle = appearance
        host.traitOverrides.userInterfaceStyle = appearance
        host.traitOverrides.preferredContentSizeCategory =
            large ? .accessibilityExtraLarge : .large
        if increaseContrast {
            host.traitOverrides.accessibilityContrast = .high
        }
        host.traitOverrides.userInterfaceIdiom = .phone
        host.safeAreaRegions = []
        host.view.frame = CGRect(origin: .zero, size: size)

        let scene =
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first {
                $0.activationState == .foregroundActive
            } ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else {
            fatalError("chat snapshots need a window scene")
        }
        let previousKey = scene.windows.first { $0.isKeyWindow }
        let window = StatusSlotSnapshotWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.overrideUserInterfaceStyle = appearance
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
        host.view.frame = CGRect(origin: .zero, size: size)
        defer {
            window.isHidden = true
            window.rootViewController = nil
            window.windowScene = nil
            previousKey?.makeKey()
        }

        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        styleNavigationBars(in: host.view, appearance: appearance)
        CATransaction.flush()
        (messageStream(in: host) ?? messageStream(in: host.view))?.layoutForStaticSnapshot(canvas: size)
        CATransaction.flush()

        let format = UIGraphicsImageRendererFormat()
        format.scale = window.screen.scale > 0 ? window.screen.scale : 3
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            host.view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }
    }

    private func messageStream(in controller: UIViewController) -> MessageStreamController? {
        if let stream = controller as? MessageStreamController { return stream }
        for child in controller.children {
            if let stream = messageStream(in: child) { return stream }
        }
        return nil
    }

    private func messageStream(in view: UIView) -> MessageStreamController? {
        if let collection = view as? UICollectionView {
            var responder: UIResponder? = collection
            while let current = responder {
                if let stream = current as? MessageStreamController { return stream }
                responder = current.next
            }
        }
        for subview in view.subviews {
            if let stream = messageStream(in: subview) { return stream }
        }
        return nil
    }

    private func styleNavigationBars(in view: UIView, appearance: UIUserInterfaceStyle) {
        if let bar = view as? UINavigationBar {
            bar.overrideUserInterfaceStyle = appearance
            bar.setNeedsLayout()
            bar.layoutIfNeeded()
        }
        for subview in view.subviews {
            styleNavigationBars(in: subview, appearance: appearance)
        }
    }

    private func snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {
        "Snapshot_\(scene)_\(appearance == .dark ? "dark" : "light")_\(language == "en" ? "en" : "zh")"
    }
}

private final class StatusSlotSnapshotWindow: UIWindow {
    override var safeAreaInsets: UIEdgeInsets { .zero }
}
