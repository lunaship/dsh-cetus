import SwiftUI

public struct DLStatusSlot: View {
    private let title: String
    private let meta: String?
    private let isEnabled: Bool

    public init(title: String, meta: String? = nil, isEnabled: Bool = true) {
        self.title = title
        self.meta = meta
        self.isEnabled = isEnabled
    }

    public var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(DLFont.headline)
                .foregroundStyle(isEnabled ? DLColor.label : DLColor.tertiaryLabel)
                .lineLimit(1)
            Spacer(minLength: 8)
            if let meta {
                Text(meta)
                    .font(DLFont.caption)
                    .foregroundStyle(DLColor.secondaryLabel)
                    .lineLimit(1)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(DLColor.groupedBackground)
        .accessibilityElement(children: .combine)
    }
}
