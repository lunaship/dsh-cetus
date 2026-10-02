/**
 * 从工具输出里识别本机 dev server 端口。
 *
 * 只保留端口和会话 id。不保存输出正文，也不写入 state。
 * 黑名单、插件端口和 Host 端口不进入列表。手机不能批准。
 */
import { previewPortProblem } from "./preview-proxy.js"
import { toolResultContent } from "./produced-files.js"

export const DETECT_MAX_SESSIONS = 8
export const DETECT_RUNNING_INTERVAL_MS = 5_000
export const DETECT_HISTORY_MESSAGES = 80

const URL_RE = /(?<![A-Za-z0-9+.-])http:\/\/(localhost|127\.0\.0\.1|0\.0\.0\.0):([1-9]\d{0,4})(?![0-9.])/gi
const EXAMPLE_RE = /(?:例如|示例|样例|e\.g\.|for example|\bplaceholder\b|(^|[^\w/])example\b)/i

function unwrapSessionEvent(item) {
  if (!item || typeof item !== "object") return null
  if (item.event && typeof item.event === "object" && typeof item.event.type === "string") return item.event
  if (typeof item.type === "string") return item
  return null
}

/** 只取 tool/result 的文本块。不序列化其他字段，避免把参数或正文留下来。 */
export function toolOutputText(item) {
  const event = unwrapSessionEvent(item)
  if (!event || event.type !== "tool/result") return ""
  const parts = []
  for (const block of toolResultContent(event)) {
    if (typeof block === "string") parts.push(block)
    else if (typeof block?.text === "string") parts.push(block.text)
  }
  return parts.join("\n")
}

/**
 * 返回去重后的端口。示例句（例如 / e.g. / example）里的地址不算。
 * 路径里的 `/example` 仍算真正的地址。
 */
export function extractDevServerPorts(text) {
  if (typeof text !== "string" || text.length === 0) return []
  const ports = []
  const seen = new Set()
  for (const line of text.split(/\r?\n/)) {
    URL_RE.lastIndex = 0
    let match
    while ((match = URL_RE.exec(line))) {
      const port = Number(match[2])
      if (!Number.isInteger(port) || port < 1 || port > 65535 || seen.has(port)) continue
      const around = `${line.slice(0, match.index)}\n${line.slice(match.index + match[0].length)}`
      if (EXAMPLE_RE.test(around)) continue
      seen.add(port)
      ports.push(port)
    }
  }
  return ports
}

export function publicDetections(rows, approvedPorts) {
  const approved = new Set((approvedPorts ?? []).map((port) => Number(port)))
  const out = []
  for (const row of rows ?? []) {
    const port = Number(row?.port)
    const sessionId = typeof row?.sessionId === "string" ? row.sessionId : ""
    if (!sessionId || !Number.isInteger(port) || approved.has(port)) continue
    out.push({ port, sessionId })
  }
  return out
}

export function createPreviewDetector({ pluginPort, hostPort } = {}) {
  const rows = new Map()
  const idleStamp = new Map()
  const scanMs = new Map()

  function endSession(sessionId) {
    const id = String(sessionId ?? "")
    if (!id) return
    for (const [key, row] of [...rows]) {
      if (row.sessionId === id) rows.delete(key)
    }
    idleStamp.delete(id)
    scanMs.delete(id)
  }

  return {
    observe(sessionId, item) {
      const id = String(sessionId ?? "").trim()
      if (!id) return
      const text = toolOutputText(item)
      if (!text) return
      for (const port of extractDevServerPorts(text)) {
        if (previewPortProblem(port, { pluginPort, hostPort })) continue
        const key = `${id}\0${port}`
        if (!rows.has(key)) rows.set(key, { port, sessionId: id })
      }
    },
    list() {
      return [...rows.values()].map((row) => ({ port: row.port, sessionId: row.sessionId }))
    },
    endSession,
    retainSessions(ids) {
      const keep = new Set(ids ?? [])
      const drop = new Set()
      for (const row of rows.values()) {
        if (!keep.has(row.sessionId)) drop.add(row.sessionId)
      }
      for (const id of drop) endSession(id)
    },
    shouldScan(item, now) {
      const id = String(item?.sessionId ?? "")
      if (!id) return false
      if (item.running) return now - (scanMs.get(id) ?? 0) >= DETECT_RUNNING_INTERVAL_MS
      return idleStamp.get(id) !== Number(item.updatedAt ?? 0)
    },
    markScanned(sessionId, stamp, now) {
      const id = String(sessionId ?? "")
      if (!id) return
      idleStamp.set(id, Number(stamp ?? 0))
      scanMs.set(id, now)
    },
  }
}

export async function refreshPreviewDetections({ detector, listSessions, history, now = Date.now() }) {
  let listed
  try {
    listed = await listSessions()
  } catch {
    return detector.list()
  }
  const items = Array.isArray(listed?.items) ? listed.items : []
  const archived = new Set((listed?.archivedSessionIds ?? []).map((id) => String(id ?? "").trim()).filter(Boolean))
  for (const id of archived) detector.endSession(id)
  const live = []
  for (const item of items) {
    const id = String(item?.sessionId ?? "").trim()
    if (!id || archived.has(id)) continue
    live.push({ ...item, sessionId: id })
  }
  detector.retainSessions(live.map((item) => item.sessionId))
  const ranked = [...live].sort((a, b) => Number(b.updatedAt ?? 0) - Number(a.updatedAt ?? 0)).slice(0, DETECT_MAX_SESSIONS)
  for (const item of ranked) {
    if (!detector.shouldScan(item, now)) continue
    let page
    try {
      page = await history(item.sessionId)
    } catch {
      continue
    }
    const events = page?.events ?? page?.records ?? []
    for (const event of events) detector.observe(item.sessionId, event)
    detector.markScanned(item.sessionId, item.updatedAt, now)
  }
  return detector.list()
}
