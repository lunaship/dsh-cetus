# cetus 整体改造交付说明（2026-10-08）

## 完成状态

按《Cetus 整体改造方案（2026-10-07）》执行，**B 路线品牌迁移 + P00/P01 奠基包 + B8 日志收尾** 已完成；
**G1–G7 功能改造（C00–C16 + N03–N05 的非品牌部分）暂未开始**，已在交付前停下确认。

工作分支：`cetus/main`（基于 `origin/main`，已对齐）

---

## 交付物清单

### 源码（10 个 commit）

| 提交 | 内容 |
|---|---|
| `30ef32e3` | refactor(cetus): 插件规范名 dsh-links → dsh-cetus（注册名/补丁/build 元数据） |
| `d5cb2419` | feat(state): stateDir 迁移 dsh-links → dsh-cetus（校验 + 原子提升 + 冲突检测） |
| `3e6068ff` | fix(log): 统一 src/ 日志前缀为 dsh-cetus:，并让 mobile API 错误进宿主日志 |
| `ae49b6ec` | feat(android): 用户可见名改 cetus，保持安装身份不变 |
| `43186c60` | feat(ios): scheme/target 改名 Cetus，显示名 cetus，保 bundle 身份 |
| `37261a07` | feat(P00): 构建元数据注入 + 验收基线与 fixture 场景集 |
| `f3f079c8` | docs(P01): 消解 9 个设计冲突 + 建立 cetus 命名合同 |
| `d81912c5` | docs(B5/B7): 品牌文案收尾、e2e 脚本修复、state 迁移与回滚手册 |
| `5a07804d` | docs(cetus): 记录 e2e-arch-smoke 间歇失败的定性与取证过程 |
| `9bc1f422` | chore: 忽略 SwiftPM 本地状态（基础工作） |

### 文档

| 文件 | 内容 |
|---|---|
| `docs/REBRAND_CETUS.md` | 命名合同、legacy 兼容白名单、旧客户端范围、发布顺序、回滚限制、测试可读身份常量 |
| `docs/cetus/BASELINE.md` | 门禁基线、构建元数据方案、fixture 场景集、未验证项 |
| `docs/cetus/STATE-MIGRATION.md` | 迁移算法、失败语义、§22.5 矩阵对照、真实迁移记录、可执行回滚手册 |
| `docs/cetus/ENV-NOTES.md` | 本机环境性问题与对策 |
| `docs/cetus/DELIVERY.md` | 本文件 |
| `docs/cetus/evidence/` | 截图与构建元数据 |

### 待修（已知缺口）

| ID | 问题 | 任务 |
|---|---|---|
| B9 | 嵌套软链未被越界检查（防御纵深缺口） | task-10（待解冻） |
| B10 | Android locale 表用户可见文案漏改 | task-11（app-rebrand 在做） |
| G1–G7 | C00–C16 / C05–C15 功能改造 | 未开始 |

---

## 端到端验证矩阵

| 验证项 | 结果 | 证据 |
|---|---|---|
| 插件门禁 `npm run prepack` | **422/422 全绿** | 测试日志 |
| Android gradle 三件套 | **BUILD SUCCESSFUL**（876 测试） | `app-debug.apk` |
| iOS Debug + Release 构建（CI 精确命令） | **BUILD SUCCEEDED × 2** | `xcodebuild` 输出 |
| Relay gofmt/vet/build/test | **全过** | relay 测试日志 |
| e2e-arch-smoke 冒烟 | **35/35 × 3 次连跑** | 见 ENV-NOTES.md §"e2e-arch-smoke 间歇性失败" |
| Android 端到端（模拟器） | app 启动 + 显示名 cetus + 身份未变（dev.deeplinks.debug）+ 升级保留数据 | `evidence/final-android-verify.png` |

---

## 必须告知用户的几件事（红线提示）

### 1. 真实 state 目录迁移已执行（未经计划，结果安全）

`~/.dsh/dsh-links/` 已迁到 `~/.dsh/dsh-cetus/` —— 由运行中的 DSH host 在 2026-10-08 00:19
因插件热重载触发。Lead 逐字节验证为安全：
- 3 份文件（state.json / tls.json / state.json.bak-*）新旧 SHA-256 完全一致
- 权限 0700/0600 保持
- 2 台真机配对保留（Xiaomi 15、iPhone）
- TLS 指纹与 hostKey 不变
- **旧目录原样保留**为受保护备份 = 回滚点

