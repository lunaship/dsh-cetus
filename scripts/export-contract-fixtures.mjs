/**
 * 导出手机合同 fixtures（PLAN I3.2，对 main）。
 *
 * 用测试同款假 Host（临时 stateDir + typertGateway 假 DSH + 固定数据）跑真实插件，
 * 对 PLAN 附录 C 的每个手机接口发真实 HTTPS 请求，把响应体原样收进
 * testdata/mobile-contract/*.json。iOS Tests/Contract 逐个解码这些文件，Android 可接入同一批。
 *
 * 确定性：脚本内冻结 Date.now（2026-01-01T00:00:00Z，耗时测量因此恒为 0）；TLS 证书由插件在
 * 临时 stateDir 里照常生成，指纹与指纹前缀替换成占位串、剩余天数固定为数值（见 normalizeDiagnostics）；
 * 会话 / 审批 / 队列等 id 全部是假 Host 里的固定字符串。token、deviceId、rpcId、previewId、
 * 工作区审批 requestId、插件版本这类运行期或随版本变化的值，以及端口、主机名、临时目录前缀、
 * 二维码 urls，在最后统一替换成占位串（规则见 testdata/mobile-contract/README.md）。
 *
 * 用法：
 *   node scripts/export-contract-fixtures.mjs           写入/更新 fixtures，并删除不再生成的旧 json
 *   node scripts/export-contract-fixtures.mjs --check   只比对，不一致时列出文件并以非 0 退出
 */
import { createServer } from "node:http"
import https from "node:https"
import {
  mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, symlinkSync, utimesSync, writeFileSync,
} from "node:fs"
import { tmpdir, hostname } from "node:os"
import { dirname, join } from "node:path"
import { pathToFileURL, fileURLToPath } from "node:url"
import { Readable } from "node:stream"
import { apply } from "../src/index.js"
import { PLUGIN_VERSION } from "../src/diagnostics.js"

const SCRIPT_PATH = fileURLToPath(import.meta.url)
const REPO_ROOT = dirname(dirname(SCRIPT_PATH))
const OUT_DIR = join(REPO_ROOT, "testdata", "mobile-contract")

/** 冻结时钟：2026-01-01T00:00:00Z。所有时间戳（createdAt / expiresAt / generatedAt / 事件 time）都由此派生。 */
const FIXED_NOW = 1767225600000
/**
 * 诊断 tls.cert 检查的剩余天数是数字，字符串占位会破坏解码；而随机证书的有效期随生成日期
 * 每日漂移，冻结时钟也定不住。这里固定成插件默认的 10 年有效期口径（README 已说明）。
 */
const FIXTURE_CERT_DAYS_REMAINING = 3650

const SESSION_LOGIN = "sess-demo-login"
const SESSION_REPORT = "sess-demo-report"
const SESSION_STOPPED = "sess-demo-stopped"
const SESSION_REFACTOR = "sess-demo-refactor"
const SESSION_ARCHIVED = "sess-demo-archived"

const t = (i) => FIXED_NOW + i * 1000
/** login 会话末尾有一个未结束的 shell 调用（首页 activity 采样点）。 */
const LOGIN_ACTIVITY_LABEL = "go test ./..."

// ---------- 假 DSH：session 事件流（login 的 seq 连续；stopped 刻意留空洞以产生 resync-required） ----------

const loginEvents = [
  { seq: 1, type: "user/message", time: t(1), data: { content: [{ type: "text", text: "帮我排查登录接口超时的问题" }] } },
  { seq: 2, type: "user/message", time: t(2), data: { content: [{ type: "text", text: "<system-reminder>\nCurrent runtime context: workspace=dsh-demo\nAGENTS.md 已加载\n</system-reminder>" }] } },
  { seq: 3, type: "assistant/chunk", time: t(3), data: { chunk: { type: "reasoning-delta", text: "先复现一次请求，确认超时发生在网关还是应用层。" } } },
  { seq: 4, type: "assistant/chunk", time: t(4), data: { chunk: { type: "block-end", block: { type: "reasoning", text: "先复现一次请求，确认超时发生在网关还是应用层。" } } } },
  { seq: 5, type: "assistant/chunk", time: t(5), data: { chunk: { type: "block-end", block: { type: "text", text: "我先复现一下请求，再看网关日志。" } } } },
  { seq: 6, type: "tool/call", time: t(6), data: { callId: "call-1", name: "shell", arguments: { command: "curl -sS -m 5 https://api.example.com/health" }, step: 1, turn: 1 } },
  { seq: 7, type: "tool/result", time: t(7), data: { turn: 1, message: { toolCallId: "call-1", role: "tool", content: [{ type: "text", text: "HTTP/1.1 200 OK" }] } } },
  { seq: 8, type: "approval/asked", time: t(8), data: { id: "appr-write-config-1", toolName: "write", callId: "call-2", reason: "需要写入 config/timeouts.json" } },
  { seq: 9, type: "approval/decided", time: t(9), data: { id: "appr-write-config-1", outcome: "allowed-once" } },
  { seq: 10, type: "tool/call", time: t(10), data: { callId: "call-2", name: "write", arguments: { file_path: "config/timeouts.json", content: "{\n  \"connectTimeoutMs\": 5000,\n  \"retries\": 3\n}\n" }, step: 2, turn: 1 } },
  { seq: 11, type: "tool/result", time: t(11), data: { turn: 1, message: { toolCallId: "call-2", role: "tool", content: [{ type: "text", text: "wrote config/timeouts.json" }] } } },
  { seq: 12, type: "todo/write", time: t(12), data: { todos: [{ content: "复现超时", status: "completed" }, { content: "调整超时配置", status: "completed" }, { content: "补回归测试", status: "in_progress" }] } },
  { seq: 13, type: "workspace/changes", time: t(13), data: { turn: 1 } },
  { seq: 14, type: "turn/end", time: t(14), data: { turn: 1, reason: { kind: "completed" } } },
  { seq: 15, type: "tool/call", time: t(15), data: { callId: "call-3", name: "shell", arguments: { command: LOGIN_ACTIVITY_LABEL }, step: 12, turn: 2 } },
]

