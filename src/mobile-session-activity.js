import { omitNullFields } from "./optional-string.js"
import { projectChangesSummary } from "./workspace-changes.js"

/**
 * 会话「当前步骤」与「结果一句话」（重设计 2026-09-28 · 方案阶段 2）。
 *
 * 首页每行要能回答「它现在在干什么」，但 DSH 的会话列表接口不带这类信息，
 * 只能由插件从 `session.history` 的事件流里推导。所以这里是**纯函数**：
 * 输入是 session.history 返回的 `events`（`{ event: { seq, time, type, data } }`），
 * 不碰网络、不碰 runtime，便于单测覆盖各种事件序列。
 *
 * 两个字段都是「有则下发、没有就不发」（omitNullFields），旧 App 直接忽略：
 *
 * - `activity`：running 会话当前在做的那一步（未结束的工具调用，或思考/写作中）；
 * - `lastResult`：已结束会话的最后一条回复摘要 + 本轮改动统计。
 *
 * 长度上限按手机单行副标题来定（60 字），超出截断加省略号；标题文案由 App 组装，
 * 插件只给内容片段，不写「正在运行」「完成」这类界面词。
 */

/** 单行副标题的内容上限（字符）。 */
export const MAX_ACTIVITY_LABEL_CHARS = 60
/** 「结果一句话」的内容上限（字符）。 */
export const MAX_RESULT_TEXT_CHARS = 60

function eventOf(item) {
  const e = item?.event
  return e && typeof e === "object" && typeof e.seq === "number" ? e : null
}

/** 压成一行并截断；空串返回 null（调用方据此不下发字段）。 */
export function collapseToLine(raw, maxChars) {
  const text = String(raw ?? "").replace(/\s+/g, " ").trim()
  if (!text) return null
  return text.length > maxChars ? `${text.slice(0, maxChars - 1).trimEnd()}…` : text
}

/**
 * 工具参数里值得写进副标题的键，按优先级排列。
 * 命令行工具只要命令本身（设计稿要的是「正在运行 go test ./...」而不是「shell: {...}」）。
 */
const PRIMARY_ARG_KEYS = [
  "command", "cmd", "file_path", "filePath", "path", "pattern", "query", "url",
  "description", "prompt", "content",
]

function parseArguments(raw) {
  if (raw && typeof raw === "object") return raw
  if (typeof raw !== "string") return null
  const text = raw.trim()
  if (!text.startsWith("{")) return null
  try {
    const parsed = JSON.parse(text)
    return parsed && typeof parsed === "object" ? parsed : null
  } catch {
    return null
  }
}

/** 工具调用 → 一行可读标签。取不到参数时退回工具名。 */
export function summarizeToolCall(name, rawArguments) {
  const tool = typeof name === "string" && name.trim() ? name.trim() : "tool"
  const args = parseArguments(rawArguments)
  if (args) {
    for (const key of PRIMARY_ARG_KEYS) {
      const value = args[key]
      if (typeof value !== "string" || !value.trim()) continue
      // 命令本身就是最好的说明，不再带工具名前缀
      if (key === "command" || key === "cmd") return collapseToLine(value, MAX_ACTIVITY_LABEL_CHARS)
      return collapseToLine(`${tool} ${value.trim()}`, MAX_ACTIVITY_LABEL_CHARS)
    }
    for (const value of Object.values(args)) {
      if (typeof value === "string" && value.trim()) {
        return collapseToLine(`${tool} ${value.trim()}`, MAX_ACTIVITY_LABEL_CHARS)
      }
    }
  } else if (typeof rawArguments === "string" && rawArguments.trim()) {
    return collapseToLine(`${tool} ${rawArguments.trim()}`, MAX_ACTIVITY_LABEL_CHARS)
  }
  return collapseToLine(tool, MAX_ACTIVITY_LABEL_CHARS)
}

/**
 * 当前步骤：最近一次**未结束**的工具调用；没有未结束调用时退回流式状态。
 *
 * - `kind: "tool"`：`label` 是命令/参数摘要，`step` 是本轮步号（事件里带才有）；
 * - `kind: "thinking" | "writing"`：模型正在推理 / 正在写回复，`label` 为 null（App 本地化）；
 * - 返回 null 表示推导不出（旧 Host 事件里没有这些类型时）。
 */
