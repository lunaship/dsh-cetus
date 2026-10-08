import assert from "node:assert/strict"
import test from "node:test"
import { createDecipheriv } from "node:crypto"
import { readFileSync } from "node:fs"
import {
  contentCiphertext,
  createPushSink,
  publicPush,
  validatePushRegistration,
} from "../src/push-sink.js"

const vector = JSON.parse(readFileSync(new URL("../testdata/push/content/dlpush-v1-seal-open.json", import.meta.url), "utf8")).cases[0]

function registration(overrides = {}) {
  return {
    gateway: "https://push.example",
    kid: "kid-1",
    sealed: { v: 1, kid: "kid-1", enc: "encpayload", ct: "ctpayload" },
    k: vector.key,
    prefs: { approval: true, question: true, completed: true, failed: true },
    ...overrides,
  }
}

function harness({ foreground = false, statuses = [200], onResponse = null } = {}) {
  const state = {
    devices: [
      { deviceId: vector.deviceId, push: registration() },
      { deviceId: "other-device", push: registration() },
    ],
  }
  const calls = []
  const logs = []
  let clock = 1_728_000_000_000
  let authorized = true
  const sink = createPushSink({
    state,
    stateFile: "memory",
    saveState() {},
    isDeviceAuthorized: (_state, device) => authorized && device.deviceId === vector.deviceId,
    hasForegroundSse: (id) => foreground && id === vector.deviceId,
    logger: { info(text) { logs.push(text) } },
    now: () => clock,
    sleep: async () => {},
    random: () => Buffer.from(vector.nonce, "hex"),
    transport: async (_url, payload) => {
      calls.push(payload)
      const status = statuses[Math.min(calls.length, statuses.length) - 1]
      onResponse?.()
      return { status }
    },
  })
  return { state, calls, logs, sink, advance(ms) { clock += ms }, revoke() { authorized = false } }
}

function openPayload(payload) {
  const raw = Buffer.from(payload.ct, "base64")
  const decipher = createDecipheriv("aes-256-gcm", Buffer.from(vector.key, "hex"), raw.subarray(0, 12))
  decipher.setAAD(Buffer.from("dlpush/1 content|" + vector.deviceId))
  decipher.setAuthTag(raw.subarray(raw.length - 16))
  return JSON.parse(Buffer.concat([decipher.update(raw.subarray(12, raw.length - 16)), decipher.final()]).toString("utf8"))
}

test("内容向量与网关生成的密文一致", () => {
  const out = contentCiphertext(vector.key, vector.deviceId, Buffer.from(vector.plaintext_utf8), Buffer.from(vector.nonce, "hex"))
  assert.equal(out, vector.ct)
})

test("注册只接受 HTTPS 对象封装", () => {
  const good = registration()
  assert.equal(validatePushRegistration(good), null)
  assert.match(validatePushRegistration({ ...good, gateway: "http://push.example" }), /HTTPS/)
  assert.match(validatePushRegistration({ ...good, sealed: JSON.stringify(good.sealed) }), /对象/)
})

test("前台 SSE 抑制，后台才推送且不带工具正文", async () => {
  const active = harness({ foreground: true })
  const suppressed = await active.sink.notify({ sessionId: "sess-1", state: "awaitingApproval", title: "Need approval" })
  assert.equal(suppressed[0].reason, "foreground")
  assert.equal(active.calls.length, 0)

  const idle = harness()
  await idle.sink.notify({ sessionId: "sess-1", state: "awaitingApproval", title: "Need approval" })
  assert.equal(idle.calls.length, 1)
  const opened = openPayload(idle.calls[0])
  assert.equal(opened.type, "approval")
  assert.equal("tool" in opened, false)
  assert.equal(idle.calls[0].priority, "high")
  const logged = idle.logs.join(String.fromCharCode(10))
  assert.equal(logged.includes(vector.key), false)
  assert.equal(logged.includes(idle.calls[0].ct), false)
})

