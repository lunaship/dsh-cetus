import DLModels
import Foundation

/// 多题草稿与一次提交（A3.6 / A3.11）。对齐 Android `QuestionAnswers.kt`：
/// 只展示并回答 payload 里的题，可选可跳过，最终一次提交 `{ answers: [...] }`。
public struct QuestionDraft: Codable, Equatable, Sendable {
    public var selected: [String]
    public var custom: String

    public init(selected: [String] = [], custom: String = "") {
        self.selected = selected
        self.custom = custom
    }
}

public struct QuestionAnswerItem: Equatable, Sendable, Encodable {
    public var id: String
    public var selected: [String]
    public var custom: String?

    public init(id: String, selected: [String], custom: String? = nil) {
        self.id = id
        self.selected = selected
        self.custom = custom
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(selected, forKey: .selected)
        if let custom, !custom.isEmpty { try container.encode(custom, forKey: .custom) }
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case selected
        case custom
    }
}

public struct QuestionAnswerBody: Equatable, Sendable, Encodable {
    public var answers: [QuestionAnswerItem]

    public init(answers: [QuestionAnswerItem]) {
        self.answers = answers
    }
}

public enum QuestionNavigation: Equatable, Sendable {
    case previous
    case skip
    case next
    case submit
}

public struct QuestionForm: Equatable, Sendable {
    public var questions: [ClarifyingQuestion]
    public var index: Int
    public var drafts: [String: QuestionDraft]

    public init(questions: [ClarifyingQuestion], index: Int = 0, drafts: [String: QuestionDraft] = [:]) {
        self.questions = questions
        self.index = questions.isEmpty ? 0 : min(max(0, index), questions.count - 1)
        self.drafts = drafts
    }

    public var current: ClarifyingQuestion? {
        questions.indices.contains(index) ? questions[index] : nil
    }

    public var canGoBack: Bool { index > 0 }
    /// 跳过只给可选题。必填题的次按钮是上一题，第一题时不可用。
    public var canSkip: Bool { current.map(Self.isOptional) == true }
    public var secondaryIsPrevious: Bool { !canSkip }
    public var isLast: Bool { !questions.isEmpty && index == questions.count - 1 }

    public func draft(for question: ClarifyingQuestion) -> QuestionDraft {
        drafts[Self.key(question, at: questions.firstIndex(of: question) ?? index)] ?? QuestionDraft()
    }

    public mutating func updateCurrent(selected: [String]? = nil, custom: String? = nil) {
        guard let question = current else { return }
        let key = Self.key(question, at: index)
        var draft = drafts[key] ?? QuestionDraft()
        if let selected { draft.selected = selected }
        if let custom { draft.custom = custom }
        drafts[key] = draft
    }

    /// 上一题保留草稿。跳过只对可选题清空并前进。最后一题且必填完整时才能提交。
    public mutating func move(_ action: QuestionNavigation) -> QuestionAnswerBody? {
        switch action {
        case .previous:
            if canGoBack { index -= 1 }
        case .skip:
            guard canSkip, let question = current else { return nil }
            drafts[Self.key(question, at: index)] = QuestionDraft()
            if isLast { return nil }
            index += 1
        case .next:
            guard !isLast, currentCanAdvance else { return nil }
            index += 1
        case .submit:
            guard isLast, isComplete, let body = answerBody else { return nil }
            return body
        }
        return nil
    }

    public var currentCanAdvance: Bool {
        guard let question = current else { return false }
        return Self.isOptional(question) || Self.answered(question, draft: draft(for: question))
    }

    public var currentIsAnswered: Bool {
        guard let question = current else { return false }
        return Self.answered(question, draft: draft(for: question))
    }

    public var isComplete: Bool {
        !questions.isEmpty && !questions.contains(where: Self.isUnsupported)
            && questions.enumerated().allSatisfy { offset, question in
                Self.isOptional(question)
                    || Self.answered(
                        question, draft: drafts[Self.key(question, at: offset)] ?? QuestionDraft())
            }
    }

