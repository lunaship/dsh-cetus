/**
 * 合同 fixtures 守门（PLAN I3.2）：脚本生成的样本必须与磁盘上的
 * testdata/mobile-contract/*.json 完全一致（含文件集合）。
 *
 * 插件改了手机响应结构但没重新导出 fixtures 时，在这里失败——这正是验收标准
 * 「插件改了响应结构但没更新 fixtures 时，插件测试失败」。失败后运行：
 *   node scripts/export-contract-fixtures.mjs
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { readdirSync, readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { dirname, join } from "node:path"
import { buildContractFixtures } from "../scripts/export-contract-fixtures.mjs"

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))
const OUT_DIR = join(ROOT, "testdata", "mobile-contract")
const REGEN_HINT = "node scripts/export-contract-fixtures.mjs"

test("手机合同 fixtures 与插件当前响应一致", async () => {
  const fixtures = await buildContractFixtures()
  const expectedNames = Object.keys(fixtures).sort()

  const diskNames = readdirSync(OUT_DIR).filter((name) => name.endsWith(".json")).sort()
  assert.deepEqual(
    diskNames,
    expectedNames,
    `fixtures 文件集合与脚本输出不一致，运行 ${REGEN_HINT} 更新 testdata/mobile-contract/`,
  )

  for (const name of expectedNames) {
    const disk = JSON.parse(readFileSync(join(OUT_DIR, name), "utf8"))
    assert.deepEqual(
      disk,
      fixtures[name],
      `fixture ${name} 与插件当前响应不一致（手机合同可能已变更），运行 ${REGEN_HINT} 重新生成`,
    )
  }
})
