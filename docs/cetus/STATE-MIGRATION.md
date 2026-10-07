# state 目录迁移与回滚（维护者手册）

面向**维护者**。方案 §22.3（迁移红线）/ §22.4（回滚）/ §22.5（验收矩阵）/ §32（升级与回滚交付物）的落地说明。

- 仓库：`/Volumes/Space/Dev/dsh-cetus`，分支 `cetus/main`
- 实现提交：`d5cb24194601d3f69b1aba3d035fb274b184163c`（`d5cb2419`，2026-10-08 00:37:15 +0800）
- 记录日期：2026-10-08
- 代码：`src/state-migration.js`（新增）、`src/index.js` 的 `ensureStateDir()`（集成）
- 测试：`test/state-migration.test.mjs`（31 例）、`test/state-migration-wiring.test.mjs`（6 例）
- 门禁：`npm run prepack` = **419 tests / 419 pass / 0 fail**（含本文档所述 37 例）

> **前提背景**：2026-09-12 曾有人用全局 state 做冒烟测试，teardown 时吊销了用户两台真机的配对。
> 这段代码碰的是**用户唯一一份配对身份**（配对证书、主机密钥、设备批准、推送注册）。
> 全部设计取舍都以一句话为准：**宁可拒绝启动，也绝不产生第二份身份、绝不静默清空。**

---

## 1. 为什么必须重构，不能只改常量

改名前 `src/index.js` 的实现是「新目录没有 `state.json` → 找第一个有 `state.json` 的 legacy 目录 → 整体 `cpSync`」。
只把常量从 `dsh-links` 改成 `dsh-cetus` 会直接触发这条路径，有四个独立缺陷：

| # | 缺陷 | 后果 |
|---|---|---|
| 1 | 只看 `state.json` 是否存在，**不校验内容与身份** | 复制到一半、磁盘满、源损坏都会"看起来成功了" |
| 2 | 没有冲突检测 | 新旧目录是两台不同"电脑"时会拼在一起 |
| 3 | `cpSync` 失败时兜底为**只写 `state.json`** | `tls.json` 丢失 |
| 4 | 无锁、非原子、无幂等标记 | 并发启动或中断后状态不明 |

**缺陷 3 的事故链最严重**：`tls.json` 丢失后，`src/tls.js` 的 `loadOrCreateTls()` 会
**静默生成一把新证书**。而 App 按 TLS SHA-256 指纹钉死证书 ——
**指纹一变，所有已配对手机全部需要重新扫码配对。**

所以第一条设计原则：**缺 `tls.json` 就停下报错，绝不"顺手"生成。**

---

## 2. 迁移算法（对照 `src/state-migration.js`）

入口：`resolveStateDir(config, { home, now, log, freeSpace })`。
`src/index.js` 的 `ensureStateDir()` 只做集成与错误文案拼装，**不实现逻辑**。

### 2.1 分支决策（红线 1）

```
显式 config.stateDir 非空？
├─ 是 → 直接用它。只做权限收敛。不扫描、不复制、不生成任何默认目录内容
└─ 否 → target = <home>/.dsh/dsh-cetus
        source = candidatesFor(base)：
                 第一个含 state.json 的 [dsh-links, dsh-deepharness, dshlinks]
                 都不含则回退到第一个候选 dsh-links

        target 已有有效状态（hasStateFiles）？
        ├─ 是 且 source 无有效状态 → 幂等命中，不改任何字节
        ├─ 是 且 source 有效       → inspectStateDir 双方 → assertSameIdentity
        │                             一致 → 幂等命中；不一致 → 抛 identity-conflict 停下
        └─ 否（全新安装）           → 只建空目录，不预生成任何密钥
```

### 2.2 真迁移的六步（红线 2）

| 步 | 动作 | 实际实现要点 |
|---|---|---|
| 1 | 取迁移锁 | `acquireLock()`：`writeFileSync(..., { flag: "wx" })` 独占创建 |
| 2 | 清理上次残留 | `cleanStaleStaging(parent)`：只删 `.migrating-*` 前缀目录 |
| 3 | `mkdtemp` 出 staging | 建在 `join(targetDir, "..")`，即**同父目录** |
| 4 | **整目录**复制 | `movableEntries()` + `cpSync` 逐条；详见 §2.3 |
| 5 | 校验 | 必需文件存在 → `verifyEntry()` 递归逐字节 → `inspectStateDir` → `assertSameIdentity` |
| 6 | 原子提升 + 写记录 | `atomicPromote()` = `renameSync`；`writeMigrationRecord()` 写 `migration.json` |

