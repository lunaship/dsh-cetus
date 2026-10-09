# T01–T46 验收报告

> 方案 §28 验收矩阵 + §32 完成定义要求的交付物。
> 记录口径：**只写有证据的判定**。本地/CI 能验的标注测试或命令；
> 需要真机、真实 APNs、Apple 账号或 24 小时观测的，一律标**未验证**，
> 不因"代码看起来对"而改判。
>
> 构建：Android `0.5.0-beta.31`（versionCode 39，签名证书 `38f71adf…2a47`）；
> iOS `cetus/main` HEAD，模拟器 iPhone 17 Pro（CI 用 Xcode 26.6；本地 27.1，
> 像素级结论只认 CI）。

## 统计

| 判定 | 数量 | 含义 |
|---|---|---|
| **已验（本地/CI）** | 29 | 有单测/集成测试或 CI 门禁直接覆盖 |
| **部分** | 11 | 有代码与部分测试，但缺关键一半（如只测了双端之一，或只验了负例） |
| **未验证** | 6 | 需真机 / 真实 APNs / Apple 账号 / 24h 观测 |
| 合计 | 46 | — |

（本表的数字由 `docs/cetus/ACCEPTANCE-T01-T46.md` 正文逐行统计得出，不是估计值。）

> **没有任何一条标为"真机通过"** —— 本环境 Android 模拟器无法开启端口，
> iOS 只能跑模拟器，因此真机项全部保留为未验证。

---

## 逐条

