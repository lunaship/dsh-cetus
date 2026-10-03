# dsh-links — AI 协作规则

DSH 手机插件（本仓插件源码）：局域网配对 + 设备管理 + 18640 接入代理 + 手机 API + 远程连接（DLP/1 Agent，`src/remote/`）。中继服务端（`dlp-relay`）在 `relay/`；Android 源码在 `apps/android/`，随本仓一起发版。旧版 DLR/1（接入码 / Control / 云端二维码）已整体删除，不要恢复。

## 红线

- 插件 state 默认全局共享（`~/.dsh/dsh-links/state.json`，不分 profile）。任何冒烟/联调必须用 `stateDir` 配置隔离（经 `--patch` 的 `- id: dsh-links, config: {stateDir: ...}` 覆盖），且不得调用设备吊销类操作——2026-09-12 曾因用全局 state 冒烟，teardown 吊销了用户两台真机的配对。
- 用户的 DSH host 若以 `link:` 方式加载本仓，改完源码必须重启 host 才生效；重启前确认没有并行会话正在该 host 上工作。当前用户的 desktop profile 以 `github:lunaship/dsh-links` 安装，只有推到 main 并在 DSH 里重装后才生效。
- 远程联调同样要隔离 `stateDir`，且不要用另一个 profile 的同一份 state 连中继：同一主机密钥后注册者会把前者顶掉（`REPLACED`）。官方中继 `relay.dshlinks.com` 由维护者运营，改动线上中继前先问。

## 门禁命令

- 插件：`npm run prepack`（= build-client + 全量测试）；单跑测试 `npm test`。
- 中继（在 `relay/` 目录下）：`gofmt -l . && go vet ./... && go build ./... && go test ./... -race`。
- 真中继端到端（需要 Go，不进 `npm test`）：`npm run test:dlp1-e2e`。
- Android（在 `apps/android/` 目录下）：`cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。

## UI 改动规则

任何 UI 改动必须对应 `docs/redesign-v4/design-v4.html` 的页面编号。未在设计稿里的 UI 不做。

## 深入文档

| 主题 | 文件 |
|---|---|
| 架构总览（分层 / 目录契约 / 数据流，新人入口） | `docs/ARCHITECTURE.md` |
| 手机同步契约（字段级，改手机 API 必读） | `docs/MOBILE_SYNC_CONTRACT.md` |
| 兼容矩阵 / DSH 基线 / 冒烟隔离警告 | `docs/COMPATIBILITY.md` |
| 远程连接协议（DLP/1，逐字节合同） | `docs/rfc/0001-dlp1-remote-pipe.md` |
| 中继自建与配置 | `relay/README.md` |
| 发布核对清单（App APK / 仓库分发） | `RELEASING.md` |
| Android 协作规则 | `apps/android/AGENTS.md` |
| Android 视觉规则（间距、形状、排版、尺寸、强调色） | `apps/android/docs/visual-rules.md` |
| RC1 内测计划与证据模板 | `docs/RC1_CLOSED_BETA_TEST_PLAN.md` |
| 专注 DSH 五期执行方案（领取 PR 前必读） | `docs/proposals/002-dsh-focus-roadmap.md` |
| Android 重设计 v4（设计稿、视觉规则、执行方案） | `docs/redesign-v4/PLAN.md` |
