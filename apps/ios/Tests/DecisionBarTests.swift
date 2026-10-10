import Testing
import UIKit

@testable import DLUI

/// C06 §10.1.4 / 10.1.6 / 10.1.7：决策面板的可见性契约——
/// 大命令限高可滚动看全文、拒绝按钮始终可见、位置文案可选、读屏顺序与视觉一致。
@MainActor
@Suite struct DecisionBarTests {
    private func makeContent(
        command: String? = nil,
        positionText: String? = nil,
        notice: String? = nil,
        enabled: Bool = true
    ) -> UIView {
        makeDecisionContent(
            status: "Wait",
            question: "Run rm -rf?",
            command: command,
            secondaryTitle: "Reject",
            primaryTitle: "Allow once",
            enabled: enabled,
            positionText: positionText,
            notice: notice,
            onSecondary: {},
            onPrimary: {})
    }

    private func descendants(_ view: UIView) -> [UIView] {
        view.subviews.flatMap { [$0] + descendants($0) }
    }

    /// 决策按钮用 `UIButton.Configuration` 建（`DLGlassBar.dlBarButton`），
    /// 标题存在 configuration 里，`title(for: .normal)` 恒为 nil。
    /// 这里按真实来源取标题，否则断言会永远失败。
    private func buttonTitles(in view: UIView) -> [String] {
        descendants(view).compactMap { ($0 as? UIButton)?.configuration?.title }
    }

    // MARK: 10.1.4 大命令

    @Test func longCommandBecomesScrollableTextView() {
        let content = makeContent(command: String(repeating: "echo line\n", count: 200))
        let textViews = descendants(content).compactMap { $0 as? UITextView }
        #expect(textViews.count == 1, "命令块应为可滚动 UITextView")
        let code = textViews.first
        guard let code else {
            Issue.record("命令块未渲染成 UITextView")
            return
        }
        #expect(code.isScrollEnabled == true, "大命令必须能滚动看全文")
        #expect(code.isEditable == false)
        #expect(code.text?.contains("echo line") == true)
    }

    @Test func commandBlockIsHeightCappedSoButtonsStayVisible() {
        let content = makeContent(command: String(repeating: "x\n", count: 500))
        content.frame = CGRect(x: 0, y: 0, width: 360, height: 2000)
        content.layoutIfNeeded()
        let code = descendants(content).compactMap { $0 as? UITextView }.first
        guard let code else {
            Issue.record("命令块未渲染成 UITextView")
            return
        }
        #expect(code.frame.height <= 133, "命令块限高，不能把下面的按钮挤出屏幕")
    }

    @Test func commandTextIsFullyRetainedNotTruncated() {
        // 旧实现用 numberOfLines = 4 截断；现在必须保留全文（靠滚动查看）。
        let long = (1...200).map { "line \($0)" }.joined(separator: "\n")
        let content = makeContent(command: long)
        let code = descendants(content).compactMap { $0 as? UITextView }.first
        guard let code else {
            Issue.record("命令块未渲染成 UITextView")
            return
        }
        #expect(code.text == long, "不能截断尾部——危险部分可能就在末尾")
    }

    @Test func commandIsStaticTextForVoiceOver() {
        let content = makeContent(command: "rm -rf /")
        let code = descendants(content).compactMap { $0 as? UITextView }.first
        guard let code else {
            Issue.record("命令块未渲染成 UITextView")
            return
        }
        #expect(code.accessibilityTraits.contains(.staticText) == true)
    }

    @Test func noCommandMeansNoCommandBlock() {
        #expect(descendants(makeContent()).compactMap { $0 as? UITextView }.isEmpty)
        #expect(descendants(makeContent(command: "   ")).compactMap { $0 as? UITextView }.isEmpty)
    }

    // MARK: 10.1.4 拒绝始终可见

    @Test func rejectButtonIsAlwaysPresentWithLongCommand() {
        let content = makeContent(command: String(repeating: "very long command\n", count: 300))
        let titles = buttonTitles(in: content)
        #expect(titles.contains("Reject"), "读完命令之前拒绝入口必须一直在")
        #expect(titles.contains("Allow once"))
    }

