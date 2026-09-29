/**
 * 插件里的远程连接运行时（RFC §6）：Agent 启停、面板状态、二维码里的 bootstrap、
 * 设备的远程能力。index.js 只负责把它接到路由与配对流程上。
 *
 * state 由调用方持有（与设备表同一份对象），这里改完调用 save() 落盘。
 */
import { RemoteAgent } from "./agent.js"
import { BootstrapTable } from "./bootstrap.js"
import {
  OFFICIAL_ENDPOINT, deviceRemote, disableRemote, enableRemote, endpointHost, ensureDeviceHandle, findDeviceByHandle,
  isOfficialEndpoint, normalizeEndpoint, normalizeOuterPin, qrRemote, remoteEnabled, remoteKeys, resetRemoteIdentity,
} from "./state.js"

/** 面板点「启用 / 更换中继」后最多等首次注册这么久（RFC §6.6），之后交给面板轮询。 */
const ENABLE_WAIT_MS = 15_000

/** ws:// 只给测试用（RFC §5.1：生产配置与 UI 均拒绝）。按调用时读取，测试在 apply 前设置即可。 */
function allowInsecureWs() {
  return process.env.DSH_LINKS_DLP_ALLOW_INSECURE_WS === "1"
}

/** promise 在 ms 内 resolve 返回 true；超时或 reject 返回 false，不抛。 */
function settleWithin(promise, ms) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), ms)
    timer.unref?.()
    Promise.resolve(promise).then(
      () => { clearTimeout(timer); resolve(true) },
      () => { clearTimeout(timer); resolve(false) },
    )
  })
}

/**
 * @param {object} deps
 * @param {object} deps.state       插件 state（含 devices、remote）
 * @param {() => void} deps.save    落盘
 * @param {number} deps.pluginPort  Agent 只连 127.0.0.1:<pluginPort>
 * @param {() => boolean} deps.isLocalReady
 * @param {(device: object) => boolean} deps.isRevoking  正在吊销的设备不再接受会合
 * @param {() => { code: string, expiresAt: number }} deps.currentPairing  当前配对码与过期时刻
 * @param {{ info(m: string): void, warn(m: string): void }} deps.logger
 */
