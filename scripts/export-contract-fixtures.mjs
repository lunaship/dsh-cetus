/**
 * 导出手机合同 fixtures（PLAN I3.2，对 main）。
 *
 * 用测试同款假 Host（临时 stateDir + typertGateway 假 DSH + 固定数据）跑真实插件，
 * 对 PLAN 附录 C 的每个手机接口发真实 HTTPS 请求，把响应体原样收进
 * testdata/mobile-contract/*.json。iOS Tests/Contract 逐个解码这些文件，Android 可接入同一批。
 *
 * 确定性：脚本内冻结 Date.now（2026-01-01T00:00:00Z，耗时测量因此恒为 0）；TLS 证书由插件在
 * 临时 stateDir 里照常生成，指纹与指纹前缀替换成占位串、剩余天数固定为数值（见 normalizeDiagnostics）；
 * 会话 / 审批 / 队列等 id 全部是假 Host 里的固定字符串。token、deviceId、rpcId、previewId、
 * 工作区审批 requestId、插件版本这类运行期或随版本变化的值，以及端口、主机名、临时目录前缀、
 * 二维码 urls，在最后统一替换成占位串（规则见 testdata/mobile-contract/README.md）。
 *
 * 用法：
 *   node scripts/export-contract-fixtures.mjs           写入/更新 fixtures，并删除不再生成的旧 json
 *   node scripts/export-contract-fixtures.mjs --check   只比对，不一致时列出文件并以非 0 退出
 */
import {
  mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, writeFileSync,
} from "node:fs"
import { tmpdir, hostname } from "node:os"
import { dirname, join } from "node:path"
import { pathToFileURL, fileURLToPath } from "node:url"
import { apply } from "../src/index.js"
import { PLUGIN_VERSION } from "../src/diagnostics.js"
import {
  FIXED_NOW,
  FIXTURE_CERT_DAYS_REMAINING,
  SESSION_LOGIN,
  SESSION_STOPPED,
  callPanelRoute,
  createFakeGateway,
  createWorkspaceTree,
  freePort,
  httpsAgentFor,
  makeCtx,
  openSse,
  proxyRequest,
  startUpstream,
} from "./ios-e2e-fixture.mjs"

const SCRIPT_PATH = fileURLToPath(import.meta.url)
const REPO_ROOT = dirname(dirname(SCRIPT_PATH))
const OUT_DIR = join(REPO_ROOT, "testdata", "mobile-contract")

// ---------- 占位串替换 ----------

/**
 * 运行期随机值 → 占位串，用精确整串匹配（部分替换会误伤正文里的巧合数字）。
 * 端口、主机名、临时目录前缀、二维码 urls 用单独规则。
 */
function createRedactor({ rootReal, proxyPort }) {
  const exact = new Map()
  const lanUrlRe = new RegExp(`^https://[^\\s/]+:${proxyPort}$`)
  const redactString = (value) => {
    if (exact.has(value)) return exact.get(value)
    if (value.startsWith(rootReal)) return `<fixtureRoot>${value.slice(rootReal.length)}`
    if (lanUrlRe.test(value)) return "https://<lanAddress>:<port>"
    return value
  }
  const redact = (value) => {
    if (typeof value === "string") return redactString(value)
    if (Array.isArray(value)) return value.map(redact)
    if (value && typeof value === "object") {
      const out = {}
      for (const [key, item] of Object.entries(value)) out[key] = redact(item)
      return out
    }
    return value
  }
  return {
    redact,
    set(value, placeholder) {
      if (value !== undefined && value !== null && String(value).length > 0) exact.set(String(value), placeholder)
    },
  }
}

/** 二维码 urls 依机器网卡数量而异：统一成单个占位条目，保持「https URL 数组」的形状。 */
function normalizePairUrls(body) {
  if (body && Array.isArray(body.urls)) body.urls = ["https://<lanAddress>:<port>"]
  return body
}

/**
 * 诊断报告里无法占位成字符串的数字字段：随机证书的剩余天数固定成
 * FIXTURE_CERT_DAYS_REMAINING（指纹 / 指纹前缀 / 插件版本走占位串，见 redactor 注册处）。
 */
function normalizeDiagnostics(report) {
  const tlsCheck = (report?.checks ?? []).find((check) => check?.id === "tls.cert")
  if (tlsCheck?.detail) tlsCheck.detail.daysRemaining = FIXTURE_CERT_DAYS_REMAINING
  return report
}

// ---------- 主流程 ----------

