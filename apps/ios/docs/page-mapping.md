# iOS 页面对照表（I1.2）

> v4 页面编号 → iOS 实现 → 与 Android 的差异 → 截图测试名。
> 以 `docs/ios/PLAN.md` 为准；与 `docs/design/` PNG 冲突时以 PLAN 为准，见文末「冲突清单」。

截图测试命名约定：`Snapshot_<pageId>_<scene>_<appearance>_<locale>`，例如 `Snapshot_2_1_inbox_light_zh`。基线只由 `ios-regen-screenshots.yml` 生成。

## 1.x 启动与配对

| v4 | 页面 | iOS 页面 / 组件 / API | 与 Android 差异 | 截图测试 |
|---|---|---|---|---|
| 1.1 | 启动 | 系统 Launch Screen；`Info.plist` 的 `UILaunchScreen` → `LaunchBackground` / `LaunchBrand`（浅 / 深色资源） | — | （系统屏，无 App 截图） |
| 1.2 | 欢迎 / 未配对 | `Features/Pairing/PairingWelcomePage` 全屏；主按钮「扫码配对」`.glassProminent` + BrandFill；次按钮「从相册识别」`PhotosPicker`→Vision；文字「先看看演示」 | 多演示入口 | `Snapshot_1_2_welcome_*` |
| 1.3 | 扫码 | `Features/Pairing/QRScanner` / `DataScannerViewController`（仅二维码）；玻璃关闭按钮；不支持时退回 `AVCaptureSession` | — | `Snapshot_1_3_scan_*` |
| 1.4 | 输入配对码 | **不做** | 同 Android / #64 | — |
| 1.5 | 等电脑批准 | `PairingPendingPage` push；显示本机名与连接方式；可取消（删本机记录）；轮询 `/mobile/sessions` | — | `Snapshot_1_5_pending_*` |
| 1.6 | 配对失败 | `PairingFailurePage` push；标题 + 原因 + 三条建议 +「返回」 | — | `Snapshot_1_6_fail_*` |
| — | 本地网络说明 | `LocalNetworkExplanationPage`；首次连局域网前；一句话 +「继续」 | Android 无对应页 | `Snapshot_1_lan_explain_*` |

I4.1 截图登记：`Tests/PairingSnapshotTests.swift`，由 `ci-ios.yml` 与 `ios-regen-screenshots.yml` 的原有 `-skip-testing` 范围自动包含。文件名 `Snapshot_<pageId>_<scene>_<light|dark>_<zh|en>.<variant>.png`；variant 为 `default` 或 `large`（accessibility3）。五个主页面各为浅 / 深 × 中 / 英 × 默认 / 大字号（accessibility3）的 8 张。其他状态只保留浅色、中文、默认字号 1 张；sameName 与 rename 各补 1 张英文。`PairingSnapshotTests` 共 61 张，不含原有欢迎基线；iPad / 宽屏留到 I4.8。1.3 相机在两种外观下都使用深色系统色；只截图离线相机表面，不启动摄像头。

I4.2 截图登记：`Tests/InboxSnapshotTests.swift`，同样由原有 `-skip-testing` 范围自动包含，不在本地生成基线。五个主状态 `2_1_inbox`、`2_2_empty`、`2_3_offline`、`2_4_search`、`2_6_context` 各 8 张（浅 / 深 × 中 / 英 × 默认 / accessibility3）。其余各 1 张浅色中文默认字号：`2_4_suggestions`、`2_4_empty`、`2_4_degraded`、`2_2_workspace`、`2_5_menu`、`2_5_archived`、`2_6_delete`、`2_6_rename`、`2_1_loading`、`2_1_filtered`。合计 50 张。2.5 菜单、2.4 最近搜索、2.6 长按 / 删除确认 / 重命名用同文案的静态列表；生产路径仍是 `toolbarTitleMenu`、`searchSuggestions`、`contextMenu`、`confirmationDialog` 和 `alert`。不生成降低透明度 fixture（与 I4.1 同一 SDK 限制）。左滑无法在静态截图里展开。

