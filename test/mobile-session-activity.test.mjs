import assert from "node:assert/strict"
import test from "node:test"
import {
  collapseToLine,
  deriveActivity,
  deriveAwaitingInput,
  deriveLastResult,
  stripMarkdown,
  summarizeToolCall,
  MAX_ACTIVITY_LABEL_CHARS,
  MAX_RESULT_TEXT_CHARS,
} from "../src/mobile-session-activity.js"

/** session.history 的事件形状：{ event: { seq, time, type, data } }。 */
function ev(seq, type, data = {}, time = seq * 100) {
  return { event: { seq, time, type, data } }
}

test("工具调用：未结束的调用给出命令与步号", () => {
  const activity = deriveActivity([
    ev(1, "user/message", { content: [{ type: "text", text: "跑一下测试" }] }),
    ev(2, "tool/call", { callId: "c1", name: "shell", arguments: { command: "go test ./..." }, step: 12 }),
  ])
  assert.equal(activity.kind, "tool")
  assert.equal(activity.label, "go test ./...")
  assert.equal(activity.step, 12)
  assert.equal(activity.startedAt, 200)
})

test("工具调用：已有 tool/result 的调用不算进行中", () => {
  const activity = deriveActivity([
    ev(2, "tool/call", { callId: "c1", name: "shell", arguments: { command: "ls" }, step: 3 }),
    ev(3, "tool/result", { callId: "c1", content: [] }),
  ])
  assert.equal(activity, null)
})

test("工具调用：多个调用同时开着时取最近一个", () => {
  const activity = deriveActivity([
    ev(2, "tool/call", { callId: "c1", name: "shell", arguments: { command: "sleep 1" } }),
    ev(3, "tool/call", { callId: "c2", name: "shell", arguments: { command: "go vet ./..." }, step: 9 }),
    ev(4, "tool/result", { callId: "c2", content: [] }),
  ])
  // c2 已结束，剩下 c1 还在跑
  assert.equal(activity.label, "sleep 1")
})

test("工具调用：reasoning-delta 之后没有新工具，按思考中上报", () => {
  const activity = deriveActivity([
    ev(2, "tool/call", { callId: "c1", name: "shell", arguments: { command: "ls" } }),
    ev(3, "tool/result", { callId: "c1", content: [] }),
    ev(4, "assistant/chunk", { chunk: { type: "reasoning-delta", text: "先看" } }),
  ])
  assert.equal(activity.kind, "thinking")
  // 思考/写作没有具体内容，label 不下发（App 按 kind 本地化文案）
  assert.equal("label" in activity, false)
  assert.equal("step" in activity, false)
})

test("工具调用：text-delta 之后按正在写回复上报", () => {
  const activity = deriveActivity([
    ev(4, "assistant/chunk", { chunk: { type: "text-delta", text: "结论是" } }),
  ])
  assert.equal(activity.kind, "writing")
})

test("工具调用：assistant/message 收尾后不再上报流式状态", () => {
  const activity = deriveActivity([
    ev(4, "assistant/chunk", { chunk: { type: "text-delta", text: "结论是" } }),
    ev(5, "assistant/message", { message: { content: [{ type: "text", text: "结论是好的" }] } }),
  ])
  assert.equal(activity, null)
})

test("工具调用：只有工具名时也要给得出标签", () => {
  const activity = deriveActivity([
    ev(2, "tool/call", { callId: "c1", name: "read_file", arguments: "{}" }),
  ])
  assert.equal(activity.label, "read_file")
})

test("工具调用：路径类参数带工具名前缀，命令行不带", () => {
  assert.equal(summarizeToolCall("read_file", { file_path: "/tmp/a.txt" }), "read_file /tmp/a.txt")
  assert.equal(summarizeToolCall("shell", { command: "go build ./..." }), "go build ./...")
  assert.equal(summarizeToolCall("shell", '{"command":"npm test"}'), "npm test")
})

test("工具调用：超长命令截断到上限并加省略号", () => {
  const label = summarizeToolCall("shell", { command: "x".repeat(200) })
  assert.equal(label.length, MAX_ACTIVITY_LABEL_CHARS)
  assert.equal(label.endsWith("…"), true)
})

test("工具调用：没有事件时返回 null", () => {
  assert.equal(deriveActivity([]), null)
  assert.equal(deriveActivity(null), null)
})

test("stripMarkdown 去掉代码块、标题、列表与强调记号", () => {
  const text = stripMarkdown([
    "## 结论",
    "",
    "改了 `foo.js` 的**重试**逻辑，",
    "",
    "```js",
    "const a = 1",
    "```",
    "",
    "- 第一点",
    "- 第二点",
  ].join("\n"))
  assert.equal(text.includes("```"), false)
  assert.equal(text.includes("**"), false)
  assert.equal(text.includes("##"), false)
  assert.equal(text.includes("const a = 1"), false)
  assert.equal(text.includes("改了 foo.js 的重试逻辑"), true)
  assert.equal(text.includes("第一点"), true)
})

