/**
 * 方案 §25.1（N05.1）：改名**不得**动这些 v1 契约。
 *
 * 它们不是"品牌残留"，而是**在用的协议常量**：改了会让已经装在用户手机上的 App
 * 立刻无法与插件通信，而且现象很隐蔽 ——
 * 推送只是永远显示通用文案、远程只是永远连不上、已配对设备指纹校验失败。
 *
 * 之所以要这条测试：改名是大范围机械替换，`dsh-links` → `dsh-cetus` 的 sed
 * 很容易顺手把这些常量也换掉。已有的测试大多**使用**这些常量（所以两边一起改
 * 仍然"通过"），只有断言**字面值**才能发现。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const read = (p) => readFileSync(join(root, p), "utf8")

test("dlpush/1 的 info 与 AAD 前缀字节不变（三端一致）", () => {
  // 插件侧
  const sink = read("src/push-sink.js")
  assert.ok(sink.includes('"dlpush/1 content|"'), "插件侧 content AAD 前缀被改动")
  // 网关侧
  const content = read("push/internal/content/content.go")
  assert.ok(content.includes('"dlpush/1 content|"'), "Go 网关 content AAD 前缀被改动")
  assert.ok(content.includes('"dlpush/1 token|"'), "Go 网关 token AAD 前缀被改动")
  // iOS 侧
  const crypto = read("apps/ios/Packages/DLSecurity/Sources/DLSecurity/PushCrypto.swift")
  assert.ok(crypto.includes('"dlpush/1 token"'), "iOS token info 被改动")
  assert.ok(crypto.includes('"dlpush/1 content|"'), "iOS content AAD 前缀被改动")
})

test("TLS 证书 CN 仍是 dsh-links —— 动了会破坏已配对设备的指纹固定", () => {
  const tls = read("src/tls.js")
  assert.ok(
    tls.includes('value: "dsh-links"'),
    "TLS CN 被改动：已配对设备按指纹固定校验，改 CN 会让所有老设备连不上")
})

test("移动 API 路径前缀仍是 /dsh-link/", () => {
  const index = read("src/index.js")
  assert.ok(index.includes("/dsh-link/"), "移动 API 前缀被改动")
  // 反例：不该出现改名后的新前缀。
  assert.equal(index.includes("/dsh-cetus/"), false, "出现了 /dsh-cetus/ —— 移动 API 前缀不是品牌名")
})

test("插件注册名与客户端 bundle id 一致（面板静默消失的根因）", () => {
  const pkg = JSON.parse(read("package.json"))
  assert.equal(pkg.name, "dsh-cetus")
  const client = read("src/client.js")
  // 注册块是多行：`__ModuleLoader__.load({` 换行后才是 `id: '…'`。
  const registered = [...client.matchAll(/__ModuleLoader__\.load\(\{\s*id:\s*'([^']+)'/g)].map((m) => m[1])
  assert.deepEqual(registered, ["dsh-cetus"], `客户端注册 id=${JSON.stringify(registered)} 与包名不一致`)
})

test("状态目录历史名保留为迁移源，不被当成品牌残留清掉", () => {
  const migration = read("src/state-migration.js")
  assert.ok(migration.includes('"dsh-links"'), "dsh-links 必须仍在迁移源列表里（老用户靠它迁移）")
  assert.ok(migration.includes('"dsh-cetus"'), "规范目录名应为 dsh-cetus")
})
