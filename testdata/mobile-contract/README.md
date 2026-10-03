# 手机合同 fixtures（I3.2）

这批 JSON 是**插件真实响应的样本**：`scripts/export-contract-fixtures.mjs` 用测试同款的假
DSH Host（固定数据 + 临时 `stateDir`）跑真实插件，对 `docs/MOBILE_SYNC_CONTRACT.md`（PLAN
附录 C）里的每个手机接口发真实 HTTPS 请求，把响应体原样收进来。iOS `Tests/Contract` 逐个
解码这些文件；Android 后续接入同一批文件。

## 生成与校验

```sh
node scripts/export-contract-fixtures.mjs          # 生成 / 更新（并删除不再生成的旧 json）
node scripts/export-contract-fixtures.mjs --check  # 只比对，不一致时退出码 1
```

**插件改了任何手机响应结构（新增 / 删除 / 改名字段、改能力位、改错误码），必须重新运行生成
命令并提交 diff**，否则 `test/contract-fixtures.test.mjs`（以及 CI）会失败。这是刻意的：
fixtures 漂移就是「插件与 App 合同漂移」的信号。

## 文件 ↔ 接口对照

| 文件 | 接口 / 场景 |
|---|---|
| `pair.json` | `POST /dsh-link/pair` 200（直接批准） |
| `pair-pending.json` | 同上 200，`requireConfirm` 开启时（`pending: true`，token 待面板批准） |
| `pair-same-name-409.json` | 同上 409 `SAME_NAME`（结构化冲突对象，409 不消费配对码） |
| `bootstrap.json` | `GET /dsh-link/mobile/bootstrap`（能力协商 + 会话 + 归档集合；`remote: null`） |
| `sessions.json` | `GET /dsh-link/mobile/sessions`（含 `activity` / `lastResult` / `stoppedReason` 三种行） |
| `sessions-search.json` | `GET /dsh-link/mobile/sessions/search?q=登录` |
| `history.json` | `GET /dsh-link/mobile/sessions/:id/history` 尾页（`kind` 分类、`stats` 含 `estimatedCost`、`queue`、`goal`） |
| `requests.json` | `GET /dsh-link/mobile/sessions/:id/requests`（pending 审批 + 澄清快照） |
| `approval-submit.json` | `POST .../approval`（`allowed-once` 提交成功） |
| `question-submit.json` | `POST .../question`（澄清答案提交成功） |
| `prompt.json` | `POST .../prompt`（`result` 是 DSH `session.prompt` 的透传值） |
| `cancel.json` | `POST .../cancel` |
| `queue-edit.json` | `POST .../queue/:itemId`（排队消息编辑） |
| `changes.json` | `GET .../changes?seq=`（改动摘要） |
| `changes-diff.json` | `GET .../changes/diff?seq=&index=`（hunk 对比；要求活跃 SSE 订阅） |
| `file-meta.json` | `GET .../file?path=` 的**元信息**（content-type / SHA-256 / 文件名；二进制正文不在 fixtures 里） |
| `tree.json` | `GET .../tree`（一层目录：dir / file / 工作区内 symlink / 工作区外 symlink） |
| `previews.json` | `GET /dsh-link/mobile/previews`（面板批准后的已批准预览） |
| `preview-detections.json` | `GET /dsh-link/mobile/preview-detections`（从工具输出识别到的端口） |
| `workspaces-200.json` | `POST /dsh-link/mobile/workspaces`（单层名称，立即注册） |
| `workspaces-202.json` | 同上（绝对路径，202 待本机批准） |
| `balance.json` | `GET /dsh-link/mobile/balance`（`status: "ready"`；signed-out / failed / unavailable 仅 status 不同） |
| `providers.json` | `GET /dsh-link/mobile/providers`（account + api 供应商、只读凭据、addable） |
| `diagnostics.json` | `GET /dsh-link/mobile/diagnostics`（手机范围检查；`remote.relay` 为 REMOTE_DISABLED 是未开远程时的正常输出） |
| `devices.json` | `GET /dsh-link/mobile/devices`（active + pending 各一台） |
| `revoke.json` | `POST /dsh-link/mobile/revoke`（只吊销调用方自己的设备） |
| `host-events.json` | `GET /dsh-link/mobile/events` 的前几帧，解析成 `{event, id, data}` 数组（`awaitingApproval` + `running`） |
| `session-stream.json` | 会话 SSE（`caps=sync2,multiQuestion,requestState`）初始补发的完整帧：`ready` + 全部 `message` + `stats` |
| `session-stream-resync.json` | 会话 SSE 遇到日志缺口：`ready` + `resync-required`（不推进游标） |

