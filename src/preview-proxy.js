/**
 * 开发服务器预览代理。
 *
 * 只拨 127.0.0.1。端口必须先在回环面板批准。手机 API 不能批准。
 * 不记录路径、查询串和正文，只给批准记录加访问计数。
 */
import { request as httpRequest } from "node:http"
import { randomBytes } from "node:crypto"

export const PREVIEW_TTL_MS = 2 * 60 * 60 * 1000
export const PREVIEW_MAX_BYTES = 50 * 1024 * 1024
export const PREVIEW_IDLE_MS = 60_000
export const PREVIEW_BLACKLIST = Object.freeze([22, 3306, 5432, 6379, 11211, 27017, 9200])

const BLACKLIST = new Set(PREVIEW_BLACKLIST)
const DROP_HEADERS = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailers",
  "trailer",
  "transfer-encoding",
  "upgrade",
  "proxy-connection",
  "authorization",
  "x-dsh-link-token",
  "referer",
  "referrer",
  "x-forwarded-for",
  "x-forwarded-host",
  "x-forwarded-proto",
  "forwarded",
  "host",
])

export function previewPortProblem(port, { pluginPort, hostPort } = {}) {
  const n = typeof port === "number" ? port : Number(String(port ?? "").trim())
  if (!Number.isInteger(n) || n < 1 || n > 65535) return "端口无效"
  if (BLACKLIST.has(n)) return "这个端口不能预览"
  if (pluginPort != null && n === Number(pluginPort)) return "不能预览插件自己的端口"
  if (hostPort != null && n === Number(hostPort)) return "不能预览 Host 自己的端口"
  return null
}

export function previewLabelProblem(label) {
  if (typeof label !== "string") return "请填写名称"
  const text = label.trim()
  if (!text) return "请填写名称"
  if (text.length > 80) return "名称太长"
  if (/[\u0000-\u001f]/.test(text)) return "名称无效"
  return null
}

/** 拨号用。主机名不是 127.0.0.1 就失败，避免请求头把代理带到别的地址。 */
export function previewLookup(hostname, options, callback) {
  const cb = typeof options === "function" ? options : callback
  const opts = typeof options === "function" ? {} : (options ?? {})
  if (hostname !== "127.0.0.1") {
    const err = new Error("preview dial is loopback only")
    err.code = "ENOTLOOPBACK"
    cb(err)
    return
  }
  if (opts.all) cb(null, [{ address: "127.0.0.1", family: 4 }])
  else cb(null, "127.0.0.1", 4)
}

export function matchPreviewPath(url) {
  const raw = String(url ?? "")
  if (/^[a-z][a-z0-9+.-]*:/i.test(raw)) return null
  let parsed
  try {
    parsed = new URL(raw || "/", "http://127.0.0.1")
  } catch {
    return null
  }
  const match = /^\/dsh-link\/mobile\/preview\/([^/]+)(\/.*)?$/.exec(parsed.pathname)
  if (!match) return null
  let previewId = match[1]
  try {
    previewId = decodeURIComponent(previewId)
  } catch {
    return null
  }
  if (!/^[a-f0-9]{24}$/.test(previewId)) return null
  const rest = match[2] && match[2].startsWith("/") ? match[2] : "/"
  return { previewId, path: rest + parsed.search }
}

function headerList(value) {
  if (value == null) return []
  return Array.isArray(value) ? value : [value]
}

export function stripHopByHop(headers) {
  const named = new Set()
  for (const value of headerList(headers?.connection)) {
    for (const part of String(value).split(",")) {
      const name = part.trim().toLowerCase()
      if (name) named.add(name)
    }
  }
  const out = {}
  for (const [key, value] of Object.entries(headers ?? {})) {
    const lower = key.toLowerCase()
    if (DROP_HEADERS.has(lower) || named.has(lower)) continue
    out[lower] = value
  }
  return out
}

export function forwardPreviewHeaders(headers, port, { websocket = false } = {}) {
  const out = stripHopByHop(headers)
  out.host = `localhost:${port}`
  if (headers?.origin) out.origin = `http://localhost:${port}`
  if (!websocket) return out
  out.connection = "Upgrade"
  out.upgrade = "websocket"
  for (const name of ["sec-websocket-key", "sec-websocket-version", "sec-websocket-protocol", "sec-websocket-extensions"]) {
    if (headers?.[name] != null) out[name] = headers[name]
  }
  return out
}

function createRegistry() {
  const live = new Map()
  return {
    track(previewId, socket) {
      if (!socket) return () => {}
      let set = live.get(previewId)
      if (!set) {
        set = new Set()
        live.set(previewId, set)
      }
      set.add(socket)
      const drop = () => {
        set.delete(socket)
        if (set.size === 0) live.delete(previewId)
      }
      socket.once("close", drop)
      return drop
    },
    disconnect(previewId) {
      const set = live.get(previewId)
      if (!set) return
      for (const socket of [...set]) {
        try { socket.destroy() } catch {}
      }
      live.delete(previewId)
    },
    stop() {
      for (const id of [...live.keys()]) this.disconnect(id)
    },
  }
}

