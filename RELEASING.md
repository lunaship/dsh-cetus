# Release 核对

GitHub `lunaship/dsh-cetus` = **插件源码、DLP/1 中继源码（`relay/`）、Android 源码（`apps/android/`）与文档**。不得提交 `state.json`（含插件的远程主机密钥）或服务器凭据。

**分发方式：全部走本仓库**。插件不发布到 npm registry，用户以 git 源安装（`dsh plugin add github:lunaship/dsh-cetus`，见 `README.md`）；中继单独部署（`relay/README.md`），不随插件发版；Android 只发本机签名 APK，附在 `app-v*` Release 上。iOS 源码在 `apps/ios/`，随 `ios/main` 集成；TestFlight 与海外 App Store 要等阶段 9 开通账号后再做，现在不算已发布。

## 插件改动

- [ ] 本仓 `git ls-files` 不得出现 keystore、token、`state.json`、服务器凭据、`local.properties` 或任何私密配置。
- [ ] `npm test` 通过；`npm run prepack`（= build-client + 全量测试）通过，且 `pnpm build:client && git diff --exit-code -- src/client.js` 干净（`src/client.js` 是提交进仓库的产物）。
- [ ] 合并到 `main` 后，用户以 git 源重装插件并重启 host 才生效（见根 `AGENTS.md` 红线）。
- [ ] 每次准备让用户升级，都要打 `v<version>` tag，并同步更新 README 的安装命令。
- [ ] `main` 不是发布渠道。用户安装写死的 tag；`github:lunaship/dsh-cetus`（main）只给想跟开发版的人，不保证稳定。

## App 发版流程

- [ ] 确认 `apps/android/` 版本号已更新，CI 全绿（`CI - Android`：「单测 + lint + 截图校验 + assembleDebug + whitespace」）。色板或组件改过时，先在 Linux 上跑 `regen-screenshots`（`workflow_dispatch`）更新 `screenshotTestDebug/reference/`，再让截图校验变绿。
- [ ] 在 `apps/android/` 执行 `./gradlew :app:assembleRelease`，使用维护者本机密钥签名（签名环境自动读取）。
- [ ] 运行 `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`，指纹与根 `README.md` / `SECURITY.md` 记录一致。
- [ ] Android 正式包：`assembleRelease` 后**务必保存混淆映射表** `app/build/outputs/mapping/release/mapping.txt`（按版本命名，例如 `cetus-<versionName>-mapping.txt`）；用户报崩溃时用它 `retrace` 还原堆栈。映射表不进仓库、不发 Release 附件，只存档。
- [ ] 用真实 Android 设备完成配对、会话/SSE、审批、吊销、重启后重连验收；远程改动还要在蜂窝网络下跑一遍远程首配与 Wi‑Fi / 蜂窝切换。
- [ ] 创建 tag `app-v<versionName>`，打 GitHub Release（Pre-release），上传签名 APK 与 `app-release.apk.sha256`；Release 说明附版本号、APK SHA-256、证书 SHA-256、最低 Android 版本与安装说明。
- [ ] 校验和与 `shasum -a 256` 输出一致。
- [ ] 更新 `docs/COMPATIBILITY.md` 记录新组合。

## iOS

源码在 `apps/ios/`，集成分支是 `ios/main`。工程占位版本是 `MARKETING_VERSION` `1.0`、`CURRENT_PROJECT_VERSION` `1`（`apps/ios/project.yml`），bundle id 仍是 `dev.deeplinks.ios.debug`。**这不是已发布版本。** 阶段 9 开通 Apple Developer Program 之前，不打 iOS tag，不上传 TestFlight，不写已上架。

### 版本号

- 对外版本是 `MARKETING_VERSION`（`CFBundleShortVersionString`），和 Android 的 `versionName` 一样写进 `docs/COMPATIBILITY.md`。构建号是 `CURRENT_PROJECT_VERSION`（`CFBundleVersion`），每次准备上传 TestFlight 加 1；App 与三个扩展（通知服务、灵动岛、分享）用同一对数字。
- 正式 bundle id 在阶段 9 再定（方案里的例子是 `dev.deeplinks.ios`，扩展分别为 `.notification-service`、`.live-activity`、`.share`）。在此之前保持 debug id，不要把占位 id 写成已在 App Store Connect 登记。
- 准备给测试者升级时打 `ios-v<MARKETING_VERSION>` tag，指向实际上传的提交。tag 只表示「这一版准备送测」，不等于 TestFlight 已通过审核，更不等于 App Store 已上架。
- 不跟 Android 的 `app-v*` 绑在同一个 tag 上。插件、Android、iOS、推送网关各自记录，兼容组合写进 `docs/COMPATIBILITY.md`。

