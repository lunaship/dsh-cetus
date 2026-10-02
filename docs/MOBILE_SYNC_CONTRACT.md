# 移动端同步与请求状态合同

业务层协议，独立于传输（局域网直连或 DLP/1 中继）。内层证书指纹与设备 Token 不因本文件升级改变。

当前插件 `protocol` 为 `2`。App 在建流时发送 `caps=sync2,multiQuestion,requestState`。

## 能力协商

`GET /dsh-link/mobile/bootstrap` 增加：

```json
{
  "protocol": 2,
  "capabilities": {
    "sync": { "resync": true, "catchupIntegrity": true },
    "questions": { "multi": true, "serverValidation": true },
    "requests": { "snapshot": true, "reconnectGraceMs": 30000 },
    "files": { "workspace": true, "maxBytes": 8388608, "tree": true, "treeMaxEntries": 2000 }
  },
  "archivedSessionIds": ["<session-id>"]
}
```

旧 App 忽略未知字段。旧插件忽略 `caps` 查询参数。

`capabilities.diagnostics: { v: 1 }` 表示可以拉连接诊断，见下方「连接诊断」。旧 App 忽略该字段。

`capabilities.events: { host: true }` 表示可以订阅 `GET /dsh-link/mobile/events`（设备 token，SSE）。事件只有会话级状态，不包含消息正文和工具参数：`id` 为单调 seq，`event: session/state`，正文 `{ type, sessionId, state, title, origin, seq }`。`state` 为 `running` / `awaitingApproval` / `awaitingInput` / `completed` / `failed` / `stopped`，`origin` 为 `user` / `subagent` / `schedule`。DSH 没有全局会话事件时，插件只在有订阅者的情况下每 5 秒对 `session.list` 做差分。心跳为 25 秒的 `event: heartbeat`。请求带 `Last-Event-ID`：缓冲里接得上就补发，接不上发 `event: resync-required`。旧 App 忽略该能力。

`capabilities.preview: { v: 1, detect: 1 }` 表示可以列已批准的本机预览，并且可以列出工具输出里看到的端口。`v` 仍是 1，旧 App 只认 `v` 时预览入口不变。`detect` 是新增字段，旧 App 忽略。手机不能批准端口。

`archivedSessionIds` 与 Web 的工作区归档集合保持一致；Web 恢复会话后，该 id 也必须从 App 的归档集合移除。App 的本机恢复仅是用户明确选择的临时覆盖，不能把服务端新归档的会话重新带回侧边栏。`sessions` 仍保留完整会话行，供设置页恢复；App 在冷启动选择会话前先应用该集合，因此已在 Web 删除的会话不会短暂出现在 App 侧边栏或被自动选中。该集合是快照字段，不代表底层会话日志已被物理删除。

`GET /dsh-link/mobile/sessions` 也返回同名 `archivedSessionIds`。App 的后台会话刷新必须先应用该集合，再更新列表和当前选择，避免列表请求与工作区请求之间产生短暂不一致。

会话行（`bootstrap.sessions` 与 `GET /dsh-link/mobile/sessions`）可带 `awaitingInput: true`：该会话有尚未结束的审批或澄清问题，不论它由手机接管还是交给电脑端网页处理。字段由插件在 `approval/request`、`user-questions/request` 钩子外层计数得出（DSH 的 `session.list` 不带这个状态），只在为真时下发，缺省即 `false`；旧 App 忽略该键。交给电脑端的请求手机只能看到状态，仍需在电脑上处理。

`GET /dsh-link/mobile/sessions/search` 同样遵守该集合：成功搜索和降级的标题搜索都不会返回 Web 已归档的 `sessionId`。

### 会话「当前步骤」与「结果一句话」（2026-09-28 重设计新增）

同一批会话行还可带两个可选字段，由插件从 `session.history` 推导（DSH 列表不带，实现在 `src/mobile-session-activity.js`）：

```json
{
  "sessionId": "…",
  "running": true,
  "activity": { "kind": "tool", "label": "go test ./...", "step": 12, "startedAt": 1759000000000 }
}
```

```json
{
  "sessionId": "…",
  "running": false,
  "lastResult": { "text": "门禁全绿", "files": 79, "added": 1200, "deleted": 300 }
}
```

