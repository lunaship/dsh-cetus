/**
 * 合同 fixtures 与 iOS e2e 共用的假 Host。
 *
 * 从 scripts/export-contract-fixtures.mjs 抽出：假 DSH gateway、工作区树、makeCtx、
 * 上游与空闲端口、TLS agent、代理请求、面板路由调用、SSE 采集。
 * 事件流仍是固定数组；另外挂一个按会话的 live 缓冲，供 e2e host 在
 * 不改变 fixtures 快照的前提下往现有 session SSE 追加 assistant/chunk。
 * session.history 的轮询会先看会话日志文件是否变大，所以 appendLiveEvent 同时写一行 JSONL。
 */
import { createServer } from "node:http"
import https from "node:https"
import {
  appendFileSync, mkdirSync, readFileSync, symlinkSync, utimesSync, writeFileSync,
} from "node:fs"
import { join } from "node:path"
import { Readable } from "node:stream"
import { sessionDirFor } from "../src/session-log-path.js"

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

function createFakeGateway({ workspacePath, sessionLogHome } = {}) {
  const rows = SESSION_ROWS.map((row) => ({
    ...row,
    cwd: row.cwd === "__WORKSPACE__" ? workspacePath : row.cwd,
  }))
  /** sessionId → 追加在固定快照之后的事件。fixtures 不读它，所以导出结果不变。 */
  const liveEvents = new Map()
  /** 手机 session.prompt 转过来的正文，按到达顺序保留。 */
  const prompts = []
  // 插件轮询 session.history 前用会话日志的 size/mtime 短路。文件必须在第一次订阅前就存在，
  // 否则缺失会被缓存 10 秒，追加的 chunk 送不到已订阅的手机。fixtures 不传 sessionLogHome，不写盘。
  if (sessionLogHome) {
    for (const row of rows) {
      const events = SESSION_EVENTS[row.sessionId] ?? []
      const dir = sessionDirFor(row.cwd, row.sessionId, { DSH_HOME: sessionLogHome })
      mkdirSync(dir, { recursive: true })
      const body = events.map((event) => JSON.stringify(event)).join("\n")
      writeFileSync(join(dir, "session.jsonl"), events.length ? body + "\n" : "")
    }
  }
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
        const all = [...(SESSION_EVENTS[sessionId] ?? []), ...(liveEvents.get(sessionId) ?? [])]
        let list = all
        if (Number.isInteger(request.throughSeq)) list = list.filter((e) => e.seq <= request.throughSeq)
        if (Number.isInteger(request.beforeSeq) && request.beforeSeq > 0) list = list.filter((e) => e.seq < request.beforeSeq)
        if (Number.isInteger(request.maxMessages) && request.maxMessages > 0) list = list.slice(-request.maxMessages)
        const projections = sessionId === SESSION_LOGIN ? loginProjections : null
        return { records: list.map((event) => ({ event })), hasMore: false, ...(projections ? { projections } : {}) }
      }
      case "session/modelCatalog":
        return structuredClone(MODEL_CATALOG)
      case "session/prompt": {
        const request = args?.request ?? {}
        const content = Array.isArray(request.content) ? request.content : []
        const text = content.filter((part) => part?.type === "text").map((part) => String(part.text ?? "")).join("")
        prompts.push({
          sessionId: request.sessionId ?? null,
          text,
          mode: request.mode ?? null,
          content,
          at: Date.now(),
        })
        return { accepted: true }
      }
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
      const base = SESSION_EVENTS[sessionId]
      if (!base) throw new Error(`fake gateway: unknown session ${sessionId}`)
      const events = [...base, ...(liveEvents.get(sessionId) ?? [])]
      const projections = sessionId === SESSION_LOGIN ? loginProjections : { values: {} }
      return oneShotFrame({ type: "snapshot", records: events.map((event) => ({ event })), hasMore: false, projections })
    }
    throw new Error(`fake gateway: unexpected stream ${name}`)
  }
  /**
   * 往某个已知会话追加一条事件，并把同一行写进会话 JSONL。
   * 插件轮询 session.history 前会用文件 size/mtime 短路；不落盘则已订阅的手机看不到新 chunk。
   * seq 缺省时接在该会话当前最大 seq 之后。
   */
  function appendLiveEvent(sessionId, event) {
    if (!SESSION_EVENTS[sessionId]) {
      const err = new Error(`unknown session ${sessionId}`)
      err.code = "unknown-session"
      throw err
    }
    const existing = liveEvents.get(sessionId) ?? []
    const baseMax = (SESSION_EVENTS[sessionId] ?? []).reduce((max, item) => Math.max(max, item.seq ?? 0), 0)
    const liveMax = existing.reduce((max, item) => Math.max(max, item.seq ?? 0), 0)
    const seq = Number.isInteger(event?.seq) ? event.seq : Math.max(baseMax, liveMax) + 1
    const record = {
      seq,
      type: event?.type,
      time: Number.isFinite(event?.time) ? event.time : Date.now(),
      data: event?.data ?? {},
    }
    const next = [...existing, record]
    liveEvents.set(sessionId, next)
    // session.history 的 page 上界取自列表 projections.asOfSeq。不推进它，
    // 轮询会一直停在 fixtures 的冻结序号，订阅看不到新 chunk。
    const row = rows.find((item) => item.sessionId === sessionId)
    if (row?.projections && Number.isInteger(row.projections.asOfSeq)) {
      row.projections.asOfSeq = Math.max(row.projections.asOfSeq, seq)
    }
    if (sessionLogHome) {
      const row = rows.find((item) => item.sessionId === sessionId)
      const dir = sessionDirFor(row?.cwd ?? workspacePath, sessionId, { DSH_HOME: sessionLogHome })
      mkdirSync(dir, { recursive: true })
      appendFileSync(join(dir, "session.jsonl"), JSON.stringify(record) + "\n")
    }
    return record
  }

  function promptsSeen() {
    return prompts.map((item) => ({ ...item, content: item.content.map((part) => ({ ...part })) }))
  }

  function seedSessionLogs() {
    if (!sessionLogHome) return
    for (const row of rows) {
      const dir = sessionDirFor(row.cwd, row.sessionId, { DSH_HOME: sessionLogHome })
      mkdirSync(dir, { recursive: true })
      const file = join(dir, "session.jsonl")
      const lines = [...(SESSION_EVENTS[row.sessionId] ?? []), ...(liveEvents.get(row.sessionId) ?? [])]
      writeFileSync(file, lines.map((record) => JSON.stringify(record)).join("\n") + (lines.length ? "\n" : ""))
    }
  }

  return { invoke, stream, appendLiveEvent, promptsSeen, seedSessionLogs }
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

