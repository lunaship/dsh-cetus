/**
 * state 目录迁移（方案 §22.3）：~/.dsh/dsh-links → ~/.dsh/dsh-cetus
 *
 * 为什么单独一个模块：这段逻辑要动的**是用户唯一一份真实配对身份**（配对证书、主机密钥、
 * 设备批准、推送注册）。2026-09-12 的事故是 teardown 吊销了两台真机——所以这里的原则是
 * 「宁可拒绝启动，也绝不产生第二份身份、绝不静默清空」。
 *
 * 设计要点（对应 §22.3 红线 2–9）：
 *   1. 显式 stateDir 永远优先：调用方传了就用，本模块不读、不扫、不复制默认目录。
 *   2. 迁移 = 取锁 → 复制到临时目录 → 校验 → 原子提升 → 写迁移记录。
 *   3. 冲突（两目录都存在且身份不同）→ 抛错停下，不拼设备数组、不选较新者、不生成第三把密钥。
 *   4. 源损坏 / 证书缺失 / 复制失败 / 磁盘满 → 保留原目录，抛错，不静默清空。
 *   5. 迁移失败不返回可用目录（上层据此不启动远程注册，避免半迁移身份把 Relay 主机顶掉）。
 *   6. 幂等：目标已存在且与源一致就直接返回；已标记完成的不重复迁移。
 *   7. 日志绝不打印密钥/token（只打目录、设备数、指纹前缀）。
 *
 * 纯函数 + 显式注入（homedir/log/statfs），测试全部用 mkdtemp 假目录。
 */
import {
  chmodSync, cpSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, readFileSync,
  realpathSync, renameSync, rmSync, statSync, statfsSync, writeFileSync,
} from "node:fs"
import { isAbsolute, join, relative } from "node:path"
import { homedir } from "node:os"
import { X509Certificate } from "node:crypto"

/** 新规范默认目录名。改名后这里就是唯一默认值。 */
export const CANONICAL_DIR_NAME = "dsh-cetus"
/** 当前用户的真实 state（旧规范目录）。迁移的**源**，只读，永不删除。 */
export const PRIOR_DIR_NAME = "dsh-links"
/** 更早的历史目录（只在新旧规范目录都没有时才考虑）。 */
export const LEGACY_DIR_NAMES = ["dsh-deepharness", "dshlinks"]

export const MIGRATION_VERSION = 1
export const MIGRATION_FILE = "migration.json"
/** 迁移用的临时目录前缀：复制中断后下次能识别并清理。 */
export const STAGING_PREFIX = ".migrating-"
/** 迁移锁：持有者崩溃后靠 mtime 过期。 */
export const LOCK_FILE = ".migration.lock"
export const LOCK_STALE_MS = 5 * 60 * 1000

/**
 * state 目录里**必须搬过去**的文件。方案 §22.3 红线 9：
 * 目录里有证书、私钥、主机密钥、设备批准、推送注册、能力与持久化索引——不能只复制 state.json。
 *
 * 实测用户真实目录 `~/.dsh/dsh-links` 的内容：
 *   state.json（设备批准 / 推送注册 / deviceId / 远程身份 hostKey+keySeed）
 *   tls.json（18640 自签证书与私钥；App 按 SHA-256 指纹钉死）
 *   state.json.bak-before-dlp-*（历史备份，同样要搬 —— 漏了它就等于丢了一份恢复点）
 * 所以 REQUIRED 只放"缺了就没法启动"的两项，其余一律按目录整体搬运（见 copyAndVerify）。
 */
export const STATE_DIR_REQUIRED = [
  "state.json",  // 少了它设备批准全丢
  "tls.json",    // 少了它 App 按指纹钉死的连接全部失败
]

/** 兼容旧名（测试与外部引用）。 */
export const STATE_DIR_CONTENTS = STATE_DIR_REQUIRED

/**
 * 搬迁时排除的文件：只排除**本模块自己产生**的运行期文件与锁，
 * 其它一切（含 .bak-*、未来的新 state 文件、持久化索引）都必须跟着走。
 * 原则是「默认搬运、显式排除」，而不是「默认丢弃、显式搬运」——
 * 后者漏一个文件就是一次静默的数据丢失。
 */
export const MIGRATION_EXCLUDED = [LOCK_FILE, MIGRATION_FILE]

