/**
 * RemoteAgent（RFC §5.4、§5.5、§6.1、§6.5、§6.7、§11 第 5 条）。
 *
 * 最小假 Relay 在 test/helpers/fake-dlp-relay.mjs；假插件端口（net.createServer）在本文件；
 * 不依赖 Go、网络或真实 state。核心断言：任何拒绝分支都不 dial 本地端口，ready 之前也不 dial。
 */
import assert from "node:assert/strict"
import test from "node:test"
import net from "node:net"
import { once } from "node:events"
import { createHash, randomBytes } from "node:crypto"
import { generate } from "selfsigned"
import { RemoteAgent } from "../src/remote/agent.js"
import { BootstrapTable, NonceCache } from "../src/remote/bootstrap.js"
import {
  b64u, bootstrapKeys, clientMac, clientTranscript, deviceRelayKey, hostPublicKey, routeId as deriveRouteId,
} from "../src/remote/crypto.js"
import { CLOSE, HOST_MAX_STREAMS, REJECT } from "../src/remote/wire.js"
import { startFakeRelay } from "./helpers/fake-dlp-relay.mjs"

const HOST_KEY_SEED = Buffer.from(Array.from({ length: 32 }, (_, i) => 0x40 + i))
const KEY_SEED = Buffer.from(Array.from({ length: 32 }, (_, i) => 0x80 + i))
const HOST_PUB = hostPublicKey(HOST_KEY_SEED)
const ROUTE = deriveRouteId(HOST_PUB)

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function until(fn, timeoutMs = 3_000) {
  const started = Date.now()
  while (!(await fn())) {
    if (Date.now() - started > timeoutMs) throw new Error("condition not met in time")
    await delay(10)
  }
}

/** 假插件端口：回显；记录每条连接与它第一次收到数据时查到的来源标签。 */
async function startFakePlugin(getAgent) {
  const plugin = { connections: 0, sockets: [], firstDataTags: [] }
  const server = net.createServer((socket) => {
    plugin.connections++
    plugin.sockets.push(socket)
    socket.on("error", () => {})
    socket.once("data", () => plugin.firstDataTags.push(getAgent().originOf(socket.remotePort)))
    socket.pipe(socket)
  })
  server.listen(0, "127.0.0.1")
  await once(server, "listening")
  plugin.port = server.address().port
  plugin.close = () => {
    for (const socket of plugin.sockets) socket.destroy()
    return new Promise((resolve) => server.close(resolve))
  }
  return plugin
}

async function setup(t, { devices = new Map(), localReady = true, now = () => Date.now(), agentOptions = {}, relayOptions = {} } = {}) {
  const relay = await startFakeRelay(relayOptions)
  const logs = []
  const logger = { info: (m) => logs.push(String(m)), warn: (m) => logs.push(String(m)) }
  const bootstrap = new BootstrapTable({ now })
  let agent
  const plugin = await startFakePlugin(() => agent)
  const state = { localReady }
  agent = new RemoteAgent({
    endpoint: relay.url,
    hostKeySeed: HOST_KEY_SEED,
    keySeed: KEY_SEED,
    pluginPort: plugin.port,
    lookupDevice: (handle) => devices.get(b64u(handle)) ?? null,
    bootstrap,
    isLocalReady: () => state.localReady,
    logger,
    allowInsecureWs: true,
    now,
    ...agentOptions,
  })
  t.after(async () => {
    await agent.stop()
    await relay.close()
    await plugin.close()
  })
  return { relay, plugin, agent, bootstrap, logs, state, devices }
}

function deviceOpen({ handle, ts = Math.floor(Date.now() / 1000), nonce = randomBytes(16), route = ROUTE, macKey } = {}) {
  const key = macKey ?? deviceRelayKey(KEY_SEED, handle)
  const mac = clientMac(key, clientTranscript({ route, kind: "device", key: handle, ts, nonce }))
  return { v: 1, route: b64u(route), kind: "device", key: b64u(handle), ts, nonce: b64u(nonce), mac: b64u(mac) }
}

