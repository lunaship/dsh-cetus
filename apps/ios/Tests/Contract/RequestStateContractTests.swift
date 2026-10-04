import DLCore
import DLModels
import Foundation
import Testing

/// 合同测试（I3.6）：逐条跑仓库根 `testdata/request-state-cases.json`，断言 DLCore 的
/// 请求状态归并与语义来源 Android `RequestState.kt` 一致。快照在该文件里是
/// `GET .../requests` 的线上真实形状，这里用 DLModels 的 `RequestsSnapshotResponse`
/// 真实解析。另覆盖 `RequestStateReducer` 四种输入走同一条归并路径。
struct RequestStateContractTests {
    /// 消息字段名与 Android `MobileMessage` 属性一致；期望侧用 present 记录显式写出的
    /// 字段（含 null 期望），只断言写出的字段。
    private struct MessageSpec: Decodable {
        var present: Set<String> = []
        var id: String?
        var role: String?
        var text: String?
        var type: String?
        var toolName: String?
        var approvalId: String?
        var callId: String?
        var questionRpcId: String?
        var questionOptions: [String]?
        var questionHeader: String?
        var questionPayloadJson: String?
        var questionPayloadJsonContains: String?
        var takenOverByPhone: Bool?
        var requestStatus: String?
        var outcome: String?

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case id
            case role
            case text
            case type
            case toolName
            case approvalId
            case callId
            case questionRpcId
            case questionOptions
            case questionHeader
            case questionPayloadJson
            case questionPayloadJsonContains
            case takenOverByPhone
            case requestStatus
            case outcome
        }

