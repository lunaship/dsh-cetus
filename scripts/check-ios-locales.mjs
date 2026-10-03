import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

const spec =
  /^%(?:(\d+)\$)?([#0\-+ ']*)?(?:\d+|\*)?(?:\.(?:\d+|\*))?(hh|ll|h|l|q|L|z|t|j)?([@dDuUxXoOfeEgGcCsSpaA%])/

export function formatTypes(text) {
  const types = new Map()
  let implicit = 0
  let previous = 0
  let i = 0
  while (i < text.length) {
    if (text[i] !== "%") {
      i += 1
      continue
    }
    const match = spec.exec(text.slice(i))
    if (!match) throw new Error(`无法解析格式符：${text.slice(i)}`)
    const explicit = match[1]
    const flags = match[2] ?? ""
    const length = match[3] ?? ""
    const conv = match[4]
    if (conv !== "%") {
      const type = `${length}${conv}`
      let index
      if (flags.includes("<")) index = previous
      else if (explicit) index = Number(explicit)
      else index = implicit + 1
      if (!explicit && !flags.includes("<")) implicit = index
      if (!Number.isInteger(index) || index < 1) throw new Error(`格式符下标无效：${text}`)
      const existing = types.get(index)
      if (existing && existing !== type) throw new Error(`同一参数下标类型冲突：${text}`)
      types.set(index, type)
      previous = index
    }
    i += match[0].length
  }
  return types
}

function stringUnits(localization) {
  if (!localization || typeof localization !== "object") return null
  const found = {}
  function walk(node, path) {
    if (!node || typeof node !== "object") return
    if (node.stringUnit && typeof node.stringUnit.value === "string") found[path] = node.stringUnit.value
    const variations = node.variations
    if (!variations || typeof variations !== "object") return
    for (const [kind, group] of Object.entries(variations)) {
      if (!group || typeof group !== "object") continue
      for (const [name, child] of Object.entries(group)) {
        walk(child, path ? `${path}/${kind}=${name}` : `${kind}=${name}`)
      }
    }
  }
  walk(localization, "")
  return Object.keys(found).length ? found : null
}

function sameTypes(left, right) {
  if (left.size !== right.size) return false
  for (const [index, type] of left) {
    if (right.get(index) !== type) return false
  }
  return true
}

function describe(types) {
  return [...types.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([index, type]) => `${index}:${type}`)
    .join(",")
}

export function checkCatalog(catalog) {
  const problems = []
  if (catalog.sourceLanguage !== "en") problems.push("sourceLanguage 必须是 en")
  const strings = catalog.strings ?? {}
  const keys = Object.keys(strings).sort()
  if (keys.length === 0) problems.push("词条为空")
  for (const key of keys) {
    const entry = strings[key] ?? {}
    const localizations = entry.localizations ?? {}
    if (entry.substitutions || localizations.en?.substitutions || localizations["zh-Hans"]?.substitutions) {
      problems.push(`${key} 含 substitutions，检查脚本还不能比对`)
    }
    const en = stringUnits(localizations.en)
    const zh = stringUnits(localizations["zh-Hans"])
    if (!en) problems.push(`${key} 缺少英文`)
    if (!zh) problems.push(`${key} 缺少简体中文`)
    if (!en || !zh) continue
    const paths = new Set([...Object.keys(en), ...Object.keys(zh)])
    for (const path of [...paths].sort()) {
      const label = path ? `${key} ${path}` : key
      const enValue = en[path]
      const zhValue = zh[path]
      if (enValue == null) problems.push(`${label} 缺少英文变体`)
      if (zhValue == null) problems.push(`${label} 缺少简体中文变体`)
      if (enValue == null || zhValue == null) continue
      if (enValue.length === 0) problems.push(`${label} 英文为空`)
      if (zhValue.length === 0) problems.push(`${label} 简体中文为空`)
      try {
        const enTypes = formatTypes(enValue)
        const zhTypes = formatTypes(zhValue)
        if (!sameTypes(enTypes, zhTypes)) {
          problems.push(`${label} 格式符类型不一致 en=${describe(enTypes)} zh=${describe(zhTypes)}`)
        }
      } catch (error) {
        problems.push(`${label} ${error.message}`)
      }
    }
  }
  return problems
}

function isMain() {
  const entry = process.argv[1]
  if (!entry) return false
  return fileURLToPath(import.meta.url) === fileURLToPath(new URL(entry, "file:"))
}

if (isMain()) {
  const catalogPath = fileURLToPath(new URL("../apps/ios/App/Resources/Localizable.xcstrings", import.meta.url))
  const catalog = JSON.parse(readFileSync(catalogPath, "utf8"))
  const problems = checkCatalog(catalog)
  if (problems.length) {
    console.error(problems.join("\n"))
    process.exit(1)
  }
}
