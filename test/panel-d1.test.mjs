import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const panel = readFileSync(new URL("../src/panel.js", import.meta.url), "utf8")

function between(source, start, end) {
  const from = source.indexOf(start)
  const to = source.indexOf(end, from + start.length)
  assert.ok(from >= 0 && to > from, `missing ${start} .. ${end}`)
  return source.slice(from, to)
}

test("D1 首屏只留二维码、一行状态、倒计时和外出开关", () => {
  const pair = between(panel, "function PairGroup", "function RelayForm")
  assert.match(pair, /放大连接码/)
  assert.match(pair, /后换新码/)
  assert.ok(pair.includes("connectionLine(remote)"))
  assert.doesNotMatch(pair, /复制/)
  assert.doesNotMatch(pair, /formatCode/)
  assert.doesNotMatch(pair, /PairAddresses/)
  assert.doesNotMatch(pair, /扫一次就行/)
  const header = between(panel, "function Header", "function AttentionGroup")
  assert.doesNotMatch(header, /dl-pill/)
  assert.doesNotMatch(header, /扫码/)
  const line = between(panel, "function connectionLine", "function PairGroup")
  assert.match(line, /未开启/)
  assert.match(line, /已就绪/)
  assert.match(line, /失败/)
})

test("D1 待处理留在首屏，设置、诊断和端口收进更多", () => {
  const attention = between(panel, "function AttentionGroup", "function connectionLine")
  assert.match(attention, /需要你处理/)
  assert.match(attention, /批准/)
  assert.match(attention, /拒绝/)
  const remote = between(panel, "function RemoteGroup", "function deviceRoute(")
  assert.match(remote, /外出时也能连/)
  assert.ok(remote.includes("summary"))
  assert.match(remote, /新手机要在这里批准/)
  assert.match(remote, /自建中继/)
  assert.match(remote, /重置远程身份/)
  assert.match(remote, /extra/)
  const body = between(panel, "function PanelBody", "function Panel")
  assert.match(body, /DiagnosticsGroup/)
  assert.match(body, /PreviewGroup/)
  assert.doesNotMatch(body, /ExposureNote/)
  const devices = between(panel, "function DevicesGroup", "function panelLocale")
  assert.ok(devices.includes("if (!paired.length) return null"))
  assert.doesNotMatch(devices, /还没有配对的手机/)
})

test("D1 设计稿登记默认、待处理、远程失败和更多", () => {
  const design = readFileSync(new URL("../docs/redesign-v4/design-v4.html", import.meta.url), "utf8")
  const section = between(design, 'id="desktop"', "<script")
  for (const label of ["默认", "待处理", "远程连接中 / 失败", "展开更多"]) {
    assert.match(section, new RegExp(label))
  }
})
