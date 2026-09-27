/**
 * 手机「模型」页：DeepSeek 账户余额 + 供应商目录（对齐桌面版 settings-models）。
 *
 * 写入只由插件自己拼 settings.mutate ops（固定路径：供应商 profile 的 models / apiKeyEnv），
 * 手机只能传模型条目与密钥；baseURL / api 等路由字段不开放，避免把请求改指向他处。
 * API 密钥单向写入 credentials.set，绝不回显、不落日志。
 */
import { callLocalRpc, LocalRpcError } from "./local-rpc.js"

export const ACCOUNT_PROVIDER = "deepseek-account"
const OFFICIAL_PROVIDER = "deepseek-official"
const LEGAL_API_KEY = /^[\x21-\x7E]+$/
const ENV_LINE = /^[A-Z][A-Z0-9_]*=[^=]/
const MAX_API_KEY_LENGTH = 1024
const MODEL_ID_PATTERN = /^[\x21-\x7E]{1,200}$/
const MAX_MODEL_NAME_LENGTH = 200
const MAX_MODELS_PER_WRITE = 64
const BODY_LIMIT = 64 * 1024
const DSH_CLIENT_VERSION_FALLBACK = "0.1.7"

export function deriveKeyRef(provider) {
  return `${String(provider).toUpperCase().replace(/[^A-Z0-9]+/g, "_")}_API_KEY`
}

/** @returns {string|null} 不合法原因；null = 可写。与桌面 apiKeyFailure 同规则。 */
export function apiKeyProblem(raw) {
  if (typeof raw !== "string") return "API 密钥必须是字符串"
  const value = raw.trim()
  if (!value) return "API 密钥不能为空"
  if (value.length > MAX_API_KEY_LENGTH) return "API 密钥过长"
  const first = value[0]
  const quoted = (first === "\"" || first === "'" || first === "`") && value.length > 1 && value.endsWith(first)
  if (ENV_LINE.test(value) || quoted || !LEGAL_API_KEY.test(value)) return "API 密钥包含不允许的字符"
  return null
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value)
}

function getPath(root, path) {
  let cur = root
  for (const key of path) {
    if (!isObject(cur) || !Object.prototype.hasOwnProperty.call(cur, key)) return undefined
    cur = cur[key]
  }
  return cur
}

function positiveInt(value) {
  return Number.isInteger(value) && value > 0 ? value : null
}

/** 桌面 joinProviderDirectory：声明目录 + 仅注册的路由；deepseek-account / official 置顶。 */
export function joinProviderDirectory(registered, directory) {
  const reg = Array.isArray(registered) ? registered : []
  const dir = Array.isArray(directory) ? directory : []
  const active = new Set(reg.map((p) => p?.id))
  const declared = new Set(dir.map((e) => e?.provider))
  const rows = dir
    .filter((e) => typeof e?.provider === "string" && e.provider)
    .map((e) => ({
      provider: e.provider,
      displayName: String(e.displayName ?? e.provider),
      settingsNs: String(e.settingsNs ?? ""),
      settingsPath: Array.isArray(e.settingsPath) ? e.settingsPath.map(String) : [],
      active: active.has(e.provider),
      declared: e.declared === true,
    }))
  for (const p of reg) {
    if (typeof p?.id !== "string" || !p.id || declared.has(p.id)) continue
    rows.push({ provider: p.id, displayName: String(p.name ?? p.id), settingsNs: "", settingsPath: [], active: true, declared: false })
  }
  const rank = (id) => (id === ACCOUNT_PROVIDER ? 0 : id === OFFICIAL_PROVIDER ? 1 : 2)
  return rows.sort((a, b) => rank(a.provider) - rank(b.provider))
}

function projectModel(model) {
  const id = String(model?.id ?? "")
  return {
    id,
    name: String(model?.name ?? "") || id,
    contextWindow: positiveInt(model?.contextWindow),
    maxTokens: positiveInt(model?.maxTokens),
  }
}

/**
 * 把目录 / 设置 / 目录模型 / 凭据状态拼成手机行。
 * modelsEditable：只有 profile 已显式带 models 数组时才允许手机增删，
 * 否则追加会把适配器默认目录整体替换掉（桌面「恢复默认」才能找回）。
 */
