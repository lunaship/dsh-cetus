import DLCore
import DLModels
import Testing

@Suite struct PhoneDecisionTests {
    @Test func latestTakenOverApprovalWins() {
        let older = RequestMessage(
            id: "q", role: "question", questionRpcId: "rpc", requestStatus: .pending, takenOverByPhone: true)
        let newer = RequestMessage(
            id: "a", role: "approval", text: "bash", approvalId: "ap-1", requestStatus: .pending,
            takenOverByPhone: true)
        let decision = pendingPhoneDecision([older, newer])
        #expect(decision == .approval(newer))
    }

    @Test func skipsApprovalThePhoneDidNotTake() {
        let remote = RequestMessage(
            id: "a", role: "approval", approvalId: "ap-1", requestStatus: .pending, takenOverByPhone: false)
        #expect(pendingPhoneDecision([remote]) == nil)
    }

    @Test func skipsQuestionAfterALaterUserMessage() {
        let question = RequestMessage(
            id: "q", role: "question", text: "Which?", questionRpcId: "rpc", requestStatus: .pending)
        let decision = pendingPhoneDecision([question]) { $0.questionRpcId == "rpc" }
        #expect(decision == nil)
    }

    @Test func terminalQuestionIsNotActionable() {
        let question = RequestMessage(
            id: "q", role: "question", questionRpcId: "rpc", requestStatus: .resolved)
        #expect(pendingPhoneDecision([question]) == nil)
    }

    @Test func readsCommandFromToolArgs() {
        #expect(approvalCommand(from: #"{"command":"npm test"}"#) == "npm test")
        #expect(approvalCommand(from: #"{"path":"Notes.md"}"#) == nil)
        #expect(approvalCommand(from: "not-json") == nil)
    }
}
