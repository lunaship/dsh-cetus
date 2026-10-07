import Foundation

/// 草稿的用途。同一会话里普通输入、问题回答、改动引用互不覆盖（C02）。
public enum ComposerDraftKind: String, Codable, CaseIterable, Sendable, Equatable {
    /// 主输入框正文。
    case prompt
    /// 澄清问题的自定义回答。
    case answer
    /// 改动引用（附加到发送的上下文片段）。
    case reference

    /// 落盘文件名与清单排序都用它，新增种类必须显式给值，不能复用既有字符串。
    var slug: String { rawValue }
}

/// 一份草稿的落盘内容。UI 只拿到这个值，不拼路径（C02 要求 3）。
public struct ComposerDraftRecord: Codable, Equatable, Sendable {
    /// 落盘格式版本。读到的版本高于本版时不猜，直接当没有草稿。
    public static let schemaVersion = 1

    public var schemaVersion: Int
    /// 空正文的草稿不落盘，因此这里永远非空。
    public var text: String
    /// 应用持有的附件引用（受保护容器里的句柄），不内联图片字节。
    public var attachments: [ComposerDraftAttachment]
    /// 最后一次编辑时间，用于"恢复的草稿"排序与陈旧清理。
    public var updatedAt: Date
    /// 归属会话；新任务尚无会话时为 nil（此时用 draftID + workspaceID 定位）。
    public var sessionID: String?
    /// 归属工作区。新任务草稿靠它区分同一台电脑上的不同目录。
    public var workspaceID: String?

    public init(
        schemaVersion: Int = Self.schemaVersion,
        text: String,
        attachments: [ComposerDraftAttachment] = [],
        updatedAt: Date = Date(),
        sessionID: String? = nil,
        workspaceID: String? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.text = text
        self.attachments = attachments
        self.updatedAt = updatedAt
        self.sessionID = sessionID
        self.workspaceID = workspaceID
    }

    /// 版本不认识就丢弃。宁可让用户重写，也不要把旧结构的字段读错位置。
    var isReadable: Bool { schemaVersion == Self.schemaVersion }
}

/// 附件只存引用：应用持有的受保护书签名，不存图片字节、不存用户可辨识路径。
public struct ComposerDraftAttachment: Codable, Equatable, Sendable, Identifiable {
    /// 受保护容器里的句柄名。真正的字节由草稿仓库独自保存。
    public var bookmark: String
    public var mediaType: String
    /// 失效附件不得被当成已附上（C02 要求 7）。
    public var isValid: Bool

    public var id: String { bookmark }

    public init(bookmark: String, mediaType: String, isValid: Bool = true) {
        self.bookmark = bookmark
        self.mediaType = mediaType
        self.isValid = isValid
    }
}

/// 草稿的定位键。至少 `(hostID, sessionID, kind)`；会话还不存在时用 draftID + workspaceID。
public struct ComposerDraftKey: Hashable, Sendable, Equatable {
    public var hostID: String
    /// 会话存在时一定有值；为空说明这是"新任务"草稿。
    public var sessionID: String?
    /// 新任务草稿的独立标识。有 sessionID 时不参与落盘文件名。
    public var draftID: String
    /// 新任务草稿的工作区，用来区分同一电脑上的不同目录。
    public var workspaceID: String?
    public var kind: ComposerDraftKind

    public init(
        hostID: String, sessionID: String? = nil, draftID: String = "", workspaceID: String? = nil,
        kind: ComposerDraftKind = .prompt
    ) {
        self.hostID = hostID
        self.sessionID = sessionID
        self.draftID = draftID
        self.workspaceID = workspaceID
        self.kind = kind
    }

    /// 同一会话的同一种草稿只有一个槽位。
    ///
    /// 有 sessionID 时 **不含** draftID：否则新任务起草（draftID 随机）
    /// 会在会话确定的那一刻换槽位，已输入的草稿就读不到了。
    var storageID: String {
        if let sessionID, !sessionID.isEmpty { return "s.\(sessionID)" }
        return "d.\(draftID).\(workspaceID ?? "-")"
    }
}

