# DLPUSH/1 推送网关（dlpush）

手机离开 App 后，电脑上的插件把**端到端加密**的通知密文交给这个网关，网关只负责把它转给 APNs。
网关无状态、无数据库、无注册接口：它用 HPKE 打开封装拿到 APNs token，向 APNs 投递密文，
**看不到通知明文**，也没有任何路径向电脑下发命令。批准与回复仍走原来的局域网 / Tailscale / DLP/1 中继。
协议合同见 [`docs/rfc/0002-push-gateway.md`](../docs/rfc/0002-push-gateway.md)。

```text
插件（用户电脑）── POST /v1/push（HPKE 封装的 token + AES-GCM 内容密文）──> dlpush :8080 ── HTTP/2 + JWT ──> APNs ──> iPhone（NSE 解密）
手机 ── 批准 / 回复 ──> 局域网 / Tailscale / 中继 ──> 插件
```

推送通道只有下行，也不提供注册接口：网关不存用户数据，没有数据库。

## 代码

| 路径 | 内容 |
|---|---|
| `cmd/dlpush/main.go` | 入口：读环境变量、加载 HPKE 私钥与 APNs key、装配限流、监听（`ReadHeaderTimeout` 10s） |
| `internal/hpke/hpke.go` | DLPUSH/1 的 HPKE 上下文：固定套件、`info`、`dlpush/1 token\|` AAD、`sealed` 对象与 Open |
| `internal/hpke/key.go` | 原始 32 字节 X25519 私钥 / 公钥的包装与导出（`GET /v1/keys` 用） |
| `internal/content/content.go` | 端到端内容加密：AES-256-GCM，AAD `dlpush/1 content\|` + deviceId，线上 `nonce\|\|ct\|\|tag` |
| `internal/http/gateway.go` | 三个 HTTP 接口、请求校验、APNs 请求构造、日志脱敏 |
| `internal/apns/apns.go` | `Sender` 接口与 `ResultFor`（410 / `BadDeviceToken` / `Unregistered` 判为失效） |
| `internal/apns/http2sender.go` | 真实 HTTP/2 投递：token 认证 JWT、`apns-*` 请求头、`/3/<token>` |
| `internal/apns/noop.go` | 开发用空投递：不触网，一律回 200 |
| `internal/limit/limit.go` | 按 `sha256(sealed)` 的内存滑动窗口限流 |

测试向量与网关共用仓库根目录的 [`testdata/push/hpke/`](../testdata/push/hpke/README.md) 与
[`testdata/push/content/`](../testdata/push/content/README.md)（与 iOS、插件共用同一份）。
两端到端的本机链路验证在 [`test/push-chain.test.mjs`](../test/push-chain.test.mjs)：拉起本地网关 + 假 APNs，
断言密文被原样转发、且日志不含密钥或密文。

## HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/push` | 提交一条推送：`kid`、`sealed`（JSON 对象）、`kind`、`ct`、`collapseId`、`priority`、`expiresIn` |
| `GET` | `/v1/keys` | 返回 `{keys:[{kid, publicKey}]}`，`publicKey` 是标准 base64 的 32 字节 X25519 公钥；轮换期新旧并存 |
| `GET` | `/healthz` | `{"ok":true,"version":"<DLPUSH_VERSION>"}` |

`POST /v1/push` 的状态码（无其他成功码）：`200` 已转交、`400` 格式错 / `sealed` 不是对象 /
`kid` 不符 / `Open` 失败 / `kind` 未知、`410` APNs 报 token 失效、`429` 限流、
`502` APNs 其他失败（源码里的 `apns_error`；RFC 0002 §5.5 未列出该码，以[代码](internal/http/gateway.go)为准）。

响应体只有 `{"ok":true}` 或 `{"error":"<短码>"}`，**不回显** `sealed` / `ct` / token。
`kind` 取 `alert` / `la-update` / `la-start` / `la-end`；后三者把 topic 改成
`<bundleId>.push-type.liveactivity`。

## 构建与本地运行

```bash
cd push
go build -trimpath -ldflags="-s -w -X main.version=$(git describe --tags --always)" -o dlpush ./cmd/dlpush
```

版本字符串也可以由 `DLPUSH_VERSION` 直接给，默认 `dev`，只出现在 `/healthz` 与启动日志里。

本地跑一次最简流程（用 `NoopSender`，不触网）：

```bash
# 生成一把 32 字节 X25519 私钥，写成无填充 base64url
head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > /tmp/dlpush.key
DLPUSH_LISTEN=127.0.0.1:8080 \
DLPUSH_HPKE_KEYS="gw-local=/tmp/dlpush.key" \
./dlpush
curl -s http://127.0.0.1:8080/healthz
curl -s http://127.0.0.1:8080/v1/keys
```

不设 `APNS_KEY_P8_PATH` 时用空投递：网关照样校验 `sealed`、走完限流与请求构造，只是不发 APNs。

要连着本机假 APNs 跑，需要同时设 `APNS_KEY_P8_PATH` / `APNS_KEY_ID` / `APNS_TEAM_ID` / `APNS_BUNDLE_ID`
和 `DLPUSH_FAKE_APNS_URL`。`.p8` 可以临时用 OpenSSL 生成（仅测试用，绝不能进 git）：

