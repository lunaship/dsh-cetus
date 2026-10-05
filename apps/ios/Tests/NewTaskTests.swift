import DLCore
import DLModels
import Testing

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
