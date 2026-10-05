import { readdirSync, readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const SCENE = /^(?:\d|wide_)[A-Za-z0-9_]+$/
const APPEARANCES = ["light", "dark"]
const LANGUAGES = ["zh", "en"]
const SIZES = ["default", "large"]
const ACCESS = ["reduce-transparency", "increase-contrast"]
const COMPONENT = ["light", "dark", "long", "disabled", "en", "large", ...ACCESS]
const FULL = new Set([
  "ChatSheetSnapshotTests.swift",
  "NewTaskSnapshotTests.swift",
  "ReviewSnapshotTests.swift",
  "SettingsSnapshotTests.swift",
  "TrajectorySnapshotTests.swift",
])
const APPEND = new Set([
  "InboxSnapshotTests.swift",
  "PairingSnapshotTests.swift",
  "ChatSnapshotTests.swift",
  "ComposerSnapshotTests.swift",
  "StatusSlotSnapshotTests.swift",
  "WideSnapshotTests.swift",
])
const WELCOME = [
  "testWelcomeLight.light",
  "Snapshot_1_2_welcome_light_zh.reduce-transparency",
  "Snapshot_1_2_welcome_light_zh.increase-contrast",
  "Snapshot_1_2_welcome_a11y_light_en.large",
  "Snapshot_1_2_welcome_a11y_dark_en.large",
]
const HELPERS = new Set(["matrix", "fixture", "oneScene", "accessibility", "renderAccessibility", "shot", "render", "scannerMatrix"])

export function requiredFor(fileName, source) {
  if (fileName === "WelcomeSnapshotTests.swift") return WELCOME.slice()
  if (fileName === "ComponentSnapshotTests.swift") return componentRequired(source)
  if (fileName === "TranscriptSnapshotTests.swift") return []
  if (FULL.has(fileName)) return sceneRequired(source, true)
  if (APPEND.has(fileName)) return sceneRequired(source, false)
  return []
}

export function missingDeclarations(files) {
  const missing = []
  for (const file of files) {
    const declared = declarations(file.source)
    for (const item of requiredFor(file.name, file.source)) {
      if (!declared.has(item)) missing.push(file.name + ": " + item)
    }
  }
  return missing.sort()
}

export function declarations(source) {
  const file = parse(source)
  const found = new Set()
  for (const call of file.calls) {
    if (call.name !== "assertSnapshot") continue
    for (const item of pairedDeclarations(call, file)) found.add(item)
  }
  return found
}

function pairedDeclarations(call, file) {
  const names = resolveNamed(call, file)
  const tests = resolveTests(call, file)
  const pairs = callPairs(call, file)
  if (pairs.length === 0) {
    const found = []
    for (const test of tests) for (const name of names) found.push(test + "." + name)
    return found
  }
  return pairs
}

function callPairs(call, file) {
  const owner = ownerOf(file, call.at)
  if (!owner) return []
  const roots = rootsForAssertion(call, file)
  const calls = []
  for (const root of roots) calls.push(...descendCall(root, file, new Set()))
  if (calls.length === 0) return []
  const found = []
  for (const item of calls) {
    const named = withFixedTest(item, call)
    const names = variantForCall(named)
    const tests = testsForCall(named, index(file.functions).get(named.name) || owner, file)
    for (const test of tests) for (const name of names) found.push(test + "." + name)
  }
  return found
}

function rootsForAssertion(call, file) {
  const tests = callingTests(ownerOf(file, call.at), file, new Set())
  const found = []
  for (const fn of tests) for (const item of file.calls) if (fn.start <= item.at && item.at < fn.end && item.name !== "assertSnapshot") found.push(item)
  return found
}

function callingTests(fn, file, seen) {
  if (!fn || seen.has(fn.name)) return []
  seen.add(fn.name)
  if (fn.name.startsWith("test")) return [fn]
  const callers = file.calls.filter((item) => item.name === fn.name).map((item) => ownerOf(file, item.at)).filter(Boolean)
  const found = []
  for (const caller of callers) found.push(...callingTests(caller, file, seen))
  return found
}
function descendCall(call, file, stack) {
  const mark = call.name + ":" + call.at
  if (stack.has(mark)) return []
  stack.add(mark)
  const fn = index(file.functions).get(call.name)
  if (!fn || fn.text.includes("assertSnapshot(")) return HELPERS.has(call.name) || call.name === "assertSnapshot" || (fn && fn.text.includes("assertSnapshot(")) ? [call] : []
  const inner = file.calls.filter((item) => HELPERS.has(item.name) && item.name !== call.name && fn.start <= item.at && item.at < fn.end && callMatchesBranch(item, call, file))
  if (inner.length === 0) return HELPERS.has(call.name) || call.name === "assertSnapshot" ? [call] : []
  const found = []
  for (const item of inner) found.push(...descendCall(bindCall(item, call, file), file, stack))
  return found
}

function bindCall(inner, outer, file) {
  const fn = index(file.functions).get(inner.name)
  const args = inner.args.slice()
  const labeled = new Map(inner.labeled)
  if (fn) {
    fn.params.forEach((param, position) => {
      const passed = passedArgument(outer, index(file.functions).get(outer.name), paramIndex(index(file.functions).get(outer.name), param.internal))
      const current = passedArgument(inner, fn, position)
      if (passed && current && current.kind === "ident" && current.value === param.internal) {
        if (param.external === "_") args[position] = passed
        else labeled.set(param.external, passed)
      }
    })
  }
  return { name: inner.name, at: inner.at, args, labeled, originAt: outer.originAt || outer.at }
}

function paramIndex(fn, name) {
  if (!fn) return -1
  return fn.params.findIndex((param) => param.internal === name)
}
function withFixedTest(item, call) {
  const expr = call.labeled.get("testName")
  if (!expr || expr.kind !== "string" || !expr.parts) return item
  const labeled = new Map(item.labeled)
  labeled.set("testName", expr)
  return { ...item, labeled }
}
function variantForCall(call) {
  const named = call.labeled.get("named") || call.labeled.get("name")
  if (named && named.kind === "string") return [named.value]
  if (named && named.kind === "ident") return [named.value]
  const access = call.labeled.get("accessibility")
  const accessName = access && (access.kind === "member" || access.kind === "ident") ? access.value : ""
  const reduce = literalBool(call.labeled.get("reduceTransparency")) === true || accessName === "reduceTransparency"
  const contrast = literalBool(call.labeled.get("increaseContrast")) === true || accessName === "increaseContrast"
  const large = literalBool(call.labeled.get("large"))
  if (reduce) return ["reduce-transparency"]
  if (contrast) return ["increase-contrast"]
  if (large === true) return ["large"]
  if (large === false) return ["default"]
  if (call.labeled.has("large")) return ["default", "large"]
  return ["default"]
}

function testsForCall(call, owner, file) {
  const fixed = fixedTests(call, file)
  if (fixed.length > 0) return fixed
  const fn = index(file.functions).get(call.name) || owner
  const scene = sceneArg(call, fn)
  const scenes = sceneValues(scene, file, new Set(), { ...call, at: call.originAt || call.at })
  const appearance = styleValues(call.labeled.get("appearance"), file)
  const language = languageValues(call.labeled.get("language"), file)
  const found = []
  for (const item of scenes) for (const look of appearance) for (const tongue of language) found.push(snap(item, look, tongue))
  return found
}

function fixedTests(call, file) {
  const expr = call.labeled.get("testName")
  if (!expr) return []
  const owner = ownerOf(file, call.at)
  return testsOf(expr, file, new Set(), owner).filter((value) => value.startsWith("Snapshot_"))
}
function literalBool(expr) {
  if (!expr || expr.kind !== "ident") return null
  if (expr.value === "true") return true
  if (expr.value === "false") return false
  return null
}

function styleValues(expr, file) {
  if (expr && (expr.kind === "ident" || expr.kind === "member") && expr.value !== "appearance") {
    if (expr.value.includes("dark")) return ["dark"]
    if (expr.value.includes("light")) return ["light"]
  }
  return APPEARANCES
}

function languageValues(expr, file) {
  if (!expr) return ["zh"]
  if (expr.kind === "string") return [expr.value === "en" ? "en" : "zh"]
  if (expr.kind === "ident" && expr.value !== "language") return [expr.value === "en" ? "en" : "zh"]
  return LANGUAGES
}
function sceneRequired(source, full) {
  const required = []
  for (const scene of sceneIds(parse(source))) {
    if (full) {
      for (const appearance of APPEARANCES) {
        for (const language of LANGUAGES) {
          for (const named of SIZES) required.push(snap(scene, appearance, language) + "." + named)
        }
      }
    }
    for (const named of ACCESS) required.push(snap(scene, "light", "zh") + "." + named)
  }
  return required
}

function componentRequired(source) {
  const required = []
  for (const fn of parse(source).functions) {
    if (!fn.name.startsWith("test")) continue
    for (const named of COMPONENT) required.push(fn.name + "." + named)
  }
  return required
}

function snap(scene, appearance, language) {
  return "Snapshot_" + scene + "_" + appearance + "_" + language
}

function sceneIds(file) {
  const found = new Set()
  const byName = index(file.functions)
  for (const call of file.calls) {
    if (!HELPERS.has(call.name)) continue
    const arg = sceneArg(call, byName.get(call.name))
    for (const scene of sceneValues(arg, file)) if (SCENE.test(scene)) found.add(scene)
  }
  return Array.from(found).sort()
}

function sceneValues(expr, file, stack, origin) {
  const seen = stack || new Set()
  if (!expr) return []
  if (expr.kind === "string") {
    if (!expr.parts) return [expr.value]
    if (expr.parts[0] && expr.parts[0].value === "1_6_fail_" && expr.parts[1] && expr.parts[1].kind === "expr") return failureScenes(file, origin)
    return []
  }
  if (expr.kind === "member" && expr.value === "snapshotScene") return statusScenes(file)
  if (expr.kind === "ident") return sceneIdent(expr.value, file, seen, origin)
  return []
}

function callMatchesBranch(inner, outer, file) {
  const branch = conditionBranch(file.source, inner.at)
  if (!branch) return true
  if (branch.kind === "nil") {
    const hint = outer.labeled.get("hint")
    const isNil = Boolean(hint && hint.kind === "ident" && hint.value === "nil")
    return branch.equal ? isNil : !isNil
  }
  return true
}

function conditionBranch(source, at) {
  const before = source.slice(Math.max(0, at - 700), at)
  const scene = before.match(/if scene == "([A-Za-z][A-Za-z0-9]*)"/g)
  const nil = before.match(/if hint == nil/g)
  const sceneAt = scene ? before.lastIndexOf(scene[scene.length - 1]) : -1
  const nilAt = nil ? before.lastIndexOf(nil[nil.length - 1]) : -1
  const atMatch = Math.max(sceneAt, nilAt)
  if (atMatch < 0) return null
  const after = before.slice(atMatch)
  const equal = !after.includes("else")
  if (nilAt > sceneAt) return { kind: "nil", equal }
  const name = scene[scene.length - 1].match(/"([A-Za-z][A-Za-z0-9]*)"/)[1]
  return { kind: "scene", name, equal }
}
function callsForFunction(fn, file, origin) {
  const calls = file.calls.filter((item) => item.name === fn.name)
  if (!origin) return calls
  const owner = ownerOf(file, origin.at)
  if (!owner || owner.name !== fn.name) return calls
  const exact = calls.filter((item) => item.at === origin.at)
  return exact.length > 0 ? exact : calls
}
function sceneIdent(name, file, stack, origin) {
  if (!name || stack.has(name)) return []
  stack.add(name)
  const found = new Set()
  for (const fn of file.functions) {
    const index = fn.params.findIndex((param) => param.internal === name)
    if (index < 0) continue
    const calls = callsForFunction(fn, file, origin)
    for (const call of calls) {
      const passed = passedArgument(call, fn, index)
      for (const scene of sceneValues(passed, file, stack, call)) if (SCENE.test(scene)) found.add(scene)
    }
  }
  return [...found]
}
function failureScenes(file, origin) {
  const scenes = pairingFailures(file)
  if (!origin) return scenes.map((scene) => "1_6_fail_" + scene)
  const branch = sceneBranch(file.source, origin.at)
  const selected = branch ? scenes.filter((scene) => branch.equal ? scene === branch.name : scene !== branch.name) : scenes
  return selected.map((scene) => "1_6_fail_" + scene)
}

function sceneBranch(source, at) {
  const before = source.slice(Math.max(0, at - 700), at)
  const match = before.match(/if scene == "([A-Za-z][A-Za-z0-9]*)"/)
  if (!match) return null
  const after = before.slice(match.index + match[0].length)
  return { name: match[1], equal: !after.includes("else") }
}
function pairingFailures(file) {
  const found = new Set()
  for (const item of file.tuples || []) if (/^[A-Za-z][A-Za-z0-9]*$/.test(item)) found.add(item)
  return [...found]
}

function statusScenes(file) {
  const cases = statusEnumCases()
  return cases.map((scene) => ((scene === "disconnected" || scene === "connecting" || scene === "failed") ? "4_8" : "4_5") + "_status_" + scene)
}

function statusEnumCases() {
  const path = join(testsDirectory(), "..", "App", "Demo", "StatusSlotScenes.swift")
  const source = readFileSync(path, "utf8")
  const start = source.indexOf("enum StatusSlotScene")
  const end = source.indexOf("var snapshotScene", start)
  const body = start < 0 || end < 0 ? "" : source.slice(start, end)
  return [...body.matchAll(/\bcase\s+([A-Za-z_][A-Za-z0-9_]*)/g)].map((match) => match[1])
}
function sceneArg(call, fn) {
  if (!fn) return call.args[0] || null
  let index = fn.params.findIndex((param) => param.internal === "scene")
  if (index < 0) index = fn.params.findIndex((param) => param.external === "_" && param.type.includes("String"))
  if (index < 0) index = 0
  return call.args[index] || call.args[0] || null
}

function resolveNamed(call, file) {
  const expr = call.labeled.get("named") || call.labeled.get("name")
  if (!expr) return []
  const scope = ownerOf(file, call.at)
  return namesOf(expr, file, new Set(), scope)
}

function resolveTests(call, file) {
  const expr = call.labeled.get("testName")
  const scope = ownerOf(file, call.at)
  const result = !expr ? (scope && scope.name.startsWith("test") ? [scope.name] : []) : testsOf(expr, file, new Set(), scope)
  return result
}

function enclosingTest(file, at) {
  let name = ""
  for (const fn of file.functions) {
    if (fn.name.startsWith("test") && fn.start <= at && at < fn.end) name = fn.name
  }
  return name ? [name] : []
}

function namesOf(expr, file, stack, scope) {
  if (!expr) return []
  if (expr.kind === "string") return expr.parts ? [] : [expr.value]
  if (expr.kind === "ternary") return namesOf(expr.yes, file, stack, scope).concat(namesOf(expr.no, file, stack, scope))
  if (expr.kind === "coalesce") return unique(namesOf(expr.left, file, stack, scope).concat(namesOf(expr.right, file, stack, scope)))
  if (expr.kind === "call") return callNames(expr.name, file, stack, expr)
  if (expr.kind === "ident") return identNames(expr.value, file, stack, scope)
  return []
}

function identNames(name, file, stack, scope) {
  const mark = "ident:" + (scope ? scope.name : "") + ":" + name
  if (stack.has(mark)) return []
  stack.add(mark)
  const values = []
  if (scope) for (const item of scope.lets || []) if (item.name === name) values.push(...namesOf(item.expr, file, stack, scope))
  for (const value of parameterValues(name, file, scope)) values.push(...namesOf(value.expr, file, stack, value.scope))
  stack.delete(mark)
  if (values.length > 0) return unique(values)
  return callNames(name, file, stack)
}

function parameterValues(name, file, scope) {
  const values = []
  const functions = scope ? [scope] : file.functions
  for (const fn of functions) {
    const index = fn.params.findIndex((param) => param.internal === name)
    if (index < 0) continue
    for (const call of file.calls) {
      if (call.name !== fn.name) continue
      const passed = passedArgument(call, fn, index)
      if (passed) values.push({ expr: passed, scope: ownerOf(file, call.at) })
    }
  }
  return values
}

function passedArgument(call, fn, index) {
  const param = fn.params[index]
  if (!param) return null
  if (param.external !== "_" && call.labeled.has(param.external)) return call.labeled.get(param.external)
  if (call.labeled.has(param.internal)) return call.labeled.get(param.internal)
  return call.args[index] || null
}
function callNames(name, file, stack, expr) {
  const fn = index(file.functions).get(name)
  if (!fn || stack.has(name)) return []
  stack.add(name)
  const fromReturn = []
  for (const item of fn.returns) fromReturn.push(...namesOf(item, file, stack))
  stack.delete(name)
  if (name === "variantName") return variantReturns(fn, expr, file)
  if (fromReturn.length > 0) return unique(fromReturn.length ? fromReturn : [])
  const fromSwitch = []
  for (const item of fn.switches) fromSwitch.push(...namesOf(item, file, stack))
  if (fromSwitch.length > 0) return unique(fromSwitch)
  return variantNames(fn)
}

function variantReturns(fn, expr, file) {
  if (!expr || !expr.args) return variantNames(fn)
  const flags = new Map()
  for (const arg of expr.args) flags.set(arg.label, { bool: arg.bool, ident: arg.ident })
  const reduce = flagValues(flags.get("reduceTransparency"), file, null).includes(true)
  const contrast = flagValues(flags.get("increaseContrast"), file, null).includes(true)
  const large = flagValues(flags.get("large"), file, null)
  const names = []
  if (reduce) names.push("reduce-transparency")
  if (contrast) names.push("increase-contrast")
  if (large.includes(true)) names.push("large")
  if (large.includes(false)) names.push("default")
  return names.length > 0 ? unique(names) : variantNames(fn)
}
function flagValues(flag, file, call) {
  if (!flag) return []
  if (flag.bool === true || flag.bool === false) return [flag.bool]
  if (!flag.ident) return []
  if (!call) return unique(boolValues(flag.ident, file, new Set()))
  const values = []
  for (const fn of file.functions) {
    const index = fn.params.findIndex((param) => param.internal === flag.ident)
    if (index < 0) continue
    for (const passedCall of file.calls) {
      if (passedCall.name !== fn.name) continue
      if (call && passedCall !== call) continue
      const passed = passedArgument(passedCall, fn, index)
      if (passed && passed.kind === "ident") values.push(...boolValues(passed.value, file, new Set([fn.name + ":" + flag.ident]), passedCall))
    }
  }
  if (values.length === 0) values.push(...boolValues(flag.ident, file, new Set()))
  return unique(values)
}

function boolValues(name, file, stack, call) {
  if (name === "true") return [true]
  if (name === "false") return [false]
  if (!name || stack.has(name)) return []
  stack.add(name)
  const through = []
  for (const fn of file.functions) {
    const index = fn.params.findIndex((param) => param.internal === name)
    if (index < 0) continue
    for (const passedCall of file.calls) {
      if (passedCall.name !== fn.name) continue
      if (call && passedCall !== call) continue
      const passed = passedArgument(passedCall, fn, index)
      if (passed && passed.kind === "ident") through.push(...boolValues(passed.value, file, stack, passedCall))
    }
  }
  const values = []
  const marker = "for " + name + " in"
  for (const fn of file.functions) {
    const at = fn.text.indexOf(marker)
    if (at < 0) continue
    const tail = fn.text.slice(at, at + 40)
    if (tail.includes("false")) values.push(false)
    if (tail.includes("true")) values.push(true)
  }
  if (values.length > 0) return unique(values)
  return unique(through)
}

function boolOf(token) {
  if (!token || token.kind !== "ident") return null
  if (token.value === "true") return true
  if (token.value === "false") return false
  return null
}
function variantNames(fn) {
  const body = fn.text
  const names = []
  if (body.includes("reduce-transparency") || body.includes("reduceTransparency")) names.push("reduce-transparency")
  if (body.includes("increase-contrast") || body.includes("increaseContrast")) names.push("increase-contrast")
  if (body.includes("large") && body.includes("default")) names.push("large", "default")
  return names
}

function testsOf(expr, file, stack, scope) {
  if (!expr) return []
  if (expr.kind === "string") {
    if (!expr.parts) return [expr.value]
    return expandParts(expr.parts, file, scope)
  }
  if (expr.kind === "call") return snapshotCall(expr.name, file, stack)
  if (expr.kind === "ident") return identTests(expr.value, file, stack, scope)
  return []
}

function identTests(name, file, stack, scope) {
  const mark = "testident:" + (scope ? scope.name : "") + ":" + name
  if (stack.has(mark)) return []
  stack.add(mark)
  const values = []
  const passed = parameterValues(name, file, scope)
  for (const item of passed) values.push(...testsOf(item.expr, file, stack, item.scope || scope))
  if (name === "testName") if (passed.length === 0 && scope) values.push(...defaultFunctionNames(name, file, scope))
  stack.delete(mark)
  if (values.length > 0) return unique(values)
  return scope && scope.name.startsWith("test") ? [scope.name] : []
}

function defaultFunctionNames(name, file, scope) {
  const param = scope.params.find((item) => item.internal === name)
  if (!param || !param.defaults) return []
  const names = []
  for (const call of file.calls) {
    if (call.name !== scope.name || call.labeled.has(name)) continue
    const caller = ownerOf(file, call.at)
    if (caller && caller.name.startsWith("test")) names.push(caller.name)
  }
  return names
}
function expandParts(parts, file, scope) {
  let values = [""]
  for (const part of parts) {
    const piece = part.kind === "text" ? [part.value] : pieceValues(part.expr, file, scope)
    const next = []
    for (const left of values) for (const right of piece) next.push(left + right)
    values = next
  }
  return values.filter((value) => value.length > 0)
}

function pieceValues(expr, file, scope) {
  if (!expr) return [""]
  if (expr.kind === "string") return [expr.value]
  if (expr.kind === "ternary") return unique(pieceValues(expr.yes, file, scope).concat(pieceValues(expr.no, file, scope)))
  if (expr.kind === "ident") {
    if (expr.value === "appearance" || expr.value === "appearanceName") return APPEARANCES
    if (expr.value === "language" || expr.value === "languageName") return LANGUAGES
    const scenes = scenesFor(expr.value, file)
    if (scenes.length > 0) return scenes
    if (scope && scope.params.some((param) => param.internal === expr.value)) return scenesFor(scope.name, file)
    return [expr.value]
  }
  return [""]
}

function scenesFor(name, file) {
  const found = new Set()
  const byName = index(file.functions)
  for (const fn of file.functions) {
    if (!fn.params.some((param) => param.internal === name)) continue
    for (const call of file.calls) {
      if (call.name !== fn.name) continue
      const arg = sceneArg(call, fn)
      for (const scene of sceneValues(arg, file)) if (SCENE.test(scene)) found.add(scene)
    }
  }
  if (found.size > 0) return Array.from(found)
  for (const call of file.calls) {
    if (call.name !== name) continue
    const arg = sceneArg(call, byName.get(name))
    if (arg && arg.kind === "string" && SCENE.test(arg.value)) found.add(arg.value)
  }
  return Array.from(found)
}

function snapshotCall(name, file, stack) {
  const fn = index(file.functions).get(name)
  if (!fn || stack.has("snap:" + name)) return []
  stack.add("snap:" + name)
  const returned = []
  for (const item of fn.returns) returned.push(...testsOf(item, file, stack))
  stack.delete("snap:" + name)
  const snapshots = returned.filter((value) => value.startsWith("Snapshot_"))
  if (snapshots.length > 0) return unique(snapshots)
  const scenes = scenesFor(name, file)
  if (scenes.length === 0) return []
  const appearances = fn.text.includes("dark") ? APPEARANCES : ["light"]
  const languages = fn.text.includes("language") ? LANGUAGES : ["zh"]
  const names = []
  for (const scene of scenes) for (const appearance of appearances) for (const language of languages) names.push(snap(scene, appearance, language))
  return names
}

function ownerOf(file, at) {
  let owner = null
  for (const fn of file.functions) if (fn.start <= at && at < fn.end) owner = fn
  return owner
}

function unique(values) { return Array.from(new Set(values)) }
function index(functions) { return new Map(functions.map((fn) => [fn.name, fn])) }

function parse(source) {
  const tokens = lex(source)
  const functions = []
  const calls = []
  const stack = []
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i]
    if (token.kind === "func") {
      const fn = readFunction(tokens, i, source)
      if (fn) { functions.push(fn); stack.push(fn); i = fn.header }
      continue
    }
    if (token.kind === "rbrace" && stack.length > 0 && token.depth === stack[stack.length - 1].depth) {
      stack[stack.length - 1].end = token.at
      stack.pop()
      continue
    }
    if (token.kind === "ident" && tokens[i + 1] && tokens[i + 1].kind === "lparen" && !declared(tokens, i)) {
      const call = readCall(tokens, i)
      if (call) calls.push(call)
    }
  }
  for (const fn of functions) {
    if (fn.end < 0) fn.end = source.length
    fn.text = source.slice(fn.start, fn.end)
    fn.returns = returned(tokens, fn)
    fn.lets = letsOf(tokens, fn)
  }
  return { functions, calls, tuples: tupleNames(tokens), enumCases: enumCases(source), source }
}

