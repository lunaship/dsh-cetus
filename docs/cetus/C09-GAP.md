# C09 差距盘点：文件、产物、预览与分享操作

> 方案来源：`Cetus-整体改造方案-2026-10-07.md` 第 335 行起（§13 C09，页面 6.3–6.6，关联 4.x、8.3）。
> 代码基线：`cetus/main` @ `78d5c4f2`（干净 worktree `/Volumes/Space/c09-wt`，`dirty: false`）。
> 逐条列出：**要求 → 代码位置（文件:行）→ 实际行为 → 判定**。
>
> 判定口径：
> - **已满足**：代码已按方案实现，且有测试或明确代码证据。
> - **部分**：主路径可用，但存在方案明确要求而缺失的细节。
> - **缺失**：未实现。

---

## 0. 结论摘要（先说最重要的）

**方案 §13 开头的前提在本基线已不成立。**

> 方案原文：「文件入口目前传 `entries: []`；预览入口传 `previews: []`；个别复制/分享 action 为空。」

在第 335 行所描述的**旧快照**，这可能是真的；但在当前 `cetus/main`（`78d5c4f2`），**C09 已经整体实现完毕**：

- `ConversationPage.swift:213-260` 把 `FilesPage` / `FilePreviewPage` / `PreviewPage` 全部接了真实数据与真实动作，**没有任何 `entries: []` / `previews: []`**。
- 占位数组只保留在 `ReviewScreen`（`ReviewPages.swift:18-62`），那是**截图专用**的静态壳（`sampleFiles` / `sampleEntries` / `sampleText`），生产路径不经过它。
- **16 项测试在干净 worktree 里全绿**（`ReviewTests`、`WorkspaceFileExportTests`、`PreviewProxyTests`，3 suites）。

因此本文件的实际作用是：**逐条复核 C09 的 12 项要求，给出证据与判定**，并标出真正尚未闭环的少数几点（见 §3）。

**§3 里唯一的真实代码缺口 G3 已修复**（2026-10-08，方案 A），修复记录与门禁证据见 §5；其余为测试覆盖或真机验收缺口。

### 0.1 门禁证据（已实跑，非推断）

在干净 worktree `/Volumes/Space/c09-wt`（`HEAD = 78d5c4f2`，`dirty: false`）执行：

```
node scripts/build-metadata.mjs --platform ios   # commit 78d5c4f2, dirty:false
xcodegen generate --spec apps/ios/project.yml
xcodebuild -project apps/ios/Cetus.xcodeproj -scheme Cetus \
  -destination 'platform=iOS Simulator,id=E5E97F60-9F2D-4A99-B382-2A4A86CE8103' \
  test -parallel-testing-enabled NO \
  -only-testing:CetusTests/ReviewTests \
  -only-testing:CetusTests/WorkspaceFileExportTests \
  -only-testing:CetusTests/PreviewProxyTests \
  CODE_SIGNING_ALLOWED=NO ONLY_ACTIVE_ARCH=YES
```

结果：**`** TEST SUCCEEDED **`，`Test run with 16 tests in 3 suites passed after 0.235 seconds`。**

### 0.2 主工作树当前无法编译的原因（**不是 C09**）

在 `/Volumes/Space/Dev/dsh-cetus` 主工作树直接跑同一门禁会失败：

```
Packages/DLCore/Sources/DLCore/QuestionAnswers.swift:83:15: error: Static methods may only be declared on a type
Packages/DLCore/Sources/DLCore/QuestionAnswers.swift:107:16: error: ... 同上
Packages/DLCore/Sources/DLCore/QuestionAnswers.swift:115:16: error: ... 同上
```

该文件是**他人在途的未提交改动**（`git status` 显示 ` M`，`git diff HEAD` 为 `+123 −3`），不属于 C09 写范围。`Cetus` app target 单独 `build` 是通过的（只有 warning）；只有 `DLCore` 被带入测试构建时才炸。**这是并行会话的写入冲突，需要该文件的作者修完**；C09 的验证因此改在干净 worktree 完成。建议 Lead 协调该文件的所有者。