- **只在 `running` 时下发 `activity`，只在非 `running` 时下发 `lastResult`**，两者不会同时出现在一行。
- `activity.kind` 为 `tool` / `thinking` / `writing`。`tool` 时 `label` 是命令或「工具名 参数」摘要（≤60 字，超出截断加 `…`），`step` 是本轮步号（事件里带才有）；`thinking` / `writing` 没有 `label`，App 按 `kind` 出本地化文案。
- `lastResult.text` 是最后一条助手回复去 Markdown 后的首段（≤60 字）；`files` / `added` / `deleted` 取自本轮 `workspace/changes` 的摘要，判定与改动卡一致（摘要没列出任何文件就不算改动）。摘要取不到（Host 重启后旧轮次没有摘要）时只丢统计，文本照常；文本与统计都没有则整个字段不下发。
- **回退语义**：字段缺失（旧插件、推导不出、会话不在最近 20 个之内）时，App 写「运行中」/「已完成」。App 不得因为缺少字段而隐藏或改变会话行。
- 边界：只对**最近 20 个**会话计算；按 `sessionId + updatedAt` 缓存，`updatedAt` 不变不重算；每次取 `session.history` 的 `maxMessages` 为 8，并发上限 4；单个会话失败只跳过该字段，不影响会话列表本身。

产出文件：历史投影可含 `role: "produced_files"` 与 `files` 路径列表。具备 `capabilities.files.workspace` 时，`GET /dsh-link/mobile/sessions/:id/file?path=` 在该会话 cwd 沙箱内返回原始字节（默认上限 8MB）。路径越出工作区返回 403。旧 App 忽略未知 role，仍可走工具结果文本。

工作区文件树（`capabilities.files.tree`）：`GET /dsh-link/mobile/sessions/:id/tree?path=` 列出该会话 cwd 沙箱内的**一层**目录，App 按层懒加载。与 `/file` 相同的门槛：只有持有该会话活跃 SSE 订阅的设备可调用，否则 403。

- `path` 为工作区内相对路径，空串或 `.` 为根；绝对路径只要解析后仍在工作区内也接受。越出工作区（含经符号链接越出）403，不存在 404，不是目录 400。
- 响应 `{ ok, path, total, truncated, entries }`：`path` 为解析后的真实相对路径（根为空串，经工作区内链接进入时给出目标路径）；`entries` 目录在前、同类按名称码元序，最多 `treeMaxEntries`（2000）条，超出时 `truncated: true`，`total` 为实际条数。
- 条目 `{ name, type }`，`type` 为 `dir` / `file` / `symlink` / `other`；`file` 另带 `size`、`mtimeMs`。指向工作区内的符号链接按目标类型给出并带 `link: true`；指向工作区外或断开的链接为 `type: "symlink", outside: true`，App 不可进入也不可打开。
- 不过滤隐藏文件（`.env`、`.git` 照常列出）：设备配对后本就能经 `/file` 读取工作区内任意文件，列目录不扩大可读范围。
- 打开文件仍走 `/file?path=<目录 path>/<name>`。旧 App 不看该能力位，旧插件不宣告即不出现入口。

## 本轮改动文件（`capabilities.files.changes`）

数据源是 DSH Host 的 `workspaceChanges` 服务（`@deepseek-ai/dsh-workspace-changes`）：每个顶层轮次末尾追加一条只带轮号的 `workspace/changes` 事件，摘要与对比按事件 seq 留在 Host 内存，Session 释放或 Host 重启后即不可取。插件只转发与裁剪，不自己做快照。

Host 挂载了该服务时 bootstrap / SSE `ready` 下发：

```json
"files": { "workspace": true, "maxBytes": 8388608, "changes": true, "diff": true, "diffMaxLines": 5000 }
```

未下发 `changes` 时 App 不显示任何改动入口。

