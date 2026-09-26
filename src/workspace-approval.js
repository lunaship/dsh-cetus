import { randomBytes } from "node:crypto"
import { MobileWorkspaceCreateError, resolveAbsoluteWorkspaceDirectory } from "./workspace-create.js"

const DEFAULT_TTL_MS = 10 * 60 * 1000
const DEFAULT_MAX = 16
const MISSING = { status: 404, body: { error: "批准请求不存在或已过期" } }

/** 绝对路径工作区注册的内存队列。手机提交 realpath，面板批准后才 workspace.create。 */
export function createWorkspaceApprovalQueue({
  ttlMs = DEFAULT_TTL_MS,
  max = DEFAULT_MAX,
  now = () => Date.now(),
  createId = () => randomBytes(9).toString("base64url"),
} = {}) {
  const items = new Map()

  function sweep(at) {
    for (const [id, item] of items) {
      if (at >= item.expiresAt) items.delete(id)
    }
  }

  return {
    submit({ deviceId, deviceName, path }, at = now()) {
      sweep(at)
      const id = String(deviceId ?? "")
      const real = String(path ?? "")
      for (const item of items.values()) {
        if (item.deviceId === id && item.path === real) return item
      }
      const item = {
        requestId: createId(),
        deviceId: id,
        deviceName: String(deviceName ?? "").slice(0, 32),
        path: real,
        createdAt: at,
        expiresAt: at + ttlMs,
      }
      items.set(item.requestId, item)
      while (items.size > max) {
        const oldest = items.keys().next().value
        if (oldest === item.requestId) break
        items.delete(oldest)
      }
      return item
    },
    list(at = now()) {
      sweep(at)
      return [...items.values()].sort((a, b) => a.createdAt - b.createdAt)
    },
    get(requestId, at = now()) {
      sweep(at)
      return items.get(String(requestId ?? "")) ?? null
    },
    remove(requestId) {
      return items.delete(String(requestId ?? ""))
    },
    dropDevice(deviceId) {
      const id = String(deviceId ?? "")
      for (const [key, item] of items) {
        if (item.deviceId === id) items.delete(key)
      }
    },
  }
}

function requestIdOf(raw) {
  const requestId = String(raw ?? "").trim()
  if (!requestId) return { error: { status: 400, body: { error: "缺少 requestId" } } }
  return { requestId }
}

export function rejectQueuedWorkspace(queue, rawRequestId) {
  const parsed = requestIdOf(rawRequestId)
  if (parsed.error) return parsed.error
  if (!queue.remove(parsed.requestId)) return MISSING
  return { status: 200, body: { ok: true } }
}

/** 批准前再次 realpath。目录消失、不再是文件夹、或与提交时不一致，则丢掉请求。 */
export async function approveQueuedWorkspace(queue, rawRequestId, createWorkspace) {
  const parsed = requestIdOf(rawRequestId)
  if (parsed.error) return parsed.error
  const item = queue.get(parsed.requestId)
  if (!item) return MISSING
  let real
  try {
    real = resolveAbsoluteWorkspaceDirectory(item.path)
  } catch (error) {
    queue.remove(parsed.requestId)
    if (error instanceof MobileWorkspaceCreateError) {
      return { status: error.status, body: { error: error.message, code: error.code } }
    }
    return { status: 400, body: { error: "无法解析该目录" } }
  }
  if (real !== item.path) {
    queue.remove(parsed.requestId)
    return { status: 409, body: { error: "目录已变化，请让手机重新提交" } }
  }
  try {
    const value = await createWorkspace(real)
    queue.remove(parsed.requestId)
    return {
      status: 200,
      deviceId: item.deviceId,
      body: {
        ok: true,
        path: real,
        workspace: value?.workspace ?? null,
        created: Boolean(value?.created),
      },
    }
  } catch (error) {
    return { status: 502, body: { error: "无法注册工作区" }, error }
  }
}
