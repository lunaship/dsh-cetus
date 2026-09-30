import assert from "node:assert/strict"
import test from "node:test"
import { bindLocalRpcRuntime, unbindLocalRpcRuntime } from "../src/local-rpc.js"
import { MOBILE_SESSION_SAFE_PRESET, applyMobileSessionSafety, handleMobileApi } from "../src/mobile-api.js"

test("手机新会话在返回前固定为工作区写入加逐次确认", () => {
  const events = []
  applyMobileSessionSafety({
    append(type, data) { events.push({ type, data }) },
  })

  assert.equal(MOBILE_SESSION_SAFE_PRESET, "workspace-write")
  assert.deepEqual(events, [
    { type: "permission/preset", data: { preset: "workspace-write" } },
    { type: "approval/policy", data: { policy: "ask" } },
    { type: "sandbox/mode", data: { mode: "workspace-write" } },
  ])
})

test("没有可写会话时拒绝继续创建流程", () => {
  assert.throws(() => applyMobileSessionSafety(), /可写入的会话实例/)
})

test("创建接口在返回 sessionId 前写入固定安全策略", async () => {
  const writes = []
  let response
  bindLocalRpcRuntime({
    invoke: async ({ namespace, method }) => {
      assert.equal(namespace, "session")
      assert.equal(method, "create")
      return { sessionId: "session-mobile-safe" }
    },
  })
  try {
    await handleMobileApi(
      { method: "POST", url: "/dsh-link/mobile/sessions", headers: {} },
      {},
      0,
      {},
      "",
      { deviceId: "device-mobile-safe" },
      "/dsh-link/mobile/sessions",
      {},
      {},
      null,
      {
        json: (_res, status, body) => { response = { status, body } },
        requireJsonWrite: () => true,
        readAuthorizedJson: async () => ({}),
        validateSessionCreateWorkspace: () => ({ ok: true }),
        runMobileDeviceMutation: async (_rt, _state, _device, operation) => operation(),
        mobileMutationWasRevoked: () => false,
        respondDeviceRevoked: () => assert.fail("不应被撤销"),
        isDeviceAuthorized: () => true,
        isDeviceSubscribedToSession: () => true,
        revokeDeviceEntry: () => assert.fail("不应吊销设备"),
        filterSettingsPatch: () => ({ ok: true }),
        publicDevice: () => ({}),
        remoteForDevice: () => null,
        applyNewSessionSafety: async (sessionId) => {
          assert.equal(sessionId, "session-mobile-safe")
          applyMobileSessionSafety({ append(type, data) { writes.push({ type, data }) } })
        },
      },
    )
  } finally {
    unbindLocalRpcRuntime()
  }
  assert.deepEqual(response, {
    status: 201,
    body: { version: 1, sessionId: "session-mobile-safe" },
  })
  assert.deepEqual(writes.map(({ type }) => type), [
    "permission/preset",
    "approval/policy",
    "sandbox/mode",
  ])
})
