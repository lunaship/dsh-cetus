/**
 * DLP/1 插件侧 Agent（RFC §5.4、§5.5、§6.1、§6.7、§10.3）。
 *
 * - 控制连接：hello → host_register → registered；按 registered.ping 发 ping；断线按 §6.7 退避重连。
 * - open：严格按 §5.5 的顺序验证，任一步失败只回 reject，**绝不 dial 本地端口**。
 * - 通过后开一条数据连接发 host_accept，收到 ready 才连 127.0.0.1:<pluginPort>；
 *   本地 socket connect 时、转发任何字节之前登记来源标签（§6.1），关闭时删除。
 *
 * 目标地址只来自构造参数 pluginPort：代码里不存在从任何消息读取目标地址的路径（§13.1）。
 * 日志只记事件与错误码，不记 route、公钥、handle、nonce、MAC、种子或数据（§11 第 5 条）。
 */
import { EventEmitter } from "node:events"
import { createHash, randomBytes } from "node:crypto"
import net from "node:net"
import tls from "node:tls"
import WebSocket, { createWebSocketStream } from "ws"
import {
  acceptTranscript, b64u, clientMac, clientTranscript, deviceRelayKey, hostPublicKey,
  registerTranscript, routeId as deriveRouteId, safeEqual, signHost, unb64u,
} from "./crypto.js"
import { NonceCache } from "./bootstrap.js"
import {
  ACCEPT_READY_TIMEOUT_MS, BOOTSTRAP_MAX_STREAMS, CHALLENGE_BYTES, CLOCK_SKEW_SEC, CLOSE, DATA_CHUNK_BYTES,
  DEVICE_MAX_STREAMS, DLP_VERSION, FIRST_MESSAGE_TIMEOUT_MS, HOST_MAX_STREAMS, KEY_BYTES, MAC_BYTES,
  MAX_DATA_MESSAGE_BYTES, NONCE_BYTES, PREWARM_IDLE_MS, RECONNECT_BASE_MS, RECONNECT_JITTER, RECONNECT_MAX_MS,
  RECONNECT_STABLE_MS, REJECT, REPLACED_BACKOFF_MS, ROUTE_BYTES, SID_BYTES, WRITE_TIMEOUT_MS,
  encodeControl, parseControlFrame, safeCode,
} from "./wire.js"

const SELFTEST_TTL_MS = 60_000
const WATCHDOG_INTERVAL_MS = 5_000

export class RemoteAgent extends EventEmitter {
  #endpoint
  #outerPin
  #hostKeySeed
  #keySeed
  #hostPub
  #routeId
  #pluginPort
  #lookupDevice
  #bootstrap
  #isLocalReady
  #logger
  #prewarmTarget
  #now
  #timers
  #random
  #nonces

  #status = "off"
  #stopped = true
  #ctrl = null
  #registeredAt = 0
  #attempt = 0
  #reconnectTimer = null
  #pingTimer = null
  #startWaiter = null
  #startPromise = null
  /** sid(b64u) → 流记录；含正在 accept 的（它们也占并发名额） */
  #streams = new Map()
  /** 本地 socket 的 localPort → 来源标签（§6.1） */
  #origins = new Map()
  #prewarm = []
  #selftest = null