---

## 13.1 文件与产物

### R1 目录按 `/tree?path=` 一层一层懒加载；显示面包屑，返回保留位置，处理空目录和加载失败

- 代码：`ConversationModel.swift:477-506`（`loadFiles(path:)`，带 `filesGeneration` 世代号防竞态）、`:491`（`service.tree(sessionID:path:)`）、`:484`（`filesReturnAnchorName`）；`ConversationLiveService.swift:262-277`（`tree` → `sessionPath(id, "/tree")`）；`ReviewPages.swift:279-291`（面包屑）、`:296-311`（空/错误/加载三态）
- 实际：逐层懒加载；面包屑可点回任意祖先层；返回时按 `filesReturnAnchorName` 计算要揭示的子项并 `scrollTo`；空目录（`filesEmptyDir`）、失败（`filesError`）、旧插件不支持（`filesUnsupported`，`tree` 返回 `nil`）**三态互斥**。
- 测试：`WorkspaceFileExportTests.treeStatesStayDistinctAndReturnRevealsPreviousFolder` 断言 `legacy`→unsupported、`broken`→error、`empty`→空数组且 `!error && !unsupported`，并验证返回锚点 `"src"`。`ReviewTests.returningToAncestorRevealsTheFolderWeCameFrom` 覆盖中文路径。
- 判定：**已满足**。

### R2 打开文件走受会话 cwd 约束的 `/file`，确保活跃订阅要求；外部符号链接不能打开，不增加手机端越界路径接口

- 代码：`ConversationLiveService.swift:278-289`（`downloadFile` → `sessionPath(id, "/file")`）；`ConversationModel.swift:514-525`（`openFile(path:)`）；`ReviewPages.swift:334-364`（`entryRow`，`outside` 时 `disabled` 且 `onTapGesture` 提前 `return`）
- 实际：路径经 `sessionPath` 只拼会话 id（`sessionPath` 拒绝含 `/?#` 的 id）；外部符号链接（`entry.outside == true`）**既不可进入也不可打开**，并给出 `filesOutside` / `filesOutsideHint` 说明。未新增任何路径接口。
- 测试：`WorkspaceFileExportTests.productionModelOpensDownloadedBytesAndKeepsFailureDistinct` 覆盖成功与失败两条路径。
- 判定：**已满足**。

### R3 文本按编码和大小限制展示；二进制、图片、PDF、未知类型给对应查看/分享路径；复用已支持类型，不写泛用无界 WebView

- 代码：`DownloadedWorkspaceFile.swift:26-49`（`maximumBytes = 8MB`、`inlineTextBytes = 512KB`、`contains(0)` 判二进制、`filePreviewKind` 决定 text/image/quickLook）；`ReviewPages.swift:399-413`（text 走 `Text`、quickLook 走 `QuickLookPreview`、其余 `binaryFile` 提示）；`Review.swift:68-84`（`filePreviewKind`）
- 实际：超 8MB 直接拒绝；超 512KB 或含 NUL 字节**不按 UTF-8 渲染**；PDF / 未知类型走 `QuickLookPreview`（系统查看器 + 分享），图片同样有临时副本可分享。
- 测试：`WorkspaceFileExportTests.pdfAndInvalidTextAreNotRenderedAsUtf8`（PDF 与非法 UTF-8 均 `text == nil`）、`oversizedExportsAreRejected`（8MB+1 抛错）。
- 判定：**已满足**。

### R4 「复制路径」真正写系统剪贴板并反馈；「分享文件」导出受保护临时副本；「引用」加入当前会话草稿

