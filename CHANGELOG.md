## 未发布（main）

**重设计 v4**

- 设计稿、页面截图和执行方案放在 `docs/redesign-v4/`。proposal 002 的「自行合并到 main」自 v4 起作废，见该目录 `PLAN.md` 第 1 节。
- Android 视觉规则换成 v4。架构测试按实底、v4 色表、4 档圆角和 4 的倍数间距执行；还没迁移的文件在 `V4MigrationAllowlist`，迁完一个模块就删一条。
- 主题换成 v4 色表与 5 档字号（26/17/15/13/12，系统字体）；去掉动态取色和品牌字体两个开关及 Plus Jakarta Sans 字体文件；纯黑背景改为 #000000 / #141416；玻璃、边缘渐隐、半透明条改为实色。

**界面修正（小米 15 / Android 16 真机反馈，方案 A）**

- 聊天页顶栏、底部输入区改成实底（与页面同色），不再是半透明条。顶栏下沿在内容滚到下面时画一条细线；底部实底上沿留 16dp 渐变。聊天页去掉边缘渐隐（原来用白色渐隐压在灰底上，出现白雾带）。
- 修顶栏高度：回填高度漏算状态栏，轨迹工具条和首条消息被压在标题下面。
- 玻璃控件（Control 档）表面不透明度提到约 88%（输入胶囊 92%），模糊 12dp，折射减半，背后正文不再透出。
- 输入区上方的建议胶囊改用卡片底色，不再透明叠字。
- 首页右上胶囊直接写当前工作区（「全部工作区」或工作区名）加下拉箭头，代替看着像禁用的漏斗图标。
- Markdown 表格：单元格支持行内 `**粗体**` 等格式，同一行单元格等高。
- 轨迹：DSH 注入的 system-reminder / runtime context 标为「上下文注入」，不再显示成「助手」或「用户」，也不另起回合。
- 输入区座位行（模型 / 访问模式）在主流手机宽度上显示文字。原来容器窄于 400dp 就只剩图标。
- 目标卡、计划清单改用共用卡面（白卡 + 发丝边 + 轻阴影）。

**预估花费**

- 插件只拿得到整段会话的累计 token，没有逐轮时间，算不出真实的峰谷占比。内置价表计价时新增 `amountMin` / `amountMax`（全谷时 / 全峰时），App 显示区间。`amount` 不变，旧 App 不受影响。模型仍按当前选中的模型计。

**预览（P5.1 复审）**

- 退出预览只清本次预览本机源的 Cookie 和存储，不再清掉 App 里所有 WebView 的数据。
- 预览路径里的 key 改为恒定时间比较。

## dsh-links 0.5.0-beta.27 — 2026-10-02

**开发服务器识别（P5.2）**

- 插件从工具输出里识别 `http://localhost`、`http://127.0.0.1`、`http://0.0.0.0` 加端口。只记下端口和会话 id，不保存输出内容。电脑面板对看到的端口可以一键批准。示例句里的地址不算。会话结束或归档后清掉。
- 手机在会话里显示「检测到 dev server :5173 — 请在电脑上批准」。没有在手机上批准的开关。

**预览（P5.1 App）**

- 会话「⋯」里的「预览」列出电脑上已批准的端口。页面在手机本机的回环代理里打开，经原来的证书钉扎和选路转到电脑。没有 JavaScript 桥，不能读文件，外链交给系统浏览器。退出时清掉这次预览的 Cookie 和存储。
- 用系统浏览器打开时只复制本机地址，不带设备凭据。页面里写死的 `localhost` 绝对地址不会被改写。

**预览端口（P5.1 插件）**

- 电脑面板可以批准一个本机端口（名称 + 端口，默认 2 小时，可撤销）。手机在已配对的前提下，把 HTTP / WebSocket 转到 `127.0.0.1` 上的这个端口。
- 不转发到其他地址。SSH、数据库、插件自己的端口和 Host 端口即使写进记录也会拒绝。手机 API 不能批准端口。访问只计数，不记录路径和内容。

**子代理树（P4.3）**

- 会话「⋯」里的「子代理」按 `parentSessionId` 组成树，显示标题和运行中 / 空闲。点进去仍是原来的会话页，子代理会话不能再发送。主列表继续隐藏子代理。父会话卡片在有子代理正在跑时显示「3 个子代理运行中」。列表里完全没有这个字段时，菜单不出现。

**计划清单（P4.2）**

- 目标卡下方增加可展开的计划清单。收起时显示「3/7 · 正在：……」，展开后逐项显示待办、进行中、完成，进行中的那一项高亮。数据用会话里已经解析好的待办，不再另做一份解析。没有计划时不显示。

**目标卡（P4.1）**

- 会话页顶栏下方显示目标卡：目标文本最多两行，阶段按 active / paused / blocked / complete 本地化，可以收起。暂停、继续、编辑、清除仍走原来的目标操作。
- 目标的 revision 变了时卡片短暂高亮，时长用现有动效令牌；系统开启「移除动画」时不高亮。没有目标时会话页不显示这张卡。

**定时任务和长任务完成通知（P3.3）**

- 定时任务结束时通知「已完成 / 失败 / 已停止」，点开进入那次运行的会话。普通任务（含子代理）只有运行超过设置的分钟数才通知，默认 3 分钟，避免短任务刷屏。总开关仍是「任务完成时提醒」。
- 通知正文只有状态和耗时。可以打开「通知里显示回复首行」，默认关闭；锁屏上仍然不显示回复。耗时按手机看到会话开始活跃的时刻计算，因为会话列表没有开始时间。

**后台监听整台电脑（P3.2 App）**

- 离开 App 后不再为后台单开一条会话流。每台已配对电脑保持一条主机事件连接；当前版本本机只保存最近一台电脑。有会话在跑或在等你时保持连接，全部结束后 5 分钟停止，常驻通知一起消失。
- 只有正在看的那个会话的审批通知带操作按钮。其他会话只提示到电脑上处理。
- 设置开关文案改为「离开 App 后继续接收通知」，默认仍是关闭。打开后说明电池优化白名单，并链到 `docs/android-background.md`（小米 / 华为 / OPPO / vivo）。

**主机事件（P3.2 插件）**

- 新增 `GET /dsh-link/mobile/events`（设备 token，SSE）。只推会话状态：执行中、等审批、等回答、完成、失败、停止，带标题和来源。不推消息正文和工具参数。
- DSH 没有全局会话事件。只有手机在订阅时，插件才每 5 秒对 `session.list` 做差分。心跳 25 秒。`Last-Event-ID` 能接上就补发，接不上就要求重同步。能力声明 `events: { host: true }`，旧 App 忽略。

**进度通知（P3.1）**

- 离开 App 后的常驻通知改为进度样式：标题是会话名，正文写执行中 / 等你审批 / 等你回答 / 已完成，并带第几步和已用时间。有待办时显示完成数，没有待办时用不确定进度条。
- Android 16 及以上使用 `ProgressStyle` 并请求提升为进行中通知；更低版本仍用普通进度条。同一条通知 2 秒内最多更新一次，流式片段不刷新。锁屏只显示「DeepLinks · 任务执行中」。

**预估花费（P2.3）**

- 插件：`history` 的 `stats` 在能计价时带 `estimatedCost`（金额、币种、价格日期、来源 `host` 或 `builtin`）。Host 模型没有价格字段时，用 2026-10-02 的 DeepSeek 官方价表，按峰谷分开计缓存命中、未命中和输出。未知模型不给金额。
- App：用量面板在有金额时显示「≈ ¥0.42（估算，价格日期 …）」。始终带「估算」，不和余额混在一起。没有金额时仍只显示 token。

**余额提醒与三档模型（P2.2）**

- App：设置里可以打开余额提醒并填写阈值，默认关闭。首页在充值余额低于阈值时显示一条不能关掉的提示，点按进入设置。未登录、查询失败或这台电脑不支持余额时不提示。进入首页最多 5 分钟拉一次余额，不轮询。
- App：选模型时顶部是省钱 / 均衡 / 最强。不写死模型 id：省钱和最强按这台电脑模型列表的上下文窗口（有输入单价时按单价），均衡用默认模型。某一档的模型不在列表里就置灰并说明原因。设置里可以改成指定的模型和推理强度。切换仍走原来的选模型接口。

**会话用量（P2.1）**

- App：点输入区的上下文环，或会话「⋯」里的「用量」，打开用量面板。列出未缓存输入、缓存命中、输出和合计，以及命中率、轮次、步数、耗时和上下文分项。旧电脑没有用量数据时只显示说明。
- 首页任务卡不显示用量。`session.list` 目前不带这些字段，不为它额外请求。

**Tailscale 优先（P1.3）**

- 插件：`100.64.0.0/10` 与 Tailscale IPv6 归为 tailnet。面板在这类地址旁标注 Tailscale。推荐地址仍是私网地址；Tailscale 地址继续出现在二维码里。
- App：配对时主地址照旧，另外保存二维码里与主地址不同的 Tailscale 地址。选路先试主地址，失败再试备用地址，都失败才走远程。成功的地址在当前网络下缓存，网络变化后作废。

