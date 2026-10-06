# Security

DeepLinks connects a phone to [DeepSeek Harness (dsh)](https://github.com/deepseek-ai) instances that can run tools and execute code on the host machine. Treat a paired device as a privileged remote console.

This public Beta supports **trusted LAN** as the documented product path. Remote access over the DLP/1 relay is **experimental and off by default**: both the plugin and the app only dial out to the relay, the plugin verifies the phone's rendezvous key before it connects its local port, the inner TLS is still pinned to the plugin certificate, and business authorization is still the device token. The relay holds no accounts or secrets. `state.json` (it contains the plugin's remote host key) must never appear in this repository or GitHub Releases.

If you use an intranet-tunnelling product yourself, treat it as an **experimental personal deployment**: it is not a supported Beta configuration and receives no compatibility or security guarantee. Do not expose port `18640` directly to the public Internet.

## Threat model (short)

- Pairing yields a long-lived device token (`x-dsh-link-token`). **Any active paired device is a privileged console**: anyone holding that token can prompt/cancel sessions, switch models, read files, and (subject to the session-scoped rules below) change a session's permission level on that host. Optional host confirmation keeps a newly paired token inert until you approve it on the「手机连接」panel. Active pairing codes live only in process memory; `state.json` must not contain a recoverable pairing code.
- **Session-scoped actions are limited to the device currently subscribed.** Permission-preset changes and session file downloads are refused with `403` unless the requesting device holds an active SSE subscription (`GET .../sessions/:id/stream`) for that exact session — i.e. it is the device currently viewing it. A token alone is not enough to retarget another session. This restriction is intended to prevent accidental cross-session interference, **not** to enforce device-to-device authorization isolation: any valid paired device can subscribe to any session first, so all paired devices effectively have equivalent privileges.
- **Mobile `danger-full-access` is refused by default.** The phone may switch a session to `read-only` or `workspace-write`; `danger-full-access` returns `403` unless the host explicitly sets `allowMobileDangerFullAccess: true` in the plugin config. The value is never silently downgraded: the request fails and the host config key is named in the error. The same gate validates `settings.update` for namespace `permission`, key `defaultPreset`.
- **Session creation is confined to registered workspaces.** A client-supplied `cwd`/`workspaceId` on `POST /mobile/sessions` must resolve inside a currently-registered workspace root; otherwise the request is rejected (`4xx`), and if the workspace list is unavailable or unparseable the plugin fails closed. The check follows symlinks, including when the final path component does not exist yet.
- **Absolute-path workspace registration requires approval on this computer.** A paired phone may still create a sibling directory of an existing workspace by name. Registering an existing absolute path does not call `workspace.create` until that exact realpath is approved on the「手机连接」panel. A symlink is shown as its target.
- **A paired phone can revoke only itself.** Cross-device revoke and revoke-all stay on the loopback panel.
- Port `18640` is an HTTPS reverse proxy with self-signed TLS. On LAN, the app **must pin the certificate fingerprint from the QR / pair-info payload before sending the pairing code**. A first connection that submits the code over an unpinned TLS session can be MITM'd on the same LAN.
- Private-network requests fail closed if the certificate fingerprint is missing or does not match. After a successful pair, the app should persist that pin (Keystore / prefs) for later requests.
- The loopback/same-origin fence on the desktop panel does not stop another process running as the same user from calling `127.0.0.1`. If the host is compromised, this plugin cannot save you; run dsh as a least-privilege user and keep `18640` off untrusted networks.
- dsh itself is powerful; this plugin does not sandbox the agent.
- Approvals received on the phone do not include tool arguments. By default the notification offers only **Reject**; approving requires opening the app. An optional setting enables **Allow once** in the notification on Android 12+ only, and it requires unlocking the device.

## Do

