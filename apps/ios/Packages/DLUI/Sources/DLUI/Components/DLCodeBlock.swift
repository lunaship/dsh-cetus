import SwiftUI
import UIKit

public struct DLCodeBlock: View {
    private let code: String
    private let highlighted: AttributedString?
    private let isEnabled: Bool
    private let copyTitle: String

    public init(
        _ code: String, isEnabled: Bool = true, copyTitle: String = "Copy", highlighted: AttributedString? = nil
    ) {
        self.code = code
        self.highlighted = highlighted
        self.isEnabled = isEnabled
        self.copyTitle = copyTitle
    }

    public var body: some View {
        HStack(alignment: .top, spacing: 8) {
            ScrollView(.horizontal) {
                codeText
                    .font(DLFont.mono(DLFont.body))
                    .fixedSize(horizontal: true, vertical: true)
            }
            .fixedSize(horizontal: false, vertical: true)
            Button(copyTitle, action: copy)
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .font(DLFont.caption)
                .frame(minHeight: 44)
                .disabled(!isEnabled)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(DLColor.groupedBackground)
    }

    private var codeText: some View {
        if let highlighted, isEnabled {
            Text(highlighted)
        } else {
            Text(code)
                .foregroundStyle(isEnabled ? DLColor.label : DLColor.tertiaryLabel)
        }
    }

    private func copy() {
        UIPasteboard.general.string = code
    }
}
