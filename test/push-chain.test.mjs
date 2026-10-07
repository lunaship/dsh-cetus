import assert from "node:assert/strict"
import test from "node:test"
import { createDecipheriv, generateKeyPairSync } from "node:crypto"
import { spawn } from "node:child_process"
import { createServer } from "node:http"
import { createServer as createNetServer } from "node:net"
import { once } from "node:events"
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createPushSink } from "../src/push-sink.js"

const vector = JSON.parse(readFileSync(new URL("../testdata/push/content/dlpush-v1-seal-open.json", import.meta.url), "utf8")).cases[0]
const token = JSON.parse(readFileSync(new URL("../testdata/push/hpke/dlpush-v1-seal-open.json", import.meta.url), "utf8")).cases[0]
const root = mkdtempSync(join(tmpdir(), "dsh-push-chain-"))

test("plugin reaches the local gateway and fake APNs", async () => {
  const fake = createServer((req, res) => {
    const chunks = []
    req.on("data", (chunk) => chunks.push(chunk))
    req.on("end", () => {
      fake.captured = {
        path: req.url,
        type: req.headers["apns-push-type"],
        priority: req.headers["apns-priority"],
        collapse: req.headers["apns-collapse-id"],
        body: JSON.parse(Buffer.concat(chunks).toString("utf8")),
      }
      res.writeHead(200)
      res.end("{}")
    })
  })
  fake.listen(0, "127.0.0.1")
  await once(fake, "listening")

  const reserved = createNetServer();
  reserved.listen(0, "127.0.0.1");
  await once(reserved, "listening");
  const gatewayPort = reserved.address().port;
  await new Promise((resolve) => reserved.close(resolve));
  const hpkePath = join(root, "hpke.key")
  const p8Path = join(root, "test-only.p8")
  writeFileSync(hpkePath, Buffer.from(token.skRm, "hex").toString("base64url"))
  const generated = generateKeyPairSync("ec", { namedCurve: "prime256v1" })
  writeFileSync(p8Path, generated.privateKey.export({ type: "pkcs8", format: "pem" }))
  const gateway = spawn("go", ["run", "./cmd/dlpush"], {
    cwd: new URL("../push/", import.meta.url),
    env: {
      ...process.env,
      DLPUSH_LISTEN: "127.0.0.1:" + gatewayPort,
      DLPUSH_HPKE_KEYS: token.kid + "=" + hpkePath,
      APNS_KEY_P8_PATH: p8Path,
      APNS_KEY_ID: "TESTKEY",
      APNS_TEAM_ID: "TESTTEAM",
      APNS_BUNDLE_ID: "dev.deeplinks.ios",
      DLPUSH_FAKE_APNS_URL: "http://127.0.0.1:" + fake.address().port,
    },
    stdio: ["ignore", "pipe", "pipe"],
    detached: true,
  })
  let output = ""
  gateway.stdout.on("data", (chunk) => { output += chunk })
  gateway.stderr.on("data", (chunk) => { output += chunk })
  const address = "127.0.0.1:" + gatewayPort
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(output || "gateway timeout")), 20000)
    const watch = async () => {
      if (!output.includes("listening on " + address)) return
      try {
        const health = await fetch("http://" + address + "/healthz")
        if (health.ok) {
          clearTimeout(timer)
          resolve()
        }
      } catch {}
    }
    gateway.stdout.on("data", watch)
    gateway.stderr.on("data", watch)
    gateway.once("exit", () => reject(new Error(output || "gateway exited")))
  })

  try {
    const state = { devices: [{ deviceId: vector.deviceId, push: {
      gateway: "https://" + address,
      kid: token.kid,
      sealed: token.sealed,
      k: vector.key,
      prefs: { approval: true, question: false, completed: true, failed: true },
    } }] }
    const sink = createPushSink({
      state,
      stateFile: "memory",
      saveState() {},
      isDeviceAuthorized: () => true,
      hasForegroundSse: () => false,
      now: () => 1_728_000_000_000,
      random: () => Buffer.from(vector.nonce, "hex"),
      transport: async (url, payload) => {
        const response = await fetch(url.replace("https://", "http://"), {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(payload),
        })
        return { status: response.status }
      },
    })
    const sent = await sink.notify({ sessionId: "sess-1", state: "awaitingApproval", title: "Need approval" })
    assert.equal(sent[0].reason, "accepted")
    assert.equal(fake.captured.path.endsWith(JSON.parse(token.plaintext_utf8).apnsToken), true)
    assert.equal(fake.captured.type, "alert")
    assert.equal(fake.captured.priority, "10")
    const raw = Buffer.from(fake.captured.body.e, "base64")
    const decipher = createDecipheriv("aes-256-gcm", Buffer.from(vector.key, "hex"), raw.subarray(0, 12))
    decipher.setAAD(Buffer.from("dlpush/1 content|" + vector.deviceId))
    decipher.setAuthTag(raw.subarray(raw.length - 16))
    const opened = JSON.parse(Buffer.concat([decipher.update(raw.subarray(12, raw.length - 16)), decipher.final()]).toString("utf8"))
    assert.equal(opened.type, "approval")
    assert.equal(output.includes(vector.key), false)
    assert.equal(output.includes(fake.captured.body.e), false)
  } finally {
    process.kill(-gateway.pid)
    await once(gateway, "exit").catch(() => {})
    fake.close()
    rmSync(root, { recursive: true, force: true })
  }
})
