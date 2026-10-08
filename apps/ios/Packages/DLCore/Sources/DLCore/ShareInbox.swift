import Foundation

/// I7.2 / 8.3. The share extension writes one item; the app only prefills a composer.
public enum ShareAppGroup {
    public static let identifier = "group.dev.deeplinks.ios"
    /// 生成端仍用 `deeplinks`（兼容期不改生成端，见 N03.4）。
    public static let scheme = "deeplinks"
    /// 解析端接受的 scheme：`deeplinks` 与 `cetus` 并存（N03.4，方案第 702 行）。
    ///
    /// 之所以"生成用旧的、解析收新的"：老版本的配对二维码 / 通知 / 已发出的
    /// 深链都写着 `deeplinks`，改生成端会让它们全部失效；而新用户手输
    /// `cetus://` 也该能用。两边都收 = 兼容期零破坏。
    public static let acceptedSchemes: Set<String> = ["deeplinks", "cetus"]
    /// Decoded image bytes. Base64 growth is checked again by classifyPromptAttachment.
    public static let imageByteLimit = 3 * 1024 * 1024
    public static let recentLimit = 6
    public static let textLimit = 100_000
    /// 单次分享最多接受多少张图片（C13 要求 3：数量限制明确）。
    public static let imageCountLimit = 3
    /// 单次分享携带的图片总字节上限，避免多张图叠加后超出合成上限。
    public static let imageTotalByteLimit = 6 * 1024 * 1024
    /// 允许的图片 UTI（C13 要求 3：格式限制明确）。
    /// 只放行系统相册/截图的常见格式；不解码、不联网。
    public static let allowedImageTypeIdentifiers: Set<String> = [
        "public.png",
        "public.jpeg",
        "public.heic",
        "public.heif",
        "public.tiff",
        "com.compuserve.gif",
        "public.webp",
    ]
}

/// security-scoped 资源访问的抽象（C13 要求 3）。
///
/// 生产实现走 `URL.startAccessingSecurityScopedResource()`；测试用假实现，
/// 以便断言「开启与释放配平」「未开启时不释放」等语义，而不必真的构造跨容器 URL。
///
/// 只依赖 Foundation，因此 `ShareInbox.swift` 仍可被 Share 扩展独立编译。
public struct ShareSecurityScope: Sendable {
    /// 开启访问，返回是否**真的**开启成功。
    /// 返回 false 时系统未计入访问计数，调用方**不得**再调用 stop。
    public var startAccessing: @Sendable (URL) -> Bool
    public var stopAccessing: @Sendable (URL) -> Void

    public init(
        startAccessing: @escaping @Sendable (URL) -> Bool,
        stopAccessing: @escaping @Sendable (URL) -> Void
    ) {
        self.startAccessing = startAccessing
        self.stopAccessing = stopAccessing
    }

    /// 生产实现：直接映射到 Foundation 的 security-scoped API。
    public static let live = ShareSecurityScope(
        startAccessing: { $0.startAccessingSecurityScopedResource() },
        stopAccessing: { $0.stopAccessingSecurityScopedResource() }
    )
}

public enum ShareInboxError: Error, Equatable, Sendable {
    case empty
    case tooLarge
    case unsafePath
    case unreadable
    /// 超过单次分享的图片数量上限。
    case tooManyImages
    /// 图片类型不在白名单内。
    case unsupportedType
    /// security-scoped 资源在复制完成前已失效。
    case attachmentUnavailable
}

extension ShareInboxError: LocalizedError {
    /// 面向用户的说明。文案使用产品名 cetus（命名合同 §1.1，全小写）。
    public var errorDescription: String? {
        switch self {
        case .empty:
            return String(localized: "分享内容为空，没有可带入的内容。")
        case .tooLarge:
            return String(localized: "图片过大，无法带入 cetus。请选择小于 3 MB 的图片。")
        case .tooManyImages:
            return String(localized: "一次最多带入 3 张图片。")
        case .unsupportedType:
            return String(localized: "暂不支持这种文件格式。请分享图片或文本。")
        case .attachmentUnavailable:
            return String(localized: "文件已不可用，请重新分享。")
        case .unsafePath, .unreadable:
            return String(localized: "无法读取分享内容，请重新分享。")
        }
    }
}

