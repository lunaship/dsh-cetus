import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import {
  acceptTranscript, b64u, bootstrapKeys, clientMac, clientTranscript,
  deviceRelayKey, hostPublicKey, registerTranscript, routeId, signHost,
} from "../src/remote/crypto.js"

const vectors = JSON.parse(await readFile(new URL("../testdata/dlp1/vectors.json", import.meta.url), "utf8"))
const bytes = (hex) => Buffer.from(hex, "hex")

test("DLP/1 Ed25519 route, transcripts and signatures match shared vectors", () => {
  const i = vectors.inputs
  const o = vectors.outputs
  const pub = hostPublicKey(bytes(i.hostKeySeed))
  assert.equal(pub.toString("hex"), o.hostPub)
  assert.equal(routeId(pub).toString("hex"), o.routeId)
  const register = registerTranscript(bytes(i.ch), pub)
  assert.equal(register.toString("hex"), o.T_register)
  assert.equal(signHost(bytes(i.hostKeySeed), register).toString("hex"), o.sig_register)
  const accept = acceptTranscript(bytes(i.ch), pub, bytes(i.sid))
  assert.equal(accept.toString("hex"), o.T_accept)
  assert.equal(signHost(bytes(i.hostKeySeed), accept).toString("hex"), o.sig_accept)
})

test("DLP/1 device and bootstrap MACs match shared vectors", () => {
  const i = vectors.inputs
  const o = vectors.outputs
  const route = bytes(o.routeId)
  const relayHandle = bytes(i.relayHandle)
  const deviceKey = deviceRelayKey(bytes(i.keySeed), relayHandle)
  assert.equal(deviceKey.toString("hex"), o.deviceRelayKey)
  const deviceTranscript = clientTranscript({ route, kind: "device", key: relayHandle, ts: i.ts, nonce: bytes(i.nonce) })
  assert.equal(deviceTranscript.toString("hex"), o.T_client_device)
  assert.equal(clientMac(deviceKey, deviceTranscript).toString("hex"), o.mac_device)

  const bootstrap = bootstrapKeys(bytes(i.bootstrapSeed), route)
  assert.equal(bootstrap.bootstrapId.toString("hex"), o.bootstrapId)
  assert.equal(bootstrap.bootstrapKey.toString("hex"), o.bootstrapKey)
  const bootstrapTranscript = clientTranscript({ route, kind: "bootstrap", key: bootstrap.bootstrapId, ts: i.ts, nonce: bytes(i.nonce) })
  assert.equal(bootstrapTranscript.toString("hex"), o.T_client_bootstrap)
  assert.equal(clientMac(bootstrap.bootstrapKey, bootstrapTranscript).toString("hex"), o.mac_bootstrap)
})

test("DLP/1 base64url outputs match vectors", () => {
  assert.equal(b64u(bytes(vectors.outputs.routeId)), vectors.outputs.b64u.routeId)
  assert.equal(b64u(bytes(vectors.inputs.relayHandle)), vectors.outputs.b64u.relayHandle)
})
