import UIKit

@MainActor
func makeDecisionContent(
    status: String,
    question: String,
    secondaryTitle: String,
    primaryTitle: String,
    enabled: Bool,
    onSecondary: @escaping () -> Void,
    onPrimary: @escaping () -> Void
) -> UIView {
    let statusLabel = UILabel()
    statusLabel.text = status
    statusLabel.font = UIFont.preferredFont(forTextStyle: .subheadline)
    statusLabel.adjustsFontForContentSizeCategory = true
    statusLabel.textColor = enabled ? DLUIKitColor.wait : DLUIKitColor.tertiaryLabel
    statusLabel.numberOfLines = 1

    let questionLabel = UILabel()
    questionLabel.text = question
    questionLabel.font = UIFont.preferredFont(forTextStyle: .title3)
    questionLabel.adjustsFontForContentSizeCategory = true
    questionLabel.textColor = enabled ? DLUIKitColor.label : DLUIKitColor.tertiaryLabel
    questionLabel.numberOfLines = 4

    let secondary = dlBarButton(title: secondaryTitle, prominent: false, enabled: enabled, action: onSecondary)
    let primary = dlBarButton(title: primaryTitle, prominent: true, enabled: enabled, action: onPrimary)
    secondary.widthAnchor.constraint(equalTo: primary.widthAnchor).isActive = true
    let buttons = UIStackView(arrangedSubviews: [secondary, primary])
    buttons.axis = .horizontal
    buttons.spacing = 12
    buttons.distribution = .fillEqually

    let column = UIStackView(arrangedSubviews: [statusLabel, questionLabel, buttons])
    column.axis = .vertical
    column.spacing = 8
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

    public init(
        status: String,
        question: String,
        secondaryTitle: String,
        primaryTitle: String,
        isEnabled: Bool = true
    ) {
        self.status = status
        self.question = question
        self.secondaryTitle = secondaryTitle
        self.primaryTitle = primaryTitle
        self.enabled = isEnabled
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
                onSecondary: { [weak self] in self?.onSecondary?() },
                onPrimary: { [weak self] in self?.onPrimary?() }
            ),
            animated: animated
        )
    }
}