任一步失败：删除 staging、抛 `StateMigrationError`、**源目录一个字节都不动**，且不留半个 target。

### 2.3 「默认搬全部、只排除运行期文件」的实际实现

**这是本节最容易被误读的一条。** 实现是**排除式**，不是白名单式：

```js
export const MIGRATION_EXCLUDED = [LOCK_FILE, MIGRATION_FILE]   // .migration.lock, migration.json
const TRANSIENT_SUFFIX = /\.\d+\.tmp$/                          // 原子写残留，如 state.json.12345.tmp

export function movableEntries(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    if (MIGRATION_EXCLUDED.includes(name)) continue   // 排除：本模块自己的运行期文件
    if (TRANSIENT_SUFFIX.test(name)) continue          // 排除：原子写中间产物
    out.push(name)                                     // 其余**全部搬运**
  }
  return out.sort()
}
```

要点：

- **排除列表只有两项 + 一个 tmp 后缀**。目录里任何其他东西（`state.json`、`tls.json`、
  `state.json.bak-before-dlp-*`、未来的新持久化索引、嵌套子目录）都会跟着走。
- 另有 `STATE_DIR_REQUIRED = ["state.json", "tls.json"]`，但那**只是"缺了就拒绝迁移"的前置检查**，
  **不是复制白名单**。二者不可混淆。
- **为什么必须是排除式**：最初写成了白名单（只列 `state.json` + `tls.json`），
  会**静默漏掉 `state.json.bak-before-dlp-20260929-235210`**。
  真实目录里确实有这个备份文件。白名单漏一项 = 一次静默数据丢失。
- 用例：#4 复刻真实目录形状（`state.json` + `tls.json` + `.bak-*` 全部搬过去）、
  #5 未知新文件也一起走、#6 `*.tmp` 不搬、#7 迁移粒度。

### 2.4 权限（红线 3）

`hardenPermissions(target)` **递归**处理：目录 → `0700`，文件 → `0600`。

> ⚠️ 真实踩坑：最初对所有复制结果一律 `chmod 600`，导致**嵌套目录丢执行位**
> （`0600` 的目录进不去），里面的文件连读都读不到 —— 属于静默破坏数据的一类 bug。
> 现在按 `statSync().isDirectory()` 区分。

### 2.5 符号链接（安全）

真实实现是 `cpSync` 的两个选项 + 一个前置守卫：

```js
assertLinkStaysInside(from, sourceDir, name)   // 越界预检（见下）
cpSync(from, to, {
  recursive: true,
  force: true,
  dereference: false,        // 不跟随：保留链接本体
  verbatimSymlinks: true,    // 链接路径原样保留，不重写
  errorOnExist: false,
})
```

`assertLinkStaysInside()` 的判断逻辑：

1. `lstatSync` 不是符号链接 → 直接返回（不管）
2. 是链接 → `realpathSync(target)`；解析失败（悬空链接）→ 抛 `unsafe-symlink`
3. `relative(realpathSync(sourceDir), resolved)`，若以 `..` 开头或为绝对路径
   → 抛 `unsafe-symlink`（指向目录之外）

> ⚠️ 真实踩坑：最初用 `dereference: true`，一个指向 `~/.ssh/id_rsa` 的软链会把
> **私钥内容复制进 state 目录** —— 能导致凭据泄漏到意外位置的安全缺陷。
> 用例：#30 顶层越界拒绝、#31 内部链接保留为链接本体。

**已知实现缺口（见 §7.3）**：该守卫只对**顶层条目**调用一次，
嵌套在子目录里的软链不会被检查。实测结论是**不会泄漏内容**（因为 `dereference: false`
仍会把嵌套链接原样保留为链接），属防御纵深缺口而非安全漏洞。详见 §7.3。

