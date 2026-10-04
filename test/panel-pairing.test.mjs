import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"
import vm from "node:vm"

const source = readFileSync(new URL("../src/panel.js", import.meta.url), "utf8")
const info = { pairingCode: "123456", expiresAt: 2000000000000, certFingerprint: "aa".repeat(32), infos: [] }

// Render the registered settings section with inert hooks: no browser, network or timers.
function renderPairing(remote, pairInfo = info) {
  const data = { info: pairInfo, devices: [], workspaceApprovals: [], remote, previews: [], detected: [] }
  const React = {
    Fragment: "fragment",
    createElement(type, props, ...children) { return { type, props: { ...props, children } } },
    useState(initial) { return [initial?.info === null ? data : initial, () => {}] },
    useEffect() {},
    useRef(value) { return { current: value } },
    useCallback(fn) { return fn },
  }
  const factory = vm.runInNewContext(`${source}\ncreatePanelModule`, { navigator: { language: "zh" } })
  const module = factory(() => React)
  let renderSection
  module.apply({
    effect(fn) { fn() },
    slots: {
      inject(_slot, fn) { fn() },
      register(_metadata, render) { renderSection = render },
    },
  })
  const nodes = []
  function visit(node) {
    if (Array.isArray(node)) { node.forEach(visit); return }
    if (!node || typeof node !== "object") return
    if (typeof node.type === "function") { visit(node.type(node.props)); return }
    nodes.push(node)
    visit(node.props.children)
  }
  visit(renderSection())
  const qr = nodes.find((node) => node.type === "img" && node.props.alt === "手机连接码")
  const hint = nodes.find((node) => node.props.className === "dl-pair-hint")
  return { qr: qr.props.src, hint: hint.props.children.filter((child) => typeof child === "string").join("") }
}

test("面板只有 remote ready 才说明二维码可远程配对", () => {
  const off = renderPairing({ state: "off", enabled: false })
  const connecting = renderPairing({ state: "connecting", enabled: true })
  const ready = renderPairing({ state: "ready", enabled: true, endpoint: "wss://relay.example/ws" })
  assert.match(off.hint, /同一网络/)
  assert.match(connecting.hint, /远程连上后/)
  assert.match(ready.hint, /外出自动走远程/)
  assert.equal(off.qr, connecting.qr, "enabled 本身不会使二维码获得远程能力")
  assert.notEqual(connecting.qr, ready.qr, "就绪时必须重取二维码")
})

test("配对码和 ready 不变时，换中继地址或指纹仍刷新图片", () => {
  const remote = { state: "ready", endpoint: "wss://relay.example/ws", outerPin: "" }
  const original = renderPairing(remote).qr
  assert.equal(renderPairing(remote).qr, original, "同一内容维持图片请求地址")
  assert.notEqual(renderPairing({ ...remote, endpoint: "wss://relay.other/ws" }).qr, original)
  assert.notEqual(renderPairing({ ...remote, outerPin: "bb".repeat(32) }).qr, original)
  assert.notEqual(renderPairing(remote, { ...info, certFingerprint: "cc".repeat(32) }).qr, original)
})