- 代码：`ConversationPage.swift:243`（`onCopyPath: { UIPasteboard.general.string = file.path }`）；`ReviewPages.swift:421-434`（复制路径按钮 + 1.5 秒 `copied` 反馈）；`:435-437`（`ShareLink(item: fileURL)`）；`ConversationPage.swift:234-242`（引用 → `drafts.update(reference, kind: .reference)`）
- 实际：三者都是真实动作，且**引用走独立草稿槽位**（`.reference`），不与正文草稿互相覆盖。分享用 `WorkspaceFileExport` 产出的受保护临时副本（0700 目录 + 0600 文件 + `completeUntilFirstUserAuthentication`）。
- 判定：**已满足**。

### R5 产物消息点开真实文件；文件已删除/未下载/能力缺失有说明，不留下无效链接

- 代码：`ConversationPage.swift:777-781`（`openFile` → `model.openFile`）；`ReviewPages.swift:395-398`（`discarded` → `discarded` 文案，红色）、`:410`（二进制说明）
- 实际：打开失败时 `OpenedFile(path:failed:)`，UI 显示 `discarded` 文案且**隐藏分享与引用**（`liveShare: !file.failed`、`showQuote: !file.failed`），不留下无效链接。
- 判定：**已满足**。

### R6 临时导出和附件有有效期/清理策略；应用进入后台不能把尚在分享中的文件删掉

- 代码：`DownloadedWorkspaceFile.swift:51-67`（`expire()` 按创建时间 > 24h 清理，跳过符号链接，逐目录删除）；每个导出**独占 UUID 子目录**（`:33-43`）
- 实际：清理按 24 小时有效期，且**不在进入后台时执行**（`DownloadedWorkspaceFile.swift:16-17` 注释明确「Expiry runs on app launch, never on backgrounding while a share sheet may be using the URL」）。每次导出独立目录，另一份下载不会覆盖正在分享的文件。
- 测试：`WorkspaceFileExportTests.launchCleanupKeepsFreshExportsAndExpiresOldOnes`（新鲜保留、25 小时后删除）、`exportsStayInsideUniqueProtectedDirectories`（两次导出 URL 不同、权限 0600、中文文件名、`../` 穿越被剥离）。
- 判定：**已满足**。

---

## 13.2 预览

### R1 列表接入已批准 previews；检测到端口与已批准预览分开，不把「发现端口」显示为已经可访问

- 代码：`ConversationLiveService.swift:290-297`（`previews()` 过滤 `expiresAt` 已过期项）；`ConversationModel.swift:532-552`（`loadPreviews()`，`:541-546` 用已批准端口集合**剔除**检测列表）；`ReviewPages.swift:542-558`（`detectedHint` 单独区块）
- 实际：已批准预览与检测端口是**两个独立数据源、两块 UI**；检测端口只作提示文字，不可点击、不可批准。被已批准覆盖的端口不会重复出现在检测列表里。
- 判定：**已满足**。

### R2 手机仅打开已批准 previewID；需要批准时告诉用户回电脑操作，不新增手机批准端口接口

- 代码：`ReviewPages.swift:600-620`（`open(item)` 先 `refreshApproved()` 复核 id 仍在批准集合内，否则 `openFailed`）、`:551-553`（`previewNeedsApproval` 文案）
- 实际：打开前**重新拉取已批准列表复核**，不信任进入页面时的快照；不在批准集合内即拒绝打开并提示。未新增批准接口。
- 判定：**已满足**。

### R3 打开前刷新过期状态；WebView 仅用既有本机回环代理；不要把设备 token 放 URL 或交给页面脚本

- 代码：`ReviewPages.swift:605-611`（打开前 `refreshApproved`）、`:589-598`（`startProxy` 用 `PreviewLocalProxy`）；`Review.swift:109-111`（`previewLoopbackURL` = `http://127.0.0.1:<port>/<key>/<previewID>/`）；`HostClient`/`PreviewUpstream` 在**代理内部**注入 token
- 实际：页面 URL 只含回环地址、随机 key（`previewRandomKey`，16 字节）与 previewID；**token 不进 URL**，由代理转发时在请求头注入。
- 测试：`ReviewTests.previewPathRequiresTheKeyAndAPreviewID` 断言 key 必须按字节匹配、previewID 必须 24 位十六进制、拒绝 `../` 穿越与绝对 URL。
- 判定：**已满足**。

