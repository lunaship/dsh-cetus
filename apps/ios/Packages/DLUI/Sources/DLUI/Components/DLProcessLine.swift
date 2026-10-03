import SwiftUI

public struct DLProcessLine: View {
    private let text: String
    private let isEnabled: Bool

    public init(_ text: String, isEnabled: Bool = true) {
        self.text = text
        self.isEnabled = isEnabled
    }

    public var body: some View {
        Text(text)
            .font(DLFont.meta)
            .foregroundStyle(isEnabled ? DLColor.secondaryLabel : DLColor.tertiaryLabel)
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
    }
}
