# DeepLinks 推送网关 RFC（DLPUSH/1）

> 状态：草案（阶段 1 / I1.3，按 PLAN v1.3 修订）——停下等维护者安全审查后再进入 I6.2 实现。  
> 写入：`docs/rfc/0002-push-gateway.md`  
> 来源：`docs/ios/PLAN.md` 阶段 6（v1.3）；格式参照 `docs/rfc/0001-dlp1-remote-pipe.md`。  
> 执行者须知：本文 §5 为协议合同（逐字节），§6–§8 为实现与红线。**有歧义时以 §5 为准，并在 PR 描述里提出，不要自行发明。**

---

## 1. 一句话结论

手机离开 App 后，由用户电脑上的插件把**端到端加密**的通知密文交给开源、无状态的推送网关；网关只负责把密文转给 APNs。网关看不到通知内容，也没有任何路径向电脑发命令。批准与回复仍走原来的局域网 / Tailscale / DLP/1 中继。

---

## 2. 为什么要做

- iOS 后台会断开 SSE；没有推送就无法在离开 App 后提示审批、提问、完成、失败。
- 不用 OneSignal 等第三方：内容与设备身份不应交给闭源中介。
- 官方网关可自建替换；默认关闭，用户主动开启。

---

## 3. 威胁模型与目标

**信任边界**

| 角色 | 可信度 | 可见 / 可做 |
|---|---|---|
| 插件（用户电脑） | 用户侧可信 | 持有端到端密钥 `K`；构造明文；持有封装后的 APNs token（打不开） |
| 推送网关 | **不可信** | 打开 HPKE 封装拿到 APNs token；向 APNs 发密文；看不到明文；不能伪造可解密通知 |
| APNs | 不可信中转 | 看到通用标题 / 正文模板 + 密文字段；看不到 `K` |
| 手机 App / NSE | 用户侧可信 | 持有 `K`；解密并展示；点击后走既有 HostClient |

**网关被攻破的最坏结果**：通知丢失或延迟；泄露「某设备在某时刻收到推送」的元数据；**看不到内容**；**伪造不了**可被 NSE 解密的通知（没有 `K`）。

**不做**

- 网关不存用户数据、不提供注册库、不向电脑下发任何命令。
- 通知栏不提供「允许 / 拒绝」动作（只能打开 App）。
- 锁屏不显示命令、文件名或会话标题原文（标题由本机按 `sessionRef` 查）。

---

## 4. 架构

```text
插件（用户电脑）──① 加密内容 + 封装 token──▶ 推送网关（官方，开源）──② HTTP/2 + JWT──▶ APNs ──③──▶ iPhone（NSE 解密）
手机 ──④ 批准 / 回复（局域网 / Tailscale / 中继）──▶ 插件
```

1. 插件在设备**没有**活跃前台 SSE 时，才为该设备发推送。
2. 推送通道只有下行。
3. Live Activity 更新走同一网关（`kind: la-*`），content-state 只含非敏感字段。

部署（维护者，阶段 9）：香港服务器；域名 `push.dshlinks.com`；Caddy TLS 反代到本机端口；与 `relay.dshlinks.com` 同机时必须是独立进程与独立 systemd 服务。

---

## 5. 协议合同（DLPUSH/1）

### 5.1 版本与标识

- 协议名：`dlpush/1`（出现在 HPKE `info` / AAD 前缀与内容加密 AAD 前缀中，见 §5.3–§5.4）。
- 明文 JSON 字段 `v` 固定为整数 `1`。
- 网关 HTTP API 前缀：`/v1/`。

### 5.2 密钥

