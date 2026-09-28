# DeepLinks 远程哑管道 RFC（DLP/1）

> 状态：提案 v2，待协议评审  
> 更新：2026-09-28  
> 决策：LAN 保持正式主路径；远程改用可自建的 WebSocket 哑管道（DeepLinks Pipe，下称 **DLP/1**），逐步取代 DLR/1 的 Control 主路径。  
> 原稿备份：`dsh-links-哑管道中继方案.v1备份.md`  
> 执行者须知：本文 §5 为协议合同（逐字节），§10 为实现指引（到文件级），§11 为执行红线。**有歧义时以 §5 为准，并在 PR 描述里提出，不要自行发明。**

---

## 0. v1 → v2 变更摘要

| # | v1 问题 | v2 处理 | 章节 |
|---|---|---|---|
| 1 | `HOST_REGISTER` 无认证，任何知道 `routeId` 的人（被吊销的手机、截图者、Relay）都能抢注路由 | 插件持有 Ed25519 主机密钥；`routeId = H(hostPub)`；注册需对 Relay 随机挑战签名。`routeId` 不再是秘密 | §4.3、§5.3 |
| 2 | Agent 单 socket 多路复用且无流控，一条慢下载会卡住所有手机的 SSE | Agent 侧也改为「一流一 WSS」：长连接只收 `open` 通知，每条流另开数据 WSS 接入。Relay 仅拼接两条 socket，TCP 背压天然生效；删除 16 字节二进制帧头 | §4.2、§5.4 |
| 3 | `streamId` 归属矛盾（App 发送 vs Relay 生成，又进 MAC） | `sid` 由 Relay 分配、不进 MAC；MAC 绑定 App 生成的 nonce 与时间戳，Agent 做 nonce 缓存 | §5.5 |
| 4 | 编码未定，无法写测试向量；三个 `v` 含义不清 | 控制消息定为 JSON 文本帧；所有签名/MAC 的 transcript 给出逐字节布局；只保留一个协议版本号 `v=1`；二维码与 bootstrap 用新键 `remote`，不再复用 `relay.v` | §5.2、§5.6、§5.7 |
| 5 | 远程首配扩大攻击面（截图 5 分钟内全球可配） | 经远程完成的首配**一律**进入「本机确认」，不受开关影响；面板标注「来自远程」 | §6.3 |
| 6 | 插件看到的远程流量全是 `127.0.0.1`；`via` 由手机在请求体自报 | Agent 在进程内给本地连接打标签，插件据此判定来源；不再信任 `body.via` 做安全决策 | §6.1 |
| 7 | 「安全错误不得 fallback」过度泛化 | LAN 探测阶段证书不符 = 该地址不是本机，继续尝试其他候选；只有远程内层 TLS 不符 / 插件明确吊销才是硬停止 | §7.2 |
| 8 | 流寿命 30 分钟会周期性打断 SSE；未定义保活 | 数据流寿命默认 6 小时（下限 30 分钟），依赖现有 SSE 游标续接；Relay 对所有 socket 每 25 秒发 WS ping | §5.8 |
| 9 | 每 route 8 流过紧（route 按电脑、多手机共享） | 每设备 6、每 route 32，由 Agent 与 Relay 分层执行 | §5.8 |
| 10 | 兼容性描述不可实现（要求旧 App 提示升级；二维码无法按 App 能力协商） | 新字段用新键 `remote`，旧 App 按现有代码忽略；如实写出旧 App 行为 | §8 |
| 11 | 二维码增量约 200 字符 | 一个 16 字节种子经 HKDF 派生 bootstrap id/key；`routeId` 16 字节；增量约 100 字符 | §5.2 |
| 12 | 明文 `deviceId` 暴露给 Relay；吊销时在途流策略未定义；Relay 伪造错误码可诱导 App 删凭据 | 用不透明 `relayHandle`；吊销即关闭该设备所有流；Relay 转来的拒绝码只作提示，删除凭据只认内层 TLS 上插件的明确答复 | §5.5、§6.4、§7.4 |
| 13 | M0 在原型前冻结 | M0 只交草案 + 测试向量；M1 原型（含 Android 隧道）通过后才冻结 | §9 |
| 14 | 缺备选方案；UI 自相矛盾 | 补 §3；页面取消页签，设备列表只出现一处 | §3、§12 |

**v2.1 细节补充（2026-09-28 晚，为无人值守执行而补）**：主机密钥统一存 32 字节种子（§5.3）；Agent 收到 `ready` 后才连本地端口（§5.5）；关闭原因字段固定为错误码字符串（§5.7）；每 IP 并发连接按角色分开计（§5.8）；`remote-test` 的自检身份定义（§6.6）；Agent 模块接口（§10.3）；依赖安装方式、测试文件位置、端到端测试不进 `npm test`（§10）。执行任务单见桌面 `dsh-links-DLP1-M0M1任务单.md`。

---

## 1. 一句话结论

远程访问采用「电脑与手机都主动出站、Relay 只会合并拼接两条 WebSocket、业务信任留在 App 与插件」的模型。

Relay 不保存账号、邀请码、设备档案或业务内容；它看不到明文，也无法冒充电脑。插件在 Agent 连接本地端口**之前**用设备会合密钥验证手机；内层 HTTPS 仍钉扎插件证书，业务授权仍是 `x-dsh-link-token`。

## 2. 为什么要换

DLR/1 的 Control、邀请码、SQLite、双端口与专用证书隔离，适合维护者运营邀请制 Relay；但它让自建用户必须先搭一套「管理系统」，而他们真正需要的只是让自己的手机安全地连回自己的电脑。

| 问题 | DLR/1 | DLP/1 目标 |
| --- | --- | --- |
| 自建成本 | Control + Relay + 数据库 + 接入码 | Relay 单进程 + Caddy |
| 公网入口 | 两个原生 TLS 端口 | 一个 `wss://relay.example/ws` |
| Relay 知道什么 | 路由、注册、能力、撤销、租户 | 在线 route 的公钥哈希、活跃 stream、IP 限流与字节计数 |
| 谁能注册电脑路由 | 持 Control 签发凭据者 | 持该电脑 Ed25519 私钥者（只存在插件 state 中） |
| 业务认证 | App ↔ 插件 token | 不变 |
| 业务明文 | 无 | 无 |
| 主路径 | LAN 正式，Relay 内测 | LAN 正式，远程可选且可自建 |

## 3. 备选方案与取舍

| 方案 | 为什么不作为默认 |
| --- | --- |
| Tailscale / Headscale / ZeroTier | 要求手机与电脑都装并登录第三方 VPN，手机上与 Clash 等代理类 App 冲突；超出「一个插件 + 一个 App」的产品边界。可在文档中作为高级用户替代方案 |
| Cloudflare Tunnel / frp / ngrok 直接暴露 18640 | 等于把 `18640` 放到公网，违反 §3.2 红线；配对口也暴露给全网扫描 |
| wstunnel / chisel 等通用 TCP-over-WS | 可以转发字节，但没有「验证后才 dial」的会合门，任何知道地址的人都能让电脑连本地端口 |
| 保留 DLR/1 | 见 §2：自建门槛过高 |
| iroh / libp2p 打洞 | 移动网络 NAT 打洞成功率不稳定，仍需中继兜底；Android 集成成本高。可作为 v2 以后的直连优化 |

DLP/1 的独特价值只有两点：**无账号自建** 与 **验证后才 dial 的会合门**。其余能力尽量复用现有 LAN 路径。

## 3.1 产品边界：要做

- 局域网直连 `https://<host>:18640` 完全不变。
- 电脑 Agent 和 App 都只建立出站 WSS 连接。
- 用户可填自己的完整 Relay URL；官方 Relay 只是默认选项，不是依赖。
- Relay 不读取、不记录、不解释 HTTPS、SSE、Token、Prompt、文件或审批内容。
- 已配对手机的业务权限、设备吊销、审批和插件证书钉扎都继续由插件执行。

## 3.2 产品边界：不做

- Relay 用户账号、OAuth、邀请码、租户表或持久化设备目录。
- 把 `18640` 暴露到公网。
- 让 Relay 终止内层插件 HTTPS。
- 把中继地址、`routeId` 当作密钥。
- 为了「纯哑」而取消并发、限流、超时或资源上限。
- v1 不做：多中继同时在线、打洞直连、按设备分配独立 route。

---

## 4. 架构

### 4.1 LAN：原样保留

```text
手机 App ── HTTPS :18640 ──> dsh-links 插件 ──回环──> DSH Host
                 └─ 配对码 + 插件证书指纹 -> device token
```

LAN 配对码、设备确认、Token、TLS 指纹与 `18640` 的暴露规则均不改变。远程已就绪时，面板展示的仍然是**同一张手机连接码**，它额外携带 `remote` 字段。

### 4.2 Remote：一流两 WSS，Relay 只拼接

