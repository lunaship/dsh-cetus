#!/usr/bin/env bash
#
# C12 NSE 模拟器验证：生成一份符合 RFC 0002 的通知 payload，用 `xcrun simctl push`
# 投递给通知服务扩展（NSE），确认扩展能在**不联网**的前提下本地解密并显示。
#
# 为什么需要它：真实 APNs 送达需要付费账号与真机（阶段 9）。`simctl push` 是 Apple
# 提供的、无需账号即可验证 NSE 行为的标准手段（RFC 0002 §10 验收清单最后第二项）。
#
# 用法：
#   scripts/ios-nse-simctl-push.sh                # 用内置调试密钥，自动写绑定后投递
#   scripts/ios-nse-simctl-push.sh --dry-run      # 只生成 payload 并打印，不投递
#   scripts/ios-nse-simctl-push.sh --device <UDID>
#
# 依赖：Xcode 命令行工具（xcrun simctl）、node。不需要付费账号。
#
# 安全边界：本脚本只使用**固定调试密钥**，且只在模拟器上运行。它不读取、不写入
# 用户真实配对数据，也不接触任何真实 APNs 凭据。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVICE="${SIMCTL_DEVICE:-E5E97F60-9F2D-4A99-B382-2A4A86CE8103}"
BUNDLE_ID="${NSE_BUNDLE_ID:-dev.deeplinks.ios.debug}"
# 与 iOS 侧 PushContent.generic 保持一致
FALLBACK_TITLE="cetus"
FALLBACK_BODY="有新的任务动态"
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --device) DEVICE="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

OUT_DIR="${ROOT}/.local/nse-simctl"
mkdir -p "$OUT_DIR"

# ---------------------------------------------------------------------------
# 1. 生成 payload：**只含 aps / e / k**（RFC 0002 §5.6 硬性要求）
#    绝不加入 deviceId / sessionId / tool / 标题原文。
# ---------------------------------------------------------------------------
node - "$OUT_DIR" <<'NODE'
const { createCipheriv, randomBytes } = require("node:crypto")
const { writeFileSync } = require("node:fs")
const { join } = require("node:path")

const outDir = process.argv[2]

// 固定调试密钥：与 iOS 测试向量同源，便于人工核对。
// 这不是真实用户密钥，只用于模拟器验证。
const KEY_HEX = "4242424242424242424242424242424242424242424242424242424242424242"
const DEVICE_ID = "sim-nse-device"
const KID = "sim-kid-0001"
const SESSION_ID = "sim-session-1"
const CONTENT_AAD_PREFIX = "dlpush/1 content|"

const plaintext = Buffer.from(
  JSON.stringify({
    v: 1,
    type: "approval",
    sessionId: SESSION_ID,
    title: "模拟器验证 · 审批请求",
    tool: "bash",
    ts: Math.floor(Date.now() / 1000),
  }),
)

// AAD = "dlpush/1 content|" + deviceId（RFC §5.4）
const nonce = randomBytes(12)
const cipher = createCipheriv("aes-256-gcm", Buffer.from(KEY_HEX, "hex"), nonce)
cipher.setAAD(Buffer.from(`${CONTENT_AAD_PREFIX}${DEVICE_ID}`, "utf8"))
const sealed = Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()])
const ct = Buffer.concat([nonce, sealed]).toString("base64")

const payload = {
  "Simulator Target Bundle": process.env.NSE_BUNDLE_ID || "dev.deeplinks.ios.debug",
  aps: {
    alert: { title: "cetus", body: "有新的任务动态" },
    "mutable-content": 1,
    "thread-id": "sim-thread",
    "interruption-level": "time-sensitive",
  },
  e: ct,
  k: KID,
}

writeFileSync(join(outDir, "payload.json"), JSON.stringify(payload, null, 2))
writeFileSync(
  join(outDir, "expected.json"),
  JSON.stringify({ key: KEY_HEX, deviceId: DEVICE_ID, kid: KID, sessionId: SESSION_ID, title: "模拟器验证 · 审批请求" }, null, 2),
)

// 自检：payload 顶层必须只有 aps/e/k（外加 simctl 需要的 bundle 键）
const extra = Object.keys(payload).filter(
  (k) => !["aps", "e", "k", "Simulator Target Bundle"].includes(k))
if (extra.length > 0) {
  console.error(`FAIL: payload 含 RFC 禁止的顶层字段: ${extra.join(", ")}`)
  process.exit(1)
}
console.log(`payload written: ${join(outDir, "payload.json")}`)
console.log(`  顶层字段: ${Object.keys(payload).join(", ")}  (RFC 要求 aps/e/k)`)
NODE

