/**
 * 方案 §22.1 第 3/4 步：profile bundle 条目迁移的**只读**助手。
 *
 * 它不写任何文件 —— profile 属于 DSH host，插件写它属越界，
 * 且红线禁止为验收重启用户 host。所以这里只验「读得准、脱敏对、建议对」。
 */
import assert from "node:assert/strict"
import test from "node:test"
import {
  bundleFields, findBundleEntries, planMigration, redactValue, renderReport,
} from "../scripts/profile-migrate.mjs"

const PROFILE = `# DSH profile
model: step-5
bundles:
  - id: dsh-links
    port: 18640
    stateDir: /Users/someone/.dsh/custom
    autoApprove: true
    token: super-secret-value
  - id: some-other-plugin
    port: 1234
permissions:
  workspaceWrite: true
`

test("能找出 bundle 条目，且不把其它段当条目", () => {
  const sections = findBundleEntries(PROFILE)
  const ids = sections.flatMap((s) => s.items.map((i) => i.id))
  assert.deepEqual(ids, ["dsh-links", "some-other-plugin"])
})

test("只识别旧 id，不误伤其它 bundle", () => {
  const r = planMigration("p.yaml", PROFILE)
  assert.equal(r.needsMigration, true)
  assert.equal(r.entries.length, 1)
  assert.equal(r.entries[0].id, "dsh-links")
})

test("保留用户配置：port / stateDir / autoApprove 都出现在建议里", () => {
  const r = planMigration("p.yaml", PROFILE)
  const text = r.plan.join("\n")
  assert.match(text, /port: 18640/, "必须保留端口")
  assert.match(text, /stateDir: \/Users\/someone\/\.dsh\/custom/, "必须保留自定义 stateDir")
  assert.match(text, /autoApprove: true/, "必须保留 autoApprove")
})

test("敏感键脱敏：值不回显", () => {
  const r = planMigration("p.yaml", PROFILE)
  const text = r.plan.join("\n")
  assert.equal(text.includes("super-secret-value"), false, "密钥值绝不能出现在输出里")
  assert.match(text, /token: <redacted>/, "应标记为已脱敏")
})

test("脱敏规则覆盖常见敏感键名", () => {
  for (const key of ["token", "apiKey", "api_key", "secret", "password", "credential", "outerPin", "pin"]) {
    assert.equal(redactValue(key, "v"), "<redacted>", `${key} 应被脱敏`)
  }
  for (const key of ["port", "stateDir", "autoApprove", "enabled"]) {
    assert.equal(redactValue(key, "v"), "v", `${key} 不该被脱敏（否则维护者核对不了）`)
  }
})

test("两个 id 同时存在时给出重复启用警告（§22.1 第 5 步）", () => {
  const both = PROFILE.replace("  - id: some-other-plugin", "  - id: dsh-cetus")
  const r = planMigration("p.yaml", both)
  const text = r.plan.join("\n")
  assert.match(text, /同时启用两个实例/, "必须警告重复启用")
  assert.match(text, /竞?争抢同一端口|争抢同一端口/, "要说明后果是抢端口")
})

test("没有旧条目时明确说无需迁移", () => {
  const clean = "bundles:\n  - id: dsh-cetus\n    port: 18640\n"
  const r = planMigration("p.yaml", clean)
  assert.equal(r.needsMigration, false)
  assert.match(renderReport([r]), /无需迁移/)
})

test("bundleFields 不会跨条目读取（缩进边界正确）", () => {
  const sections = findBundleEntries(PROFILE)
  const first = sections[0].items[0]
  const fields = bundleFields(PROFILE, first.line)
  const keys = fields.map((f) => f.key)
  assert.deepEqual(keys, ["port", "stateDir", "autoApprove", "token"], "不应吃掉下一个条目的 port")
})

test("报告给出维护者可执行的下一步，且明确插件不自动写入", () => {
  const r = planMigration("p.yaml", PROFILE)
  const report = renderReport([r])
  assert.match(report, /停止 DSH host/, "必须要求先停 host")
  assert.match(report, /备份/, "必须要求先备份")
  assert.match(report, /插件不会自动写入/, "必须说明本工具不写文件")
  assert.equal(report.includes("super-secret-value"), false)
})

test("多行块标量等复杂内容不导致崩溃（行级解析的容错）", () => {
  const weird = "bundles:\n  - id: dsh-links\n    stateDir: |\n      /multi\n      /line\n    port: 18640\nother: 1\n"
  const r = planMigration("p.yaml", weird)
  assert.equal(r.needsMigration, true)
  // 不要求解析块标量内容，只要求不抛错、且能识别条目与后续字段。
  assert.ok(r.plan.length > 0)
})