**连接诊断（P1.2）**

- App：设置里打开某台电脑后可以进入「连接诊断」。按顺序检查网络、已保存的局域网地址、远程隧道、证书、设备凭据和时钟，并在插件声明了诊断能力时展示电脑侧结果。证书变更或凭据失效只给出重新配对的说明，不删除本机凭据。复制结果只有状态码和数字。
- App：设置里的电脑行用四色状态点表示最近一次连接（绿直连、黄仅远程、红失败、灰从未或超过 7 天）。只读已有探测结果。

**连接诊断（P1.1）**

- 插件：新增 `GET /dsh-link/mobile/diagnostics`（设备 token）和面板「一键检查」（回环 `GET /dsh-link/diagnostics`）。能力协商 `diagnostics: { v: 1 }`。结果只有状态码和枚举，不含 token、路径、地址或正文。关掉远程或本机接口超时会返回对应的 fail 和 code。

**Android UI：Lody 风格简化（#18）**

- 全宽导航条下线，顶部 / 底部改为边缘渐隐；返回、⋯、搜索、+ 等改为悬浮的液态玻璃控件（`DshGlassCapsule` / `DshGlassCircle`，Control 档：折射 + 边缘高光 + 柔阴影 + 按压回弹）。
- 首页分区、设置分组、设备信息改用灰底上的白色分组卡；标准页顶栏统一由 `DshPageChrome` 布局。
- 对话流按工具类型归类折叠；输入区新增「继续 / 复核 / 查看改动」建议行（只预填、不发送）。

**Android UI v3：玻璃打磨（#19）**

- 修复：无模糊回退（Android 8–11、省电 / 关动画、预览）忽略形状，胶囊与圆钮被画成方块；现按形状绘制，并采用拟物回退（对角渐变 + 顶光描边 + 柔阴影，借鉴 ChunUI，MIT）。
- 边缘渐隐：Android 12+ 渐进模糊（max 16dp）+ 画布色 0.96 渐变，只在内容滚到边缘下方时出现。
- 白卡统一 `dshCardSurface()`：0.6dp 发丝边 + 浅色一级柔阴影。
- 动效 token：入场揭示 320ms、cubic(0.22, 0.8, 0.36, 1)、错峰 70ms，用于建议 chip 与空态 / 错误态；无循环动画。
- 输入区座位行窄屏时模型名先省略，访问模式标签保持完整。
- 截图测试：对话墙改走生产消息流并使用白色聊天画布。

**仓库整理**

- 删除已完成的改版过程文档与对照图、DLP/1 M1 执行汇报、未引用的旧截图与 logo 概念稿。
- README 安装命令改为 `main`（`v0.1.0-beta.19` tag 尚未推送）；移除过时的「UI 精简整改」段落（记录见 0.5.0-beta.25）。
- 兼容矩阵精简为版本表与验证摘要；修正 Android 文档中的失效路径与过时的中继说明。

---

## dsh-links 0.5.0-beta.26 — 2026-10-02

**复核建议落地（参考 dsh-mobile / dsh-plugin-mobile-gateway）**

- App：长回复分段渲染。超过约 320 字的 Markdown 回复在 LazyColumn 里拆成多个条目（代码块围栏不拆开），滚动与首帧不再一次测量整条长消息；操作行与流式光标只挂在最后一段。
- App：流式增量合帧。SSE 增量按帧合并（每帧最多提交一次，48ms 兜底），长回复流式输出时不再逐 token 触发重组；流式分块处理抽到 `StreamChunks.kt`。
- App：顶栏 / 输入区毛玻璃。Android 12+ 使用 Kyant0 Backdrop 做背景模糊 + 活力（表面 78% 不透明）；低版本、省电 / 减少透明度、预览环境退回原半透明 / 不透明方案。
- App + 插件：会话控制。新增排队消息条（编辑 / 撤回 / 插队）、目标控制（编辑目标与轮数上限、暂停 / 继续 / 清除）、「⋯」菜单「定时任务」面板（查看规则与历史、修改间隔、删除）。插件新增 `/sessions/:id/queue`、`/sessions/:id/goal/*`、`/schedules`、`/sessions/:id/schedules*` 路由，RPC 白名单补 goals / session.updateQueue / schedule.*；能力协商 `capabilities.control` 声明支持情况，旧插件不显示这些入口。
- App + 插件：文件下载完整性校验。插件在文件响应头返回 `x-dsh-link-sha256`，App 下载后校验，不一致则丢弃并报错。
- App：隐私安全诊断。新增仅 debug 构建启用的 `PrivacySafeDiagnostics`，只记录布尔值与计数，不记录正文、路径或 token。
- CI：新增 ktlint 检查（1.8.0，固定 sha256），全量格式化一次。
- 文档：第三方声明补 Kyant0 Backdrop（Apache-2.0）与 dsh-mobile / dsh-plugin-mobile-gateway（MIT）。
- 截图基线需由 `regen-screenshots.yml` 重新生成。

---

## dsh-links 0.5.0-beta.25 — 2026-10-01

**Android UI 精简整改（7 步）**

- 第 1 步 · 设置页去重：首页收成 5 个分区（电脑 / 智能体 / 通用 / 通知 / 隐私）；进入设备页的入口只剩电脑行一处，改名、连接方式、更换电脑、解除配对都在设备页；语言页不再放「配对管理」；「繁忙时发送」并入通用。
- 第 2 步 · 首页顶栏 DeepLinks：单行「DeepLinks + 连接状态点 + 搜索 + 设置」，不再显示电脑名与下拉箭头；工作区筛选作为列表首项随列表滚动；「新任务」胶囊悬浮在列表底部。
- 第 3 步 · 对话 / 轨迹切换挪到顶栏右侧：顶栏单行，右侧紧凑分段控件（窄屏显示图标）+「⋯」；子代理入口移入「⋯」菜单首项，有子代理时「⋯」带小圆点。
- 第 4 步 · 会话流去重：执行中的目标与 todo 进度只在顶部吸顶摘要条出现；输入框上方仅在电脑离线或有改动时显示提示；思考指示只保留一个；操作行只挂在每轮最后一条回复上，其余消息长按可复制 / 分支；对话视图里工具调用按批折叠成一行，点按跳到轨迹。
- 第 5 步 · 悬浮半透明：会话页与首页改为叠层布局，内容可滚到半透明（92%）顶栏和输入区下方；分隔线仅在内容滚到其下方时出现；省电 / 关闭动画时退化为不透明。
- 第 6 步 · 图标规范化：图标统一 16 视口、线宽 1.25，名称与视口一致（14 号命名全部并入 16）；`Icon` 尺寸只用 `DshIconSize` 四档；图标向量改为惰性缓存，不再每次访问重建；新增单测门禁。
- 第 7 步 · 截图基线：按新 UI 由 `regen-screenshots.yml` 重生成。
- 修复（复核）：半透明修饰符把内容绘制两遍；会话页输入区在旧布局下被消息区挤出屏幕；工具摘要 LazyColumn key 可能重复导致崩溃；轮中助手消息无法长按复制；分段控件未选中段不变灰；SSE 重连时误报「电脑离线」。

---

## dsh-links 0.5.0-beta.24 — 2026-10-01

**UI / 通知 / 远程图片 / 验证收尾（本地方案五步）**

