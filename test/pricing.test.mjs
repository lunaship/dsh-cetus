import assert from "node:assert/strict"
import test from "node:test"
import { buildEstimatedCost, isPeakHour, lookupBuiltinPrice, priceFromHostModel } from "../src/pricing.js"

const usage = {
  cacheReadTokens: 2_000_000,
  uncachedInputTokens: 1_000_000,
  outputTokens: 500_000,
}

test("缓存命中和未命中分开计价", () => {
  const off = new Date("2026-10-03T12:00:00Z") // Saturday, off-peak
  assert.equal(isPeakHour(off), false)
  const cost = buildEstimatedCost({ modelId: "deepseek-flash", usage, at: off })
  // 2M * 0.003 + 1M * 0.15 + 0.5M * 0.6 = 0.006 + 0.15 + 0.3
  assert.equal(cost.amount, 0.456)
  assert.equal(cost.currency, "USD")
  assert.equal(cost.source, "builtin")
  assert.equal(cost.priceDate, "2026-10-02")
  const hitOnly = buildEstimatedCost({
    modelId: "deepseek-flash",
    usage: { cacheReadTokens: 1_000_000, uncachedInputTokens: 0, outputTokens: 0 },
    at: off,
  })
  const missOnly = buildEstimatedCost({
    modelId: "deepseek-flash",
    usage: { cacheReadTokens: 0, uncachedInputTokens: 1_000_000, outputTokens: 0 },
    at: off,
  })
  assert.equal(hitOnly.amount, 0.003)
  assert.equal(missOnly.amount, 0.15)
  assert.notEqual(hitOnly.amount, missOnly.amount)
})

test("峰时用更高的官方单价", () => {
  const peak = new Date("2026-10-05T02:30:00Z") // Monday
  assert.equal(isPeakHour(peak), true)
  assert.equal(isPeakHour(new Date("2026-10-05T05:00:00Z")), false)
  assert.equal(isPeakHour(new Date("2026-10-04T02:30:00Z")), false) // Sunday
  const cost = buildEstimatedCost({ modelId: "deepseek-v4-pro", usage: { cacheReadTokens: 1_000_000 }, at: peak })
  assert.equal(cost.amount, 0.044)
  assert.equal(lookupBuiltinPrice("deepseek-v4-flash", peak).cacheMissPerMillion, 0.3)
})

test("未知模型返回 null", () => {
  assert.equal(buildEstimatedCost({ modelId: "not-a-model", usage, at: new Date("2026-10-03T12:00:00Z") }), null)
  assert.equal(lookupBuiltinPrice("deepseek-chat"), null)
})

test("Host 价格优先于内置表", () => {
  const hostModel = {
    id: "deepseek-flash",
    pricing: {
      cacheHitPerMillion: 1,
      cacheMissPerMillion: 2,
      outputPerMillion: 4,
      currency: "CNY",
      priceDate: "2026-09-01",
    },
  }
  assert.equal(priceFromHostModel({ id: "x" }), null)
  const cost = buildEstimatedCost({
    modelId: "deepseek-flash",
    usage: { uncachedInputTokens: 1_000_000, cacheReadTokens: 0, outputTokens: 0 },
    hostModel,
    at: new Date("2026-10-05T02:30:00Z"),
  })
  assert.equal(cost.amount, 2)
  assert.equal(cost.currency, "CNY")
  assert.equal(cost.source, "host")
  assert.equal(cost.priceDate, "2026-09-01")
})