```text
                  ┌──────────────────────── Relay（单进程，内存态）────────────────────────┐
                  │                                                                      │
 电脑 Agent ══控制 WSS（长连接）══> route 表: routeId -> 控制连接                        │
      ▲           │                                                                      │
      │  open(sid)│<───────────── 手机 A 的流 1：client WSS（client_open） ──────────────── 手机 A
      │           │                                                                      │
      └─数据 WSS(host_accept sid)──> 拼接 sid: [Agent 数据 WSS] <==> [手机 client WSS]    │
                  └──────────────────────────────────────────────────────────────────────┘

 手机 ──────────── 内层 HTTPS（钉扎插件证书）穿过拼接后的字节管道 ────────────> 127.0.0.1:18640
```

一次远程 TCP 连接（OkHttp 眼中的一条 socket）的生命周期：

1. 手机新开一条 WSS，发送 `client_open`（带 MAC）。
2. Relay 分配 `sid`，经控制 WSS 把 `open{sid, req}` 推给 Agent。
3. Agent 验证 MAC；失败则回 `reject`，**不** dial 本地端口。
4. 成功则 Agent 新开一条数据 WSS，发送 `host_accept{sid}`（签名）。
5. Relay 把两条 WSS 拼接，向两端发 `ready`；Agent 收到 `ready` 后才连接 `127.0.0.1:<pluginPort>`。此后两条 socket 上只有二进制消息，Relay 原样转发。
6. 手机在管道上做内层 TLS（钉扎插件证书），之后是原有 HTTP / SSE。
7. 任一端关闭，Relay 关闭另一端。

与 DLR/1 一致：DLR/1 的 Agent 本来就是「收 OPEN → 新开连接 BIND → 与本地 socket 对接」（`src/relay/agent.js` 的 `handleOpen` / `bridgeToPlugin`），DLP/1 只是把传输换成 WSS、把认证换成主机签名 + 设备 MAC。

**为什么不多路复用**：多条流共用一条 socket 必须自己实现按流的额度窗口，否则慢消费者会阻塞整条 socket 上的所有流（队头阻塞）。一流一 socket 时，Relay 的 `Read → Write` 循环写不进去就不再读，背压沿 TCP 自然传回发送端，不需要任何流控帧。代价是每条新连接在 Agent 侧多一次 WSS 建连；OkHttp 连接池会复用已建连接，所以开销按连接计，不按请求计。实现可以预热（§10.2.4），协议无需变化。

### 4.3 信任与可见性

| 值 | 生成方 / 长度 | Relay 可见 | 是否秘密 | 用途 |
| --- | --- | --- | --- | --- |
| `hostKey`（Ed25519 私钥） | 插件，32 B 种子 | 否 | **是** | 证明「我是这台电脑」，注册 route、接受流 |
| `hostPub` | 由 `hostKey` 导出，32 B | 是 | 否 | Relay 验签 |
| `routeId` | `SHA-256("DLP1 route\0" ‖ hostPub)[0:16]` | 是 | 否 | 寻址；Relay 从 `hostPub` 推导，无需存储 |
| `keySeed` | 插件，32 B | 否 | **是** | 派生所有设备的 `deviceRelayKey` |
| `relayHandle` | 插件为每设备生成，16 B | 是 | 否 | 设备的不透明寻址号，替代明文 `deviceId` |
| `deviceRelayKey` | `HMAC(keySeed, …relayHandle)`，32 B | 否 | **是** | 手机证明自己是已配对设备 |
| `bootstrapSeed` | 插件，每张二维码 16 B | 否 | **是** | 派生首配用 `bootstrapId` / `bootstrapKey` |
| `bootstrapId` | 由种子派生，16 B | 是 | 否 | Agent 查找对应 key |
| 插件证书指纹 | 插件 | 否 | 否（防篡改锚） | 内层 TLS 钉扎 |
| `x-dsh-link-token` | 插件 | 否（在内层 TLS 内） | **是** | 业务授权与吊销 |

**Relay 不可信模型**：恶意或被入侵的 Relay 能看到 IP、时间、流量大小、哪个 route 在线、哪个 `relayHandle` 在连；能丢弃、延迟、重放 WS 消息，能伪造错误码。它**不能**：读取内容、伪造 MAC 或主机签名、通过内层证书校验、让 Agent 连接 `127.0.0.1:<pluginPort>` 以外的任何地址、冒充某台电脑注册 route。可用性不在保证范围内；客户端必须能恢复。

---

## 5. DLP/1 协议合同

本节是冻结对象（冻结时点见 §9）。v1 内新增字段必须可被旧实现忽略；改变语义必须升 `v`。

### 5.1 入口与传输

- 唯一入口：完整 URL，如 `wss://relay.example/ws`（路径可自定义，App/插件按原样使用）。
- `ws://` 仅允许在 debug 构建 / 测试中使用；生产配置解析与 UI 均拒绝。
- 反向代理在边缘终止外层 TLS；Relay 本体只监听回环或私有容器网络。
- 外层证书：默认按系统 CA 校验。用户显式提供 `outerPin`（叶证书 DER 的 SHA-256，64 位小写 hex）时，改为只校验该指纹、跳过 CA 与主机名。内层插件证书指纹始终必填。
- 禁用 `permessage-deflate`（三端都必须显式关闭）。
- 每条 WebSocket 连接的第一个消息由 Relay 发出（`hello`）；随后第一条客户消息决定该连接的角色，角色不可改变。
- 控制消息：WebSocket **文本**帧，UTF-8 JSON 对象，单条 ≤ 4 KiB，拒绝重复键。数据消息：WebSocket **二进制**帧，仅在 `ready` 之后出现。
- 编码约定：所有二进制字段为 base64url **无填充**，解码后长度必须精确等于表中长度，否则按 `PROTOCOL_ERROR` 处理。整数为 JSON number（安全整数范围内）。时间为 Unix **秒**。
- 未知字段：忽略。缺少必填字段 / 类型不符 / 长度不符：`PROTOCOL_ERROR`。

### 5.2 手机连接码（二维码）中的 `remote`

在现有二维码 JSON（`type`、`pairingCode`、`urls`、`certFingerprint`、`name`、`issuedAt`、`expiresAt`）基础上，**仅当 Agent 已注册成功（远程「已就绪」）时**增加：

```json
{
  "remote": {
    "e": "wss://relay.example/ws",
    "r": "<routeId, b64u 16B>",
    "s": "<bootstrapSeed, b64u 16B>",
    "p": "<可选 outerPin, 64 hex>"
  }
}
```

- 过期时间复用顶层 `expiresAt`（毫秒，现有字段），bootstrap 能力与配对码同生同灭，最长 5 分钟。
- App 本地派生（HKDF-SHA-256，`salt = routeId`，`ikm = bootstrapSeed`）：
  - `bootstrapId  = HKDF(ikm, salt, info = "DLP1 bootstrap id",  L = 16)`
  - `bootstrapKey = HKDF(ikm, salt, info = "DLP1 bootstrap key", L = 32)`
- 插件内存保存「当前码 + 上一张未过期的码」的种子；**不写入 state.json**；插件重启后旧码的远程首配失效（App 提示刷新二维码）。
- 增量约 100 字符。面板必须在现有固定尺寸下实测可扫（§9 M2 验收）。
- 旧键 `relay`（DLR/1，`v: 2`）语义不变，M5 前与 `remote` 互不影响。

### 5.3 电脑侧：主机密钥与 route

- 插件首次启用远程时生成 32 字节随机种子作为 Ed25519 私钥，保存在 `state.remote.hostKey`（b64u，32 B 种子；不存 PKCS#8，方便三端一致）。
  - Go：`ed25519.NewKeyFromSeed(seed)`。
  - Node：`crypto.createPrivateKey({ key: Buffer.concat([Buffer.from("302e020100300506032b657004220420", "hex"), seed]), format: "der", type: "pkcs8" })`；公钥取 `createPublicKey(priv).export({ format: "der", type: "spki" })` 的最后 32 字节。
  - Python（仅测试向量）：`cryptography` 的 `Ed25519PrivateKey.from_private_bytes(seed)`。
- `routeId = SHA-256("DLP1 route\0" ‖ hostPub)` 的前 16 字节。`"DLP1 route\0"` 为 ASCII 加一个 `0x00` 字节。
- 「更换中继」不换密钥（`routeId` 不变）；「重置远程身份」才换密钥，此时所有设备需在下次 bootstrap 获得新 `r`。

### 5.4 连接角色与消息

所有连接建立后，Relay 立即发送：

```json
{"t":"hello","v":1,"ch":"<b64u 32B 随机挑战>","now":1790000000}
```

客户须在 5 秒内发出第一条消息，否则 Relay 以 `4000` 关闭。

#### 5.4.1 控制连接（Agent，长连接）

Agent → Relay：

```json
{"t":"host_register","v":1,"pub":"<b64u 32B>","sig":"<b64u 64B>"}
```

`sig = Ed25519.sign(hostKey, T_register)`，见 §5.6。

