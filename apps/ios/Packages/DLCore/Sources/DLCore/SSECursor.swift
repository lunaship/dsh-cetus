import Foundation

/// SSE 游标（I3.5；按 PLAN 4.1 放在 DLCore）：**接收游标**与**已提交游标**分开（合同「快照与增量」）。
///
/// - `received`：收到并解析的最大 seq。事件带数字 `id:`（主机事件流）时客户端自动推进；
///   会话流的 seq 在 `data` 里，由上层解析后经 DLNet `SSEClient.recordReceived(_:)` 推进。
/// - `committed`：上层确认已应用的 seq。只有已提交部分是「可证的」，重连/resync 一律以它为准；
///   上层经 DLNet `SSEClient.commit(_:)` 推进，客户端永不自行越过未提交部分。
///
/// 重连请求的游标 = `resumeValue` = `committed`。合同：「重连 afterSeq 使用已提交游标」，
/// 「不得把尾部当作连续补发交付」「禁止靠无限断开重连修补缺口」。
public struct SSECursor: Equatable, Sendable {
    public private(set) var received: Int
    public private(set) var committed: Int

    public init(received: Int = 0, committed: Int = 0) {
        self.committed = committed
        self.received = max(received, committed)
    }

    /// 收到一条 seq（幂等：只前进不回退）。
    public mutating func observe(_ seq: Int) {
        received = max(received, seq)
    }

    /// 上层确认已应用一条 seq（幂等：只前进不回退）。
    public mutating func commit(_ seq: Int) {
        committed = max(committed, seq)
        received = max(received, committed)
    }

    /// 收到 `resync-required`：本轮在途增量不可证，接收游标退回已提交游标。
    public mutating func discardUncommitted() {
        received = committed
    }

    /// 快照游标落地：接收与已提交都对齐到快照位置。
    public mutating func reset(to seq: Int) {
        received = seq
        committed = seq
    }

    /// 重连/resume 使用的游标值。
    public var resumeValue: Int { committed }
}
