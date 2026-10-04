import DLCore
import DLModels
import DLSecurity
import DLUI
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

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
            ConversationPage(model: model(kind, language: language), staticSnapshot: true, pinsToTail: pinsToTail)
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
            .environment(\.dynamicTypeSize, large ? DynamicTypeSize.accessibility3 : DynamicTypeSize.large)
            .tint(DLColor.accent)
            .transaction { $0.disablesAnimations = true }
        let traits = UITraitCollection(traitsFrom: [
            UITraitCollection(userInterfaceStyle: appearance),
            UITraitCollection(userInterfaceIdiom: .phone),
            UITraitCollection(
                preferredContentSizeCategory: large
                    ? UIContentSizeCategory.accessibilityExtraLarge : UIContentSizeCategory.large),
        ])
        assertSnapshot(
            of: content, as: .image(layout: .fixed(width: 402, height: 874), traits: traits),
            named: large ? "large" : "default",
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {
        "Snapshot_\(scene)_\(appearance == .dark ? "dark" : "light")_\(language == "en" ? "en" : "zh")"
    }
}

private enum ChatFixture: String {
    case running
    case tail
    case process
    case unconfirmed
    case image
}

private struct IdleConversationService: ConversationServing {}