- App：输入框右侧的上下文百分比有了说明。核对后确认它与「会话用量」面板的「上下文占用」是同一口径（都是 `contextPressureTokens / contextWindow`），截图里的 46% vs 19% 只是两份预览样例数据不同——已抽成同一份 `sampleSessionStats`，两处数值一致（无需改名）。进度圈的 contentDescription 改为带数值的「上下文已用 46%」（新增中英文案 `contextUsedPercent`，不再单独挂 stateDescription）；点按从原来的小下拉面板改为直接打开「会话用量」面板（信息是原下拉的超集：同一进度条 + 系统 / 工具 / 对话消息分段明细都在），展开态收回进度圈自身，不动上下文条与 WorkspaceScreen。
- App：小字号上调。`DshType.microRelaxed / microMedium / microStrong` 从 11sp 提到 12sp（行高 16sp 不变，行高/字号 1.33 仍符合字阶契约）：统计了全部 31 处调用点，均为用户要读的辅助信息（会话用量面板「令牌总量 / 缓存命中率」、对话页底部「N 轮 · M 步 · X 令牌」状态栏、设置分组说明、轨迹行元数据、表格标签、错误文案等），没有纯装饰用途；12sp 是方案给的正文辅助信息下限。设置分组说明原本就用 `captionRelaxed`（12sp），不变。所有 sp 字号经 FontScaleManager 的 density fontScale 缩放，设置里的「字号」选项与系统字体缩放照常生效；大字号（1.3）下是否截断由 CI 重生成的 `SettingsDarkEnLarge` 等基线检查。
- App：空会话的截图基线不再是一张全空白图。核对后确认 `ChatCanvasKind.Empty` 按设计「空会话只留白」（画布就是空的 `fillParentMaxSize`，不存在入场动画，预览同样空白），真正的问题是只截画布；截图帧改为把真机上空会话的起点——下方输入区（上下文条 + 输入框占位句）一起入镜，输入区样例数据抽成 `ChatComposerArea` 与「对话页底部」预览共用。
- App：英文界面不再混中文。截图 / 预览帧在 `english = true` 时除注入本地词条外，同时切换全局界面语言（新增仅切内存态、不落盘的 `LocaleManager.setLanguageForPreview`：Compose 截图跑在 layoutlib 渲染环境里，SharedPreferences 不可用，且测试不应把语言偏好写进宿主机 App 数据）；首页、设置、设备、对话等英文截图全部为英文，各共享预览墙的示例数据也按语言切换。
- App：模型切换提示由原样显示 DSH 注入的英文系统文本改为本地化分隔线（「以上回复由 %s 生成」）；未配对电脑时设置页只保留配对入口，不再显示改名 / 连接方式 / 权限等无效行；首页、设置、设备页三处在线状态点统一为共享组件与同一组颜色 token；首页「最近」列表条目的副标题在有一句话结果时也会拼上停止原因。
- App：对话内审批卡补「不含完整参数」说明（与首页共用一份文案）；工具名不再同屏出现两次；底部 9dp 空心圆点与「^」提交图标改为带文字按钮（按所选项显示「拒绝 / 允许一次」），提问卡提交按钮同步替换；「状态待确认」改为「已发送，等待电脑确认」。首页审批卡「允许一次」改用描边按钮，与「拒绝」同等视觉权重。
- 对话中的网络图片默认不自动加载，点按单张加载；新增「隐私」设置分组。
- 通知栏默认只提供「拒绝」；新增「允许在通知栏直接批准」（默认关闭，仅 Android 12+，需解锁）。
- 锁屏状态下的审批通知不再显示工具名。
- App：通知栏审批兜底重排。关掉「离开 App 后继续接管审批」后，旧通知上的「允许」只收回通知、不再生效；在该开关开着但关掉「允许在通知栏直接批准」后点「允许」，通知改为提示「请在 App 内确认」。
- App：公式（KaTeX）与 Mermaid 图表的离屏 WebView 禁止联网并增加 CSP：只放行 `file:///android_asset/` 本地 bundle（`blockNetworkLoads` + `shouldInterceptRequest` 双重禁止联网）；Chromium 对 file:// 页面的 `'self'` 匹配不可靠，CSP 的 scheme 列表显式带 `file:`。
- CI：release（R8）构建后上传 mapping 制品（保留 30 天，用于崩溃反混淆）；模拟器 job 真正执行设备测试——公式与 Mermaid 渲染链路（`MathRendererInstrumentedTest` / `MermaidRendererInstrumentedTest`）此前只 `assembleDebugAndroidTest` 不执行，新增 `MermaidRendererInstrumentedTest`（`graph TD; A-->B;` 断言位图非空）与 TokenCrypto 并发首生用例（删测试密钥后 16 线程同时首次 `encrypt`，全部 `decrypt` 成功）。
- 新增提案：设备 token 过期与轮换（`docs/proposals/001-device-token-expiry-rotation.md`）。

## dsh-links 0.1.0-beta.19 — 2026-09-30

**配色统一（2026-09-30 第四轮）**

- App：浅色灰阶改跟品牌蓝同色相的冷灰（画布 `#F3F4F7`、卡片仍白）。深色中性灰不动。深色品牌蓝与思考轨改为 `#8B9DFF`。
- App：语义色只留 6dp 圆点和文字。审批卡、横幅、状态不再用色底胶囊或侧边色条。过程按钮（新任务、停止）改用 `inkFill` / `onInk`：浅色实心墨，深色 `#3A3C43`。禁用统一为 `bgSubtle` + `labelDimmed`。
- 插件：`tls.json` 无法解析或缺少证书时改名备份并拒绝启动，不再静默换证。安装命令改为 `github:lunaship/dsh-links#v0.1.0-beta.19`。
- App：冷启动后若有未看过的崩溃，首页给一条横幅，可查看或忽略。设置「关于」默认检查新版本，最多每 24 小时问一次 GitHub，只打开浏览器，不下载安装包。
- Relay：`/healthz` 返回 `ok <version>`。每 5 分钟打一行统计后清零，日志里没有路由、地址或密钥。本轮没有部署。

**远程连接改用 DLP/1，一张连接码（2026-09-29，RFC 0001 M2 + M3）**

- 插件：接入 DLP/1 Agent。面板打开「外出时也能连」即向中继注册（默认官方 `wss://relay.dshlinks.com/ws`，可自建），不需要接入码。二维码统一为一张：远程就绪时附带 `remote`，手机在家走局域网首配、在外经中继首配（远程首配一律需本机批准）。已配对手机经 bootstrap 自动补齐远程能力；吊销即断开该设备的远程流。
- 插件：「手机连接」面板重做，跟随 DSH 设置页的扁平行与 `--dsw-*` 主题（含深色）：需要处理的事置顶、连接码标出局域网 / 远程是否可用、远程连接的开关与自检、已配对手机标出能走的路。
- 插件：DLR/1（接入码 + 云端二维码 + 8443/8444 中继）整体下线，删除 `src/relay/`；启动时清除 `state.relay`，旧的「· 云」设备保留并标「旧版云端配对，已停用」。
- App：用 DLP/1 替换 DLR/1。每次新建连接先约 1 秒探测局域网（按网络变化作废结论），不通走中继；去掉「云端优先 / 局域网优先」与「扫码恢复云端」；远程错误按 RFC §7.5 提示，中继的拒绝码从不导致删除本机凭据。
- Relay：首条消息超时改为先发 `PROTOCOL_ERROR` 再以 4000 关闭（此前直接断开）。

**手机接管审批修复（2026-09-29）**

- 插件：`approval/request`、`user-questions/request` 两个 waterfall 改为 prepend 注册。DSH 的 api-remotes 也监听它们并转给电脑网页，排在插件前面时插件永远轮不到——手机即使正在订阅会话也接不到审批（此前记作「钩子不触发」）。无手机订阅时插件仍 `next()` 交回网页。
- 插件：SSE 背压不再误判断线。`res.write()` 返回 false 只是超过 16 KiB 水位；补历史时连写几十帧就会触发，曾被直接 destroy，手机订阅一连上就断并无限重连。现在积压超过 4 MiB 才按慢消费者断开，补历史积压过大时暂停、排空后续补。
- 插件：会话日志目录跟随 `DSH_HOME`（原先写死 `~/.dsh`，用 `DSH_HOME` 起的实例收不到实时事件，也读不到思考过程）。插件 state 目录不变。
- App：离开 App 后继续接管审批改为设置「通知」里的开关，**默认关闭**；关闭时离开 App 即断流、审批留在电脑网页。

**手机端重设计（2026-09-28 方案，分支 `redesign/inbox`）** —— 产品定位从「聊天 App」改成
「电脑上那几个智能体的遥控器与收件箱」。已完成阶段 1–5：

阶段 1 · 视觉底座

- 换一整套色板与角色：浅色画布 `#F5F5F2` + 白卡、深色画布 `#121214` + 卡片 `#1C1D21`；文字四级灰阶、分隔线、遮罩、强调蓝（`#3F5BD6` / `#7C93FF`）全部重取。色板整体偏离 DSH，`DshTheme.kt` 逐行标注原因。
- 新增 `warnContainer`（等你批准胶囊的暖色底）；深色 `onBrand` 改深色（白字在 `#7C93FF` 上只有 2.8:1）；浅色 `bgSubtle` 下压一档（稿值过不了既有的 1.05 分层下限）。
- 新增收件箱共享积木：`DshPillButton`（Accent/Ink/Tonal）、`DshStatusChip`、`DshStatusIcon`、`DshGroupCard`、`DshSectionLabel`、`DshFloatingPill`；补 5 个图标（带勾文档、断开的云、列表、锁、上传箭头）。
- `DshListRow` 升到方案规格：最小高 52、可传 32dp 图标槽、图标间距落到 `s12`。

阶段 2 · 插件会话摘要新增两个可选字段

- `activity`（进行中会话的当前步骤：命令摘要 / 步号）与 `lastResult`（结果一句话 + 本轮改动统计），由 `src/mobile-session-activity.js` 从 `session.history` 推导；只算最近 20 个会话、按 `sessionId+updatedAt` 缓存、并发 4、单个失败只跳过该字段。旧 App 与旧 Host 走同一套回退。字段级约定见 `docs/MOBILE_SYNC_CONTRACT.md`。

阶段 3 · 首页收件箱

- 三段分区（等你处理 / 进行中 / 最近），行首 32dp 状态圈，副标题改用上面两个新字段，行尾时间写「昨天 / 周五」。
- 顶栏：电脑图标块 + 名字 + `● 在线 · 云端 · 31ms`（离线写「离线 · N 分钟前在线」，本机新增 `LastOnlineStore` 记录）。
- 概况行「N 件等你处理 · M 个在跑」+ 工作区筛选菜单（横向胶囊条删除，长按菜单的两项功能挪进菜单行尾）。
- 内联审批卡（暖色胶囊 + 命令块 + 拒绝 / 允许一次），仅在「该审批由手机接管」时出现；其余行写「在电脑上处理」。
- 离线卡 + 列表 72% 不透明 + 新任务置灰；空态三行起手式；「新任务」改墨色悬浮胶囊。

