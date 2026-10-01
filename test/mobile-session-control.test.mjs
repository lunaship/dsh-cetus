/**
 * 会话控制路由：排队消息 / 目标 / 定时任务。参数由插件拼装，CAS 冲突映射为 409。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { bindLocalRpcRuntime, unbindLocalRpcRuntime, LocalRpcError } from "../src/local-rpc.js"
import {
  controlErrorStatus,
  goalRefFrom,
  handleMobileSessionControlApi,
  queueItemsFromInbox,
  scheduleExpectedRecord,
} from "../src/mobile-session-control.js"

function harness(invoke) {
  const calls = []
  bindLocalRpcRuntime({
    invoke: async (req) => {
      calls.push(req)
      return invoke(req)
    },
  })
  const out = { status: 0, body: null }
  const deps = {
    json: (_res, status, body) => { out.status = status; out.body = body },
    readAuthorizedJson: async (req) => req.body,
    requireJsonWrite: () => true,
    runMobileDeviceMutation: async (_rt, _state, _device, fn) => fn(),
    mobileMutationWasRevoked: () => false,
    respondDeviceRevoked: () => { out.status = 401 },
  }
  const run = (method, path, body) => handleMobileSessionControlApi(
    { method, url: path, body }, {}, 0, {}, { deviceId: "d1" }, path.split("?")[0], {}, deps,
  )
  return { calls, out, run }
}

test("queueItemsFromInbox：next-turn 为排队，next-step 用户来源为引导，其余为上下文；坏条目跳过", () => {
  const items = queueItemsFromInbox({
    "next-turn": [{ id: "a", content: [{ type: "text", text: "hi" }, { type: "image" }], source: { kind: "user" } }],
    "next-step": [
      { id: "b", content: [{ type: "text", text: "go" }], source: { kind: "user" } },
      { id: "c", content: [], source: { kind: "tool" } },
      { id: 3, content: [] },
    ],
  })
  assert.deepEqual(items, [
    { id: "a", placement: "queued", text: "hi", images: 1 },
    { id: "b", placement: "steering", text: "go", images: 0 },
    { id: "c", placement: "context", text: "", images: 0 },
  ])
  assert.deepEqual(queueItemsFromInbox(null), [])
  assert.deepEqual(queueItemsFromInbox({ "next-turn": "x" }), [])
})

test("排队编辑：插件拼 content，空文本 400", async () => {
  const h = harness(async () => ({}))
  try {
    assert.equal(await h.run("POST", "/dsh-link/mobile/sessions/s1/queue/q1", { action: "edit", text: "new" }), true)
    assert.equal(h.out.status, 200)
    assert.deepEqual(h.calls[0], {
      namespace: "session",
      method: "updateQueue",
      args: { request: { sessionId: "s1", itemId: "q1", action: { kind: "edit", content: [{ type: "text", text: "new" }] } } },
    })
    await h.run("POST", "/dsh-link/mobile/sessions/s1/queue/q1", { action: "edit", text: "  " })
    assert.equal(h.out.status, 400)
    await h.run("POST", "/dsh-link/mobile/sessions/s1/queue/q1", { action: "drop" })
    assert.equal(h.out.status, 400)
    await h.run("POST", "/dsh-link/mobile/sessions/s1/queue/q1", { action: "steer" })
    assert.deepEqual(h.calls.at(-1).args.request.action, { kind: "steer" })
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("目标暂停 / 编辑：CAS 引用原样下发，返回新 revision；缺 ref 400", async () => {
  const h = harness(async () => ({ id: "g1", revision: 4 }))
  try {
    await h.run("POST", "/dsh-link/mobile/sessions/s1/goal/pause", { ref: { id: "g1", revision: 3 } })
    assert.equal(h.out.status, 200)
    assert.deepEqual(h.out.body.ref, { id: "g1", revision: 4 })
    assert.deepEqual(h.calls[0], { namespace: "goals", method: "pause", args: { agentId: "s1", ref: { id: "g1", revision: 3 } } })
    await h.run("POST", "/dsh-link/mobile/sessions/s1/goal/edit", { ref: { id: "g1", revision: 4 }, objective: " ship ", maxGoalRounds: 5 })
    assert.deepEqual(h.calls[1].args, { agentId: "s1", ref: { id: "g1", revision: 4 }, request: { objective: "ship", maxGoalRounds: 5 } })
    await h.run("POST", "/dsh-link/mobile/sessions/s1/goal/clear", { ref: { id: "g1" } })
    assert.equal(h.out.status, 400)
    await h.run("POST", "/dsh-link/mobile/sessions/s1/goal/edit", { ref: { id: "g1", revision: 4 } })
    assert.equal(h.out.status, 400)
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("目标 revision 过期：DSH 冲突映射为 409 并带 code", async () => {
  const h = harness(async () => { throw Object.assign(new Error("goal revision changed"), { code: "goal/revision-conflict" }) })
  try {
    await h.run("POST", "/dsh-link/mobile/sessions/s1/goal/resume", { ref: { id: "g1", revision: 2 } })
    assert.equal(h.out.status, 409)
    assert.equal(h.out.body.code, "goal/revision-conflict")
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("定时任务修改：expected 只保留原始记录字段，且 id 必须一致", async () => {
  const h = harness(async () => ({ ok: true }))
  try {
    await h.run("PUT", "/dsh-link/mobile/sessions/s1/schedules/t1", {
      expected: { id: "t1", kind: "every", everySeconds: 60, title: "x", nextRunAt: 123, sessionId: "s1" },
      title: "y",
    })
    assert.equal(h.out.status, 200)
    assert.deepEqual(h.calls[0].args.request, {
      sessionId: "s1", id: "t1", expected: { id: "t1", kind: "every", everySeconds: 60, title: "x" }, title: "y",
    })
    await h.run("PUT", "/dsh-link/mobile/sessions/s1/schedules/t1", { expected: { id: "other" } })
    assert.equal(h.out.status, 400)
    await h.run("DELETE", "/dsh-link/mobile/sessions/s1/schedules/t1")
    assert.deepEqual(h.calls.at(-1), { namespace: "schedule", method: "delete", args: { request: { sessionId: "s1", id: "t1" } } })
    await h.run("GET", "/dsh-link/mobile/sessions/s1/schedules/t1/history?limit=500")
    assert.equal(h.calls.at(-1).args.request.limit, 100)
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("辅助：goalRefFrom / scheduleExpectedRecord / controlErrorStatus", () => {
  assert.deepEqual(goalRefFrom({ ref: { id: " g ", revision: 1 } }), { id: "g", revision: 1 })
  assert.equal(goalRefFrom({ ref: { id: "g", revision: 0 } }), null)
  assert.equal(scheduleExpectedRecord("x"), null)
  assert.equal(controlErrorStatus(new LocalRpcError("m", { code: "schedule/not-found" })), 404)
  assert.equal(controlErrorStatus(new LocalRpcError("m", { code: "internal" })), 502)
})

test("未匹配路径返回 false，交给后续路由", async () => {
  const h = harness(async () => ({}))
  try {
    assert.equal(await h.run("GET", "/dsh-link/mobile/sessions/s1/other"), false)
  } finally {
    unbindLocalRpcRuntime()
  }
})
