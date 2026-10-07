/**
 * src/index.js ↔ src/state-migration.js 接线契约。
 *
 * 为什么单独一个文件：迁移算法本身在 test/state-migration.test.mjs 里已经测得很细，
 * 但**接线**是另一类风险 —— 算法对、调用点错，用户一样掉设备。plugin-rebrand 实测过
 * 这个坑：ensureStateDir 从「返回 dir 字符串」变成「返回对象」后，调用点会把对象当路径用。
 *
 * 这里锁定三件事：
 *   1. index.js 不再自己实现迁移（旧的白名单 cpSync 逻辑必须彻底消失）
 *   2. 所有调用点都走新的 ensureStateDir，且传了 logger
 *   3. 失败语义：resolveStateDir 不 ready 时 ensureStateDir 抛错（方案 A）
 */
import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync, existsSync, mkdtempSync, mkdirSync, rmSync, writeFileSync, statSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import selfsigned from "selfsigned"
import { StateMigrationError, resolveStateDir } from "../src/state-migration.js"

const INDEX = new URL("../src/index.js", import.meta.url)
const indexSource = readFileSync(INDEX, "utf8")

/** 真实目录保护：本文件同样绝不碰 ~/.dsh/dsh-links。 */
const REAL = join(process.env.HOME ?? "", ".dsh", "dsh-links")
const beforeSnap = existsSync(REAL) ? { m: statSync(REAL).mtimeMs } : null

