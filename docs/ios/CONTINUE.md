# DeepLinks iOS 续作执行单（从 I4.8 收尾到上架）

> 版本：v1.1（2026-10-06）。放入仓库：`docs/ios/CONTINUE.md`。本轮收尾以桌面 `DeepLinks-收尾方案.md` 为准；与本文旧顺序冲突时以收尾方案和 PLAN v1.4 为准。
> 2026-10-06 起点：`origin/main` @ `b1054fa0`（已包含 `origin/ios/main` @ `b5b796ee`，另有一次 `push/` 依赖更新）。没有开着的 PR。
> 本文是 `docs/ios/PLAN.md`（v1.4）的**续作执行单**。阶段 A 已完成的子项仍以 PLAN 第 11 节为准。规则、红线、设计约束以 PLAN 第 2、3 节和 `apps/ios/AGENTS.md` 为准。

---

## 0. 接手须知（先读）

1. 先读：`docs/ios/PLAN.md` 第 0–3 节、第 11 节执行记录、附录 A–C；`apps/ios/AGENTS.md`；`apps/ios/docs/visual-rules-ios.md`；`apps/ios/docs/page-mapping.md`；`docs/MOBILE_SYNC_CONTRACT.md`；`docs/rfc/0001-dlp1-remote-pipe.md`；`docs/rfc/0002-push-gateway.md`。
2. 分支与 PR：沿用 PLAN 规则。集成分支 `ios/main`；子项分支 `ios/i<阶段>.<序号>-<短名>`；PR 标题 `[iOS I<阶段>.<序号>] …`；影响插件、Android、中继、网关部署的改动对 `main`，由维护者合并。
3. 门禁：你的环境如果没有 Xcode，就以 CI（`ci-ios.yml`：iOS build / iOS unit tests / iOS screenshot check）为唯一门禁，不得在 PR 里写“本地已通过”。截图基线只能由 `ios-regen-screenshots.yml` 生成（PR 标题带 `[regen-ios-baselines]`）。
4. 每合并一个 PR，在 PLAN 第 11 节追加一行，格式和前面的记录一致（做了什么、偏差、squash 提交号）。
5. 停下等人的点在每节末尾用 **停下** 标出；除此之外连续推进。
6. 现状一句话：阶段 0–4 的界面已经全部写完，但**从 I4.1 起没有在模拟器或真机上实际运行过**，验收只靠截图；不少页面有“能看不能用”的偏差。所以先做阶段 A（补漏与可运行性），再进入远程、推送、系统层和分发。

---

## 阶段 A：补漏与可运行性（进入阶段 5 之前必须完成）

### A0 收尾 I4.8（PR #114）

- 让 #114 的三个 iOS 检查全绿；横屏审查列表的两次修复（`fda6507`、`e21ba59`）保留。
- 合并后补执行记录。之后所有 PR 基于包含 I4.8 的 `ios/main`。

### A1 CI 端到端冒烟：让 App 真正跑起来（新增，最高优先级）

目的：用自动化代替“没人运行过”。在 macOS CI 上启动**真实插件 + 假 Host**，用模拟器里的 App 走完主流程。

1. 复用 I3.2 的 `scripts/export-contract-fixtures.mjs` 里的假 Host，抽成 `scripts/ios-e2e-host.mjs`：在 `127.0.0.1` 启动插件，输出配对二维码载荷（JSON）到一个文件；提供“触发一条审批 / 一条提问 / 一段流式输出 / 完成”的控制接口（只监听回环）。
2. 新增 UI 测试 target `DeepLinksUITests`（XCUITest），启动参数 `-e2eQRPayload <path>` 让 App 跳过相机，直接用这份载荷配对（只在 Debug 构建可用，Release 编译期移除）。
3. 用例（每条独立、可重跑）：
   - `testPairAndInbox`：配对 → 电脑上批准（脚本自动批准）→ 首页出现会话。
   - `testStreamAndSend`：打开会话 → 看到流式文本 → 发送一条消息 → 消息出现在流里。
   - `testApproval`：脚本发起审批 → 决策栏出现 → 点“允许一次” → 插件侧收到 allow。
   - `testQuestion`：脚本发起两题提问 → 依次作答 → 插件侧收到答案。
   - `testReconnect`：脚本重启插件 → App 显示断线状态槽 → 恢复后续传，无重复消息。
   - `testRevoke`：脚本吊销设备 → App 回到欢迎页且本地凭据清除。