function tupleNames(tokens) {
  const names = []
  for (let i = 0; i < tokens.length - 2; i += 1) {
    if (tokens[i].kind !== "lparen" || tokens[i + 1].kind !== "string" || tokens[i + 2].kind !== "comma") continue
    const value = tokens[i + 1].expr && tokens[i + 1].expr.value
    if (value && /^[A-Za-z][A-Za-z0-9]*$/.test(value)) names.push(value)
  }
  return names
}

function enumCases(source) {
  const names = []
  const pattern = /\bcase\s+([A-Za-z_][A-Za-z0-9_]*)/g
  for (const match of source.matchAll(pattern)) names.push(match[1])
  return names
}
function readFunction(tokens, at, source) {
  let i = at + 1
  if (!tokens[i] || tokens[i].kind !== "ident") return null
  const name = tokens[i].value
  i += 1
  if (tokens[i] && tokens[i].kind === "lt") i = matchDelim(tokens, i, "lt", "gt") + 1
  if (!tokens[i] || tokens[i].kind !== "lparen") return null
  const params = readParams(tokens, i)
  const close = matchDelim(tokens, i, "lparen", "rparen")
  const brace = nextKind(tokens, close, "lbrace")
  if (brace < 0) return null
  return { name, params, depth: tokens[brace].depth, start: tokens[brace].at, end: -1, header: brace, text: "", returns: [], switches: [] }
}