/** 迁移时不搬的运行期前缀（临时/原子写残留）。 */
const TRANSIENT_SUFFIX = /\.\d+\.tmp$/

/** 列出目录里所有应当一起搬迁的条目。 */
export function movableEntries(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    if (MIGRATION_EXCLUDED.includes(name)) continue
    if (TRANSIENT_SUFFIX.test(name)) continue
    out.push(name)
  }
  return out.sort()
}

/**
 * 迁移记录只写这些字段，**绝不含密钥/token**。
 *
 * 为什么 `tlsFingerprint` 可以写、而 `privKey` / `remote.hostKey` 不可以：
 *   - TLS 指纹（SHA-256）是**公开身份**，不是秘密。它本来就要给手机看（App 按指纹钉死证书，
 *     配对二维码与面板都展示它），写进迁移记录不扩大暴露面，却能让人一眼核对
 *     "迁移前后是不是同一台电脑"，是排查身份错乱的关键证据。
 *   - 私钥（tls.json 的 key）、远程主机密钥（remote.hostKey / keySeed）、设备 token、
 *     推送 token 是**凭据**：拿到即可冒充本机连中继 / 推消息。它们没有任何排查价值，
 *     只应在 state 目录内以 0600 存在。
 *
 * 所以：以后要往这里加字段，先问"这个值给攻击者有没有用"。有用 = 不要加。
 * 有 test/state-migration.test.mjs 的用例守着这条（含全部失败路径）。
 */
function publicRecord(record) {
  return {
    migrationVersion: record.migrationVersion,
    sourcePath: record.sourcePath,
    status: record.status,
    at: record.at,
    deviceCount: record.deviceCount,
    tlsFingerprint: record.tlsFingerprint,
    note: record.note,
  }
}

export class StateMigrationError extends Error {
  constructor(code, message, { sourcePath, targetPath, remedy } = {}) {
    super(message)
    this.name = "StateMigrationError"
    this.code = code
    this.sourcePath = sourcePath
    this.targetPath = targetPath
    this.remedy = remedy
  }
}

function readJson(file) {
  let raw
  try {
    raw = readFileSync(file, "utf8")
  } catch (err) {
    throw new StateMigrationError("read-failed", `无法读取 ${file}: ${err?.message ?? err}`)
  }
  try {
    return JSON.parse(raw)
  } catch {
    throw new StateMigrationError(
      "source-corrupt",
      `state JSON 已损坏，拒绝迁移也拒绝静默清空：${file}`,
      { remedy: "修好或移走该文件后重试；源目录保持原样" },
    )
  }
}

function deviceIdsOf(state) {
  const devices = Array.isArray(state?.devices) ? state.devices : []
  return devices.map((d) => d?.deviceId).filter((id) => typeof id === "string" && id).sort()
}

/** 证书指纹（小写 hex，去冒号）。证书缺失/坏 → 抛错，绝不"顺手生成一把新的"。 */
export function tlsFingerprintOf(dir) {
  const file = join(dir, "tls.json")
  if (!existsSync(file)) {
    throw new StateMigrationError("tls-missing", `缺少 tls.json（配对证书与私钥）：${file}`, {
      remedy: "从备份恢复该文件；不要重新生成，否则已配对手机全部需要重扫",
    })
  }
  const tls = readJson(file)
  if (!tls?.cert || !tls?.key) {
    throw new StateMigrationError("tls-missing", `tls.json 缺 cert/key：${file}`)
  }
  let fp
  try {
    const cert = new X509Certificate(tls.cert)
    fp = String(cert.fingerprint256).replace(/:/g, "").toLowerCase()
  } catch (err) {
    throw new StateMigrationError("tls-corrupt", `tls.json 证书无法解析：${file} (${err?.message ?? err})`)
  }
  return typeof tls.fingerprint === "string" && tls.fingerprint ? tls.fingerprint.toLowerCase() : fp
}

/** 远程主机公钥（DLP/1 routeId 的来源）。没有远程身份返回 ""，不是错误。 */
export function remoteHostKeyOf(state) {
  const key = state?.remote?.hostKey
  return typeof key === "string" && key ? key : ""
}

/** 目录是否"看起来有"可变状态（用于区分空目录与真状态目录）。 */
export function hasStateFiles(dir) {
  return STATE_DIR_REQUIRED.some((f) => existsSync(join(dir, f)))
}