Relay 校验通过后：由 `pub` 推导 `routeId`；若该 route 已有控制连接，则关闭旧连接（close `4010 REPLACED`），新连接生效（只有私钥持有者能注册，因此后到者胜是安全的）。回复：

```json
{"t":"registered","route":"<b64u 16B>","ping":20}
```

此后：

| 方向 | 消息 | 说明 |
| --- | --- | --- |
| Relay → Agent | `{"t":"open","sid":"<b64u 16B>","req":{…client_open 原样字段，去掉 t…}}` | 新流请求。`req` 不含客户端 IP |
| Agent → Relay | `{"t":"reject","sid":"…","code":"BAD_MAC","hostNow":1790000000}` | 拒绝；`hostNow` 仅在 `CLOCK_SKEW` 时出现 |
| 双向 | `{"t":"ping"}` / `{"t":"pong"}` | Agent 每 `ping` 秒发一次；60 秒无任何消息，Relay 关闭控制连接 |

控制连接上出现其他消息 → `4000`。

#### 5.4.2 数据连接（Agent，每流一条）

Agent → Relay：

```json
{"t":"host_accept","v":1,"pub":"<b64u 32B>","sid":"<b64u 16B>","sig":"<b64u 64B>"}
```

`sig = Ed25519.sign(hostKey, T_accept)`。Relay 检查：签名有效；`sid` 处于待接受状态；该 `sid` 所属 route 等于由 `pub` 推导的 route。任一不满足 → 仅关闭这条数据连接（`4000`），不影响手机侧的等待（等待超时后手机收到 `OPEN_TIMEOUT`）。

满足 → Relay 向**两端**各发 `{"t":"ready"}`，进入数据阶段。

#### 5.4.3 客户连接（手机，每流一条）

手机 → Relay：

```json
{
  "t":"client_open","v":1,
  "route":"<b64u 16B>",
  "kind":"device",
  "key":"<b64u 16B relayHandle 或 bootstrapId>",
  "ts":1790000000,
  "nonce":"<b64u 16B>",
  "mac":"<b64u 32B>"
}
```

- `kind` ∈ `device`（已配对，`key = relayHandle`）| `bootstrap`（首配，`key = bootstrapId`）。
- Relay 检查 IP 限流、route 在线、容量；然后分配 `sid`（16 B 随机），推 `open` 给 Agent，启动 10 秒等待。
- 成功：收到 `{"t":"ready"}`。失败：收到 `{"t":"error","code":"…"}` 后被关闭（关闭码见 §5.7）。

#### 5.4.4 数据阶段

- 只允许二进制消息；出现文本消息 → 关闭该对连接（`4000`）。
- 单条二进制消息 ≤ 256 KiB（发送端按 ≤ 64 KiB 切块；Relay 读上限 256 KiB）。
- Relay 对每个方向跑独立的「读一条 → 写一条」循环，写超时 30 秒；写不出去就不再读（背压）。**禁止**在 Relay 内做无界缓冲。
- 没有半关闭：任一端关闭（任何关闭码），Relay 以 `1000` 关闭另一端。TLS 的 `close_notify` 在数据内自行传递。
- 所有连接（含数据阶段）由 Relay 每 25 秒发送 WebSocket 协议层 ping，维持运营商 NAT 映射；对端库自动回 pong，不影响数据。

### 5.5 手机侧会合验证（Agent 执行）

Agent 收到 `open` 后，按以下顺序处理，**任一步失败即回 `reject` 且不 dial**：

1. 字段格式校验（长度、`v == 1`、`kind` 合法）。
2. `route` 必须等于本机 `routeId`。
3. 时间：`|ts − now| ≤ 60`，否则 `CLOCK_SKEW`（附 `hostNow`）。
4. 取 key：
   - `device`：在 `state.devices` 中查找 `remoteHandle == key` 的设备；找不到（未配对 / 已吊销 / pending 已过期）→ `UNKNOWN_KEY`。`deviceRelayKey = HMAC-SHA-256(keySeed, "DLP1 device key\0" ‖ relayHandle)`。
   - `bootstrap`：在内存种子表中查找 `bootstrapId == key`；不存在 → `BOOTSTRAP_UNKNOWN`；已过期 → `BOOTSTRAP_EXPIRED`；已消费 → `BOOTSTRAP_USED`。
5. `mac` 用常量时间比较；不符 → `BAD_MAC`。
6. 重放：`(kind, key, nonce)` 已在缓存中 → `REPLAY`。否则写入缓存，保留到 `ts + 60` 秒；缓存上限 10,000 条，满了 → `SERVER_BUSY`（不淘汰未过期条目）。
7. 容量：该设备活跃流 ≥ 6（bootstrap 每个 id ≥ 4）→ `DEVICE_LIMIT`；本机活跃流 ≥ 32 → `SERVER_BUSY`。
8. 插件本地服务未就绪（readiness ≠ ready）→ `LOCAL_UNAVAILABLE`。
9. 打开数据 WSS（或取一条预热连接）发送 `host_accept`，等待 `ready`（10 秒）。
10. 收到 `ready` 后才连接 `127.0.0.1:<pluginPort>`；在本地 socket 的 `connect` 事件里、**转发任何字节之前**登记来源标签（§6.1）。本地连接失败 → 以 `1011` 关闭数据 WSS。
11. 数据阶段中，本地 socket 与数据 WSS 任一方关闭或出错，另一方随即关闭，并删除来源登记与活跃计数。

说明：重放成功的后果最多是 Agent 连了本地端口，而攻击者无法通过内层 TLS；nonce 缓存是纵深防御，不是唯一防线。

### 5.6 Transcript 逐字节布局

`‖` 表示拼接；字符串为 ASCII，结尾的 `\0` 是一个 `0x00` 字节；`u8` 单字节；`u64be` 为 8 字节大端。

| 名称 | 布局 | 用途 |
| --- | --- | --- |
| `T_route` | `"DLP1 route\0" ‖ hostPub(32)` → SHA-256 取前 16 字节 | 推导 `routeId` |
| `T_register` | `"DLP1 host_register\0" ‖ u8(v) ‖ ch(32) ‖ hostPub(32)` | 控制连接注册签名 |
| `T_accept` | `"DLP1 host_accept\0" ‖ u8(v) ‖ ch(32) ‖ hostPub(32) ‖ sid(16)` | 数据连接接受签名；`ch` 为**本条数据连接**收到的挑战 |
| `T_client` | `"DLP1 client_open\0" ‖ u8(v) ‖ route(16) ‖ u8(kind) ‖ key(16) ‖ u64be(ts) ‖ nonce(16)` | `mac = HMAC-SHA-256(k, T_client)`；`kind`：`device=1`，`bootstrap=2`；`k` 为 `deviceRelayKey` 或 `bootstrapKey` |
| `T_devkey` | `"DLP1 device key\0" ‖ relayHandle(16)` | `deviceRelayKey = HMAC-SHA-256(keySeed, T_devkey)` |
| HKDF | RFC 5869，SHA-256；`salt = routeId(16)`；`ikm = bootstrapSeed(16)`；`info` 见 §5.2（ASCII，无结尾 0） | bootstrap 派生 |

测试向量：`testdata/dlp1/vectors.json`（M0 交付），包含固定 `hostKey` 种子、`ch`、`sid`、`relayHandle`、`keySeed`、`bootstrapSeed`、`ts`、`nonce` 及上表每一项的期望输出（hex）。Go / JS / Kotlin 三端测试读取同一文件。

### 5.7 错误码与关闭码

**Agent 拒绝码**（`reject.code`，经 Relay 以 `error` 转给手机，随后以 `4007` 关闭）：

| code | 含义 | App 行为 |
| --- | --- | --- |
| `BAD_MAC` | MAC 不符 | 不重试；提示「远程凭据无效，请回到局域网或重新扫码」；**不删凭据** |
| `UNKNOWN_KEY` | 设备 handle 未知（可能已吊销） | 同上；**不删凭据**（见 §7.4） |
| `CLOCK_SKEW` | 时间偏差 > 60 秒；附 `hostNow` | 用 `hostNow − 本机时间` 作为本主机的偏移量重试一次；仍失败则提示「手机时间不准」 |
| `REPLAY` | nonce 重复 | 生成新 nonce 立即重试一次 |
| `BOOTSTRAP_UNKNOWN` / `BOOTSTRAP_EXPIRED` | 二维码已刷新 / 过期 / 插件重启过 | 提示「请刷新电脑上的二维码」 |
| `BOOTSTRAP_USED` | 该码已完成一次配对 | 同上 |
| `DEVICE_LIMIT` | 该设备并发流已满 | 退避 1–3 秒后重试 |
| `SERVER_BUSY` | 电脑侧容量满 | 退避重试 |
| `LOCAL_UNAVAILABLE` | 插件本地服务未就绪 | 显示「电脑上的服务暂不可用」，退避重试 |