function readParams(tokens, open) {
  const close = matchDelim(tokens, open, "lparen", "rparen")
  const params = []
  let first = ""
  let second = ""
  let type = ""
  let phase = "name"
  let defaults = false
  let depth = 0
  for (let i = open + 1; i < close; i += 1) {
    const token = tokens[i]
    if (token.kind === "lparen" || token.kind === "lbrace") depth += 1
    else if (token.kind === "rparen" || token.kind === "rbrace") depth -= 1
    if (depth === 0 && token.kind === "comma") { params.push(param(first, second, type, defaults)); first = ""; second = ""; type = ""; defaults = false; phase = "name"; continue }
    if (depth !== 0) continue
    if (phase === "name" && token.kind === "ident") { if (!first) first = token.value; else second = token.value }
    else if (phase === "name" && token.kind === "colon") phase = "type"
    else if (phase === "type" && token.kind === "ident") type += token.value
    else if (phase === "type" && token.kind === "eq") { phase = "default"; defaults = true }
  }
  if (first || second) params.push(param(first, second, type, defaults))
  return params
}

function param(first, second, type, defaults) {
  return { external: first, internal: second || first, type, defaults: Boolean(defaults) }
}

function readCall(tokens, at) {
  const open = at + 1
  const close = matchDelim(tokens, open, "lparen", "rparen")
  if (close < 0) return null
  const args = []
  const labeled = new Map()
  let start = open + 1
  let depth = 0
  for (let i = open + 1; i <= close; i += 1) {
    const token = tokens[i]
    if (i < close && (token.kind === "lparen" || token.kind === "lbrace" || token.kind === "lt")) depth += 1
    if (i < close && (token.kind === "rparen" || token.kind === "rbrace" || token.kind === "gt")) depth -= 1
    if (i === close || (depth === 0 && token.kind === "comma")) {
      const parsed = readArg(tokens, start, i)
      if (parsed.label) labeled.set(parsed.label, parsed.expr)
      else if (parsed.expr) args.push(parsed.expr)
      start = i + 1
    }
  }
  return { name: tokens[at].value, at: tokens[at].at, args, labeled }
}

