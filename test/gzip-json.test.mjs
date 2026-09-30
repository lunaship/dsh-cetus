/**
 * S3：插件对大 JSON 响应开 gzip。
 *
 * - 大响应（> 1KB）且请求方接受 gzip → 压缩，带 content-encoding: gzip 与 vary: accept-encoding，
 *   content-length 改成压缩后的长度，解压后内容与原来一致；
 * - 小响应（≤ 1KB，如含 token 的配对响应）不压缩；
 * - 请求没带 accept-encoding 时不压缩。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { gunzipSync } from "node:zlib"
import { json } from "../src/index.js"

function mockRes(acceptEncoding) {
  const res = {
    req: { headers: acceptEncoding === undefined ? {} : { "accept-encoding": acceptEncoding } },
    headers: {},
    writeHead(code, headers) {
      this.code = code
      Object.assign(this.headers, headers)
    },
    end(body) {
      this.body = body
    },
  }
  return res
}

function bigObject() {
  return {
    version: 1,
    sessions: Array.from({ length: 300 }, (_, i) => ({
      sessionId: `session-${i}`,
      title: `Conversation number ${i} with a reasonably long title`,
      updatedAt: 1_700_000_000_000 + i,
      running: false,
    })),
  }
}

test("大响应接受 gzip 时压缩且解压后一致", () => {
  const obj = bigObject()
  const raw = JSON.stringify(obj)
  assert.ok(Buffer.byteLength(raw) > 1024, "用例本身要大于 1KB")

  const res = mockRes("gzip, deflate, br")
  json(res, 200, obj)

  assert.equal(res.headers["content-encoding"], "gzip")
  assert.equal(res.headers.vary, "accept-encoding")
  assert.equal(res.headers["content-length"], String(res.body.length))
  assert.ok(res.body.length < Buffer.byteLength(raw), "压缩后应更小")
  assert.deepEqual(JSON.parse(gunzipSync(res.body).toString("utf8")), obj)
})

test("小响应不压缩", () => {
  const obj = { ok: true, token: "short" }
  const res = mockRes("gzip")
  json(res, 200, obj)
  assert.equal(res.headers["content-encoding"], undefined)
  assert.equal(res.headers.vary, undefined)
  assert.equal(res.headers["content-length"], String(Buffer.byteLength(JSON.stringify(obj))))
})

test("请求没有 accept-encoding 时不压缩", () => {
  const obj = bigObject()
  const res = mockRes(undefined)
  json(res, 200, obj)
  assert.equal(res.headers["content-encoding"], undefined)
  assert.deepEqual(JSON.parse(res.body.toString("utf8")), obj)
})

test("额外响应头仍生效", () => {
  const res = mockRes("gzip")
  json(res, 503, bigObject(), { "retry-after": "1" })
  assert.equal(res.headers["retry-after"], "1")
  assert.equal(res.headers["content-encoding"], "gzip")
})
