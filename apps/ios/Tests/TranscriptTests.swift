import DLCore
import DLModels
import Foundation
import Testing

@Suite struct TranscriptTests {
    @Test func lazySplitKeepsFenceTogether() {
        let fence = "```swift\nlet a = 1\n\nlet b = 2\n```"
        let markdown = "开头说明，把围栏外面的空行当成切分点。\n\n\(fence)\n\n结尾。"
        let parts = splitMarkdownForLazyLayout(markdown, targetCharacters: 20)
        #expect(parts.count > 1)
        #expect(parts.contains { $0.contains("let a = 1\n\nlet b = 2") })
        #expect(!parts.contains { $0 == "let b = 2" })
    }

    @Test func imagesAllowOnlyHTTPS() {
        guard case .allowed(let url) = imageLoadDecision("https://cdn.example.com/a.png") else {
            Issue.record("https 应允许")
            return
        }
        #expect(url.scheme == "https")
        #expect(imageLoadDecision("http://cdn.example.com/a.png") == .blocked)
        #expect(imageLoadDecision("data:image/png;base64,aaaa") == .blocked)
        #expect(imageLoadDecision("javascript:alert(1)") == .blocked)
    }

    @Test func markdownBlocks() {
        let blocks = parseMarkdown(
            """
            ## 标题
            一段 `code` 和 $x$
            - 一
            - 二
            > 引用
            | A | B |
            | --- | --- |
            | 1 | 2 |
            ```swift
            let n = 1
            ```
            ```mermaid
            flowchart LR
            ```
            $$
            E = mc^2
            $$
            ![图](https://cdn.example.com/a.png)
            """)
        #expect(blocks.contains { if case .heading(2, _) = $0 { true } else { false } })
        #expect(blocks.contains { if case .list(false, let items) = $0 { items.count == 2 } else { false } })
        #expect(blocks.contains { if case .quote = $0 { true } else { false } })
        #expect(
            blocks.contains {
                if case .table(let header, let rows) = $0 {
                    header == ["A", "B"] && rows == [["1", "2"]]
                } else {
                    false
                }
            })
        #expect(blocks.contains { if case .code("swift", _) = $0 { true } else { false } })
        #expect(blocks.contains { if case .mermaid = $0 { true } else { false } })
        #expect(blocks.contains { if case .math = $0 { true } else { false } })
        #expect(
            blocks.contains { if case .image("图", "https://cdn.example.com/a.png") = $0 { true } else { false } })
        let paragraph = blocks.compactMap { block -> [InlineRun]? in
            if case .paragraph(let runs) = block { return runs }
            return nil
        }.first
        #expect(paragraph?.contains(.code("code")) == true)
        #expect(paragraph?.contains(.math("x")) == true)
    }

    @Test func highlightKeepsTextAndUsesSystemKinds() {
        let tokens = highlightCode("LET value = 12 // note", language: "swift")
        #expect(tokens.contains(CodeToken(kind: .keyword, text: "LET")))
        #expect(tokens.contains(CodeToken(kind: .number, text: "12")))
        #expect(tokens.contains { $0.kind == .comment && $0.text.contains("note") })
        #expect(highlightCode("print()", language: "swift").contains { $0.kind == .function && $0.text == "print" })
    }

    @Test func processGroupsAndRunningOmitsTail() {
        let rows = projectTranscript(
            messages: [
                HistoryMessage(id: "u", role: "user", kind: .user, text: "看一下"),
                HistoryMessage(
                    id: "c", role: "tool_call", kind: .role("tool_call"), callId: "1", name: "bash",
                    args: #"{"command":"ls"}"#),
                HistoryMessage(
                    id: "r", role: "tool_result", kind: .role("tool_result"), text: "ok", callId: "1", durationMs: 1500),
                HistoryMessage(id: "a", role: "assistant", kind: .role("assistant"), text: "看完了"),
            ], running: true, stats: nil, expandedProcessIDs: [], confirmingSnapshot: false)
        let processes = rows.compactMap { row -> ProcessBlock? in
            if case .process(let block) = row { return block }
            return nil
        }
        #expect(processes.count == 1)
        #expect(processes[0].summary.parts == [.commands(1)])
        #expect(!rows.contains { if case .tail = $0 { true } else { false } })
    }

    @Test func finishedTurnShowsTailAndCapsChangeRows() {
        let files = (0..<4).map { ChangedFile(path: "f\($0).txt", added: 1, deleted: 0) }
        let rows = projectTranscript(
            messages: [
                HistoryMessage(id: "u", role: "user", kind: .user, text: "改一下"),
                HistoryMessage(id: "a", role: "assistant", kind: .role("assistant"), text: "改好了"),
                HistoryMessage(
                    id: "ch", seq: 9, role: "workspace_changes", kind: .role("workspace_changes"),
                    changes: ChangesSummary(turn: 1, total: 4, added: 8, deleted: 1, files: files)),
            ],
            running: false,
            stats: HistoryStats(tokenUsage: TokenUsage(uncachedInputTokens: 10, outputTokens: 2, model: "demo")),
            expandedProcessIDs: [], confirmingSnapshot: false)
        guard case .tail(let tail) = rows.last else {
            Issue.record("轮尾应在最后")
            return
        }
        #expect(tail.lines.count == 3)
        #expect(tail.total == 4)
        #expect(tail.showsCard)
        #expect(tail.showsSuggestions)
        #expect(tail.meta.model == "demo")
        #expect(tail.meta.tokens == 12)
        #expect(tail.changesSeq == 9)
    }

    @Test func snapshotApprovalIsUnconfirmed() {
        let rows = projectTranscript(
            messages: [
                HistoryMessage(
                    id: "ap", role: "approval", kind: .role("approval"), text: "bash deploy",
                    requestStatus: .pending)
            ], running: false, stats: nil, expandedProcessIDs: [], confirmingSnapshot: true)
        #expect(rows == [.unconfirmed(id: "ap")])
        #expect(
            !isUnconfirmedApproval(HistoryMessage(role: "approval", kind: .role("approval"), requestStatus: .resolved)))
    }

    @Test func coalesceDeltasButNotAcrossBlockEnd() {
        let frames = [
            delta("He", seq: 1),
            delta("llo", seq: 2),
            blockEnd(seq: 3),
            delta("!", seq: 4),
        ]
        let merged = coalesceStreamDeltas(frames)
        #expect(merged.map(\.seq) == [2, 3, 4])
        #expect(chunkText(merged[0]) == "Hello")
        let messages = reduceTranscript([], frames: merged)
        #expect(messages.count == 1)
        #expect(messages[0].text == "Hello!")
        #expect(messages[0].running == true)
        let settled = reduceTranscript([], frames: [delta("He", seq: 1), blockEnd(seq: 2, text: "Hello")])
        #expect(settled[0].text == "Hello")
        #expect(settled[0].running == false)
        #expect(reduceRunning(false, frames: [delta("x", seq: 1)]) == true)
        #expect(reduceRunning(true, frames: [StreamFrame(seq: 2, type: "turn/end", time: 1, data: .null)]) == false)
    }

    @Test func freshAssistantsFadeOnGrowthOnly() {
        var seen: [String: String] = [:]
        let first = AssistantBlock(id: "a", markdown: "Hi", streaming: true, fade: false)
        let grown = AssistantBlock(id: "a", markdown: "Hi!", streaming: true, fade: false)
        #expect(fade(markFreshAssistants([.assistant(first)], seen: &seen)))
        #expect(fade(markFreshAssistants([.assistant(grown)], seen: &seen)))
        #expect(!fade(markFreshAssistants([.assistant(grown)], seen: &seen)))
        rememberAssistants([.assistant(first)], seen: &seen)
        let replaced = markFreshAssistants([.assistant(first)], seen: &seen)
        #expect(!fade(replaced))
    }

    @Test func replayedSequenceDoesNotDuplicate() {
        let existing = HistoryMessage(
            id: "msg-4", seq: 4, role: "user", kind: .user, text: "继续", type: "text")
        let replayed = StreamFrame(
            seq: 4, type: "user/message", time: 1, data: .object(["text": .string("继续")]))
        let messages = reduceTranscript([existing], frames: [replayed, replayed])
        #expect(messages.count == 1)
        #expect(messages[0].text == "继续")
    }

    @Test func durableFramesSkipPlainDeltas() {
        #expect(!isDurableFrame(delta("a", seq: 1)))
        #expect(isDurableFrame(blockEnd(seq: 2)))
        #expect(isDurableFrame(StreamFrame(seq: 3, type: "turn/end", time: 1, data: .null)))
    }
}

private func delta(_ text: String, seq: Int) -> StreamFrame {
    StreamFrame(
        seq: seq, type: "assistant/chunk", time: 1,
        data: .object([
            "turn": .number(1),
            "chunk": .object([
                "type": .string("text-delta"),
                "index": .number(0),
                "text": .string(text),
            ]),
        ]))
}

private func blockEnd(seq: Int, text: String = "Hello") -> StreamFrame {
    StreamFrame(
        seq: seq, type: "assistant/chunk", time: 1,
        data: .object([
            "turn": .number(1),
            "chunk": .object([
                "type": .string("block-end"),
                "index": .number(0),
                "block": .object([
                    "type": .string("text"),
                    "text": .string(text),
                ]),
            ]),
        ]))
}

private func chunkText(_ frame: StreamFrame) -> String? {
    guard case .object(let object) = frame.data, case .object(let chunk) = object["chunk"],
        case .string(let text) = chunk["text"]
    else { return nil }
    return text
}

private func fade(_ rows: [TranscriptRow]) -> Bool {
    guard case .assistant(let block) = rows.first else { return false }
    return block.fade
}
