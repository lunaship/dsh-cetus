import { omitNullFields, optionalString } from "./optional-string.js"

/**
 * [extra.awaitingInput]：插件自记的「有未结束的审批 / 澄清问题」（DSH 列表不带），
 * 只在为真时下发，旧 App 忽略该键。
 */
export function mobileSessionSummary(item, extra = {}) {
  const projections = item?.projections?.values ?? {}
  const title = typeof projections.title === "string" && projections.title.trim()
    ? projections.title.trim()
    : "未命名会话"
  const parentSessionId = item.parentSessionId
    ?? item.parentSession?.sessionId
    ?? item.parentSession?.id
    ?? item.spawn?.parentSessionId
    ?? null
  const subagentCountRaw = item.subagentCount ?? item.activeSubagentCount
    ?? (Array.isArray(item.subagents) ? item.subagents.length : null)
  return omitNullFields({
    sessionId: item.sessionId,
    title,
    updatedAt: item.updatedAt,
    running: Boolean(item.running),
    blank: Boolean(item.blank),
    cwd: optionalString(item.cwd),
    agentPreset: optionalString(item.agentPreset),
    origin: optionalString(item.origin),
    parentSessionId: optionalString(parentSessionId),
    subagentCount: Number.isFinite(subagentCountRaw) ? subagentCountRaw : null,
    awaitingInput: extra.awaitingInput ? true : null,
  })
}
