import DLCore
import DLModels
import DLUI
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import Cetus

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
            // 设计稿 4.4：两道题，第一道可选、带三个选项，截图里要看到选项列表与「跳过 / 下一题」。
            questionPayloadJson: #"""
                [{"id":"q1","question":"每台设备每分钟上限设成多少？","optional":true,
                "options":[{"label":"60 次"},{"label":"120 次"},{"label":"不限"}]},
                {"id":"q2","question":"超出上限时怎么提示？"}]
                """#,
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
        accessibility(scene) { make("zh-Hans") }
    }

    private func oneScene<V: View>(_ scene: String, make: () -> V) {
        render(scene, appearance: .light, language: "zh-Hans", large: false, make: make)
        accessibility(scene, make: make)
    }

    private func accessibility<V: View>(_ scene: String, make: () -> V) {
        render(
            scene, appearance: .light, language: "zh-Hans", large: false,
            reduceTransparency: true, named: "reduce-transparency", make: make)
        render(
            scene, appearance: .light, language: "zh-Hans", large: false,
            increaseContrast: true, named: "increase-contrast", make: make)
    }

    private func render<V: View>(
        _ scene: String,
        appearance: UIUserInterfaceStyle,
        language: String,
        large: Bool,
        reduceTransparency: Bool = false,
        increaseContrast: Bool = false,
        named: String? = nil,
        make: () -> V
    ) {
        let content = make()
            .environment(\.locale, Locale(identifier: language))
            .environment(\.colorScheme, appearance == .dark ? .dark : .light)
            .environment(\.dynamicTypeSize, large ? .accessibility3 : .large)
            .environment(\._accessibilityReduceTransparency, reduceTransparency)
            .transaction { $0.disablesAnimations = true }
        let image = chatImage(
            content, appearance: appearance, large: large, increaseContrast: increaseContrast)
        assertSnapshot(
            // 与其它截图套件同档容差。裸 `.image` 是**逐像素全等**，在大字号 /
            // 无障碍变体下渲染不稳定：CI 自己生成的基线，下次 CI 又判不匹配，
            // 于是「重生成 → 仍失败」反复循环（ChatSheetSnapshotTests 同一根因）。
            of: image, as: .image(precision: 0.995, perceptualPrecision: 0.99),
            named: named ?? (large ? "large" : "default"),
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func chatImage<V: View>(
        _ view: V, appearance: UIUserInterfaceStyle, large: Bool, increaseContrast: Bool = false
    ) -> UIImage {
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: view)
        host.view.backgroundColor = .systemBackground
        host.overrideUserInterfaceStyle = appearance
        host.traitOverrides.userInterfaceStyle = appearance
        host.traitOverrides.preferredContentSizeCategory = large ? .accessibilityExtraLarge : .large
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
        // 固定广色域 16 位（与现有基线一致）。默认 .automatic 按当时的 UITraitCollection.current 选，
        // 同一张图两次运行可能一次 P3 16 位、一次 sRGB 8 位，饱和色区域（品牌色按钮）整块对不上。
        format.preferredRange = .extended
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
