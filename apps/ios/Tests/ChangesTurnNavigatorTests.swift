import DLCore
import Testing

/// C08：轮次导航只在真实有改动的轮间切换；提问只生成引用、不覆盖草稿。
@Suite struct ChangesTurnNavigatorTests {
    private func tail(_ id: String, seq: Int?, total: Int) -> TranscriptRow {
        .tail(
            TurnTail(
                id: id, lines: [], added: total, deleted: 0, total: total,
                meta: TurnMeta(model: nil, thinkingSeconds: nil, tokens: nil, elapsedSeconds: nil),
                copyText: "", showsSuggestions: false, changesSeq: seq))
    }

    @Test func seqsSkipTurnsWithoutChangesAndDuplicates() {
        let rows = [
            tail("a", seq: 3, total: 2), tail("b", seq: 5, total: 0), tail("c", seq: nil, total: 4),
            tail("d", seq: 9, total: 1), tail("e", seq: 9, total: 1),
        ]
        #expect(ChangesTurnNavigator.seqs(in: rows) == [3, 9])
    }

    @Test func previousAndNextStopAtEnds() {
        let seqs = [3, 9, 12]
        #expect(ChangesTurnNavigator.previous(of: 3, in: seqs) == nil)
        #expect(ChangesTurnNavigator.next(of: 3, in: seqs) == 9)
        #expect(ChangesTurnNavigator.previous(of: 12, in: seqs) == 9)
        #expect(ChangesTurnNavigator.next(of: 12, in: seqs) == nil)
        #expect(ChangesTurnNavigator.next(of: 7, in: seqs) == nil)
        #expect(ChangesTurnNavigator.previous(of: nil, in: seqs) == nil)
    }

    @Test func askReferenceAppendsWithoutOverwriting() {
        let reference = ChangesTurnNavigator.askReference(paths: ["src/a.swift", "", "b c.md"])
        #expect(reference == "@\"src/a.swift\" @\"b c.md\"")
        #expect(ChangesTurnNavigator.appending(reference, to: "") == reference)
        #expect(ChangesTurnNavigator.appending(reference, to: "已写的") == "已写的\n" + reference)
        #expect(ChangesTurnNavigator.appending("", to: "已写的") == "已写的")
    }
}