const reportEvents = [
  { seq: 1, type: "user/message", time: t(21), data: { content: [{ type: "text", text: "把这周的工作整理成周报" }] } },
  { seq: 2, type: "assistant/chunk", time: t(22), data: { chunk: { type: "block-end", block: { type: "text", text: "周报已写入 docs/weekly.md：门禁全绿，改动已提交。" } } } },
  // 工具输出里的 dev server 地址：preview-detections 从这里识别端口 5199
  { seq: 3, type: "tool/result", time: t(23), data: { turn: 1, message: { toolCallId: "call-30", role: "tool", content: [{ type: "text", text: "Vite dev server ready at http://127.0.0.1:5199/" }] } } },
  { seq: 4, type: "workspace/changes", time: t(24), data: { turn: 1 } },
  { seq: 5, type: "turn/end", time: t(25), data: { turn: 1, reason: { kind: "completed" } } },
]

// seq 有空洞：订阅该会话会得到 resync-required 帧（App 必须处理的分支）
const stoppedEvents = [
  { seq: 1, type: "user/message", time: t(31), data: { content: [{ type: "text", text: "部署到预发环境" }] } },
  { seq: 2, type: "tool/call", time: t(32), data: { callId: "call-9", name: "shell", arguments: { command: "make deploy-staging" }, step: 1, turn: 1 } },
  { seq: 101, type: "tool/result", time: t(33), data: { turn: 1, message: { toolCallId: "call-9", role: "tool", content: [{ type: "text", text: "deploy aborted" }] } } },
  { seq: 102, type: "turn/end", time: t(34), data: { turn: 1, reason: { kind: "interrupted" } } },
]

const refactorEvents = [
  { seq: 1, type: "user/message", time: t(41), data: { content: [{ type: "text", text: "把登录逻辑从 server.js 拆出来" }] } },
  { seq: 2, type: "tool/call", time: t(42), data: { callId: "call-20", name: "edit", arguments: { file_path: "src/auth.js", old_string: "export function login()", new_string: "export async function login()" }, step: 3, turn: 1 } },
]

const SESSION_EVENTS = {
  [SESSION_LOGIN]: loginEvents,
  [SESSION_REPORT]: reportEvents,
  [SESSION_STOPPED]: stoppedEvents,
  [SESSION_REFACTOR]: refactorEvents,
}

const loginProjections = {
  values: {
    tokenUsage: { uncachedInputTokens: 12500, cacheReadTokens: 98000, outputTokens: 3450 },
    // 以下投影由插件原样透传（src/mobile-api.js），形状以 Android 的解析为准（MobileApi.kt parseMobileSessionStats、
    // SessionControl.kt parseSessionGoal），不是插件定义的。
    sessionStats: { turns: 2, steps: 7, llmMs: 61000, toolMs: 42000, ttftMs: 1800, ttftSteps: 7, decodeMs: 52000, decodeTokens: 3450 },
    contextPressure: { projectedTokens: 113950, contextWindow: 1048576 },
    contextBreakdown: { systemTokens: 4200, toolsTokens: 18600, messageTokens: 91150 },
    todos: [{ content: "复现超时", status: "completed" }, { content: "调整超时配置", status: "completed" }, { content: "补回归测试", status: "in_progress" }],
    inbox: { "next-turn": [{ id: "inbox-1", content: [{ type: "text", text: "把重试次数改成 3 次，并在修复后跑一遍回归测试" }], source: { kind: "user" } }] },
    goal: { goal: { id: "goal-login-1", revision: 3, objective: "修复登录接口超时并补齐回归测试", phase: "active", maxGoalRounds: 8 }, roundsStarted: 2 },
  },
}

const SESSION_ROWS = [
  { sessionId: SESSION_LOGIN, cwd: "__WORKSPACE__", origin: "user", running: true, updatedAt: t(16), projections: { asOfSeq: 15, values: { title: "修复登录超时", ...loginProjections.values } } },
  { sessionId: SESSION_REPORT, cwd: "__WORKSPACE__", origin: "user", running: false, agentPreset: "writer", updatedAt: t(26), projections: { asOfSeq: 5, values: { title: "整理周报" } } },
  { sessionId: SESSION_STOPPED, cwd: "__WORKSPACE__", origin: "user", running: false, updatedAt: t(36), projections: { asOfSeq: 102, values: { title: "部署到预发环境" } } },
  { sessionId: SESSION_REFACTOR, cwd: "__WORKSPACE__", origin: "user", running: true, updatedAt: t(46), projections: { asOfSeq: 2, values: { title: "重构登录模块" } } },
]

