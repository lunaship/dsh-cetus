import DLCore
import DLModels
import DLNet
import DLUI
import QuickLook
import SwiftUI
import WebKit

enum ReviewSurface: String {
    case changes
    case diff
    case files
    case file
    case preview
    case previewEmpty
}

struct ReviewScreen: View {
    var surface: ReviewSurface
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = ReviewCopy(locale: locale)
        switch surface {
        case .changes:
            ChangesPage(files: Self.sampleFiles, turn: 3, canPrevious: true, canNext: false, copy: copy)
        case .diff:
            DiffPage(lines: Self.sampleDiff, copy: copy)
        case .files:
            FilesPage(path: "src/app", entries: Self.sampleEntries, copy: copy)
        case .file:
            FilePreviewPage(path: "src/app/Main.swift", text: Self.sampleText, copy: copy)
        case .preview:
            PreviewPage(previews: [PreviewInfo(previewId: "p1", label: "本机服务", port: 3000)], copy: copy)
        case .previewEmpty:
            PreviewPage(previews: [], copy: copy)
        }
    }

    static let sampleFiles = [
        ChangedFile(path: "src/app/Main.swift", display: "src/app/Main.swift", added: 12, deleted: 3),
        ChangedFile(path: "src/app/Login.kt", display: "src/app/Login.kt", added: 1, deleted: 8),
    ]

    static let sampleDiff: [DiffLine] = {
        var budget = intralineBudgetCells
        return diffLines(
            from: [
                DiffHunk(
                    oldStart: 10, oldLines: 1, newStart: 10, newLines: 1,
                    lines: ["-    val progress = Animatable(0f)", "+    val progress = Animatable(initialProgress)"])
            ],
            budget: &budget)
    }()

    static let sampleEntries = [
        TreeEntry(name: "src", type: .dir),
        TreeEntry(name: "Main.swift", type: .file, size: 2400),
    ]

    static let sampleText = "fun main() {\n    println(\"hi\")\n}\n"
}

struct ChangesPage: View {
    var files: [ChangedFile]
    var turn: Int
    var canPrevious: Bool
    var canNext: Bool
    var copy: ReviewCopy
    var onAsk: () -> Void = {}

    var body: some View {
        List {
            ForEach(Array(files.enumerated()), id: \.offset) { _, file in
                let parts = fileTitleParts(file.display ?? file.path ?? "")
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(parts.name).font(DLFont.mono(DLFont.body)).lineLimit(1)
                        if !parts.directory.isEmpty {
                            Text(parts.directory)
                                .font(DLFont.footnote)
                                .foregroundStyle(DLColor.secondaryLabel)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 8)
                    Text(copy.format(.added, file.added ?? 0)).foregroundStyle(DLColor.ok)
                    Text(copy.format(.deleted, file.deleted ?? 0)).foregroundStyle(DLColor.err)
                }
                .font(DLFont.mono(DLFont.footnote))
                .frame(minHeight: 44)
            }
        }
        .navigationTitle(copy.text(.changesTitle))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(copy.text(.previousTurn)) {}
                    .disabled(!canPrevious)
            }
            ToolbarItem(placement: .principal) {
                Text(copy.format(.turn, turn)).font(DLFont.footnote)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(copy.text(.nextTurn)) {}
                    .disabled(!canNext)
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button(copy.text(.askChanges), action: onAsk)
                .frame(maxWidth: .infinity, minHeight: 44)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(DLColor.background)
        }
    }
}

