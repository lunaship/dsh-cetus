# DeepLinks compatibility matrix

This is the single compatibility reference for the public `dsh-links`
repository (plugin plus Relay under `relay/`).
Android source is in `apps/android/`. Update this file when a source baseline, tag, release,
or verified combination changes.

## Current source baseline

These values describe the source snapshots used for the current Beta work.
They do not imply that a package, APK, or Relay deployment has been
published.

| Component | Source baseline | Published / released status | Verified compatibility status |
|---|---|---|---|
| DSH | `0.1.7-alpha.1` (host package + resolved sub-packages updated 2026-09-22 on the operator machine; npm `alpha`) | Upstream dependency. npm dist-tags on 2026-09-22: `latest` `0.1.5-rc.2`, `next` `0.1.5-rc.3`, `alpha` `0.1.7-alpha.1`. `0.1.5-rc.3` was verified to be a version-string-only build for the surfaces this plugin touches (CLI `lib/*.js` and 12 seam sub-packages — session-format, api-gateway, api-remotes, client-ui-layout/settings/slots, terminal, tool-ask-user, typert-loader/protocol/registry, web-app — byte-identical to `rc.2` except `package.json`), so it is not a code update. `0.1.7-alpha.1` carries real plugin-facing changes; the seam review and migration consequences are recorded under “DSH `0.1.7-alpha.1` migration notes” below |
| Plugin `dsh-links` | package `0.1.0-beta.18`; GitHub tag `v0.1.0-beta.18`; follows DSH `0.1.7-alpha.1` (adds the V4 `tool/result` seam: `toolResultMeta`/`toolResultContent` accept both the V3 `role: user` + `tool-result` wrapper message and the V4 first-class `role: tool` message, so `isError` and the result text resolve on v3 and v4 sessions alike) on top of the beta.17 baseline (V3 session log path; omit null session fields; produced files + workspace file GET; terminal approvals/questions session-bound; mobile archive-set contract; pairing readiness gate 503 `proxy_not_ready` before HTTPS listen; no-callId approval waterfall fallback binding; mobile `session_busy` 409 mapping; security hardening: mobile `danger-full-access` refused unless `allowMobileDangerFullAccess`, permission/file actions require an active session SSE subscription, `session.create` cwd confined to registered workspaces; relay control API errors sanitized, IPC handshake challenge-bound, `init` no longer prints the admin password; `@deepseek-ai/schemastery` 3.18.2) | npm publishing removed 2026-09-30 (`.github/workflows/publish-npm.yml` deleted; the plugin is distributed from this repository as a git source, not a registry package); the V4 seam fix shipped in `0.1.0-beta.18` | Unit tests 194 green locally 2026-09-23 (6 new V3/V4 `tool/result` cases; the new cases fail against the pre-fix source — verified by reverting `src/` under `git stash`); e2e arch smoke 35/35 green against `0.1.7-alpha.1` 2026-09-22 (isolated scratch `stateDir`, operator `state.json` hash unchanged); host restarted on `0.1.7-alpha.1` with both existing phone pairings and the relay route intact; phone end-to-end on this baseline not rerun |
| Android `apps/android/` | `versionName app-v0.5.0-beta.20`; GitHub tag `app-v0.5.0-beta.20`; adds the adaptive workspace/navigation redesign and the security review fixes: per-device startup routing + last-session restore, Medium navigation rail, bounded Mermaid bitmap cache (pixel budget), one confirmation path for high-privilege commands, system-share cache quota, shared `FLAG_SECURE` helper — on top of the beta.19 hardening (bundled Mermaid 11.17.2, locked-down untrusted WebViews (no content/file-URL access, Safe Browsing), share target accepts only `content://`, release log redaction + R8 log stripping, Gradle wrapper SHA-256) | Official signed APK on GitHub Releases `app-v0.5.0-beta.20`; SHA-256 recorded on the release | Unit tests (411) + lint + screenshot validation green locally 2026-09-23 and in CI; real-device e2e not rerun for this build (last phone e2e 2026-09-14 with `app-v0.5.0-beta.18`, below) |
| Relay (`relay/` in this repo) | `dlp-relay` (DLP/1) from `main` after 2026-09-29; DLR/1 server removed | Official instance `wss://relay.dshlinks.com/ws` (systemd + Caddy, `relay/deploy/`); self-host from source | Experimental; stateless, no accounts; per-route 5 GiB/day on the official instance |

## Verified combination and scope