// Host workspaceChanges 服务：只回应固定坐标，其余视为 Host 重启后不可取（undefined）
const CHANGES_SUMMARIES = {
  [`${SESSION_LOGIN}:13`]: {
    turn: 1,
    total: 2,
    added: 36,
    deleted: 4,
    files: [
      { path: "config/timeouts.json", display: "config/timeouts.json", added: 12, deleted: 2 },
      { path: "src/login.ts", display: "src/login.ts", added: 24, deleted: 2 },
    ],
  },
  [`${SESSION_REPORT}:4`]: {
    turn: 1,
    total: 3,
    added: 120,
    deleted: 45,
    files: [
      { path: "docs/weekly.md", display: "docs/weekly.md", added: 96, deleted: 12 },
      { path: "docs/notes.md", display: "docs/notes.md", added: 18, deleted: 33 },
      { path: ".gitignore", display: ".gitignore", added: 6, deleted: 0 },
    ],
  },
}

const CHANGES_DIFFS = {
  [`${SESSION_LOGIN}:13:1`]: {
    path: "src/login.ts",
    display: "src/login.ts",
    kind: "text",
    before: true,
    after: true,
    coarse: false,
    hunks: [
      {
        oldStart: 1,
        oldLines: 2,
        newStart: 1,
        newLines: 3,
        lines: [
          " export const LOGIN_TIMEOUT_MS = 5000",
          "-export const LOGIN_RETRIES = 1",
          "+export const LOGIN_RETRIES = 3",
          "+export const LOGIN_BACKOFF_MS = 250",
        ],
      },
    ],
  },
}

// Host workspace.list / workspace.create（fake）使用的固定工作区行；path 由脚本注入真实临时目录
const WORKSPACE_ROW = { workspaceId: "ws-demo-main", path: "__WORKSPACE__" }

// 手机「模型与余额」页的 Host 目录：deepseek-account 模型自带 pricing，
// history 的 estimatedCost 走 source=host（不随真实时钟的峰谷变化，可复现）。
const MODEL_CATALOG = {
  default: { provider: "deepseek-account", model: "deepseek-v4-pro" },
  groups: [
    {
      id: "deepseek-account",
      name: "DeepSeek 账号",
      models: [{
        id: "deepseek-v4-pro",
        name: "DeepSeek V4 Pro",
        contextWindow: 131072,
        maxTokens: 8192,
        pricing: { cacheHitPerMillion: 0.022, cacheMissPerMillion: 0.66, outputPerMillion: 1.98, currency: "USD", priceDate: "2026-09-01" },
      }],
    },
    { id: "deepseek-official", name: "DeepSeek 开放平台", models: [{ id: "deepseek-v4", name: "DeepSeek V4", contextWindow: 131072, maxTokens: 8192 }] },
    { id: "zai", name: "Z.ai", models: [{ id: "glm-5.3", name: "GLM-5.3", contextWindow: 1000000, maxTokens: 8192 }] },
  ],
  failures: [],
  routableProviders: [],
}

const PROVIDER_SETTINGS = {
  writable: true,
  namespaces: [
    {
      ns: "llm-deepseek",
      revision: 1,
      user: null,
      applies: "restart",
      secrets: [{ path: ["apiKeyEnv"], set: true }],
      value: { apiKeyEnv: "DEEPSEEK_API_KEY", models: [{ id: "deepseek-v4", name: "DeepSeek V4", reasoning: { efforts: ["high"] } }] },
    },
    {
      ns: "llm-pi-ai",
      revision: 7,
      user: null,
      applies: "restart",
      secrets: [{ path: ["providers"], set: true }],
      value: { providers: { zai: { apiKeyEnv: "ZAI_API_KEY", models: [{ id: "glm-5.3", name: "GLM-5.3", contextWindow: 1000000 }] } } },
    },
  ],
}

const CREDENTIALS = {
  DEEPSEEK_API_KEY: { configured: true, writable: false, source: "environment" },
  ZAI_API_KEY: { configured: true, writable: true, source: null },
}

// ---------- 假 DSH RPC（typertGateway 形状：invoke + stream） ----------

function oneShotFrame(value) {
  return (async function* () {
    yield value
  })()
}

