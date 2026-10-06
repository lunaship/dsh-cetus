import DLCore
import DLModels
import DLSecurity
import Foundation
import Testing

@testable import DeepLinks

@Suite struct QuestionRawJSONTests {
    @Test func roundTripKeepsUnknownFieldsOrderAndNull() throws {
        let raw = """
        {"rpcId":"q-1","status":"pending","questions":[{"id":"tone","question":"语气？","options":["直接",{"id":"soft","label":"温和","extra":null}],"kind":"select","vendor":{"keep":true}}],"note":null}
        """
        let data = Data(raw.utf8)
        let decoded = try JSONDecoder().decodePreservingRawJSON(PendingQuestion.self, from: data)
        #expect(decoded.questionsJSON?.contains("\"vendor\"") == true)
        #expect(decoded.questionsJSON?.contains("\"extra\"") == true)
        #expect(decoded.questionsJSON?.contains("null") == true)
        let questionsIndex = raw.firstIndex(of: "[")
        let questionsEnd = raw.lastIndex(of: "]")
        let original = raw[questionsIndex!...questionsEnd!]
        #expect(decoded.questionsJSON == String(original))

        let encoded = try JSONEncoder().encode(decoded)
        let text = String(decoding: encoded, as: UTF8.self)
        #expect(!text.contains("questionsJSON"))
        #expect(text.contains("\"vendor\"") == false)

        let eventRaw = """
        {"rpcId":"q-1","questions":[{"z":1,"id":"tone","unknown":null}]}
        """
        let event = try JSONDecoder().decodePreservingRawJSON(
            QuestionRequestEvent.self, from: Data(eventRaw.utf8))
        #expect(event.questionsJSON == "[{\"z\":1,\"id\":\"tone\",\"unknown\":null}]")
        let eventText = String(decoding: try JSONEncoder().encode(event), as: UTF8.self)
        #expect(!eventText.contains("questionsJSON"))
    }

    @Test func snapshotKeepsOriginalQuestionJSON() throws {
        let raw = """
        {"questions":[{"rpcId":"q","status":"pending","questions":[{"id":"tone","z":1,"unknown":null}]}]}
        """
        let response = try JSONDecoder().decodePreservingRawJSON(
            RequestsSnapshotResponse.self, from: Data(raw.utf8))
        let snapshot = parseSessionRequestSnapshot(response)
        let messages = mergeMessagesWithRequestSnapshot([], snapshot: snapshot)
        let expected = "[" + String(decoding: Data([0x7b,0x22,0x69,0x64,0x22,0x3a,0x22,0x74,0x6f,0x6e,0x65,0x22,0x2c,0x22,0x7a,0x22,0x3a,0x31,0x2c,0x22,0x75,0x6e,0x6b,0x6e,0x6f,0x77,0x6e,0x22,0x3a,0x6e,0x75,0x6c,0x6c,0x7d]), as: UTF8.self) + "]"
        #expect(messages.first?.questionPayloadJson == expected)
    }
}

@Suite struct QuestionNavigationTests {
    @Test func previousSkipNextSubmitOnce() {
        var form = QuestionForm(
            questions: [
                ClarifyingQuestion(id: "tone", question: "语气？", options: [QuestionOption(id: "direct", label: "直接")], kind: "select"),
                ClarifyingQuestion(id: "note", question: "补充？", kind: "text", optional: true),
            ])
        form.updateCurrent(selected: ["direct"])
        #expect(form.move(.next) == nil)
        #expect(form.index == 1)
        #expect(form.move(.previous) == nil)
        #expect(form.index == 0)
        _ = form.move(.next)
        #expect(form.move(.skip) == nil)
        #expect(form.answerBody?.answers.map { $0.id } == ["tone", "note"])
        #expect(form.answerBody?.answers[1].selected.isEmpty == true)
        let submitted = form.move(.submit)
        #expect(submitted?.answers.count == 2)
    }

    @Test func requiredQuestionCannotSkipOrSubmitEarly() {
        var form = QuestionForm(questions: [ClarifyingQuestion(id: "must", question: "必填", optional: false)])
        #expect(!form.canSkip)
        #expect(form.move(.submit) == nil)
        form.updateCurrent(custom: "好的")
        #expect(form.move(.submit)?.answers.first?.custom == "好的")
    }

    @Test func dropsUnknownSelectionsAndKeepsOneSingleChoice() {
        var form = QuestionForm(
            questions: [
                ClarifyingQuestion(
                    id: "tone", question: "语气？", options: [QuestionOption(id: "direct", label: "直接")],
                    kind: "select")
            ])
        form.updateCurrent(selected: ["missing", "direct", "extra"])
        #expect(form.move(.submit)?.answers.first?.selected == ["direct"])
    }
}

