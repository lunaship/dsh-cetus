/**
 * 会话列表接口的活动字段接线（方案阶段 2 的护栏：只算最近 20 个、按
 * sessionId+updatedAt 缓存、并发上限 4、单个失败只丢该字段）。
 *
 * 用 deps 注入假的 readEvents / changesService，不碰网络也不碰 runtime。
 * 推导本身（deriveActivity / deriveLastResult）在 mobile-session-activity.test.mjs 里单独测。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { attachSessionActivity } from "../src/mobile-api.js"

function ev(seq, type, data = {}, time = seq * 100) {
  return { event: { seq, time, type, data } }
}

const runningEvents = [
  ev(2, "tool/call", { callId: "c1", name: "shell", arguments: { command: "go test ./..." }, step: 12 }),
]
const doneEvents = [
  ev(3, "assistant/message", { message: { content: [{ type: "text", text: "门禁全绿" }] } }),
]

function rows(n, { running = true } = {}) {
  return Array.from({ length: n }, (_, i) => ({
    sessionId: `s${i}`,
    updatedAt: 1000 + i,
    running,
    title: `会话 ${i}`,
  }))
}

test("running 会话只补 activity，非 running 只补 lastResult", async () => {
  const rt = { sessionActivityCache: new Map() }
  const sessions = [
    { sessionId: "run", updatedAt: 1, running: true },
    { sessionId: "done", updatedAt: 2, running: false },
  ]
  await attachSessionActivity(rt, 0, sessions, {
    readEvents: async (id) => (id === "run" ? runningEvents : doneEvents),
    changesService: null,
  })
  assert.equal(sessions[0].activity.label, "go test ./...")
  assert.equal(sessions[0].lastResult, undefined)
  assert.equal(sessions[1].lastResult.text, "门禁全绿")
  assert.equal(sessions[1].activity, undefined)
})

test("结果一句话取到改动摘要时带上 files/added/deleted", async () => {
  const rt = { sessionActivityCache: new Map() }
  const sessions = [{ sessionId: "done", updatedAt: 2, running: false }]
  await attachSessionActivity(rt, 0, sessions, {
    readEvents: async () => [ev(1, "workspace/changes", { turn: 1 }), ...doneEvents],
    changesService: {
      summary: () => ({ turn: 1, total: 79, added: 1200, deleted: 300, files: [{ path: "a.js" }] }),
    },
  })
  assert.deepEqual(sessions[0].lastResult, { text: "门禁全绿", files: 79, added: 1200, deleted: 300 })
})

test("只算最近 20 个会话", async () => {
  const rt = { sessionActivityCache: new Map() }
  const sessions = rows(25)
  const asked = []
  await attachSessionActivity(rt, 0, sessions, {
    readEvents: async (id) => { asked.push(id); return runningEvents },
    changesService: null,
  })
  assert.equal(asked.length, 20)
  assert.equal(sessions[20].activity, undefined)
  assert.equal(sessions[19].activity.label, "go test ./...")
})

test("按 sessionId + updatedAt 缓存：已结束的会话 updatedAt 不变不重复拉历史", async () => {
  const rt = { sessionActivityCache: new Map() }
  let reads = 0
  const deps = {
    readEvents: async () => { reads += 1; return doneEvents },
    changesService: null,
  }
  const first = rows(2, { running: false })
  await attachSessionActivity(rt, 0, first, deps)
  assert.equal(reads, 2)

  // 同一批（updatedAt 未变）再拉一次：命中缓存，不再读历史
  const again = rows(2, { running: false })
  await attachSessionActivity(rt, 0, again, deps)
  assert.equal(reads, 2)
  assert.equal(again[0].lastResult.text, "门禁全绿")

  // updatedAt 变了（有新事件）才重算
  const changed = rows(2, { running: false }).map((r) => ({ ...r, updatedAt: r.updatedAt + 10 }))
  await attachSessionActivity(rt, 0, changed, deps)
  assert.equal(reads, 4)
})

test("进行中的行每次都重读历史（审批不改 updatedAt，缓存会让首页漏掉「等你处理」）", async () => {
  const rt = { sessionActivityCache: new Map() }
  let reads = 0
  await attachSessionActivity(rt, 0, [{ sessionId: "s1", updatedAt: 5, running: true }], {
    readEvents: async () => { reads += 1; return runningEvents },
    changesService: null,
  })
  assert.equal(reads, 1)
  // 同 key 再拉一次：running 行必须重读，否则新来的审批事件永远看不到
  await attachSessionActivity(rt, 0, [{ sessionId: "s1", updatedAt: 5, running: true }], {
    readEvents: async () => { reads += 1; return askedEvents },
    changesService: null,
  })
  assert.equal(reads, 2)

  // 已结束的行仍走缓存键（running 与已结束不串味）
  const done = [{ sessionId: "s1", updatedAt: 5, running: false }]
  await attachSessionActivity(rt, 0, done, {
    readEvents: async () => doneEvents,
    changesService: null,
  })
  assert.equal(done[0].lastResult.text, "门禁全绿")
  assert.equal(done[0].activity, undefined)
})

test("单个会话读历史失败只跳过该字段，不影响其他行", async () => {
  const rt = { sessionActivityCache: new Map() }
  const sessions = rows(3)
  await attachSessionActivity(rt, 0, sessions, {
    readEvents: async (id) => (id === "s1" ? null : runningEvents),
    changesService: null,
  })
  assert.equal(sessions[0].activity.label, "go test ./...")
  assert.equal(sessions[1].activity, undefined)
  assert.equal(sessions[2].activity.label, "go test ./...")
  // 失败的行不写缓存：下一次还会再试
  assert.equal(rt.sessionActivityCache.has("s1:1001:r"), false)
})

test("推导不出结果时不写字段（App 走「已完成」回退）", async () => {
  const rt = { sessionActivityCache: new Map() }
  const sessions = [{ sessionId: "s1", updatedAt: 1, running: false }]
  await attachSessionActivity(rt, 0, sessions, {
    readEvents: async () => [],
    changesService: null,
  })
  assert.equal("lastResult" in sessions[0], false)
})

test("并发上限 4", async () => {
  const rt = { sessionActivityCache: new Map() }
  let inFlight = 0
  let peak = 0
  await attachSessionActivity(rt, 0, rows(12), {
    readEvents: async () => {
      inFlight += 1
      peak = Math.max(peak, inFlight)
      await new Promise((resolve) => setTimeout(resolve, 5))
      inFlight -= 1
      return runningEvents
    },
    changesService: null,
  })
  assert.equal(peak, 4)
})

test("没有 sessionId 的行与空列表都不报错", async () => {
  const rt = { sessionActivityCache: new Map() }
  await attachSessionActivity(rt, 0, [], { readEvents: async () => runningEvents, changesService: null })
  const sessions = [{ title: "没有 id" }, null]
  await attachSessionActivity(rt, 0, sessions, { readEvents: async () => runningEvents, changesService: null })
  assert.equal(sessions[0].activity, undefined)
})

test("rt 没有缓存 Map 时也能跑（不写缓存，不报错）", async () => {
  const sessions = rows(1)
  await attachSessionActivity({}, 0, sessions, {
    readEvents: async () => runningEvents,
    changesService: null,
  })
  assert.equal(sessions[0].activity.label, "go test ./...")
})

test("已结束的会话：stoppedReason 跟着摘要一起下发（首页「最近」用它区分已完成 / 已停止）", async () => {
  const { attachSessionActivity } = await import("../src/mobile-api.js")
  const { mobileSessionSummary } = await import("../src/mobile-session-summary.js")
  const sessions = [{ sessionId: "s1", updatedAt: 5, running: false, status: "idle" }]
  await attachSessionActivity({}, null, sessions, {
    readEvents: async () => [
      { event: { seq: 1, time: 1, type: "user/message", data: { content: [{ text: "跑测试" }] } } },
      { event: { seq: 2, time: 2, type: "turn/end", data: { reason: { kind: "interrupted" } } } },
    ],
    changesService: null,
  })
  assert.equal(sessions[0].stoppedReason, "interrupted")
  // 生产路径：先 summarizeSession 出摘要，attachSessionActivity 再把字段挂到**摘要对象**上
  // （所以这里要按 extra 契约验证，而不是拿原始行去问摘要）
  assert.equal(mobileSessionSummary({ sessionId: "s1" }, { stoppedReason: "interrupted" }).stoppedReason, "interrupted")
})

test("正常完成的会话不下发 stoppedReason（回退成「已完成」）", async () => {
  const { attachSessionActivity } = await import("../src/mobile-api.js")
  const sessions = [{ sessionId: "s2", updatedAt: 6, running: false, status: "idle" }]
  await attachSessionActivity({}, null, sessions, {
    readEvents: async () => [
      { event: { seq: 3, time: 3, type: "turn/end", data: { reason: { kind: "completed" } } } },
    ],
    changesService: null,
  })
  assert.equal(sessions[0].stoppedReason, undefined)
})

// 2026-09-29 真机走查：沙箱升级这类审批不经过插件的 approval/request 钩子（探针实测），
// 于是等审批的会话被首页错归到「进行中」。改为按历史推导：approval/asked 没配上 decided。
const askedEvents = [
  ev(2, "tool/call", { callId: "c1", name: "shell", arguments: { command: "echo hi > /usr/local/x" }, step: 1 }),
  ev(3, "approval/asked", { id: "ap-1", toolName: "shell" }),
]

test("历史里有没人回答的审批 → 行上带 awaitingInput（首页「等你处理」）", async () => {
  const rt = { sessionActivityCache: new Map() }
  const sessions = [{ sessionId: "wait", updatedAt: 5, running: true }]
  await attachSessionActivity(rt, 0, sessions, {
    readEvents: async () => askedEvents,
    changesService: null,
  })
  assert.equal(sessions[0].awaitingInput, true)
  // running 行每次都重读历史：审批不改 updatedAt，靠缓存会一直命中旧的「没在等」那行
  const again = [{ sessionId: "wait", updatedAt: 5, running: true }]
  await attachSessionActivity(rt, 0, again, { readEvents: async () => askedEvents, changesService: null })
  assert.equal(again[0].awaitingInput, true)
})

test("审批已被回答 → 不带 awaitingInput", async () => {
  const rt = { sessionActivityCache: new Map() }
  const sessions = [{ sessionId: "answered", updatedAt: 6, running: true }]
  await attachSessionActivity(rt, 0, sessions, {
    readEvents: async () => [...askedEvents, ev(4, "approval/decided", { id: "ap-1", outcome: "allowed-once" })],
    changesService: null,
  })
  assert.equal(sessions[0].awaitingInput, undefined)
})

test("非 running 的会话里残留的未答审批不算等人（上一轮已作废）", async () => {
  const rt = { sessionActivityCache: new Map() }
  const sessions = [{ sessionId: "stopped", updatedAt: 7, running: false }]
  await attachSessionActivity(rt, 0, sessions, {
    readEvents: async () => [...askedEvents, ev(5, "turn/end", { reason: { kind: "interrupted" } })],
    changesService: null,
  })
  assert.equal(sessions[0].stoppedReason, "interrupted")
  assert.equal(sessions[0].awaitingInput, undefined)
})

test("running 的会话即便挂着上一轮的 interrupted，也要算在等人（真机实测的坑）", async () => {
  const rt = { sessionActivityCache: new Map() }
  const sessions = [{ sessionId: "running-stale", updatedAt: 8, running: true }]
  await attachSessionActivity(rt, 0, sessions, {
    readEvents: async () => [...askedEvents, ev(5, "turn/end", { reason: { kind: "interrupted" } })],
    changesService: null,
  })
  assert.equal(sessions[0].stoppedReason, "interrupted")
  assert.equal(sessions[0].awaitingInput, true)
})