function createFakeGateway({ workspacePath }) {
  const rows = SESSION_ROWS.map((row) => ({
    ...row,
    cwd: row.cwd === "__WORKSPACE__" ? workspacePath : row.cwd,
  }))
  const invoke = async ({ namespace, method, args }) => {
    const name = `${namespace}/${method}`
    switch (name) {
      case "session/list":
        return { items: rows }
      case "session/search":
        return { items: [{ sessionId: SESSION_LOGIN, snippet: "登录接口超时排查" }], hasMore: false }
      case "session/page": {
        const request = args?.request ?? {}
        const sessionId = request.address?.sessionId
        const all = SESSION_EVENTS[sessionId] ?? []
        let list = all
        if (Number.isInteger(request.throughSeq)) list = list.filter((e) => e.seq <= request.throughSeq)
        if (Number.isInteger(request.beforeSeq) && request.beforeSeq > 0) list = list.filter((e) => e.seq < request.beforeSeq)
        if (Number.isInteger(request.maxMessages) && request.maxMessages > 0) list = list.slice(-request.maxMessages)
        const projections = sessionId === SESSION_LOGIN ? loginProjections : null
        return { records: list.map((event) => ({ event })), hasMore: false, ...(projections ? { projections } : {}) }
      }
      case "session/modelCatalog":
        return structuredClone(MODEL_CATALOG)
      case "session/prompt":
        return { accepted: true }
      case "session/cancel":
        return { ok: true }
      case "session/updateQueue":
        return { ok: true }
      case "workspace/create":
        return { workspace: { workspaceId: "ws-demo-analysis", path: args?.request?.path ?? "", name: "analysis" }, created: true }
      case "account/getBalance":
        return { status: "ready", value: [{ currency: "CNY", balance: "128.50" }], bonusWallets: [{ currency: "CNY", balance: "6.00" }] }
      case "llm/listProviders":
        return [{ id: "deepseek-account", name: "DeepSeek" }, { id: "deepseek-official", name: "DeepSeek 开放平台" }, { id: "zai", name: "Z.ai" }]
      case "llm/listConfigurableProviders":
        return [
          { provider: "deepseek-official", displayName: "DeepSeek 开放平台", settingsNs: "llm-deepseek", settingsPath: [] },
          { provider: "zai", displayName: "Z.ai", settingsNs: "llm-pi-ai", settingsPath: ["providers", "zai"] },
          { provider: "openai", displayName: "OpenAI", settingsNs: "llm-pi-ai", settingsPath: ["providers", "openai"] },
        ]
      case "settings/describe":
        return structuredClone(PROVIDER_SETTINGS)
      case "credentials/describe": {
        const refs = Array.isArray(args?.refs) ? args.refs : []
        return Object.fromEntries(refs.filter((ref) => ref in CREDENTIALS).map((ref) => [ref, { ...CREDENTIALS[ref] }]))
      }
      default:
        throw new Error(`fake gateway: unexpected ${name}`)
    }
  }
  const stream = async (request) => {
    const name = `${request?.namespace}/${request?.method}`
    if (name === "workspace/follow") {
      return oneShotFrame({ type: "baseline", value: { items: [{ ...WORKSPACE_ROW, path: workspacePath }], archivedSessionIds: [SESSION_ARCHIVED] } })
    }
    if (name === "session/follow") {
      const sessionId = request?.args?.request?.address?.sessionId
      const events = SESSION_EVENTS[sessionId]
      if (!events) throw new Error(`fake gateway: unknown session ${sessionId}`)
      const projections = sessionId === SESSION_LOGIN ? loginProjections : { values: {} }
      return oneShotFrame({ type: "snapshot", records: events.map((event) => ({ event })), hasMore: false, projections })
    }
    throw new Error(`fake gateway: unexpected stream ${name}`)
  }
  return { invoke, stream }
}

const changesService = {
  summary(sessionId, seq) {
    const hit = CHANGES_SUMMARIES[`${sessionId}:${seq}`]
    return hit ? structuredClone(hit) : undefined
  },
  async diff(sessionId, seq, index) {
    const hit = CHANGES_DIFFS[`${sessionId}:${seq}:${index}`]
    return hit ? structuredClone(hit) : undefined
  },
}

// ---------- 测试脚手架（与 test/*.mjs 同一套做法） ----------

function startUpstream() {
  return new Promise((resolve) => {
    const srv = createServer((req, res) => {
      res.writeHead(200, { "content-type": "text/plain" })
      res.end("upstream-ok")
    })
    srv.listen(0, "127.0.0.1", () => resolve(srv))
  })
}

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = createServer()
    srv.unref()
    srv.once("error", reject)
    srv.listen(0, "127.0.0.1", () => {
      const port = srv.address().port
      srv.close(() => resolve(port))
    })
  })
}

function makeCtx({ upstreamPort, gateway }) {
  const registered = []
  const effects = []
  const listeners = new Map()
  const ctx = {
    logger: { info() {}, warn() {} },
    get(name) {
      if (name === "webServer") {
        return {
          port: upstreamPort,
          register(route) { registered.push(route); return () => {} },
          tapIndex() { return () => {} },
        }
      }
      if (name === "typertGateway") return gateway
      if (name === "workspaceChanges") return changesService
      if (name === "sessions") return { get: async () => null }
      return null
    },
    on(name, listener) { listeners.set(name, listener) },
    effect(fn) { effects.push(fn()) },
    listeners,
  }
  return { ctx, registered, effects, listeners }
}

function createWorkspaceTree(rootDir) {
  const workspaceDir = join(rootDir, "workspace")
  mkdirSync(join(workspaceDir, "config"), { recursive: true })
  mkdirSync(join(workspaceDir, "src"), { recursive: true })
  mkdirSync(join(workspaceDir, "assets"), { recursive: true })
  writeFileSync(join(workspaceDir, "README.md"), "# Demo 工作区\n\n用于生成手机合同 fixtures 的示例工作区。\n")
  writeFileSync(join(workspaceDir, "config", "timeouts.json"), "{\n  \"connectTimeoutMs\": 5000,\n  \"retries\": 3\n}\n")
  writeFileSync(join(workspaceDir, "src", "login.ts"), "export const LOGIN_TIMEOUT_MS = 5000\nexport const LOGIN_RETRIES = 3\nexport const LOGIN_BACKOFF_MS = 250\n")
  symlinkSync(join(workspaceDir, "src"), join(workspaceDir, "link-to-src"))
  const elsewhere = join(rootDir, "elsewhere")
  mkdirSync(elsewhere, { recursive: true })
  writeFileSync(join(elsewhere, "outside.log"), "workspace 外的文件\n")
  symlinkSync(join(elsewhere, "outside.log"), join(workspaceDir, "outside.log"))
  // 固定 mtime：tree 的 mtimeMs 才能跨次运行一致
  const fixed = new Date(FIXED_NOW)
  for (const rel of [".", "README.md", "config", "config/timeouts.json", "src", "src/login.ts", "assets", "link-to-src", "outside.log"]) {
    utimesSync(join(workspaceDir, rel), fixed, fixed)
  }
  return { workspaceDir, elsewhere }
}

