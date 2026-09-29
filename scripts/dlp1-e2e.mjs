#!/usr/bin/env node
/**
 * DLP/1 本机端到端（RFC §10.3 第 10 条、§13.1、§13.2 前两条）。
 *
 * 编译并启动真 Go Relay（监听 127.0.0.1:0，从日志取实际端口），接上 Node Agent、一个充当插件的
 * HTTPS 假服务（自签证书）和若干 Node 客户端（tls.connect({ socket: wsDuplex }) 做内层 TLS）。
 * 每条验收项输出一行 PASS / FAIL / SKIP <原因>；有 FAIL 时以非零退出码结束。
 * 临时文件（证书、Relay 二进制、日志）全部放在 mkdtemp 目录，结束时删除。
 *
 * 不进 npm test / prepack：依赖 Go 工具链，性能项要跑约两分钟。用法：npm run test:dlp1-e2e
 * 环境变量 DLP1_E2E_SKIP_ANDROID=1 时跳过 Kotlin 向量测试（Gradle 较慢）。
 */
import { spawn, spawnSync } from "node:child_process"
import { createHash, randomBytes } from "node:crypto"
import { existsSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import http from "node:http"
import https from "node:https"
import { tmpdir } from "node:os"
import { join } from "node:path"
import tls from "node:tls"
import { fileURLToPath } from "node:url"
import WebSocket, { createWebSocketStream } from "ws"
import { generate } from "selfsigned"
import { RemoteAgent } from "../src/remote/agent.js"
import { BootstrapTable } from "../src/remote/bootstrap.js"
import {
  acceptTranscript, b64u, bootstrapKeys, clientMac, clientTranscript, deviceRelayKey, hostPublicKey,
  registerTranscript, routeId as deriveRouteId, signHost,
} from "../src/remote/crypto.js"
import { DATA_CHUNK_BYTES, MAX_DATA_MESSAGE_BYTES } from "../src/remote/wire.js"

const ROOT = fileURLToPath(new URL("..", import.meta.url))
const RELAY_DIR = join(ROOT, "relay")
const BIG_BYTES = 100 * 1024 * 1024
const results = []
const cleanups = []

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const nowSec = () => Math.floor(Date.now() / 1000)

function report(status, name, detail = "") {
  console.log(status === "PASS" && !detail ? `PASS ${name}` : `${status} ${name}${detail ? `：${detail}` : ""}`)
  results.push(status)
}

async function check(name, fn) {
  try {
    const outcome = await fn()
    if (outcome?.skip) report("SKIP", name, outcome.skip)
    else report("PASS", name, outcome?.note ?? "")
  } catch (error) {
    report("FAIL", name, error?.message ?? String(error))
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

// ─── Relay / 插件 / 客户端 ─────────────────────────────────────────────────

async function startRelay(tmp, bin, name, env = {}) {
  const logPath = join(tmp, `${name}.log`)
  const fd = openSync(logPath, "w")
  const child = spawn(bin, [], { env: { ...process.env, DLP_LISTEN: "127.0.0.1:0", ...env }, stdio: ["ignore", fd, fd] })
  cleanups.push(() => child.kill("SIGTERM"))
  const started = Date.now()
  let address = null
  while (!address) {
    if (Date.now() - started > 15_000 || child.exitCode !== null) throw new Error(`${name} 未能启动`)
    await delay(50)
    address = /DLP Relay listening on (\S+)/.exec(readFileSync(logPath, "utf8"))?.[1] ?? null
  }
  return { child, name, url: `ws://${address}/ws`, base: `http://${address}`, logPath, pid: child.pid }
}

async function startPlugin(tmp) {
  const pems = await generate([{ name: "commonName", value: "dlp1-e2e-plugin" }], { days: 1, keySize: 2048 })
  writeFileSync(join(tmp, "plugin-cert.pem"), pems.cert)
  writeFileSync(join(tmp, "plugin-key.pem"), pems.private)
  const der = Buffer.from(pems.cert.replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""), "base64")
  const plugin = { tcpConnections: 0, fingerprint: createHash("sha256").update(der).digest("hex") }
  const server = https.createServer({ key: pems.private, cert: pems.cert }, (req, res) => {
    if (req.url === "/api") {
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({ ok: true, at: Date.now() }))
    } else if (req.url === "/sse") {
      res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-store" })
      const timer = setInterval(() => res.write(`data: ${JSON.stringify({ t: Date.now() })}\n\n`), 200)
      res.on("close", () => clearInterval(timer))
    } else if (req.url === "/big") {
      res.writeHead(200, { "content-type": "application/octet-stream", "content-length": BIG_BYTES })
      const chunk = Buffer.alloc(DATA_CHUNK_BYTES, 0x5a)
      let sent = 0
      const pump = () => {
        while (sent < BIG_BYTES) {
          sent += chunk.length
          if (!res.write(chunk)) return res.once("drain", pump)
        }
        res.end()
      }
      res.on("close", () => { sent = BIG_BYTES })
      pump()
    } else {
      res.writeHead(404).end()
    }
  })
  server.on("connection", (socket) => {
    plugin.tcpConnections++
    socket.on("error", () => {})
  })
  server.listen(0, "127.0.0.1")
  await new Promise((resolve) => server.once("listening", resolve))
  plugin.port = server.address().port
  cleanups.push(() => {
    server.closeAllConnections?.()
    server.close()
  })
  return plugin
}

/** 给一条 WebSocket 排队收帧；detach 后交给 duplex，避免大下载堆在队列里。 */
function frames(ws) {
  const queue = []
  const waiters = []
  const push = (event) => {
    const waiter = waiters.shift()
    if (waiter) waiter(event)
    else queue.push(event)
  }
  const onMessage = (data, isBinary) => {
    let msg = null
    if (!isBinary) try { msg = JSON.parse(String(data)) } catch {}
    push({ kind: "message", data, isBinary, msg })
  }
  const onClose = (code, reason) => push({ kind: "close", code, reason: String(reason) })
  ws.on("message", onMessage)
  ws.on("close", onClose)
  ws.on("error", () => {})
  return {
    next(timeoutMs = 15_000) {
      if (queue.length) return Promise.resolve(queue.shift())
      return new Promise((resolve, reject) => {
        const waiter = (event) => {
          clearTimeout(timer)
          resolve(event)
        }
        const timer = setTimeout(() => {
          waiters.splice(waiters.indexOf(waiter), 1)
          reject(new Error("等待帧超时"))
        }, timeoutMs)
        waiters.push(waiter)
      })
    },
    async nextClose(timeoutMs = 15_000) {
      for (;;) {
        const event = await this.next(timeoutMs)
        if (event.kind === "close") return event
      }
    },
    detach() {
      ws.off("message", onMessage)
      ws.off("close", onClose)
    },
  }
}

function connect(url, xff) {
  const ws = new WebSocket(url, {
    perMessageDeflate: false,
    maxPayload: MAX_DATA_MESSAGE_BYTES,
    headers: xff ? { "X-Forwarded-For": xff } : undefined,
  })
  cleanups.push(() => ws.terminate())
  return ws
}

async function hello(ws) {
  const f = frames(ws)
  const event = await f.next()
  assert(event.kind === "message" && event.msg?.t === "hello", "没有收到 hello")
  return { f, ch: Buffer.from(event.msg.ch, "base64url") }
}

/** 以手机身份发 client_open；tamper 在 MAC 算完之后改字段（用于逐字段篡改）。 */
async function clientOpen(url, { route, kind, key, macKey, ts = nowSec(), nonce = randomBytes(16), xff, tamper } = {}) {
  const ws = connect(url, xff)
  const { f } = await hello(ws)
  const mac = clientMac(macKey, clientTranscript({ route, kind, key, ts, nonce }))
  let req = { t: "client_open", v: 1, route: b64u(route), kind, key: b64u(key), ts, nonce: b64u(nonce), mac: b64u(mac) }
  if (tamper) req = tamper(req)
  ws.send(JSON.stringify(req))
  const event = await f.next()
  if (event.kind === "message" && event.msg?.t === "ready") {
    f.detach()
    return { ready: true, ws, nonce, ts }
  }
  const error = event.kind === "message" ? event.msg : null
  const closed = event.kind === "close" ? event : await f.nextClose().catch(() => null)
  return { ready: false, ws, code: error?.code ?? closed?.reason ?? null, hostNow: error?.hostNow, closeCode: closed?.code ?? null }
}

async function innerTls(ws, fingerprint) {
  const duplex = createWebSocketStream(ws, { highWaterMark: DATA_CHUNK_BYTES })
  duplex.on("error", () => {})
  const socket = tls.connect({ socket: duplex, rejectUnauthorized: false, servername: "" })
  await new Promise((resolve, reject) => {
    socket.once("secureConnect", resolve)
    socket.once("error", reject)
  })
  const fp = createHash("sha256").update(socket.getPeerCertificate().raw).digest("hex")
  if (fp !== fingerprint) {
    socket.destroy()
    throw new Error("内层证书与插件指纹不符")
  }
  socket.on("error", () => {})
  return socket
}

function httpGet(socket, path, agent) {
  return new Promise((resolve, reject) => {
    const options = { path, method: "GET", headers: { host: "plugin" } }
    if (agent) options.agent = agent
    else options.createConnection = () => socket
    const req = http.request(options, (res) => {
      res.on("error", () => {})
      resolve(res)
    })
    req.on("error", reject)
    req.end()
  })
}

function readBody(res) {
  return new Promise((resolve, reject) => {
    const chunks = []
    res.on("data", (c) => chunks.push(c))
    res.on("end", () => resolve(Buffer.concat(chunks)))
    res.on("error", reject)
  })
}

/** 一条走完整隧道的流：client_open → ready → 内层 TLS（钉扎）。 */
async function tunnel(ctx, device, extra = {}) {
  const opened = await clientOpen(ctx.relay.url, { route: ctx.route, kind: "device", key: device.handle, macKey: device.key, ...extra })
  assert(opened.ready, `client_open 未就绪（${opened.code ?? opened.closeCode}）`)
  const socket = await innerTls(opened.ws, ctx.plugin.fingerprint)
  return { ws: opened.ws, socket }
}

function percentile(values, p) {
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))]
}

