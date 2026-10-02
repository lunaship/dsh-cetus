/**
 * 二维码可扫性回归。
 *
 * 面板底托（src/panel.js 的 .dl-qr）是 168px 见方、内边距 9px、边框 0.5px → 内区
 * 149 CSS px；Retina 上折合 298 物理像素。手机相机要可靠解码需要每模块约 3 个物理像素，
 * 所以二维码模块数必须 ≤ 298/3 ≈ 99。统一码（局域网 + remote）约 81 模块，带外层指纹约 89。
 *
 * 2026-09-20 的事故：`qrPng` 直接把面板用的 pairInfo 整个塞进二维码，带上
 * `exposure`（网卡告警文案）和 `infos`（地址标签）后载荷 823/1053 字符、模块数
 * 113/125 → 内区不到 1 CSS px/模块，手机解成乱码，App 报「不是 dsh 连接二维码」。
 * 这两个字段没有任何客户端读（App 的 PairingQr.kt 只认 type/pairingCode/urls/
 * remote/certFingerprint/name），所以二维码只编码这一份精简载荷。
 *
 * 改大底托尺寸或往载荷里加字段时，这个测试会直接拦下来。
 */
import assert from "node:assert/strict"
import test from "node:test"
import QRCode from "qrcode"
import { qrPayload } from "../src/index.js"

const PLATE_PX = 168
const PLATE_PADDING_PX = 9
const PLATE_BORDER_PX = 0.5
const INNER_CSS_PX = PLATE_PX - 2 * PLATE_PADDING_PX - 2 * PLATE_BORDER_PX
const RETINA = 2
const MIN_DEVICE_PX_PER_MODULE = 3
const MAX_MODULES = Math.floor((INNER_CSS_PX * RETINA) / MIN_DEVICE_PX_PER_MODULE)

function modulesFor(payload) {
  // margin 2 是 qrPng 的配置，两侧各 2 模块也要占地方。
  return QRCode.create(JSON.stringify(payload), {}).modules.size + 4
}

/** 局域网码的现场形状：本机 192.168.x + Tailscale 100.x 两个地址（均为占位值）。 */
function lanInfo() {
  return {
    v: 1,
    type: "dsh-link",
    deviceId: "dsh-0000000000000000",
    name: "example.local",
    port: 18640,
    urls: ["https://192.168.1.10:18640", "https://100.64.0.10:18640"],
    infos: [
      { url: "https://192.168.1.10:18640", label: "192.168.1.10", category: "private", isRecommended: true },
      { url: "https://100.64.0.10:18640", label: "100.64.0.10", category: "tailnet", isRecommended: false },
    ],
    pairingCode: "123456",
    certFingerprint: "aa11bb22cc33dd44ee55ff6677889900aa11bb22cc33dd44ee55ff6677889900",
    requireConfirm: true,
    // pairInfo 每次渲染都写入：issuedAt=渲染时刻，expiresAt=当前配对码过期时刻。
    issuedAt: 1789891800000,
    expiresAt: 1789892400000,
    exposure: {
      listen: { address: "0.0.0.0", port: 18640 },
      networks: [{ label: "192.168.1.10", category: "private", url: "https://192.168.1.10:18640" }],
      level: "untrusted",
      warning: "检测到非私有网卡地址。当前监听 0.0.0.0:18640，请确认这些网段可信。",
    },
  }
}

/** 远程已就绪时的统一码：局域网字段 + remote（RFC §5.2，e/r/s 为占位值；p 是可选的外层指纹）。 */
function remoteInfo({ outerPin = false } = {}) {
  return {
    ...lanInfo(),
    remote: {
      e: "wss://relay.example.com/ws",
      r: "AAAAAAAAAAAAAAAAAAAAAA",
      s: "BBBBBBBBBBBBBBBBBBBBBB",
      ...(outerPin ? { p: "cc".repeat(32) } : {}),
    },
  }
}

test("二维码载荷丢掉面板专属字段", () => {
  const payload = qrPayload(lanInfo())
  assert.equal(payload.type, "dsh-link")
  assert.equal(payload.pairingCode, "123456")
  assert.deepEqual(payload.urls, lanInfo().urls)
  assert.equal(payload.certFingerprint, lanInfo().certFingerprint)
  for (const dropped of ["exposure", "infos", "deviceId", "port"]) {
    assert.equal(payload[dropped], undefined, `${dropped} 不该进二维码`)
  }
})

test("二维码载荷保留客户端解析所需的全部字段", () => {
  const lan = qrPayload(lanInfo())
  // PairingQr.kt：code 与 urls 至少要有其一；remote 原样带上，App 自行派生首配密钥。
  assert.ok(lan.pairingCode)
  assert.ok(lan.urls.length > 0)
  // 时效戳：App 扫码后判断码是否已过期/陈旧，避免拿旧截图的码撞 401。
  assert.equal(lan.issuedAt, lanInfo().issuedAt)
  assert.equal(lan.expiresAt, lanInfo().expiresAt)
  const unified = qrPayload(remoteInfo())
  assert.deepEqual(unified.remote, remoteInfo().remote)
  // 同一张码仍带全部局域网字段：在家走局域网首配，外面走远程首配（RFC §7.6）
  assert.deepEqual(unified.urls, lanInfo().urls)
  assert.equal(unified.certFingerprint, lanInfo().certFingerprint)
  assert.equal(unified.relay, undefined)
  // 无时效戳的 pairInfo（兼容路径）不得把 undefined 编进二维码。
  const legacy = { ...lanInfo() }
  delete legacy.issuedAt
  delete legacy.expiresAt
  assert.equal(qrPayload(legacy).issuedAt, undefined)
  assert.equal(qrPayload(legacy).expiresAt, undefined)
})

test("纯局域网码与统一码都塞得进面板底托（≥3 物理像素/模块）", () => {
  for (const [name, info] of [["局域网", lanInfo()], ["统一", remoteInfo()], ["统一（带外层指纹）", remoteInfo({ outerPin: true })]]) {
    const payload = qrPayload(info)
    const modules = modulesFor(payload)
    const devicePxPerModule = (INNER_CSS_PX * RETINA) / modules
    assert.ok(
      modules <= MAX_MODULES,
      `${name}二维码 ${modules} 模块超过上限 ${MAX_MODULES}（载荷 ${JSON.stringify(payload).length} 字符）`,
    )
    assert.ok(
      devicePxPerModule >= MIN_DEVICE_PX_PER_MODULE,
      `${name}二维码每模块只有 ${devicePxPerModule.toFixed(2)} 物理像素，手机扫不出来`,
    )
  }
})
