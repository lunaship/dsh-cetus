import UIKit

final class DLGlassBar: UIView {
    private let effectView: UIVisualEffectView

    override init(frame: CGRect) {
        let glass = UIGlassEffect(style: .regular)
        glass.isInteractive = false
        effectView = UIVisualEffectView(effect: glass)
        super.init(frame: frame)
        effectView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(effectView)
        NSLayoutConstraint.activate([
            effectView.leadingAnchor.constraint(equalTo: leadingAnchor),
            effectView.trailingAnchor.constraint(equalTo: trailingAnchor),
            effectView.topAnchor.constraint(equalTo: topAnchor),
            effectView.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    required init?(coder: NSCoder) {
        return nil
    }

    func install(_ content: UIView, animated: Bool) {
        let holder = effectView.contentView
        let previous = holder.subviews
        content.translatesAutoresizingMaskIntoConstraints = false
        let apply = {
            for view in previous {
                view.removeFromSuperview()
            }
            holder.addSubview(content)
            NSLayoutConstraint.activate([
                content.leadingAnchor.constraint(equalTo: holder.leadingAnchor, constant: 12),
                content.trailingAnchor.constraint(equalTo: holder.trailingAnchor, constant: -12),
                content.topAnchor.constraint(equalTo: holder.topAnchor, constant: 12),
                content.bottomAnchor.constraint(equalTo: holder.bottomAnchor, constant: -12),
            ])
            self.layoutIfNeeded()
        }
        if animated, !UIAccessibility.isReduceMotionEnabled {
            UIView.animate(
                withDuration: 0.35,
                delay: 0,
                usingSpringWithDamping: 0.86,
                initialSpringVelocity: 0.2,
                animations: apply
            )
        } else {
            apply()
        }
    }
}

@MainActor
func dlBarButton(
    title: String,
    prominent: Bool,
    enabled: Bool,
    action: @escaping () -> Void
) -> UIButton {
    var config = prominent ? UIButton.Configuration.borderedProminent() : UIButton.Configuration.bordered()
    config.title = title
    config.cornerStyle = .capsule
    config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12)
    config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
        var outgoing = incoming
        outgoing.font = UIFont.preferredFont(forTextStyle: .body)
        return outgoing
    }
    let button = UIButton(configuration: config, primaryAction: UIAction { _ in action() })
    button.isEnabled = enabled
    if prominent {
        button.tintColor = DLUIKitColor.brandFill
    }
    button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
    return button
}
