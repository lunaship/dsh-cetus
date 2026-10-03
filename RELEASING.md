# Release 核对

GitHub `lunaship/dsh-links` = **插件源码、DLP/1 中继源码（`relay/`）、Android 源码（`apps/android/`）与文档**。不得提交 `state.json`（含插件的远程主机密钥）或服务器凭据。

**分发方式：全部走本仓库**。插件不发布到 npm registry，用户以 git 源安装（`dsh plugin add github:lunaship/dsh-links`，见 `README.md`）；中继单独部署（`relay/README.md`），不随插件发版；Android 只发本机签名 APK，附在 `app-v*` Release 上。

## 插件改动

- [ ] 本仓 `git ls-files` 不得出现 keystore、token、`state.json`、服务器凭据、`local.properties` 或任何私密配置。
- [ ] `npm test` 通过；`npm run prepack`（= build-client + 全量测试）通过，且 `pnpm build:client && git diff --exit-code -- src/client.js` 干净（`src/client.js` 是提交进仓库的产物）。
- [ ] 合并到 `main` 后，用户以 git 源重装插件并重启 host 才生效（见根 `AGENTS.md` 红线）。
- [ ] 每次准备让用户升级，都要打 `v<version>` tag，并同步更新 README 的安装命令。
- [ ] `main` 不是发布渠道。用户安装写死的 tag；`github:lunaship/dsh-links`（main）只给想跟开发版的人，不保证稳定。

## App 发版流程

- [ ] 确认 `apps/android/` 版本号已更新，CI 全绿（`CI - Android`：「单测 + lint + 截图校验 + assembleDebug + whitespace」）。色板或组件改过时，先在 Linux 上跑 `regen-screenshots`（`workflow_dispatch`）更新 `screenshotTestDebug/reference/`，再让截图校验变绿。
- [ ] 在 `apps/android/` 执行 `./gradlew :app:assembleRelease`，使用维护者本机密钥签名（签名环境自动读取）。
- [ ] 运行 `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`，指纹与根 `README.md` / `SECURITY.md` 记录一致。
- [ ] Android 正式包：`assembleRelease` 后**务必保存混淆映射表** `app/build/outputs/mapping/release/mapping.txt`（按版本命名，例如 `DeepLinks-<versionName>-mapping.txt`）；用户报崩溃时用它 `retrace` 还原堆栈。映射表不进仓库、不发 Release 附件，只存档。
- [ ] 用真实 Android 设备完成配对、会话/SSE、审批、吊销、重启后重连验收；远程改动还要在蜂窝网络下跑一遍远程首配与 Wi‑Fi / 蜂窝切换。
- [ ] 创建 tag `app-v<versionName>`，打 GitHub Release（Pre-release），上传签名 APK 与 `app-release.apk.sha256`；Release 说明附版本号、APK SHA-256、证书 SHA-256、最低 Android 版本与安装说明。
- [ ] 校验和与 `shasum -a 256` 输出一致。
- [ ] 更新 `docs/COMPATIBILITY.md` 记录新组合。

## 对外口径

- **Beta / Android only / Trusted LAN / 远程连接（DLP/1）实验性、默认关闭**；DSH 基线见 `docs/COMPATIBILITY.md`。
- 用户自行使用内网穿透仅为实验性个人部署，不是支持路径，也不提供安全或兼容承诺。
- 不得将 `18640` 直接暴露到公网；跨网络请用插件内置的远程连接（手机与电脑都只向外连中继）。