function rssKb(pid) {
  const out = spawnSync("ps", ["-o", "rss=", "-p", String(pid)], { encoding: "utf8" })
  return Number(out.stdout.trim())
}

async function registerRaw(url, seed, { ch: forcedCh, xff } = {}) {
  const ws = connect(url, xff)
  const { f, ch } = await hello(ws)
  const pub = hostPublicKey(seed)
  const sig = signHost(seed, registerTranscript(forcedCh ?? ch, pub))
  ws.send(JSON.stringify({ t: "host_register", v: 1, pub: b64u(pub), sig: b64u(sig) }))
  const event = await f.next()
  return { ws, f, event, ch }
}

// ─── 主流程 ───────────────────────────────────────────────────────────────

async function main() {
  const tmp = mkdtempSync(join(tmpdir(), "dlp1-"))
  cleanups.push(() => rmSync(tmp, { recursive: true, force: true }))
  const relayBin = join(tmp, "dlp-relay")
  const build = spawnSync("go", ["build", "-o", relayBin, "./cmd/dlp-relay"], { cwd: RELAY_DIR, encoding: "utf8" })
  if (build.status !== 0) {
    report("FAIL", "编译 Relay", (build.stderr || build.error?.message || "").trim().slice(0, 300))
    return
  }

  // 主 Relay 上的客户端都来自 127.0.0.1：把每 IP 打开速率调高，限流项单独在 relay-limits 上测
  const relay = await startRelay(tmp, relayBin, "relay-main", { DLP_IP_OPEN_PER_MIN: "100000" })
  const plugin = await startPlugin(tmp)
  const agentLogs = []
  const logger = { info: (m) => agentLogs.push(String(m)), warn: (m) => agentLogs.push(String(m)) }
  const keySeed = randomBytes(32)
  const devices = new Map()
  const bootstrap = new BootstrapTable()
  const secrets = [keySeed]
  const makeDevice = (deviceId) => {
    const handle = randomBytes(16)
    const key = deviceRelayKey(keySeed, handle)
    devices.set(b64u(handle), { deviceId })
    secrets.push(handle, key)
    return { deviceId, handle, key }
  }
  const makeAgent = (url, seed) => {
    secrets.push(seed, hostPublicKey(seed), deriveRouteId(hostPublicKey(seed)))
    const agent = new RemoteAgent({
      endpoint: url,
      hostKeySeed: seed,
      keySeed,
      pluginPort: plugin.port,
      lookupDevice: (handle) => devices.get(b64u(handle)) ?? null,
      bootstrap,
      isLocalReady: () => true,
      logger,
      allowInsecureWs: true,
    })
    cleanups.push(() => agent.stop())
    return agent
  }

  const seedA = randomBytes(32)
  const agentA = makeAgent(relay.url, seedA)
  await agentA.start()
  const ctx = { relay, plugin, route: agentA.routeId }
  const d1 = makeDevice("dev-1")
  const openAs = (device, extra) => clientOpen(relay.url, { route: ctx.route, kind: "device", key: device.handle, macKey: device.key, ...extra })

  // ── §13.1 ──
  await check("§13.1 三端向量测试一致（routeId、两种签名、MAC、HKDF、设备 key）", async () => {
    const js = spawnSync(process.execPath, ["--test", "test/remote-crypto.test.mjs"], { cwd: ROOT, encoding: "utf8" })
    assert(js.status === 0, "JS 向量测试失败")
    const go = spawnSync("go", ["test", "./internal/dlp", "-run", "TestDLP1Vectors", "-count=1"], { cwd: RELAY_DIR, encoding: "utf8" })
    assert(go.status === 0, "Go 向量测试失败")
    const androidDir = join(ROOT, "apps", "android")
    if (process.env.DLP1_E2E_SKIP_ANDROID === "1" || !existsSync(join(androidDir, "local.properties"))) {
      return { skip: "JS、Go 已通过；Kotlin 未跑（没有 apps/android/local.properties 或设置了 DLP1_E2E_SKIP_ANDROID）" }
    }
    const kt = spawnSync("./gradlew", [":app:testDebugUnitTest", "--tests", "dev.deeplinks.core.remote.DlpCryptoTest", "-q", "--console=plain"], { cwd: androidDir, encoding: "utf8" })
    assert(kt.status === 0, "Kotlin 向量测试失败")
  })

  await check("§13.1 没有 hostKey 不能注册 route", async () => {
    const pubA = hostPublicKey(seedA)
    const ws = connect(relay.url)
    const { f } = await hello(ws)
    ws.send(JSON.stringify({ t: "host_register", v: 1, pub: b64u(pubA), sig: b64u(randomBytes(64)) }))
    const closed = await f.nextClose()
    assert(closed.code === 4002, `期望 4002，实际 ${closed.code}`)
    assert(agentA.status === "ready", "伪造注册不应顶替真实 Agent")
  })

  await check("§13.1 route A 的合法 host_register 不能接受 route B 的 sid", async () => {
    const seedB = randomBytes(32)
    secrets.push(seedB, hostPublicKey(seedB), deriveRouteId(hostPublicKey(seedB)))
    const ctrlB = await registerRaw(relay.url, seedB)
    assert(ctrlB.event.msg?.t === "registered", "route B 未注册")
    const pendingClient = clientOpen(relay.url, { route: deriveRouteId(hostPublicKey(seedB)), kind: "device", key: randomBytes(16), macKey: randomBytes(32) })
    const open = await ctrlB.f.next()
    assert(open.msg?.t === "open", "route B 未收到 open")
    const data = connect(relay.url)
    const { f, ch } = await hello(data)
    const sid = Buffer.from(open.msg.sid, "base64url")
    const sig = signHost(seedA, acceptTranscript(ch, hostPublicKey(seedA), sid))
    data.send(JSON.stringify({ t: "host_accept", v: 1, pub: b64u(hostPublicKey(seedA)), sid: open.msg.sid, sig: b64u(sig) }))
    const closed = await f.nextClose()
    assert(closed.code === 4000, `期望只关闭该数据连接（4000），实际 ${closed.code}`)
    ctrlB.ws.send(JSON.stringify({ t: "reject", sid: open.msg.sid, code: "UNKNOWN_KEY" }))
    const result = await pendingClient
    assert(!result.ready, "客户端不应被接通")
    ctrlB.ws.close()
  })

  await check("§13.1 用旧 ch 重放 host_register / host_accept 失败", async () => {
    const first = connect(relay.url)
    const { ch: oldCh } = await hello(first)
    first.close()
    const replay = await registerRaw(relay.url, seedA, { ch: oldCh })
    const closed = replay.event.kind === "close" ? replay.event : await replay.f.nextClose()
    assert(closed.code === 4002, `host_register 旧 ch：期望 4002，实际 ${closed.code}`)
    assert(agentA.status === "ready", "旧 ch 重放不应顶替真实 Agent")

    const seedC = randomBytes(32)
    secrets.push(seedC, hostPublicKey(seedC), deriveRouteId(hostPublicKey(seedC)))
    const ctrlC = await registerRaw(relay.url, seedC)
    const pendingClient = clientOpen(relay.url, { route: deriveRouteId(hostPublicKey(seedC)), kind: "device", key: randomBytes(16), macKey: randomBytes(32) })
    const open = await ctrlC.f.next()
    const stale = connect(relay.url)
    const { ch: staleCh } = await hello(stale)
    stale.close()
    const data = connect(relay.url)
    const { f } = await hello(data)
    const sig = signHost(seedC, acceptTranscript(staleCh, hostPublicKey(seedC), Buffer.from(open.msg.sid, "base64url")))
    data.send(JSON.stringify({ t: "host_accept", v: 1, pub: b64u(hostPublicKey(seedC)), sid: open.msg.sid, sig: b64u(sig) }))
    const acceptClosed = await f.nextClose()
    assert(acceptClosed.code === 4002, `host_accept 旧 ch：期望 4002，实际 ${acceptClosed.code}`)
    ctrlC.ws.send(JSON.stringify({ t: "reject", sid: open.msg.sid, code: "UNKNOWN_KEY" }))
    await pendingClient
    ctrlC.ws.close()
  })

  await check("§13.1 没有设备 key 的客户端不能让 Agent dial 本地端口", async () => {
    const before = plugin.tcpConnections
    const result = await clientOpen(relay.url, { route: ctx.route, kind: "device", key: randomBytes(16), macKey: randomBytes(32) })
    assert(!result.ready && result.code === "UNKNOWN_KEY" && result.closeCode === 4007, `期望 UNKNOWN_KEY/4007，实际 ${result.code}/${result.closeCode}`)
    await delay(200)
    assert(plugin.tcpConnections === before, "本地端口收到了连接")
  })

  await check("§13.1 MAC 覆盖 v、route、kind、key、ts、nonce：逐字段篡改均失败；同 nonce 重放 REPLAY；|ts−now|>60 CLOCK_SKEW", async () => {
    const before = plugin.tcpConnections
    const cases = [
      ["v", (r) => ({ ...r, v: 2 }), (o) => o.closeCode === 4001],
      ["route", (r) => ({ ...r, route: b64u(randomBytes(16)) }), (o) => o.closeCode === 4003],
      ["kind", (r) => ({ ...r, kind: "bootstrap" }), (o) => o.code === "BOOTSTRAP_UNKNOWN"],
      ["key", (r) => ({ ...r, key: b64u(randomBytes(16)) }), (o) => o.code === "UNKNOWN_KEY"],
      ["ts", (r) => ({ ...r, ts: r.ts + 1 }), (o) => o.code === "BAD_MAC"],
      ["nonce", (r) => ({ ...r, nonce: b64u(randomBytes(16)) }), (o) => o.code === "BAD_MAC"],
    ]
    for (const [field, tamper, ok] of cases) {
      const result = await openAs(d1, { tamper })
      assert(!result.ready && ok(result), `篡改 ${field} 未按预期失败（${result.code}/${result.closeCode}）`)
    }
    const skew = await openAs(d1, { ts: nowSec() - 61 })
    assert(skew.code === "CLOCK_SKEW" && Math.abs(skew.hostNow - nowSec()) <= 2, `CLOCK_SKEW 未按预期（${skew.code}）`)
    await delay(200)
    assert(plugin.tcpConnections === before, "篡改或时钟偏差的请求让 Agent dial 了本地端口")
    const first = await openAs(d1)
    assert(first.ready, "合法请求未就绪")
    first.ws.close()
    const again = await openAs(d1, { nonce: first.nonce, ts: first.ts })
    assert(!again.ready && again.code === "REPLAY", `同 nonce 重放期望 REPLAY，实际 ${again.code}`)
  })

  await check("§13.1 吊销设备后：新 client_open 返回 UNKNOWN_KEY；已有数据流 1 秒内关闭", async () => {
    const d2 = makeDevice("dev-2")
    const { ws, socket } = await tunnel(ctx, d2)
    const res = await httpGet(socket, "/sse")
    res.on("data", () => {})
    const closed = new Promise((resolve) => ws.once("close", resolve))
    devices.delete(b64u(d2.handle))
    const started = Date.now()
    const count = agentA.dropDevice("dev-2")
    await Promise.race([closed, delay(2_000)])
    const elapsed = Date.now() - started
    assert(count === 1 && ws.readyState === WebSocket.CLOSED && elapsed < 1_000, `流未在 1 秒内关闭（${elapsed} ms，关闭 ${count} 条）`)
    const again = await openAs(d2)
    assert(!again.ready && again.code === "UNKNOWN_KEY", `吊销后期望 UNKNOWN_KEY，实际 ${again.code}`)
    return { note: `关闭用时 ${elapsed} ms` }
  })

  await check("§13.1 bootstrap：过期、已消费、未知三种错误码正确", async () => {
    const open = (keys) => clientOpen(relay.url, { route: ctx.route, kind: "bootstrap", key: keys.bootstrapId, macKey: keys.bootstrapKey })
    const unknown = await open(bootstrapKeys(randomBytes(16), ctx.route))
    assert(unknown.code === "BOOTSTRAP_UNKNOWN", `未知：${unknown.code}`)
    const expired = bootstrap.issue(ctx.route, Date.now() - 1)
    const expiredResult = await open(bootstrapKeys(expired.seed, ctx.route))
    assert(expiredResult.code === "BOOTSTRAP_EXPIRED", `过期：${expiredResult.code}`)
    const used = bootstrap.issue(ctx.route, Date.now() + 300_000)
    const usedKeys = bootstrapKeys(used.seed, ctx.route)
    secrets.push(used.seed, usedKeys.bootstrapKey)
    const ok = await open(usedKeys)
    assert(ok.ready, "有效 bootstrap 未就绪")
    ok.ws.close()
    bootstrap.consume(used.bootstrapId)
    const usedResult = await open(usedKeys)
    assert(usedResult.code === "BOOTSTRAP_USED", `已消费：${usedResult.code}`)
  })
  report("SKIP", "§13.1 bootstrap 来源访问 /dsh-link/pair 以外的路径得到 403；经 bootstrap 完成的配对一定是 pending", "属 M2：需要 src/index.js 按来源标签做路径白名单与配对策略，M1 不改 src/index.js")
  report("SKIP", "§13.1 device 来源的连接携带其他设备的 token 时得到 403", "属 M2：需要 src/index.js 用来源标签绑定 deviceId，M1 不改 src/index.js")

  await check("§13.1 Agent 永不连接 127.0.0.1:<pluginPort> 以外的地址", async () => {
    const source = readFileSync(join(ROOT, "src", "remote", "agent.js"), "utf8")
    const dials = source.match(/net\.(?:createConnection|connect)\([^)]*\)/g) ?? []
    assert(dials.length === 1 && dials[0] === "net.createConnection({ host: \"127.0.0.1\", port: this.#pluginPort })", `本地 dial 点：${JSON.stringify(dials)}`)
    assert(!/tls\.connect\(\{[^}]*host:/.test(source), "存在按参数指定 host 的 tls.connect")
    return { note: "唯一的本地 dial 点写死 127.0.0.1 与构造参数 pluginPort；目标地址不来自任何消息" }
  })

  await check("§13.1 畸形输入只关闭当前连接，Relay 不 panic", async () => {
    const send = async (payload) => {
      const ws = connect(relay.url)
      const { f } = await hello(ws)
      ws.send(payload)
      return (await f.nextClose()).code
    }
    const good = { t: "client_open", v: 1, route: b64u(ctx.route), kind: "device", key: b64u(randomBytes(16)), ts: nowSec(), nonce: b64u(randomBytes(16)), mac: b64u(randomBytes(32)) }
    const codes = {
      nonJson: await send("not json at all"),
      duplicateKey: await send(JSON.stringify(good).replace("{\"t\":\"client_open\",", "{\"t\":\"client_open\",\"t\":\"client_open\",")),
      oversize: await send(JSON.stringify({ ...good, pad: "x".repeat(5_000) })),
      badLength: await send(JSON.stringify({ ...good, key: b64u(randomBytes(15)) })),
    }
    for (const [name, code] of Object.entries(codes)) assert(code === 4000 || (name === "oversize" && code === 1009), `${name}：关闭码 ${code}`)
    const { ws } = await tunnel(ctx, d1)
    const closed = new Promise((resolve) => ws.once("close", resolve))
    ws.send("text frame in data stage")
    const [code] = await Promise.race([closed.then((c) => [c]), delay(3_000).then(() => [null])])
    assert(code === 4000, `数据阶段文本帧：关闭码 ${code}`)
    assert(relay.child.exitCode === null, "Relay 进程退出了")
    const health = await fetch(`${relay.base}/healthz`)
    assert(health.status === 200, "healthz 不可用")
    const after = await tunnel(ctx, d1)
    after.ws.close()
    return { note: `关闭码 ${JSON.stringify({ ...codes, textFrame: code })}` }
  })

  await check("§13.1 资源耗尽：每 IP / 每 route / 全局限额返回正确关闭码，且不影响其他 route", async () => {
    const limits = await startRelay(tmp, relayBin, "relay-limits", {
      DLP_ROUTE_MAX_STREAMS: "16", DLP_IP_MAX_CONNS: "16", DLP_MAX_STREAMS: "20", DLP_IP_OPEN_PER_MIN: "1",
    })
    const agentLA = makeAgent(limits.url, randomBytes(32))
    const agentLB = makeAgent(limits.url, randomBytes(32))
    await Promise.all([agentLA.start(), agentLB.start()])
    const devs = [makeDevice("lim-1"), makeDevice("lim-2"), makeDevice("lim-3"), makeDevice("lim-4")]
    const open = (agent, device, xff, extra) => clientOpen(limits.url, { route: agent.routeId, kind: "device", key: device.handle, macKey: device.key, xff, ...extra })
    const observed = {}

    // 每 IP 并发客户连接（含未定角色的连接）：16 条之后的第 17 条
    const idle = []
    for (let i = 0; i < 16; i++) {
      const ws = connect(limits.url, "10.1.0.1")
      idle.push(ws)
      await hello(ws)
    }
    const extra = connect(limits.url, "10.1.0.1")
    const { f: extraFrames } = await hello(extra)
    observed.ipConns = (await extraFrames.nextClose()).code
    for (const ws of idle) ws.terminate()
    assert(observed.ipConns === 4005, `每 IP 并发连接：${observed.ipConns}`)

    // 每 IP client_open 速率（突发 20）：第 21 次
    for (let i = 0; i < 20; i++) await open(agentLA, devs[0], "10.2.0.1", { macKey: randomBytes(32) })
    observed.openRate = (await open(agentLA, devs[0], "10.2.0.1", { macKey: randomBytes(32) })).closeCode
    assert(observed.openRate === 4004, `每 IP 打开速率：${observed.openRate}`)

    // 每 route 并发流：route A 打满 16 条，第 17 条被拒；route B 照常可用
    const held = []
    for (let i = 0; i < 16; i++) {
      const result = await open(agentLA, devs[Math.floor(i / 6)], `10.3.0.${i + 1}`)
      assert(result.ready, `route A 第 ${i + 1} 条未就绪（${result.code}/${result.closeCode}）`)
      held.push(result.ws)
    }
    observed.route = (await open(agentLA, devs[2], "10.3.1.1")).closeCode
    assert(observed.route === 4005, `每 route 并发：${observed.route}`)
    const other = await open(agentLB, devs[3], "10.4.0.1")
    assert(other.ready, "route A 打满时 route B 应不受影响")
    held.push(other.ws)

    // 全局并发流：上限 20，再开 3 条到 20，第 21 条被拒
    for (let i = 0; i < 3; i++) {
      const result = await open(agentLB, devs[3], `10.4.1.${i + 1}`)
      assert(result.ready, `全局第 ${18 + i} 条未就绪`)
      held.push(result.ws)
    }
    observed.global = (await open(agentLB, devs[3], "10.4.2.1")).closeCode
    assert(observed.global === 4005, `全局并发：${observed.global}`)
    for (const ws of held) ws.close()
    await Promise.all([agentLA.stop(), agentLB.stop()])
    limits.child.kill("SIGTERM")
    return { note: `关闭码 ${JSON.stringify(observed)}` }
  })

  await check("§6.6 自检 selfTest：外层连接 / 会合 / 内层 TLS 各阶段通过", async () => {
    const result = await agentA.selfTest({ certFingerprint: plugin.fingerprint })
    assert(result.ok && result.failedStage === null, `失败阶段 ${result.failedStage}`)
    const wrong = await agentA.selfTest({ certFingerprint: "0".repeat(64) })
    assert(!wrong.ok && wrong.failedStage === "inner", "证书不符时自检应在内层 TLS 阶段失败")
    return { note: `外层 ${result.outerMs} ms / 会合 ${result.rendezvousMs} ms / 内层 TLS ${result.innerTlsMs} ms` }
  })

  report("SKIP", "§13.1 App 在远程路径上拒绝插件证书不符", "App 侧：Android 的隧道内层 TLS 钉扎用例与 PinnedSslTest 覆盖，二者联合用例随 M3 接入 HostHttp 时补")
  report("SKIP", "§13.1 Relay 伪造 UNKNOWN_KEY 时 App 不删除凭据", "App 侧凭据处理属 M3，M1 未接入")

  // ── §13.2 ──
  await check("§13.2 两台手机各 1 SSE + 4 API + 1 个 100 MB 下载；一台慢读 32 KiB/s，另一台 SSE p95 < 500 ms（30 秒）", async () => {
    const phones = [makeDevice("phone-fast"), makeDevice("phone-slow")]
    const latencies = [[], []]
    const downloaded = [0, 0]
    let running = true
    const tasks = []
    const streams = []
    for (const [index, phone] of phones.entries()) {
      const sse = await tunnel(ctx, phone)
      streams.push(sse)
      const sseRes = await httpGet(sse.socket, "/sse")
      let buffer = ""
      sseRes.on("data", (chunk) => {
        buffer += chunk
        let at
        while ((at = buffer.indexOf("\n\n")) >= 0) {
          const line = buffer.slice(0, at)
          buffer = buffer.slice(at + 2)
          const t = JSON.parse(line.replace(/^data: /, "")).t
          if (running) latencies[index].push(Date.now() - t)
        }
      })
      for (let i = 0; i < 4; i++) {
        const api = await tunnel(ctx, phone)
        streams.push(api)
        const agent = new http.Agent({ keepAlive: true, maxSockets: 1 })
        agent.createConnection = () => api.socket
        tasks.push((async () => {
          while (running) await readBody(await httpGet(null, "/api", agent))
        })())
      }
      const big = await tunnel(ctx, phone)
      streams.push(big)
      const bigRes = await httpGet(big.socket, "/big")
      if (index === 1) {
        // 慢读：每秒只读 32 KiB
        bigRes.on("data", (chunk) => {
          downloaded[index] += chunk.length
          bigRes.pause()
          setTimeout(() => bigRes.resume(), (chunk.length / 32_768) * 1000).unref()
        })
      } else {
        bigRes.on("data", (chunk) => { downloaded[index] += chunk.length })
      }
    }
    await delay(30_000)
    running = false
    await Promise.race([Promise.allSettled(tasks), delay(5_000)])
    for (const s of streams) s.ws.close()
    const fast = percentile(latencies[0], 95)
    const slow = percentile(latencies[1], 95)
    assert(latencies[0].length >= 100, `快手机只收到 ${latencies[0].length} 条 SSE`)
    assert(fast < 500, `快手机 SSE p95 = ${fast} ms`)
    assert(downloaded[0] === BIG_BYTES, `快手机 100 MB 下载未完成（${downloaded[0]} 字节）`)
    const mb = (n) => (n / 1024 / 1024).toFixed(1)
    return { note: `快手机 p95 ${fast} ms（${latencies[0].length} 条），慢读手机 p95 ${slow} ms；下载：快 ${mb(downloaded[0])} MB，慢 ${mb(downloaded[1])} MB` }
  })

  await check("§13.2 手机停止读取下载流 60 秒：Relay RSS 增长 < 16 MB", async () => {
    const phone = makeDevice("phone-stall")
    const big = await tunnel(ctx, phone)
    const res = await httpGet(big.socket, "/big")
    let received = 0
    await new Promise((resolve) => {
      const onData = (chunk) => {
        received += chunk.length
        if (received >= 1024 * 1024) {
          res.off("data", onData)
          res.pause()
          resolve()
        }
      }
      res.on("data", onData)
    })
    await delay(2_000)
    const before = rssKb(relay.pid)
    let closedAt = null
    const started = Date.now()
    big.ws.once("close", () => { closedAt = Date.now() - started })
    await delay(60_000)
    const after = rssKb(relay.pid)
    big.ws.close()
    const growthMb = (after - before) / 1024
    assert(growthMb < 16, `RSS 增长 ${growthMb.toFixed(1)} MB`)
    return { note: `RSS ${(before / 1024).toFixed(1)} → ${(after / 1024).toFixed(1)} MB（+${growthMb.toFixed(1)} MB）${closedAt ? `；该流在第 ${Math.round(closedAt / 1000)} 秒按写超时（RFC §5.8，30 秒）被关闭` : ""}` }
  })

  report("SKIP", "§13.2 100 ms RTT 链路上首个请求 < 1.5 s、复用后 < 1 RTT + 服务时间", "未验证：需要 root 注入延迟（tc netem / dnctl），RFC 允许 M1 不验")

  // ── 最后查日志 ──
  await check("§13.1 日志不含秘密（Relay 与 Agent；Relay 也不记录客户端 IP）", async () => {
    const relayLogs = [relay.logPath, join(tmp, "relay-limits.log")].filter(existsSync).map((p) => readFileSync(p, "utf8")).join("\n")
    const text = `${relayLogs}\n${agentLogs.join("\n")}`
    for (const secret of secrets) {
      assert(!text.includes(secret.toString("hex")) && !text.includes(b64u(secret)), "日志里出现了秘密或 route 标识")
    }
    assert(!/10\.[1-4]\.\d+\.\d+/.test(relayLogs), "Relay 日志里出现了客户端 IP")
    return { note: `检查 ${secrets.length} 个秘密值，Relay 日志 ${relayLogs.split("\n").length} 行，Agent 日志 ${agentLogs.length} 行` }
  })
}

try {
  await main()
} catch (error) {
  report("FAIL", "端到端脚本异常", error?.stack ?? String(error))
} finally {
  for (const fn of cleanups.reverse()) {
    try { await fn() } catch {}
  }
}
const failed = results.filter((s) => s === "FAIL").length
console.log(`\n共 ${results.length} 项：PASS ${results.filter((s) => s === "PASS").length}，FAIL ${failed}，SKIP ${results.filter((s) => s === "SKIP").length}`)
process.exit(failed ? 1 : 0)
