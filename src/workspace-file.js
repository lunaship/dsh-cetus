import { isAbsolute, relative, resolve, sep } from "node:path"
import { lstatSync, readdirSync, realpathSync, statSync } from "node:fs"

export const MAX_WORKSPACE_FILE_BYTES = 8 * 1024 * 1024
export const MAX_WORKSPACE_DIR_ENTRIES = 2000

export function resolveWorkspaceFile(cwd, requested) {
  if (typeof cwd !== "string" || !cwd.trim()) {
    const err = new Error("缺少工作区")
    err.status = 400
    throw err
  }
  if (typeof requested !== "string" || !requested.trim()) {
    const err = new Error("缺少 path")
    err.status = 400
    throw err
  }
  const root = realpathSync(resolve(cwd.trim()))
  const candidate = isAbsolute(requested.trim()) ? resolve(requested.trim()) : resolve(root, requested.trim())
  const lexicalRel = relative(root, candidate)
  if (lexicalRel.startsWith("..") || isAbsolute(lexicalRel)) {
    const err = new Error("路径越出工作区")
    err.status = 403
    throw err
  }
  let real
  try {
    real = realpathSync(candidate)
  } catch {
    const err = new Error("文件不存在")
    err.status = 404
    throw err
  }
  const rel = relative(root, real)
  if (!rel || rel.startsWith("..") || isAbsolute(rel)) {
    const err = new Error("路径越出工作区")
    err.status = 403
    throw err
  }
  const st = statSync(real)
  if (!st.isFile()) {
    const err = new Error("不是文件")
    err.status = 400
    throw err
  }
  if (st.size > MAX_WORKSPACE_FILE_BYTES) {
    const err = new Error("文件过大")
    err.status = 413
    throw err
  }
  return {
    abs: real,
    rel: rel.split("\\").join("/"),
    size: st.size,
    name: real.slice(real.lastIndexOf(sep) + 1),
  }
}

function httpError(message, status) {
  const err = new Error(message)
  err.status = status
  return err
}

function insideRoot(root, real) {
  const rel = relative(root, real)
  return rel === "" || (!rel.startsWith(`..${sep}`) && rel !== ".." && !isAbsolute(rel))
}

/**
 * 列出会话 cwd 沙箱内的一层目录（手机文件树按层懒加载）。
 * - requested 为空 / "." 即工作区根；越界（含经符号链接越界）403，不存在 404，不是目录 400。
 * - 条目：目录在前、同类按名称码元序；最多 MAX_WORKSPACE_DIR_ENTRIES 条，超出置 truncated。
 * - 符号链接：目标仍在工作区内时按目标类型给出并带 link: true；越界或断链为 type "symlink" + outside: true，不可进入。
 */
export function listWorkspaceDir(cwd, requested) {
  if (typeof cwd !== "string" || !cwd.trim()) throw httpError("缺少工作区", 400)
  const root = realpathSync(resolve(cwd.trim()))
  const raw = typeof requested === "string" ? requested.trim() : ""
  const candidate = raw === "" || raw === "." ? root : (isAbsolute(raw) ? resolve(raw) : resolve(root, raw))
  if (!insideRoot(root, candidate)) throw httpError("路径越出工作区", 403)
  let real
  try {
    real = realpathSync(candidate)
  } catch {
    throw httpError("目录不存在", 404)
  }
  if (!insideRoot(root, real)) throw httpError("路径越出工作区", 403)
  if (!statSync(real).isDirectory()) throw httpError("不是目录", 400)

  const dirents = readdirSync(real, { withFileTypes: true })
  const entries = []
  for (const d of dirents) {
    const abs = resolve(real, d.name)
    const entry = { name: d.name }
    try {
      if (d.isSymbolicLink()) {
        let target = null
        try {
          target = realpathSync(abs)
        } catch {
          target = null
        }
        if (!target || !insideRoot(root, target)) {
          entries.push({ ...entry, type: "symlink", outside: true })
          continue
        }
        const st = statSync(target)
        entry.link = true
        Object.assign(entry, describeStat(st))
      } else {
        Object.assign(entry, describeStat(lstatSync(abs)))
      }
    } catch {
      entry.type = "other"
    }
    entries.push(entry)
  }
  const rank = (e) => (e.type === "dir" ? 0 : 1)
  entries.sort((a, b) => rank(a) - rank(b) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
  const rel = relative(root, real).split("\\").join("/")
  return {
    path: rel,
    total: entries.length,
    truncated: entries.length > MAX_WORKSPACE_DIR_ENTRIES,
    entries: entries.slice(0, MAX_WORKSPACE_DIR_ENTRIES),
  }
}

function describeStat(st) {
  if (st.isDirectory()) return { type: "dir" }
  if (st.isFile()) return { type: "file", size: st.size, mtimeMs: Math.trunc(st.mtimeMs) }
  return { type: "other" }
}

const MIME_TYPES = {
  png: "image/png",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  gif: "image/gif",
  webp: "image/webp",
  svg: "image/svg+xml",
  md: "text/markdown; charset=utf-8",
  txt: "text/plain; charset=utf-8",
  json: "application/json; charset=utf-8",
  html: "text/html; charset=utf-8",
  pdf: "application/pdf",
}

export function mimeFromName(name) {
  const ext = String(name ?? "").split(".").pop()?.toLowerCase()
  if (!ext || !Object.prototype.hasOwnProperty.call(MIME_TYPES, ext)) return "application/octet-stream"
  return MIME_TYPES[ext]
}
