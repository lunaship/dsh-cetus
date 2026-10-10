/**
 * iOS UI 测试用的真插件宿主（隔离 stateDir）。
 *
 * 起真实 apply()（src/index.js）+ 合同 fixtures 的假 DSH gateway。手机端口由 OS 分配，
 * 状态目录是 os.tmpdir() 下的 mkdtemp，不碰用户 ~/.dsh。配对 autoApprove，并在就绪后
 * 经面板 pair-settings 关掉 requireConfirm。
 *
 * 插件自己的 HTTPS 代理按产品契约监听 0.0.0.0（src/index.js 写死）。控制面是另一台
 * 只听 127.0.0.1 的 HTTP 服务；非回环来源一律 403。不打印设备 token。
 *
 * 用法：
 *   node scripts/ios-e2e-host.mjs --qr /tmp/dsh-e2e-qr.json [--log /tmp/dsh-e2e-host.log]
 *
 * 就绪后 stdout 两行：
 *   e2e-host ready port=<n> qr=<path>
 *   control=http://127.0.0.1:<port>
 */
import { createServer as createHttpServer } from "node:http"
import { randomBytes } from "node:crypto"
import {
  appendFileSync, chmodSync, mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync,
} from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import { pathToFileURL } from "node:url"
import { apply, qrPayload } from "../src/index.js"
import {
  callPanelRoute,
  createFakeGateway,
  createWorkspaceTree,
  freePort,
  httpsAgentFor,
  makeCtx,
  proxyRequest,
  startUpstream,
} from "./ios-e2e-fixture.mjs"

const HOOK_WAIT_MS = 20_000
const BODY_LIMIT = 64 * 1024

function parseArgs(argv) {
  const out = { qr: "", log: "", performance: false }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === "--qr") out.qr = argv[++i] ?? ""
    else if (arg.startsWith("--qr=")) out.qr = arg.slice("--qr=".length)
    else if (arg === "--log") out.log = argv[++i] ?? ""
    else if (arg.startsWith("--log=")) out.log = arg.slice("--log=".length)
    else if (arg === "--performance-session") out.performance = true
    else throw new Error(`未知参数 ${arg}`)
  }
  if (!out.qr) throw new Error("缺少 --qr <path>")
  return out
}

function isLoopbackAddress(addr) {
  if (!addr) return false
  const value = String(addr).replace(/^::ffff:/i, "").toLowerCase()
  return value === "127.0.0.1" || value === "::1" || value === "localhost"
}

function readJsonBody(req, limit = BODY_LIMIT) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    let done = false
    const fail = (err) => {
      if (done) return
      done = true
      reject(err)
    }
    req.on("data", (chunk) => {
      if (done) return
      size += chunk.length
      if (size > limit) {
        fail(Object.assign(new Error("payload too large"), { status: 413 }))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on("end", () => {
      if (done) return
      done = true
      if (size === 0) {
        resolve({})
        return
      }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")))
      } catch {
        reject(Object.assign(new Error("invalid json"), { status: 400 }))
      }
    })
    req.on("error", () => fail(Object.assign(new Error("invalid json"), { status: 400 })))
  })
}

function sendJson(res, status, body) {
  const payload = Buffer.from(JSON.stringify(body))
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": String(payload.length),
    "cache-control": "no-store",
  })
  res.end(payload)
}

function closeServer(server) {
  if (!server) return Promise.resolve()
  return new Promise((resolve) => {
    try { server.closeAllConnections?.() } catch {}
    server.close(() => resolve())
  })
}

/** 可中止的假 signal：超时或 shutdown 时 abort，避免审批钩子一直挂到插件的 5 分钟。 */
function createAbortSignal() {
  const listeners = new Set()
  const signal = {
    aborted: false,
    reason: undefined,
    addEventListener(type, fn) {
      if (type === "abort" && typeof fn === "function") listeners.add(fn)
    },
    removeEventListener(type, fn) {
      if (type === "abort") listeners.delete(fn)
    },
  }
  return {
    signal,
    abort(reason = "aborted") {
      if (signal.aborted) return
      signal.aborted = true
      signal.reason = reason
      for (const fn of [...listeners]) {
        try { fn() } catch {}
      }
    },
  }
}

function unknownSession(err) {
  if (err?.code !== "unknown-session") return null
  return { status: 404, body: { ok: false, error: String(err.message), code: "unknown-session" } }
}

