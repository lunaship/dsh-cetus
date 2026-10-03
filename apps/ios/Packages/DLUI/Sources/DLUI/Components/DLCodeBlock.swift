import SwiftUI
import UIKit

public struct DLCodeBlock: View {
    private let code: String
    private let isEnabled: Bool
    private let copyTitle: String

    public init(_ code: String, isEnabled: Bool = true, copyTitle: String = "Copy") {
        self.code = code
        self.isEnabled = isEnabled
        self.copyTitle = copyTitle
    }

    public var body: some View {
        HStack(alignment: .top, spacing: 8) {
            ScrollView(.horizontal) {
                Text(code)
                    .font(DLFont.mono(DLFont.body))
                    .foregroundStyle(isEnabled ? DLColor.label : DLColor.tertiaryLabel)
                    .frame(maxHeight: .infinity, alignment: .topLeading)
            }
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

    private func copy() {
        UIPasteboard.general.string = code
    }
}
