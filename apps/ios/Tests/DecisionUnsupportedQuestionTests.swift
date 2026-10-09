import DLCore
import DLModels
import Foundation
import Testing

/// C06 §10.2.2：未知题型必须**有用户可见说明**且**不得静默提交空数组**。
@Suite struct DecisionUnsupportedQuestionTests {
    private func question(_ id: String, kind: String?) -> ClarifyingQuestion {
        ClarifyingQuestion(id: id, question: "题", kind: kind)
    }

    @Test func knownKindsAreSupported() {
        for kind in ["select", "text", "input"] {
            #expect(!QuestionForm.isUnsupported(question("a", kind: kind)), "\(kind) 应视为已知")
        }
    }

    @Test func absentKindStaysSupported() {
        // 空 kind 沿用旧行为：已知，不给未知警告。
        #expect(!QuestionForm.isUnsupported(question("a", kind: nil)))
        #expect(!QuestionForm.isUnsupported(question("a", kind: "   ")))
    }

    @Test func kindComparisonIsCaseAndWhitespaceInsensitive() {
        #expect(!QuestionForm.isUnsupported(question("a", kind: " SELECT ")))
        #expect(!QuestionForm.isUnsupported(question("a", kind: "Text")))
    }

    @Test func unknownKindIsUnsupported() {
        for kind in ["rank", "matrix", "slider", "unknown-type"] {
            #expect(QuestionForm.isUnsupported(question("a", kind: kind)), "\(kind) 应视为未知")
        }
    }

    @Test func formReportsFirstUnsupportedAtIndexOneBased() {
        let form = QuestionForm(questions: [
            question("a", kind: "select"),
            question("b", kind: "slider"),
            question("c", kind: "rank"),
        ])
        #expect(form.hasUnsupportedQuestion)
        #expect(form.firstUnsupportedIndex == 2)
    }

    @Test func fullyKnownFormHasNoUnsupported() {
        let form = QuestionForm(questions: [question("a", kind: "select"), question("b", kind: "text")])
        #expect(!form.hasUnsupportedQuestion)
        #expect(form.firstUnsupportedIndex == nil)
    }

    /// 核心：未知题型**不能**产出提交体（哪怕其他题都答了）。
    @Test func unknownKindBlocksSubmissionEntirely() {
        var form = QuestionForm(questions: [
            ClarifyingQuestion(id: "a", question: "第一题", kind: "text"),
            ClarifyingQuestion(id: "b", question: "第二题", kind: "slider"),
        ])
        form.updateCurrent(custom: "答案 A")
        _ = form.moveToQuestion(QuestionValidationError(questionIndex: 2))
        form.updateCurrent(custom: "随便写点什么")
        #expect(!form.isComplete)
        #expect(form.answerBody == nil, "未知题型绝不能默默提交空数组")
        #expect(form.move(.submit) == nil)
    }

    @Test func knownKindsStillSubmit() {
        var form = QuestionForm(questions: [ClarifyingQuestion(id: "a", question: "题", kind: "text")])
        form.updateCurrent(custom: "答案")
        #expect(form.answerBody?.answers.count == 1)
    }
}

// MARK: - C14：两端 optional/required 语义必须一致

@Suite struct RequiredFieldNormalizationTests {
    private func decode(_ json: String) throws -> ClarifyingQuestion {
        try JSONDecoder().decode(ClarifyingQuestion.self, from: Data(json.utf8))
    }

    /// 插件与 Android 都把 `required: false` 当作可选。iOS 之前只看 `optional`，
    /// 同一道题在 Android 能跳过、在 iOS 卡住 —— 这是两端语义不一致。
    @Test("required=false 等价于可选")
    func requiredFalseMeansOptional() throws {
        let question = try decode(#"{"id":"q1","question":"题","kind":"text","required":false}"#)
        #expect(question.optional == true)
    }

    /// `required: true` 不能把显式的 `optional: true` 覆盖掉。
    @Test("显式 optional 优先于 required")
    func explicitOptionalWins() throws {
        let question = try decode(
            #"{"id":"q1","question":"题","kind":"text","optional":true,"required":true}"#)
        #expect(question.optional == true)
    }

    /// 两者都没给时保持未指定，沿用既有「必填」判定。
    @Test("未给任何字段时不做推断")
    func absentStaysUnspecified() throws {
        let question = try decode(#"{"id":"q1","question":"题","kind":"text"}"#)
        #expect(question.optional == nil)
    }

    /// `required: true` 单独出现也不应被当成可选。
    @Test("required=true 不产生 optional")
    func requiredTrueStaysRequired() throws {
        let question = try decode(#"{"id":"q1","question":"题","kind":"text","required":true}"#)
        #expect(question.optional == nil)
    }
}
