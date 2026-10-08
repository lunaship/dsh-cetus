import DLCore
import Foundation

/// One running Live Activity as the controller sees it. `id` is ActivityKit's
/// own identifier, kept so `end` can target the exact instance.
public struct LiveActivityHandle: Equatable, Sendable {
    public var id: String
    public var sessionRef: String

    public init(id: String, sessionRef: String) {
        self.id = id
        self.sessionRef = sessionRef
    }
}

/// The ActivityKit surface the controller needs. Production uses
/// `ActivityKitLiveActivityAdapter`; unit tests inject a fake so no real
/// activity is ever requested during `xcodebuild test`.
public protocol LiveActivityAdapting: Sendable {
    func start(content: LiveActivityContent) async throws -> LiveActivityHandle
    func update(handle: LiveActivityHandle, content: LiveActivityContent) async throws
    func end(handle: LiveActivityHandle, content: LiveActivityContent) async throws
    /// Instances the system still holds after a cold launch, so a restart
    /// reattaches instead of starting a duplicate.
    func running() async -> [LiveActivityHandle]
}

public enum LiveActivityError: Error, Equatable {
    case notSupported
}

/// Owns the Live Activity lifecycle: start, update, end.
///
/// Rules from the C12 plan §16.4:
/// * one instance per session — a second `start` for the same session updates
///   the existing activity instead of creating a duplicate;
/// * the activity ends on completed, failed, or stopped;
/// * a cold launch reattaches to instances ActivityKit still holds;
/// * content-state carries only the RFC 0002 §5.6 fields.
public actor LiveActivityController {
    private let adapter: any LiveActivityAdapting
    private let isEnabled: @Sendable () -> Bool
    private let now: @Sendable () -> Date
    private var active: [String: LiveActivityHandle] = [:]

    public init(
        adapter: any LiveActivityAdapting,
        isEnabled: @escaping @Sendable () -> Bool = { true },
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.adapter = adapter
        self.isEnabled = isEnabled
        self.now = now
    }

    /// Reattaches to activities that survived a relaunch. Deprecated orphans are
    /// ended so the lock screen cannot show a stale "running" task forever.
    @discardableResult
    public func restore() async -> [LiveActivityHandle] {
        let running = await adapter.running()
        guard isEnabled() else {
            for handle in running {
                try? await adapter.end(handle: handle, content: endedPlaceholder(handle))
            }
            active.removeAll()
            return []
        }
        for handle in running { active[handle.sessionRef] = handle }
        return running
    }

    /// Starts for a long task or refreshes the instance already showing it.
    @discardableResult
    public func start(
        hostRef: String, sessionRef: String, phase: LiveActivityPhase, step: Int, startedAt: Date, waitingCount: Int
    ) async -> LiveActivityHandle? {
        guard isEnabled(),
            let content = LiveActivityPolicy.content(
                enabled: true, hostRef: hostRef, sessionRef: sessionRef, phase: phase, step: step,
                startedAt: startedAt, waitingCount: waitingCount)
        else { return nil }

        if let existing = active[sessionRef] {
            try? await adapter.update(handle: existing, content: content)
            return existing
        }
        // A duplicate may exist even if this process never started it.
        if let orphan = await adapter.running().first(where: { $0.sessionRef == sessionRef }) {
            active[sessionRef] = orphan
            try? await adapter.update(handle: orphan, content: content)
            return orphan
        }
        guard let handle = try? await adapter.start(content: content) else { return nil }
        active[sessionRef] = handle
        return handle
    }

    /// Updates the running instance. A session with no instance is started, so a
    /// long task that crossed the threshold mid-flight still gets one.
    @discardableResult
    public func update(
        hostRef: String, sessionRef: String, phase: LiveActivityPhase, step: Int, startedAt: Date, waitingCount: Int
    ) async -> LiveActivityHandle? {
        guard isEnabled() else {
            await end(sessionRef: sessionRef)
            return nil
        }
        guard let existing = active[sessionRef] else {
            return await start(
                hostRef: hostRef, sessionRef: sessionRef, phase: phase, step: step, startedAt: startedAt,
                waitingCount: waitingCount)
        }
        guard
            let content = LiveActivityPolicy.content(
                enabled: true, hostRef: hostRef, sessionRef: sessionRef, phase: phase, step: step,
                startedAt: startedAt, waitingCount: waitingCount)
        else {
            await end(sessionRef: sessionRef)
            return nil
        }
        try? await adapter.update(handle: existing, content: content)
        return existing
    }

    /// Ends on completed, failed, or stopped. Ending an unknown session is a
    /// no-op so repeated terminal events stay idempotent.
    public func end(sessionRef: String) async {
        guard let handle = active.removeValue(forKey: sessionRef) else { return }
        try? await adapter.end(handle: handle, content: endedPlaceholder(handle))
    }

    /// Ends every activity. Used when the user turns the feature off or unpairs.
    public func endAll() async {
        let handles = Array(active.values)
        active.removeAll()
        for handle in handles {
            try? await adapter.end(handle: handle, content: endedPlaceholder(handle))
        }
    }

    public func activeHandles() -> [LiveActivityHandle] {
        Array(active.values)
    }

    private func endedPlaceholder(_ handle: LiveActivityHandle) -> LiveActivityContent {
        LiveActivityContent(
            hostRef: "", sessionRef: handle.sessionRef, phase: .ended, step: 1, startedAt: now(), waitingCount: 0)
    }
}

/// Marker the lock-screen view uses to stop counting and show the last update.
public enum LiveActivityStaleness {
    /// Past this age with no update, the activity is shown as stale instead of
    /// continuing to look live (§16.4.3).
    public static let threshold: TimeInterval = 15 * 60

    public static func isStale(lastUpdated: Date, now: Date = Date()) -> Bool {
        now.timeIntervalSince(lastUpdated) > threshold
    }
}