    @Test func buttonsRemainEnabledWhenCommandIsHuge() {
        let content = makeContent(command: String(repeating: "x", count: 10_000))
        let enabled = descendants(content).compactMap { $0 as? UIButton }.filter { $0.isEnabled }
        #expect(enabled.count == 2, "命令再长也不能禁用决策按钮")
    }

    // MARK: 10.1.6 位置

    @Test func positionTextIsRenderedWhenProvided() {
        let texts = descendants(makeContent(positionText: "Request 1 of 3"))
            .compactMap { ($0 as? UILabel)?.text }
        #expect(texts.contains("Request 1 of 3"))
    }

    @Test func positionIsOmittedWhenNilOrBlank() {
        for value in [nil, "", "   "] as [String?] {
            let texts = descendants(makeContent(positionText: value)).compactMap { ($0 as? UILabel)?.text }
            #expect(!texts.contains { $0.contains("Request") })
        }
    }

    // MARK: 10.1.5 面板内提示

    @Test func noticeIsRenderedInsidePanel() {
        let texts = descendants(makeContent(notice: "Couldn't submit.")).compactMap { ($0 as? UILabel)?.text }
        #expect(texts.contains("Couldn't submit."))
    }

    @Test func noticeIsOmittedWhenNil() {
        let texts = descendants(makeContent()).compactMap { ($0 as? UILabel)?.text }
        #expect(texts.contains("Couldn't submit.") == false)
    }

    // MARK: 10.1.7 读屏顺序与不截断

    @Test func accessibilityOrderFollowsVisualOrder() {
        let content = makeContent(command: "rm -rf /", positionText: "Request 2 of 3", notice: "Retry")
        let elements = content.accessibilityElements ?? []
        #expect(elements.count >= 4, "状态/题面/位置/命令/提示/按钮都应可被读屏访问")
        // 最后一个可访问元素必须是按钮组（决策动作可及）。
        let last = elements.last as? UIView
        let buttons = last?.subviews.compactMap { $0 as? UIButton } ?? []
        #expect(buttons.count == 2)
    }

    @Test func statusLabelIsNotTruncatedToOneLine() {
        let content = makeContent()
        let labels = descendants(content).compactMap { $0 as? UILabel }.filter { $0.text == "Wait" }
        guard let status = labels.first else {
            Issue.record("状态标签未渲染")
            return
        }
        #expect(status.numberOfLines == 0, "状态不能截成一行")
    }

    @Test func commandTextViewHasNoAccessibilityLabelLeakingIntoButtons() {
        let content = makeContent(command: "rm -rf /")
        guard let code = descendants(content).compactMap({ $0 as? UITextView }).first else {
            Issue.record("命令块未渲染成 UITextView")
            return
        }
        #expect((code.accessibilityLabel?.isEmpty ?? true) || code.accessibilityLabel == code.text)
    }

    // MARK: 设计稿 4.4 提问卡

    @Test func questionCardShowsChoicesAndAnswerFieldInsideOneBlock() {
        var picked: [String] = []
        let field = UITextField()
        let content = makeDecisionContent(
            status: "Waiting",
            question: "Limit?",
            secondaryTitle: "Skip",
            primaryTitle: "Next",
            enabled: true,
            answer: DecisionAnswerInput(
                choices: [
                    DecisionChoice(value: "60", title: "60", selected: true),
                    DecisionChoice(value: "120", title: "120", selected: false),
                ],
                placeholder: "Write your own answer", text: ""),
            answerField: field,
            onChoice: { picked.append($0) },
            onSecondary: {},
            onPrimary: {})
        let all = descendants(content)
        #expect(all.contains { $0 === field }, "自由回答输入框应在决策面板里")
        #expect(field.placeholder == "Write your own answer")
        let choices = all.compactMap { $0 as? UIButton }.filter { $0.configuration == nil }
        #expect(choices.map(\.accessibilityLabel) == ["60", "120"])
        #expect(choices.first?.accessibilityTraits.contains(.selected) == true)
        choices.last?.sendActions(for: .primaryActionTriggered)
        #expect(picked == ["120"])
        #expect(buttonTitles(in: content) == ["Skip", "Next"])
    }
}