/** 读取并校验一个状态目录；返回可用于比较的身份快照。 */
export function inspectStateDir(dir) {
  const stateFile = join(dir, "state.json")
  const state = readJson(stateFile)
  return {
    dir,
    state,
    deviceIds: deviceIdsOf(state),
    deviceCount: Array.isArray(state?.devices) ? state.devices.length : 0,
    tlsFingerprint: tlsFingerprintOf(dir),
    remoteHostKey: remoteHostKeyOf(state),
  }
}

/** 是否处于复制中断留下的暂存目录。 */
export function isStagingDir(name) {
  return typeof name === "string" && name.startsWith(STAGING_PREFIX)
}

/** 清理历史遗留的暂存目录（中断恢复）。只删暂存，不碰真目录。 */
export function cleanStaleStaging(parentDir) {
  let removed = []
  if (!existsSync(parentDir)) return removed
  for (const entry of readdirSync(parentDir)) {
    if (!isStagingDir(entry)) continue
    try {
      rmSync(join(parentDir, entry), { recursive: true, force: true })
      removed.push(entry)
    } catch { /* 清不掉就留着，下次再试；不影响正确性 */ }
  }
  return removed
}

/** 磁盘是否放得下 bytes（含 20% 余量）。statfs 不可用时保守放行。 */
export function hasDiskSpace(dir, bytes, { reserve = 0.2, freeSpace = null } = {}) {
  try {
    let avail
    if (typeof freeSpace === "function") {
      avail = freeSpace(dir)
      if (typeof avail !== "number" || !Number.isFinite(avail)) return true
    } else {
      const st = statfsSync(existsSync(dir) ? dir : join(dir, ".."))
      avail = Number(st.bavail) * Number(st.bsize)
    }
    return avail >= bytes * (1 + reserve)
  } catch {
    return true
  }
}

function dirSizeOf(dir) {
  let total = 0
  const walk = (p) => {
    let st
    try { st = statSync(p, { throwIfNoEntry: false }) } catch { return }
    if (!st) return
    if (st.isDirectory()) {
      try { for (const c of readdirSync(p)) walk(join(p, c)) } catch { /* 忽略 */ }
      return
    }
    total += st.size
  }
  for (const name of movableEntries(dir)) walk(join(dir, name))
  return total
}

/** 原子提升：同父目录 rename，避免跨设备 exdev 与半成品目录。 */
function atomicPromote(stagingDir, targetDir) {
  mkdirSync(join(targetDir, ".."), { recursive: true, mode: 0o700 })
  renameSync(stagingDir, targetDir)
  try { chmodSync(targetDir, 0o700) } catch {}
}

/**
 * 一致性校验：源与目标必须是**同一身份**。
 * 红线 4：指纹/公钥不一致就停下——不拼设备数组、不选较新者、不生成第三把密钥。
 */
export function assertSameIdentity(source, target) {
  if (source.tlsFingerprint !== target.tlsFingerprint) {
    throw new StateMigrationError(
      "identity-conflict",
      "新旧状态目录的 TLS 身份不一致：这是两台不同的「电脑」。已停止迁移，两边数据都原样保留。",
      {
        sourcePath: source.dir,
        targetPath: target.dir,
        remedy: "确认哪一份是真的；删掉错误的那份（或显式配置 stateDir）后重试。不会自动合并，也不会新建证书。",
      },
    )
  }
  const a = source.remoteHostKey
  const b = target.remoteHostKey
  if (a && b && a !== b) {
    throw new StateMigrationError(
      "identity-conflict",
      "新旧状态目录的远程主机公钥不一致：这是两个不同的中继身份。已停止迁移，两边数据都原样保留。",
      {
        sourcePath: source.dir,
        targetPath: target.dir,
        remedy: "确认哪一份是真的；删掉错误的那份（或显式配置 stateDir）后重试。",
      },
    )
  }
}

function writeMigrationRecord(targetDir, record) {
  const file = join(targetDir, MIGRATION_FILE)
  const tmp = `${file}.${process.pid}.tmp`
  writeFileSync(tmp, JSON.stringify(publicRecord(record), null, 2), { mode: 0o600 })
  renameSync(tmp, file)
  try { chmodSync(file, 0o600) } catch {}
}

/** 读迁移记录；不存在或损坏都返回 null（损坏的记录不能阻止启动）。 */
export function readMigrationRecord(dir) {
  const file = join(dir, MIGRATION_FILE)
  if (!existsSync(file)) return null
  try {
    return JSON.parse(readFileSync(file, "utf8"))
  } catch {
    return null
  }
}

