# 远端连接路线（实验性）

扫码中的地址或手动填写的地址可以指向家中电脑或远程服务器上运行的 DSH。`dsh-links` 当前正式支持的是可信局域网 Android Beta。本页说明插件内置的「远程连接」（DLP/1 中继，实验性），以及两种由你自己管理的跨网络路径。

| 方式 | 适合什么 | 公网入口 | 手机怎么加 |
|---|---|---|---|
| Tailscale | 个人电脑和自己的手机 | 无 | 扫同一张手机连接码（自动带上 Tailscale 地址） |
| Cloudflare Tunnel | 自己的域名、跨网络直连 | 有，由你的 Tunnel 管理 | 手动填写 `https://dsh.example.com` |
| 远程连接（DLP/1 中继） | 不想装 VPN、也不想自己配域名 | 中继（官方或自建），电脑与手机都只向外连 | 扫同一张手机连接码 |

## Tailscale 私网（实验性个人路径）

这是由用户自管的个人实验路径：电脑和手机加入同一个 tailnet，不开放路由器端口。配对时二维码会自动带上电脑的 Tailscale 地址，不需要手动填写。

1. 在电脑和 Android 手机上安装 Tailscale，并登录同一个 tailnet。
2. 让 dsh 与插件运行。电脑「手机连接」面板上，Tailscale 地址旁会标出 Tailscale。
3. 用 App 扫描这张连接码。主地址仍是最先连通的那条（通常是局域网私网地址）；码里的 Tailscale 地址另外存成备用直连。主地址不通时再试备用地址，两条路钉扎同一张证书。换 Wi-Fi 不会改变已固定的主机身份。

手动添加仍然可用：填写 `https://100.x.y.z:18640` 和当前 6 位配对码。App 把 `100.64.0.0/10` 按私网自签证书钉扎指纹。手动添加没有二维码，不会自动写入备用地址。

## Cloudflare Tunnel

这条路径使用你自己的 Cloudflare 账号与域名。Tunnel 从电脑主动建立出站连接，把 `https://你的域名` 转发到本机 `https://127.0.0.1:18640`，不需要路由器端口转发。

```bash
cloudflared tunnel login
cloudflared tunnel create dsh-links
cloudflared tunnel route dns dsh-links dsh.example.com
cloudflared tunnel run dsh-links
```

配置 Tunnel 时，只发布 18640 的手机 API；绝不要一并发布 DSH Web 管理台 3080。

```yaml
ingress:
  - hostname: dsh.example.com
    service: https://127.0.0.1:18640
    originRequest:
      noTLSVerify: true
  - service: http_status:404
```

在 App“手动添加”中输入 `https://dsh.example.com` 和电脑端当前配对码即可。域名侧 HTTPS 由 Cloudflare 提供，插件仍要求配对后发放的设备 Token。

### 当前 Cloudflare Access 限制

Android App 当前不会执行 Cloudflare Access 的网页登录，也不会携带 `Cf-Access-Jwt-Assertion`。因此配置 `originRequest.access.required: true` 会拒绝 App 的配对与运行请求；不要把该配置误认为当前可用。

## 远程连接（DLP/1 中继，实验性）

```
手机 App ── WSS ──> 中继 <── WSS ── 电脑插件（验证手机后才连本机 127.0.0.1:18640）
     └────────── 内层 HTTPS（钉扎插件证书）穿过中继 ──────────┘
```

1. 电脑：设置 →「手机连接」→ 打开「外出时也能连」。默认用官方中继 `wss://relay.dshlinks.com/ws`，也可以点「用自建中继」填自己的地址（自签证书时填中继证书指纹）。不需要接入码、账号或邀请。
2. 手机：扫**同一张**手机连接码。在家时 App 走局域网首配；不在同一网络时经中继首配，此时电脑上一定会出现「经远程首次配对」的待批准，确认是自己的手机再批准。
3. 已配对的手机下次连上电脑时会自动拿到远程能力，不用重扫。之后每次新建连接，App 先用约 1 秒探测局域网，通就走局域网，不通就走中继；换网络后重新选路。

中继看不到内容：它只拼接两条 WebSocket，内层 TLS 仍钉扎插件证书，业务授权仍是设备 Token。中继能看到的只有 IP、时间、流量大小与在线状态。官方中继每条路由每天限 5 GB。吊销设备会立即断开它的远程连接。

需要 App 与插件都是 2026-09-29 之后的版本（DLP/1）；旧 App 扫新码只会走局域网。自建中继见 `relay/` 与 `docs/rfc/0001-dlp1-remote-pipe.md` §14。

旧版 DeepLinks Relay（DLR/1，接入码 + 云端二维码）已下线：插件启动时清除遗留接入配置，旧的「· 云」设备在面板上标为「旧版云端配对，已停用」，可直接吊销。