**发送规则**：凡是以 `4xxx` 关闭连接，Relay 都先发一条 `{"t":"error","code":"<CODE>"}`（Agent 拒绝时附带 `hostNow`），再关闭；WebSocket 关闭原因（reason）字段固定为同一个 code 字符串（≤ 32 字节）。接收方以 `error` 消息为准，消息丢失时以 close reason 为准，两者都没有时按关闭码映射。

**Relay 错误码**（`error.code`）与 WebSocket 关闭码：

| 关闭码 | code | 触发 |
| --- | --- | --- |
| `4000` | `PROTOCOL_ERROR` | 格式错误、角色违规、首条消息超时、数据阶段出现文本帧 |
| `4001` | `UNSUPPORTED_VERSION` | `v` 不支持 |
| `4002` | `AUTH_FAILED` | 主机签名无效 |
| `4003` | `ROUTE_OFFLINE` | route 无在线控制连接 → App 显示「电脑离线」 |
| `4004` | `RATE_LIMITED` | IP / route 限流 |
| `4005` | `SERVER_BUSY` | Relay 全局容量满 |
| `4006` | `OPEN_TIMEOUT` | 10 秒内 Agent 未接受 |
| `4007` | （Agent 拒绝码） | 见上表 |
| `4008` | `IDLE_TIMEOUT` | 数据流空闲超时 |
| `4009` | `LIFETIME_EXCEEDED` | 数据流达到寿命上限 |
| `4010` | `REPLACED` | 同一 route 的新控制连接顶替了旧连接 |
| `1001` | — | Relay 正常关停 |

### 5.8 限额与超时（默认值）

| 项 | 默认 | 执行方 | 可配置 |
| --- | --- | --- | --- |
| 首条消息超时 | 5 秒 | Relay | 否 |
| 流打开超时（`client_open` → `ready`） | 10 秒 | Relay | 否 |
| 数据流空闲超时 | 10 分钟 | Relay | 可下调，下限 2 分钟 |
| 数据流寿命上限 | 6 小时 | Relay | 可下调，下限 30 分钟 |
| 单次写超时 | 30 秒 | Relay / Agent | 否 |
| WS 协议层 ping | 25 秒 | Relay | 否 |
| 控制连接应用层 ping | 20 秒（`registered.ping`） | Agent | Relay 下发 |
| 每 route 并发流（含待接受） | 64 | Relay | 可下调，下限 16 |
| 每设备并发流 | 6 | Agent | 否 |
| 每个 bootstrapId 并发流 | 4 | Agent | 否 |
| 每台电脑并发流 | 32 | Agent | 否 |
| Relay 全局并发流 | 2,000 | Relay | 可调 |
| 每 IP `client_open` | 60 次/分钟，突发 20 | Relay | 可下调 |
| 每 IP 并发客户连接（`client_open` 角色 + 尚未确定角色的连接） | 64 | Relay | 可下调，下限 16 |
| 每 IP 并发主机连接（控制 + 数据 + 预热） | 256 | Relay | 可下调，下限 64（同一 NAT 后可能有多台电脑） |
| 每 IP `host_register` | 10 次/分钟 | Relay | 可下调 |
| 每 route 日流量（官方 Relay） | 5 GiB | Relay | 自建默认关闭 |

所有拒绝计入聚合指标。「可下调」不得低于下限，也不得关闭。

说明：SSE 依赖现有游标续接（`docs/MOBILE_SYNC_CONTRACT.md`「快照与增量」）。插件每 15 秒发送 `: keepalive`，不会触发空闲超时；达到寿命上限断开后，App 按已提交游标重连即可。

---

## 6. 插件侧规则

### 6.1 来源判定：不以回环地址做授权

Agent 连的是 `127.0.0.1:<pluginPort>`，插件看到的远程流量对端都是回环地址。规则：

1. **不变量**：18640 上任何授权判断都不得依赖「对端是回环地址」。现有 `PANEL_ONLY_PATHS`（`src/index.js`）按路径屏蔽，满足此不变量，必须保持。
2. Agent 在本地 socket `connect` 事件中，以该 socket 的 `localPort` 为键登记 `remoteOrigins.set(localPort, { kind, deviceId?, bootstrapId?, sid })`，在 `close` 时删除。插件处理请求时：对端为回环地址且 `req.socket.remotePort` 在表中 → 远程来源；否则为 LAN 来源。
3. `handlePair` 中的 `via` 改由第 2 条判定；请求体里的 `body.via` 仅为兼容旧 App 保留，**不得**参与限流 key、确认策略或安全判断。
4. 配对限流 key：LAN 来源用 `lan::<对端 IP>`（现状）；远程 bootstrap 来源用 `remote::<bootstrapId>`；远程 device 来源不应出现在 `/pair`（见第 5 条）。
5. bootstrap 来源的连接只允许 `POST /dsh-link/pair`，其余路径一律 `403`。
6. device 来源的连接：token 解析出的 `deviceId` 必须等于来源标签里的 `deviceId`，否则 `403`（防止拿 A 设备的会合密钥承载 B 设备的 token）。
7. `pairedFrom` 显示：远程来源写「远程（经中继）」，不再显示 `127.0.0.1`。

### 6.2 state 结构（全部位于 `stateDir` 下）

```jsonc
{
  "remote": {
    "enabled": true,
    "endpoint": "wss://relay.example/ws",
    "outerPin": "",               // 可选
    "hostKey": "<b64u 32B 种子>",  // 秘密
    "keySeed": "<b64u 32B>",      // 秘密
    "createdAt": 1790000000000
  },
  "devices": [
    { "deviceId": "dev-…", "remoteHandle": "<b64u 16B>", "remoteIssuedAt": 1790000000000, "…": "…" }
  ]
}
```

- `hostKey`、`keySeed` 不得出现在任何日志、诊断、面板 API 响应中；`publicDevice()` 不输出 `remoteHandle`。
- `bootstrapSeed`、nonce 缓存、`remoteOrigins` 只在内存。
- 「停止远程」：`enabled=false`，停止 Agent，保留密钥（重新启用时设备无需重新获取能力）。
- 「重置远程身份」（远程设置里的危险操作，需二次确认）：重新生成 `hostKey` 与 `keySeed`，清空所有设备的 `remoteHandle`。

### 6.3 配对（`POST /dsh-link/pair`）

- 远程 bootstrap 来源：`requireConfirm` **强制为 true**，不受「配对需本机确认」开关影响。
- 成功（HTTP 200）时，在同一个同步临界区内：消费配对码；若为 bootstrap 来源，把 `bootstrapId` 标为已消费；为新设备生成 `remoteHandle`（远程已启用时）。`409 SAME_NAME` 不消费（与现有配对码语义一致）。
- 响应在远程已启用时增加（LAN 来源与远程来源一致）：

```json
{
  "remote": {
    "e": "wss://relay.example/ws",
    "r": "<routeId>",
    "h": "<relayHandle>",
    "k": "<deviceRelayKey b64u 32B>",
    "p": "<可选 outerPin>"
  }
}
```

- pending 设备同样获得 `remote`：Agent 接受 pending 设备的会合（内层 API 在批准前仍是 `403`），这样纯远程首配的手机能等到批准结果。pending 过期或被拒绝时删除设备，handle 随之失效。
- 保留现有 `requestId` 重放缓存：LAN 在请求可能已送达后失败时，App 可用同一 `requestId` 经远程重试。

### 6.4 已认证 `GET /dsh-link/mobile/bootstrap`

- 远程已启用：返回该设备自己的 `remote` 对象（同上）；设备还没有 `remoteHandle` 时在此刻生成并写 `remoteIssuedAt`。已配对手机因此在下一次 bootstrap（走任一路径）时自动补齐远程能力，无需重新扫码。
- 远程未启用 / 已停止：返回 `"remote": null`。
- 旧键 `relay` 行为保持不变至 M5。

### 6.5 吊销

`revokeDeviceEntry` 在现有逻辑之外：

1. 删除设备记录（`remoteHandle` 随之消失，新 `client_open` 得到 `UNKNOWN_KEY`）。
2. 调用 `remoteAgent.dropDevice(deviceId)`：立即关闭该设备所有活跃数据流（本地 socket 与数据 WSS 都关闭）。

「吊销全部」同理。

### 6.6 面板 API（全部加入 `PANEL_ONLY_PATHS`，并 `requireLoopbackSameOrigin`）

| 路径 | 方法 | 作用 |
| --- | --- | --- |
| `/dsh-link/remote-status` | GET | `{ state: "off"｜"connecting"｜"ready"｜"error", endpoint, lastOnlineAt, error, remoteDevices }`，不含任何密钥 |
| `/dsh-link/remote-enable` | POST `{ endpoint, outerPin? }` | 校验 URL（生产只接受 `wss://`）→ 生成密钥（若无）→ 启动 Agent，等待 `registered`（15 秒） |
| `/dsh-link/remote-disable` | POST | 停止远程 |
| `/dsh-link/remote-test` | POST | 自检：Agent 在内存中生成一个 60 秒有效的临时 `relayHandle`（查找函数返回 `{ deviceId: "selftest", selftest: true }`），以 `kind=device` 充当客户端经 Relay 打开一条流，钉扎本机证书完成内层 TLS 握手后关闭。selftest 来源的连接上所有 HTTP 路径一律 `403`。返回各阶段耗时（外层连接 / 会合 / 内层 TLS）与失败阶段。不改变协议 |
| `/dsh-link/remote-reset-identity` | POST | 见 §6.2 |

