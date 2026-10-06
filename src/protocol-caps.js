/** 业务层能力协商。远程通道（DLP/1）与内层证书钉扎不因这些字段改变。 */

import { MAX_WORKSPACE_DIR_ENTRIES, MAX_WORKSPACE_FILE_BYTES } from "./workspace-file.js"
import { MAX_DIFF_LINES } from "./workspace-changes.js"

export const PLUGIN_PROTOCOL = 2
export const CAP_SYNC2 = "sync2"
export const CAP_MULTI_QUESTION = "multiQuestion"
export const CAP_REQUEST_STATE = "requestState"
export const RECONNECT_GRACE_MS = 30_000

export const CLIENT_CAPS_QUERY = `${CAP_SYNC2},${CAP_MULTI_QUESTION},${CAP_REQUEST_STATE}`

export function parseClientCaps(raw) {
  const set = new Set(String(raw ?? "").split(",").map((item) => item.trim()).filter(Boolean))
  return {
    sync2: set.has(CAP_SYNC2),
    multiQuestion: set.has(CAP_MULTI_QUESTION),
    requestState: set.has(CAP_REQUEST_STATE),
  }
}

/**
 * @param {{ changes?: boolean }} [host] changes：Host 挂载了 workspaceChanges 服务
 *   （DSH 0.1.7 起的 `@deepseek-ai/dsh-workspace-changes`）。旧 App 忽略未知字段。
 */
export function pluginCapabilities({ changes = false } = {}) {
  return {
    protocol: PLUGIN_PROTOCOL,
    sync: { resync: true, catchupIntegrity: true },
    questions: { multi: true, serverValidation: true },
    requests: { snapshot: true, reconnectGraceMs: RECONNECT_GRACE_MS },
    // 排队消息管理、目标操作、定时任务（DSH 不支持的 Host 上会返回 400/502，App 据此隐藏入口）
    control: { queue: true, goals: true, schedules: true },
    files: {
      workspace: true,
      maxBytes: MAX_WORKSPACE_FILE_BYTES,
      tree: true,
      treeMaxEntries: MAX_WORKSPACE_DIR_ENTRIES,
      sha256: true,
      ...(changes ? { changes: true, diff: true, diffMaxLines: MAX_DIFF_LINES } : {}),
    },
    // 连接诊断。旧 App 忽略未知字段；没声明时 App 不展示主机诊断报告。
    diagnostics: { v: 1 },
    // 主机级会话状态流。旧 App 忽略未知字段，仍只订阅单个会话。
    events: { host: true },
    // 可选后台推送。旧 App 忽略未知字段；网关未部署，不表示真实 APNs 已可用。
    push: { v: 1 },
    // 已批准的本机预览端口。detect=1 表示可以列出工具输出里看到的端口。旧 App 忽略未知字段。手机不能批准端口。
    preview: { v: 1, detect: 1 },
  }
}

export function anyConnHasCap(writers, cap) {
  for (const conn of writers ?? []) {
    if (conn?.caps?.[cap]) return true
  }
  return false
}

export function subscribedDeviceIds(writers) {
  const ids = new Set()
  for (const conn of writers ?? []) {
    if (conn?.deviceId) ids.add(conn.deviceId)
  }
  return ids
}
