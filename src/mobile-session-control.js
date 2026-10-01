/**
 * 手机端会话控制：排队消息（编辑 / 删除 / 转为引导）、目标（暂停 / 继续 / 编辑 / 清除）、
 * 定时任务（列表 / 历史 / 修改 / 删除；DSH 不开放远程新建）。
 *
 * 全部走 RPC_METHOD_ALLOWLIST 里的固定方法；参数由插件拼装，手机只能传 id / 文本 / CAS 引用。
 * 目标与定时任务都是 compare-and-set：过期 revision / expected 由 DSH 拒绝，这里映射成 409，
 * App 收到后刷新再让用户重试，不会覆盖网页端更新。
 * 协议形状参考 Clarklevis1995/dsh-plugin-mobile-gateway（MIT）。
 */
import { callLocalRpc, LocalRpcError } from "./local-rpc.js"

const BODY_LIMIT = 64 * 1024
const MAX_QUEUE_TEXT = 32 * 1024
const MAX_GOAL_OBJECTIVE = 8 * 1024
const MAX_SCHEDULE_TEXT = 16 * 1024
const SCHEDULE_RECORD_FIELDS = Object.freeze([
  "id", "kind", "title", "prompt", "scheduledAt", "afterSeconds", "everySeconds",
  "time", "timeZone", "weekdays", "expression",
])

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value)
}

function nonEmpty(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null
}

function textOf(content) {
  if (!Array.isArray(content)) return ""
  return content
    .filter((block) => isRecord(block) && block.type === "text" && typeof block.text === "string")
    .map((block) => block.text)
    .join("")
}

/**
 * DSH rc.1 durable inbox 投影 → 手机排队列表。宽松解析：形状不对的条目跳过而不是抛错，
 * 旧 Host 没有 inbox 时返回空表。next-turn = 排队；next-step 里用户来源 = 引导，其余 = 上下文。
 */
export function queueItemsFromInbox(inbox) {
  if (!isRecord(inbox)) return []
  const convert = (messages, target) => (Array.isArray(messages) ? messages : [])
    .filter((m) => isRecord(m) && typeof m.id === "string" && m.id && Array.isArray(m.content))
    .map((m) => {
      const kind = isRecord(m.source) && typeof m.source.kind === "string" ? m.source.kind : ""
      const placement = target === "next-turn" ? "queued" : kind === "user" ? "steering" : "context"
      const images = m.content.filter((b) => isRecord(b) && b.type === "image").length
      return { id: m.id, placement, text: textOf(m.content), images }
    })
  return [...convert(inbox["next-turn"], "next-turn"), ...convert(inbox["next-step"], "next-step")]
}

/** 只回原始 ScheduleRecord 字段：目录行附带的 Host 管理元数据不能进 update 的 expected。 */
export function scheduleExpectedRecord(expected) {
  if (!isRecord(expected)) return null
  return Object.fromEntries(
    SCHEDULE_RECORD_FIELDS.filter((field) => Object.hasOwn(expected, field)).map((field) => [field, expected[field]]),
  )
}

export function goalRefFrom(body) {
  const ref = isRecord(body?.ref) ? body.ref : null
  const id = nonEmpty(ref?.id)
  const revision = Number.isSafeInteger(ref?.revision) && ref.revision > 0 ? ref.revision : null
  return id && revision !== null ? { id, revision } : null
}

/** DSH typed error → HTTP：CAS 冲突 409、找不到 404、参数 400，其余 502（不回显内部细节）。 */
export function controlErrorStatus(error) {
  const code = String(error?.code ?? "")
  if (/conflict|stale|revision|mismatch|expected/i.test(code)) return 409
  if (/not-?found|missing|unknown/i.test(code)) return 404
  if (/invalid|bad-request|validation|unsupported/i.test(code)) return 400
  return 502
}