### 6.7 Agent 重连

- 控制连接断开：指数退避 1 秒起、上限 60 秒、±30% 抖动；稳定 60 秒后重置退避。
- 收到 `4010 REPLACED`：退避不少于 60 秒，面板提示「另一处正在以这台电脑的身份连接中继（可能是另一个 DSH profile 共用了插件状态目录）」。这是全局共享 state 的已知风险，用 `stateDir` 隔离即可解决。
- 收到 `4002 AUTH_FAILED` / `4001`：停止重连，面板显示错误。

---

## 7. App 侧规则

### 7.1 存储

- Host 记录新增：`remoteEndpoint`、`remoteRouteId`、`remoteHandle`、`remoteKey`、`remoteOuterPin`、`remoteClockOffsetSec`。
- 与现有字段一样，随 hosts 列表整体经 `TokenCrypto`（Android Keystore AES-GCM）加密存储（`HostStore.kt` 现有机制）。不得另写明文偏好项，不得进日志。
- 与 DLR/1 字段（`relay*`）并存，互不覆盖；M5 删除 DLR/1 字段。

### 7.2 自动选路

「自动切换」是**每次新建连接时选路**，不是按 SSID 判断，也不是把正在传输的连接硬切到另一条路径。

1. **网络代**：`ConnectivityManager.NetworkCallback` 的 `onAvailable` / `onLost` / `onLinkPropertiesChanged` 使网络代 +1。网络代变化时：清空选路缓存；对该主机的两个 OkHttpClient 调用 `connectionPool.evictAll()`；按已提交游标重连 SSE。
2. **缓存**：`{ generation, route: LAN(url)|REMOTE, until }`。LAN 结果保留 30 秒；REMOTE 结果保留 15 秒（回家后能较快切回 LAN）。
3. **探测**（无有效缓存时）：对 `urls[]` **并行**做 TCP 连接（单地址 800 毫秒）+ 钉扎证书的 TLS 握手，不发送任何 HTTP、配对码或 Token；总预算 1.2 秒；第一个成功者胜出。
4. 某个 LAN 候选**证书不符** = 该地址不是本机（常见于咖啡馆同网段、DHCP 把 IP 分给了别的机器）。标记该候选不可用，继续其他候选；全部不可用则走远程。因为两条路径同样钉扎插件证书，这里不存在降级攻击面。
5. 所有 LAN 候选都不可用，且主机有远程能力 → 走远程。
6. 已经运行的 SSE 或请求不做中途迁移。
7. **不重放非幂等请求**：只有在「路由建立阶段」失败（`RouteConnectException`：LAN 探测失败、隧道 `error`/关闭发生在 `ready` 之前）时才可换路径重试；请求体开始写出后的任何失败都交给上层，按现有请求状态语义处理。
8. **硬停止**（不换路径、不自动重试）：远程内层 TLS 证书不符；插件在内层 TLS 上返回设备已吊销。
9. debug 构建提供「强制远程」开关，用于验收测试。

### 7.3 隧道实现

- `WebSocketTunnelSocketFactory`：沿用 `RelaySslSocketFactory` 已验证的「`connect()` 内建隧道 + 本机网关 ServerSocket」模式（OkHttp 5 需要裸连接阶段就是可读写的真实 socket）。
- 外层 WSS 用独立的 OkHttpClient（系统 CA，或 `outerPin` 钉扎；关闭压缩）。
- `connect()`：打开 WSS → 等 `hello` → 发 `client_open` → 等 `ready`（总超时取 min(调用方 connectTimeout, 10 秒)）→ 建网关对接。
- 下行：`WebSocketListener.onMessage(bytes)` 中**阻塞地**写入网关 socket。OkHttp 每条 WebSocket 有独立读线程，阻塞即形成背压，这是有意设计。
- 上行：独立线程从网关读，按 ≤ 64 KiB 调 `ws.send()`；`queueSize() > 1 MiB` 时等待。
- 任一侧关闭 → 关闭另一侧与网关。
- 连接失败按 §5.7 映射为类型化异常（`RouteConnectException` 子类），供 §7.2 判定。

### 7.4 不可信错误码

Relay 转来的 `error` 码（包括 `UNKNOWN_KEY`、`BAD_MAC`）可能是恶意 Relay 伪造的。App **只能据此显示提示，不得删除或修改本地凭据**。删除设备凭据、清除远程能力只认两种依据：

- 插件在内层 TLS 上的明确答复（现有吊销响应）；
- 已认证 bootstrap 返回 `"remote": null`（只清远程字段，保留 LAN 配对）。

### 7.5 用户可见状态

| 状态 | 文案 | 触发 |
| --- | --- | --- |
| LAN | 局域网 | 当前连接走 LAN |
| 远程 | 远程 | 当前连接走 Relay |
| Relay 不可达 | 连不上中继服务器 | 外层 WSS 连接 / TLS 失败 |
| 电脑离线 | 电脑不在线 | `4003 ROUTE_OFFLINE` |
| 会合失败 | 远程凭据无效，请回到局域网或重新扫码 | `BAD_MAC` / `UNKNOWN_KEY` |
| 时间不准 | 手机时间不准，请校准 | 偏移重试后仍 `CLOCK_SKEW` |
| 证书不符 | 电脑身份校验失败（硬停止） | 内层 TLS 证书不符 |
| 已吊销 | 此手机已被电脑移除 | 插件内层答复 |
| 本地服务不可用 | 电脑上的服务暂不可用 | `LOCAL_UNAVAILABLE` / 内层 503 |

### 7.6 扫码配对流程

1. 解析二维码；本地检查 `expiresAt`，过期直接提示刷新（现有行为）。
2. 并行 LAN 探测（§7.2 第 3–4 条）。成功 → 走现有 LAN 配对，请求带 `requestId`。
3. LAN 全部不可用且二维码含 `remote` → 派生 bootstrap id/key，经 `kind=bootstrap` 隧道、钉扎 `certFingerprint` 做内层 TLS，`POST /dsh-link/pair`（同一 `requestId`）。
4. 响应含 `remote` → 与 token 一起保存到**同一条** Host 记录。
5. `pending: true` → 显示「等待电脑上批准」，之后用 `kind=device` 远程流轮询（现有 pending 轮询逻辑）。
6. 配对码错误、过期、证书不符属于认证失败：不换路径重试。

---

## 8. 兼容性

| 组合 | 行为（依据现有代码） |
| --- | --- |
| 旧 App 扫新二维码 | `PairingQr.kt` 不认识 `remote` 键，直接忽略，按 `urls` 走 LAN 配对。LAN 不可达时报连接失败，**不会**误报「已配对」。无法让旧 App 提示升级；面板二维码下方加一行「外网首配需 App ≥ <版本>」 |
| 旧 App 收新 bootstrap | `applyBootstrapRelay` 只读 `relay` 键；`remote` 被忽略。插件在 M5 前继续按现状下发 `relay` |
| 新 App 连旧插件 | 二维码与 bootstrap 均无 `remote`；新 App 按纯 LAN（或 DLR/1）工作 |
| 新 App + 新插件 + DLR/1 仍启用 | `relay` 与 `remote` 并存；App 选路优先级：LAN → DLP/1 → DLR/1 |
| 迁移期旧「云端独立设备」 | 面板标为「旧远程配对」，提供「重新扫描并合并」操作；不静默合并、不静默吊销 |

不需要能力协商：新能力使用新键，旧实现按现有代码忽略未知键。

---

## 9. 里程碑

| 阶段 | 交付 | 完成条件 |
| --- | --- | --- |
| M0 草案 | 本 RFC 入库 `docs/rfc/0001-dlp1-remote-pipe.md`；`testdata/dlp1/vectors.json`；JS / Go / Kotlin 三端向量测试 | 三端向量测试全绿；评审无阻塞意见。**此时不冻结** |
| M1 原型 | Go Relay；Node Agent（独立模块）；Android `WebSocketTunnelSocketFactory` 最小实现与 JVM 集成测试 | §13.1 全部通过；§13.2 的并发 / 队头阻塞 / 背压测试通过。**M1 结束冻结 DLP/1 v1** |
| M2 插件 | 来源标签、state、pair/bootstrap、吊销联动、面板 API、新页面 | LAN 零回归（`npm run prepack` 全绿）；验证前不 dial；统一二维码在面板尺寸下实测可扫；远程三状态可用 |
| M3 Android | 存储、选路、配对流程、状态文案 | 新旧 App 版本门槛写入 `docs/COMPATIBILITY.md`；同一张码可 LAN 首配、也可远程首配；已配对设备自动补齐远程能力 |
| M4 自建部署 | `relay/deploy/dlp/`：Compose + Caddyfile + 文档 | 从空 VPS 到真机远程会话按文档完成，并记录证据 |
| M5 DLR/1 弃用 | 旧路径默认关闭、迁移提示、Control 用例与文档归档 | 另开 RFC 决定删除时间 |