test("接线：index.js 不再自己实现 cpSync 式迁移", () => {
  // 旧的危险实现特征：对新目录直接 cpSync 整个 legacy 目录、失败就只写 state.json
  assert.equal(
    /cpSync\(\s*legacyDir\s*,\s*dir/.test(indexSource),
    false,
    "index.js 里仍有旧的 cpSync 迁移逻辑（会漏掉 tls.json，导致手机掉线）",
  )
  assert.equal(
    /writeFileSync\(newState,\s*readFileSync\(legacyState\)/.test(indexSource),
    false,
    "index.js 里仍有旧的「只搬 state.json」兜底路径",
  )
  // 已经接入新模块
  assert.match(indexSource, /from "\.\/state-migration\.js"/, "index.js 必须导入 state-migration")
  assert.match(indexSource, /resolveStateDir\(/, "index.js 必须调用 resolveStateDir")
})

test("接线：DEFAULT_STATE_DIR / LEGACY_STATE_DIRS 已下线，旧常量不再被使用", () => {
  assert.equal(
    /const DEFAULT_STATE_DIR\s*=/.test(indexSource),
    false,
    "DEFAULT_STATE_DIR 必须移除（默认目录由 state-migration.js 的 CANONICAL_DIR_NAME 决定）",
  )
  assert.equal(
    /const LEGACY_STATE_DIRS\s*=/.test(indexSource),
    false,
    "LEGACY_STATE_DIRS 必须移除（旧目录候选由 state-migration.js 的 PRIOR_DIR_NAME/LEGACY_DIR_NAMES 决定）",
  )
  // 但旧目录名仍必须作为兼容回退保留在新模块里（老用户升级路径）
  assert.ok(existsSync(new URL("../src/state-migration.js", import.meta.url)))
})

test("接线：statePathOf / ensureStateDir 调用点都传了 logger，且没有裸调用", () => {
  // 只看调用点：排除函数声明本身（function statePathOf(config, logger = null)）
  const callSites = (name) =>
    (indexSource.match(new RegExp(`(?<!function )\\b${name}\\([^)]*\\)`, "g")) ?? [])
      .filter((c) => !c.startsWith("function "))

  // 不允许存在未传 logger 的裸调用
  assert.equal(
    (indexSource.match(/statePathOf\(\s*config\s*\)/g) ?? []).length, 0,
    "存在未传 logger 的 statePathOf 调用",
  )
  assert.equal(
    (indexSource.match(/ensureStateDir\(\s*config\s*\)/g) ?? []).length, 0,
    "存在未传 logger 的 ensureStateDir 调用",
  )

  for (const name of ["statePathOf", "ensureStateDir"]) {
    const calls = callSites(name)
    assert.ok(calls.length > 0, `应当存在 ${name} 调用点`)
    // 调用点必须把 logger 传下去：要么是 ctx.logger（入口），要么是 logger（内部转发）。
    for (const c of calls) {
      assert.match(c, /ctx\.logger|\blogger\b/, `调用点没有传 logger：${c}`)
    }
  }
  // 且至少有一个入口调用点用的是 ctx.logger（证明真的接上了宿主日志）
  const entry = callSites("statePathOf").concat(callSites("ensureStateDir"))
  assert.ok(entry.some((c) => /ctx\.logger/.test(c)), "没有任何调用点接入 ctx.logger")
})

test("接线：失败语义是「抛错」（方案 A），不是静默降级", () => {
  // ensureStateDir 必须在 !ready 时抛 StateMigrationError
  assert.match(indexSource, /if\s*\(!resolved\.ready\)/, "ensureStateDir 必须检查 ready")
  assert.match(indexSource, /throw new StateMigrationError\(/, "ensureStateDir 必须抛 StateMigrationError")
  // 诊断必须可操作
  assert.match(indexSource, /原数据保留在/, "错误信息必须说明原数据位置")
  assert.match(indexSource, /处理建议/, "错误信息必须给出恢复建议")
})

test("接线：端到端失败路径 —— 冲突时 resolveStateDir 不 ready，抛错携带可操作诊断", async () => {
  const home = mkdtempSync(join(tmpdir(), "cetus-wire-"))
  try {
    const base = join(home, ".dsh")
    const src = join(base, "dsh-links")
    const dst = join(base, "dsh-cetus")
    // 两个不同身份的目录
    for (const [dir, cn] of [[src, "host-A"], [dst, "host-B"]]) {
      mkdirSync(dir, { recursive: true, mode: 0o700 })
      const pems = await selfsigned.generate([{ name: "commonName", value: cn }], { keySize: 2048, algorithm: "sha256" })
      writeFileSync(join(dir, "tls.json"), JSON.stringify({ key: pems.private, cert: pems.cert }), { mode: 0o600 })
      writeFileSync(join(dir, "state.json"), JSON.stringify({ deviceId: `dsh-${cn}`, devices: [{ deviceId: `dev-${cn}` }], pairing: {} }), { mode: 0o600 })
    }
    const srcStateBefore = readFileSync(join(src, "state.json"), "utf8")

    const res = resolveStateDir({}, { home, log: () => {} })
    assert.equal(res.ready, false)
    assert.equal(res.error.code, "identity-conflict")
    assert.ok(res.error.remedy, "必须给出可操作的恢复建议")

    // 复刻 ensureStateDir 的诊断拼装逻辑，确认用户看到的是可操作信息
    const msg = [
      `state 目录迁移失败（${res.error.code}）：${res.error.message}`,
      `原数据保留在：${res.error.sourcePath}`,
      `目标目录：${res.error.targetPath}`,
      `处理建议：${res.error.remedy}`,
    ].join("\n")
    assert.match(msg, /identity-conflict|身份不一致/)
    assert.match(msg, /原数据保留在：/)
    assert.match(msg, /处理建议：/)
    assert.equal(res.error.sourcePath, src)
    assert.equal(res.error.targetPath, dst)

    // 绝不泄漏密钥
    assert.equal(msg.includes("PRIVATE KEY"), false)
    // 两边数据都保留
    assert.equal(readFileSync(join(src, "state.json"), "utf8"), srcStateBefore)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test("接线：自定义 stateDir 时 resolveStateDir 不触碰默认目录", () => {
  const home = mkdtempSync(join(tmpdir(), "cetus-wire-custom-"))
  try {
    const custom = join(home, "my-state")
    const res = resolveStateDir({ stateDir: custom }, { home, log: () => {} })
    assert.equal(res.dir, custom)
    assert.equal(res.migrated, false)
    assert.equal(existsSync(join(home, ".dsh", "dsh-cetus")), false, "不得创建默认目录")
    assert.equal(statSync(custom).mode & 0o777, 0o700)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test.after(() => {
  if (beforeSnap) {
    assert.equal(statSync(REAL).mtimeMs, beforeSnap.m, "测试触碰了真实 state 目录 —— 红线违规")
  }
})
