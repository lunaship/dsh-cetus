import Foundation

/// App 前后台阶段（I3.5）。自定义枚举是为了让 DLNet 不 import SwiftUI；
/// 真正接 `scenePhase` 的转换放在 App 层（PLAN 阶段 4）。
public enum AppPhase: Equatable, Sendable {
    case active
    /// 非激活但不后台（来电、控制中心、多任务手势）：不改变连接。
    case inactive
    case background
}

/// 前台门控（I3.5）：保存当前 `AppPhase` 并广播变化。
///
/// 语义（PLAN I3.5 / I7.3）：**只在前台连接**——`.background` 时主动断开 SSE；
/// 回到 `.active` 时用已提交游标重连并补发；`.inactive` 不动连接。
/// 消费方（`SSEClient.follow(_:)`）自行决定如何反应；本类型只负责阶段状态与广播。
public final class ForegroundGate: @unchecked Sendable {
    private let lock = NSLock()
    private var currentPhase: AppPhase
    private var subscribers: [UUID: AsyncStream<AppPhase>.Continuation] = [:]

    public init(phase: AppPhase = .active) {
        self.currentPhase = phase
    }

    public var phase: AppPhase {
        lock.withLock { currentPhase }
    }

    /// 更新阶段；与当前相同则不广播。
    public func update(_ phase: AppPhase) {
        let targets: [AsyncStream<AppPhase>.Continuation] = lock.withLock {
            guard currentPhase != phase else { return [] }
            currentPhase = phase
            return Array(subscribers.values)
        }
        for continuation in targets {
            continuation.yield(phase)
        }
    }

    /// 订阅阶段变化。首个值是订阅时刻的当前阶段（订阅即拿到现状，便于跟随者初始化）。
    /// 可多路订阅，各自独立。
    public func changes() -> AsyncStream<AppPhase> {
        AsyncStream(bufferingPolicy: .unbounded) { continuation in
            let token = UUID()
            let initial: AppPhase = lock.withLock {
                subscribers[token] = continuation
                return currentPhase
            }
            continuation.yield(initial)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.withLock { _ = self.subscribers.removeValue(forKey: token) }
            }
        }
    }
}