struct DiffPage: View {
    var lines: [DiffLine]
    var copy: ReviewCopy
    var onAsk: () -> Void = {}

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    lineText(line)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 2)
                        .background(lineBackground(line.kind))
                }
            }
        }
        .navigationTitle(copy.text(.diffTitle))
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            HStack {
                Button(copy.text(.previousHunk)) {}
                Button(copy.text(.nextHunk)) {}
                Spacer()
                Button(copy.text(.askFile), action: onAsk)
            }
            .frame(minHeight: 44)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(DLColor.background)
        }
    }

    private func lineBackground(_ kind: DiffLineKind) -> Color {
        switch kind {
        case .add: DLColor.ok.opacity(0.16)
        case .delete: DLColor.err.opacity(0.16)
        default: .clear
        }
    }

    private func lineText(_ line: DiffLine) -> Text {
        let mono = Font.system(.footnote, design: .monospaced)
        guard !line.emphasis.isEmpty else { return Text(line.text).font(mono) }
        let tint = line.kind == .delete ? DLColor.err : DLColor.ok
        var cursor = line.text.startIndex
        var out = Text("")
        for range in line.emphasis {
            let start = String.Index(utf16Offset: range.start, in: line.text)
            let end = String.Index(utf16Offset: range.end, in: line.text)
            if cursor < start {
                out = out + Text(String(line.text[cursor..<start])).font(mono)
            }
            out = out + Text(String(line.text[start..<end])).font(mono).bold().foregroundStyle(tint)
            cursor = end
        }
        if cursor < line.text.endIndex {
            out = out + Text(String(line.text[cursor...])).font(mono)
        }
        return out
    }
}

func breadcrumbLabel(_ crumb: String, copy: ReviewCopy) -> String {
    if crumb.isEmpty { return copy.text(.root) }
    return crumb.split(separator: "/").last.map(String.init) ?? crumb
}

struct FilesPage: View {
    var path: String
    var entries: [TreeEntry]
    var copy: ReviewCopy

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView(.horizontal) {
                HStack(spacing: 4) {
                    ForEach(breadcrumbPaths(path), id: \.self) { crumb in
                        Text(breadcrumbLabel(crumb, copy: copy))
                            .font(DLFont.footnote)
                            .foregroundStyle(crumb == path ? DLColor.label : DLColor.secondaryLabel)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            List(entries, id: \.name) { entry in
                HStack {
                    Image(systemName: entry.type == .dir ? "folder" : "doc")
                        .foregroundStyle(DLColor.secondaryLabel)
                    Text(entry.name ?? "")
                    Spacer()
                    if entry.type == .dir {
                        Image(systemName: "chevron.right").foregroundStyle(DLColor.tertiaryLabel)
                    }
                }
                .frame(minHeight: 44)
            }
        }
        .navigationTitle(copy.text(.filesTitle))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct FilePreviewPage: View {
    var path: String
    var text: String?
    var kind: FilePreviewKind = .text
    var fileURL: URL?
    var discarded = false
    var liveShare = false
    var copy: ReviewCopy
    var onQuote: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if discarded {
                Text(copy.text(.discarded))
                    .foregroundStyle(DLColor.err)
                    .padding(16)
            } else if let text {
                ScrollView {
                    Text(text)
                        .font(DLFont.mono(DLFont.footnote))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .textSelection(.enabled)
                }
            } else if kind == .quickLook, let fileURL {
                QuickLookPreview(url: fileURL)
            } else {
                Text(copy.text(.binaryFile))
                    .foregroundStyle(DLColor.secondaryLabel)
                    .padding(16)
            }
            Spacer(minLength: 0)
        }
        .background(DLColor.background)
        .navigationTitle(fileTitleParts(path).name)
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            HStack {
                Button(copy.text(.copyPath)) {}
                if liveShare {
                    ShareLink(item: path) { Text(copy.text(.shareFile)) }
                } else {
                    Button(copy.text(.shareFile)) {}
                }
                Spacer()
                Button(copy.text(.quote), action: onQuote)
            }
            .frame(minHeight: 44)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(DLColor.background)
        }
    }
}

struct PreviewPage: View {
    var previews: [PreviewInfo]
    var copy: ReviewCopy
    var loadsWeb = false
    var forward: (@Sendable (String) async -> PreviewHTTPResult)? = nil
    @State private var proxy: PreviewLocalProxy?
    @State private var boundPort = 0
    @State private var openURL: URL?

    var body: some View {
        let page = Group {
            if previews.isEmpty {
                DLEmptyState(
                    title: copy.text(.previewEmpty), systemImage: "rectangle.dashed",
                    message: copy.text(.previewEmptyDetail))
            } else {
                List(previews, id: \.previewId) { item in
                    row(item)
                }
            }
        }
        .navigationTitle(copy.text(.previewTitle))
        .navigationBarTitleDisplayMode(.inline)
        .background(DLColor.background)
        if loadsWeb {
            page
                .navigationDestination(item: $openURL) { url in
                    PreviewWebView(url: url, port: boundPort)
                }
                .task { await startProxy() }
                .onDisappear { Task { await proxy?.stop() } }
        } else {
            page
        }
    }

