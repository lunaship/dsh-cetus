import { createCipheriv, createHash, randomBytes } from "node:crypto"
import { request as httpRequest } from "node:http"
import { request as httpsRequest } from "node:https"

const CONTENT_AAD_PREFIX = "dlpush/1 content|"
const TERMINAL_WINDOW_MS = 30_000
const MAX_ATTEMPTS = 4

export function publicPush(record) {
  return { enabled: Boolean(record && typeof record === "object") }
}

export function validatePushRegistration(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return "请求格式无效"
  let url
  try {
    url = new URL(String(body.gateway ?? ""))
  } catch {
    return "gateway 无效"
  }
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) {
    return "gateway 必须是无凭据的 HTTPS 地址"
  }
  const kid = String(body.kid ?? "").trim()
  const sealed = body.sealed
  if (!kid || !sealed || typeof sealed !== "object" || Array.isArray(sealed)) return "sealed 必须是对象"
  if (sealed.v !== 1 || sealed.kid !== kid || !isBase64Url(sealed.enc) || !isBase64Url(sealed.ct)) {
    return "sealed 格式无效"
  }
  if (!/^[0-9a-f]{64}$/i.test(String(body.k ?? ""))) return "k 无效"
  const prefs = body.prefs
  if (!prefs || typeof prefs !== "object" || Array.isArray(prefs)) return "prefs 无效"
  for (const key of ["approval", "question", "completed", "failed"]) {
    if (typeof prefs[key] !== "boolean") return "prefs 无效"
  }
  return null
}

export function registrationFromBody(body, nowMs) {
  const kid = String(body.kid).trim()
  return {
    gateway: new URL(body.gateway).origin,
    kid,
    sealed: { v: 1, kid, enc: body.sealed.enc, ct: body.sealed.ct },
    k: String(body.k).toLowerCase(),
    prefs: {
      approval: body.prefs.approval,
      question: body.prefs.question,
      completed: body.prefs.completed,
      failed: body.prefs.failed,
    },
    updatedAt: nowMs,
  }
}

export function contentCiphertext(keyHex, deviceId, plaintext, nonce = randomBytes(12)) {
  const cipher = createCipheriv("aes-256-gcm", Buffer.from(keyHex, "hex"), nonce)
  cipher.setAAD(Buffer.from(`${CONTENT_AAD_PREFIX}${deviceId}`, "utf8"))
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()])
  return Buffer.concat([nonce, encrypted]).toString("base64")
}

export function notificationForState(state) {
  if (state === "awaitingApproval") return { type: "approval", priority: "high", timeSensitive: true }
  if (state === "awaitingInput") return { type: "question", priority: "high", timeSensitive: true }
  if (state === "completed" || state === "stopped") return { type: "completed", priority: "normal", timeSensitive: false }
  if (state === "failed") return { type: "failed", priority: "normal", timeSensitive: false }
  return null
}

