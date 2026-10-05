import DLCore
import DLModels
import DLUI
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor final class ComposerSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testApproval() {
        matrix("4_3_approval") { language in
            page(language: language, decision: .approval(approval))
        }
    }

    func testQuestion() {
        oneScene("4_4_question") {
            page(language: "zh-Hans", decision: .question(question))
        }
    }

    private func page(language: String, decision: PhoneDecision) -> some View {
        NavigationStack {
            ConversationPage(
                model: StatusSlotScenes.model(.empty, locale: Locale(identifier: language)),
                staticSnapshot: true,
                showsStatusSlot: false,
                showsComposer: true,
                decisionPreview: decision)
        }
    }

    private var approval: RequestMessage {
        RequestMessage(
            id: "a", role: "approval", text: "Run the tests", toolName: "bash",
            toolArgs: #"{"command":"npm test"}"#, approvalId: "ap-1", takenOverByPhone: true,
            requestStatus: .pending)
    }

    private var question: RequestMessage {
        RequestMessage(
            id: "q", role: "question", text: "Which file should change?", questionRpcId: "rpc-1",
            requestStatus: .pending)
    }

    private func matrix<V: View>(_ scene: String, make: (String) -> V) {
        for language in ["zh-Hans", "en"] {
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                for large in [false, true] {
                    render(scene, appearance: appearance, language: language, large: large) { make(language) }
                }
            }
        }
    }

    private func oneScene<V: View>(_ scene: String, make: () -> V) {
        render(scene, appearance: .light, language: "zh-Hans", large: false, make: make)
    }

    private func render<V: View>(
        _ scene: String, appearance: UIUserInterfaceStyle, language: String, large: Bool, make: () -> V
    ) {
        let content = make()
            .environment(\.locale, Locale(identifier: language))
            .environment(\.colorScheme, appearance == .dark ? .dark : .light)
            .environment(\.dynamicTypeSize, large ? .accessibility3 : .large)
            .transaction { $0.disablesAnimations = true }
        let image = chatImage(content, appearance: appearance, large: large)
        assertSnapshot(
            of: image, as: .image, named: large ? "large" : "default",
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func chatImage<V: View>(_ view: V, appearance: UIUserInterfaceStyle, large: Bool) -> UIImage {
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: view)
        host.view.backgroundColor = .systemBackground
        host.overrideUserInterfaceStyle = appearance
        host.traitOverrides.userInterfaceStyle = appearance
        host.traitOverrides.preferredContentSizeCategory = large ? .accessibilityExtraLarge : .large
        host.traitOverrides.userInterfaceIdiom = .phone
        host.safeAreaRegions = []
        host.view.frame = CGRect(origin: .zero, size: size)
        let scene =
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first {
                $0.activationState == .foregroundActive
            } ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else { fatalError("chat snapshots need a window scene") }
        let previousKey = scene.windows.first { $0.isKeyWindow }
        let window = ComposerSnapshotWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.overrideUserInterfaceStyle = appearance
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        CATransaction.flush()
        (messageStream(in: host) ?? messageStream(in: host.view))?.layoutForStaticSnapshot(canvas: size)
        CATransaction.flush()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            window.windowScene = nil
            previousKey?.makeKey()
        }
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

    private func snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {
        "Snapshot_\(scene)_\(appearance == .dark ? "dark" : "light")_\(language == "en" ? "en" : "zh")"
    }
}

private final class ComposerSnapshotWindow: UIWindow {
    override var safeAreaInsets: UIEdgeInsets { .zero }
}
