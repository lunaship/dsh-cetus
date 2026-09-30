# DLP/1 中继（dlp-relay）

DeepLinks 远程连接的「哑管道」：电脑插件与手机 App 都只向外连这里，中继把两条 WebSocket 拼起来，
原样转发内层 TLS 字节。它不保存账号、设备档案或任何业务内容，看不到明文，也无法冒充电脑。
协议合同见 [`docs/rfc/0001-dlp1-remote-pipe.md`](../docs/rfc/0001-dlp1-remote-pipe.md)。

```text
手机 App ── wss://…/ws ──> Caddy :443 ──> dlp-relay 127.0.0.1:8412 <── wss ── 电脑插件
```

## 代码

| 路径 | 内容 |
|---|---|
| `cmd/dlp-relay/` | 入口：读环境变量、监听、SIGTERM 优雅关停 |
| `internal/dlp/wire.go` | 控制帧解析（≤ 4 KiB、拒绝重复键、定长 b64u） |
| `internal/dlp/crypto.go` | routeId 推导、Ed25519 验签、transcript（向量见 `../testdata/dlp1/`） |
| `internal/dlp/hub.go` | route 表、待接受流、拼接、背压、超时与关闭码 |
| `internal/dlp/limits.go` | 每 IP / 每 route / 全局限额，日流量配额 |
| `internal/dlp/config.go` | 环境变量 |

## 自建

```bash
cd relay
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w -X main.version=$(git describe --tags --always)" -o dlp-relay ./cmd/dlp-relay
```

`-X main.version=…` 会写进启动日志，并出现在 `GET /healthz` 的正文里（`ok <version>`，仍是 200 纯文本，不含连接数或 route）。不传时版本是 `dev`。

建议用外部监控每分钟探测一次 `https://<域名>/healthz`。进程内统计在日志里，每 5 分钟一行后清零：

```bash
journalctl -u dlp-relay | grep stats
```

把二进制放到 `/usr/local/bin/`，按 [`deploy/dlp-relay.service`](deploy/dlp-relay.service) 装成 systemd 服务，
用 [`deploy/Caddyfile`](deploy/Caddyfile) 在同机反代 `/ws` 与 `/healthz`（Caddy 自动签证书、处理 WebSocket 升级）。
防火墙只开 80 / 443。也可以用 `Dockerfile` 构建镜像（监听 `0.0.0.0:8411`，前面同样放 TLS 反代）。

电脑上：设置 →「手机连接」→ 打开「外出时也能连」→「用自建中继」，填 `wss://你的域名/ws`。

## 配置（环境变量）

| 变量 | 默认 | 说明 |
|---|---|---|
| `DLP_LISTEN` | `127.0.0.1:8411` | 监听地址；只应暴露给同机或私网里的 TLS 反代 |
| `DLP_TRUSTED_PROXIES` | `127.0.0.1/32,::1/128` | 只有来自这些地址的 `X-Forwarded-For` 才被采信 |
| `DLP_ROUTE_DAILY_BYTES` | `0`（不限） | 每条 route 每天的流量上限；官方中继为 5 GiB |
| `DLP_MAX_STREAMS` | `2000` | 全局并发流 |
| `DLP_ROUTE_MAX_STREAMS` | `64` | 每条 route 并发流（下限 16） |
| `DLP_IP_OPEN_PER_MIN` | `60` | 每 IP 每分钟 `client_open` |
| `DLP_IP_MAX_CONNS` | `64` | 每 IP 并发客户连接（下限 16） |
| `DLP_IP_MAX_HOST_CONNS` | `256` | 每 IP 并发主机连接（下限 64，同一 NAT 后可能有多台电脑） |
| `DLP_IDLE_TIMEOUT` | `10m` | 数据流空闲超时（下限 2m） |
| `DLP_MAX_LIFETIME` | `6h` | 数据流寿命上限（下限 30m） |

低于下限的值拒绝启动。日志只记事件、错误码与按日轮换 HMAC 后的 route 标识，不记 IP、routeId 或消息内容。

## 门禁

```bash
gofmt -l . && go vet ./... && go build ./... && go test ./... -race
```