I4.3a 截图登记：`Tests/ChatSnapshotTests.swift`，由原有 `-skip-testing` 范围自动包含，不在本地生成基线。三个主状态 `4_1_running`、`4_2_tail`、`4_6_process` 各 8 张（浅 / 深 × 中 / 英 × 默认 / accessibility3）。其余各 1 张浅色中文默认字号：`4_1_unconfirmed`、`4_1_image`。合计 26 张。截图走 `staticSnapshot`：不挂 `.task`、不调用 `start()`、不连网、不创建 `WKWebView`、不弹系统 alert / confirmationDialog / sheet。公式和 Mermaid 在截图里显示源码，生产路径才加载锁死 WebView。4.3 / 4.4 / 4.5 / 4.7 / 4.9 不属于本项。不生成降低透明度 fixture。

I4.3b 截图登记：`Tests/StatusSlotSnapshotTests.swift`，由 `ci-ios.yml` 与 `ios-regen-screenshots.yml` 的现有 `-skip-testing` 范围自动包含。五个主状态 `4_5_status_collapsed`、`4_5_status_expanded`、`4_8_status_disconnected`、`4_5_status_pending`、`4_5_status_preview` 各 8 张（浅 / 深 × 中 / 英 × 默认 / accessibility3）。其余 `4_8_status_connecting`、`4_8_status_failed`、`4_5_status_planOnly`、`4_5_status_noPlan`、`4_5_status_completed`、`4_5_status_empty` 各 1 张浅色中文默认字号，合计 46 张。均使用 `App/Demo/StatusSlotScenes` 与 `ConversationPage(staticSnapshot: true)`，不 `.task`、不连网、不创建 WebView、不弹系统 alert / sheet。展开完成项灰字无删除线；没有计划时删去进度与清单。I4.3a 的 `ChatSnapshotTests` 显式 `showsStatusSlot: false`，保持 4.1 / 4.2 / 4.6 原有 fixture 内容与滚动边缘设置。不生成降低透明度 fixture；新基线仍只由 CI 生成。

iOS 26.5 SDK 的 `accessibilityReduceTransparency` 只读，UIKit 无对应可写 trait，现有 DLUI 也没有能覆盖系统玻璃样式的注入点；移除五张降低透明度 fixture，保留实际系统设置验收。

`1_2_sameName` 直接截图测试专用静态冲突内容（复用生产 alert 的本地化标题、说明、替换 / 改名 / 取消选项）；`1_2_rename` 在独立 `NavigationStack` 中截图与生产 sheet 共用的 `PairingRenameForm`（原名称、说明、取消与禁用的配对按钮），并传入 `allowsFocus: false`，字段不会成为第一响应者，也不绘制光标。两者都不在真实窗口里呈现系统 alert / sheet，scene 名与各两张中英基线的登记保持不变；生产弹窗与可聚焦输入保持不变。其他 scene 的渲染代码不变。

| 页面 | scene | 张数 |
|---|---|---|
| 1.1 | 系统启动屏，无 App 截图 | — |
| 1.2 | `1_2_welcome` | 8 |
| 1.2 状态 | `1_2_submitting`、`1_2_photoReading` | 各 1 |
| 1.2 同名 / 改名 | `1_2_sameName`、`1_2_rename` | 各 2（中 / 英） |
| 本地网络说明 | `1_lan_explain` | 8 |
| 1.3 | `1_3_scan`（离线相机表面） | 8 |
| 1.3 无效码 | `1_3_scan_invalid` | 1 |
| 1.5 | `1_5_pending` | 8 |
| 1.5 状态 | `1_5_pending_tailscale`、`1_5_pending_cleanupFailed` | 各 1 |
| 1.6 代表原因 | `1_6_fail_network` | 8 |
| 1.6 其他原因 | `1_6_fail_expired`、`1_6_fail_certificate`、`1_6_fail_missingPin`、`1_6_fail_rejected`、`1_6_fail_cameraDenied`、`1_6_fail_cameraUnavailable`、`1_6_fail_invalidQR`、`1_6_fail_noPhotoQR`、`1_6_fail_rateLimit`、`1_6_fail_unsupported`、`1_6_fail_hostHint`、`1_6_fail_storage` | 各 1，共 12 |
| 原有 1.2 基线 | `WelcomeSnapshotTests/testWelcomeLight.light.png`。截图用 `WelcomeView(staticSnapshot:)`：不跑配对 `.task`、关闭动画、主按钮用 `.borderedProminent` 而不是玻璃；由 CI 重生成 | 1（不计入新增 61 张） |

