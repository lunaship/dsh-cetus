# cetus 整体改造进度与交付证据

更新：2026-10-08。本文记录进度，不代表最终验收完成。执行依据为用户桌面的《Cetus 整体改造方案（2026-10-07）》；用户已授权继续全部实施，并接受模拟器验证。

## 当前状态

- 工作分支 `cetus/main`，提交推送至 `origin/main`；保留既有提交历史。
- B 路线插件、App 显示名和 stateDir 迁移已实施。用户照做的 Android 后台设置文档仍需补查。
- C01–C08 已有代码与提交，但尚未逐项满足方案验收，不能称为 G1–G3 完成。
- C09 文件、预览、分享生产路径正在实施。
- C10–C16、远程完整闭环、推送/ActivityKit、两端一致性和最终安装包验收仍需完成。当前没有可据此宣称“整套完成”的证据。

## 最新核实的 CI

HEAD `410e7d7391f480bde5b529517cdf34517514ba80`：

| 检查 | 结果 | 证据 |
|---|---|---|
| CI（插件/Android） | success | GitHub Actions run 37738586154 |
| CI iOS | failure | run 37738586185 |
| CI iOS e2e | failure | run 37738586097 |

CI iOS 的单测与截图检查在测试编译阶段失败：`PhoneDecisionTests.swift` 引用了不存在的 `RequestStatus.rejected`。合同中的拒绝 UI 状态为 `resolved`。修复正在工作树中，需由后续实际测试验证。

本地 Xcode 27.1 / XcodeGen 2.45.4 的测试目标失败还显示 Cetus 模块缺少 `-enable-testing`。有效 Debug 设置曾是 `ENABLE_TESTABILITY = NO`。工程配置现已明确 Debug 测试、优化与 DEBUG 编译条件。2026-10-08 本地 iPhone 17 Pro（iOS 26.5）实际执行文件导出、生产 ConversationModel 下载、启动清理、HostClient 合同和决策状态共 22 个测试通过，xcodebuild 退出码 0。日志：`/tmp/cetus-c09-targeted-tests-final.log`。针对修改文件的 swift-format strict lint 和 git diff --check 通过；后续 CI 仍需独立检查。

## 截图基线结论

已有七轮 CI 生成截图的历史提交。从变化文件数量减少，不能推导截图已收敛，也不能据此证明字体或 runner 是根因。关于页的构建元数据随提交变化，以及 wide 布局/状态，都需要分别调查。

此前关于“只需再生成一两轮”“只是 runner/font 微差”“不影响代码质量”的判断没有足够证据，撤回。当前测试编译失败先于截图比对，必须先修编译。

基线仅由指定 CI workflow 生成。使用 `GITHUB_TOKEN` 的自动 push 通常不会递归触发其他 workflow；paths 过滤器本身不能改变这个行为。后续需要显式运行检查，不能等待 bot 提交自动触发。

## 功能验收尚缺

| 项目 | 已有工作 | 仍需补齐或证明 |
|---|---|---|
| C01 输入 | 保留 UIKit 输入实例、marked text 保护 | 生产组词/键盘与焦点场景 |
| C02 草稿 | 会话键、AES-GCM、Keychain、环境注入 | 合并保存接入、附件恢复、离开/后台 flush、旧草稿恢复入口 |
| C03 发送 | 提交状态与修订判断 | 生产 busy 绑定、超时与并发编辑测试 |
| C04 阅读 | tail 状态、新消息提示、分页入口 | anchor 装配、初始跟随、分页 latch 和人工重试 |
| C05 原生输入 | placeholder overlay | 非空 placeholder 可见性与真实 UI 验证 |
| C06 决策 | 已处理显示及状态归并 | 普通草稿隔离、自由回答、竞争后终态可见性 |
| C07 首页 | 工作区分组、展开持久化、unknown 状态 | 普通行时间/状态/预览与方案对应 |
| C08 改动 | 摘要和 diff HTTP 服务 | 轮次缓存隔离、取消/错误重试、菜单装配、段落导航与引用/分享 |
| C09 文件/预览 | 树/下载接现有 API，SHA-256 校验、大小限制、受保护分享副本、引用入草稿、打开前批准刷新 | 目录返回位置、完整能力区分、WebSocket/跳转/断网/过期/关闭恢复及真实入口验收 |

现有 PreviewLocalProxy 的 WebSocket 路径使用默认本地帧回显，没有连接远端服务；不能将相关 echo 测试记为生产 WebSocket 通过。该路径需要真正代理与双向生命周期测试。

静态 fixture 截图可以辅助回归测试，不是最终交付证据。App build 也不代表测试目标已编译或功能验收通过。

## state 迁移与运行宿主

历史迁移事件：2026-10-08 00:19，运行中的 linked 插件触发迁移。此前逐字节校验了旧、新目录三份文件的 SHA-256，配对设备、TLS 和 hostKey 保留，旧目录继续作为回滚来源。事件详情和回滚限制见 `docs/cetus/STATE-MIGRATION.md`。

任何后续冒烟继续使用隔离 stateDir，不吊销真实设备。`src/state-migration.js` 的嵌套软链预检仍待处理；运行 host 的加载状态和并行会话未重新核实，不重启用户 host。

## 历史验证范围

早期品牌改造曾记录插件 422 测试通过、Android 构建/单测/lint 通过及 iOS App 构建通过。这些是对应历史版本的结果，不是当前工作树或最终版本的验收。

最终需交付可安装且可使用的包、精确 commit/build 元数据、生产入口操作证据和完整验收矩阵。整体目标保持进行中。