/**
 * 取迁移锁。锁被别的进程持有且未过期 → 抛错（并发启动时宁可不迁移）。
 * 过期锁（持有者崩溃）允许接管。
 */
function acquireLock(parentDir, now) {
  mkdirSync(parentDir, { recursive: true, mode: 0o700 })
  const lock = join(parentDir, LOCK_FILE)
  if (existsSync(lock)) {
    let age = Infinity
    try { age = now - statSync(lock).mtimeMs } catch { age = Infinity }
    if (age < LOCK_STALE_MS) {
      throw new StateMigrationError(
        "locked",
        `另一个进程正在迁移状态目录（锁 ${lock}）。已跳过迁移，源目录未改动。`,
        { remedy: "等它结束再启动；若确认没有别的进程，删掉该锁文件后重试" },
      )
    }
    try { rmSync(lock, { force: true }) } catch { /* 交给下面的写入失败暴露 */ }
  }
  try {
    writeFileSync(lock, JSON.stringify({ pid: process.pid, at: now }), { mode: 0o600, flag: "wx" })
  } catch (err) {
    throw new StateMigrationError("locked", `无法获取迁移锁 ${lock}：${err?.message ?? err}`)
  }
  return lock
}

function releaseLock(lock) {
  try { rmSync(lock, { force: true }) } catch { /* 过期锁会自行失效 */ }
}

/**
 * 把源目录**整体**复制到 staging，再逐字节校验。
 *
 * 为什么是整体而非白名单：用户真实目录里除了 state.json / tls.json 还有
 * `state.json.bak-before-dlp-*` 这类备份。白名单漏一项就是一次静默数据丢失，
 * 所以策略是「默认搬全部，只排除本模块自己的运行期文件」。
 *
 * 复制失败 / 磁盘满 → 删 staging、抛错、源目录原封不动（红线 5）。
 */
function copyAndVerify(sourceDir, stagingDir, { now, log, freeSpace = null }) {
  const size = dirSizeOf(sourceDir)
  // freeSpace 是可注入的剩余空间探针（默认 statfs）。测试用它在不挂小文件系统的前提下
  // 真实走一遍 ENOSPC 分支——这条分支必须被测到，因为它的后果是"数据没了"。
  if (!hasDiskSpace(stagingDir, size, { freeSpace })) {
    throw new StateMigrationError(
      "disk-full",
      "磁盘空间不足，已取消迁移；源目录原样保留。",
      { sourcePath: sourceDir, remedy: "腾出空间后重试" },
    )
  }
  // 必需文件先检查：缺 tls.json 就绝不动手（否则会"顺手"生成一把新证书）
  for (const f of STATE_DIR_REQUIRED) {
    if (existsSync(join(sourceDir, f))) continue
    const code = f === "state.json" ? "incomplete-source" : "tls-missing"
    throw new StateMigrationError(
      code,
      `源状态目录缺少必需文件 ${f}，拒绝迁移（避免产生半份身份）：${sourceDir}`,
      { sourcePath: sourceDir, remedy: "从备份恢复该文件后重试" },
    )
  }
  const entries = movableEntries(sourceDir)
  if (entries.length === 0) {
    throw new StateMigrationError("incomplete-source", `源状态目录为空，拒绝迁移：${sourceDir}`, { sourcePath: sourceDir })
  }
  mkdirSync(stagingDir, { recursive: true, mode: 0o700 })
  try { chmodSync(stagingDir, 0o700) } catch {}
  for (const name of entries) {
    const from = join(sourceDir, name)
    const to = join(stagingDir, name)
    // 符号链接：绝不 dereference。跟随链接会把**链接目标**（可能在 state 目录之外，
    // 比如 ~/.ssh/id_rsa）的内容复制进 state 目录 —— 既是越界拷贝，也是把秘密搬到了
    // 预期之外的位置。这里先做越界检查，再用 verbatimSymlinks 原样保留链接本身。
    assertTreeLinksStayInside(from, sourceDir, name)
    try {
      cpSync(from, to, {
        recursive: true,
        force: true,
        dereference: false,          // 不跟随：保留链接，不把目标内容搬进来
        verbatimSymlinks: true,      // 链接路径原样保留，不做重写
        errorOnExist: false,
      })
    } catch (err) {
      throw new StateMigrationError(
        "copy-failed",
        `复制 ${name} 失败：${err?.message ?? err}。源目录原样保留。`,
        { sourcePath: sourceDir },
      )
    }
    hardenPermissions(to)
  }
  // 复制后逐条比对：磁盘满的典型表现就是静默截断。目录要递归比对。
  for (const name of entries) {
    verifyEntry(join(sourceDir, name), join(stagingDir, name), name, sourceDir)
  }
  // staging 必须是**身份一致**的一份拷贝，否则不许提升。
  const staged = inspectStateDir(stagingDir)
  return { staged, size, at: now, log }
}