// ---------- HTTP 客户端 ----------

function httpsAgentFor(stateDir) {
  const tls = JSON.parse(readFileSync(join(stateDir, "tls.json"), "utf8"))
  return new https.Agent({ ca: tls.cert, rejectUnauthorized: true, checkServerIdentity: () => undefined })
}

function proxyRequest({ agent, proxyPort, token, path, method = "GET", body }) {
  return new Promise((resolve, reject) => {
    const headers = { ...(token ? { "x-dsh-link-token": token } : {}) }
    let payload
    if (body !== undefined) {
      headers["content-type"] = "application/json"
      payload = Buffer.from(JSON.stringify(body))
      headers["content-length"] = String(payload.length)
    }
    const req = https.request(new URL(`https://127.0.0.1:${proxyPort}${path}`), { method, headers, agent }, (res) => {
      const chunks = []
      res.on("data", (c) => chunks.push(c))
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8")
        let json = null
        try { json = JSON.parse(text) } catch {}
        resolve({ status: res.statusCode, headers: res.headers, text, json })
      })
    })
    req.on("error", reject)
    if (payload) req.write(payload)
    req.end()
  })
}

/** 以回环同源身份调用面板路由（pair-info / pair-settings / previews 批准只在回环面板上）。 */
async function callPanelRoute(route, { body } = {}) {
  let status = 0
  let out = ""
  const res = {
    writeHead(code) { status = code },
    end(b) { out = b == null ? "" : Buffer.isBuffer(b) ? b.toString("utf8") : String(b) },
  }
  let req
  if (body !== undefined) {
    req = Readable.from([Buffer.from(JSON.stringify(body))])
    req.method = "POST"
    req.headers = { host: "127.0.0.1:3080", "content-type": "application/json" }
  } else {
    req = { method: "GET", headers: { host: "127.0.0.1:3080" } }
  }
  req.url = route.path
  req.socket = { remoteAddress: "127.0.0.1" }
  await route.handler(req, res)
  let json = null
  try { json = JSON.parse(out) } catch {}
  return { status, json, raw: out }
}

// ---------- SSE 采集 ----------

function parseSseBlock(block) {
  let event = "message"
  let id = null
  const data = []
  for (const line of block.split("\n")) {
    if (!line || line.startsWith(":")) continue
    const m = /^([A-Za-z-]+):\s?(.*)$/.exec(line)
    if (!m) continue
    if (m[1] === "event") event = m[2]
    else if (m[1] === "id") id = Number(m[2])
    else if (m[1] === "data") data.push(m[2])
  }
  if (data.length === 0) return null
  let parsed = null
  try { parsed = JSON.parse(data.join("\n")) } catch { parsed = data.join("\n") }
  return { event, id, data: parsed }
}

/**
 * 打开一条 SSE 连接，收满 maxFrames 后 resolve（连接保持打开）。
 * 审批 / 提问要求「正在查看该会话」——采集登录会话流的连接必须一直挂着。
 */
function openSse({ agent, proxyPort, token, path, quietMs = 500, timeoutMs = 10_000 }) {
  const frames = []
  let buffer = ""
  let settled = false
  let resolveFrames = null
  let rejectFrames = null
  let quietTimer = null
  const framesPromise = new Promise((resolve, reject) => {
    resolveFrames = resolve
    rejectFrames = reject
  })
  const settle = (err) => {
    if (settled) return
    settled = true
    clearTimeout(timer)
    if (quietTimer) clearTimeout(quietTimer)
    if (err && frames.length === 0) rejectFrames(err)
    else resolveFrames(frames)
  }
  // 连接安静 quietMs 即认为初始补发结束（心跳 15s/25s、轮询 5s 都远晚于这个窗口）。
  const armQuiet = () => {
    if (quietTimer) clearTimeout(quietTimer)
    quietTimer = setTimeout(() => settle(), quietMs)
  }
  const headers = { accept: "text/event-stream", ...(token ? { "x-dsh-link-token": token } : {}) }
  const req = https.request(new URL(`https://127.0.0.1:${proxyPort}${path}`), { method: "GET", headers, agent }, (res) => {
    if (res.statusCode !== 200) {
      const chunks = []
      res.on("data", (c) => chunks.push(c))
      res.on("end", () => settle(new Error(`SSE ${path} → ${res.statusCode}: ${Buffer.concat(chunks).toString("utf8").slice(0, 200)}`)))
      return
    }
    res.setEncoding("utf8")
    res.on("data", (chunk) => {
      buffer += chunk
      let idx
      while ((idx = buffer.indexOf("\n\n")) >= 0) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        const frame = parseSseBlock(block)
        if (frame) frames.push(frame)
      }
      armQuiet()
    })
    res.on("error", () => settle())
    res.on("end", () => settle())
  })
  const timer = setTimeout(() => settle(new Error(`SSE ${path} 采集超时`)), timeoutMs)
  req.on("error", (err) => settle(err))
  req.end()
  return { frames: framesPromise, close: () => { settle(); try { req.destroy() } catch {} } }
}

