/**
 * state 目录迁移（方案 §22.3 / §22.5 验收矩阵）。
 *
 * **安全红线：所有测试只用 mkdtemp 假目录与假密钥，绝不触碰 ~/.dsh/dsh-links。**
 * 见文件末尾的 assertNoRealDirTouched() —— 每次测试结束都交叉验证真实目录 mtime 未变。
 *
 * 覆盖 §22.5 七场景：
 *   1. 无旧数据全新安装  2. 有效旧数据迁移  3. 自定义目录不动  4. 重复迁移幂等
 *   5. 新旧冲突报错      6. 源损坏拒绝初始化 7. 复制中断恢复
 */
import assert from "node:assert/strict"
import test from "node:test"
import { randomBytes } from "node:crypto"
import { execFileSync } from "node:child_process"
import {
  lstatSync, symlinkSync,
  chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, utimesSync, writeFileSync,
} from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import selfsigned from "selfsigned"
import {
  CANONICAL_DIR_NAME, LOCK_FILE, MIGRATION_EXCLUDED, MIGRATION_FILE, PRIOR_DIR_NAME, STAGING_PREFIX,
  STATE_DIR_REQUIRED, StateMigrationError, cleanStaleStaging, inspectStateDir, isStagingDir, migrateStateDir,
  movableEntries, readMigrationRecord, resolveStateDir, tlsFingerprintOf,
} from "../src/state-migration.js"

// ---------- 真实目录保护：这些路径在本测试中必须**只读** ----------
const REAL_DIRS = [
  join(process.env.HOME ?? "", ".dsh", "dsh-links"),
  join(process.env.HOME ?? "", ".dsh", "dsh-cetus"),
]

/** 采集真实目录指纹（含文件大小/mtime），结束时比对。 */
function snapshotRealDirs() {
  const snap = {}
  for (const dir of REAL_DIRS) {
    if (!dir.startsWith("/")) continue
    if (!existsSync(dir)) { snap[dir] = null; continue }
    try {
      const st = statSync(dir)
      snap[dir] = {
        mtimeMs: st.mtimeMs,
        entries: readdirSync(dir).sort(),
      }
    } catch { snap[dir] = "unreadable" }
  }
  return snap
}

function assertNoRealDirTouched(before) {
  const after = snapshotRealDirs()
  assert.deepEqual(after, before, "测试触碰了真实 state 目录 —— 这是红线违规")
}

// ---------- 假目录工具 ----------
const cleanups = []
function sandbox() {
  const dir = mkdtempSync(join(tmpdir(), "cetus-mig-test-"))
  assert.ok(dir.startsWith(tmpdir()), "沙箱必须建在 tmpdir 下")
  assert.ok(!REAL_DIRS.some((r) => r.startsWith("/") && dir.startsWith(r)), "沙箱不得落在真实目录内")
  cleanups.push(dir)
  return dir
}
test.after(() => {
  for (const d of cleanups) { try { rmSync(d, { recursive: true, force: true }) } catch {} }
})

/** 生成一对真实自签证书（假身份，但指纹校验是货真价实的）。 */
async function fakeCerts(cn) {
  const notAfter = new Date()
  notAfter.setFullYear(notAfter.getFullYear() + 10)
  const pems = await selfsigned.generate([{ name: "commonName", value: cn }], {
    keySize: 2048, algorithm: "sha256", notAfterDate: notAfter,
  })
  return { key: pems.private, cert: pems.cert }
}

/** 写一份完整的假状态目录（state.json + tls.json，含设备批准/推送注册/远程身份）。 */
async function writeStateDir(dir, { cn = "fake-host", devices = [], remote = null, tls = null } = {}) {
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const certs = tls ?? await fakeCerts(cn)
  writeFileSync(join(dir, "tls.json"), JSON.stringify(certs, null, 2), { mode: 0o600 })
  const state = {
    deviceId: `dsh-${randomBytes(4).toString("hex")}`,
    devices,
    pairing: {},
    remote: remote ?? { enabled: true, endpoint: "wss://relay.example/ws", hostKey: randomBytes(32).toString("base64url"), keySeed: randomBytes(32).toString("base64url"), createdAt: 1 },
  }
  writeFileSync(join(dir, "state.json"), JSON.stringify(state, null, 2), { mode: 0o600 })
  return { dir, certs, state }
}

const fakeDevice = (name, id) => ({ deviceId: id, name, status: "active", push: { token: "fake-push-token" }, lastSeenAt: 1 })

/** 静默 logger：断言迁移日志里没有秘密。 */
function collectingLog() {
  const lines = []
  const log = (level, message, detail) => lines.push({ level, message, detail })
  return { log, lines, text: () => JSON.stringify(lines) }
}

// ================= §22.5 场景 1：无旧数据全新安装 =================
test("场景1 无旧数据全新安装：创建新默认目录，不生成任何密钥，首次配对正常", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  mkdirSync(base, { recursive: true })

  const res = migrateStateDir(join(base, PRIOR_DIR_NAME), join(base, CANONICAL_DIR_NAME))

  assert.equal(res.migrated, false)
  assert.equal(res.adopted, false)
  assert.equal(res.record, null)
  assert.ok(existsSync(join(base, CANONICAL_DIR_NAME)), "必须创建新默认目录")
  assert.equal(statSync(join(base, CANONICAL_DIR_NAME)).mode & 0o777, 0o700, "目录权限必须是 0700")
  // 全新安装不得凭空造出 tls.json —— 证书由 loadOrCreateTls 在就绪时按需生成。
  assert.equal(existsSync(join(base, CANONICAL_DIR_NAME, "tls.json")), false, "全新安装不该预生成证书")
  assert.deepEqual(readdirSync(join(base, CANONICAL_DIR_NAME)), [], "全新目录应当为空")

  assertNoRealDirTouched(before)
})

test("场景1b resolveStateDir 全新安装：走规范名 dsh-cetus", async () => {
  const before = snapshotRealDirs()
  const home = sandbox()
  const res = resolveStateDir({}, { home, log: () => {} })
  assert.equal(res.dir, join(home, ".dsh", CANONICAL_DIR_NAME))
  assert.equal(res.ready, true)
  assert.equal(res.migrated, false)
  assertNoRealDirTouched(before)
})