    public var answerBody: QuestionAnswerBody? {
        guard isComplete else { return nil }
        let items = questions.enumerated().map { offset, question in
            let draft = drafts[Self.key(question, at: offset)] ?? QuestionDraft()
            let custom = draft.custom.trimmingCharacters(in: .whitespacesAndNewlines)
            return QuestionAnswerItem(
                id: Self.questionID(question, at: offset), selected: Self.allowed(draft.selected, question: question),
                custom: custom.isEmpty ? nil : custom)
        }
        return QuestionAnswerBody(answers: items)
    }

    public static func questions(from payload: String?) -> [ClarifyingQuestion] {
        guard let payload, let data = payload.data(using: .utf8) else { return [] }
        return (try? JSONDecoder().decodePreservingRawJSON([ClarifyingQuestion].self, from: data)) ?? []
    }

    private static func isOptional(_ question: ClarifyingQuestion) -> Bool {
        question.optional == true
    }

    private static func isUnsupported(_ question: ClarifyingQuestion) -> Bool {
        let kind = question.kind?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return !kind.isEmpty && kind != "select" && kind != "text" && kind != "input"
    }

    private static func answered(_ question: ClarifyingQuestion, draft: QuestionDraft) -> Bool {
        if isUnsupported(question) { return false }
        let custom = draft.custom.trimmingCharacters(in: .whitespacesAndNewlines)
        if custom.count > 8_000 { return false }
        return !Self.allowed(draft.selected, question: question).isEmpty || !custom.isEmpty
    }

    private static func allowed(_ selected: [String], question: ClarifyingQuestion) -> [String] {
        let values = Set((question.options ?? []).compactMap { Self.optionValue($0) })
        let kept = selected.filter { values.isEmpty || values.contains($0) }
        return question.multiple == true ? kept : Array(kept.prefix(1))
    }

    public static func optionValue(_ option: QuestionOption) -> String? {
        for candidate in [option.id, option.value, option.label] {
            let trimmed = candidate?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !trimmed.isEmpty { return trimmed }
        }
        return nil
    }

    private static func questionID(_ question: ClarifyingQuestion, at index: Int) -> String {
        let id = question.id?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return id.isEmpty ? "q\(index)" : id
    }

    private static func key(_ question: ClarifyingQuestion, at index: Int) -> String {
        questionID(question, at: index)
    }

    /// 当前题的自由回答。与普通消息草稿完全分开（C06 要求 10.2.6）。
    public var currentCustom: String {
        current.map { draft(for: $0).custom } ?? ""
    }

    /// 落盘用的快照：按 rpcID 归属，每题草稿按题目 ID 存（C06 要求 10.2.4）。
    public func snapshot(rpcID: String) -> QuestionFormSnapshot {
        QuestionFormSnapshot(rpcID: rpcID, index: index, drafts: drafts)
    }

    /// 只恢复同一个问题请求的草稿；rpcID 不符（已换题）一律丢弃。
    public mutating func restore(_ snapshot: QuestionFormSnapshot?, rpcID: String) {
        guard let snapshot, snapshot.rpcID == rpcID else { return }
        drafts = snapshot.drafts
        index = questions.isEmpty ? 0 : min(max(0, snapshot.index), questions.count - 1)
    }
}

/// 问题回答草稿的落盘形状。存进会话的 answer 槽位，不碰普通消息草稿。
public struct QuestionFormSnapshot: Codable, Equatable, Sendable {
    public var rpcID: String
    public var index: Int
    public var drafts: [String: QuestionDraft]

    public init(rpcID: String, index: Int, drafts: [String: QuestionDraft]) {
        self.rpcID = rpcID
        self.index = index
        self.drafts = drafts
    }

    /// 没有任何内容时返回空串：草稿仓库把空串当删除。
    public var encoded: String {
        let hasContent = drafts.values.contains { !$0.selected.isEmpty || !$0.custom.isEmpty }
        guard hasContent, let data = try? JSONEncoder().encode(self) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    public static func decode(_ text: String) -> QuestionFormSnapshot? {
        guard !text.isEmpty else { return nil }
        return try? JSONDecoder().decode(QuestionFormSnapshot.self, from: Data(text.utf8))
    }
}