阶段 4 · 新任务底部面板

- 新任务从首页浮起面板：继续上次的任务 / 工作区胶囊 / 多行输入卡（保留中文输入法）/ 模型与模式座；失败不关面板、错误写在输入卡下方。
- 对话页的新会话草稿起始块删除；命令面板、侧栏、空态起手式、系统分享全部改走这个面板。

阶段 5 · 对话页

- 顶栏两行标题（会话名 / 「工作区 · 电脑名」，执行中换成「◌ 正在执行 · 第 N 步 · M 分钟」），「对话 / 轨迹」从胶囊分段改成文字 Tab。
- 过程折叠行：一轮里的工具调用收成一行灰底胶囊「已完成工作 · Read (2)」/ 执行中「◌ 执行中」，行首带勾文档、执行中墨色转圈（原先转圈是品牌蓝，已按新合同改）。
- 轮末行只留复制与分叉，赞踩移入长按菜单；流内审批卡换暖色 tonal 外观。
- 底部一行小字「● 电脑名 在线 ········ N 轮 · N 步 · N 令牌」，输入条执行中显示墨色停止键，占位改成「补充说明，这一步结束后发给它」；改动卡底部改「查看全部 N 个 ›」。

阶段 6 · 看改动

- 文件条补「‹ 文件名 1 / N ›」；行号列只显示**新文件**行号（删除行留空）。
- 未改动的中间段折叠成「展开中间 N 行」（超过 10 行才折，保留首尾各 3 行；hunk 头是天然分隔符），点一下展开。
- 折行改为**悬挂缩进 2ch**：先用 TextMeasurer 量出可用宽度，再按真实排版取断点，切片走 AnnotatedString，行内改动的加深底色不会丢。
- 差异区底部加「就这个文件的改动问智能体…」提问条，点击把「关于 <路径> 的改动：」预填进对话页输入框。

阶段 7 · 设置页

- 电脑卡：名称 / 等宽地址 / **修改名称**（本机别名，不动 host 契约）/ 连接方式 / 智能体权限 / 更换电脑 / 模型与余额 / 「● 在线 · 云端 · 31ms」（与首页同一个探针的单一来源，见 `HostConnectivity`）。
- 通知分区两个开关（开启态墨色，全 App 生效）；说明「审批可以在锁屏上直接处理。」。
- 通用设置里的「忙碌时发送行为」（原「对话」页，保留三种取值）；撤掉「会话」入口（去处在首页筛选菜单「已归档」）。
- 底部「解除配对」独立红字块（只做入口，吊销流程仍在设备页）；页脚「DeepLinks 版本号 · 关于」。

阶段 8 · 通知

- 频道拆两个：审批高优先级、完成默认优先级。
- 审批通知带「允许一次 / 拒绝」动作：`ApprovalActionReceiver`（exported=false）直接提交；批准要求先解锁（Android 12+），拒绝不需要；成功改文案后 4 秒消失，失败清通知并打开会话。
- 完成通知正文改用插件的 `lastResult`（BigTextStyle 展开看全），带「查看改动 / 回复」两个动作。
- 两个开关关闭时不发对应通知（关卡放在 `DshNotifier` 内部，一处管住所有通知）。
- 后台常驻连接不在本次范围（方案 D2：另立项）。

阶段 9 · 平板与横屏

- 宽屏左侧常驻收件箱宽 390；对话页正文与输入区最宽 760 居中；右侧不显示返回键；新任务面板在大屏是居中弹层。

真机走查（2026-09-29，Android 17 / 1200×2670）

- 首页 / 新任务面板 / 对话页 / 设置 / 深色五屏与设计稿并排基本一致；进行中行的时长、离线态空心圆、
  执行中占位「补充说明，这一步结束后发给它」、墨色停止键、本机别名三处联动都在真机上确认。
- 修掉真机才看得见的五处：①列表行标题被含换行的主机名撑成两行 ②升级遗留的旧通知频道
  `dsh_events` 一直留着 ③新任务面板第二个座喂成了权限预设（方案要求是「模式」）且被推理档挤到截断
  ④模型切换提示被渲染成用户气泡（插件新增 `system_notice` 角色，App 安静居中一行）
  ⑤首页「最近」行图标与文案自相矛盾——根因是列表 API 没有 `stoppedReason`，已补进摘要。

未发布 · 其余

手机端补齐五项体验与工程能力（对照 lody-iOS 的做法，视觉仍以 DSH 为准）。

- 草稿落盘：输入框正文按主机持久化，进程被系统杀掉后回到同一会话仍在；附件不落盘；14 天过期、最多 30 条；删除会话或解除配对时一并清掉。
- 截图门禁：新增 `ChatFeedScreenshotTest`（一轮回复、流式中、审批卡、提问卡，亮 / 暗、中 / 英、1.3 字号）共 8 张基准图。
- 词级 diff：改动面板里相邻的删除行与新增行配对后，标出行内真正变化的片段；整行重写不标，插入的注释行不再打乱配对。
- 会话秒开：打开会话先显示上次的本地快照（Keystore 加密、按主机隔离、每主机最近 30 个会话），网络结果回来后整体接管；快照里未结束的审批 / 提问显示为「状态待确认」，不可提交。
- 工作区文件浏览：插件新增 `GET /dsh-link/mobile/sessions/:id/tree`（`capabilities.files.tree`，与 `/file` 同沙箱与订阅门槛，单层最多 2000 条）；App 会话菜单新增「浏览文件」，按层进入目录，图片与文本就地预览，未知后缀按内容嗅探。需要重启 host 才生效。
- 审批卡：提交栏不再单独铺白底，浅色模式下卡片不再像被截断，与提问卡一致。

手机端第二轮打磨：设置与主界面统一成同一套层次。

- 浅色模式改为「白底 + 浅灰分组卡」（与侧栏会话行、输入框同一层次），不再是灰底白卡；列表行首图标改中性灰，品牌蓝只留给操作行和选中勾。横幅去掉左侧竖色条。
- 模型页的供应商合并为一个分组，展开的明细缩进到名称起点；模型的上下文大小移到副标题，模型名不再被折断。
- 会话用量看板改为紧凑对话框：三个关键数一行、令牌构成一行、上下文一条进度；输入框下的统计行只保留一行浅色文字「7 轮 · 223 步 · 23.5M 令牌」。
- 解析层不再把 JSON `null` 读成 `"null"`：新增 `optStringOrEmpty`（不修剪空白），`MobileApi` / `QuestionAnswers` / `WorkspaceChanges` 全部改用空安全读取；新增 `JsonNullSafetyTest`。

修复手机切换会话模型必定失败：`445a21f` 拆分 `mobile-api.js` 时漏掉了 `selectSessionModel` 的定义，`POST /dsh-link/mobile/sessions/:id/model` 一直抛 `ReferenceError`，返回 502。已恢复原实现（按会话模型目录解析供应商 / 模型 id，推理等级不在允许列表时回落默认），`test/mobile-error-map.test.mjs` 新增回归用例。需要重启 host 才生效。

手机设置、设备、模型和工作区面板改成分组列表。

- 冷灰底上的白卡片，小标题和页脚放说明；一行里是着色图标、标题、当前值和箭头或勾选。
- 外观增加深色背景：柔和或纯黑，纯黑只在深色主题下生效。
- 侧栏会话行去掉左侧图标；进行中的会话在副标题前显示细环。

手机端统一成一套设计语言：M3 做骨架，自有图标一套，细节对齐。

- 图标：换掉 83 处 Material Icons，改为 Web 复刻集 `DshIcons` 加同笔法新画的 30 个 `DshGlyphs`（16 格满幅、线宽 1.35）；移除 `material-icons-extended` 依赖。新增 `iconsComeFromTheInHouseSetOnly` 门禁，截图墙新增 `IconsLight` / `IconsDark` 做基线。
- 列表行：取值贴右，右箭头排成一条竖线；下拉尾标由 iOS 式上下箭头改为下箭头；图标统一 18dp。
- 设备页按「设备 → 设置 → 操作 → 危险操作」排列，去掉与设备卡重复的在线状态。会话管理页去掉页眉红字「全部清除」，危险批量操作只保留底部「清除全部记录」一行。
- 数字：概览用 `29.8K`，明细用 `18,400`，单位一律大写 K / M；统计数字用等宽数位（tnum），不再换成等宽字体。
- 文案：`revision conflict` 这类服务端原文改为本地化提示；ASCII `...` 统一为 `…`；英文界面的预设名跟随界面语言。
- 真机修正：模型弹层副标题不再显示「Null」（`defaultEffort` / 当前模型字段改用 `optNullableString`）；模型页「插件过旧」在余额和供应商两处统一为灰字提示；默认模型行只显示模型名，不再被截断；「解除配对」换成更易辨认的断链图标。
- 空态 / 错误态：新增 `DshEmptyState` / `DshBrandMark` 模板，空会话、会话加载失败、无配对电脑共用；错误态居中，标题不再与说明重复，原始错误降为脚注。按钮统一为胶囊。
- Token：业务代码的数字圆角清零（新增 `DshRadius.xs` / `tail`、`DshTileShape`），并加 `cornerRadiiComeFromTokens` 门禁；`DshType` 删除 12 个像素命名角色（剩 13 语义 + 6 密集档）；裸 `sp` 从 46 处降到 33 处。