public struct ShareRecentSession: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var detail: String
    public var updatedAt: Int

    public init(id: String, title: String, detail: String, updatedAt: Int) {
        self.id = id
        self.title = title
        self.detail = detail
        self.updatedAt = updatedAt
    }
}

public struct ShareInboxRecord: Codable, Equatable, Sendable, Identifiable {
    public enum Kind: String, Codable, Equatable, Sendable {
        case text
        case image
    }

    public var id: String
    public var kind: Kind
    public var text: String
    public var imageFilename: String?
    public var createdAt: Date

    public init(
        id: String, kind: Kind, text: String, imageFilename: String? = nil, createdAt: Date
    ) {
        self.id = id
        self.kind = kind
        self.text = text
        self.imageFilename = imageFilename
        self.createdAt = createdAt
    }
}

public enum ShareTarget: Equatable, Sendable {
    case newTask
    case session(String)
}

/// What the chosen composer receives. Nothing here is transmitted.
public struct SharePrefill: Equatable, Sendable {
    public var target: ShareTarget
    public var text: String
    public var images: [PromptImage]

    public init(target: ShareTarget, text: String, images: [PromptImage] = []) {
        self.target = target
        self.text = text
        self.images = images
    }
}

public enum ShareInbox {
    public static func makeID() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    /// 生成分享链接。**固定用 `deeplinks`**（N03.4：兼容期不改生成端）。
    public static func openURL(id: String) -> URL? {
        guard isSafeID(id) else { return nil }
        return URL(string: "\(ShareAppGroup.scheme)://share/\(id)")
    }

    /// 从深链解析分享 id。**同时接受 `deeplinks://` 与 `cetus://`**（N03.4）。
    ///
    /// - scheme 比较**大小写不敏感**：iOS 会按注册原样回传，但用户手输或
    ///   第三方拼链接时大小写不可控（`Cetus://`、`DeepLinks://`），统一归一化。
    /// - host 同样大小写不敏感（`SHARE`）。
    /// - 其余 scheme 一律拒绝：不接受 `https://`、`file://` 等。
    public static func id(from url: URL) -> String? {
        guard let rawScheme = url.scheme?.lowercased(),
            ShareAppGroup.acceptedSchemes.contains(rawScheme)
        else { return nil }
        guard url.host?.lowercased() == "share" else { return nil }
        let id = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard isSafeID(id), !id.contains("/") else { return nil }
        return id
    }

    public static func textRecord(text: String, id: String = makeID(), now: Date = Date())
        -> Result<ShareInboxRecord, ShareInboxError>
    {
        let trimmed = String(text.prefix(ShareAppGroup.textLimit))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, isSafeID(id) else { return .failure(.empty) }
        return .success(ShareInboxRecord(id: id, kind: .text, text: trimmed, createdAt: now))
    }

    public static func imageRecord(
        bytes: Data, filename: String? = nil, text: String = "", id: String = makeID(), now: Date = Date()
    ) -> Result<ShareInboxRecord, ShareInboxError> {
        guard isSafeID(id) else { return .failure(.empty) }
        guard !bytes.isEmpty, bytes.count <= ShareAppGroup.imageByteLimit else { return .failure(.tooLarge) }
        let storedName = "\(id).img"
        guard filename == nil || filename == storedName else { return .failure(.unsafePath) }
        let trimmed = String(text.prefix(ShareAppGroup.textLimit))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return .success(
            ShareInboxRecord(
                id: id, kind: .image, text: trimmed, imageFilename: storedName, createdAt: now))
    }

