/**
 * 远程首配种子表与重放缓存（RFC §5.2、§5.5 第 4、6 步）。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { BootstrapTable, NonceCache } from "../src/remote/bootstrap.js"
import { bootstrapKeys } from "../src/remote/crypto.js"
import { REJECT } from "../src/remote/wire.js"

const route = Buffer.from(Array.from({ length: 16 }, (_, i) => i + 1))

test("签发：返回 16 字节种子与由它派生的 bootstrapId，查询拿到对应的 bootstrapKey", () => {
  const table = new BootstrapTable({ now: () => 1_000 })
  const { seed, bootstrapId } = table.issue(route, 301_000)
  assert.equal(seed.length, 16)
  const derived = bootstrapKeys(seed, route)
  assert.ok(bootstrapId.equals(derived.bootstrapId))
  const entry = table.lookup(bootstrapId)
  assert.ok(entry.key.equals(derived.bootstrapKey))
  assert.equal(entry.expiresAtMs, 301_000)
  assert.equal(entry.consumed, false)
})

test("保留上一张未过期的码；再签发一次，最早那张被丢弃", () => {
  let now = 0
  const table = new BootstrapTable({ now: () => now })
  const first = table.issue(route, 300_000)
  now = 60_000
  const second = table.issue(route, 360_000)
  assert.ok(table.lookup(first.bootstrapId), "上一张未过期应保留")
  assert.ok(table.lookup(second.bootstrapId))
  now = 120_000
  const third = table.issue(route, 420_000)
  assert.equal(table.lookup(first.bootstrapId), null, "只保留当前码 + 上一张")
  assert.ok(table.lookup(second.bootstrapId))
  assert.ok(table.lookup(third.bootstrapId))
})

test("上一张在签发新码时已过期就不保留", () => {
  let now = 0
  const table = new BootstrapTable({ now: () => now })
  const first = table.issue(route, 10_000)
  now = 20_000
  table.issue(route, 320_000)
  assert.equal(table.lookup(first.bootstrapId), null)
})

test("过期的码在保留期内仍可查到，由调用方判为 BOOTSTRAP_EXPIRED", () => {
  let now = 0
  const table = new BootstrapTable({ now: () => now })
  const { bootstrapId } = table.issue(route, 10_000)
  now = 20_000
  const entry = table.lookup(bootstrapId)
  assert.ok(entry)
  assert.ok(entry.expiresAtMs <= now)
})

test("消费：标记后 lookup 返回 consumed=true；未知或错误长度的 id 返回 null/false", () => {
  const table = new BootstrapTable({ now: () => 0 })
  const { bootstrapId } = table.issue(route, 300_000)
  assert.equal(table.consume(bootstrapId), true)
  assert.equal(table.lookup(bootstrapId).consumed, true)
  assert.equal(table.consume(Buffer.alloc(16, 9)), false)
  assert.equal(table.lookup(Buffer.alloc(16, 9)), null)
  assert.equal(table.lookup(Buffer.alloc(15)), null)
})

test("重放缓存：同 (kind,key,nonce) 在 ts+60 秒内返回 REPLAY；kind 不同互不影响；过期后可再次通过", () => {
  const cache = new NonceCache()
  const key = Buffer.alloc(16, 1)
  const nonce = Buffer.alloc(16, 2)
  assert.equal(cache.check("device", key, nonce, 1_000, 1_000), null)
  assert.equal(cache.check("device", key, nonce, 1_000, 1_030), REJECT.REPLAY)
  assert.equal(cache.check("bootstrap", key, nonce, 1_000, 1_030), null)
  assert.equal(cache.check("device", key, nonce, 1_000, 1_061), null, "超过 ts+60 已由 CLOCK_SKEW 兜住，条目可清理")
})

test("重放缓存满了且没有可清理的过期条目：SERVER_BUSY，不淘汰未过期条目", () => {
  const cache = new NonceCache({ max: 2 })
  const key = Buffer.alloc(16, 1)
  assert.equal(cache.check("device", key, Buffer.alloc(16, 1), 1_000, 1_000), null)
  assert.equal(cache.check("device", key, Buffer.alloc(16, 2), 1_000, 1_000), null)
  assert.equal(cache.check("device", key, Buffer.alloc(16, 3), 1_000, 1_000), REJECT.SERVER_BUSY)
  assert.equal(cache.check("device", key, Buffer.alloc(16, 1), 1_000, 1_000), REJECT.REPLAY, "未过期条目仍在")
  // 过期后清理出空间
  assert.equal(cache.check("device", key, Buffer.alloc(16, 3), 1_100, 1_100), null)
  assert.equal(cache.size, 1)
})
