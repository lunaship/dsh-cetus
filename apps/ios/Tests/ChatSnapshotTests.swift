import DLCore
import DLModels
import DLSecurity
import DLUI
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import Cetus

@MainActor final class ChatSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testRunning() { matrix("4_1_running") { page(.running, language: $0, pinsToTail: false) } }
    func testTail() { matrix("4_2_tail") { page(.tail, language: $0, pinsToTail: true) } }
    func testProcess() { matrix("4_6_process") { page(.process, language: $0, pinsToTail: false) } }
    func testUnconfirmed() {
        oneScene("4_1_unconfirmed") { page(.unconfirmed, language: "zh-Hans", pinsToTail: false) }
    }
    func testImage() { oneScene("4_1_image") { page(.image, language: "zh-Hans", pinsToTail: false) } }

    private func page(_ kind: ChatFixture, language: String, pinsToTail: Bool) -> some View {
        NavigationStack {
            ConversationPage(
                model: model(kind, language: language), staticSnapshot: true, pinsToTail: pinsToTail,
                showsStatusSlot: false,
                // 设计稿 4.1：运行中截图带上输入区（模型 / 权限胶囊与圆形发送按钮）。
                showsComposer: kind == .running ? true : nil)
        }
    }

    private func model(_ kind: ChatFixture, language: String) -> ConversationModel {
        let english = language == "en"
        let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(
            "deeplinks-chat-snapshots", isDirectory: true)
        return ConversationModel(
            hostID: "snapshot-host",
            sessionID: "snapshot-\(kind)",
            seed: ConversationSeed(
                title: english ? "Release notes" : "发布说明",
                workspace: "/src/app",
                running: kind == .running || kind == .process,
                step: 3,
                added: 12,
                deleted: 3),
            service: IdleConversationService(),
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory),
            prepared: PreparedTranscript(
                messages: messages(kind, english: english),
                stats: kind == .tail ? tailStats() : nil,
                running: kind == .running || kind == .process,
                expanded: kind == .process ? ["think-process"] : [],
                confirmingSnapshot: kind == .unconfirmed),
            autostart: false)
    }

    private func messages(_ kind: ChatFixture, english: Bool) -> [HistoryMessage] {
        switch kind {
        case .running:
            return [
                user(english ? "Shorten the release notes." : "把发布说明写短一点", step: 1),
                HistoryMessage(
                    id: "think", role: "reasoning", kind: .role("reasoning"),
                    text: english ? "Look at the current notes." : "先看现有说明", durationMs: 2000),
                HistoryMessage(
                    id: "read", role: "tool_call", kind: .role("tool_call"), callId: "read-1", name: "read",
                    args: #"{"path":"README.md"}"#, step: 2),
                HistoryMessage(
                    id: "read-out", role: "tool_result", kind: .role("tool_result"), text: "ok", callId: "read-1",
                    durationMs: 400),
                HistoryMessage(
                    id: "assistant", role: "assistant", kind: .role("assistant"),
                    text: english ? "I'll start with the current notes." : "我先看一下现有说明。"),
                HistoryMessage(
                    id: "bash", role: "tool_call", kind: .role("tool_call"), callId: "bash-1", name: "bash",
                    args: #"{"command":"git diff"}"#, step: 3, running: true),
            ]
        case .tail:
            return [
                user(english ? "Shorten the release notes." : "把发布说明写短一点"),
                HistoryMessage(
                    id: "think", role: "reasoning", kind: .role("reasoning"), text: english ? "Draft." : "起草",
                    durationMs: 2000),
                HistoryMessage(
                    id: "assistant", role: "assistant", kind: .role("assistant"), text: tailMarkdown(english: english)),
                HistoryMessage(
                    id: "changes", seq: 9, role: "workspace_changes", kind: .role("workspace_changes"),
                    changes: ChangesSummary(
                        turn: 1, total: 2, added: 12, deleted: 3,
                        files: [
                            ChangedFile(path: "Notes.md", display: "Notes.md", added: 8, deleted: 2),
                            ChangedFile(path: "README.md", display: "README.md", added: 4, deleted: 1),
                        ])),
            ]
        case .process:
            return [
                user(english ? "Check the notes." : "看一下说明"),
                HistoryMessage(
                    id: "think", role: "reasoning", kind: .role("reasoning"),
                    text: english ? "Read the file first." : "先读文件", durationMs: 1000),
                HistoryMessage(
                    id: "read", role: "tool_call", kind: .role("tool_call"), callId: "read-1", name: "read",
                    args: #"{"path":"Notes.md"}"#),
                HistoryMessage(
                    id: "read-out", role: "tool_result", kind: .role("tool_result"), text: "error: missing heading",
                    callId: "read-1"),
                HistoryMessage(
                    id: "assistant", role: "assistant", kind: .role("assistant"),
                    text: english ? "The heading is missing." : "标题不见了。"),
            ]
        case .unconfirmed:
            return [
                user("继续"),
                HistoryMessage(
                    id: "ap", role: "approval", kind: .role("approval"), text: "bash deploy", requestStatus: .pending),
            ]
        case .image:
            return [
                user("看这张图"),
                HistoryMessage(
                    id: "assistant", role: "assistant", kind: .role("assistant"),
                    text: "示意图\n\n![架构](https://cdn.example.com/arch.png)"),
            ]
        }
    }

    private func user(_ text: String, step: Int? = nil) -> HistoryMessage {
        HistoryMessage(id: "user", role: "user", kind: .user, text: text, step: step)
    }

    private func tailMarkdown(english: Bool) -> String {
        if english {
            return "## Notes\n\n- Shorter title\n- Drop the repeat\n\n```swift\nnpm test\n```"
        }
        return "## 发布说明\n\n- 缩短标题\n- 去掉重复\n\n```swift\nnpm test\n```"
    }

    private func tailStats() -> HistoryStats {
        HistoryStats(tokenUsage: TokenUsage(uncachedInputTokens: 1200, outputTokens: 300, model: "demo"))
    }

    private func matrix<V: View>(_ scene: String, make: (String) -> V) {
        for language in ["zh-Hans", "en"] {
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                for large in [false, true] {
                    render(scene, appearance: appearance, language: language, large: large, make: { make(language) })
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
            .environment(\.dynamicTypeSize, large ? DynamicTypeSize.accessibility3 : DynamicTypeSize.large)
            .environment(\._accessibilityReduceTransparency, reduceTransparency)
            .tint(DLColor.accent)
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
        let window = ChatSnapshotWindow(windowScene: scene)
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

private final class ChatSnapshotWindow: UIWindow {
    override var safeAreaInsets: UIEdgeInsets { .zero }
}

private enum ChatFixture: String {
    case running
    case tail
    case process
    case unconfirmed
    case image
}

private struct IdleConversationService: ConversationServing {}