function readArg(tokens, start, end) {
  while (start < end && tokens[start].kind === "nl") start += 1
  while (end > start && tokens[end - 1].kind === "nl") end -= 1
  if (start >= end) return { label: "", expr: null }
  let label = ""
  if (tokens[start].kind === "ident" && tokens[start + 1] && tokens[start + 1].kind === "colon") {
    const third = tokens[start + 3]
    if (!third || third.kind !== "colon") { label = tokens[start].value; start += 2 }
  }
  return { label, expr: expression(tokens, start, end) }
}

function expression(tokens, start, end) {
  return expressionTokens(collect(tokens, start, end))
}

function expressionTokens(slice) {
  const bare = unwrap((slice || []).filter((token) => token && token.kind !== "nl"))
  if (bare.length === 0) return null
  const coal = split(bare, "coal")
  if (coal) return { kind: "coalesce", left: expressionTokens(coal[0]), right: expressionTokens(coal[1]) }
  const tern = ternary(bare)
  if (tern) return { kind: "ternary", yes: expressionTokens(tern[0]), no: expressionTokens(tern[1]) }
  return primary(bare)
}

function collect(tokens, start, end) {
  const slice = []
  const limit = Math.min(end, tokens.length)
  for (let i = start; i < limit; i += 1) if (tokens[i]) slice.push(tokens[i])
  return slice
}

