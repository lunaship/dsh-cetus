/**
 * 连接诊断：每个检查的 ok / warn / fail，mobile 不带面板字段，
 * 输出里没有 token、绝对路径或地址。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { execFileSync } from "node:child_process"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { X509Certificate } from "node:crypto"
import { pluginCapabilities } from "../src/protocol-caps.js"
import {
  DIAGNOSTIC_RPC_TIMEOUT_MS,
  classifyListenHost,
  hostDiagnosticsSource,
  runDiagnostics,
  tlsExpiryMs,
} from "../src/diagnostics.js"

const DAY = 24 * 60 * 60 * 1000
const NOW = 1_750_000_000_000
const FULL_FP = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
const SELF_TOKEN = "sekret-token-self-0123456789abcdef0123456789abcd"
const OTHER_TOKEN = "sekret-token-other-0123456789abcdef0123456789ab"
const SECRET_PATH = "/Users/me/.dsh/state.json"

const LEAK = /sekret-token|\/Users\/|\/home\/|state\.json|eeff0011|192\.168\.|100\.64\.|fd7a:115c/

function healthy(overrides = {}) {
  return {
    now: () => NOW,
    rpc: async () => ({ items: [] }),
    services: { workspaceChanges: true, typertGateway: true, sessions: true },
    plugin: {
      version: "0.1.0-beta.19",
      protocol: 2,
      capabilities: pluginCapabilities({ changes: true }),
    },
    tls: { fingerprint: FULL_FP, notAfterMs: NOW + 40 * DAY },
    devices: [
      { deviceId: "dev-self", status: "active", token: SELF_TOKEN, name: SECRET_PATH },
      { deviceId: "dev-other", status: "active", token: OTHER_TOKEN },
      { deviceId: "dev-wait", status: "pending", token: OTHER_TOKEN },
    ],
    device: { deviceId: "dev-self", status: "active", token: SELF_TOKEN },
    listenHosts: ["192.168.1.20", "100.64.1.2", "fd7a:115c:a1e0::8", SECRET_PATH],
    remote: { enabled: true, state: "ready", error: "", replaced: false },
    ...overrides,
  }
}

function check(report, id) {
  const found = report.checks.find((item) => item.id === id)
  assert.ok(found, id)
  return found
}

function assertPrivate(report) {
  const text = JSON.stringify(report)
  assert.doesNotMatch(text, LEAK)
  assert.doesNotMatch(text, /[/\\]/)
  for (const item of report.checks) {
    for (const value of Object.values(item.detail)) {
      const kind = typeof value
      assert.ok(kind === "number" || kind === "boolean" || kind === "string", `${item.id} detail`)
      if (kind === "string") assert.match(value, /^[A-Za-z0-9_.-]{1,32}$/)
    }
  }
}

test("能力声明 diagnostics v1，旧字段仍在", () => {
  const caps = pluginCapabilities()
  assert.deepEqual(caps.diagnostics, { v: 1 })
  assert.equal(caps.protocol, 2)
  assert.equal(pluginCapabilities({ changes: true }).diagnostics.v, 1)
  assert.equal(caps.files.changes, undefined)
})

test("诊断 RPC 超时预算低于 1 秒", () => {
  assert.ok(DIAGNOSTIC_RPC_TIMEOUT_MS > 0)
  assert.ok(DIAGNOSTIC_RPC_TIMEOUT_MS <= 900)
})

test("scope 只能是 mobile 或 panel", async () => {
  await assert.rejects(() => runDiagnostics(healthy(), { scope: "public" }), /scope/)
  await assert.rejects(() => runDiagnostics(healthy(), {}), /scope/)
})

test("面板范围：全部通过时 1 秒内出结果，且只有枚举和数字", async () => {
  let method = ""
  let params = null
  const started = Date.now()
  const report = await runDiagnostics(healthy({
    rpc: async (name, body) => {
      method = name
      params = body
      return { items: [] }
    },
  }), { scope: "panel" })
  assert.ok(Date.now() - started < 1000)
  assert.equal(method, "workspace.list")
  assert.deepEqual(params, {})
  assert.equal(report.version, 1)
  assert.equal(report.generatedAt, Math.floor(NOW / 1000))
  assert.deepEqual(report.checks.map((item) => item.id), [
    "host.rpc", "host.services", "plugin.version", "tls.cert", "pairing.devices", "listen.addresses", "remote.relay", "clock",
  ])
  assert.equal(check(report, "host.rpc").status, "ok")
  assert.equal(check(report, "host.rpc").code, "HOST_RPC_OK")
  assert.equal(typeof check(report, "host.rpc").detail.ms, "number")
  assert.equal(check(report, "host.services").code, "HOST_SERVICES_OK")
  assert.equal(check(report, "plugin.version").code, "PLUGIN_VERSION_OK")
  assert.equal(check(report, "plugin.version").detail.version, "0.1.0-beta.19")
  assert.equal(check(report, "plugin.version").detail.capDiagnostics, true)
  assert.equal(check(report, "tls.cert").code, "TLS_CERT_OK")
  assert.equal(check(report, "tls.cert").detail.fingerprintPrefix, "aabbccdd")
  assert.equal(check(report, "tls.cert").detail.daysRemaining, 40)
  assert.deepEqual(check(report, "pairing.devices").detail, { count: 2, pending: 1 })
  assert.equal(check(report, "listen.addresses").code, "LISTEN_OK")
  assert.equal(check(report, "listen.addresses").detail.private, 1)
  assert.equal(check(report, "listen.addresses").detail.tailnet, 2)
  assert.equal(check(report, "remote.relay").code, "REMOTE_READY")
  assert.equal(check(report, "clock").detail.unixSec, report.generatedAt)
  assertPrivate(report)
})

test("手机范围不泄露面板字段、其他设备或地址", async () => {
  const report = await runDiagnostics(healthy(), { scope: "mobile" })
  assert.deepEqual(report.checks.map((item) => item.id), [
    "host.rpc", "host.services", "plugin.version", "tls.cert", "pairing.devices", "remote.relay", "clock",
  ])
  assert.equal(check(report, "pairing.devices").code, "PAIRING_SELF_OK")
  assert.deepEqual(check(report, "pairing.devices").detail, { valid: true })
  assert.equal(JSON.stringify(report).includes("listen.addresses"), false)
  assert.equal(JSON.stringify(report).includes("\"count\""), false)
  assertPrivate(report)
})

test("host.rpc：超时是 fail，偏慢是 warn，调用失败是 fail", async () => {
  let stopHang = () => {}
  const hung = new Promise((_, reject) => { stopHang = reject })
  hung.catch(() => {})
  let timeout
  try {
    timeout = await runDiagnostics(healthy({
      rpc: () => hung,
      rpcTimeoutMs: 30,
    }), { scope: "mobile" })
  } finally {
    stopHang(Object.assign(new Error("stop"), { code: "TIMEOUT" }))
  }
  assert.equal(check(timeout, "host.rpc").status, "fail")
  assert.equal(check(timeout, "host.rpc").code, "HOST_RPC_TIMEOUT")
  assert.ok(check(timeout, "host.rpc").detail.ms >= 30)
  assert.ok(check(timeout, "host.rpc").detail.ms < 1000)

  const slow = await runDiagnostics(healthy({
    rpc: () => new Promise((resolve) => { setTimeout(resolve, 25) }),
    rpcSlowMs: 10,
  }), { scope: "mobile" })
  assert.equal(check(slow, "host.rpc").status, "warn")
  assert.equal(check(slow, "host.rpc").code, "HOST_RPC_SLOW")

  const failed = await runDiagnostics(healthy({
    rpc: async () => { throw new Error(`boom ${SECRET_PATH} ${SELF_TOKEN}`) },
  }), { scope: "mobile" })
  assert.equal(check(failed, "host.rpc").status, "fail")
  assert.equal(check(failed, "host.rpc").code, "HOST_RPC_FAILED")
  assertPrivate(failed)

  const missing = await runDiagnostics(healthy({ rpc: undefined }), { scope: "mobile" })
  assert.equal(check(missing, "host.rpc").code, "HOST_RPC_UNAVAILABLE")
})

test("host.services：缺可选服务是 warn，缺必需服务或读不到是 fail", async () => {
  const partial = await runDiagnostics(healthy({
    services: { workspaceChanges: false, typertGateway: true, sessions: true },
  }), { scope: "panel" })
  assert.equal(check(partial, "host.services").status, "warn")
  assert.equal(check(partial, "host.services").code, "HOST_SERVICES_PARTIAL")
  assert.equal(check(partial, "host.services").detail.workspaceChanges, false)

  const noGateway = await runDiagnostics(healthy({
    services: { workspaceChanges: true, typertGateway: false, sessions: true },
  }), { scope: "panel" })
  assert.equal(check(noGateway, "host.services").status, "fail")
  assert.equal(check(noGateway, "host.services").code, "HOST_SERVICES_MISSING")

  const noSessions = await runDiagnostics(healthy({
    services: { workspaceChanges: true, typertGateway: true, sessions: false },
  }), { scope: "panel" })
  assert.equal(check(noSessions, "host.services").code, "HOST_SERVICES_MISSING")

  const unavailable = await runDiagnostics(healthy({ services: null }), { scope: "panel" })
  assert.equal(check(unavailable, "host.services").code, "HOST_SERVICES_UNAVAILABLE")
})

test("plugin.version：没有版本字符串是 warn，没有协议号是 fail，路径不会被当成版本", async () => {
  const unknown = await runDiagnostics(healthy({
    plugin: { protocol: 2, version: SECRET_PATH, capabilities: { diagnostics: { v: 1 } } },
  }), { scope: "mobile" })
  assert.equal(check(unknown, "plugin.version").status, "warn")
  assert.equal(check(unknown, "plugin.version").code, "PLUGIN_VERSION_UNKNOWN")
  assert.equal(check(unknown, "plugin.version").detail.version, undefined)
  assert.equal(check(unknown, "plugin.version").detail.capDiagnostics, true)
  assertPrivate(unknown)

  const invalid = await runDiagnostics(healthy({ plugin: { version: "0.1.0" } }), { scope: "mobile" })
  assert.equal(check(invalid, "plugin.version").status, "fail")
  assert.equal(check(invalid, "plugin.version").code, "PLUGIN_VERSION_INVALID")
})

test("tls.cert：不足 30 天 warn，已过期或缺失 fail，指纹只留前 8 位", async () => {
  const expiring = await runDiagnostics(healthy({
    tls: { fingerprint: "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99", notAfterMs: NOW + 29 * DAY },
  }), { scope: "mobile" })
  assert.equal(check(expiring, "tls.cert").status, "warn")
  assert.equal(check(expiring, "tls.cert").code, "TLS_CERT_EXPIRING")
  assert.equal(check(expiring, "tls.cert").detail.fingerprintPrefix, "aabbccdd")
  assert.equal(check(expiring, "tls.cert").detail.daysRemaining, 29)
  assert.equal(JSON.stringify(expiring).includes("eeff0011"), false)

  const boundary = await runDiagnostics(healthy({
    tls: { fingerprint: FULL_FP, notAfterMs: NOW + 30 * DAY },
  }), { scope: "mobile" })
  assert.equal(check(boundary, "tls.cert").status, "ok")
  assert.equal(check(boundary, "tls.cert").detail.daysRemaining, 30)

  const expired = await runDiagnostics(healthy({
    tls: { fingerprint: FULL_FP, notAfterMs: NOW - DAY },
  }), { scope: "mobile" })
  assert.equal(check(expired, "tls.cert").status, "fail")
  assert.equal(check(expired, "tls.cert").code, "TLS_CERT_EXPIRED")

  const missing = await runDiagnostics(healthy({ tls: null }), { scope: "mobile" })
  assert.equal(check(missing, "tls.cert").code, "TLS_CERT_MISSING")

  const unknown = await runDiagnostics(healthy({
    tls: { fingerprint: FULL_FP, notAfterMs: null },
  }), { scope: "mobile" })
  assert.equal(check(unknown, "tls.cert").code, "TLS_CERT_UNKNOWN_EXPIRY")
  assert.equal(check(unknown, "tls.cert").detail.daysRemaining, undefined)
})

test("pairing.devices：面板无设备是 warn，数据缺失是 fail；手机只看自己", async () => {
  const none = await runDiagnostics(healthy({ devices: [] }), { scope: "panel" })
  assert.equal(check(none, "pairing.devices").status, "warn")
  assert.equal(check(none, "pairing.devices").code, "PAIRING_NONE")
  assert.deepEqual(check(none, "pairing.devices").detail, { count: 0, pending: 0 })

  const bad = await runDiagnostics(healthy({ devices: null }), { scope: "panel" })
  assert.equal(check(bad, "pairing.devices").status, "fail")
  assert.equal(check(bad, "pairing.devices").code, "PAIRING_UNAVAILABLE")

  const revoked = await runDiagnostics(healthy({
    devices: [{ deviceId: "dev-old", status: "revoked", token: OTHER_TOKEN }],
  }), { scope: "panel" })
  assert.equal(check(revoked, "pairing.devices").code, "PAIRING_NONE")
  assertPrivate(revoked)

  const invalid = await runDiagnostics(healthy({ device: { deviceId: "dev-self", status: "pending", token: SELF_TOKEN } }), { scope: "mobile" })
  assert.equal(check(invalid, "pairing.devices").status, "fail")
  assert.equal(check(invalid, "pairing.devices").code, "PAIRING_SELF_INVALID")
  assert.deepEqual(check(invalid, "pairing.devices").detail, { valid: false })
  assertPrivate(invalid)

  const legacy = await runDiagnostics(healthy({
    device: { deviceId: "dev-self", status: "legacy", token: SELF_TOKEN },
  }), { scope: "mobile" })
  assert.equal(check(legacy, "pairing.devices").status, "warn")
  assert.equal(check(legacy, "pairing.devices").code, "PAIRING_SELF_LEGACY")

  const absent = await runDiagnostics(healthy({ device: null }), { scope: "mobile" })
  assert.equal(check(absent, "pairing.devices").code, "PAIRING_SELF_INVALID")
})

test("listen.addresses：无地址、只剩回环、只有公网是 warn，读不到是 fail", async () => {
  const none = await runDiagnostics(healthy({ listenHosts: [] }), { scope: "panel" })
  assert.equal(check(none, "listen.addresses").status, "warn")
  assert.equal(check(none, "listen.addresses").code, "LISTEN_NONE")

  const loopback = await runDiagnostics(healthy({ listenHosts: ["127.0.0.1", "localhost"] }), { scope: "panel" })
  assert.equal(check(loopback, "listen.addresses").code, "LISTEN_NO_LAN")
  assert.equal(check(loopback, "listen.addresses").detail.loopback, 2)

  const untrusted = await runDiagnostics(healthy({ listenHosts: ["8.8.8.8", "1.1.1.1"] }), { scope: "panel" })
  assert.equal(check(untrusted, "listen.addresses").code, "LISTEN_UNTRUSTED")
  assert.equal(check(untrusted, "listen.addresses").detail.other, 2)
  assert.equal(JSON.stringify(untrusted).includes("8.8.8.8"), false)

  const unavailable = await runDiagnostics(healthy({ listenHosts: null }), { scope: "panel" })
  assert.equal(check(unavailable, "listen.addresses").status, "fail")
  assert.equal(check(unavailable, "listen.addresses").code, "LISTEN_UNAVAILABLE")

  const mobile = await runDiagnostics(healthy({ listenHosts: ["8.8.8.8"] }), { scope: "mobile" })
  assert.equal(mobile.checks.some((item) => item.id === "listen.addresses"), false)
})

test("remote.relay：关掉中继、拒绝码、掉线、被顶替是 fail，连接中是 warn", async () => {
  const disabled = await runDiagnostics(healthy({
    remote: { enabled: false, state: "off", error: "", replaced: false },
  }), { scope: "panel" })
  assert.equal(check(disabled, "remote.relay").status, "fail")
  assert.equal(check(disabled, "remote.relay").code, "REMOTE_DISABLED")
  assert.equal(check(disabled, "remote.relay").detail.enabled, false)

  const rejected = await runDiagnostics(healthy({
    remote: () => ({ enabled: true, state: "error", error: "ENOTFOUND", replaced: false }),
  }), { scope: "panel" })
  assert.equal(check(rejected, "remote.relay").status, "fail")
  assert.equal(check(rejected, "remote.relay").code, "REMOTE_REJECTED")
  assert.equal(check(rejected, "remote.relay").detail.lastCode, "ENOTFOUND")

  const prose = await runDiagnostics(healthy({
    remote: { enabled: true, state: "error", error: `打不开 ${SECRET_PATH} token=${SELF_TOKEN}`, replaced: false },
  }), { scope: "panel" })
  assert.equal(check(prose, "remote.relay").status, "fail")
  assert.equal(check(prose, "remote.relay").code, "REMOTE_DOWN")
  assert.equal(check(prose, "remote.relay").detail.lastCode, undefined)
  assertPrivate(prose)

  const replaced = await runDiagnostics(healthy({
    remote: { enabled: true, state: "ready", error: "", replaced: true },
  }), { scope: "panel" })
  assert.equal(check(replaced, "remote.relay").code, "REMOTE_REPLACED")

  const connecting = await runDiagnostics(healthy({
    remote: { enabled: true, state: "connecting", error: "", replaced: false },
  }), { scope: "mobile" })
  assert.equal(check(connecting, "remote.relay").status, "warn")
  assert.equal(check(connecting, "remote.relay").code, "REMOTE_CONNECTING")

  const retrying = await runDiagnostics(healthy({
    remote: { enabled: true, state: "connecting", error: "ETIMEDOUT", replaced: false },
  }), { scope: "mobile" })
  assert.equal(check(retrying, "remote.relay").status, "fail")
  assert.equal(check(retrying, "remote.relay").code, "REMOTE_REJECTED")
  assert.equal(check(retrying, "remote.relay").detail.lastCode, "ETIMEDOUT")
})

test("clock：离谱的时间是 warn，读不到是 fail", async () => {
  const early = await runDiagnostics(healthy({ now: () => 1_000 * 1000 }), { scope: "mobile" })
  assert.equal(check(early, "clock").status, "warn")
  assert.equal(check(early, "clock").code, "CLOCK_UNREASONABLE")
  assert.equal(early.generatedAt, 1000)

  const invalid = await runDiagnostics(healthy({ now: () => Number.NaN }), { scope: "mobile" })
  assert.equal(check(invalid, "clock").status, "fail")
  assert.equal(check(invalid, "clock").code, "CLOCK_INVALID")
  assert.equal(invalid.generatedAt, 0)
})

test("地址分类：私网、Tailscale、回环、公网", () => {
  assert.equal(classifyListenHost("10.1.2.3"), "private")
  assert.equal(classifyListenHost("172.16.0.1"), "private")
  assert.equal(classifyListenHost("172.31.255.255"), "private")
  assert.equal(classifyListenHost("172.15.0.1"), "other")
  assert.equal(classifyListenHost("172.32.0.1"), "other")
  assert.equal(classifyListenHost("192.168.0.1"), "private")
  assert.equal(classifyListenHost("100.64.0.1"), "tailnet")
  assert.equal(classifyListenHost("100.127.255.1"), "tailnet")
  assert.equal(classifyListenHost("100.63.0.1"), "other")
  assert.equal(classifyListenHost("100.128.0.1"), "other")
  assert.equal(classifyListenHost("127.0.0.1"), "loopback")
  assert.equal(classifyListenHost("localhost"), "loopback")
  assert.equal(classifyListenHost("::1"), "loopback")
  assert.equal(classifyListenHost("fd7a:115c:a1e0::8"), "tailnet")
  assert.equal(classifyListenHost("2001:db8::1"), "other")
  assert.equal(classifyListenHost("8.8.8.8"), "other")
  assert.equal(classifyListenHost(SECRET_PATH), null)
  assert.equal(classifyListenHost(SELF_TOKEN), null)
})

function tempCertPem() {
  const dir = mkdtempSync(join(tmpdir(), "dsh-diag-cert-"))
  const cert = join(dir, "cert.pem")
  try {
    execFileSync("openssl", [
      "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
      "-keyout", join(dir, "key.pem"), "-out", cert, "-days", "40", "-nodes", "-subj", "/CN=dsh-links",
    ], { stdio: "ignore" })
    return readFileSync(cert, "utf8")
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

test("证书 PEM 只用于算到期，不会进入诊断上下文或结果", async () => {
  const marker = "PEM-SECRET-MARKER"
  const cert = tempCertPem()
  const expiry = tlsExpiryMs(cert)
  assert.equal(typeof expiry, "number")
  assert.ok(expiry > Date.now())
  const parsed = new X509Certificate(cert)
  assert.equal(expiry, Date.parse(parsed.validTo))

  const source = hostDiagnosticsSource({
    rpc: async () => ({}),
    services: { workspaceChanges: false, typertGateway: true, sessions: true },
    certPem: `${cert}\n${marker}`,
    fingerprint: FULL_FP,
    devices: [],
    listenHosts: ["10.0.0.8"],
    remote: { enabled: false, state: "off" },
  })
  assert.equal(source.certPem, undefined)
  assert.equal(JSON.stringify(source).includes(marker), false)
  assert.equal(JSON.stringify(source).includes("BEGIN CERTIFICATE"), false)
  const report = await runDiagnostics(source, { scope: "panel" })
  assert.equal(check(report, "tls.cert").detail.fingerprintPrefix, "aabbccdd")
  assert.equal(check(report, "host.services").code, "HOST_SERVICES_PARTIAL")
  assert.equal(check(report, "remote.relay").code, "REMOTE_DISABLED")
  assert.equal(JSON.stringify(report).includes(marker), false)
  assert.equal(JSON.stringify(report).includes(FULL_FP), false)
})

test("面板按钮和回环路由挂在源码上，18640 不暴露该路径", () => {
  const index = readFileSync(new URL("../src/index.js", import.meta.url), "utf8")
  const panel = readFileSync(new URL("../src/panel.js", import.meta.url), "utf8")
  const auth = readFileSync(new URL("./auth.test.mjs", import.meta.url), "utf8")
  assert.match(index, /path: "\/dsh-link\/diagnostics"/)
  assert.match(index, /"\/dsh-link\/diagnostics"/)
  assert.match(panel, /一键检查/)
  assert.match(panel, /Run check/)
  assert.match(panel, /\/dsh-link\/diagnostics/)
  assert.match(auth, /\/dsh-link\/diagnostics/)
})