test("结果一句话：取最后一条助手回复，去 Markdown 截断到 60 字", () => {
  const result = deriveLastResult([
    ev(1, "user/message", { content: [{ type: "text", text: "做完了吗" }] }),
    ev(2, "assistant/message", { message: { content: [{ type: "text", text: "先做了一部分" }] } }),
    ev(3, "assistant/message", { message: { content: [{ type: "text", text: "**门禁全绿**，`npm run prepack` 通过。" }] } }),
  ])
  assert.equal(result.text, "门禁全绿，npm run prepack 通过。")
  assert.equal("files" in result, false)
})

test("结果一句话：block-end 的 text 块同样算回复", () => {
  const result = deriveLastResult([
    ev(2, "assistant/chunk", { chunk: { type: "block-end", block: { type: "text", text: "改了 3 处并跑通测试" } } }),
  ])
  assert.equal(result.text, "改了 3 处并跑通测试")
})

test("结果一句话：带本轮改动统计（total/added/deleted）", () => {
  const calls = []
  // DSH 的摘要里 files 是完整清单；空清单等于「这一轮没有改动」，与改动卡同一判定
  const summary = {
    turn: 1,
    total: 79,
    added: 1200,
    deleted: 300,
    files: [{ path: "src/a.js", added: 10, deleted: 2 }],
  }
  const result = deriveLastResult(
    [
      ev(2, "workspace/changes", { turn: 1 }),
      ev(3, "assistant/message", { message: { content: [{ type: "text", text: "完成" }] } }),
    ],
    (seq) => { calls.push(seq); return summary },
  )
  assert.deepEqual(calls, [2])
  assert.equal(result.text, "完成")
  assert.equal(result.files, 79)
  assert.equal(result.added, 1200)
  assert.equal(result.deleted, 300)
})

test("结果一句话：改动摘要没有列出任何文件时不算改动", () => {
  const result = deriveLastResult(
    [ev(2, "workspace/changes", { turn: 1 })],
    () => ({ turn: 1, total: 0, added: 0, deleted: 0, files: [] }),
  )
  assert.equal(result, null)
})

test("结果一句话：取不到改动摘要时只丢统计，文本照常", () => {
  const result = deriveLastResult(
    [ev(2, "workspace/changes", { turn: 1 }), ev(3, "assistant/message", { message: { content: [{ type: "text", text: "完成" }] } })],
    () => { throw new Error("Host 重启后旧轮次没有摘要") },
  )
  assert.equal(result.text, "完成")
  assert.equal("files" in result, false)
})

test("结果一句话：没有文本但有改动数字时仍下发", () => {
  const result = deriveLastResult(
    [ev(2, "workspace/changes", { turn: 1 })],
    () => ({ turn: 1, total: 6, added: 12, deleted: 3, files: [{ path: "a.kt", added: 12, deleted: 3 }] }),
  )
  assert.equal("text" in result, false)
  assert.equal(result.files, 6)
})

test("结果一句话：什么都没有时返回 null（调用方不下发字段）", () => {
  assert.equal(deriveLastResult([]), null)
  assert.equal(deriveLastResult([ev(1, "user/message", { content: [] })]), null)
})

test("collapseToLine：压空白、超长截断", () => {
  assert.equal(collapseToLine("  a\n\n b  ", 10), "a b")
  assert.equal(collapseToLine("", 10), null)
  const long = collapseToLine("字".repeat(100), MAX_RESULT_TEXT_CHARS)
  assert.equal(long.length, MAX_RESULT_TEXT_CHARS)
  assert.equal(long.endsWith("…"), true)
})

test("deriveStoppedReason：正常完成返回 null，非 completed 返回原因", async () => {
  const { deriveStoppedReason } = await import("../src/mobile-session-activity.js")
  // 真实历史行是包了一层的（eventOf 取 item.event），测试要照同样的形状
  const end = (kind) => ({ event: { seq: 1, time: 1, type: "turn/end", data: { reason: { kind } } } })
  assert.equal(deriveStoppedReason([end("completed")]), null)
  assert.equal(deriveStoppedReason([end("interrupted")]), "interrupted")
  assert.equal(deriveStoppedReason([end("maxTokens")]), "maxTokens")
  // 没有 turn/end：不知道就不说
  assert.equal(deriveStoppedReason([{ event: { seq: 2, time: 2, type: "assistant/message", data: {} } }]), null)
  // 取最后一个 turn/end
  assert.equal(deriveStoppedReason([end("interrupted"), end("completed")]), null)
})

test("deriveAwaitingInput：问了没答才算等人", () => {
  const asked = (id, seq = 1) => ({ event: { seq, type: "approval/asked", data: { id } } })
  const decided = (id, seq = 2) => ({ event: { seq, type: "approval/decided", data: { id } } })
  assert.equal(deriveAwaitingInput([asked("a1")]), true)
  assert.equal(deriveAwaitingInput([asked("a1"), decided("a1")]), false)
  // 多轮：前一轮答过、后一轮又问了 → 仍在等人
  assert.equal(deriveAwaitingInput([asked("a1"), decided("a1"), asked("a2")]), true)
  // 只有回答、没有问过（历史被截断）→ 不算等人
  assert.equal(deriveAwaitingInput([decided("a1")]), false)
  // 空 / 缺 id 的事件安全跳过
  assert.equal(deriveAwaitingInput([]), false)
  assert.equal(deriveAwaitingInput(undefined), false)
  assert.equal(deriveAwaitingInput([{ event: { type: "approval/asked", data: {} } }]), false)
})
