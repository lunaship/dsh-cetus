/**
 * Tailscale 地址分类：100.64.0.0/10 与 fd7a:115c:a1e0::/48 是 tailnet。
 * 推荐位仍给私网地址；tailnet 必须留在二维码 urls 里，infos 不得进码。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { classifyUrl, lanInfosForAddresses, qrPayload } from "../src/index.js"

test("100.64/10 与 Tailscale IPv6 归为 tailnet", () => {
  assert.equal(classifyUrl("https://100.64.0.1:18640"), "tailnet")
  assert.equal(classifyUrl("https://100.127.255.1:18640"), "tailnet")
  assert.equal(classifyUrl("https://100.63.0.1:18640"), "other")
  assert.equal(classifyUrl("https://100.128.0.1:18640"), "other")
  assert.equal(classifyUrl("https://[fd7a:115c:a1e0::8]:18640"), "tailnet")
  assert.equal(classifyUrl("https://192.168.1.10:18640"), "private")
  assert.equal(classifyUrl("https://10.1.2.3:18640"), "private")
  assert.equal(classifyUrl("https://127.0.0.1:18640"), "loopback")
})

test("私网仍是推荐地址，tailnet 作为第二候选进入二维码", () => {
  const lan = lanInfosForAddresses(["100.64.0.10", "192.168.1.10", "198.18.0.1", "100.64.0.10"], 18640)
  assert.deepEqual(lan.urls, ["https://100.64.0.10:18640", "https://192.168.1.10:18640"])
  assert.equal(lan.infos[0].category, "tailnet")
  assert.equal(lan.infos[0].isRecommended, false)
  assert.equal(lan.infos[1].category, "private")
  assert.equal(lan.infos[1].isRecommended, true)
  const payload = qrPayload({
    v: 1,
    type: "dsh-link",
    name: "example",
    pairingCode: "123456",
    urls: lan.urls,
    infos: lan.infos,
    certFingerprint: "aa".repeat(32),
  })
  assert.deepEqual(payload.urls, lan.urls)
  assert.equal(payload.infos, undefined)
})

test("只有 tailnet 时它成为推荐，但分类不变", () => {
  const lan = lanInfosForAddresses(["100.64.1.8"], 18640)
  assert.equal(lan.infos[0].category, "tailnet")
  assert.equal(lan.infos[0].isRecommended, true)
  assert.deepEqual(lan.urls, ["https://100.64.1.8:18640"])
})
