import SwiftUI

public struct DLBanner: View {
    private let text: String
    private let isEnabled: Bool

    public init(_ text: String, isEnabled: Bool = true) {
        self.text = text
        self.isEnabled = isEnabled
    }

    public var body: some View {
        Text(text)
            .font(DLFont.body)
            .foregroundStyle(isEnabled ? DLColor.label : DLColor.tertiaryLabel)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(DLColor.groupedBackground)
    }
}
