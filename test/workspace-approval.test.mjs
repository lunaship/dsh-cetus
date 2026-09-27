import { test } from "node:test"
import assert from "node:assert/strict"
import { createWorkspaceApprovalQueue } from "../src/workspace-approval.js"

test("同一设备同一路径复用未过期的批准请求", () => {
  let clock = 1_000
  let n = 0
  const queue = createWorkspaceApprovalQueue({
    ttlMs: 50,
    now: () => clock,
    createId: () => `req-${++n}`,
  })
  const first = queue.submit({ deviceId: "dev-a", deviceName: "手机", path: "/tmp/proj" })
  clock = 1_020
  const again = queue.submit({ deviceId: "dev-a", deviceName: "手机", path: "/tmp/proj" })
  assert.equal(again.requestId, first.requestId)
  assert.equal(queue.list().length, 1)
  clock = 1_050
  assert.equal(queue.get(first.requestId), null)
  const fresh = queue.submit({ deviceId: "dev-a", deviceName: "手机", path: "/tmp/proj" })
  assert.notEqual(fresh.requestId, first.requestId)
})

test("吊销设备会丢掉它尚未批准的工作区", () => {
  const queue = createWorkspaceApprovalQueue({ createId: (() => {
    let n = 0
    return () => `req-${++n}`
  })() })
  queue.submit({ deviceId: "dev-a", deviceName: "甲", path: "/tmp/a" })
  queue.submit({ deviceId: "dev-b", deviceName: "乙", path: "/tmp/b" })
  queue.dropDevice("dev-a")
  const left = queue.list()
  assert.deepEqual(left.map((item) => item.deviceId), ["dev-b"])
})

test("队列满时丢掉最早的其他请求", () => {
  let n = 0
  let clock = 10
  const queue = createWorkspaceApprovalQueue({
    max: 2,
    now: () => clock,
    createId: () => `req-${++n}`,
  })
  queue.submit({ deviceId: "dev-a", deviceName: "甲", path: "/tmp/1" })
  clock = 11
  queue.submit({ deviceId: "dev-a", deviceName: "甲", path: "/tmp/2" })
  clock = 12
  const third = queue.submit({ deviceId: "dev-a", deviceName: "甲", path: "/tmp/3" })
  const paths = queue.list().map((item) => item.path)
  assert.deepEqual(paths, ["/tmp/2", "/tmp/3"])
  assert.equal(third.path, "/tmp/3")
})
