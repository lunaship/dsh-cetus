import DLCore
import Foundation
import os

/// 重连原因。
extension SSEClient {
    public enum RetryReason: Equatable, Sendable {
        /// 连接建立后流正常结束（服务端断开 / 网络静默断开）。
        case streamEnded
        /// 心跳超时：`Timing.heartbeatTimeout` 内没有任何行到达。
        case heartbeatTimeout
        /// HTTP 状态码非 2xx（401 除外，401 走终止）。
        case status(Int)
        /// 传输层抛错。
        case transportError
    }

    public enum Failure: Error, Equatable, Sendable {
        /// 设备 token 无效 / 被吊销（HTTP 401）。**只上报，不删凭据**——删凭据由上层决定。
        case unauthorized
        case stopped
    }

    /// 客户端交给上层的下行输出。
    public enum Output: Equatable, Sendable {
        /// 连接建立（2xx），续传起点 = 当前已提交游标。
        case connected
        /// 已解析的 SSE 事件（含 `heartbeat`，上层可忽略未知/保活事件）。
        case event(SSEEvent)
        /// `resync-required`：客户端已丢弃未提交增量并停住当前连接；等待上层拉快照后
        /// 调 `resumeFromSnapshot(_:)` 从快照游标重连。期间不自动重连
        /// （合同：禁止靠无限断开重连修补缺口）。
        case resyncRequired(SSEEvent)
        /// 断线，将在 `delayMs` 后按指数退避重连。
        case retryScheduled(delayMs: Int, reason: RetryReason)
        /// 终态；事件流随后结束。
        case terminated(Failure)
    }
}

