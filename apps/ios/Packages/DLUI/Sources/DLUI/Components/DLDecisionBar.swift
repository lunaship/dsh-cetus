import UIKit

@MainActor
func makeDecisionContent(
    status: String,
    question: String,
    command: String? = nil,
    secondaryTitle: String,
    primaryTitle: String,
    enabled: Bool,
    positionText: String? = nil,
    notice: String? = nil,
    onSecondary: @escaping () -> Void,
    onPrimary: @escaping () -> Void
) -> UIView {
    let statusLabel = UILabel()
    statusLabel.text = status
    statusLabel.font = UIFont.preferredFont(forTextStyle: .subheadline)
    statusLabel.adjustsFontForContentSizeCategory = true
    statusLabel.textColor = enabled ? DLUIKitColor.wait : DLUIKitColor.tertiaryLabel
    // C06 10.1.7：状态不再被截成一行，大字号与读屏都能拿到完整状态。
    statusLabel.numberOfLines = 0
    statusLabel.setContentHuggingPriority(.required, for: .vertical)

    let questionLabel = UILabel()
    questionLabel.text = question
    questionLabel.font = UIFont.preferredFont(forTextStyle: .title3)
    questionLabel.adjustsFontForContentSizeCategory = true
    questionLabel.textColor = enabled ? DLUIKitColor.label : DLUIKitColor.tertiaryLabel
    questionLabel.numberOfLines = 4
    questionLabel.setContentHuggingPriority(.required, for: .vertical)

    let secondary = dlBarButton(title: secondaryTitle, prominent: false, enabled: enabled, action: onSecondary)
    let primary = dlBarButton(title: primaryTitle, prominent: true, enabled: enabled, action: onPrimary)
    let buttons = UIStackView(arrangedSubviews: [secondary, primary])
    buttons.axis = .horizontal
    buttons.spacing = 12
    buttons.distribution = .fillEqually

    var rows: [UIView] = [statusLabel, questionLabel]
    // C06 10.1.6：多个待处理请求时显示「第 N / 共 M 个」的位置。
    if let positionText, !positionText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        let position = UILabel()
        position.text = positionText
        position.font = UIFont.preferredFont(forTextStyle: .footnote)
        position.adjustsFontForContentSizeCategory = true
        position.textColor = DLUIKitColor.tertiaryLabel
        position.numberOfLines = 0
        rows.append(position)
    }
    if let command, !command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        // C06 10.1.4：大命令**限高 + 内部滚动**看全文，不再用 4 行截断掩盖危险部分。
        let code = UITextView()
        code.text = command
        code.font = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        code.adjustsFontForContentSizeCategory = true
        code.textColor = enabled ? DLUIKitColor.label : DLUIKitColor.tertiaryLabel
        code.backgroundColor = .clear
        code.isEditable = false
        code.isSelectable = true
        code.isScrollEnabled = true
        code.textContainerInset = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
        code.textContainer.lineFragmentPadding = 0
        // 读屏把它当一段静态文本，而不是可编辑控件。
        code.accessibilityTraits = .staticText
        let pad = UIView()
        code.translatesAutoresizingMaskIntoConstraints = false
        pad.addSubview(code)
        NSLayoutConstraint.activate([
            code.leadingAnchor.constraint(equalTo: pad.leadingAnchor, constant: 4),
            code.trailingAnchor.constraint(equalTo: pad.trailingAnchor, constant: -4),
            code.topAnchor.constraint(equalTo: pad.topAnchor, constant: 0),
            code.bottomAnchor.constraint(equalTo: pad.bottomAnchor, constant: 0),
            // 上限约 6 行等宽正文；超出即内部滚动，命令块不会把下面的按钮挤出屏幕。
            code.heightAnchor.constraint(lessThanOrEqualToConstant: 132),
        ])
        pad.backgroundColor = .secondarySystemFill
        pad.layer.cornerRadius = 8
        pad.clipsToBounds = true
        rows.append(pad)
    }
    // C06 10.1.5：失败提示留在面板内，与它解释的按钮同屏。
    if let notice, !notice.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        let noticeLabel = UILabel()
        noticeLabel.text = notice
        noticeLabel.font = UIFont.preferredFont(forTextStyle: .footnote)
        noticeLabel.adjustsFontForContentSizeCategory = true
        noticeLabel.textColor = DLUIKitColor.err
        noticeLabel.numberOfLines = 0
        rows.append(noticeLabel)
    }
    // 拒绝按钮排在命令块之后、**不随命令滚动**——10.1.4 要求读完前始终可见。
    rows.append(buttons)
    for row in rows {
        row.setContentHuggingPriority(.required, for: .vertical)
    }
    let column = UIStackView(arrangedSubviews: rows)
    column.axis = .vertical
    column.spacing = 8
    // C06 10.1.7：读屏顺序 = 状态 → 题面 → 位置 → 命令 → 提示 → 按钮，与视觉一致。
    column.shouldGroupAccessibilityChildren = false
    column.accessibilityElements = rows
    return column
}

public final class DLDecisionBar: UIView {
    public var onSecondary: (() -> Void)?
    public var onPrimary: (() -> Void)?

    private let glass = DLGlassBar()
    private var keyboardPins: [NSLayoutConstraint] = []
    private let status: String
    private let question: String
    private let secondaryTitle: String
    private let primaryTitle: String
    private let enabled: Bool
    /// C06 10.1.6：多个待处理请求时的位置文案。
    private let positionText: String?
    /// C06 10.1.5：面板内的失败提示。
    private let notice: String?

    public init(
        status: String,
        question: String,
        secondaryTitle: String,
        primaryTitle: String,
        isEnabled: Bool = true,
        positionText: String? = nil,
        notice: String? = nil
    ) {
        self.status = status
        self.question = question
        self.secondaryTitle = secondaryTitle
        self.primaryTitle = primaryTitle
        self.enabled = isEnabled
        self.positionText = positionText
        self.notice = notice
        super.init(frame: .zero)
        glass.translatesAutoresizingMaskIntoConstraints = false
        addSubview(glass)
        NSLayoutConstraint.activate([
            glass.leadingAnchor.constraint(equalTo: leadingAnchor),
            glass.trailingAnchor.constraint(equalTo: trailingAnchor),
            glass.topAnchor.constraint(equalTo: topAnchor),
            glass.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        reload(animated: false)
    }

    required init?(coder: NSCoder) {
        return nil
    }

    public override func didMoveToSuperview() {
        super.didMoveToSuperview()
        NSLayoutConstraint.deactivate(keyboardPins)
        keyboardPins = []
        guard let superview else { return }
        translatesAutoresizingMaskIntoConstraints = false
        keyboardPins = [
            leadingAnchor.constraint(equalTo: superview.leadingAnchor),
            trailingAnchor.constraint(equalTo: superview.trailingAnchor),
            bottomAnchor.constraint(equalTo: superview.keyboardLayoutGuide.topAnchor),
        ]
        NSLayoutConstraint.activate(keyboardPins)
    }

    private func reload(animated: Bool) {
        glass.install(
            makeDecisionContent(
                status: status,
                question: question,
                secondaryTitle: secondaryTitle,
                primaryTitle: primaryTitle,
                enabled: enabled,
                positionText: positionText,
                notice: notice,
                onSecondary: { [weak self] in self?.onSecondary?() },
                onPrimary: { [weak self] in self?.onPrimary?() }
            ),
            animated: animated
        )
    }
}