### R4 检查 HTTP、WebSocket、跳转、断网、过期、代理关闭和 App 返回后的恢复；代理只在必要生命周期存活

- 代码：`ReviewPages.swift:510-536`（`navigationDestination` 推入 WebView；`:519-528` 仅在真正离开导航时停代理，`:529-536` 进后台关代理、回前台 `onRetry()`）；`Review.swift:87-91`（`previewNavigationAllowed` 只放行回环源）；`ConversationModel.swift`（`previewForwarder()` / `previewWebSocketOpener()`）
- 实际：WebSocket 由 `PreviewUpstream.open` **桥接到钉扎主机**，不是本地回显；跳转被 `previewNavigationAllowed` 限制在回环源，其余交给系统浏览器；后台关闭代理、回前台重新拉取。
- 测试：`PreviewProxyTests`（158 行，含 WebSocket 帧与代理行为）。
- 判定：**已满足**。

### R5 锁死不可信 HTML/公式/Mermaid 的存储、网络、文件权限；保留仓库注明的 KaTeX/Mermaid 样式依赖

- 代码：`ReviewPages.swift:629-631`（`websiteDataStore = .nonPersistent()`）
- 实际：预览 WebView 用非持久存储。公式 / Mermaid 的 `style-src 'unsafe-inline'` 依赖按 `apps/ios/AGENTS.md` 保留未动。
- 判定：**已满足**。

### R6 网络图片保持默认不自动加载；用户选择加载时有来源与失败状态，不把整个页面联网权限放开

- 代码：`ConversationModel.swift:571-594`（`loadImage` 需显式触发，只允许 `https`，记录 `.loading/.loaded/.failed/.blocked`）；`Review.swift` 及 Chat 侧 `imageLoadDecision`
- 实际：默认 `.blocked`，用户点按才加载；仅 HTTPS；失败与阻止都有独立状态。
- 判定：**已满足**。

---

## 3. 真正尚未闭环的点（建议后续处理，均非「占位」问题）

以下是复核中发现的**真实**缺口，与方案 §13 开头描述的「空数组占位」无关：

| # | 要求 | 现状 | 建议 |
|---|---|---|---|
| G1 | 13.1 R6「应用进入后台不能把尚在分享中的文件删掉」 | 逻辑正确（只在启动时清理），但**没有测试**断言「进入后台不触发清理」 | 补一条测试或明确记录为人工验证项 |
| G2 | 13.2 R4「过期 / 代理关闭 / App 返回后的恢复」 | 有实现与部分测试；**端到端**（真断网、真过期、真返回）未覆盖 | 纳入真机/模拟器验收清单 |
| G3 | 验收项「8MB 边界按协商能力」 | ~~确认为真实缺口~~ **已修复（2026-10-08）。** 插件侧 `src/protocol-caps.js:37` 在 `files` 能力里声明 `maxBytes: 8 * 1024 * 1024`（`src/workspace-file.js:4`）；iOS 侧 `DLModels/Bootstrap.swift:182-205` 的 `FileCapabilities` 已建模 `maxBytes`，但原先 `DownloadedWorkspaceFile.swift:23` 硬编码 `maximumBytes = 8 * 1024 * 1024`，**没有任何地方消费该能力值**。今天两边数值恰好相同（都 8MB）所以看不出问题，但插件一旦调整，App 会继续按 8MB 判定，与「按协商能力」的验收口径不符。 | **已完成**，见 §5 |
| G4 | 验收项「中文文件名」「路径引用」 | 单测已覆盖（`WorkspaceFileExportTests` 的中文路径与 `ReviewTests` 的 `文档/设计`） | 无需动作，记录已覆盖 |
| G5 | 验收项「WebSocket 预览」 | `PreviewProxyTests` 覆盖帧解析与桥接；真机 WebSocket 预览未验 | 纳入真机验收清单 |
| G6 | 主工作树编译被 `QuestionAnswers.swift` 在途改动阻塞 | 非 C09 问题 | 由该文件作者修复；Lead 协调 |