export function projectProviderDirectory({ registered, directory, settings, catalog, credentials }) {
  const namespaces = new Map((settings?.namespaces ?? []).map((ns) => [ns.ns, ns]))
  const groups = new Map((catalog?.groups ?? []).map((g) => [g.id ?? g.provider, g]))
  const creds = isObject(credentials) ? credentials : {}
  const providers = []
  const addable = []
  for (const entry of joinProviderDirectory(registered, directory)) {
    const group = groups.get(entry.provider)
    const catalogModels = (group?.models ?? []).map(projectModel).filter((m) => m.id)
    if (entry.provider === ACCOUNT_PROVIDER) {
      if (catalogModels.length === 0) continue
      providers.push({
        provider: entry.provider,
        displayName: entry.displayName,
        kind: "account",
        active: entry.active,
        custom: false,
        keyRef: null,
        credential: null,
        models: catalogModels,
        modelsEditable: false,
        canDiscover: false,
      })
      continue
    }
    const ns = namespaces.get(entry.settingsNs)
    const configured = ns !== undefined && (entry.settingsPath.length === 0 || getPath(ns.value, entry.settingsPath) !== undefined)
    if (!configured) {
      if (ns !== undefined && entry.settingsPath.length > 0) {
        addable.push({ provider: entry.provider, displayName: entry.displayName })
      }
      continue
    }
    const profile = entry.settingsPath.length === 0 ? ns?.value : getPath(ns?.value, entry.settingsPath)
    const storedModels = Array.isArray(profile?.models) ? profile.models : null
    const keyRef = typeof profile?.apiKeyEnv === "string" && profile.apiKeyEnv ? profile.apiKeyEnv : null
    const cred = creds[keyRef ?? deriveKeyRef(entry.provider)]
    providers.push({
      provider: entry.provider,
      displayName: entry.displayName,
      kind: "api",
      active: entry.active,
      custom: entry.declared,
      keyRef: keyRef ?? deriveKeyRef(entry.provider),
      credential: isObject(cred)
        ? { configured: cred.configured === true, writable: cred.writable === true, source: typeof cred.source === "string" ? cred.source : null }
        : null,
      models: storedModels ? storedModels.map(projectModel).filter((m) => m.id) : catalogModels,
      modelsEditable: storedModels !== null && Boolean(entry.settingsNs),
      canDiscover: Boolean(entry.settingsNs),
    })
  }
  return { writable: settings?.writable !== false, providers, addable }
}

async function loadDirectory(targetPort) {
  const [registered, directory, settings, catalog] = await Promise.all([
    callLocalRpc(targetPort, "llm.listProviders", {}),
    callLocalRpc(targetPort, "llm.listConfigurableProviders", {}),
    callLocalRpc(targetPort, "settings.describe", {}),
    callLocalRpc(targetPort, "llm.models", {}).catch(() => null),
  ])
  const draft = projectProviderDirectory({ registered, directory, settings, catalog, credentials: {} })
  const refs = [...new Set(draft.providers.filter((p) => p.keyRef).map((p) => p.keyRef))]
  let credentials = {}
  if (refs.length > 0) {
    try {
      credentials = await callLocalRpc(targetPort, "credentials.describe", { refs })
    } catch {
      credentials = {}
    }
  }
  const view = projectProviderDirectory({ registered, directory, settings, catalog, credentials })
  const entries = new Map(joinProviderDirectory(registered, directory).map((e) => [e.provider, e]))
  const namespaces = new Map((settings?.namespaces ?? []).map((ns) => [ns.ns, ns]))
  return { view, entries, namespaces, credentials }
}

function hostDshVersion() {
  const fromEnv = String(process.env.DSH_CLIENT_VERSION ?? "").trim()
  return fromEnv || DSH_CLIENT_VERSION_FALLBACK
}

/** account.getBalance 三态 → 手机：ready / signed-out / failed / unavailable（旧 DSH 无该方法）。 */
export function projectBalance(value) {
  if (value === null || value === undefined) return { status: "signed-out", wallets: [], bonusWallets: [] }
  if (value?.status !== "ready") return { status: "failed", wallets: [], bonusWallets: [] }
  const wallet = (w) => ({ currency: String(w?.currency ?? ""), balance: String(w?.balance ?? "") })
  return {
    status: "ready",
    wallets: (Array.isArray(value.value) ? value.value : []).map(wallet).filter((w) => w.currency),
    bonusWallets: (Array.isArray(value.bonusWallets) ? value.bonusWallets : []).map(wallet).filter((w) => w.currency),
  }
}

function parseModelDraft(raw) {
  const id = String(raw?.id ?? "").trim()
  if (!MODEL_ID_PATTERN.test(id)) return null
  const name = String(raw?.name ?? "").trim().slice(0, MAX_MODEL_NAME_LENGTH)
  const model = { id, name: name || id }
  const contextWindow = positiveInt(raw?.contextWindow)
  const maxTokens = positiveInt(raw?.maxTokens)
  if (contextWindow) model.contextWindow = contextWindow
  if (maxTokens) model.maxTokens = maxTokens
  const input = Array.isArray(raw?.inputModalities)
    ? raw.inputModalities.filter((m) => m === "text" || m === "image")
    : []
  if (input.length > 0) model.input = [...new Set(input)]
  return model
}

