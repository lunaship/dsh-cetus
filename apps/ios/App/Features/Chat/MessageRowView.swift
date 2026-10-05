import DLCore
import DLUI
import SwiftUI
import UIKit

struct MessageRowView: View {
    var row: TranscriptRow
    var chrome: MessageChrome

    var body: some View {
        switch row {
        case .user(let bubble):
            user(bubble)
        case .assistant(let block):
            fading(block.fade) { assistant(block) }
        case .process(let block):
            process(block)
        case .tail(let tail):
            turnTail(tail)
        case .notice(let block):
            notice(block)
        case .unconfirmed:
            Text(chrome.copy.text(.unconfirmed))
                .font(DLFont.footnote)
                .foregroundStyle(DLColor.secondaryLabel)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .accessibilityLabel(chrome.copy.text(.unconfirmed))
        }
    }

    private func user(_ bubble: UserBubble) -> some View {
        HStack {
            Spacer(minLength: 48)
            Text(bubble.text)
                .font(DLFont.body)
                .foregroundStyle(DLColor.label)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(uiColor: .secondarySystemFill), in: ConcentricRectangle())
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }

    private func assistant(_ block: AssistantBlock) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(parseMarkdown(block.markdown).enumerated()), id: \.offset) { _, block in
                markdown(block)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .contextMenu {
            if let onSelectText = chrome.onSelectText {
                Button(chrome.copy.text(.selectText)) { onSelectText(block.markdown) }
            }
        }
    }

    @ViewBuilder private func markdown(_ block: MarkdownBlock) -> some View {
        switch block {
        case .heading(let level, let runs):
            inline(runs).font(level <= 1 ? DLFont.title : (level == 2 ? DLFont.headline : DLFont.meta))
        case .paragraph(let runs):
            inline(runs).font(DLFont.body)
        case .list(let ordered, let items):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(ordered ? "\(index + 1)." : "•")
                            .font(DLFont.body)
                            .foregroundStyle(DLColor.secondaryLabel)
                        inline(item).font(DLFont.body)
                    }
                }
            }
        case .quote(let runs):
            HStack(alignment: .top, spacing: 8) {
                Rectangle().fill(DLColor.tertiaryLabel).frame(width: 3)
                inline(runs).font(DLFont.body).foregroundStyle(DLColor.secondaryLabel)
            }
        case .table(let header, let rows):
            ScrollView(.horizontal) {
                VStack(alignment: .leading, spacing: 4) {
                    tableRow(header, emphasis: true)
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        tableRow(row, emphasis: false)
                    }
                }
            }
        case .code(let language, let source):
            DLCodeBlock(
                source, copyTitle: chrome.copy.text(.copy), highlighted: highlighted(source, language: language))
        case .math(let source):
            webBlock(source, kind: .math, title: chrome.copy.text(.formula))
        case .mermaid(let source):
            webBlock(source, kind: .mermaid, title: chrome.copy.text(.diagram))
        case .image(let alt, let url):
            image(alt: alt, url: url)
        }
    }

    private func inline(_ runs: [InlineRun]) -> Text {
        var result = AttributedString()
        for run in runs {
            switch run {
            case .text(let value):
                result.append(AttributedString(value))
            case .code(let value):
                var piece = AttributedString(value)
                piece.font = DLFont.mono(DLFont.body)
                piece.foregroundColor = DLColor.accent
                result.append(piece)
            case .math(let value):
                var piece = AttributedString(value)
                piece.font = DLFont.mono(DLFont.footnote)
                result.append(piece)
            }
        }
        return Text(result)
    }

    private func tableRow(_ cells: [String], emphasis: Bool) -> some View {
        HStack(spacing: 12) {
            ForEach(Array(cells.enumerated()), id: \.offset) { _, cell in
                Text(cell)
                    .font(emphasis ? DLFont.headline : DLFont.mono(DLFont.footnote))
                    .frame(minWidth: 72, alignment: .leading)
            }
        }
    }

    private func webBlock(_ source: String, kind: LockedContentKind, title: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(DLFont.caption).foregroundStyle(DLColor.secondaryLabel)
            if chrome.allowsWeb {
                LockedContentView(source: source, kind: kind)
                    .frame(minHeight: 88)
            } else {
                Text(source)
                    .font(DLFont.mono(DLFont.footnote))
                    .lineLimit(6)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(DLColor.groupedBackground, in: ConcentricRectangle())
    }

    @ViewBuilder private func image(alt: String, url: String) -> some View {
        switch chrome.images[url] {
        case .loaded(let data):
            if let uiImage = UIImage(data: data) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 240)
                    .accessibilityLabel(alt.isEmpty ? chrome.copy.text(.loadImage) : alt)
            }
        case .blocked:
            Text(chrome.copy.text(.imageBlocked))
                .font(DLFont.footnote)
                .foregroundStyle(DLColor.secondaryLabel)
        case .failed:
            Text(chrome.copy.text(.imageFailed))
                .font(DLFont.footnote)
                .foregroundStyle(DLColor.secondaryLabel)
        default:
            Button(chrome.copy.text(.loadImage)) { chrome.onLoadImage(url) }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .frame(minHeight: 44)
                .accessibilityLabel(alt.isEmpty ? chrome.copy.text(.loadImage) : alt)
        }
    }

    private func process(_ block: ProcessBlock) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Button {
                chrome.onToggle(block.id)
            } label: {
                HStack(spacing: 8) {
                    if block.running {
                        if chrome.staticSnapshot {
                            Image(systemName: "ellipsis").font(DLFont.footnote).foregroundStyle(DLColor.secondaryLabel)
                        } else {
                            ProgressView().controlSize(.small)
                        }
                        Text(chrome.copy.text(.runningNow)).font(DLFont.headline)
                        if let command = block.command, !command.isEmpty {
                            Text(command)
                                .font(DLFont.mono(DLFont.footnote))
                                .foregroundStyle(DLColor.secondaryLabel)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    } else {
                        DLProcessLine(chrome.copy.activity(block.summary))
                    }
                    Image(systemName: block.expanded ? "chevron.down" : "chevron.right")
                        .font(DLFont.footnote)
                        .foregroundStyle(DLColor.tertiaryLabel)
                        .padding(.trailing, block.running ? 0 : 16)
                }
                .padding(.horizontal, block.running ? 16 : 0)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(block.expanded ? chrome.copy.text(.collapse) : chrome.copy.text(.expand))
            if block.expanded {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(block.steps) { step in
                        HStack(alignment: .top, spacing: 8) {
                            outcomeMark(step.outcome)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(stepTitle(step)).font(DLFont.footnote)
                                if let detail = step.detail, !detail.isEmpty {
                                    Text(detail)
                                        .font(DLFont.mono(DLFont.caption))
                                        .foregroundStyle(DLColor.secondaryLabel)
                                }
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }
                .padding(.leading, 20)
                .overlay(alignment: .leading) {
                    Rectangle().fill(DLColor.tertiaryLabel).frame(width: 1).padding(.vertical, 8)
                }
                .padding(.horizontal, 16)
            }
        }
    }

    @ViewBuilder private func outcomeMark(_ outcome: ProcessOutcome) -> some View {
        switch outcome {
        case .ok:
            Image(systemName: "checkmark").foregroundStyle(DLColor.ok).font(DLFont.caption)
        case .failed:
            Image(systemName: "xmark").foregroundStyle(DLColor.err).font(DLFont.caption)
        case .running:
            if chrome.staticSnapshot {
                Image(systemName: "ellipsis").font(DLFont.caption).foregroundStyle(DLColor.secondaryLabel)
            } else {
                ProgressView().controlSize(.small)
            }
        }
    }

    private func stepTitle(_ step: ProcessStep) -> String {
        switch step.kind {
        case .reasoning: chrome.copy.text(.reasoning)
        case .tool(let name): name.isEmpty ? chrome.copy.text(.process) : name
        case .result: chrome.copy.text(.result)
        }
    }

    private func turnTail(_ tail: TurnTail) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if tail.showsCard {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(chrome.copy.format(.filesChanged, tail.total)).font(DLFont.headline)
                        Spacer()
                        Text(chrome.copy.format(.added, tail.added)).foregroundStyle(DLColor.ok)
                        Text(chrome.copy.format(.deleted, tail.deleted)).foregroundStyle(DLColor.err)
                    }
                    .font(DLFont.mono(DLFont.footnote))
                    ForEach(tail.lines) { line in
                        HStack {
                            Text(line.path).font(DLFont.mono(DLFont.footnote)).lineLimit(1)
                            Spacer()
                            Text(chrome.copy.format(.added, line.added)).foregroundStyle(DLColor.ok)
                            Text(chrome.copy.format(.deleted, line.deleted)).foregroundStyle(DLColor.err)
                        }
                        .font(DLFont.mono(DLFont.caption))
                    }
                    Button(chrome.copy.text(.viewAll)) { chrome.onViewChanges(tail.changesSeq) }
                        .font(DLFont.headline)
                        .frame(minHeight: 44)
                        .modifier(ChangesZoomSource(namespace: chrome.changesNamespace, id: tail.changesSeq ?? 0))
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(DLColor.groupedBackground, in: ConcentricRectangle())
            }
            let meta = chrome.copy.meta(tail.meta)
            if !meta.isEmpty {
                Text(meta).font(DLFont.footnote).foregroundStyle(DLColor.secondaryLabel)
            }
            if !tail.copyText.isEmpty {
                HStack(spacing: 16) {
                    Button(chrome.copy.text(.copy)) { chrome.onCopy(tail.copyText) }
                    Button(chrome.copy.text(.regenerate)) { chrome.onRegenerate(tail.copyText) }
                    if chrome.staticSnapshot {
                        Button(chrome.copy.text(.share)) {}
                    } else {
                        ShareLink(item: tail.copyText) { Text(chrome.copy.text(.share)) }
                    }
                }
                .buttonStyle(.plain)
                .font(DLFont.body)
                .frame(minHeight: 44)
            }
            if tail.showsSuggestions {
                HStack(spacing: 8) {
                    DLChip(chrome.copy.text(.suggestContinue)) {
                        chrome.onSuggest(chrome.copy.text(.suggestContinue))
                    }
                    DLChip(chrome.copy.text(.suggestReview)) { chrome.onSuggest(chrome.copy.text(.suggestReview)) }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }

    @ViewBuilder private func notice(_ block: NoticeBlock) -> some View {
        let text = noticeText(block.notice)
        if !text.isEmpty {
            Text(text)
                .font(DLFont.footnote)
                .foregroundStyle(DLColor.secondaryLabel)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
        }
    }

    private func noticeText(_ notice: TranscriptNotice) -> String {
        switch notice {
        case .injection(let labels):
            chrome.copy.format(.injection, labels.joined(separator: ", "))
        case .goal(let round, let objective):
            chrome.copy.format(.goal, [round, objective].compactMap { $0 }.joined(separator: " · "))
        case .modelChanged(let text):
            text ?? chrome.copy.text(.modelChanged)
        case .approval(let text):
            text.isEmpty ? chrome.copy.text(.approval) : text
        case .compaction(let text):
            text.isEmpty ? chrome.copy.text(.compaction) : text
        case .files(let files):
            files.isEmpty ? chrome.copy.text(.produced) : files.joined(separator: "\n")
        case .todo(let done, let total):
            chrome.copy.format(.todo, done, total)
        case .plain(let text):
            text
        }
    }

    private func highlighted(_ code: String, language: String?) -> AttributedString {
        var result = AttributedString()
        for token in highlightCode(code, language: language) {
            var piece = AttributedString(token.text)
            piece.foregroundColor = codeColor(token.kind)
            result.append(piece)
        }
        return result
    }

    private func codeColor(_ kind: CodeTokenKind) -> Color {
        switch kind {
        case .keyword: Color.accentColor
        case .string: DLColor.ok
        case .comment: DLColor.tertiaryLabel
        case .number: DLColor.wait
        case .function, .plain: DLColor.label
        }
    }

    private func fading<Content: View>(_ fade: Bool, @ViewBuilder content: () -> Content) -> some View {
        content().modifier(FadeIn(enabled: fade && !chrome.reduceMotion))
    }
}

private struct FadeIn: ViewModifier {
    var enabled: Bool
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(!enabled || shown ? 1 : 0)
            .onAppear {
                guard enabled else { return }
                withAnimation(.easeOut(duration: 0.18)) { shown = true }
            }
    }
}