/**
 * 收紧权限（红线 3）：**目录 0700、秘密文件 0600**。
 *
 * 这里必须区分类型。曾经写成"一律 chmod 600"，结果嵌套目录丢了执行位（0600 的目录进不去），
 * 里面的文件连读都读不到 —— 是一类会静默破坏数据的 bug，所以对目录递归处理。
 */
function hardenPermissions(target) {
  let st
  try { st = statSync(target, { throwIfNoEntry: false }) } catch { return }
  if (!st) return
  if (st.isDirectory()) {
    try { chmodSync(target, 0o700) } catch { /* 尽力而为 */ }
    let names = []
    try { names = readdirSync(target) } catch { return }
    for (const name of names) hardenPermissions(join(target, name))
    return
  }
  try { chmodSync(target, 0o600) } catch { /* 尽力而为 */ }
}

/**
 * 符号链接越界检查：链接指向 state 目录之外一律拒绝。
 *
 * 理由：迁移只应搬 state 目录**自己**的数据。一个指向 ~/.ssh/id_rsa 的软链如果被跟随，
 * 就会把私钥内容复制进 state 目录（并且可能被后续逻辑当成 state 的一部分）。
 * 这种"越界拷贝"同时是安全问题与数据完整性问题，所以直接拒绝并让人工处理。
 */
function assertLinkStaysInside(target, sourceDir, label) {
  let st
  try { st = lstatSync(target, { throwIfNoEntry: false }) } catch { return }
  if (!st?.isSymbolicLink()) return
  let resolved
  try { resolved = realpathSync(target) } catch {
    throw new StateMigrationError(
      "unsafe-symlink",
      `状态目录里的符号链接 ${label} 指向不存在的目标，已停止迁移（避免复制出意外内容）。`,
      { sourcePath: sourceDir, remedy: `检查 ${label} 这个软链，指向正确位置后重试` },
    )
  }
  const root = realpathSync(sourceDir)
  const rel = relative(root, resolved)
  if (rel.startsWith("..") || isAbsolute(rel)) {
    throw new StateMigrationError(
      "unsafe-symlink",
      `状态目录里的符号链接 ${label} 指向目录之外（${resolved}），已停止迁移以免把外部文件复制进来。`,
      { sourcePath: sourceDir, remedy: `把 ${label} 换成目录内的真实文件，或删掉该软链后重试` },
    )
  }
}

/**
 * 递归预检：嵌套目录里的软链同样不许越界。
 *
 * 只查顶层不够：`sessions/x -> ~/.ssh/id_rsa` 这种二级软链在复制后校验（statSync /
 * readFileSync 会跟随链接）时仍会读到外部文件。所以复制前用 lstat 逐层下钻，
 * 不跟随任何链接进入子目录。
 */
function assertTreeLinksStayInside(target, sourceDir, label) {
  let st
  try { st = lstatSync(target, { throwIfNoEntry: false }) } catch { return }
  if (!st) return
  if (st.isSymbolicLink()) {
    assertLinkStaysInside(target, sourceDir, label)
    return
  }
  if (!st.isDirectory()) return
  let names = []
  try { names = readdirSync(target) } catch { return }
  for (const child of names) {
    assertTreeLinksStayInside(join(target, child), sourceDir, `${label}/${child}`)
  }
}

