/**
 * 最小假 DLP/1 Relay（测试共用）：只实现 Agent 侧需要的 hello / host_register / registered /
 * open 推送 / host_accept 验签；客户端一侧由测试自己通过 sendOpen + 数据连接模拟。
 * 放在 test/helpers/ 下：`node --test test/*.mjs` 不会把它当测试文件跑。
 */
import http from "node:http"
import https from "node:https"
import { EventEmitter, once } from "node:events"
import { createPublicKey, randomBytes, verify } from "node:crypto"
import { WebSocketServer } from "ws"
import { acceptTranscript, b64u, registerTranscript, routeId as deriveRouteId } from "../../src/remote/crypto.js"
import { CLOSE } from "../../src/remote/wire.js"

const SPKI_ED25519_PREFIX = Buffer.from("302a300506032b6570032100", "hex")

export function edPublicKey(raw) {
  return createPublicKey({ key: Buffer.concat([SPKI_ED25519_PREFIX, raw]), format: "der", type: "spki" })
}

export /** 最小假 Relay：hello → host_register 验签 → registered；open 由测试推送；host_accept 验签后交给测试。 */
async function startFakeRelay({ tlsOptions } = {}) {
  const server = tlsOptions ? https.createServer(tlsOptions) : http.createServer()
  const wss = new WebSocketServer({ server, perMessageDeflate: false })
  const relay = new EventEmitter()
  relay.ctrl = null
  relay.registrations = 0
  relay.ctrlMessages = []
  wss.on("connection", (ws) => {
    const ch = randomBytes(32)
    ws.on("error", () => {})
    ws.send(JSON.stringify({ t: "hello", v: 1, ch: b64u(ch), now: Math.floor(Date.now() / 1000) }))
    ws.once("message", (data) => {
      const msg = JSON.parse(String(data))
      if (msg.t === "host_register") {
        const pub = Buffer.from(msg.pub, "base64url")
        const ok = verify(null, registerTranscript(ch, pub), edPublicKey(pub), Buffer.from(msg.sig, "base64url"))
        if (!ok) return ws.close(CLOSE.AUTH_FAILED)
        relay.ctrl = ws
        relay.registrations++
        ws.on("message", (raw) => {
          const m = JSON.parse(String(raw))
          relay.ctrlMessages.push(m)
          relay.emit("ctrl", m)
        })
        ws.send(JSON.stringify({ t: "registered", route: b64u(deriveRouteId(pub)), ping: 20 }))
        relay.emit("registered", ws)
      } else if (msg.t === "host_accept") {
        const pub = Buffer.from(msg.pub, "base64url")
        const sid = Buffer.from(msg.sid, "base64url")
        const sigOk = verify(null, acceptTranscript(ch, pub, sid), edPublicKey(pub), Buffer.from(msg.sig, "base64url"))
        relay.emit("accept", { ws, msg, sigOk })
      }
    })
  })
  server.listen(0, "127.0.0.1")
  await once(server, "listening")
  relay.url = `${tlsOptions ? "wss" : "ws"}://127.0.0.1:${server.address().port}/ws`
  relay.sendOpen = (req) => {
    const sid = b64u(randomBytes(16))
    relay.ctrl.send(JSON.stringify({ t: "open", sid, req }))
    return sid
  }
  relay.nextCtrl = (type) => new Promise((resolve) => {
    const onCtrl = (m) => {
      if (m.t !== type) return
      relay.off("ctrl", onCtrl)
      resolve(m)
    }
    relay.on("ctrl", onCtrl)
  })
  relay.nextAccept = () => once(relay, "accept").then(([value]) => value)
  relay.close = async () => {
    for (const client of wss.clients) client.terminate()
    wss.close()
    server.closeAllConnections?.()
    await new Promise((resolve) => server.close(resolve))
  }
  return relay
}