## 4. 为什么最初没有新增代码

C09 的 12 项要求在 `cetus/main` 上**已全部实现并有测试**。若按「接通真实数据」再写一遍，会出现第二套并行的数据通路，违反方案 §2.3「不发明第二套」与仓库「不新增入口」的规则。因此首轮产出是**这份差距复核**，加上在干净 worktree 上跑通的 16 项门禁证据。

---

## 5. G3 修复记录（2026-10-08，Lead 选定方案 A）

### 5.1 改动

| 文件 | 改动 |
|---|---|
| `apps/ios/App/Features/Review/DownloadedWorkspaceFile.swift` | 新增 `effectiveMaximumBytes(declared:)`：`nil` 或非正数 → 回退 `maximumBytes`（8MB）；否则用声明值。`prepare(...)` 新增 `maximumBytes: Int = WorkspaceFileExport.maximumBytes` 参数（默认值保持既有调用兼容），内部先过一次 `effectiveMaximumBytes` 再比较。 |
| `apps/ios/App/Features/Chat/ConversationModel.swift` | 新增 `private(set) var declaredFileMaxBytes: Int?`；在 `absorbStatusEvent` 的 `ready` 分支写 `declaredFileMaxBytes = ready.capabilities?.files?.maxBytes`；`openFile(path:)` 传入 `WorkspaceFileExport.effectiveMaximumBytes(declared: declaredFileMaxBytes)`。 |
| `apps/ios/Tests/WorkspaceFileExportTests.swift` | 新增 3 条测试（见 5.2）。 |

**未新增任何服务端接口**；`ConversationModel` 的改动是最小的三处（属性、ready 赋值、openFile 传参）。

### 5.2 新增测试（Lead 指定的三条）

| 测试 | 断言 |
|---|---|
| `declaredSmallerLimitIsEnforced` | 声明 4MB 时：4MB 通过、4MB+1 拒绝、**5MB 也拒绝**（证明旧的硬编码 8MB 上限不再生效） |
| `missingDeclarationFallsBackToEightMegabytes` | `declared: nil` → `effectiveMaximumBytes` == `maximumBytes`，且 `maximumBytes == 8 * 1024 * 1024` |
| `nonPositiveDeclarationFallsBackToEightMegabytes` | `declared: 0` 与 `-1` → 回退 8MB，且结果 > 0（不会被 0 卡成「什么都不许传」） |

### 5.3 门禁证据（干净 worktree `/Volumes/Space/c09-g3`，`HEAD = def80993`）

```
node scripts/build-metadata.mjs --platform ios   # commit def80993, dirty:false
xcodegen generate --spec apps/ios/project.yml
xcodebuild -project apps/ios/Cetus.xcodeproj -scheme Cetus \
  -destination 'platform=iOS Simulator,id=E5E97F60-9F2D-4A99-B382-2A4A86CE8103' \
  test -parallel-testing-enabled NO -derivedDataPath /Volumes/Space/dd/design-contract \
  -only-testing:CetusTests/WorkspaceFileExportTests \
  -only-testing:CetusTests/ReviewTests \
  -only-testing:CetusTests/PreviewProxyTests \
  CODE_SIGNING_ALLOWED=NO ONLY_ACTIVE_ARCH=YES
```

结果：**`** TEST SUCCEEDED **`，`Test run with 19 tests in 3 suites passed`**（原 16 → 19，新增 3 条全绿）。

`xcrun swift-format lint --strict --configuration apps/ios/.swift-format` 对三个改动文件：**通过（exit 0）**。
修复过程中发现并修掉一处 `[LineLength]` 超长行（`ConversationModel.swift:525`）。

### 5.4 仍未闭环（与 G3 无关）

G1（后台不清理缺测试）、G2（预览端到端未覆盖）、G5（WebSocket 真机未验）、G6（他人文件阻塞编译）见 §3；G4 已覆盖。
