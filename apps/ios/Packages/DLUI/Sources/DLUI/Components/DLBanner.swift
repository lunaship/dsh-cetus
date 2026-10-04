import SwiftUI

public struct DLBanner: View {
    private let text: String
    private let isEnabled: Bool
    private let systemImage: String?
    private let iconIsError: Bool

    public init(_ text: String, isEnabled: Bool = true, systemImage: String? = nil, iconIsError: Bool = false) {
        self.text = text
        self.isEnabled = isEnabled
        self.systemImage = systemImage
        self.iconIsError = iconIsError
    }

    public var body: some View {
        if let systemImage {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: systemImage)
                    .font(DLFont.body)
                    .foregroundStyle(iconIsError ? DLColor.err : DLColor.secondaryLabel)
                    .accessibilityHidden(true)
                Text(text)
                    .font(DLFont.body)
                    .foregroundStyle(isEnabled ? DLColor.label : DLColor.tertiaryLabel)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(DLColor.groupedBackground)
        } else {
            Text(text)
                .font(DLFont.body)
                .foregroundStyle(isEnabled ? DLColor.label : DLColor.tertiaryLabel)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(DLColor.groupedBackground)
        }
    }
}