### 2.6 身份一致性校验：`assertSameIdentity` 到底比对什么

**只比对两项**，任一不一致即抛 `identity-conflict`：

| 序 | 比对字段 | 来源 | 说明 |
|---|---|---|---|
| 1 | `tlsFingerprint` | `tlsFingerprintOf(dir)` 读 `tls.json` 的 `cert` 算 X509 SHA-256（去冒号小写）；记录里有 `fingerprint` 字段则优先用它 | 决定手机能否连上 |
| 2 | `remoteHostKey` | `remoteHostKeyOf(state)` = `state.remote.hostKey` | 决定中继身份会不会被顶掉 |

注意三点（**容易记错**）：

- **不比对 `deviceId`**。`deviceId` 是设备记录的标识，不是身份锚点。
- **`hostKey` 比对是"两边都存在才比"**（`if (a && b && a !== b)`）。
  若某一侧没有远程身份（`""`），**不会**因此报冲突。
- **不比对设备数组本身**。设备集合通过"整目录复制 + 指纹一致"间接保证。
  冲突时的行为（红线 4）逐条是：**抛错停下** —— 不拼设备数组、不选较新者、不合并、
  不生成第三把密钥、两边数据都原样保留。

用例：#11 证书身份不同 → 停下且不拼设备、#12 hostKey 不同 → 停下、#13 冲突时 `ready=false`。

### 2.7 迁移锁：过期时间与接管条件

```js
export const LOCK_STALE_MS = 5 * 60 * 1000      // 5 分钟
```

`acquireLock(parentDir, now)` 逻辑：

1. 锁文件 `.migration.lock` 在**目标目录的父目录**（即 `~/.dsh/`），不在 state 目录内
2. 锁已存在 → 取 `age = now - statSync(lock).mtimeMs`
   - `age < 5min` → 抛 `locked`（"另一个进程正在迁移"）
   - `age >= 5min` → 视为持有者已崩溃，`rmSync` 删掉后接管
   - `statSync` 抛错（如权限）→ `age = Infinity` → **走接管路径**
3. 用 `{ flag: "wx" }` 独占创建；创建失败（已被别人抢先）→ 抛 `locked`
4. 锁内容 `{ pid, at }`，权限 `0600`
5. `finally` 中 `releaseLock()` 无条件 `rmSync(force)`

> 注意 `now` 默认是 `Date.now()`，而接管判断用的是**文件 mtime 与 now 的差**，
> 所以依赖系统时钟正常。用例 #26 覆盖"持有中跳过"与"过期可接管"两条路径。

### 2.8 日志与落盘内容（红线 8）

**绝不打印**：私钥、`remote.hostKey` / `keySeed`、设备 token、推送 token、state 原文。

**可以写/可以打**：目录路径、设备数、TLS 指纹（日志里只打前 12 位 + `…`）。

`migration.json` 字段白名单（`publicRecord()`，**只有这 7 个字段**）：

```json
{
  "migrationVersion": 1,
  "sourcePath": "/Users/<user>/.dsh/dsh-links",
  "status": "migrated",
  "at": 1791389980811,
  "deviceCount": 2,
  "tlsFingerprint": "11c4672ae86a60830e783fa129deb1cd049de1caad90620d50dc0768db52460f",
  "note": "源目录保持原样作为受保护备份；确认新目录正常后可自行删除。"
}
```

**为什么 `tlsFingerprint` 可以写、`hostKey` / 私钥不可以**：

- TLS 指纹是**公开身份**，不是秘密。它本来就要给手机看（App 按指纹钉死证书，
  配对二维码与面板都展示它）。写进记录不扩大暴露面，却能让人一眼核对
  "迁移前后是不是同一台电脑"，是排查身份错乱的关键证据。
- 私钥、`remote.hostKey` / `keySeed`、设备/推送 token 是**凭据**：
  拿到即可冒充本机连中继或推消息，且没有任何排查价值。

**给以后加字段的人的判据**：这个值给攻击者有没有用？有用 = 不要加。
用例 #27 守着这条，覆盖全部失败路径。

---

## 3. 失败语义：方案 A

`state` 解析发生在 `apply()` 中**任何服务注册之前**（`src/index.js` 1352–1379 行）：