@MainActor @Suite struct ChatControlsServiceTests {
    @Test func olderHistoryGoalAndQuestionUseInjectedService() async throws {
        let service = ScriptedConversationService()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let model = ConversationModel(
            hostID: "host", sessionID: "session",
            service: service,
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory),
            prepared: PreparedTranscript(messages: [], running: false), autostart: false)
        model.testStatus.goal = SessionGoal(
            ref: SessionGoalRef(id: "goal", revision: 3), objective: "完成 A3", phase: .active)
        let payload = "[{\"id\":\"tone\",\"question\":\"语气？\",\"vendor\":{\"keep\":true}}]"
        model.testStatus.absorbQuestion(
            RequestMessage(
                id: "question-q", role: "question", questionRpcId: "q", questionPayloadJson: payload,
                requestStatus: .pending))
        var form = QuestionForm(questions: QuestionForm.questions(from: payload))
        form.updateCurrent(selected: ["direct"])
        let submitted = form.move(.submit)
        let body = try #require(submitted)
        try await model.serviceQuestion(rpcID: "q", answer: body)
        await model.serviceEditGoal(objective: "改目标", rounds: 4)
        #expect(model.testStatus.goal?.objective == "改目标")
        await model.serviceClearGoal()
        #expect(model.testStatus.goal == nil)
        model.seedForTest(
            messages: [
                HistoryMessage(id: "overlap", role: "user", kind: .user, text: "新副本"),
                HistoryMessage(id: "new", role: "user", kind: .user, text: "现在"),
            ], hasOlder: true, beforeSeq: 12)
        await model.loadOlder()
        let calls = await service.calls
        #expect(calls.contains(.question("q", body)))
        #expect(calls.contains(.edit("goal", 3, "改目标", 4)))
        #expect(calls.contains(.clear("goal", 3)))
        #expect(calls.contains(.history(12)))
        #expect(model.messages.map { $0.id } == ["old", "overlap", "new"])
        #expect(model.messages.first { $0.id == "overlap" }?.text == "旧副本")
        #expect(!model.hasOlder)
    }

    @Test func olderHistoryFailureKeepsMessagesAndCursor() async {
        let service = ScriptedConversationService(historyError: true)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let model = ConversationModel(
            hostID: "host", sessionID: "session", service: service,
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory),
            prepared: PreparedTranscript(messages: [], running: false), autostart: false)
        model.seedForTest(
            messages: [HistoryMessage(id: "new", role: "user", kind: .user, text: "现在")], hasOlder: true,
            beforeSeq: 12)
        await model.loadOlder()
        #expect(model.messages.map { $0.id } == ["new"])
        #expect(model.testOlderBeforeSeq == 12)
        #expect(model.olderFailed)
    }

    @Test func goalPauseFailureKeepsPhase() async {
        let service = ScriptedConversationService(pauseError: true)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        let model = ConversationModel(
            hostID: "host", sessionID: "session", service: service,
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory),
            prepared: PreparedTranscript(messages: [], running: false), autostart: false)
        model.testStatus.goal = SessionGoal(
            ref: SessionGoalRef(id: "goal", revision: 3), objective: "完成 A3", phase: .active)
        await model.servicePauseGoal()
        #expect(model.testStatus.goal?.phase == .active)
    }

    @Test func goalClearFailureKeepsGoal() async {
        let service = ScriptedConversationService(clearError: true)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let model = ConversationModel(
            hostID: "host", sessionID: "session", service: service,
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory),
            prepared: PreparedTranscript(messages: [], running: false), autostart: false)
        model.testStatus.goal = SessionGoal(
            ref: SessionGoalRef(id: "goal", revision: 3), objective: "完成 A3", phase: .active)
        await model.serviceClearGoal()
        #expect(model.testStatus.goal?.objective == "完成 A3")
    }

    @Test func laterUserMessageStopsQuestion() {
        let question = RequestMessage(id: "question-q", role: "question", questionRpcId: "q", requestStatus: .pending)
        let history = [
            HistoryMessage(id: "question-q", role: "question", kind: .role("question"), text: "语气？"),
            HistoryMessage(id: "later", role: "user", kind: .user, text: "先这样"),
        ]
        #expect(pendingPhoneDecision(history, requests: [question]) == nil)
    }
}

private actor ScriptedConversationService: ConversationServing {
    enum Call: Equatable {
        case history(Int?)
        case question(String, QuestionAnswerBody)
        case edit(String, Int, String, Int)
        case pause(String, Int, Bool)
        case clear(String, Int)
    }

    var historyError = false
    var pauseError = false
    var clearError = false
    private(set) var calls: [Call] = []

    init(historyError: Bool = false, pauseError: Bool = false, clearError: Bool = false) {
        self.historyError = historyError
        self.pauseError = pauseError
        self.clearError = clearError
    }

    func history(sessionID: String, beforeSeq: Int?) async throws -> HistoryResponse {
        _ = sessionID
        calls.append(.history(beforeSeq))
        if historyError { throw ConversationServiceError.failed }
        if beforeSeq == nil {
            return HistoryResponse(
                messages: [HistoryMessage(id: "new", role: "user", kind: .user, text: "现在")], hasMore: true,
                nextBeforeSeq: 12, maxSeq: 20)
        }
        return HistoryResponse(
            messages: [
                HistoryMessage(id: "old", role: "user", kind: .user, text: "更早"),
                HistoryMessage(id: "overlap", role: "user", kind: .user, text: "旧副本"),
            ], hasMore: false,
            nextBeforeSeq: nil, maxSeq: 11)
    }

    func submitQuestion(sessionID: String, rpcID: String, answer: QuestionAnswerBody) async throws {
        _ = sessionID
        calls.append(.question(rpcID, answer))
    }

    func editGoal(
        sessionID: String, refID: String, revision: Int, objective: String, rounds: Int
    ) async throws {
        _ = sessionID
        calls.append(.edit(refID, revision, objective, rounds))
    }

    func pauseGoal(sessionID: String, refID: String, revision: Int, resume: Bool) async throws {
        _ = sessionID
        calls.append(.pause(refID, revision, resume))
        if pauseError { throw ConversationServiceError.failed }
    }

    func clearGoal(sessionID: String, refID: String, revision: Int) async throws {
        _ = sessionID
        calls.append(.clear(refID, revision))
        if clearError { throw ConversationServiceError.failed }
    }
}