---

## 10. 实现指引（到文件级）

### 10.1 目录约定

**新增，不改 DLR/1 代码**（M5 之前，`src/relay/`、`relay/internal/{control,store,ingress,bridge,registry,protocol,agent}`、`RelaySslSocketFactory.kt` 只允许读取，不允许修改）。

```text
docs/rfc/0001-dlp1-remote-pipe.md        # 本文
testdata/dlp1/vectors.json               # 三端共用测试向量
relay/cmd/dlp-relay/main.go              # 新 Relay 入口
relay/internal/dlp/                      # 新 Relay 实现
  wire.go        消息结构、JSON 解析、b64u 定长校验
  crypto.go      routeId 推导、Ed25519 验签、transcript 构造
  hub.go         route 表、待接受 sid 表、拼接
  limits.go      IP / route / 全局限额，令牌桶
  config.go      环境变量解析
  *_test.go
relay/deploy/dlp/{docker-compose.yml,Caddyfile,README.md}
src/remote/
  crypto.js      transcript、HKDF、HMAC、Ed25519 签名、routeId
  agent.js       控制连接、open 处理、数据连接、本地对接、来源登记
  bootstrap.js   内存种子表、nonce 缓存
  state.js       state.remote 读写、设备 handle 生成
test/remote-*.test.mjs
apps/android/app/src/main/java/dev/deeplinks/core/remote/
  DlpCrypto.kt                  transcript、HKDF、HMAC
  WebSocketTunnelSocketFactory.kt
  RouteSelector.kt              网络代、缓存、并行探测
  RemoteRoute.kt                数据类 + JSON 解析（二维码 / pair / bootstrap）
apps/android/app/src/test/java/dev/deeplinks/core/remote/*Test.kt
```

### 10.2 Relay（Go）

1. 依赖：`github.com/coder/websocket`（`cd relay && go get github.com/coder/websocket@latest`；`AcceptOptions{CompressionMode: websocket.CompressionDisabled, InsecureSkipVerify: true}`。不校验 Origin，因为协议不使用 cookie 等环境凭据）。Ed25519、HMAC、SHA-256 用标准库。**不引入数据库**。`go.mod` 声明 `go 1.25.0`，本机 Go 为 1.22.5，会自动使用已缓存的 1.25 工具链，不要改 `go.mod` 的 go 版本。
   - 读上限：握手阶段 `conn.SetReadLimit(4096)`，进入数据阶段后改为 `256*1024`。
   - `conn.Ping(ctx)` 会阻塞到收到 pong，且要求同一连接上有 goroutine 在读；在独立 goroutine 中以 10 秒超时调用，失败即关闭连接。
   - 关闭：`conn.Close(websocket.StatusCode(4003), "ROUTE_OFFLINE")`；关闭前先 `wsjson.Write` 发 `error` 消息（写超时 2 秒）。
2. HTTP：`/ws` 升级；`/healthz` 返回 `200 ok`；指标在独立回环端口 `/metrics`（可选，默认关）。
3. 客户端 IP：仅当直连对端属于 `DLP_TRUSTED_PROXIES`（默认 `127.0.0.1/32,::1/128`）时，才取 `X-Forwarded-For` 最右侧的非可信地址；否则用直连地址。可参考 `relay/internal/ingress/ipkey.go` 的思路，但在 `dlp` 包内重写，不跨包引用。
4. 每条连接一个 goroutine：发 `hello` → 5 秒内读第一条消息 → 按 `t` 分派到 `serveControl` / `serveHostData` / `serveClient`。
5. `hub` 结构（全部加一把 `sync.Mutex`，临界区内不做 I/O）：
   - `routes map[[16]byte]*route`，`route{ctrl *conn, active int, pending int, bytesToday int64}`；
   - `pending map[[16]byte]*pendingStream`，`pendingStream{route [16]byte, client *conn, created time.Time, accepted chan *conn}`。
6. 拼接：两个 goroutine，各自循环 `typ, r, err := src.Reader(ctx)`；非二进制即关闭；`w := dst.Writer(ctx, MessageBinary)`；`io.CopyN(w, r, 256KiB+1)`，超过上限即关闭；写操作使用带 30 秒超时的 ctx。空闲 / 寿命计时用 `time.Timer`。
7. 日志：只记事件类型、错误码、日轮换 HMAC 后的 route 标识（`HMAC(dailyKey, routeId)[0:8]` hex）、字节聚合。禁止记录原始 IP（官方模式）、`routeId`、任何消息体。`DLP_LOG_IP=1` 仅供自建调试。
8. 配置（环境变量）：`DLP_LISTEN`（默认 `127.0.0.1:8411`）、`DLP_TRUSTED_PROXIES`、`DLP_MAX_STREAMS`、`DLP_ROUTE_MAX_STREAMS`、`DLP_IP_OPEN_PER_MIN`、`DLP_IP_MAX_CONNS`、`DLP_IDLE_TIMEOUT`、`DLP_MAX_LIFETIME`、`DLP_ROUTE_DAILY_BYTES`（0 = 关闭）。低于下限的值拒绝启动。
9. 门禁：`cd relay && gofmt -l . && go vet ./... && go build ./... && go test ./internal/dlp/... -race`。

### 10.3 插件 Agent（Node）

1. 依赖：新增 `ws@^8`（零依赖；用 `createWebSocketStream` 得到带背压的 Duplex，再与本地 socket `pipeline`）。`perMessageDeflate: false`，`maxPayload: 256 * 1024`。同步更新 `THIRD_PARTY_NOTICES.md`。
   - 仓库用 pnpm（`pnpm-lock.yaml` 已提交，`node_modules` 是 pnpm 布局），本机 PATH 没有 pnpm。安装命令：`corepack pnpm@9 add ws@^8`。**禁止**用 `npm install` 加依赖（会生成 `package-lock.json` 并破坏 pnpm 布局）。
   - corepack 不可用或无网络时：不要换别的办法绕过，把这一项记入汇报，继续做不依赖 `ws` 的部分（crypto、向量、Go Relay、Android）。
2. 外层 TLS：默认 CA 校验；有 `outerPin` 时 `rejectUnauthorized: false`，并在 `upgrade` 之前的 `secureConnect` 阶段比对 `getPeerCertificate().raw` 的 SHA-256，不符立即销毁 socket。
3. Ed25519：`crypto.sign(null, data, privateKey)`；HKDF：`crypto.hkdfSync`；比较用 `crypto.timingSafeEqual`。
4. `open` 处理严格按 §5.5 的顺序；每一步有单测。
5. **预热（可选，默认开启）**：Agent 保持最多 2 条已收到 `hello` 的空闲数据 WSS；收到 `open` 且验证通过后，用其中一条（用它自己的 `ch`）发送 `host_accept`，并立即补一条新的。空闲 60 秒的预热连接关闭并重建。
6. 本地对接：`net.createConnection({ host: "127.0.0.1", port: pluginPort })`；在 `connect` 回调里先 `remoteOrigins.set(local.localPort, tag)`，再 `pipeline(wsStream, local)` 与 `pipeline(local, wsStream)`；`close` 时删除登记。
7. **模块接口**（M1 按此实现，M2 由 `src/index.js` 调用；M1 不修改 `src/index.js`）：

```js
// src/remote/agent.js
export class RemoteAgent extends EventEmitter {
  constructor({
    endpoint,          // "wss://…"；测试可传 "ws://127.0.0.1:<port>/ws" 并设 allowInsecureWs: true
    outerPin,          // "" 或 64 位小写 hex
    hostKeySeed,       // Buffer(32)
    keySeed,           // Buffer(32)
    pluginPort,        // number；只连 127.0.0.1
    lookupDevice,      // (relayHandle: Buffer) => { deviceId: string, selftest?: boolean } | null，每次 open 现查
    bootstrap,         // BootstrapTable 实例（见 bootstrap.js）
    isLocalReady,      // () => boolean
    logger,            // { info, warn }；禁止传入秘密
    allowInsecureWs = false,
    prewarm = 2,
    now = () => Date.now(),
  })
  start(): Promise<void>        // 启动并保持重连；resolve 于首次 registered
  stop(): Promise<void>         // 关闭全部连接
  get status()                  // "off" | "connecting" | "ready" | "error"
  get routeId()                 // Buffer(16)
  originOf(localPort)           // 来源标签或 undefined（§6.1）
  dropDevice(deviceId)          // 关闭该设备全部流，返回关闭数量
  selfTest({ certFingerprint }) // §6.6，返回各阶段耗时
  // 事件："status"（新状态）、"replaced"、"stream-open"（{ kind, deviceId }）、"stream-close"
}

// src/remote/bootstrap.js
export class BootstrapTable {
  issue(routeId, expiresAtMs)   // 生成新种子，返回 { seed: Buffer(16), bootstrapId: Buffer(16) }；保留上一张未过期的
  lookup(bootstrapId)           // { key, expiresAtMs, consumed } | null
  consume(bootstrapId)          // 配对成功时调用
}

// src/remote/crypto.js：全部为纯函数，每个 transcript 一个导出函数，名称与 RFC §5.6 对应
```