```js
const stateFile = statePathOf(config, ctx.logger)   // 1352：解析 + 迁移在这里
const state = loadState(stateFile)                  // 1353
...
saveState(stateFile, state)                         // 1379：落盘
sweepExpiredPending(state, stateFile, rt)           // 1380
// ↓ 之后才开始注册面板、TLS 服务、远程 Agent
```

因此 **A 方案**（迁移失败 `throw`，`apply()` 不 catch）是唯一正确的语义：

- 抛错 ⇒ 后续代码根本不执行 ⇒ **不可能**出现"半迁移身份"去注册 Relay
  （否则同一主机密钥的后注册者会把用户原有主机顶掉，中继日志表现为 `REPLACED`）
- 宿主感知插件加载失败，用户看到可操作诊断

### 错误信息四要素

`ensureStateDir()` 在 `!resolved.ready` 时拼装：

```
state 目录迁移失败（<code>）：<原因>
原数据保留在：<sourcePath>
目标目录：<targetPath>
处理建议：<remedy>
插件不会启动，也不会创建新的空身份；修好后重启即可。
```

### 错误码全表（对照代码实际抛出的 code）

| code | 含义 | 用户该做什么 |
|---|---|---|
| `locked` | 另一进程正在迁移 / 抢锁失败 | 等它结束；确认无其他进程后删 `.migration.lock` |
| `identity-conflict` | 新旧目录身份不同（指纹或 hostKey） | 确认哪份是真的，删掉错的那份（或显式配 `stateDir`） |
| `tls-missing` | 源缺 `tls.json`，或 `tls.json` 缺 `cert`/`key` | **从备份恢复，不要重新生成** || `source-corrupt` | 源 JSON 解析失败 | 修好或移走该文件 |
| `tls-corrupt` | `tls.json` 的证书无法解析 | 从备份恢复 |
| `incomplete-source` | 源缺必需文件 / 目录为空 | 从备份恢复 |
| `read-failed` | 读取源文件失败（含 EACCES） | 检查权限 |
| `copy-failed` | 复制失败 / 复制后逐字节不一致 | 检查磁盘与权限后重试 |
| `disk-full` | 空间不足（含 20% 余量判断） | 腾空间后重试 |
| `unsafe-symlink` | 顶层软链指向目录外或悬空 | 换成目录内真实文件，或删掉软链 |
| `explicit-dir-unusable` | 配置的 `stateDir` 不可用 | 检查该路径权限 |
| `failed` | 兜底包装（非 `StateMigrationError` 的异常） | 看 message |

`src/index.js` 另有一个 `migration-failed` 兜底码，用于 `resolved.error` 意外为空的情况。

> **已知小瑕疵**：`tls-missing` 有**三个**抛出点，其中
> 「`tls.json` 缺 `cert`/`key`」那一处（`tlsFingerprintOf()` 内）**没有带 `remedy` 字段**，
> 因此用户看到的信息里会少一行"处理建议"（其余两个抛出点都有）。
> 属文案完整性问题，不影响安全行为。修法：给该抛出点补 `remedy`。

---

## 4. §22.5 矩阵 ↔ 用例对应

```bash
node --test test/state-migration.test.mjs          # 31/31 pass
node --test test/state-migration-wiring.test.mjs   #  6/6 pass
```

| §22.5 场景 | 期望 | 用例（编号 = 文件内顺序） |
|---|---|---|
| 无旧数据全新安装 | 创建新默认目录，首次配对正常 | #1 场景1、#2 场景1b |
| 有效旧数据、新目录不存在 | 完整迁移，设备与证书身份不变 | #3 场景2、#7 场景2b、#4 场景2c、#5 场景2d、#6 场景2e |
| 用户配置自定义目录 | 原目录继续用，不触碰默认目录 | #8 场景3 |
| 重复启动 / 重复迁移 | 幂等，不重复注册、不回覆盖 | #9 场景4、#10 场景4b、#22 场景7b |
| 新旧目录冲突 | 停止并说明，原数据保留 | #11 场景5、#12 场景5b、#13 场景5c |
| 源损坏 / 权限拒绝 / 磁盘满 | 拒绝静默初始化，可恢复 | #14 场景6a、#15 场景6b、#16 场景6c、#17 场景6d、#18 场景6e、#19 场景6e-2、#20 场景6f |
| 复制中断 | 识别临时状态并继续或安全回退 | #21 场景7、#22 场景7b、#26 场景7c |