| 密钥 | 生成方 | 存放 | 用途 |
|---|---|---|---|
| 网关 HPKE 密钥对（X25519），带 `kid` | 维护者 | 私钥只在网关；公钥在 `GET /v1/keys` 公开，并编进 App | 手机把 APNs token 封装给网关 |
| 端到端内容密钥 `K`（32 字节） | 手机，每台设备一把 | 手机 Keychain（与 NSE 共享 access group）；插件 state（随设备吊销删除） | 加密通知内容 |
| APNs `.p8` | 维护者在 Apple 后台生成 | 只在网关的环境变量 / 0600 文件 | 网关向 APNs 签 JWT |

### 5.3 封装 token（手机 → 插件 → 网关）

手机构造明文对象（UTF-8 JSON，键顺序不限；实现序列化后作为 HPKE plaintext）：

```json
{
  "apnsToken": "<小写 hex 的 device token>",
  "env": "sandbox" | "production",
  "bundleId": "<App bundle id>",
  "laToken": "<可选，Live Activity push-to-start token，缺省则省略键>"
}
```

**算法组合（已定，v1）**：RFC 9180 **base 模式**（`mode_base` = `0x00`）

| 角色 | ID | 名称 |
|---|---|---|
| KEM | `0x0020` | DHKEM(X25519, HKDF-SHA256) |
| KDF | `0x0001` | HKDF-SHA256 |
| AEAD | `0x0003` | ChaCha20-Poly1305 |

- CryptoKit：只用系统预置 `HPKE.Ciphersuite.Curve25519_SHA256_ChachaPoly`，**不自拼**套件。
- Go（`cloudflare/circl`）：`hpke.NewSuite(hpke.KEM_X25519_HKDF_SHA256, hpke.KDF_HKDF_SHA256, hpke.AEAD_ChaCha20Poly1305)`。
- `info` = UTF-8 `"dlpush/1 token"`（含空格）。
- HPKE `aad` = UTF-8 `"dlpush/1 token|" + kid`（**域分离**：与内容加密 AAD 前缀不同，见 §5.4）。
- 每个 HPKE 上下文只封装 **一条** 消息（序号 0）。
- 算法组合与 `kid` **绑定**：换组合（例如日后 X-Wing）只能发新 `kid`，不在同一 `kid` 下协商。网关遇未知 `kid` 返回 **400**，不尝试其他组合。

**线上格式**（字段 `sealed` **必须是 JSON 对象**；无填充 base64url）：

```json
{
  "v": 1,
  "kid": "<string，与选用的网关公钥一致>",
  "enc": "<base64url，32 字节 encapsulated key>",
  "ct": "<base64url，HPKE ciphertext>"
}
```

- `sealed` **不得**是把上述对象序列化后的字符串。网关若收到字符串类型 → **400**。
- 插件只保存 `sealed`（以及端到端 `k` 等），**打不开**它。网关用 `kid` 对应私钥 Open。

**共享测试数据** `testdata/push/hpke/`（向量本体在阶段 6 由 Go 生成；本仓库先放占位与说明）：

1. `rfc9180-a2-base.json`：RFC 9180 附录 A.2.1 官方向量。Go 完整验证（含固定临时密钥的 Seal）；CryptoKit 无法注入临时密钥，只用 `skRm + enc` 验证 Open。
2. `dlpush-v1-*.json`：用本项目的 `info` / `aad` / 线上格式由 Go 生成；iOS 验证 Open 得相同明文。
3. 负例与互通：篡改 `enc` / `ct` / `aad` / `kid` 必须失败；**用内容加密前缀 `"dlpush/1 content|"` 当作 token aad 解密必须失败**；CI 中可 iOS Seal → Go Open（或内嵌期望密文）。

### 5.4 内容加密（插件 → 网关 → APNs → NSE）

```text
ct = AES-256-GCM(
  key   = K,                    // 32 bytes
  nonce = random 12 bytes,
  aad   = "dlpush/1 content|" || deviceId,   // UTF-8 前缀 + 设备 ID 原文字节
  plaintext = UTF8(JSON)
)
```

**附加数据域分离（硬性）**