/// 无法归因到会话的旧草稿。交给用户选目标，绝不自动塞进最近会话（C02 要求 8）。
public struct OrphanDraft: Identifiable, Sendable, Equatable {
    public var id: String
    public var text: String
    public var updatedAt: Date
    /// 旧文件名没有会话信息，这里恒为 nil，UI 据此显示"未知会话"。
    public var sessionID: String?

    public init(id: String, text: String, updatedAt: Date, sessionID: String? = nil) {
        self.id = id
        self.text = text
        self.updatedAt = updatedAt
        self.sessionID = sessionID
    }
}

/// 写盘结果。写失败必须保留内存副本并提示，不能静默丢失（C02 要求 6）。
public enum ComposerDraftWriteResult: Equatable, Sendable {
    /// 已落盘（正文为空时等于已删除）。
    case persisted
    /// 落盘失败。调用方必须保留内存草稿并提示。
    case failed
}

/// 会话级草稿的持有者（C02 要求 3）：UI 只认这个类型，不碰路径、不拼文件名。
///
/// 三件事：
/// - 三个槽位（正文 / 回答 / 引用）分开保存，互不覆盖；
/// - 审批出现时**冻结**正文草稿：只暂停落盘、不清空 —— 回到普通输入时文字还在；
/// - 合并写入交给 `ComposerDraftWriter`，不是每个字符都写盘。
@MainActor
final class ComposerDraftController {
    /// 空壳：草稿能力不可用（截图路径）时使用，所有写入都是空操作。
    static let disabled = ComposerDraftController(store: nil, key: ComposerDraftKey(hostID: ""))

    var key: ComposerDraftKey
    private let store: ComposerDraftStore?
    private let writer: ComposerDraftWriter?
    /// 审批 / 问题卡片占据输入区时置真：这一类草稿冻结，不清空。
    private var frozen: [ComposerDraftKind: Bool] = [:]

    init(store: ComposerDraftStore?, key: ComposerDraftKey) {
        self.store = store
        self.key = key
        self.writer = store.map { ComposerDraftWriter(store: $0, key: key) }
    }

    var isEnabled: Bool { store != nil }

    var onWriteFailure: (@MainActor (ComposerDraftKind) -> Void)? {
        get { writer?.onFailure }
        set { writer?.onFailure = newValue }
    }

    /// 单测用：拉长静默窗口，让"未 flush 前不落盘"成为确定性断言。
    func configureForTest(quietWindow: Duration, maxPending: Duration, clock: @escaping @MainActor () -> Date) {
        writer?.quietWindow = quietWindow
        writer?.maxPending = maxPending
        writer?.clock = clock
    }

    // MARK: - 读

    func restored(_ kind: ComposerDraftKind) -> String {
        guard let store else { return "" }
        var slot = key
        slot.kind = kind
        return store.text(slot)
    }

    // MARK: - 写

    /// 排队一次写入。冻结期间这一类不落盘，内存里的文字由调用方继续持有。
    func update(_ text: String, kind: ComposerDraftKind) {
        guard frozen[kind] != true else { return }
        writer?.update(text, kind: kind)
    }

    /// 审批 / 问题卡片出现：**冻结**这一类草稿，不清空。
    func freeze(_ kind: ComposerDraftKind) {
        frozen[kind] = true
    }

    /// 回到普通输入：解冻，并把内存里的最新文字补写一次。
    func unfreeze(_ kind: ComposerDraftKind, text: String) {
        frozen[kind] = false
        writer?.update(text, kind: kind)
    }

    func isFrozen(_ kind: ComposerDraftKind) -> Bool { frozen[kind] == true }

    /// 立即落盘。页面消失 / 进后台 / 发送前调用。
    func flush() {
        writer?.flushNow()
    }

    /// 发送成功 / 用户主动丢弃：清空内存与磁盘上的这一槽位。
    func clear(_ kind: ComposerDraftKind) {
        frozen[kind] = false
        writer?.clear(kind)
    }

    func clearAll() {
        for kind in ComposerDraftKind.allCases { clear(kind) }
    }

    var hasPending: Bool { writer?.hasPending ?? false }

    /// 归属会话确定的那一刻（新任务刚建出会话）调用：之后草稿跟会话走。
    func bind(sessionID: String) {
        var next = key
        next.sessionID = sessionID
        key = next
        writer?.key = next
    }
}