/// 会话 / 主机事件流客户端（PLAN I3.5）。
///
/// - 传输经 `SSETransport` 注入（生产 = `URLSessionSSETransport`；测试 = 脚本化假传输）。
///   默认实现用 `URLSession.bytes(for:)` 逐行解析。
/// - 请求由 `makeRequest` 按**已提交游标**构造：会话流合同用 `afterSeq=` 查询参数
///   （`src/index.js`），主机事件流用 `Last-Event-ID` 头（`src/host-events.js`），由上层按
///   各自合同拼装。App 建会话流时带 `caps=sync2,multiQuestion,requestState`。
/// - 游标：接收 / 已提交分离（`SSECursor`）；带数字 `id:` 的事件自动推进接收游标，
///   会话流由上层 `recordReceived` + `commit` 驱动。重连一律用已提交游标。
/// - 心跳超时：`heartbeatTimeout`（默认 35 秒）内没有任何行到达判定断线
///   （主机事件流 25 秒发 `event: heartbeat`，会话流 15 秒发 `: keepalive` 注释行，都算活动）。
/// - 断线后指数退避重连，上限 30 秒；401 终止且不再重连，报 `.unauthorized`。
/// - 前台门控：`follow(_:)` 接入 `ForegroundGate`；进 background 主动断开，
///   回 active 用已提交游标重连（真正接 `scenePhase` 在 App 层）。
public actor SSEClient {
    public struct Timing: Sendable {
        /// 无行到达多久判定断线；nil = 不检测（仅测试用）。默认 35 秒（PLAN I3.5）。
        public var heartbeatTimeout: Duration?
        /// 首次重连等待；连续失败按 2 的幂增长。
        public var initialBackoff: Duration
        /// 退避上限（PLAN：30 秒）。
        public var maxBackoff: Duration

        public init(
            heartbeatTimeout: Duration? = .seconds(35),
            initialBackoff: Duration = .seconds(1),
            maxBackoff: Duration = .seconds(30)
        ) {
            self.heartbeatTimeout = heartbeatTimeout
            self.initialBackoff = initialBackoff
            self.maxBackoff = maxBackoff
        }

        public static let standard = Timing()
    }

    private enum RunState: Equatable {
        case idle
        case connecting
        case live
        case backingOff
        case waitingForSnapshot
        case suspended
        case stopped
    }

    private let transport: any SSETransport
    private let makeRequest: @Sendable (Int) -> URLRequest
    private let timing: Timing

    /// 下行输出。单消费者：一个会话视图消费即可。
    /// `nonisolated`：不可变 Sendable 流，外部无需 await 即可拿到（消费时才真正挂起）。
    public nonisolated let events: AsyncStream<Output>
    private let eventsContinuation: AsyncStream<Output>.Continuation

    private var parser = SSELineParser()
    private var cursor = SSECursor()
    private var phase: AppPhase
    private var wantsToRun = false
    private var runState: RunState = .idle
    private var connectionGeneration = 0
    private var connectionTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var consecutiveFailures = 0

    public init(
        transport: any SSETransport,
        makeRequest: @escaping @Sendable (Int) -> URLRequest,
        timing: Timing = .standard,
        phase: AppPhase = .active
    ) {
        self.transport = transport
        self.makeRequest = makeRequest
        self.timing = timing
        self.phase = phase
        let (stream, continuation) = AsyncStream<Output>.makeStream()
        self.events = stream
        self.eventsContinuation = continuation
    }

    deinit {
        connectionTask?.cancel()
        retryTask?.cancel()
        eventsContinuation.finish()
    }

    // MARK: - 生命周期

    /// 开始连接（前台时立即连，后台时挂起等待回前台）。
    public func start() {
        guard runState == .idle || runState == .suspended else { return }
        wantsToRun = true
        if phase == .active {
            connectNow()
        }
    }

    /// 彻底停止：取消一切并结束事件流（不可再 start）。
    public func stop() {
        guard runState != .stopped else { return }
        wantsToRun = false
        connectionGeneration += 1
        connectionTask?.cancel()
        connectionTask = nil
        retryTask?.cancel()
        retryTask = nil
        runState = .stopped
        emit(.terminated(.stopped))
        eventsContinuation.finish()
    }

    /// 前台门控驱动（也可由 App 层直接接 `scenePhase` 后调用）。
    public func setPhase(_ newPhase: AppPhase) {
        guard newPhase != phase else { return }
        phase = newPhase
        switch newPhase {
        case .background:
            // PLAN I3.5 / I7.3：后台立即断开；保留 wantsToRun 与游标，回前台重连。
            connectionGeneration += 1
            connectionTask?.cancel()
            connectionTask = nil
            retryTask?.cancel()
            retryTask = nil
            if runState != .stopped, runState != .waitingForSnapshot {
                runState = .suspended
            }
        case .active:
            if wantsToRun, runState == .suspended || runState == .idle {
                connectNow()
            }
        // waitingForSnapshot 保持等待快照游标（拉快照是 HTTP，不受前后台影响）。
        case .inactive:
            break
        }
    }

    /// 跟随一个前台门控：阶段变化时自动 `setPhase`。客户端生命周期内跟随一次。
    public nonisolated func follow(_ gate: ForegroundGate) {
        Task { [weak self] in
            for await phase in gate.changes() {
                guard let self else { return }
                await self.setPhase(phase)
            }
        }
    }

    // MARK: - 游标（接收 / 已提交分离）

    /// 上层确认已应用一条 seq；重连/resync 游标随之前进。
    public func commit(_ seq: Int) {
        cursor.commit(seq)
    }

    /// 上层收到并解析了一条 seq（会话流 seq 在 data 里，客户端无法自推）。
    public func recordReceived(_ seq: Int) {
        cursor.observe(seq)
    }

    /// 当前游标快照（诊断：received - committed 即未确认的在途增量）。
    public func cursorNow() -> SSECursor {
        cursor
    }

    /// `resync-required` 之后由上层提供快照游标：重置游标并从该游标重连。
    /// 仅在等待快照、可运行、前台时真正发起连接。
    public func resumeFromSnapshot(_ seq: Int) {
        cursor.reset(to: seq)
        guard wantsToRun, phase == .active, runState == .waitingForSnapshot else { return }
        connectNow()
    }

    // MARK: - 退避（纯函数，供测试）

    /// 指数退避：第 n 次连续失败等 `initial * 2^(n-1)`，封顶 `max`。
    public static func backoffDelay(failures: Int, initial: Duration, cap: Duration) -> Duration {
        let exponent = max(0, failures - 1)
        var delay = initial
        for _ in 0..<min(exponent, 32) {
            delay = delay * 2
            if delay >= cap {
                return cap
            }
        }
        return delay >= cap ? cap : delay
    }

    // MARK: - 状态机

    private func connectNow() {
        connectionGeneration += 1
        let generation = connectionGeneration
        let request = makeRequest(cursor.resumeValue)
        runState = .connecting
        retryTask?.cancel()
        retryTask = nil
        connectionTask?.cancel()
        connectionTask = Task.detached(priority: .medium) { [weak self] in
            guard let self else { return }
            await self.runConnection(request: request, generation: generation)
        }
    }

    private func runConnection(request: URLRequest, generation: Int) async {
        do {
            let connection = try await transport.open(request)
            guard generation == connectionGeneration, !Task.isCancelled else {
                connection.cancel()
                return
            }
            if connection.statusCode == 401 {
                // 合同/PLAN：401 终止、不再重连。只上报，不删凭据。
                terminate(.unauthorized)
                return
            }
            if let status = connection.statusCode, !(200..<300).contains(status) {
                connection.cancel()
                scheduleRetry(reason: .status(status), generation: generation)
                return
            }
            runState = .live
            consecutiveFailures = 0
            emit(.connected)

            var lineStream = connection.lines
            if let timeout = timing.heartbeatTimeout {
                lineStream = Self.withIdleTimeout(connection.lines, timeout: timeout)
            }
            do {
                for try await line in lineStream {
                    handleLine(line, generation: generation)
                }
                guard generation == connectionGeneration, runState == .live else { return }
                scheduleRetry(reason: .streamEnded, generation: generation)
            } catch is IdleTimeoutError {
                guard generation == connectionGeneration else { return }
                scheduleRetry(reason: .heartbeatTimeout, generation: generation)
            } catch is CancellationError {
                return
            } catch {
                guard generation == connectionGeneration else { return }
                scheduleRetry(reason: .transportError, generation: generation)
            }
        } catch is CancellationError {
            return
        } catch {
            guard generation == connectionGeneration else { return }
            scheduleRetry(reason: .transportError, generation: generation)
        }
    }

    private func handleLine(_ line: String, generation: Int) {
        guard generation == connectionGeneration else { return }
        guard let event = parser.feed(line) else { return }
        // 主机事件流：`id:` 即单调 seq，自动推进接收游标。
        if let id = event.id, let seq = Int(id) {
            cursor.observe(seq)
        }
        if event.event == "resync-required" {
            // 合同「快照与增量」：丢弃不可证的在途增量，等上层拉快照后从快照游标继续。
            cursor.discardUncommitted()
            runState = .waitingForSnapshot
            connectionGeneration += 1
            connectionTask?.cancel()
            connectionTask = nil
            emit(.resyncRequired(event))
            return
        }
        emit(.event(event))
    }

    private func scheduleRetry(reason: RetryReason, generation: Int) {
        guard generation == connectionGeneration,
            wantsToRun,
            phase == .active,
            runState != .stopped,
            runState != .waitingForSnapshot
        else { return }
        runState = .backingOff
        consecutiveFailures += 1
        let delay = Self.backoffDelay(
            failures: consecutiveFailures,
            initial: timing.initialBackoff,
            cap: timing.maxBackoff
        )
        emit(.retryScheduled(delayMs: Self.milliseconds(of: delay), reason: reason))
        retryTask = Task.detached(priority: .medium) { [weak self] in
            do {
                try await Task.sleep(for: delay)
            } catch {
                return
            }
            guard let self, !Task.isCancelled else { return }
            await self.fireRetry(generation: generation)
        }
    }

    private func fireRetry(generation: Int) {
        guard generation == connectionGeneration, wantsToRun, phase == .active, runState == .backingOff else { return }
        connectNow()
    }

    private func terminate(_ failure: Failure) {
        wantsToRun = false
        connectionGeneration += 1
        connectionTask?.cancel()
        connectionTask = nil
        retryTask?.cancel()
        retryTask = nil
        runState = .stopped
        emit(.terminated(failure))
        eventsContinuation.finish()
    }

    private func emit(_ output: Output) {
        eventsContinuation.yield(output)
    }

    private static func milliseconds(of duration: Duration) -> Int {
        let (seconds, attoseconds) = duration.components
        return Int(seconds) * 1000 + Int(attoseconds) / 1_000_000_000_000_000
    }

    // MARK: - 心跳（空闲）超时

    /// 在 `timeout` 内没有任何行到达时，让行流以 `IdleTimeoutError` 结束。
    /// 看门狗每次睡到「最近一次活动 + timeout」再检查，判定时刻与 timeout 对齐。
    private static func withIdleTimeout(
        _ source: AsyncThrowingStream<String, any Error>,
        timeout: Duration
    ) -> AsyncThrowingStream<String, any Error> {
        let tracker = ActivityTracker()
        let (stream, continuation) = AsyncThrowingStream<String, any Error>.makeStream()
        let producer = Task {
            var iterator = source.makeAsyncIterator()
            while true {
                do {
                    guard let line = try await iterator.next() else {
                        continuation.finish()
                        return
                    }
                    tracker.touch()
                    continuation.yield(line)
                } catch {
                    continuation.finish(throwing: error)
                    return
                }
            }
        }
        let watchdog = Task {
            while !Task.isCancelled {
                // 睡到「最近一次活动 + timeout」为止：超时在 timeout 时刻判定，而不是最晚两个周期。
                guard let remaining = tracker.remaining(before: timeout) else {
                    producer.cancel()
                    continuation.finish(throwing: IdleTimeoutError())
                    return
                }
                try? await Task.sleep(for: remaining)
            }
        }
        continuation.onTermination = { _ in
            producer.cancel()
            watchdog.cancel()
        }
        return stream
    }
}

/// 心跳超时的内部错误标记。
private struct IdleTimeoutError: Error, Sendable {}

/// 最近一次活动的时刻（锁保护；跨 producer / watchdog 两个任务共享）。
private final class ActivityTracker: @unchecked Sendable {
    private let lock = OSAllocatedUnfairLock<ContinuousClock.Instant>(initialState: ContinuousClock.now)

    func touch() {
        lock.withLock { $0 = ContinuousClock.now }
    }

    /// 距离判定超时还剩多久；已经空闲满 `timeout` 时返回 nil。
    func remaining(before timeout: Duration) -> Duration? {
        lock.withLock { last in
            let left = timeout - (ContinuousClock.now - last)
            return left > .zero ? left : nil
        }
    }
}
