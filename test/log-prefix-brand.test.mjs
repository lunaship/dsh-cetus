/**
 * 防回归：src/ 下不得再出现旧品牌日志前缀 `dsh-links:`。
 *
 * 背景：2026-10-08 插件规范名从 dsh-links 改为 dsh-cetus（B1/task-1）。
 * B1 只改了 src/index.js，src/ 其余文件里 14 处 `dsh-links:` 日志前缀被漏掉，
 * 而 SECURITY.md 的审计说明已按代码事实写成 `dsh-cetus:`。
 * 后果是**审计日志前缀不一致**：运维按 `dsh-cetus:` grep 审计事件会漏掉一半，
 * 这是功能性缺陷而非文案问题（B8/task-9）。
 *
 * 这类"改名漏改"靠人眼 review 会反复漏（B1 就是这么漏的），因此用测试钉住。
 *
 * 注意区分「日志前缀」与「目录路径」：
 *   - `dsh-links:`（带冒号，出现在字符串里）= 日志前缀 → 禁止
 *   - `"dsh-links"` / `~/.dsh/dsh-links`（目录名，如 LEGACY_STATE_DIRS 的兼容回退）= 允许保留
 */
import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync, readdirSync, existsSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const srcDir = join(root, "src")

/** 收集 src 顶层与一层子目录里的 .js 源文件（与插件现状层级一致）。 */
function collectSourceFiles() {
  const out = []
  for (const entry of readdirSync(srcDir, { withFileTypes: true })) {
    if (entry.isFile() && entry.name.endsWith(".js")) out.push(join(srcDir, entry.name))
    else if (entry.isDirectory()) {
      const sub = join(srcDir, entry.name)
      for (const inner of readdirSync(sub, { withFileTypes: true })) {
        if (inner.isFile() && inner.name.endsWith(".js")) out.push(join(sub, inner.name))
      }
    }
  }
  return out
}

test("src/ 下不存在旧品牌日志前缀 dsh-links:", () => {
  const files = collectSourceFiles()
  assert.ok(files.length > 0, "未扫描到任何 src 源文件，测试自身失效")

  const offenders = []
  for (const file of files) {
    const lines = readFileSync(file, "utf8").split("\n")
    lines.forEach((line, i) => {
      if (line.includes("dsh-links:")) {
        offenders.push(`${file.slice(root.length + 1)}:${i + 1}: ${line.trim().slice(0, 100)}`)
      }
    })
  }
  assert.deepEqual(offenders, [],
    `发现旧日志前缀 dsh-links:（应为 dsh-cetus:，审计 grep 会漏）：\n${offenders.join("\n")}`)
})

test("兼容回退用的 dsh-links 目录名必须保留（不得被顺手清掉）", () => {
  const migration = join(srcDir, "state-migration.js")
  assert.ok(existsSync(migration), "state-migration.js 缺失")
  assert.match(readFileSync(migration, "utf8"), /PRIOR_DIR_NAME\s*=\s*"dsh-links"/,
    "PRIOR_DIR_NAME 必须仍是 dsh-links：旧目录是迁移来源与回滚点，清掉会丢用户配对数据")
})

test("mobile API catch-all 走宿主 logger，而不是 console.error", () => {
  const src = readFileSync(join(srcDir, "mobile-api.js"), "utf8")
  assert.equal(src.includes("console.error(`dsh-cetus: mobile API error"),
    false, "catch-all 又用回 console.error：宿主日志看不到真因，排障会被吞掉")
  assert.match(src, /logger\?\.(warn|error)\?\.\(`dsh-cetus: mobile API error/,
    "mobile API catch-all 必须经 logger 上报")
})