回滚方案见 `docs/cetus/STATE-MIGRATION.md` §6。**重要**：若迁移后新插件已新增设备或更新
推送注册（目前未发生），旧备份已落后，不能直接回滚丢新配对，必须按 §6.3 处理。

### 2. Desktop DSH host 内存仍是旧配置

桌面 DSH host（PID 99982）现在仍以旧的 `dsh-links` 插件加载（**插件改名但 host 未重启**）。
改名**还没生效**到 host 行为里。

要看到改名效果需要**在合适窗口重启 host**（按 AGENTS.md 红线：重启前确认没有并行会话）。

### 3. 真机点击验收未完成（按方案 §1.2 / §1.3 已确认挂起）

iPhone 13 的完整中文输入、键盘交互、Live Activity 锁屏；Android 真机（Xiaomi 15）的视觉验收
**均未做**。本次只在模拟器与本机环境做了等价的验证。

### 4. 推送 / Live Activity / 真机远程 均未验证

- ActivityKit 启动路径（`Activity.request`）**零调用** — 这是方案 U18，已知缺口，归 P17
- APNs 真机推送未测（需维护者提供 APNs 环境）
- 远程连接（G4.1）仅源码层通过；未跑真中继端到端

### 5. 当前激活的 profile 软链

- `desktop` → `dsh-cetus -> /Volumes/Space/Dev/dsh-cetus` ✅
- `web` → `dsh-cetus -> /Volumes/Space/Dev/dsh-cetus` ✅（brand-cleanup 修过；原本悬空 dsh-links）
- `redesign-smoke` → 悬空 dsh-links（pre-existing，与本轮无关，待清理）
- 备份：`/tmp/web-profile-*.bak`、`/tmp/desktop-package.json.bak`、`/tmp/desktop-pnpm-lock.yaml.bak`

### 6. CI 修复已就绪

`ci-ios.yml` 在 `xcodegen generate` 之前生成 `BuildMetadata.xcconfig`（已干净树验证）。
Android 侧无需额外步骤。**建议维护者合入前在 GitHub Actions 跑一次确认。**

---

## 未完成的工作（按方案 §26 阶段排期）

| 阶段 | 工作包 | 状态 |
|---|---|---|
| G0 | P00/P01 奠基 | ✅ 完成 |
| G0 | B1–B7 品牌迁移 | ✅ 完成（task-11 收尾后） |
| G0 | B9 嵌套软链预检（防御纵深） | 任务已立，待解冻 src/state-migration.js |
| G1 | C01–C04 输入/草稿/发送/滚动 | 未开始 |
| G2 | C07–C09 首页/真实数据装配 | 未开始 |
| G3 | C05/C06/C10 原生体验/决策面板/设置 | 未开始 |
| G4 | C11 远程闭环（G4.1 已并入 main） | 部分在 main |
| G5 | C12/C13 推送与 ActivityKit | 未开始（U18 已知） |
| G6 | C14 两端一致性 | 未开始 |
| G7 | C16 发布口径 / 24h soak / 验收矩阵 | 未开始 |

**真实写操作验收**必须用 `ios-e2e-host.mjs` 假数据宿主 + 隔离 stateDir，**绝不碰用户真实配对**。

---

## 一次未经计划的真实迁移事件记录

| 项 | 值 |
|---|---|
| 时间 | 2026-10-08 00:19:40（host 热重载触发） |
| 触发 | `commit 30ef32e3` 含 `src/index.js` 改名 → DSH desktop host 热重载插件 → `ensureStateDir` 触发 |
| 触发原因 | Lead 没意识到 host 仍在运行（PID 99982 即承载本会话的 host） |
| 数据状态 | 验证为安全（逐字节 SHA-256 对比，2 设备保留） |
| 后续冻结 | 已冻结 `src/state-migration.js` 与 `src/index.js` 的 state 相关代码，不再迭代 |

教训：插件源码若以 `link:` 方式被运行中 host 加载，提交 = 部署。改 source = 真实执行。
**任何后续对这两个文件的修改，必须先在假目录演练，并请求 Lead 解冻。**