function unwrap(slice) {
  let current = slice
  while (current.length >= 2 && current[0].kind === "lparen") {
    const close = matchSlice(current, 0)
    if (close !== current.length - 1) break
    current = current.slice(1, close)
  }
  return current
}

function matchSlice(tokens, open) {
  let depth = 0
  for (let i = open; i < tokens.length; i += 1) {
    if (tokens[i].kind === "lparen") depth += 1
    else if (tokens[i].kind === "rparen") { depth -= 1; if (depth === 0) return i }
  }
  return -1
}
function primary(slice) {
  if (!slice || slice.length === 0) return null
  if (slice[0].kind === "string") return slice[0].expr
  if (slice[0].kind === "dot" && slice[1] && slice[1].kind === "ident") return { kind: "member", owner: "", value: slice[1].value }
  if (slice[0].kind === "ident" && slice[1] && slice[1].kind === "dot" && slice[2] && slice[2].kind === "ident") return { kind: "member", owner: slice[0].value, value: slice[2].value }
  if (slice[0].kind === "ident" && slice[1] && slice[1].kind === "lparen") return { kind: "call", name: slice[0].value, args: callArgs(slice) }
  if (slice[0].kind === "ident") return { kind: "ident", value: slice[0].value }
  return null
}

function callArgs(slice) {
  const close = matchSlice(slice, 1, "lparen", "rparen")
  if (close < 0) return []
  const args = []
  let start = 2
  let depth = 0
  for (let i = 2; i <= close; i += 1) {
    const token = slice[i]
    if (i < close && (token.kind === "lparen" || token.kind === "lbrace")) depth += 1
    else if (i < close && (token.kind === "rparen" || token.kind === "rbrace")) depth -= 1
    if (i === close || (depth === 0 && token.kind === "comma")) {
      const part = slice.slice(start, i).filter((item) => item.kind !== "nl")
      if (part[0] && part[0].kind === "ident" && part[1] && part[1].kind === "colon") {
        const value = part[2]
        args.push({ label: part[0].value, bool: boolOf(value), ident: value && value.kind === "ident" ? value.value : "" })
      }
      start = i + 1
    }
  }
  return args
}
function split(tokens, kind) {
  let depth = 0
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i]
    if (token.kind === "lparen" || token.kind === "lbrace" || token.kind === "lt") depth += 1
    else if (token.kind === "rparen" || token.kind === "rbrace" || token.kind === "gt") depth -= 1
    else if (depth === 0 && token.kind === kind) return [tokens.slice(0, i), tokens.slice(i + 1)]
  }
  return null
}