    public static func recentSessions<S>(
        _ sessions: [S], id: (S) -> String?, title: (S) -> String?, detail: (S) -> String,
        updatedAt: (S) -> Int?, limit: Int = ShareAppGroup.recentLimit
    ) -> [ShareRecentSession] {
        sessions.compactMap { session -> ShareRecentSession? in
            guard let rawID = id(session)?.trimmingCharacters(in: .whitespacesAndNewlines),
                isSafeID(rawID),
                let rawTitle = title(session)?.trimmingCharacters(in: .whitespacesAndNewlines),
                !rawTitle.isEmpty
            else { return nil }
            return ShareRecentSession(
                id: rawID, title: rawTitle, detail: detail(session), updatedAt: updatedAt(session) ?? 0)
        }
        .sorted { $0.updatedAt > $1.updatedAt }
        .prefix(limit)
        .map { $0 }
    }

    /// Choosing a row builds a draft. The caller must not send it.
    public static func prefill(
        record: ShareInboxRecord, image: PromptImage?, target: ShareTarget
    ) -> SharePrefill {
        SharePrefill(
            target: target, text: record.text,
            images: record.kind == .image ? (image.map { [$0] } ?? []) : [])
    }

    /// 把分享内容并入**已有草稿**，绝不覆盖用户已经写下的字（C13 要求 2）。
    ///
    /// 语义与 `ChangesTurnNavigator.appending(_:to:)` 保持一致：已有内容在前、
    /// 分享内容在后，空草稿则直接采用分享内容。
    ///
    /// - Note: 这里刻意**内联**追加逻辑，不调用 `ChangesTurnNavigator`。
    ///   原因：`ShareInbox.swift` 被 Share 扩展 target 当作**独立源文件**编译
    ///   （见 project.yml 中扩展的 sources），而扩展只带 `ShareInbox.swift` +
    ///   `PromptAttachment.swift`，并不链接整个 DLCore。若在此引用
    ///   `ChangesTurnNavigator`，扩展会编译失败（cannot find in scope）。
    ///   两处语义必须保持一致；`ShareInboxConsumeTests` 有用例守护。
    ///
    /// - Parameters:
    ///   - draft: 用户当前草稿（可能为空、可能来自本地草稿存储）
    ///   - shared: 分享进来的文本
    /// - Returns: 合并结果，以及是否发生了「追加」（false 表示草稿原本为空）
    public static func merging(draft: String, shared: String) -> (text: String, appended: Bool) {
        let incoming = shared.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !incoming.isEmpty else { return (draft, false) }
        guard !draft.isEmpty else { return (incoming, false) }
        // 已包含则不再重复追加（用户可能重复分享同一条内容）
        if draft == incoming || draft.hasSuffix(incoming) { return (draft, false) }
        return (draft + "\n" + incoming, true)
    }

    /// 已合并草稿的图片：分享图片追加到既有图片之后，并遵守数量上限。
    public static func mergingImages(
        existing: [PromptImage], shared: [PromptImage], limit: Int = ShareAppGroup.imageCountLimit
    ) -> [PromptImage] {
        guard !shared.isEmpty else { return existing }
        return Array((existing + shared).prefix(max(0, limit)))
    }

    public static func isSafeID(_ id: String) -> Bool {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789")
        return (8...64).contains(id.count) && id.unicodeScalars.allSatisfy { allowed.contains($0) }
    }

