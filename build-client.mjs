/**
 * 组装 src/client.js —— 仅「手机连接」面板（配对码/二维码/设备管理）。
 * Android 使用原生 UI；插件只提供桌面端连接面板。
 */
import { readFileSync, writeFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const root = dirname(fileURLToPath(import.meta.url))
const panel = readFileSync(join(root, "src", "panel.js"), "utf8")

const header = `/**
 * dsh-cetus 客户端面（src/client.js，由 build-client.mjs 生成，勿手改）
 * 单模块：createPanelModule —— 「手机连接」面板（src/panel.js）
 * Android 使用原生 UI；本插件只提供桌面端连接面板。
 */
`

const boot = `
window.__ModuleLoader__.load({
  id: 'dsh-cetus',
  factory: (require) => {
    const panel = createPanelModule(require)
    const module = { exports: {} }
    const exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })

    function apply(ctx) {
      panel.apply(ctx)
    }

    exports.apply = apply
    exports.inject = panel.inject ?? []
    return module.exports
  },
})
`

const out = header + panel + boot
writeFileSync(join(root, "src", "client.js"), out)
console.log(`client.js written: ${out.split("\n").length} lines`)
