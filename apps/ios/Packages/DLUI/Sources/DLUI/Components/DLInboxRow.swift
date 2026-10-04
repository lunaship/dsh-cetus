import SwiftUI

/// 收件箱行的状态点。只用于「等你处理」（wait）和「进行中」（accent）。
public enum DLInboxDot: Hashable, Sendable {
    case wait
    case accent
}

public struct DLInboxRow: View {
    private enum Layout {
        case legacy
        case mail
    }

    private let layout: Layout
    private let title: String
    private let workspace: String
    private let time: String
    private let status: String?
    private let preview: String?
    private let command: String?
    private let dot: DLInboxDot?
    private let needle: String?
    private let isEnabled: Bool

    /// 阶段 2 的原有外观。组件截图继续走这条路径。
    public init(
        title: String,
        workspace: String,
        time: String,
        status: String? = nil,
        preview: String? = nil,
        isEnabled: Bool = true
    ) {
        layout = .legacy
        self.title = title
        self.workspace = workspace
        self.time = time
        self.status = status
        self.preview = preview
        command = nil
        dot = nil
        needle = nil
        self.isEnabled = isEnabled
    }

    /// 首页收件箱（PLAN 2.1）：脚注「工作区 · 状态」、正文中粗标题、最多两行预览、可选等宽命令。
    /// 列表内边距由 `List` 提供，这里不再加水平 padding。
    public init(
        mailMeta: String,
        title: String,
        time: String,
        preview: String? = nil,
        command: String? = nil,
        dot: DLInboxDot? = nil,
        needle: String? = nil,
        isEnabled: Bool = true
    ) {
        layout = .mail
        self.title = title
        workspace = mailMeta
        self.time = time
        status = nil
        self.preview = preview
        self.command = command
        self.dot = dot
        self.needle = needle
        self.isEnabled = isEnabled
    }

    public var body: some View {
        switch layout {
        case .legacy: legacyBody
        case .mail: mailBody
        }
    }

    private var legacyBody: some View {
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

    private var mailBody: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .center, spacing: 6) {
                if let dot {
                    Circle()
                        .fill(dot == .wait ? DLColor.wait : DLColor.accent)
                        .frame(width: 8, height: 8)
                        .accessibilityHidden(true)
                }
                Text(workspace)
                    .font(DLFont.footnote)
                    .foregroundStyle(isEnabled ? DLColor.secondaryLabel : DLColor.tertiaryLabel)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(time)
                    .font(DLFont.footnote)
                    .foregroundStyle(DLColor.tertiaryLabel)
                    .lineLimit(1)
            }
            rich(title, hit: DLColor.accent, base: isEnabled ? DLColor.label : DLColor.tertiaryLabel)
                .font(DLFont.body.weight(.medium))
                .lineLimit(2)
            if let preview {
                rich(preview, hit: DLColor.accent, base: isEnabled ? DLColor.secondaryLabel : DLColor.tertiaryLabel)
                    .font(DLFont.footnote)
                    .lineLimit(2)
            }
            if let command {
                Text(command)
                    .font(DLFont.mono(DLFont.footnote))
                    .foregroundStyle(isEnabled ? DLColor.label : DLColor.tertiaryLabel)
                    .lineLimit(1)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(DLColor.fill)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            }
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func rich(_ text: String, hit: Color, base: Color) -> Text {
        var attributed = AttributedString(text)
        attributed.foregroundColor = base
        guard isEnabled, let needle else { return Text(attributed) }
        let query = needle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return Text(attributed) }
        var search = attributed.startIndex
        while search < attributed.endIndex,
            let range = attributed[search..<attributed.endIndex].range(
                of: query, options: [.caseInsensitive, .diacriticInsensitive])
        {
            attributed[range].foregroundColor = hit
            search = range.upperBound
        }
        return Text(attributed)
    }
}