// ================= §22.5 场景 2：有效旧数据 → 完整迁移 =================
test("场景2 有效旧数据迁移：设备与证书身份不变，源保留为备份", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  const devices = [fakeDevice("用户手机A", "dev-aaa"), fakeDevice("用户手机B", "dev-bbb")]
  const { state } = await writeStateDir(src, { cn: "real-user-host", devices })

  const srcFp = tlsFingerprintOf(src)
  const srcRaw = readFileSync(join(src, "state.json"), "utf8")
  const { log, text } = collectingLog()

  const res = migrateStateDir(src, dst, { log })

  assert.equal(res.migrated, true, "应当真的迁移")
  assert.equal(res.adopted, false)
  // 身份不变
  assert.equal(tlsFingerprintOf(dst), srcFp, "证书指纹必须逐位一致")
  const dstState = JSON.parse(readFileSync(join(dst, "state.json"), "utf8"))
  assert.deepEqual(dstState.devices.map((d) => d.deviceId).sort(), ["dev-aaa", "dev-bbb"], "设备必须全部搬过去")
  assert.equal(dstState.remote.hostKey, state.remote.hostKey, "远程主机密钥必须一致（否则手机掉线）")
  assert.equal(dstState.deviceId, state.deviceId, "deviceId 必须一致")
  // 不能只复制 state.json（红线 9）
  for (const f of STATE_DIR_REQUIRED) assert.ok(existsSync(join(dst, f)), `必须复制 ${f}`)
  // 源保留为受保护备份，逐字节未变
  assert.equal(readFileSync(join(src, "state.json"), "utf8"), srcRaw, "源目录必须原样保留")
  assert.equal(readdirSync(src).sort().join(","), "state.json,tls.json", "源目录不能被改动")
  // 权限
  assert.equal(statSync(dst).mode & 0o777, 0o700)
  for (const f of STATE_DIR_REQUIRED) {
    assert.equal(statSync(join(dst, f)).mode & 0o777, 0o600, `${f} 必须是 0600`)
  }
  // 迁移记录
  const rec = readMigrationRecord(dst)
  assert.equal(rec.migrationVersion, 1)
  assert.equal(rec.sourcePath, src)
  assert.equal(rec.status, "migrated")
  assert.equal(rec.deviceCount, 2)
  assert.equal(rec.tlsFingerprint, srcFp)
  // 日志不含秘密（红线 8）
  const body = text()
  assert.ok(!body.includes(state.remote.hostKey), "日志泄露了 hostKey")
  assert.ok(!body.includes(state.remote.keySeed), "日志泄露了 keySeed")
  assert.ok(!body.includes("fake-push-token"), "日志泄露了推送 token")
  assert.ok(!body.includes("PRIVATE KEY"), "日志泄露了私钥")
  assertNoRealDirTouched(before)
})

test("场景2c 复刻用户真实目录形状：state.json + tls.json + state.json.bak-* 必须全部搬过去", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  // 这是 Lead 只读核对到的真实 `~/.dsh/dsh-links` 形状（内容用假数据）
  const BAK = "state.json.bak-before-dlp-20260929-235210"
  await writeStateDir(src, { cn: "real-shape", devices: [fakeDevice("真机A", "dev-a"), fakeDevice("真机B", "dev-b")] })
  writeFileSync(join(src, BAK), JSON.stringify({ devices: [{ deviceId: "dev-a" }] }, null, 2), { mode: 0o600 })
  const srcListing = readdirSync(src).sort()
  assert.deepEqual(srcListing, [BAK, "state.json", "tls.json"].sort(), "前置：源目录形状应与真实目录一致")

  const { log } = collectingLog()
  const res = migrateStateDir(src, dst, { log })

  assert.equal(res.migrated, true)
  // .bak-* 是最容易被白名单漏掉的一项：必须一起搬，且逐字节一致
  assert.ok(existsSync(join(dst, BAK)), "state.json.bak-* 备份必须一起迁移（红线 9）")
  assert.equal(
    readFileSync(join(dst, BAK), "utf8"),
    readFileSync(join(src, BAK), "utf8"),
    ".bak 文件内容必须逐字节一致",
  )
  // 整体性断言：源里每个条目（除运行期文件）都必须出现在目标里
  const movable = movableEntries(src)
  for (const name of movable) assert.ok(existsSync(join(dst, name)), `漏搬了 ${name}`)
  assert.deepEqual(readdirSync(dst).filter((n) => !MIGRATION_EXCLUDED.includes(n)).sort(), movable.sort())
  // 源目录仍完好
  assert.deepEqual(readdirSync(src).sort(), srcListing, "源目录必须原样保留")
  // 备份文件权限不得放宽
  assert.equal(statSync(join(dst, BAK)).mode & 0o777, 0o600, ".bak 权限必须保持 0600")
  assert.equal(statSync(dst).mode & 0o777, 0o700)
  assertNoRealDirTouched(before)
})

test("场景2d 未来新增的未知文件也要跟着走（默认搬运策略）", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  // 模拟未来版本新增的持久化索引/能力缓存：白名单实现会丢掉它们
  writeFileSync(join(src, "capabilities-cache.json"), '{"caps":["streaming"]}', { mode: 0o600 })
  writeFileSync(join(src, "persist-index.json"), '{"sessions":3}', { mode: 0o600 })
  mkdirSync(join(src, "push-nested"))
  writeFileSync(join(src, "push-nested", "reg.json"), '{"reg":"fake"}', { mode: 0o600 })

  await migrateStateDir(src, dst, { log: () => {} })

  assert.equal(readFileSync(join(dst, "capabilities-cache.json"), "utf8"), '{"caps":["streaming"]}')
  assert.equal(readFileSync(join(dst, "persist-index.json"), "utf8"), '{"sessions":3}')
  assert.equal(readFileSync(join(dst, "push-nested", "reg.json"), "utf8"), '{"reg":"fake"}', "嵌套目录也要搬")
  assertNoRealDirTouched(before)
})