function publicPreview(item) {
  return {
    previewId: item.previewId,
    label: item.label,
    port: item.port,
    expiresAt: item.expiresAt,
  }
}

function panelPreview(item) {
  return { ...publicPreview(item), hits: item.hits ?? 0 }
}

export function rejectUpgrade(socket, code, message) {
  const body = JSON.stringify({ error: message })
  const reason = code === 401 ? "Unauthorized" : code === 403 ? "Forbidden" : code === 404 ? "Not Found" : "Error"
  try {
    socket.write(
      `HTTP/1.1 ${code} ${reason}\r\ncontent-type: application/json; charset=utf-8\r\ncontent-length: ${Buffer.byteLength(body)}\r\nconnection: close\r\n\r\n${body}`,
    )
  } catch {}
  socket.destroy()
}

function writeJson(res, code, obj) {
  const body = Buffer.from(JSON.stringify(obj))
  res.writeHead(code, {
    "content-type": "application/json; charset=utf-8",
    "content-length": String(body.length),
    "x-content-type-options": "nosniff",
    "cache-control": "no-store",
  })
  res.end(body)
}

/**
 * @param {{ state: object, save: () => void, pluginPort: number, hostPort: number, logger?: { info?: Function }, now?: () => number, idleMs?: number, maxBytes?: number, requestImpl?: Function }} options
 */