### TestFlight（手动触发）

仓库里还没有上传 TestFlight 的工作流。`CI iOS` 只做构建、单测和截图校验，签名是关掉的。开通账号之后：

- 推到 `ios/main`（或之后的 `main`）且 iOS 版本号有变化时，**由维护者手动触发**上传。不要在普通 push 上自动传包。
- 签名用 App Store Connect API Key。Key 放在维护者本机或 GitHub Actions Secrets，不进 git，不写进工作流明文，不出现在 Release 说明里。
- 内部测试先给维护者和核心测试者。外部测试要等 Beta App Review；公开链接通过后再由维护者填进根 `README.md` 的「iOS」小节。链接空着就留空，不要编造 URL。
- 审核备注按 `docs/ios/PLAN.md` I9.4：这是 DeepSeek Harness 的配套客户端，需要用户自己的电脑；提供「先看看演示」入口。名称和副标题不出现 DeepSeek。

### 密钥与账号

- Apple Developer Program（个人，付费）由维护者开通。阶段 9 之前用免费账号加模拟器，不把未开通写成已开通。
- APNs 认证密钥（`.p8`）在 Apple 后台只能下载一次。记下 Key ID 与 Team ID 后立即离线备份。文件权限 0600，只放推送网关本机；不进 git，不进 GitHub Secrets 以外的日志。泄露后按 `docs/rfc/0002-push-gateway.md` 吊销并重建。
- App Store Connect API Key（`.p8` 形态的 Issuer ID / Key ID / 私钥）同样只由维护者离线保管。
- 网关的 HPKE 私钥与 `.p8` 同一规则：不进仓库。真 APNs 送达留到阶段 9，在此之前不宣称推送已在生产验证。

### 核对清单

- [ ] `git ls-files` 不得出现 `.p8`、App Store Connect API Key、HPKE 私钥、`state.json` 或任何 Apple 凭据。
- [ ] `CI iOS` 全绿：构建（Debug 与 Release）、单测、截图校验。改了界面才用 `ios-regen-screenshots`（`workflow_dispatch`）更新基线，逐张人工看过再提交；不要在本地生成基线后直接当已核对。
- [ ] `apps/ios/project.yml` 的 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` 已按上面的规则更新；App 与三个扩展一致。
- [ ] 正式 bundle id、Push Notifications、App Groups、Time Sensitive Notifications、Keychain Sharing 只在付费团队开通后登记。未登记不要写「已配置」。
- [ ] 真机验收按 `docs/ios/PLAN.md` 阶段 8：局域网冷启动、前后台、Wi-Fi 与蜂窝切换、远程首配、审批、提问、推送四类、Live Activity、吊销。缺项就在 `docs/COMPATIBILITY.md` 标成未验证。
- [ ] 上传 TestFlight 由维护者手动触发；构建号比上次上传大 1。内部测试不写成外部测试，Beta 审核未过不写公开链接。
- [ ] 海外 App Store 另行核对截图、隐私营养标签与出口合规问卷。国区需要 App 备案，**不绑这次海外上架**，未备案不选中国大陆。
- [ ] 更新 `docs/COMPATIBILITY.md`：只记录已经核对过的提交与版本。未发布、未部署、未做真 APNs 送达，都维持「未发布」。

## 对外口径

- **Beta / Android only / Trusted LAN / 远程连接（DLP/1）实验性、默认关闭**；DSH 基线见 `docs/COMPATIBILITY.md`。
- 用户自行使用内网穿透仅为实验性个人部署，不是支持路径，也不提供安全或兼容承诺。
- 不得将 `18640` 直接暴露到公网；跨网络请用插件内置的远程连接（手机与电脑都只向外连中继）。
