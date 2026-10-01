/**
 * 连接诊断。手机与回环面板共用一份结构，文案不在这里：
 * 只返回 status / code / detail，detail 仅数字、布尔和短枚举。
 *
 * 不返回 token、证书全文、绝对路径、IP、消息正文。
 * host.rpc 只调只读的 workspace.list（已在 RPC_METHOD_ALLOWLIST），不写 Host。
 */
import { X509Certificate } from "node:crypto"
import { PLUGIN_PROTOCOL, pluginCapabilities } from "./protocol-caps.js"
import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
export const PLUGIN_VERSION = require("../package.json").version

/** 面板「一键检查」要在 1 秒内返回；超时路径也算在这预算里。 */
export const DIAGNOSTIC_RPC_TIMEOUT_MS = 800
/** 回环 RPC 超过这个时间仍成功，记为 warn 而不是 ok。 */
export const DIAGNOSTIC_RPC_SLOW_MS = 300
const CERT_WARN_DAYS = 30
const DAY_MS = 24 * 60 * 60 * 1000
const CLOCK_MIN_SEC = 1_577_836_800 // 2020-01-01T00:00:00Z
const CLOCK_MAX_SEC = 4_102_444_800 // 2100-01-01T00:00:00Z

const STATUSES = new Set(["ok", "warn", "fail", "skip"])
const CODE = /^[A-Z][A-Z0-9_]{0,40}$/
const ENUM_STRING = /^[A-Za-z0-9_.-]{1,32}$/
const IPV4 = /^(?:\d{1,3}\.){3}\d{1,3}$/
const LONG_HEX = /^[0-9a-f]{16,}$/i

const CHECKS = Object.freeze({
  mobile: ["host.rpc", "host.services", "plugin.version", "tls.cert", "pairing.devices", "remote.relay", "clock"],
  panel: ["host.rpc", "host.services", "plugin.version", "tls.cert", "pairing.devices", "listen.addresses", "remote.relay", "clock"],
})

export function tlsExpiryMs(certPem) {
  if (!certPem || typeof certPem !== "string") return null
  try {
    const parsed = Date.parse(new X509Certificate(certPem).validTo)
    return Number.isFinite(parsed) ? parsed : null
  } catch {
    return null
  }
}

/**
 * 监听地址分类。100.64.0.0/10 与 Tailscale IPv6 `fd7a:115c:a1e0::/48` 归为 tailnet。
 * 无法识别的字符串（路径、主机名）返回 null，调用方不得把它写进结果。
 */
export function classifyListenHost(host) {
  const raw = String(host ?? "").trim().toLowerCase().replace(/^\[|\]$/g, "")
  if (!raw || raw.length > 64 || /[/\\]/.test(raw)) return null
  if (raw === "localhost" || raw === "::1") return "loopback"
  const v4 = ipv4ToInt(raw)
  if (v4 != null) {
    if (ipv4InNet(v4, 0x7f000000, 0xff000000)) return "loopback"
    // 100.64.0.0/10
    if (ipv4InNet(v4, 0x64400000, 0xffc00000)) return "tailnet"
    if (ipv4InNet(v4, 0x0a000000, 0xff000000)) return "private"
    if (ipv4InNet(v4, 0xac100000, 0xfff00000)) return "private"
    if (ipv4InNet(v4, 0xc0a80000, 0xffff0000)) return "private"
    return "other"
  }
  if (raw.startsWith("fd7a:115c:a1e0:")) return "tailnet"
  if (/^[0-9a-f:]+$/.test(raw) && raw.includes(":")) return "other"
  return null
}

/**
 * @param {object} input
 * @param {(method: string, params: object) => Promise<unknown>} [input.rpc]
 * @param {{ workspaceChanges?: boolean, typertGateway?: boolean, sessions?: boolean }} [input.services]
 * @param {string} [input.certPem] 只用来算到期时间，不会进入返回值
 * @param {string} [input.fingerprint]
 * @param {object[]} [input.devices]
 * @param {object | null} [input.device]
 * @param {string[]} [input.listenHosts]
 * @param {object | (() => object)} [input.remote]
 */
