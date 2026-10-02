/**
 * 预览代理：只拨 127.0.0.1、黑名单、过期、撤销即断开、WebSocket、响应截断。
 * 手机 API 不能批准端口。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { createServer, request as httpRequest } from "node:http"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { Readable } from "node:stream"
import { apply } from "../src/index.js"
import { pluginCapabilities } from "../src/protocol-caps.js"
import {
  PREVIEW_BLACKLIST,
  PREVIEW_IDLE_MS,
  PREVIEW_MAX_BYTES,
  PREVIEW_TTL_MS,
  createPreviewService,
  forwardPreviewHeaders,
  matchPreviewPath,
  previewLookup,
  previewPortProblem,
  stripHopByHop,
} from "../src/preview-proxy.js"

test("能力声明 preview v1，协议号不变", () => {
  const caps = pluginCapabilities()
  assert.deepEqual(caps.preview, { v: 1, detect: 1 })
  assert.equal(caps.protocol, 2)
  assert.deepEqual(caps.diagnostics, { v: 1 })
  assert.equal(caps.events.host, true)
})

test("端口黑名单、插件端口和 Host 端口都不能批准", () => {
  assert.deepEqual(PREVIEW_BLACKLIST, [22, 3306, 5432, 6379, 11211, 27017, 9200])
  assert.equal(PREVIEW_TTL_MS, 2 * 60 * 60 * 1000)
  assert.equal(PREVIEW_MAX_BYTES, 50 * 1024 * 1024)
  assert.equal(PREVIEW_IDLE_MS, 60_000)
  for (const port of PREVIEW_BLACKLIST) {
    assert.equal(previewPortProblem(port, { pluginPort: 18640, hostPort: 3000 }), "这个端口不能预览")
  }
  assert.equal(previewPortProblem(18640, { pluginPort: 18640, hostPort: 3000 }), "不能预览插件自己的端口")
  assert.equal(previewPortProblem(3000, { pluginPort: 18640, hostPort: 3000 }), "不能预览 Host 自己的端口")
  assert.equal(previewPortProblem(5173, { pluginPort: 18640, hostPort: 3000 }), null)
  assert.equal(previewPortProblem(0, {}), "端口无效")
  assert.equal(previewPortProblem("22abc", {}), "端口无效")
})

test("lookup 只接受 127.0.0.1", async () => {
  const ok = await new Promise((resolve) => {
    previewLookup("127.0.0.1", {}, (err, address, family) => resolve({ err, address, family }))
  })
  assert.equal(ok.err, null)
  assert.equal(ok.address, "127.0.0.1")
  assert.equal(ok.family, 4)
  const bad = await new Promise((resolve) => previewLookup("169.254.169.254", {}, (err) => resolve(err)))
  assert.equal(bad.code, "ENOTLOOPBACK")
})

test("转发头改写 Host/Origin，去掉凭据和 hop-by-hop", () => {
  const stripped = stripHopByHop({
    connection: "close, x-hop",
    "x-hop": "1",
    "keep-alive": "timeout=5",
    "content-type": "text/html",
    host: "evil.test",
  })
  assert.equal(stripped["content-type"], "text/html")
  assert.equal(stripped["x-hop"], undefined)
  assert.equal(stripped["keep-alive"], undefined)
  assert.equal(stripped.host, undefined)

  const headers = forwardPreviewHeaders({
    host: "203.0.113.9:18640",
    origin: "https://evil.test",
    authorization: "Bearer sekret-token",
    "x-dsh-link-token": "sekret-token",
    "x-forwarded-for": "198.51.100.4",
    referer: "https://plugin.example/dsh-link/mobile/preview/abc/secret",
    "content-type": "text/plain",
  }, 5173)
  assert.equal(headers.host, "localhost:5173")
  assert.equal(headers.origin, "http://localhost:5173")
  assert.equal(headers.authorization, undefined)
  assert.equal(headers["x-dsh-link-token"], undefined)
  assert.equal(headers["x-forwarded-for"], undefined)
  assert.equal(headers.referer, undefined)
  assert.equal(headers["content-type"], "text/plain")
})

test("预览路径不接受绝对 URL", () => {
  const id = "ab".repeat(12)
  const matched = matchPreviewPath(`/dsh-link/mobile/preview/${id}/assets/app.js?x=1`)
  assert.equal(matched.previewId, id)
  assert.equal(matched.path, "/assets/app.js?x=1")
  assert.equal(matchPreviewPath(`http://169.254.169.254/dsh-link/mobile/preview/${id}/`), null)
  assert.equal(matchPreviewPath("/dsh-link/mobile/previews"), null)
  assert.equal(matchPreviewPath(`/dsh-link/mobile/preview/${"zz".repeat(12)}/`), null)
})

function listen(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler)
    server.listen(0, "127.0.0.1", () => resolve(server))
  })
}

function callHttp(server, path, headers = {}) {
  return new Promise((resolve, reject) => {
    const r = httpRequest({
      host: "127.0.0.1",
      port: server.address().port,
      path,
      method: "GET",
      headers,
    }, (res) => {
      const chunks = []
      res.on("data", (c) => chunks.push(c))
      res.on("end", () => resolve({ status: res.statusCode, body: Buffer.concat(chunks), headers: res.headers }))
    })
    r.on("error", reject)
    r.end()
  })
}

function frontFor(svc) {
  return listen((req, res) => {
    if (!svc.handleHttp(req, res)) {
      res.writeHead(404)
      res.end()
    }
  })
}

test("代理只连回环、改写头、截断，并且不记录路径", async () => {
  const seen = []
  const upstream = await listen((req, res) => {
    seen.push({ url: req.url, headers: { ...req.headers }, remote: req.socket.remoteAddress })
    if (req.url === "/big") {
      res.writeHead(200, { "content-type": "application/octet-stream", "content-length": "400" })
      res.end(Buffer.alloc(400, 9))
      return
    }
    res.writeHead(200, { "content-type": "text/plain" })
    res.end("ok-body")
  })
  const port = upstream.address().port
  const state = { previews: [] }
  const logs = []
  const svc = createPreviewService({
    state,
    save() {},
    pluginPort: 18640,
    hostPort: 9,
    maxBytes: 120,
    idleMs: 1000,
    logger: { info(m) { logs.push(String(m)) } },
  })
  const front = await frontFor(svc)
  try {
    assert.equal(svc.approve({ port: 22, label: "ssh" }).status, 400)
    assert.equal(svc.approve({ port: 18640, label: "plugin" }).status, 400)
    assert.equal(svc.approve({ port: 9, label: "host" }).status, 400)
    const approved = svc.approve({ port, label: "Vite" })
    assert.equal(approved.status, 200)
    const again = svc.approve({ port, label: "Vite" })
    assert.equal(again.body.preview.previewId, approved.body.preview.previewId)
    const id = approved.body.preview.previewId
    const stored = state.previews[0]
    assert.equal(stored.expiresAt - stored.approvedAt, PREVIEW_TTL_MS)

    const page = await callHttp(front, `/dsh-link/mobile/preview/${id}/secret-marker?q=1`, {
      host: "203.0.113.8",
      origin: "https://evil.test",
      authorization: "Bearer sekret-token",
      "x-dsh-link-token": "sekret-token",
      "x-forwarded-for": "198.51.100.9",
    })
    assert.equal(page.status, 200)
    assert.equal(page.body.toString(), "ok-body")
    assert.equal(seen[0].url, "/secret-marker?q=1")
    assert.equal(seen[0].remote, "127.0.0.1")
    assert.equal(seen[0].headers.host, `localhost:${port}`)
    assert.equal(seen[0].headers.origin, `http://localhost:${port}`)
    assert.equal(seen[0].headers.authorization, undefined)
    assert.equal(seen[0].headers["x-dsh-link-token"], undefined)
    assert.equal(seen[0].headers["x-forwarded-for"], undefined)
    assert.equal(logs.join("\n").includes("secret-marker"), false)
    assert.equal(JSON.stringify(state).includes("secret-marker"), false)
    assert.equal(state.previews[0].hits, 1)
    assert.equal(svc.listPublic()[0].hits, undefined)
    assert.equal(svc.listPanel()[0].hits, 1)

    const big = await callHttp(front, `/dsh-link/mobile/preview/${id}/big`)
    assert.equal(big.status, 200)
    assert.equal(big.body.length, 120)

    state.previews.push({
      previewId: "cd".repeat(12),
      port: 22,
      label: "ssh",
      approvedAt: 1,
      expiresAt: Date.now() + 60_000,
      hits: 0,
    })
    const blocked = await callHttp(front, `/dsh-link/mobile/preview/${"cd".repeat(12)}/`)
    assert.equal(blocked.status, 403)
  } finally {
    svc.stop()
    front.close()
    upstream.close()
  }
})

test("空闲超时、过期和撤销都会断开", async () => {
  let arrived = false
  const upstream = await listen((req, res) => {
    if (req.url === "/hang") {
      arrived = true
      return
    }
    res.writeHead(200)
    res.end("up")
  })
  const port = upstream.address().port
  const state = { previews: [] }
  const svc = createPreviewService({
    state,
    save() {},
    pluginPort: 1,
    hostPort: 2,
    idleMs: 300,
  })
  const front = await frontFor(svc)
  const id = svc.approve({ port, label: "dev" }).body.preview.previewId
  try {
    const timed = await callHttp(front, `/dsh-link/mobile/preview/${id}/hang`)
    assert.equal(timed.status, 504)
    assert.equal(arrived, true)

    arrived = false
    const hanging = callHttp(front, `/dsh-link/mobile/preview/${id}/hang`)
    for (let i = 0; i < 40 && !arrived; i++) await new Promise((r) => setTimeout(r, 25))
    assert.equal(arrived, true)
    assert.equal(svc.revoke(id).status, 200)
    await assert.rejects(hanging)
    assert.equal((await callHttp(front, `/dsh-link/mobile/preview/${id}/`)).status, 404)

    const expired = createPreviewService({
      state: {
        previews: [{
          previewId: "ef".repeat(12),
          port,
          label: "old",
          approvedAt: 1,
          expiresAt: Date.now() - 1,
          hits: 0,
        }],
      },
      save() {},
      pluginPort: 1,
      hostPort: 2,
    })
    const expiredFront = await frontFor(expired)
    try {
      const res = await callHttp(expiredFront, `/dsh-link/mobile/preview/${"ef".repeat(12)}/`)
      assert.equal(res.status, 404)
      assert.equal(expired.listPublic().length, 0)
    } finally {
      expired.stop()
      expiredFront.close()
    }
  } finally {
    svc.stop()
    front.close()
    upstream.close()
  }
})

const TMP = mkdtempSync(join(tmpdir(), "dsh-preview-test-"))
const PORT = 24000 + Math.floor(Math.random() * 1000)
const hostServer = await listen((_req, res) => {
  res.writeHead(200)
  res.end("host")
})
const devSeen = []
let devHang
const devServer = await listen((req, res) => {
  devSeen.push({ url: req.url, headers: { ...req.headers }, remote: req.socket.remoteAddress })
  if (req.url === "/hold") {
    devHang = res
    return
  }
  res.writeHead(200, { "content-type": "text/plain" })
  res.end("from-dev")
})
const { WebSocketServer, default: WebSocket } = await import("ws")
const wss = new WebSocketServer({ server: devServer })
wss.on("connection", (ws) => {
  ws.on("message", (data) => ws.send(data))
})

const registered = []
const effects = []
const ctx = {
  logger: { info() {}, warn() {} },
  get(name) {
    if (name === "webServer") {
      return {
        port: hostServer.address().port,
        register(route) { registered.push(route); return () => {} },
        tapIndex() { return () => {} },
      }
    }
    return null
  },
  on() {},
  effect(fn) { effects.push(fn()) },
}
await apply(ctx, { port: PORT, pairingTtlSeconds: 300, autoApprove: true, stateDir: TMP, eventPollIntervalMs: 60000 })

function panelCall(path, { method = "GET", body, remoteAddress = "127.0.0.1" } = {}) {
  const route = registered.find((item) => item.path === path)
  const req = Readable.from(body ? [Buffer.from(body)] : [])
  req.method = method
  req.url = path
  req.headers = { host: "127.0.0.1", "content-type": "application/json" }
  req.socket = { remoteAddress }
  let status = 0
  const chunks = []
  const res = {
    writeHead(code) { status = code },
    end(payload) { if (payload) chunks.push(Buffer.from(payload)) },
    req,
  }
  return route.handler(req, res).then(() => {
    const text = Buffer.concat(chunks).toString("utf8")
    return { status, body: text ? JSON.parse(text) : null }
  })
}

async function proxyFetch(path, init = {}) {
  const https = await import("node:https")
  const tls = JSON.parse(readFileSync(join(TMP, "tls.json"), "utf8"))
  const agent = new https.Agent({ ca: tls.cert, rejectUnauthorized: true, checkServerIdentity: () => undefined })
  return new Promise((resolve, reject) => {
    const req = https.request(new URL(`https://127.0.0.1:${PORT}${path}`), {
      method: init.method || "GET",
      headers: init.headers || {},
      agent,
    }, (res) => {
      const chunks = []
      res.on("data", (c) => chunks.push(c))
      res.on("end", () => resolve(new Response(Buffer.concat(chunks), { status: res.statusCode, headers: res.headers })))
    })
    req.on("error", reject)
    if (init.body) req.write(init.body)
    req.end()
  })
}

const pairInfo = registered.find((route) => route.path === "/dsh-link/pair-info")
let token
{
  let out = ""
  const res = { writeHead() {}, end(b) { out = String(b ?? "") } }
  await pairInfo.handler({
    method: "GET",
    headers: { host: "127.0.0.1" },
    url: "/dsh-link/pair-info",
    socket: { remoteAddress: "127.0.0.1" },
  }, res)
  const code = JSON.parse(out).pairingCode
  const pair = await proxyFetch("/dsh-link/pair", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "preview-test", requestId: "preview-test-1" }),
  })
  assert.equal(pair.status, 200)
  token = (await pair.json()).token
}

test("面板批准后手机只能转发，不能自己批准", async () => {
  const devPort = devServer.address().port
  const remote = await panelCall("/dsh-link/previews", {
    method: "POST",
    body: JSON.stringify({ port: devPort, label: "Vite" }),
    remoteAddress: "203.0.113.5",
  })
  assert.equal(remote.status, 403)

  for (const port of [22, PORT, hostServer.address().port]) {
    const denied = await panelCall("/dsh-link/previews", {
      method: "POST",
      body: JSON.stringify({ port, label: "nope" }),
    })
    assert.equal(denied.status, 400)
  }

  const added = await panelCall("/dsh-link/previews", {
    method: "POST",
    body: JSON.stringify({ port: devPort, label: "Vite" }),
  })
  assert.equal(added.status, 200)
  const preview = added.body.preview
  assert.equal(preview.port, devPort)
  assert.equal(preview.label, "Vite")
  assert.equal(typeof preview.previewId, "string")

  const onPlugin = await proxyFetch("/dsh-link/previews", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ port: devPort, label: "from-phone" }),
  })
  assert.equal(onPlugin.status, 404)

  const mobileApprove = await proxyFetch("/dsh-link/mobile/previews", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ port: devPort, label: "from-phone" }),
  })
  assert.equal(mobileApprove.status, 404)

  const listed = await proxyFetch("/dsh-link/mobile/previews", {
    headers: { authorization: `Bearer ${token}` },
  })
  assert.equal(listed.status, 200)
  const pub = (await listed.json()).previews.find((item) => item.previewId === preview.previewId)
  assert.deepEqual(Object.keys(pub).sort(), ["expiresAt", "label", "port", "previewId"])

  const page = await proxyFetch(`/dsh-link/mobile/preview/${preview.previewId}/secret-marker`, {
    headers: {
      authorization: `Bearer ${token}`,
      origin: "https://evil.test",
      "x-forwarded-for": "198.51.100.8",
    },
  })
  assert.equal(page.status, 200)
  assert.equal(await page.text(), "from-dev")
  const hit = devSeen.find((item) => item.url === "/secret-marker")
  assert.ok(hit)
  assert.equal(hit.remote, "127.0.0.1")
  assert.equal(hit.headers.host, `localhost:${devPort}`)
  assert.equal(hit.headers.origin, `http://localhost:${devPort}`)
  assert.equal(hit.headers.authorization, undefined)
  const stateText = readFileSync(join(TMP, "state.json"), "utf8")
  assert.equal(stateText.includes("secret-marker"), false)

  const tls = JSON.parse(readFileSync(join(TMP, "tls.json"), "utf8"))
  const echoed = await new Promise((resolve, reject) => {
    const ws = new WebSocket(`wss://127.0.0.1:${PORT}/dsh-link/mobile/preview/${preview.previewId}/socket`, {
      headers: { authorization: `Bearer ${token}` },
      ca: tls.cert,
      rejectUnauthorized: true,
      checkServerIdentity: () => undefined,
    })
    const timer = setTimeout(() => {
      ws.terminate()
      reject(new Error("websocket timeout"))
    }, 5000)
    ws.on("open", () => ws.send("ping-preview"))
    ws.on("message", (data) => {
      clearTimeout(timer)
      ws.close()
      resolve(String(data))
    })
    ws.on("error", (err) => {
      clearTimeout(timer)
      reject(err)
    })
  })
  assert.equal(echoed, "ping-preview")

  const holding = proxyFetch(`/dsh-link/mobile/preview/${preview.previewId}/hold`, {
    headers: { authorization: `Bearer ${token}` },
  })
  for (let i = 0; i < 40 && !devHang; i++) await new Promise((r) => setTimeout(r, 25))
  assert.ok(devHang)
  const revoked = await panelCall("/dsh-link/previews/revoke", {
    method: "POST",
    body: JSON.stringify({ previewId: preview.previewId }),
  })
  assert.equal(revoked.status, 200)
  await assert.rejects(holding)
  const gone = await proxyFetch(`/dsh-link/mobile/preview/${preview.previewId}/`, {
    headers: { authorization: `Bearer ${token}` },
  })
  assert.equal(gone.status, 404)
})

test.after(() => {
  for (const fn of effects) {
    try { fn() } catch {}
  }
  try { wss.close() } catch {}
  hostServer.close()
  devServer.close()
  rmSync(TMP, { recursive: true, force: true })
})
