/**
 * 插件集成 DLP/1（RFC §5.2、§6.1–§6.6，M1 报告里留给 M2 的 SKIP 项）。
 *
 * 真实 apply() + 最小假 Relay（test/helpers/fake-dlp-relay.mjs）。「手机」一侧由测试扮演：
 * 让假 Relay 推 open → Agent 验证后开数据连接 → 测试在这条连接上做内层 TLS（钉扎插件证书）再发 HTTP。
 * 全部 state 在临时 stateDir，不碰 ~/.dsh（CLAUDE.md 红线）。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { createServer, request as httpRequest } from "node:http"
import https from "node:https"
import tls from "node:tls"
import { once } from "node:events"
import { createHash, randomBytes } from "node:crypto"
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { Readable } from "node:stream"
import QRCode from "qrcode"
import { createWebSocketStream } from "ws"
import { apply } from "../src/index.js"
import { bindLocalRpcRuntime, unbindLocalRpcRuntime } from "../src/local-rpc.js"
import {
  b64u, bootstrapKeys, clientMac, clientTranscript, deviceRelayKey, hostPublicKey, routeId as deriveRouteId,
} from "../src/remote/crypto.js"
import { startFakeRelay } from "./helpers/fake-dlp-relay.mjs"

const TMP = mkdtempSync(join(tmpdir(), "dsh-remote-plugin-"))

let upstream, relay, registered, dispose, proxyPort
/** 截获 qr.png 编进二维码的原文（测试替身，不改产品代码）。 */
let lastQrText = ""

test.before(async () => {
  process.env.DSH_LINKS_DLP_ALLOW_INSECURE_WS = "1"
  // 旧版 DLR/1 遗留：接入凭据 + 一台「云端独立设备」
  writeFileSync(join(TMP, "state.json"), JSON.stringify({
    relay: { agentAddress: "relay.example.com:8444", routeSecret: "legacy-secret", hostSeed: "x" },
    devices: [{ deviceId: "dev-legacy", name: "旧手机 · 云", tokenHash: "unused", via: "relay", createdAt: 1, lastSeenAt: 1 }],
  }))
  const origToBuffer = QRCode.toBuffer.bind(QRCode)
  QRCode.toBuffer = (text, options) => {
    lastQrText = text
    return origToBuffer(text, options)
  }
  upstream = createServer((req, res) => res.end("ok"))
  upstream.listen(0, "127.0.0.1")
  await once(upstream, "listening")
  relay = await startFakeRelay()
  registered = []
  const effects = []
  const ctx = {
    logger: { info() {}, warn() {} },
    get(name) {
      if (name !== "webServer") return null
      return { port: upstream.address().port, register(route) { registered.push(route); return () => {} } }
    },
    on() {},
    effect(fn) { effects.push(fn()) },
  }
  proxyPort = 23000 + Math.floor(Math.random() * 1000)
  await apply(ctx, { port: proxyPort, pairingTtlSeconds: 300, autoApprove: true, stateDir: TMP, eventPollIntervalMs: 60000 })
  dispose = () => { for (const fn of effects) try { fn() } catch {} }
  bindLocalRpcRuntime({
    invoke: async ({ method }) => (method === "list" ? { items: [] } : {}),
    stream: async () => ({ next: async () => ({ value: { type: "baseline", value: { items: [], archivedSessionIds: [] } } }) }),
  })
})

test.after(async () => {
  unbindLocalRpcRuntime()
  dispose()
  await relay.close()
  upstream.close()
  delete process.env.DSH_LINKS_DLP_ALLOW_INSECURE_WS
  rmSync(TMP, { recursive: true, force: true })
})

// ─── 工具 ────────────────────────────────────────────────────────────────

const route = (path) => registered.find((r) => r.path === path)

/** 以回环同源身份调用面板路由。 */
function panel(path, { body, method } = {}) {
  let status = 0
  let out = ""
  const res = {
    writeHead(code) { status = code },
    end(b) { out = b == null ? "" : Buffer.isBuffer(b) ? b : String(b) },
  }
  const host = `127.0.0.1:${upstream.address().port}`
  let req
  if (body !== undefined) {
    req = Readable.from([Buffer.from(JSON.stringify(body))])
    req.method = method ?? "POST"
    req.headers = { host, "content-type": "application/json" }
  } else {
    req = { method: method ?? "GET", headers: { host } }
  }
  req.url = path
  req.socket = { remoteAddress: "127.0.0.1" }
  return Promise.resolve(route(path).handler(req, res)).then(() => {
    const raw = Buffer.isBuffer(out) ? out.toString("utf8") : out
    return { status, raw, body: safeJson(raw) }
  })
}

function safeJson(text) {
  try { return JSON.parse(text) } catch { return null }
}