**矩阵之外的加固用例**（来自真实机器核对）：

| 用例 | 守的是什么 |
|---|---|
| #23–#25 源选择 | 实测 `~/.dsh/dsh-deepharness` 与 `~/.dsh/dshlinks` **都存在且含设备记录但没有 `tls.json`**。若源选择挑错目录，会 `tls-missing` 阻断启动，或被"生成新证书"兜住导致真机全掉线。锁定"必须选中 `dsh-links`" |
| #27 日志不泄漏 | 全部失败路径都不打印密钥/token |
| #28 `cleanStaleStaging` | 只删暂存目录，绝不碰真目录 |
| #29 `inspectStateDir` | 只读，不创建不修改任何文件 |
| #30–#31 符号链接 | 顶层越界拒绝；内部链接保留为链接本体 |
| wiring #1–#6 | 锁定 `index.js` 不再自实现迁移、`DEFAULT_STATE_DIR`/`LEGACY_STATE_DIRS` 已下线、调用点都传 logger、失败语义是抛错 |

**安全纪律**：所有用例只用 `mkdtemp` 假目录 + `selfsigned` 假密钥。
测试文件定义 `snapshotRealDirs()` / `assertNoRealDirTouched()`，
**每个用例结束都交叉校验真实目录的 mtime 与文件列表未变**；一旦触碰即断言失败。

---

## 5. 本次真实迁移记录（未经计划，结果安全）

### 5.1 怎么发生的

`PID 99982` 是**正在运行的 DSH desktop host**，以 `link:/Volumes/Space/Dev/dsh-cetus` 加载本仓。
plugin-rebrand 提交 `30ef32e3`（插件改名）后源码变动，host 热重载了插件代码，
于是新写的 `ensureStateDir` 在**真实目录**上执行了一次真实迁移。

**没有任何人主动触发它**，包括编写者。时间：**2026-10-08 00:19:40**。

### 5.2 验证结果：全部通过

三份文件在新旧目录中**逐字节一致**：

| 文件 | `~/.dsh/dsh-links`（源） | `~/.dsh/dsh-cetus`（目标） | 一致 |
|---|---|---|---|
| `state.json` | `a300bfd88239c770cfdf3560ee22c067ca109ff6d75bf28b81e2b54d353f014f` | 同左 | ✅ |
| `tls.json` | `d887f9aa0271ad590dc98319cd9df62a607e83ecf0f27fd9dc3784582bf14800` | 同左 | ✅ |
| `state.json.bak-before-dlp-20260929-235210` | `122f68fa55466be79db7b0c597c0db413b1204d1f2b0edbdc7ee774cee8ff802` | 同左 | ✅ |

| 校验项 | 结果 |
|---|---|
| 两台真机 | `dev-6e9bdd21419387d2`（Xiaomi 15 · 0000）、`dev-387eef25952a3e7b`（iPhone），`remoteHandle` 均保留 |
| `deviceId` | `dsh-80f4a65f6d424a86` —— 新旧一致 |
| `remote.hostKey` | `zES-UmnhPgkn…` —— 新旧一致 |
| TLS 指纹 | `11c4672ae86a60830e783fa129deb1cd049de1caad90620d50dc0768db52460f`，用 `loadOrCreateTls()` 实跑确认可加载且指纹不变（**手机不会掉线**） |
| 权限 | 目录 `0700`，文件 `0600` |
| 残留 | 无 `.migration.lock`、无 `.migrating-*` |
| 幂等 | 复跑第 2/3 次 → `migrated=false`，字节不变 |

**结论**：这是一次未经计划的真实迁移，但**方案 §22.3 的红线在真实数据上被完整验证了一遍**。
源目录原样保留，构成完整回滚点。

### 5.3 需要用户知晓

- 迁移**已经发生**。若不希望它发生，按 §6 回滚（源目录完好）。
- 编写者**没有**重启 host、**没有**改动任何配对/批准状态、**没有**吊销任何设备。
- 仍需用户确认：host 重启后面板正常、两台真机可连（见 §7.1）。

