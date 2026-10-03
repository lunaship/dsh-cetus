import assert from "node:assert/strict"
import test from "node:test"
import { checkCatalog, formatTypes } from "./check-ios-locales.mjs"

function types(text) {
  return Object.fromEntries(formatTypes(text))
}

test("ignores escaped percent and matches positional types out of order", () => {
  assert.deepEqual(types("100%% then %2$@ and %1$d"), { 1: "d", 2: "@" })
  assert.deepEqual(types("%1$d then %2$@"), { 1: "d", 2: "@" })
})

test("treats length modifiers as part of the type", () => {
  assert.deepEqual(types("%ld"), { 1: "ld" })
  assert.notDeepEqual(types("%d"), types("%ld"))
})

test("rejects a specifier it cannot parse", () => {
  assert.throws(() => formatTypes("bad %q"), /无法解析格式符/)
})

test("reports a d-versus-object mismatch", () => {
  const problems = checkCatalog({
    sourceLanguage: "en",
    strings: {
      "goal.round": {
        localizations: {
          en: { stringUnit: { state: "translated", value: "Round %d" } },
          "zh-Hans": { stringUnit: { state: "translated", value: "第 %@ 轮" } },
        },
      },
    },
  })
  assert.equal(problems.length, 1)
  assert.match(problems[0], /格式符类型不一致/)
})

test("reports a missing language and an empty value", () => {
  const problems = checkCatalog({
    sourceLanguage: "en",
    strings: {
      "only.en": {
        localizations: {
          en: { stringUnit: { state: "translated", value: "Hello" } },
        },
      },
      blank: {
        localizations: {
          en: { stringUnit: { state: "translated", value: "" } },
          "zh-Hans": { stringUnit: { state: "translated", value: "空" } },
        },
      },
    },
  })
  assert.ok(problems.some((problem) => problem.includes("only.en") && problem.includes("缺少简体中文")))
  assert.ok(problems.some((problem) => problem.includes("blank") && problem.includes("英文为空")))
})