function state() {
  return JSON.parse(readFileSync(join(TMP, "state.json"), "utf8"))
}

function certFingerprint() {
  return JSON.parse(readFileSync(join(TMP, "tls.json"), "utf8")).fingerprint
}

/** 局域网直连 18640（钉扎插件证书）。 */
function lanFetch(path, { method = "GET", headers = {}, body } = {}) {
  const pin = certFingerprint().replace(/:/g, "").toLowerCase()
  return new Promise((resolve, reject) => {
    const req = https.request({
      host: "127.0.0.1", port: proxyPort, path, method, headers,
      rejectUnauthorized: false,
      checkServerIdentity: (_h, cert) => (createHash("sha256").update(cert.raw).digest("hex") === pin ? undefined : new Error("pin")),
    }, (res) => collect(res, resolve))
    req.on("error", reject)
    if (body) req.write(body)
    req.end()
  })
}

function collect(res, resolve) {
  const chunks = []
  res.on("data", (c) => chunks.push(c))
  res.on("end", () => resolve({ status: res.statusCode, body: safeJson(Buffer.concat(chunks).toString("utf8")) }))
}

function jsonPost(body, token) {
  return {
    method: "POST",
    headers: { "content-type": "application/json", ...(token ? { "x-dsh-link-token": token } : {}) },
    body: JSON.stringify(body),
  }
}

async function lanPair(deviceName) {
  const code = (await panel("/dsh-link/pair-info")).body.pairingCode
  const r = await lanFetch("/dsh-link/pair", jsonPost({ code, deviceName }))
  assert.equal(r.status, 200, JSON.stringify(r.body))
  return r.body
}

function routeIdOf() {
  return deriveRouteId(hostPublicKey(Buffer.from(state().remote.hostKey, "base64url")))
}

function clientOpenReq({ kind, key, macKey }) {
  const ts = Math.floor(Date.now() / 1000)
  const nonce = randomBytes(16)
  const routeId = routeIdOf()
  const mac = clientMac(macKey, clientTranscript({ route: routeId, kind, key, ts, nonce }))
  return { v: 1, route: b64u(routeId), kind, key: b64u(key), ts, nonce: b64u(nonce), mac: b64u(mac) }
}

/** 扮演手机：经假 Relay 开一条流，返回数据连接上钉扎插件证书的内层 TLS socket。 */
async function openTunnel(req) {
  const accepted = relay.nextAccept()
  relay.sendOpen(req)
  const { ws, sigOk } = await accepted
  assert.equal(sigOk, true)
  ws.send(JSON.stringify({ t: "ready" }))
  const duplex = createWebSocketStream(ws)
  duplex.on("error", () => {})
  const inner = tls.connect({ socket: duplex, rejectUnauthorized: false })
  await once(inner, "secureConnect")
  const fp = createHash("sha256").update(inner.getPeerCertificate().raw).digest("hex")
  assert.equal(fp, certFingerprint().replace(/:/g, "").toLowerCase(), "内层 TLS 必须是插件证书")
  return { ws, inner }
}

/** 在隧道上发一个 HTTP 请求。 */
function tunnelFetch(tunnel, path, { method = "GET", headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = httpRequest({ createConnection: () => tunnel.inner, path, method, headers: { host: "dsh-link", connection: "close", ...headers } },
      (res) => collect(res, resolve))
    req.on("error", reject)
    if (body) req.write(body)
    req.end()
  })
}

async function expectReject(req) {
  const rejected = relay.nextCtrl("reject")
  relay.sendOpen(req)
  return (await rejected).code
}

// ─── 用例（按顺序共享一套插件实例） ─────────────────────────────────────────

test("启动时清除 DLR/1 遗留接入配置；旧云端设备保留并标 via=relay、无远程能力", async () => {
  assert.equal(state().relay, undefined)
  const devices = (await panel("/dsh-link/devices")).body.devices
  const legacy = devices.find((d) => d.deviceId === "dev-legacy")
  assert.equal(legacy.via, "relay")
  assert.equal(legacy.remote, false)
})

test("远程未启用：状态 off，配对响应与 bootstrap 都不带远程能力", async () => {
  assert.equal((await panel("/dsh-link/remote-status")).body.state, "off")
  const paired = await lanPair("家里的手机")
  assert.equal(paired.remote, undefined)
  const boot = await lanFetch("/dsh-link/mobile/bootstrap", { headers: { "x-dsh-link-token": paired.token } })
  assert.equal(boot.status, 200)
  assert.equal(boot.body.remote, null)
  assert.equal(boot.body.relay, null)
  globalThis.__home = paired
})

