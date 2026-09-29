/**
 * src/remote/state.js 纯函数（RFC §5.1、§5.2、§5.3、§6.2–§6.4）。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { randomBytes } from "node:crypto"
import { b64u, deviceRelayKey, hostPublicKey, routeId } from "../src/remote/crypto.js"
import {
  OFFICIAL_ENDPOINT, deviceRemote, disableRemote, enableRemote, ensureDeviceHandle, findDeviceByHandle,
  normalizeEndpoint, normalizeOuterPin, qrRemote, remoteEnabled, remoteKeys, resetRemoteIdentity,
} from "../src/remote/state.js"

test("中继地址：空值用官方中继；主机名、https:// 简写补成 wss://…/ws；自定义路径原样保留", () => {
  assert.equal(normalizeEndpoint(""), OFFICIAL_ENDPOINT)
  assert.equal(normalizeEndpoint("relay.example.com"), "wss://relay.example.com/ws")
  assert.equal(normalizeEndpoint("https://relay.example.com"), "wss://relay.example.com/ws")
  assert.equal(normalizeEndpoint("wss://relay.example.com:8443/pipe"), "wss://relay.example.com:8443/pipe")
})

test("中继地址：生产拒绝 ws:// 与 http://；带账号、锚点的一律拒绝", () => {
  assert.throws(() => normalizeEndpoint("ws://relay.example.com/ws"), /wss/)
  assert.throws(() => normalizeEndpoint("http://relay.example.com"), /wss/)
  assert.throws(() => normalizeEndpoint("wss://user:pw@relay.example.com/ws"), /账号/)
  assert.equal(normalizeEndpoint("ws://127.0.0.1:9/ws", { allowInsecureWs: true }), "ws://127.0.0.1:9/ws")
})

test("外层证书指纹：接受带冒号的写法并转小写；长度不对拒绝", () => {
  const hex = "AB".repeat(32)
  assert.equal(normalizeOuterPin(hex.match(/../g).join(":")), "ab".repeat(32))
  assert.equal(normalizeOuterPin(""), "")
  assert.throws(() => normalizeOuterPin("abcd"), /64/)
})

test("启用生成密钥；更换中继不换密钥（routeId 不变）；停止保留密钥", () => {
  const state = { devices: [] }
  enableRemote(state, { endpoint: OFFICIAL_ENDPOINT })
  const keys = remoteKeys(state)
  assert.equal(keys.hostKeySeed.length, 32)
  assert.deepEqual(keys.routeId, routeId(hostPublicKey(keys.hostKeySeed)))
  enableRemote(state, { endpoint: "wss://self.example/ws" })
  assert.deepEqual(remoteKeys(state).routeId, keys.routeId)
  assert.equal(state.remote.endpoint, "wss://self.example/ws")
  disableRemote(state)
  assert.equal(remoteEnabled(state), false)
  assert.deepEqual(remoteKeys(state).routeId, keys.routeId)
})

test("设备 handle 只发一次；deviceRemote 的 k 由 keySeed 派生；未启用时为 null", () => {
  const state = { devices: [] }
  const device = { deviceId: "dev-a" }
  assert.equal(ensureDeviceHandle(device), true)
  const handle = device.remoteHandle
  assert.equal(ensureDeviceHandle(device), false)
  assert.equal(device.remoteHandle, handle)
  assert.equal(deviceRemote(state, device), null)
  enableRemote(state, { endpoint: OFFICIAL_ENDPOINT, outerPin: "cd".repeat(32) })
  const remote = deviceRemote(state, device)
  const keys = remoteKeys(state)
  assert.equal(remote.h, handle)
  assert.equal(remote.r, b64u(keys.routeId))
  assert.equal(remote.k, b64u(deviceRelayKey(keys.keySeed, Buffer.from(handle, "base64url"))))
  assert.equal(remote.p, "cd".repeat(32))
})

test("二维码 remote 只带 e/r/s（有外层指纹时加 p），不带设备密钥", () => {
  const state = { devices: [] }
  enableRemote(state, { endpoint: OFFICIAL_ENDPOINT })
  const seed = randomBytes(16)
  assert.deepEqual(Object.keys(qrRemote(state, seed)).sort(), ["e", "r", "s"])
  assert.equal(qrRemote(state, seed).s, b64u(seed))
})

test("按 handle 找设备：正在吊销、pending 已过期的都不认", () => {
  const now = 1_000_000
  const active = { deviceId: "a", remoteHandle: b64u(Buffer.alloc(16, 1)) }
  const expired = { deviceId: "b", remoteHandle: b64u(Buffer.alloc(16, 2)), status: "pending", pendingExpiresAt: now - 1 }
  const waiting = { deviceId: "c", remoteHandle: b64u(Buffer.alloc(16, 3)), status: "pending", pendingExpiresAt: now + 1 }
  const state = { devices: [active, expired, waiting] }
  assert.equal(findDeviceByHandle(state, Buffer.alloc(16, 1), { now }), active)
  assert.equal(findDeviceByHandle(state, Buffer.alloc(16, 2), { now }), null)
  assert.equal(findDeviceByHandle(state, Buffer.alloc(16, 3), { now }), waiting)
  assert.equal(findDeviceByHandle(state, Buffer.alloc(16, 1), { now, isRevoking: (d) => d === active }), null)
  assert.equal(findDeviceByHandle(state, Buffer.alloc(16, 9), { now }), null)
})

test("重置远程身份：换密钥并清空全部设备 handle", () => {
  const device = { deviceId: "a" }
  const state = { devices: [device] }
  enableRemote(state, { endpoint: OFFICIAL_ENDPOINT })
  ensureDeviceHandle(device)
  const before = state.remote.hostKey
  resetRemoteIdentity(state)
  assert.notEqual(state.remote.hostKey, before)
  assert.equal(device.remoteHandle, undefined)
})
