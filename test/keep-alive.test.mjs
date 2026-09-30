/**
 * R1：插件 HTTP keep-alive 测试。
 *
 * 1. LAN 路径：同一条 TLS socket 连续发 2 个 GET /dsh-link/health，不带 connection: close，
 *    两次都要返回 200，且 socket 没有被服务端关闭。
 * 2. 远程路径：沿用 remote-plugin.test.mjs 里 tunnel.inner 的写法，用同一条 tunnel.inner 连发两次请求。
 * 3. 断言响应头里没有 connection: close。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { createServer as createUpstream } from "node:http"
import https from "node:https"
import tls from "node:tls"
import { once } from "node:events"
import { createHash, randomBytes } from "node:crypto"
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join, dirname } from "node:path"
import { fileURLToPath } from "node:url"
import { createWebSocketStream } from "ws"
import { apply } from "../src/index.js"
import { bindLocalRpcRuntime, unbindLocalRpcRuntime } from "../src/local-rpc.js"
import {
  b64u, clientMac, clientTranscript, routeId as deriveRouteId, deviceRelayKey, hostPublicKey,
} from "../src/remote/crypto.js"
import { startFakeRelay } from "./helpers/fake-dlp-relay.mjs"

const __dirname = dirname(fileURLToPath(import.meta.url))
const TMP = mkdtempSync(join(tmpdir(), "dsh-keepalive-"))

/** 固定的远程身份，测试据此派生路由与设备 MAC；state 写在 TMP，不碰 ~/.dsh。 */
const HOST_KEY = Buffer.alloc(32, 1)
const KEY_SEED = Buffer.alloc(32, 2)
const DEVICE_HANDLE = Buffer.alloc(16, 3)

let dispose, proxyPort, relay, upstream, registered

function route(path) {
  return registered.find((r) => r.path === path)
}

function routeIdOf() {
  return deriveRouteId(hostPublicKey(HOST_KEY))
}

function certFingerprint() {
  return JSON.parse(readFileSync(join(TMP, "tls.json"), "utf8")).fingerprint
}

test.before(async () => {
  process.env.DSH_LINKS_DLP_ALLOW_INSECURE_WS = "1"
  upstream = createUpstream((req, res) => res.end("ok"))
  upstream.listen(0, "127.0.0.1")
  await once(upstream, "listening")
  relay = await startFakeRelay()
  writeFileSync(join(TMP, "state.json"), JSON.stringify({
    remote: {
      enabled: true,
      endpoint: relay.url,
      outerPin: "",
      hostKey: b64u(HOST_KEY),
      keySeed: b64u(KEY_SEED),
      createdAt: 1,
    },
    devices: [{
      deviceId: "dev-keepalive",
      name: "keepalive phone",
      tokenHash: "unused",
      remoteHandle: b64u(DEVICE_HANDLE),
      remoteIssuedAt: 1,
      createdAt: 1,
      lastSeenAt: 1,
    }],
  }))
  registered = []
  const effects = []
  const ctx = {
    logger: { info() {}, warn() {} },
    get(name) {
      if (name !== "webServer") return null
      return { port: upstream.address().port, register(r) { registered.push(r); return () => {} } }
    },
    on() {},
    effect(fn) { effects.push(fn()) },
  }
  proxyPort = 23000 + Math.floor(Math.random() * 1000)
  await apply(ctx, { port: proxyPort, pairingTtlSeconds: 300, autoApprove: true, stateDir: TMP, eventPollIntervalMs: 60000 })
  dispose = () => { for (const fn of effects) try { fn() } catch {} }
  bindLocalRpcRuntime({
    invoke: async () => ({}),
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

test("LAN：同一条 TLS socket 连发 2 次 health 不被服务端关闭，且响应头不含 connection: close", async (t) => {
  const socket = tls.connect({ port: proxyPort, rejectUnauthorized: false })
  await once(socket, "secureConnect")

  const fp = createHash("sha256").update(socket.getPeerCertificate().raw).digest("hex")
  assert.equal(fp, certFingerprint().replace(/:/g, "").toLowerCase())

  const send = (path) => new Promise((resolve, reject) => {
    socket.write(`GET ${path} HTTP/1.1\r\nhost: dsh-link\r\n\r\n`)
    socket.once("error", reject)
    const onData = (chunk) => {
      socket.removeListener("data", onData)
      const data = Buffer.concat(t.chunks ?? [chunk]).toString("utf8")
      t.chunks = undefined
      const headersEnd = data.indexOf("\r\n\r\n")
      const rawHeaders = data.slice(data.indexOf("\r\n") + 1, headersEnd)
      const headers = Object.fromEntries(rawHeaders.split("\r\n").map((line) => {
        const [key, value] = line.split(": ")
        return [key.toLowerCase(), value]
      }))
      const statusLine = data.slice(0, data.indexOf("\r\n"))
      resolve({ statusLine, headers })
    }
    socket.on("data", onData)
  })

  const first = await send("/dsh-link/health")
  assert.ok(first.statusLine.startsWith("HTTP/1.1 200"), `first: ${first.statusLine}`)
  assert.notEqual(first.headers.connection, "close", "first 不能强关连接")

  const second = await send("/dsh-link/health")
  assert.ok(second.statusLine.startsWith("HTTP/1.1 200"), `second: ${second.statusLine}`)
  assert.notEqual(second.headers.connection, "close", "second 不能强关连接")

  socket.end()
})

test("远程：同一条 tunnel.inner 连发 2 次请求，不被服务端关闭，且响应头不含 connection: close", async (t) => {
  // 通过假 relay 开一条远程隧道（用 before() 里固定的远程身份）
  const macKey = deviceRelayKey(KEY_SEED, DEVICE_HANDLE)
  const ts = Math.floor(Date.now() / 1000)
  const nonce = randomBytes(16)
  const route = routeIdOf()
  const mac = clientMac(macKey, clientTranscript({ route, kind: "device", key: DEVICE_HANDLE, ts, nonce }))
  const req = { t: "client_open", v: 1, route: b64u(route), kind: "device", key: b64u(DEVICE_HANDLE), ts, nonce: b64u(nonce), mac: b64u(mac) }

  const accepted = relay.nextAccept()
  relay.sendOpen(req)
  const { ws, sigOk } = await accepted
  assert.equal(sigOk, true)
  ws.send(JSON.stringify({ t: "ready" }))
  const duplex = createWebSocketStream(ws)
  duplex.on("error", () => {})
  const inner = tls.connect({ socket: duplex, rejectUnauthorized: false })
  await once(inner, "secureConnect")

  const send = (path) => new Promise((resolve, reject) => {
    const req = https.request({ createConnection: () => inner, path, method: "GET", headers: { host: "dsh-link", connection: "keep-alive" } }, (res) => {
      const chunks = []
      res.on("data", (c) => chunks.push(c))
      res.on("end", () => {
        resolve({ status: res.statusCode, headers: res.headers })
      })
    })
    req.on("error", reject)
    req.end()
  })

  const first = await send("/dsh-link/health")
  assert.equal(first.status, 200, "first 远程 health 应 200")
  assert.notEqual(first.headers.connection, "close", "first 远程不能强关连接")

  const second = await send("/dsh-link/health")
  assert.equal(second.status, 200, "second 远程 health 应 200")
  assert.notEqual(second.headers.connection, "close", "second 远程不能强关连接")

  inner.end()
})
