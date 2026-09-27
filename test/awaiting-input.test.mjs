import assert from "node:assert/strict"
import test from "node:test"
import { createAwaitingInput } from "../src/awaiting-input.js"
import { mobileSessionSummary } from "../src/mobile-session-summary.js"

test("审批 / 问题未结束时会话处于等待确认，结束后释放", async () => {
  const awaiting = createAwaitingInput()
  let resolve
  const pending = awaiting.track("s1", () => new Promise((r) => { resolve = r }))
  assert.equal(awaiting.has("s1"), true)
  assert.equal(awaiting.has("s2"), false)
  resolve("allowed-once")
  assert.equal(await pending, "allowed-once")
  assert.equal(awaiting.has("s1"), false)
})

test("同一会话多个请求按计数释放；拒绝和同步抛错也会释放", async () => {
  const awaiting = createAwaitingInput()
  let r1, j2
  const a = awaiting.track("s1", () => new Promise((r) => { r1 = r }))
  const b = awaiting.track("s1", () => new Promise((_, j) => { j2 = j }))
  r1("ok")
  await a
  assert.equal(awaiting.has("s1"), true)
  j2(new Error("aborted"))
  await assert.rejects(b)
  assert.equal(awaiting.has("s1"), false)
  assert.throws(() => awaiting.track("s1", () => { throw new Error("boom") }))
  assert.equal(awaiting.has("s1"), false)
})

test("没有 sessionId 时直接透传，不记账", async () => {
  const awaiting = createAwaitingInput()
  assert.equal(await awaiting.track(undefined, () => "next"), "next")
})

test("会话摘要只在等待确认时下发 awaitingInput", () => {
  const base = { sessionId: "s1", updatedAt: 1, running: true, projections: { values: { title: "t" } } }
  assert.equal("awaitingInput" in mobileSessionSummary(base), false)
  assert.equal("awaitingInput" in mobileSessionSummary(base, { awaitingInput: false }), false)
  assert.equal(mobileSessionSummary(base, { awaitingInput: true }).awaitingInput, true)
})
