import { strict as assert } from "node:assert"
import { test } from "node:test"

import {
  SCENARIOS,
  SCENARIO_SET_VERSION,
  validateScenarios,
} from "../scripts/e2e-scenarios.mjs"
import {
  CONTRACT_VERSION,
  androidBuildConfigFields,
  collectBuildMetadata,
  iosPlistEntries,
  render,
} from "../scripts/build-metadata.mjs"

// ---------- fixture 场景集（方案 §4 第 4 条） ----------

test("场景集覆盖方案要求的十一个场景", () => {
  const required = [
    "unpaired",
    "pending-approval",
    "online-home",
    "offline-cache",
    "streaming-session",
    "approval",
    "free-answer",
    "changes",
    "files",
    "preview",
    "settings",
  ]
  const keys = SCENARIOS.map((s) => s.key)
  assert.deepEqual(keys, required)
  assert.ok(SCENARIO_SET_VERSION >= 1)
})

test("场景 id 唯一且都有期望结果", () => {
  const ids = SCENARIOS.map((s) => s.id)
  assert.equal(new Set(ids).size, ids.length)
  for (const scenario of SCENARIOS) {
    assert.ok(scenario.expect.length > 0, `${scenario.id} 缺少期望结果`)
    assert.ok(scenario.setup.length > 0, `${scenario.id} 缺少搭建步骤`)
  }
})

test("场景引用的 fixture 常量在 ios-e2e-fixture 里都存在", () => {
  assert.deepEqual(validateScenarios(), [])
})

// ---------- 构建元数据（方案 §4 C00） ----------

test("构建元数据含提交、日期、配置与合同版本", () => {
  const meta = collectBuildMetadata({ config: "Release", date: new Date("2026-10-08T03:00:00Z") })
  assert.equal(meta.date, "2026-10-08")
  assert.equal(meta.configuration, "Release")
  assert.equal(meta.contractVersion, CONTRACT_VERSION)
  assert.match(meta.commit, /^([0-9a-f]{6,40}(-dirty)?|unknown)$/)
})

test("iOS 与 Android 的键名一致地承载同样四个字段", () => {
  const meta = collectBuildMetadata({ config: "Debug", date: new Date("2026-10-08T03:00:00Z") })
  const ios = iosPlistEntries(meta)
  const android = androidBuildConfigFields(meta)
  assert.deepEqual(Object.values(ios), Object.values(android))
})

test("生成的 iOS xcconfig 可被 Xcode 解析（每行 key = value）", () => {
  const meta = collectBuildMetadata({ config: "Debug", date: new Date("2026-10-08T03:00:00Z") })
  const text = render("ios", meta)
  const lines = text.trim().split("\n")
  assert.equal(lines.length, 4)
  for (const line of lines) {
    assert.match(line, /^[A-Za-z][A-Za-z0-9_]* = \S+$/, `bad xcconfig line: ${line}`)
  }
})

test("脏工作树的提交带 -dirty 后缀，不冒充干净提交", () => {
  const meta = collectBuildMetadata({ config: "Debug" })
  if (meta.dirty) assert.match(meta.commit, /-dirty$/)
  else assert.doesNotMatch(meta.commit, /-dirty$/)
})

test("未知平台直接报错，不静默生成错文件", () => {
  assert.throws(() => render("windows", collectBuildMetadata({})), /未知 platform/)
})
