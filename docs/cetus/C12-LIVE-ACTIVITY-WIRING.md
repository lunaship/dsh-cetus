# C12 Live Activity 接线 diff（交 lead 合入）

> 背景：`LiveActivityController`、`ActivityKitLiveActivityAdapter`、`LiveActivityPolicy` 均已实现并单测通过。
> 本文件只包含**接入 Chat/Home 所需的最小改动**，按 lead 要求每处 3–10 行。
> 接线点位于 `apps/ios/App/Features/Home/`（lead 明确不让 plugin-rebrand 直接改），故以 diff 形式提交。
>
> 行号基于 HEAD `6449bda8` + plugin-rebrand 的 14 文件补丁；合入时若行号漂移，请按上下文锚点定位。

---

## 设计要点（为什么这样接）

1. **唯一数据源**：`InboxModel.sessions: [SessionSummary]` 已带 `running` / `awaitingInput` / `stoppedReason`，
   无需新增网络调用，也无需改手机 API 合同。
2. **单一出口**：所有会话状态变化最终都汇聚到 `reload(resetStreams:)`（首屏、手动刷新、SSE 事件、错误路径都会走到），
   因此在那一处统一驱动 controller，避免散落多处造成「同一任务两个实例」。
3. **只挑一个会话**：多任务竞争时按「有等待 > 运行中 > 最近更新」选一个展示（§16.4.2 要求写入合同，此处为实现决策）。
4. **长任务阈值**：`running` 且已运行超过 60s 才创建，避免短任务反复起活动（§16.4.2）。
5. **终端态结束**：`stoppedReason` 非空或 `running == false` 且非等待 → `end`（§16.4.3）。

---

## 改动 1／3：`apps/ios/App/Features/Home/InboxModel.swift` — 持有 controller

在 `InboxModel` 的属性区（`var sessions: [SessionSummary] = []` 附近）加一个可注入的 controller：

```swift
// Live Activity 出口。默认不启用；由 Settings 开关与配对状态驱动。
// 单测注入 fake adapter，生产用 ActivityKit。
var liveActivity: LiveActivityController?

/// 当前正在展示活动的会话，避免同一任务重复 start。
private var liveActivitySession: String?
```

> 说明：`LiveActivityController` 是 `actor`，属性持有即可，调用处用 `await`。

---

## 改动 2／3：`InboxModel.swift` — 在 `reload(resetStreams:)` 末尾统一驱动

锚点：`reload(resetStreams:)` 里 `pairedDeviceID = payload.pairedDeviceID` 之后（当前第 871 行）。

```swift
             pairedDeviceID = payload.pairedDeviceID
+            await syncLiveActivity(with: payload.sessions)
             loading = false
```

然后在同文件加方法（放在 `reload` 之后）：

```swift
+    /// 依据最新会话列表推进 Live Activity（§16.4）。
+    /// 只挑一个会话展示：有等待 > 运行中 > 最近更新。
+    private func syncLiveActivity(with sessions: [SessionSummary]) async {
+        guard let controller = liveActivity else { return }
+        let candidate = sessions.first { $0.awaitingInput == true }
+            ?? sessions.first { $0.running == true && isLongRunning($0) }
+        guard let session = candidate, let sessionID = session.sessionId else {
+            await controller.endAll()
+            liveActivitySession = nil
+            return
+        }
+        // 会话已结束：显式收尾，避免锁屏一直显示「运行中」。
+        if session.stoppedReason != nil || (session.running != true && session.awaitingInput != true) {
+            await controller.end(sessionRef: sessionID)
+            liveActivitySession = nil
+            return
+        }
+        let phase = LiveActivityPolicy.phase(
+            pendingApprovals: session.awaitingInput == true ? 1 : 0,
+            pendingQuestions: 0,
+            running: session.running == true)
+        let startedAt = liveActivitySession == sessionID
+            ? (liveActivityStart ?? now) : now
+        liveActivityStart = startedAt
+        liveActivitySession = sessionID
+        _ = await controller.start(
+            hostRef: hostID, sessionRef: sessionID, phase: phase,
+            step: 1, startedAt: startedAt, waitingCount: session.awaitingInput == true ? 1 : 0)
+    }
+
+    /// 长任务阈值：短任务不创建活动，避免反复起停。
+    private func isLongRunning(_ session: SessionSummary) -> Bool {
+        guard let updatedAt = session.updatedAt else { return true }
+        return now.timeIntervalSince1970 - TimeInterval(updatedAt) >= 60
+    }
```

同时加一个记录起始时刻的属性：

```swift
+    private var liveActivityStart: Date?
```

> 注意：`awaitingInput == true` 时 `phase` 取 `.approval`；App 目前不区分 question，
> 若后续要区分，把 `pendingQuestions` 换成真实计数即可，`LiveActivityPolicy` 已支持。

---

## 改动 3／3：`apps/ios/App/Features/Home/InboxPage.swift` — 装配 controller

锚点：`PushSettingsRegistration(hostID:..., pushVersion:..., pairedDeviceID:...)`（当前第 797–798 行）。

```swift
                 push: PushSettingsRegistration(
                     hostID: model.hostID, pushVersion: model.pushVersion, pairedDeviceID: model.pairedDeviceID))
```

在该视图出现处加装配（`InboxPage` 的 `.task { }` 或 `onAppear` 内）：

```swift
+        .task {
+            // 生产装配：真实 ActivityKit。开关关闭时不创建（controller 内部判 enabled）。
+            if model.liveActivity == nil {
+                model.liveActivity = LiveActivityController(
+                    adapter: ActivityKitLiveActivityAdapter(),
+                    isEnabled: { model.liveActivityEnabled })
+                await model.liveActivity?.restore()
+            }
+        }
```

并在 `InboxModel` 暴露开关（从 Settings 7.4 的存储读，默认**关**）：

```swift
+    /// 设置页 7.4 的 Live Activity 开关，默认关。
+    var liveActivityEnabled: Bool {
+        defaults.object(forKey: "liveActivity.enabled") as? Bool ?? false
+    }
```

> `ActivityKitLiveActivityAdapter` 标了 `@available(iOS 16.2, *)`；若部署目标低于 16.2，
> 装配处需包 `if #available(iOS 16.2, *)`。请按 `project.yml` 的 deploymentTarget 决定。

---

## 合入后需要补的两件事

1. **合同**：把「多任务选哪一个展示」写进 RFC 0002 §5.6 或 `MOBILE_SYNC_CONTRACT.md`
   （当前实现：有等待 > 运行中 > 最近更新）。
2. **测试**：`LiveActivityControllerTests` 已覆盖 controller 全部行为；
   接线层建议补一条 `InboxModel` 级测试（注入 fake controller）断言：
   等待中的会话创建活动、`stoppedReason` 非空时会话结束后调用 `end`。

## 已验证 / 未验证

- ✅ 已验证：controller 与 policy 全部单测通过；完整 App build `** BUILD SUCCEEDED **`。
- ⚠️ **未验证**：本 diff 未实际合入运行（接线点在他人 scope）。
  锁屏实机效果、灵动岛紧凑/最小/展开、多活动竞争均**未验证**，需真机（§16.4.5）。
- ⚠️ **未验证**：真实 APNs / ActivityKit 推送更新需 Apple 账号与真机，当前无账号 → 标注**未验证**。