```bash
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out /tmp/dlpush-test.p8
```

从仓库根目录跑完整链路验证（会自己拉起网关与假 APNs，不需要真实 Apple 账号）：

```bash
node --test test/push-chain.test.mjs
```

## 配置（环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `DLPUSH_LISTEN` | `:8080` | 监听地址；只应暴露给同机或私网里的 TLS 反代 |
| `DLPUSH_HPKE_KEYS` | 无（必填） | `kid=私钥文件路径`，多项用逗号分隔；每个文件是**无填充 base64url 的 32 字节 X25519 私钥**。留空则拒绝启动 |
| `APNS_KEY_P8_PATH` | 空 | APNs `.p8` 认证密钥路径（0600，属主为服务用户，不进 git）。**留空 = 空投递**，不触网 |
| `APNS_KEY_ID` | 空 | APNs Key ID，用于签 JWT |
| `APNS_TEAM_ID` | 空 | Apple Team ID |
| `APNS_BUNDLE_ID` | 空 | App bundle id；实际 topic 优先取 `sealed` 明文里的 `bundleId` |
| `APNS_HOST` | `api.push.apple.com` | APNs 主机名覆盖；一般不用设 |
| `DLPUSH_FAKE_APNS_URL` | 空 | 指向本机假 APNs 的 `http(s)://` 地址。`http` 只允许 `127.0.0.1` / `localhost` / `::1` |
| `DLPUSH_RATE_MINUTE` | `10` | 每 `sha256(sealed)` 每分钟上限；非整数回落到默认 |
| `DLPUSH_RATE_HOUR` | `120` | 每 `sha256(sealed)` 每小时上限；非整数回落到默认 |
| `DLPUSH_VERSION` | `dev` | `/healthz` 里回显的版本串 |

限流按 `sha256(sealed JSON)` 计数，滑窗在内存里，重启即清零（网关本身无持久化）。

## 隐私与日志边界

- 网关**看不到明文**：内容由插件用端到端密钥 `K` 做 AES-256-GCM 加密，`K` 不进网关；网关只把密文塞进 APNs payload 的 `e` 字段。
- 网关**伪造不了**通知：没有 `K`，也无法构造能被 NSE 解密的密文。
- 日志只记计数、HTTP 状态、耗时与 `kind`。**不记** `sealed`、`ct`、APNs token、客户端 IP、`deviceId`、明文——
  [`gateway_test.go`](internal/http/gateway_test.go) 里的 `TestPushLogsOmitSecrets` 会断言这些串不出现在日志中。
- 网关无数据库、无注册接口，不提供查询设备 / 列出 token 的路径。
- 通知栏不提供「允许 / 拒绝」动作，只有「打开」。

## 自建

Fork 用自己的 bundle id、`.p8`、网关地址与公钥：生成一对 X25519 密钥，把 `kid` 与公钥编进自编译的 App，
私钥按上面的 `DLPUSH_HPKE_KEYS` 格式放到网关；App 的「高级」设置里填自定义网关地址。
轮换时新增一把密钥（`GET /v1/keys` 会同时返回新旧两把），旧 `kid` 至少保留 90 天再下线。

## 部署状态

**截至本文件写入时，`push/` 还没有部署产物**：没有 `Dockerfile`，没有 `deploy/` 目录
（systemd unit、Caddyfile），也没有任何生产域名或部署记录。上面全部命令都在开发机上验证过，
只在 `127.0.0.1` 上监听。

按 [RFC 0002 §4](../docs/rfc/0002-push-gateway.md) 的计划，正式部署属于**阶段 9**：香港服务器、
HTTPS 反代 TLS 到本机端口，并且与 `relay.dshlinks.com` 同机时必须是独立进程与独立 systemd 服务。
在那之前不要照抄 [`relay/README.md`](../relay/README.md) 的部署章节——中继有 `relay/deploy/` 与
`relay/Dockerfile`，网关目前没有。

## 门禁

```bash
cd push
gofmt -l . && go vet ./... && go build ./... && go test ./... -race
```

[`ci-push.yml`](../.github/workflows/ci-push.yml) 在 `push/**` 有改动时跑同一套（格式、vet、race 测试），
外加 fuzz：扫描 `*_test.go` 里所有 `func Fuzz` 逐个跑 10 秒。目前只有一个目标——
[`FuzzPushBody`](internal/http/gateway_test.go)，喂任意字节给 `POST /v1/push`，只允许
200 / 400 / 429 / 410 / 502 / 405 这几种状态。

```bash
go test ./internal/http -run '^$' -fuzz FuzzPushBody -fuzztime 10s
```

## 执行红线（来自 RFC 0002 §8）

1. 网关无状态、无用户数据库；禁止为「方便调试」记录密文或 token。
2. `.p8` 与 HPKE 私钥权限 0600，属主为服务用户；不进 git。
3. 网关错误码不得诱导 App 删除局域网凭据；删推送 `sealed` 只认 410 / 用户关闭 / 设备吊销。
4. 不在通知或 Live Activity 上提供批准按钮。
5. AAD 前缀域分离（`dlpush/1 token\|` 与 `dlpush/1 content\|`）与「误用另一种前缀必须失败」的负例不得删。
