import Foundation

/// 一次发送的提交状态（方案 §7 / C03）。
///
/// 旧实现的问题：`send()` 成功后**无条件** `draft = ""` + `attachments = []`，
/// 于是等待期间新写的内容会被清掉；`catch {}` 静默吞错，用户看不到失败。
///
/// 现在的状态模型是 `editing → submitting → accepted`：
/// - 提交时快照文本/附件，并记下当时的 `draftRevision`
/// - 成功时**只**清除"提交过且之后没被改动"的那一份
/// - 失败分两种：确定没发出（`failedBeforeAccept`）与结果未知（`outcomeUnknown`），
///   两者都保留输入；后者**不自动重发**（服务端可能已收到）
public enum SubmissionState: Equatable, Sendable {
    case idle
    /// 有提交在途。`revision` 是被提交的那份快照的版本。
    case submitting(revision: Int)
    /// 提交已被接受。
    case accepted(revision: Int)
    /// 服务端明确没收到（例如连接建立失败、被拒绝）。可以重试。
    case failedBeforeAccept(revision: Int, message: String)
    /// 结果未知：请求发出后超时/断连，服务端可能已接受。**不自动重发**。
    case outcomeUnknown(revision: Int)

    public var isSubmitting: Bool {
        if case .submitting = self { return true }
        return false
    }

    /// 在途或终态未决时，按钮应进忙碌态以阻止重复点击。
    public var busy: Bool { isSubmitting }

    public var failureMessage: String? {
        switch self {
        case .failedBeforeAccept(_, let message): message
        case .outcomeUnknown: nil
        default: nil
        }
    }
}

/// 提交用的快照：提交期间用户继续编辑产生的是**新** revision，与这份快照分开。
public struct SubmissionSnapshot: Equatable, Sendable {
    public var revision: Int
    public var text: String
    public var attachmentCount: Int

    public init(revision: Int, text: String, attachmentCount: Int) {
        self.revision = revision
        self.text = text
        self.attachmentCount = attachmentCount
    }
}

/// 修订号与清空的判定逻辑（纯函数，便于单测）。
///
/// 核心规则：**成功只清除"提交过且未改变"的 revision**。
/// 提交期间若用户又写了新内容（revision 已前进），则不清空——否则会丢输入。
public enum SubmissionResolver {
    /// 提交成功后，当前草稿是否应当被清空。
    ///
    /// - Parameters:
    ///   - submitted: 提交时的快照 revision。
    ///   - current: 现在的 revision（用户若在等待期间编辑过，它会大于 submitted）。
    /// - Returns: 只有两者相等（提交后没再动过）才返回 true。
    public static func shouldClear(submitted: Int, current: Int) -> Bool {
        submitted == current
    }

    /// 附件同理：只有 revision 未前进才清空附件数组。
    public static func shouldClearAttachments(submitted: Int, current: Int) -> Bool {
        shouldClear(submitted: submitted, current: current)
    }
}
