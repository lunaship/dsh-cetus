/**
 * SSE 背压：`res.write()` 返回 false 只是超过 highWaterMark，不是断线。
 * 回归：补历史时连写几十帧（或一帧上下文注入就 >16 KiB）曾被当成慢消费者直接 destroy，
 * 手机订阅一连上就断，插件看不到订阅，审批全部落到电脑网页。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { EventEmitter } from "node:events"
import { SSE_MAX_BACKLOG_BYTES, sseBacklogExceeded, writeCatchup } from "../src/stream-cursor.js"
import { handleMuxBlock } from "../src/question-bridge.js"

/** write 永远返回 false（模拟 TLS 缓冲超过水位）；backlogAfter(n) 决定第 n 次写后的积压字节数。 */
function fakeRes(backlogAfter = () => 1024) {
  const res = new EventEmitter()
  res.frames = []
  res.destroyed = false
  res.writableLength = 0
  res.write = (frame) => {
    res.frames.push(frame)
    res.writableLength = backlogAfter(res.frames.length)
    return false
  }
  res.destroy = () => { res.destroyed = true }
  return res
}

function events(from, to) {
  const out = []
  for (let seq = from; seq <= to; seq++) out.push({ seq, type: "assistant/message", time: seq, data: { seq } })
  return out
}

test("积压低于上限时，write 返回 false 也不断开，补历史全部写出", () => {
  const res = fakeRes(() => 74 * 1024)
  const conn = { res, lastSeq: 0 }
  const writers = new Set([conn])
  writeCatchup(writers, conn, { complete: true, events: events(1, 40), projections: null }, "s1", () => {})
  assert.equal(res.destroyed, false)
  assert.ok(writers.has(conn))
  assert.equal(conn.lastSeq, 40)
  assert.equal(res.frames.length, 40)
})

test("积压超过上限时暂停在已写出的最后一帧，排空后请求续补且连接不断", () => {
  let drained = false
  const res = fakeRes((n) => (!drained && n >= 3 ? SSE_MAX_BACKLOG_BYTES + 1 : 1024))
  const conn = { res, lastSeq: 0 }
  const writers = new Set([conn])
  const polled = []
  writeCatchup(writers, conn, { complete: true, events: events(1, 10), projections: null }, "s1", (id) => polled.push(id))
  assert.equal(conn.lastSeq, 3)
  assert.equal(res.frames.length, 3)
  assert.equal(res.destroyed, false)
  assert.ok(writers.has(conn))

  // 暂停期间再次补洞：不往积压里追加
  writeCatchup(writers, conn, { complete: true, events: events(4, 10), projections: null }, "s1", (id) => polled.push(id))
  assert.equal(res.frames.length, 3)
  assert.deepEqual(polled, [])

  drained = true
  res.writableLength = 0
  res.emit("drain")
  assert.deepEqual(polled, ["s1"])
  writeCatchup(writers, conn, { complete: true, events: events(4, 10), projections: null }, "s1", () => {})
  assert.equal(conn.lastSeq, 10)
})

test("实时推送：积压小不断开，积压超过上限才按慢消费者断开", () => {
  const block = (seq) => `data: ${JSON.stringify({ rpcId: "r", payload: { type: "session/event", sessionId: "s1", event: { seq, type: "x", time: seq, data: {} } } })}`
  const light = { seeded: true, lastSeq: 0, res: fakeRes(() => 32 * 1024) }
  const heavy = { seeded: true, lastSeq: 0, res: fakeRes(() => SSE_MAX_BACKLOG_BYTES + 1) }
  const rt = { sessionStreams: new Map([["s1", new Set([light, heavy])]]) }
  handleMuxBlock(block(1), rt, null, () => {})
  assert.equal(light.res.destroyed, false)
  assert.equal(light.lastSeq, 1)
  assert.equal(heavy.res.destroyed, true)
  assert.equal(rt.sessionStreams.get("s1").has(heavy), false)
})

test("sseBacklogExceeded 只看真实积压", () => {
  assert.equal(sseBacklogExceeded({ writableLength: 16 * 1024 + 1 }), false)
  assert.equal(sseBacklogExceeded({ writableLength: SSE_MAX_BACKLOG_BYTES + 1 }), true)
  assert.equal(sseBacklogExceeded(undefined), false)
})
