/**
 * 会话预估花费。优先用 Host 模型上的价格；没有就用这里的官方表。
 * 官方表来自 https://api-docs.deepseek.com/quick_start/pricing （2026-10-02）。
 * 峰时是 UTC 工作日 01:00–04:00 与 06:00–10:00，其余为谷时。
 * 中国法定节假日官方按谷时计，这里没有节假日日历，工作日峰时窗口仍按峰时估算。
 *
 * Host 只给整段会话的累计 token，没有逐轮时间和逐轮模型，所以内置表算不出每轮的真实峰谷价。
 * 内置表计价时同时给出全谷时 / 全峰时的区间（amountMin / amountMax），App 显示区间；
 * `amount` 仍按当前时刻单价，兼容只认 `amount` 的旧 App。模型按当前选中的模型计，中途换过模型时也是估算。
 */

export const PRICE_PAGE = "https://api-docs.deepseek.com/quick_start/pricing"
export const PRICE_DATE = "2026-10-02"

const FLASH = {
  currency: "USD",
  priceDate: PRICE_DATE,
  offPeak: { cacheHitPerMillion: 0.003, cacheMissPerMillion: 0.15, outputPerMillion: 0.6 },
  peak: { cacheHitPerMillion: 0.006, cacheMissPerMillion: 0.3, outputPerMillion: 1.2 },
}

const PRO = {
  currency: "USD",
  priceDate: PRICE_DATE,
  offPeak: { cacheHitPerMillion: 0.022, cacheMissPerMillion: 0.66, outputPerMillion: 1.98 },
  peak: { cacheHitPerMillion: 0.044, cacheMissPerMillion: 1.32, outputPerMillion: 3.96 },
}

/** 官方页写明旧名仍按 Flash 计价。 */
const BUILTIN = new Map([
  ["deepseek-flash", FLASH],
  ["deepseek-v4-flash", FLASH],
  ["deepseek-v4-flash-vision-exp", FLASH],
  ["deepseek-v4-pro", PRO],
])

export function isPeakHour(at) {
  const day = at.getUTCDay()
  if (day === 0 || day === 6) return false
  const hour = at.getUTCHours()
  return (hour >= 1 && hour < 4) || (hour >= 6 && hour < 10)
}

function finiteNumber(value) {
  const n = typeof value === "number" ? value : Number(value)
  return Number.isFinite(n) && n >= 0 ? n : null
}

/** Host 模型对象上的价格。认 `pricing` / `price` 里的每百万 token 单价。没有完整三档就返回 null。 */
export function priceFromHostModel(model) {
  const pricing = model?.pricing ?? model?.price
  if (!pricing || typeof pricing !== "object") return null
  const cacheHitPerMillion = finiteNumber(pricing.cacheHitPerMillion ?? pricing.cacheHit)
  const cacheMissPerMillion = finiteNumber(pricing.cacheMissPerMillion ?? pricing.input)
  const outputPerMillion = finiteNumber(pricing.outputPerMillion ?? pricing.output)
  const currency = typeof pricing.currency === "string" ? pricing.currency.trim() : ""
  if (cacheHitPerMillion == null || cacheMissPerMillion == null || outputPerMillion == null || !currency) return null
  const priceDate = typeof pricing.priceDate === "string" && pricing.priceDate.trim() ? pricing.priceDate.trim() : null
  return { cacheHitPerMillion, cacheMissPerMillion, outputPerMillion, currency, priceDate, source: "host" }
}

function sumCost(usage, price) {
  return (
    nonNegative(usage.cacheReadTokens) * price.cacheHitPerMillion
    + nonNegative(usage.uncachedInputTokens) * price.cacheMissPerMillion
    + nonNegative(usage.outputTokens) * price.outputPerMillion
  ) / 1_000_000
}

export function lookupBuiltinPrice(modelId, at = new Date()) {
  const row = BUILTIN.get(String(modelId ?? ""))
  if (!row) return null
  const rates = isPeakHour(at) ? row.peak : row.offPeak
  return { ...rates, currency: row.currency, priceDate: row.priceDate, source: "builtin" }
}

export function modelIdFromProjections(values) {
  const next = values?.modelSelection?.next ?? values?.modelSelection?.active
  if (typeof next === "string" && next.trim()) return next.trim()
  if (next && typeof next.model === "string" && next.model.trim()) return next.model.trim()
  const usageModel = values?.tokenUsage?.model
  if (typeof usageModel === "string" && usageModel.trim()) return usageModel.trim()
  return null
}

function nonNegative(value) {
  const n = finiteNumber(value)
  return n == null ? 0 : n
}

/**
 * 缓存命中和未命中分开计价。未知模型且 Host 没有价格时返回 null，调用方只显示 token。
 */
export function buildEstimatedCost({ modelId, usage, hostModel, at = new Date() }) {
  if (!usage || typeof usage !== "object") return null
  const host = priceFromHostModel(hostModel)
  const price = host ?? lookupBuiltinPrice(modelId, at)
  if (!price) return null
  const amount = sumCost(usage, price)
  if (!Number.isFinite(amount)) return null
  const out = {
    amount: Number(amount.toFixed(6)),
    currency: price.currency,
    priceDate: price.priceDate,
    source: price.source,
  }
  if (!host) {
    const row = BUILTIN.get(String(modelId ?? ""))
    const min = sumCost(usage, row.offPeak)
    const max = sumCost(usage, row.peak)
    if (Number.isFinite(min) && Number.isFinite(max) && max > min) {
      out.amountMin = Number(min.toFixed(6))
      out.amountMax = Number(max.toFixed(6))
    }
  }
  return out
}
