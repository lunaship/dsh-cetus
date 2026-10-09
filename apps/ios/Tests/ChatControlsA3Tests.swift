import DLCore
import DLModels
import DLSecurity
import Foundation
import Testing

@testable import Cetus

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
        let expected =
            "["
            + String(
                decoding: Data([
                    0x7b, 0x22, 0x69, 0x64, 0x22, 0x3a, 0x22, 0x74, 0x6f, 0x6e, 0x65, 0x22, 0x2c, 0x22, 0x7a, 0x22,
                    0x3a, 0x31, 0x2c, 0x22, 0x75, 0x6e, 0x6b, 0x6e, 0x6f, 0x77, 0x6e, 0x22, 0x3a, 0x6e, 0x75, 0x6c,
                    0x6c, 0x7d,
                ]), as: UTF8.self) + "]"
        #expect(messages.first?.questionPayloadJson == expected)
    }
}

@Suite struct QuestionNavigationTests {
    @Test func previousSkipNextSubmitOnce() {
        var form = QuestionForm(
            questions: [
                ClarifyingQuestion(
                    id: "tone", question: "语气？", options: [QuestionOption(id: "direct", label: "直接")], kind: "select"),
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
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
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
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
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
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
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

// MARK: - T15：连续加载三页历史

/// 方案的 T15 要求「连续加载三页历史 → 不重复，位置稳定，失败可重试」。
///
/// 此前只有**单页**用例。三页连拉才会暴露游标问题：如果 `nextBeforeSeq`
/// 没有随每页前进（或 `hasOlder` 没被正确置位），第二页会重复请求同一段，
/// 表现为列表里出现重复消息、或"点了没反应"。
///
/// 这里用按游标返回不同页的服务，逐页断言：请求的游标在前进、消息不重复、
/// 顺序保持、最后一页正确停止。
@MainActor @Suite(.serialized) struct MultiPageHistoryTests {
    /// 按 `beforeSeq` 返回对应的一页，模拟真实分页。
    private actor PagingService: ConversationServing {
        var requestedBefore: [Int?] = []
        func history(sessionID: String, beforeSeq: Int?) async throws -> HistoryResponse {
            _ = sessionID
            requestedBefore.append(beforeSeq)
            switch beforeSeq {
            case nil:
                // 尾页：seq 30 起。
                return HistoryResponse(
                    messages: [HistoryMessage(id: "m30", role: "user", kind: .user, text: "现在")],
                    hasMore: true, nextBeforeSeq: 20, maxSeq: 30)
            case 20:
                return HistoryResponse(
                    messages: [
                        HistoryMessage(id: "m20", role: "user", kind: .user, text: "较早"),
                        HistoryMessage(id: "m19", role: "user", kind: .user, text: "更早一点"),
                    ],
                    hasMore: true, nextBeforeSeq: 10, maxSeq: 29)
            case 10:
                return HistoryResponse(
                    messages: [HistoryMessage(id: "m10", role: "user", kind: .user, text: "最早")],
                    hasMore: false, nextBeforeSeq: nil, maxSeq: 29)
            default:
                // 不该被请求：说明游标没前进。
                return HistoryResponse(messages: [], hasMore: false, nextBeforeSeq: nil, maxSeq: 29)
            }
        }
    }

    private func makeModel(_ service: PagingService) -> ConversationModel {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        return ConversationModel(
            hostID: "host", sessionID: "session", service: service,
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory),
            prepared: PreparedTranscript(messages: [], running: false), autostart: false)
    }

    @Test func threePagesLoadWithoutDuplicates() async throws {
        let service = PagingService()
        let model = makeModel(service)

        // 第一页（尾页由 seed 提供，模拟已加载）。
        model.seedForTest(
            messages: [
                HistoryMessage(id: "m30", role: "user", kind: .user, text: "现在")
            ], hasOlder: true, beforeSeq: 20)

        await model.loadOlder()
        #expect(model.messages.map(\.id) == ["m20", "m19", "m30"], "第二页应前插且顺序正确")

        await model.loadOlder()
        #expect(model.messages.map(\.id) == ["m10", "m20", "m19", "m30"], "第三页应继续前插")

        // 游标必须一路前进，不能重复请求同一段。
        let asked = await service.requestedBefore
        #expect(asked == [20, 10], "游标应 20 → 10 前进，实际 \(asked)")

        // 没有更早的了 → 再点不应发请求。
        #expect(!model.hasOlder)
        await model.loadOlder()
        let after = await service.requestedBefore
        #expect(after == [20, 10], "hasOlder=false 后不应再请求，实际 \(after)")
    }

    /// 每页都不重复：三页合并后 ID 唯一。
    @Test func mergedPagesHaveUniqueIDs() async {
        let service = PagingService()
        let model = makeModel(service)
        model.seedForTest(
            messages: [
                HistoryMessage(id: "m30", role: "user", kind: .user, text: "现在")
            ], hasOlder: true, beforeSeq: 20)
        await model.loadOlder()
        await model.loadOlder()
        let ids = model.messages.map(\.id)
        #expect(Set(ids).count == ids.count, "合并后出现重复消息：\(ids)")
    }

    /// 中途失败后重试必须从**同一个**游标继续，而不是跳过一页。
    @Test func failureThenRetryKeepsCursor() async {
        let service = FlakyPagingService()
        let model = makeModel2(service)
        await model.loadOlder()
        #expect(model.olderFailed, "首次应失败")
        // 游标不被失败推进。
        await model.loadOlder()
        let asked = await service.requestedBefore
        #expect(asked == [20, 20], "重试必须用同一游标，实际 \(asked)")
        #expect(model.messages.contains { $0.id == "m20" }, "重试成功后应拿到该页")
    }

    private actor FlakyPagingService: ConversationServing {
        var requestedBefore: [Int?] = []
        private var first = true
        func history(sessionID: String, beforeSeq: Int?) async throws -> HistoryResponse {
            _ = sessionID
            requestedBefore.append(beforeSeq)
            if first {
                first = false
                throw ConversationServiceError.failed
            }
            return HistoryResponse(
                messages: [HistoryMessage(id: "m20", role: "user", kind: .user, text: "较早")],
                hasMore: true, nextBeforeSeq: 10, maxSeq: 29)
        }
    }

    private func makeModel2(_ service: FlakyPagingService) -> ConversationModel {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        let model = ConversationModel(
            hostID: "host", sessionID: "session", service: service,
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory),
            prepared: PreparedTranscript(messages: [], running: false), autostart: false)
        model.seedForTest(
            messages: [
                HistoryMessage(id: "m30", role: "user", kind: .user, text: "现在")
            ], hasOlder: true, beforeSeq: 20)
        return model
    }
}