8. 与 `src/index.js` 的集成点（M2）：`handlePair`（§6.1、§6.3）、mobile bootstrap（§6.4）、`revokeDeviceEntry`（§6.5）、`requestHandler`（bootstrap 来源的路径白名单、device 来源的 deviceId 绑定）、`pairInfo` / `qrPayload`（§5.2）、面板路由（§6.6）。每个集成点改动尽量小，把逻辑放在 `src/remote/` 里导出纯函数。
9. 门禁：`npm run prepack`。
10. 测试布局：
    - `test/remote-crypto.test.mjs`（向量）、`test/remote-agent.test.mjs`（用 `ws` 的 `WebSocketServer` 在测试内写一个最小假 Relay，覆盖 §5.5 每个拒绝分支和「验证前不 dial」）、`test/remote-bootstrap.test.mjs`。这些测试进 `npm test`，**不得**依赖 Go、网络或真实 state。
    - 真 Relay 端到端：`scripts/dlp1-e2e.mjs`，在 `package.json` 增加脚本 `"test:dlp1-e2e": "node scripts/dlp1-e2e.mjs"`。脚本先用 `go build -o <临时目录>/dlp-relay ./cmd/dlp-relay`（在 `relay/` 下）编译，再启动 Relay、Agent、一个充当插件的 Node `https` 假服务（自签证书，临时目录）、若干个 Node 客户端（`tls.connect({ socket: wsDuplex })` 做内层 TLS）。**不进** `npm test` / `prepack`。

### 10.4 Android

1. `DlpCrypto.kt` 用 `javax.crypto.Mac`（HmacSHA256）；HKDF 自己实现 extract + expand（约 20 行），用向量测试锁定。App 不需要 Ed25519。
2. `RemoteRoute.kt` 解析三处 `remote`：二维码（`e/r/s/p`）、pair 响应和 bootstrap（`e/r/h/k/p`）。bootstrap 语义：有键且为对象 → 更新；`null` → 清远程字段；无键 → 不动（与 `applyBootstrapRelay` 相同）。
3. `HostHttp.kt`：新增 `remoteClient(host)`（`socketFactory` = 隧道工厂的裸 socket，`sslSocketFactory` = 内层钉扎），客户端缓存 key 包含 `remoteEndpoint | remoteRouteId | remoteHandle | remoteKey 哈希 | outerPin`。路由选择交给 `RouteSelector`，替换现有 `failoverRouteOrder` 的调用点；DLR/1 分支作为 DLP/1 之后的第三候选保留到 M5。
4. `PairingQr.kt` / 配对流程按 §7.6 调整；`hostFromPair` 不再给名字加「· 云」后缀（远程是连接能力，不是另一台设备）。
5. 门禁：`cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。
6. 测试：
   - 向量文件路径：JVM 单测的工作目录是 `apps/android/app`，用 `File("../../../testdata/dlp1/vectors.json")` 读取，不要复制到资源目录。
   - 模拟 Relay：新增测试依赖 `com.squareup.okhttp3:mockwebserver3`（版本跟 `libs.versions.toml` 里的 `okhttp` 一致），在 `libs.versions.toml` 登记后用 `testImplementation` 引入；用 `MockResponse.Builder().webSocketUpgrade(listener)` 实现最小 Relay 行为（发 `hello`、校验 `client_open`、发 `ready` 后回显二进制）。
   - 隧道测试至少覆盖：`ready` 前各种 `error` 码映射为对应的 `RouteConnectException` 子类；回显 1 MiB 数据完整无误；对端关闭后 socket 读到 EOF；在隧道上用自签证书 + 钉扎完成一次 TLS 握手与 HTTP 请求。
7. 不要对任何 release 变体跑设备测试；M1 不需要真机（见 `apps/android/CLAUDE.md` 红线）。

### 10.5 面板 UI

按 §12 实现；改动集中在客户端面板对应组件，新增 `/dsh-link/remote-*` API 调用。DLR/1 的旧区块移入「远程设置 → 旧版中继（DLR/1）」折叠区，仅当 `state.relay` 有凭据时显示。

---

## 11. 执行红线（给实现者）

1. **state 隔离**：任何冒烟 / 联调 / 测试都必须用独立 `stateDir`（经 `--patch` 的 `- id: dsh-links, config: {stateDir: ...}`）。**禁止**对用户全局 state 调用任何吊销、重置远程身份、吊销全部类操作。2026-09-12 曾因此吊销了用户两台真机。
2. **不动 DLR/1**：M5 前不修改、不删除 DLR/1 生产路径（见 §10.1 的只读清单）。
3. **不回滚他人改动**：开工前运行 `git status`；工作区里已有的未提交改动不属于本任务，不要格式化、回滚或提交它们。
4. **不重启用户的 host**：用户的 DSH host 若以 `link:` 方式加载本仓，需要重启时先询问用户，并确认没有其他会话在该 host 上工作。
5. **秘密不落日志**：`hostKey`、`keySeed`、`bootstrapSeed`、`deviceRelayKey`、`bootstrapKey`、Token、二维码正文、数据帧内容，不得出现在日志、错误消息、诊断、测试快照或文档示例中。三端各写一个测试：用固定秘密跑完整流程，断言日志输出里不含这些值。
6. **常量只有一份**：每种语言的协议常量（前缀字符串、长度、限额、错误码）集中定义在一个文件里，并由向量测试覆盖。
7. **不自行扩展协议**：遇到本文未覆盖的情况，在 PR 描述里列出，不要自行加消息类型或改语义。
8. **门禁必须全绿**才能提交：插件 `npm run prepack`；Relay 见 §10.2 第 9 条；Android 见 §10.4 第 5 条。
9. 代码注释沿用仓库风格（中文，解释「为什么」）。

---

## 12. 插件页面

### 12.1 当前问题

现在的「手机连接 → 远端连接」围绕「粘贴接入码、接入、名额、控制台、断开 / 释放」组织，并把 LAN 码、云端码和设备记录拆成两套。用户要解决的是「我的手机现在能否安全连接这台电脑」，不是管理 Relay 租户或挑选该扫哪张码。

### 12.2 信息架构（单列，不分页签）

二维码与设备列表都只有一份，分页签会导致它们在两个页签里重复出现，因此取消页签。

```text
手机连接                                          ● 本机服务正常
这台电脑可由已配对的手机安全访问                     配对需本机确认：已开启

┌ 手机连接码 ───────────────────────────────────────────┐
│  [二维码]   剩余 4:12                        [刷新]    │
│  在家自动优先局域网；外出自动使用远程。                │   ← 远程已就绪
│  此码可添加设备，请勿截图或转发。                      │
│  证书指纹 [查看] [复制]                                │
└───────────────────────────────────────────────────────┘

┌ 待批准 (1) ───────────────────────────────────────────┐
│  Pixel 9 · 来自远程（经中继）         [批准] [拒绝]    │
└───────────────────────────────────────────────────────┘

┌ 已配对手机 (3) ───────────────────────────────────────┐
│  Alice 的手机   局域网 · 远程   最近 2 分钟前  [吊销]   │
│  Bob 的手机     仅局域网        最近 3 天前    [吊销]   │
│  旧手机         旧远程配对      [重新扫描并合并] [吊销] │
└───────────────────────────────────────────────────────┘