function respondRpcError(json, res, error) {
  if (!(error instanceof LocalRpcError)) throw error
  const status = controlErrorStatus(error)
  return json(res, status, {
    error: status === 502 ? "DSH 暂时无法处理这个操作" : (error.message || "操作失败"),
    code: error.code,
  })
}

export async function handleMobileSessionControlApi(req, res, targetPort, state, device, pathname, rt, deps) {
  const { json, readAuthorizedJson, runMobileDeviceMutation, mobileMutationWasRevoked, respondDeviceRevoked } = deps

  const mutate = async (fn) => {
    try {
      const value = await runMobileDeviceMutation(rt, state, device, fn)
      if (mobileMutationWasRevoked(value)) {
        respondDeviceRevoked(res)
        return { done: true }
      }
      return { value }
    } catch (error) {
      respondRpcError(json, res, error)
      return { done: true }
    }
  }

  // ---- 排队消息 ----
  const queueList = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/queue$/)
  if (req.method === "GET" && queueList) {
    const sessionId = decodeURIComponent(queueList[1])
    // 不带 maxMessages：走 follow 快照，投影最全（list 行不一定带 inbox）
    const value = await callLocalRpc(targetPort, "session.history", { sessionId })
    json(res, 200, { ok: true, sessionId, items: queueItemsFromInbox(value?.projections?.values?.inbox) })
    return true
  }
  const queueItem = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/queue\/([^/]+)$/)
  if (req.method === "POST" && queueItem) {
    const sessionId = decodeURIComponent(queueItem[1])
    const itemId = decodeURIComponent(queueItem[2])
    const body = await readAuthorizedJson(req, res, state, device, BODY_LIMIT)
    if (!body) return true
    const kind = String(body.action ?? "")
    let action
    if (kind === "edit") {
      const text = typeof body.text === "string" ? body.text : ""
      if (!text.trim()) return json(res, 400, { error: "排队消息不能改成空的" }), true
      if (text.length > MAX_QUEUE_TEXT) return json(res, 413, { error: "消息过长" }), true
      action = { kind: "edit", content: [{ type: "text", text }] }
    } else if (kind === "remove" || kind === "steer") {
      action = { kind }
    } else {
      return json(res, 400, { error: "action 只能是 edit / remove / steer" }), true
    }
    const result = await mutate(() => callLocalRpc(targetPort, "session.updateQueue", { sessionId, itemId, action }))
    if (result.done) return true
    json(res, 200, { ok: true, sessionId, itemId, action: kind })
    return true
  }

  // ---- 目标 ----
  const goalMatch = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/goal\/(edit|pause|resume|clear)$/)
  if (req.method === "POST" && goalMatch) {
    const sessionId = decodeURIComponent(goalMatch[1])
    const op = goalMatch[2]
    const body = await readAuthorizedJson(req, res, state, device, BODY_LIMIT)
    if (!body) return true
    const ref = goalRefFrom(body)
    if (!ref) return json(res, 400, { error: "缺少目标引用（ref.id / ref.revision）" }), true
    const payload = { sessionId, ref }
    if (op === "edit") {
      if (body.objective !== undefined) {
        const objective = nonEmpty(body.objective)
        if (!objective) return json(res, 400, { error: "目标内容不能为空" }), true
        if (objective.length > MAX_GOAL_OBJECTIVE) return json(res, 413, { error: "目标内容过长" }), true
        payload.objective = objective
      }
      if (body.maxGoalRounds !== undefined) {
        const rounds = body.maxGoalRounds
        if (rounds !== null && !(Number.isSafeInteger(rounds) && rounds > 0 && rounds <= 1000)) {
          return json(res, 400, { error: "轮数上限需为 1–1000 的整数" }), true
        }
        payload.maxGoalRounds = rounds
      }
      if (payload.objective === undefined && payload.maxGoalRounds === undefined) {
        return json(res, 400, { error: "没有要修改的内容" }), true
      }
    }
    const result = await mutate(() => callLocalRpc(targetPort, `goals.${op}`, payload))
    if (result.done) return true
    const value = result.value
    const nextRef = isRecord(value) && typeof value.id === "string" && Number.isSafeInteger(value.revision)
      ? { id: value.id, revision: value.revision }
      : null
    json(res, 200, { ok: true, sessionId, op, ...(nextRef ? { ref: nextRef } : {}), ...(op === "clear" ? { cleared: true } : {}) })
    return true
  }

  // ---- 定时任务 ----
  if (req.method === "GET" && pathname === "/dsh-link/mobile/schedules") {
    try {
      const items = await callLocalRpc(targetPort, "schedule.catalog", {})
      json(res, 200, { ok: true, items: Array.isArray(items) ? items : [] })
    } catch (error) {
      respondRpcError(json, res, error)
    }
    return true
  }
  const schedList = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/schedules$/)
  if (req.method === "GET" && schedList) {
    const sessionId = decodeURIComponent(schedList[1])
    try {
      const items = await callLocalRpc(targetPort, "schedule.list", { sessionId })
      json(res, 200, { ok: true, sessionId, items: Array.isArray(items) ? items : [] })
    } catch (error) {
      respondRpcError(json, res, error)
    }
    return true
  }
  const schedHistory = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/schedules\/([^/]+)\/history$/)
  if (req.method === "GET" && schedHistory) {
    const sessionId = decodeURIComponent(schedHistory[1])
    const id = decodeURIComponent(schedHistory[2])
    const search = new URL(req.url ?? "/", "http://x").searchParams
    const limit = Math.min(100, Math.max(1, Number.parseInt(search.get("limit") ?? "20", 10) || 20))
    const before = nonEmpty(search.get("before"))
    try {
      const value = await callLocalRpc(targetPort, "schedule.history", { sessionId, id, limit, ...(before ? { before } : {}) })
      json(res, 200, { ok: true, sessionId, id, value: value ?? null })
    } catch (error) {
      respondRpcError(json, res, error)
    }
    return true
  }
  const schedItem = pathname.match(/^\/dsh-link\/mobile\/sessions\/([^/]+)\/schedules\/([^/]+)$/)
  if (schedItem && (req.method === "PUT" || req.method === "DELETE")) {
    const sessionId = decodeURIComponent(schedItem[1])
    const id = decodeURIComponent(schedItem[2])
    if (req.method === "DELETE") {
      // 无请求体的写操作也要过同源 / CSRF 校验（与 readAuthorizedJson 同一道门）
      if (typeof deps.requireJsonWrite === "function" && !deps.requireJsonWrite(req, res)) return true
      const result = await mutate(() => callLocalRpc(targetPort, "schedule.delete", { sessionId, id }))
      if (result.done) return true
      json(res, 200, { ok: true, sessionId, id, deleted: true })
      return true
    }
    const body = await readAuthorizedJson(req, res, state, device, BODY_LIMIT)
    if (!body) return true
    const expected = scheduleExpectedRecord(body.expected)
    if (!expected || expected.id !== id) {
      return json(res, 400, { error: "缺少与 id 一致的原始记录（expected）" }), true
    }
    const payload = { sessionId, id, expected }
    for (const field of ["title", "prompt"]) {
      if (body[field] === undefined) continue
      if (typeof body[field] !== "string" || body[field].length > MAX_SCHEDULE_TEXT) {
        return json(res, 400, { error: `${field} 不合法` }), true
      }
      payload[field] = body[field]
    }
    if (body.change !== undefined) {
      if (!isRecord(body.change)) return json(res, 400, { error: "change 不合法" }), true
      payload.change = body.change
    }
    const result = await mutate(() => callLocalRpc(targetPort, "schedule.update", payload))
    if (result.done) return true
    json(res, 200, { ok: true, sessionId, id, value: result.value ?? null })
    return true
  }

  return false
}