- **历史**：`workspace/changes` 投影为 `{ id: "changes-<seq>", seq, role: "workspace_changes", turn, changes: { turn, total, added, deleted, files: [...] } }`，`files` 最多 100 个。同一页内同轮后一条宣告取代前一条（后一条为空列表时直接移除）；跨页由 App 按 `turn` 保留最大 `seq`。Host 取不到摘要时不出这条消息。
- **实时**：SSE 照常转发原始 `workspace/changes` 事件（`data.turn`）。App 以该事件的 `seq` 调摘要路由；404 时静默不出卡片。
- **摘要** `GET /dsh-link/mobile/sessions/:id/changes?seq=`：`{ ok, seq, turn, total, added, deleted, files }`，`files` 保持 Host 顺序（按 `display` 码元序），最多 500 个；数组下标即对比路由的 `index`。文件行：`path`（工作目录内相对、否则绝对）、`display`（斜杠分隔，`../`、`~` 或绝对路径）、`added`、`deleted`，以及可选 `binary` / `oversized`（二者都没有行数与对比）。
- **对比** `GET /dsh-link/mobile/sessions/:id/changes/diff?seq=&index=`：`kind` 为 `text` / `binary` / `oversized`。`text` 带 `before` / `after`（轮首 / 轮末是否存在，据此判定新建 / 删除）、`coarse`（逐行对比超时，整文件替换）、`hunks[{ oldStart, oldLines, newStart, newLines, lines }]`（每行保留 `+` / `-` / 空格前缀，三行上下文），`hunks` 为空表示两侧相同。超过 `diffMaxLines` 行或 150 万字符（压缩脚本等超长行）时只丢尾部行并带 `truncated: { shownLines, totalLines }`；App 按 4MB 读取该 JSON。
- **权限**：对比会送出文件全文（包括被忽略的文件与工作区外文件），与文件下载同规则，只给持有该会话活跃 SSE 订阅的设备，否则 403。摘要与历史同级。
- **错误**：Host 无该服务 404 `code: "changes_unsupported"`；摘要 / 对比已不可用 404 `code: "changes_unavailable"`；坐标非法 400。

## 配对与重装恢复（`POST /dsh-link/pair`）

同名设备默认拒绝静默替换，409 返回结构化字段：

```json
{
  "error": "已存在同名设备，请先吊销旧设备或更换名称",
  "code": "SAME_NAME",
  "existing": { "deviceId": "dev-...", "name": "手机", "status": "active" }
}
```

- 旧 App 只读 `error` 文本，不受影响；新 App 应据 `code == "SAME_NAME"` 弹对话框：
  「已有同名设备（可能来自上次安装）：[替换它] [换个名字]」。
- `GET /dsh-link/devices` 与 `GET /dsh-link/mobile/devices` 的设备行新增布尔 `replacing`：
  仅 `status: "pending"` 且批准后会吊销同名旧设备时为 `true`，面板/App 用它提示
  「批准即替换」；旧 App 忽略未知字段。
- 409 验码通过但**不消费配对码**；用户选「替换」后用同一张码重发，额外带 `"replace": true`。
- 替换的落点由主机的「配对需本机确认」（`requireConfirm`）决定：
  - 关：立即吊销同名旧设备，新设备直接生效，200 响应带 `replacedDeviceIds: ["dev-..."]`；
  - 开：旧设备保持在线，新设备进 `pending` 且 `replacing: true`；**面板批准的那一刻**才吊销
    `replaces` 里的旧设备（批准响应用 `replacedDeviceIds` 返回），拒绝/超时不碰旧设备。
- `replace: true` 而无同名冲突时就是普通配对（不返回 `replacedDeviceIds`）。
- 手机卸载重装会销毁 Keystore 里的 token，重装后必须重新扫码；上述流程让重装恢复
  不需要先到电脑端手工吊销。

二维码 / `pair-info` 载荷新增两个时效戳（Unix 毫秒，主机时钟）：

- `issuedAt`：本次渲染时刻；`expiresAt`：当前配对码过期时刻。
- App 扫码后应先比较本机时间：超过 `expiresAt`（或 `issuedAt` 过旧）直接提示
  「请刷新电脑面板上的二维码」，不要提交注定 401 的码，避免撞限流冷却。
- 旧插件不下发这两个字段；缺失时 App 回退为直接尝试配对。

## 远程能力（DLP/1）

完整合同见 [`rfc/0001-dlp1-remote-pipe.md`](rfc/0001-dlp1-remote-pipe.md) §5.2、§6.3、§6.4。

- **二维码 / `pair-info`**：远程已就绪时二维码额外带 `remote: { e, r, s, p? }`（中继地址、routeId、
  一次性 bootstrap 种子、可选外层证书指纹）。`s` 只编进二维码图片，面板的 `pair-info` JSON 不带它，
  只给 `remoteStatus: { state, host }`。旧 App 不认识 `remote`，按 `urls` 走局域网配对。
