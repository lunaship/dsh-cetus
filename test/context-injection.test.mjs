/**
 * 上下文注入判定与 App 共用 testdata/context-injection-cases.json。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { isContextInjectionText } from "../src/context-injection.js"

const cases = JSON.parse(
  readFileSync(new URL("../testdata/context-injection-cases.json", import.meta.url), "utf8"),
).cases

test("shared context-injection cases", () => {
  assert.ok(cases.length >= 10, "用例清单太短，可能没读到文件")
  for (const c of cases) {
    assert.equal(isContextInjectionText(c.text), c.injection, c.name)
  }
})
