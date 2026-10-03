# deeplinks-ios — AI 协作规则

DeepLinks iOS 客户端（`apps/ios/`）。配对插件源码在仓库根目录 `src/`；版本基线以 `../../docs/COMPATIBILITY.md` 为准。执行方案：`../../docs/ios/PLAN.md`。

连接方式：局域网直连与 DLP/1 远程（`Packages/DLCore`）。两条路都钉扎同一张插件证书。中继转来的拒绝码只能提示，删除或改写本机凭据只认插件在内层 TLS 上的答复（RFC 0001 §7.4）。

云端 agent 在 Linux 上**没有 Xcode**：不得声称「本地已通过」；以 `ci-ios.yml`（macOS）结果为唯一门禁（PLAN 执行规则第 11 条）。

## 目录契约

```text
apps/ios/
  project.yml                 # XcodeGen
  App/                        # 壳：导航、生命周期、Debug/Demo 入口
    Features/{Pairing,Home,NewTask,Chat,Sheets,Changes,Settings}/
  Packages/
    DLCore/                   # 无 UI：HTTP、SSE、配对、选路、加密、快照
    DLUI/                     # 纯 UI：Theme、Components；不依赖 DLCore 网络层
  Extensions/{NotificationService,LiveActivity,Share}/
  docs/{visual-rules-ios.md,page-mapping.md,design/}
  Screenshots/                # 仅 CI 生成的基线
  testdata/                   # 与 Android / 插件共享的向量（或指向仓库根 testdata/）
```

## 依赖方向

- `App` → `DLUI` + `DLCore`
- `DLUI` **不得** import 网络 / Keychain / HostClient
- `DLCore` **不得** import SwiftUI 视图（可有极少 Foundation-only 模型）
- 扩展只依赖各自需要的最小接口；NSE / Share **不联网**、不读设备 token
- 新封装组件先改 `docs/visual-rules-ios.md`，再写代码

## 红线

沿用仓库 `AGENTS.md`：隔离 `stateDir` 联调；不吊销真机；不改线上 `relay.dshlinks.com`；手机 API 无高危吊销 / 工作区批准。

iOS 追加：

- 凭据只存 Keychain：`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`，`kSecAttrSynchronizable = false`
- 不开 `NSAllowsArbitraryLoads`；只允许 `NSAllowsLocalNetworking`；主机连接必须证书指纹校验
- 中继错误码只提示，不据此删凭据
- NSE / Share 扩展不联网、不读设备 token
- WKWebView 渲染不可信内容：非持久存储、禁文件、CSP 只放行随包资源；预览页只放行本机回环代理
- 网络图片默认不自动加载
- 不引入第三方统计 / 崩溃 SDK；崩溃只用 MetricKit，本机查看、用户导出
- **不复制** lody-ios 或其他 AGPL / GPL 源码；新依赖在 PR 写明许可证
- 不改版本号、不打 tag、不处理 Apple 账号 / 证书 / `.p8`（维护者事项）

## 设计稿用法（I1.5a 第 4 条）

- PNG（`docs/design/`）**只定布局与层级**
- 实现一律用系统组件、SF Symbols、动态字体与 PLAN / `visual-rules-ios.md` 色板 token
- **禁止从 PNG 取色或按像素复刻**
- PNG 中的玻璃是近似；真实效果以 I1.5b 模拟器截图为准
- PLAN 与设计稿冲突时以 PLAN 为准（例如 1.2 / 1.3 去掉「输入配对码」）；设计稿需要的数据插件没有 → 删掉该元素并在 PR 写明

## UI 改动规则

- 任何 UI 改动必须对应 `docs/redesign-v4/design-v4.html` / `docs/page-mapping.md` 的页面编号
- 未在对照表中的页面不做；平台差异写进 PLAN 第 11 节
- 视觉取值遵守 `docs/visual-rules-ios.md`（≤120 行）

## 门禁命令

在有 Xcode 的 macOS / CI 上：

- 生成工程：`xcodegen generate`
- 构建：`xcodebuild -scheme DeepLinks -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build`
- 单测：`xcodebuild … test`
- 截图校验：由 `ci-ios.yml` / `ios-screenshot.yml` 跑
- 合同 / 向量：与仓库根 `testdata/` 对齐的包内测试

Linux 云端 agent：只改文档或非编译文件时，以仓库现有 Node / Go gates 为准；iOS 编译结果以 CI 为准。

## 截图基线

- **只允许**由 `.github/workflows/ios-regen-screenshots.yml` 生成；禁止提交本地基线
- UI 改动影响截图：先触发该 workflow，再把 reference 提交进 PR
- PR 描述附「新基线 ↔ 页面编号」对照；命名见 `docs/page-mapping.md`

## 深入文档

| 主题 | 文件 |
|---|---|
| iOS 执行方案 | `../../docs/ios/PLAN.md` |
| 视觉规则 | `docs/visual-rules-ios.md` |
| 页面对照 | `docs/page-mapping.md` |
| 设计稿 | `docs/design/README.md` |
| 推送网关 | `../../docs/rfc/0002-push-gateway.md` |
| 手机同步契约 | `../../docs/MOBILE_SYNC_CONTRACT.md` |
| 兼容矩阵 | `../../docs/COMPATIBILITY.md` |
| DLP/1 | `../../docs/rfc/0001-dlp1-remote-pipe.md` |
| 全仓架构 | `../../docs/ARCHITECTURE.md` |
