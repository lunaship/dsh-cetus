/**
 * 远程首配的内存种子表与 client_open 重放缓存（RFC §5.2、§5.5 第 4、6 步）。
 *
 * 两者都只在内存：种子不写 state.json，插件重启后旧二维码的远程首配失效、App 提示刷新
 * ——这是 RFC 明确的取舍（秘密少落盘一处）。
 */
import { randomBytes } from "node:crypto"
import { b64u, bootstrapKeys } from "./crypto.js"
import { CLOCK_SKEW_SEC, KEY_BYTES, NONCE_CACHE_MAX, REJECT, ROUTE_BYTES } from "./wire.js"

export class BootstrapTable {
  /** 新的在前：最多「当前码 + 上一张未过期的码」两条。 */
  #entries = []
  #now

  constructor({ now = () => Date.now() } = {}) {
    this.#now = now
  }

  /** 签发新种子；上一张若未过期则保留，更早的丢弃（RFC §5.2）。 */
  issue(routeId, expiresAtMs) {
    if (!Buffer.isBuffer(routeId) || routeId.length !== ROUTE_BYTES) throw new TypeError("routeId must be 16 bytes")
    if (!Number.isSafeInteger(expiresAtMs)) throw new TypeError("expiresAtMs must be an integer")
    const seed = randomBytes(16)
    const { bootstrapId, bootstrapKey } = bootstrapKeys(seed, routeId)
    const previous = this.#entries[0]
    this.#entries = [{ id: bootstrapId, key: bootstrapKey, expiresAtMs, consumed: false }]
    if (previous && previous.expiresAtMs > this.#now()) this.#entries.push(previous)
    return { seed, bootstrapId: Buffer.from(bootstrapId) }
  }

  /** 过期与已消费的条目在保留期内照常返回，由调用方区分 EXPIRED / USED（RFC §5.5 第 4 步）。 */
  lookup(bootstrapId) {
    const entry = this.#find(bootstrapId)
    if (!entry) return null
    return { key: entry.key, expiresAtMs: entry.expiresAtMs, consumed: entry.consumed }
  }

  /** 配对成功时调用（RFC §6.3）；返回是否找到该码。 */
  consume(bootstrapId) {
    const entry = this.#find(bootstrapId)
    if (!entry) return false
    entry.consumed = true
    return true
  }

  #find(bootstrapId) {
    if (!Buffer.isBuffer(bootstrapId) || bootstrapId.length !== KEY_BYTES) return null
    return this.#entries.find((entry) => entry.id.equals(bootstrapId)) ?? null
  }
}

/**
 * (kind, key, nonce) 重放缓存：条目保留到 ts + 60 秒；满 10,000 条且没有可清理的过期条目时
 * 返回 SERVER_BUSY，不淘汰未过期条目（淘汰会让被挤出的 nonce 可以重放）。
 */
export class NonceCache {
  #entries = new Map()
  #max

  constructor({ max = NONCE_CACHE_MAX } = {}) {
    this.#max = max
  }

  get size() {
    return this.#entries.size
  }

  /** @returns {null | "REPLAY" | "SERVER_BUSY"} null 表示已记录、可继续 */
  check(kind, key, nonce, ts, nowSec) {
    const id = `${kind}:${b64u(key)}:${b64u(nonce)}`
    const existing = this.#entries.get(id)
    if (existing !== undefined && existing >= nowSec) return REJECT.REPLAY
    if (this.#entries.size >= this.#max) this.#prune(nowSec)
    if (this.#entries.size >= this.#max) return REJECT.SERVER_BUSY
    this.#entries.set(id, ts + CLOCK_SKEW_SEC)
    return null
  }

  #prune(nowSec) {
    for (const [id, expiresAt] of this.#entries) {
      if (expiresAt < nowSec) this.#entries.delete(id)
    }
  }
}