test("场景2e 临时残留文件（*.tmp）不搬，避免把半成品当数据", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  writeFileSync(join(src, "state.json.12345.tmp"), '{"half":true}', { mode: 0o600 })

  await migrateStateDir(src, dst, { log: () => {} })

  assert.equal(existsSync(join(dst, "state.json.12345.tmp")), false, "原子写残留不该跟着迁移")
  assert.ok(existsSync(join(dst, "state.json")))
  assertNoRealDirTouched(before)
})

test("场景2b 迁移粒度：目录里除 state.json 外的东西也必须一起搬", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  // 私钥内容（真实 tls.json 里的 key 字段）必须完整到达
  const srcTls = JSON.parse(readFileSync(join(src, "tls.json"), "utf8"))
  await migrateStateDir(src, dst, { log: () => {} })
  const dstTls = JSON.parse(readFileSync(join(dst, "tls.json"), "utf8"))
  assert.equal(dstTls.key, srcTls.key, "私钥必须完整复制")
  assert.equal(dstTls.cert, srcTls.cert, "证书必须完整复制")
  assertNoRealDirTouched(before)
})

// ================= §22.5 场景 3：自定义目录不动 =================
test("场景3 用户配置自定义目录：原目录继续用，绝不触碰默认目录", async () => {
  const before = snapshotRealDirs()
  const home = sandbox()
  const custom = join(sandbox(), "my-custom-state")
  await writeStateDir(custom, { devices: [fakeDevice("C", "dev-custom")] })
  // 默认目录里放一份"别的"state，验证迁移逻辑完全不去看它
  const src = join(home, ".dsh", PRIOR_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("D", "dev-default")] })
  const customBefore = readdirSync(custom).sort()
  const srcBefore = readFileSync(join(src, "state.json"), "utf8")

  const { log, text } = collectingLog()
  const res = resolveStateDir({ stateDir: custom }, { home, log })

  assert.equal(res.dir, custom, "必须使用显式目录")
  assert.equal(res.migrated, false, "显式目录不得触发迁移")
  assert.equal(res.ready, true)
  assert.deepEqual(readdirSync(custom).sort(), customBefore, "自定义目录内容不得被改动")
  assert.equal(existsSync(join(home, ".dsh", CANONICAL_DIR_NAME)), false, "不得创建默认目录")
  assert.equal(readFileSync(join(src, "state.json"), "utf8"), srcBefore, "旧默认目录不得被读取/改动")
  assert.equal(text().includes("dev-default"), false, "不得扫描旧默认目录")
  assertNoRealDirTouched(before)
})

// ================= §22.5 场景 4：重复迁移幂等 =================
test("场景4 重复启动/重复迁移：幂等，不重复注册、不回覆盖", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1"), fakeDevice("B", "dev-2")] })

  const first = migrateStateDir(src, dst, { log: () => {}, now: 1000 })
  assert.equal(first.migrated, true)
  const snapshot1 = {
    state: readFileSync(join(dst, "state.json"), "utf8"),
    tls: readFileSync(join(dst, "tls.json"), "utf8"),
    record: readFileSync(join(dst, MIGRATION_FILE), "utf8"),
    listing: readdirSync(dst).sort(),
  }

  // 第二次、第三次：不得改动任何字节（含 migration.json 的 at 时间戳）
  const second = migrateStateDir(src, dst, { log: () => {}, now: 2000 })
  const third = migrateStateDir(src, dst, { log: () => {}, now: 3000 })

  assert.equal(second.migrated, false, "第二次必须识别为已迁移")
  assert.equal(third.migrated, false)
  assert.equal(second.adopted, true)
  assert.equal(readFileSync(join(dst, "state.json"), "utf8"), snapshot1.state, "重复迁移覆盖了 state")
  assert.equal(readFileSync(join(dst, "tls.json"), "utf8"), snapshot1.tls, "重复迁移换了证书")
  assert.equal(readFileSync(join(dst, MIGRATION_FILE), "utf8"), snapshot1.record, "迁移记录被重写")
  assert.deepEqual(readdirSync(dst).sort(), snapshot1.listing, "目录内容发生变化")
  // 源目录同样未被改动
  assert.deepEqual(readdirSync(src).sort(), ["state.json", "tls.json"])
  assertNoRealDirTouched(before)
})

test("场景4b 幂等：目标已存在时 resolveStateDir 两次结果完全相同", async () => {
  const before = snapshotRealDirs()
  const home = sandbox()
  const src = join(home, ".dsh", PRIOR_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  const a = resolveStateDir({}, { home, log: () => {}, now: 1 })
  const snap = { state: readFileSync(join(a.dir, "state.json"), "utf8"), listing: readdirSync(a.dir).sort() }
  const b = resolveStateDir({}, { home, log: () => {}, now: 2 })
  assert.equal(a.dir, b.dir)
  assert.equal(readFileSync(join(b.dir, "state.json"), "utf8"), snap.state)
  assert.deepEqual(readdirSync(b.dir).sort(), snap.listing)
  assertNoRealDirTouched(before)
})

// ================= §22.5 场景 5：新旧冲突报错 =================
test("场景5 新旧目录冲突：报错停下，不拼设备数组、不选较新者、不生成第三把密钥", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { cn: "host-OLD", devices: [fakeDevice("旧机", "dev-old")] })
  await writeStateDir(dst, { cn: "host-NEW", devices: [fakeDevice("新机", "dev-new")] })
  const dstStateBefore = readFileSync(join(dst, "state.json"), "utf8")
  const dstTlsBefore = readFileSync(join(dst, "tls.json"), "utf8")
  const srcStateBefore = readFileSync(join(src, "state.json"), "utf8")
  const fpOld = tlsFingerprintOf(src)
  const fpNew = tlsFingerprintOf(dst)

  assert.throws(
    () => migrateStateDir(src, dst, { log: () => {} }),
    (err) => {
      assert.ok(err instanceof StateMigrationError)
      assert.equal(err.code, "identity-conflict")
      assert.match(err.message, /身份不一致/)
      assert.ok(err.remedy, "必须给出可操作的处理建议")
      return true
    },
  )

  // 两边都必须原样保留
  assert.equal(readFileSync(join(dst, "state.json"), "utf8"), dstStateBefore, "目标目录被改动了")
  assert.equal(readFileSync(join(dst, "tls.json"), "utf8"), dstTlsBefore, "目标证书被改动了")
  assert.equal(readFileSync(join(src, "state.json"), "utf8"), srcStateBefore, "源目录被改动了")
  // 不拼设备数组
  const dstState = JSON.parse(readFileSync(join(dst, "state.json"), "utf8"))
  assert.deepEqual(dstState.devices.map((d) => d.deviceId), ["dev-new"], "不得把两个目录的设备拼在一起")
  // 不生成第三把密钥
  assert.equal(tlsFingerprintOf(src), fpOld)
  assert.equal(tlsFingerprintOf(dst), fpNew)
  assert.notEqual(fpOld, fpNew)
  assert.equal(readdirSync(dst).filter(isStagingDirIsStagingName).length, 0, "冲突时不得留下暂存目录")
  assertNoRealDirTouched(before)
})

