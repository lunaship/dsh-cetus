import DLCore
import DLUI
import SwiftUI

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

    func makeUIView(context: Context) -> DLComposerView {
        let view = DLComposerView(sendTitle: copy.text(.send))
        view.pinsToKeyboard = false
        view.usesSolidSnapshotBackground = solidSnapshot
        return view
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: DLComposerView, context: Context) -> CGSize? {
        let width = proposal.width ?? uiView.bounds.width
        let height = uiView.intrinsicContentSize.height
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
        view.suggestions = suggestions
        view.onSuggestion = onSuggestion
        view.showsAttachButton = showsAttach
        view.attachTitle = attachTitle
        view.onAttach = onAttach
        view.isSending = isSending
        view.placeholder = placeholder
        let inDecision = decision != nil
        view.isEnabled = !(inDecision && decisionBusy)
        let key = ObjectIdentifier(view)
        let wasInDecision = Self.lastMode[key] ?? false
        if let decision {
            if !wasInDecision {
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
                    // C06 10.2.7：末题「提交回答」由题目导航区提供。若导航区正在
                    // 承担提交，决策栏只保留拒绝，**不再出现第二个「发送」**。
                    let questionHandled = decisionHandled || questionUsesNavigatorSubmit
                    view.showDecision(
                        status: decisionHandled ? copy.text(.decisionHandledStatus) : copy.text(.waitAnswer),
                        question: message.text.isEmpty ? copy.text(.question) : message.text,
                        secondaryTitle: decisionHandled ? "" : copy.text(.reject),
                        primaryTitle: questionHandled ? "" : copy.text(.send),
                        command: nil,
                        positionText: decisionPosition,
                        notice: decisionNotice,
                        animated: false)
                }
                Self.lastMode[key] = true
            }
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
