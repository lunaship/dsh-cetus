import Foundation

/// I7.3：只为一次尚未完成的 HTTP 写申请后台时间。
///
/// 前台 SSE、后台断开和回前台重连仍由 `ForegroundGate` 与 `SSEClient` 负责。
/// 这里不做保活，也不使用 `BGContinuedProcessingTask`。失败后的草稿保留与不自动重发
/// 由调用方决定；本类型只保证后台任务在请求结束后关闭。
public struct BackgroundSendDecision: Equatable, Sendable {
    public enum Action: Equatable, Sendable {
        /// 没有在途发送，或 App 还没进入后台。
        case idle
        /// 为这一次 HTTP 写申请一小段后台时间。
        case begin
        /// 请求已结束，结束后台任务。
        case end
    }

    /// 只有「发送还没完成，且 App 已在后台」才申请。
    /// `inactive`（来电、控制中心、多任务手势）不申请。
    public static func entering(phase: AppPhase, sendInFlight: Bool) -> Action {
        guard phase == .background, sendInFlight else { return .idle }
        return .begin
    }

    /// 每次发送结束都要求关闭任务。没有任务时，生产实现应忽略这个动作。
    public static func finishing() -> Action {
        .end
    }
}

/// 可替换的后台任务句柄。测试记录调用；生产实现才接触 `UIApplication`。
public protocol BackgroundTaskHandling: Sendable {
    func begin(name: String) -> Int
    func end(_ token: Int)
}

/// 没有 UIKit 的调用方使用。它不向系统申请后台时间。
public struct NoopBackgroundTasks: BackgroundTaskHandling {
    public init() {}

    public func begin(name: String) -> Int {
        0
    }

    public func end(_ token: Int) {}
}

/// 测试记录开始与结束，不接触 UIKit。
public final class RecordingBackgroundTasks: BackgroundTaskHandling, @unchecked Sendable {
    private let lock = NSLock()
    private var next = 1
    private var began: [String] = []
    private var ended: [Int] = []
    private var live = Set<Int>()

    public init() {}

    public func begin(name: String) -> Int {
        lock.withLock {
            let token = next
            next += 1
            began.append(name)
            live.insert(token)
            return token
        }
    }

    public func end(_ token: Int) {
        lock.withLock {
            guard live.remove(token) != nil else { return }
            ended.append(token)
        }
    }

    public var beganNames: [String] {
        lock.withLock { began }
    }

    public var endedTokens: [Int] {
        lock.withLock { ended }
    }

    public var openCount: Int {
        lock.withLock { live.count }
    }
}

/// 一次在途 HTTP 写的后台覆盖。同一时刻只保留一个系统任务，完成后立即结束。
public final class BackgroundSendCover: @unchecked Sendable {
    public static let taskName = "deeplinks.http-write"

    private let tasks: any BackgroundTaskHandling
    private let lock = NSLock()
    private var token: Int?
    private var sends = 0

    public init(tasks: any BackgroundTaskHandling = NoopBackgroundTasks()) {
        self.tasks = tasks
    }

    /// 开始一次 HTTP 写。若此刻已经在后台，立即申请短任务，
    /// 避免阶段变化发生在发送开始之前时漏掉覆盖。
    public func beginSend(phase: AppPhase) {
        let shouldBegin = lock.withLock { () -> Bool in
            sends += 1
            return BackgroundSendDecision.entering(phase: phase, sendInFlight: true) == .begin && token == nil
        }
        guard shouldBegin else { return }
        adopt(tasks.begin(name: Self.taskName))
    }

    /// 进入后台时调用。没有在途发送，或已经有一个任务时，不会再申请。
    /// 系统调用放在锁外，避免主线程同步时和这把锁互相等待。
    @discardableResult
    public func coverIfNeeded(phase: AppPhase) -> BackgroundSendDecision.Action {
        let shouldBegin = lock.withLock {
            BackgroundSendDecision.entering(phase: phase, sendInFlight: sends > 0) == .begin && token == nil
        }
        guard shouldBegin else { return .idle }
        adopt(tasks.begin(name: Self.taskName))
        return .begin
    }

    private func adopt(_ opened: Int) {
        let redundant: Int? = lock.withLock {
            guard token == nil, sends > 0 else { return opened }
            token = opened
            return nil
        }
        if let redundant {
            tasks.end(redundant)
        }
    }

    /// 一次 HTTP 写结束。无论成功或失败，都结束已申请的后台任务。
    public func finishSend() {
        let closing: Int? = lock.withLock {
            sends = max(0, sends - 1)
            guard sends == 0, let token else { return nil }
            self.token = nil
            return token
        }
        if let closing {
            tasks.end(closing)
        }
    }

    public var hasOpenTask: Bool {
        lock.withLock { token != nil }
    }
}

#if canImport(UIKit)
    import UIKit

    /// 生产实现。`UIApplication` 的后台任务在主线程登记和结束。
    public struct SystemBackgroundTasks: BackgroundTaskHandling {
        public init() {}

        public func begin(name: String) -> Int {
            Self.onMain {
                let token = UIApplication.shared.beginBackgroundTask(withName: name) {}
                return Int(token.rawValue)
            }
        }

        public func end(_ token: Int) {
            Self.onMain {
                let identifier = UIBackgroundTaskIdentifier(rawValue: token)
                guard identifier != .invalid else { return }
                UIApplication.shared.endBackgroundTask(identifier)
            }
        }

        private static func onMain<T>(_ body: @MainActor () -> T) -> T {
            if Thread.isMainThread {
                MainActor.assumeIsolated { body() }
            } else {
                DispatchQueue.main.sync { body() }
            }
        }
    }
#endif
