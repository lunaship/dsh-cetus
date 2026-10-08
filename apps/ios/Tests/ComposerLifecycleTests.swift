import DLUI
import UIKit
import XCTest

@testable import Cetus

@MainActor
final class ComposerLifecycleTests: XCTestCase {
    private func makeView(text: String = "hello") -> DLComposerView {
        let view = DLComposerView(text: text, isEnabled: true, sendTitle: "Send")
        view.frame = CGRect(x: 0, y: 0, width: 402, height: 200)
        view.layoutIfNeeded()
        return view
    }

    /// 同模式重复 update 保持文本视图实例与选区。
    func testRepeatedComposerUpdateKeepsFieldInstanceAndSelection() {
        let view = makeView(text: "draft")
        let before = view.editor
        let beforeSelection = before.selectedRange
        view.text = "draft"
        view.showComposer(animated: false)
        view.showComposer(animated: false)
        view.text = "draft"
        view.suggestions = []
        view.suggestions = []
        view.showsAttachButton = false
        XCTAssertTrue(view.editor === before, "editor instance must not change on composer→composer")
        XCTAssertEqual(view.editor.selectedRange, beforeSelection, "selection must not change")
        XCTAssertEqual(view.editor.text, "draft")
    }

    /// 程序设置文本不触发 onDraft 回调（避免 SwiftUI↔UIKit 死循环）。
    func testExternalTextAssignmentDoesNotEchoOnDraft() {
        let view = makeView()
        var drafts: [String] = []
        view.onDraft = { drafts.append($0) }
        view.text = "from-swiftui-1"
        view.text = "from-swiftui-2"
        view.text = "from-swiftui-2"
        XCTAssertEqual(drafts, [], "external text must not echo back into onDraft")
        XCTAssertEqual(view.editor.text, "from-swiftui-2")
    }

    /// 同模式更新不替换内容：编辑器父视图应保持不变（不重建 glass 内容）。
    func testComposerUpdateDoesNotReplaceContentView() {
        let view = makeView()
        view.text = "a"
        let parentBefore = view.editor.superview
        view.suggestions = [
            ComposerSuggestion(id: "x", group: "G", title: "t", detail: "d"),
            ComposerSuggestion(id: "y", group: "G", title: "t", detail: "d"),
        ]
        view.suggestions = []
        view.suggestions = [
            ComposerSuggestion(id: "x", group: "G", title: "t", detail: "d")
        ]
        XCTAssertNotNil(parentBefore)
        XCTAssertTrue(view.editor.superview === parentBefore, "editor parent must remain")
    }

    /// 切换到 decision 再回 composer，编辑器是同一个实例、草稿保留。
    func testRoundTripComposerDecisionComposerKeepsInstanceAndDraft() {
        let view = makeView(text: "keep me")
        let before = view.editor
        view.showDecision(
            status: "Wait", question: "Run?",
            secondaryTitle: "Reject", primaryTitle: "Allow",
            command: "npm test", animated: false)
        view.showDecision(
            status: "Wait", question: "Run?",
            secondaryTitle: "Reject", primaryTitle: "Allow",
            command: "npm test", animated: false)
        view.showComposer(animated: false)
        XCTAssertTrue(view.editor === before, "editor instance must survive decision round-trip")
        XCTAssertEqual(view.editor.text, "keep me", "draft must survive decision round-trip")
    }

    /// 同 decision 多次 push 不重复 install。
    func testRepeatedDecisionShowsInstallOnce() {
        let view = makeView()
        // 触发 install
        view.showDecision(
            status: "Wait", question: "Q?",
            secondaryTitle: "S", primaryTitle: "P", command: nil, animated: false)
        let editorSnapshotDuringDecision = view.editor
        view.showDecision(
            status: "Wait", question: "Q?",
            secondaryTitle: "S", primaryTitle: "P", command: nil, animated: false)
        // composer 模式与 decision 模式切换后编辑器不应被释放。
        view.showComposer(animated: false)
        XCTAssertTrue(view.editor === editorSnapshotDuringDecision, "editor identity must persist")
    }

    /// 硬件 Return / Shift-Return 合同：Return 发送，Shift-Return 换行。
    func testReturnKeyContract() {
        let view = makeView(text: "line")
        var sent = 0
        view.onSubmit = { sent += 1 }
        let tv = view.editor as! DLComposerTextView
        let d = tv.delegate
        // 普通 Return → 拒收新文本、触发 onSubmit
        XCTAssertFalse(d!.textView!(tv, shouldChangeTextIn: NSRange(location: 4, length: 0), replacementText: "\n"))
        XCTAssertEqual(sent, 1)
        // Shift-Return → 允许换行、不触发发送
        tv.shiftIsDown = true
        XCTAssertTrue(d!.textView!(tv, shouldChangeTextIn: NSRange(location: 4, length: 0), replacementText: "\n"))
        XCTAssertEqual(sent, 1)
        tv.shiftIsDown = false
    }

    // MARK: - 中文组词保护（U01 的核心）

    /// 组词期间（markedTextRange 非空）外部状态刷新**不得**替换编辑器内容。
    ///
    /// 方案 §5 验收用例：拼音组词中持续接收 30 秒流事件，候选/焦点/光标不丢。
    /// 这里用 `setMarkedText` 造出真实的 marked 状态，再走 SwiftUI 侧的 `text` 赋值，
    /// 断言 marked 文本不被冲掉。
    func testExternalUpdateDuringMarkedTextDoesNotReplaceContent() {
        let view = makeView(text: "")
        let tv = view.editor
        tv.becomeFirstResponder()
        // 模拟拼音中间态：marked 区间上是 "zhang"，尚未上屏。
        tv.setMarkedText("zhang", selectedRange: NSRange(location: 5, length: 0))
        XCTAssertNotNil(tv.markedTextRange, "应处于组词中间态")
        let markedBefore = tv.text

        // 流事件导致 SwiftUI 侧把绑定文本刷成旧值 —— 必须被挡住。
        view.text = "older-from-server"

        XCTAssertEqual(
            tv.text, markedBefore,
            "组词期间外部刷新不得替换编辑器内容")
        XCTAssertNotNil(tv.markedTextRange, "组词状态必须保留")
        XCTAssertEqual(tv.text, "zhang")
    }

    /// 组词期间不得触发发送（中文确认候选不能当成 Return）。
    func testMarkedTextDoesNotTriggerSubmit() {
        let view = makeView(text: "")
        let tv = view.editor as! DLComposerTextView
        var sent = 0
        view.onSubmit = { sent += 1 }
        tv.becomeFirstResponder()
        tv.setMarkedText("ni", selectedRange: NSRange(location: 2, length: 0))
        // 中文候选确认会走 shouldChangeTextIn，带普通文本而非 "\n"
        _ = tv.delegate?.textView?(
            tv, shouldChangeTextIn: NSRange(location: 2, length: 0),
            replacementText: "你")
        XCTAssertEqual(sent, 0, "确认中文候选不得触发发送")
    }
}
