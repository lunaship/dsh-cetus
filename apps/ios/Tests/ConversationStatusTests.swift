import DLCore
import DLModels
import Foundation
import Testing

@Suite struct ConversationStatusTests {
    @Test func priorityAcrossAllCandidateCombinations() {
        for offline in [false, true] {
            for pending in [false, true] {
                for goal in [false, true] {
                    for preview in [false, true] {
                        var state = ConversationStatusState()
                        if offline { state.connection = .reconnecting }
                        if pending { state.question(QuestionRequestEvent(rpcId: "q")) }
                        if goal { state.goal = SessionGoal(objective: "Test") }
                        if preview {
                            state.apply(
                                detections: PreviewDetectionsResponse(detections: [
                                    PreviewDetection(port: 3000, sessionId: "s")
                                ]),
                                sessionID: "s")
                        }
                        let expected: ConversationStatusKind? =
                            offline ? .disconnected : pending ? .pending : goal ? .goal : preview ? .preview : nil
                        #expect(state.kind == expected)
                    }
                }
            }
        }
    }

    @Test func historyAndStatsUseRealContractShape() throws {
        var state = ConversationStatusState()
        let history = try JSONDecoder().decode(
            HistoryResponse.self,
            from: Data(
                #"{"maxSeq":12,"goal":{"goal":{"objective":"Fix","phase":"active","maxGoalRounds":8},"roundsStarted":2},"stats":{"todos":[{"content":"Read","status":"completed"},{"content":"Test","status":"in_progress"}]}}"#
                    .utf8))
        state.replace(history: history)
        #expect(state.kind == .goal)
        #expect(state.goal?.roundsStarted == 2)
        #expect(state.progress == 0.5)
        state.apply(projections: .object(["tokenUsage": .object([:])]))
        #expect(state.goal == history.goal)
        #expect(state.plan.count == 2)
        state.apply(projections: .object(["goal": .null, "todos": .array([])]))
        #expect(state.kind == nil)
        #expect(state.progress == nil)
    }

    @Test func clearedNestedGoalAndAndroidPlanAliases() {
        var state = ConversationStatusState()
        state.goal = SessionGoal(objective: "Old")
        state.apply(projections: .object(["goal": .object(["goal": .null, "roundsStarted": .number(0)])]))
        #expect(state.goal == nil)
        for status in ["done", "completed", "complete"] { #expect(conversationPlanKind(status) == .completed) }
        for status in ["in_progress", "inprogress", "running", "active", "progress"] {
            #expect(conversationPlanKind(status) == .active)
        }
        #expect(conversationPlanKind("unknown") == .pending)
        #expect(conversationPlanKind(nil) == .pending)
    }

    @Test func todoFramesDeduplicateAndDoNotInventCompletion() throws {
        var state = ConversationStatusState()
        state.absorb(
            frame(
                2, "todo/write",
                #"{"todos":[{"content":"  "},{"content":"Read","status":"completed"},{"content":"Test","status":"future-status"}]}"#
            ))
        #expect(state.plan.count == 2)
        #expect(state.progress == 0.5)
        state.absorb(frame(1, "todo/write", #"{"todos":[]}"#))
        #expect(state.plan.count == 2)
        state.absorb(frame(2, "todo/write", #"{"todos":[]}"#))
        #expect(state.plan.count == 2)
        state.absorb(frame(3, "todo/write", #"{"todos":[]}"#))
        #expect(state.kind == nil)
        state.goal = SessionGoal(objective: "No measurable plan")
        #expect(state.progress == nil)
    }

    @Test func terminalRequestsSurviveLatePendingAndReconnectSnapshots() {
        var state = ConversationStatusState()
        state.absorb(frame(1, "approval/asked", #"{"id":"a"}"#))
        state.question(QuestionRequestEvent(rpcId: "q"))
        state.question(QuestionRequestEvent(rpcId: "q"))
        #expect(state.pendingCount == 2)
        state.absorb(frame(2, "approval/decided", #"{"id":"a","outcome":"allowed-once"}"#))
        state.resolveQuestion(QuestionResolvedEvent(rpcId: "q", outcome: "answered"))
        state.merge(
            requests: RequestsSnapshotResponse(
                approvals: [PendingApproval(approvalId: "a", status: .pending)],
                questions: [PendingQuestion(rpcId: "q", status: .pending)]))
        #expect(state.pendingCount == 0)
        #expect(state.kind == nil)
        state.question(QuestionRequestEvent(rpcId: "q"))
        #expect(state.pendingCount == 0)
        state.merge(requests: RequestsSnapshotResponse(questions: [PendingQuestion(rpcId: "new", status: .pending)]))
        #expect(state.pendingCount == 1)
        state.merge(requests: RequestsSnapshotResponse(approvals: [], questions: []))
        // I3.6: absence is not proof of settlement.
        #expect(state.pendingCount == 1)
        state.merge(requests: RequestsSnapshotResponse(questions: [PendingQuestion(rpcId: "new", status: .expired)]))
        #expect(state.pendingCount == 0)
    }

    @Test func previewIsScopedDeduplicatedAndCleared() {
        var state = ConversationStatusState()
        state.apply(
            detections: PreviewDetectionsResponse(detections: [
                PreviewDetection(port: 3000, sessionId: "s"), PreviewDetection(port: 3000, sessionId: "s"),
                PreviewDetection(port: 8080, sessionId: "other"), PreviewDetection(port: 0, sessionId: "s"),
                PreviewDetection(port: 65536, sessionId: "s"), PreviewDetection(port: 4000, sessionId: "s"),
            ]), sessionID: "s")
        #expect(state.previewPorts == [3000, 4000])
        #expect(state.kind == .preview)
        state.apply(detections: PreviewDetectionsResponse(detections: []), sessionID: "s")
        #expect(state.kind == nil)
    }

    @Test func disconnectDoesNotEraseGoalOrRequests() {
        var state = ConversationStatusState()
        state.goal = SessionGoal(objective: "Fix")
        state.question(QuestionRequestEvent(rpcId: "q"))
        state.connection = .reconnecting
        #expect(state.kind == .disconnected)
        state.connection = .connected
        #expect(state.kind == .pending)
        state.resolveQuestion(QuestionResolvedEvent(rpcId: "q", outcome: "cancelled"))
        #expect(state.kind == .goal)
    }

    private func frame(_ seq: Int, _ type: String, _ json: String) -> StreamFrame {
        StreamFrame(
            seq: seq, type: type, time: 0, data: try! JSONDecoder().decode(JSONValue.self, from: Data(json.utf8)))
    }
}
