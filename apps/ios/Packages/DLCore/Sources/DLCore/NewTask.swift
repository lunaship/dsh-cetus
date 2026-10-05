import DLModels
import Foundation

public enum WorkspacePick: Equatable, Sendable {
    case listed(WorkspaceInfo)
    case usePath(String)
}

public func isAbsoluteWorkspacePath(_ text: String) -> Bool {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.hasPrefix("/") || trimmed.hasPrefix("~")
}

/// 搜索工作区。输入绝对路径且列表里没有这条路径时，多一行「使用该路径」。
public func workspacePickRows(workspaces: [WorkspaceInfo], query: String) -> [WorkspacePick] {
    let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
    let folded = needle.lowercased()
    let listed = workspaces.filter { workspace in
        guard !folded.isEmpty else { return true }
        let path = workspace.path ?? ""
        let title = workspace.title ?? ""
        return path.lowercased().contains(folded) || title.lowercased().contains(folded)
    }
    var rows = listed.map { WorkspacePick.listed($0) }
    if isAbsoluteWorkspacePath(needle), !listed.contains(where: { ($0.path ?? "") == needle }) {
        rows.append(.usePath(needle))
    }
    return rows
}

public enum WorkspaceSubmit: Equatable, Sendable {
    case pendingApproval
    case created
}

/// 绝对路径走 202 等电脑批准。单层名称立即创建。
public func workspaceSubmitKind(_ text: String) -> WorkspaceSubmit {
    isAbsoluteWorkspacePath(text) ? .pendingApproval : .created
}