// ---------- 占位串替换 ----------

/**
 * 运行期随机值 → 占位串，用精确整串匹配（部分替换会误伤正文里的巧合数字）。
 * 端口、主机名、临时目录前缀、二维码 urls 用单独规则。
 */
function createRedactor({ rootReal, proxyPort }) {
  const exact = new Map()
  const lanUrlRe = new RegExp(`^https://[^\\s/]+:${proxyPort}$`)
  const redactString = (value) => {
    if (exact.has(value)) return exact.get(value)
    if (value.startsWith(rootReal)) return `<fixtureRoot>${value.slice(rootReal.length)}`
    if (lanUrlRe.test(value)) return "https://<lanAddress>:<port>"
    return value
  }
  const redact = (value) => {
    if (typeof value === "string") return redactString(value)
    if (Array.isArray(value)) return value.map(redact)
    if (value && typeof value === "object") {
      const out = {}
      for (const [key, item] of Object.entries(value)) out[key] = redact(item)
      return out
    }
    return value
  }
  return {
    redact,
    set(value, placeholder) {
      if (value !== undefined && value !== null && String(value).length > 0) exact.set(String(value), placeholder)
    },
  }
}

/** 二维码 urls 依机器网卡数量而异：统一成单个占位条目，保持「https URL 数组」的形状。 */
function normalizePairUrls(body) {
  if (body && Array.isArray(body.urls)) body.urls = ["https://<lanAddress>:<port>"]
  return body
}

/**
 * 诊断报告里无法占位成字符串的数字字段：随机证书的剩余天数固定成
 * FIXTURE_CERT_DAYS_REMAINING（指纹 / 指纹前缀 / 插件版本走占位串，见 redactor 注册处）。
 */
function normalizeDiagnostics(report) {
  const tlsCheck = (report?.checks ?? []).find((check) => check?.id === "tls.cert")
  if (tlsCheck?.detail) tlsCheck.detail.daysRemaining = FIXTURE_CERT_DAYS_REMAINING
  return report
}

// ---------- 主流程 ----------