export function deriveActivity(events) {
  const list = Array.isArray(events) ? events : []
  const openCalls = new Map()
  const callOrder = []
  let streaming = null

  for (const item of list) {
    const e = eventOf(item)
    if (!e) continue
    const data = e.data ?? {}
    if (e.type === "tool/call") {
      const callId = data.callId ?? `seq-${e.seq}`
      openCalls.set(callId, { name: data.name, arguments: data.arguments, step: data.step, time: e.time })
      callOrder.push(callId)
      streaming = null
    } else if (e.type === "tool/result") {
      if (data.callId != null) openCalls.delete(data.callId)
    } else if (e.type === "assistant/chunk") {
      const type = data.chunk?.type
      if (type === "reasoning-delta") streaming = { kind: "thinking", time: e.time }
      else if (type === "text-delta") streaming = { kind: "writing", time: e.time }
    } else if (e.type === "assistant/message" || e.type === "turn/end" || e.type === "user/message") {
      streaming = null
    }
  }

  for (let i = callOrder.length - 1; i >= 0; i--) {
    const rec = openCalls.get(callOrder[i])
    if (!rec) continue
    return omitNullFields({
      kind: "tool",
      label: summarizeToolCall(rec.name, rec.arguments),
      step: Number.isFinite(rec.step) ? rec.step : null,
      startedAt: Number.isFinite(rec.time) ? rec.time : null,
    })
  }
  if (streaming) {
    return omitNullFields({ kind: streaming.kind, label: null, step: null, startedAt: streaming.time })
  }
  return null
}

/**
 * 去掉 Markdown 记号，只留可读正文。
 * 不追求完整解析：目标是把一段回复压成能放进单行的纯文本，宁可多丢符号也不要漏出 `**` 或 ```。
 */
/**
 * 这一轮是怎么结束的（`turn/end` 的 `reason.kind`）。返回 null 表示正常完成或没找到，
 * 否则是 interrupted / stopped / error / maxTokens 之类。与 history.js 的会话详情口径一致。
 *
 * 放在这里是因为同一遍已经读了 session.history——列表页不额外拉一次历史。
 */
export function deriveStoppedReason(events) {
  const list = Array.isArray(events) ? events : []
  for (let i = list.length - 1; i >= 0; i--) {
    const e = eventOf(list[i])
    if (!e || e.type !== "turn/end") continue
    const kind = e.data?.reason?.kind ?? null
    return kind && kind !== "completed" ? kind : null
  }
  return null
}

export function stripMarkdown(raw) {
  let text = String(raw ?? "")
  text = text.replace(/```[\s\S]*?```/g, " ")          // 围栏代码块
  text = text.replace(/`([^`]*)`/g, "$1")              // 行内代码
  text = text.replace(/!\[[^\]]*\]\([^)]*\)/g, " ")    // 图片
  text = text.replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")  // 链接留文字
  text = text.replace(/^\s{0,3}#{1,6}\s+/gm, "")       // 标题
  text = text.replace(/^\s{0,3}>\s?/gm, "")            // 引用
  text = text.replace(/^\s{0,3}([-*+]|\d+\.)\s+/gm, "") // 列表符号
  text = text.replace(/^\s*\|.*\|\s*$/gm, " ")         // 表格行
  text = text.replace(/[*_~]{1,3}/g, "")               // 强调记号
  return text.replace(/\s+/g, " ").trim()
}

/**
 * 结果一句话：最后一条助手回复的首段纯文本 + 本轮改动统计。
 *
 * `changesSummary` 与 `projectHistoryPage` 同一个回调（`(seq) => DSH 原始摘要`），
 * 取不到（Host 重启后旧轮次没有摘要）时只丢统计，不影响文本。
 * 两者都没有则返回 null，调用方不下发字段。
 */
export function deriveLastResult(events, changesSummary) {
  const list = Array.isArray(events) ? events : []
  let lastText = null
  let changesSeq = null

  for (const item of list) {
    const e = eventOf(item)
    if (!e) continue
    const data = e.data ?? {}
    if (e.type === "assistant/chunk") {
      const block = data.chunk?.type === "block-end" ? data.chunk.block : null
      if (block?.type === "text" && String(block.text ?? "").trim()) lastText = block.text
    } else if (e.type === "assistant/message") {
      const text = (data.message?.content ?? [])
        .filter((block) => block?.type === "text")
        .map((block) => block.text ?? "")
        .join("\n")
      if (text.trim()) lastText = text
    } else if (e.type === "workspace/changes") {
      changesSeq = e.seq
    }
  }

  let stats = null
  if (changesSeq !== null && typeof changesSummary === "function") {
    try {
      // maxFiles: 1 —— 只要 total/added/deleted 三个数，不在列表接口里搬整份文件清单
      stats = projectChangesSummary(changesSummary(changesSeq), { maxFiles: 1 })
    } catch {
      stats = null
    }
  }

  const text = collapseToLine(stripMarkdown(lastText), MAX_RESULT_TEXT_CHARS)
  const files = Number.isFinite(stats?.total) ? stats.total : null
  const added = Number.isFinite(stats?.added) ? stats.added : null
  const deleted = Number.isFinite(stats?.deleted) ? stats.deleted : null
  if (text === null && files === null) return null
  return omitNullFields({ text, files, added, deleted })
}
