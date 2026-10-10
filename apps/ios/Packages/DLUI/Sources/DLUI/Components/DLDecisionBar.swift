import UIKit

/// 设计稿 4.4：提问卡里的一个选项。`value` 交回宿主，`title` 是显示文字。
public struct DecisionChoice: Equatable {
    public var value: String
    public var title: String
    public var selected: Bool

    public init(value: String, title: String, selected: Bool) {
        self.value = value
        self.title = title
        self.selected = selected
    }
}

/// 设计稿 4.4：提问卡里题面下方的选项列表 + 自由回答输入框，与状态行、题面、
/// 「跳过 / 下一题」放在同一块玻璃里。回答文字不参与去重签名：用户打字时不重装视图。
public struct DecisionAnswerInput: Equatable {
    public var choices: [DecisionChoice]
    public var placeholder: String
    public var text: String
    /// C06 10.2.2：未知题型的说明（按钮为什么点不动）。
    public var note: String?
    /// 选项列表的读屏分组名。
    public var accessibilityLabel: String

    public init(
        choices: [DecisionChoice], placeholder: String, text: String, note: String? = nil,
        accessibilityLabel: String = ""
    ) {
        self.choices = choices
        self.placeholder = placeholder
        self.text = text
        self.note = note
        self.accessibilityLabel = accessibilityLabel
    }

    /// 去重签名：不含 `text`。
    var signature: String {
        let rows = choices.map { "\($0.value)␞\($0.title)␞\($0.selected ? 1 : 0)" }
        return (rows + [placeholder, note ?? "", accessibilityLabel]).joined(separator: "␝")
    }
}

/// 实色块的底色：玻璃面板里的命令块、选项块都用它，避免玻璃叠玻璃。
/// 深色下面板本身接近 secondarySystemBackground，块要再亮一级才分得出来。
var decisionBlockColor: UIColor {
    UIColor { traits in
        traits.userInterfaceStyle == .dark ? .tertiarySystemBackground : .secondarySystemBackground
    }
}

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
    answer: DecisionAnswerInput? = nil,
    answerField: UITextField? = nil,
    onChoice: @escaping (String) -> Void = { _ in },
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
    // 设计稿 4.3 / 4.4：题面是 title3 中粗体。只改字重，字号仍随动态字体。
    let title3 = UIFont.preferredFont(forTextStyle: .title3)
    questionLabel.font =
        title3.fontDescriptor.withSymbolicTraits(.traitBold).map { UIFont(descriptor: $0, size: 0) } ?? title3
    questionLabel.adjustsFontForContentSizeCategory = true
    questionLabel.textColor = enabled ? DLUIKitColor.label : DLUIKitColor.tertiaryLabel
    questionLabel.numberOfLines = 4
    questionLabel.setContentHuggingPriority(.required, for: .vertical)

    // 设计稿 4.4：标题为空的按钮不画——空白的品牌实心按钮既没有意义，也会被读屏读成「按钮」。
    var buttonViews: [UIView] = []
    if !secondaryTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        buttonViews.append(
            dlBarButton(title: secondaryTitle, prominent: false, enabled: enabled, action: onSecondary))
    }
    if !primaryTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        buttonViews.append(dlBarButton(title: primaryTitle, prominent: true, enabled: enabled, action: onPrimary))
    }
    let buttons = UIStackView(arrangedSubviews: buttonViews)
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
        let code = DLCommandTextView()
        code.text = command
        // 等宽字体按 footnote 的基准字号建，再交给 UIFontMetrics 随动态字体缩放。
        let footnote = UIFont.preferredFont(
            forTextStyle: .footnote, compatibleWith: UITraitCollection(preferredContentSizeCategory: .large))
        code.font = UIFontMetrics(forTextStyle: .footnote).scaledFont(
            for: UIFont.monospacedSystemFont(ofSize: footnote.pointSize, weight: .regular))
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
        // 设计稿 4.3：玻璃面板里的命令块用实色底，避免玻璃叠玻璃。
        // 深色下面板本身接近 secondarySystemBackground，命令块要再亮一级才分得出来。
        pad.backgroundColor = decisionBlockColor
        pad.layer.cornerRadius = 8
        pad.clipsToBounds = true
        rows.append(pad)
    }
    // 设计稿 4.4：选项与自由回答是题面下方的一个实色分组块，和按钮同在一块玻璃里。
    if let answer {
        rows.append(
            makeAnswerBlock(answer, field: answerField, enabled: enabled, onChoice: onChoice))
        if let note = answer.note, !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let noteLabel = UILabel()
            noteLabel.text = note
            noteLabel.font = UIFont.preferredFont(forTextStyle: .footnote)
            noteLabel.adjustsFontForContentSizeCategory = true
            noteLabel.textColor = DLUIKitColor.err
            noteLabel.numberOfLines = 0
            rows.append(noteLabel)
        }
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
    if !buttonViews.isEmpty {
        rows.append(buttons)
    }
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