1.1 使用 `UILaunchScreen` 字典中的 `UIColorName = LaunchBackground` 与 `UIImageName = LaunchBrand`，Assets.xcassets 提供浅 / 深色背景和矢量品牌字标；不使用 storyboard。I4.1 配对成功交接 hostId。I4.2 起 Root 持有 hostId 时进入首页；缺凭据时回到欢迎页。

## 2.x 首页收件箱

| v4 | 页面 | iOS 页面 / 组件 / API | 与 Android 差异 | 截图测试 |
|---|---|---|---|---|
| 2.1 | 首页 | `NavigationStack` + 大标题；`.navigationSubtitle` 电脑状态；`toolbarTitleMenu`（2.5）；plain `List` 三组；`DLInboxRow`；底栏筛选 / 搜索 /「新任务」（唯一 BrandFill）；`.refreshable` | 无 FAB；搜索在底栏；「允许一次」为 `.bordered`+tint | `Snapshot_2_1_inbox_*`、`Snapshot_2_1_loading`、`Snapshot_2_1_filtered` |
| 2.2 | 空态 | `DLEmptyState` +「从一件事开始」三行 | — | `Snapshot_2_2_empty_*`、`Snapshot_2_2_workspace` |
| 2.3 | 离线 | 顶 `DLBanner` + 重试 / 诊断；批准按钮禁用 | — | `Snapshot_2_3_offline_*` |
| 2.4 | 搜索 | `.searchable(text:tokens:)`；工作区 token；`.searchSuggestions` | 工作区用 token | `Snapshot_2_4_search_*`、`Snapshot_2_4_suggestions`、`Snapshot_2_4_empty`、`Snapshot_2_4_degraded` |
| 2.5 | 电脑与工作区 | `toolbarTitleMenu` 两组 | 菜单代替弹层 | `Snapshot_2_5_menu`、`Snapshot_2_5_archived`（静态列表） |
| 2.6 | 长按 / 左滑 | `contextMenu`；`swipeActions` 归档 / 删除；**审批不滑动** | 多左滑 | `Snapshot_2_6_context_*`、`Snapshot_2_6_delete`、`Snapshot_2_6_rename` |

## 3.x 新任务

| v4 | 页面 | iOS 页面 / 组件 / API | 与 Android 差异 | 截图测试 |
|---|---|---|---|---|
| 3.1 | 草稿 | push 对话草稿态；工作区胶囊；预设 chip；`DLComposerView` | — | `Snapshot_3_1_draft_*` |
| 3.2 | 选工作区 | `.sheet` + 搜索；绝对路径多「使用 X」 | — | `Snapshot_3_2_workspace_*` |
| 3.3 | 添加工作区 | 路径 + 最近目录；202 pending 提示 | — | `Snapshot_3_3_add_ws_*` |
| 3.4 | 智能体预设 | `.sheet` 单选 + 说明 | — | `Snapshot_3_4_preset_*` |

## 4.x 对话

| v4 | 页面 | iOS 页面 / 组件 / API | 与 Android 差异 | 截图测试 |
|---|---|---|---|---|
| 4.1 | 运行中 | `UICollectionView` 消息流；系统导航栏 + `.navigationSubtitle`；diff 角标 + ⋯ `Menu`（同一 `ToolbarItemGroup`）。输入区 `DLComposerView` 留给 I4.3c | 本项不做输入区 | `Snapshot_4_1_running_*`、`Snapshot_4_1_unconfirmed`、`Snapshot_4_1_image` |
| 4.2 | 轮尾 | 改动卡（最多 3 行 + 查看全部）→ 元信息 → 复制 / 重新生成 / 分享 → chip | 查看全部不进入改动页 | `Snapshot_4_2_tail_*` |
| 4.3 | 待审批 | 对话页底部 `DLComposerView` 切到决策：状态行、问题、命令块、拒绝 / 允许一次；消息流约 42% | 只处理手机接管的最新一条。加号、模型、权限、麦克风没有本项数据，不画 | `Snapshot_4_3_approval_*` |
| 4.4 | 回答问题 | 同一玻璃容器切到提问。多题翻页和跳过还没接 | 只显示最新一条未终态提问 | `Snapshot_4_4_question_light_zh` |
| 4.5 / 4.8 | 状态槽 | `DLStatusSlot`；优先级断线 > 待处理 > 目标 > 预览；`scrollEdgeEffectStyle(.soft, for: .top)` | 状态槽无玻璃 | `Snapshot_4_5_status_*`、`Snapshot_4_8_status_*` |
| 4.6 | 工具过程 | `DLProcessLine` 展开细线步骤 | — | `Snapshot_4_6_process_*` |
| 4.7 | 轨迹 | push `TrajectoryPage`：搜索、筛选 chip、按轮分组，组头右侧时间次要色 | 改动 / 文件 / 子代理 / 用量 / 预览等菜单项目标页留给后续项 | `Snapshot_4_7_trace_light_zh` |
| 4.9 | ⋯ 菜单 | `Menu` 两组：查看（改动、文件、轨迹、子代理、用量、预览）/ 操作（目标、定时任务、重命名、分叉、分享）。只有轨迹会 push | 归档 / 删除只在首页。其余目标页留给后续项 | （按钮含在 4.1，不单开基线） |