export async function buildContractFixtures() {
  const rootDir = mkdtempSync(join(tmpdir(), "dsh-contract-fixtures-"))
  const rootReal = realpathSync(rootDir)
  const stateDir = join(rootDir, "state")
  mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  // TLS 证书不预置：由 apply() 的 loadOrCreateTls 在临时 stateDir 里照常生成
  const { workspaceDir, elsewhere } = createWorkspaceTree(rootDir)

  const realDateNow = Date.now
  Date.now = () => FIXED_NOW
  const upstream = await startUpstream()
  let effects = []
  try {
    const proxyPort = await freePort()
    // macOS 的 tmpdir 带符号链接（/var → /private/var）：cwd 一律用 realpath，
    // 否则响应里的路径前缀和 rootReal 对不上，占位替换会漏。
    const gateway = createFakeGateway({ workspacePath: realpathSync(workspaceDir) })
    const { ctx, registered, effects: fx } = makeCtx({ upstreamPort: upstream.address().port, gateway })
    effects = fx
    await apply(ctx, { port: proxyPort, pairingTtlSeconds: 300, autoApprove: true, stateDir, eventPollIntervalMs: 60000 })

    const agent = httpsAgentFor(stateDir)
    const request = (path, init = {}) => proxyRequest({ agent, proxyPort, path, ...init })
    const pluginState = JSON.parse(readFileSync(join(stateDir, "state.json"), "utf8"))
    const redactor = createRedactor({ rootReal, proxyPort })
    redactor.set(hostname(), "<hostName>")
    redactor.set(pluginState.deviceId, "<hostId>")
    // 插件本次运行生成的自签证书：指纹整串与 8 位前缀精确匹配替换
    const generatedTls = JSON.parse(readFileSync(join(stateDir, "tls.json"), "utf8"))
    redactor.set(generatedTls.fingerprint, "<fingerprint>")
    redactor.set(String(generatedTls.fingerprint ?? "").slice(0, 8), "<fingerprintPrefix>")
    redactor.set(PLUGIN_VERSION, "<pluginVersion>")

    // 先收集原始响应，占位值收集齐后在导出前统一替换
    const raw = new Map()
    const put = (name, value, normalize) => { raw.set(name, { value: JSON.parse(JSON.stringify(value)), normalize }) }
    const authed = (path, init = {}) => request(path, { ...init, token: mainToken })

    const route = (path) => registered.find((r) => r.path === path)
    const pairInfo = async () => (await callPanelRoute(route("/dsh-link/pair-info"))).json
    const pairSettings = (requireConfirm) => callPanelRoute(route("/dsh-link/pair-settings"), { body: { requireConfirm } })
    const pair = (body) => request("/dsh-link/pair", { method: "POST", body })

    let mainToken = null

    // ---- 配对：pending / 成功 / 同名 409 ----
    await pairSettings(true)
    const pendingPair = await pair({ code: (await pairInfo()).pairingCode, deviceName: "iPad Pro", requestId: "fixture-pair-ipad-1" })
    if (pendingPair.status !== 200) throw new Error(`pair pending → ${pendingPair.status}`)
    redactor.set(pendingPair.json.token, "<token>")
    redactor.set(pendingPair.json.deviceId, "<deviceId>")
    put("pair-pending.json", pendingPair.json, normalizePairUrls)

    await pairSettings(false)
    const mainPair = await pair({ code: (await pairInfo()).pairingCode, deviceName: "iPhone 15 Pro", requestId: "fixture-pair-main-1" })
    if (mainPair.status !== 200) throw new Error(`pair main → ${mainPair.status}`)
    mainToken = mainPair.json.token
    redactor.set(mainToken, "<token>")
    redactor.set(mainPair.json.deviceId, "<deviceId>")
    put("pair.json", mainPair.json, normalizePairUrls)

    const conflict = await pair({ code: (await pairInfo()).pairingCode, deviceName: "iPhone 15 Pro", requestId: "fixture-pair-conflict-1" })
    if (conflict.status !== 409) throw new Error(`pair same-name → ${conflict.status}`)
    put("pair-same-name-409.json", conflict.json)

    // ---- 列表 / 详情 / 诊断 ----
    put("bootstrap.json", (await authed("/dsh-link/mobile/bootstrap")).json)
    put("sessions.json", (await authed("/dsh-link/mobile/sessions")).json)
    put("sessions-search.json", (await authed(`/dsh-link/mobile/sessions/search?q=${encodeURIComponent("登录")}`)).json)
    put("history.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/history`)).json)
    put("changes.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/changes?seq=13`)).json)
    put("diagnostics.json", (await authed("/dsh-link/mobile/diagnostics")).json, normalizeDiagnostics)

    // ---- 工作区：单层名称（200）与绝对路径（202 待本机批准） ----
    put("workspaces-200.json", (await authed("/dsh-link/mobile/workspaces", { method: "POST", body: { input: "analysis" } })).json)
    const pendingWorkspace = await authed("/dsh-link/mobile/workspaces", { method: "POST", body: { input: elsewhere } })
    if (pendingWorkspace.status !== 202) throw new Error(`workspaces absolute-path → ${pendingWorkspace.status}`)
    redactor.set(pendingWorkspace.json.requestId, "<requestId>")
    put("workspaces-202.json", pendingWorkspace.json)

    // ---- 发送与控制 ----
    put("prompt.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/prompt`, {
      method: "POST",
      body: { text: "把重试次数改成 3 次，然后跑一遍测试" },
    })).json)
    put("cancel.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/cancel`, { method: "POST", body: {} })).json)
    put("queue-edit.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/queue/inbox-1`, {
      method: "POST",
      body: { action: "edit", text: "把重试次数改成 3 次，并在修复后跑一遍回归测试" },
    })).json)

    // ---- 预览：面板批准后手机只能看 ----
    const approved = await callPanelRoute(route("/dsh-link/previews"), { body: { port: 4173, label: "Vite" } })
    if (approved.status !== 200) throw new Error(`previews approve → ${approved.status}`)
    redactor.set(approved.json.preview.previewId, "<previewId>")
    put("previews.json", (await authed("/dsh-link/mobile/previews")).json)
    put("preview-detections.json", (await authed("/dsh-link/mobile/preview-detections")).json)

    // ---- 模型页 ----
    put("balance.json", (await authed("/dsh-link/mobile/balance?locale=zh-CN")).json)
    put("providers.json", (await authed("/dsh-link/mobile/providers")).json)

    // ---- 会话 SSE：先采正常帧；登录会话的连接保持打开（文件 / 审批 / 提问 / diff 都要求活跃订阅） ----
    const streamPath = (sessionId) =>
      `/dsh-link/mobile/sessions/${sessionId}/stream?caps=${encodeURIComponent("sync2,multiQuestion,requestState")}`
    const loginStream = openSse({ agent, proxyPort, token: mainToken, path: streamPath(SESSION_LOGIN) })
    const loginFrames = await loginStream.frames
    if (!loginFrames.some((f) => f.event === "ready") || !loginFrames.some((f) => f.event === "stats")) {
      throw new Error(`session-stream 帧不完整：${loginFrames.map((f) => f.event).join(",")}`)
    }
    put("session-stream.json", loginFrames)
    const resyncStream = openSse({ agent, proxyPort, token: mainToken, path: streamPath(SESSION_STOPPED) })
    const resyncFrames = await resyncStream.frames
    if (!resyncFrames.some((f) => f.event === "resync-required")) {
      throw new Error(`session-stream-resync 缺少 resync-required 帧：${resyncFrames.map((f) => f.event).join(",")}`)
    }
    put("session-stream-resync.json", resyncFrames)
    resyncStream.close()

    // ---- 文件元信息 / 目录树（真实读临时工作区，mtime 已固定；要求活跃订阅） ----
    const fileRes = await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/file?path=${encodeURIComponent("config/timeouts.json")}`)
    if (fileRes.status !== 200) throw new Error(`file → ${fileRes.status}`)
    put("file-meta.json", {
      path: "config/timeouts.json",
      contentType: fileRes.headers["content-type"],
      size: Number(fileRes.headers["content-length"]),
      sha256: fileRes.headers["x-dsh-link-sha256"],
      filename: decodeURIComponent(fileRes.headers["x-dsh-link-filename"]),
      attachment: fileRes.headers["content-disposition"] !== undefined,
    })
    put("tree.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/tree`)).json)

    // ---- 审批 / 提问：经 approval/request、user-questions/request 瀑布挂起，手机快照 + 提交 ----
    const fakeSignal = { aborted: false, addEventListener() {}, removeEventListener() {} }
    const approvalPromise = ctx.listeners.get("approval/request")({
      id: "appr-tool-write-1",
      callId: "call-99",
      toolName: "write",
      agent: { session: { id: SESSION_LOGIN, events: [] } },
      signal: fakeSignal,
    }, async () => "rejected")
    const questionPromise = ctx.listeners.get("user-questions/request")({
      agent: { session: { id: SESSION_LOGIN } },
      signal: fakeSignal,
      questions: [{
        id: "deploy-target",
        header: "部署",
        question: "要把调整后的配置写到哪个环境？",
        options: [{ id: "staging", label: "测试环境" }, { id: "production", label: "生产环境" }],
      }],
    }, async () => null)

    const requests = await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/requests`)
    if (requests.status !== 200) throw new Error(`requests → ${requests.status}`)
    const rpcId = requests.json.questions.find((q) => q.status === "pending")?.rpcId
    if (!rpcId) throw new Error("requests 快照里没有 pending 澄清")
    redactor.set(rpcId, "<rpcId>")
    put("requests.json", requests.json)

    // 主机事件流：此刻 login 挂着审批（awaitingApproval），refactor 在跑（running）
    const hostFrames = await openSse({ agent, proxyPort, token: mainToken, path: "/dsh-link/mobile/events" }).frames
    if (!hostFrames.some((f) => f.data?.state === "awaitingApproval") || !hostFrames.some((f) => f.data?.state === "running")) {
      throw new Error(`host-events 帧不完整：${hostFrames.map((f) => f.data?.state).join(",")}`)
    }
    put("host-events.json", hostFrames)

    put("approval-submit.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/approval`, {
      method: "POST",
      body: { approvalId: "appr-tool-write-1", outcome: "allowed-once" },
    })).json)
    put("question-submit.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/question`, {
      method: "POST",
      body: { rpcId, answer: { answers: [{ id: "deploy-target", selected: ["staging"] }] } },
    })).json)
    await approvalPromise
    await questionPromise

    // diff 送出文件全文，只给持有该会话活跃订阅的设备：保持订阅期间再取
    put("changes-diff.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/changes/diff?seq=13&index=1`)).json)
    loginStream.close()

    // ---- 设备与吊销（只吊销 fixtures 自己的测试设备） ----
    put("devices.json", (await authed("/dsh-link/mobile/devices")).json)
    put("revoke.json", (await authed("/dsh-link/mobile/revoke", { method: "POST", body: { deviceId: mainPair.json.deviceId } })).json)

    // ---- 统一占位替换后导出 ----
    const fixtures = {}
    for (const [name, entry] of raw) {
      const redacted = redactor.redact(entry.value)
      fixtures[name] = entry.normalize ? entry.normalize(redacted) : redacted
    }
    return fixtures
  } finally {
    Date.now = realDateNow
    for (const fn of effects) try { fn() } catch {}
    upstream.close()
    rmSync(rootDir, { recursive: true, force: true })
  }
}