| 用例 | 判定 | 证据 / 缺口 |
|---|---|---|
| T01 新装→欢迎→演示→退出 | 部分 | 品牌已统一为 cetus（`ProductNamingContractTests` 断言本地化目录 + Info.plist + 安装身份）；Demo 与真实服务由 `PerformanceLaunchFixture` 启动参数隔离。**缺**：真机双端截图与导航录像 |
| T02 相机扫码、相册识别 | 部分 | `PairingPhotoDecoderTests`、`PairingQRPayload` 解析与拒绝路径有测试；**缺**真机相机权限与实拍 |
| T03 远程/需确认配对 | 部分 | `PairingFlowModel` 的 pending/approved/rejected 与检查点恢复有测试（C14 配对维度已逐条对照）；**缺**真实 Relay 链路 |
| T04 权限拒绝后重试 | 未验证 | 需真机（相机/本地网络/通知三种授权）。代码有分类文案，无真机录像 |
| T05 两电脑/同名工作区 | 已验 | `inboxWorkspaceLabels` 最短可区分后缀 + 既有测试 `pinnedAwaitingIsNotRepeatedAndSameNamesKeepParents` |
| T06 收起有待处理的工作区 | 已验 | `InboxFlowTests` 覆盖计数与置顶不重复；截图变体覆盖收起态 |
| T07 离线首页/搜索/历史 | 已验 | `offlineEmpty(reason:)` 区分"连不上/配对失效"（C14 修复 #2）；写操作由 `link.isOnline` 门控；`lastOnlineAt` 显示最后更新时间 |
| T08 中文拼音组词时收流 | 未验证 | 需 iPhone/Android 真机 + 中文输入法录像 |
| T09 多行粘贴/表情/听写 | 未验证 | 需真机手动记录 |
| T10 A/B 会话各留草稿 | 已验 | `ComposerDraftTests`；键为 `(hostID, sessionID, kind)`，与 Android 的 `slotKey`+`ownerKey` 两级隔离已对照一致 |
| T11 发 A、等待中写 B | 已验 | `ConversationFlowTests` 提交状态机；`PhoneDecisionTests` 请求归并 |
| T12 快速连点发送 | 已验 | `decisionBusy` 忙碌锁 + `ConversationStatusTests` 归并；`PhoneDecisionTests` 断言同 ID 终态唯一 |
| T13 发送超时/失败 | 已验 | `SubmissionFailureTests`；超时归为"结果未知"，保留草稿，无自动重发 |
| T14 上翻中持续接收增量 | 已验 | **本轮补强**：`SustainedIncrementFollowTests` 模拟 100 次增量（小/大两种步长），断言全程保持 hold 且**锚点零漂移**（累积漂移是最隐蔽的 bug）。**缺**真机录像（策略层已压满） |
| T15 连续加载三页历史 | 已验 | **本轮补强**：`MultiPageHistoryTests` 用按游标返回不同页的服务，断言游标 20→10 前进、三页合并无重复、`hasOlder=false` 后不再请求、失败后重试用**同一**游标不跳页。此前只有单页用例 |
| T16 键盘/旋转/栏切换 | 未验证 | 需真机录像 |
| T17 审批允许/拒绝 | 已验 | `InboxFlowTests.approveSendsAllowedOnceAndDoesNothingOffline` 断言请求载荷；`allowed-once`/`rejected` 两端一致 |
| T18 电脑先处理同审批 | 已验 | `RequestState` 终态不被迟到 pending 回滚（两端规则逐条对齐）；`DshNotifier` 幂等窗口 |
| T19 单选/多选/自由回答 | 已验 | `QuestionForm` 必填/跳过 + `required:false` 归一（C14 修复 #4）；payload 断言存在 |
| T20 两题往返、回答时有普通草稿 | 已验 | 问卷草稿与普通草稿分槽（`questionAnswerPlaceholder` 独立于 prompt 槽） |
| T21 改动范围/轮次/文件 | 已验 | `ReviewTests`；统计来自 Host 响应，无本地推算 |
| T22 diff 上下段、引用 | 已验 | 差异段导航 + 死字符串清理（C08）；行号推进规则与 Android 逐条一致 |
| T23 文件树/符号链接/大文件 | 已验 | `ShareInbox.mergingImages` 限制；`ReadSecurityScopedFile` 越界拒绝；符号链接越界由迁移侧测试覆盖（`符号链接：指向目录之外时必须拒绝`） |
| T24 复制/分享/文件引用 | 已验 | `ShareInboxConsumeTests`（真复制路径 + `ShareLink`）；`WorkspaceFileExportTests` |
| T25 已批准/未批准/过期预览 | 已验 | `PreviewProxyTests` 用独立服务端验证 502/501/过期；token 只在主机请求头 |
| T26 主题/语言/设置写失败 | 已验 | 外观根层生效（C10 修复 #5，`ThemePreferenceTests`）；写失败不更新展示值（`save()` 只在服务端返回后合并） |
| T27 诊断/余额/崩溃空状态 | 已验 | 移除生产样例诊断 + 空态说明（C10 修复 #2）；诊断脱敏（`DiagnosticsRedactionTests`）；余额为十进制字符串 |
| T28 远程 bootstrap 与 SSE | 部分 | `RemoteInnerTLSTests`、`RemoteHTTP1WireTests`、`DlpVectorTests` 覆盖协议与钉扎；**缺**隔离 Relay 端到端 |
| T29 五次 Wi-Fi/蜂窝切换 | 未验证 | 需真机网络记录 |
| T30 Relay 拒绝/证书错/断网 | 已验 | RFC §7.4「拒绝码只提示、不删凭据」；`SSL alert 46` 负例在 `RemoteInnerTLSTests` 中实测 |
| T31 真实审批/提问/完成/失败推送 | 未验证 | **需真实 APNs（无 Apple 付费账号）**。本地已验：payload 顶层仅 `aps`/`e`/`k`（`TestAlertPayloadTopLevelIsExactlyApsEK`）、通用锁屏文案、NSE 不联网 |
| T32 推送解密失败/超时 | 已验 | `PushPayloadChainTests` 用真实 payload 形状覆盖未知 kid 回退与篡改密文负例 |
| T33 通知打开已处理/已删除会话 | 已验 | `PushOpenRouter` 三态（session/refresh/homeMissing）；`PushOpenRouteTests` |
| T34 Live Activity 启动/更新/结束 | 部分 | 真实 `Activity.request/update/end` 调用链 + `staleDate` 15 分钟 + 离线显示；**缺**真机锁屏与灵动岛外观 |
| T35 系统分享时已有草稿 | 已验 | C13「合并不覆盖」+ `SharePrefillStaysLocalTests`（只预填不发送） |
| T36 后台/结束进程/内存压力恢复 | 部分 | 草稿加密落盘与恢复有测试；配对检查点跨重启恢复有测试；**缺**真机内存压力与杀进程录像 |
| T37 大字号/VO/透明度/对比度/减动画 | 部分 | 截图矩阵覆盖浅/深 × 中/英 × 字号 + 降低透明度 + 增强对比度；`AccessibilityContractTests` 守住语义字体/减弱动态/关键标识。**缺** VoiceOver 实际朗读顺序与 AX5 极值操作（需真机） |
| T38 iPad/横屏/分屏/硬件键盘 | 部分 | `WideSnapshotTests`、`WideLayoutTests` 覆盖宽屏与 inspector；**缺**横屏与硬件键盘真机 |
| T39 Android 同签名覆盖升级 | 部分 | 交付包 `applicationId=dev.deeplinks`、证书 `38f71adf…2a47`、`versionCode 39` 已核验；`ProductNamingTest` 断言安装身份保持 legacy。**缺**真机覆盖安装 |
| T40 插件默认状态迁移 | 已验 | `test/state-migration.test.mjs` 34 条；真实目录已迁移并逐字节校验（`BASELINE.md` §6） |
| T41 自定义 stateDir 与冲突/故障 | 已验 | 同一测试文件覆盖自定义目录优先、冲突停下、源损坏/权限/磁盘满/中断 |
| T42 iOS 同 ID 升级/新 ID 安装 | 部分 | Debug/Release bundle id 已分离（C16）；`IOSBundleIdentifierTests` 断言 Debug 保持历史值。**缺**真机容器与签名核对 |
| T43 旧 QR/旧 API/旧深链/加密向量 | 已验 | `test/v1-contract-freeze.test.mjs` 钉住 `dlpush/1` 字节、TLS CN、`/dsh-link/` 前缀；双 scheme（`deeplinks://` + `cetus://`）已实现并有测试 |
| T44 旧版更新器→新仓库资产 | 已验 | `UpdateCheckTest` 过渡期同时接受新旧仓库，含 `dsh-links-evil` 相似前缀防护 |
| T45 profile 迁移/新旧插件重复启用 | 已验 | **本轮补强**：① 端口冲突诊断（`port-conflict.test.mjs`，含「不得含自动处置」断言）；② profile 迁移**只读助手** `scripts/profile-migrate.mjs` —— 用假 profile 端到端跑通：识别旧条目、列出会保留的 port/stateDir/autoApprove、敏感键脱敏、双 id 并存时警告抢端口，10 条测试。**写入不由插件执行**（越界），属维护者步骤且已在输出中写明 |
| T46 24 小时运行+迁移后回滚 | 部分 | 回滚侧：`STATE-MIGRATION.md` §6 可执行手册 + 沙箱实测 + §22.5 场景 9 用例。**缺** 24 小时 soak（本环境无法长时间独占运行） |

