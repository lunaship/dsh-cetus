import DLCore
import DLModels
import Foundation
import Testing

/// C06 要求 10.2.4 / 10.2.6：问题回答草稿按 rpcID 归属、每题独立，且不碰普通消息草稿。
@Suite struct QuestionFormSnapshotTests {
    private func form() -> QuestionForm {
        QuestionForm(questions: [
            ClarifyingQuestion(id: "a", question: "第一题"),
            ClarifyingQuestion(id: "b", question: "第二题"),
        ])
    }

    @Test func roundTripRestoresPerQuestionDraftsAndIndex() throws {
        var original = form()
        original.updateCurrent(custom: "答案 A")
        _ = original.move(.next)
        original.updateCurrent(custom: "答案 B")
        let text = original.snapshot(rpcID: "rpc-1").encoded
        #expect(!text.isEmpty)

        var restored = form()
        restored.restore(QuestionFormSnapshot.decode(text), rpcID: "rpc-1")
        #expect(restored.index == 1)
        #expect(restored.currentCustom == "答案 B")
        _ = restored.move(.previous)
        #expect(restored.currentCustom == "答案 A")
    }

    @Test func differentRequestIsIgnored() {
        var original = form()
        original.updateCurrent(custom: "旧题的答案")
        let snapshot = original.snapshot(rpcID: "rpc-old")
        var fresh = form()
        fresh.restore(snapshot, rpcID: "rpc-new")
        #expect(fresh.currentCustom.isEmpty)
    }

    @Test func emptyFormEncodesToEmptyStringSoStoreDeletes() {
        #expect(form().snapshot(rpcID: "rpc").encoded.isEmpty)
        #expect(QuestionFormSnapshot.decode("") == nil)
        #expect(QuestionFormSnapshot.decode("not json") == nil)
    }

    @Test func outOfRangeIndexIsClamped() {
        var restored = form()
        restored.restore(
            QuestionFormSnapshot(
                rpcID: "r", index: 9, drafts: ["a": QuestionDraft(selected: [], custom: "x")]),
            rpcID: "r")
        #expect(restored.index == 1)
    }
}
