/**
 * ios-e2e-host 控制面（不带模拟器）。
 *
 * 起隔离宿主，核对二维码、用插件 TLS 证书配对、确认无订阅时审批钩子立即失败，
 * 再确认 shutdown 删掉临时 stateDir。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { startE2eHost } from "../scripts/ios-e2e-host.mjs"
import {
  SESSION_LOGIN,
  SESSION_PERFORMANCE,
  httpsAgentFor,
  openSse,
  proxyRequest,
} from "../scripts/ios-e2e-fixture.mjs"

function postControl(port, path, body, { timeoutMs = 5_000 } = {}) {
  return new Promise((resolve, reject) => {
    const payload = Buffer.from(JSON.stringify(body ?? {}))
    const req = http.request({
      host: "127.0.0.1",
      port,
      path,
      method: "POST",
      headers: {
        "content-type": "application/json",
        "content-length": String(payload.length),
      },
    }, (res) => {
      const chunks = []
      res.on("data", (chunk) => chunks.push(chunk))
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8")
        let json = null
        try { json = JSON.parse(text) } catch {}
        resolve({ status: res.statusCode, json, text })
      })
    })
    req.setTimeout(timeoutMs, () => req.destroy(new Error("control timeout")))
    req.on("error", reject)
    req.end(payload)
  })
}

test("e2e host：二维码、配对、无订阅审批快速失败、shutdown 删除 stateDir", async () => {
  const scratch = mkdtempSync(join(tmpdir(), "dsh-ios-e2e-test-"))
  const qrPath = join(scratch, "qr.json")
  const logPath = join(scratch, "host.log")
  const host = await startE2eHost({ qrPath, logPath })
  try {
    assert.equal(existsSync(qrPath), true)
    const mode = statSync(qrPath).mode & 0o777
    assert.equal(mode, 0o600, `qr mode ${mode.toString(8)}`)
    const qr = JSON.parse(readFileSync(qrPath, "utf8"))
    assert.match(qr.certFingerprint, /^[0-9a-f]{64}$/)
    assert.match(qr.pairingCode, /^[0-9]{6}$/)
    assert.equal(qr.v, 1)
    assert.equal(qr.type, "dsh-link")
    assert.equal(qr.requireConfirm, false)
    assert.ok(Array.isArray(qr.urls) && qr.urls.length >= 1)
    assert.match(qr.urls[0], new RegExp(`^https://127\.0\.0\.1:${host.port}$`))
    assert.equal(typeof qr.issuedAt, "number")
    assert.equal(typeof qr.expiresAt, "number")

    const agent = httpsAgentFor(host.stateDir)
    const paired = await proxyRequest({
      agent,
      proxyPort: host.port,
      path: "/dsh-link/pair",
      method: "POST",
      body: {
        code: qr.pairingCode,
        deviceName: "e2e-iphone",
        requestId: "ios-e2e-host-test-1",
      },
    })
    assert.equal(paired.status, 200, paired.text)
    assert.equal(typeof paired.json?.token, "string")
    assert.ok(paired.json.token.length > 8)
    assert.equal(paired.json.requireConfirm ?? qr.requireConfirm, false)
    const log = readFileSync(logPath, "utf8")
    assert.equal(log.includes(paired.json.token), false, "日志不应包含设备 token")

    const started = Date.now()
    const approval = await postControl(host.controlPort, "/control/approval", {
      sessionId: SESSION_LOGIN,
    }, { timeoutMs: 8_000 })
    const elapsed = Date.now() - started
    assert.ok(elapsed < 3_000, `无订阅审批耗时 ${elapsed}ms，不应挂起`);
    assert.equal(approval.status, 409, approval.text)
    assert.equal(approval.json?.ok, false)
    assert.equal(approval.json?.code, "no-subscriber")

    const seen = await postControl(host.controlPort, "/control/prompt-seen", {})
    assert.equal(seen.status, 200, seen.text)
    assert.deepEqual(seen.json.prompts, [])
  } finally {
    const stateDir = host.stateDir
    const rootDir = host.rootDir
    await host.shutdown()
    assert.equal(existsSync(stateDir), false, "shutdown 后 stateDir 应删除")
    assert.equal(existsSync(rootDir), false, "shutdown 后临时根目录应删除")
    rmSync(scratch, { recursive: true, force: true })
  }
});

test("e2e host：重启后同一端口仍可配对，订阅中的会话能收到注入文本", async () => {
  const scratch = mkdtempSync(join(tmpdir(), "dsh-ios-e2e-stream-"))
  const qrPath = join(scratch, "qr.json")
  const host = await startE2eHost({ qrPath, logPath: join(scratch, "host.log") })
  let stream = null
  try {
    const before = JSON.parse(readFileSync(qrPath, "utf8"))
    const restarted = await postControl(host.controlPort, "/control/restart", {}, { timeoutMs: 15_000 })
    assert.equal(restarted.status, 200, restarted.text)
    assert.equal(restarted.json?.ok, true)
    const after = JSON.parse(readFileSync(qrPath, "utf8"))
    assert.equal(after.urls[0], before.urls[0])
    assert.match(after.certFingerprint, /^[0-9a-f]{64}$/)

    const agent = httpsAgentFor(host.stateDir)
    const paired = await proxyRequest({
      agent,
      proxyPort: host.port,
      path: "/dsh-link/pair",
      method: "POST",
      body: { code: after.pairingCode, deviceName: "e2e-stream", requestId: "ios-e2e-host-restart-1" },
    })
    assert.equal(paired.status, 200, paired.text)
    const live = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("SSE 未在时限内收到注入文本")), 4_000)
      stream = openSse({
        agent,
        proxyPort: host.port,
        token: paired.json.token,
        path: `/dsh-link/mobile/sessions/${SESSION_LOGIN}/stream`,
        quietMs: 60_000,
        timeoutMs: 8_000,
        onFrame(frame) {
          if (JSON.stringify(frame.data ?? "").includes("e2e-live-hello")) {
            clearTimeout(timer)
            resolve(frame)
          }
        },
      })
    })
    await new Promise((resolve) => setTimeout(resolve, 250))
    const injected = await postControl(host.controlPort, "/control/stream", {
      sessionId: SESSION_LOGIN,
      text: "e2e-live-hello",
    })
    assert.equal(injected.status, 200, injected.text)
    const got = await live
    assert.match(JSON.stringify(got.data), /e2e-live-hello/)
  } finally {
    try { stream?.close() } catch {}
    await host.shutdown()
    rmSync(scratch, { recursive: true, force: true })
  }
})

test("e2e host：性能会话默认关闭，显式打开后可追加第 3001 条", async () => {
  const scratch = mkdtempSync(join(tmpdir(), "dsh-ios-performance-host-"))
  const qrPath = join(scratch, "qr.json")
  const disabled = await startE2eHost({ qrPath, logPath: join(scratch, "disabled.log") })
  try {
    const missing = await postControl(disabled.controlPort, "/control/stream", {
      sessionId: SESSION_PERFORMANCE,
      text: "追加 3001",
    })
    assert.equal(missing.status, 404, missing.text)
  } finally {
    await disabled.shutdown()
  }

  const enabled = await startE2eHost({
    qrPath,
    logPath: join(scratch, "enabled.log"),
    includePerformanceSession: true,
  })
  let stream = null
  let liveTimer = null
  try {
    const qr = JSON.parse(readFileSync(qrPath, "utf8"))
    const agent = httpsAgentFor(enabled.stateDir)
    const paired = await proxyRequest({
      agent,
      proxyPort: enabled.port,
      path: "/dsh-link/pair",
      method: "POST",
      body: { code: qr.pairingCode, deviceName: "performance", requestId: "ios-performance-host-1" },
    })
    assert.equal(paired.status, 200, paired.text)
    const listed = await proxyRequest({
      agent,
      proxyPort: enabled.port,
      token: paired.json.token,
      path: "/dsh-link/mobile/sessions",
    })
    assert.equal(listed.status, 200, listed.text)
    const row = listed.json?.sessions?.find((item) => item.sessionId === SESSION_PERFORMANCE)
    assert.equal(row?.title, "性能会话")
    const history = await proxyRequest({
      agent,
      proxyPort: enabled.port,
      token: paired.json.token,
      path: '/dsh-link/mobile/sessions/' + SESSION_PERFORMANCE + '/history?maxMessages=50',
    })
    assert.equal(history.status, 200, history.text)
    assert.equal(history.json?.messages?.at(-1)?.text, "固定消息 3000")
    assert.equal(history.json?.hasMore, true)
    const cursor = history.json?.maxSeq
    assert.ok(cursor > 2950)
    let resolveLive
    let rejectLive
    const live = new Promise((resolve, reject) => {
      resolveLive = resolve
      rejectLive = reject
    })
    liveTimer = setTimeout(() => rejectLive(new Error("性能会话未收到追加文本")), 8_000)
    stream = openSse({
      agent,
      proxyPort: enabled.port,
      token: paired.json.token,
      path: '/dsh-link/mobile/sessions/' + SESSION_PERFORMANCE + '/stream?afterSeq=' + cursor,
      quietMs: 60_000,
      timeoutMs: 12_000,
      onFrame(frame) {
        if (JSON.stringify(frame.data ?? "").includes("追加 3001")) {
          clearTimeout(liveTimer)
          resolveLive(frame)
        }
      },
    })
    const injected = await postControl(enabled.controlPort, "/control/stream", {
      sessionId: SESSION_PERFORMANCE,
      text: "追加 3001",
    })
    assert.equal(injected.status, 200, injected.text)
    assert.ok(injected.json?.seq > cursor)
    const got = await live
    assert.match(JSON.stringify(got.data), /追加 3001/)
  } finally {
    clearTimeout(liveTimer)
    try { stream?.close() } catch {}
    await enabled.shutdown()
    rmSync(scratch, { recursive: true, force: true })
  }
})