4. 新工作流 `ios-e2e.yml`：PR 改动 `apps/ios/`、`src/` 时运行；失败上传 `xcresult` 和插件日志为 artifact。加入 PR 必须通过的检查。
5. 规则补充（写进 `apps/ios/AGENTS.md`）：凡是截图测试使用 `staticSnapshot` 或换成实心底的页面，生产代码路径必须至少有一个单测或 UI 测试覆盖；PR 里列出“截图路径 ≠ 生产路径”的差异清单。

验收：6 条 UI 测试在 CI 连续 3 次全绿；执行记录写明耗时。

### A2 截图矩阵补齐

现状（`apps/ios/Tests/__Snapshots__/`）：

| 测试类 | 现有 | 深色 | 英文 |
|---|---|---|---|
| ChatSheetSnapshotTests（5.x） | 13 | 0 | 0 |
| NewTaskSnapshotTests（3.x） | 4 | 0 | 0 |
| ReviewSnapshotTests（6.x） | 6 | 0 | 0 |
| SettingsSnapshotTests（7.x） | 7 | 0 | 0 |
| TrajectorySnapshotTests（4.7） | 1 | 0 | 0 |
| WelcomeSnapshotTests（1.2） | 1 | 0 | 0 |
| ComponentSnapshotTests | 36 | 9 | 0 |
| 其余（Inbox / Chat / Composer / StatusSlot / Pairing） | 已有深色与英文 | | |

要做：

1. 上表前 6 类补齐：浅色 / 深色 × 中文 / 英文；每类再加一张最大辅助字号（`.accessibility3`）。
2. **所有类**新增“降低透明度”变体（至少每页一张，浅色中文）；新增“增强对比度”变体（每模块一张）。
3. 截图矩阵写进 `apps/ios/docs/visual-rules-ios.md` 第“截图矩阵”节，并加一个脚本 `apps/ios/scripts/check-snapshot-matrix.mjs`：按页面编号检查每页是否有规定的变体，缺了 CI 失败。
4. 每个 PR 只补 1–2 个测试类，避免一次重录过多基线难以评审。

验收：矩阵脚本通过；PR 附“页面编号 ↔ 新基线”对照表。

### A3 功能补齐（按页面编号）

逐条实现，接口以 `docs/MOBILE_SYNC_CONTRACT.md` 为准；插件已有的手机接口见附录 E。

| 编号 | 页面 | 要补的 | 用到的接口 |
|---|---|---|---|
| A3.1 | 7.2 电脑与配对 | 解除配对：调用 `POST /dsh-link/mobile/revoke`（本机设备），成功后删凭据回欢迎页；失败不删凭据。**改名只在本机生效**（v4 定义），写本地存储即可 | `mobile/revoke`、`mobile/devices` |
| A3.2 | 7.3 连接诊断 | 读真实诊断，替换占位 | `GET mobile/diagnostics` |
| A3.3 | 7.6 对话默认 | 读写真实设置，选项以插件声明为准 | `GET mobile/settings`、`POST mobile/settings/update`、`mobile/agent-presets`、`mobile/models` |
| A3.4 | 7.7–7.11 模型与余额 | 余额、供应商列表、添加供应商、更换密钥（空输入框，不回显）、获取模型、余额提醒 | `mobile/balance`、`mobile/providers`、`providers/add`、`providers/credential`、`providers/discover`、`providers/models`、`mobile/llm-models` |
| A3.5 | 7.15 上次崩溃 | 导出用系统分享表，内容先经脱敏函数（去掉路径、token、消息正文），留在本机 | 无 |
| A3.6 | 4.4 回答问题 | “上一题 / 跳过（仅可选题）/ 下一题”，多题一次提交；“之后用户又发过消息就不再问”与 Android 对齐 | `GET .../requests`、提问提交 |
| A3.7 | 4.5 目标 | 暂停、编辑、清除目标；断线状态槽的“重试”按钮 | 以合同中的目标接口为准，没有接口则删掉入口并写明 |
| A3.8 | 4.1 历史 | 向上滚动用 `beforeSeq` 加载更早消息（插件 `src/local-rpc.js` 已支持） | `.../history?beforeSeq=` |
| A3.9 | 2.1 首页 | 等待中且无请求快照的行文案改为与 Android 一致的“等你批准 / 等你回答”（按类型） | 无 |
| A3.10 | 配对 | 同一台电脑重新配对时去重：按电脑身份（证书指纹或插件给的 hostId）替换旧记录，不新增 | `POST /dsh-link/pair` |
| A3.11 | 提问数据 | DLModels 保留提问的原始 JSON，回填时原样使用，不再重新编码（避免丢未知字段） | 无 |
| A3.12 | 5.6 附件 | 增加“文件”来源（`UIDocumentPicker`）；图片裁剪不做，写明 | 以合同为准 |

