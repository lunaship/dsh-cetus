import Foundation

/// `GET /dsh-link/mobile/workspaces`。
public struct WorkspaceListResponse: Codable, Equatable, Sendable {
    public var version: Int?
    public var workspaces: [WorkspaceInfo]?
    public var archivedSessionIds: [String]?

    public init(
        version: Int? = nil,
        workspaces: [WorkspaceInfo]? = nil,
        archivedSessionIds: [String]? = nil
    ) {
        self.version = version
        self.workspaces = workspaces
        self.archivedSessionIds = archivedSessionIds
    }
}

/// 工作区行（DSH `workspace.list` 投影；`title` 缺失时 App 用路径末段兜底）。
public struct WorkspaceInfo: Codable, Equatable, Sendable {
    public var workspaceId: String?
    public var path: String?
    public var title: String?
    public var sessionIds: [String]?

    public init(
        workspaceId: String? = nil,
        path: String? = nil,
        title: String? = nil,
        sessionIds: [String]? = nil
    ) {
        self.workspaceId = workspaceId
        self.path = path
        self.title = title
        self.sessionIds = sessionIds
    }
}

/// `POST /dsh-link/mobile/workspaces` 的 200：单层名称，立即在锚点工作区同级创建目录并注册。
public struct WorkspaceCreateResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var workspace: WorkspaceInfo?
    public var created: Bool?
    public var directoryCreated: Bool?
    /// "subdirectory" / "name" 等，见插件 `planMobileWorkspaceCreate`。
    public var inputKind: String?
    public var resolvedPath: String?

    public init(
        ok: Bool? = nil,
        workspace: WorkspaceInfo? = nil,
        created: Bool? = nil,
        directoryCreated: Bool? = nil,
        inputKind: String? = nil,
        resolvedPath: String? = nil
    ) {
        self.ok = ok
        self.workspace = workspace
        self.created = created
        self.directoryCreated = directoryCreated
        self.inputKind = inputKind
        self.resolvedPath = resolvedPath
    }
}

/// `POST /dsh-link/mobile/workspaces` 的 202：绝对路径，目录已存在但不调用 workspace.create，
/// 电脑在「手机连接」面板批准后才注册；拒绝、过期或设备被吊销则丢弃。
public struct WorkspacePendingApprovalResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var pending: Bool?
    public var requestId: String?
    public var path: String?
    public var inputKind: String?
    public var expiresAt: Int?

    public init(
        ok: Bool? = nil,
        pending: Bool? = nil,
        requestId: String? = nil,
        path: String? = nil,
        inputKind: String? = nil,
        expiresAt: Int? = nil
    ) {
        self.ok = ok
        self.pending = pending
        self.requestId = requestId
        self.path = path
        self.inputKind = inputKind
        self.expiresAt = expiresAt
    }
}