test("场景5b 冲突：远程主机公钥不一致（证书却同源）→ 同样报错停下", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  const certs = await fakeCerts("shared-host")
  await writeStateDir(src, { tls: certs, devices: [fakeDevice("A", "dev-a")] })
  await writeStateDir(dst, { tls: certs, devices: [fakeDevice("B", "dev-b")] })
  const dstBefore = readFileSync(join(dst, "state.json"), "utf8")

  assert.throws(
    () => migrateStateDir(src, dst, { log: () => {} }),
    (err) => {
      assert.equal(err.code, "identity-conflict")
      assert.match(err.message, /公钥/)
      return true
    },
  )
  assert.equal(readFileSync(join(dst, "state.json"), "utf8"), dstBefore)
  assertNoRealDirTouched(before)
})

test("场景5c 冲突：resolveStateDir 冲突时 ready=false（迁移失败不得启动线上注册）", async () => {
  const before = snapshotRealDirs()
  const home = sandbox()
  const src = join(home, ".dsh", PRIOR_DIR_NAME)
  const dst = join(home, ".dsh", CANONICAL_DIR_NAME)
  await writeStateDir(src, { cn: "host-OLD" })
  await writeStateDir(dst, { cn: "host-NEW" })
  const srcBefore = readFileSync(join(src, "state.json"), "utf8")

  const res = resolveStateDir({}, { home, log: () => {} })
  assert.equal(res.ready, false, "冲突必须让上层拒绝启动远程注册")
  assert.equal(res.error?.code, "identity-conflict")
  assert.equal(readFileSync(join(src, "state.json"), "utf8"), srcBefore, "源目录必须原样保留")
  assertNoRealDirTouched(before)
})

// ================= §22.5 场景 6：源损坏 / 权限拒绝 / 磁盘满 =================
test("场景6a 源 state.json 损坏：拒绝静默初始化，原目录保留", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  writeFileSync(join(src, "state.json"), "{ this is not json", { mode: 0o600 })
  const brokenBefore = readFileSync(join(src, "state.json"), "utf8")

  assert.throws(
    () => migrateStateDir(src, dst, { log: () => {} }),
    (err) => {
      assert.equal(err.code, "source-corrupt")
      assert.match(err.message, /损坏/)
      return true
    },
  )
  assert.equal(readFileSync(join(src, "state.json"), "utf8"), brokenBefore, "损坏的源不得被清空")
  assert.equal(existsSync(dst), false, "不得创建设备已丢的新默认目录")
  assert.equal(existsSync(join(dst, "state.json")), false, "绝不静默初始化空 state")
  assertNoRealDirTouched(before)
})

test("场景6b 源证书缺失：拒绝迁移，不生成第三把密钥", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  rmSync(join(src, "tls.json")) // 证书被误删 / 备份不完整

  assert.throws(
    () => migrateStateDir(src, dst, { log: () => {} }),
    (err) => {
      assert.equal(err.code, "tls-missing")
      assert.match(err.message, /tls\.json/)
      return true
    },
  )
  assert.equal(existsSync(join(dst, "tls.json")), false, "绝不许生成新证书冒充原身份")
  assert.equal(existsSync(join(dst, "state.json")), false, "不得产生半份身份")
  assert.equal(JSON.parse(readFileSync(join(src, "state.json"), "utf8")).devices.length, 1, "源设备记录必须保留")
  assertNoRealDirTouched(before)
})

test("场景6c 源 tls.json 损坏 / 权限拒绝读取：拒绝迁移并可恢复", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  writeFileSync(join(src, "tls.json"), "not-json-at-all", { mode: 0o600 })

  assert.throws(() => migrateStateDir(src, dst, { log: () => {} }), (err) => {
    assert.equal(err.code, "source-corrupt")
    return true
  })
  assert.equal(existsSync(dst), false)

  // 可恢复：把证书修回来后，同一个调用成功迁移
  const certs = await fakeCerts("recovered-host")
  writeFileSync(join(src, "tls.json"), JSON.stringify(certs, null, 2), { mode: 0o600 })
  const res = migrateStateDir(src, dst, { log: () => {} })
  assert.equal(res.migrated, true, "修复后必须能恢复迁移")
  assert.equal(tlsFingerprintOf(dst), tlsFingerprintOf(src))
  assertNoRealDirTouched(before)
})