手机「模型」页对齐桌面版：余额恢复显示，供应商可在手机上补模型、填 API 密钥。

- 恢复 `GET /dsh-link/mobile/balance`，这次代调真实存在的 `account/getBalance`（DSH 0.1.7 起），按充值 / 赠金钱包返回；未登录、平台失败、旧 DSH 分别给 `signed-out` / `failed` / `unavailable`，不再 404。
- 新增 `GET /dsh-link/mobile/providers` 与 `POST .../providers/{models,credential,add,discover}`：供应商目录、增删模型、单向写 API 密钥、添加目录供应商、从供应商拉取可用模型。写入只落在 profile 的 `models` / `apiKeyEnv`，由插件自拼 `settings/mutate` ops；`baseURL` / `api` 不开放，密钥不回显。字段见 `docs/MOBILE_SYNC_CONTRACT.md`。
- `RPC_METHOD_ALLOWLIST` 新增 `account.getBalance`、`credentials.describe`、`credentials.set`、`llm.discoverModels`、`llm.listConfigurableProviders`、`llm.listProviders`、`settings.mutate`。
- 证据：新增 `test/mobile-models.test.mjs` 13 条（密钥校验、目录排序、余额三态、继承目录拒写、只读凭据、并发冲突、discover 忽略手机传入的 baseURL、响应不含密钥）。

去「Web 套壳」味：字阶向原生 M3 收敛，容器分层改 tonal 色阶。

- 排版：淘汰 1:1 平移 DSH Web CSS 的像素微字号——13sp 正文族（`t13`/`bodyDense` 等 10 个角色、约 150 处调用）并入语义角色 `body`/`title`/`bodyStrong`（15sp 起）；「小字号 + 松行高」角色（`t11`/`t12` 11/22、12/22 等）并入 `micro*`/`caption`/`label`（行高比收敛到 M3 最松的 bodyLarge 16/26 之内）。`DshType` 从 40+ 像素角色收敛为 13 语义 + 18 密集档，新增 `DshTypeScaleTest` 锁死契约（字号在字阶表内、行高比 ≤ 1.65、白名单制），Web 形态回流会直接红灯。
- 容器分层：设置分组卡、设备卡、审批/问题卡、模型选择行、两处菜单浮层共 11 处「底色 + 1dp 发丝线描边」改为 tonal 填充（`bgSubtle`）或纯阴影（对话框）；`DshTag` 删除无人使用的 `borderColor` 死参数。新增 `SurfaceHierarchyTest`：容器再用 `borderSubtle` 描边即红灯（白名单仅留媒体取景框）。
- 影响面：聊天/列表/设置/设备页的正文与次级文本字号、行高、卡片底色属有意的视觉调整；已逐一核对截图基线只渲染 `SettingsHome` 与组件墙（语义字阶 + FilterChip），不覆盖本轮改动，无需重录；仍建议真机过一眼。

收紧手机对主机工作区边界和设备吊销的权限。

- 绝对路径注册工作区不再直接调用 `workspace.create`。手机提交已存在目录的 realpath（符号链接展开成目标），电脑「手机连接」面板批准后才注册；拒绝、过期或该设备被吊销则丢弃。单层名称仍在当前工作区同级立即创建。
- `POST /mobile/sessions` 的 `cwd` 包含性检查会跟随符号链接：末段尚不存在时，对最深的已存在祖先做 `realpath` 再接回后缀。
- `POST /dsh-link/mobile/revoke` 只能吊销当前这台设备。吊销其他设备或全部设备仍只在回环面板。

解决「App 卸载重装后重新扫码连不上」：把同名设备冲突从死胡同变成显式替换流程，并给二维码加时效戳。

- 配对 409 结构化：`POST /dsh-link/pair` 同名冲突时返回 `code: "SAME_NAME"` 与 `existing {deviceId, name, status}`，旧 App 只读 `error` 文本不受影响。409 验码通过但不消费配对码，同一张码可直接重试。
- 新增显式替换：请求带 `replace: true` 时吊销同名旧设备再换发新 token（200 带 `replacedDeviceIds`）；开启「配对需本机确认」时旧设备保持在线、新设备进 `pending`（`replacing: true`），**面板批准的那一刻**才吊销 `replaces` 里的旧设备，拒绝/pending 超时不碰旧设备。替换动作始终被现有确认闸门覆盖，持码者不能在电脑端不知情时顶替现有设备。无冲突时 `replace` 即普通配对。合同见 `docs/MOBILE_SYNC_CONTRACT.md`。
- 二维码 / `pair-info` 新增 `issuedAt` 与 `expiresAt`（Unix 毫秒，主机时钟）：App 扫码后先比对本机时间，过期/陈旧的码直接提示刷新面板，不再拿旧截图的码撞 401/限流（吸取 OpenClaw 一次性码消费后重试死循环的教训）。实测云端二维码 85 模块 ≤ 93 上限，可扫性回归测试同步覆盖新字段。
- 面板待确认行在替换配对时显示「批准后替换同名旧设备」；批准/立即替换分别写审计日志 `device replace` / `device replace approve`（短 id，不含 token）。
- 文档：README 新增「App 卸载重装后扫码连不上？」恢复步骤；重申接入码与 App 更新/卸载无关（ENROLL 一次性、凭据长期自动续期，Relay 层无改动）。
- 证据：单测 198 绿（原 194 + 新增 4 条：立即替换、确认闸门下批准时替换、拒绝保旧、无冲突 replace；另扩展 2 条既有用例覆盖结构化 409 与二维码时效戳）。`git stash src/` 回退源码后，这 6 条相关用例中 5 条失败（「无冲突 replace」通过属预期：它是兼容守卫，旧代码本就忽略未知 `replace` 字段），另有 1 条既有用例因前序新用例中断在状态恢复之前而连带失败——断言确实锁住了新行为。

### 本轮改动文件（转发 DSH `workspaceChanges`）

- 新增能力 `capabilities.files.changes` / `diff` / `diffMaxLines`：仅当 Host 挂载了 `@deepseek-ai/dsh-workspace-changes`（DSH 0.1.7 起 Web 组合默认挂载）时下发；旧 Host 不下发，旧 App 忽略。插件不自己做快照，运行时按请求 `ctx.get("workspaceChanges")`（不进 `inject`，避免在旧 Host 上阻止插件加载）。
- 历史投影新增 `role: "workspace_changes"`：`workspace/changes` 事件（只带轮号）按其 seq 取 Host 摘要内嵌为卡片（路径、`display`、增删行数、`binary`/`oversized`，最多 100 个文件，`total` 为完整数量；不送 `cwd` 与快照 tree id）。同轮后一条宣告取代前一条（含取代为空）；Host 重启后取不到摘要的旧轮次不出卡片。
- 新路由 `GET /dsh-link/mobile/sessions/:id/changes?seq=`（完整摘要，最多 500 个文件）与 `GET .../changes/diff?seq=&index=`（单文件 hunk，按 5000 行 / 150 万字符截断并给出 `truncated`）。对比会送出文件全文（含工作区外文件），与文件下载同规则：只给持有该会话活跃 SSE 订阅的设备。
- 证据：单测 210 绿（新增 `test/workspace-changes.test.mjs` 12 条：摘要裁剪与降级标记、坐标校验、对比按行 / 按字符截断、binary/oversized、服务缺失容错、能力门禁、历史内嵌 / 取不到摘要 / 同轮取代）。

## dsh-links 0.1.0-beta.18 — 2026-09-23

DSH `0.1.7-alpha.1` 适配（V4 `tool/result` 缝合）+ 多帧 zstd 会话日志修复。

