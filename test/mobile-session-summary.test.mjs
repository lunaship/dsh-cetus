import assert from "node:assert/strict"
import test from "node:test"
import { mobileSessionSummary } from "../src/mobile-session-summary.js"
import { omitNullFields, optionalString } from "../src/optional-string.js"

test("optionalString 丢掉 JSON 空值与字面量 null", () => {
  assert.equal(optionalString(null), null)
  assert.equal(optionalString("  "), null)
  assert.equal(optionalString("null"), null)
  assert.equal(optionalString("NULL"), null)
  assert.equal(optionalString("undefined"), null)
  assert.equal(optionalString("standard"), "standard")
})

test("omitNullFields 不把 null 写进 JSON，避免旧 App optString 显示 null", () => {
  const json = JSON.stringify(omitNullFields({ a: 1, b: null, c: "ok" }))
  assert.equal(json.includes("b"), false)
  assert.deepEqual(JSON.parse(json), { a: 1, c: "ok" })
})

test("session.list 的 agentPreset 为 null 时不下发该键", () => {
  const summary = mobileSessionSummary({
    sessionId: "s1",
    updatedAt: 1,
    running: false,
    cwd: null,
    agentPreset: null,
    projections: { values: { title: "Reply with exactly PONG015" } },
  })
  assert.equal("agentPreset" in summary, false)
  assert.equal("cwd" in summary, false)
  assert.equal(summary.title, "Reply with exactly PONG015")
  // session.list 不带用量。首页不为此加请求，用量只在会话内显示。
  assert.equal("tokenUsage" in summary, false)
  assert.equal("stats" in summary, false)
  assert.equal(JSON.parse(JSON.stringify(summary)).agentPreset, undefined)
})

test("真实预设与 cwd 仍会下发", () => {
  const summary = mobileSessionSummary({
    sessionId: "s1",
    cwd: "/Volumes/Space/Dev/workspace",
    agentPreset: "standard",
    projections: { values: {} },
  })
  assert.equal(summary.agentPreset, "standard")
  assert.equal(summary.cwd, "/Volumes/Space/Dev/workspace")
  assert.equal(summary.title, "未命名会话")
})

test("activity / lastResult 只在有值时才下发（旧 Host 与推导失败走同一条回退）", () => {
  const bare = mobileSessionSummary({ sessionId: "s1", projections: { values: {} } })
  assert.equal("activity" in bare, false)
  assert.equal("lastResult" in bare, false)
})

test("activity / lastResult 按 extra 原样下发", () => {
  const running = mobileSessionSummary(
    { sessionId: "s1", updatedAt: 5, running: true, projections: { values: {} } },
    { activity: { kind: "tool", label: "go test ./...", step: 12 } },
  )
  assert.deepEqual(running.activity, { kind: "tool", label: "go test ./...", step: 12 })
  assert.equal("lastResult" in running, false)

  const done = mobileSessionSummary(
    { sessionId: "s2", updatedAt: 6, running: false, projections: { values: {} } },
    { lastResult: { text: "门禁全绿", files: 79 } },
  )
  assert.deepEqual(done.lastResult, { text: "门禁全绿", files: 79 })
  assert.equal("activity" in done, false)
})