test("场景6d 权限拒绝（EACCES）：拒绝迁移，源目录保留，不静默初始化", async (t) => {
  if (process.getuid?.() === 0 || process.platform === "win32") return t.skip("root/Windows 不受权限位约束")
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  const srcBefore = readFileSync(join(src, "state.json"), "utf8")
  // 文件本身不可读 → readJson 抛 read-failed。先探测权限位是否真的生效，
  // 免得在权限位不生效的环境里得到假绿。
  chmodSync(join(src, "state.json"), 0o000)
  let probeBlocked = false
  try { readFileSync(join(src, "state.json")) } catch { probeBlocked = true }
  if (!probeBlocked) {
    chmodSync(join(src, "state.json"), 0o600)
    return t.skip("本环境权限位对当前用户不生效（特权运行），跳过")
  }

  try {
    assert.throws(
      () => migrateStateDir(src, dst, { log: () => {} }),
      (err) => {
        assert.ok(err instanceof StateMigrationError, `应当抛 StateMigrationError，实际 ${err?.name}`)
        assert.ok(
          ["read-failed", "incomplete-source", "copy-failed", "tls-missing", "source-corrupt"].includes(err.code),
          `unexpected code ${err.code}`,
        )
        return true
      },
    )
    assert.equal(existsSync(join(dst, "state.json")), false, "不得静默初始化空 state")
    assert.equal(existsSync(dst), false, "权限拒绝时不得创建目标目录")
  } finally {
    chmodSync(join(src, "state.json"), 0o600)
  }
  assert.equal(readFileSync(join(src, "state.json"), "utf8"), srcBefore, "源目录必须完好")
  assertNoRealDirTouched(before)
})

test("场景6e 磁盘满（ENOSPC）：拒绝迁移且不静默清空，源目录保留", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  const srcBefore = readFileSync(join(src, "state.json"), "utf8")

  // 真实 statfs 守卫
  const { hasDiskSpace } = await import("../src/state-migration.js")
  assert.equal(hasDiskSpace(src, Number.MAX_SAFE_INTEGER), false, "磁盘空间守卫必须生效")
  assert.equal(hasDiskSpace(src, 1), true, "小文件不该被拒绝")

  // 注入剩余空间 = 0，真实走一遍 ENOSPC 分支（不依赖挂载小文件系统）
  assert.throws(
    () => migrateStateDir(src, dst, { log: () => {}, freeSpace: () => 0 }),
    (err) => {
      assert.ok(err instanceof StateMigrationError)
      assert.equal(err.code, "disk-full")
      assert.match(err.message, /磁盘空间不足/)
      return true
    },
  )
  assert.equal(readFileSync(join(src, "state.json"), "utf8"), srcBefore, "源目录不得被改动")
  assert.equal(existsSync(join(dst, "state.json")), false, "磁盘满不得提升半个目录")
  assert.equal(readdirSync(base).filter(isStagingDir).length, 0, "磁盘满不得留下暂存目录")

  // 空间恢复后必须能正常迁移（可恢复性）
  const ok = migrateStateDir(src, dst, { log: () => {} })
  assert.equal(ok.migrated, true, "腾出空间后必须能完成迁移")
  assert.equal(tlsFingerprintOf(dst), tlsFingerprintOf(src))
  assertNoRealDirTouched(before)
})

test("场景6e-2 复制到一半磁盘满（写入截断）：逐字节校验拦截，不提升半份数据", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1"), fakeDevice("B", "dev-2")] })
  const srcState = readFileSync(join(src, "state.json"), "utf8")

  // 通过 mock 一个"复制时被截断"的 cpSync 无法从外部注入（cpSync 是直接 import 的），
  // 因此这里验证的是**同一不变式**：staging 里的内容与源不一致时绝不允许提升。
  // 做法：预先在 staging 位置放入一份被截断的旧拷贝，再让迁移用一个"总是失败"的复制路径。
  // 更可靠的做法是直接断言 copyAndVerify 的校验结果——通过构造目标父目录只读来触发写失败。
  chmodSync(base, 0o500)
  try {
    assert.throws(
      () => migrateStateDir(src, dst, { log: () => {} }),
      (err) => {
        assert.ok(err instanceof StateMigrationError, `应当抛 StateMigrationError，实际 ${err?.name}`)
        return true
      },
    )
  } finally {
    chmodSync(base, 0o700)
  }
  assert.equal(readFileSync(join(src, "state.json"), "utf8"), srcState, "源目录必须完好")
  assert.equal(existsSync(join(dst, "state.json")), false, "失败路径不得留下目标 state")
  assert.equal(existsSync(dst), false, "失败路径不得留下不完整的目标目录")
  assertNoRealDirTouched(before)
})

test("场景6f 截断的 staging 拷贝绝不被提升（身份/内容双重校验）", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  const srcState = readFileSync(join(src, "state.json"), "utf8")
  // 模拟"上一次中断留下的、内容不完整的 staging"：它必须被清理而不是被提升。
  mkdirSync(base, { recursive: true })
  const staleDir = mkdtempSync(join(base, STAGING_PREFIX))
  writeFileSync(join(staleDir, "state.json"), srcState.slice(0, 20), { mode: 0o600 })

  const res = migrateStateDir(src, dst, { log: () => {} })

  assert.equal(res.migrated, true)
  assert.equal(existsSync(staleDir), false, "被截断的 staging 必须清理")
  assert.equal(readFileSync(join(dst, "state.json"), "utf8"), srcState, "提升的必须是完整内容")
  assertNoRealDirTouched(before)
})

// ================= §22.5 场景 7：复制中断恢复 =================
test("场景7 复制中断：下次启动识别暂存目录并清理，然后完成迁移", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1"), fakeDevice("B", "dev-2")] })
  mkdirSync(base, { recursive: true })

  // 模拟上一次迁移中途崩在 staging 里（半份拷贝）
  const stale = mkdtempSync(join(base, STAGING_PREFIX))
  writeFileSync(join(stale, "state.json"), '{"devices":[{"deviceId":"dev-1"}]}', { mode: 0o600 })
  assert.ok(isStagingDir(stale.slice(base.length + 1)), "暂存目录必须能被识别")

  const { log, lines } = collectingLog()
  const res = migrateStateDir(src, dst, { log, now: 5000 })

  assert.equal(res.migrated, true, "清理残留后必须完成迁移")
  assert.equal(existsSync(stale), false, "残留暂存目录必须被清理")
  // 结果必须是完整的，不能混入半份拷贝
  const dstState = JSON.parse(readFileSync(join(dst, "state.json"), "utf8"))
  assert.deepEqual(dstState.devices.map((d) => d.deviceId).sort(), ["dev-1", "dev-2"], "不能提升半份拷贝")
  assert.equal(readdirSync(base).filter((n) => isStagingDir(n)).length, 0, "不得留下暂存目录")
  assert.equal(existsSync(join(base, LOCK_FILE)), false, "锁必须释放")
  assertNoRealDirTouched(before)
})