- 适配 DSH `0.1.7-alpha.1` 的 V4 session 日志：`tool/result` 从「`role: user` + `tool-result` 包装块」升为一等 `role: tool` 消息（`toolCallId` / `isError` 提到 `message` 上，`content` 直接是内层内容数组）。新增 `toolResultMeta()` / `toolResultContent()`，两种形状都认：`callId` 以 `message.toolCallId` 为先、回落 `source.callId`；`isError` 以 `message.isError` 为先、回落包装块；结果正文在包装块存在时取内层 `content`。
- 修复：V4 会话上失败的 `write`/`edit` 会被当成成功变更，手机端「产出文件」卡片把失败操作写过的文件也列进来。
- 修复：真实 V3 会话的结果正文原本是把整个 `ToolResultBlock` 序列化后的 JSON，现在取内层正文块（App 侧把 `tool_result` 的 `text` 当纯文本渲染，无需 App 改动）。
- 兼容性：DSH 源码基线由 `0.1.5-rc.2` 升至 `0.1.7-alpha.1`（npm `alpha`）。`next`/`0.1.5-rc.3` 经逐字节比对为本插件相关面上无代码变更（仅 `package.json` 版本号）。迁移面与回滚限制（V4 新会话无法在 RC 线读取、`settings.yaml` 一次性导入 `profiles/web/cordis.patch.yml`）详见 `docs/COMPATIBILITY.md` 的「DSH `0.1.7-alpha.1` migration notes」。
- 证据：单测 183 绿（新增 6 条 V3/V4 `tool/result` 用例，先 `git stash` 掉 `src/` 验证过新用例确会失败）；`scripts/e2e-arch-smoke.mjs` 对 `0.1.7-alpha.1` 35/35（隔离 `stateDir`，操作者 `state.json` 哈希未变）；操作者 host 已在 `0.1.7-alpha.1` 上重启，两台既有手机配对与 Relay 路由凭据完好。真机端到端尚未在本基线重跑。
- 修复：会话日志的多帧 zstd 没被解完。`session*.jsonl.zstd` 是按追加逐帧写的容器，而 Node 的 `zstdDecompressSync` / `createZstdDecompress` **只解第一帧**（后续帧不报错也不出现在输出里），原来的 `zstdDecompressAll()` 单次调用只能拿到会话头那一行（实测：973,558 字节的日志只消费了 178 字节）——「从会话文件补全 reasoning」因此一直静默失效（返回空 Map）。新增 `src/zstd-frames.js`，逐帧解压并按解码器自身消费的压缩字节数（`bytesWritten`）推进；实测同一文件 562 帧 / 1,489 行 / 3.5MB 正文、耗时约 48–99ms。同时该路径现在也能直接读未压缩的 `session.vN.jsonl`。
- 证据：单测 194 绿（新增 `test/zstd-frames.test.mjs` 8 条 + `test/session-log-reasoning.test.mjs` 3 条：多帧、单帧、空输入、逐帧 jsonl 保序、尾随非 zstd 字节只返回前缀、非 zstd 返回空、输出上限抛错、64 帧覆盖、文件→推理→投影端到端）。把 `decompressZstdFrames` 临时换回“单次调用旧行为”后这批用例 11 条中 7 条失败，确认测试锁住了该回归。
- 为能真正测到「文件补全思考」这条链路，把日志扫描从 `src/index.js` 抽成 `src/session-log-reasoning.js` 的纯函数 `reasoningBlocksFromSessionLog()`（flush 点仍与 `src/history.js` 同源），`readSessionReasoning` 只留 IO、路径解析与缓存。
- 验证：`scripts/e2e-arch-smoke.mjs` 在 `0.1.7-alpha.1` 上 35/35（含插件加载，即新模块 import 生效）；host 已重启加载新源码，loopback `pair-info`/`devices`/`relay-status` 与手机端 `18640/dsh-link/health` 均 200，两台配对完好。
- 未复现：上述验证过程中有一次 `npm run prepack` 报 193/1（未记下用例名），随后连续 8 次全量 + 6 次集成子集重跑均 194/0，无稳定复现。
- 未修复的已知缺口（已确认，非本次改动引入）：seeded/forked 会话（日志含 `session/end-seed`）没有 `assistant/chunk` 行，reasoning 只存在 `assistant/message` 的 `content` reasoning 块里；而 `history.js` 的 `assistant/message` 分支只取 `text` 块、文件扫描只认 chunk 行，所以这类会话在手机上不显示思考。
- 仓库卫生：`test/pairing-qr-payload.test.mjs` 的配对夹具原是从真实 `pair-info` 响应抄下来的，含维护者真实主机名、局域网/Tailscale 地址、设备 id、配对码与主机证书指纹。本仓公开，已全部替换为同形状占位值，并重写历史清除旧值。
- Android App 配套版本 `0.5.0-beta.20`（自适应工作台与导航重构、Mermaid 位图缓存像素预算、高权限命令统一确认、系统分享缓存配额、二级窗口 `FLAG_SECURE`）。

## dsh-links 0.1.0-beta.17 — 2026-09-20

- 安全加固（手机 API）：权限预设与 `settings.update` 的 `permission.defaultPreset` 拒绝原型键；新增宿主开关 `allowMobileDangerFullAccess`（默认关闭），手机端 `danger-full-access` 一律 403 拒绝而非静默降级；权限变更与文件下载要求该设备正持有对应会话的活跃 SSE 订阅。
- `POST /mobile/sessions` 的 `cwd` / `workspaceId` 必须落在 `workspace.list` 已注册工作区内，列表不可用时 fail-closed。
- 文件下载响应补 `X-Content-Type-Options: nosniff`，`html`/`svg` 改为 `Content-Disposition: attachment`；插件 JSON 响应补 nosniff / frame-options / referrer-policy；`tls.json` 读取时 `chmod 0600`。
- 工作区创建与文件接口不再回显 OS 原始报错；设备吊销与权限预设变更记录审计日志（短 id，不含 token）。合同与威胁模型见 `SECURITY.md`。
- Relay 控制面：控制 API 不再回显内部错误（服务端日志经 `logutil` 转义）；IPC 握手绑定服务端一次性 challenge（HMAC(token, challenge)）防重放，token 不再经过 socket；`init` 不再把管理员密码打印到 stdout，只写入 `admin.password`（0600）。
- Android App 配套版本 `0.5.0-beta.19`（Mermaid 11.17.2 + WebView 加固、分享仅收 `content://`、release 日志脱敏并 R8 剥离、`FLAG_SECURE`、Gradle wrapper SHA-256 校验）。

## dsh-links 0.1.0-beta.16 — 2026-09-14

- 冷启动配对就绪门控：`pair-info` / `qr.png` 在 TLS 加载完成且 HTTPS 端口真正 listen 之前返回可识别的 503（`proxy_not_ready`，附 `phase` 与 `Retry-After`），不再返回可用于扫码但 `certFingerprint` 为空的配对信息；TLS 初始化失败或端口被占用保持 failed 态，不假就绪、无未处理 rejection。Web 面板（`src/client.js` 同步重建）按 `phase` 区分「启动中（有限重试）」与「启动失败」。
- 审批接管兜底：无 `callId` 的审批 waterfall（如沙箱升级审批）回退绑定最近一条未决 `approval/asked`，使手机可决策、插件的 5 分钟超时对此类审批实际生效；接管与透传原因写入日志便于排查。
- 被占用会话的错误映射：目标会话已被 Web 端/其他设备持有时，移动 API 返回 409 `session_busy` 与可读文案，不再笼统 502。合同见 `docs/MOBILE_SYNC_CONTRACT.md`。
- `scripts/e2e-arch-smoke.mjs` 加固：每次运行独立临时目录与随机空闲端口；503 视为等待；200 必须携带有效指纹并用其对 HTTPS 证书做钉扎核验；空会话环境输出 SKIP；进程与临时目录 try/finally 管理。
- 新增回归测试：就绪窗口（`test/readiness.test.mjs`）与移动 API 错误映射（`test/mobile-error-map.test.mjs`）。
- Android App 配套版本 `0.5.0-beta.18`（SSE 游标重置 + 运行中静默看门狗、图片附件读取回退、配对后设备列表即时刷新、语义触觉与 spring 按压缩放、侧边栏收窄、会话卡片密度收紧）。

## dsh-links 0.1.0-beta.15 — 2026-09-12

