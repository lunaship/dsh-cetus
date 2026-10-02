import assert from "node:assert/strict"
import test from "node:test"
import {
  classifyHostSession,
  createHostEventHub,
  diffHostSessions,
  hostEventFrame,
  terminalHostState,
} from "../src/host-events.js"

function row(overrides) {
  return {
    sessionId: "s1",
    title: "修登录",
    origin: "user",
    running: true,
    pendingApproval: false,
    pendingQuestion: false,
    awaitingInput: false,
    stoppedReason: null,
    ...overrides,
  }
}

test("差分只报状态变化，不带工具参数", () => {
  const running = classifyHostSession(row({ arguments: "rm -rf /", command: "secret" }))
  assert.deepEqual(Object.keys(running).sort(), ["origin", "sessionId", "state", "title"])
  assert.equal(running.state, "running")
  const approval = classifyHostSession(row({ pendingApproval: true, pendingQuestion: true }))
  assert.equal(approval.state, "awaitingApproval")
  const question = classifyHostSession(row({ pendingQuestion: true }))
  assert.equal(question.state, "awaitingInput")
  const done = classifyHostSession(row({ running: false }))
  assert.equal(done.state, "completed")
  assert.equal(terminalHostState("error"), "failed")
  assert.equal(terminalHostState("interrupted"), "stopped")
  assert.equal(classifyHostSession(row({ origin: "schedule" })).origin, "schedule")
  assert.equal(classifyHostSession(row({ origin: "fork" })).origin, "user")

  const prev = new Map([["s1", running]])
  assert.equal(diffHostSessions(prev, prev).length, 0)
  const next = new Map([["s1", approval]])
  const changed = diffHostSessions(prev, next)
  assert.equal(changed.length, 1)
  assert.equal(changed[0].state, "awaitingApproval")
  assert.equal("arguments" in changed[0], false)
})

test("第一次快照不回放已经结束的会话", () => {
  const next = new Map([
    ["live", classifyHostSession(row({ sessionId: "live" }))],
    ["old", classifyHostSession(row({ sessionId: "old", running: false }))],
  ])
  const initial = diffHostSessions(new Map(), next, { initial: true })
  assert.deepEqual(initial.map((event) => event.sessionId), ["live"])
  const later = diffHostSessions(new Map(), next, { initial: false })
  assert.equal(later.length, 2)
})

test("没有订阅者时不轮询，断线后续传", async () => {
  const calls = []
  let rows = [row()]
  const timers = []
  const hub = createHostEventHub({
    listSessions: async () => {
      calls.push("list")
      return rows
    },
    terminalReason: async () => "error",
    setIntervalFn: (fn, ms) => {
      timers.push({ fn, ms })
      return { id: timers.length }
    },
    clearIntervalFn: (id) => { timers.push({ cleared: id }) },
    bufferLimit: 1,
  })
  const missed = await hub.poll()
  assert.equal(missed.polled, false)
  assert.equal(calls.length, 0)
  assert.equal(hub.polling, false)

  const chunks = []
  const res = {
    write(frame) { chunks.push(frame); return true },
    destroy() {},
  }
  const unsubscribe = hub.subscribe(res, null)
  assert.equal(hub.subscriberCount, 1)
  assert.equal(hub.polling, true)
  assert.deepEqual(timers.map((timer) => timer.ms), [5_000, 25_000])
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(calls.length, 1)
  assert.match(chunks[0], /"state":"running"/)
  assert.match(chunks[0], /"type":"session\/state"/)
  assert.equal(chunks[0].includes("arguments"), false)
  const firstId = chunks[0].match(/^id: (\d+)/)[1]

  chunks.length = 0
  rows = [row({ running: false })]
  await hub.poll()
  assert.match(chunks[0], /"state":"failed"/)

  const replayed = []
  const res2 = { write(frame) { replayed.push(frame); return true }, destroy() {} }
  const unsubscribe2 = hub.subscribe(res2, firstId)
  assert.equal(replayed.length, 1)
  assert.match(replayed[0], /"state":"failed"/)
  assert.match(replayed[0], new RegExp(`^id: ${Number(firstId) + 1}`))

  rows = [row({ title: "又开始了" })]
  await hub.poll()
  const expired = []
  const res3 = { write(frame) { expired.push(frame); return true }, destroy() {} }
  const unsubscribe3 = hub.subscribe(res3, "1")
  assert.match(expired.join(""), /resync-required/)

  unsubscribe()
  unsubscribe2()
  unsubscribe3()
  assert.equal(hub.subscriberCount, 0)
  assert.equal(hub.polling, false)
  const before = calls.length
  await hub.poll()
  assert.equal(calls.length, before)
})

test("事件帧带 seq，心跳不带会话内容", () => {
  const frame = hostEventFrame({
    seq: 4,
    sessionId: "s1",
    state: "running",
    title: "修登录",
    origin: "user",
  })
  assert.match(frame, /^id: 4\nevent: session\/state\n/)
  const body = JSON.parse(frame.split("data: ")[1])
  assert.deepEqual(body, {
    type: "session/state",
    sessionId: "s1",
    state: "running",
    title: "修登录",
    origin: "user",
    seq: 4,
  })
})