export function createRemoteRuntime({ state, save, pluginPort, isLocalReady, isRevoking, currentPairing, logger }) {
  const bootstrap = new BootstrapTable()
  let agent = null
  let error = ""
  let lastOnlineAt = 0
  let replaced = false
  // 当前二维码的 bootstrap 种子，按「配对码 + 过期时刻 + route + 地址」复用：
  // 面板每 8 秒轮询一次，若每次出图都换种子，手机扫到的码两轮后就失效了
  let qr = null

  async function stop() {
    const current = agent
    agent = null
    qr = null
    if (!current) return
    current.removeAllListeners()
    await current.stop().catch(() => {})
  }

  /** 按 state.remote 重启 Agent；返回的 promise 在首次注册成功时 resolve。 */
  async function start() {
    await stop()
    error = ""
    replaced = false
    if (!remoteEnabled(state)) return
    const keys = remoteKeys(state)
    if (!keys) {
      error = "远程身份不完整，请在远程设置里重置远程身份"
      return
    }
    let next
    try {
      next = new RemoteAgent({
        endpoint: state.remote.endpoint,
        outerPin: state.remote.outerPin ?? "",
        hostKeySeed: keys.hostKeySeed,
        keySeed: keys.keySeed,
        pluginPort,
        // 每次 open 现查：吊销、pending 过期都即时生效（RFC §5.5 第 4 步）
        lookupDevice: (handle) => {
          const device = findDeviceByHandle(state, handle, { isRevoking })
          return device ? { deviceId: device.deviceId } : null
        },
        bootstrap,
        isLocalReady,
        logger,
        allowInsecureWs: allowInsecureWs(),
      })
    } catch (err) {
      error = String(err?.message ?? err)
      return
    }
    next.on("status", (status) => {
      if (status !== "ready") return
      lastOnlineAt = Date.now()
      error = ""
      replaced = false
    })
    // 常见于两个 DSH profile 共用同一个 stateDir（RFC §6.7）
    next.on("replaced", () => { replaced = true })
    agent = next
    const started = next.start()
    started.catch((err) => {
      if (agent === next) error = String(err?.message ?? err)
    })
    return started
  }

  /** 面板 GET /dsh-link/remote-status（不含任何密钥）。 */
  function status() {
    const enabled = remoteEnabled(state)
    const endpoint = state.remote?.endpoint || OFFICIAL_ENDPOINT
    let current = "off"
    if (enabled) current = agent ? (agent.status === "off" ? "connecting" : agent.status) : "error"
    return {
      state: current,
      enabled,
      endpoint,
      host: endpointHost(endpoint),
      official: isOfficialEndpoint(endpoint),
      outerPin: state.remote?.outerPin ?? "",
      lastOnlineAt: lastOnlineAt || null,
      error: error || (current === "connecting" ? agent?.lastError ?? "" : ""),
      replaced,
      remoteDevices: (state.devices ?? []).filter((d) => d.remoteHandle && d.status !== "pending").length,
    }
  }

  /** 启用或更换中继。地址 / 指纹不合法时抛 TypeError（message 可直接给用户看）。 */
  async function enable({ endpoint, outerPin }) {
    const normalized = normalizeEndpoint(endpoint, { allowInsecureWs: allowInsecureWs() })
    const pin = normalizeOuterPin(outerPin)
    const previous = state.remote?.enabled ? state.remote.endpoint : ""
    enableRemote(state, { endpoint: normalized, outerPin: pin })
    save()
    logger.info(`dsh-links: remote enable host=${endpointHost(normalized)}${previous && previous !== normalized ? " (switched)" : ""}`)
    // 超时不算失败：Agent 会继续按退避重连，面板轮询状态即可
    await settleWithin(start(), ENABLE_WAIT_MS)
    return status()
  }

  async function disable() {
    if (disableRemote(state)) save()
    await stop()
    logger.info("dsh-links: remote disable")
    return status()
  }

  /** 危险操作：所有手机的远程凭据立即作废。从未启用过远程时返回 null。 */
  async function resetIdentity() {
    if (!resetRemoteIdentity(state)) return null
    save()
    logger.info("dsh-links: remote identity reset")
    if (remoteEnabled(state)) await settleWithin(start(), ENABLE_WAIT_MS)
    else await stop()
    return status()
  }

  /** 二维码里的 remote；远程未就绪时为 null（二维码退化为纯局域网码，RFC §5.2）。 */
  function qrPayload() {
    if (!agent || agent.status !== "ready" || !remoteEnabled(state)) return null
    const { code, expiresAt } = currentPairing()
    if (!Number.isSafeInteger(expiresAt)) return null
    const route = agent.routeId
    const endpoint = state.remote.endpoint
    if (!qr || qr.code !== code || qr.expiresAt !== expiresAt || !qr.route.equals(route) || qr.endpoint !== endpoint) {
      qr = { code, expiresAt, route, endpoint, seed: bootstrap.issue(route, expiresAt).seed }
    }
    return qrRemote(state, qr.seed)
  }

  /** mobile bootstrap 的 remote（RFC §6.4）：已配对手机借此自动补齐远程能力，无需重扫。 */
  function forDevice(device) {
    if (!remoteEnabled(state)) return null
    const current = (state.devices ?? []).find((d) => d.deviceId === device?.deviceId)
    if (!current || isRevoking(current)) return null
    if (ensureDeviceHandle(current)) save()
    return deviceRemote(state, current)
  }

  /** 新配对的设备：远程已启用时发 handle（pending 也发，纯远程首配的手机要靠它等批准结果，RFC §6.3）。 */
  function issueForNewDevice(device) {
    if (!remoteEnabled(state)) return
    ensureDeviceHandle(device)
  }

  return {
    bootstrap,
    get agent() { return agent },
    start,
    stop,
    status,
    enable,
    disable,
    resetIdentity,
    qrPayload,
    forDevice,
    issueForNewDevice,
    deviceRemote: (device) => deviceRemote(state, device),
  }
}