- 手机 API 下发 Web 归档集合：`bootstrap` / `sessions` 携带 `archivedSessionIds`，`sessions/search` 的索引路径与降级标题搜索都过滤已归档会话；旧 App 忽略未知字段。合同见 `docs/MOBILE_SYNC_CONTRACT.md`。
- `workspace.list` 适配只认 `workspace/follow` 的持久 baseline（跳过瞬态状态帧，不再把瞬态帧当成空快照），并从 baseline 提取 `archivedSessionIds`；流式首帧读加 15 秒期限，读完后始终关闭迭代器。
- Android App 配套版本 `0.5.0-beta.17`；归档集合跟随需本版与该版的组合，旧组合忽略新字段、不破坏既有行为。
- - Relay Control 支持维护者开通的管控租户：邀请与 Host 按 `user_id` 隔离。自托管仍是单个 `admin`；没有 App 登录、没有公开注册。会话继续只存在于插件本机。停用租户会立刻作废未用邀请、吊销其 Host，并让已有控制台会话失效；维护者可恢复登录（已吊销 Host 不自动回来）。托管租户默认最多 4 个未用邀请、8 台已接入 Host，避免共享 Relay 被单户占满。配额「已接入」是未吊销名额，插件断开不释放。租户控制台 Host 列表主表只显示占名额的电脑，已吊销折起。邀请主表是未用码和仍占名额的已接入记录，失效折起；清理不会删掉还能对照吊销电脑的已用码。租户概览大数显示已接入占名额与未用邀请（对照上限），不是含已吊销的历史总数，管理员台账列出各户用量，已接入或未用码满额的标「已满」并排到前面（`tenant list` 同理），维护者不开户内机器，对方自己登录去吊销或签发；台账可再复制登录地址（回环不外发）。台账「查看电脑」只列出该户 Host / 邀请 / 记录（类似按用户看节点），优先让对方自己登录吊销；SSH 用 `tenant hosts --login` 列出占名额电脑（`--all` 含已吊销，不含路由密钥）；未用邀请满额时不带 `replaceOldestUnused` 仍返回 409，控制台确认后会作废最早未用码再签发（未用名额仍不超过上限）。满额 ENROLL（接入码有效、名额已满）返回 `QUOTA_EXCEEDED`，插件提示到控制台吊销，而不是再签发接入码。满额不消耗该码，吊销后同一张码可再接入。已接入满额且仍有未用码时，控制台签发按钮改为「同一电脑换路由」，避免作废新电脑已贴的码。吊销腾出名额后，若仍有未用码则提示新电脑再点接入，而不是立刻再签发。租户控制台在已接入满额时显示横幅，离线 Host 标「占名额」，列表把离线占名额的排在前面、最久未见的标「建议吊销」，横幅可一键吊销那台；签发前确认这张码只适合同一电脑换新路由。控制台不再要求换 Relay 前先吊销。控制台操作记录（`GET /v1/events`）记下谁签发/吊销了邀请与 Host，以及控制台登录成败（不含接入码、密码、会话）。未知登录名的失败不写入，以免冲掉真实操作记录。维护者可用 `dsh-links-relay tenant` 在本机开通/停用/恢复租户。Host 列表显示插件本机主机名，并按 Agent `REGISTER` 与节流后的 `PING` 显示在线/离线（写入 `last_seen_at`）；插件断开后立即离线，不是 90 秒窗口里的假在线。插件「断开」暂停 Agent 并保留路由凭据，可重连，不是吊销。同一电脑贴新接入码会换新路由、不占额外名额；旧路由的 `REVOKED` 不会擦掉新凭据，接入失败会恢复原 Agent。换到别的 Relay 会先 409 确认（`confirmRelaySwitch`）；新接入成功后插件会对原 Relay 发 `REVOKE_SELF` 以空出名额，原 Relay 不可达时才提醒去原控制台吊销，若原接入串带有控制台地址则插件可打开「原控制台」（不是 App 登录）。新电脑贴带控制台地址的接入码却满额时，插件记住该公网地址，刷新后仍可打开控制台去吊销。更换或换 Relay 成功后插件提示同一网络下的手机下次打开即可跟上，纯远程请重新扫云端配对码，手机不登录控制台。云端配对码在插件已有有效路由时即显示，不必等 Agent 心跳在线；暂停或吊销后隐藏。换路由后云端码按路由戳刷新，避免扫到旧码。App `CONNECT` 在 MAC 通过后若 Host 已吊销或旧路由已被替换，返回 `REVOKED`（不是 `AUTH_FAILED`），手机清掉失效云端路由、保留局域网配对，并在设备列表留下「扫码恢复云端」（本机标记，不登录 Control）。设备列表健康探测不再把该错误当成暂时离线，也不再因此删除整台配对。`GET /dsh-link/mobile/bootstrap` 带当前 `relay` 快照（未接入为 `null`），同一网络下可换上新路由。插件暂停仍是可重试的 `AGENT_OFFLINE`。匿名日流量挂起返回可重试的 `RATE_LIMITED`，不是 `REVOKED`：插件保留路由凭据，手机保留配对，控制台显示「挂起（日流量）」，UTC 零点后自动恢复；邀请制租户不受日流量限制。控制台吊销 Host 后，插件把 `REVOKED` 当终态：停止 REGISTER 重连、清掉 route 凭据（保留本机 Host 密钥以便再接入），并提示到控制台签发新接入码。控制台「复制」优先给出可粘贴进插件的完整接入串（含主机）；裸邀请码只作为官方 Relay 的简写。吊销后在同一台自建 Relay 上再贴裸码，会回到已记住的主机，而不是误连官方。新开或被重置密码的租户必须先改成自己的控制台密码，才能签发或吊销；自建 `admin` 仍只用 config 密码。TLS 反代若对回环 Control 走 HTTP，可设 `admin_secure_cookies`，登录 Cookie 才会带 Secure；自建 `http://127.0.0.1` 保持关闭。Control 只在对端是回环时采信 `X-Forwarded-Proto` / `X-Forwarded-For`，这样租户经 HTTPS 反代能登录，登录限流也不会把所有人算成本机。租户可自己改控制台密码，管理员可重置；其它控制台会话会失效。租户可清理自己的失效邀请和已吊销 Host，不会动到别人；Host 列表用电脑名、Host ID 和接入时间区分同名电脑，邀请记录标出消费该码的电脑（吊销或删除 Host 后仍保留当时的电脑名），电脑仍占名额时可从该行吊销，操作记录带电脑名（仍不含接入码）。接入码默认 8 小时、最长 24 小时；控制台可选 30 分钟 / 2 小时 / 8 小时 / 24 小时，签发结果写出截止时间；超长 `ttl` 会被夹紧。满额 ENROLL 不消耗该码，并把短于默认时长的未用码续到 8 小时（不超过签发后 24 小时），方便吊销后再贴。开通租户后优先交出 config 的 `public_control_url`（指向回环 8080 的 HTTPS 反代）；未配置时才抄非回环 Origin。`127.0.0.1` 不会当成租户登录入口。配置了该地址时，接入串带 `c=`，插件记住并可「打开控制台」；手机 bootstrap / 云端码不含此字段。App 仍不登录 Control。插件「断开」仍只暂停；「释放名额」发送 DLR `REVOKE_SELF`，立刻空出控制台已接入名额（手机随后看到 `REVOKED`，云端字段失效、局域网配对仍在），不经过 App 登录。
- 历史把成功的 write/edit/str_replace_editor 投影为本轮产出文件；`GET .../sessions/:id/file?path=` 在工作区沙箱内读文件（上限 8MB）。bootstrap `capabilities.files.workspace` 声明该能力。
- 会话列表不再下发 JSON `null` 的 `agentPreset` / `cwd` 等可选字段。旧 App 的 `JSONObject.optString` 会把 `null` 显示成顶栏字面量「null」；缺键则回落默认预设。
- 兼容性：DSH 源码基线由 `0.1.2-alpha.5` 升至 `0.1.5-alpha.2`（npm `alpha`）。npm `latest` 现为 `0.1.5-rc.1`（`next` 为 `0.1.5-rc.2`）。详见 `docs/COMPATIBILITY.md`。
- 移动端同步合同写明：`GET .../requests` 快照须带 pending 澄清题目与审批元数据；App 历史刷新 / 重同步后不得丢掉仍 pending 的澄清与审批卡。
- Relay README 不再把已删除的旧私有仓写成「已归档」；race 测试命令与 CI 一致，不再把 `CGO_ENABLED=0` 和 `-race` 写在一起。
- CI 比对插件 `testdata/dlr1-vectors.json` 与同仓 `relay/testdata` 镜像；RC1 证据脚本的 Relay 命令与门禁对齐。
- README 截图标明仍来自 App `0.5.0-beta.14`；SECURITY 写明公开仓含 `relay/` 源码、npm 包不含。

## dsh-links 0.1.0-beta.14 — 2026-09-07

- 补发历史超过 500 事件时不再静默跳号：无法证明连续覆盖则发重同步信号，游标不越过缺口。
- 多题澄清改为逐题校验；旧 App 不声明多题能力时回落桌面，避免用第一题答案填其余题。
- 审批生命周期与 SSE 断开分离：短暂断线有限宽限，吊销/插件退出立即失效；重复提交返回已记录终态。
- 历史与实时流把 `approval/asked` 与 `approval/decided` 归并为同一请求状态。
- 配套合同见 `docs/MOBILE_SYNC_CONTRACT.md`。完整后台推送（ENH-01）仍为渠道待定，未实施。
- Relay 源码并入本仓库 `relay/`；Android 客户端仍为私有仓。
- Android 客户端需 `0.5.0-beta.16` 才能使用重同步与多题校验；旧组合不会静默丢事件，但也不能假装完全兼容。
- npm `beta` 自 `0.1.0-beta.12` 以来的下一发包；源码线上的 `0.1.0-beta.13`（DSH `0.1.2-alpha.5` 适配）未单独发包，本版一并包含。

## dsh-links 0.1.0-beta.13 — 2026-09-02

- 适配 DSH `0.1.2-alpha.5`：ApiProxy 移除后，本机调用改走 Typert Gateway（`session/list`、`session/page`、`session/modelCatalog`、`settings/describe|update`、`agentPresets/list`）；`session.history` 与 `workspace.list` 分别适配为 `session/page`（或 `session/follow` 快照）与 `workspace/follow` 基线。
- `session.prompt` 补上必填 `requestId`；审批从 `Session.snapshotEvents()` 反查 `approval/asked`；澄清卡改接 `user-questions/request` waterfall（不再依赖已删除的 `/api/events.mux` / `/api/respond`）。
- 客户端注入去掉已下线的 `@deepseek-ai/dsh-client-runtime`，改为 `dsh-client-ui-layout` + `dsh-client-ui-settings`。
- 轮询/补洞的 `session.history` 带 `maxMessages` 时走 `session/page`，不再每秒开一条 `session/follow`；仅手机打开无参尾页、或 list 没有 `asOfSeq` 时才 follow。澄清卡答题与审批一样要求该会话当前 SSE 订阅。peer `@deepseek-ai/cordis` 锁到 `^4.0.2`。
- 兼容性：DSH 基线由 `0.1.1-rc.2` 升至 `0.1.2-alpha.5`（npm `alpha`）。npm `latest` 仍为 `0.1.1-rc.2`。详见 `docs/COMPATIBILITY.md`。