function bootstrapOpen({ bootstrapId, bootstrapKey, ts = Math.floor(Date.now() / 1000), nonce = randomBytes(16) }) {
  const mac = clientMac(bootstrapKey, clientTranscript({ route: ROUTE, kind: "bootstrap", key: bootstrapId, ts, nonce }))
  return { v: 1, route: b64u(ROUTE), kind: "bootstrap", key: b64u(bootstrapId), ts, nonce: b64u(nonce), mac: b64u(mac) }
}

async function expectReject(relay, req) {
  const sid = relay.sendOpen(req)
  const reject = await relay.nextCtrl("reject")
  assert.equal(reject.sid, sid)
  return reject
}

/** 打开一条流并完成 accept → ready，返回 Relay 侧的数据连接。 */
async function openStream(relay, req) {
  const accepted = relay.nextAccept()
  relay.sendOpen(req)
  const { ws, sigOk } = await accepted
  assert.equal(sigOk, true)
  ws.send(JSON.stringify({ t: "ready" }))
  return ws
}

test("§5.5 每个拒绝分支：回对应 reject 码，且假插件端口没有收到任何连接", async (t) => {
  const handle = randomBytes(16)
  const devices = new Map([[b64u(handle), { deviceId: "dev-a" }]])
  const env = await setup(t, { devices })
  await env.agent.start()
  const { relay, plugin, bootstrap } = env
  const nowSec = Math.floor(Date.now() / 1000)

  // 1. 格式（v 不对）
  assert.equal((await expectReject(relay, { ...deviceOpen({ handle }), v: 2 })).code, REJECT.BAD_MAC)
  // 1. 格式（b64u 长度不对）
  assert.equal((await expectReject(relay, { ...deviceOpen({ handle }), nonce: b64u(randomBytes(15)) })).code, REJECT.BAD_MAC)
  // 2. route 不是本机
  assert.equal((await expectReject(relay, deviceOpen({ handle, route: randomBytes(16) }))).code, REJECT.UNKNOWN_KEY)
  // 3. 时间偏差，附 hostNow
  const skew = await expectReject(relay, deviceOpen({ handle, ts: nowSec - 61 }))
  assert.equal(skew.code, REJECT.CLOCK_SKEW)
  assert.ok(Math.abs(skew.hostNow - nowSec) <= 2)
  // 4. device 未知
  assert.equal((await expectReject(relay, deviceOpen({ handle: randomBytes(16) }))).code, REJECT.UNKNOWN_KEY)
  // 4. bootstrap 未知 / 过期 / 已消费
  const unknown = bootstrapKeys(randomBytes(16), ROUTE)
  assert.equal((await expectReject(relay, bootstrapOpen(unknown))).code, REJECT.BOOTSTRAP_UNKNOWN)
  const expiredSeed = bootstrap.issue(ROUTE, Date.now() - 1)
  assert.equal((await expectReject(relay, bootstrapOpen(bootstrapKeys(expiredSeed.seed, ROUTE)))).code, REJECT.BOOTSTRAP_EXPIRED)
  const usedSeed = bootstrap.issue(ROUTE, Date.now() + 300_000)
  bootstrap.consume(usedSeed.bootstrapId)
  assert.equal((await expectReject(relay, bootstrapOpen(bootstrapKeys(usedSeed.seed, ROUTE)))).code, REJECT.BOOTSTRAP_USED)
  // 5. MAC 不符（device 与 bootstrap 各一）
  assert.equal((await expectReject(relay, deviceOpen({ handle, macKey: randomBytes(32) }))).code, REJECT.BAD_MAC)
  const liveSeed = bootstrap.issue(ROUTE, Date.now() + 300_000)
  const live = bootstrapKeys(liveSeed.seed, ROUTE)
  assert.equal((await expectReject(relay, bootstrapOpen({ ...live, bootstrapKey: randomBytes(32) }))).code, REJECT.BAD_MAC)
  // 8. 本地服务未就绪；同时为第 6 步准备一个已记录的 nonce
  env.state.localReady = false
  const nonce = randomBytes(16)
  const ts = Math.floor(Date.now() / 1000)
  assert.equal((await expectReject(relay, deviceOpen({ handle, nonce, ts }))).code, REJECT.LOCAL_UNAVAILABLE)
  // 6. 同一 nonce 重放
  env.state.localReady = true
  assert.equal((await expectReject(relay, deviceOpen({ handle, nonce, ts }))).code, REJECT.REPLAY)

  await delay(100)
  assert.equal(plugin.connections, 0, "任何拒绝分支都不得 dial 本地端口")
})