规则：设计稿或 PLAN 要的数据插件没有时，按“删掉该元素”处理并在 PR 写明，不自行发明接口；需要插件新增接口的，单独开对 `main` 的 PR 并停下等维护者。

验收：每条有单测或 A1 的 UI 测试覆盖；截图同步更新。

### A4 遗留小项

- SSE 重连退避加入随机抖动（±20%），与 Android 行为在 PR 中对比说明。
- 公式渲染的 `style-src 'unsafe-inline'`：评估能否改为 hash 或 nonce；不能则在 `PRIVACY.md` / 安全说明中写明理由。
- `PrivacyInfo.xcprivacy`：对照目前用到的 API 补全理由。

### A5 真机验证改到收尾 G7（不再挡住阶段 5）

`apps/ios/docs/device-check.md` 已写好，但 2026-10-06 收尾方案把真机点按、付费签名和线上送达整段放到 G7。开发过程不要求维护者安装 App、提供截图或开通 Apple 账号。

- 允许编写、扩展并运行自动化 XCUITest、单测和截图工作流。
- 真机结果留空。G6 再把安装说明换成最终构建，并标明「自动验证覆盖的部分」和「仍需真机的部分」。
- 未完成 A5 不再阻止 G4 远程实现。远程仍须先完成 G4.0 本地传输验证。

---

## 阶段 5：远程连接（DLP/1）

### 已核实的事实（替换 PLAN I5.2 的“待核实”）

RFC 0001 已定为“**一流一 WSS**”：每条流单独一条 WebSocket，中继只把两条 socket 拼接起来，不做多路复用、没有流控帧（见 RFC §4.2、§5.4 与修订表第 2 条）。因此方案 A（本地回环桥）中“每来一条 TCP 连接就开一条 DLP/1 流”与协议完全对应，**优先做方案 A**。

### I5.1 帧编解码（`DLRemote`）

- 按 RFC 0001 实现控制帧、会合密钥、MAC（绑定 App 生成的 nonce 和时间戳；`sid` 由中继分配，不进 MAC）。
- 跑通 `testdata/dlp1/` 全部向量（与 JS / Go / Kotlin 同一份）；模糊测试：随机输入不崩溃。

### I5.2 / 收尾 G4.0：先做本地传输验证

文档结论仍是方案 A，但回环鉴权不能照搬预览代理的 URL 密钥：内层是 TLS，桥在转发前看不到 HTTP 路径。G4.0 用本地 `relay/`、隔离插件和测试证书证明鉴权、双层钉扎、关闭压缩、有界背压、关闭释放和连接预算。记录进 `docs/ios/I5.2-spike.md`。

- 不连 `relay.dshlinks.com`，不改线上中继。
- 找不到系统 API 能在转发内层 TLS 之前完成的鉴权，或触发 spike 四条失败条件：停止 G4.1 / G4.2，提交证据，不自行改 SwiftNIO。
- 通过之后才做 G4.1（回环桥、连接池、统一接入）和 G4.2（首配、bootstrap 三态、选路）。真机蜂窝切换留在 G7。

---

## 阶段 6：推送（可与阶段 5 并行）

网关与插件部分不依赖 iOS 代码，可在阶段 A 完成后就开始。

### I6.1 / I6.2 与收尾 G5

RFC 已锁定：HPKE base 模式、X25519 / HKDF-SHA256 / ChaCha20-Poly1305，`sealed` 为 JSON 对象，附加数据 `dlpush/1 token|` 与 `dlpush/1 content|` 分离。网关本地测试已在 I6.2 合入。插件出口和 iOS 接入仍未做。

安全审查是 G5 合并进 `main` 之前的条件，不是写代码之前的停点，也不放到 G7。实现和自动验证完成后，把 RFC、接口、数据流和测试交给维护者审查；审查通过后由维护者合并。待审期间可以继续用本地假 APNs 做不依赖该合并的验证。不部署网关，不读取真实 `.p8`。

### I6.2 网关（`push/`，Go，对 `main`）

- 按 PLAN I6.2。补充：
  - 生成并提交 `testdata/push/hpke/rfc9180-a2-base.json`（RFC 9180 A.2.1 官方向量）和 `dlpush-v1-*.json`；`testdata/push/content/` 的 AES-256-GCM 向量；两类各含篡改负例与“用错附加数据前缀必须失败”负例。
  - 与 APNs 保持一条 HTTP/2 长连接；JWT 50 分钟刷新一次。
  - 网关日志不打印 token、`sealed`、密文。