假 Host 的固定数据本身就是接口的示例载荷：会话 id 是 `sess-demo-*`，审批 id、队列条目 id、
题目 id 都是可读的固定字符串。

## 占位串规则

输出必须完全确定（连跑两次一字不差；`--check` 会在任何机器、任何日期通过）。不可避免的
变化值统一替换为占位串：

| 占位串 | 含义 |
|---|---|
| `<token>` | 设备 token（配对响应） |
| `<deviceId>` | 设备 id（`dev-…`） |
| `<hostId>` | 主机 id（`dsh-…`） |
| `<rpcId>` | 澄清请求的 `rpcId`（`q-…`，运行期随机） |
| `<requestId>` | 工作区注册 202 的 `requestId`（运行期随机） |
| `<previewId>` | 预览 id（运行期随机） |
| `<hostName>` | 电脑主机名 |
| `<fixtureRoot>` | 生成时临时目录的绝对路径前缀（`cwd`、`resolvedPath` 等） |
| `<lanAddress>:<port>` | 二维码 / 配对响应里的局域网地址与端口（`urls` 固定为单个占位条目，实际条数依网卡而定） |
| `<fingerprint>` | 插件本次运行生成的自签证书 SHA-256 指纹（64 位十六进制） |
| `<fingerprintPrefix>` | 同一指纹的 8 位前缀（`diagnostics.json` 的 `tls.cert` 检查） |
| `<pluginVersion>` | 插件版本号（取自 `package.json`；发版改版本号**不需要**重新导出 fixtures） |

时间戳不需要占位：脚本内冻结 `Date.now`（2026-01-01T00:00:00Z），所有时间都由此派生；
tree 条目的 mtime 也是固定的。TLS 证书由插件在临时 stateDir 里照常生成、不预置：指纹类
字符串走上面的占位串，唯一没法变成字符串的是 `daysRemaining` 数字字段——它随证书生成日期
每日漂移，冻结时钟也定不住，脚本把它固定为 **3650**（插件默认的 10 年有效期口径），JSON
类型保持数字不变。`estimatedCost` 样本走 `source: "host"`（Host 模型自带价格），因为内置
价表的 `amount` 随真实时钟的峰谷变化，无法复现。

## 本次未覆盖

- `bootstrap` 里 `remote` 为对象的样本：需要 DLP/1 中继全链路（假中继 + 远程首配，见
  `test/remote-plugin.test.mjs`）；字段形状以 `docs/rfc/0001-dlp1-remote-pipe.md` §6.4 为准。
- `push/register`：接口尚未实现（PLAN 阶段 6 随插件 PR 一起加）。
- `file` 的二进制正文、预览转发的 HTTP/WS 正文：不是 JSON 合同，`file-meta.json` 给出响应头元信息。
- SSE 的 `Last-Event-ID` 续传帧与心跳帧：帧格式与 `message` / `heartbeat` 相同，未单独采样。
- 审批 / 澄清的 409（已结束）与 403（非当前设备）错误样本：错误形状见
  `docs/MOBILE_SYNC_CONTRACT.md`「错误与重试」，未纳入本批。
