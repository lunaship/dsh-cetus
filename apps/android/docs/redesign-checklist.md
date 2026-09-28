# 重设计执行清单（阶段 6 之后）

对照《DeepLinks Android 重设计 · 实施方案》。这里只记**还没做完**的部分与踩过的坑，
已完成的部分见 [CHANGELOG.md](../../../CHANGELOG.md) 的「手机端重设计」一节。

## 已完成

阶段 1–6 已全部落地（阶段 5 的「下一步建议胶囊」按 D3 结论 v1 不做，仅留插槽）。

## 阶段 7　设置页精简（进行中）

**现状**（`native/SettingsRoute.kt`，917 行，`SettingsHome` 在 377 行）：

| 现在的分区 | 内容 |
|---|---|
| `sectionPairedComputer` | 一行：电脑名 + 地址 → 打开设备页 |
| `sectionGeneral` | 语言 / 外观 / 对话（`agentPreset`） |
| `sectionWorkspace` | 模型（`defaultModel` 作 value）/ 会话（→ `SettingsDest.SESSIONS`） |
| `sectionMore` | 关于（`BuildConfig.VERSION_NAME`） |

**方案要的新结构**（稿 05 / 15）：

1. 大标题「设置」——**缺**
2. 电脑卡：图标 + 名称 + 在线状态与延迟 + **等宽地址** + 「重命名」；三行「连接方式 / 智能体权限（原 `permissionPreset`）/ 更换电脑」+ 一行「模型与余额 ›」——**缺**（余额区块是功能，不许删）
3. 通知：「需要审批时提醒」「任务完成时提醒」两个开关（开启态墨色）+ 说明一行——**缺**
4. 通用：语言、外观（外观二级页保留主题 / 动态取色 / 纯黑 / 高对比）+ 一行「执行中发消息：排队 / 插话」（原 `busyEnter`）——语言、外观已有，**`busyEnter` 行缺**
5. 底部：「解除配对」独立红字块 + 页脚「DeepLinks 版本号 · 关于」——**缺**（现在「关于」是普通一行）

**原有功能的去处**（方案原表，一项都不能丢）：默认模型 / 推理强度 → 新任务面板记住上次选择，
设置里不再单列；模型与余额 → 电脑卡那行；`agentPreset` → 新任务面板的「模式 ⌄」；
`busyEnter` → 通用里的那一行；字号 → 外观二级页；会话（归档恢复）→ 首页筛选菜单「已归档」
（阶段 3.2 已完成）；关于、开源许可 → 页脚。

### 两个实施决定（按文档 + 现状定，不是新方案）

- **通知开关存哪**：方案写「开关存**本机**（`AppSettingsStore`）」。查证后
  `AppSettingsStore` 其实是**host 侧**（`src/index.js` 的 `settings.update` 白名单），
  把它当本机存储会与 WI-004「服务端设置为唯一真实源」冲突，还要动插件契约。
  因此按「本机」二字落在 App 的 `WorkspacePrefs`（与 `LastOnlineStore` 同一层），
  阶段 8 的 `DshNotifier` 从那里读——不动插件契约。
- **`SettingsDest.SESSIONS` 的处置**：方案把「会话（归档恢复）」的去处定为首页筛选菜单「已归档」。
  该入口阶段 3.2 已经接上，所以设置页这一项**移除入口、保留二级页代码**，
  避免违反「不要删除任何现有功能」。

### 红线

验收时**不要点「解除配对」「更换电脑」**（方案红线 5）。

## 阶段 8　通知（进行中）

**已完成**：频道拆两个（`dsh_approvals` 高优先级 / `dsh_tasks` 默认）；两个开关生效（关卡放在
`DshNotifier` 内部，一处管住所有通知）；完成通知正文改用 `lastResult`（带 `BigTextStyle`，
无结果时退回「任务完成 / 会话「x」已完成」）。

**已完成**（本轮补）：审批通知的动作按钮——`ApprovalActionReceiver`（exported=false）+ 清单注册
+ Notifier 动作 + 调用点传 `approvalId`；「允许一次」要求解锁、「拒绝」不要求；成功改文案后 4 秒
消失、失败清通知并打开会话。

**剩余**：完成通知的两个动作（查看改动 / 回复）。落点已经查清，留作下一片：

- 两个动作都是 `PendingIntent` 指向 `WorkspaceActivity`，各自带一个 extra（`openChanges` /
  `focusComposer`），`FLAG_IMMUTABLE`。
- **难点不在通知侧，而在消费侧**：`WorkspaceActivity.onCreate` 会把路由转给 `MainActivity`
  （`EXTRA_START_ROUTE`），外部 Intent 的真正解析在 `core/StartupRouting.kt`；而「打开改动面板」
  与「聚焦输入框」都要改 `WorkspaceScreen` 里的状态——那个函数预算是 2955 行、当前正好 2955，
  文件 3084/3087。所以要先抽一层（把两个 extra 的解析放进 `StartupRouting.kt`，并在
  `WorkspaceScreen` 里各留一行调用），并且多半要再合并几处相邻参数腾出行数。
- 参考：`composerFocusRequester` 在 244 行声明、2699 行传入；`composerKeyboardController?.show()`
  与 `requestFocus()` 的搭配在 2547–2550 行有一个现成写法可抄。
写之前已查清的事实：

| 需要的东西 | 现成的 |
|---|---|
| 提交审批 | `MobileApiClient(host).answerApproval(sessionId, approvalId, outcome): Boolean`（同步阻塞，接收器里要挪到工作线程） |
| outcome 取值 | `"allowed-once"`（批准）、`"rejected"`（拒绝），与 `ApprovalCard.kt:86` 一致 |
| 取回 host | `HostStore.current(context)`；Intent 里也带 `host.putInto()` 的三个 extra，可用 `List<Host>.resolveFromIntent(intent)` 校验 |
| 通知触发点 | `WorkspaceActivity` 约 1393 行，`approvalId` 那个局部值就在同一段作用域 |
| 清单 | `AndroidManifest.xml` 第 111 行 `</application>` 之前；目前**没有任何 receiver** |

**必须守住三条**：① `PendingIntent` 一律 `FLAG_IMMUTABLE`，Intent 只带 `sessionId`/`approvalId`、
**不带 token**（token 由 `HostStore` 在进程内取）；② `ApprovalActionReceiver` 注册为
`exported="false"`；③ 批准动作要 `setAuthenticationRequired(true)`（Android 12+ 先解锁），
拒绝不需要。

**为什么不留半成品**：这一片要么整体写对（Receiver + 清单 + Notifier 动作 + 调用点传 `approvalId`），
要么一行都不写。一个「注册了但没接上」或「点了没反应」的接收器，比不做更糟——门禁全绿但功能是假的。

**真机验证项**（当前 `adb devices` 为空）：锁屏下批准需解锁、拒绝不需要；动作成功 / 失败两条路径；
两个开关生效。
