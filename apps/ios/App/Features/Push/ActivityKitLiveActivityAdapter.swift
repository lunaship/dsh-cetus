import ActivityKit
import DLCore
import Foundation

/// Activity attributes declared in the app target.
///
/// The widget extension declares its own structurally identical copy
/// (`CetusActivityAttributes` in `Extensions/LiveActivity`). The two targets
/// share no Swift module, and ActivityKit matches the two sides by the encoded
/// attribute + content-state shape, not by type identity — so the members and
/// their Codable keys must stay in lockstep. `LiveActivityWireContractTests`
/// pins the key list.
public struct CetusActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        public var state: String
        public var step: Int
        public var startedAt: Date
        public var waitingCount: Int
        public var sessionRef: String

        public init(state: String, step: Int, startedAt: Date, waitingCount: Int, sessionRef: String) {
            self.state = state
            self.step = step
            self.startedAt = startedAt
            self.waitingCount = waitingCount
            self.sessionRef = sessionRef
        }
    }

    public var hostRef: String
    /// Task title looked up in the app when the activity starts (user decision
    /// 2026-10-10: show it on the lock screen and Dynamic Island). Attributes are
    /// fixed for the activity's lifetime and never travel in a push; `content-state`
    /// stays the five RFC 0002 §5.6 fields. Optional so older activities decode.
    public var title: String?

    public init(hostRef: String, title: String? = nil) {
        self.hostRef = hostRef
        self.title = title
    }
}

/// Production adapter over ActivityKit.
///
/// It lives beside the controller so unit tests can stay on the fake: nothing
/// here runs during `xcodebuild test` unless a test opts in.
#if canImport(ActivityKit) && os(iOS)
    @available(iOS 16.2, *)
    public struct ActivityKitLiveActivityAdapter: LiveActivityAdapting {
        private let staleAfter: TimeInterval

        public init(staleAfter: TimeInterval = LiveActivityStaleness.threshold) {
            self.staleAfter = staleAfter
        }

        public func start(content: LiveActivityContent) async throws -> LiveActivityHandle {
            let attributes = CetusActivityAttributes(hostRef: content.hostRef, title: content.title)
            let state = Self.state(from: content)
            let activity = try Activity<CetusActivityAttributes>.request(
                attributes: attributes,
                content: ActivityContent(state: state, staleDate: Date().addingTimeInterval(staleAfter)),
                pushType: nil)
            return LiveActivityHandle(id: activity.id, sessionRef: content.sessionRef)
        }

        public func update(handle: LiveActivityHandle, content: LiveActivityContent) async throws {
            guard let activity = Self.find(handle) else { return }
            await activity.update(
                ActivityContent(
                    state: Self.state(from: content),
                    staleDate: Date().addingTimeInterval(staleAfter)))
        }

        public func end(handle: LiveActivityHandle, content: LiveActivityContent) async throws {
            guard let activity = Self.find(handle) else { return }
            await activity.end(
                ActivityContent(state: Self.state(from: content), staleDate: nil), dismissalPolicy: .default)
        }

        public func running() async -> [LiveActivityHandle] {
            Activity<CetusActivityAttributes>.activities.map {
                LiveActivityHandle(id: $0.id, sessionRef: $0.content.state.sessionRef)
            }
        }

        private static func find(_ handle: LiveActivityHandle) -> Activity<CetusActivityAttributes>? {
            Activity<CetusActivityAttributes>.activities.first { $0.id == handle.id }
        }

        /// Maps contract content onto the widget's content state. Only the
        /// RFC 0002 §5.6 fields travel here; the title lives in the attributes.
        private static func state(from content: LiveActivityContent) -> CetusActivityAttributes.ContentState {
            CetusActivityAttributes.ContentState(
                state: content.phase.rawValue,
                step: content.step,
                startedAt: content.startedAt,
                waitingCount: content.waitingCount,
                sessionRef: content.sessionRef)
        }
    }
#endif
