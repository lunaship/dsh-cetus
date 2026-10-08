import DLCore
import DLModels
import Foundation
import Testing

@testable import Cetus

/// 守护 C07 要求 5：未知状态不默认完成。
///
/// `inboxRowContent` 的收尾分支（`Packages/DLCore/Sources/DLCore/Inbox.swift` ~430）
/// 在既没有 `stoppedReason` 也没有 `lastResult` 时必须落到 `.unknown`，
/// 不能因为"看起来结束了"就显示已完成 —— 缓存滞后和刚结束的会话都会走到这里。
@Suite struct InboxUnknownStatusTests {
    private func session(
        _ id: String,
        stopped: String? = nil,
        lastResult: SessionLastResult? = nil,
        running: Bool = false,
        awaiting: Bool = false,
        cwd: String? = nil
    ) -> SessionSummary {
        SessionSummary(
            sessionId: id,
            title: id,
            updatedAt: 1,
            running: running,
            cwd: cwd,
            awaitingInput: awaiting,
            lastResult: lastResult,
            stoppedReason: stopped)
    }

    // MARK: - 核心守护：无停止原因、无结果 → unknown，绝不 done

    @Test func bareFinishedSessionIsUnknownNotDone() {
        let row = inboxRowContent(session: session("bare"), action: nil, offline: false)
        #expect(row.status == .unknown)
        #expect(row.status != .done)
    }

    /// 反向锚点：明确有结果才算完成。没有这条，上面那条可能只是"永远不返回 done"。
    @Test func resultMakesDoneAndStopReasonMakesStopped() {
        let done = inboxRowContent(
            session: session("done", lastResult: SessionLastResult(files: 1)), action: nil, offline: false)
        #expect(done.status == .done)
        let stopped = inboxRowContent(
            session: session("stopped", stopped: "interrupted"), action: nil, offline: false)
        #expect(stopped.status == .stopped(.interrupted))
    }

    @Test func emptyResultTextStillCountsAsResult() {
        let row = inboxRowContent(
            session: session("empty", lastResult: SessionLastResult(text: "   ")), action: nil, offline: false)
        #expect(row.status == .done)
    }

    @Test func blankStopReasonFallsBackToUnknown() {
        // 只有空白字符不算"有停止原因"，否则会出现空停止态。
        let row = inboxRowContent(session: session("blank", stopped: "  "), action: nil, offline: false)
        #expect(row.status == .unknown)
    }

    // MARK: - 未知态不带"已完成"的视觉信号

    @Test func unknownHasNoAccentDotAndNoResultPreview() {
        let row = inboxRowContent(session: session("unknown"), action: nil, offline: false)
        #expect(row.dot == nil)
        #expect(row.preview == nil)
    }

    @Test func runningBeatsUnknown() {
        let row = inboxRowContent(session: session("run", running: true), action: nil, offline: false)
        #expect(row.status == nil)
        #expect(row.dot == .accent)
    }

    @Test func awaitingBeatsUnknown() {
        let row = inboxRowContent(session: session("wait", awaiting: true), action: nil, offline: false)
        #expect(row.status == .waiting)
    }

    // MARK: - 文案：unknown 必须能显示，且不是"已完成"

    @Test func unknownCopyIsDistinctFromDone() {
        let zh = InboxCopy(locale: Locale(identifier: "zh-Hans"))
        let en = InboxCopy(locale: Locale(identifier: "en"))
        let unknownZh = zh.status(.unknown)
        let unknownEn = en.status(.unknown)
        #expect(unknownZh != zh.status(.done))
        #expect(unknownEn != en.status(.done))
        #expect(!unknownZh.isEmpty)
        #expect(!unknownEn.isEmpty)
    }

    @Test func unknownCopyNamesUpdating() {
        let zh = InboxCopy(locale: Locale(identifier: "zh-Hans"))
        let en = InboxCopy(locale: Locale(identifier: "en"))
        #expect(zh.status(.unknown) == zh.text(.unknownStatus))
        #expect(en.status(.unknown) == en.text(.unknownStatus))
        #expect(zh.text(.unknownStatus) == "未知/更新中")
        #expect(en.text(.unknownStatus) == "Unknown — updating")
    }

    /// 反向锚点：文案表真的取得到（防止上面几条因为两边都返回空串而通过）。
    @Test func statusCopyIsNotEmptyAcrossKinds() {
        let copy = InboxCopy(locale: Locale(identifier: "zh-Hans"))
        for kind: InboxStatusKind in [.waitingApproval, .waitingAnswer, .waiting, .done, .unknown] {
            #expect(!copy.status(kind).isEmpty)
        }
    }

    // MARK: - 元信息行：unknown 必须出现在脚注里

    @Test func unknownAppearsInRowMeta() {
        let copy = InboxCopy(locale: Locale(identifier: "zh-Hans"))
        let row = inboxRowContent(session: session("bare", cwd: "/work/app"), action: nil, offline: false)
        let meta = copy.meta(workspace: row.workspace, subagents: row.subagentCount, status: row.status)
        #expect(meta.contains(copy.text(.unknownStatus)))
        #expect(!meta.contains(copy.status(.done)))
    }
}