test("§5.5 第 6 步：重放缓存满时回 SERVER_BUSY，且不 dial", async (t) => {
  const handle = randomBytes(16)
  const full = new NonceCache({ max: 1 })
  full.check("device", Buffer.alloc(16), Buffer.alloc(16), Math.floor(Date.now() / 1000) + 30, Math.floor(Date.now() / 1000))
  const env = await setup(t, { devices: new Map([[b64u(handle), { deviceId: "dev-a" }]]), agentOptions: { nonceCache: full } })
  await env.agent.start()
  assert.equal((await expectReject(env.relay, deviceOpen({ handle }))).code, REJECT.SERVER_BUSY)
  await delay(50)
  assert.equal(env.plugin.connections, 0)
})

test("§5.5 第 7 步：每设备 12 条、每个 bootstrapId 4 条、本机 32 条上限；超限的那一次不 dial", async (t) => {
  const handles = Array.from({ length: 12 }, () => randomBytes(16))
  const devices = new Map(handles.map((h, i) => [b64u(h), { deviceId: `dev-${i}` }]))
  const env = await setup(t, { devices })
  await env.agent.start()
  const { relay, plugin, bootstrap } = env

  for (let i = 0; i < 12; i++) await openStream(relay, deviceOpen({ handle: handles[0] }))
  await until(() => plugin.connections === 12)
  assert.equal((await expectReject(relay, deviceOpen({ handle: handles[0] }))).code, REJECT.DEVICE_LIMIT)

  const seed = bootstrap.issue(ROUTE, Date.now() + 300_000)
  const keys = bootstrapKeys(seed.seed, ROUTE)
  for (let i = 0; i < 4; i++) await openStream(relay, bootstrapOpen(keys))
  await until(() => plugin.connections === 10)
  assert.equal((await expectReject(relay, bootstrapOpen(keys))).code, REJECT.DEVICE_LIMIT)

  // 再补到 32 条（其余 5 台设备各 ≤ 6 条）
  let opened = 10
  for (let d = 1; opened < HOST_MAX_STREAMS; d = d % 5 + 1) {
    await openStream(relay, deviceOpen({ handle: handles[d] }))
    opened++
  }
  await until(() => plugin.connections === HOST_MAX_STREAMS)
  const spare = randomBytes(16)
  devices.set(b64u(spare), { deviceId: "dev-spare" })
  assert.equal((await expectReject(relay, deviceOpen({ handle: spare }))).code, REJECT.SERVER_BUSY)
  await delay(100)
  assert.equal(plugin.connections, HOST_MAX_STREAMS, "超限的 open 不得 dial")
})