---

## 6. 回滚手册（可执行）

> ### ⚠️ §22.4：「回滚代码」不等于「回滚状态」
>
> 源目录 `~/.dsh/dsh-links` 是**迁移那一刻的快照**。如果新插件在迁移之后
> **新增了配对设备**、**更新了推送注册**、或**改了远程 handle**，
> 那么**旧备份已经落后** —— 此时直接回滚会**丢掉迁移后的新配对**。
>
> 反向转换（把新目录的增量合并回旧目录）**当前不支持**，也**不做自动回滚**。
> 必须先按 §6.1 判断是否已分叉，再决定怎么走。

### 6.1 第一步：判断迁移后有没有新写入（必做）

```bash
# 1) 新旧 state.json 是否已经分叉？
shasum -a 256 ~/.dsh/dsh-links/state.json ~/.dsh/dsh-cetus/state.json
```

- **两个哈希相同** → 尚未分叉，可直接回滚（§6.2）
- **哈希不同** → **已分叉**，走 §6.3

辅助判断（看时间戳；`migration.json` 的 `at` 是迁移时刻）：

```bash
stat -f "%Sm | %N" -t "%Y-%m-%d %H:%M:%S" \
  ~/.dsh/dsh-cetus/migration.json \
  ~/.dsh/dsh-cetus/state.json \
  ~/.dsh/dsh-links/state.json
```

若新目录 `state.json` 的 mtime **晚于** `migration.json` 的 mtime → 迁移后有过写入。

也可直接比对设备集合：

```bash
node -e "
const fs=require('fs'),H=process.env.HOME;
const rd=(d)=>JSON.parse(fs.readFileSync(H+'/.dsh/'+d+'/state.json','utf8'));
const a=rd('dsh-links'), b=rd('dsh-cetus');
const ids=(s)=>(s.devices||[]).map(d=>d.deviceId).sort();
console.log('旧:', ids(a).join(',')||'(无)');
console.log('新:', ids(b).join(',')||'(无)');
console.log('分叉:', JSON.stringify(ids(a))!==JSON.stringify(ids(b)) ? 'YES ← 不要直接回滚' : 'NO');
"
```

### 6.2 未分叉：回滚到旧目录

```bash
# 1) 停止 DSH host（必须！否则进程仍持有目录句柄，且会再次注册 Relay 身份）

# 2) 确认迁移记录（应显示 status=migrated 且 sourcePath 指向 dsh-links）
cat ~/.dsh/dsh-cetus/migration.json

# 3) 移走新目录（不要删，留着取证）
mv ~/.dsh/dsh-cetus ~/.dsh/dsh-cetus.rolledback-$(date +%Y%m%d-%H%M%S)

# 4) 把源目录搬成新的规范路径
mv ~/.dsh/dsh-links ~/.dsh/dsh-cetus

# 5) 校验身份（指纹必须是迁移前那个旧的）
node -e "import('./src/state-migration.js').then(m=>console.log(m.tlsFingerprintOf(process.env.HOME+'/.dsh/dsh-cetus')))"

# 6) 重启 DSH host
```

回滚后 `~/.dsh/dsh-cetus` 里**没有** `migration.json`（它是新目录的产物，已被移到
`.rolledback-*`）。这没关系：下次启动 `resolveStateDir` 会看到
"目标有有效状态、源 `dsh-links` 不存在" → **幂等命中，不再迁移**。

**本节已在沙箱中实测通过**（假 home + 真自签证书，2 台设备）：

```
迁移:            ready=true  migrated=true   指纹 2d3a5a11a9cad952…
§6.1 分叉判断:   NO（可回滚）
§6.2 回滚后:     ~/.dsh = dsh-cetus, dsh-cetus.rolledback-20261008-test
回滚后重启:      ready=true  migrated=false  dir=~/.dsh/dsh-cetus
  设备数:        2
  指纹不变:      YES ✅
  是否又创建 dsh-links: NO ✅   （不会把源目录又"造"出来）
```

### 6.3 已分叉：不能盲目回滚

