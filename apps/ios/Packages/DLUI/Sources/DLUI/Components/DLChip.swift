import SwiftUI

public struct DLChip: View {
    public enum Style {
        case glass
        case bordered
    }

    private let title: String
    private let style: Style
    private let isEnabled: Bool
    private let action: () -> Void

    public init(
        _ title: String,
        style: Style = .bordered,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.style = style
        self.isEnabled = isEnabled
        self.action = action
    }

    public var body: some View {
        Group {
            switch style {
            case .glass:
                Button(title, action: action)
                    .buttonStyle(.glass)
                    .buttonBorderShape(.capsule)
            case .bordered:
                Button(title, action: action)
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
            }
        }
        .font(DLFont.body)
        .frame(minHeight: 44)
        .disabled(!isEnabled)
    }
}