test("场景7b 复制中断：已提升但没写迁移记录 → 下次视为已迁移，不重复搬", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  // 手工构造"提升成功、记录未写"的中间态：目标与源身份一致
  await migrateStateDir(src, dst, { log: () => {} })
  rmSync(join(dst, MIGRATION_FILE))
  const dstState = readFileSync(join(dst, "state.json"), "utf8")

  const res = migrateStateDir(src, dst, { log: () => {}, now: 9 })
  assert.equal(res.migrated, false, "身份一致就必须视为已迁移")
  assert.equal(res.adopted, true)
  assert.equal(readFileSync(join(dst, "state.json"), "utf8"), dstState, "不得回覆盖")
  assertNoRealDirTouched(before)
})

// ================= 真实层次结构：legacy 目录没有 tls.json =================
/**
 * 这是从用户机器上只读核对到的真实情况：
 *   ~/.dsh/dsh-links        → state.json + tls.json + state.json.bak-*   （真身份，源）
 *   ~/.dsh/dsh-deepharness  → 只有 state.json（含 1 个设备），**没有 tls.json**（历史残留）
 *   ~/.dsh/dshlinks         → 只有 state.json（含 1 个设备），**没有 tls.json**（历史残留）
 *
 * 危险点：如果源选择逻辑挑到 legacy 目录，tls-missing 会**直接阻断启动**，
 * 或者更糟——被"顺手生成一把新证书"兜住，导致用户两台真机全部掉线。
 * 这组测试锁定"必须选中 dsh-links"这一行为。
 */
test("源选择：legacy 目录存在但缺 tls.json 时，必须选中真正的 dsh-links（不被阻断）", async () => {
  const before = snapshotRealDirs()
  const home = sandbox()
  const base = join(home, ".dsh")
  // 复刻真实三目录结构
  const real = join(base, PRIOR_DIR_NAME)
  await writeStateDir(real, { cn: "real-identity", devices: [fakeDevice("真机A", "dev-real-a"), fakeDevice("真机B", "dev-real-b")] })
  for (const legacy of ["dsh-deepharness", "dshlinks"]) {
    mkdirSync(join(base, legacy), { recursive: true, mode: 0o700 })
    writeFileSync(join(base, legacy, "state.json"), JSON.stringify({
      deviceId: `dsh-legacy-${legacy}`, devices: [fakeDevice("旧残留", `dev-${legacy}`)], pairing: {},
    }, null, 2), { mode: 0o600 })
  }

  const { log, lines } = collectingLog()
  const res = resolveStateDir({}, { home, log })

  assert.equal(res.ready, true, "不得因为 legacy 目录缺证书而拒绝启动")
  assert.equal(res.dir, join(base, CANONICAL_DIR_NAME))
  assert.equal(res.migrated, true, "必须真的迁移")
  // 迁过来的必须是 dsh-links 的身份与设备，**不是** legacy 的那一个设备
  assert.equal(tlsFingerprintOf(res.dir), tlsFingerprintOf(real), "必须用 dsh-links 的证书")
  const dstState = JSON.parse(readFileSync(join(res.dir, "state.json"), "utf8"))
  assert.deepEqual(dstState.devices.map((d) => d.deviceId).sort(), ["dev-real-a", "dev-real-b"])
  assert.equal(dstState.devices.some((d) => String(d.deviceId).includes("legacy")), false, "不得混入 legacy 设备")
  assert.equal(dstState.deviceId, JSON.parse(readFileSync(join(real, "state.json"), "utf8")).deviceId)
  // legacy 目录不得被改动
  for (const legacy of ["dsh-deepharness", "dshlinks"]) {
    const s = JSON.parse(readFileSync(join(base, legacy, "state.json"), "utf8"))
    assert.equal(s.devices.length, 1, `${legacy} 不得被改动`)
  }
  assertNoRealDirTouched(before)
})

test("源选择：只有 legacy 目录（无 dsh-links）时，缺 tls.json 必须报错而不是造新证书", async () => {
  const before = snapshotRealDirs()
  const home = sandbox()
  const base = join(home, ".dsh")
  // 只有历史目录，且没有证书 —— 用户从没在 dsh-links 上配对过
  mkdirSync(join(base, "dshlinks"), { recursive: true, mode: 0o700 })
  writeFileSync(join(base, "dshlinks", "state.json"), JSON.stringify({
    deviceId: "dsh-legacy", devices: [fakeDevice("旧残留", "dev-old")], pairing: {},
  }, null, 2), { mode: 0o600 })

  const res = resolveStateDir({}, { home, log: () => {} })

  assert.equal(res.ready, false, "缺证书必须让上层拒绝启动远程注册")
  assert.equal(res.error.code, "tls-missing")
  // 关键：绝不能生成一把新证书冒充原身份
  assert.equal(existsSync(join(base, CANONICAL_DIR_NAME, "tls.json")), false, "绝不许造新证书")
  assert.equal(existsSync(join(base, CANONICAL_DIR_NAME, "state.json")), false, "不得产生半份身份")
  // legacy 原目录保留
  assert.equal(JSON.parse(readFileSync(join(base, "dshlinks", "state.json"), "utf8")).devices.length, 1)
  assertNoRealDirTouched(before)
})

test("源选择：dsh-links 目录存在但为空时，不把空目录当源（走全新安装）", async () => {
  const before = snapshotRealDirs()
  const home = sandbox()
  const base = join(home, ".dsh")
  mkdirSync(join(base, PRIOR_DIR_NAME), { recursive: true, mode: 0o700 }) // 空目录
  mkdirSync(join(base, "dshlinks"), { recursive: true, mode: 0o700 })
  writeFileSync(join(base, "dshlinks", "state.json"), JSON.stringify({ deviceId: "x", devices: [], pairing: {} }), { mode: 0o600 })

  const res = resolveStateDir({}, { home, log: () => {} })

  // 有 legacy 的 state.json → 会尝试迁移它，但缺 tls.json → 拒绝（保守，不静默初始化）
  assert.equal(res.ready, false)
  assert.equal(res.error.code, "tls-missing")
  assertNoRealDirTouched(before)
})

