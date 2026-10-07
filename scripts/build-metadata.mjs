#!/usr/bin/env node
/**
 * 生成两端共用的「构建元数据」（方案 §4 C00 / 工作包 P00）。
 *
 * 解决的真实问题：iOS 安装信息恒为 1.0(1)、Android 正式包只有营销版本，
 * 拿到一张截图无法定位到具体提交。这里把**内部**构建信息写进产物：
 *
 *   - 短提交 SHA（工作树脏时带 -dirty，且如实标注）
 *   - 构建日期（UTC）
 *   - Debug / Release
 *   - 协议能力版本（手机同步合同 version，见 docs/MOBILE_SYNC_CONTRACT.md）
 *
 * 元数据只在「关于 / 诊断」页展示，不进日常首页。
 *
 * **不升营销版本、不建 tag**：MARKETING_VERSION / CURRENT_PROJECT_VERSION /
 * Android versionCode / versionName 一律原样保留，由维护者按发布阶段处理。
 *
 * 用法：
 *   node scripts/build-metadata.mjs --platform ios   [--out <文件>] [--config Debug]
 *   node scripts/build-metadata.mjs --platform android [--out <文件>] [--config Debug]
 *   node scripts/build-metadata.mjs --print         # 只打印 JSON，便于核对
 */
import { execFileSync } from "node:child_process"
import { mkdirSync, writeFileSync } from "node:fs"
import { dirname, join, resolve } from "node:path"
import { fileURLToPath } from "node:url"

const SCRIPT_PATH = fileURLToPath(import.meta.url)
export const REPO_ROOT = dirname(dirname(SCRIPT_PATH))

/** 手机同步合同版本（docs/MOBILE_SYNC_CONTRACT.md 的 payload version）。 */
export const CONTRACT_VERSION = 1

function git(args, fallback = "") {
  try {
    return execFileSync("git", args, { cwd: REPO_ROOT, encoding: "utf8" }).trim()
  } catch {
    return fallback
  }
}

/**
 * 构建元数据。任何字段拿不到都如实写 "unknown"，不猜。
 * `dirty` 为 true 时 SHA 加 -dirty 后缀：脏工作树的产物不能对应到干净提交。
 */
export function collectBuildMetadata({ config = "Debug", date = new Date() } = {}) {
  const sha = git(["rev-parse", "--short=8", "HEAD"], "unknown")
  const dirtyRaw = git(["status", "--porcelain"])
  const dirty = dirtyRaw.length > 0
  return {
    commit: dirty ? `${sha}-dirty` : sha,
    commitFull: git(["rev-parse", "HEAD"], "unknown"),
    dirty,
    date: date.toISOString().slice(0, 10),
    builtAt: date.toISOString(),
    configuration: config,
    contractVersion: CONTRACT_VERSION,
  }
}

/** iOS：写进 Info.plist 的自定义键（App 用 Bundle.main 读回）。 */
export function iosPlistEntries(meta) {
  return {
    DLBuildCommit: meta.commit,
    DLBuildDate: meta.date,
    DLBuildConfiguration: meta.configuration,
    DLBuildContractVersion: String(meta.contractVersion),
  }
}

/** Android：写进 BuildConfig 的字段（build.gradle.kts buildConfigField）。 */
export function androidBuildConfigFields(meta) {
  return {
    BUILD_COMMIT: meta.commit,
    BUILD_DATE: meta.date,
    BUILD_CONFIGURATION: meta.configuration,
    BUILD_CONTRACT_VERSION: String(meta.contractVersion),
  }
}

function parseArgs(argv) {
  const out = { platform: "", out: "", config: "Debug", print: false }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === "--print") out.print = true
    else if (arg === "--platform") out.platform = argv[++i] ?? ""
    else if (arg.startsWith("--platform=")) out.platform = arg.slice("--platform=".length)
    else if (arg === "--out") out.out = argv[++i] ?? ""
    else if (arg.startsWith("--out=")) out.out = arg.slice("--out=".length)
    else if (arg === "--config") out.config = argv[++i] ?? "Debug"
    else if (arg.startsWith("--config=")) out.config = arg.slice("--config=".length)
    else throw new Error(`未知参数 ${arg}`)
  }
  return out
}

/** xcconfig 片段：project.yml 的 settings 直接引用，值里不能有裸空格。 */
export function xcconfigText(meta) {
  return Object.entries(iosPlistEntries(meta))
    .map(([key, value]) => `${key} = ${value}`)
    .join("\n")
    .concat("\n")
}

/** Kotlin 源文件：两端都不用生成代码，Android 侧交给 Gradle 的 buildConfigField。 */
export function kotlinBuildInfo(meta) {
  return [
    "// 由 scripts/build-metadata.mjs 生成，请勿手改。",
    "package dev.deeplinks.core",
    "",
    "internal object BuildInfo {",
    `    const val COMMIT: String = "${meta.commit}"`,
    `    const val DATE: String = "${meta.date}"`,
    `    const val CONFIGURATION: String = "${meta.configuration}"`,
    `    const val CONTRACT_VERSION: Int = ${meta.contractVersion}`,
    "}",
    "",
  ].join("\n")
}

export function render(platform, meta) {
  if (platform === "ios") return xcconfigText(meta)
  if (platform === "android") return kotlinBuildInfo(meta)
  throw new Error(`未知 platform：${platform}`)
}

export function defaultOut(platform) {
  if (platform === "ios") return join(REPO_ROOT, "apps", "ios", "BuildMetadata.xcconfig")
  if (platform === "android") {
    return join(
      REPO_ROOT,
      "apps",
      "android",
      "app",
      "src",
      "main",
      "java",
      "dev",
      "deeplinks",
      "core",
      "BuildInfo.kt",
    )
  }
  throw new Error(`未知 platform：${platform}`)
}

function main(argv) {
  const args = parseArgs(argv)
  const meta = collectBuildMetadata({ config: args.config })
  if (args.print) {
    process.stdout.write(JSON.stringify(meta, null, 2) + "\n")
    return
  }
  if (!args.platform) throw new Error("必须指定 --platform ios|android（或 --print）")
  const out = resolve(args.out || defaultOut(args.platform))
  mkdirSync(dirname(out), { recursive: true })
  writeFileSync(out, render(args.platform, meta))
  process.stdout.write(`wrote ${out}\n${JSON.stringify(meta)}\n`)
}

if (process.argv[1] && resolve(process.argv[1]) === SCRIPT_PATH) {
  try {
    main(process.argv.slice(2))
  } catch (error) {
    process.stderr.write(`build-metadata: ${error.message}\n`)
    process.exit(1)
  }
}