    /// 从分享扩展拿到的文件 URL 读取字节（C13 要求 3）。
    ///
    /// 关键约束：跨 App 容器拿到的 URL 是 **security-scoped**，必须
    /// `startAccessingSecurityScopedResource()` → 读字节 → **同一个同步范围内**
    /// `stopAccessingSecurityScopedResource()`。若先停再读，读取会失败；若只 start
    /// 不 stop，会泄漏对其他 App 容器的访问权（扩展可能存活很久）。
    ///
    /// 该函数把「开启-读取-释放」收敛到一个出口，因此**不会**在异常路径上漏掉 stop。
    /// 平台调用通过 `ShareSecurityScope` 注入，便于在测试里断言配平与失败语义。
    ///
    /// - Note: 读取在 scope 内**同步**完成；`stop` 通过 `defer` 保证一定执行。
    public static func readSecurityScopedFile(
        at url: URL,
        scope: ShareSecurityScope = .live,
        byteLimit: Int = ShareAppGroup.imageByteLimit
    ) throws -> Data {
        // 非文件 URL（例如已是 Data/内存对象）无需开启作用域
        guard url.isFileURL else { throw ShareInboxError.unsupportedType }

        let opened = scope.startAccessing(url)
        defer {
            // 只有真的开启成功才需要释放；否则会打破系统计数
            if opened { scope.stopAccessing(url) }
        }

        // 文件可能在分享面板弹出后被移动/删除（失效附件）
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else {
            throw ShareInboxError.attachmentUnavailable
        }
        guard !data.isEmpty else { throw ShareInboxError.attachmentUnavailable }
        guard data.count <= byteLimit else { throw ShareInboxError.tooLarge }
        return data
    }

    /// 校验一批待写入的图片是否满足数量/总量限制（C13 要求 3）。
    /// 返回第一个不合格项的错误，全部合格返回 nil。
    public static func validateImageBatch(_ items: [Data]) -> ShareInboxError? {
        guard items.count <= ShareAppGroup.imageCountLimit else { return .tooManyImages }
        guard items.allSatisfy({ !$0.isEmpty && $0.count <= ShareAppGroup.imageByteLimit }) else {
            return .tooLarge
        }
        guard items.reduce(0, { $0 + $1.count }) <= ShareAppGroup.imageTotalByteLimit else {
            return .tooLarge
        }
        return nil
    }

    /// 图片类型是否在白名单内（C13 要求 3：格式限制明确）。
    ///
    /// 只做**精确匹配**。曾经想用前缀匹配来"兼容同族 UTI"，但那是错的：
    /// UTI 的派生关系由系统 conformance 决定，**不编码在字符串里**
    /// （`public.heic` 的同族是 `public.heif-standard` 这样的独立标识符，
    /// 而不是 `public.heic.*`）。前缀匹配会放行 `public.png.evil` 这类
    /// 任意后缀，等于把白名单变成形状检查 —— 故删除该分支。
    public static func isAllowedImageType(_ identifier: String) -> Bool {
        ShareAppGroup.allowedImageTypeIdentifiers.contains(identifier)
    }
}

/// File container shared by the extension and the app. Tests pass a temporary root.
public struct ShareGroupStore: Sendable {
    public var root: URL

    public init(root: URL) {
        self.root = root
    }

    public static func live(fileManager: FileManager = .default) -> ShareGroupStore? {
        guard
            let root = fileManager.containerURL(
                forSecurityApplicationGroupIdentifier: ShareAppGroup.identifier)
        else { return nil }
        return ShareGroupStore(root: root)
    }

    public func writeRecord(_ record: ShareInboxRecord, image: Data? = nil) throws {
        guard ShareInbox.isSafeID(record.id) else { throw ShareInboxError.unsafePath }
        if record.kind == .image {
            guard let image, image.count <= ShareAppGroup.imageByteLimit,
                record.imageFilename == "\(record.id).img"
            else { throw ShareInboxError.tooLarge }
            try image.write(to: try file(named: record.imageFilename ?? ""), options: .atomic)
        }
        let data = try JSONEncoder().encode(record)
        try data.write(to: try file(named: "\(record.id).json"), options: .atomic)
        try replaceSnapshot(record)
    }

    public func readRecord(id: String) -> ShareInboxRecord? {
        guard ShareInbox.isSafeID(id), let data = try? Data(contentsOf: fileURL(named: "\(id).json")) else {
            return nil
        }
        return try? JSONDecoder().decode(ShareInboxRecord.self, from: data)
    }

