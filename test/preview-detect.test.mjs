import { test } from "node:test"
import assert from "node:assert/strict"
import {
  DETECT_MAX_SESSIONS,
  createPreviewDetector,
  extractDevServerPorts,
  publicDetections,
  refreshPreviewDetections,
  toolOutputText,
} from "../src/preview-detect.js"

const MARKER = "SECRET-OUTPUT-MARKER"

function toolResult(text, version = "v4") {
  if (version === "v3") {
    return {
      event: {
        seq: 3,
        type: "tool/result",
        data: {
          message: {
            role: "user",
            source: { callId: "c1" },
            content: [{ type: "tool-result", toolCallId: "c1", content: [{ type: "text", text }] }],
          },
        },
      },
    }
  }
  return {
    type: "tool/result",
    seq: 4,
    data: {
      message: {
        role: "tool",
        toolCallId: "c1",
        content: [{ type: "text", text }],
      },
    },
  }
}

test("识别本机 http 端口，并丢掉示例地址和别的主机", () => {
  const text = [
    "  ➜  Local:   http://localhost:5173/",
    "  ➜  Network: http://192.168.1.20:5173/",
    "ready http://127.0.0.1:5173/example",
    "also http://0.0.0.0:4173",
    "例如：打开 http://localhost:3000",
    "e.g. http://127.0.0.1:8080",
    "for example http://0.0.0.0:4173",
    "see example http://localhost:9",
    "placeholder http://localhost:11",
    "样例 http://127.0.0.1:13",
    "https://localhost:5173",
    "http://example.com:80",
    "http://localhost:70000",
    "http://localhost:0",
    "nothttp://localhost:3000",
    "http://localhost:5173.evil.test",
    "http://localhost:PORT",
  ].join("\n")
  assert.deepEqual(extractDevServerPorts(text), [5173, 4173])
})

test("同一会话重复出现只留一条，不同会话分开记", () => {
  const detector = createPreviewDetector({ pluginPort: 18640, hostPort: 3000 })
  const output = `Local: http://localhost:5173/\n${MARKER}\nhttp://127.0.0.1:5173/`
  detector.observe("s1", toolResult(output))
  detector.observe("s1", toolResult(output, "v3"))
  detector.observe("s2", toolResult("http://0.0.0.0:4173"))
  detector.observe("s1", { type: "assistant/message", data: { message: { content: [{ type: "text", text: "http://localhost:9999" }] } } })
  detector.observe("s1", toolResult("http://localhost:22"))
  detector.observe("s1", toolResult("http://localhost:18640"))
  detector.observe("s1", toolResult("http://127.0.0.1:3000"))
  const listed = detector.list()
  assert.deepEqual(listed, [
    { port: 5173, sessionId: "s1" },
    { port: 4173, sessionId: "s2" },
  ])
  assert.equal(JSON.stringify(listed).includes(MARKER), false)
  assert.equal(Object.keys(listed[0]).sort().join(), "port,sessionId")
})

test("工具输出只读文本块", () => {
  assert.equal(toolOutputText(toolResult(`http://localhost:5173 ${MARKER}`)).includes(MARKER), true)
  assert.equal(toolOutputText({ type: "tool/call", data: { arguments: `http://localhost:1 ${MARKER}` } }), "")
  assert.equal(toolOutputText({ type: "user/message", data: { content: [{ type: "text", text: MARKER }] } }), "")
})

test("会话结束后清掉该会话的端口", () => {
  const detector = createPreviewDetector({ pluginPort: 1, hostPort: 2 })
  detector.observe("s1", toolResult("http://localhost:5173"))
  detector.observe("s2", toolResult("http://127.0.0.1:4173"))
  detector.endSession("s1")
  assert.deepEqual(detector.list(), [{ port: 4173, sessionId: "s2" }])
  detector.endSession("s2")
  assert.deepEqual(detector.list(), [])
})

test("刷新时去掉已结束和已归档的会话，并去重历史", async () => {
  const detector = createPreviewDetector({ pluginPort: 1, hostPort: 2 })
  detector.observe("gone", toolResult(`http://localhost:1111 ${MARKER}`))
  detector.observe("arch", toolResult("http://localhost:2222"))
  const historyCalls = []
  const listed = await refreshPreviewDetections({
    detector,
    now: 10_000,
    listSessions: async () => ({
      items: [
        { sessionId: "live", updatedAt: 5, running: false },
        { sessionId: "arch", updatedAt: 9, running: false },
        { sessionId: "busy", updatedAt: 8, running: true },
      ],
      archivedSessionIds: ["arch"],
    }),
    history: async (sessionId) => {
      historyCalls.push(sessionId)
      if (sessionId === "live") {
        return { events: [toolResult(`ready http://localhost:5173/\n${MARKER}`), toolResult("http://127.0.0.1:5173/")] }
      }
      return { events: [toolResult("http://0.0.0.0:4173")] }
    },
  })
  assert.deepEqual(historyCalls.sort(), ["busy", "live"])
  assert.deepEqual(listed, [
    { port: 4173, sessionId: "busy" },
    { port: 5173, sessionId: "live" },
  ])
  assert.equal(JSON.stringify(listed).includes(MARKER), false)
  const again = []
  await refreshPreviewDetections({
    detector,
    now: 12_000,
    listSessions: async () => ({
      items: [
        { sessionId: "live", updatedAt: 5, running: false },
        { sessionId: "busy", updatedAt: 8, running: true },
      ],
      archivedSessionIds: [],
    }),
    history: async (sessionId) => {
      again.push(sessionId)
      return { events: [] }
    },
  })
  assert.deepEqual(again, [])
})

test("列表失败时保留已有记录", async () => {
  const detector = createPreviewDetector({ pluginPort: 1, hostPort: 2 })
  detector.observe("s1", toolResult("http://localhost:5173"))
  const listed = await refreshPreviewDetections({
    detector,
    listSessions: async () => { throw new Error("down") },
    history: async () => ({ events: [] }),
  })
  assert.deepEqual(listed, [{ port: 5173, sessionId: "s1" }])
})

test("只回看最近的会话", async () => {
  const detector = createPreviewDetector({ pluginPort: 1, hostPort: 2 })
  const calls = []
  const items = []
  for (let i = 0; i < DETECT_MAX_SESSIONS + 1; i++) {
    items.push({ sessionId: `s${i}`, updatedAt: i, running: false })
  }
  await refreshPreviewDetections({
    detector,
    now: 1,
    listSessions: async () => ({ items, archivedSessionIds: [] }),
    history: async (sessionId) => {
      calls.push(sessionId)
      return { events: [] }
    },
  })
  assert.equal(calls.length, DETECT_MAX_SESSIONS)
  assert.equal(calls.includes("s0"), false)
})

test("已批准的端口不再提示", () => {
  const rows = [
    { port: 5173, sessionId: "s1" },
    { port: 4173, sessionId: "s1", note: MARKER },
  ]
  assert.deepEqual(publicDetections(rows, [5173]), [{ port: 4173, sessionId: "s1" }])
})