- Pair only on networks you trust; prefer QR / pairing code over sharing URLs widely.
- For untrusted LANs, enable「配对需本机确认」so a scanned code still needs a click on this computer. Turning the setting off does not activate devices already waiting for approval.
- Revoke lost or unused devices from the Web UI「手机连接」panel immediately; use「吊销全部设备」if a token may have leaked.
- Keep `18640` off the public Internet. The panel shows the listen address and reachable networks — treat a red warning as “this is not a trusted LAN”.
- Prefer short-lived pairing codes; do not paste tokens into chat logs or screenshots.
- After uninstalling the app, re-pair by scanning a **fresh** QR from the panel. If the host reports the old device name still exists, the updated official app offers an explicit replace: the same pairing code revokes the stale record and issues a new token (or waits for your panel approval when「配对需本机确认」is on). With an app build that predates this flow, revoke the old record from the panel first — a stale record left by an uninstall is harmless but occupies the name.
- Leave `allowMobileDangerFullAccess` off unless you specifically need mobile「完全访问」and accept that the paired phone can then run with full host access.
- Watch the host log for the audit lines `dsh-links: device revoke device=<8>`, `dsh-links: device revoke-all removed=<n>`, `dsh-links: device replace device=<8>[,<8>] new=<8>`, `dsh-links: device replace approve device=<8> replaced=<8>[,<8>]`, and `dsh-links: permission preset -> <preset> session=<8> device=<8>`; they record revocations, replacements, and permission-preset changes (short ids only, never tokens).

## Do not

- Remote images in conversations are not loaded automatically, to prevent prompt-injected output from exfiltrating data via image URLs. Only public HTTPS hosts are allowed even after the user taps to load. A setting under Privacy enables auto-load.

- Expose `0.0.0.0:18640` to untrusted networks.
- Treat Cloudflare Tunnel, Tailscale, frp, or the experimental DLP/1 relay as a supported public Beta feature.
- Commit `local.properties`, keystores, `state.json`, or any `*.token` / `*.pem` files.
- Screenshot or share the **phone connection QR** while it is valid: it can add a device. When remote access is on, the QR also carries a one-time bootstrap seed; a remote first pairing through it always waits for approval on this computer, but still refresh the QR (it rotates after use or expiry) if it leaks. If remote credentials may have leaked, use「重置远程身份」on the panel.
- Rely on Host/Origin rewriting as authentication — auth is the device token.
- Expect a mobile permission change or file download to succeed from a device that is not currently viewing that session: both require an active SSE subscription for the session, and will fail with `403` otherwise.
- Assume a valid token grants mobile `danger-full-access`: it is refused unless the host enables `allowMobileDangerFullAccess`.

## Android pairing (client checklist)

The plugin already puts `certFingerprint` in the QR / loopback `pair-info` payload. The official app must:

1. Compare that fingerprint with the live TLS peer **before** `POST /dsh-link/pair`.
2. Abort on mismatch — do not “continue with a warning”.
3. Persist the pin after a successful pair and reuse it for later requests.

This repository cannot enforce those steps. A pairing code sent over an unpinned first connection is the one LAN attack that does not need a stolen token.

On the host, enable「配对需本机确认」so an unexpected device still needs a click on this computer.

## iOS formula renderer

The formula and Mermaid page (`apps/ios/App/Resources/Math/shell.html`) keeps `style-src deeplinks-asset: 'unsafe-inline'`. A hash or nonce cannot replace it.

KaTeX 0.18.4 writes presentation styles while laying out each formula. `katex.min.js` assigns element `style` properties (height, width, margins, borders, and error color) from the formula metrics, and its MathML path calls `setAttribute("style", ...)` with values such as a computed border width. Those strings change with the formula, so they are not known when the bundled page is built. A CSP hash only matches a complete `<style>` element present in the page source; it does not authorize later style attributes. A nonce would have to be attached to every style node KaTeX creates, which the bundled library does not do.

The rest of the page stays closed: `default-src 'none'`, scripts only from the app-defined `deeplinks-asset:` scheme, no network, images, frames, objects, or forms. The web view is non-persistent, navigation leaves that scheme only when cancelled, and KaTeX is called with `trust: false`. The inline-style exception is limited to layout generated inside that locked page.

## Source and APK trust

- The public repository opens the `dsh-links` plugin, the DLP/1 relay source under `relay/`, the Android client source under `apps/android/`, and docs (MIT). Install only signed APKs from this project's GitHub Releases; the official signing certificate SHA-256 fingerprint is published in the README. The plugin is distributed from this repository (installed as a git source); installing it pulls only the plugin's declared package files, not `relay/` or `apps/android/`.
- Do not trust third-party rebuilds or sideloaded APKs that claim to be DeepLinks.

## Reporting

If you find a vulnerability in the plugin, docs, or the official APK, open a private report to the maintainer of [`lunaship/dsh-links`](https://github.com/lunaship/dsh-links) (or email the owner listed on the GitHub profile). Please include repro steps and impact; avoid filing public issues for exploitable auth or proxy bypasses until a fix is available.