| 用途 | AAD 前缀（UTF-8） | 后缀 |
|---|---|---|
| Token 封装（HPKE） | `dlpush/1 token\|` | `kid` |
| 内容加密（AES-GCM） | `dlpush/1 content\|` | `deviceId` |

- 两端**不得**共用同一个 AAD 构造函数；前缀字符串必须是两个独立常量。
- 负例（各至少一条，放在对应目录）：用另一种前缀去解密 / Open **必须失败**。

传输时内容 `ct` 为 `nonce || ciphertext || tag` 的 base64（标准，无换行）；APNs payload 字段名 `e`。

明文 JSON：

```json
{
  "v": 1,
  "type": "approval" | "question" | "completed" | "failed" | "stopped",
  "sessionId": "<string>",
  "title": "<string，可给 NSE 换标题；锁屏模板仍用通用文案>",
  "tool": "<可选，仅 approval>",
  "ts": <unix 秒，整数>
}
```

NSE 规则：

1. 解密成功且 `now - ts ≤ 15 * 60`：用明文替换通知标题与正文；`tool` **不**在锁屏展示。
2. `ts` 超过 15 分钟：显示通用文案。
3. 解密失败：显示「DeepLinks 有新的任务动态」。

**共享测试数据** `testdata/push/content/`（阶段 6 由 Go 生成；本仓库先放占位）：正例、过期 / 失败兜底、以及「误用 token 前缀」负例。

### 5.5 网关 HTTP 接口

#### `POST /v1/push`

请求 JSON：

```json
{
  "kid": "<string，须与 sealed.kid 一致>",
  "sealed": {
    "v": 1,
    "kid": "<string>",
    "enc": "<base64url>",
    "ct": "<base64url>"
  },
  "kind": "alert" | "la-update" | "la-start" | "la-end",
  "ct": "<base64 nonce||ciphertext||tag，内容密文，非 HPKE>",
  "collapseId": "<string，≤ 64 字节建议>",
  "priority": "high" | "normal",
  "expiresIn": <秒，正整数>
}
```

`sealed` **必须**为上述 JSON 对象。若类型为字符串 → **400**。顶层 `kid` 与 `sealed.kid` 不一致 → 400。

响应：

| 状态 | 含义 |
|---|---|
| 200 | 已接受并转交 APNs（或假服务器） |
| 400 | 请求格式 / `sealed` 非对象（含字符串）/ `kid` 不符 / 解密 sealed 失败 |
| 410 | APNs 报告 token 失效（`BadDeviceToken` / Unregistered）；插件应删除该设备 `sealed` |
| 429 | 限流 |

无其他成功码；响应体可含 `{ "ok": true }` 或 `{ "error": "<短码>" }`，**不得**回显 `sealed` / `ct` / token。

#### `GET /v1/keys`

```json
{
  "keys": [
    { "kid": "<current>", "publicKey": "<base64 raw 32-byte X25519>" },
    { "kid": "<previous>", "publicKey": "<base64 ...>" }
  ]
}
```

轮换期两把并存；无上一把时数组长度 1。

#### `GET /healthz`

```json
{ "ok": true, "version": "<semver or git describe>" }
```

**禁止**：任何注册、查询设备、列出 token、持久化用户数据的接口。网关无数据库。

### 5.6 APNs 请求（网关构造）

#### `kind: alert`

- Header：`apns-push-type: alert`
- `apns-priority`: `10`（审批、提问）或 `5`（完成 / 失败 / 停止）；与请求 `priority` 映射：`high`→10，`normal`→5
- `apns-collapse-id` = 请求 `collapseId`
- `apns-expiration` = now + `expiresIn`
- Body：

```json
{
  "aps": {
    "alert": { "title": "DeepLinks", "body": "有新的任务动态" },
    "mutable-content": 1,
    "thread-id": "<会话哈希，稳定短串>",
    "interruption-level": "time-sensitive"
  },
  "e": "<ct base64>",
  "k": "<kid>"
}
```

