/**
 * state.remote 读写与设备远程能力（RFC §5.2、§5.3、§6.2–§6.4）。
 *
 * 全部是纯函数：index.js 只在集成点调用，逻辑与测试都留在这里（RFC §10.3 第 8 条）。
 * hostKey / keySeed 是秘密：只在这里读写，不进任何返回给面板的对象。
 */
import { randomBytes } from "node:crypto"
import { b64u, deviceRelayKey, hostPublicKey, routeId as deriveRouteId, unb64u } from "./crypto.js"

/** 官方中继。只是默认值，不是依赖：用户可以换成自建地址（RFC §3.1）。 */
export const OFFICIAL_ENDPOINT = "wss://relay.dshlinks.com/ws"

const HANDLE_BYTES = 16

/**
 * 把用户输入规范成完整的 wss:// URL。
 * 接受 `relay.example.com`、`https://relay.example.com` 这类简写，补成 `wss://…/ws`；
 * 生产只接受 wss://（RFC §5.1），ws:// 仅在测试里显式放行。
 * @returns {string} 规范化后的 URL；非法输入抛 TypeError（message 可直接给用户看）
 */
export function normalizeEndpoint(raw, { allowInsecureWs = false } = {}) {
  let text = String(raw ?? "").trim()
  if (!text) return OFFICIAL_ENDPOINT
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(text)) text = `wss://${text}`
  let url
  try {
    url = new URL(text)
  } catch {
    throw new TypeError("中继地址无效")
  }
  if (url.protocol === "https:") url.protocol = "wss:"
  if (url.protocol === "http:" && allowInsecureWs) url.protocol = "ws:"
  if (url.protocol !== "wss:" && !(url.protocol === "ws:" && allowInsecureWs)) {
    throw new TypeError("中继地址必须是 wss://")
  }
  if (url.username || url.password || url.hash) throw new TypeError("中继地址不能带账号或锚点")
  if (url.pathname === "/" || url.pathname === "") url.pathname = "/ws"
  return url.toString()
}

/** 外层证书指纹：空 = 按系统 CA 校验；否则 64 位小写 hex（允许用户粘贴带冒号的写法）。 */
export function normalizeOuterPin(raw) {
  const text = String(raw ?? "").replace(/[:\s]/g, "").toLowerCase()
  if (!text) return ""
  if (!/^[0-9a-f]{64}$/.test(text)) throw new TypeError("外层证书指纹须为 64 位 SHA-256")
  return text
}

export function isOfficialEndpoint(endpoint) {
  return String(endpoint ?? "") === OFFICIAL_ENDPOINT
}

/** 中继主机名，用于面板展示（不展示路径）。 */
export function endpointHost(endpoint) {
  try {
    return new URL(endpoint).host
  } catch {
    return ""
  }
}

/**
 * 首次启用时生成主机密钥与设备密钥种子；之后只更新地址与开关（RFC §5.3：更换中继不换密钥）。
 * @returns {boolean} state 是否被修改
 */
export function enableRemote(state, { endpoint, outerPin = "" }, now = Date.now()) {
  const prev = state.remote
  const identity = hasIdentity(prev)
  state.remote = {
    enabled: true,
    endpoint,
    outerPin,
    hostKey: identity ? prev.hostKey : b64u(randomBytes(32)),
    keySeed: identity ? prev.keySeed : b64u(randomBytes(32)),
    createdAt: identity ? prev.createdAt ?? now : now,
  }
  return true
}

/** 停止远程：保留密钥，重新启用时设备无需重新获取能力（RFC §6.2）。 */
export function disableRemote(state) {
  if (!state.remote?.enabled) return false
  state.remote.enabled = false
  return true
}

/** 重置远程身份：换密钥、清空所有设备 handle（RFC §6.2）。 */
export function resetRemoteIdentity(state, now = Date.now()) {
  if (!state.remote) return false
  state.remote.hostKey = b64u(randomBytes(32))
  state.remote.keySeed = b64u(randomBytes(32))
  state.remote.createdAt = now
  for (const device of state.devices ?? []) {
    delete device.remoteHandle
    delete device.remoteIssuedAt
  }
  return true
}

export function remoteEnabled(state) {
  return Boolean(state.remote?.enabled && hasIdentity(state.remote))
}

/** 构造 Agent 需要的密钥；state 不完整时返回 null。 */
export function remoteKeys(state) {
  if (!hasIdentity(state.remote)) return null
  try {
    const hostKeySeed = unb64u(state.remote.hostKey, 32)
    const keySeed = unb64u(state.remote.keySeed, 32)
    return { hostKeySeed, keySeed, routeId: deriveRouteId(hostPublicKey(hostKeySeed)) }
  } catch {
    return null
  }
}

/**
 * 给设备补发 relayHandle（配对时、或已配对设备下次 bootstrap 时）。
 * @returns {boolean} 是否新发了 handle（调用方据此落盘）
 */
export function ensureDeviceHandle(device, now = Date.now()) {
  if (typeof device.remoteHandle === "string" && device.remoteHandle) return false
  device.remoteHandle = b64u(randomBytes(HANDLE_BYTES))
  device.remoteIssuedAt = now
  return true
}

/** pair 响应与 bootstrap 里的 `remote`（RFC §6.3）：`{ e, r, h, k, p? }`。 */
export function deviceRemote(state, device) {
  const keys = remoteKeys(state)
  if (!keys || !remoteEnabled(state) || !device?.remoteHandle) return null
  let handle
  try {
    handle = unb64u(device.remoteHandle, HANDLE_BYTES)
  } catch {
    return null
  }
  const out = {
    e: state.remote.endpoint,
    r: b64u(keys.routeId),
    h: device.remoteHandle,
    k: b64u(deviceRelayKey(keys.keySeed, handle)),
  }
  if (state.remote.outerPin) out.p = state.remote.outerPin
  return out
}

/** 二维码里的 `remote`（RFC §5.2）：`{ e, r, s, p? }`。 */
export function qrRemote(state, seed) {
  const keys = remoteKeys(state)
  if (!keys || !remoteEnabled(state)) return null
  const out = { e: state.remote.endpoint, r: b64u(keys.routeId), s: b64u(seed) }
  if (state.remote.outerPin) out.p = state.remote.outerPin
  return out
}

/**
 * Agent 的 lookupDevice：按 handle 找设备。
 * 吊销即从 state.devices 删除，所以查不到就是已吊销；pending 过期的也不认（RFC §5.5 第 4 步）。
 */
export function findDeviceByHandle(state, handle, { isRevoking = () => false, now = Date.now() } = {}) {
  const key = b64u(handle)
  for (const device of state.devices ?? []) {
    if (device.remoteHandle !== key) continue
    if (isRevoking(device)) return null
    if (device.status === "pending" && now >= (device.pendingExpiresAt ?? 0)) return null
    return device
  }
  return null
}

function hasIdentity(remote) {
  return Boolean(remote && typeof remote.hostKey === "string" && typeof remote.keySeed === "string")
}