test("面板拒绝非 wss 的中继地址（ws:// 仅在测试开关下放行）", async () => {
  delete process.env.DSH_LINKS_DLP_ALLOW_INSECURE_WS
  try {
    const r = await panel("/dsh-link/remote-enable", { body: { endpoint: relay.url } })
    assert.equal(r.status, 400)
    assert.equal(state().remote, undefined)
  } finally {
    process.env.DSH_LINKS_DLP_ALLOW_INSECURE_WS = "1"
  }
})

test("启用远程：注册成功后状态 ready；响应不含任何密钥", async () => {
  const r = await panel("/dsh-link/remote-enable", { body: { endpoint: relay.url } })
  assert.equal(r.status, 200)
  assert.equal(r.body.ok, true)
  assert.equal(r.body.state, "ready")
  const remote = state().remote
  assert.equal(remote.enabled, true)
  for (const secret of [remote.hostKey, remote.keySeed]) {
    assert.ok(!r.raw.includes(secret), "面板响应泄露了远程密钥")
    assert.ok(!(await panel("/dsh-link/remote-status")).raw.includes(secret))
    assert.ok(!(await panel("/dsh-link/pair-info")).raw.includes(secret))
  }
})

test("统一码：远程就绪后二维码带 remote{e,r,s}；同一张配对码的种子在多次出图间保持不变", async () => {
  const info = (await panel("/dsh-link/pair-info")).body
  assert.equal(info.remoteStatus.state, "ready")
  assert.equal(info.remote, undefined, "pair-info JSON 不得带 bootstrap 种子")
  await panel("/dsh-link/qr.png")
  const first = JSON.parse(lastQrText)
  await panel("/dsh-link/qr.png")
  const second = JSON.parse(lastQrText)
  assert.equal(first.pairingCode, info.pairingCode)
  assert.deepEqual(first.urls, info.urls)
  assert.equal(first.remote.e, relay.url)
  assert.equal(first.remote.r, b64u(routeIdOf()))
  assert.match(first.remote.s, /^[A-Za-z0-9_-]{22}$/)
  assert.equal(second.remote.s, first.remote.s)
})

test("已配对手机经 bootstrap 自动补齐远程能力（RFC §6.4），k 由 keySeed 派生", async () => {
  const boot = await lanFetch("/dsh-link/mobile/bootstrap", { headers: { "x-dsh-link-token": globalThis.__home.token } })
  assert.equal(boot.status, 200)
  const remote = boot.body.remote
  assert.equal(remote.e, relay.url)
  assert.equal(remote.r, b64u(routeIdOf()))
  const handle = Buffer.from(remote.h, "base64url")
  const expected = deviceRelayKey(Buffer.from(state().remote.keySeed, "base64url"), handle)
  assert.equal(remote.k, b64u(expected))
  const stored = state().devices.find((d) => d.deviceId === globalThis.__home.deviceId)
  assert.equal(stored.remoteHandle, remote.h)
  const listed = (await panel("/dsh-link/devices")).body.devices.find((d) => d.deviceId === globalThis.__home.deviceId)
  assert.equal(listed.remote, true)
  assert.equal(listed.remoteHandle, undefined, "面板不输出 remoteHandle")
  globalThis.__homeRemote = remote
})

test("远程 device 来源：本机 token 可用；拿别的设备 token 403；/pair 403", async () => {
  const other = await lanPair("另一台手机")
  const { h, k } = globalThis.__homeRemote
  const req = () => clientOpenReq({ kind: "device", key: Buffer.from(h, "base64url"), macKey: Buffer.from(k, "base64url") })

  const ok = await openTunnel(req())
  const own = await tunnelFetch(ok, "/dsh-link/mobile/bootstrap", { headers: { "x-dsh-link-token": globalThis.__home.token } })
  assert.equal(own.status, 200)
  assert.equal(own.body.device.name, "家里的手机")

  const cross = await openTunnel(req())
  const stolen = await tunnelFetch(cross, "/dsh-link/mobile/bootstrap", { headers: { "x-dsh-link-token": other.token } })
  assert.equal(stolen.status, 403)

  const pairTunnel = await openTunnel(req())
  const code = (await panel("/dsh-link/pair-info")).body.pairingCode
  const pair = await tunnelFetch(pairTunnel, "/dsh-link/pair", jsonPost({ code, deviceName: "借道配对" }))
  assert.equal(pair.status, 403)
  await panel("/dsh-link/revoke", { body: { deviceId: other.deviceId } })
})

