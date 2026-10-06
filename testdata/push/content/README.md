# 内容加密测试向量（DLPUSH/1）

> 位置：`testdata/push/content/`  
> 合同：`docs/rfc/0002-push-gateway.md` §5.4；PLAN v1.3 阶段 6。  
> 真实向量已由 I6.2 的 Go 测试生成并写入本目录。  
> HPKE / sealed 向量见并列目录 `../hpke/`。

## 算法

- AES-256-GCM；`nonce` 随机 12 字节；传输 `nonce || ciphertext || tag`（标准 base64，无换行）
- `aad` = UTF-8 `dlpush/1 content|` + `deviceId`
- **不得**与 token 封装共用 AAD 构造函数；token 前缀是 `dlpush/1 token|`

## 计划文件

| 文件 | 用途 | 状态 |
|---|---|---|
| `dlpush-v1-seal-open.json` | 正例：加密 / 解密得相同明文 | 已生成 |
| `dlpush-v1-negative.json` | 篡改；误用 `token|` 前缀必须失败；过期 / 损坏兜底 | 已生成 |

## 生成约定（阶段 6）

1. Go 生成固定密钥与 nonce 的正例；插件与 iOS NSE 共用。
2. 域分离负例：用 `dlpush/1 token|`+deviceId 作 aad 解密必须失败。
3. 另含 `ts` 过期与 tag 损坏用例（期望失败 / 通用文案路径）。

`test/push-chain.test.mjs` 用这里的内容向量和 `../hpke/` 的 token 向量，在本机跑通插件、网关和假 APNs。它只证明密文被原样转发且日志不含密钥或密文。iOS NSE 仍只有解密失败时的通用文案；没有真实 APNs、真机或网关部署。