test("合法 open：host_accept 签名可验证 → ready 之前不 dial → ready 后才连 → originOf 给出标签 → 关闭后标签删除", async (t) => {
  const handle = randomBytes(16)
  const env = await setup(t, { devices: new Map([[b64u(handle), { deviceId: "dev-a" }]]) })
  const events = []
  env.agent.on("stream-open", (e) => events.push(["open", e]))
  env.agent.on("stream-close", (e) => events.push(["close", e]))
  await env.agent.start()
  assert.equal(env.agent.status, "ready")
  assert.ok(env.agent.routeId.equals(ROUTE))

  const accepted = env.relay.nextAccept()
  const sid = env.relay.sendOpen(deviceOpen({ handle }))
  const { ws, msg, sigOk } = await accepted
  assert.equal(sigOk, true, "host_accept 签名必须能用 hostPub 验证")
  assert.equal(msg.sid, sid)
  assert.equal(msg.pub, b64u(HOST_PUB))
  await delay(150)
  assert.equal(env.plugin.connections, 0, "收到 ready 之前不得连接本地端口")

  ws.send(JSON.stringify({ t: "ready" }))
  await until(() => env.plugin.connections === 1)
  const echoed = once(ws, "message")
  ws.send(Buffer.from("hello through the pipe"))
  const [data, isBinary] = await echoed
  assert.equal(isBinary, true)
  assert.equal(String(data), "hello through the pipe")
  assert.deepEqual(env.plugin.firstDataTags[0], { kind: "device", deviceId: "dev-a", sid })
  const localPort = env.plugin.sockets[0].remotePort
  assert.deepEqual(env.agent.originOf(localPort), { kind: "device", deviceId: "dev-a", sid })

  ws.close(1000)
  await until(() => env.agent.originOf(localPort) === undefined)
  await until(() => env.plugin.sockets[0].destroyed)
  assert.deepEqual(events.map(([k]) => k), ["open", "close"])
  assert.deepEqual(events[0][1], { kind: "device", deviceId: "dev-a" })
})

test("数据阶段出现文本帧：Agent 关闭该流", async (t) => {
  const handle = randomBytes(16)
  const env = await setup(t, { devices: new Map([[b64u(handle), { deviceId: "dev-a" }]]) })
  await env.agent.start()
  const ws = await openStream(env.relay, deviceOpen({ handle }))
  await until(() => env.plugin.connections === 1)
  const closed = once(ws, "close")
  ws.send("not binary")
  const [code] = await closed
  assert.equal(code, CLOSE.PROTOCOL_ERROR)
})

test("dropDevice 关闭该设备全部流并返回数量；其他设备不受影响", async (t) => {
  const a = randomBytes(16)
  const b = randomBytes(16)
  const env = await setup(t, { devices: new Map([[b64u(a), { deviceId: "dev-a" }], [b64u(b), { deviceId: "dev-b" }]]) })
  await env.agent.start()
  const a1 = await openStream(env.relay, deviceOpen({ handle: a }))
  const a2 = await openStream(env.relay, deviceOpen({ handle: a }))
  const b1 = await openStream(env.relay, deviceOpen({ handle: b }))
  await until(() => env.plugin.connections === 3)
  const closedA = Promise.all([once(a1, "close"), once(a2, "close")])
  assert.equal(env.agent.dropDevice("dev-a"), 2)
  await closedA
  assert.equal(b1.readyState, b1.OPEN)
  assert.equal(env.agent.dropDevice("dev-a"), 0)
})

test("收到 4010 REPLACED：发 replaced 事件，退避不少于 60 秒（注入定时器，不真等）", async (t) => {
  const delays = []
  const timers = {
    setTimeout: (fn, ms) => {
      delays.push(ms)
      return { fake: true }
    },
    clearTimeout: () => {},
    setInterval,
    clearInterval,
  }
  const env = await setup(t, { agentOptions: { timers, random: () => 1 } })
  await env.agent.start()
  const replaced = once(env.agent, "replaced")
  env.relay.ctrl.close(CLOSE.REPLACED)
  await replaced
  await until(() => delays.length > 0)
  assert.ok(delays.at(-1) >= 60_000, `4010 后的退避 ${delays.at(-1)}ms 应不少于 60 秒`)
  assert.equal(env.agent.status, "connecting")
})