/// 设计稿 4.4：选项行（文字 + 右侧对勾）+ 分隔线 + 自由回答输入框，整块实色 12pt 连续圆角。
@MainActor
private func makeAnswerBlock(
    _ answer: DecisionAnswerInput, field: UITextField?, enabled: Bool, onChoice: @escaping (String) -> Void
) -> UIView {
    var items: [UIView] = []
    for choice in answer.choices {
        if !items.isEmpty { items.append(decisionSeparator()) }
        items.append(makeChoiceRow(choice, enabled: enabled, onChoice: onChoice))
    }
    if let field {
        if !items.isEmpty { items.append(decisionSeparator()) }
        field.placeholder = answer.placeholder
        field.isEnabled = enabled
        let holder = UIView()
        field.translatesAutoresizingMaskIntoConstraints = false
        holder.addSubview(field)
        NSLayoutConstraint.activate([
            field.leadingAnchor.constraint(equalTo: holder.leadingAnchor, constant: 12),
            field.trailingAnchor.constraint(equalTo: holder.trailingAnchor, constant: -12),
            field.topAnchor.constraint(equalTo: holder.topAnchor, constant: 4),
            field.bottomAnchor.constraint(equalTo: holder.bottomAnchor, constant: -4),
        ])
        items.append(holder)
    }
    let block = UIStackView(arrangedSubviews: items)
    block.axis = .vertical
    block.spacing = 0
    block.backgroundColor = decisionBlockColor
    block.layer.cornerRadius = 12
    block.layer.cornerCurve = .continuous
    block.clipsToBounds = true
    block.accessibilityContainerType = .list
    block.accessibilityLabel = answer.accessibilityLabel
    return block
}

@MainActor
private func decisionSeparator() -> UIView {
    let holder = UIView()
    let line = UIView()
    line.backgroundColor = .separator
    line.translatesAutoresizingMaskIntoConstraints = false
    holder.addSubview(line)
    NSLayoutConstraint.activate([
        line.leadingAnchor.constraint(equalTo: holder.leadingAnchor, constant: 12),
        line.trailingAnchor.constraint(equalTo: holder.trailingAnchor),
        line.topAnchor.constraint(equalTo: holder.topAnchor),
        line.bottomAnchor.constraint(equalTo: holder.bottomAnchor),
        line.heightAnchor.constraint(equalToConstant: 1 / max(1, UITraitCollection.current.displayScale)),
    ])
    return holder
}

@MainActor
private func makeChoiceRow(_ choice: DecisionChoice, enabled: Bool, onChoice: @escaping (String) -> Void)
    -> UIView
{
    let value = choice.value
    let button = UIButton(type: .custom, primaryAction: UIAction { _ in onChoice(value) })
    button.isEnabled = enabled && !value.isEmpty
    let title = UILabel()
    title.text = choice.title.isEmpty ? choice.value : choice.title
    title.font = UIFont.preferredFont(forTextStyle: .body)
    title.adjustsFontForContentSizeCategory = true
    title.textColor = enabled ? DLUIKitColor.label : DLUIKitColor.tertiaryLabel
    title.numberOfLines = 0
    let check = UIImageView(image: UIImage(systemName: "checkmark"))
    check.preferredSymbolConfiguration = UIImage.SymbolConfiguration(textStyle: .body, scale: .medium)
    check.tintColor = DLUIKitColor.accent
    check.alpha = choice.selected ? 1 : 0
    check.setContentHuggingPriority(.required, for: .horizontal)
    check.setContentCompressionResistancePriority(.required, for: .horizontal)
    let line = UIStackView(arrangedSubviews: [title, check])
    line.axis = .horizontal
    line.alignment = .center
    line.spacing = 12
    line.isUserInteractionEnabled = false
    line.translatesAutoresizingMaskIntoConstraints = false
    button.addSubview(line)
    NSLayoutConstraint.activate([
        line.leadingAnchor.constraint(equalTo: button.leadingAnchor, constant: 12),
        line.trailingAnchor.constraint(equalTo: button.trailingAnchor, constant: -12),
        line.topAnchor.constraint(equalTo: button.topAnchor, constant: 10),
        line.bottomAnchor.constraint(equalTo: button.bottomAnchor, constant: -10),
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
    ])
    button.isAccessibilityElement = true
    button.accessibilityLabel = title.text
    button.accessibilityTraits = choice.selected ? [.button, .selected] : [.button]
    return button
}

/// 4.3 命令块。可滚动的 `UITextView` 没有固有高度，放进竖向 `UIStackView` 会被压成 0 高。
/// 这里按当前宽度报告内容高度；外面再用 ≤132pt 的约束限高，超出部分在块内滚动。
final class DLCommandTextView: UITextView {
    private var measuredWidth: CGFloat = 0

    override var intrinsicContentSize: CGSize {
        // 首次测量时还没有宽度，先按常见手机内容宽估一个；布局后按真实宽度重算。
        let width = bounds.width > 1 ? bounds.width : 320
        let fitted = sizeThatFits(CGSize(width: width, height: CGFloat.greatestFiniteMagnitude))
        return CGSize(width: UIView.noIntrinsicMetric, height: ceil(fitted.height))
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard abs(bounds.width - measuredWidth) > 0.5 else { return }
        measuredWidth = bounds.width
        invalidateIntrinsicContentSize()
    }
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
