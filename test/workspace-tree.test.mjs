import test from "node:test"
import assert from "node:assert/strict"
import { mkdirSync, mkdtempSync, realpathSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { listWorkspaceDir, MAX_WORKSPACE_DIR_ENTRIES } from "../src/workspace-file.js"
import { pluginCapabilities } from "../src/protocol-caps.js"

function fixture() {
  const base = realpathSync(mkdtempSync(join(tmpdir(), "dsh-ws-tree-")))
  const root = join(base, "ws")
  const outside = join(base, "outside")
  mkdirSync(join(root, "src", "deep"), { recursive: true })
  mkdirSync(outside)
  writeFileSync(join(root, "b.txt"), "bb")
  writeFileSync(join(root, "a.md"), "a")
  writeFileSync(join(root, ".env"), "SECRET=1")
  writeFileSync(join(root, "src", "main.js"), "x")
  writeFileSync(join(outside, "secret.txt"), "s")
  symlinkSync(join(root, "src"), join(root, "link-in"))
  symlinkSync(outside, join(root, "link-out"))
  symlinkSync(join(root, "missing"), join(root, "link-broken"))
  return { base, root }
}

test("listWorkspaceDir 列根目录：目录在前、名称码元序、符号链接按目标归类", () => {
  const { base, root } = fixture()
  try {
    const out = listWorkspaceDir(root, "")
    assert.equal(out.path, "")
    assert.equal(out.truncated, false)
    assert.deepEqual(out.entries.map((e) => e.name), [
      "link-in", "src", ".env", "a.md", "b.txt", "link-broken", "link-out",
    ])
    const byName = Object.fromEntries(out.entries.map((e) => [e.name, e]))
    assert.deepEqual(byName["link-in"], { name: "link-in", type: "dir", link: true })
    assert.equal(byName["b.txt"].type, "file")
    assert.equal(byName["b.txt"].size, 2)
    assert.equal(typeof byName["b.txt"].mtimeMs, "number")
    assert.deepEqual(byName["link-out"], { name: "link-out", type: "symlink", outside: true })
    assert.deepEqual(byName["link-broken"], { name: "link-broken", type: "symlink", outside: true })
  } finally {
    rmSync(base, { recursive: true, force: true })
  }
})

test("listWorkspaceDir 子目录、经工作区内链接进入，返回相对路径", () => {
  const { base, root } = fixture()
  try {
    assert.equal(listWorkspaceDir(root, ".").path, "")
    const src = listWorkspaceDir(root, "src")
    assert.equal(src.path, "src")
    assert.deepEqual(src.entries.map((e) => e.name), ["deep", "main.js"])
    // 经链接进入：返回解析后的真实相对路径
    assert.equal(listWorkspaceDir(root, "link-in").path, "src")
    assert.equal(listWorkspaceDir(root, "src/deep").entries.length, 0)
  } finally {
    rmSync(base, { recursive: true, force: true })
  }
})

test("listWorkspaceDir 拒绝越界、不存在与非目录", () => {
  const { base, root } = fixture()
  try {
    assert.throws(() => listWorkspaceDir(root, ".."), { status: 403 })
    assert.throws(() => listWorkspaceDir(root, "../outside"), { status: 403 })
    assert.throws(() => listWorkspaceDir(root, join(base, "outside")), { status: 403 })
    assert.throws(() => listWorkspaceDir(root, "link-out"), { status: 403 })
    assert.throws(() => listWorkspaceDir(root, "nope"), { status: 404 })
    assert.throws(() => listWorkspaceDir(root, "a.md"), { status: 400 })
    assert.throws(() => listWorkspaceDir("", ""), { status: 400 })
  } finally {
    rmSync(base, { recursive: true, force: true })
  }
})

test("listWorkspaceDir 允许以 .. 开头的普通名字", () => {
  const { base, root } = fixture()
  try {
    mkdirSync(join(root, "..cache"))
    assert.equal(listWorkspaceDir(root, "..cache").path, "..cache")
  } finally {
    rmSync(base, { recursive: true, force: true })
  }
})

test("listWorkspaceDir 超过上限截断并报告总数", () => {
  const base = realpathSync(mkdtempSync(join(tmpdir(), "dsh-ws-tree-big-")))
  try {
    const n = MAX_WORKSPACE_DIR_ENTRIES + 5
    for (let i = 0; i < n; i++) writeFileSync(join(base, `f${String(i).padStart(5, "0")}`), "")
    const out = listWorkspaceDir(base, "")
    assert.equal(out.truncated, true)
    assert.equal(out.total, n)
    assert.equal(out.entries.length, MAX_WORKSPACE_DIR_ENTRIES)
  } finally {
    rmSync(base, { recursive: true, force: true })
  }
})

test("capabilities.files 宣告 tree", () => {
  const files = pluginCapabilities().files
  assert.equal(files.tree, true)
  assert.equal(files.treeMaxEntries, MAX_WORKSPACE_DIR_ENTRIES)
})