- **`POST /dsh-link/pair`** 成功时，远程已启用则附带该设备的 `remote: { e, r, h, k, p? }`（设备 handle
  与会合密钥），pending 设备也有。经中继首配（bootstrap 来源）一律 `pending: true`，不受「配对需本机确认」开关影响。
- **`GET /dsh-link/mobile/bootstrap`**：`remote` 为对象 = 更新远程能力（已配对设备此时自动补发 handle，无需重扫）；
  `null` = 远程已停用，App 只清远程字段、保留局域网配对；缺键（旧插件）= 不动。
  `relay` 固定为 `null`：旧版 DLR/1 已下线，旧 App 据此清掉失效的云端路由。
- 中继转来的错误码（`UNKNOWN_KEY`、`BAD_MAC` 等）可能被伪造，App 只能据此提示，不得删除或改写本机凭据；
  删除凭据只认插件在内层 TLS 上的明确答复（设备已吊销）。
- 设备列表（`/dsh-link/mobile/devices` 与面板）的 `via`：`lan` / `remote`（经中继首配）/ `relay`（旧版云端配对，已停用）；
  `remote: true` 表示该设备已有远程能力。`remoteHandle` 从不下发。

| 组合 | 行为 |
| --- | --- |
| 新 App / 新插件 | 补发不完整时发 `resync-required`，App 拉快照后从快照游标继续 |
| 旧 App / 新插件 | 不发送截断尾部，不推进游标越过缺口；发 `error`（`upgradeRequired`），连接保持，避免重连风暴 |
| 新 App / 旧插件 | 无重同步事件；App 不假装已具备完整恢复。需两端一起升级 |

禁止靠无限断开重连修补缺口。

## 预估花费

`GET /dsh-link/mobile/sessions/:id/history` 的 `stats` 在能计价时多一个 `estimatedCost`：`{ amount, currency, priceDate, source }`。`source` 为 `host`（模型对象自带价格）或 `builtin`（插件内置官方价表）。未知模型或 Host 与内置表都没有价格时省略该字段，App 只显示 token。旧 App 忽略这个字段。金额是估算，不并入余额。

内置表计价时还带 `amountMin` / `amountMax`：整段会话全按谷时 / 全按峰时的金额。Host 只给累计 token，没有逐轮时间，算不出真实峰谷占比，App 有区间时显示区间。`amount` 仍按当前时刻单价（兼容旧 App）。模型按当前选中的模型计。Host 自带价格时没有区间。

## 快照与增量

- 历史 REST 是快照；SSE `message` 是增量。
- `loadEventsAfter` 只有在 `afterSeq+1` 到已收集尾部连续可证时才交付该批次。
- 达到 10 页 / 500 事件、页内截断、日志缺口或 seed 队列溢出时，`complete=false`，不得把尾部 701…1200 当作 afterSeq=100 的连续补发。
- 新 SSE 事件 `resync-required`：`{ sessionId, reason, afterSeq, oldestAvailableSeq, nextCursor }`。
- App 丢弃本轮在途增量中不可证的分页缓存，保留输入草稿；用会话 generation 丢弃迟到结果。
- 提交游标（已应用）与接收游标分开；重连 `afterSeq` 使用已提交游标。

## 请求状态

状态：`pending` / `resolved` / `cancelled` / `expired` / `unknown`。

DSH 结果映射：`allowed-once`/`rejected` → `resolved`；`cancelled` → `cancelled`；`unavailable` → `expired`。

- 同页 `approval/asked` 与 `approval/decided` 投影为一张终态卡。
- 同 `approvalId` 的审批卡、同 `rpcId` 的澄清卡在客户端各归并为一张；终态不得被 pending 回滚。
- 实时流、重连快照、`GET .../requests`、提交响应走同一归并：终态不得被 pending 回滚。
- `GET .../requests` 的 pending 澄清带原始 `questions` 数组，pending 审批带 `toolName` / `callId`；空白 id 忽略。
- 澄清卡不进 history 投影。App 历史刷新与 `resync-required` 后必须保留仍 pending 的 `question` / `approval` 气泡，并用快照补回进程重启或列表被清空后缺失的 pending 卡。
- 审批重复提交若已有终态，返回 `alreadySettled` 且不再次 settle。
- SSE 断开后有不超过 30 秒且不超过原审批剩余期限的重连宽限；重连不重置 5 分钟总超时。
- 设备吊销、DSH abort、插件退出立即结束，不能借宽限恢复权限。
- 插件重启后内存回调不可恢复，未决请求失效。
- 澄清多题：旧 App 不声明 `multiQuestion` 时回落桌面，不在手机上构造未展示题目的答案。