## 5.x 弹层与对话框

| v4 | 页面 | iOS 做法 | 与 Android 差异 | 截图测试 |
|---|---|---|---|---|
| 5.1 | 指令面板 | `/` 后在输入区上方列表（同玻璃容器） | — | `Snapshot_5_1_slash_*` |
| 5.2 | 模型与推理 | `.sheet` + 分段推理 | — | `Snapshot_5_2_model_*` |
| 5.3 / 5.4 | 权限 | `.sheet` 三项；完全权限橙色；`confirmationDialog` | — | `Snapshot_5_3_perm_*` |
| 5.5 / 5.6 | 附件 | 拍照 / 相册 / 文件（`UIDocumentPicker`）。只把 PNG、JPEG、WebP、GIF 放进现有 `prompt.images`；其他文件拒绝。图片裁剪明确不做 | Android 仍无「文件」 | `Snapshot_5_5_attach_*`（本项未重录基线） |
| 5.7 | 用量 | `.sheet` 大数字 + 进度 | — | `Snapshot_5_7_usage_*` |
| 5.8 | 子代理 | push 树状列表 | — | `Snapshot_5_8_subagent_*` |
| 5.9 | 分享 | `.sheet`；图片 / 文本 | — | `Snapshot_5_9_share_*` |
| 5.10 | 重命名 | `alert` + 文本框 | — | `Snapshot_5_10_rename_*` |
| 5.11 | 删除 | `confirmationDialog` `.destructive` | — | `Snapshot_5_11_delete_*` |
| 5.12 | 定时任务 | push；分段；无暂停开关（插件无接口） | — | `Snapshot_5_12_schedule_*` |
| 5.13 | 编辑目标 | `.sheet`；「清除目标」红字左下 | — | `Snapshot_5_13_goal_*` |
| 5.14 | 选择文字 | 全屏系统文本选择 | — | `Snapshot_5_14_select_*` |

## 6.x 改动 / 文件 / 预览

| v4 | 页面 | iOS 页面 / 组件 / API | 与 Android 差异 | 截图测试 |
|---|---|---|---|---|
| 6.1 | 改动列表 | push；zoom 转场；底「就这些改动提问」 | — | `Snapshot_6_1_changes_*` |
| 6.2 | diff | 统一视图；词级高亮；底上一个 / 下一个 / 提问 | — | `Snapshot_6_2_diff_*` |
| 6.3 | 文件浏览 | push；面包屑 + 列表（无树） | — | `Snapshot_6_3_tree_*` |
| 6.4 | 文件预览 | 图片/文本就地；其余 `QLPreviewController`；SHA-256 | — | `Snapshot_6_4_file_*` |
| 6.5 | 预览 | 本机 `127.0.0.1` 代理 → 插件预览；WKWebView 只放行回环 | — | `Snapshot_6_5_preview_*` |
| 6.6 | 预览空态 | `DLEmptyState` | — | `Snapshot_6_6_preview_empty_*` |

## 7.x 设置

