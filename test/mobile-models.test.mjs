/**
 * 手机模型页：余额 / 供应商目录 / 增删模型 / 写 API 密钥 / 添加目录供应商。
 * 写入只能落在供应商 profile 的 models / apiKeyEnv；密钥单向进 credentials.set，响应不回显。
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { createServer } from "node:http"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { apply } from "../src/index.js"
import { bindLocalRpcRuntime, unbindLocalRpcRuntime } from "../src/local-rpc.js"
import { apiKeyProblem, deriveKeyRef, joinProviderDirectory, projectBalance } from "../src/mobile-models.js"

test("deriveKeyRef 与桌面同规则", () => {
  assert.equal(deriveKeyRef("minimax-cn"), "MINIMAX_CN_API_KEY")
  assert.equal(deriveKeyRef("zai"), "ZAI_API_KEY")
})

test("apiKeyProblem 拒绝空值、环境变量行、引号与不可见字符", () => {
  assert.equal(apiKeyProblem("sk-abc123"), null)
  assert.equal(apiKeyProblem("ABCD=="), null)
  assert.ok(apiKeyProblem(""))
  assert.ok(apiKeyProblem("   "))
  assert.ok(apiKeyProblem("OPENAI_API_KEY=sk-1"))
  assert.ok(apiKeyProblem("\"sk-1\""))
  assert.ok(apiKeyProblem("sk 1"))
  assert.ok(apiKeyProblem(42))
})

test("joinProviderDirectory：account / official 置顶，仅注册路由补在后面", () => {
  const rows = joinProviderDirectory(
    [{ id: "zai", name: "Z.ai" }, { id: "deepseek-account", name: "DeepSeek" }, { id: "extra", name: "Extra" }],
    [
      { provider: "zai", displayName: "Z.ai", settingsNs: "llm-pi-ai", settingsPath: ["providers", "zai"] },
      { provider: "deepseek-official", displayName: "DeepSeek API", settingsNs: "llm-deepseek", settingsPath: [] },
      { provider: "deepseek-account", displayName: "DeepSeek", settingsNs: "", settingsPath: [] },
    ],
  )
  assert.deepEqual(rows.map((r) => r.provider), ["deepseek-account", "deepseek-official", "zai", "extra"])
  assert.equal(rows.find((r) => r.provider === "deepseek-official").active, false)
  assert.equal(rows.find((r) => r.provider === "extra").settingsNs, "")
})

test("projectBalance 覆盖未登录 / 失败 / 就绪三态", () => {
  assert.equal(projectBalance(null).status, "signed-out")
  assert.equal(projectBalance({ status: "failed" }).status, "failed")
  const ready = projectBalance({
    status: "ready",
    value: [{ currency: "CNY", balance: "12.50" }],
    bonusWallets: [{ currency: "CNY", balance: "3.00" }],
  })
  assert.deepEqual(ready, {
    status: "ready",
    wallets: [{ currency: "CNY", balance: "12.50" }],
    bonusWallets: [{ currency: "CNY", balance: "3.00" }],
  })
})

// ── 端到端：经 18640 代理 + 假 gateway ──

const TMP = mkdtempSync(join(tmpdir(), "dsh-models-test-"))
const PORT = 22000 + Math.floor(Math.random() * 1000)

const upstream = await new Promise((resolve) => {
  const srv = createServer((_req, res) => {
    res.writeHead(200, { "content-type": "text/plain" })
    res.end("upstream-ok")
  })
  srv.listen(0, "127.0.0.1", () => resolve(srv))
})

const registered = []
const effects = []
const ctx = {
  logger: { info() {}, warn() {} },
  get(name) {
    if (name === "webServer") {
      return {
        port: upstream.address().port,
        register(route) { registered.push(route); return () => {} },
        tapIndex() { return () => {} },
      }
    }
    return null
  },
  on() {},
  effect(fn) { effects.push(fn()) },
}

await apply(ctx, { port: PORT, pairingTtlSeconds: 300, autoApprove: true, stateDir: TMP, eventPollIntervalMs: 60000 })

async function proxyFetch(path, init = {}) {
  const https = await import("node:https")
  const tls = JSON.parse(readFileSync(join(TMP, "tls.json"), "utf8"))
  const agent = new https.Agent({ ca: tls.cert, rejectUnauthorized: true, checkServerIdentity: () => undefined })
  return new Promise((resolve, reject) => {
    const req = https.request(new URL(`https://127.0.0.1:${PORT}${path}`), {
      method: init.method || "GET",
      headers: init.headers || {},
      agent,
    }, (res) => {
      const chunks = []
      res.on("data", (c) => chunks.push(c))
      res.on("end", () => resolve(new Response(Buffer.concat(chunks), { status: res.statusCode, headers: res.headers })))
    })
    req.on("error", reject)
    if (init.body) req.write(init.body)
    req.end()
  })
}

const pairInfoRoute = registered.find((r) => r.path === "/dsh-link/pair-info")
let token
{
  let out = ""
  const res = { writeHead() {}, end(b) { out = String(b ?? "") } }
  await pairInfoRoute.handler({ method: "GET", headers: { host: `127.0.0.1:${upstream.address().port}` }, url: "/dsh-link/pair-info", socket: { remoteAddress: "127.0.0.1" } }, res)
  const code = JSON.parse(out).pairingCode
  const pair = await proxyFetch(`/dsh-link/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName: "models-test", requestId: "models-test-1" }),
  })
  assert.equal(pair.status, 200)
  token = (await pair.json()).token
}

function fakeGateway() {
  const calls = []
  const secrets = new Map()
  const namespaces = {
    "llm-deepseek": { ns: "llm-deepseek", revision: 1, value: { apiKeyEnv: "DEEPSEEK_API_KEY", models: [{ id: "deepseek-v4", name: "DeepSeek V4", reasoning: { efforts: ["high"] } }] } },
    "llm-pi-ai": {
      ns: "llm-pi-ai",
      revision: 7,
      value: {
        providers: {
          zai: { apiKeyEnv: "ZAI_API_KEY", baseURL: "https://open.bigmodel.cn/api/paas/v4", models: [{ id: "glm-5.3", name: "GLM-5.3", contextWindow: 1000000 }] },
          stepfun: { baseURL: "https://api.stepfun.com/v1" },
        },
      },
    },
  }
  function setPath(root, path, value) {
    let cur = root
    for (const key of path.slice(0, -1)) {
      if (typeof cur[key] !== "object" || cur[key] === null) cur[key] = {}
      cur = cur[key]
    }
    cur[path[path.length - 1]] = value
  }
  const invoke = async ({ namespace, method, args }) => {
    const name = `${namespace}/${method}`
    calls.push({ name, args })
    switch (name) {
      case "account/getBalance":
        return { status: "ready", value: [{ currency: "CNY", balance: "8.88" }], bonusWallets: [{ currency: "CNY", balance: "1.00" }] }
      case "llm/listProviders":
        return [{ id: "deepseek-account", name: "DeepSeek" }, { id: "deepseek-official", name: "DeepSeek API" }, { id: "zai", name: "Z.ai" }, { id: "stepfun", name: "StepFun" }]
      case "llm/listConfigurableProviders":
        return [
          { provider: "deepseek-official", displayName: "DeepSeek API", settingsNs: "llm-deepseek", settingsPath: [] },
          { provider: "zai", displayName: "Z.ai", settingsNs: "llm-pi-ai", settingsPath: ["providers", "zai"] },
          { provider: "stepfun", displayName: "StepFun", settingsNs: "llm-pi-ai", settingsPath: ["providers", "stepfun"], declared: true },
          { provider: "openai", displayName: "OpenAI", settingsNs: "llm-pi-ai", settingsPath: ["providers", "openai"] },
        ]
      case "settings/describe":
        return { writable: true, namespaces: Object.values(namespaces).map((ns) => structuredClone(ns)) }
      case "session/modelCatalog":
        return {
          default: { provider: "zai", model: "glm-5.3" },
          groups: [
            { id: "deepseek-account", name: "DeepSeek", models: [{ id: "deepseek-chat", name: "DeepSeek Chat" }] },
            { id: "stepfun", name: "StepFun", models: [{ id: "step-5", name: "Step 5" }] },
          ],
        }
      case "credentials/describe":
        return Object.fromEntries(args.refs.map((ref) => [ref, { configured: secrets.has(ref) || ref === "ZAI_API_KEY", writable: ref !== "DEEPSEEK_API_KEY" }]))
      case "credentials/set":
        secrets.set(args.ref, args.value)
        return { ok: true }
      case "settings/mutate": {
        const ns = namespaces[args.ns]
        if (args.expectedRevision !== undefined && args.expectedRevision !== ns.revision) {
          throw Object.assign(new Error("revision mismatch"), { code: "settings/conflict" })
        }
        for (const op of args.ops) setPath(ns.value, op.path, structuredClone(op.value))
        ns.revision += 1
        return structuredClone(ns)
      }
      case "llm/discoverModels":
        return [{ id: "glm-6", name: "GLM-6", contextWindow: 200000, inputModalities: ["text", "image"] }]
      default:
        throw new Error(`unexpected ${name}`)
    }
  }
  return { calls, secrets, namespaces, invoke }
}

function authed(path, body) {
  return proxyFetch(path, body === undefined
    ? { headers: { "x-dsh-link-token": token } }
    : { method: "POST", headers: { "x-dsh-link-token": token, "content-type": "application/json" }, body: JSON.stringify(body) })
}

test("GET balance 代查 account.getBalance 并投影钱包", async () => {
  const gw = fakeGateway()
  bindLocalRpcRuntime({ invoke: gw.invoke })
  try {
    const r = await authed("/dsh-link/mobile/balance?locale=zh-CN")
    assert.equal(r.status, 200)
    const body = await r.json()
    assert.equal(body.status, "ready")
    assert.deepEqual(body.wallets, [{ currency: "CNY", balance: "8.88" }])
    const call = gw.calls.find((c) => c.name === "account/getBalance")
    assert.equal(call.args.client.locale, "zh-CN")
    assert.ok(Number.isInteger(call.args.client.timezoneOffsetSeconds))
    assert.ok(call.args.client.version)
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("旧 DSH 无 account.getBalance 时返回 unavailable 而不是 502", async () => {
  bindLocalRpcRuntime({ invoke: async () => { throw Object.assign(new Error("no such method"), { code: "gateway/not-found" }) } })
  try {
    const r = await authed("/dsh-link/mobile/balance")
    assert.equal(r.status, 200)
    assert.equal((await r.json()).status, "unavailable")
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("GET providers：已配置行 + 可添加目录，密钥只给状态", async () => {
  const gw = fakeGateway()
  bindLocalRpcRuntime({ invoke: gw.invoke })
  try {
    const r = await authed("/dsh-link/mobile/providers")
    assert.equal(r.status, 200)
    const body = await r.json()
    assert.deepEqual(body.providers.map((p) => p.provider), ["deepseek-account", "deepseek-official", "zai", "stepfun"])
    const zai = body.providers.find((p) => p.provider === "zai")
    assert.deepEqual(zai.credential, { configured: true, writable: true, source: null })
    assert.equal(zai.modelsEditable, true)
    assert.deepEqual(zai.models.map((m) => m.id), ["glm-5.3"])
    const stepfun = body.providers.find((p) => p.provider === "stepfun")
    assert.equal(stepfun.custom, true)
    assert.equal(stepfun.keyRef, "STEPFUN_API_KEY")
    assert.equal(stepfun.modelsEditable, false, "没有显式 models 时不允许手机覆盖默认目录")
    assert.deepEqual(stepfun.models.map((m) => m.id), ["step-5"])
    assert.equal(body.providers[0].kind, "account")
    assert.deepEqual(body.addable, [{ provider: "openai", displayName: "OpenAI" }])
    assert.equal(JSON.stringify(body).includes("baseURL"), false)
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("POST providers/models 追加与删除只写 profile.models，保留原条目字段", async () => {
  const gw = fakeGateway()
  bindLocalRpcRuntime({ invoke: gw.invoke })
  try {
    const r = await authed("/dsh-link/mobile/providers/models", {
      provider: "deepseek-official",
      add: [{ id: "deepseek-v5", name: "", inputModalities: ["text", "image", "audio"] }],
    })
    assert.equal(r.status, 200)
    const mutate = gw.calls.find((c) => c.name === "settings/mutate")
    assert.equal(mutate.args.ns, "llm-deepseek")
    assert.equal(mutate.args.expectedRevision, 1)
    assert.deepEqual(mutate.args.ops, [{
      op: "set",
      path: ["models"],
      value: [
        { id: "deepseek-v4", name: "DeepSeek V4", reasoning: { efforts: ["high"] } },
        { id: "deepseek-v5", name: "deepseek-v5", inputModalities: ["text", "image"] },
      ],
    }])
    const body = await r.json()
    assert.deepEqual(body.providers.find((p) => p.provider === "deepseek-official").models.map((m) => m.id), ["deepseek-v4", "deepseek-v5"])

    const removed = await authed("/dsh-link/mobile/providers/models", { provider: "zai", remove: ["glm-5.3"], add: [{ id: "glm-6", inputModalities: ["text"] }] })
    assert.equal(removed.status, 200)
    const zaiOps = gw.calls.filter((c) => c.name === "settings/mutate").at(-1).args.ops
    assert.deepEqual(zaiOps, [{ op: "set", path: ["providers", "zai", "models"], value: [{ id: "glm-6", name: "glm-6", input: ["text"] }] }])
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("POST providers/models 拒绝继承目录、未知供应商与清空", async () => {
  const gw = fakeGateway()
  bindLocalRpcRuntime({ invoke: gw.invoke })
  try {
    const inherited = await authed("/dsh-link/mobile/providers/models", { provider: "stepfun", add: [{ id: "x" }] })
    assert.equal(inherited.status, 409)
    assert.equal((await inherited.json()).code, "models-inherited")
    const unknown = await authed("/dsh-link/mobile/providers/models", { provider: "deepseek-account", add: [{ id: "x" }] })
    assert.equal(unknown.status, 404)
    const empty = await authed("/dsh-link/mobile/providers/models", { provider: "zai", remove: ["glm-5.3"] })
    assert.equal(empty.status, 400)
    const badId = await authed("/dsh-link/mobile/providers/models", { provider: "zai", add: [{ id: "has space" }] })
    assert.equal(badId.status, 400)
    assert.equal(gw.calls.some((c) => c.name === "settings/mutate"), false)
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("POST providers/credential 单向写密钥，缺 apiKeyEnv 时先记录引用", async () => {
  const gw = fakeGateway()
  bindLocalRpcRuntime({ invoke: gw.invoke })
  try {
    const r = await authed("/dsh-link/mobile/providers/credential", { provider: "stepfun", apiKey: "  sk-step-secret  " })
    assert.equal(r.status, 200)
    const text = await r.text()
    assert.equal(text.includes("sk-step-secret"), false, "响应不得回显密钥")
    const mutate = gw.calls.find((c) => c.name === "settings/mutate")
    assert.deepEqual(mutate.args.ops, [{ op: "set", path: ["providers", "stepfun", "apiKeyEnv"], value: "STEPFUN_API_KEY" }])
    assert.equal(gw.secrets.get("STEPFUN_API_KEY"), "sk-step-secret")
    assert.equal(JSON.parse(text).providers.find((p) => p.provider === "stepfun").credential.configured, true)

    const readOnly = await authed("/dsh-link/mobile/providers/credential", { provider: "deepseek-official", apiKey: "sk-x" })
    assert.equal(readOnly.status, 409)
    assert.equal((await readOnly.json()).code, "credential-read-only")

    const bad = await authed("/dsh-link/mobile/providers/credential", { provider: "zai", apiKey: "ZAI_API_KEY=sk-1" })
    assert.equal(bad.status, 400)
    assert.equal(gw.secrets.has("ZAI_API_KEY"), false)
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("POST providers/add 把目录供应商落成 profile 并写密钥", async () => {
  const gw = fakeGateway()
  bindLocalRpcRuntime({ invoke: gw.invoke })
  try {
    const r = await authed("/dsh-link/mobile/providers/add", { provider: "openai", apiKey: "sk-openai" })
    assert.equal(r.status, 200)
    const mutate = gw.calls.find((c) => c.name === "settings/mutate")
    assert.deepEqual(mutate.args.ops, [{ op: "set", path: ["providers", "openai"], value: { apiKeyEnv: "OPENAI_API_KEY" } }])
    assert.equal(gw.secrets.get("OPENAI_API_KEY"), "sk-openai")
    const body = await r.json()
    assert.ok(body.providers.some((p) => p.provider === "openai"))
    assert.equal(body.addable.length, 0)

    const again = await authed("/dsh-link/mobile/providers/add", { provider: "zai" })
    assert.equal(again.status, 404)
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("POST providers/discover 用已存 profile 的路由字段，不接受手机传 baseURL", async () => {
  const gw = fakeGateway()
  bindLocalRpcRuntime({ invoke: gw.invoke })
  try {
    const r = await authed("/dsh-link/mobile/providers/discover", { provider: "zai", baseURL: "https://evil.example" })
    assert.equal(r.status, 200)
    const body = await r.json()
    assert.deepEqual(body.models.map((m) => m.id), ["glm-6"])
    assert.deepEqual(body.models[0].inputModalities, ["text", "image"])
    const call = gw.calls.find((c) => c.name === "llm/discoverModels")
    assert.deepEqual(call.args, { settingsNs: "llm-pi-ai", request: { provider: "zai", baseURL: "https://open.bigmodel.cn/api/paas/v4" } })
  } finally {
    unbindLocalRpcRuntime()
  }
})

test("设置并发冲突映射为 409 conflict", async () => {
  const gw = fakeGateway()
  const invoke = async (req) => {
    if (req.namespace === "settings" && req.method === "mutate") {
      throw Object.assign(new Error("revision mismatch"), { code: "settings/conflict" })
    }
    return gw.invoke(req)
  }
  bindLocalRpcRuntime({ invoke })
  try {
    const r = await authed("/dsh-link/mobile/providers/models", { provider: "zai", add: [{ id: "glm-7" }] })
    assert.equal(r.status, 409)
    assert.equal((await r.json()).code, "conflict")
  } finally {
    unbindLocalRpcRuntime()
  }
})

test.after(() => {
  for (const fn of effects) try { fn() } catch {}
  upstream.close()
  rmSync(TMP, { recursive: true, force: true })
})