test("普通断线按 1 秒起的指数退避（±30% 抖动）；4002 AUTH_FAILED 停止重连并报错", async (t) => {
  const delays = []
  const timers = {
    setTimeout: (fn, ms) => {
      delays.push({ fn, ms })
      return { fake: true }
    },
    clearTimeout: () => {},
    setInterval,
    clearInterval,
  }
  const env = await setup(t, { agentOptions: { timers, random: () => 0.5 } })
  await env.agent.start()
  env.relay.ctrl.close(1011)
  await until(() => delays.length === 1)
  assert.equal(delays[0].ms, 1_000)
  // 触发重连 → 这次 Relay 拒绝注册
  env.relay.once("registered", (ws) => ws.close(CLOSE.AUTH_FAILED))
  delays[0].fn()
  await until(() => env.agent.status === "error")
  assert.equal(delays.length, 1, "AUTH_FAILED 之后不再安排重连")
})

test("outerPin：指纹不符时不注册，相符时正常注册", async (t) => {
  const pems = await generate([{ name: "commonName", value: "relay.test" }], { days: 1, keySize: 2048 })
  const der = Buffer.from(pems.cert.replace(/-----[^-]+-----/g, "").replace(/\s+/g, ""), "base64")
  const pin = createHash("sha256").update(der).digest("hex")

  const wrong = await setup(t, { relayOptions: { tlsOptions: { key: pems.private, cert: pems.cert } }, agentOptions: { outerPin: "0".repeat(64) } })
  wrong.agent.start()
  await delay(500)
  assert.equal(wrong.relay.registrations, 0, "外层证书指纹不符不得完成注册")
  assert.notEqual(wrong.agent.status, "ready")

  const right = await setup(t, { relayOptions: { tlsOptions: { key: pems.private, cert: pems.cert } }, agentOptions: { outerPin: pin } })
  await right.agent.start()
  assert.equal(right.relay.registrations, 1)
})

test("生产配置只接受 wss://（ws:// 需显式 allowInsecureWs）", () => {
  assert.throws(() => new RemoteAgent({
    endpoint: "ws://127.0.0.1:1/ws",
    hostKeySeed: HOST_KEY_SEED,
    keySeed: KEY_SEED,
    pluginPort: 1,
    lookupDevice: () => null,
    bootstrap: new BootstrapTable(),
    isLocalReady: () => true,
  }), /wss/)
})

test("日志不含秘密：用固定秘密跑完注册、拒绝、接受、数据与关闭全流程", async (t) => {
  const handle = Buffer.from(Array.from({ length: 16 }, (_, i) => 0xc0 + i))
  const env = await setup(t, { devices: new Map([[b64u(handle), { deviceId: "dev-a" }]]) })
  await env.agent.start()
  const seed = env.bootstrap.issue(ROUTE, Date.now() + 300_000)
  const boot = bootstrapKeys(seed.seed, ROUTE)
  const nonce = Buffer.from(Array.from({ length: 16 }, (_, i) => 0xe0 + i))
  const req = deviceOpen({ handle, nonce })
  await expectReject(env.relay, deviceOpen({ handle, macKey: randomBytes(32) }))
  await expectReject(env.relay, bootstrapOpen({ ...boot, bootstrapKey: randomBytes(32) }))
  const ws = await openStream(env.relay, req)
  await until(() => env.plugin.connections === 1)
  ws.send(Buffer.from("payload-secret-bytes"))
  await once(ws, "message")
  await expectReject(env.relay, req)
  ws.close()
  env.relay.ctrl.close(1011)
  await until(() => env.logs.length >= 4)

  const secrets = [
    HOST_KEY_SEED, KEY_SEED, HOST_PUB, ROUTE, handle, deviceRelayKey(KEY_SEED, handle),
    seed.seed, boot.bootstrapId, boot.bootstrapKey, nonce, Buffer.from(req.mac, "base64url"),
  ]
  const text = env.logs.join("\n")
  for (const secret of secrets) {
    assert.ok(!text.includes(secret.toString("hex")), "日志里出现了秘密（hex）")
    assert.ok(!text.includes(b64u(secret)), "日志里出现了秘密（b64u）")
  }
  assert.ok(!text.includes("payload-secret-bytes"), "日志里出现了数据内容")
})
