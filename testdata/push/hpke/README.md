# HPKE 测试向量（DLPUSH/1）

> 位置：`testdata/push/hpke/`  
> 合同：`docs/rfc/0002-push-gateway.md` §5.3；算法见 PLAN v1.3 阶段 6。  
> 真实向量已由 I6.2 的 Go 测试生成并写入本目录。  
> 内容加密向量见并列目录 `../content/`。

## 套件（已定）

RFC 9180 `mode_base`（`0x00`）：

- KEM `0x0020` DHKEM(X25519, HKDF-SHA256)
- KDF `0x0001` HKDF-SHA256
- AEAD `0x0003` ChaCha20-Poly1305

CryptoKit：`Curve25519_SHA256_ChachaPoly`。Go circl：同一组合。

- `info` = UTF-8 `dlpush/1 token`
- HPKE `aad` = UTF-8 `dlpush/1 token|` + `kid`（**不得**使用 `dlpush/1 content|`）
- `sealed` 线上值为 JSON **对象** `{v,kid,enc,ct}`（base64url 无填充）；不是字符串

## 计划文件

| 文件 | 用途 | 状态 |
|---|---|---|
| `rfc9180-a2-base.json` | RFC 9180 附录 A.2.1 官方向量（第一条加密） | 已生成 |
| `dlpush-v1-seal-open.json` | 本项目 info/aad/线上格式的正例 | 已生成 |
| `dlpush-v1-negative.json` | 篡改 enc/ct/aad/kid；误用 `content|` 前缀必须失败 | 已生成 |

## 生成约定（阶段 6）

1. Go 测试用固定种子生成 `dlpush-v1-*.json`，提交进本目录。
2. iOS 单测只做 Open（及无法注入临时密钥时的 RFC Open）；Seal 互通走 CI 产物或内嵌期望。
3. 负例每条注明被篡改字段与期望错误类别；域分离负例：用 `dlpush/1 content|`+kid 作 aad 必须失败。
4. 所有 base64 字段用 **无填充 base64url**；字节序与 RFC 9180 一致。