  constructor({
    endpoint,
    outerPin = "",
    hostKeySeed,
    keySeed,
    pluginPort,
    lookupDevice,
    bootstrap,
    isLocalReady,
    logger,
    allowInsecureWs = false,
    prewarm = 0,
    now = () => Date.now(),
    // 以下仅供测试注入（不是协议的一部分）
    timers = { setTimeout, clearTimeout, setInterval, clearInterval },
    random = Math.random,
    nonceCache = new NonceCache(),
  }) {
    super()
    const url = new URL(endpoint)
    if (url.protocol !== "wss:" && !(url.protocol === "ws:" && allowInsecureWs)) {
      throw new TypeError("DLP endpoint must use wss://")
    }
    if (typeof outerPin !== "string" || (outerPin !== "" && !/^[0-9a-f]{64}$/.test(outerPin))) {
      throw new TypeError("outerPin must be empty or 64 lowercase hex characters")
    }
    if (!Buffer.isBuffer(keySeed) || keySeed.length !== 32) throw new TypeError("keySeed must be 32 bytes")
    if (!Number.isInteger(pluginPort) || pluginPort < 1 || pluginPort > 65535) throw new TypeError("invalid pluginPort")
    if (typeof lookupDevice !== "function" || typeof isLocalReady !== "function") throw new TypeError("missing callbacks")
    if (!bootstrap || typeof bootstrap.lookup !== "function") throw new TypeError("missing bootstrap table")
    this.#endpoint = url.toString()
    this.#outerPin = outerPin
    this.#hostKeySeed = Buffer.from(hostKeySeed)
    this.#hostPub = hostPublicKey(this.#hostKeySeed)
    this.#routeId = deriveRouteId(this.#hostPub)
    this.#keySeed = Buffer.from(keySeed)
    this.#pluginPort = pluginPort
    this.#lookupDevice = lookupDevice
    this.#bootstrap = bootstrap
    this.#isLocalReady = isLocalReady
    this.#logger = logger ?? { info() {}, warn() {} }
    // RFC 待定：§10.3 第 5 条让预热连接空闲到 60 秒，但 §5.4 规定 Relay 对 5 秒内不发首条消息的连接
    // 以 4000 关闭——两者矛盾，预热连接活不过 5 秒。按规则 A 取保守一方：默认不预热（0），
    // 显式开启时预热连接空闲上限压到首条消息超时之内。
    this.#prewarmTarget = Math.max(0, Math.min(2, prewarm | 0))
    this.#now = now
    this.#timers = timers
    this.#random = random
    this.#nonces = nonceCache
  }

  get status() {
    return this.#status
  }

  get routeId() {
    return Buffer.from(this.#routeId)
  }

  /** 来源标签（§6.1）；插件以 req.socket.remotePort 查询。 */
  originOf(localPort) {
    const tag = this.#origins.get(localPort)
    return tag ? { ...tag } : undefined
  }

  start() {
    if (!this.#stopped) return this.#startPromise
    this.#stopped = false
    this.#attempt = 0
    this.#startPromise = new Promise((resolve, reject) => {
      this.#startWaiter = { resolve, reject }
    })
    // 调用方不 await 时也不要冒出未处理的拒绝
    this.#startPromise.catch(() => {})
    this.#connectControl()
    return this.#startPromise
  }