`interruption-level: time-sensitive` **只**给 `type` 为 `approval` / `question` 的推送（插件侧在构造前决定）；完成类用默认级别且可不带该字段。需要 App 开启 Time Sensitive Notifications 能力。

#### `kind: la-update` / `la-start` / `la-end`

- `apns-push-type: liveactivity`
- topic：`<bundleId>.push-type.liveactivity`
- content-state **只允许**：

```json
{
  "state": "<string enum，实现锁定>",
  "step": <int>,
  "startedAt": <unix 秒>,
  "waitingCount": <int>,
  "sessionRef": "<不透明序号>"
}
```

标题由 Widget 从 App Group 按 `sessionRef` 本地查找。命令、文件名不得出现。

### 5.7 防滥用与隐私

- 限流键：`sha256(sealed)`（内存计数器）。默认：每分钟 10 条、每小时 120 条（环境变量可配）。
- 日志只记：计数、HTTP 状态、耗时、`kind`、限流命中。**不记** `sealed`、`ct`、APNs token、客户端 IP、`deviceId`、明文。
- APNs 410 / `BadDeviceToken`：网关对插件原样回 410。

### 5.8 密钥轮换

1. 网关新增密钥对 → `GET /v1/keys` 同时返回新旧。
2. 新版 App 用新 `kid` 重新封装并 `POST .../push/register`。
3. 旧 `kid` 保留至少 **90 天**再下线。
4. `.p8` 怀疑泄露：Apple 后台吊销并重建；网关换环境变量重启。

### 5.9 可自建

Fork 使用自己的 bundle id、`.p8`、网关地址与公钥。App「高级」设置可填自定义网关地址与公钥（Debug / 自编译默认可见；官方构建隐藏）。

---

## 6. 插件侧规则（I6.3，对 main，安全审查后合并）

### 6.1 文件与能力

- 新文件 `src/push-sink.js`：订阅主机事件差分；无前台 SSE 才推送。
- `collapseId = deviceId + sessionId + type`；同一会话 30 秒内最多一条完成类通知。
- 重试：网络错误指数退避最多 3 次；410 删 `sealed`；429 退避。
- `pluginCapabilities()` 增加 `push: { v: 1 }`。

### 6.2 手机 API

- `POST /dsh-link/mobile/push/register`：body `{ gateway, kid, sealed, k, prefs }`。`k` 仅在证书固定的 HTTPS 上传输。
- `DELETE /dsh-link/mobile/push/register`：注销。
- 写入经设备变更闸门；吊销设备时同步删除该设备推送数据。

注册字段：

- `gateway` 必须是无用户名、密码、查询参数和片段的 HTTPS 源地址。插件只保存其 origin。
- `sealed` 必须是 `{ v: 1, kid, enc, ct }` 对象；`enc`、`ct` 为 base64url。字符串或其他类型返回 400。
- 顶层 `kid` 必须与 `sealed.kid` 一致。`k` 是 32 字节内容密钥的 64 位十六进制。
- `prefs` 只含布尔值 `approval`、`question`、`completed`、`failed`。未开启的类型不发送。

关闭与轮换：

- 手机 `DELETE`、面板关闭、设备吊销或待确认设备过期，都会删除该设备完整 `push` 记录，并取消尚未发出的重试。
- 同一设备再次 `POST` 是完整替换，不保留旧 `k`、`sealed` 或合并时间。网关密钥轮换后，App 必须用新 `kid` 重新封装并注册。
- 410 只删除该设备的 `push` 记录，不删除设备 token、证书钉扎或局域网配对。

合同细节写入 `docs/MOBILE_SYNC_CONTRACT.md`「推送」节；`COMPATIBILITY.md` / `PRIVACY.md` 同步边界说明。

### 6.3 面板

「手机连接」每台设备显示「推送：已开启 / 未开启」，可一键关闭。