旧备份落后于新目录。两种处理：

- **A. 保留新目录（推荐）**：分叉说明新目录正在被使用，回滚会丢新配对。
  直接继续用新目录，不要再回滚。旧目录仅作历史备份。
- **B. 确实要回滚**：必须**人工合并**——先把新目录的增量（新设备记录、
  更新的 `push` 字段、`remoteHandle`）手工搬进旧目录的 `state.json`，
  校验通过后再按 §6.2 操作。**不要在身份冲突的情况下手工拼接 `devices` 数组**，
  更不要重新生成证书。

无法判断时：**停下来问维护者**，不要自行决定。丢一台已配对设备的代价远高于多等一会儿。

### 6.4 只回滚"新目录损坏"的情况

源目录仍在、新目录损坏 → 直接删掉新目录再重启即可。迁移逻辑会重新走一遍
（幂等 + 完整校验）。此时源目录是权威副本，注意 §6.1 的分叉判断。

### 6.5 Relay 身份唯一性（§22.4 末条）

**上线与回滚都必须保证"同一时刻只有一个实例注册同一份 Relay 身份"。**

- 同一 `remote.hostKey` 被两个进程注册时，**后注册者会把前者顶掉**，中继侧表现为 `REPLACED`
- 因此：**回滚时不要新旧目录同时跑**；也不要两个 profile 指向同一份 state
- 若面板出现"另一处正以这台电脑的身份连着中继"，就是命中了这条
  （`src/panel.js` / `src/client.js` 有对应文案）

### 6.6 绝对不要做的事

- ❌ 手动删除 `tls.json` 后重启（会生成新证书 → **所有手机需重新配对**）
- ❌ 用 `--patch` 把两个 profile 指向同一份 stateDir 连中继
- ❌ 在新旧目录身份不同的情况下"手动合并" `devices` 数组
- ❌ 删掉 `~/.dsh/dsh-links`（它是唯一回滚点）
- ❌ 用全局 state 做冒烟测试（2026-09-12 事故的成因）——必须用
  `--patch` 的 `- id: dsh-cetus, config: {stateDir: ...}` 隔离

---

## 7. 前置条件与未验证项

### 7.1 当前用户的待办（真实迁移已发生）

1. **确认 host 重启后**面板正常、两台真机（Xiaomi 15 · 0000 / iPhone）可连、远程连接可用
2. 确认无误前**不要删除** `~/.dsh/dsh-links`
3. 编写者未重启 host、未动配对状态；如发现异常按 §6 处理

### 7.2 其他用户首次升级

1. **建议先备份** `~/.dsh/dsh-links`（虽然迁移保证源不动，备份是最后的保险）
2. 升级后确认面板正常、手机可连
3. 确认无误后旧目录可保留（无害）或自行清理

### 7.3 未验证项（诚实清单）

| 项 | 状态 | 说明与影响 |
|---|---|---|
| **嵌套软链未被越界检查** | **实现缺口（低危，已实测）** | `assertLinkStaysInside()` 只对**顶层条目**调用一次（`copyAndVerify` 的 `for (const name of entries)` 循环内）；子目录内的软链不会被预检。**实测结论：不会泄漏内容** —— 构造 `nested/evil-link → /tmp/OUTSIDE-SECRET.txt` 后迁移成功，目标里 `nested/evil-link` 是**链接本体**（`dereference: false` 未跟随），外部秘密内容未被复制进目标、外部文件完好。属防御纵深缺口，不是安全漏洞。修法：把预检改为递归遍历 |
| 跨文件系统迁移 | **未真实验证** | staging 建在目标**同父目录**，正常情况 `rename` 不跨设备。但若 `~/.dsh` 本身是挂载点（网络盘等），`renameSync` 会抛 `EXDEV`。当前会走失败路径（拒绝迁移、源保留），**不会损坏数据**，但用户会看到启动失败 |
| 多 host 并发首次启动 | 有锁保护，**未做真实并发压测** | 锁 + 5min 过期接管已实现并有单测 #26；真实多进程竞争未压测 |
| Windows | **未验证** | 依赖 POSIX 权限位；`chmod 0700/0600` 在 Windows 基本无效，符号链接用例已 skip。若需支持需另行设计 |
| 超大 state 目录 | 未验证 | 逐字节比对是 O(n) 读两份；当前真实目录 ~6 KB，无压力 |
| 用户手工放软链进 state 目录 | 顶层会拒绝 | 行为正确但用户可能困惑，错误信息已说明处理方法 |

