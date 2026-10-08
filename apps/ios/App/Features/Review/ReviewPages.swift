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
    // MARK: - C08 真实数据与动作
    var loading = false
    var error = false
    var onRetry: () -> Void = {}
    /// 点文件行 → 拉取该文件对比（下标）。
    var onOpenDiff: (Int) -> Void = { _ in }
    /// 已拉取的单文件对比（index → diff）。
    var diff: [Int: ChangesDiffResponse] = [:]

    var body: some View {
        List {
            if files.isEmpty && error {
                DLEmptyState(title: copy.text(.changesUnavailable), systemImage: "exclamationmark.triangle")
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                if loading {
                    Button(copy.text(.changesRetry), action: onRetry)
                }
            } else {
                ForEach(Array(files.enumerated()), id: \.offset) { index, file in
                    fileRow(index: index, file: file)
                }
            }
        }
        .navigationTitle(copy.text(.changesTitle))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // C08：上/下一轮按钮在没有多轮数据时不显示（不画死按钮）。
            // 当前模型只持有最新一轮 changes，轮次切换需按 seq 分页拉取，
            // 属 C09 范围，此处不占位。
            ToolbarItem(placement: .principal) {
                Text(copy.format(.turn, max(1, turn))).font(DLFont.footnote)
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

    private func fileRow(index: Int, file: ChangedFile) -> some View {
        let parts = fileTitleParts(file.display ?? file.path ?? "")
        let known = file.binary == true || file.oversized == true
        let diffLines = diff[index].flatMap { linesFromDiff($0) }
        return VStack(alignment: .leading, spacing: 2) {
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
                if known {
                    Text(copy.text(.binaryFile))
                        .font(DLFont.mono(DLFont.footnote))
                        .foregroundStyle(DLColor.secondaryLabel)
                } else {
                    Text(copy.format(.added, file.added ?? 0)).foregroundStyle(DLColor.ok)
                    Text(copy.format(.deleted, file.deleted ?? 0)).foregroundStyle(DLColor.err)
                }
                if !known {
                    Button(copy.text(.diffOpen)) { onOpenDiff(index) }
                        .font(DLFont.mono(DLFont.footnote))
                }
            }
            .font(DLFont.mono(DLFont.footnote))
            if let diffLines, !diffLines.isEmpty {
                ForEach(Array(diffLines.enumerated()), id: \.offset) { _, line in
                    Text(line.text)
                        .font(DLFont.mono(DLFont.footnote))
                        .foregroundStyle(
                            line.kind == .add
                                ? DLColor.ok : (line.kind == .delete ? DLColor.err : DLColor.secondaryLabel)
                        )
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, 12)
                }
            }
        }
        .frame(minHeight: 44, alignment: .top)
    }

    /// 把 ChangesDiffResponse 的 hunk 转成 DiffLine 列表。
    private func linesFromDiff(_ response: ChangesDiffResponse) -> [DiffLine]? {
        guard response.kind == .text, let hunks = response.hunks, !hunks.isEmpty else { return nil }
        var budget = intralineBudgetCells
        return diffLines(from: hunks, budget: &budget)
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
                // C08：diff 按 hunk 展示（C09 的 hunk 锚点导航属后续），
                // 不再有"上/下 hunk"死按钮；保留"就这个文件提问"。
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
    /// 当前层条目。C09：真实数据从 ConversationModel.fileEntries 取；
    /// 加载/错误时用 loading/error 表示，不用空数组冒充"真的空"。
    var entries: [TreeEntry]?
    var copy: ReviewCopy
    // MARK: - C09 懒加载 / 错误态 / 动作
    var loading = false
    var error = false
    var truncated = false
    var unsupported = false
    var openingFile = false
    /// 面包屑点某层 → 回到那层（父路径）。
    var onBreadcrumb: (String) -> Void = { _ in }
    /// 点目录 → 进入。
    var onEnterDir: (String) -> Void = { _ in }
    /// 点文件 → 打开预览。
    var onOpenFile: (String) -> Void = { _ in }
    /// 目录/错误重试。
    var onRetry: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView(.horizontal) {
                HStack(spacing: 4) {
                    ForEach(breadcrumbPaths(path), id: \.self) { crumb in
                        Button(breadcrumbLabel(crumb, copy: copy)) {
                            onBreadcrumb(crumb)
                        }
                        .font(DLFont.footnote)
                        .foregroundStyle(crumb == path ? DLColor.label : DLColor.secondaryLabel)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            if unsupported {
                DLEmptyState(title: copy.text(.changesUnavailable), systemImage: "folder.badge.questionmark")
            } else if error {
                DLEmptyState(title: copy.text(.filesError), systemImage: "exclamationmark.triangle")
            } else if let entries, entries.isEmpty, !loading {
                DLEmptyState(title: copy.text(.filesEmptyDir), systemImage: "folder")
            } else if let entries {
                List(entries, id: \.name) { entry in
                    entryRow(entry)
                }
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if openingFile { ProgressView(copy.text(.filesLoading)).padding(16) }
            if truncated {
                Text(copy.text(.filesTruncated))
                    .font(DLFont.footnote)
                    .foregroundStyle(DLColor.secondaryLabel)
                    .padding(.horizontal, 16)
            }
            if loading {
                Text(copy.text(.filesLoading))
                    .font(DLFont.footnote)
                    .foregroundStyle(DLColor.secondaryLabel)
                    .padding(.horizontal, 16)
            }
            if error {
                Button(copy.text(.filesRetry), action: onRetry)
                    .padding(.horizontal, 16)
            }
        }
        .navigationTitle(copy.text(.filesTitle))
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder private func entryRow(_ entry: TreeEntry) -> some View {
        let outside = entry.outside == true
        let name = entry.name ?? ""
        let isDir = entry.type == .dir
        HStack {
            Image(systemName: isDir ? "folder" : (outside ? "link.badge.exclamationmark" : "doc"))
                .foregroundStyle(DLColor.secondaryLabel)
            Text(name)
            Spacer()
            if isDir {
                Image(systemName: "chevron.right").foregroundStyle(DLColor.tertiaryLabel)
            }
            if outside {
                Text(copy.text(.filesOutside))
                    .font(DLFont.footnote)
                    .foregroundStyle(DLColor.tertiaryLabel)
            }
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .onTapGesture {
            if outside { return }  // 外部符号链接不可进入 / 不可打开
            if isDir {
                onEnterDir(childPath(path, name))
            } else {
                onOpenFile(childPath(path, name))
            }
        }
        .disabled(outside || openingFile)
        .accessibilityHint(outside ? copy.text(.filesOutsideHint) : "")
    }

    private func childPath(_ parent: String, _ child: String) -> String {
        parent.isEmpty ? child : parent + "/" + child
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
    // MARK: - C09 真实动作
    /// "复制路径"写系统剪贴板并反馈；nil = 无剪贴板环境（截图路径）→ 不显示。
    var onCopyPath: (() -> Void)? = nil
    /// "引用"加入当前会话草稿（无会话上下文时不显示）。
    var showQuote = true

    @State private var copiedPath = false

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
                if let onCopyPath {
                    Button {
                        onCopyPath()
                        copiedPath = true
                    } label: {
                        Text(copiedPath ? copy.text(.copied) : copy.text(.copyPath))
                    }
                    .task(id: copiedPath) {
                        guard copiedPath else { return }
                        try? await Task.sleep(for: .seconds(1.5))
                        guard !Task.isCancelled else { return }
                        copiedPath = false
                    }
                }
                if liveShare, let fileURL {
                    ShareLink(item: fileURL) { Text(copy.text(.shareFile)) }
                }
                Spacer()
                if showQuote {
                    Button(copy.text(.quote), action: onQuote)
                }
            }
            .frame(minHeight: 44)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(DLColor.background)
        }
    }
}

struct PreviewPage: View {
    /// C09：nil = 尚未拉取 / 拉取失败（看 error）。已批准预览；检测到的端口是另一类，
    /// 不能在这里当"可访问"显示。
    var previews: [PreviewInfo]?
    var copy: ReviewCopy
    var loadsWeb = false
    var forward: (@Sendable (String) async -> PreviewHTTPResult)? = nil
    // MARK: - C09 状态
    var loading = false
    var error = false
    /// 检测到的端口（不可批准，仅提示"去电脑批准"）。
    var detectedPorts: [Int] = []
    /// 重新拉取。
    var onRetry: () -> Void = {}
    var refreshApproved: (@Sendable () async throws -> [PreviewInfo])? = nil
    @State private var proxy: PreviewLocalProxy?
    @State private var openFailed = false
    @State private var boundPort = 0
    @State private var openURL: URL?

    var body: some View {
        let page = Group {
            if error {
                DLEmptyState(
                    title: copy.text(.filesError), systemImage: "exclamationmark.triangle")
                if !detectedPorts.isEmpty {
                    detectedHint
                }
            } else if let previews, previews.isEmpty, !loading {
                DLEmptyState(
                    title: copy.text(.previewEmpty), systemImage: "rectangle.dashed",
                    message: copy.text(.previewEmptyDetail))
                if !detectedPorts.isEmpty {
                    detectedHint
                }
            } else if let previews {
                List(previews, id: \.previewId) { item in
                    row(item)
                }
                if !detectedPorts.isEmpty {
                    detectedHint
                }
            } else {
                Text(copy.text(.filesLoading))
                    .font(DLFont.footnote)
                    .foregroundStyle(DLColor.secondaryLabel)
                    .padding(16)
            }
            if error {
                Button(copy.text(.filesRetry), action: onRetry)
                    .padding(.horizontal, 16)
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
                .alert(copy.text(.previewEmptyDetail), isPresented: $openFailed) {
                    Button(copy.text(.filesRetry)) { onRetry() }
                }
                .onDisappear {
                    // Pushing the web view keeps this proxy alive; stop only when leaving its navigation.
                    guard openURL == nil else { return }
                    let previous = proxy
                    proxy = nil
                    Task { await previous?.stop() }
                }
                .onChange(of: openURL) { _, url in
                    if url == nil {
                        let previous = proxy
                        proxy = nil
                        Task { await previous?.stop() }
                    }
                }
        } else {
            page
        }
    }

    /// C09 要求 2：检测到端口与已批准预览分开显示；手机不能批准端口。
    @ViewBuilder private var detectedHint: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(copy.text(.previewDetected))
                .font(DLFont.footnote)
                .foregroundStyle(DLColor.secondaryLabel)
            Text(detectedPorts.map(String.init).joined(separator: ", "))
                .font(DLFont.mono(DLFont.footnote))
                .foregroundStyle(DLColor.tertiaryLabel)
            Text(copy.text(.previewNeedsApproval))
                .font(DLFont.footnote)
                .foregroundStyle(DLColor.secondaryLabel)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
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
        guard let id = item.previewId else { return }
        do {
            if let refreshApproved {
                let approved = try await refreshApproved()
                guard approved.contains(where: { $0.previewId == id }) else {
                    openFailed = true
                    onRetry()
                    return
                }
            }
            await startProxy()
            guard let proxy, boundPort > 0 else {
                openFailed = true
                return
            }
            openURL = URL(string: await proxy.localURL(previewID: id))
        } catch { openFailed = true }
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
    case copied
    case shareFile
    case quote
    case binaryFile
    case discarded
    // C08：真实数据缺失 / 重试 / 打开对比
    case changesUnavailable
    case changesRetry
    case diffOpen
    // C09 文件 / 预览 真实数据
    case filesError
    case filesEmptyDir
    case filesTruncated
    case filesLoading
    case filesRetry
    case filesOutside
    case filesOutsideHint
    case previewNeedsApproval
    case previewDetected
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
        case .copied: "Copied"
        case .shareFile: "Share"
        case .quote: "Quote in chat"
        case .binaryFile: "Binary file"
        case .discarded: "Check failed. The download was discarded."
        case .changesUnavailable: "Changes unavailable"
        case .changesRetry: "Retry"
        case .diffOpen: "Diff"
        case .filesError: "Couldn't load directory"
        case .filesEmptyDir: "Empty directory"
        case .filesTruncated: "Showing first entries"
        case .filesLoading: "Loading…"
        case .filesRetry: "Retry"
        case .filesOutside: "Outside workspace"
        case .filesOutsideHint: "Symbolic link points outside the workspace"
        case .previewNeedsApproval: "Approve this port on your computer first"
        case .previewDetected: "Detected — not yet approved"
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
