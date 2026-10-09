import DLCore
import DLUI
import SwiftUI

/// 设计稿 4.4：提问卡的状态行、当前题面和两个按钮的标题。
/// 次按钮：可选题是「跳过」，必填题是「上一题」（第一题时为空、不画）；
/// 主按钮：不是最后一题是「下一题」，最后一题是「发送」。
struct QuestionBarTitles: Equatable {
    var status: String
    var question: String
    var secondary: String
    var primary: String
}

struct ConversationBar: UIViewRepresentable {
    var decision: PhoneDecision?
    var draft: String
    var copy: ConversationCopy
    var onDraft: (String) -> Void
    var onSend: () -> Void
    var onEscape: () -> Void = {}
    var onSecondary: () -> Void
    var onPrimary: () -> Void
    var solidSnapshot = false
    var suggestions: [ComposerSuggestion] = []
    var onSuggestion: (String) -> Void = { _ in }
    var showsAttach = false
    var attachTitle = ""
    var onAttach: () -> Void = {}
    /// C03：提交在途时发送按钮进忙碌态，阻止重复点击。
    var isSending = false
    /// C05：编辑器为空时的提示文案。
    var placeholder = ""
    /// C06：请求已被其他设备处理 → 决策面板显示「已处理」，不给成功触感。
    var decisionHandled = false
    /// C06 10.1.6：多个待处理请求时的位置文案（如「第 1 / 3 个请求」）。
    var decisionPosition: String?
    /// C06 10.1.5：面板内的失败提示。
    var decisionNotice: String?
    /// C06 10.2.7：问题模式下，末题的「提交回答」已由题目导航区提供，
    /// 决策栏不再放第二个语义重复的发送按钮。
    var questionUsesNavigatorSubmit = true
    /// C06：审批 / 回答提交在途时决策按钮不可点，防止重复提交。
    var decisionBusy = false
    /// 设计稿 4.4：问题模式下由页面按当前题算好的标题。
    var questionTitles: QuestionBarTitles?
    /// 设计稿 4.1：输入区底部的模型 / 权限胶囊。
    var chips: [ComposerChip] = []
    var onChip: (String) -> Void = { _ in }
    /// 设计稿 4.4：题面下方的选项 + 自由回答，与按钮同在决策栏这一块玻璃里。
    var answer: DecisionAnswerInput?
    var onChoice: (String) -> Void = { _ in }
    var onAnswer: (String) -> Void = { _ in }

    func makeUIView(context: Context) -> DLComposerView {
        let view = DLComposerView(sendTitle: copy.text(.send))
        view.pinsToKeyboard = false
        view.usesSolidSnapshotBackground = solidSnapshot
        return view
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: DLComposerView, context: Context) -> CGSize? {
        // 按 SwiftUI 提议的宽度现量高度，不读上一轮布局留下的固有高度：
        // 那个值取决于视图此前被排到过什么宽度，截图在两次运行之间会不一致。
        let width = proposal.width ?? uiView.bounds.width
        guard width.isFinite, width > 1 else { return nil }
        let height = uiView.fittingHeight(width: width)
        guard height > 1, height < 10_000 else { return nil }
        return CGSize(width: width, height: height)
    }

    /// SwiftUI 连续更新时少做一次模式切换：只有进入/离开 decision 才需要。
    /// 同模式（composer→composer）下重新调用只是无害 no-op，但避开可以
    /// 让键盘焦点路径少走一次 install。
    private static var lastMode: [ObjectIdentifier: Bool] = [:]

    func updateUIView(_ view: DLComposerView, context: Context) {
        view.pinsToKeyboard = false
        view.usesSolidSnapshotBackground = solidSnapshot
        view.onDraft = onDraft
        view.onSubmit = onSend
        view.onEscape = onEscape
        view.onDecisionSecondary = onSecondary
        view.onDecisionPrimary = onPrimary
        view.onDecisionChoice = onChoice
        view.onDecisionAnswer = onAnswer
        view.suggestions = suggestions
        view.onSuggestion = onSuggestion
        view.showsAttachButton = showsAttach
        view.attachTitle = attachTitle
        view.onAttach = onAttach
        view.isSending = isSending
        view.placeholder = placeholder
        view.chips = chips
        view.onChip = onChip
        let inDecision = decision != nil
        view.isEnabled = !(inDecision && decisionBusy)
        let key = ObjectIdentifier(view)
        let wasInDecision = Self.lastMode[key] ?? false
        if let decision {
            // 每次都下推：题号、按钮标题会随切题变化。DLComposerView 按签名去重，
            // 内容没变时不会重装视图，也不会动键盘焦点。
            switch decision {
            case .approval(let message):
                if decisionHandled {
                    view.showDecision(
                        status: copy.text(.decisionHandledStatus),
                        question: message.text.isEmpty ? (message.toolName ?? copy.text(.approval)) : message.text,
                        secondaryTitle: "",
                        primaryTitle: copy.text(.decisionHandledPrimary),
                        command: approvalCommand(from: message.toolArgs),
                        positionText: decisionPosition,
                        notice: decisionNotice,
                        animated: false)
                } else {
                    view.showDecision(
                        status: copy.text(.waitApproval),
                        question: message.text.isEmpty ? (message.toolName ?? copy.text(.approval)) : message.text,
                        secondaryTitle: copy.text(.reject),
                        primaryTitle: copy.text(.allowOnce),
                        command: approvalCommand(from: message.toolArgs),
                        positionText: decisionPosition,
                        notice: decisionNotice,
                        animated: false)
                }
            case .question(let message):
                let fallbackQuestion = message.text.isEmpty ? copy.text(.question) : message.text
                if decisionHandled {
                    view.showDecision(
                        status: copy.text(.decisionHandledStatus),
                        question: fallbackQuestion,
                        secondaryTitle: "",
                        primaryTitle: "",
                        command: nil,
                        positionText: decisionPosition,
                        notice: decisionNotice,
                        animated: false)
                } else {
                    // C06 10.2.7：只有一个提交入口。题目导航的「跳过 / 上一题」「下一题 / 发送」
                    // 就是决策栏的两个按钮，不再在别处重复放。
                    let titles =
                        questionTitles
                        ?? QuestionBarTitles(
                            status: copy.text(.waitAnswer), question: fallbackQuestion, secondary: "",
                            primary: questionUsesNavigatorSubmit ? "" : copy.text(.send))
                    view.showDecision(
                        status: titles.status,
                        question: titles.question.isEmpty ? fallbackQuestion : titles.question,
                        secondaryTitle: titles.secondary,
                        primaryTitle: titles.primary,
                        command: nil,
                        positionText: decisionPosition,
                        notice: decisionNotice,
                        answer: answer,
                        animated: false)
                }
            }
            Self.lastMode[key] = true
        } else {
            if wasInDecision {
                view.showComposer(animated: false)
                Self.lastMode[key] = false
            } else {
                view.text = draft
            }
        }
    }
}