export function hostDiagnosticsSource(input = {}) {
  const services = input.services ?? {}
  const fingerprint = typeof input.fingerprint === "string" ? input.fingerprint : ""
  const notAfterMs = tlsExpiryMs(input.certPem)
  return {
    now: input.now,
    rpc: input.rpc,
    rpcTimeoutMs: input.rpcTimeoutMs,
    rpcSlowMs: input.rpcSlowMs,
    services,
    plugin: {
      version: PLUGIN_VERSION,
      protocol: PLUGIN_PROTOCOL,
      capabilities: pluginCapabilities({ changes: Boolean(services.workspaceChanges) }),
    },
    tls: fingerprint ? { fingerprint, notAfterMs } : null,
    devices: input.devices,
    device: input.device ?? null,
    listenHosts: input.listenHosts,
    remote: input.remote,
  }
}

/**
 * @param {object} rt hostDiagnosticsSource 的产物，或测试用的同形对象
 * @param {{ scope: "mobile" | "panel" }} options
 */
export async function runDiagnostics(rt, { scope } = {}) {
  if (scope !== "mobile" && scope !== "panel") {
    throw new TypeError("scope must be mobile or panel")
  }
  const source = rt ?? {}
  const nowMs = typeof source.now === "function" ? source.now() : Date.now()
  const generatedAt = clockSeconds(nowMs)
  const byId = {
    "host.rpc": await checkHostRpc(source),
    "host.services": checkServices(source),
    "plugin.version": checkPlugin(source),
    "tls.cert": checkTls(source, nowMs),
    "pairing.devices": checkPairing(source, scope),
    "listen.addresses": checkListen(source),
    "remote.relay": checkRemote(source),
    clock: checkClock(generatedAt),
  }
  const checks = CHECKS[scope].map((id) => byId[id])
  const report = {
    version: 1,
    generatedAt: generatedAt ?? 0,
    checks,
  }
  assertReportPrivate(report)
  return report
}

async function checkHostRpc(rt) {
  if (typeof rt.rpc !== "function") {
    return row("host.rpc", "fail", "HOST_RPC_UNAVAILABLE", { ms: 0 })
  }
  const timeoutMs = finiteMs(rt.rpcTimeoutMs, DIAGNOSTIC_RPC_TIMEOUT_MS)
  const slowMs = finiteMs(rt.rpcSlowMs, DIAGNOSTIC_RPC_SLOW_MS)
  const started = Date.now()
  let timer
  try {
    await Promise.race([
      Promise.resolve().then(() => rt.rpc("workspace.list", {})),
      new Promise((_, reject) => {
        timer = setTimeout(() => {
          const error = new Error("timeout")
          error.code = "TIMEOUT"
          reject(error)
        }, timeoutMs)
      }),
    ])
    const ms = Date.now() - started
    if (ms >= slowMs) return row("host.rpc", "warn", "HOST_RPC_SLOW", { ms })
    return row("host.rpc", "ok", "HOST_RPC_OK", { ms })
  } catch (error) {
    const ms = Date.now() - started
    const timedOut = error?.code === "TIMEOUT"
    return row("host.rpc", "fail", timedOut ? "HOST_RPC_TIMEOUT" : "HOST_RPC_FAILED", { ms })
  } finally {
    if (timer) clearTimeout(timer)
  }
}

function checkServices(rt) {
  const services = rt.services
  if (!services || typeof services !== "object" || Array.isArray(services)) {
    return row("host.services", "fail", "HOST_SERVICES_UNAVAILABLE", {})
  }
  const detail = {
    workspaceChanges: Boolean(services.workspaceChanges),
    typertGateway: Boolean(services.typertGateway),
    sessions: Boolean(services.sessions),
  }
  if (!detail.typertGateway || !detail.sessions) {
    return row("host.services", "fail", "HOST_SERVICES_MISSING", detail)
  }
  if (!detail.workspaceChanges) return row("host.services", "warn", "HOST_SERVICES_PARTIAL", detail)
  return row("host.services", "ok", "HOST_SERVICES_OK", detail)
}

