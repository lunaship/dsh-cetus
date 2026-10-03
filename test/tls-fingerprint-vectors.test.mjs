/**
 * I3.4 证书指纹共享向量（testdata/tls-fingerprint/vectors.json）：
 * 每条向量的 DER 还原成 PEM 后，插件 certFingerprintSha256 的结果必须等于
 * 向量里的期望指纹——插件、Android（PinnedSsl.fingerprintOf）、iOS
 * （DLSecurity CertificateFingerprint.sha256）三端共用同一份文件。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { dirname, join } from "node:path"
// 直接用插件实现：向量的期望值必须与插件实际算法一致，不另写等价实现。
import { certFingerprintSha256 } from "../src/tls.js"

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))
const { vectors } = JSON.parse(readFileSync(join(ROOT, "testdata", "tls-fingerprint", "vectors.json"), "utf8"))

function derToPem(derBase64) {
  const body = Buffer.from(derBase64, "base64").toString("base64").replace(/(.{64})/g, "$1\n")
  return `-----BEGIN CERTIFICATE-----\n${body}\n-----END CERTIFICATE-----\n`
}

test("每条向量 DER → PEM 后指纹与期望一致", () => {
  assert.ok(vectors.length >= 3, "向量清单太短，可能没读到文件")
  for (const vector of vectors) {
    const pem = derToPem(vector.certDerBase64)
    assert.equal(certFingerprintSha256(pem), vector.fingerprint, `vector ${vector.name}`)
  }
})