function ternary(tokens) {
  let depth = 0
  let ask = -1
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i]
    if (token.kind === "lparen" || token.kind === "lbrace" || token.kind === "lt") depth += 1
    else if (token.kind === "rparen" || token.kind === "rbrace" || token.kind === "gt") depth -= 1
    else if (depth === 0 && token.kind === "question") ask = i
    else if (depth === 0 && token.kind === "colon" && ask >= 0) return [tokens.slice(ask + 1, i), tokens.slice(i + 1)]
  }
  return null
}

function returned(tokens, fn) {
  const values = []
  const body = tokens.filter((token) => token.at > fn.start && token.at < fn.end && token.kind !== "nl")
  for (let i = 0; i < body.length; i += 1) {
    const token = body[i]
    if (token.kind !== "string") continue
    const prev = body[i - 1]
    if (token.returned || !prev || prev.kind === "lbrace" || prev.kind === "return") values.push(token.expr)
  }
  return values
}

function letsOf(tokens, fn) {
  const values = []
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i]
    if (token.kind !== "ident" || (token.value !== "let" && token.value !== "var") || token.at <= fn.start || token.at >= fn.end) continue
    const name = tokens[i + 1]
    if (!name || name.kind !== "ident") continue
    let cursor = i + 2
    while (tokens[cursor] && tokens[cursor].kind !== "eq" && tokens[cursor].at < fn.end) cursor += 1
    if (!tokens[cursor] || tokens[cursor].kind !== "eq") continue
    let end = cursor + 1
    let depth = 0
    while (tokens[end] && tokens[end].at < fn.end) {
      if (tokens[end].kind === "lparen" || tokens[end].kind === "lbrace") depth += 1
      else if (tokens[end].kind === "rparen" || tokens[end].kind === "rbrace") { if (depth === 0) break; depth -= 1 }
      else if (depth === 0 && tokens[end].kind === "nl") break
      end += 1
    }
    values.push({ name: name.value, expr: expression(tokens, cursor + 1, end) })
  }
  return values
}
function declared(tokens, at) {
  const previous = tokens[at - 1]
  return Boolean(previous && (previous.kind === "func" || previous.kind === "hash"))
}