---

## 7. App / NSE 规则（I6.4）

1. `registerForRemoteNotifications` → 取 `GET /v1/keys` → HPKE 封装 → 生成 `K` 存 Keychain → 调插件注册。
2. 设置 7.4：总开关默认关；开启前说明页写明内容端到端加密、网关看不到。
3. 通知分类：`approval`、`question`、`completed`、`failed`；只有「打开」动作。
4. 点击：打开对应会话；不在列表则刷新；仍找不到则停首页并提示。
5. `hiddenPreviewsBodyPlaceholder` = `DeepLinks · 有新的任务动态`。
6. NSE：**不联网**、不读设备 token；只读共享 Keychain 的 `K`；控制内存与耗时。

---

## 8. 执行红线

1. 网关无状态、无用户数据库；禁止为「方便调试」记录密文或 token。
2. `.p8` 与 HPKE 私钥权限 0600，属主为服务用户；不进 git。
3. 中继错误码与网关错误码均不得诱导 App 删除局域网凭据；删推送 `sealed` 只认 410 / 用户关闭 / 设备吊销。
4. 不在通知或 Live Activity 上提供批准按钮。
5. 测试向量与 iOS / 插件 / 网关共用 `testdata/push/hpke/` 与 `testdata/push/content/`；改合同先改本 RFC 与向量。AAD 前缀域分离与「误用另一种前缀必须失败」的负例不得删。

---

## 9. 实现指引（摘要）

| 组件 | 位置 | 依赖（许可证） |
|---|---|---|
| 网关 | `push/cmd/dlpush`、`internal/{hpke,apns,limit,http}` | `sideshow/apns2`（MIT）、`cloudflare/circl`（BSD-3） |
| 插件 | `src/push-sink.js` + 注册路由 | 无新 npm 加密依赖（用 Node crypto） |
| iOS | App + `Extensions/NotificationService` | CryptoKit |

网关配置：`DLPUSH_LISTEN`、`DLPUSH_HPKE_KEYS`、`APNS_KEY_P8_PATH`、`APNS_KEY_ID`、`APNS_TEAM_ID`、`APNS_BUNDLE_ID`、`DLPUSH_RATE_*`。

门禁（在 `push/`）：`gofmt -l . && go vet ./... && go build ./... && go test ./... -race`。

---

## 10. 验收清单

- [ ] HPKE：RFC 9180 A.2.1 官方向量（Go Seal+Open；iOS Open）
- [ ] HPKE：`dlpush-v1-*` 项目向量；负例（篡改 enc/ct/aad/kid；误用 `content|` 前缀）
- [ ] 内容 AES-GCM：`testdata/push/content/` 正例；过期与失败兜底；误用 `token|` 前缀负例
- [ ] `sealed` 为字符串时网关返回 400
- [ ] 限流 429；APNs 假服务器 410 透传
- [ ] 前台 SSE 抑制推送；collapse 合并；吊销清理
- [ ] 日志脱敏检查
- [ ] 模拟器 `simctl push` 验证 NSE（无需付费账号）
- [ ] 真实 APNs 送达留到阶段 9

---

## 11. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-03 | I1.3 初稿：从 PLAN v1.1 阶段 6 抽出合同 |
| 2026-10-03 | 按 PLAN v1.2：锁定 HPKE 套件为 X25519 / HKDF-SHA256 / ChaCha20-Poly1305；明确 `info`/`aad`/线上格式/`kid` 绑定；增加 `testdata/push/hpke/` 占位说明 |
| 2026-10-03 | 按 PLAN v1.3：`sealed` 定为 JSON 对象（字符串 → 400）；AAD 域分离为 `dlpush/1 token|` / `dlpush/1 content|`；向量目录分 `hpke/` 与 `content/` |
| 2026-10-04 | 明确插件注册字段、关闭、密钥轮换、410 与吊销清理边界 |
