import Foundation

/// 合并频繁输入：不是每个字符都写盘（C02 要求 6）。
///
/// 两条触发线：
/// - 静默窗口（默认 0.6s）到了就写一次；
/// - 从最早一条待写内容起累计超过上限（默认 3s）就立即写，避免长输入一直不落盘。
///
/// 页面离开 / 进后台 / 发送前调用 `flushNow()` 立即写。写失败时保留内存副本，
/// 由 `onFailure` 提示，绝不假装已经保存。
@MainActor
final class ComposerDraftWriter {
    var store: ComposerDraftStore
    var key: ComposerDraftKey
    var onFailure: (@MainActor (ComposerDraftKind) -> Void)?
    /// 静默窗口。单测给长窗口，让"未 flush 前不落盘"成为确定性断言。
    var quietWindow: Duration = .milliseconds(600)
    /// 从最早一条待写内容起的最长延迟。
    var maxPending: Duration = .seconds(3)
    /// 时钟注入：测试直接推进时间而不用真等。
    var clock: @MainActor () -> Date = { Date() }

    private var pending: [ComposerDraftKind: String] = [:]
    private var scheduled: Task<Void, Never>?
    private var oldestPendingAt: Date?

    init(store: ComposerDraftStore, key: ComposerDraftKey) {
        self.store = store
        self.key = key
    }

    /// 排队一次写入。同槽位后续输入覆盖前一次，最终只落一次盘。
    func update(_ text: String, kind: ComposerDraftKind) {
        if pending.isEmpty { oldestPendingAt = clock() }
        pending[kind] = text
        if let oldest = oldestPendingAt, clock().timeIntervalSince(oldest) >= maxPending.timeInterval {
            flushNow()
            return
        }
        scheduleIfNeeded()
    }

    /// 取消该槽位排队，并删掉已落盘的草稿（发送成功后用）。
    func clear(_ kind: ComposerDraftKind) {
        var slot = key
        slot.kind = kind
        pending.removeValue(forKey: kind)
        store.remove(slot)
        if pending.isEmpty {
            scheduled?.cancel()
            scheduled = nil
            oldestPendingAt = nil
        }
    }

    /// 立即把排队内容写盘。页面消失 / 进后台 / 发送前调用。
    func flushNow() {
        scheduled?.cancel()
        scheduled = nil
        let snapshot = pending
        pending.removeAll()
        oldestPendingAt = nil
        for (kind, text) in snapshot {
            var slot = key
            slot.kind = kind
            if store.save(slot, text: text) == .failed { onFailure?(kind) }
        }
    }

    /// 丢掉排队内容但不写盘。
    func discardPending() {
        pending.removeAll()
        oldestPendingAt = nil
        scheduled?.cancel()
        scheduled = nil
    }

    var hasPending: Bool { !pending.isEmpty }

    private func scheduleIfNeeded() {
        guard scheduled == nil else { return }
        let window = quietWindow
        scheduled = Task { [weak self] in
            try? await Task.sleep(for: window)
            await self?.flushNow()
        }
    }
}

extension Duration {
    /// `Duration` 没有秒数访问器，这里统一换算成 `TimeInterval` 便于比较。
    var timeInterval: TimeInterval {
        let parts = components
        return Double(parts.seconds) + Double(parts.attoseconds) / 1_000_000_000_000_000_000
    }
}