export async function buildContractFixtures() {
  const rootDir = mkdtempSync(join(tmpdir(), "dsh-contract-fixtures-"))
  const rootReal = realpathSync(rootDir)
  const stateDir = join(rootDir, "state")
  mkdirSync(stateDir, { recursive: true, mode: 0o700 })
  // TLS 证书不预置：由 apply() 的 loadOrCreateTls 在临时 stateDir 里照常生成
  const { workspaceDir, elsewhere } = createWorkspaceTree(rootDir)

  const realDateNow = Date.now
  Date.now = () => FIXED_NOW
  // e2e host 会改进程的 DSH_HOME。fixtures 只许看自己的临时目录，不能落到用户的 ~/.dsh。
  const previousHome = process.env.DSH_HOME
  const fixtureHome = join(rootDir, "dsh-home")
  mkdirSync(fixtureHome, { recursive: true, mode: 0o700 })
  process.env.DSH_HOME = fixtureHome
  const upstream = await startUpstream()
  let effects = []
  try {
    const proxyPort = await freePort()
    // macOS 的 tmpdir 带符号链接（/var → /private/var）：cwd 一律用 realpath，
    // 否则响应里的路径前缀和 rootReal 对不上，占位替换会漏。
    const gateway = createFakeGateway({ workspacePath: realpathSync(workspaceDir) })
    const { ctx, registered, effects: fx } = makeCtx({ upstreamPort: upstream.address().port, gateway })
    effects = fx
    await apply(ctx, { port: proxyPort, pairingTtlSeconds: 300, autoApprove: true, stateDir, eventPollIntervalMs: 60000 })

    const agent = httpsAgentFor(stateDir)
    const request = (path, init = {}) => proxyRequest({ agent, proxyPort, path, ...init })
    const pluginState = JSON.parse(readFileSync(join(stateDir, "state.json"), "utf8"))
    const redactor = createRedactor({ rootReal, proxyPort })
    redactor.set(hostname(), "<hostName>")
    redactor.set(pluginState.deviceId, "<hostId>")
    // 插件本次运行生成的自签证书：指纹整串与 8 位前缀精确匹配替换
    const generatedTls = JSON.parse(readFileSync(join(stateDir, "tls.json"), "utf8"))
    redactor.set(generatedTls.fingerprint, "<fingerprint>")
    redactor.set(String(generatedTls.fingerprint ?? "").slice(0, 8), "<fingerprintPrefix>")
    redactor.set(PLUGIN_VERSION, "<pluginVersion>")

    // 先收集原始响应，占位值收集齐后在导出前统一替换
    const raw = new Map()
    const put = (name, value, normalize) => { raw.set(name, { value: JSON.parse(JSON.stringify(value)), normalize }) }
    const authed = (path, init = {}) => request(path, { ...init, token: mainToken })

    const route = (path) => registered.find((r) => r.path === path)
    const pairInfo = async () => (await callPanelRoute(route("/dsh-link/pair-info"))).json
    const pairSettings = (requireConfirm) => callPanelRoute(route("/dsh-link/pair-settings"), { body: { requireConfirm } })
    const pair = (body) => request("/dsh-link/pair", { method: "POST", body })

    let mainToken = null

    // ---- 配对：pending / 成功 / 同名 409 ----
    await pairSettings(true)
    const pendingPair = await pair({ code: (await pairInfo()).pairingCode, deviceName: "iPad Pro", requestId: "fixture-pair-ipad-1" })
    if (pendingPair.status !== 200) throw new Error(`pair pending → ${pendingPair.status}`)
    redactor.set(pendingPair.json.token, "<token>")
    redactor.set(pendingPair.json.deviceId, "<deviceId>")
    put("pair-pending.json", pendingPair.json, normalizePairUrls)

    await pairSettings(false)
    const mainPair = await pair({ code: (await pairInfo()).pairingCode, deviceName: "iPhone 15 Pro", requestId: "fixture-pair-main-1" })
    if (mainPair.status !== 200) throw new Error(`pair main → ${mainPair.status}`)
    mainToken = mainPair.json.token
    redactor.set(mainToken, "<token>")
    redactor.set(mainPair.json.deviceId, "<deviceId>")
    put("pair.json", mainPair.json, normalizePairUrls)

    const conflict = await pair({ code: (await pairInfo()).pairingCode, deviceName: "iPhone 15 Pro", requestId: "fixture-pair-conflict-1" })
    if (conflict.status !== 409) throw new Error(`pair same-name → ${conflict.status}`)
    put("pair-same-name-409.json", conflict.json)

    // ---- 列表 / 详情 / 诊断 ----
    put("bootstrap.json", (await authed("/dsh-link/mobile/bootstrap")).json)
    put("sessions.json", (await authed("/dsh-link/mobile/sessions")).json)
    put("sessions-search.json", (await authed(`/dsh-link/mobile/sessions/search?q=${encodeURIComponent("登录")}`)).json)
    put("history.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/history`)).json)
    put("changes.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/changes?seq=13`)).json)
    put("diagnostics.json", (await authed("/dsh-link/mobile/diagnostics")).json, normalizeDiagnostics)

    // ---- 工作区：单层名称（200）与绝对路径（202 待本机批准） ----
    put("workspaces-200.json", (await authed("/dsh-link/mobile/workspaces", { method: "POST", body: { input: "analysis" } })).json)
    const pendingWorkspace = await authed("/dsh-link/mobile/workspaces", { method: "POST", body: { input: elsewhere } })
    if (pendingWorkspace.status !== 202) throw new Error(`workspaces absolute-path → ${pendingWorkspace.status}`)
    redactor.set(pendingWorkspace.json.requestId, "<requestId>")
    put("workspaces-202.json", pendingWorkspace.json)

    // ---- 发送与控制 ----
    put("prompt.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/prompt`, {
      method: "POST",
      body: { text: "把重试次数改成 3 次，然后跑一遍测试" },
    })).json)
    put("cancel.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/cancel`, { method: "POST", body: {} })).json)
    put("queue-edit.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/queue/inbox-1`, {
      method: "POST",
      body: { action: "edit", text: "把重试次数改成 3 次，并在修复后跑一遍回归测试" },
    })).json)

    // ---- 预览：面板批准后手机只能看 ----
    const approved = await callPanelRoute(route("/dsh-link/previews"), { body: { port: 4173, label: "Vite" } })
    if (approved.status !== 200) throw new Error(`previews approve → ${approved.status}`)
    redactor.set(approved.json.preview.previewId, "<previewId>")
    put("previews.json", (await authed("/dsh-link/mobile/previews")).json)
    put("preview-detections.json", (await authed("/dsh-link/mobile/preview-detections")).json)

    // ---- 模型页 ----
    put("balance.json", (await authed("/dsh-link/mobile/balance?locale=zh-CN")).json)
    put("providers.json", (await authed("/dsh-link/mobile/providers")).json)

    // ---- 会话 SSE：先采正常帧；登录会话的连接保持打开（文件 / 审批 / 提问 / diff 都要求活跃订阅） ----
    const streamPath = (sessionId) =>
      `/dsh-link/mobile/sessions/${sessionId}/stream?caps=${encodeURIComponent("sync2,multiQuestion,requestState")}`
    const loginStream = openSse({ agent, proxyPort, token: mainToken, path: streamPath(SESSION_LOGIN) })
    const loginFrames = await loginStream.frames
    if (!loginFrames.some((f) => f.event === "ready") || !loginFrames.some((f) => f.event === "stats")) {
      throw new Error(`session-stream 帧不完整：${loginFrames.map((f) => f.event).join(",")}`)
    }
    put("session-stream.json", loginFrames)
    const resyncStream = openSse({ agent, proxyPort, token: mainToken, path: streamPath(SESSION_STOPPED) })
    const resyncFrames = await resyncStream.frames
    if (!resyncFrames.some((f) => f.event === "resync-required")) {
      throw new Error(`session-stream-resync 缺少 resync-required 帧：${resyncFrames.map((f) => f.event).join(",")}`)
    }
    put("session-stream-resync.json", resyncFrames)
    resyncStream.close()

    // ---- 文件元信息 / 目录树（真实读临时工作区，mtime 已固定；要求活跃订阅） ----
    const fileRes = await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/file?path=${encodeURIComponent("config/timeouts.json")}`)
    if (fileRes.status !== 200) throw new Error(`file → ${fileRes.status}`)
    put("file-meta.json", {
      path: "config/timeouts.json",
      contentType: fileRes.headers["content-type"],
      size: Number(fileRes.headers["content-length"]),
      sha256: fileRes.headers["x-dsh-link-sha256"],
      filename: decodeURIComponent(fileRes.headers["x-dsh-link-filename"]),
      attachment: fileRes.headers["content-disposition"] !== undefined,
    })
    put("tree.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/tree`)).json)

    // ---- 审批 / 提问：经 approval/request、user-questions/request 瀑布挂起，手机快照 + 提交 ----
    const fakeSignal = { aborted: false, addEventListener() {}, removeEventListener() {} }
    const approvalPromise = ctx.listeners.get("approval/request")({
      id: "appr-tool-write-1",
      callId: "call-99",
      toolName: "write",
      agent: { session: { id: SESSION_LOGIN, events: [] } },
      signal: fakeSignal,
    }, async () => "rejected")
    const questionPromise = ctx.listeners.get("user-questions/request")({
      agent: { session: { id: SESSION_LOGIN } },
      signal: fakeSignal,
      questions: [{
        id: "deploy-target",
        header: "部署",
        question: "要把调整后的配置写到哪个环境？",
        options: [{ id: "staging", label: "测试环境" }, { id: "production", label: "生产环境" }],
      }],
    }, async () => null)

    const requests = await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/requests`)
    if (requests.status !== 200) throw new Error(`requests → ${requests.status}`)
    const rpcId = requests.json.questions.find((q) => q.status === "pending")?.rpcId
    if (!rpcId) throw new Error("requests 快照里没有 pending 澄清")
    redactor.set(rpcId, "<rpcId>")
    put("requests.json", requests.json)

    // 主机事件流：此刻 login 挂着审批（awaitingApproval），refactor 在跑（running）
    const hostFrames = await openSse({ agent, proxyPort, token: mainToken, path: "/dsh-link/mobile/events" }).frames
    if (!hostFrames.some((f) => f.data?.state === "awaitingApproval") || !hostFrames.some((f) => f.data?.state === "running")) {
      throw new Error(`host-events 帧不完整：${hostFrames.map((f) => f.data?.state).join(",")}`)
    }
    put("host-events.json", hostFrames)

    put("approval-submit.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/approval`, {
      method: "POST",
      body: { approvalId: "appr-tool-write-1", outcome: "allowed-once" },
    })).json)
    put("question-submit.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/question`, {
      method: "POST",
      body: { rpcId, answer: { answers: [{ id: "deploy-target", selected: ["staging"] }] } },
    })).json)
    await approvalPromise
    await questionPromise

    // diff 送出文件全文，只给持有该会话活跃订阅的设备：保持订阅期间再取
    put("changes-diff.json", (await authed(`/dsh-link/mobile/sessions/${SESSION_LOGIN}/changes/diff?seq=13&index=1`)).json)
    loginStream.close()

    // ---- 设备与吊销（只吊销 fixtures 自己的测试设备） ----
    put("devices.json", (await authed("/dsh-link/mobile/devices")).json)
    put("revoke.json", (await authed("/dsh-link/mobile/revoke", { method: "POST", body: { deviceId: mainPair.json.deviceId } })).json)

    // ---- 统一占位替换后导出 ----
    const fixtures = {}
    for (const [name, entry] of raw) {
      const redacted = redactor.redact(entry.value)
      fixtures[name] = entry.normalize ? entry.normalize(redacted) : redacted
    }
    return fixtures
  } finally {
    Date.now = realDateNow
    if (previousHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previousHome
    for (const fn of effects) try { fn() } catch {}
    upstream.close()
    rmSync(rootDir, { recursive: true, force: true })
  }
}