| DSH | Plugin | Android App | Relay | Verified path |
|---|---|---|---|---|
| `0.1.7-alpha.1` | unreleased (repo `main`, `fix/round3-crash-coldstart` merged) | `app-v0.5.0-beta.23` | `relay.dshlinks.com` (read-only; not exercised as the operator's route) | 2026-09-30, **emulator only, no real phone**. Round-3 tasks (§2.1–§2.6, §3.1–§3.8, §4, §5) landed on `fix/round3-crash-coldstart` → merged to `main` (`ebae903`). Crash investigation: the operator's real device (Xiaomi 15 / Android 16, cellular remote) crashed on **every** cold start (`开 App → 正在连接… → 闪退`, also after reinstall, on beta.21 and beta.22). Reproduced on the emulator by forcing the **remote** route (isolated host with remote enabled, QR whose `urls` point at an unreachable address, fresh install + album pairing) → `android.os.NetworkOnMainThreadException` at `HostHttp.evictIdleRemote` (`HostHttp.kt:329`) ← `WorkspaceActivity` `ON_START` observer (`WorkspaceActivity.kt:1084`) ← `ConnectionPool.evictAll` → Conscrypt TLS `close_notify`. Root cause: connection-pool eviction is real network I/O and ran on the main thread; only the remote route has idle tunnels in `remoteClients`, which is why LAN (emulator) never crashed. Fix `e61cef3`: `HostHttp.evictPools` detects the main thread and dispatches eviction to a background executor; regression guard `architecture/MainThreadNetworkTest`. After the fix, remote cold start ×N and background→foreground ×3 no longer crash. Gates on `main`: plugin `npm run prepack` 295/295, `npm run test:dlp1-e2e` PASS 15 / FAIL 0 / SKIP 5, Android `assembleDebug + JVM tests + lint` green, `assembleRelease` signed (`0.5.0-beta.23`). Cold start (cached list) 0.62–0.73 s; remote `home_network_data` 6.1–7.6 s. Screenshots only from the debug build (`FLAG_SECURE` blocks them on release). Pending: real-device confirmation on beta.23 |
| `0.1.7-alpha.1` | unreleased (`feat/dlp1-m2-m3`, after `0.1.0-beta.18`) | unreleased (after `app-v0.5.0-beta.20`) | `dlp-relay` at `relay.dshlinks.com` | 2026-09-29, DLP/1 remote (RFC 0001 M2 + M3), no real phone yet: plugin with an isolated scratch `stateDir` registered on the live relay; panel self-test through the relay passed 8/8 (outer ~150–630 ms, rendezvous ~210–580 ms, inner TLS ~80–760 ms); a Node phone simulator and, separately, the Kotlin client (`PairClient.pairWithQr` + `HostHttp` on the JVM) both completed remote first pairing from the unified QR (forced pending), host approval, device-origin `mobile/bootstrap` 200 through the relay, same credentials auto-switching to LAN (28 ms), and `UNKNOWN_KEY` after revoke. Gates: plugin `npm run prepack` 289/289, Android assemble + 564 JVM tests + lint (0 errors), relay `go test ./internal/dlp/... -race`. Version gate: remote first pairing and the remote route need both plugin and App from this branch; older Apps ignore the QR `remote` key and pair over LAN only. Pending: real phone on cellular, Wi-Fi ↔ cellular switching, 24 h soak |
| `0.1.7-alpha.1` | `0.1.0-beta.18` | `app-v0.5.0-beta.20` | `v0.1.0-beta.18` `relay/` | 2026-09-22, host-only (no phone): isolated-host arch smoke `scripts/e2e-arch-smoke.mjs` 35/35 against `0.1.7-alpha.1` (scratch `stateDir`, random free ports) — plugin load + TLS ready + port bind, loopback `pair-info` 200 with a valid 64-hex fingerprint pinned against the live peer certificate, `qr.png`, scratch pairing with requestId replay + consumed-code rejection, cross-site 403, `bootstrap`/`sessions`/`models`/`llm-models`/`workspaces`/`settings`/`agent-presets`/`devices` 200, SSE `ready` frame with protocol caps, client bundle serves the `settings.section` panel, smoke device confined to scratch state. Operator host then restarted on `0.1.7-alpha.1` (pid 94479, port 3080): loopback `pair-info`/`devices`/`relay-status` 200, the two existing phone pairings (LAN + cloud route) and the relay route credentials survived the upgrade, `settings.yaml` imported losslessly into `profiles/web/cordis.patch.yml` (4 providers / 31 models unchanged), `storages/workspace.json` intact (2 workspaces, 283 archived session ids). Pending: real-device e2e on this baseline, and a first V4-written session to confirm the mobile produced-file card on live data |
| `0.1.5-rc.1` | `0.1.0-beta.16` (readiness 503 gate, no-callId approval fallback, session_busy 409) | `app-v0.5.0-beta.18` | `v0.1.0-beta.16` `relay/` | 2026-09-13/14: isolated-host LAN e2e with the fixed plugin + app (scratch `stateDir`, mobile port 18642): manual pairing with TOFU fingerprint matched bit-for-bit against `pair-info` and the live peer certificate, host-side device active (lan), device card online; session create + prompt + exact model reply verified server-side and on-device; generation-time Wi-Fi outage (10s) auto-recovered with no re-pair and no loss/duplication in the server-side history; background-during-generation and force-stop restore kept pairing and history; cold-start pairing-info readiness regression covered by `test/readiness.test.mjs` (503 gate, fingerprint match after listen, corrupt TLS creds, port occupied). Pending device confirmation: in-window approval decision end-to-end, image attach e2e (MIUI safe-access openInputStream failure is fixed in app-v0.5.0-beta.18 but unverified on device), relay path.
| `0.1.5-rc.1` | `0.1.0-beta.15` (schemastery 3.18.2, session-bound terminals, archive-set contract) | `app-v0.5.0-beta.17` | `v0.1.0-beta.15` `relay/` | 2026-09-12: host smoke on a scratch profile. Note: the smoke ran with the plugin's default global state dir, which shared pairing state with the operator's real profile and revoked two real phone pairings during teardown (see warning below; recovered by re-pairing) and reset the workspace registry (recovered by resetting `storages/workspace.json` to `initialized: false` so the host re-bootstraps from session history). Verified: plugin load + port bind, loopback `pair-info`/`qr.png`, LAN pairing with one-time code + requestId replay + host approval, `bootstrap`/`sessions`/`models`/`llm-models`/`workspaces`/`settings`/`agent-presets`/`devices` 200, session history tail + `maxMessages` paging + `nextBeforeSeq` on V3 `session.v3.jsonl.zstd` logs, prompt accepted with model reply, workspace register (absolute path, existing dir) + list, session file GET with 403 on workspace-escape, SSE `ready` frame with protocol caps, client bundle serves the `settings.section` panel. `session.list` items may omit `projections` for cold sessions (projection cache miss), so paged history must not rely on `list.projections.asOfSeq`; the plugin's list+page fallback handles it. Content `session.search` degrades to title match when the query provider is absent (by design). 2026-09-12 (evening), real-device LAN e2e with the release builds: phone paired and online in the panel, session list + history render on the device, and the app auto-reconnected after a host restart; instrumented tests OK (5) on the device. Live archive-set follow-up (archive on Web, observe the app) remains in the closed-beta scope. |
| `0.1.5-alpha.2` | `0.1.0-beta.14` working tree (V3 log path + omit null + produced files) | `app-v0.5.0-beta.16` working tree | `v0.1.0-beta.14` `relay/` | 2026-09-09: host upgraded to alpha.2; plugin/App source aligned for produced files, workspace file GET, `/feedback`. Phone APK still `app-v0.5.0-beta.15` until a new build is installed. |
| `0.1.5-alpha.1` | `0.1.0-beta.14` working tree (V3 log path + omit null session fields) | `app-v0.5.0-beta.16` | `v0.1.0-beta.14` `relay/` | 2026-09-09: host smoke plus LAN phone (installed `app-v0.5.0-beta.15`): pairing persisted, session list, send prompt, SSE/history, V3 log. Approval not exercised (full access). Model 401 is host API key, not plugin. |
| `0.1.2-alpha.5` | `0.1.0-beta.14` | `app-v0.5.0-beta.16` | `v0.1.0-beta.14` `relay/` | 2026-09-07 published source: plugin npm `beta` + GitHub Release, App signed APK `vapp-v0.5.0-beta.16`. Unit gates for catchup integrity, multi-question validation, approval grace/idempotency, history terminal state, and unidirectional Bridge idle. Trusted LAN smoke and Android→Relay→Plugin were **not** rerun. Previous LAN smoke remains the last phone-path evidence and used App `app-v0.5.0-beta.14` with plugin `0.1.0-beta.13`. |
| `0.1.1-rc.2` | `0.1.0-beta.12` | `app-v0.5.0-beta.14` | not required | Trusted LAN smoke, 2026-08-30: plugin load, `/dsh-link/*` routes, `session.list` / `session.history` / `llm.models` / `workspace.list` / `settings.describe` RPCs, `events.mux` WebSocket frames, and the settings panel slot all verified against `@deepseek-ai/dsh@0.1.1-rc.2`. Phone end-to-end (pairing, SSE push, approval) not yet rerun on this DSH version. |
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