function makeCtx({ upstreamPort, gateway, logger } = {}) {
  const registered = []
  const effects = []
  const listeners = new Map()
  const ctx = {
    logger: logger ?? { info() {}, warn() {} },
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
function openSse({ agent, proxyPort, token, path, quietMs = 500, timeoutMs = 10_000, onFrame } = {}) {
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
        if (frame) {
          frames.push(frame)
          try { onFrame?.(frame) } catch {}
        }
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

export {
  CHANGES_DIFFS,
  CHANGES_SUMMARIES,
  CREDENTIALS,
  FIXED_NOW,
  FIXTURE_CERT_DAYS_REMAINING,
  LOGIN_ACTIVITY_LABEL,
  MODEL_CATALOG,
  PROVIDER_SETTINGS,
  SESSION_ARCHIVED,
  SESSION_EVENTS,
  SESSION_LOGIN,
  SESSION_REFACTOR,
  SESSION_REPORT,
  SESSION_ROWS,
  SESSION_STOPPED,
  WORKSPACE_ROW,
  callPanelRoute,
  changesService,
  createFakeGateway,
  createWorkspaceTree,
  freePort,
  httpsAgentFor,
  loginEvents,
  loginProjections,
  makeCtx,
  oneShotFrame,
  openSse,
  parseSseBlock,
  proxyRequest,
  refactorEvents,
  reportEvents,
  startUpstream,
  stoppedEvents,
  t,
}