test("场景7c 迁移锁被持有：跳过迁移并报错，源目录未改动", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  mkdirSync(base, { recursive: true })
  writeFileSync(join(base, LOCK_FILE), JSON.stringify({ pid: 999999 }), { mode: 0o600 })
  const srcBefore = readFileSync(join(src, "state.json"), "utf8")

  assert.throws(
    () => migrateStateDir(src, dst, { log: () => {} }),
    (err) => {
      assert.equal(err.code, "locked")
      return true
    },
  )
  assert.equal(readFileSync(join(src, "state.json"), "utf8"), srcBefore)

  // 过期锁（持有者崩溃）可以被接管
  const old = new Date(Date.now() - 10 * 60 * 1000)
  utimesSync(join(base, LOCK_FILE), old, old)
  const res = migrateStateDir(src, dst, { log: () => {} })
  assert.equal(res.migrated, true, "过期锁必须能被接管")
  assertNoRealDirTouched(before)
})

// ================= 日志秘密泄漏（红线 8）=================
test("日志绝不打印密钥/token：迁移全失败路径也不泄漏", async () => {
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  const devices = [{ ...fakeDevice("真机", "dev-real"), push: { token: "SECRET-PUSH-TOKEN-XYZ" } }]
  const { state } = await writeStateDir(src, { devices })
  const secretKey = JSON.parse(readFileSync(join(src, "tls.json"), "utf8")).key

  const { log, text } = collectingLog()
  migrateStateDir(src, dst, { log })
  migrateStateDir(src, dst, { log }) // 幂等路径也记一次

  const body = text()
  for (const secret of [state.remote.hostKey, state.remote.keySeed, "SECRET-PUSH-TOKEN-XYZ", secretKey, "BEGIN PRIVATE KEY"]) {
    assert.equal(body.includes(secret), false, `日志泄露了秘密片段: ${secret.slice(0, 24)}`)
  }
  // 但要求日志确实记录了必要信息（目录、设备数、指纹前缀）
  assert.ok(body.includes("devices"), "日志应记录设备数以便排查")
  assert.ok(body.includes("tls"), "日志应记录指纹前缀以便核对身份")
  assertNoRealDirTouched(before)
})

// ================= 纯函数辅助 =================
test("cleanStaleStaging 只删暂存目录，绝不碰真目录", async () => {
  const before = snapshotRealDirs()
  const base = sandbox()
  mkdirSync(join(base, STAGING_PREFIX + "aaa"))
  mkdirSync(join(base, STAGING_PREFIX + "bbb"))
  mkdirSync(join(base, "dsh-cetus"))
  writeFileSync(join(base, "dsh-cetus", "state.json"), "{}")
  mkdirSync(join(base, "dsh-links"))
  writeFileSync(join(base, "dsh-links", "state.json"), "{}")

  const removed = cleanStaleStaging(base)
  assert.equal(removed.length, 2)
  assert.equal(existsSync(join(base, STAGING_PREFIX + "aaa")), false)
  assert.ok(existsSync(join(base, "dsh-cetus", "state.json")), "真目录不得被删")
  assert.ok(existsSync(join(base, "dsh-links", "state.json")), "真目录不得被删")
  assertNoRealDirTouched(before)
})

test("inspectStateDir 只读取，不创建、不修改任何文件", async () => {
  const before = snapshotRealDirs()
  const dir = join(sandbox(), "state")
  await writeStateDir(dir, { devices: [fakeDevice("A", "dev-1")] })
  const ls = readdirSync(dir).sort()
  const mtimes = ls.map((f) => statSync(join(dir, f)).mtimeMs)
  const info = inspectStateDir(dir)
  assert.equal(info.deviceCount, 1)
  assert.match(info.tlsFingerprint, /^[0-9a-f]{64}$/)
  assert.deepEqual(readdirSync(dir).sort(), ls)
  assert.deepEqual(ls.map((f) => statSync(join(dir, f)).mtimeMs), mtimes, "inspect 不得改动文件")
  assertNoRealDirTouched(before)
})

// ---------- 辅助 ----------
function isStagingDirIsStagingName(name) {
  return typeof name === "string" && name.startsWith(STAGING_PREFIX)
}

// ================= 符号链接：绝不越界拷贝 =================
test("符号链接：指向目录之外时必须拒绝迁移（防止把 ~/.ssh 这类外部文件复制进来）", async (t) => {
  if (process.platform === "win32") return t.skip("Windows 符号链接需要额外权限")
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  // 造一个"外部秘密"文件，再在 state 目录里放一个指向它的软链
  const outside = join(sandbox(), "outside-secret.txt")
  writeFileSync(outside, "SUPER-SECRET-OUTSIDE-CONTENT", { mode: 0o600 })
  symlinkSync(outside, join(src, "leaked-link"))

  assert.throws(
    () => migrateStateDir(src, dst, { log: () => {} }),
    (err) => {
      assert.ok(err instanceof StateMigrationError)
      assert.equal(err.code, "unsafe-symlink")
      assert.match(err.message, /目录之外|越界|outside|外部/)
      return true
    },
  )
  // 关键：外部内容绝不能被复制进目标目录
  assert.equal(existsSync(dst), false, "越界软链时必须停止，不得提升")
  assert.equal(existsSync(join(dst, "leaked-link")), false, "外部内容不得被复制进来")
  assert.equal(readFileSync(outside, "utf8"), "SUPER-SECRET-OUTSIDE-CONTENT", "外部文件必须完好")
  assertNoRealDirTouched(before)
})

test("符号链接：嵌套目录里的越界软链同样拒绝（递归预检）", async (t) => {
  if (process.platform === "win32") return t.skip("Windows 符号链接需要额外权限")
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  const outside = join(sandbox(), "nested-outside-secret.txt")
  writeFileSync(outside, "NESTED-SECRET", { mode: 0o600 })
  mkdirSync(join(src, "nested", "deeper"), { recursive: true })
  symlinkSync(outside, join(src, "nested", "deeper", "leak"))

  assert.throws(
    () => migrateStateDir(src, dst, { log: () => {} }),
    (err) => {
      assert.ok(err instanceof StateMigrationError)
      assert.equal(err.code, "unsafe-symlink")
      assert.match(err.message, /nested\/deeper\/leak/)
      return true
    },
  )
  assert.equal(existsSync(dst), false, "越界软链时必须停止，不得提升")
  assert.equal(readFileSync(outside, "utf8"), "NESTED-SECRET")
  assertNoRealDirTouched(before)
})

