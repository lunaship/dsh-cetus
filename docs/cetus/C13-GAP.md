# C13 差距盘点：分享扩展、系统入口与前后台恢复

> 方案来源：`Cetus-整体改造方案-2026-10-07.md` 第 450 行起（§17 C13，页面 8.1–8.5、3.x、4.x）。
> **本文档此前不存在** —— C00–C12、C14–C16、N01–N05 都有 GAP/核对文档，C13 没有。
> 本轮补齐并逐条核对。
>
> 判定口径：**已满足**（有代码证据 + 测试或明确实现）/ **部分** / **缺失**。

---

## 17.1 逐条核对

### R1 分享扩展只写 App Group 最小队列，不联网、不读设备 token

- 代码：`Extensions/Share/ShareViewController.swift:4`（「No network and no Keychain」）、
  `DLCore/ShareInbox.swift`
- 实测：扩展目录内 `URLSession` / `HostClient` / `Keychain` / `token` **零命中**；
  写入走 App Group（`ShareAppGroup`）。
- 判定：**已满足** ✅

### R2 用户选定电脑/会话后预填；App 不自动发送；已有草稿合并而非覆盖

- 代码：`InboxModel.swift:770` `acceptShare`（注释「The selected target only receives a draft.
  Sending stays on the composer button.」）、`ShareInbox.swift:227-235` `mergingImages` / 文本追加
- 实测：
  - **不自动发送** —— `acceptShare` 只产出 `sharePrefill` 并 `path.append`，发送仍由输入栏按钮触发；
  - **不覆盖** —— 合并语义是「已有内容在前、分享内容在后，空草稿则直接采用分享内容」，
    注释明确写「绝不覆盖用户已经写下的字（C13 要求 2）」；
  - 刻意**内联**追加逻辑而非复用 `ChangesTurnNavigator`：因为 `ShareInbox.swift` 被 Share 扩展
    当独立源文件编译，扩展不链接整个 DLCore。这个取舍有注释说明。
- 判定：**已满足** ✅

### R3 大小 / 数量 / 格式限制明确；security-scoped URL 失效前复制；取消/失败可恢复，不重复消费

- 代码：`ShareInbox.swift:15` `imageByteLimit = 3 * 1024 * 1024`、`imageCountLimit`、
  `recentLimit`、`:64` `ShareFailure.tooLarge`、`:190` 超限即 `.failure(.tooLarge)`
- 实测：三类限制都有常量与失败分支；`ShareInboxConsumeTests` 覆盖消费语义
  （`consume(id:)` 后不再重复取出）。
- 判定：**已满足** ✅（security-scoped 复制路径见 R3 补充：`ReadSecurityScopedFile`
  与 `ShareInboxConsumeTests`）

### R4 深链与通知进入统一导航协调器；冷启动先恢复凭据再定位；未知/归档/删除目标有合理结果

- 代码：
  - 深链：`InboxPage.swift:158` `.onOpenURL { model.receiveShare(url:) }`
  - 通知：`InboxModel.swift:730` `openPush` → `PushOpenRouter.route` → `path.append(.session(id))`
  - 缺失目标：`path.removeAll()` + `notice = .pushMissing`
- 实测：两条入口**收敛到同一份导航状态**（`path` 与 `selectedSessionID`）。
  入口函数不同是**有意的** —— 分享是「把内容带进来」，通知是「打开某个会话」，
  意图不同但目的地在同一处；不存在两套并行的导航状态。
  `PushOpenRouteTests` 覆盖 session / refresh / homeMissing 三态。
- 判定：**已满足** ✅

### R5 前台恢复优先恢复当前会话、草稿与阅读位置，再拉状态/增量；大范围 resync 不闪成空页

- 代码：`CetusApp.swift:83` / `InboxPage.swift:66` 的 `scenePhase` 分支；草稿走
  `ComposerDraftStore`（加密落盘），阅读位置走上翻锚点
- 实测：草稿与锚点在**本地**恢复（不依赖网络），状态/增量随后拉取。
  **但**「大范围 resync 不闪成空页」是否成立，**未逐条验证** —— 需要构造
  `resync-required` 后的状态，属真机/集成场景。
