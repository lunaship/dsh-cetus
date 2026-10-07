/**
 * 防回归：生成物 src/client.js 的模块注册 id 必须等于 package.json 的 name。
 *
 * 背景：DSH host 用 package name 作为 client module graph row 的 id
 * （dsh-client-modules `locatePkgJson` → `packageName` → `graphRow(packageName, …)`），
 * 浏览器端加载 bundle 后用同一个 id 校验注册结果：
 *
 *   if (!this.factories.has(id)) throw new Error(
 *     `client-modules: bundle ${url} loaded without registering "${id}" via __ModuleLoader__.load`)
 *
 * 两个 id 一旦不一致（例如只改了 package.json 却忘了 build-client.mjs 里的 id），
 * 「手机连接」设置面板会在浏览器里静默消失 —— 这个错误 `npm test` 抓不到
 * （e2e smoke 的断言只是宽松正则），所以在这里钉住它。
 *
 * 另外锁住「生成物与 build-client.mjs 同步」：prepack 会重新生成 src/client.js，
 * 若有人手改了 client.js 而没改 build-client.mjs，这里也会失败。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf8"))
const clientSrc = readFileSync(join(root, "src", "client.js"), "utf8")
const buildSrc = readFileSync(join(root, "build-client.mjs"), "utf8")

/** 取出 client bundle 里 __ModuleLoader__.load({ id: '…' }) 的注册 id。 */
function registeredIds(source) {
  return [...source.matchAll(/__ModuleLoader__\.load\(\{\s*id:\s*'([^']+)'/g)].map((m) => m[1])
}

test("client bundle 恰好注册一个模块，且 id 等于 package.json name", () => {
  const ids = registeredIds(clientSrc)
  assert.deepEqual(ids, [pkg.name], `注册 id=${JSON.stringify(ids)} 必须等于 package name "${pkg.name}"`)
})

test("build-client.mjs 生成器里的 id 与 package.json name 一致", () => {
  const ids = registeredIds(buildSrc)
  assert.deepEqual(ids, [pkg.name], `build-client.mjs 写入的 id=${JSON.stringify(ids)} 必须等于 package name "${pkg.name}"`)
})

test("client bundle 里不再残留旧品牌名 dsh-links", () => {
  assert.equal(clientSrc.includes("dsh-links"), false, "src/client.js 残留 dsh-links：生成物未重新构建或漏改")
})

test("settings.section 注册 id 跟随 package name", () => {
  const panelSrc = readFileSync(join(root, "src", "panel.js"), "utf8")
  const sectionId = panelSrc.match(/name:\s*'settings\.section',\s*\n\s*id:\s*'([^']+)'/)?.[1]
  assert.equal(sectionId, pkg.name, `面板 section id="${sectionId}" 必须等于 package name "${pkg.name}"`)
})
