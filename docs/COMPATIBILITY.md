# DeepLinks compatibility matrix

This is the single compatibility reference for the public `dsh-links`
repository (plugin plus Relay under `relay/`).
Android source is in `apps/android/`. Update this file when a source baseline, tag, release,
or verified combination changes.

## Current source baseline

These values describe the source snapshots used for the current Beta work.
They do not imply that a package, APK, or Relay deployment has been
published. Per-change details live in `CHANGELOG.md`.

| Component | Source baseline | Published / released status |
|---|---|---|
| DSH | `0.1.7-alpha.1` (npm `alpha`) | Upstream dependency |
| Plugin `dsh-links` | package `0.1.0-beta.19` on `main` | Distributed from this repository as a git source (npm publishing removed 2026-09-30). Latest pushed tag: `v0.1.0-beta.18`; `v0.1.0-beta.19` is not tagged yet |
| Android `apps/android/` | `versionName 0.5.0-beta.27` on `main` | Latest signed APK on GitHub Releases: `app-v0.5.0-beta.27` (versionCode 35, SHA-256 `3022c5a5b25aa05c9e1e597b7a40dfcb54e50ab72aa57cc8a115bc1e583f3223`), built from `0f0fd75` — the revision the `app-v0.5.0-beta.27` tag points at. `0.5.0-beta.24`–`0.5.0-beta.26` were never published; their changes ship in `0.5.0-beta.27`. `CI - Android` (unit tests, lint, screenshot validation, `assembleDebug`, emulator smoke) is green on `0f0fd75`; real-device acceptance for this build is still pending |
| Relay (`relay/`) | `dlp-relay` (DLP/1) from `main`; DLR/1 server removed | Official instance `wss://relay.dshlinks.com/ws`; self-hosting in `relay/README.md` |

## Capability: dev server preview

Unreleased plugin source on `main` declares `capabilities.preview = { v: 1 }`
and serves `GET /dsh-link/mobile/previews` plus
`/dsh-link/mobile/preview/:previewId/*`. The proxy dials `127.0.0.1` only,
and only for a port approved on the loopback panel. There is no mobile
approve route. `PLUGIN_PROTOCOL` stays `2`. Package and APK version numbers
are unchanged. Android builds that do not know the field ignore it.
Unreleased Android source shows a Preview entry when `preview.v` is 1 and
opens approved ports through a loopback proxy on the phone.
`preview.detect = 1` adds a session hint for ports seen in tool output.
The hint tells the user to approve on the computer. There is still no
mobile approve route, and no phone setting for it.

## Capability: host session events

Unreleased plugin source on `main` declares `capabilities.events = { host: true }`
and serves `GET /dsh-link/mobile/events`. The stream carries session state only.
`PLUGIN_PROTOCOL` stays `2`. Package and APK version numbers are unchanged.
Android builds that do not know the field ignore it and keep using the
single-session stream.

## Capability: connection diagnostics

Unreleased plugin source on `main` declares `capabilities.diagnostics = { v: 1 }`
and serves `GET /dsh-link/mobile/diagnostics`. `PLUGIN_PROTOCOL` stays `2`.
Package and APK version numbers are unchanged. Android builds that do not
know the field ignore it and keep working. The connection-diagnostics screen
reads `GET /dsh-link/mobile/diagnostics` when the route exists, and still
shows the on-phone checks when the plugin returns 404.

## Capability: Tailscale spare address

Unreleased plugin source on `main` classifies `100.64.0.0/10` and Tailscale
IPv6 `fd7a:115c:a1e0::/48` as `tailnet`. Those addresses stay in the QR
`urls`. The panel labels them Tailscale. The recommended address is still
the first private address. `PLUGIN_PROTOCOL` stays `2`. Package and APK
version numbers are unchanged.

Unreleased Android source stores a QR tailnet URL that is different from
the primary address on `Host.tailnetUrl`. JSON written before this field
loads as an empty spare. Route selection tries the primary address, then
the spare, then the relay, and caches the address that succeeded until the
network generation changes. Older Apps ignore the extra URL and keep the
first reachable address only. Older plugins that still label
`100.64.0.0/10` as `other` still put that URL in `urls`; a new App
classifies it from the URL itself.