function checkPlugin(rt) {
  const plugin = rt.plugin ?? {}
  const protocol = Number(plugin.protocol)
  const detail = {}
  if (Number.isSafeInteger(protocol) && protocol > 0 && protocol < 1000) detail.protocol = protocol
  if (isEnumString(plugin.version)) detail.version = String(plugin.version)
  const caps = plugin.capabilities
  if (caps && typeof caps === "object") {
    for (const key of ["sync", "questions", "requests", "control", "files", "diagnostics"]) {
      if (caps[key] != null) detail[`cap${key[0].toUpperCase()}${key.slice(1)}`] = true
    }
  }
  if (!detail.protocol) return row("plugin.version", "fail", "PLUGIN_VERSION_INVALID", detail)
  if (!detail.version) return row("plugin.version", "warn", "PLUGIN_VERSION_UNKNOWN", detail)
  return row("plugin.version", "ok", "PLUGIN_VERSION_OK", detail)
}

function checkTls(rt, nowMs) {
  const tls = rt.tls
  if (!tls || typeof tls !== "object") return row("tls.cert", "fail", "TLS_CERT_MISSING", {})
  const prefix = fingerprintPrefix(tls.fingerprint)
  if (!prefix) return row("tls.cert", "fail", "TLS_CERT_MISSING", {})
  const notAfterMs = typeof tls.notAfterMs === "number" ? tls.notAfterMs : Number.NaN
  if (!Number.isFinite(notAfterMs)) {
    return row("tls.cert", "fail", "TLS_CERT_UNKNOWN_EXPIRY", { fingerprintPrefix: prefix })
  }
  const daysRemaining = Math.floor((notAfterMs - nowMs) / DAY_MS)
  const detail = { fingerprintPrefix: prefix, daysRemaining }
  if (daysRemaining < 0) return row("tls.cert", "fail", "TLS_CERT_EXPIRED", detail)
  if (daysRemaining < CERT_WARN_DAYS) return row("tls.cert", "warn", "TLS_CERT_EXPIRING", detail)
  return row("tls.cert", "ok", "TLS_CERT_OK", detail)
}

function checkPairing(rt, scope) {
  if (scope === "mobile") {
    const device = rt.device
    const idOk = typeof device?.deviceId === "string" && device.deviceId.length > 0 && device.deviceId.length <= 64
    const blocked = device?.status === "pending" || device?.status === "revoked"
    if (!device || !idOk || blocked) {
      return row("pairing.devices", "fail", "PAIRING_SELF_INVALID", { valid: false })
    }
    // 已配对设备落盘时通常没有 status 字段；显式 active 与缺省都算有效。
    if (device.status != null && device.status !== "active") {
      return row("pairing.devices", "warn", "PAIRING_SELF_LEGACY", { valid: true })
    }
    return row("pairing.devices", "ok", "PAIRING_SELF_OK", { valid: true })
  }
  if (!Array.isArray(rt.devices)) {
    return row("pairing.devices", "fail", "PAIRING_UNAVAILABLE", {})
  }
  let count = 0
  let pending = 0
  for (const device of rt.devices) {
    if (!device || typeof device !== "object") continue
    if (device.status === "pending") pending += 1
    else if (device.status !== "revoked") count += 1
  }
  const detail = { count, pending }
  if (count === 0) return row("pairing.devices", "warn", "PAIRING_NONE", detail)
  return row("pairing.devices", "ok", "PAIRING_OK", detail)
}

function checkListen(rt) {
  if (!Array.isArray(rt.listenHosts)) {
    return row("listen.addresses", "fail", "LISTEN_UNAVAILABLE", {})
  }
  const counts = { private: 0, tailnet: 0, other: 0, loopback: 0 }
  for (const host of rt.listenHosts) {
    const category = classifyListenHost(host)
    if (category) counts[category] += 1
  }
  const total = counts.private + counts.tailnet + counts.other + counts.loopback
  const detail = { ...counts, total }
  if (total === 0) return row("listen.addresses", "warn", "LISTEN_NONE", detail)
  if (counts.private === 0 && counts.tailnet === 0 && counts.other > 0) {
    return row("listen.addresses", "warn", "LISTEN_UNTRUSTED", detail)
  }
  if (counts.private === 0 && counts.tailnet === 0) {
    return row("listen.addresses", "warn", "LISTEN_NO_LAN", detail)
  }
  return row("listen.addresses", "ok", "LISTEN_OK", detail)
}