        init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            for key in CodingKeys.allCases where container.contains(key) {
                present.insert(key.stringValue)
            }
            id = try container.decodeIfPresent(String.self, forKey: .id)
            role = try container.decodeIfPresent(String.self, forKey: .role)
            text = try container.decodeIfPresent(String.self, forKey: .text)
            type = try container.decodeIfPresent(String.self, forKey: .type)
            toolName = try container.decodeIfPresent(String.self, forKey: .toolName)
            approvalId = try container.decodeIfPresent(String.self, forKey: .approvalId)
            callId = try container.decodeIfPresent(String.self, forKey: .callId)
            questionRpcId = try container.decodeIfPresent(String.self, forKey: .questionRpcId)
            questionOptions = try container.decodeIfPresent([String].self, forKey: .questionOptions)
            questionHeader = try container.decodeIfPresent(String.self, forKey: .questionHeader)
            questionPayloadJson = try container.decodeIfPresent(String.self, forKey: .questionPayloadJson)
            questionPayloadJsonContains = try container.decodeIfPresent(
                String.self,
                forKey: .questionPayloadJsonContains
            )
            takenOverByPhone = try container.decodeIfPresent(Bool.self, forKey: .takenOverByPhone)
            requestStatus = try container.decodeIfPresent(String.self, forKey: .requestStatus)
            outcome = try container.decodeIfPresent(String.self, forKey: .outcome)
        }
    }

    /// parseSnapshot 期望记录；presence 语义同 MessageSpec。
    private struct RecordSpec: Decodable {
        var present: Set<String> = []
        var approvalId: String?
        var rpcId: String?
        var status: String?
        var outcome: String?
        var toolName: String?
        var callId: String?
        var questionPayloadJsonContains: String?

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case approvalId
            case rpcId
            case status
            case outcome
            case toolName
            case callId
            case questionPayloadJsonContains
        }

        init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            for key in CodingKeys.allCases where container.contains(key) {
                present.insert(key.stringValue)
            }
            approvalId = try container.decodeIfPresent(String.self, forKey: .approvalId)
            rpcId = try container.decodeIfPresent(String.self, forKey: .rpcId)
            status = try container.decodeIfPresent(String.self, forKey: .status)
            outcome = try container.decodeIfPresent(String.self, forKey: .outcome)
            toolName = try container.decodeIfPresent(String.self, forKey: .toolName)
            callId = try container.decodeIfPresent(String.self, forKey: .callId)
            questionPayloadJsonContains = try container.decodeIfPresent(
                String.self,
                forKey: .questionPayloadJsonContains
            )
        }
    }

    private struct SharedCase: Decodable {
        var name: String
        var op: String
        var outcome: String?
        var current: String?
        var incoming: String?
        var status: String?
        var expectStatus: String?
        var expectOutcome: String?
        var messages: [MessageSpec]?
        /// 线上真实形状，直接用 DLModels 真实解析器解码。
        var snapshot: RequestsSnapshotResponse?
        var expectMessages: [MessageSpec]?
        var expectApprovals: [RecordSpec]?
        var expectQuestions: [RecordSpec]?
    }

    private struct CaseList: Decodable {
        var cases: [SharedCase]
    }

    @Test func sharedCasesMatchAndroidImplementation() throws {
        let cases = try decodeSharedCases()
        #expect(cases.count >= 30, "用例清单太短，可能没读到文件")
        for item in cases {
            try runCase(item)
        }
    }

    private func runCase(_ item: SharedCase) throws {
        let name = item.name
        switch item.op {
        case "approvalUiStatus":
            let expected = try #require(item.expectStatus)
            #expect(
                approvalUiStatus(outcome: item.outcome) == RequestStatus.decoding(expected),
                "case \(name)"
            )
        case "mergeStatus":
            let expected = item.expectStatus.map(RequestStatus.decoding)
            let actual = mergeStatus(
                item.current.map(RequestStatus.decoding),
                item.incoming.map(RequestStatus.decoding)
            )
            #expect(actual == expected, "case \(name)")
        case "mergeOutcome":
            let actual = mergeOutcome(item.current, item.incoming, status: item.status.map(RequestStatus.decoding))
            #expect(actual == item.expectOutcome, "case \(name)")
        case "coalesce":
            try assertMessages(item, coalesce(try inputMessages(item)), name: name)
        case "applySnapshot":
            let snapshot = parseSessionRequestSnapshot(try #require(item.snapshot))
            let actual = applyRequestSnapshotToMessages(try inputMessages(item), snapshot: snapshot)
            try assertMessages(item, actual, name: name)
        case "mergeSnapshot":
            let snapshot = parseSessionRequestSnapshot(try #require(item.snapshot))
            let actual = mergeMessagesWithRequestSnapshot(try inputMessages(item), snapshot: snapshot)
            try assertMessages(item, actual, name: name)
        case "parseSnapshot":
            try assertParsedSnapshot(item, parseSessionRequestSnapshot(try #require(item.snapshot)), name: name)
        default:
            Issue.record("未知 op \(item.op)（case \(name)）")
        }
    }

    // MARK: - reducer 四种输入走同一条路

    @Test func reducerLiveResolvedThenPendingSnapshotStaysResolved() throws {
        var reducer = RequestStateReducer()
        reducer.absorb([
            RequestMessage(
                id: "approval-ap-1",
                role: "approval",
                text: "run",
                approvalId: "ap-1",
                requestStatus: .pending
            )
        ])
        // ① 实时流 approval/decided：pending → resolved
        reducer.resolveApproval(approvalId: "ap-1", outcome: "rejected")
        #expect(reducer.messages.count == 1)
        #expect(reducer.messages.first?.requestStatus == .resolved)
        #expect(reducer.messages.first?.outcome == "rejected")
        // ②/③ 迟到的 pending 快照（重连 / GET .../requests）不得回滚终态
        let payload = #"{"approvals":[{"approvalId":"ap-1","status":"pending","toolName":"bash"}]}"#
        let snapshot = try JSONDecoder().decode(RequestsSnapshotResponse.self, from: Data(payload.utf8))
        reducer.merge(snapshot: snapshot)
        #expect(reducer.messages.count == 1)
        #expect(reducer.messages.first?.requestStatus == .resolved)
        #expect(reducer.messages.first?.outcome == "rejected")
    }

    @Test func reducerSubmitThenStalePendingStreamDoesNotRollBack() {
        var reducer = RequestStateReducer()
        reducer.absorb([
            RequestMessage(
                id: "approval-ap-2",
                role: "approval",
                text: "run",
                approvalId: "ap-2",
                requestStatus: .pending
            )
        ])
        // ④ 提交响应（approval-submit.json 形状）
        let submit = RequestSubmitResponse(
            ok: true,
            accepted: true,
            alreadySettled: nil,
            outcome: "allowed-once",
            status: .resolved,
            handledBy: "plugin"
        )
        reducer.apply(submit: submit, requestId: "ap-2")
        #expect(reducer.messages.first?.requestStatus == .resolved)
        // ① 迟到的旧 pending 流事件（approval/asked 重放）：不回滚，也并成同一条
        reducer.absorb([
            RequestMessage(
                id: "approval-late",
                role: "approval",
                text: "run",
                approvalId: "ap-2",
                requestStatus: .pending
            )
        ])
        #expect(reducer.messages.count == 1)
        #expect(reducer.messages.first?.requestStatus == .resolved)
        #expect(reducer.messages.first?.outcome == "allowed-once")
    }

    @Test func reducerFourInputKindsConvergeOnOneState() {
        var reducer = RequestStateReducer(messages: [RequestMessage(id: "msg-1", role: "user", text: "hi")])
        // ① 实时流：pending 审批卡 + 提问卡
        reducer.absorb([
            RequestMessage(
                id: "approval-a1",
                role: "approval",
                text: "run",
                toolName: "bash",
                approvalId: "a1",
                requestStatus: .pending
            ),
            RequestMessage(
                id: "question-q1",
                role: "question",
                text: "选一个",
                questionRpcId: "q1",
                requestStatus: .pending
            ),
        ])
        #expect(reducer.messages.count == 3)
        // ② 重连快照：补状态 + 打手机接管标记
        reducer.apply(
            snapshot: RequestsSnapshotResponse(
                approvals: [PendingApproval(approvalId: "a1", status: .pending, toolName: "bash")]
            )
        )
        #expect(reducer.messages.first { $0.approvalId == "a1" }?.takenOverByPhone == true)
        // ③ GET .../requests 快照：审批落终态
        reducer.merge(
            snapshot: RequestsSnapshotResponse(
                approvals: [PendingApproval(approvalId: "a1", status: .resolved, outcome: "rejected")]
            )
        )
        // ④ 提交响应：提问作答成功（question-submit.json 不带 status/outcome，按 resolved 处理）
        reducer.apply(submit: RequestSubmitResponse(ok: true, accepted: true), requestId: "q1")
        #expect(reducer.messages.count == 3)
        #expect(reducer.messages.first { $0.approvalId == "a1" }?.requestStatus == .resolved)
        #expect(reducer.messages.first { $0.approvalId == "a1" }?.outcome == "rejected")
        #expect(reducer.messages.first { $0.questionRpcId == "q1" }?.requestStatus == .resolved)
    }

    // MARK: - 断言与解析

    private func assertMessages(_ item: SharedCase, _ actual: [RequestMessage], name: String) throws {
        let expected = try #require(item.expectMessages)
        #expect(expected.count == actual.count, "case \(name)：条数应一致")
        for (index, exp) in expected.enumerated() where index < actual.count {
            let got = actual[index]
            for key in exp.present.sorted() {
                let context: Comment = "case \(name) [\(index)] \(key)"
                switch key {
                case "id": #expect(got.id == exp.id, context)
                case "role": #expect(got.role == exp.role, context)
                case "text": #expect(got.text == exp.text, context)
                case "type": #expect(got.type == exp.type, context)
                case "toolName": #expect(got.toolName == exp.toolName, context)
                case "approvalId": #expect(got.approvalId == exp.approvalId, context)
                case "callId": #expect(got.callId == exp.callId, context)
                case "questionRpcId": #expect(got.questionRpcId == exp.questionRpcId, context)
                case "questionHeader": #expect(got.questionHeader == exp.questionHeader, context)
                case "questionPayloadJson": #expect(got.questionPayloadJson == exp.questionPayloadJson, context)
                case "questionOptions": #expect(got.questionOptions == (exp.questionOptions ?? []), context)
                case "takenOverByPhone": #expect(got.takenOverByPhone == (exp.takenOverByPhone ?? false), context)
                case "requestStatus":
                    #expect(got.requestStatus == exp.requestStatus.map(RequestStatus.decoding), context)
                case "outcome": #expect(got.outcome == exp.outcome, context)
                case "questionPayloadJsonContains":
                    let needle = exp.questionPayloadJsonContains ?? ""
                    #expect(got.questionPayloadJson?.contains(needle) == true, context)
                default:
                    Issue.record("未知期望字段 \(key)（case \(name)）")
                }
            }
        }
    }

    private func assertParsedSnapshot(
        _ item: SharedCase,
        _ snapshot: SessionRequestSnapshot,
        name: String
    ) throws {
        let expectedApprovals = item.expectApprovals ?? []
        #expect(snapshot.approvals.count == expectedApprovals.count, "case \(name)：approvals 条数")
        for (index, expected) in expectedApprovals.enumerated() where index < snapshot.approvals.count {
            let record = snapshot.approvals[index]
            for key in expected.present.sorted() {
                let context: Comment = "case \(name) approvals[\(index)] \(key)"
                switch key {
                case "approvalId": #expect(record.id == expected.approvalId, context)
                case "rpcId": #expect(record.id == expected.rpcId, context)
                case "status": #expect(record.status == expected.status.map(RequestStatus.decoding), context)
                case "outcome": #expect(record.outcome == expected.outcome, context)
                case "toolName": #expect(record.toolName == expected.toolName, context)
                case "callId": #expect(record.callId == expected.callId, context)
                default:
                    Issue.record("未知期望字段 \(key)（case \(name)）")
                }
            }
        }
        let expectedQuestions = item.expectQuestions ?? []
        #expect(snapshot.questions.count == expectedQuestions.count, "case \(name)：questions 条数")
        for (index, expected) in expectedQuestions.enumerated() where index < snapshot.questions.count {
            let record = snapshot.questions[index]
            for key in expected.present.sorted() {
                let context: Comment = "case \(name) questions[\(index)] \(key)"
                switch key {
                case "approvalId": #expect(record.id == expected.approvalId, context)
                case "rpcId": #expect(record.id == expected.rpcId, context)
                case "status": #expect(record.status == expected.status.map(RequestStatus.decoding), context)
                case "outcome": #expect(record.outcome == expected.outcome, context)
                case "questionPayloadJsonContains":
                    // Android 比较序列化串，iOS 记录是解码后的 questions：断言题面内容被保留。
                    let needle = expected.questionPayloadJsonContains ?? ""
                    let kept = (record.questions ?? []).contains { question in
                        [question.id, question.header, question.question]
                            .contains { $0?.contains(needle) == true }
                    }
                    #expect(kept, context)
                default:
                    Issue.record("未知期望字段 \(key)（case \(name)）")
                }
            }
        }
    }

    private func inputMessages(_ item: SharedCase) throws -> [RequestMessage] {
        (try #require(item.messages)).map(inputMessage(from:))
    }

    private func inputMessage(from spec: MessageSpec) -> RequestMessage {
        RequestMessage(
            id: spec.id ?? "",
            role: spec.role ?? "",
            text: spec.text ?? "",
            type: spec.type ?? "text",
            toolName: spec.toolName,
            approvalId: spec.approvalId,
            callId: spec.callId,
            takenOverByPhone: spec.takenOverByPhone ?? false,
            questionRpcId: spec.questionRpcId,
            questionOptions: spec.questionOptions ?? [],
            questionHeader: spec.questionHeader,
            questionPayloadJson: spec.questionPayloadJson,
            requestStatus: spec.requestStatus.map(RequestStatus.decoding),
            outcome: spec.outcome
        )
    }

    private func decodeSharedCases() throws -> [SharedCase] {
        let url = repoRoot()
            .appendingPathComponent("testdata")
            .appendingPathComponent("request-state-cases.json")
        return try JSONDecoder().decode(CaseList.self, from: Data(contentsOf: url)).cases
    }

    private func repoRoot() -> URL {
        // …/apps/ios/Tests/Contract/<file>.swift 逐级上溯 5 层到仓库根
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}
