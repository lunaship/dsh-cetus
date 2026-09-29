import { createPrivateKey, createPublicKey, createHash, createHmac, sign, timingSafeEqual } from "node:crypto"

const ROUTE_PREFIX = Buffer.from("DLP1 route\0", "ascii")
const REGISTER_PREFIX = Buffer.from("DLP1 host_register\0", "ascii")
const ACCEPT_PREFIX = Buffer.from("DLP1 host_accept\0", "ascii")
const CLIENT_PREFIX = Buffer.from("DLP1 client_open\0", "ascii")
const DEVICE_KEY_PREFIX = Buffer.from("DLP1 device key\0", "ascii")
const ED25519_PKCS8_PREFIX = Buffer.from("302e020100300506032b657004220420", "hex")

export function b64u(bytes) {
  return Buffer.from(bytes).toString("base64url")
}

export function unb64u(value, expectedLength) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]*$/.test(value)) throw new TypeError("invalid base64url")
  const bytes = Buffer.from(value, "base64url")
  if (b64u(bytes) !== value || (expectedLength !== undefined && bytes.length !== expectedLength)) throw new TypeError("invalid base64url length")
  return bytes
}

export function privateKeyFromSeed(seed) {
  if (!Buffer.isBuffer(seed) || seed.length !== 32) throw new TypeError("host key seed must be 32 bytes")
  return createPrivateKey({ key: Buffer.concat([ED25519_PKCS8_PREFIX, seed]), format: "der", type: "pkcs8" })
}

export function hostPublicKey(seed) {
  return createPublicKey(privateKeyFromSeed(seed)).export({ format: "der", type: "spki" }).subarray(-32)
}

export function routeId(hostPub) {
  if (!Buffer.isBuffer(hostPub) || hostPub.length !== 32) throw new TypeError("host public key must be 32 bytes")
  return createHash("sha256").update(ROUTE_PREFIX).update(hostPub).digest().subarray(0, 16)
}

export function registerTranscript(ch, hostPub, version = 1) {
  assertLength(ch, 32, "challenge")
  assertLength(hostPub, 32, "host public key")
  assertVersion(version)
  return Buffer.concat([REGISTER_PREFIX, Buffer.from([version]), ch, hostPub])
}

export function acceptTranscript(ch, hostPub, sid, version = 1) {
  assertLength(ch, 32, "challenge")
  assertLength(hostPub, 32, "host public key")
  assertLength(sid, 16, "sid")
  assertVersion(version)
  return Buffer.concat([ACCEPT_PREFIX, Buffer.from([version]), ch, hostPub, sid])
}

export function signHost(seed, transcript) {
  return sign(null, transcript, privateKeyFromSeed(seed))
}

export function clientTranscript({ route, kind, key, ts, nonce, version = 1 }) {
  assertLength(route, 16, "route")
  assertLength(key, 16, "key")
  assertLength(nonce, 16, "nonce")
  assertVersion(version)
  const kindByte = kind === "device" ? 1 : kind === "bootstrap" ? 2 : 0
  if (!kindByte) throw new TypeError("invalid client kind")
  if (!Number.isSafeInteger(ts) || ts < 0) throw new TypeError("invalid timestamp")
  const timestamp = Buffer.alloc(8)
  timestamp.writeBigUInt64BE(BigInt(ts))
  return Buffer.concat([CLIENT_PREFIX, Buffer.from([version]), route, Buffer.from([kindByte]), key, timestamp, nonce])
}

export function deviceRelayKey(keySeed, relayHandle) {
  assertLength(keySeed, 32, "key seed")
  assertLength(relayHandle, 16, "relay handle")
  return createHmac("sha256", keySeed).update(DEVICE_KEY_PREFIX).update(relayHandle).digest()
}

export function clientMac(key, transcript) {
  assertLength(key, 32, "client key")
  return createHmac("sha256", key).update(transcript).digest()
}

export function hkdf(ikm, salt, info, length) {
  if (!Buffer.isBuffer(ikm) || !Buffer.isBuffer(salt) || !Buffer.isBuffer(info) || !Number.isInteger(length) || length < 1 || length > 255 * 32) throw new TypeError("invalid HKDF input")
  const prk = createHmac("sha256", salt).update(ikm).digest()
  const blocks = []
  let previous = Buffer.alloc(0)
  for (let counter = 1; Buffer.concat(blocks).length < length; counter++) {
    previous = createHmac("sha256", prk).update(previous).update(info).update(Buffer.from([counter])).digest()
    blocks.push(previous)
  }
  return Buffer.concat(blocks).subarray(0, length)
}

export function bootstrapKeys(bootstrapSeed, route) {
  return {
    bootstrapId: hkdf(bootstrapSeed, route, Buffer.from("DLP1 bootstrap id", "ascii"), 16),
    bootstrapKey: hkdf(bootstrapSeed, route, Buffer.from("DLP1 bootstrap key", "ascii"), 32),
  }
}

export function safeEqual(a, b) {
  return Buffer.isBuffer(a) && Buffer.isBuffer(b) && a.length === b.length && timingSafeEqual(a, b)
}

function assertLength(value, length, label) {
  if (!Buffer.isBuffer(value) || value.length !== length) throw new TypeError(`${label} must be ${length} bytes`)
}

function assertVersion(version) {
  if (version !== 1) throw new TypeError("unsupported version")
}