echo
echo "== 生成的 payload =="
cat "$OUT_DIR/payload.json"
echo

if [[ "$DRY_RUN" == "1" ]]; then
  echo
  echo "dry-run：未投递。"
  echo "注意：真实投递需要 App 已把测试密钥与 kid 绑定写入共享 Keychain，"
  echo "      否则 NSE 取不到 deviceId 会按设计回退到通用文案（这是正确行为，不是失败）。"
  exit 0
fi

# ---------------------------------------------------------------------------
# 2. 确认 App 与 NSE 已安装
# ---------------------------------------------------------------------------
if ! xcrun simctl get_app_container "$DEVICE" "$BUNDLE_ID" app >/dev/null 2>&1; then
  echo "FAIL: 模拟器 $DEVICE 上未安装 $BUNDLE_ID" >&2
  echo "      先构建并安装：" >&2
  echo "        node scripts/build-metadata.mjs --platform ios" >&2
  echo "        xcodegen generate --spec apps/ios/project.yml" >&2
  echo "        xcodebuild -project apps/ios/Cetus.xcodeproj -scheme Cetus \\" >&2
  echo "          -destination 'platform=iOS Simulator,id=$DEVICE' build" >&2
  echo "        xcrun simctl install $DEVICE <Cetus.app 路径>" >&2
  exit 1
fi
echo "OK: $BUNDLE_ID 已安装在 $DEVICE"

# ---------------------------------------------------------------------------
# 3. 投递
# ---------------------------------------------------------------------------
echo
echo "== xcrun simctl push =="
xcrun simctl push "$DEVICE" "$BUNDLE_ID" "$OUT_DIR/payload.json"

cat <<'EOF'

== 人工核对要点（NSE 行为）==
1. 通知栏出现的是**通用文案**「cetus / 有新的任务动态」→ NSE 未拿到密钥或绑定（回退路径，正确）。
2. 若已注入测试密钥与 kid 绑定，标题应变为解密出的明文标题。
3. 通知**不含**「允许 / 拒绝」动作按钮 —— RFC 0002 §3「不做」与 §8.4 红线。
4. 通知**不显示** tool / 文件名 / 命令 —— 只有标题，body 为空。

== 如何注入测试密钥与绑定（仅 DEBUG）==
`CetusApp` 启动时用 launch argument 写入共享 Keychain：
    xcrun simctl launch <device> <bundle> -CetusPushSimSeed
实现见 `CetusApp.swift` 的 `DebugPushSimSeed`（整段包在 `#if DEBUG`，Release 不存在）。

== ⚠️ 本机实测结论（2026-10-08，Xcode 27.1 / iOS 26.5 模拟器）==
`xcrun simctl push` 在本环境下**只把通知投递给 SpringBoard，不会启动 NSE**：
- 通知确实送达 —— 可在
  `data/Library/UserNotifications/*/DeliveredNotifications.plist` 看到
  `cetus` / `有新的任务动态`，且含本次 payload 的 `sim-kid-0001`；
- 但 `log show --predicate 'processImagePath CONTAINS "NotificationService"'`
  **始终为空** —— 即使 payload 带 `mutable-content: 1`、App 不在前台、
  扩展已由 pluginkit 注册（`pluginkit -m -p com.apple.usernotifications.service` 可见）。
- 因此**不能**用 `simctl push` 证明「NSE 解密后显示真实标题」。

**NSE 的解密逻辑**由以下覆盖（不需要模拟器推送）：
- `apps/ios/Tests/PushPayloadChainTests.swift`（5/5）：用**真实网关 payload 形状**
  （只含 `aps`/`e`/`k`）验证 `PushPayloadReader.deviceID(in:bindings:)` 经 `kid` 绑定
  解析出 deviceId、`PushContent.open` 解出正文；含未知 kid 回退与篡改密文负例。
- NSE 的 `didReceive` 本身是薄封装：取 `e`/`k` → 解析 deviceId → `PushContent.open`
  → 设置 title、body 置空、不带批准动作。

**仍未验证**：真机 + 真实 APNs 下 NSE 的实际拉起与锁屏展示（需付费账号，属阶段 9）。

排查时踩到的两个坑，记录以免重复：
1. 本工程用 **debug dylib**（`Cetus.debug.dylib` / `NotificationService.debug.dylib`），
   真实代码不在同名可执行文件里 —— 用 `nm`/`strings` 查 stub 会误判成「符号缺失」。
2. 覆盖安装后 pluginkit 可能仍指向旧容器路径；`simctl uninstall` 再 `install`
   可强制重新注册扩展。
EOF
