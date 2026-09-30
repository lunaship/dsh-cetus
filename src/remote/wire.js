/**
 * DLP/1 协议常量与控制帧编解码（RFC §5.1、§5.4、§5.5、§5.7、§5.8）。
 *
 * 常量只有一份（RFC §11 第 6 条）：Agent、bootstrap 表与测试都从这里取，不在各处写魔数。
 * transcript 前缀属于密码学常量，留在 crypto.js 并由向量测试锁定。
 */

export const DLP_VERSION = 1

/** 控制消息：文本帧，单条 ≤ 4 KiB（RFC §5.1）。 */
export const MAX_CONTROL_BYTES = 4 * 1024
/** 数据消息：Relay 读上限 256 KiB；发送端按 ≤ 64 KiB 切块（RFC §5.4.4）。 */
export const MAX_DATA_MESSAGE_BYTES = 256 * 1024
export const DATA_CHUNK_BYTES = 64 * 1024

export const ROUTE_BYTES = 16
export const SID_BYTES = 16
export const KEY_BYTES = 16
export const NONCE_BYTES = 16
export const CHALLENGE_BYTES = 32
export const HOST_PUB_BYTES = 32
export const MAC_BYTES = 32

/** |ts − now| 的容差，也是 nonce 缓存的保留时长（RFC §5.5 第 3、6 步）。 */
export const CLOCK_SKEW_SEC = 60
export const NONCE_CACHE_MAX = 10_000

/** Agent 执行的并发上限（RFC §5.8）。 */
export const DEVICE_MAX_STREAMS = 12
export const BOOTSTRAP_MAX_STREAMS = 4
export const HOST_MAX_STREAMS = 32

/** Relay 的首条消息超时（不可配置）。也是 v1 不预热的原因（RFC §10.3 第 5 条）。 */
export const FIRST_MESSAGE_TIMEOUT_MS = 5_000
/** host_accept 之后等待 ready（RFC §5.5 第 9 步）。 */
export const ACCEPT_READY_TIMEOUT_MS = 10_000
/** 单次写超时（RFC §5.8）。 */
export const WRITE_TIMEOUT_MS = 30_000

/** 控制连接重连（RFC §6.7）。 */
export const RECONNECT_BASE_MS = 1_000
export const RECONNECT_MAX_MS = 60_000
export const RECONNECT_JITTER = 0.3
export const RECONNECT_STABLE_MS = 60_000
export const REPLACED_BACKOFF_MS = 60_000

/** Agent 拒绝码（RFC §5.7）。不得自行新增。 */
export const REJECT = Object.freeze({
  BAD_MAC: "BAD_MAC",
  UNKNOWN_KEY: "UNKNOWN_KEY",
  CLOCK_SKEW: "CLOCK_SKEW",
  REPLAY: "REPLAY",
  BOOTSTRAP_UNKNOWN: "BOOTSTRAP_UNKNOWN",
  BOOTSTRAP_EXPIRED: "BOOTSTRAP_EXPIRED",
  BOOTSTRAP_USED: "BOOTSTRAP_USED",
  DEVICE_LIMIT: "DEVICE_LIMIT",
  SERVER_BUSY: "SERVER_BUSY",
  LOCAL_UNAVAILABLE: "LOCAL_UNAVAILABLE",
})

/** WebSocket 关闭码（RFC §5.7）。 */
export const CLOSE = Object.freeze({
  NORMAL: 1000,
  GOING_AWAY: 1001,
  INTERNAL_ERROR: 1011,
  PROTOCOL_ERROR: 4000,
  UNSUPPORTED_VERSION: 4001,
  AUTH_FAILED: 4002,
  ROUTE_OFFLINE: 4003,
  RATE_LIMITED: 4004,
  SERVER_BUSY: 4005,
  OPEN_TIMEOUT: 4006,
  AGENT_REJECTED: 4007,
  IDLE_TIMEOUT: 4008,
  LIFETIME_EXCEEDED: 4009,
  REPLACED: 4010,
})

/**
 * 解析一条控制帧：UTF-8、≤ 4 KiB、JSON 对象、无重复键（RFC §5.1）。
 * 失败返回 null（调用方按 PROTOCOL_ERROR 关闭当前连接）。
 */
export function parseControlFrame(raw) {
  const bytes = Buffer.isBuffer(raw) ? raw : Buffer.from(String(raw), "utf8")
  if (bytes.length === 0 || bytes.length > MAX_CONTROL_BYTES) return null
  const text = bytes.toString("utf8")
  // 非法 UTF-8 会被替换成 U+FFFD，往返不一致即拒绝
  if (!Buffer.from(text, "utf8").equals(bytes)) return null
  let value
  try {
    value = JSON.parse(text)
  } catch {
    return null
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return null
  if (hasDuplicateKeys(text)) return null
  return value
}

export function encodeControl(message) {
  return JSON.stringify(message)
}

/** 只清洗对端给的错误码再进日志：限长、限字符集，避免把任意内容写进日志。 */
export function safeCode(value) {
  const text = typeof value === "string" ? value : ""
  return /^[A-Z_]{1,32}$/.test(text) ? text : "UNKNOWN"
}

/**
 * JSON.parse 会让后出现的重复键静默覆盖前者，所以在已确认语法合法的文本上再扫一遍对象键。
 */
function hasDuplicateKeys(text) {
  const stack = []
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (c === "{") {
      stack.push({ keys: new Set(), expectKey: true })
    } else if (c === "[") {
      stack.push(null)
    } else if (c === "}" || c === "]") {
      stack.pop()
    } else if (c === ",") {
      const top = stack[stack.length - 1]
      if (top) top.expectKey = true
    } else if (c === "\"") {
      let j = i + 1
      while (text[j] !== "\"") j += text[j] === "\\" ? 2 : 1
      const top = stack[stack.length - 1]
      if (top?.expectKey) {
        const key = JSON.parse(text.slice(i, j + 1))
        if (top.keys.has(key)) return true
        top.keys.add(key)
        top.expectKey = false
      }
      i = j
    }
  }
  return false
}