/** 递归校验一条复制结果：目录逐层下钻，文件逐字节比对。 */
function verifyEntry(from, to, name, sourceDir) {
  let st
  try {
    st = statSync(from, { throwIfNoEntry: false })
  } catch {
    st = null
  }
  if (!st) {
    throw new StateMigrationError("copy-failed", `复制后源文件消失了：${name}`, { sourcePath: sourceDir })
  }
  if (st.isDirectory()) {
    let names
    try {
      names = readdirSync(from).sort()
    } catch (err) {
      throw new StateMigrationError("copy-failed", `复制后校验无法读取目录 ${name}：${err?.message ?? err}`, { sourcePath: sourceDir })
    }
    for (const child of names) {
      verifyEntry(join(from, child), join(to, child), `${name}/${child}`, sourceDir)
    }
    return
  }
  let a, b
  try {
    a = readFileSync(from)
    b = readFileSync(to)
  } catch (err) {
    throw new StateMigrationError("copy-failed", `复制后校验无法读取 ${name}：${err?.message ?? err}`, { sourcePath: sourceDir })
  }
  if (!a.equals(b)) {
    throw new StateMigrationError(
      "copy-failed",
      `复制后校验不一致（${name}）：可能是磁盘满。已取消迁移，源目录原样保留。`,
      { sourcePath: sourceDir },
    )
  }
}

/** 默认日志：只打目录、设备数、指纹前缀。永不打印密钥/token/state 原文。 */
function defaultLog(level, message, detail) {
  const line = detail ? `${message} ${JSON.stringify(detail)}` : message
  if (level === "error") console.error(`dsh-cetus: ${line}`)
  else console.log(`dsh-cetus: ${line}`)
}

function shortFp(fp) {
  return typeof fp === "string" && fp.length >= 12 ? `${fp.slice(0, 12)}…` : "(unknown)"
}

/** 身份摘要：只输出可安全打印的字段。 */
function identitySummary(dir, snapshot) {
  return {
    dir,
    devices: snapshot.deviceCount,
    tls: shortFp(snapshot.tlsFingerprint),
    remote: snapshot.remoteHostKey ? "present" : "none",
  }
}

/**
 * 核心：把 `sourceDir` 的有效状态迁移进 `targetDir`。
 *
 * @returns {{migrated: boolean, adopted: boolean, record: object|null, targetDir: string}}
 *   migrated: 本次真的做了迁移
 *   adopted:  目标已存在且与源一致（幂等命中，未改动任何数据）
 */
export function migrateStateDir(sourceDir, targetDir, { now = Date.now(), log = defaultLog, freeSpace = null } = {}) {
  const targetExists = existsSync(targetDir)
  const targetHasState = targetExists && hasStateFiles(targetDir)
  const sourceHasState = existsSync(sourceDir) && hasStateFiles(sourceDir)

  // ---- 幂等：目标已经是有效状态目录 ----
  if (targetHasState) {
    const target = inspectStateDir(targetDir)
    if (!sourceHasState) {
      // 目标就是唯一真相；源已经不存在（或从没存在过）。不重复迁移。
      const record = readMigrationRecord(targetDir)
      log("info", "state 目录已是当前规范，无需迁移", identitySummary(targetDir, target))
      return { migrated: false, adopted: true, record: record ? publicRecord(record) : null, targetDir }
    }
    const source = inspectStateDir(sourceDir)
    // 目标存在且身份一致 → 幂等命中，绝不回覆盖（红线 7）。
    assertSameIdentity(source, target)
    const record = readMigrationRecord(targetDir) ?? {
      migrationVersion: MIGRATION_VERSION,
      sourcePath: sourceDir,
      status: "adopted",
      at: now,
      deviceCount: target.deviceCount,
      tlsFingerprint: target.tlsFingerprint,
    }
    log("info", "state 目录已迁移过，跳过（幂等）", identitySummary(targetDir, target))
    return { migrated: false, adopted: true, record: publicRecord(record), targetDir }
  }

  // ---- 没有源数据：全新安装 ----
  if (!sourceHasState) {
    mkdirSync(targetDir, { recursive: true, mode: 0o700 })
    try { chmodSync(targetDir, 0o700) } catch {}
    log("info", "无旧状态数据，按全新安装初始化", { dir: targetDir })
    return { migrated: false, adopted: false, record: null, targetDir }
  }

  // ---- 真迁移 ----
  const source = inspectStateDir(sourceDir)
  const parent = join(targetDir, "..")
  const lock = acquireLock(parent, now)
  let stagingDir = null
  try {
    // 复制中断的残留先清掉，避免上一次的半个目录被误当成"已迁移"。
    cleanStaleStaging(parent)
    stagingDir = mkdtempSync(join(parent, STAGING_PREFIX))
    try { chmodSync(stagingDir, 0o700) } catch {}
    const { staged } = copyAndVerify(sourceDir, stagingDir, { now, log, freeSpace })
    // 提升前的最后一道身份闸门。
    assertSameIdentity(source, staged)
    atomicPromote(stagingDir, targetDir)
    stagingDir = null
    const record = {
      migrationVersion: MIGRATION_VERSION,
      sourcePath: sourceDir,
      status: "migrated",
      at: now,
      deviceCount: source.deviceCount,
      tlsFingerprint: source.tlsFingerprint,
      note: "源目录保持原样作为受保护备份；确认新目录正常后可自行删除。",
    }
    writeMigrationRecord(targetDir, record)
    log("info", "state 迁移完成（源目录保留为备份）", identitySummary(targetDir, source))
    return { migrated: true, adopted: false, record: publicRecord(record), targetDir }
  } catch (err) {
    // 失败一律回滚：删 staging，**绝不**动 source，也绝不留下半个 target（红线 5）。
    if (stagingDir) {
      try { rmSync(stagingDir, { recursive: true, force: true }) } catch { /* 下次 cleanStaleStaging */ }
    }
    if (err instanceof StateMigrationError) throw err
    throw new StateMigrationError("failed", `迁移失败，源目录原样保留：${err?.message ?? err}`, {
      sourcePath: sourceDir,
      targetPath: targetDir,
    })
  } finally {
    releaseLock(lock)
  }
}