## Verified combination and scope

| DSH | Plugin | Android App | Relay | Verified path |
|---|---|---|---|---|
| `0.1.7-alpha.1` | `0.1.0-beta.19` + mobile-session safety fix | `app-v0.5.0-beta.23` debug | `dlp-relay` source (not deployed) | 2026-09-30, real phone (debug build): LAN cold start 10/10 and foreground return 3/3; `StartupSmokeTest` 1/1; five Wi-Fi ↔ cellular cycles each showed `在线 · 远程`; no fatal / StrictMode / main-thread network log. Remote first pairing, approval, background, revoke and soak evidence still pending |
| `0.1.7-alpha.1` | `main` (`fix/round3-crash-coldstart`) | `app-v0.5.0-beta.23` | `relay.dshlinks.com` | 2026-09-30, emulator: remote-route cold-start crash (`NetworkOnMainThreadException` from connection-pool eviction) reproduced and fixed (`e61cef3`, guarded by `MainThreadNetworkTest`) |
| `0.1.7-alpha.1` | `feat/dlp1-m2-m3` (after `0.1.0-beta.18`) | after `app-v0.5.0-beta.20` | `relay.dshlinks.com` | 2026-09-29, DLP/1 M2 + M3 without a real phone: panel self-test through the relay 8/8; Node simulator and the Kotlin client both completed remote first pairing, host approval, bootstrap via relay, LAN auto-switch and `UNKNOWN_KEY` after revoke. Older Apps ignore the QR `remote` key and pair over LAN only |
| `0.1.7-alpha.1` | `0.1.0-beta.18` | `app-v0.5.0-beta.20` | `v0.1.0-beta.18` | 2026-09-22, host only: `scripts/e2e-arch-smoke.mjs` 35/35 on an isolated `stateDir`; upgrade kept existing pairings, relay route and settings |
| `0.1.5-rc.1` | `0.1.0-beta.16` | `app-v0.5.0-beta.18` | `v0.1.0-beta.16` | 2026-09-13/14, isolated-host LAN e2e: manual pairing with TOFU fingerprint, session create + prompt, Wi-Fi outage recovery, background / force-stop restore |
| `0.1.5-rc.1` | `0.1.0-beta.15` | `app-v0.5.0-beta.17` | `v0.1.0-beta.15` | 2026-09-12, host smoke + real-device LAN e2e with release builds (see the smoke-isolation warning below) |
| `0.1.5-alpha.2` / `0.1.5-alpha.1` | `0.1.0-beta.14` | `app-v0.5.0-beta.16` | `v0.1.0-beta.14` | 2026-09-09/10, host + LAN |
| `0.1.2-alpha.5` | `0.1.0-beta.14` | `app-v0.5.0-beta.16` | `v0.1.0-beta.14` | 2026-09-07, published source |
| `0.1.1-rc.2` | `0.1.0-beta.12` | `app-v0.5.0-beta.14` | not required | 2026-08-30, trusted LAN smoke |
| `0.1.0-rc.8` | `0.1.0-beta.9` | `app-v0.5.0-beta.14` | not required | Trusted LAN; the previously documented Beta combination |

The rows above are LAN compatibility statements unless they say otherwise.
Remote (DLP/1) evidence is recorded only where a row names it; real-phone
cellular runs, network switching, and soak tests are tracked separately.

## DSH `0.1.7-alpha.1` migration notes

Recorded 2026-09-22 from the seam review that accompanied the operator
host upgrade (`0.1.5-rc.2` → `0.1.7-alpha.1`). These are the surface
facts a future upgrade or rollback has to know; they are not a claim
about upstream intent.

