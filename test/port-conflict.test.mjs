/**
 * 方案 §22.1 第 5 步：插件改名过渡期可能出现**新旧 id 同时启用**，
 * 两个实例抢同一个端口。裸的 `EADDRINUSE` 看不出这一点，用户会以为插件坏了。
 */
import assert from "node:assert/strict"
import test from "node:test"
import { portConflictHint } from "../src/index.js"

test("端口冲突时给出「可能是新旧实例同时启用」的诊断", () => {
  const err = Object.assign(new Error("listen EADDRINUSE: address already in use :::18640"), {
    code: "EADDRINUSE",
  })
  const hint = portConflictHint(err, 18640)
  assert.ok(hint, "应当命中端口冲突")
  assert.match(hint, /18640/, "要写明是哪个端口")
  assert.match(hint, /同时启用|重复|另一个实例/, "要点出最可能的原因是新旧实例同时在跑")
  assert.match(hint, /重启 host|禁用/, "要给出可操作的下一步")
})

test("其它监听错误不误报端口冲突", () => {
  const err = Object.assign(new Error("listen EACCES: permission denied"), { code: "EACCES" })
  assert.equal(portConflictHint(err, 18640), null, "EACCES 不是端口被占，不该套用该诊断")
})

test("没有 code 的错误不误报", () => {
  assert.equal(portConflictHint(new Error("boom"), 18640), null)
  assert.equal(portConflictHint(null, 18640), null)
  assert.equal(portConflictHint(undefined, 18640), null)
})

test("诊断只提示、不含任何自动处置动作", () => {
  const err = Object.assign(new Error("in use"), { code: "EADDRINUSE" })
  const hint = portConflictHint(err, 18640)
  // 抢端口的另一端可能正是用户正在用的旧实例 —— 不能自动杀进程或改配置。
  assert.doesNotMatch(hint, /kill|pkill|已自动|已切换/, "不得包含自动处置")
})
