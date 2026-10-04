import Foundation
import Network
import os

/// 首次回调仅登记（Android `NetworkChangeTest`）；之后系统报告的路径更新均使选路失效。
public enum NetworkPathEvent: Sendable {
    case initial
    case changed
}

/// 一次性路径事件源。测试注入 AsyncStream，不使用真实网络；取消必须结束流并释放观察资源。
public protocol NetworkPathEventSource: Sendable {
    func events() -> AsyncStream<NetworkPathEvent>
    func cancel()
}

/// NWPathMonitor 适配器；创建时不启动，`events()` 才启动，只允许一个消费者。
/// 可变状态全部受锁保护，NWPathMonitor 的 handler 不捕获 self。
/// iOS 没有 Android Network handle / LinkProperties 的同等事件合同：首次后任何路径回调
/// （包括接口、状态、路由属性更新）都保守作废缓存，不套用 Android 的 DNS-only 忽略规则。
public final class SystemNetworkPathEventSource: NetworkPathEventSource, Sendable {
    private struct State {
        var started = false
        var cancelled = false
        var firstUpdate = true
    }

    private let state = OSAllocatedUnfairLock(initialState: State())
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "dev.deeplinks.routing.path")
    private let stream: AsyncStream<NetworkPathEvent>
    private let continuation: AsyncStream<NetworkPathEvent>.Continuation

    public init() {
        (stream, continuation) = AsyncStream.makeStream()
        let state = self.state
        let continuation = self.continuation
        monitor.pathUpdateHandler = { _ in
            let event: NetworkPathEvent? = state.withLock { value in
                if value.cancelled { return nil }
                defer { value.firstUpdate = false }
                return value.firstUpdate ? .initial : .changed
            }
            if let event { continuation.yield(event) }
        }
        continuation.onTermination = { [weak monitor] _ in monitor?.cancel() }
    }

    public func events() -> AsyncStream<NetworkPathEvent> {
        state.withLock { value in
            if !value.started && !value.cancelled {
                value.started = true
                monitor.start(queue: queue)
            }
        }
        return stream
    }

    public func cancel() {
        state.withLock { $0.cancelled = true }
        monitor.cancel()
        continuation.finish()
    }

    deinit {
        monitor.cancel()
        continuation.finish()
    }
}

/// 由 App 生命周期持有观察任务，`Task { await observer.run() }`，结束时取消该任务。
/// 不操作 URLSession 连接池或 SSE 游标；I3.7 只负责下一次连接的路由缓存。
public struct NetworkPathObserver: Sendable {
    private let selector: RouteSelector
    private let source: any NetworkPathEventSource

    public init(selector: RouteSelector, source: any NetworkPathEventSource = SystemNetworkPathEventSource()) {
        self.selector = selector
        self.source = source
    }

    /// 单消费者、一次性运行；事件按系统回调顺序处理，取消后不再改变网络代。
    public func run() async {
        defer { source.cancel() }
        for await event in source.events() {
            if Task.isCancelled { return }
            switch event {
            case .initial: break
            case .changed: await selector.onNetworkChanged()
            }
        }
    }
}