/**
 * 调 approval/request 或 user-questions/request。
 * 没有手机 SSE 订阅时钩子会立刻 next()，这里把 passthrough 当成明确错误返回，不空等。
 * 有订阅则等到手机提交，或 HOOK_WAIT_MS 后 abort signal。
 */
function fireHook(listeners, name, req, { timeoutMs = HOOK_WAIT_MS, passthrough }) {
  const listener = listeners.get(name)
  if (typeof listener !== "function") {
    return Promise.resolve({
      status: 500,
      body: { ok: false, error: `插件未注册 ${name}`, code: "hook-missing" },
    })
  }
  const abort = createAbortSignal()
  req.signal = abort.signal
  let timer = null
  let settled = false
  return new Promise((resolve) => {
    const finish = (status, body) => {
      if (settled) return
      settled = true
      if (timer) clearTimeout(timer)
      resolve({ status, body })
    }
    timer = setTimeout(() => {
      abort.abort("timeout")
      finish(504, {
        ok: false,
        error: "手机未在时限内提交",
        code: "timeout",
        timeoutMs,
      })
    }, timeoutMs)
    timer.unref?.()
    Promise.resolve()
      .then(() => listener(req, async () => passthrough))
      .then((value) => {
        if (settled) return
        if (name === "user-questions/request") {
          if (value == null) {
            finish(409, {
              ok: false,
              error: "该会话没有支持多题的手机 SSE 订阅，钩子已交回桌面（未挂起）",
              code: "no-subscriber",
            })
            return
          }
          finish(200, { ok: true, answers: value })
          return
        }
        if (value === passthrough) {
          finish(409, {
            ok: false,
            error: "该会话没有手机 SSE 订阅，钩子已交回桌面（未挂起）",
            code: "no-subscriber",
          })
          return
        }
        finish(200, { ok: true, outcome: value })
      })
      .catch((err) => finish(500, { ok: false, error: String(err?.message ?? err), code: "hook-failed" }))
  })
}

function writeQr(qrPath, payload) {
  mkdirSync(dirname(qrPath), { recursive: true })
  writeFileSync(qrPath, `${JSON.stringify(payload, null, 2)}\n`, { mode: 0o600 })
  try { chmodSync(qrPath, 0o600) } catch {}
}

function questionBody() {
  return [
    {
      id: "deploy-target",
      header: "部署",
      question: "要把调整后的配置写到哪个环境？",
      options: [{ id: "staging", label: "测试环境" }, { id: "production", label: "生产环境" }],
    },
    {
      id: "notify",
      header: "通知",
      question: "完成后要通知谁？",
      options: [{ id: "me", label: "只通知我" }, { id: "team", label: "通知整个小组" }],
    },
  ]
}

/**
 * @param {{ qrPath: string, logPath?: string }} options
 */