function matchDelim(tokens, open, left, right) {
  let depth = 0
  for (let i = open; i < tokens.length; i += 1) {
    if (tokens[i].kind === left) depth += 1
    else if (tokens[i].kind === right) { depth -= 1; if (depth === 0) return i }
  }
  return -1
}

function nextKind(tokens, from, kind) {
  for (let i = from; i < tokens.length; i += 1) if (tokens[i].kind === kind) return i
  return -1
}

function lex(source) {
  const tokens = []
  let i = 0
  let paren = 0
  let brace = 0
  let angle = 0
  let pending = false
  while (i < source.length) {
    const code = source.charCodeAt(i)
    if (code === 47 && source.charCodeAt(i + 1) === 47) { const nl = source.indexOf("\n", i); i = nl < 0 ? source.length : nl; continue }
    if (code === 47 && source.charCodeAt(i + 1) === 42) { const end = source.indexOf("*/", i + 2); i = end < 0 ? source.length : end + 2; continue }
    if (code === 34) { const parsed = readString(source, i); tokens.push({ kind: "string", at: i, expr: parsed.expr, returned: pending }); if (!pending) pending = false; i = parsed.end; continue }
    if (code === 96) { i = skipRaw(source, i); pending = false; continue }
    if (code === 35) { tokens.push({ kind: "hash", at: i }); i += 1; continue }
    if (isStart(code)) {
      const start = i
      i += 1
      while (i < source.length && isCont(source.charCodeAt(i))) i += 1
      const value = source.slice(start, i)
      const kind = value === "func" ? "func" : value === "return" ? "return" : value === "let" || value === "var" ? "ident" : "ident"
      if (kind === "return") pending = true
      else if (kind !== "ident") pending = false
      tokens.push({ kind, at: start, value })
      continue
    }
    if (code === 63 && source.charCodeAt(i + 1) === 63) { tokens.push({ kind: "coal", at: i }); pending = false; i += 2; continue }
    const simple = one(code, i, paren, brace, angle)
    if (simple) { tokens.push(simple.token); paren = simple.paren; brace = simple.brace; angle = simple.angle; if (!simple.token.keep) pending = false; else pending = true; i += 1; continue }
    i += 1
  }
  return tokens
}

