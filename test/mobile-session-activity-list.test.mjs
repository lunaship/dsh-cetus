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

test("按 sessionId + updatedAt 缓存：updatedAt 不变不重复拉历史", async () => {
  const rt = { sessionActivityCache: new Map() }
  let reads = 0
  const deps = {
    readEvents: async () => { reads += 1; return runningEvents },
    changesService: null,
  }
  const first = rows(2)
  await attachSessionActivity(rt, 0, first, deps)
  assert.equal(reads, 2)

  // 同一批（updatedAt 未变）再拉一次：命中缓存，不再读历史
  const again = rows(2)
  await attachSessionActivity(rt, 0, again, deps)
  assert.equal(reads, 2)
  assert.equal(again[0].activity.label, "go test ./...")

  // updatedAt 变了（有新事件）才重算
  const changed = rows(2).map((r) => ({ ...r, updatedAt: r.updatedAt + 10 }))
  await attachSessionActivity(rt, 0, changed, deps)
  assert.equal(reads, 4)
})

test("running 与已结束用不同的缓存键，同一个会话切换状态不会串味", async () => {
  const rt = { sessionActivityCache: new Map() }
  const deps = { readEvents: async () => runningEvents, changesService: null }
  const running = [{ sessionId: "s1", updatedAt: 5, running: true }]
  await attachSessionActivity(rt, 0, running, deps)
  assert.equal(running[0].activity.label, "go test ./...")

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