    public func imageData(for record: ShareInboxRecord) -> Data? {
        guard record.kind == .image, let name = record.imageFilename, name == "\(record.id).img" else {
            return nil
        }
        return try? Data(contentsOf: fileURL(named: name))
    }

    public func writeRecents(_ sessions: [ShareRecentSession]) throws {
        let data = try JSONEncoder().encode(Array(sessions.prefix(ShareAppGroup.recentLimit)))
        try data.write(to: try file(named: "recent-sessions.json"), options: .atomic)
    }

    public func readRecents() -> [ShareRecentSession] {
        guard let data = try? Data(contentsOf: fileURL(named: "recent-sessions.json")),
            let sessions = try? JSONDecoder().decode([ShareRecentSession].self, from: data)
        else { return [] }
        return Array(sessions.prefix(ShareAppGroup.recentLimit))
    }

    /// 消费一条分享记录。**幂等**：重复调用同一 id 不再改变任何状态。
    ///
    /// 顺序刻意做成「先删 JSON 元数据、再删图片」：
    /// JSON 是记录的**唯一入口**（`readRecord` 只认它），所以先移除入口后即使
    /// 中途崩溃/被杀，也只是残留一个**不可达**的图片文件，而不会留下一条
    /// 元数据完好、却引用已删除图片的「半消费」记录 —— 后者会让 App 再次
    /// 消费同一条分享（重复预填），正是 C13 要消除的。
    ///
    /// 返回是否真的删掉了什么，便于调用方与测试区分「首次消费」与「重复消费」。
    @discardableResult
    public func consume(id: String) -> Bool {
        guard ShareInbox.isSafeID(id) else { return false }
        // 先读一次拿到图片名（若记录已不存在则无事可做 → 幂等）
        guard let record = readRecord(id: id) else { return false }

        // 1) 先移除唯一入口，避免半消费状态被再次读到
        let metadataURL = fileURL(named: "\(id).json")
        do {
            try FileManager.default.removeItem(at: metadataURL)
        } catch {
            // 文件本就不存在（并发重复消费）→ 视为已完成，不再动图片
            if (error as NSError).code == NSFileNoSuchFileError { return false }
            return false
        }

        // 2) 入口已移除，再清理图片与快照
        if let name = record.imageFilename, name == "\(id).img" {
            try? FileManager.default.removeItem(at: fileURL(named: name))
        }
        clearSnapshotIfPointingAt(id: id)
        return true
    }

    /// 该 id 是否仍可被消费（用于 UI 判断分享是否已被处理）。
    public func isPending(id: String) -> Bool {
        readRecord(id: id) != nil
    }

    private func replaceSnapshot(_ record: ShareInboxRecord) throws {
        let data = try JSONEncoder().encode(record)
        try data.write(to: try file(named: "latest.json"), options: .atomic)
    }

    /// `latest.json` 是最后一次分享的快照。它在 `consume` 后必须清除，
    /// 否则已消费记录的内容会长期滞留在 App Group 里（既占空间，也让
    /// 「已处理」的分享在磁盘上看起来仍然存在）。
    private func clearSnapshotIfPointingAt(id: String) {
        guard let data = try? Data(contentsOf: fileURL(named: "latest.json")),
            let snapshot = try? JSONDecoder().decode(ShareInboxRecord.self, from: data),
            snapshot.id == id
        else { return }
        try? FileManager.default.removeItem(at: fileURL(named: "latest.json"))
    }

    private func file(named name: String) throws -> URL {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return fileURL(named: name)
    }

    private func fileURL(named name: String) -> URL {
        let safe = name.split(separator: "/").count == 1 && !name.hasPrefix(".") ? name : "rejected"
        return root.appendingPathComponent(safe, isDirectory: false)
    }
}