┌ 远程连接（可选） ─────────────────────────────────────┐
│  ● 已就绪  relay.example.com   电脑上线于 10:02        │
│  3 台手机具备远程能力                    [测试连接]    │
│  ▸ 远程设置                                            │
└───────────────────────────────────────────────────────┘
```

视觉：状态优先、少量卡片；蓝色只用于可操作项，绿色只表示已验证就绪，黄 / 红只用于需要用户处理的风险。不用云、账号、租户、名额图标。

### 12.3 手机连接码卡

- 副文案：远程已就绪时写「在家自动优先局域网；外出自动使用远程。此码可添加设备，请勿截图或转发。」；未启用远程时写「仅限可信局域网」。
- 远程已就绪时追加一行小字：「外网首次配对需要电脑上确认」。

### 12.4 已配对手机卡

| 标签 | 条件 |
| --- | --- |
| `局域网 · 远程` | 远程已启用，且设备有 `remoteHandle` |
| `仅局域网` | 远程未启用，或设备还没有 `remoteHandle` |
| `远程待补齐` | 远程已启用但设备尚无 handle。说明：「下次连接时自动补齐，或重新扫码」 |
| `旧远程配对` | DLR/1 时代的 `via: relay` 设备 |
| `已吊销` | 吊销后短暂显示 |

吊销只在这里，全页唯一。

### 12.5 远程连接卡：三种状态

**未启用**：「远程连接是可选功能。电脑和手机都只向外连接中继；中继无法读取你的内容。」操作：`使用官方中继`、`配置自建中继`（不出现邀请码输入框）。

**设置中**：自建时只输入完整 WSS URL（可选外层证书指纹）；点击 `连接并验证`，逐步显示「连接中继 → 校验证书 → 注册电脑」及失败原因。Agent 注册成功后，手机连接码自动升级为含 `remote` 的版本。

**已就绪 / 需处理**：显示 `已就绪`、Relay 域名、电脑最后上线时间、具备远程能力的设备数；次级操作 `测试连接`。`远程设置` 折叠区内：`更换中继`（提示「尚在外网的手机会断开，直到回到局域网或重新扫码」）、`停止远程`（「仅停止外网入口；局域网和已配对设备不受影响」）、外层证书指纹、`重置远程身份`（危险，二次确认）、诊断入口、旧版中继（DLR/1）区块。

### 12.6 文案替换

| 旧 DLR/1 表达 | 新表达 |
| --- | --- |
| 接入码 / 接入 | 启用远程 / 连接中继 |
| 已接入 | 已就绪 |
| 控制台 | 远程设置 |
| 释放名额 | 停止远程 |
| 局域网码 / 云端二维码 | 手机连接码 |
| 远端连接 | 远程连接 |
| 设备名 ·云 | （删除后缀，用标签表示） |

---

## 13. 验收清单

### 13.1 安全与协议（M1 必须全部自动化）

- [ ] 三端向量测试一致（routeId、两种签名、MAC、HKDF、设备 key）。
- [ ] 没有 `hostKey` 的连接不能注册 route；对 route A 的合法 `host_register` 不能接受 route B 的 `sid`。
- [ ] 用旧 `ch` 重放 `host_register` / `host_accept` 失败。
- [ ] 没有设备 key 的客户端不能让 Agent dial 本地端口（断言本地监听端口未收到连接）。
- [ ] MAC 覆盖 v、route、kind、key、ts、nonce：逐字段篡改均失败；同 nonce 重放返回 `REPLAY`；`|ts−now|>60` 返回 `CLOCK_SKEW`。
- [ ] 吊销设备后：新 `client_open` 返回 `UNKNOWN_KEY`；该设备已有的数据流在 1 秒内被关闭。
- [ ] bootstrap：过期、已消费、未知三种情况的错误码正确；bootstrap 来源访问 `/dsh-link/pair` 以外的路径得到 `403`；经 bootstrap 完成的配对一定是 pending。
- [ ] device 来源的连接携带其他设备的 token 时得到 `403`。
- [ ] Agent 永不连接 `127.0.0.1:<pluginPort>` 以外的地址（代码中不存在从消息读取目标地址的路径）。
- [ ] 各类畸形输入（非 JSON、重复键、超长、错误长度的 b64u、数据阶段出现文本帧）只关闭当前连接，Relay 不 panic；为消息解析器写 Go fuzz 测试。
- [ ] 资源耗尽：超过每 IP / 每 route / 全局限额时返回正确关闭码，且不影响其他 route。
- [ ] 日志不含秘密（§11 第 5 条）。
- [ ] App 在远程路径上拒绝插件证书不符。
- [ ] Relay 伪造 `UNKNOWN_KEY` 时 App 不删除凭据。

### 13.2 性能与可靠性（M1 原型需通过）

- [ ] 一台电脑两台手机：每台 1 条 SSE + 4 条并发 API + 1 个 100 MB 下载；把其中一台手机限速到 256 kbps，另一台的 SSE 事件延迟 p95 < 500 ms（证明没有队头阻塞）。
- [ ] 手机停止读取下载流 60 秒：Relay RSS 增长 < 16 MB（证明背压生效、没有无界缓冲）。
- [ ] 远程首个请求端到端耗时（含隧道 + 内层 TLS）在 100 ms RTT 链路上 < 1.5 s；连接复用后的请求 < 1 RTT + 服务时间。

M1 在本机用 `scripts/dlp1-e2e.mjs` 验证前两条，方法如下：

- 「限速」用慢读模拟：慢客户端每秒只从内层 TLS 流读 32 KiB（`pause()` / `resume()`），不做网络层限速。
- 假插件提供 `GET /sse`（每 200 ms 推一条带发送时间戳的事件）、`GET /api`（立即返回小 JSON）、`GET /big`（100 MB 流）。
- 延迟 = 客户端收到事件的时间 − 事件内的时间戳，统计 30 秒内的 p95。
- RSS 用 `ps -o rss= -p <relay pid>` 在慢读开始前、60 秒后各取一次。
- 第三条需要人为制造 RTT（`tc netem` 或 macOS 的 `dnctl`，都要 root），M1 标记为「未验证」。

### 13.3 真实体验与运行（M3–M4）

- [ ] 普通 API、发送消息、SSE、文件下载可以并发，SSE 不阻塞其他请求。
- [ ] 手机 / 电脑 / Relay 分别重启，以及 Wi-Fi 与蜂窝网络互切后，都能自动恢复。
- [ ] Relay 重启和 Caddy 重载后连接恢复，错误文案准确。
- [ ] 真机用同一张手机连接码完成：LAN 首配 → 断开 LAN 后远程会话 → 回到 LAN 后自动切回（≤ 15 秒 + 网络代变化）→ 审批 → 吊销 → 重新配对。
- [ ] 真机纯远程首配：扫码 → 电脑上出现「来自远程」的待批准 → 批准 → 手机可用。
- [ ] 两个 DSH profile 误共用 state 时，面板出现 `REPLACED` 提示，且不会以 1 秒间隔反复抢占。
- [ ] 24 小时 soak、容量测量、公网 TLS 验证与 RC1 证据分开记录；缺失项写「未验证」。

---

## 14. 自建部署

```text
Internet ──> Caddy :443（TLS / WebSocket upgrade） ──> dlp-relay 127.0.0.1:8411
```

两种部署任选其一：

- Caddy 与 Relay 都在宿主机：Relay 绑定 `127.0.0.1:8411`。
- Caddy 与 Relay 在同一私有 Docker 网络：Relay 不发布宿主机端口，只允许 Caddy 访问 `relay:8411`；`DLP_TRUSTED_PROXIES` 设为该网络网段。

Caddyfile 最小示例：

```caddyfile
relay.example.com {
    reverse_proxy /ws 127.0.0.1:8411
    respond /healthz 200
}
```

Caddy 会自动处理 WebSocket 升级且没有默认读超时，无需额外配置。防火墙只开放 80 / 443。Cloudflare 代理（橙云）不是禁区，但首版默认只用 DNS；启用代理前必须单独验证重连、长 SSE、发布导致的断线恢复与流量限制。

### 14.1 官方 Relay 的最小运维合同

- 执行 §5.8 全部限额，并启用每 route 日流量上限（无账号的开放 Relay 必然面临被当作通用隧道滥用的风险，只能靠这些限额兜底）。
- 原始 IP 与 `routeId` 不写业务日志；诊断只记录日轮换 HMAC 后的 route 标识、错误码、连接 / 字节聚合值。
- 原始连接日志（Caddy access log）保留不超过 7 天；日聚合容量指标保留不超过 30 天；均不含帧内容。
- 滥用处置：Relay 内存中的 route 拒绝列表（按 routeId，带过期时间），通过本机管理端口操作；不引入数据库。
- 临时 IP 封禁、容量告警、维护重启要有用户可理解的状态页或错误提示。
- 官方 Relay 不承诺不间断连接；客户端重连是协议功能，不是运维补救。

---

## 15. 已知限制

- Relay 能看到元数据：IP、时间、流量大小、route 在线状态、`relayHandle` 活动。v1 不做流量混淆。
- 「更换中继」后，尚在外网、还没拿到新 endpoint 的手机会断开，直到回到局域网或重新扫码。v1 不支持多中继同时在线。
- 手机时间偏差超过 60 秒且 `hostNow` 偏移重试仍失败时，无法远程连接。
- 全局共享 state 下，多个 DSH profile 会以同一身份抢占 route（有 `REPLACED` 提示与退避，但根治靠 `stateDir` 隔离）。
- 官方 Relay 无账号，只能依靠限额防滥用。

## 16. 后续动作

1. 本文件移入 `docs/rfc/0001-dlp1-remote-pipe.md`，评审 §5 与 §6。
2. 编写 `testdata/dlp1/vectors.json` 与三端向量测试（M0）。
3. M1：Relay + Node Agent + Android 隧道原型，跑通 §13.1 与 §13.2 后冻结协议。
4. 在新协议冻结并完成真机验收之前，不修改或删除 DLR/1 生产路径。
5. 用独立子域部署自建实例；真机与稳定性验收通过后，再考虑官方默认 Relay。
