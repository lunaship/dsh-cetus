import { realpathSync } from "node:fs"
import { basename, dirname, isAbsolute, resolve, sep } from "node:path"
import { optionalString } from "./optional-string.js"

/** 跟随符号链接。末段尚不存在时，realpath 最深的已存在祖先，再接回缺失后缀。 */
function canonicalPathForContainment(p) {
  const resolved = resolve(String(p ?? ""))
  const missing = []
  let current = resolved
  while (true) {
    try {
      const real = realpathSync(current)
      return missing.length ? resolve(real, ...missing) : real
    } catch {
      const parent = dirname(current)
      if (parent === current) return resolved
      missing.unshift(basename(current))
      current = parent
    }
  }
}

function isPathWithinRoot(target, root) {
  if (target === root) return true
  const prefix = root.endsWith(sep) ? root : root + sep
  return target.startsWith(prefix)
}

/** session.create 的 cwd / workspaceId 必须落在已注册工作区内。都没给时交给 DSH 选默认工作区。 */
export function validateSessionCreateWorkspace({ cwd, workspaceId, workspaces }) {
  const roots = (Array.isArray(workspaces) ? workspaces : [])
    .map((item) => ({ workspaceId: String(item?.workspaceId ?? "").trim(), path: optionalString(item?.path) }))
    .filter((w) => w.path && isAbsolute(w.path))
  if (workspaceId) {
    const hit = roots.find((w) => w.workspaceId && w.workspaceId === workspaceId)
    if (!hit) return { error: "workspaceId 未注册或不可用" }
    return { workspaceId }
  }
  if (!cwd) return { ok: true }
  const target = canonicalPathForContainment(cwd)
  const inside = roots.some((w) => isPathWithinRoot(target, canonicalPathForContainment(w.path)))
  if (!inside) return { error: "cwd 不在已注册工作区内" }
  return { cwd: target }
}