**Session logs are now V4.** `0.1.7-alpha.1` ships
`@deepseek-ai/dsh-session-format-catalog` with `currentVersion: 4` and
`releasedV4SessionFormatCodec` as the current encoder, so new sessions
are written as `session.v4.jsonl[.zstd]`. V3 files are restored through
the `v3-to-v4` migration without rewriting the stored generation. The
plugin's `session-log-path.js` already picks the highest `vN`, and
`assistant/chunk`, `tool/call`, `tool/result`, `turn/end`,
`user/message`, `approval/asked`, `compaction/*`, `todo/write` are all
still event names in the V4 vocabulary (V4 adds `developer/message`,
`image/offload`, `workspace/changes`; none removed). The one shape
change that reaches this plugin is the `tool/result` message:

| | V3 | V4 |
|---|---|---|
| `message.role` | `user` | `tool` |
| call id | `message.content[0].toolCallId`, `message.source.callId` | `message.toolCallId` (`source` retained) |
| error flag | `message.content[0].isError` | `message.isError` |
| result blocks | `message.content[0].content` | `message.content` |

Before the V4 seam fix, `toolResultIsError` read `content[0].isError`
only, so on a V4 session a failed `write`/`edit` would have been counted
as a successful mutation and the mobile produced-file card would have
listed files from failed operations; the result text was also read from
the wrong level on real V3 sessions (the `tool-result` wrapper was
stringified instead of unwrapped). Both are covered by
`test/produced-files.test.mjs` and `test/history.test.mjs`.

**Rollback is one-way for new sessions.** `0.1.5-rc.2` only ships
codecs v0–v3, and its catalog classifies an unknown stored version as
`unsupported`. Sessions created after the upgrade are therefore
unreadable on the RC line (`readHeader` refuses them); sessions created
before it stay readable. Re-upgrading restores the V4 sessions. No v3
file is rewritten in place.

**Settings moved out of `~/.dsh/settings.yaml`.** On first boot,
`0.1.7-alpha.1` imports that file into the current profile's plugin
configuration (`~/.dsh/profiles/web/cordis.patch.yml`) and renames the
original to `~/.dsh/settings.yaml.imported`. The import is attempted
once. Verified lossless on 2026-09-22: 4 `llm-pi-ai` providers and all
31 model rows preserved, `ui-onboarding` → `ui-settings-general`,
`agent-default-model`, `locale`, `ui-conversation`, `permission`,
`ui-theme` carried over. The legacy `agent-presets` key has no direct
target — upstream now declares and installs agent presets through
plugin bundles, so legacy directory-based presets need migration.
`~/.dsh/storages/workspace.json` and the plugin's global
`~/.dsh/dsh-links/state.json` are **not** part of this migration; the
2026-09-22 upgrade left both intact (2 workspaces / 283 archived session
ids, 2 phone pairings, relay route credentials).

**Seams reviewed as compatible** (no plugin change needed): the
`session`, `settings`, `workspace`, `messageFeedback`,
`agentPreset(s)`, and `credentials` Remote namespaces and the methods
this plugin calls (`session/list|page|follow|create|prompt|cancel|fork|rename|search|selectModel|modelCatalog`,
`settings/describe|update`, `workspace/list|create|delete|archiveSession|follow`,
`agentPresets/list`) — additive only, e.g. `SessionSummary` gained
`agentAvailable`, `session` gained `projections` /
`workspacePathApplications`, and `session/fork` now accepts an open cut.
The `typert` protocol additions (bidirectional stream uplink, owned
values, binary results) are opt-in; the in-process
`ctx.typertGateway` `invoke`/`stream` calls this plugin makes keep their
shape. `prompt` content parts still accept `{ type: 'image', mediaType,
 data }`. The `settings.section` client slot is unchanged (only a new
optional `settings.launcher` was added) and the plugin does not use the
renamed `ctx.settingsScope` / `ctx.configForms` context. The V3→V4
`readBytes` unification in `@deepseek-ai/dsh-api-workspace-files` (V3
`readBytes(path, range)` / `readAll` / `readRelated` → V4
`readBytes(path, { range, baseFile })`) does not apply: this plugin reads
workspace files from disk itself and never calls that Remote. The
single-file `dsh.bundle.patch` form still loads.

