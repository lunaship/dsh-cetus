/**
 * Cetus 验收 fixture 场景集（方案 §4 第 4 条）。
 *
 * 方案要求在两端建立**同一套** fixture 场景：
 *   未配对、等待批准、在线首页、离线缓存、流式会话、审批、自由回答、改动、文件、预览、设置。
 *
 * 这里不另造一套假 Host：场景全部指向**已有**的
 *   - scripts/ios-e2e-fixture.mjs  假 DSH gateway / 工作区树 / 会话事件流
 *   - scripts/ios-e2e-host.mjs     真插件宿主（隔离 stateDir）+ 控制面
 *   - scripts/export-contract-fixtures.mjs  合同快照（testdata/mobile-contract）
 * 只把「哪个场景用哪个已有入口、期望看到什么」显式登记下来，让两端验收引用同一份编号。
 *
 * 用法：
 *   node scripts/e2e-scenarios.mjs            打印场景表（Markdown）
 *   node scripts/e2e-scenarios.mjs --json     打印 JSON
 *   node scripts/e2e-scenarios.mjs --check    校验场景引用的常量确实存在（CI 可跑）
 */
import { fileURLToPath } from "node:url"
import * as fixture from "./ios-e2e-fixture.mjs"

/**
 * 场景集版本。fixture 内容变化（事件流、会话集、路由）时必须递增，
 * 并在 docs/cetus/BASELINE.md 记录：截图/录像要写清对应哪个版本的场景集。
 */
export const SCENARIO_SET_VERSION = 1

/** 场景的宿主模式：真插件宿主（可交互）还是纯合同快照。 */
export const HOST_MODE = {
  /** scripts/ios-e2e-host.mjs 起的真插件 + 假 DSH，可审批/流式/断开。 */
  liveHost: "live-host",
  /** scripts/export-contract-fixtures.mjs 的离线合同快照，不解设备。 */
  contractSnapshot: "contract-snapshot",
  /** 不需要宿主：App 未配对状态。 */
  noHost: "no-host",
}

/**
 * 十一个场景。`fixtures` 里写的是**已有脚本导出的符号名**，`--check` 会验证它们存在，
 * 这样脚本重命名时能立刻发现场景表过期。
 */