export function createPreviewService(options) {
  const {
    state,
    save,
    pluginPort,
    hostPort,
    logger,
    now = () => Date.now(),
    idleMs = PREVIEW_IDLE_MS,
    maxBytes = PREVIEW_MAX_BYTES,
    requestImpl = httpRequest,
  } = options
  if (!Array.isArray(state.previews)) state.previews = []
  const registry = createRegistry()
  const guards = { pluginPort, hostPort }

  function sweep(at = now()) {
    const expired = []
    const next = []
    for (const item of state.previews) {
      if (item && Number(item.expiresAt) > at) next.push(item)
      else if (item?.previewId) expired.push(item.previewId)
    }
    const changed = next.length !== state.previews.length
    state.previews = next
    for (const id of expired) registry.disconnect(id)
    if (changed) save()
  }

  function find(previewId, at = now()) {
    sweep(at)
    return state.previews.find((item) => item.previewId === previewId) ?? null
  }

  function refusePort(port) {
    return previewPortProblem(port, guards)
  }

  function armExpiry(item, sockets) {
    const remain = Number(item.expiresAt) - now()
    const timer = setTimeout(() => {
      for (const socket of sockets) {
        try { socket?.destroy() } catch {}
      }
    }, Math.max(0, remain))
    timer.unref?.()
    const clear = () => clearTimeout(timer)
    for (const socket of sockets) socket?.once?.("close", clear)
    return clear
  }

  function openUpstream(item, method, path, headers) {
    return requestImpl({
      host: "127.0.0.1",
      port: item.port,
      family: 4,
      lookup: previewLookup,
      method,
      path,
      headers,
      agent: false,
      timeout: idleMs,
    })
  }

  return {
    listPublic() {
      sweep()
      return state.previews.map(publicPreview)
    },
    listPanel() {
      sweep()
      return state.previews.map(panelPreview)
    },
    approve({ port, label }) {
      const portError = refusePort(port)
      if (portError) return { status: 400, body: { error: portError } }
      const labelError = previewLabelProblem(label)
      if (labelError) return { status: 400, body: { error: labelError } }
      const n = Number(port)
      const text = label.trim()
      sweep()
      const at = now()
      let item = state.previews.find((row) => row.port === n)
      if (!item) {
        item = {
          previewId: randomBytes(12).toString("hex"),
          port: n,
          label: text,
          approvedAt: at,
          expiresAt: at + PREVIEW_TTL_MS,
          hits: 0,
        }
        state.previews.push(item)
      } else {
        item.label = text
        item.approvedAt = at
        item.expiresAt = at + PREVIEW_TTL_MS
      }
      save()
      logger?.info?.(`dsh-cetus: preview approve port=${n}`)
      return { status: 200, body: { ok: true, preview: panelPreview(item) } }
    },
    revoke(previewId) {
      const id = String(previewId ?? "").trim()
      const index = state.previews.findIndex((item) => item.previewId === id)
      if (index < 0) return { status: 404, body: { error: "预览不存在" } }
      const [item] = state.previews.splice(index, 1)
      registry.disconnect(id)
      save()
      logger?.info?.(`dsh-cetus: preview revoke port=${item.port}`)
      return { status: 200, body: { ok: true } }
    },
    handleHttp(req, res) {
      if (req.method === "CONNECT") {
        writeJson(res, 405, { error: "method not allowed" })
        return true
      }
      const matched = matchPreviewPath(req.url)
      if (!matched) return false
      const item = find(matched.previewId)
      if (!item) {
        writeJson(res, 404, { error: "预览不存在或已过期" })
        return true
      }
      const blocked = refusePort(item.port)
      if (blocked) {
        writeJson(res, 403, { error: blocked })
        return true
      }
      item.hits = (item.hits ?? 0) + 1
      const headers = forwardPreviewHeaders(req.headers, item.port)
      const upstream = openUpstream(item, req.method, matched.path, headers)
      const sockets = [req.socket, upstream]
      registry.track(item.previewId, req.socket)
      registry.track(item.previewId, upstream)
      armExpiry(item, sockets)
      let sent = 0
      let finished = false
      const closeUpstream = () => {
        if (finished) return
        finished = true
        try { upstream.destroy() } catch {}
      }
      const fail = (code, error) => {
        if (finished) return
        finished = true
        try { upstream.destroy() } catch {}
        if (!res.headersSent) writeJson(res, code, { error })
        else if (!res.writableEnded) {
          try { res.end() } catch {}
        }
      }
      upstream.on("timeout", () => fail(504, "预览空闲超时"))
      upstream.on("error", () => fail(502, "预览端口没有响应"))
      upstream.on("response", (up) => {
        sockets.push(up.socket)
        registry.track(item.previewId, up.socket)
        const out = stripHopByHop(up.headers)
        const declared = Number(out["content-length"])
        if (!Number.isFinite(declared) || declared > maxBytes) delete out["content-length"]
        if (!res.headersSent) res.writeHead(up.statusCode ?? 502, out)
        up.on("data", (chunk) => {
          if (finished || res.writableEnded) return
          const room = maxBytes - sent
          if (room <= 0) {
            closeUpstream()
            if (!res.writableEnded) res.end()
            return
          }
          const slice = chunk.length > room ? chunk.subarray(0, room) : chunk
          sent += slice.length
          res.write(slice)
          if (sent >= maxBytes) {
            closeUpstream()
            if (!res.writableEnded) res.end()
          }
        })
        up.on("end", () => {
          closeUpstream()
          if (!res.writableEnded) res.end()
        })
        up.on("error", () => fail(502, "预览端口没有响应"))
      })
      req.on("aborted", () => closeUpstream())
      req.on("error", () => closeUpstream())
      req.pipe(upstream)
      return true
    },
    handleUpgrade(req, socket, head) {
      const matched = matchPreviewPath(req.url)
      if (!matched) {
        rejectUpgrade(socket, 404, "not found")
        return
      }
      const item = find(matched.previewId)
      if (!item) {
        rejectUpgrade(socket, 404, "not found")
        return
      }
      if (refusePort(item.port)) {
        rejectUpgrade(socket, 403, "forbidden")
        return
      }
      item.hits = (item.hits ?? 0) + 1
      const headers = forwardPreviewHeaders(req.headers, item.port, { websocket: true })
      const upstream = openUpstream(item, "GET", matched.path, headers)
      registry.track(item.previewId, socket)
      registry.track(item.previewId, upstream)
      armExpiry(item, [socket, upstream])
      const fail = () => {
        if (!socket.destroyed) rejectUpgrade(socket, 502, "bad gateway")
        try { upstream.destroy() } catch {}
      }
      upstream.on("error", fail)
      upstream.on("timeout", fail)
      upstream.on("response", (up) => {
        up.resume()
        fail()
      })
      upstream.on("upgrade", (upRes, upSocket, upHead) => {
        registry.track(item.previewId, upSocket)
        armExpiry(item, [upSocket])
        const lines = [`HTTP/1.1 ${upRes.statusCode ?? 101} ${upRes.statusMessage || "Switching Protocols"}`]
        for (const [key, value] of Object.entries(upRes.headers)) {
          for (const piece of headerList(value)) lines.push(`${key}: ${piece}`)
        }
        try {
          socket.write(`${lines.join("\r\n")}\r\n\r\n`)
          if (upHead?.length) socket.write(upHead)
          if (head?.length) upSocket.write(head)
        } catch {
          socket.destroy()
          upSocket.destroy()
          return
        }
        const idle = () => {
          socket.destroy()
          upSocket.destroy()
        }
        socket.setTimeout(idleMs, idle)
        upSocket.setTimeout(idleMs, idle)
        upSocket.pipe(socket)
        socket.pipe(upSocket)
        socket.once("close", () => upSocket.destroy())
        upSocket.once("close", () => socket.destroy())
        socket.on("error", idle)
        upSocket.on("error", idle)
      })
      upstream.end()
    },
    stop() {
      registry.stop()
    },
  }
}
