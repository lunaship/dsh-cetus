import assert from "node:assert/strict"
import test from "node:test"
import { createHash } from "node:crypto"
import { createServer } from "node:http"
import https from "node:https"
import { once } from "node:events"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { Readable } from "node:stream"
import { apply, publicDevice } from "../src/index.js"

const TMP = mkdtempSync(join(tmpdir(), "dsh-push-plugin-"))
const PORT = 25000 + Math.floor(Math.random() * 1000)
let upstream, registered, dispose

test.before(async () => {
  upstream = createServer((_req, res) => res.end("ok"))
  upstream.listen(0, "127.0.0.1")
  await once(upstream, "listening")
  registered = []
  const effects = []
  await apply({
    logger: { info() {}, warn() {} },
    get(name) {
      if (name !== "webServer") return null
      return { port: upstream.address().port, register(route) { registered.push(route); return () => {} } }
    },
    on() {},
    effect(fn) { effects.push(fn()) },
  }, { port: PORT, stateDir: TMP, eventPollIntervalMs: 60000 })
  dispose = () => { for (const fn of effects) try { fn() } catch {} }
})

test.after(() => {
  dispose()
  upstream.close()
  rmSync(TMP, { recursive: true, force: true })
})

const route = (path) => registered.find((item) => item.path === path)
const state = () => JSON.parse(readFileSync(join(TMP, "state.json"), "utf8"))
const fingerprint = () => JSON.parse(readFileSync(join(TMP, "tls.json"), "utf8")).fingerprint.replace(/:/g, "").toLowerCase()

function panel(path, body) {
  let status = 0
  let out = ""
  const res = { writeHead(code) { status = code }, end(value) { out = value == null ? "" : String(value) } }
  const req = Readable.from([Buffer.from(JSON.stringify(body ?? {}))])
  req.method = "POST"
  req.headers = { host: "127.0.0.1:" + upstream.address().port, "content-type": "application/json" }
  req.url = path
  req.socket = { remoteAddress: "127.0.0.1" }
  return Promise.resolve(route(path).handler(req, res)).then(() => ({ status, body: JSON.parse(out || "null"), raw: out }))
}

function panelGet(path) {
  let status = 0
  let out = ""
  const res = { writeHead(code) { status = code }, end(value) { out = value == null ? "" : String(value) } }
  const req = { method: "GET", headers: { host: "127.0.0.1:" + upstream.address().port }, url: path, socket: { remoteAddress: "127.0.0.1" } }
  return Promise.resolve(route(path).handler(req, res)).then(() => ({ status, body: JSON.parse(out || "null"), raw: out }))
}

function mobile(path, { method = "POST", token, body } = {}) {
  const payload = body === undefined ? null : Buffer.from(JSON.stringify(body))
  return new Promise((resolve, reject) => {
    const req = https.request({
      host: "127.0.0.1", port: PORT, path, method,
      headers: { ...(payload ? { "content-type": "application/json", "content-length": payload.length } : {}), ...(token ? { "x-dsh-link-token": token } : {}) },
      rejectUnauthorized: false,
      checkServerIdentity: (_host, cert) => createHash("sha256").update(cert.raw).digest("hex") === fingerprint() ? undefined : new Error("pin"),
    }, (res) => {
      const chunks = []
      res.on("data", (chunk) => chunks.push(chunk))
      res.on("end", () => resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString("utf8") || "null") }))
    })
    req.on("error", reject)
    if (payload) req.write(payload)
    req.end()
  })
}

async function pair(name) {
  const info = await panelGet("/dsh-link/pair-info")
  const paired = await mobile("/dsh-link/pair", { body: { code: info.body.pairingCode, deviceName: name } })
  assert.equal(paired.status, 200)
  return paired.body
}

function registration() {
  return {
    gateway: "https://push.example/ignored",
    kid: "kid-1",
    sealed: { v: 1, kid: "kid-1", enc: "encpayload", ct: "ctpayload" },
    k: "42".repeat(32),
    prefs: { approval: true, question: false, completed: true, failed: true },
  }
}

test("注册、面板关闭和吊销只处理当前设备", async () => {
  const first = await pair("甲")
  const second = await pair("乙")
  const bad = await mobile("/dsh-link/mobile/push/register", { token: first.token, body: { ...registration(), gateway: "http://push.example" } })
  assert.equal(bad.status, 400)

  const registeredPush = await mobile("/dsh-link/mobile/push/register", { token: first.token, body: registration() })
  assert.equal(registeredPush.status, 200)
  assert.deepEqual(registeredPush.body.push, { enabled: true })
  const stored = state().devices.find((item) => item.deviceId === first.deviceId)
  assert.equal(stored.push.gateway, "https://push.example")
  assert.equal(JSON.stringify(publicDevice(stored)).includes(stored.push.k), false)

  const devices = await panelGet("/dsh-link/devices")
  assert.equal(devices.body.devices.find((item) => item.deviceId === first.deviceId).push.enabled, true)
  assert.equal(devices.body.devices.find((item) => item.deviceId === second.deviceId).push.enabled, false)
  assert.equal(devices.raw.includes(stored.push.k), false)

  const disabled = await panel("/dsh-link/push/disable", { deviceId: first.deviceId })
  assert.equal(disabled.status, 200)
  assert.equal(state().devices.find((item) => item.deviceId === first.deviceId).push, undefined)

  await mobile("/dsh-link/mobile/push/register", { token: first.token, body: registration() })
  const revoked = await panel("/dsh-link/revoke", { deviceId: first.deviceId })
  assert.equal(revoked.status, 200)
  assert.equal(state().devices.some((item) => item.deviceId === first.deviceId), false)
  assert.equal(state().devices.find((item) => item.deviceId === second.deviceId).push, undefined)
})