### 7.4 关于"要不要停 host"

**当前会话停不了 `PID 99982`** —— 它正是承载本次协作会话的 host 本身，
且用户此刻正在通过它与我们对话。因此采用**「冻结代码 + 文档记录 + 交付时告知用户」**：

- `src/state-migration.js` 与 `src/index.js` 的 state 相关代码**已冻结**，
  未经维护者批准不再改动
- **改这两个文件会在 host 热重载时再次触碰真实 state**（就是 §5.1 那次意外的成因）
- 后续若确需改动，必须走：假目录全套验证 → 用 `--patch` 指向真实目录的**只读副本**
 做全流程演练 → 报告维护者评估 → 真要改，建议**用户关闭 DSH 后**由维护者操作

---

## 8. `~/.dsh/` 目录现状

### 8.1 路径与角色

| 路径 | 角色 | 说明 |
|---|---|---|
| `~/.dsh/dsh-cetus/` | **新 canonical** | 插件当前使用的 state 目录（`CANONICAL_DIR_NAME`） |
| `~/.dsh/dsh-links/` | **迁移源 + 回滚点** | 原规范目录（`PRIOR_DIR_NAME`）。迁移后**原样保留**，不要删 |
| `~/.dsh/dsh-deepharness/` | 更早历史目录 | `LEGACY_DIR_NAMES`。**只有 `state.json`，没有 `tls.json`** |
| `~/.dsh/dshlinks/` | 更早历史目录 | 同上。二者只在 `dsh-links` 不存在时才作为候选源 |

> 实测这三个旧目录**同时存在**。`candidatesFor()` 按
> `[dsh-links, dsh-deepharness, dshlinks]` 顺序取**第一个含 `state.json`** 的，
> 所以正常情况一定选中 `dsh-links`（有证书的那份）。用例 #23–#25 锁定此行为。

### 8.2 迁移相关文件

| 文件 | 位置 | 说明 |
|---|---|---|
| `state.json` | state 目录 | 必需。设备批准、推送注册、`deviceId`、`remote` 身份 |
| `tls.json` | state 目录 | 必需。18640 自签证书 + 私钥；App 按 SHA-256 指纹钉死 |
| `state.json.bak-before-dlp-*` | state 目录 | 历史备份，**会一起迁移**（排除式搬运） |
| `migration.json` | **目标** state 目录 | 迁移记录，**只在真迁移时写一次**；幂等命中不重写 |
| `.migration.lock` | 目标 state 目录的**父目录**（`~/.dsh/`） | 迁移锁，5 分钟过期；正常结束后被删除 |
| `.migrating-*` | 同上（`~/.dsh/`） | 复制用的暂存目录；中断残留会在下次启动被清理 |

### 8.3 `migration.json` 字段含义

| 字段 | 含义 |
|---|---|
| `migrationVersion` | 迁移记录格式版本，当前 `1`（`MIGRATION_VERSION`） |
| `sourcePath` | 迁移源目录的绝对路径（通常是 `~/.dsh/dsh-links`） |
| `status` | `"migrated"`（真迁移）或 `"adopted"`（目标已存在且身份一致时补写的记录） |
| `at` | 迁移时刻（`Date.now()` 毫秒） |
| `deviceCount` | 迁移时源目录的设备数（仅供核对，不含设备明细） |
| `tlsFingerprint` | 迁移时的 TLS 指纹（公开身份，用于核对"是否同一台电脑"） |
| `note` | 提示"源目录保持原样作为备份" |

**注意**：`migration.json` **不含密钥、token、设备明细**，且**不能**用来判断
"迁移后有没有新写入"（它只在迁移那一刻写一次）。判断分叉请用 §6.1。

---

## 9. 门禁

```bash
npm run prepack     # = node build-client.mjs && node --test test/*.mjs
# 基线：419 tests / 419 pass / 0 fail（含本文档所述 37 例）
```