test("远程首配（kind=bootstrap）：只放行 /pair；即使关闭本机确认也进 pending；bootstrap 用一次即失效", async () => {
  assert.equal((await panel("/dsh-link/pair-info")).body.requireConfirm, false)
  await panel("/dsh-link/qr.png")
  const qr = JSON.parse(lastQrText)
  const seed = Buffer.from(qr.remote.s, "base64url")
  const { bootstrapId, bootstrapKey } = bootstrapKeys(seed, Buffer.from(qr.remote.r, "base64url"))
  const req = () => clientOpenReq({ kind: "bootstrap", key: bootstrapId, macKey: bootstrapKey })

  const probe = await openTunnel(req())
  assert.equal((await tunnelFetch(probe, "/dsh-link/health")).status, 403)

  const tunnel = await openTunnel(req())
  const r = await tunnelFetch(tunnel, "/dsh-link/pair", jsonPost({ code: qr.pairingCode, deviceName: "外面的手机", requestId: "remote-pair-0001" }))
  assert.equal(r.status, 200, JSON.stringify(r.body))
  assert.equal(r.body.pending, true)
  assert.ok(Number.isSafeInteger(r.body.serverNow))
  assert.ok(r.body.pendingExpiresAt > r.body.serverNow)
  assert.ok(r.body.remote?.h && r.body.remote?.k, "pending 设备也要拿到远程能力，才能等批准结果")

  // 同一逻辑请求换路重试时，只刷新响应时钟；不得换 token/handle 或重置批准期。
  await new Promise((resolve) => setTimeout(resolve, 15))
  const replayStartedAt = Date.now()
  const replay = await lanFetch("/dsh-link/pair", jsonPost({ code: qr.pairingCode, deviceName: "外面的手机", requestId: "remote-pair-0001" }))
  assert.equal(replay.status, 200)
  assert.ok(replay.body.serverNow >= replayStartedAt)
  assert.ok(replay.body.serverNow > r.body.serverNow)
  assert.deepEqual(replay.body, { ...r.body, serverNow: replay.body.serverNow })
  assert.equal(state().devices.filter((device) => device.deviceId === r.body.deviceId).length, 1)

  const listed = (await panel("/dsh-link/devices")).body.devices.find((d) => d.deviceId === r.body.deviceId)
  assert.equal(listed.status, "pending")
  assert.equal(listed.via, "remote")
  assert.equal(listed.pairedFrom, "远程（经中继）")

  assert.equal(await expectReject(req()), "BOOTSTRAP_USED")
  globalThis.__outside = r.body
})

test("pending 设备经远程只能拿到 403 pending；批准后可用", async () => {
  const { h, k } = globalThis.__outside.remote
  const req = () => clientOpenReq({ kind: "device", key: Buffer.from(h, "base64url"), macKey: Buffer.from(k, "base64url") })
  const before = await tunnelFetch(await openTunnel(req()), "/dsh-link/mobile/bootstrap", { headers: { "x-dsh-link-token": globalThis.__outside.token } })
  assert.equal(before.status, 403)
  assert.equal(before.body.pending, true)
  assert.equal((await panel("/dsh-link/pair-approve", { body: { deviceId: globalThis.__outside.deviceId } })).status, 200)
  const after = await tunnelFetch(await openTunnel(req()), "/dsh-link/mobile/bootstrap", { headers: { "x-dsh-link-token": globalThis.__outside.token } })
  assert.equal(after.status, 200)
})

test("吊销：该设备的远程流立即关闭，之后的 open 得到 UNKNOWN_KEY", async () => {
  const { h, k } = globalThis.__outside.remote
  const req = () => clientOpenReq({ kind: "device", key: Buffer.from(h, "base64url"), macKey: Buffer.from(k, "base64url") })
  const tunnel = await openTunnel(req())
  const closed = once(tunnel.ws, "close")
  assert.equal((await panel("/dsh-link/revoke", { body: { deviceId: globalThis.__outside.deviceId } })).status, 200)
  await closed
  assert.equal(await expectReject(req()), "UNKNOWN_KEY")
})

test("停止远程：状态 off，bootstrap 下发 remote: null（App 只清远程字段），密钥保留", async () => {
  const hostKey = state().remote.hostKey
  const r = await panel("/dsh-link/remote-disable", { body: {} })
  assert.equal(r.status, 200)
  assert.equal(r.body.state, "off")
  assert.equal(state().remote.hostKey, hostKey)
  const boot = await lanFetch("/dsh-link/mobile/bootstrap", { headers: { "x-dsh-link-token": globalThis.__home.token } })
  assert.equal(boot.body.remote, null)
  await panel("/dsh-link/qr.png")
  assert.equal(JSON.parse(lastQrText).remote, undefined, "远程停止后二维码退回纯局域网码")
})

test("重置远程身份需显式确认；确认后换密钥并清空所有设备 handle", async () => {
  assert.equal((await panel("/dsh-link/remote-reset-identity", { body: {} })).status, 400)
  const before = state().remote.hostKey
  const r = await panel("/dsh-link/remote-reset-identity", { body: { confirm: true } })
  assert.equal(r.status, 200)
  assert.notEqual(state().remote.hostKey, before)
  assert.ok(state().devices.every((d) => !d.remoteHandle))
})
