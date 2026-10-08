import DLCore
import DLModels
import Foundation
import Testing

/// C06 §10.2.8：服务器校验错误要**定位到具体题**，用户改完可重试；
/// 定位不到时**不猜**，退回通用文案。
@Suite struct DecisionValidationErrorTests {
    private struct ServerError: Error, CustomStringConvertible {
        let description: String
    }

    private func questions() -> [ClarifyingQuestion] {
        [
            ClarifyingQuestion(id: "alpha", question: "第一题"),
            ClarifyingQuestion(id: "beta", question: "第二题"),
            ClarifyingQuestion(id: "gamma", question: "第三题"),
        ]
    }

    // MARK: 定位

    @Test func locatesByQuestionID() {
        let located = locateQuestionValidationError(
            ServerError(description: "invalid_answer: beta"), questions: questions())
        #expect(located?.questionID == "beta")
        #expect(located?.questionIndex == 2)
        #expect(located?.isLocalized == true)
        #expect(located?.code == "invalid_answer")
    }

    @Test func locatesByQNumber() {
        let located = locateQuestionValidationError(
            ServerError(description: "bad_request: q3 is empty"), questions: questions())
        #expect(located?.questionIndex == 3)
        #expect(located?.questionID == "gamma")
    }

    @Test func locatesByHashNumber() {
        let located = locateQuestionValidationError(
            ServerError(description: "field #1 rejected"), questions: questions())
        #expect(located?.questionIndex == 1)
    }

    @Test func locatesByQuestionWordAndChineseForm() {
        #expect(
            locateQuestionValidationError(ServerError(description: "question 2 required"), questions: questions())?
                .questionIndex == 2)
        #expect(
            locateQuestionValidationError(ServerError(description: "第2题没答"), questions: questions())?.questionIndex == 2
        )
    }

    @Test func prefersQuestionIDOverNumber() {
        // id 命中优先：即使串里也有别的数字，也按 id 定位（更准）。
        let located = locateQuestionValidationError(
            ServerError(description: "invalid_option for gamma (index 1)"), questions: questions())
        #expect(located?.questionID == "gamma")
        #expect(located?.questionIndex == 3)
    }

    // MARK: 不猜

    @Test func cannotLocateReturnsNonLocalized() {
        let located = locateQuestionValidationError(
            ServerError(description: "validation_failed"), questions: questions())
        #expect(located != nil)
        #expect(located?.isLocalized == false)
        #expect(located?.questionIndex == nil)
        #expect(located?.questionID == nil)
        #expect(located?.code == "validation_failed")
    }

    @Test func outOfRangeNumberIsNotLocalized() {
        let located = locateQuestionValidationError(
            ServerError(description: "q99 failed"), questions: questions())
        #expect(located?.isLocalized == false, "越界题号不能指到不存在的题")
    }

    @Test func extractsKnownErrorCode() {
        #expect(
            locateQuestionValidationError(ServerError(description: "missing_answer"), questions: questions())?.code
                == "missing_answer")
        #expect(
            locateQuestionValidationError(ServerError(description: "invalid_option"), questions: questions())?.code
                == "invalid_option")
    }

    // MARK: 跳转

    @Test func moveToQuestionJumpsByIndexAndReturnsTrue() {
        var form = QuestionForm(questions: questions())
        let moved = form.moveToQuestion(QuestionValidationError(questionIndex: 3))
        #expect(moved)
        #expect(form.index == 2)
    }

    @Test func moveToQuestionJumpsByID() {
        var form = QuestionForm(questions: questions())
        let moved = form.moveToQuestion(QuestionValidationError(questionID: "beta"))
        #expect(moved)
        #expect(form.index == 1)
    }

    @Test func moveToQuestionIgnoresOutOfRange() {
        var form = QuestionForm(questions: questions())
        form.moveToQuestion(QuestionValidationError(questionIndex: 2))
        // `moveToQuestion` 是 mutating：必须在 #expect 之外调用，
        // 否则宏会捕获一份不可变副本，既编译不过也测不到真实状态。
        let outOfRange = form.moveToQuestion(QuestionValidationError(questionIndex: 99))
        let unlocatable = form.moveToQuestion(QuestionValidationError())
        #expect(!outOfRange)
        #expect(!unlocatable)
        #expect(form.index == 1, "定位失败时不能改动当前题")
    }

    /// 定位 → 跳题 → 修改 → 可重试：这是 10.2.8 的完整闭环。
    @Test func locateThenFixThenSubmit() {
        var form = QuestionForm(questions: [
            ClarifyingQuestion(id: "a", question: "第一题", kind: "text"),
            ClarifyingQuestion(id: "b", question: "第二题", kind: "text"),
        ])
        form.updateCurrent(custom: "答案 A")
        _ = form.move(.next)
        // 第二题没答 → 服务器打回
        let located = locateQuestionValidationError(
            ServerError(description: "missing_answer q2"), questions: form.questions)
        #expect(located?.questionIndex == 2)
        let movedToLocated = form.moveToQuestion(located!)
        #expect(movedToLocated)
        #expect(form.index == 1)
        #expect(!form.isComplete)
        // 用户就地补答案 → 可提交
        form.updateCurrent(custom: "答案 B")
        #expect(form.isComplete)
        #expect(form.answerBody?.answers.count == 2)
    }
}