export const SCENARIOS = [
  {
    id: "S01",
    key: "unpaired",
    name: "未配对",
    host: HOST_MODE.noHost,
    setup: "全新安装（或删除配对）后直接启动；host 不运行。",
    expect: [
      "首页显示未连接/扫码入口，不显示任何会话",
      "「关于」页仍能打开并显示构建元数据（构建信息不依赖配对）",
    ],
    fixtures: [],
  },
  {
    id: "S02",
    key: "pending-approval",
    name: "等待批准",
    host: HOST_MODE.liveHost,
    setup:
      "host 以 panel route pair-settings 打开 requireConfirm，再发起配对；不调用 /control/approve-pairing。",
    expect: ["首页显示「等待电脑批准」，设备未拿到 token", "批准后自动进入在线首页"],
    fixtures: [],
  },
  {
    id: "S03",
    key: "online-home",
    name: "在线首页",
    host: HOST_MODE.liveHost,
    setup: "host 就绪 + 配对已批准；session/list 返回固定工作区与会话行。",
    expect: ["显示工作区与会话列表", "会话行含状态/时间/预览"],
    fixtures: ["WORKSPACE_ROW", "SESSION_ROWS"],
  },
  {
    id: "S04",
    key: "offline-cache",
    name: "离线缓存",
    host: HOST_MODE.liveHost,
    setup: "先按 S03 在线加载一次，再调 /control/shutdown 停上游，然后重新进入首页。",
    expect: ["显示上次缓存内容并标注离线/过期", "不因上游不可达而清空界面或崩溃"],
    fixtures: ["WORKSPACE_ROW", "SESSION_ROWS"],
  },
  {
    id: "S05",
    key: "streaming-session",
    name: "流式会话",
    host: HOST_MODE.liveHost,
    setup: "打开 SESSION_LOGIN，用 /control/stream 往现有 session SSE 追加 assistant/chunk。",
    expect: ["增量渲染且不整页重排", "滚动跟尾不抖动", "seq 空洞时处理 resync-required"],
    fixtures: ["SESSION_LOGIN", "loginEvents", "SESSION_STOPPED", "stoppedEvents"],
  },
  {
    id: "S06",
    key: "approval",
    name: "审批",
    host: HOST_MODE.liveHost,
    setup: "用 /control/approval 触发 approval/request，等待 App 展示后决定。",
    expect: ["审批卡片显示工具与原因", "允许/拒绝后状态回写且不重复弹"],
    fixtures: ["SESSION_LOGIN", "loginEvents"],
  },
  {
    id: "S07",
    key: "free-answer",
    name: "自由回答",
    host: HOST_MODE.liveHost,
    setup: "用 /control/question 触发 user-questions/request（含自由文本，不只选项）。",
    expect: ["多问题可分别作答", "自由文本提交后原样回传"],
    fixtures: ["SESSION_LOGIN"],
  },
  {
    id: "S08",
    key: "changes",
    name: "改动",
    host: HOST_MODE.liveHost,
    setup: "打开 SESSION_REFACTOR 的改动入口；changesService 返回固定 diff。",
    expect: ["按文件分组展示 diff", "增删行着色正确"],
    fixtures: ["SESSION_REFACTOR", "refactorEvents", "CHANGES_DIFFS", "CHANGES_SUMMARIES", "changesService"],
  },
  {
    id: "S09",
    key: "files",
    name: "文件",
    host: HOST_MODE.liveHost,
    setup: "在工作区树里浏览文件（createWorkspaceTree 生成真实临时目录）。",
    expect: ["目录可展开且路径稳定", "只读浏览不写用户目录"],
    fixtures: ["createWorkspaceTree", "WORKSPACE_ROW"],
  },
  {
    id: "S10",
    key: "preview",
    name: "预览",
    host: HOST_MODE.liveHost,
    setup: "用 SESSION_REPORT 的工具输出里 5199 端口的 dev server 地址触发预览探测。",
    expect: ["识别出预览端口并给出入口", "只放行本机回环代理"],
    fixtures: ["SESSION_REPORT", "reportEvents"],
  },
  {
    id: "S11",
    key: "settings",
    name: "设置",
    host: HOST_MODE.liveHost,
    setup: "打开设置 → 关于；settings/describe 与 diagnostics 走 contracts 快照。",
    expect: ["关于页显示构建元数据（提交/日期/配置/合同版本）", "诊断项与合同 fixture 一致"],
    fixtures: ["CREDENTIALS", "PROVIDER_SETTINGS", "MODEL_CATALOG"],
  },
]

/** 校验场景表引用的符号在 fixture 模块里确实存在。 */
export function validateScenarios(module = fixture) {
  const missing = []
  for (const scenario of SCENARIOS) {
    for (const name of scenario.fixtures) {
      if (!(name in module)) missing.push(`${scenario.id} ${scenario.key}: ${name}`)
    }
  }
  return missing
}

function toMarkdown() {
  const lines = [
    `# Cetus 验收 fixture 场景集 v${SCENARIO_SET_VERSION}`,
    "",
    `共 ${SCENARIOS.length} 个场景。宿主统一走 scripts/ios-e2e-host.mjs（隔离 stateDir），`,
    "离线合同走 scripts/export-contract-fixtures.mjs。两边引用同一份场景编号。",
    "",
    "| ID | 场景 | 宿主 | 期望 |",
    "|---|---|---|---|",
  ]
  for (const s of SCENARIOS) {
    lines.push(`| ${s.id} | ${s.name} | ${s.host} | ${s.expect.join("；")} |`)
  }
  return lines.join("\n") + "\n"
}

function main(argv) {
  if (argv.includes("--check")) {
    const missing = validateScenarios()
    if (missing.length) {
      process.stderr.write(`场景表引用了不存在的 fixture：\n${missing.join("\n")}\n`)
      process.exit(1)
    }
    process.stdout.write(`场景集 v${SCENARIO_SET_VERSION}：${SCENARIOS.length} 个场景，引用全部存在\n`)
    return
  }
  if (argv.includes("--json")) {
    process.stdout.write(
      JSON.stringify({ version: SCENARIO_SET_VERSION, scenarios: SCENARIOS }, null, 2) + "\n",
    )
    return
  }
  process.stdout.write(toMarkdown())
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    main(process.argv.slice(2))
  } catch (error) {
    process.stderr.write(`e2e-scenarios: ${error.message}\n`)
    process.exit(1)
  }
}