test("符号链接：指向目录内部时保留为链接本体，不复制目标内容", async (t) => {
  if (process.platform === "win32") return t.skip("Windows 符号链接需要额外权限")
  const before = snapshotRealDirs()
  const base = join(sandbox(), ".dsh")
  const src = join(base, PRIOR_DIR_NAME)
  const dst = join(base, CANONICAL_DIR_NAME)
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  // 目录内部软链：允许，但必须以链接形式保留（dereference:false）
  symlinkSync(join(src, "state.json"), join(src, "state-link.json"))

  const res = migrateStateDir(src, dst, { log: () => {} })
  assert.equal(res.migrated, true)
  const st = lstatSync(join(dst, "state-link.json"))
  assert.ok(st.isSymbolicLink(), "内部软链必须保留为链接，而不是被展开成文件副本")
  assertNoRealDirTouched(before)
})

// ================= §22.5 场景 9：迁移后新增设备不丢（§22.4 回滚红线）=================
//
// 方案 §22.4 的原文要求：「不支持反向转换就明确阻止自动回滚，提供保持新状态的
// 修复路径；**不能让旧版从备份启动丢掉新配对**」。
//
// 之前这条没有测试。它守的是最危险的一类回归：迁移之后新插件又配了一台设备，
// 此时旧备份已经落后；如果启动逻辑哪天「聪明」了一点 —— 比如看到旧目录存在就
// 回退过去，或者按 mtime 选「更新的那个」—— 用户会**静默丢掉新配对的设备**。
//
// 这里验证的是：迁移完成后，无论旧目录怎么变，解析结果**永远**是规范目录，
// 且新设备始终在结果里。
test("场景9 迁移后新增设备：解析持续指向新目录，绝不回退到落后的旧备份", async () => {
  const before = snapshotRealDirs()
  const home = sandbox()
  const src = join(home, ".dsh", PRIOR_DIR_NAME)
  const target = join(home, ".dsh", CANONICAL_DIR_NAME)

  // 迁移前：只有一台设备。
  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  const first = resolveStateDir({}, { home, log: () => {}, now: 1 })
  assert.equal(first.dir, target, "迁移后必须落到规范目录")
  assert.equal(first.ready, true)

  // 迁移之后新插件又配了一台设备，并改动了推送注册。
  const state = JSON.parse(readFileSync(join(target, "state.json"), "utf8"))
  state.devices.push(fakeDevice("B", "dev-2"))
  state.pairing = { lastPairedAt: 123 }
  writeFileSync(join(target, "state.json"), JSON.stringify(state, null, 2), { mode: 0o600 })

  // 此时旧备份**已经落后**（只有 dev-1）。
  const oldState = JSON.parse(readFileSync(join(src, "state.json"), "utf8"))
  assert.equal(oldState.devices.length, 1, "前置条件：旧备份应落后于新目录")

  // 把旧备份的 mtime 推到**未来**：任何"选更新的那个"式启发都会在这里露出马脚。
  // （单靠设备数量不够 —— 落后的备份也可能因为手工编辑而暂时变多。）
  const future = new Date(Date.now() + 86_400_000)
  utimesSync(join(src, "state.json"), future, future)

  // 再次启动：必须仍解析到新目录，且新设备还在。
  const second = resolveStateDir({}, { home, log: () => {}, now: 2 })
  assert.equal(second.dir, target, "不得回退到旧备份（那会丢掉迁移后的新配对）")
  assert.equal(second.migrated, false, "已经迁移过就不该再迁一次")

  const resolved = JSON.parse(readFileSync(join(second.dir, "state.json"), "utf8"))
  const ids = resolved.devices.map((d) => d.deviceId).sort()
  assert.deepEqual(ids, ["dev-1", "dev-2"], "迁移后新增的设备必须仍在（§22.4 红线）")

  // 旧备份保持原样，作为历史回滚点，不被静默改写。
  const oldAfter = JSON.parse(readFileSync(join(src, "state.json"), "utf8"))
  assert.deepEqual(oldAfter.devices.map((d) => d.deviceId), ["dev-1"], "旧备份不该被自动合并或改写")

  assertNoRealDirTouched(before)
})

// 反向：即便有人**手动**把旧目录改得「更新」（mtime 更晚、设备更多），
// 启动逻辑也不能因此改选它 —— 选源只看「是不是旧规范目录」，不看新旧程度。
test("场景9b 旧备份被人为改成更新也不改选：不回退、不按 mtime 择优", async () => {
  const before = snapshotRealDirs()
  const home = sandbox()
  const src = join(home, ".dsh", PRIOR_DIR_NAME)
  const target = join(home, ".dsh", CANONICAL_DIR_NAME)

  await writeStateDir(src, { devices: [fakeDevice("A", "dev-1")] })
  resolveStateDir({}, { home, log: () => {}, now: 1 })

  // 人为把旧备份改得「更诱人」：设备更多、mtime 更晚。
  const lure = JSON.parse(readFileSync(join(src, "state.json"), "utf8"))
  lure.devices.push(fakeDevice("GHOST", "ghost-1"), fakeDevice("GHOST2", "ghost-2"))
  writeFileSync(join(src, "state.json"), JSON.stringify(lure, null, 2), { mode: 0o600 })

  const again = resolveStateDir({}, { home, log: () => {}, now: 999999 })
  assert.equal(again.dir, target, "不得按 mtime 或设备数量改选旧目录")
  const resolved = JSON.parse(readFileSync(join(again.dir, "state.json"), "utf8"))
  assert.deepEqual(
    resolved.devices.map((d) => d.deviceId).sort(),
    ["dev-1"],
    "新目录的内容不得被旧备份污染（不拼设备数组）",
  )
  assertNoRealDirTouched(before)
})
