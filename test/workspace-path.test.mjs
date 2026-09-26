import { test } from "node:test"
import assert from "node:assert/strict"
import { mkdirSync, mkdtempSync, realpathSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { validateSessionCreateWorkspace } from "../src/workspace-path.js"

test("缺失末段也会跟随工作区内指向外部的符号链接", () => {
  const root = mkdtempSync(join(tmpdir(), "dsh-links-cwd-"))
  try {
    const workspace = join(root, "proj")
    const outside = join(root, "outside")
    mkdirSync(workspace)
    mkdirSync(outside)
    symlinkSync(outside, join(workspace, "link"))
    const workspaces = [{ workspaceId: "ws", path: workspace }]
    const escaped = validateSessionCreateWorkspace({
      cwd: join(workspace, "link", "newdir"),
      workspaces,
    })
    assert.equal(escaped.error, "cwd 不在已注册工作区内")

    const inside = validateSessionCreateWorkspace({
      cwd: join(workspace, "sub", "newdir"),
      workspaces,
    })
    assert.equal(inside.cwd, join(realpathSync(workspace), "sub", "newdir"))

    const sibling = validateSessionCreateWorkspace({
      cwd: `${workspace}-evil`,
      workspaces,
    })
    assert.equal(sibling.error, "cwd 不在已注册工作区内")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("已存在且留在工作区内的符号链接可以通过", () => {
  const root = mkdtempSync(join(tmpdir(), "dsh-links-cwd-"))
  try {
    const workspace = join(root, "proj")
    mkdirSync(workspace)
    const file = join(workspace, "notes.txt")
    writeFileSync(file, "ok")
    symlinkSync(file, join(workspace, "alias"))
    const checked = validateSessionCreateWorkspace({
      cwd: join(workspace, "alias"),
      workspaces: [{ workspaceId: "ws", path: workspace }],
    })
    assert.equal(checked.cwd, realpathSync(file))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
