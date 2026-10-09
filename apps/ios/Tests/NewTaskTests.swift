import DLCore
import DLModels
import Foundation
import Testing

@testable import Cetus

@Suite struct NewTaskTests {
    @Test func absolutePathAddsUseRow() {
        let rooms = [WorkspaceInfo(path: "/src/app", title: "app")]
        let rows = workspacePickRows(workspaces: rooms, query: "/src")
        #expect(rows.count == 2)
        #expect(rows.last == .usePath("/src"))
        #expect(workspacePickRows(workspaces: rooms, query: "/tmp/new") == [.usePath("/tmp/new")])
        #expect(workspaceSubmitKind("/tmp/new") == .pendingApproval)
    }

    @Test func knownPathDoesNotDuplicate() {
        let rooms = [WorkspaceInfo(path: "/src/app", title: "app")]
        let rows = workspacePickRows(workspaces: rooms, query: "/src/app")
        #expect(rows == [.listed(rooms[0])])
    }

    @Test func nameFiltersAndCreatesImmediately() {
        let rooms = [
            WorkspaceInfo(path: "/src/app", title: "app"),
            WorkspaceInfo(path: "/src/web", title: "web"),
        ]
        let rows = workspacePickRows(workspaces: rooms, query: "web")
        #expect(rows.count == 1)
        #expect(workspaceSubmitKind("notes") == .created)
    }
}

// MARK: - C15 §19.2 R3：动作必须给出结果，不能静默失败

@Suite struct NewTaskWorkspaceErrorTests {
    /// 添加工作区失败时，用户必须看到**可操作说明**。
    ///
    /// 旧实现是 `catch {}`：路径非法、无权限、电脑离线全都静默失败 ——
    /// 用户点了「提交」什么都没发生，也不知道为什么。
    @Test("失败文案存在且可操作")
    func failureCopyIsActionable() {
        let text = NewTaskText.addWorkspaceFailed.fallback
        #expect(!text.isEmpty)
        // 必须告诉用户「检查什么」，而不是只说「失败了」。
        let lowered = text.lowercased()
        #expect(
            lowered.contains("path") || lowered.contains("connection"),
            "失败文案要指出检查方向，实际：\(text)")
    }

    /// 失败文案必须与「已提交等待确认」区分开 —— 两者对用户的下一步动作不同。
    @Test("失败与等待确认不是同一句话")
    func failureDiffersFromPending() {
        #expect(NewTaskText.addWorkspaceFailed.fallback != NewTaskText.pending.fallback)
    }

    /// 两种语言都要有，否则中文界面会夹英文。
    @Test("失败文案 en/zh 齐备")
    func failureCopyIsLocalized() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "App/Resources/Localizable.xcstrings")
        let catalog = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        let strings = try #require(catalog?["strings"] as? [String: Any])
        let entry = try #require(strings["newTask.addWorkspaceFailed"] as? [String: Any])
        let localizations = try #require(entry["localizations"] as? [String: Any])
        for language in ["en", "zh-Hans"] {
            let unit = (localizations[language] as? [String: Any])?["stringUnit"] as? [String: Any]
            #expect((unit?["value"] as? String)?.isEmpty == false, "缺 \(language) 翻译")
        }
    }
}
