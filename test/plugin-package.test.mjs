/**
 * 插件包与 CI 契约（原 release-workflow 测试）。
 *
 * npm 发布已移除（2026-09-30）：插件只从本仓以 git 源分发，不再发布到 registry。
 * 这里守住三件事：
 *   1. 没有 npm 发布工作流，CI 里也不出现 publish/registry token；
 *   2. 工作流把第三方 Action 固定到已核验提交（供应链）；
 *   3. 插件包内容只含插件文件，不含 `relay/` 或 `apps/android/`。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { execFileSync } from "node:child_process"
import { existsSync, readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { dirname } from "node:path"

const root = dirname(fileURLToPath(new URL("../package.json", import.meta.url)))
const read = (rel) => readFileSync(fileURLToPath(new URL(`../${rel}`, import.meta.url)), "utf8")
const ci = read(".github/workflows/ci.yml")
const ciAndroid = read(".github/workflows/ci-android.yml")
const pkg = JSON.parse(readFileSync(fileURLToPath(new URL("../package.json", import.meta.url)), "utf8"))

test("没有 npm 发布工作流", () => {
  assert.equal(
    existsSync(fileURLToPath(new URL("../.github/workflows/publish-npm.yml", import.meta.url))),
    false,
  )
})

test("CI 不发布 npm 包、不读取 registry 凭据", () => {
  for (const [name, text] of [["ci.yml", ci], ["ci-android.yml", ciAndroid]]) {
    assert.doesNotMatch(text, /npm publish/, name)
    assert.doesNotMatch(text, /NPM_TOKEN|NODE_AUTH_TOKEN/, name)
    assert.doesNotMatch(text, /registry-url|registry\.npmjs\.org/, name)
    assert.doesNotMatch(text, /id-token: write/, name)
  }
})

test("工作流把第三方 Action 固定到已核验提交", () => {
  for (const [name, text] of [["ci.yml", ci], ["ci-android.yml", ciAndroid]]) {
    assert.doesNotMatch(text, /uses:\s*[\w.-]+\/[\w.-]+@v\d+\b/, `${name} 有未固定到 SHA 的 Action`)
    assert.doesNotMatch(text, /uses:\s*[\w.-]+\/[\w.-]+@(main|master|HEAD)\b/, `${name} 有浮动 ref`)
  }
  assert.match(ci, /actions\/checkout@d23441a48e516b6c34aea4fa41551a30e30af803\s+# v6/)
  assert.match(ciAndroid, /actions\/checkout@d23441a48e516b6c34aea4fa41551a30e30af803\s+# v6/)
})

test("Android CI 存在且限定 apps/android 路径", () => {
  assert.match(ciAndroid, /name: CI - Android/)
  assert.match(ciAndroid, /paths:\n      - 'apps\/android\/\*\*'/)
  assert.match(ciAndroid, /working-directory: apps\/android/)
})

test("插件包内容只含插件文件，不含 relay / apps/android", () => {
  assert.equal(pkg.publishConfig, undefined)
  assert.ok(pkg.files.includes("src"))
  assert.ok(pkg.files.includes("SECURITY.md"))
  assert.ok(pkg.files.includes("PRIVACY.md"))
  assert.ok(pkg.files.includes("REMOTE_ACCESS.md"))
  assert.ok(pkg.files.includes("docs/*.md"))
  assert.ok(pkg.files.includes("docs/images/*-latest*.png"))
  assert.ok(!pkg.files.some((entry) => entry === "relay" || String(entry).startsWith("relay/")))
  assert.ok(!pkg.files.some((entry) => String(entry).startsWith("apps/")))
  assert.match(ci, /go build \.\/cmd\/dlp-relay/)
  assert.match(ci, /go test \.\/\.\.\. -race/)
  assert.doesNotMatch(ci, /CGO_ENABLED=0 go test \.\/\.\.\. -race/)

  const pack = JSON.parse(
    execFileSync("npm", ["pack", "--dry-run", "--ignore-scripts", "--json"], { encoding: "utf8", cwd: root }),
  )
  const packedFiles = new Set(pack[0].files.map((file) => file.path))
  assert.ok(packedFiles.has("SECURITY.md"))
  assert.ok(packedFiles.has("PRIVACY.md"))
  assert.ok(packedFiles.has("REMOTE_ACCESS.md"))
  assert.ok(packedFiles.has("docs/COMPATIBILITY.md"))
  assert.ok(packedFiles.has("docs/MOBILE_SYNC_CONTRACT.md"))
  assert.ok(packedFiles.has("docs/images/dsh-workbench-latest.png"))
  assert.ok(packedFiles.has("docs/images/android-workspace-latest.png"))
  assert.ok(![...packedFiles].some((entry) => /^relay\/.+\.go$/.test(entry)))
  assert.ok(![...packedFiles].some((entry) => entry.startsWith("cmd/")))
  assert.ok(![...packedFiles].some((entry) => entry.startsWith("apps/")))
  assert.ok(!packedFiles.has("docs/images/android-workspace-dark.jpg"))
})