export function createPushSink({
  state,
  stateFile,
  saveState,
  isDeviceAuthorized,
  hasForegroundSse,
  logger = null,
  now = Date.now,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  random = randomBytes,
  transport = defaultTransport,
} = {}) {
  const queues = new Map()

  function log(deviceId, status, reason) {
    logger?.info?.("dsh-cetus: push device=" + String(deviceId).slice(0, 8) + " status=" + status + " reason=" + reason)
  }

  function registration(device) {
    return isDeviceAuthorized(state, device) ? device.push ?? null : null
  }

  function enqueue(deviceId, operation) {
    const previous = queues.get(deviceId) ?? Promise.resolve()
    const next = previous.then(operation, operation)
    queues.set(deviceId, next.then(() => undefined, () => undefined))
    return next
  }

  async function clear(device, reason) {
    const current = (state.devices ?? []).find((item) => item.deviceId === device.deviceId)
    if (current?.push) {
      delete current.push
      saveState(stateFile, state)
    }
    log(device.deviceId, "cleared", reason)
  }

  async function send(device, event) {
    const notice = notificationForState(event.state)
    const record = registration(device)
    if (!notice || !record || record.prefs[notice.type] !== true) return { sent: false, reason: "disabled" }
    if (hasForegroundSse(device.deviceId)) return { sent: false, reason: "foreground" }
    const nowMs = now()
    if (notice.type === "completed") {
      const recent = record.lastTerminalAt?.[event.sessionId] ?? 0
      if (nowMs - recent < TERMINAL_WINDOW_MS) return { sent: false, reason: "collapsed" }
      record.lastTerminalAt = { ...(record.lastTerminalAt ?? {}), [event.sessionId]: nowMs }
      saveState(stateFile, state)
    }
    const plaintext = Buffer.from(JSON.stringify({
      v: 1,
      type: notice.type,
      sessionId: event.sessionId,
      title: event.title,
      ts: Math.floor(nowMs / 1000),
    }))
    const payload = {
      kid: record.kid,
      sealed: record.sealed,
      kind: "alert",
      ct: contentCiphertext(record.k, device.deviceId, plaintext, random(12)),
      collapseId: createHash("sha256")
        .update([device.deviceId, event.sessionId, notice.type].join(String.fromCharCode(10)))
        .digest("hex")
        .slice(0, 32),
      priority: notice.priority,
      expiresIn: 900,
      timeSensitive: notice.timeSensitive,
    }
    return deliver(device, record, payload)
  }

  async function deliver(device, record, payload) {
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
      let response
      try {
        response = await transport(record.gateway + "/v1/push", payload)
      } catch {
        if (attempt === MAX_ATTEMPTS) {
          log(device.deviceId, "failed", "network")
          return { sent: false, reason: "network" }
        }
        await sleep(100 * 2 ** (attempt - 1))
        continue
      }
      if (!registration(device)) return { sent: false, reason: "revoked" }
      if (response.status === 200) {
        log(device.deviceId, 200, "accepted")
        return { sent: true, reason: "accepted" }
      }
      if (response.status === 410) {
        await clear(device, "expired")
        return { sent: false, reason: "expired" }
      }
      if (response.status === 429 && attempt < MAX_ATTEMPTS) {
        await sleep(100 * 2 ** (attempt - 1))
        continue
      }
      log(device.deviceId, response.status, response.status === 429 ? "limited" : "rejected")
      return { sent: false, reason: response.status === 429 ? "limited" : "rejected" }
    }
    return { sent: false, reason: "failed" }
  }

  return {
    register(device, body) {
      const error = validatePushRegistration(body)
      if (error) return { status: 400, body: { error } }
      if (!isDeviceAuthorized(state, device)) return { status: 401, body: { error: "设备已被吊销" } }
      device.push = registrationFromBody(body, now())
      saveState(stateFile, state)
      log(device.deviceId, "registered", "updated")
      return { status: 200, body: { ok: true, push: publicPush(device.push) } }
    },
    async unregister(device) {
      if (!isDeviceAuthorized(state, device)) return { status: 401, body: { error: "设备已被吊销" } }
      await clear(device, "disabled")
      return { status: 200, body: { ok: true, push: { enabled: false } } }
    },
    notify(event) {
      return Promise.all((state.devices ?? [])
        .filter((device) => device.push)
        .map((device) => enqueue(device.deviceId, () => send(device, event))))
    },
    dropDevice(device) {
      if (device?.push) delete device.push
      log(device?.deviceId, "cleared", "revoked")
    },
  }
}

function isBase64Url(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]+$/.test(value) && value.length >= 8 && value.length <= 4096
}

function defaultTransport(url, payload) {
  const target = new URL(url)
  const body = Buffer.from(JSON.stringify(payload))
  const request = target.protocol === "https:" ? httpsRequest : httpRequest
  return new Promise((resolve, reject) => {
    const req = request(target, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "content-length": body.length,
      },
    }, (res) => {
      res.resume()
      res.on("end", () => resolve({ status: res.statusCode ?? 0 }))
    })
    req.on("error", reject)
    req.end(body)
  })
}
