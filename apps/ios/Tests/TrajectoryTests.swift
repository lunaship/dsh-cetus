import DLCore
import DLModels
import Testing

@Suite struct TrajectoryTests {
    @Test func groupsByTurnAndKeepsLaterTime() {
        let messages = [
            HistoryMessage(id: "u", role: "user", text: "Shorten it", turn: 1, time: 10),
            HistoryMessage(id: "a", role: "assistant", text: "Done", turn: 1, time: 20),
            HistoryMessage(id: "u2", role: "user", text: "Again", turn: 2, time: 30),
        ]
        let turns = trajectoryTurns(messages)
        #expect(turns.map(\.id) == [1, 2])
        #expect(turns[0].time == 20)
        #expect(turns[0].lines.map(\.title) == ["user", "assistant"])
    }

    @Test func filtersToolsAndSearch() {
        let messages = [
            HistoryMessage(id: "u", role: "user", text: "Read notes", turn: 1),
            HistoryMessage(id: "t", role: "tool_call", name: "read", args: #"{"path":"Notes.md"}"#, turn: 1),
        ]
        let tools = trajectoryTurns(messages, filter: .tool)
        #expect(tools.first?.lines.map(\.title) == ["read"])
        #expect(trajectoryTurns(messages, query: "notes").first?.lines.map(\.id) == ["u"])
        #expect(trajectoryTurns(messages, query: "missing").isEmpty)
    }
}