// ---------- CLI ----------

function serialize(value) {
  return `${JSON.stringify(value, null, 2)}\n`
}

function diffFixtureSets(fixtures) {
  const expected = new Map(Object.entries(fixtures).map(([name, value]) => [name, serialize(value)]))
  const disk = new Map()
  let names = []
  try {
    names = readdirSync(OUT_DIR).filter((name) => name.endsWith(".json")).sort()
  } catch {
    // 目录不存在：全部视为待写入
  }
  for (const name of names) {
    try { disk.set(name, readFileSync(join(OUT_DIR, name), "utf8")) } catch { disk.set(name, null) }
  }
  const missing = [...expected.keys()].filter((name) => !disk.has(name)).sort()
  const stale = [...disk.keys()].filter((name) => !expected.has(name)).sort()
  const changed = [...expected.keys()].filter((name) => disk.has(name) && disk.get(name) !== expected.get(name)).sort()
  return { missing, stale, changed }
}

async function main() {
  const check = process.argv.includes("--check")
  const fixtures = await buildContractFixtures()
  const { missing, stale, changed } = diffFixtureSets(fixtures)
  if (check) {
    const problems = [
      ...missing.map((name) => `缺少 ${name}`),
      ...changed.map((name) => `内容不一致 ${name}`),
      ...stale.map((name) => `不再生成的旧文件 ${name}`),
    ]
    if (problems.length > 0) {
      console.error("contract fixtures 与当前插件响应不一致（运行 node scripts/export-contract-fixtures.mjs 更新）：")
      for (const line of problems) console.error(`  - ${line}`)
      process.exit(1)
    }
    console.log(`contract fixtures 与当前插件响应一致（${Object.keys(fixtures).length} 个文件）`)
    return
  }
  mkdirSync(OUT_DIR, { recursive: true })
  for (const [name, value] of Object.entries(fixtures)) {
    writeFileSync(join(OUT_DIR, name), serialize(value), { mode: 0o644 })
  }
  for (const name of stale) {
    rmSync(join(OUT_DIR, name), { force: true })
  }
  console.log(`已写入 ${Object.keys(fixtures).length} 个 fixture → testdata/mobile-contract/${stale.length ? `（删除 ${stale.length} 个旧文件）` : ""}`)
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(err)
    process.exit(1)
  })
}
