import Foundation
import Testing

@testable import Cetus

/// C03：发送后的清空策略（方案 §7）。
///
/// 这些是**纯逻辑**判定，不依赖网络，所以能在 Linux/CI 上跑；
/// 真实延迟与竞争场景仍需真机（见 docs/cetus/DELIVERY.md 的未验证项）。
@Suite struct SubmissionResolverTests {
    /// 方案验收用例：发 A 后写 B，A 成功时 **B 仍在**。
    ///
    /// 提交时 revision = 0；等待期间用户又写了内容 → revision 前进到 1。
    /// 此时不应清空。
    @Test func doesNotClearWhenUserTypedDuringSubmit() {
        #expect(SubmissionResolver.shouldClear(submitted: 0, current: 1) == false)
        #expect(SubmissionResolver.shouldClearAttachments(submitted: 0, current: 1) == false)
    }

    /// 提交后没再动过 → 正常清空。
    @Test func clearsWhenDraftUntouchedDuringSubmit() {
        #expect(SubmissionResolver.shouldClear(submitted: 3, current: 3) == true)
        #expect(SubmissionResolver.shouldClearAttachments(submitted: 3, current: 3) == true)
    }

    /// 边界：revision 只前进 1 也要保住（不能"差不多"就清）。
    @Test func singleRevisionAdvanceIsEnoughToKeepInput() {
        #expect(SubmissionResolver.shouldClear(submitted: 9, current: 10) == false)
    }

    // MARK: - 状态机

    @Test func submittingIsBusyButAcceptedIsNot() {
        #expect(SubmissionState.submitting(revision: 1).busy == true)
        #expect(SubmissionState.accepted(revision: 1).busy == false)
        #expect(SubmissionState.idle.busy == false)
    }

    /// 两种失败都**不是** busy（用户可以继续编辑/重试），但都不清空输入。
    @Test func failuresAreNotBusy() {
        #expect(
            SubmissionState.failedBeforeAccept(revision: 1, message: "boom").busy == false)
        #expect(SubmissionState.outcomeUnknown(revision: 1).busy == false)
    }

    /// 结果未知时没有"失败消息"——它的文案是"不自动重发"，而不是错误原因。
    @Test func outcomeUnknownHasNoFailureMessage() {
        #expect(SubmissionState.outcomeUnknown(revision: 1).failureMessage == nil)
        #expect(
            SubmissionState.failedBeforeAccept(revision: 1, message: "nope").failureMessage
                == "nope")
    }

    /// 快照与 revision 的对应关系：快照记的是**提交那一刻**的文本。
    @Test func snapshotCarriesSubmittedText() {
        let snap = SubmissionSnapshot(revision: 2, text: "first", attachmentCount: 1)
        #expect(snap.revision == 2)
        #expect(snap.text == "first")
        #expect(snap.attachmentCount == 1)
    }
}