**Optional follow-ups** (not defects): the new Plugin Manager reads
localized titles/descriptions and an icon from `package.json`, which
this plugin does not declare yet, and the V4 `developer/message` event is
ignored by the mobile projection — if new sessions deliver runtime
context or instruction text as developer messages instead of user
messages, the mobile context-injection chip will simply not appear for
them.

## Support boundary

- **Public supported:** Android and DSH on the same trusted LAN.
- **Experimental (off by default):** remote access over the DLP/1 relay
  (RFC 0001). The panel's「外出时也能连」registers the plugin on a relay
  (official `wss://relay.dshlinks.com/ws` or self-hosted, `relay/README.md`);
  no invite code or account. One QR serves LAN and remote first pairing; a
  remote first pairing always waits for approval on the computer. Needs both
  plugin and App from the DLP/1 builds; older Apps ignore the QR `remote` key
  and pair over LAN. The DLR/1 invite-only relay (Control, enroll codes, cloud
  QR) was retired on 2026-09-29 and removed from the source tree.
- **Experimental:** a Tailscale or Cloudflare Tunnel path operated by the
  user. It is not a supported Beta path and carries no project compatibility
  promise.
- **Not supported:** direct public exposure of plugin port `18640`, or frp.

## Smoke-isolation warning (plugin state is global by default)

The plugin stores pairing, device, and remote identity (host key) state in
`~/.dsh/dsh-links/state.json` **globally** — not per DSH profile — and
migrates legacy dirs into it. Any profile that loads the plugin (including a
throwaway smoke profile) therefore shares devices, the remote host key, and the
workspace-facing pairing surface with the operator's real setup:

- Revoking devices or clicking teardown actions in a smoke host revokes the
  real phones too; phone pairings cannot be restored from disk (tokens are
  HMAC-hashed at rest), so the phones must re-scan the QR.
- The Relay Agent in a smoke host connects to the production Relay with the
  real route credentials under the real host identity.
- The host-level `~/.dsh/storages/workspace.json` registry is also global; a
  smoke host that registers workspaces writes it. Reset it to
  `initialized: false` with empty `workspaceIds`/`workspaces` to make the
  host re-derive workspaces from session history on the next boot.

Any future host smoke must set the plugin's `stateDir` config (scratch
directory, 0700) via a `--patch` config overlay in the smoke profile, and
must not exercise device revocation against shared state.

## Release-status rule

Repository tags, package registry metadata, APK releases, and running Relay
deployments are separate facts. A tag or a passing local test must not be
described as a published release or a production deployment. Confirm each
external fact in the relevant release or deployment environment before
changing the status above. For every verification or release record, lock the
exact checked-out revision of each repository in that evidence; this matrix
does not hard-code moving `main` revisions or ahead counts.

### 审批瀑布在 `0.1.5-rc.3` 上不触发（2026-09-29 实测，`0.1.7-alpha.1` 待验）

用隔离实例（`--profile` + 临时 `stateDir`，插件 `link:` 到工作树）跑真实审批时发现：插件挂在
`approval/request`（与 `user-questions/request`）上的钩子**一次都没被调用**——两轮探针分别在钩子入口
写文件，各轮 100 秒内文件始终为空；同一条会话的 `session.history` 里却有 `approval/asked` 事件、
手机侧也能看到审批。

影响：`rt.requests` 永远为空 → 插件「手机接管审批」在这版上不可达（App 侧因此走 D1-A 的诚实降级：
只显示「在电脑上处理」，不给批准/拒绝按钮）。

**边界**：只在 CLI 的 `0.1.5-rc.3` 上证实。本仓基线的宿主包是 `0.1.7-alpha.1`（见上表），它带真正的
插件面变更，**该版本上钩子是否触发尚未验证**——换基线复验时请优先看这条。手机端据此推导
`awaitingInput`（历史里 `approval/asked` 无配对 `approval/decided`）不受影响，两种基线上都成立。