| v4 | 页面 | iOS 做法 | 与 Android 差异 | 截图测试 |
|---|---|---|---|---|
| 7.1 | 设置首页 | `Form` insetGrouped：电脑 / 通用 / 智能体 / 其他 | 图标无彩色底块 | `Snapshot_7_1_settings_*` |
| 7.2 | 电脑与配对 | 连接三行 + 诊断 / 改名 / 换电脑 + 解除配对 | — | `Snapshot_7_2_host_*` |
| 7.3 | 连接诊断 | SF Symbol 状态行；复制仅枚举与数字 | — | `Snapshot_7_3_diag_*` |
| 7.4 | 通知 | 总开关、审批、完成、Live Activity、网关说明 | 多 Live Activity | `Snapshot_7_4_notify_*` |
| 7.5 | 外观 | 跟随系统 / 浅 / 深；无 App 内字号滑杆 | 字号跟系统 | `Snapshot_7_5_appearance_*` |
| 7.6 | 对话默认 | 新会话：智能体预设、权限、默认模型；运行中再次发送方式（排队 / 引导 / 插话等，选项以插件声明为准）；接口与 Android 相同 | — | `Snapshot_7_6_chat_defaults_*` |
| 7.7–7.11 | 模型与余额等 | 与 Android 同接口 | — | `Snapshot_7_7_models_*` |
| 7.12–7.15 | 会话记录 / 关于 / 法律 / 崩溃 | MetricKit 本机导出 | — | `Snapshot_7_12_misc_*` |

## I4.8 宽屏

常规宽度用 `NavigationSplitView`：侧栏是首页，详情是对话，设置和新任务仍压在详情栈上。改动审查在常规宽度用 `.inspector`，紧凑宽度仍推进页面。快捷键：⌘N 新任务、⌘F 搜索、Esc 关掉当前弹层或详情栈顶；⌘↩ 在输入区发送。截图 `Tests/WideSnapshotTests.swift`，各 1 张浅色中文：`wide_portrait`、`wide_landscape`（审查面打开）、`wide_split_two_thirds`、`wide_split_half`、`wide_split_third`（紧凑，只留首页）。

## 8.x 系统

| v4 | 页面 | iOS 做法 | 与 Android 差异 | 截图测试 |
|---|---|---|---|---|
| 8.1–8.2 | 通知 | 分类 + NSE；锁屏不显示内容；无通知栏批准 | 多 Live Activity | `Snapshot_8_1_notification_*` |
| 8.3 | 分享进来 | 分享扩展 → `SharePickerSheet`；只预填不发送 | — | `Snapshot_8_3_share_in_*` |
| 8.4 | Live Activity 锁屏 | Widget；状态 / 步数 / 计时 | Android 无 | `Snapshot_8_4_live_activity_*` |
| 8.5 | 灵动岛 | 收起 / 展开；「需要审批 · 在 App 中处理」 | Android 无 | `Snapshot_8_5_island_*` |

## 组件对照（附录 A.2）

| Android | iOS |
|---|---|
| `DlTopBar` | 系统导航栏 + `.navigationSubtitle` + toolbar |
| `DlListRow` / `DlSectionHeader` | `List` / `Form` / `Section` |
| `DlStatusSlot` | `DLStatusSlot` |
| `DlInboxItem` | `DLInboxRow` |
| `DlComposer` | `DLComposerView`（UIKit） |
| `DlDecisionBar` | `DLDecisionBar` |
| `DlBottomSheet` | `.sheet` + `presentationDetents` |
| `DlDialog` | `alert` / `confirmationDialog` |
| `DlChip` / `DlSegmented` | `DLChip` / `Picker(.segmented)` |

## 冲突清单（设计稿 PNG vs PLAN，以 PLAN 为准）

| 位置 | 设计稿 | PLAN / 仓库决定 | 处理 |
|---|---|---|---|
| 1.2 欢迎、1.3 扫码 | ~~曾有「输入配对码」~~；v1.2 设计稿已去掉 | 1.4 不做（#64） | **已对齐**（#73 替换设计稿） |
| 4.3 / 4.4 决策栏按钮 | 实色填充（与 README 一致） | 附录 B：玻璃容器内用 `.bordered` / `.borderedProminent`，不叠玻璃 | 按 PLAN / 设计 README |
| 2.1「允许一次」 | `.bordered` + tint | 同屏唯一 BrandFill 给「新任务」 | 一致，按此实现 |
| 7.6 对话默认 | 设计稿未单独出图（入口在 7.1） | PLAN v1.2 已补规格 | 按 PLAN 实现；截图名 `Snapshot_7_6_*` |

其他：设计稿需要的数据若插件没有，按「删掉该元素」处理，在对应实现 PR 里写明。
