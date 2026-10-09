#!/usr/bin/env node
/**
 * 方案 §22.1 第 3/4 步：把旧 `dsh-links` bundle 条目迁移到 `dsh-cetus` 的**只读**助手。
 *
 * ## 为什么是"只读"
 *
 * 第 3/4 步要求「保留用户 port / stateDir / autoApprove / 远程与权限配置，
 * 不覆盖未知键、不改动其他 bundle、先产生脱敏 diff、写前备份、写后解析验证」。
 *
 * 但 profile 配置文件属于 **DSH host**，不是本插件的资产。插件在运行时写它有两个问题：
 * 1. 越过宿主边界（host 可能正持有该文件，写它会造成不一致）；
 * 2. 无法验证 —— 仓库红线禁止为验收重启用户的 host。
 *
 * 所以这里只做**能安全做且真正有用**的那一半：**读、比对、产出脱敏后的建议 diff**。
 * 真正的写入由维护者在停掉 host 后执行（见输出里的指引）。
 *
 * ## 用法
 *
 *   node scripts/profile-migrate.mjs <profile.yaml...>          # 打印建议 diff（脱敏）
 *   node scripts/profile-migrate.mjs --json <profile.yaml...>   # 机器可读结果
 *
 * 退出码：0 = 无需改动或仅打印；1 = 用法/读取错误。
 */
import { readFileSync } from "node:fs"
import { basename } from "node:path"

/** 会被迁移的旧 id。只认这两个：更早的历史名不算 bundle 条目。 */
const LEGACY_IDS = ["dsh-links"]
const CANONICAL_ID = "dsh-cetus"

/** 需要脱敏的字段名（值不回显，只回显"这个键存在"）。 */
const SECRET_KEY_PATTERN = /(token|secret|key|password|passwd|credential|pin)/i

/**
 * 从 YAML 文本里找出 bundle 条目。
 *
 * 刻意不做完整 YAML 解析：profile 文件由 host 生成，可能含块标量、锚点等；
 * 引一个 YAML 依赖去做一次性迁移不划算，而**行级缩进**在 bundles 列表里足够可靠。
 */
export function findBundleEntries(text) {
  const lines = text.split("\n")
  const entries = []
  let current = null
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const trimmed = line.trim()
    // 顶层 bundles: 段
    if (/^bundles\s*:/.test(line)) {
      current = { startLine: i, items: [] }
      continue
    }
    if (current) {
      // 离开 bundles 段（回到顶格的其它键）
      if (/^\S/.test(line) && !/^bundles\s*:/.test(line) && trimmed !== "") {
        entries.push(current)
        current = null
        continue
      }
      const idMatch = trimmed.match(/^-?\s*id\s*:\s*["']?([A-Za-z0-9._@/-]+)["']?\s*$/)
      if (idMatch) current.items.push({ line: i, id: idMatch[1] })
    }
  }
  if (current) entries.push(current)
  return entries
}

/** 找出该 bundle 条目块内的可配置字段（缩进深于 id 的那些 key: value）。 */
export function bundleFields(text, entryLine) {
  const lines = text.split("\n")
  const idIndent = lines[entryLine].match(/^\s*/)[0].length
  const fields = []
  for (let i = entryLine + 1; i < lines.length; i++) {
    const line = lines[i]
    if (line.trim() === "") continue
    const indent = line.match(/^\s*/)[0].length
    if (indent <= idIndent) break
    const m = line.trim().match(/^([A-Za-z0-9_.-]+)\s*:\s*(.*)$/)
    if (m) fields.push({ line: i, key: m[1], value: m[2].trim() })
  }
  return fields
}

/** 脱敏：敏感键的值不回显。 */
export function redactValue(key, value) {
  if (SECRET_KEY_PATTERN.test(key)) return value === "" ? "" : "<redacted>"
  return value
}

/**
 * 生成迁移计划（不写文件）。
 *
 * @returns {{ file: string, needsMigration: boolean, entries: Array<{line:number,id:string}>, plan: string[] }}
 */
export function planMigration(file, text) {
  const bundles = findBundleEntries(text)
  const legacy = []
  let canonicalPresent = false
  for (const section of bundles) {
    for (const item of section.items) {
      if (item.id === CANONICAL_ID) canonicalPresent = true
      if (LEGACY_IDS.includes(item.id)) legacy.push(item)
    }
  }

  const plan = []
  for (const item of legacy) {
    const fields = bundleFields(text, item.line)
    plan.push(`- 第 ${item.line + 1} 行：id: ${item.id} → ${CANONICAL_ID}`)
    // 第 3 步：**保留**用户配置。逐条列出会被保留的键，让维护者能核对。
    const keep = fields.filter((f) => !SECRET_KEY_PATTERN.test(f.key))
    const kept = fields.filter((f) => SECRET_KEY_PATTERN.test(f.key))
    for (const f of keep) plan.push(`    保留 ${f.key}: ${redactValue(f.key, f.value)}`)
    for (const f of kept) plan.push(`    保留 ${f.key}: <redacted>`)
    if (fields.length === 0) plan.push("    （该条目无可配置字段）")
  }
  if (canonicalPresent) {
    // 第 5 步的重复启用风险：两个 id 同时在 bundles 里会各起一个代理抢端口。
    plan.push(`⚠️ bundles 里已存在 ${CANONICAL_ID}：迁移后会**同时启用两个实例**，它们会争抢同一端口。`)
    plan.push("   请先移除旧条目，或确认只保留一个。")
  }

  return { file, needsMigration: legacy.length > 0, entries: legacy, plan }
}

/** 渲染成人可读输出。 */
export function renderReport(results) {
  const out = []
  for (const r of results) {
    out.push(`== ${r.file} ==`)
    if (!r.needsMigration) {
      out.push(`  无需迁移（未发现 ${LEGACY_IDS.join(" / ")} 条目）`)
      out.push("")
      continue
    }
    out.push(...r.plan.map((l) => `  ${l}`))
    out.push("")
  }
  out.push("下一步（由维护者执行，插件不会自动写入）：")
  out.push("  1. 停止 DSH host —— 它可能正持有该文件，热写会造成不一致。")
  out.push("  2. 备份：" + " cp <profile>.yaml <profile>.yaml.bak-$(date +%Y%m%d-%H%M%S)")
  out.push("  3. 按上面的 diff 手工改 id（**只改 id**，其余配置原样保留）。")
  out.push("  4. 改完重新解析校验（例如 host 启动时能正常读取），再启动 host。")
  out.push("  5. 若同时存在两个 id，先移除重复条目 —— 见上面的 ⚠️。")
  return out.join("\n")
}

// ---------- CLI ----------
const isMain = process.argv[1] && import.meta.url.endsWith(basename(process.argv[1]))
if (isMain) {
  const args = process.argv.slice(2)
  const asJson = args.includes("--json")
  const files = args.filter((a) => !a.startsWith("--"))
  if (files.length === 0) {
    console.error("用法: node scripts/profile-migrate.mjs [--json] <profile.yaml...>")
    process.exit(1)
  }
  const results = []
  try {
    for (const f of files) results.push(planMigration(f, readFileSync(f, "utf8")))
  } catch (err) {
    console.error(`读取失败：${err?.message ?? err}`)
    process.exit(1)
  }
  if (asJson) {
    console.log(JSON.stringify(results, null, 2))
  } else {
    console.log(renderReport(results))
  }
}