## 工作区注册与设备吊销

- 单层名称：`POST /dsh-link/mobile/workspaces` 仍立即在锚点工作区的同级创建目录并注册，200 返回 `workspace`。
- 绝对路径：目录必须已存在。插件把它解析成 realpath（符号链接展开成目标），**不**调用 `workspace.create`，返回 202：

```json
{ "ok": true, "pending": true, "requestId": "...", "path": "/realpath", "inputKind": "absolute-path", "expiresAt": 0 }
```

  电脑在「手机连接」面板批准后才注册；拒绝、过期或该设备被吊销则丢弃。旧 App 看到没有 `workspace` 对象时按原错误提示，不会静默注册。
- `POST /dsh-link/mobile/revoke` 只能吊销调用方自己的设备。`deviceId` 或 `name` 指向其他设备时返回 403 `只能吊销当前设备`。跨设备吊销与全部吊销仍只在回环面板。

## 模型页：余额与供应商（DSH 0.1.7 起）

- `GET /dsh-link/mobile/balance?locale=` 代调 `account/getBalance`，恒回 200：`{ status, wallets, bonusWallets }`。`status` 为 `ready`（钱包 `{currency, balance}`，`balance` 为平台原样十进制字符串）、`signed-out`（主机未登录 DeepSeek 账户）、`failed`（平台查询失败）、`unavailable`（旧 DSH 没有该方法）。
- `GET /dsh-link/mobile/providers` 返回 `{ writable, providers, addable }`，行序与桌面一致（`deepseek-account`、`deepseek-official` 置顶）。每行 `{ provider, displayName, kind: "account"|"api", active, custom, keyRef, credential: {configured, writable, source}|null, models: [{id, name, contextWindow, maxTokens}], modelsEditable, canDiscover }`。不下发 `baseURL` / `api` 等路由字段，更不下发密钥值。`addable` 是目录里尚未配置的供应商 `{provider, displayName}`。
- 写接口都返回刷新后的同一形状（外加 `ok: true`），经设备变更闸门执行，吊销即 401：
  - `POST .../providers/models {provider, add?: [{id, name?, contextWindow?, maxTokens?, inputModalities?}], remove?: [id]}`：只写该供应商 profile 的 `models` 数组，原有条目字段原样保留。仅 `modelsEditable` 为 true（profile 显式带 `models`）时可用，否则 409 `models-inherited`，避免手机把适配器默认目录整体替换掉。不允许清空为 0 个。
  - `POST .../providers/credential {provider, apiKey}`：密钥规则与桌面相同（可见 ASCII、非 `NAME=value`、非引号包裹）。写入 profile 的 `apiKeyEnv`，没有则写入派生名 `<PROVIDER>_API_KEY` 并补记到 profile；值单向进 `credentials/set`，响应与日志均不回显。来源只读（如环境变量）时 409 `credential-read-only`。
  - `POST .../providers/add {provider, apiKey?}`：只接受 `addable` 里的目录供应商。自定义接口（协议 + baseURL）仍只在电脑端添加。
  - `POST .../providers/discover {provider}`：用已存 profile 的 `baseURL` / `api` 与已存密钥调 `llm/discoverModels`，忽略手机传来的路由字段；返回候选 `models`，由用户勾选后再走 `providers/models` 写入。
- 所有写入带读到的 `expectedRevision`；电脑端同时修改时返回 409 `conflict`，App 刷新后重试。

## 连接诊断（`capabilities.diagnostics`）

`GET /dsh-link/mobile/diagnostics`（设备 token）在插件声明 `capabilities.diagnostics.v = 1` 时可用。回环面板走 `GET /dsh-link/diagnostics`（仅本机同源，不出现在 18640）。两边都是：

```json
{
  "version": 1,
  "generatedAt": 1730000000,
  "checks": [
    { "id": "host.rpc", "status": "ok", "code": "HOST_RPC_OK", "detail": { "ms": 12 } }
  ]
}
```

