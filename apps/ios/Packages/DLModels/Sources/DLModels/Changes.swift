import Foundation

/// `GET /dsh-link/mobile/sessions/:id/changes?seq=`（摘要）。`files` 保持 Host 顺序，
/// 数组下标即对比路由的 `index`。
public struct ChangesSummaryResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var seq: Int?
    public var turn: Int?
    public var total: Int?
    public var added: Int?
    public var deleted: Int?
    public var files: [ChangedFile]?

    public init(
        ok: Bool? = nil,
        seq: Int? = nil,
        turn: Int? = nil,
        total: Int? = nil,
        added: Int? = nil,
        deleted: Int? = nil,
        files: [ChangedFile]? = nil
    ) {
        self.ok = ok
        self.seq = seq
        self.turn = turn
        self.total = total
        self.added = added
        self.deleted = deleted
        self.files = files
    }
}

/// `GET /dsh-link/mobile/sessions/:id/changes/diff?seq=&index=`（对比）。
/// `hunks` 为空表示两侧相同；截断只丢尾部行，`truncated` 给总行数供 UI 说明。
public struct ChangesDiffResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var seq: Int?
    public var index: Int?
    public var kind: DiffKind?
    public var path: String?
    public var display: String?
    /// 轮首 / 轮末是否存在，据此判定新建 / 删除。
    public var before: Bool?
    public var after: Bool?
    /// 逐行对比超时，整文件替换。
    public var coarse: Bool?
    public var hunks: [DiffHunk]?
    public var truncated: DiffTruncation?

    public init(
        ok: Bool? = nil,
        seq: Int? = nil,
        index: Int? = nil,
        kind: DiffKind? = nil,
        path: String? = nil,
        display: String? = nil,
        before: Bool? = nil,
        after: Bool? = nil,
        coarse: Bool? = nil,
        hunks: [DiffHunk]? = nil,
        truncated: DiffTruncation? = nil
    ) {
        self.ok = ok
        self.seq = seq
        self.index = index
        self.kind = kind
        self.path = path
        self.display = display
        self.before = before
        self.after = after
        self.coarse = coarse
        self.hunks = hunks
        self.truncated = truncated
    }
}

public enum DiffKind: DLStringEnum {
    case text
    case binary
    case oversized
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "text": .text
        case "binary": .binary
        case "oversized": .oversized
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .text: "text"
        case .binary: "binary"
        case .oversized: "oversized"
        case .unknown(let raw): raw
        }
    }
}

public struct DiffHunk: Codable, Equatable, Sendable {
    public var oldStart: Int?
    public var oldLines: Int?
    public var newStart: Int?
    public var newLines: Int?
    /// 每行保留 `+` / `-` / 空格前缀，三行上下文。
    public var lines: [String]?

    public init(
        oldStart: Int? = nil,
        oldLines: Int? = nil,
        newStart: Int? = nil,
        newLines: Int? = nil,
        lines: [String]? = nil
    ) {
        self.oldStart = oldStart
        self.oldLines = oldLines
        self.newStart = newStart
        self.newLines = newLines
        self.lines = lines
    }
}

public struct DiffTruncation: Codable, Equatable, Sendable {
    public var shownLines: Int?
    public var totalLines: Int?

    public init(shownLines: Int? = nil, totalLines: Int? = nil) {
        self.shownLines = shownLines
        self.totalLines = totalLines
    }
}

/// 文件行。`binary` / `oversized` 都没有行数与对比；`display` 是斜杠分隔的展示路径
/// （`../`、`~` 或绝对路径）。
public struct ChangedFile: Codable, Equatable, Sendable {
    public var path: String?
    public var display: String?
    public var added: Int?
    public var deleted: Int?
    public var binary: Bool?
    public var oversized: Bool?

    public init(
        path: String? = nil,
        display: String? = nil,
        added: Int? = nil,
        deleted: Int? = nil,
        binary: Bool? = nil,
        oversized: Bool? = nil
    ) {
        self.path = path
        self.display = display
        self.added = added
        self.deleted = deleted
        self.binary = binary
        self.oversized = oversized
    }
}

/// 历史页内嵌的改动摘要（`workspace_changes` 消息，最多 100 个文件）。
/// 同轮后一条宣告取代前一条：App 跨页按 `turn` 保留最大 `seq`。
public struct ChangesSummary: Codable, Equatable, Sendable {
    public var turn: Int?
    public var total: Int?
    public var added: Int?
    public var deleted: Int?
    public var files: [ChangedFile]?

    public init(
        turn: Int? = nil,
        total: Int? = nil,
        added: Int? = nil,
        deleted: Int? = nil,
        files: [ChangedFile]? = nil
    ) {
        self.turn = turn
        self.total = total
        self.added = added
        self.deleted = deleted
        self.files = files
    }
}