## dsh-links 0.1.0-beta.12 — 2026-08-30

- 「手机连接」面板改为 Claude 风：暖象牙纸底 + 赤陶主色、衬线标题、下划线 Tab、设置/设备分组列表；暗色为暖炭黑（`prefers-color-scheme`）。
- 布局收紧：配对区横向（二维码左、配对码与复制右），内容列约 452px 居中，避免宽设置面板被拉散。仅动 JSX 与 STYLE，接口与后端逻辑无变更。
- 下线 `GET /dsh-link/mobile/balance`：`llm.balance` 并非 DSH 的 RPC 方法（rc.8 起即不存在），端点自上线即返回不可用；同从 `RPC_METHOD_ALLOWLIST` 移除。App 关于页余额入口已容错为不展示（`balance = null`），无需 App 改动；若未来要恢复，需实现真实的余额代查（如经插件读取 DeepSeek 平台 API）。
- 兼容性：DSH 基线由 `0.1.0-rc.8` 升至 `0.1.1-rc.2`（npm latest）。已在本机对 rc.2 完成冒烟：插件加载、`/dsh-link/*` 路由、`session.list` / `session.history` / `llm.models` / `workspace.list` / `settings.describe` RPC、`events.mux` WebSocket 帧与设置面板 slot 均正常；真机端到端（扫码、SSE 推送、审批）尚未在 rc.2 重跑，详见 `docs/COMPATIBILITY.md`。

## dsh-links 0.1.0-beta.11 — 2026-08-28

- 「手机连接」面板 UI 重设计：配色收敛为统一的青绿令牌体系（主色/危险/成功/警告各配柔和底色与边线），圆角统一为 8/12/18 三档。
- 面板质感升级：弹窗遮罩毛玻璃、三层投影、品牌头渐变链接图标；二维码卡片加高光与虚线装饰框，配对码下方补充扫码引导文案。
- 交互细节：待确认设备圆点呼吸动效（`prefers-reduced-motion` 下自动关闭）、主/次按钮与输入框完整 hover/active/focus/disabled 状态、「配对需本机确认」升级为卡片式开关、设备列表数量徽标、暴露警告加警示图标、弹窗支持 Esc 关闭。
- 逻辑、数据流与接口调用无变更；Android 客户端仍为 `0.5.0-beta.14`。

## dsh-links 0.1.0-beta.10 — 2026-08-27

- 工程：新增 push/PR CI——DLR/1 向量校验、`build:client` 产物一致性检查、单测、`pack` 试运行、生产依赖审计。
- 工程：新增 RC1 封测计划（T1–T6、停止条件、出口标准）、七层验收证据模板与三仓证据收集脚本。
- Android 客户端仍为 `0.5.0-beta.14`，本次无 App 变更。

## dsh-links 0.1.0-beta.9 — 2026-08-26

- 安全：配对码只留在进程内存，`state.json` 不再写入 salt/hash。旧版落盘哈希可离线穷举 6 位码；升级后重启即作废当前二维码，已配对设备不受影响。
- 安全：18640 HTTPS 明确最低 TLS 1.2；手机端 prompt 图片只接受 png/jpeg/webp/gif。
- Android 客户端 `0.5.0-beta.14`：Markdown 图片拒绝十进制/十六进制/短格式 IP；Relay HTTP/1.1 客户端拒绝请求行与头里的 CR/LF。

## dsh-links 0.1.0-beta.8 — 2026-08-26

- 官方 Relay 只需接入码即可接入：主机与 TLS 指纹预填，不再要求手抄指纹或粘贴 enroll URI；已接入主机重连会复用已存的 TLS pin。
- 「手机连接」面板改为二维码优先、文案更安静。
- 设备 token 同时接受 `Authorization: Bearer`；HMAC 密钥在启动时落盘，避免重启后换钥导致已配对设备失效。
- 手机新建会话优先传 `workspaceId`，不再只靠目录路径，避免会话进错网页工作区。
- Android 客户端 `0.5.0-beta.13`：侧栏按工作区成员关系分组；启动图标改为角色绘；工作区列表与主机登记对齐，过期配对可恢复。

## dsh-links 0.1.0-beta.7 — 2026-08-25

- 面板可开启「配对需本机确认」：扫码仍发 token，但 API 要等本机点批准才放行（兼容旧 App）。关闭该开关只影响此后新配对，已在等待的设备须逐台批准、拒绝或到期，不会被静默放行。
- 「手机连接」增加吊销全部设备，并展示 18640 监听地址与可达网段（非私有地址 / extraUrls 时红色警告）。
- 设备 token 哈希改为恒定时间比较；Relay 自签 TLS 必须提供完整 SHA-256 指纹，控制帧有长度与并发上限。
- 文档补充 Android 客户端须先验证书指纹再发送配对码；失败即中断，成功后钉扎。
- Android 客户端 `0.5.0-beta.12`：识别主机确认 pending，不在批准前进工作区；同步网页归档工作区、加大无障碍点击区域，并收紧连接失败处理。

## dsh-links 0.1.0-beta.6 — 2026-08-23

- 远端连接面板不再展示默认 Relay 端口（8444/8443）；只填主机名即可接入，自定义端口仍可用。
- Android 客户端仍为 `0.5.0-beta.11`，本次无 App 变更。

## dsh-links 0.1.0-beta.5 — 2026-08-22

- 安全加固：设备 token 落盘哈希由裸 SHA-256 换为每安装 HMAC-SHA256；旧哈希在下次认证时自动迁移，手机无需重新配对。
- 安全加固：面板二维码 URL 不再携带配对码（服务端本就不读该参数），配对码不再进入 web 访问日志；配对码轮换改为重挂载取图。
- 安全加固：本机 RPC 方法抽为 `RPC_METHOD_ALLOWLIST` 闭集并在 `callLocalRpc` 内发出请求前强制校验，新增单测锁定闭集与全部调用点，防止未来重构引入开放转发。
- 防御性收口：SSE 补历史期间 mux 排队事件加 2000 条上限，超限丢弃并保留强制轮询兜底。
- 文档：标明云端二维码内含 Relay 路由凭据（`routeSecret`）、与接入码同等敏感；补充手机端审批 5 分钟超时行为说明。
- Android 客户端 `0.5.0-beta.10`：更新启动图标。

## dsh-links 0.1.0-beta.4 — 2026-08-22

- 电脑插件可凭维护者发放的接入码接入 DSH Links Relay；接入成功后才显示单独的云端配对码。
- 局域网设备与云端设备分开列出、分开吊销。
- Android 客户端 `0.5.0-beta.9`：扫云端码走 Relay，与局域网入口并存。
- 公开文档标明 Relay 仍为内测；接入码、Relay 凭据和 `state.json` 不入库、不随 Release / npm 发布。

## dsh-links 0.1.0-beta.3 — 2026-08-22

- Web ↔ 手机实时同步：mux `session/event` 直推到手机 SSE，绕开 1s `session.history` 轮询与「文件未变化」短路。
- mux 桥优先 WebSocket（新版 apiproxy `/api/events.mux` 对 SSE GET 返回 426），失败回退 SSE；握手超时后自动重试。
- 连号快路径直推，跳号走强制补洞轮询；连接补历史完成前不接直推，避免乱序与游标跳号。

## dsh-links 0.1.0-beta.2 — 2026-08-21

- 手机 SSE 转发 Web 澄清卡（`ask_user_question` / mux `question/requested`），并支持 `/question` 回传答案。
- Android 客户端 `0.5.0-beta.2`：会话竞态、审批确认、澄清卡 UI 等修复。

## 0.5.0-beta.1 — 2026-08-21

Android App 局域网 Beta。

- 原生工作台：多主机配对、会话、SSE、审批、通知。
- Release 构建可过 R8；正式签名通过仓库外的环境变量配置。
- 最小权限：删除未使用的旧存储权限，相机为可选硬件。
- 备份与设备迁移排除 Token / HostStore。
- 关于页提供 MIT 与第三方声明入口。
- 产品口径限定为可信局域网；不把远程连接写成可用能力。
- 分发口径：插件 / 文档 MIT 开源；Android 客户端闭源，仅 GitHub Releases 正式签名 APK。

## dsh-links 0.1.0-beta.1 — 2026-08-21

- 局域网扫码配对、一次性配对码、设备吊销、18640 手机 API。
- 「手机连接」面板只保留局域网路径。
- npm metadata、`prepack` 生成校验与 Beta 版本号。
