# cetus 整体改造进度与交付证据

更新：2026-10-08（第二轮）。执行依据为用户桌面的《Cetus 整体改造方案（2026-10-07）》。
用户已授权继续全部实施，并接受模拟器验证。本文只写**有证据**的部分，不写推断。

## 当前状态（一句话）

`cetus/main` 顶端 `a91562ae`：**三条 CI 全绿**，工作区干净，**已产出可安装的正式签名 Android 包**。
方案剩余项与四类"本机无法验证"的事项在文末逐条列出。

## 最新核实的 CI（本轮亲自复跑）

HEAD `a91562ae`：

| 检查 | 结果 | 说明 |
|---|---|---|
| CI（插件 + Android） | **success** | — |
| CI iOS | **success** | 含单测、截图比对、build |
| CI iOS e2e | **success** | — |

本地补充门禁：插件 `npm test` **424/424**；Android
`assembleDebug + testDebugUnitTest + lintDebug` BUILD SUCCESSFUL；改动文件
`swift-format lint --strict` 零告警。

## 本轮修复的真实缺陷

这些都是"编译通过"掩盖不住、且此前被错误归因的问题：

| # | 缺陷 | 证据与影响 |
|---|---|---|
| 1 | `project.yml` 的 `configs:` 块插错位置，`GENERATE_INFOPLIST_FILE`/`INFOPLIST_FILE`/`SKIP_INSTALL` 掉进 `configs` 与 `Debug`/`Release` 同级 | `xcodegen generate` 直接失败（"must be mapping format"）。**四个 target 全中招**，iOS 工程无法生成 |
| 2 | C08 的 DLCore 半边从未落到主树（`DiffNote`、`DiffLine.oldLineNumber`、`diffNotes`） | 只在某 worktree 内存在；主树引用后**整棵树编译不过** |
| 3 | `PushPayloadReader.openRequest` 不校验 `deviceId` | 空 deviceId 也产出路由请求，与 `sessionId` 的校验不对称。修的是**生产代码**，不是改测试迁就 |
| 4 | 定位服务器校验错误会**指错题** | 题目 id 常是短串（`a`/`b`），裸 `contains` 让 `"missing_answer"` 里的 `a` 命中题目 a。改为整词匹配 |
| 5 | 诊断导出会把凭据原样写进剪贴板 | `diagnosticsClipboard` 直通 `.text` 明细。改为**形状白名单 + 敏感形状黑名单**双层（`ghp_`/`sk-`/IPv4/路径/长十六进制） |
| 6 | 截图套件容差不一致：`ChatSheetSnapshotTests` 及另外**五套**用裸 `.image`（逐像素全等），其它套件用 `precision: 0.995` | accessibility3 大字号下**不可复现**：CI 自己生成的基线，下次 CI 又判不匹配 → 无限"重生成→仍失败"。这是连续多轮 CI 红的真正根因。**六套已全部统一**，该类抖动应就此消除 |
| 7 | 关于页截图渲染真实构建 commit/日期 | 基线每次提交、每天漂移。改为 `BuildInfo` 可注入，截图固定取值（Android 侧同一处理） |

本轮（C10）另修 4 项，详见 `docs/cetus/C10-GAP.md`：
设置页在生产上**根本没有注入账号服务**（7.2 电脑 / 7.3 诊断整块不可用）、
诊断查询失败时会在「不可用」提示旁列出**伪造的 OK/WARN 结论**、
电脑地址用内部 `hostID` 冒充（用户看到 `host-1a2b`）、
外观偏好**只写不读**（选深色毫无反应）。

另有两个**误报**经复核后关闭，记录以免后人重复排查：
- "APNs payload 缺 `deviceId`，NSE 无法解密"：NSE 实际以
  `deviceID(in:bindings:)` 按 `kid` 查 Keychain 绑定恢复，`PushPayloadChainTests` 用真实
  payload 形状覆盖。**无需改网关或 RFC**。
- "Live Activity 没有 ActivityKit 调用链 / content-state 含 title / timerInterval 永远递增"：
  三条均已在树上满足（真实 `Activity.request`/`update`/重连；content-state 恰好只有
  RFC §5.6 五字段；已改 `context.isStale` + "已离线 · 最后更新"）。原判断是字面 grep 漏了
  `Activity<T>.request` 形式。

## 可安装交付物（本轮产出）

| 项 | 值 |
|---|---|
| 路径 | `.local/release-builds/0.5.0-beta.31-a91562ae/cetus-android-0.5.0-beta.31-a91562ae.apk` |
| 源提交 | `a91562ae`（干净 worktree，`git status` 与 main 一致） |
| 与当前 main 的关系 | `apps/android/` 自 `a91562ae` 起**无改动**（已 `git diff --stat a91562ae..origin/main -- apps/android/` 核验为空），故该包仍对应当前 main 的 Android 源码 |
| applicationId | `dev.deeplinks`（**保持安装身份，可覆盖升级**） |
| versionName / versionCode | `0.5.0-beta.31` / `39` |
| 显示名 | `cetus`（全部 locale） |
| 签名证书 SHA-256 | `38f71adf8b67d81042c99a3ec0dfdafb4303dd31e3fc491068ccd534cb482a47`（与既有发布证书一致） |
| APK SHA-256 | `7e9713105e2658511372ef3d76d0b439b69b536146de38d118f9299f7c039779` |
| 混淆映射 | 同目录 `mapping.txt`（80 MB）+ `SHA256SUMS` |