- 门禁：`gofmt -l . && go vet ./... && go build ./... && go test ./... -race`（`ci-push.yml`）。

### I6.3 插件推送出口（对 `main`，**停下**做安全审查）

按 PLAN I6.3 原文（`src/push-sink.js`、`POST/DELETE /dsh-link/mobile/push/register`、能力 `push: { v: 1 }`、面板开关、测试与文档）。

### I6.4 iOS 推送接入 + I6.5 免费测试

按 PLAN I6.4 / I6.5。补充：

- A1 的 E2E 增加：插件 → 本地网关 → 假 APNs，取出 payload 后用 `xcrun simctl push` 投递，验证 NSE 解密与点击跳转（`testPushApprovalOpensSession`）。
- 免费账号没有 APNs 能力：`aps-environment` 只在付费团队的签名配置里打开，免费签名构建不带推送能力且不报错。

---

## 阶段 7：系统层

按 PLAN I7.1–I7.3 原文执行。补充约束：

- Live Activity 只推不敏感字段（状态、步数、开始时间、等待数量、会话引用）；标题由扩展从 App Group 快照本地查。锁屏和灵动岛上没有任何“允许”按钮，只有“打开 App”。参考设计稿 `8.4-live-activity` 与 `8.5-dynamic-island`。
- 分享扩展：不联网、不读 Keychain；选择会话后只预填，不发送。
- 每个扩展都要有截图测试（浅 / 深）和至少一个单测；Live Activity 用 `ActivityKit` 的预览快照。

---

## 阶段 8：质量加固

按 PLAN 阶段 8 原文。补充：

- 性能测试放进 CI：XCTest `measure` 测冷启动（有快照）与 3000 条消息滚动，设基线；超出 20% 失败。
- 稳定性五种情况（断网、证书变化、token 吊销、电脑重启、插件升级）都写成 A1 的 UI 测试。
- 真机验收清单写进 `docs/RC1_CLOSED_BETA_TEST_PLAN.md` 的 iOS 部分，由维护者执行（**停下**）。

---

## 阶段 9：分发（全部需要维护者，agent 只准备材料）

agent 准备：

1. `apps/ios/docs/release-checklist.md`：I9.1–I9.6 每一步的操作清单（App ID、能力、App Group、Keychain 分组、`.p8` 备份、网关部署、App Store Connect 字段）。
2. App Store 文案（中英）、审核备注草稿（说明需要用户自己的电脑、演示模式入口、原生 API 客户端、不属于 4.2.7）、隐私营养标签填写建议、出口合规问卷答案草稿。
3. 商店截图：用演示数据由 CI 生成 6.9 / 6.5 英寸 iPhone 与 13 英寸 iPad，浅 / 深各一套。
4. TestFlight 上传工作流（手动触发，签名用 App Store Connect API Key，存 GitHub Secrets），先以 dry-run 形式合入。

维护者执行：付费开通、密钥生成与备份、网关上线（香港服务器，按 PLAN I6.6）、提交 TestFlight 与审核（**停下**）。

---

## 附录 E：插件现有手机接口（`main` @ `3ff06c5`，从 `src/` 提取）

`/dsh-link/pair`、`/dsh-link/mobile/bootstrap`、`sessions`、`sessions/search`、会话子路径（history / prompt / requests / changes / file / tree 等，见合同）、`events`、`previews`、`preview-detections`、`workspaces`、`workspaces/delete`、`agent-presets`、`models`、`llm-models`、`settings`、`settings/update`、`balance`、`providers`、`providers/add`、`providers/credential`、`providers/discover`、`providers/models`、`schedules`、`diagnostics`、`devices`、`revoke`。

（电脑面板用的 `/dsh-link/pair-approve`、`revoke-all`、`remote-*`、`workspace-approve` 等不对手机开放，iOS 不得调用。）

## 附录 F：维护者事项（按时间）

| 时间点 | 事项 |
|---|---|
| 收尾 G4.0 失败时 | 决定是否离开方案 A；通过则不必再确认 |
| 收尾 G5 合并前 | 安全审查 RFC 0002 与插件推送出口；不需要真机或真实 APNs |
| 收尾 G7 | 真机执行更新后的 `device-check.md`；定深色主按钮色；Wi-Fi ↔ 蜂窝；付费开通、`.p8`、网关上线、TestFlight |
| 全程 | 合并对 `main` 的 PR，以及之后的 `ios/main → main` |