/**
 * 解析最终生效的 state 目录并完成必要的迁移。
 *
 * 红线 1：显式 stateDir 永远优先 —— 直接返回，不扫描、不复制默认目录。
 *
 * @param {object} config 插件配置（只读 stateDir）
 * @param {object} [opts]
 * @param {string} [opts.home] 用户 home 目录。默认取 os.homedir()；测试注入假 home。
 * @returns {{dir: string|null, migrated: boolean, adopted: boolean, record: object|null,
 *            ready: boolean, error?: Error}}
 *   ready=false 表示迁移失败。上层**不得**继续启动远程注册（红线 6）。
 */
export function resolveStateDir(config, { home = null, now = Date.now(), log = defaultLog, freeSpace = null } = {}) {
  const explicit = typeof config?.stateDir === "string" ? config.stateDir.trim() : ""
  if (explicit) {
    // 显式配置：只做权限收敛，不迁移、不生成密钥。
    try {
      mkdirSync(explicit, { recursive: true, mode: 0o700 })
      try { chmodSync(explicit, 0o700) } catch {}
    } catch (err) {
      return {
        dir: explicit, migrated: false, adopted: false, record: null, ready: false,
        error: new StateMigrationError("explicit-dir-unusable", `无法使用配置的 stateDir ${explicit}：${err?.message ?? err}`),
      }
    }
    return { dir: explicit, migrated: false, adopted: false, record: null, ready: true }
  }
  // home 是「用户目录」，state 放在 <home>/.dsh/ 下。
  const base = join(home ?? homedir(), ".dsh")
  const targetDir = join(base, CANONICAL_DIR_NAME)
  try {
    const migrated = migrateStateDir(candidatesFor(base), targetDir, { now, log, freeSpace })
    return {
      dir: migrated.targetDir,
      migrated: migrated.migrated,
      adopted: migrated.adopted,
      record: migrated.record,
      ready: true,
    }
  } catch (err) {
    // 迁移失败：不抛给启动流程炸掉，而是显式标记 ready=false。
    // 上层据此跳过远程注册（红线 6：半迁移身份会把 Relay 主机顶掉）。
    log("error", "state 目录迁移失败，已跳过迁移；远程连接不会启动，源目录未改动", {
      error: err?.code ?? err?.name ?? "unknown",
      source: err?.sourcePath,
      target: err?.targetPath,
      remedy: err?.remedy,
    })
    return { dir: null, migrated: false, adopted: false, record: null, ready: false, error: err }
  }
}

/**
 * 迁移源候选：真实的旧规范目录优先；其次才是更早的历史目录。
 * 只挑第一个**确实有 state.json** 的目录，避免把空目录当成源。
 */
function candidatesFor(base) {
  const candidates = [join(base, PRIOR_DIR_NAME), ...LEGACY_DIR_NAMES.map((n) => join(base, n))]
  return candidates.find((d) => existsSync(join(d, "state.json"))) ?? candidates[0]
}