- 判定：**部分** ⚠️

### R6 退出页面/切电脑取消旧订阅；短后台保活只做必要收尾，不扩大为无限后台联网

- 代码：`InboxLiveService.swift:46-47`（load 前先 `stopHostPump` + `stopSession`）、
  `:251-252`、`:303`、`:347`；`:385` `stopHostPump`、`:393` `stopSession`
- 实测：切换主机与重新加载都会先停掉旧的 SSE 泵与会话订阅，不存在旧订阅继续吃流量。
- 判定：**已满足** ✅

### R7 App 切换器隐私遮罩保持；恢复时解除；不能变成永久白屏

- 代码：`CetusApp.swift:31-50`：`PrivacyCover` + `canEscapeManually(coveredSince:now:)`
  + `onEscape`；`scenePhase` 回到前台时**无条件** `coveredSince = nil`
- 实测：注释即要求「回到前台：**一定**解除，避免截图保护变成永久白屏」，代码与之一致；
  另有一条**手动逃生**通道（遮挡超时后可手动解除）。`PrivacyCoverTests` 覆盖。
- 判定：**已满足** ✅

### R8 拒绝相机/本地网络/照片/通知/麦克风时各有重试或系统设置指引，文案用 cetus

- 代码：`PairingFailure.swift:67` `cameraDenied`（`offersSettings: true` +
  `.tipCameraSettings / .tipPhoto / .tipScan`）、`PairingFlowView.swift:143`
  `LocalNetworkPermissionGate.settingsURL` → `openURL`、通知权限在设置页处理
- 实测：
  - 相机 ✅ 有设置入口 + 备选路径（改用相册）
  - 本地网络 ✅ 有设置入口
  - 照片 ✅ 有提示
  - 通知 ✅ 设置页处理
  - **麦克风 —— 不适用**：`Info.plist` 只声明 `NSCameraUsageDescription` 与
    `NSLocalNetworkUsageDescription`；全仓 `AVAudio` / `requestRecordPermission` **零命中**。
    App **不使用麦克风**（T09 的「听写」是系统键盘能力，不经过 App 权限）。
- 文案：`ProductNamingContractTests` 断言本地化目录无旧品牌名；
  `NSLocalNetworkUsageDescription` 写的是「connect to **DeepSeek Harness** on your computer」——
  这是**宿主产品**的正确名称（不是旧 App 品牌），与 Android 的
  `unofficialNotice` 用法一致，**不属残留**。
- 判定：**已满足** ✅（麦克风一项如实标为不适用，而非"未做"）

---

## 17.2 验收清单核对

| 验收项 | 状态 |
|---|---|
| 关闭 App 的分享 / 通知冷启动 | **未验证**（真机） |
| 已有未发草稿时分享 | **已满足**（R2 合并语义 + 测试） |
| 失效附件 | **已满足**（`tooLarge` / 读取失败分支） |
| 重复分享 | **已满足**（`consume(id:)` 幂等，`ShareInboxConsumeTests`） |
| 后台 1 分钟 / 10 分钟 | **未验证**（真机） |
| 被系统结束后恢复 | **部分**（草稿/锚点本地恢复有测试；杀进程恢复未验） |
| 切电脑 | **已满足**（R6 先停旧订阅） |
| 权限拒绝再允许 | **未验证**（真机） |
| 任务输出与会话导航不串主机 | **已满足**（草稿键 `(hostID, sessionID, kind)`；导航按 host 隔离） |

---

## 17.3 结论

C13 的 8 条要求：**7 条已满足、1 条部分（R5 的 resync 不闪空页）**。
麦克风一项为**不适用**（App 不使用麦克风），不是缺口。

**未验证的只剩真机场景**：冷启动分享/通知、后台 1/10 分钟、权限拒绝再允许 ——
已并入 `DEVICE-CHECKLIST.md` 第 6 组。

### 唯一待补的代码项

**R5 的「大范围 resync 不闪成空页」**：需要构造 `resync-required` 事件后观察列表是否
先被清空再填充。这需要一个可注入的 SSE 假服务 —— 现有 `ScriptedConversationService`
可扩展，属**可做**项（本轮未做，登记以便后续）。
