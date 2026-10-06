import SwiftUI

public struct DLStatusSlot: View {
    private let title: String
    private let meta: String?
    private let isEnabled: Bool
    private var systemImage: String?
    private var isExpanded = false
    private var toggleLabel = ""
    private var onToggle: (() -> Void)?
    private var expandedContent: AnyView?
    @Environment(\.dynamicTypeSize) private var typeSize

    public init(title: String, meta: String? = nil, isEnabled: Bool = true) {
        self.title = title
        self.meta = meta
        self.isEnabled = isEnabled
    }

    /// Optional disclosure keeps the stage 2 summary-only API and its appearance intact.
    public init<Content: View>(
        title: String, meta: String? = nil, systemImage: String,
        isExpanded: Bool, toggleLabel: String, onToggle: (() -> Void)?,
        @ViewBuilder expandedContent: () -> Content
    ) {
        self.init(title: title, meta: meta)
        self.systemImage = systemImage
        self.isExpanded = isExpanded
        self.toggleLabel = toggleLabel
        self.onToggle = onToggle
        self.expandedContent = AnyView(expandedContent())
    }

    public var body: some View {
        if systemImage != nil {
            VStack(alignment: .leading, spacing: 8) {
                if isExpanded, let expandedContent {
                    expandedContent
                        .padding(12)
                        .frame(maxWidth: .infinity, maxHeight: 280, alignment: .topLeading)
                        .background(
                            DLColor.groupedBackground,
                            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
                        )
                }
                Group {
                    if let onToggle {
                        Button(action: onToggle) { summary }
                            .buttonStyle(.plain)
                            .accessibilityHint(toggleLabel)
                    } else {
                        summary
                    }
                }
                .padding(.horizontal, 12)
                .frame(minHeight: 36)
                .background(DLColor.groupedBackground, in: Capsule(style: .continuous))
                .fixedSize(horizontal: !isExpanded && !typeSize.isAccessibilitySize, vertical: false)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        } else {
            summary.background(DLColor.groupedBackground)
        }
    }

    private var summary: some View {
        HStack(spacing: 8) {
            if let systemImage {
                Image(systemName: systemImage)
                    .foregroundStyle(DLColor.secondaryLabel)
                    .accessibilityHidden(true)
            }
            Text(title)
                .font(DLFont.headline)
                .foregroundStyle(isEnabled ? DLColor.label : DLColor.tertiaryLabel)
                .lineLimit(systemImage != nil && (isExpanded || typeSize.isAccessibilitySize) ? nil : 1)
                .fixedSize(
                    horizontal: false,
                    vertical: systemImage != nil && (isExpanded || typeSize.isAccessibilitySize)
                )
            if systemImage == nil || isExpanded || typeSize.isAccessibilitySize {
                Spacer(minLength: 8)
            }
            if let meta {
                Text(meta)
                    .font(DLFont.caption)
                    .foregroundStyle(DLColor.secondaryLabel)
                    .lineLimit(1)
            }
            if onToggle != nil {
                Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                    .foregroundStyle(DLColor.secondaryLabel)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, systemImage == nil ? 12 : 0)
        .padding(.vertical, systemImage == nil ? 12 : 6)
        .frame(
            maxWidth: systemImage == nil || isExpanded || typeSize.isAccessibilitySize ? .infinity : nil,
            minHeight: systemImage == nil ? nil : 36,
            alignment: .leading
        )
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