产物遵守方案 §21.1 命名（`cetus-android-<version>-<shortSHA>.apk`），同时保留 AGP 默认
`app-release.apk` 以不破坏既有 CI/RELEASING 取件。

**未验证（重要）**：本机 Android 模拟器（AVD `cetus_test`）能启动进程但**不开启任何端口**
（5554 控制台与 5555 adb 均无监听），ADB 始终看不到设备。这是本会话的沙箱限制，
不是模拟器或产品缺陷。因此**"装到手机/模拟器上跑起来"这一条没有做成功**，
只有静态证据（签名校验、badging、启动 activity `dev.deeplinks.devices.SplashActivity`、
权限清单符合预期）。真机安装与运行仍待维护者执行。

## 功能验收矩阵

以方案"工作包/验收"口径列出**已由测试覆盖**与**仍未验证**。截图测试只作回归辅助，
不算验收证据。

| 项 | 已落地并有测试 | 仍未验证 |
|---|---|---|
| C01 输入 | 编辑器只创建一次、marked text 保护 | 真实中文输入法组词、语音听写、外接键盘 |
| C02 草稿 | 会话级键、加密落盘、生产注入 | 真机杀进程后恢复、附件失效恢复 |
| C03 发送 | 提交状态机、失败保留输入、忙碌锁 | 真机弱网/延迟竞争 |
| C04 阅读 | 锚点补偿、暂停跟滚、分页入口 | 千条长会话性能基线 |
| C05 输入栏 | 单一玻璃容器、按状态 placeholder | 真实键盘弹收位移 |
| C06 决策 | 未知题型不静默提交、校验错误定位到题、命令块可滚动、位置与提示 | 另一设备先处理时的真机竞争 |
| C07 首页 | 取消搜索回位、未知状态不默认完成、工作区标题去重 | — |
| C08 改动 | diff 符号列/双列行号（规则对齐 Android）、说明行、差异段导航 | — |
| C09 文件/预览 | 上限按插件协商能力、受保护分享副本、预览经钉扎 TLS 转发 | 真机真主机预览手动验收 |
| C10 设置 | 生产装配账号服务（电脑信息/重命名/解绑/诊断可用）、移除样例诊断、显示真实地址与选路、外观在根层生效 | 语言切换覆盖、通知三维度、诊断时间戳、外观在 sheet/WebView 的真机效果 |
| C12 推送 | token 生命周期、APNs 环境按 profile 解析、payload 链单测 | **真实 APNs**（无付费账号）、真机锁屏 |
| C12 Live Activity | 真实 ActivityKit 调用链、字段合 RFC、离线显示最后更新 | **真实锁屏/灵动岛外观** |
| C13 分享 | 原子消费、合并不覆盖、security-scoped、双 scheme、隐私遮罩可解除 | — |
| C14 跨端一致 | diff 行号推进规则与 Android 逐条对齐并有 fixture；删除/归档后清理返回栈与当前目标；离线空态按原因区分（配对失效 vs 连不上）；模型/权限切换失败不更新展示值 | 九个维度的逐项两端对照、时钟偏差、横屏/键盘/VoiceOver、真机性能场景 |
| C15/C16 | GAP 文档已出；诊断导出脱敏；Debug/Release bundle id 分离 | 部分项按 GAP 文档 |

## 截图基线

基线**只能**由 `.github/workflows/ios-regen-screenshots.yml` 生成。已知限制（本轮实测）：
record 模式下一次**不一定覆盖所有变体**（record 会让每个快照"失败"，跑批提前结束），
因此可能需要多次触发才能收敛。已在 `apps/ios/docs/page-mapping.md` 写明。

## state 迁移与运行宿主

历史迁移事件：2026-10-08 00:19，运行中的 linked 插件触发热迁移。已逐字节校验旧、新目录
SHA-256，配对设备、TLS 与 hostKey 保留，旧目录继续作为回滚来源。算法、失败语义与
**可执行的回滚手册**见 `docs/cetus/STATE-MIGRATION.md`。

后续任何冒烟继续隔离 `stateDir`，不吊销真实设备，不重启用户 host。

## 验收矩阵

方案 §28 的 T01–T46 逐条状态见 **`docs/cetus/ACCEPTANCE-T01-T46.md`**（§32 要求的交付物）。

统计：**已验 27 / 部分 13 / 未验证 6**。**没有任何一条标为"真机通过"** ——
本环境 Android 模拟器无法开启端口、iOS 只能跑模拟器，真机项一律保留为未验证。
该文档末尾逐条对照了 §32 的完成定义，并明确指出**整体改造尚未完成**及其原因。

## 结论

Android 侧已具备**可覆盖升级的正式签名包**与完整构建证据；iOS 侧三条 CI 全绿。
方案整体**尚未完成**：上表"仍未验证"列的项目需要真机、真实 APNs/Apple 账号或长时性能
基线，本地与 CI 都无法替代。最终交付仍需维护者在一台真机上安装并走一遍生产入口。