function checkRemote(rt) {
  const remote = readRemote(rt)
  const detail = {
    enabled: remote.enabled,
    state: remote.state,
    replaced: remote.replaced,
  }
  if (remote.lastCode) detail.lastCode = remote.lastCode
  // 关掉中继（未启用）也要给出 fail 和 code，方便面板一眼看到。
  if (!remote.enabled) return row("remote.relay", "fail", "REMOTE_DISABLED", detail)
  if (remote.replaced) return row("remote.relay", "fail", "REMOTE_REPLACED", detail)
  if (remote.state === "ready" && !remote.lastCode) return row("remote.relay", "ok", "REMOTE_READY", detail)
  if (remote.state === "connecting" && !remote.lastCode) return row("remote.relay", "warn", "REMOTE_CONNECTING", detail)
  if (remote.lastCode) return row("remote.relay", "fail", "REMOTE_REJECTED", detail)
  return row("remote.relay", "fail", "REMOTE_DOWN", detail)
}

function checkClock(generatedAt) {
  if (generatedAt == null) return row("clock", "fail", "CLOCK_INVALID", {})
  if (generatedAt < CLOCK_MIN_SEC || generatedAt > CLOCK_MAX_SEC) {
    return row("clock", "warn", "CLOCK_UNREASONABLE", { unixSec: generatedAt })
  }
  return row("clock", "ok", "CLOCK_OK", { unixSec: generatedAt })
}

function readRemote(rt) {
  const raw = typeof rt.remote === "function" ? rt.remote() : rt.remote
  const remote = raw && typeof raw === "object" ? raw : {}
  const state = ["off", "connecting", "ready", "error"].includes(remote.state) ? remote.state : "off"
  const lastCode = isEnumString(remote.error) && !IPV4.test(String(remote.error)) ? String(remote.error) : ""
  return {
    enabled: Boolean(remote.enabled),
    state,
    replaced: Boolean(remote.replaced),
    lastCode,
  }
}

function row(id, status, code, detail) {
  return {
    id,
    status: STATUSES.has(status) ? status : "fail",
    code: CODE.test(code) ? code : "UNKNOWN",
    detail: sanitizeDetail(detail),
  }
}

function sanitizeDetail(value, depth = 0) {
  if (depth > 2 || value == null) return {}
  if (typeof value !== "object" || Array.isArray(value)) return {}
  const out = {}
  for (const [key, item] of Object.entries(value)) {
    if (!/^[a-zA-Z][a-zA-Z0-9]{0,32}$/.test(key)) continue
    if (typeof item === "boolean") out[key] = item
    else if (typeof item === "number" && Number.isFinite(item) && Math.abs(item) <= Number.MAX_SAFE_INTEGER) out[key] = item
    else if (isEnumString(item)) out[key] = String(item)
  }
  return out
}

function isEnumString(value) {
  if (typeof value !== "string" || !ENUM_STRING.test(value)) return false
  if (IPV4.test(value) || LONG_HEX.test(value)) return false
  return true
}

function fingerprintPrefix(value) {
  const hex = String(value ?? "").replace(/:/g, "").toLowerCase()
  if (!/^[0-9a-f]{8,}$/.test(hex)) return ""
  return hex.slice(0, 8)
}

function clockSeconds(nowMs) {
  if (typeof nowMs !== "number" || !Number.isFinite(nowMs)) return null
  const sec = Math.floor(nowMs / 1000)
  if (!Number.isSafeInteger(sec) || sec < 0) return null
  return sec
}

function finiteMs(value, fallback) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 60_000 ? value : fallback
}

function ipv4ToInt(host) {
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host)
  if (!match) return null
  const parts = match.slice(1).map(Number)
  if (parts.some((part) => part > 255)) return null
  // 不用 <<：JS 位运算是有符号 32 位，192.168 / 172.16 会变成负数，和网段常量比不相等。
  return parts[0] * 16777216 + parts[1] * 65536 + parts[2] * 256 + parts[3]
}

function ipv4InNet(hostInt, base, mask) {
  return (hostInt & mask) >>> 0 === base >>> 0
}

const PRIVATE_TEXT = /[/\\]|token|secret|password|bearer|\b(?:\d{1,3}\.){3}\d{1,3}\b/i

function assertReportPrivate(report) {
  const text = JSON.stringify(report)
  if (PRIVATE_TEXT.test(text)) {
    throw new Error("diagnostics report failed privacy check")
  }
}
