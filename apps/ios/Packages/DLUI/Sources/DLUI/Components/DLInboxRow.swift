import SwiftUI

public struct DLInboxRow: View {
    private let title: String
    private let workspace: String
    private let time: String
    private let status: String?
    private let preview: String?
    private let isEnabled: Bool

    public init(
        title: String,
        workspace: String,
        time: String,
        status: String? = nil,
        preview: String? = nil,
        isEnabled: Bool = true
    ) {
        self.title = title
        self.workspace = workspace
        self.time = time
        self.status = status
        self.preview = preview
        self.isEnabled = isEnabled
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                if let status {
                    Text(status)
                        .font(DLFont.caption)
                        .foregroundStyle(isEnabled ? DLColor.wait : DLColor.tertiaryLabel)
                        .lineLimit(1)
                }
                Text(workspace)
                    .font(DLFont.meta)
                    .foregroundStyle(isEnabled ? DLColor.secondaryLabel : DLColor.tertiaryLabel)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(time)
                    .font(DLFont.caption)
                    .foregroundStyle(DLColor.tertiaryLabel)
                    .lineLimit(1)
            }
            Text(title)
                .font(DLFont.headline)
                .foregroundStyle(isEnabled ? DLColor.label : DLColor.tertiaryLabel)
                .lineLimit(2)
            if let preview {
                Text(preview)
                    .font(DLFont.meta)
                    .foregroundStyle(isEnabled ? DLColor.secondaryLabel : DLColor.tertiaryLabel)
                    .lineLimit(2)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
