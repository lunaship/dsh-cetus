import Foundation

/// `GET /dsh-link/mobile/sessions/:id/tree?path=`（工作区文件树，按层懒加载）。
/// `path` 为解析后的真实相对路径（根为空串）；不过滤隐藏文件。
public struct TreeResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var path: String?
    public var total: Int?
    public var truncated: Bool?
    /// 目录在前、同类按名称码元序，最多 treeMaxEntries（2000）条。
    public var entries: [TreeEntry]?

    public init(
        ok: Bool? = nil,
        path: String? = nil,
        total: Int? = nil,
        truncated: Bool? = nil,
        entries: [TreeEntry]? = nil
    ) {
        self.ok = ok
        self.path = path
        self.total = total
        self.truncated = truncated
        self.entries = entries
    }
}

public struct TreeEntry: Codable, Equatable, Sendable {
    public var name: String?
    public var type: TreeEntryType?
    /// 仅 `file`：字节大小与修改时间。
    public var size: Int?
    public var mtimeMs: Int?
    /// 符号链接且目标仍在工作区内：按目标类型给出，App 可进入 / 打开。
    public var link: Bool?
    /// 指向工作区外或断开的链接：不可进入也不可打开。
    public var outside: Bool?

    public init(
        name: String? = nil,
        type: TreeEntryType? = nil,
        size: Int? = nil,
        mtimeMs: Int? = nil,
        link: Bool? = nil,
        outside: Bool? = nil
    ) {
        self.name = name
        self.type = type
        self.size = size
        self.mtimeMs = mtimeMs
        self.link = link
        self.outside = outside
    }
}

public enum TreeEntryType: DLStringEnum {
    case dir
    case file
    case symlink
    case other
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "dir": .dir
        case "file": .file
        case "symlink": .symlink
        case "other": .other
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .dir: "dir"
        case .file: "file"
        case .symlink: "symlink"
        case .other: "other"
        case .unknown(let raw): raw
        }
    }
}
