# testdata/tls-fingerprint

I3.4 证书指纹共享向量：插件（`src/tls.js`）、Android（`PinnedSsl`）、iOS（`DLSecurity`）三端用同一份 `vectors.json` 校验指纹算法与格式。

## 内容

- `vectors[]`：每条含 `name`、`certDerBase64`（自签证书的 DER）、`fingerprint`（期望值：证书 DER 的 SHA-256，小写十六进制、无冒号）。
- `normalizeCases[]`：带冒号 / 大写 / 空格 / null 的输入 → 期望规范化结果，规则与 Android `PinnedSsl.normalizeFingerprint` 一致（lowercase → 去冒号 → 去空格 → trim）。

## 向量是怎么生成的

1. `/usr/bin/openssl`（LibreSSL 3.3.6）在 `/tmp` 生成三张自签证书（私钥只在 `/tmp`，生成后已删除，仓库只保留证书公开部分）：
   - `rsa-2048`：`openssl req -x509 -newkey rsa:2048 -sha256 -nodes -subj "/CN=dsh-links-rsa"`
   - `ec-p256`：`openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -sha256 -nodes -subj "/CN=dsh-links-ec"`
   - `chinese-cn-and-long-san`：`-utf8 -subj "/CN=工作室电脑" -addext "subjectAltName=DNS:studio.local,DNS:studio-workstation-long-name.example.local,IP:192.168.10.17,IP:fd00::1234"`
2. 期望指纹由插件 `certFingerprintSha256` 的算法（`X509Certificate.fingerprint256` 去冒号转小写）对 PEM 计算并与 DER 直哈希（SHA-256 of `certDerBase64` 解码结果）比对一致后写入，保证三端逐位一致。