function one(code, at, paren, brace, angle) {
  if (code === 40) return { token: { kind: "lparen", at, depth: paren + 1 }, paren: paren + 1, brace, angle }
  if (code === 41) return { token: { kind: "rparen", at, depth: paren }, paren: paren - 1, brace, angle }
  if (code === 123) return { token: { kind: "lbrace", at, depth: brace + 1 }, paren, brace: brace + 1, angle }
  if (code === 125) return { token: { kind: "rbrace", at, depth: brace }, paren, brace: brace - 1, angle }
  if (code === 60) return { token: { kind: "lt", at }, paren, brace, angle: angle + 1 }
  if (code === 62) return { token: { kind: "gt", at }, paren, brace, angle: Math.max(0, angle - 1) }
  if (code === 63) return { token: { kind: "question", at, keep: true }, paren, brace, angle }
  if (code === 58) return { token: { kind: "colon", at, keep: true }, paren, brace, angle }
  if (code === 46) return { token: { kind: "dot", at }, paren, brace, angle }
  if (code === 61) return { token: { kind: "eq", at }, paren, brace, angle }
  if (code === 44) return { token: { kind: "comma", at }, paren, brace, angle }
  if (code === 10) return { token: { kind: "nl", at }, paren, brace, angle }
  return null
}

function readString(source, start) {
  let i = start + 1
  let value = ""
  const parts = [{ kind: "text", value: "" }]
  let interpolated = false
  while (i < source.length) {
    const code = source.charCodeAt(i)
    if (code === 92) {
      if (source[i + 1] === "(") {
        interpolated = true
        const close = matchCode(source, i + 1, 40, 41)
        const expr = close < 0 ? null : expression(lex(source.slice(i + 2, close)), 0, 999999)
        parts.push({ kind: "expr", expr })
        parts.push({ kind: "text", value: "" })
        i = close < 0 ? source.length : close + 1
        continue
      }
      value += source[i + 1] || ""
      parts[parts.length - 1].value += source[i + 1] || ""
      i += 2
      continue
    }
    if (code === 34) { i += 1; break }
    value += source[i]
    parts[parts.length - 1].value += source[i]
    i += 1
  }
  return { end: i, expr: interpolated ? { kind: "string", value, parts } : { kind: "string", value } }
}

function skipRaw(source, start) {
  let i = start + 1
  while (i < source.length) {
    if (source.charCodeAt(i) === 92) { i += 2; continue }
    if (source.charCodeAt(i) === 96) return i + 1
    i += 1
  }
  return source.length
}

function matchCode(source, open, left, right) {
  let depth = 0
  let quote = 0
  for (let i = open; i < source.length; i += 1) {
    const code = source.charCodeAt(i)
    if (quote) { if (code === 92) { i += 1; continue } if (code === quote) quote = 0; continue }
    if (code === 34 || code === 96) { quote = code; continue }
    if (code === 47 && source.charCodeAt(i + 1) === 47) { const nl = source.indexOf("\n", i); i = nl < 0 ? source.length : nl; continue }
    if (code === left) depth += 1
    else if (code === right) { depth -= 1; if (depth === 0) return i }
  }
  return -1
}

function isStart(code) { return (code >= 65 && code <= 90) || (code >= 97 && code <= 122) || code === 95 }
function isCont(code) { return isStart(code) || (code >= 48 && code <= 57) }

function testsDirectory() {
  return join(dirname(fileURLToPath(import.meta.url)), "..", "Tests")
}

function main() {
  const directory = testsDirectory()
  const files = readdirSync(directory).filter((name) => name.endsWith("SnapshotTests.swift")).sort().map((name) => ({
    name,
    source: readFileSync(join(directory, name), "utf8"),
  }))
  const missing = missingDeclarations(files)
  if (missing.length > 0) {
    console.error("缺少 " + missing.length + " 个截图声明：")
    for (const item of missing) console.error(item)
    process.exitCode = 1
    return
  }
  console.log("截图声明齐全")
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main()
