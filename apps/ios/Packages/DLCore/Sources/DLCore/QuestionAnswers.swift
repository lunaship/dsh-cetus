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

/// C06 10.2.8：服务器校验把提交打回时，**定位到具体题**，让用户改完那一题再重试。
///
/// 以前所有错误都是一句通用的「提交失败」，用户不知道是哪一题错、要改什么。
/// 纯值类型 + 纯函数：不依赖 UI，便于单测，也便于后续扩到更多错误码。
public struct QuestionValidationError: Equatable, Sendable {
    /// 出错的题号（1 基，用于「第 N 题」文案）。nil 表示无法定位到题。
    public var questionIndex: Int?
    /// 出错的题目 id（服务端按 id 指认时优先用它定位）。
    public var questionID: String?
    /// 服务端给的原始错误码 / 字段名，用于排障，不直接展示给用户。
    public var code: String?

    public init(questionIndex: Int? = nil, questionID: String? = nil, code: String? = nil) {
        self.questionIndex = questionIndex
        self.questionID = questionID
        self.code = code
    }

    /// 能否定位到某一题。false 时调用方退回通用文案。
    public var isLocalized: Bool { questionIndex != nil || questionID != nil }
}

/// C06 10.2.8：从服务端错误里尽力定位到题。
///
/// 定位顺序：① 错误码/消息里出现的题目 id（最准）；② 错误码里的 `q<N>` / `#<N>` /
/// `question <N>` / `index <N>` 等 1 基题号。都拿不到就返回一个无法定位的实例，
/// 调用方显示通用文案——**不猜**，宁可少定位也不能指错题。
public func locateQuestionValidationError(
    _ error: any Error,
    questions: [ClarifyingQuestion]
) -> QuestionValidationError? {
    let raw = String(describing: error)
    let lowered = raw.lowercased()
    let ids = questions.enumerated().map { offset, question in
        (offset: offset + 1, id: question.id?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "")
    }
    // ① 题目 id 按**整词**出现（id 是服务端下发的稳定串，最准）。
    //
    // 必须用词边界而不是 `contains`：题目 id 常常很短（示例里就是 `a`/`b`），
    // 裸子串匹配会把 `missing_answer` 里的 `a` 当成题目 `a` 命中，
    // 于是把用户送到**错误的题**上——比不定位更糟。定位不到宁可退回通用文案。
    for entry in ids where !entry.id.isEmpty {
        if containsWholeWord(entry.id.lowercased(), in: lowered) {
            return QuestionValidationError(
                questionIndex: entry.offset, questionID: entry.id, code: questionErrorCodeToken(lowered))
        }
    }
    // ② q<N> / #<N> / question <N> / index <N> / 第<N>题（1 基）。
    if let index = firstQuestionNumber(in: lowered), index >= 1, index <= max(questions.count, 1) {
        let id = questions.indices.contains(index - 1) ? questions[index - 1].id : nil
        return QuestionValidationError(
            questionIndex: index, questionID: id, code: questionErrorCodeToken(lowered))
    }
    return QuestionValidationError(code: questionErrorCodeToken(lowered))
}

/// 整词匹配：`needle` 必须以非字母数字（或串首/串尾）为边界出现。
///
/// 用于题目 id 定位：短 id（如 `a`）用裸 `contains` 会命中 `missing_answer` 里
/// 的字母，把用户送到错误的题上。
private func containsWholeWord(_ needle: String, in haystack: String) -> Bool {
    guard !needle.isEmpty else { return false }
    var searchRange = haystack.startIndex..<haystack.endIndex
    while let found = haystack.range(of: needle, range: searchRange) {
        let beforeOK =
            found.lowerBound == haystack.startIndex
            || !haystack[haystack.index(before: found.lowerBound)].isLetter
                && !haystack[haystack.index(before: found.lowerBound)].isNumber
        let afterOK =
            found.upperBound == haystack.endIndex
            || !haystack[found.upperBound].isLetter && !haystack[found.upperBound].isNumber
        if beforeOK && afterOK { return true }
        guard found.upperBound < haystack.endIndex else { return false }
        searchRange = haystack.index(after: found.lowerBound)..<haystack.endIndex
    }
    return false
}

/// 从错误描述里抽一个短 token 作为错误码，供日志排障使用。
private func questionErrorCodeToken(_ lowered: String) -> String? {
    for needle in ["invalid_answer", "missing_answer", "invalid_option", "validation_failed", "bad_request"] {
        if lowered.contains(needle) { return needle }
    }
    return nil
}

/// 抽 1 基题号：`q1` / `#2` / `question 3` / `index 2` / `第3题`。
private func firstQuestionNumber(in lowered: String) -> Int? {
    let patterns = [
        #"(?<![a-z0-9])q(\d{1,3})(?![0-9])"#,
        #"#(\d{1,3})"#,
        #"question[\s_:#-]*(\d{1,3})"#,
        #"index[\s_:#-]*(\d{1,3})"#,
        #"第(\d{1,3})题"#,
    ]
    for pattern in patterns {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
        let range = NSRange(lowered.startIndex..., in: lowered)
        guard let match = regex.firstMatch(in: lowered, range: range), match.numberOfRanges > 1,
            let captured = Range(match.range(at: 1), in: lowered), let value = Int(lowered[captured])
        else { continue }
        return value
    }
    return nil
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

    /// C06 10.2.8：把当前题跳到服务器指认的那一题，让用户就地修改后重试。
    /// 越界或无法定位时不动，返回是否真的跳了。
    @discardableResult
    public mutating func moveToQuestion(_ error: QuestionValidationError) -> Bool {
        if let id = error.questionID?.trimmingCharacters(in: .whitespacesAndNewlines), !id.isEmpty {
            if let offset = questions.firstIndex(where: {
                Self.questionID($0, at: questions.firstIndex(of: $0) ?? 0) == id
            }) {
                index = offset
                return true
            }
        }
        if let number = error.questionIndex, questions.indices.contains(number - 1) {
            index = number - 1
            return true
        }
        return false
    }

    private static func isOptional(_ question: ClarifyingQuestion) -> Bool {
        question.optional == true
    }

    /// C06 10.2.2：已知题型。空 kind 视为已知（沿用旧行为，不给未知警告）。
    public static let knownKinds: Set<String> = ["select", "text", "input"]

    /// C06 10.2.2：未知题型。**不猜、不降级**——既不能默默提交空数组，也不能让
    /// 提交入口莫名点不动而没有任何说明。调用方据此显示安全文案并禁用提交。
    public static func isUnsupported(_ question: ClarifyingQuestion) -> Bool {
        let kind = kind(of: question)
        return !kind.isEmpty && !knownKinds.contains(kind)
    }

    private static func kind(of question: ClarifyingQuestion) -> String {
        question.kind?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
    }

    /// C06 10.2.2：整份表单里第一个未知题型的题号（1 基，供文案定位）；全部已知时 nil。
    public var firstUnsupportedIndex: Int? {
        questions.firstIndex(where: Self.isUnsupported).map { $0 + 1 }
    }

    /// C06 10.2.2：这份表单是否含未知题型（提交因此被完整拦下，不是静默跳过）。
    public var hasUnsupportedQuestion: Bool {
        questions.contains(where: Self.isUnsupported)
    }

    /// C06 10.2.3：无选项题直接显示编辑器，无需额外「自己写答案」开关。
    public static func hasOptions(_ question: ClarifyingQuestion) -> Bool {
        !(question.options ?? []).isEmpty
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