  async stop() {
    this.#stopped = true
    this.#clearTimer("reconnect")
    this.#clearPing()
    this.#settleStart(new Error("remote agent stopped"))
    const closing = []
    if (this.#ctrl) closing.push(closeAndWait(this.#ctrl, CLOSE.NORMAL))
    this.#ctrl = null
    for (const rec of [...this.#streams.values()]) {
      if (rec.ws) closing.push(closeAndWait(rec.ws, CLOSE.NORMAL))
      this.#finishStream(rec)
    }
    for (const slot of this.#prewarm.splice(0)) closing.push(closeAndWait(slot.ws, CLOSE.NORMAL))
    await Promise.all(closing)
    this.#setStatus("off")
  }

  /** 吊销联动（§6.5）：关闭该设备全部流（含正在 accept 的），返回关闭数量。 */
  dropDevice(deviceId) {
    let count = 0
    for (const rec of [...this.#streams.values()]) {
      if (rec.tag.deviceId !== deviceId) continue
      count++
      if (rec.ws) rec.ws.close(CLOSE.NORMAL)
      this.#finishStream(rec)
    }
    return count
  }

  /**
   * 面板自检（§6.6）：以临时 relayHandle 充当 kind=device 客户端经 Relay 打开一条流，
   * 钉扎本机证书完成内层 TLS 握手后关闭。selftest 来源在插件侧一律 403。
   */
  async selfTest({ certFingerprint }) {
    const pin = String(certFingerprint ?? "").replace(/:/g, "").toLowerCase()
    const result = { ok: false, failedStage: "outer", outerMs: null, rendezvousMs: null, innerTlsMs: null }
    if (this.#status !== "ready") return result
    const handle = randomBytes(KEY_BYTES)
    this.#selftest = { handle, expiresAtMs: this.#now() + SELFTEST_TTL_MS }
    const started = Date.now()
    let ws
    try {
      ws = this.#newSocket()
      await waitForHello(ws)
      result.outerMs = Date.now() - started
      result.failedStage = "rendezvous"
      const ts = Math.floor(this.#now() / 1000)
      const nonce = randomBytes(NONCE_BYTES)
      const transcript = clientTranscript({ route: this.#routeId, kind: "device", key: handle, ts, nonce })
      const mac = clientMac(deviceRelayKey(this.#keySeed, handle), transcript)
      ws.send(encodeControl({ t: "client_open", v: DLP_VERSION, route: b64u(this.#routeId), kind: "device", key: b64u(handle), ts, nonce: b64u(nonce), mac: b64u(mac) }))
      await waitForControl(ws, "ready", ACCEPT_READY_TIMEOUT_MS)
      result.rendezvousMs = Date.now() - started - result.outerMs
      result.failedStage = "inner"
      const innerStarted = Date.now()
      const duplex = createWebSocketStream(ws, { highWaterMark: DATA_CHUNK_BYTES })
      duplex.on("error", () => {})
      const inner = tls.connect({ socket: duplex, rejectUnauthorized: false, servername: "" })
      await new Promise((resolve, reject) => {
        inner.once("secureConnect", () => {
          const fp = createHash("sha256").update(inner.getPeerCertificate().raw).digest("hex")
          if (fp === pin) resolve()
          else reject(new Error("inner certificate mismatch"))
        })
        inner.once("error", reject)
      })
      result.innerTlsMs = Date.now() - innerStarted
      result.ok = true
      result.failedStage = null
      inner.destroy()
    } catch {
      // 失败阶段已记在 result.failedStage
    } finally {
      this.#selftest = null
      ws?.close(CLOSE.NORMAL)
    }
    return result
  }

  // ─── 控制连接 ────────────────────────────────────────────────────────────

  #connectControl() {
    if (this.#stopped) return
    this.#setStatus("connecting")
    const ws = this.#newSocket()
    this.#ctrl = ws
    let registered = false
    let sentRegister = false
    ws.on("message", (data, isBinary) => {
      const msg = isBinary ? null : parseControlFrame(data)
      if (!msg || typeof msg.t !== "string") {
        ws.close(CLOSE.PROTOCOL_ERROR)
        return
      }
      if (msg.t === "hello" && !sentRegister) {
        const ch = decodeField(msg.ch, CHALLENGE_BYTES)
        if (msg.v !== DLP_VERSION || !ch) {
          ws.close(CLOSE.PROTOCOL_ERROR)
          return
        }
        sentRegister = true
        const sig = signHost(this.#hostKeySeed, registerTranscript(ch, this.#hostPub))
        ws.send(encodeControl({ t: "host_register", v: DLP_VERSION, pub: b64u(this.#hostPub), sig: b64u(sig) }))
      } else if (msg.t === "registered" && sentRegister && !registered) {
        const route = decodeField(msg.route, ROUTE_BYTES)
        // Relay 给出的 route 必须与本机推导一致，否则不是我们注册成功的那条
        if (!route || !route.equals(this.#routeId)) {
          ws.close(CLOSE.PROTOCOL_ERROR)
          return
        }
        registered = true
        this.#registeredAt = this.#now()
        this.#startPing(ws, Number.isSafeInteger(msg.ping) && msg.ping > 0 ? msg.ping : 20)
        this.#logger.info("dlp-agent: registered")
        this.#setStatus("ready")
        this.#settleStart(null)
        this.#refillPrewarm()
      } else if (msg.t === "open" && registered) {
        this.#handleOpen(msg)
      } else if (msg.t === "pong" && registered) {
        // 只用于保活
      } else if (msg.t === "error") {
        this.#logger.warn(`dlp-agent: relay error code=${safeCode(msg.code)}`)
      } else {
        ws.close(CLOSE.PROTOCOL_ERROR)
      }
    })
    ws.on("error", () => {})
    ws.on("close", (code) => this.#onControlClose(ws, code, registered))
  }

  #onControlClose(ws, code, registered) {
    if (this.#ctrl !== ws) return
    this.#ctrl = null
    this.#clearPing()
    if (this.#stopped) return
    if (code === CLOSE.AUTH_FAILED || code === CLOSE.UNSUPPORTED_VERSION) {
      // 签名或版本不被接受：重连不会变好，停下等人处理（§6.7）
      this.#logger.warn(`dlp-agent: registration refused code=${code}`)
      this.#stopped = true
      this.#setStatus("error")
      this.#settleStart(new Error(`relay refused registration (${code})`))
      return
    }
    if (registered && this.#now() - this.#registeredAt >= RECONNECT_STABLE_MS) this.#attempt = 0
    let delay
    if (code === CLOSE.REPLACED) {
      // 同一身份在别处连上了（常见于多个 profile 共用 state）：至少 60 秒后再试，避免两边互相顶替
      this.emit("replaced")
      this.#logger.warn("dlp-agent: replaced by another connection with the same host key")
      delay = REPLACED_BACKOFF_MS * (1 + this.#random() * RECONNECT_JITTER)
    } else {
      const base = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** this.#attempt)
      delay = Math.min(RECONNECT_MAX_MS, base * (1 + (this.#random() * 2 - 1) * RECONNECT_JITTER))
      this.#attempt++
    }
    this.#setStatus("connecting")
    this.#reconnectTimer = this.#timers.setTimeout(() => {
      this.#reconnectTimer = null
      this.#connectControl()
    }, delay)
    this.#reconnectTimer?.unref?.()
  }

  #startPing(ws, seconds) {
    this.#clearPing()
    this.#pingTimer = this.#timers.setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) ws.send(encodeControl({ t: "ping" }))
    }, seconds * 1000)
    this.#pingTimer?.unref?.()
  }

  #clearPing() {
    if (this.#pingTimer) this.#timers.clearInterval(this.#pingTimer)
    this.#pingTimer = null
  }

  #clearTimer(which) {
    if (which === "reconnect" && this.#reconnectTimer) {
      this.#timers.clearTimeout(this.#reconnectTimer)
      this.#reconnectTimer = null
    }
  }

  #settleStart(error) {
    const waiter = this.#startWaiter
    this.#startWaiter = null
    if (!waiter) return
    if (error) waiter.reject(error)
    else waiter.resolve()
  }

  #setStatus(status) {
    if (this.#status === status) return
    this.#status = status
    this.emit("status", status)
  }

  // ─── open：§5.5 验证 ─────────────────────────────────────────────────────

  #handleOpen(msg) {
    const sid = decodeField(msg.sid, SID_BYTES)
    if (!sid) {
      // 连 sid 都无法解析就无法回 reject；这是 Relay 的协议违规
      this.#ctrl?.close(CLOSE.PROTOCOL_ERROR)
      return
    }
    const sidText = b64u(sid)
    const verdict = this.#verifyOpen(msg.req)
    if (verdict.code) {
      const reject = { t: "reject", sid: sidText, code: verdict.code }
      if (verdict.hostNow !== undefined) reject.hostNow = verdict.hostNow
      this.#ctrl?.send(encodeControl(reject))
      this.#logger.info(`dlp-agent: open rejected code=${verdict.code}`)
      return
    }
    const rec = { sid, sidText, tag: { ...verdict.tag, sid: sidText }, limitKey: verdict.limitKey, ws: null, local: null, localPort: null, done: false, opened: false, watchdog: null }
    this.#streams.set(sidText, rec)
    this.#acceptStream(rec)
  }

  /** @returns {{ code: string, hostNow?: number } | { code: null, tag: object, limitKey: string }} */
  #verifyOpen(req) {
    // 1. 格式
    const fields = parseClientOpen(req)
    // RFC 待定：§5.5 第 1、2 步没有指定拒绝码，且规则 A 禁止新增错误码。
    // 格式错误按鉴权失败处理（BAD_MAC），route 不符按「凭据不属于本机」处理（UNKNOWN_KEY）；两者 App 都不重试、不删凭据。
    if (!fields) return { code: REJECT.BAD_MAC }
    // 2. route
    if (!fields.route.equals(this.#routeId)) return { code: REJECT.UNKNOWN_KEY }
    // 3. 时间
    const nowSec = Math.floor(this.#now() / 1000)
    if (Math.abs(fields.ts - nowSec) > CLOCK_SKEW_SEC) return { code: REJECT.CLOCK_SKEW, hostNow: nowSec }
    // 4. 取 key
    let key
    let tag
    let limitKey
    let limit
    if (fields.kind === "device") {
      const device = this.#findDevice(fields.key)
      if (!device || typeof device.deviceId !== "string") return { code: REJECT.UNKNOWN_KEY }
      key = deviceRelayKey(this.#keySeed, fields.key)
      tag = { kind: "device", deviceId: device.deviceId, ...(device.selftest ? { selftest: true } : {}) }
      limitKey = `device:${device.deviceId}`
      limit = DEVICE_MAX_STREAMS
    } else {
      const entry = this.#bootstrap.lookup(fields.key)
      if (!entry) return { code: REJECT.BOOTSTRAP_UNKNOWN }
      if (entry.expiresAtMs <= this.#now()) return { code: REJECT.BOOTSTRAP_EXPIRED }
      if (entry.consumed) return { code: REJECT.BOOTSTRAP_USED }
      key = entry.key
      tag = { kind: "bootstrap", bootstrapId: b64u(fields.key) }
      limitKey = `bootstrap:${b64u(fields.key)}`
      limit = BOOTSTRAP_MAX_STREAMS
    }
    // 5. MAC（常量时间比较）
    const expected = clientMac(key, clientTranscript({ route: fields.route, kind: fields.kind, key: fields.key, ts: fields.ts, nonce: fields.nonce }))
    if (!safeEqual(expected, fields.mac)) return { code: REJECT.BAD_MAC }
    // 6. 重放
    const replay = this.#nonces.check(fields.kind, fields.key, fields.nonce, fields.ts, nowSec)
    if (replay) return { code: replay }
    // 7. 容量（正在 accept 的流也计入，防止并发 open 同时越过上限）
    let perIdentity = 0
    for (const rec of this.#streams.values()) if (rec.limitKey === limitKey) perIdentity++
    if (perIdentity >= limit) return { code: REJECT.DEVICE_LIMIT }
    if (this.#streams.size >= HOST_MAX_STREAMS) return { code: REJECT.SERVER_BUSY }
    // 8. 本地服务
    if (!this.#isLocalReady()) return { code: REJECT.LOCAL_UNAVAILABLE }
    return { code: null, tag, limitKey }
  }

  #findDevice(handle) {
    const selftest = this.#selftest
    if (selftest && selftest.expiresAtMs > this.#now() && selftest.handle.equals(handle)) {
      return { deviceId: "selftest", selftest: true }
    }
    return this.#lookupDevice(Buffer.from(handle)) ?? null
  }

  // ─── 数据连接 ────────────────────────────────────────────────────────────

  #acceptStream(rec) {
    const slot = this.#takePrewarm()
    const ws = slot?.ws ?? this.#newSocket()
    rec.ws = ws
    const readyTimer = this.#timers.setTimeout(() => {
      // ready 没来：Relay 那边手机会得到 OPEN_TIMEOUT，这里只收尾
      this.#logger.info("dlp-agent: accept timed out")
      ws.close(CLOSE.NORMAL)
      this.#finishStream(rec)
    }, ACCEPT_READY_TIMEOUT_MS)
    readyTimer?.unref?.()
    let stage = "hello"
    const sendAccept = (ch) => {
      const sig = signHost(this.#hostKeySeed, acceptTranscript(ch, this.#hostPub, rec.sid))
      ws.send(encodeControl({ t: "host_accept", v: DLP_VERSION, pub: b64u(this.#hostPub), sid: rec.sidText, sig: b64u(sig) }))
      stage = "ready"
    }
    const onMessage = (data, isBinary) => {
      if (stage === "data") {
        // 数据阶段只允许二进制（§5.4.4）
        if (!isBinary) ws.close(CLOSE.PROTOCOL_ERROR)
        return
      }
      const msg = isBinary ? null : parseControlFrame(data)
      if (msg?.t === "hello" && stage === "hello") {
        const ch = decodeField(msg.ch, CHALLENGE_BYTES)
        if (msg.v !== DLP_VERSION || !ch) ws.close(CLOSE.PROTOCOL_ERROR)
        else sendAccept(ch)
      } else if (msg?.t === "ready" && stage === "ready") {
        stage = "data"
        this.#timers.clearTimeout(readyTimer)
        this.#bridgeLocal(rec)
      } else {
        ws.close(CLOSE.PROTOCOL_ERROR)
      }
    }
    ws.on("message", onMessage)
    ws.on("error", () => {})
    ws.on("close", () => {
      this.#timers.clearTimeout(readyTimer)
      this.#finishStream(rec)
    })
    if (slot) {
      slot.ws.off("message", slot.onMessage)
      sendAccept(slot.ch)
      this.#refillPrewarm()
    }
  }

  /** ready 之后才连本地（§5.5 第 10 步）。 */
  #bridgeLocal(rec) {
    if (rec.done) return
    const ws = rec.ws
    // 先建 duplex，ready 之后客户端可能立刻发数据；本地连上之前由 duplex 缓冲（满了会暂停 ws）
    const wsStream = createWebSocketStream(ws, { highWaterMark: DATA_CHUNK_BYTES })
    wsStream.on("error", () => {})
    const local = net.createConnection({ host: "127.0.0.1", port: this.#pluginPort })
    rec.local = local
    local.on("error", () => {})
    local.once("connect", () => {
      if (rec.done) {
        local.destroy()
        return
      }
      // 在转发任何字节之前登记来源（§6.1）
      rec.localPort = local.localPort
      this.#origins.set(local.localPort, { ...rec.tag })
      rec.opened = true
      this.emit("stream-open", publicTag(rec.tag))
      wsStream.pipe(local)
      local.pipe(wsStream)
      this.#startWatchdog(rec)
    })
    local.once("close", () => {
      if (!rec.opened) {
        // 本地连接失败（§5.5 第 10 步）
        ws.close(CLOSE.INTERNAL_ERROR)
      } else {
        ws.close(CLOSE.NORMAL)
      }
      this.#finishStream(rec)
    })
  }

  /** 单次写超时 30 秒（RFC §5.8）：任一方向的待写数据 30 秒没有进展就关闭这一对。 */
  #startWatchdog(rec) {
    let lastWs = 0
    let lastLocal = 0
    let stalledSince = null
    rec.watchdog = this.#timers.setInterval(() => {
      const wsPending = rec.ws?.bufferedAmount ?? 0
      const localPending = rec.local?.writableLength ?? 0
      const progressing = (wsPending === 0 || wsPending < lastWs) && (localPending === 0 || localPending < lastLocal)
      lastWs = wsPending
      lastLocal = localPending
      if (progressing) {
        stalledSince = null
        return
      }
      stalledSince ??= Date.now()
      if (Date.now() - stalledSince >= WRITE_TIMEOUT_MS) {
        this.#logger.info("dlp-agent: stream write timed out")
        rec.ws?.terminate()
        this.#finishStream(rec)
      }
    }, WATCHDOG_INTERVAL_MS)
    rec.watchdog?.unref?.()
  }

  #finishStream(rec) {
    if (rec.done) return
    rec.done = true
    if (rec.watchdog) this.#timers.clearInterval(rec.watchdog)
    if (rec.localPort !== null) this.#origins.delete(rec.localPort)
    this.#streams.delete(rec.sidText)
    rec.local?.destroy()
    if (rec.ws && rec.ws.readyState !== WebSocket.CLOSED && rec.ws.readyState !== WebSocket.CLOSING) rec.ws.close(CLOSE.NORMAL)
    if (rec.opened) this.emit("stream-close", publicTag(rec.tag))
  }

  // ─── 预热 ────────────────────────────────────────────────────────────────

  #refillPrewarm() {
    if (this.#stopped || this.#status !== "ready") return
    while (this.#prewarm.length < this.#prewarmTarget) {
      const ws = this.#newSocket()
      const slot = { ws, ch: null, onMessage: null, timer: null }
      slot.onMessage = (data, isBinary) => {
        const msg = isBinary ? null : parseControlFrame(data)
        const ch = msg?.t === "hello" && msg.v === DLP_VERSION ? decodeField(msg.ch, CHALLENGE_BYTES) : null
        if (!ch || slot.ch) {
          ws.close(CLOSE.PROTOCOL_ERROR)
          return
        }
        slot.ch = ch
      }
      ws.on("message", slot.onMessage)
      ws.on("error", () => {})
      ws.on("close", () => {
        this.#timers.clearTimeout(slot.timer)
        const index = this.#prewarm.indexOf(slot)
        if (index >= 0) {
          this.#prewarm.splice(index, 1)
          this.#refillPrewarm()
        }
      })
      // 空闲上限压在 Relay 首条消息超时之内（见构造函数里的 RFC 待定）
      slot.timer = this.#timers.setTimeout(() => ws.close(CLOSE.NORMAL), Math.min(PREWARM_IDLE_MS, FIRST_MESSAGE_TIMEOUT_MS - 1_000))
      slot.timer?.unref?.()
      this.#prewarm.push(slot)
    }
  }

  #takePrewarm() {
    const index = this.#prewarm.findIndex((slot) => slot.ch && slot.ws.readyState === WebSocket.OPEN)
    if (index < 0) return null
    const [slot] = this.#prewarm.splice(index, 1)
    this.#timers.clearTimeout(slot.timer)
    return slot
  }

  // ─── 外层连接 ────────────────────────────────────────────────────────────

  #newSocket() {
    const options = { perMessageDeflate: false, maxPayload: MAX_DATA_MESSAGE_BYTES, handshakeTimeout: 10_000 }
    if (this.#outerPin && this.#endpoint.startsWith("wss:")) {
      const pin = this.#outerPin
      // 显式 outerPin：跳过 CA 与主机名，改为只认这张叶证书；在 secureConnect 阶段、Upgrade 之前比对
      options.createConnection = (opts) => {
        const socket = tls.connect({
          ...opts,
          path: undefined,
          servername: net.isIP(opts.host) ? "" : opts.servername ?? opts.host,
          rejectUnauthorized: false,
        })
        socket.once("secureConnect", () => {
          const raw = socket.getPeerCertificate()?.raw
          const fp = raw ? createHash("sha256").update(raw).digest("hex") : ""
          if (fp !== pin) socket.destroy(new Error("outer certificate pin mismatch"))
        })
        return socket
      }
    }
    return new WebSocket(this.#endpoint, options)
  }
}

function parseClientOpen(req) {
  if (!req || typeof req !== "object" || Array.isArray(req)) return null
  if (req.v !== DLP_VERSION) return null
  if (req.kind !== "device" && req.kind !== "bootstrap") return null
  if (!Number.isSafeInteger(req.ts) || req.ts < 0) return null
  const route = decodeField(req.route, ROUTE_BYTES)
  const key = decodeField(req.key, KEY_BYTES)
  const nonce = decodeField(req.nonce, NONCE_BYTES)
  const mac = decodeField(req.mac, MAC_BYTES)
  if (!route || !key || !nonce || !mac) return null
  return { kind: req.kind, route, key, ts: req.ts, nonce, mac }
}

function decodeField(value, length) {
  try {
    return unb64u(value, length)
  } catch {
    return null
  }
}

function publicTag(tag) {
  return { kind: tag.kind, ...(tag.deviceId ? { deviceId: tag.deviceId } : {}) }
}

function waitForHello(ws) {
  return waitForControl(ws, "hello", FIRST_MESSAGE_TIMEOUT_MS)
}

function waitForControl(ws, type, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(new Error(`timeout waiting for ${type}`)), timeoutMs)
    timer.unref?.()
    const onMessage = (data, isBinary) => {
      const msg = isBinary ? null : parseControlFrame(data)
      if (msg?.t === type) finish(null, msg)
      else finish(new Error(`unexpected message while waiting for ${type}`))
    }
    const onClose = () => finish(new Error(`closed while waiting for ${type}`))
    const finish = (error, msg) => {
      clearTimeout(timer)
      ws.off("message", onMessage)
      ws.off("close", onClose)
      if (error) reject(error)
      else resolve(msg)
    }
    ws.on("message", onMessage)
    ws.on("close", onClose)
    ws.on("error", () => {})
  })
}

function closeAndWait(ws, code) {
  return new Promise((resolve) => {
    if (ws.readyState === WebSocket.CLOSED) return resolve()
    const timer = setTimeout(() => {
      ws.terminate()
      resolve()
    }, 2_000)
    timer.unref?.()
    ws.once("close", () => {
      clearTimeout(timer)
      resolve()
    })
    if (ws.readyState === WebSocket.CONNECTING) ws.terminate()
    else ws.close(code)
  })
}