// ---------- CLI ----------

function serialize(value) {
  return `${JSON.stringify(value, null, 2)}\n`
}

function diffFixtureSets(fixtures) {
  const expected = new Map(Object.entries(fixtures).map(([name, value]) => [name, serialize(value)]))
  const disk = new Map()
  let names = []
  try {
    names = readdirSync(OUT_DIR).filter((name) => name.endsWith(".json")).sort()
  } catch {
    // 目录不存在：全部视为待写入
  }
  for (const name of names) {
    try { disk.set(name, readFileSync(join(OUT_DIR, name), "utf8")) } catch { disk.set(name, null) }
  }
  const missing = [...expected.keys()].filter((name) => !disk.has(name)).sort()
  const stale = [...disk.keys()].filter((name) => !expected.has(name)).sort()
  const changed = [...expected.keys()].filter((name) => disk.has(name) && disk.get(name) !== expected.get(name)).sort()
  return { missing, stale, changed }
}

async function main() {
  const check = process.argv.includes("--check")
  const fixtures = await buildContractFixtures()
  const { missing, stale, changed } = diffFixtureSets(fixtures)
  if (check) {
    const problems = [
      ...missing.map((name) => `缺少 ${name}`),
      ...changed.map((name) => `内容不一致 ${name}`),
      ...stale.map((name) => `不再生成的旧文件 ${name}`),
    ]
    if (problems.length > 0) {
      console.error("contract fixtures 与当前插件响应不一致（运行 node scripts/export-contract-fixtures.mjs 更新）：")
      for (const line of problems) console.error(`  - ${line}`)
      process.exit(1)
    }
    console.log(`contract fixtures 与当前插件响应一致（${Object.keys(fixtures).length} 个文件）`)
    return
  }
  mkdirSync(OUT_DIR, { recursive: true })
  for (const [name, value] of Object.entries(fixtures)) {
    writeFileSync(join(OUT_DIR, name), serialize(value), { mode: 0o644 })
  }
  for (const name of stale) {
    rmSync(join(OUT_DIR, name), { force: true })
  }
  console.log(`已写入 ${Object.keys(fixtures).length} 个 fixture → testdata/mobile-contract/${stale.length ? `（删除 ${stale.length} 个旧文件）` : ""}`)
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(err)
    process.exit(1)
  })
}