export async function startE2eHost({ qrPath, logPath = "", includePerformanceSession = false } = {}) {
  if (!qrPath) throw new Error("缺少 qrPath")
  const rootDir = realpathSync(mkdtempSync(join(tmpdir(), "dsh-ios-e2e-")))
  const stateDir = join(rootDir, "state")
  const sessionLogHome = join(rootDir, "dsh-home")
  mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  try { chmodSync(stateDir, 0o700) } catch {}
  mkdirSync(sessionLogHome, { recursive: true, mode: 0o700 })
  // 插件用 DSH_HOME（缺省 ~/.dsh）找会话 JSONL，并用 size/mtime 决定要不要再拉 session.history。
  // 只改本进程：宿主是独立 node 进程，不会碰到用户正在跑的 DSH。
  process.env.DSH_HOME = sessionLogHome
  const { workspaceDir } = createWorkspaceTree(rootDir)
  const workspacePath = realpathSync(workspaceDir)

  const emitLog = (level, args) => {
    const line = `[${level}] ${args.map((item) => String(item ?? "")).join(" ")}\n`
    if (logPath) {
      try {
        mkdirSync(dirname(logPath), { recursive: true })
        appendFileSync(logPath, line)
      } catch {}
      return
    }
    process.stderr.write(line)
  }
  const logger = {
    info(...args) { emitLog("info", args) },
    warn(...args) { emitLog("warn", args) },
  }

  const upstream = await startUpstream()
  const upstreamPort = upstream.address().port
  const gateway = createFakeGateway({ workspacePath, sessionLogHome, includePerformanceSession })
  const proxyPort = await freePort()

  let control = null
  let generation = null
  let closed = false
  let booting = null

  async function disposeGeneration(gen) {
    if (!gen) return
    for (const fn of gen.effects) {
      try { fn() } catch {}
    }
    await new Promise((resolve) => setTimeout(resolve, 80))
  }

  async function boot() {
    // 插件把「日志文件不存在」缓存 10 秒。重启会清空该缓存，必须在 listen 前把 JSONL 重写回去。
    gateway.seedSessionLogs?.()
    const { ctx, registered, effects, listeners } = makeCtx({ upstreamPort, gateway, logger })
    await apply(ctx, {
      port: proxyPort,
      pairingTtlSeconds: 600,
      autoApprove: true,
      stateDir,
      eventPollIntervalMs: 200,
    })
    const route = (path) => registered.find((item) => item.path === path)
    const settings = await callPanelRoute(route("/dsh-link/pair-settings"), { body: { requireConfirm: false } })
    if (settings.status !== 200 || settings.json?.requireConfirm !== false) {
      throw new Error(`pair-settings 未关闭 requireConfirm：${settings.status} ${settings.raw}`)
    }
    const info = (await callPanelRoute(route("/dsh-link/pair-info"))).json
    if (!info?.pairingCode || !info?.certFingerprint) throw new Error("pair-info 未就绪")
    const payload = qrPayload(info)
    payload.urls = [`https://127.0.0.1:${proxyPort}`]
    writeQr(qrPath, payload)
    return { ctx, registered, effects, listeners, route, payload }
  }

  generation = await boot()

  async function handleControl(pathname, body) {
    if (closed) return { status: 503, body: { ok: false, error: "host stopped" } }
    if (booting) {
      try { await booting } catch {}
    }
    if (!generation) return { status: 503, body: { ok: false, error: "plugin not ready" } }
    switch (pathname) {
      case "/control/approve-pairing": {
        const settings = await callPanelRoute(generation.route("/dsh-link/pair-settings"), {
          body: { requireConfirm: false },
        })
        return {
          status: settings.status === 200 ? 200 : 502,
          body: {
            ok: settings.json?.requireConfirm === false,
            requireConfirm: settings.json?.requireConfirm ?? null,
          },
        }
      }
      case "/control/approval": {
        const sessionId = String(body?.sessionId ?? "").trim()
        if (!sessionId) return { status: 400, body: { ok: false, error: "缺少 sessionId" } }
        return fireHook(generation.listeners, "approval/request", {
          id: `appr-e2e-${randomBytes(4).toString("hex")}`,
          callId: `call-e2e-${randomBytes(4).toString("hex")}`,
          toolName: "write",
          agent: { session: { id: sessionId, events: [] } },
        }, { passthrough: "rejected" })
      }
      case "/control/question": {
        const sessionId = String(body?.sessionId ?? "").trim()
        if (!sessionId) return { status: 400, body: { ok: false, error: "缺少 sessionId" } }
        return fireHook(generation.listeners, "user-questions/request", {
          agent: { session: { id: sessionId } },
          questions: questionBody(),
        }, { passthrough: null })
      }
      case "/control/stream": {
        const sessionId = String(body?.sessionId ?? "").trim()
        const text = String(body?.text ?? "")
        if (!sessionId) return { status: 400, body: { ok: false, error: "缺少 sessionId" } }
        if (!text) return { status: 400, body: { ok: false, error: "缺少 text" } }
        try {
          // text-delta 只把手机活动标成「正在写」；紧跟的 text block-end 才是会话里可见的正文。
          const delta = gateway.appendLiveEvent(sessionId, {
            type: "assistant/chunk",
            data: { chunk: { type: "text-delta", text } },
          })
          const done = gateway.appendLiveEvent(sessionId, {
            type: "assistant/chunk",
            data: { chunk: { type: "block-end", block: { type: "text", text } } },
          })
          return { status: 200, body: { ok: true, seq: done.seq, type: done.type, deltaSeq: delta.seq } }
        } catch (err) {
          return unknownSession(err) ?? { status: 500, body: { ok: false, error: String(err?.message ?? err) } }
        }
      }
      case "/control/prompt-seen":
        return { status: 200, body: { ok: true, prompts: gateway.promptsSeen() } }
      case "/control/turn-end": {
        const sessionId = String(body?.sessionId ?? "").trim()
        const kind = String(body?.kind ?? "interrupted")
        if (!sessionId) return { status: 400, body: { ok: false, error: "缺少 sessionId" } }
        try {
          const done = gateway.appendLiveEvent(sessionId, {
            type: "turn/end",
            data: { reason: { kind } },
          })
          return { status: 200, body: { ok: true, seq: done.seq } }
        } catch (err) {
          return unknownSession(err) ?? { status: 500, body: { ok: false, error: String(err?.message ?? err) } }
        }
      }
      case "/control/restart": {
        const previous = generation
        generation = null
        booting = (async () => {
          await disposeGeneration(previous)
          generation = await boot()
        })()
        try {
          await booting
        } catch (err) {
          return { status: 500, body: { ok: false, error: String(err?.message ?? err) } }
        } finally {
          booting = null
        }
        const agent = httpsAgentFor(stateDir)
        const health = await proxyRequest({ agent, proxyPort, path: "/dsh-link/health" })
        if (health.status !== 200) {
          return { status: 500, body: { ok: false, error: `restart 后 health=${health.status}` } }
        }
        return { status: 200, body: { ok: true } }
      }
      case "/control/revoke": {
        const list = await callPanelRoute(generation.route("/dsh-link/devices"))
        const items = list.json?.devices ?? []
        const targetId = String(body?.deviceId ?? "").trim() || items.find((item) => item.status === "active")?.deviceId || items[0]?.deviceId
        if (!targetId) return { status: 404, body: { ok: false, error: "没有可吊销的设备" } }
        const revoked = await callPanelRoute(generation.route("/dsh-link/revoke"), { body: { deviceId: targetId } })
        return {
          status: revoked.status,
          body: revoked.json ?? { ok: false, error: "revoke failed" },
        }
      }
      case "/control/shutdown": {
        queueMicrotask(() => { shutdown().catch(() => {}) })
        return { status: 200, body: { ok: true } }
      }
      default:
        return { status: 404, body: { ok: false, error: "not found" } }
    }
  }

  control = createHttpServer(async (req, res) => {
    try {
      const remote = req.socket?.remoteAddress
      if (!isLoopbackAddress(remote)) {
        sendJson(res, 403, { ok: false, error: "forbidden" })
        return
      }
      const url = new URL(req.url ?? "/", "http://127.0.0.1")
      if (req.method !== "POST") {
        sendJson(res, 405, { ok: false, error: "method not allowed" })
        return
      }
      let body = {}
      try {
        body = await readJsonBody(req)
      } catch (err) {
        sendJson(res, err.status ?? 400, { ok: false, error: err.message || "invalid body" })
        return
      }
      const result = await handleControl(url.pathname, body)
      sendJson(res, result.status, result.body)
    } catch (err) {
      if (!res.headersSent) sendJson(res, 500, { ok: false, error: String(err?.message ?? err) })
      else res.destroy()
    }
  })
  const controlPort = await new Promise((resolve, reject) => {
    control.once("error", reject)
    control.listen(0, "127.0.0.1", () => {
      control.removeListener("error", reject)
      resolve(control.address().port)
    })
  })

  async function shutdown() {
    if (closed) return
    closed = true
    const previous = generation
    generation = null
    await closeServer(control)
    await disposeGeneration(previous)
    try { upstream.close() } catch {}
    try { rmSync(rootDir, { recursive: true, force: true }) } catch {}
  }

  return {
    port: proxyPort,
    controlPort,
    qrPath,
    stateDir,
    rootDir,
    shutdown,
    get payload() { return generation?.payload ?? null },
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  const host = await startE2eHost({
    qrPath: args.qr,
    logPath: args.log || "",
    includePerformanceSession: args.performance,
  })
  process.stdout.write(`e2e-host ready port=${host.port} qr=${args.qr}\n`)
  process.stdout.write(`control=http://127.0.0.1:${host.controlPort}\n`)
  let stopping = false
  const stop = () => {
    if (stopping) return
    stopping = true
    host.shutdown().finally(() => process.exit(0))
  }
  process.on("SIGINT", stop)
  process.on("SIGTERM", stop)
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(err?.stack || err?.message || err)
    process.exit(1)
  })
}