    @ViewBuilder private func row(_ item: PreviewInfo) -> some View {
        let label = VStack(alignment: .leading, spacing: 4) {
            Text(item.label ?? copy.text(.previewTitle)).font(DLFont.headline)
            Text("\(previewBindHost):\(item.port ?? 0)")
                .font(DLFont.mono(DLFont.footnote))
                .foregroundStyle(DLColor.secondaryLabel)
            Text(copy.text(.previewLocal))
                .font(DLFont.footnote)
                .foregroundStyle(DLColor.secondaryLabel)
        }
        .frame(minHeight: 44)
        if loadsWeb {
            Button {
                Task { await open(item) }
            } label: {
                label
            }
        } else {
            label
        }
    }

    private func startProxy() async {
        guard proxy == nil else { return }
        let started = PreviewLocalProxy { path in
            await forward?(path) ?? PreviewHTTPResult(status: 502, body: Data())
        }
        try? await started.start()
        boundPort = Int(await started.port)
        proxy = started
    }

    private func open(_ item: PreviewInfo) async {
        guard let id = item.previewId, let proxy else { return }
        openURL = URL(string: await proxy.localURL(previewID: id))
    }
}

struct PreviewWebView: UIViewRepresentable {
    var url: URL
    var port: Int

    func makeCoordinator() -> Coordinator { Coordinator(port: port) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let web = WKWebView(frame: .zero, configuration: configuration)
        web.navigationDelegate = context.coordinator
        web.load(URLRequest(url: url))
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        context.coordinator.port = port
    }

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.websiteDataStore.removeData(
            ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast
        ) {}
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var port: Int
        init(port: Int) { self.port = port }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction
        ) async -> WKNavigationActionPolicy {
            guard let url = navigationAction.request.url else { return .cancel }
            if previewNavigationAllowed(url, loopbackPort: port) { return .allow }
            await UIApplication.shared.open(url)
            return .cancel
        }
    }
}

struct QuickLookPreview: UIViewControllerRepresentable {
    var url: URL

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {
        context.coordinator.url = url
        controller.reloadData()
    }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        var url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

struct ChangesZoomSource: ViewModifier {
    var namespace: Namespace.ID?
    var id: Int

    func body(content: Content) -> some View {
        if let namespace {
            content.matchedTransitionSource(id: id, in: namespace)
        } else {
            content
        }
    }
}

enum ReviewText: String {
    case changesTitle
    case previousTurn
    case nextTurn
    case turn
    case askChanges
    case diffTitle
    case previousHunk
    case nextHunk
    case askFile
    case filesTitle
    case root
    case copyPath
    case shareFile
    case quote
    case binaryFile
    case discarded
    case previewTitle
    case previewEmpty
    case previewEmptyDetail
    case previewLocal
    case added
    case deleted

    var fallback: String {
        switch self {
        case .changesTitle: "Changes"
        case .previousTurn: "Previous"
        case .nextTurn: "Next"
        case .turn: "Turn %d"
        case .askChanges: "Ask about these changes"
        case .diffTitle: "Diff"
        case .previousHunk: "Previous"
        case .nextHunk: "Next"
        case .askFile: "Ask about this file"
        case .filesTitle: "Files"
        case .root: "Workspace"
        case .copyPath: "Copy path"
        case .shareFile: "Share"
        case .quote: "Quote in chat"
        case .binaryFile: "Binary file"
        case .discarded: "Check failed. The download was discarded."
        case .previewTitle: "Preview"
        case .previewEmpty: "No preview"
        case .previewEmptyDetail: "Approved ports from the computer show up here."
        case .previewLocal: "Opens only on this phone"
        case .added: "+%d"
        case .deleted: "−%d"
        }
    }
}

struct ReviewCopy {
    var locale: Locale

    func text(_ key: ReviewText) -> String {
        L10n.string("review.\(key.rawValue)", fallback: key.fallback, locale: locale)
    }

    func format(_ key: ReviewText, _ arguments: CVarArg...) -> String {
        String(format: text(key), locale: locale, arguments: arguments)
    }
}