/** pi-ai profile 用 `input`，DeepSeek 根 profile 用 `inputModalities`。 */
function modelForLayout(model, rootLayout) {
  if (!model.input) return model
  if (!rootLayout) return model
  const { input, ...rest } = model
  return { ...rest, inputModalities: input }
}

class MobileModelsError extends Error {
  constructor(status, message, code) {
    super(message)
    this.status = status
    this.code = code
  }
}

function rpcFailure(error, fallback) {
  if (error instanceof MobileModelsError) return error
  if (error instanceof LocalRpcError) {
    if (error.code === "settings/conflict") return new MobileModelsError(409, "设置已在电脑端被修改，请刷新后重试", "conflict")
    return new MobileModelsError(400, error.message || fallback, error.code)
  }
  return new MobileModelsError(502, fallback, "unavailable")
}

function editableEntry(loaded, provider) {
  const entry = loaded.entries.get(provider)
  if (!entry || provider === ACCOUNT_PROVIDER) throw new MobileModelsError(404, "供应商不存在或不可在手机端修改", "unknown-provider")
  if (!loaded.view.writable) throw new MobileModelsError(409, "电脑端设置为只读", "read-only")
  const ns = loaded.namespaces.get(entry.settingsNs)
  if (!entry.settingsNs || !ns) throw new MobileModelsError(409, "该供应商没有可写的设置", "not-configurable")
  return { entry, ns }
}

async function writeModels(targetPort, loaded, provider, body) {
  const { entry, ns } = editableEntry(loaded, provider)
  const row = loaded.view.providers.find((p) => p.provider === provider)
  if (!row) throw new MobileModelsError(409, "请先在电脑端或手机端添加该供应商", "not-configured")
  if (!row.modelsEditable) throw new MobileModelsError(409, "该供应商正在使用默认模型目录，请在电脑端自定义", "models-inherited")
  const profilePath = entry.settingsPath
  const profile = profilePath.length === 0 ? ns.value : getPath(ns.value, profilePath)
  const current = Array.isArray(profile?.models) ? profile.models : []
  const remove = new Set((Array.isArray(body.remove) ? body.remove : []).map((id) => String(id ?? "").trim()).filter(Boolean))
  const addRaw = Array.isArray(body.add) ? body.add : []
  if (addRaw.length > MAX_MODELS_PER_WRITE) throw new MobileModelsError(400, "一次添加的模型过多", "too-many")
  const add = []
  for (const raw of addRaw) {
    const model = parseModelDraft(raw)
    if (!model) throw new MobileModelsError(400, "模型 ID 无效", "invalid-model")
    add.push(modelForLayout(model, profilePath.length === 0))
  }
  if (remove.size === 0 && add.length === 0) throw new MobileModelsError(400, "没有要修改的模型", "empty")
  const kept = current.filter((m) => !remove.has(String(m?.id ?? "")))
  const seen = new Set(kept.map((m) => String(m?.id ?? "")))
  for (const model of add) {
    if (seen.has(model.id)) continue
    seen.add(model.id)
    kept.push(model)
  }
  if (kept.length === 0) throw new MobileModelsError(400, "至少保留一个模型", "empty-models")
  await callLocalRpc(targetPort, "settings.mutate", {
    ns: entry.settingsNs,
    ops: [{ op: "set", path: [...profilePath, "models"], value: kept }],
    expectedRevision: ns.revision,
  })
}

async function writeCredential(targetPort, loaded, provider, apiKey, { materialize = false } = {}) {
  const problem = apiKeyProblem(apiKey)
  if (problem) throw new MobileModelsError(400, problem, "invalid-key")
  const { entry, ns } = editableEntry(loaded, provider)
  const profilePath = entry.settingsPath
  const profile = profilePath.length === 0 ? ns.value : getPath(ns.value, profilePath)
  const named = typeof profile?.apiKeyEnv === "string" && profile.apiKeyEnv ? profile.apiKeyEnv : null
  const keyRef = named ?? deriveKeyRef(provider)
  const cred = loaded.credentials?.[keyRef]
  if (isObject(cred) && cred.writable === false) {
    throw new MobileModelsError(409, "该密钥来自环境变量等只读来源，请在电脑端修改", "credential-read-only")
  }
  const ops = []
  if (materialize && profile === undefined) {
    ops.push({ op: "set", path: [...profilePath], value: { apiKeyEnv: keyRef } })
  } else if (!named && profilePath.length > 0) {
    ops.push({ op: "set", path: [...profilePath, "apiKeyEnv"], value: keyRef })
  }
  if (ops.length > 0) {
    await callLocalRpc(targetPort, "settings.mutate", { ns: entry.settingsNs, ops, expectedRevision: ns.revision })
  }
  await callLocalRpc(targetPort, "credentials.set", { ref: keyRef, value: apiKey.trim() })
}

