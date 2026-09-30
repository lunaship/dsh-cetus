# Release 核对

GitHub `lunaship/dsh-links` = **插件源码、DLP/1 中继源码（`relay/`）、Android 源码（`apps/android/`）与文档**。不得提交 `state.json`（含插件的远程主机密钥）或服务器凭据。npm 包仍只包含插件文件，不含 `relay/` 或 `apps/android/`。中继单独部署（`relay/README.md`），不随插件发版。

## 发布前

- [ ] 本仓 `git ls-files` 不得出现 keystore、token、`state.json`、服务器凭据、`local.properties` 或任何私密配置。
- [ ] `npm test` 通过，`npm pack --dry-run` 的文件清单仅包含声明的插件发布文件。
- [ ] npm 已登录，包名与版本正确；发布后在干净 profile 以 `dsh plugin --profile web add dsh-links@<version>` 成功安装。
- [ ] 用真实 Android 设备完成扫码配对、会话/SSE、审批、吊销、重启后重连验收；远程改动还要在蜂窝网络下跑一遍远程首配与 Wi‑Fi / 蜂窝切换。
- [ ] APK 是正式签名产物；在 GitHub Release（`app-v*` tag）附版本号、SHA-256、最低 Android 版本和安装说明。
- [ ] Android 正式包：`./gradlew :app:assembleRelease` 后**务必保存混淆映射表** `app/build/outputs/mapping/release/mapping.txt`（按版本命名，例如 `DeepLinks-<versionName>-mapping.txt`）；用户报崩溃时用它 `retrace` 还原堆栈。映射表不进仓库、不发 Release 附件，只存档。
- [ ] `apps/android/scripts/release-apk.sh` 输出校验和与当前 tag 一致。

## npm 自动发布

本仓的 [`.github/workflows/publish-npm.yml`](.github/workflows/publish-npm.yml) 使用 npm Trusted Publishing（GitHub OIDC），不使用也不读取 `NPM_TOKEN`。只有发布 GitHub Release 时才会触发；手动重试必须显式输入既有 tag。

首次启用需要在 npm 完成一次性配置：

1. 在发布环境先用 `npm view dsh-links versions --json` 和
   `npm view dsh-links dist-tags --json` 核对 registry 当前状态。不要根据
   本地仓库推断包是否已存在；若包尚不存在，必须由包所有者明确执行首个
   `npm publish --access public --tag beta`。
2. 在 npmjs.com 的 `dsh-links` → **Settings** → **Trusted Publisher** 添加 GitHub Actions：Owner `lunaship`、Repository `dsh-links`、Workflow filename `publish-npm.yml`，并允许 `npm publish`。
3. 创建与 `package.json` 版本完全一致的 tag `v<package.json 的 version>` 并发布 GitHub Release。工作流会运行锁定依赖安装、测试、版本校验，随后发布。

预发布版本会按预发布标识发布到对应 npm dist-tag（例如 `0.1.0-beta.1` → `beta`）；非预发布版本发布到 `latest`。如 npm 侧尚未建立可信发布关系，工作流会失败，不会退回到长期 token。

## App 发版流程

1. 确认 `apps/android/` 版本号已更新，CI 全绿。
2. 在 `apps/android/` 目录执行 `./gradlew :app:assembleRelease`，使用维护者本机密钥签名。
3. 运行 `apksigner verify --print-certs apps/android/app/build/outputs/apk/release/app-release.apk | grep SHA-256`，把指纹更新到根目录 `README.md` 和 `SECURITY.md`。
4. 创建 tag `app-v<versionName>`，打 GitHub Release，上传签名 APK、`app-v<versionName>.apk.asc` 和校验和。
5. 更新 `docs/COMPATIBILITY.md` 记录新组合。

## 对外口径

- **Beta / Android only / Trusted LAN / 远程连接（DLP/1）实验性、默认关闭**；DSH 基线见 `docs/COMPATIBILITY.md`。
- 用户自行使用内网穿透仅为实验性个人部署，不是支持路径，也不提供安全或兼容承诺。
- 不得将 `18640` 直接暴露到公网；跨网络请用插件内置的远程连接（手机与电脑都只向外连中继）。
