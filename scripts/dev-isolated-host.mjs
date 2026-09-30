/**
 * dev-isolated-host —— 常驻的隔离 DSH 联调宿主（第三轮 K1）。
 *
 * 与 `scripts/e2e-arch-smoke.mjs` 同源：起第二个 DSH 网页宿主，随机/指定端口，插件 `stateDir`
 * 指向一个临时目录（0700），**不碰**用户全局 `~/.dsh/dsh-links/state.json`，也不做任何设备吊销。
 * 区别是这个脚本启动后保持运行，直到 Ctrl-C 或 SIGTERM，并在退出时清理干净。
 *
 * 隔离与只读约束（与 docs/COMPATIBILITY.md「Smoke-isolation warning」一致）：
 * - 插件状态完全在临时 `stateDir`；配对设备只写临时状态。
 * - 读取用户真实的 `~/.dsh` 会话数据（bootstrap 会返回真实会话），仅用于还原真机数据形态，**只读**：
 *   不发送 prompt、不新建/归档/删除/重命名会话、不注册/删除工作区、不改设置。
 * - 手机端口默认 18641（**绝不能**碰用户的 18640）。远程保持关闭（临时状态默认关）。
 * - `autoApprove: true`：配对不需要在电脑面板再点确认，省去联调中的批准步骤。
 *
 * 用法：
 *   node scripts/dev-isolated-host.mjs
 *   WEB_PORT=19389 MOBILE_PORT=18641 node scripts/dev-isolated-host.mjs
 *
 * 输出（联调时按这些值在手机 / 模拟器里配对）：
 *   - 面板：http://127.0.0.1:<WEB_PORT>/dsh-link/pair-info
 *   - 手机（模拟器走 10.0.2.2）：https://10.0.2.2:<MOBILE_PORT>
 *   - 配对码、证书指纹、stateDir、日志路径
 */
import { spawn } from "node:child_process"
import { createServer } from "node:http"
import { homedir, tmpdir } from "node:os"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = createServer()
    srv.unref()
    srv.once("error", reject)
    srv.listen(0, "127.0.0.1", () => {
      const port = srv.address().port
      srv.close(() => resolve(port))
    })
  })
}

const WEB_PORT = Number(process.env.WEB_PORT ?? 0) || (await freePort())
const MOBILE_PORT = Number(process.env.MOBILE_PORT ?? 18641)
if (MOBILE_PORT === 18640) {
  console.error("拒绝：MOBILE_PORT 不能是用户正在使用的 18640")
  process.exit(2)
}

const SCRATCH = mkdtempSync(join(tmpdir(), "dsh-links-devhost-"))
const STATE_DIR = join(SCRATCH, "state")
const PATCH_FILE = join(SCRATCH, "patch.yml")
const LOG_FILE = join(SCRATCH, "host.log")

mkdirSync(STATE_DIR, { recursive: true, mode: 0o700 })
try { chmodSync(STATE_DIR, 0o700) } catch {}
writeFileSync(PATCH_FILE, [
  "# dev-isolated-host：插件状态隔离到临时目录，端口避开用户正在使用的 18640",
  "- id: dsh-links",
  "  config:",
  `    stateDir: ${STATE_DIR}`,
  `    port: ${MOBILE_PORT}`,
  "    autoApprove: true",
  "",
].join("\n"))

console.log(`[dev-isolated-host] scratch=${SCRATCH}`)
console.log(`[dev-isolated-host] stateDir=${STATE_DIR}（临时，退出时删除）`)
console.log(`[dev-isolated-host] 面板 http://127.0.0.1:${WEB_PORT}/dsh-link/pair-info`)
console.log(`[dev-isolated-host] 手机(模拟器) https://10.0.2.2:${MOBILE_PORT}`)

const child = spawn("dsh", ["--profile", "web", "--patch", PATCH_FILE, "--port", String(WEB_PORT), "--no-open"], {
  cwd: ROOT,
  env: { ...process.env },
  stdio: ["ignore", "pipe", "pipe"],
})
const log = []
child.stdout.on("data", (d) => log.push(String(d)))
child.stderr.on("data", (d) => log.push(String(d)))
let exitInfo = null
child.on("exit", (code, sig) => { exitInfo = { code, sig } })

async function pairInfo() {
  try {
    const res = await fetch(`http://127.0.0.1:${WEB_PORT}/dsh-link/pair-info`)
    if (!res.ok) return null
    return await res.json()
  } catch {
    return null
  }
}

async function waitForReady(timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (exitInfo) return null
    const info = await pairInfo()
    if (info?.type === "dsh-link" && /^[0-9a-f]{64}$/.test(String(info.certFingerprint ?? ""))) {
      return info
    }
    await sleep(1000)
  }
  return null
}

let cleaned = false
function cleanup() {
  if (cleaned) return
  cleaned = true
  writeFileSync(LOG_FILE, log.join(""))
  try { child.kill("SIGTERM") } catch {}
  const deadline = Date.now() + 5000
  while (!exitInfo && Date.now() < deadline) {
    // 同步等待进程退出（顶层脚本不能 await here 之外的清理；用忙等兜底）
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 200)
  }
  if (!exitInfo) { try { child.kill("SIGKILL") } catch {} }
  console.log(`[dev-isolated-host] 日志：${LOG_FILE}`)
  rmSync(SCRATCH, { recursive: true, force: true })
  console.log("[dev-isolated-host] 已清理临时 stateDir 与进程")
}

process.on("SIGINT", () => { cleanup(); process.exit(0) })
process.on("SIGTERM", () => { cleanup(); process.exit(0) })

const info = await waitForReady()
if (!info) {
  writeFileSync(LOG_FILE, log.join(""))
  console.error(`[dev-isolated-host] 启动失败（host 未就绪）。日志：${LOG_FILE}`)
  console.error(log.join("").slice(-2000))
  cleanup()
  process.exit(1)
}

const fp = String(info.certFingerprint).toLowerCase()
const fpPretty = fp.replace(/(.{4})/g, "$1 ").trim()
console.log("")
console.log("================ 隔离宿主已就绪 ================")
console.log(`面板地址   : http://127.0.0.1:${WEB_PORT}/dsh-link/pair-info`)
console.log(`手机地址   : https://10.0.2.2:${MOBILE_PORT}`)
console.log(`配对码     : ${info.pairingCode}`)
console.log(`证书指纹   : ${fpPretty}`)
console.log(`stateDir   : ${STATE_DIR}`)
console.log(`host 日志  : ${LOG_FILE}`)
console.log("------------------------------------------------")
console.log("在模拟器里用「手动添加」填上面地址 + 配对码 + 指纹（beta.21 才有手动添加）。")
console.log("Ctrl-C 退出并清理。")
console.log("================================================")

// 保持运行：定期把子进程日志刷到文件，便于排查。
const flush = setInterval(() => writeFileSync(LOG_FILE, log.join("")), 2000)
process.on("exit", () => { clearInterval(flush); if (!cleaned) cleanup() })