async function addCatalogProvider(targetPort, loaded, provider, apiKey) {
  if (!loaded.view.addable.some((p) => p.provider === provider)) {
    throw new MobileModelsError(404, "该供应商不在可添加目录中", "unknown-provider")
  }
  if (apiKey !== undefined && apiKey !== null && String(apiKey).length > 0) {
    await writeCredential(targetPort, loaded, provider, String(apiKey), { materialize: true })
    return
  }
  const { entry, ns } = editableEntry(loaded, provider)
  await callLocalRpc(targetPort, "settings.mutate", {
    ns: entry.settingsNs,
    ops: [{ op: "set", path: [...entry.settingsPath], value: {} }],
    expectedRevision: ns.revision,
  })
}

async function discoverModels(targetPort, loaded, provider) {
  const { entry, ns } = editableEntry(loaded, provider)
  const profile = entry.settingsPath.length === 0 ? ns.value : getPath(ns.value, entry.settingsPath)
  const request = { provider }
  if (typeof profile?.baseURL === "string" && profile.baseURL) request.baseURL = profile.baseURL
  if (typeof profile?.api === "string" && profile.api) request.api = profile.api
  const found = await callLocalRpc(targetPort, "llm.discoverModels", { settingsNs: entry.settingsNs, request })
  return (Array.isArray(found) ? found : [])
    .map((m) => ({
      ...projectModel(m),
      inputModalities: Array.isArray(m?.inputModalities) ? m.inputModalities.filter((x) => x === "text" || x === "image") : [],
    }))
    .filter((m) => m.id)
    .slice(0, 500)
}

/**
 * @returns {Promise<boolean>} true = 本模块已响应。
 */
export async function handleMobileModelsApi(req, res, targetPort, state, device, pathname, rt, deps) {
  const { json, readAuthorizedJson, runMobileDeviceMutation, mobileMutationWasRevoked, respondDeviceRevoked } = deps

  if (req.method === "GET" && pathname === "/dsh-link/mobile/balance") {
    const client = {
      version: hostDshVersion(),
      locale: String(new URL(req.url ?? "/", "http://x").searchParams.get("locale") ?? "zh-CN").slice(0, 16) || "zh-CN",
      timezoneOffsetSeconds: -new Date().getTimezoneOffset() * 60,
    }
    try {
      const value = await callLocalRpc(targetPort, "account.getBalance", { client })
      json(res, 200, { version: 1, ...projectBalance(value) })
    } catch {
      json(res, 200, { version: 1, status: "unavailable", wallets: [], bonusWallets: [] })
    }
    return true
  }

  if (req.method === "GET" && pathname === "/dsh-link/mobile/providers") {
    const { view } = await loadDirectory(targetPort)
    json(res, 200, { version: 1, ...view })
    return true
  }

  const writes = {
    "/dsh-link/mobile/providers/models": (loaded, body) => writeModels(targetPort, loaded, body.provider, body),
    "/dsh-link/mobile/providers/credential": (loaded, body) => writeCredential(targetPort, loaded, body.provider, body.apiKey),
    "/dsh-link/mobile/providers/add": (loaded, body) => addCatalogProvider(targetPort, loaded, body.provider, body.apiKey),
  }

  if (req.method === "POST" && pathname === "/dsh-link/mobile/providers/discover") {
    const body = await readAuthorizedJson(req, res, state, device, BODY_LIMIT)
    if (!body) return true
    const provider = String(body.provider ?? "").trim()
    if (!provider) {
      json(res, 400, { error: "缺少供应商" })
      return true
    }
    try {
      const loaded = await loadDirectory(targetPort)
      const models = await discoverModels(targetPort, loaded, provider)
      json(res, 200, { version: 1, provider, models })
    } catch (error) {
      const failure = rpcFailure(error, "获取模型列表失败")
      json(res, failure.status, { error: failure.message, code: failure.code })
    }
    return true
  }

  const write = req.method === "POST" ? writes[pathname] : undefined
  if (!write) return false
  const body = await readAuthorizedJson(req, res, state, device, BODY_LIMIT)
  if (!body) return true
  const provider = String(body.provider ?? "").trim()
  if (!provider) {
    json(res, 400, { error: "缺少供应商" })
    return true
  }
  let result
  try {
    result = await runMobileDeviceMutation(rt, state, device, async () => {
      const loaded = await loadDirectory(targetPort)
      await write(loaded, { ...body, provider })
      return (await loadDirectory(targetPort)).view
    })
  } catch (error) {
    const failure = rpcFailure(error, "保存失败")
    json(res, failure.status, { error: failure.message, code: failure.code })
    return true
  }
  if (mobileMutationWasRevoked(result)) {
    respondDeviceRevoked(res)
    return true
  }
  json(res, 200, { version: 1, ok: true, ...result })
  return true
}