---

## 未验证项汇总（发布说明不得写成已支持）

| 类别 | 条目 |
|---|---|
| **真实 APNs** | T31（含锁屏展示、投递及时性） |
| **真实 Live Activity 外观** | T34（锁屏、灵动岛紧凑/最小/展开、多活动竞争） |
| **真机输入法** | T08、T09、T16 |
| **真机网络** | T04、T29 |
| **真机安装升级** | T39、T42 |
| **真机恢复与压力** | T36、T46（24h soak） |
| **辅助功能实机操作** | T37（VoiceOver 朗读顺序、AX5 极值） |
| **真实 Relay 链路** | T03、T28 |

## 与 §32 完成定义的差距

§32 要求「满足以下条件才可以说整体改造完成」。逐条对照：

| 完成条件 | 状态 |
|---|---|
| 所有 P0 关闭，主要 P1 在真实构建/生产入口验证 | **部分** —— P0 已关闭（见各 GAP 文档）；生产入口验证受真机缺失限制 |
| Android/iOS 用同 fixture，主要数据/状态/权限一致 | **部分** —— 状态归并、diff 行号、审批 outcome、草稿隔离、`required` 语义已逐条对照一致；其余维度未逐项比 |
| iOS 输入/键盘/返回/历史/决策达验收，不靠玻璃截图宣称 | **未达成** —— 需真机（T08/T09/T16） |
| 品牌均为 cetus，插件规范名 dsh-cetus；legacy 值有清单 | **已达成** —— `N-GAP.md` N01 有清单与原因；`v1-contract-freeze` 钉住保留项 |
| 已配对用户升级不丢状态；必须新装的路径诚实说明 | **已达成** —— 迁移算法 + 回滚手册 + `applicationId` 保持 |
| 远程/APNs/Live Activity 有与支持声明相称的证据，未验证不宣传 | **已达成** —— `C12-CONTRACT.md` 按四级分级记录，未验证项明确 |
| 截图基线由指定 CI 生成；两端完整真机矩阵与 24h 记录可追踪 | **部分** —— 基线规则满足；**真机矩阵与 24h 记录缺失** |
| 包/签名/hash/mapping/版本组合/安装说明/迁移说明/回滚限制齐全 | **部分** —— 包与 mapping 齐（`ANDROID-RELEASE-EVIDENCE.md`）；README 安装说明待维护者同步 |

## 结论

**整体改造未完成。** 代码与文档层的缺口已基本收敛（9 份 GAP/核对文档，
累计 47 个提交，CI 三工作流全绿，插件 435/435），但 §32 的完成条件中
「真机矩阵」「24 小时记录」「生产入口验证」三项在本环境**无法达成** ——
需要维护者在一台真机上执行 T08/T09/T16/T29/T36/T39/T42/T46 等条目，
并具备 Apple 付费账号后才能推进 T31/T34。

在此之前，本项目**不应宣称**已满足 §32 的完成定义。
