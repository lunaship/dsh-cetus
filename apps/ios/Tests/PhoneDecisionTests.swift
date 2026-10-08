import DLCore
import DLModels
import Testing

@Suite struct PhoneDecisionTests {
    @Test func latestTakenOverApprovalWins() {
        let older = RequestMessage(
            id: "q", role: "question", takenOverByPhone: true, questionRpcId: "rpc", requestStatus: .pending)
        let newer = RequestMessage(
            id: "a", role: "approval", text: "bash", approvalId: "ap-1", takenOverByPhone: true,
            requestStatus: .pending)
        let decision = pendingPhoneDecision([older, newer])
        #expect(decision == .approval(newer))
    }

    @Test func skipsApprovalThePhoneDidNotTake() {
        let remote = RequestMessage(
            id: "a", role: "approval", approvalId: "ap-1", takenOverByPhone: false, requestStatus: .pending)
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

// MARK: - C06 决策面板：另一设备已处理

@Suite struct DecisionHandledTests {
    /// 请求已是终态 → 面板显示"已处理"。
    @Test func terminalRequestIsHandled() {
        #expect(isTerminalRequestStatus(.resolved) == true)
        #expect(isTerminalRequestStatus(approvalUiStatus(outcome: "rejected")) == true)
        #expect(isTerminalRequestStatus(.cancelled) == true)
        #expect(isTerminalRequestStatus(.expired) == true)
    }

    /// 请求仍 pending → 可操作。
    @Test func pendingRequestIsActionable() {
        #expect(isTerminalRequestStatus(.pending) == false)
        #expect(isTerminalRequestStatus(nil) == false)
    }

    /// 终态不能被迟到 pending 回滚（C06 要求 3）。
    @Test func terminalCannotBeRolledBackByLatePending() {
        #expect(mergeStatus(.resolved, .pending) == .resolved)
        #expect(mergeStatus(approvalUiStatus(outcome: "rejected"), .pending) == .resolved)
        // 非终态的终态可前进
        #expect(mergeStatus(.pending, .resolved) == .resolved)
    }
}