- `generatedAt` 是主机 Unix 秒，App 用它和本机时间算时钟偏差。
- `status`：`ok` / `warn` / `fail` / `skip`。
- `code` 是稳定枚举。文案由 App / 面板按 `code` 本地化，插件不返回自由文本。
- `detail` 只含数字、布尔和短枚举（版本号、8 位指纹前缀、拒绝码）。不含 token、证书全文、绝对路径、IP 或消息正文。
- 手机范围的检查：`host.rpc`、`host.services`、`plugin.version`、`tls.cert`、`pairing.devices`、`remote.relay`、`clock`。`pairing.devices` 只报告当前设备 `valid`，不给其他设备的数量或标识。
- 面板额外有 `listen.addresses`（按 private / tailnet / other / loopback 计数，不回地址本身）。
- `host.rpc` 只读调用已在白名单内的 `workspace.list`，超时预算 800ms。`remote.relay` 只读现有中继状态，不新发探测。未开启远程的 code 是 `REMOTE_DISABLED`（status `fail`）。证书剩余天数不足 30 为 `TLS_CERT_EXPIRING`（`warn`）。
- 旧 App 不调用该路径也能正常使用其他接口。

## 开发服务器预览（`capabilities.preview`）

批准只在电脑的回环面板完成：`POST /dsh-link/previews`（端口和名称），`POST /dsh-link/previews/revoke`。这两条不出现在 18640。手机 API 没有批准端口的接口。

`GET /dsh-link/mobile/previews`（设备 token）返回未过期的 `{ previewId, label, port, expiresAt }`。默认 2 小时过期。

`/dsh-link/mobile/preview/:previewId/*`（设备 token）把 HTTP 和 WebSocket 转到 `127.0.0.1:<port>`。`previewId` 是随机 id，不是端口号。转发时改写 `Host` / `Origin` 为 `localhost:<port>`，去掉 hop-by-hop 头和设备 token。单次响应超过 50 MB 截断。空闲 60 秒断开。插件不记录路径和正文，只计访问次数。

即使记录里有这个端口，下列端口也会被拒绝：22、3306、5432、6379、11211、27017、9200，以及插件端口和 DSH Host 端口。撤销会立刻断开已经打开的连接。

页面里写死的 `http://localhost:<port>` 绝对地址不会被改写成预览路径。Vite / Next 的热更新如果使用当前页面的 host，走的是这条预览连接。

`GET /dsh-link/mobile/preview-detections`（设备 token）返回 `{ detections: [{ port, sessionId }] }`。只含端口和会话 id，没有工具输出。已经批准的端口不在这里。插件从 `tool/result` 的文本里识别 `http://localhost`、`http://127.0.0.1`、`http://0.0.0.0` 加端口。示例句里的地址（例如、e.g.、example）不算。会话从列表消失或被归档后，这些记录删掉。这条接口不能批准端口。

## 错误与重试

- 校验失败（未知题目 ID、无效选项、缺必填、超长）返回 400，请求保持可处理。
- 已结束且无终态缓存：409。
- 非当前会话 / 非授权设备：403/409。
- 目标会话已被其他写方占用（Web 端或另一设备正持有写句柄）：`POST .../prompt` 返回 409 `{"error", "code": "session_busy"}`；App 提示换会话，不得自动重试或转排队。
- 非幂等 prompt 不自动重放。

## 后台通知

完整后台推送（ENH-01）渠道待定，未实施。现有通知仍只覆盖 App 进程收到当前会话 SSE 之后的本地提醒。

### `stoppedReason`（与 `activity` / `lastResult` 同一批）

- **位置**：会话列表每一项（`GET /dsh-link/mobile/sessions` 与 bootstrap 里的 `sessions`）。
- **含义**：这一轮是**怎么结束**的——`turn/end` 的 `reason.kind`；`completed` 或取不到时**不下发该键**。
- **取值**：`interrupted` / `stopped` / `error` / `maxTokens` 等，与**会话详情**里的同名字段同一口径。
- **口径**：与 `activity` / `lastResult` 复用同一遍历史读取（最近 20 个会话、缓存键含 `updatedAt`），
  不额外拉一次历史。
- **回退**：旧插件不下发该键时，App 把这一行当作「已完成」（方案 3.6 的明文回退）；**图标与文案
  必须同时按这一个判断走**，否则会出现「已完成」配灰底方块的自相矛盾（这正是 2026-09-29 真机走查
  抓到的第 5 处问题）。