test("完成类 30 秒内合并，失败类仍独立", async () => {
  const box = harness()
  await box.sink.notify({ sessionId: "sess-1", state: "completed", title: "Done" })
  const merged = await box.sink.notify({ sessionId: "sess-1", state: "stopped", title: "Done again" })
  assert.equal(merged[0].reason, "collapsed")
  box.advance(30_000)
  const failed = await box.sink.notify({ sessionId: "sess-1", state: "failed", title: "Failed" })
  assert.equal(failed[0].sent, true)
  assert.equal(openPayload(box.calls.at(-1)).type, "failed")
})

test("410 只清理命中的设备，429 与网络错误最多四次", async () => {
  const expired = harness({ statuses: [410] })
  const result = await expired.sink.notify({ sessionId: "sess-1", state: "awaitingInput", title: "Question" })
  assert.equal(result[0].reason, "expired")
  assert.equal(expired.state.devices[0].push, undefined)
  assert.ok(expired.state.devices[1].push)

  const limited = harness({ statuses: [429, 429, 429, 429] })
  assert.equal((await limited.sink.notify({ sessionId: "sess-1", state: "failed", title: "Failed" }))[0].reason, "limited")
  assert.equal(limited.calls.length, 4)

  const network = harness()
  let attempts = 0
  network.sink = createPushSink({
    state: network.state,
    stateFile: "memory",
    saveState() {},
    isDeviceAuthorized: (_state, device) => device.deviceId === vector.deviceId,
    hasForegroundSse: () => false,
    now: () => 1_728_000_000_000,
    sleep: async () => {},
    transport: async () => { attempts += 1; throw new Error("down") },
  })
  assert.equal((await network.sink.notify({ sessionId: "sess-1", state: "failed", title: "Failed" }))[0].reason, "network")
  assert.equal(attempts, 4)
})

test("吊销后不再重试，公开状态不带密钥", async () => {
  const box = harness({ statuses: [429, 200], onResponse() { box.revoke() } })
  const pending = box.sink.notify({ sessionId: "sess-1", state: "awaitingApproval", title: "Need approval" })
  assert.equal((await pending)[0].reason, "revoked")
  assert.equal(box.calls.length, 1)
  box.sink.dropDevice(box.state.devices[0])
  assert.equal(box.state.devices[0].push, undefined)
  assert.deepEqual(publicPush(registration()), { enabled: true })
  assert.equal(JSON.stringify(publicPush(registration())).includes(vector.key), false)
})

test("单台设备抛错不阻塞其他设备投递", async () => {
  // RFC §16.3.4：某设备失败不阻塞其他设备，也不影响任务运行。
  // 修复前 notify() 用 Promise.all 且 send() 内会抛（构造密文/写 state），
  // 一台坏设备会让整批通知一起失败。
  const delivered = []
  let nonceCalls = 0
  const bad = { deviceId: "bad-device", push: registration({ kid: "kid-bad", sealed: { v: 1, kid: "kid-bad", enc: "encb", ct: "ctb" } }) }
  const good = { deviceId: "good-device", push: registration({ kid: "kid-good", sealed: { v: 1, kid: "kid-good", enc: "encg", ct: "ctg" } }) }
  const sink = createPushSink({
    state: { devices: [bad, good] },
    stateFile: "memory",
    saveState() {},
    isDeviceAuthorized: () => true,
    hasForegroundSse: () => false,
    now: () => 1_728_000_000_000,
    sleep: async () => {},
    // 第二次构造 nonce 时抛错，模拟单台设备侧失败
    random: (len) => {
      nonceCalls += 1
      if (nonceCalls === 2) throw new Error("device-local failure")
      return Buffer.alloc(len)
    },
    transport: async (_url, payload) => { delivered.push(payload); return { status: 200 } },
  })

  const results = await sink.notify({ sessionId: "sess-1", state: "awaitingApproval", title: "Need approval" })
  assert.equal(results.length, 2)
  assert.equal(results.some((item) => item.sent === true), true, "好设备必须仍然投递成功")
  assert.equal(results.some((item) => item.reason === "error"), true, "坏设备降级为 error 而不是抛出")
  assert.equal(delivered.length, 1, "好设备只投递一次")
})
