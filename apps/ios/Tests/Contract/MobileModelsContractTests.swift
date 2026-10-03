import DLModels
import Foundation
import Testing

/// 合同测试：用 `docs/MOBILE_SYNC_CONTRACT.md` 的 JSON 示例与从插件 `src/` 推出的
/// 最小样本，验证 DLModels 的每个响应类型能解码。I3.2 起样本迁到
/// `testdata/mobile-contract/`；本项先内嵌在测试里。
/// 覆盖：未知字段忽略、可选字段缺失可解码、枚举新取值落 unknown、`kind` 缺失不炸。
struct MobileModelsContractTests {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    // MARK: - Bootstrap

    @Test func bootstrapDecodesContractSampleAndIgnoresUnknownFields() throws {
        let bootstrap = try decode(
            BootstrapResponse.self,
            """
            {
              "version": 1,
              "protocol": 2,
              "capabilities": {
                "sync": { "resync": true, "catchupIntegrity": true },
                "questions": { "multi": true, "serverValidation": true },
                "requests": { "snapshot": true, "reconnectGraceMs": 30000 },
                "control": { "queue": true, "goals": true, "schedules": true },
                "files": { "workspace": true, "maxBytes": 8388608, "tree": true,
                           "treeMaxEntries": 2000, "sha256": true,
                           "changes": true, "diff": true, "diffMaxLines": 5000 },
                "diagnostics": { "v": 1 },
                "events": { "host": true },
                "preview": { "v": 1, "detect": 1 }
              },
              "host": { "name": "Mac", "deviceId": "host-1" },
              "device": { "name": "iPhone" },
              "sessions": [{
                "sessionId": "s-1", "title": "修 bug", "updatedAt": 1759000000000,
                "running": true, "blank": false, "cwd": "/repo", "agentPreset": "standard",
                "origin": "user", "parentSessionId": null, "subagentCount": 0,
                "awaitingInput": true,
                "activity": { "kind": "tool", "label": "go test ./...", "step": 12, "startedAt": 1759000000000 }
              }, {
                "sessionId": "s-2", "title": "已完成", "updatedAt": 1759000001000, "running": false, "blank": false,
                "lastResult": { "text": "门禁全绿", "files": 79, "added": 1200, "deleted": 300 },
                "stoppedReason": "interrupted"
              }],
              "archivedSessionIds": ["s-archived"],
              "webPath": "/",
              "relay": null,
              "remote": { "e": "wss://relay.example", "r": "cm91dGU", "h": "aGFuZGxl", "k": "a2V5", "p": "sha256/ab12" },
              "futureField": { "anything": true }
            }
            """
        )
        #expect(bootstrap.version == 1)
        #expect(bootstrap.protocolVersion == 2)
        #expect(bootstrap.capabilities?.sync?.resync == true)
        #expect(bootstrap.capabilities?.requests?.reconnectGraceMs == 30000)
        #expect(bootstrap.capabilities?.files?.changes == true)
        #expect(bootstrap.capabilities?.diagnostics?.v == 1)
        #expect(bootstrap.capabilities?.events?.host == true)
        #expect(bootstrap.capabilities?.preview?.detect == 1)
        #expect(bootstrap.host?.name == "Mac")
        #expect(bootstrap.device?.name == "iPhone")
        #expect(bootstrap.sessions?.count == 2)
        #expect(bootstrap.sessions?[0].activity?.kind == .tool)
        #expect(bootstrap.sessions?[0].activity?.label == "go test ./...")
        #expect(bootstrap.sessions?[0].awaitingInput == true)
        #expect(bootstrap.sessions?[1].lastResult?.text == "门禁全绿")
        #expect(bootstrap.sessions?[1].stoppedReason == "interrupted")
        #expect(bootstrap.archivedSessionIds == ["s-archived"])
        #expect(bootstrap.webPath == "/")
        #expect(
            bootstrap.remote
                == .enabled(
                    DeviceRemoteInfo(
                        endpoint: "wss://relay.example",
                        routeId: "cm91dGU",
                        deviceHandle: "aGFuZGxl",
                        relayKey: "a2V5",
                        outerCertificatePin: "sha256/ab12"
                    )
                ))
    }

    @Test func bootstrapRemoteAbsentNullAndObjectAreDistinct() throws {
        let absent = try decode(BootstrapResponse.self, #"{"version": 1}"#)
        #expect(absent.remote == .absent)
        let disabled = try decode(BootstrapResponse.self, #"{"version": 1, "remote": null}"#)
        #expect(disabled.remote == .disabled)
        let enabled = try decode(
            BootstrapResponse.self,
            #"{"version": 1, "remote": {"e": "wss://relay", "r": "route", "h": "handle", "k": "key"}}"#
        )
        #expect(enabled.remote != .absent)
        #expect(enabled.remote != .disabled)
    }

    // MARK: - Sessions

    @Test func sessionListDecodesMinimalRows() throws {
        let list = try decode(
            SessionListResponse.self,
            """
            { "version": 1, "sessions": [{ "sessionId": "s-1", "title": "未命名会话", "updatedAt": 1, "running": false, "blank": true }],
              "archivedSessionIds": [] }
            """
        )
        let row = try #require(list.sessions?.first)
        #expect(row.sessionId == "s-1")
        #expect(row.activity == nil)
        #expect(row.lastResult == nil)
        #expect(row.awaitingInput == nil)
        #expect(row.stoppedReason == nil)
        #expect(list.archivedSessionIds?.isEmpty == true)
    }

    @Test func sessionSearchDecodesBothPaths() throws {
        let full = try decode(
            SessionSearchResponse.self,
            #"{"version": 1, "items": [{"sessionId": "s-1", "snippet": "…命中…", "extra": 1}], "hasMore": false, "degraded": false}"#
        )
        #expect(full.items?.first?.sessionId == "s-1")
        #expect(full.items?.first?.snippet == "…命中…")
        #expect(full.degraded == false)
        let degraded = try decode(
            SessionSearchResponse.self,
            #"{"version": 1, "items": [{"sessionId": "s-2", "snippet": "标题"}], "hasMore": false, "degraded": true}"#
        )
        #expect(degraded.degraded == true)
    }

    // MARK: - History

    @Test func historyDecodesAllMessageRolesAndKindFallback() throws {
        let history = try decode(
            HistoryResponse.self,
            """
            {
              "ok": true,
              "sessionId": "s-1",
              "messages": [
                { "id": "msg-10", "seq": 10, "role": "user", "kind": "injection",
                  "labels": ["runtime"], "text": "Current runtime context:", "time": 1759000000000, "type": "text" },
                { "id": "msg-11", "seq": 11, "role": "context_injection", "kind": "goal_round",
                  "goal": { "round": 1, "maxRounds": 256, "objective": "ship it" }, "text": "<goal_round>…", "time": 1759000001000 },
                { "id": "msg-12", "seq": 12, "role": "system_notice", "kind": "model_changed", "text": "[model changed: …]" },
                { "id": "msg-13", "seq": 13, "role": "assistant", "kind": "assistant", "text": "你好" },
                { "id": "msg-14", "seq": 14, "role": "user", "text": "没有 kind 的旧插件消息" },
                { "id": "approval-1", "seq": 15, "role": "approval", "kind": "approval", "toolName": "shell",
                  "approvalId": "ap-1", "callId": "c-1", "requestStatus": "pending", "outcome": null },
                { "id": "tool-16", "seq": 16, "role": "tool_call", "kind": "tool_call", "name": "shell",
                  "args": "{}", "callId": "c-1", "turn": 1, "step": 2 },
                { "id": "tool-res-17", "seq": 17, "role": "tool_result", "kind": "tool_result", "callId": "c-1",
                  "durationMs": 1200, "text": "ok" },
                { "id": "reason-18", "seq": 18, "role": "reasoning", "kind": "reasoning", "text": "想一想", "running": true },
                { "id": "compact-19", "seq": 19, "role": "compaction", "kind": "compaction", "text": "", "running": true },
                { "id": "todo-20", "seq": 20, "role": "todo", "kind": "todo",
                  "todos": [{ "content": "跑门禁", "status": "pending" }] },
                { "id": "changes-21", "seq": 21, "role": "workspace_changes", "kind": "workspace_changes", "turn": 1,
                  "changes": { "turn": 1, "total": 2, "added": 10, "deleted": 3,
                               "files": [{ "path": "a.swift", "display": "a.swift", "added": 10, "deleted": 3 }] } },
                { "id": "files-22", "seq": 22, "role": "produced_files", "kind": "produced_files", "files": ["a.swift", "b.swift"] },
                { "id": "msg-23", "seq": 23, "role": "assistant", "kind": "weird_new_kind", "text": "未来取值" }
              ],
              "hasMore": false,
              "nextBeforeSeq": 10,
              "maxSeq": 23,
              "stoppedReason": null,
              "stats": {
                "tokenUsage": { "uncachedInputTokens": 100, "cacheReadTokens": 200, "outputTokens": 50, "model": "deepseek-v4-pro" },
                "sessionStats": { "turns": 2, "steps": 5, "llmMs": 1000, "toolMs": 200, "ttftMs": 90, "ttftSteps": 1,
                                  "decodeMs": 300, "decodeTokens": 40 },
                "contextPressure": { "projectedTokens": 900, "contextWindow": 128000 },
                "contextBreakdown": { "systemTokens": 100, "toolsTokens": 200, "messageTokens": 600 },
                "todos": [],
                "estimatedCost": { "amount": 0.5, "currency": "USD", "priceDate": "2026-10-02", "source": "builtin",
                                   "amountMin": 0.25, "amountMax": 1.0 }
              },
              "queue": [{ "id": "q-1", "placement": "queued", "text": "接着跑", "images": 0 }],
              "goal": { "goal": { "id": "goal-1", "revision": 3, "objective": "ship it", "phase": "active",
                                  "maxGoalRounds": 8 }, "roundsStarted": 1 },
              "unknownTopLevel": true
            }
            """
        )
        let messages = try #require(history.messages)
        #expect(messages.count == 14)
        #expect(messages[0].kind == .injection)
        #expect(messages[0].labels == ["runtime"])
        #expect(messages[1].kind == .goalRound)
        #expect(messages[1].goal?.round == 1)
        #expect(messages[1].goal?.maxRounds == 256)
        #expect(messages[1].goal?.objective == "ship it")
        #expect(messages[2].kind == .modelChanged)
        #expect(messages[3].kind == .role("assistant"))
        #expect(messages[4].kind == nil)
        #expect(messages[5].kind == .role("approval"))
        #expect(messages[5].requestStatus == .pending)
        #expect(messages[5].outcome == nil)
        #expect(messages[6].args == "{}")
        #expect(messages[7].durationMs == 1200)
        #expect(messages[8].running == true)
        #expect(messages[10].todos?.first?.content == "跑门禁")
        #expect(messages[11].changes?.files?.count == 1)
        #expect(messages[12].files == ["a.swift", "b.swift"])
        #expect(messages[13].kind == .role("weird_new_kind"))

        #expect(history.stats?.tokenUsage?.outputTokens == 50)
        #expect(history.stats?.contextPressure?.contextWindow == 128000)
        #expect(history.stats?.estimatedCost?.source == .builtin)
        #expect(history.stats?.estimatedCost?.amountMax == 1.0)
        #expect(history.queue?.first?.placement == .queued)
        #expect(history.goal?.phase == .active)
        #expect(history.goal?.ref?.revision == 3)
        #expect(history.goal?.roundsStarted == 1)
        #expect(history.nextBeforeSeq == 10)
        #expect(history.maxSeq == 23)
    }

    @Test func historyDecodesMinimalResponse() throws {
        let history = try decode(
            HistoryResponse.self,
            #"{"ok": true, "sessionId": "s-1", "messages": [], "hasMore": false}"#
        )
        #expect(history.messages?.isEmpty == true)
        #expect(history.stats == nil)
        #expect(history.goal == nil)
        #expect(history.queue == nil)
    }

    @Test func estimatedCostSourceFallsBackToUnknown() throws {
        let history = try decode(
            HistoryResponse.self,
            """
            {"ok": true, "sessionId": "s", "messages": [],
             "stats": { "estimatedCost": { "amount": 1, "currency": "USD", "source": "custom-table" }} }
            """
        )
        #expect(history.stats?.estimatedCost?.source == .unknown("custom-table"))
    }

    // MARK: - Requests

    @Test func requestsSnapshotDecodes() throws {
        let snapshot = try decode(
            RequestsSnapshotResponse.self,
            """
            { "version": 1,
              "approvals": [
                { "approvalId": "ap-1", "status": "pending", "outcome": null, "sessionId": "s-1",
                  "createdAt": 1759000000000, "deadlineAt": 1759000300000, "callId": "c-1", "toolName": "shell" },
                { "approvalId": "ap-0", "status": "resolved", "outcome": "allowed-once", "sessionId": "s-1",
                  "createdAt": 1759000000000, "deadlineAt": 1759000000000 }
              ],
              "questions": [
                { "rpcId": "q-1", "status": "pending", "sessionId": "s-1", "createdAt": 1759000000000,
                  "deadlineAt": 1759000300000,
                  "questions": [
                    { "id": "q1", "header": "构建", "question": "用哪种方式？", "type": "select",
                      "options": ["a", { "id": "b", "label": "选 B" }], "multiple": false, "optional": false },
                    { "id": "q2", "question": "自由输入", "options": [] }
                  ] }
              ],
              "graceActive": false }
            """
        )
        let approval = try #require(snapshot.approvals?.first)
        #expect(approval.status == .pending)
        #expect(approval.toolName == "shell")
        let settled = try #require(snapshot.approvals?.last)
        #expect(settled.status == .resolved)
        let question = try #require(snapshot.questions?.first)
        #expect(question.status == .pending)
        let options = try #require(question.questions?.first?.options)
        #expect(options.count == 2)
        #expect(options[0].label == "a")
        #expect(options[1].id == "b")
        #expect(options[1].label == "选 B")
        #expect(snapshot.graceActive == false)
    }

    @Test func requestStatusFallsBackToUnknown() throws {
        let snapshot = try decode(
            RequestsSnapshotResponse.self,
            """
            { "version": 1, "approvals": [{ "approvalId": "ap-1", "status": "deferred", "sessionId": "s-1" }], "questions": [] }
            """
        )
        #expect(snapshot.approvals?.first?.status == .unknown("deferred"))
    }

    @Test func requestSubmitResponseDecodes() throws {
        let settled = try decode(
            RequestSubmitResponse.self,
            #"{"ok": true, "accepted": true, "alreadySettled": true, "outcome": "allowed-once", "status": "resolved", "handledBy": "plugin"}"#
        )
        #expect(settled.alreadySettled == true)
        #expect(settled.outcome == "allowed-once")
        let fresh = try decode(
            RequestSubmitResponse.self,
            #"{"ok": true, "accepted": true, "handledBy": "plugin"}"#
        )
        #expect(fresh.alreadySettled == nil)
        #expect(fresh.outcome == nil)
    }

    // MARK: - Changes

    @Test func changesSummaryAndDiffDecode() throws {
        let summary = try decode(
            ChangesSummaryResponse.self,
            """
            { "ok": true, "seq": 21, "turn": 1, "total": 2, "added": 10, "deleted": 3,
              "files": [
                { "path": "a.swift", "display": "a.swift", "added": 10, "deleted": 3 },
                { "path": "/abs/logo.png", "display": "~/logo.png", "added": 0, "deleted": 0, "binary": true },
                { "path": "big.min.js", "display": "big.min.js", "added": 0, "deleted": 0, "oversized": true }
              ] }
            """
        )
        #expect(summary.files?.count == 3)
        #expect(summary.files?[1].binary == true)
        #expect(summary.files?[2].oversized == true)

        let diff = try decode(
            ChangesDiffResponse.self,
            """
            { "ok": true, "seq": 21, "index": 0, "kind": "text", "path": "a.swift", "display": "a.swift",
              "before": true, "after": true, "coarse": false,
              "hunks": [{ "oldStart": 1, "oldLines": 3, "newStart": 1, "newLines": 4,
                          "lines": [" context", "-old", "+new"] }],
              "truncated": { "shownLines": 5000, "totalLines": 9000 } }
            """
        )
        #expect(diff.kind == .text)
        #expect(diff.hunks?.first?.lines == [" context", "-old", "+new"])
        #expect(diff.truncated?.totalLines == 9000)

        let binary = try decode(
            ChangesDiffResponse.self,
            #"{"ok": true, "seq": 21, "index": 1, "kind": "binary", "path": "/abs/logo.png", "display": "~/logo.png"}"#
        )
        #expect(binary.kind == .binary)
        #expect(binary.hunks == nil)

        let moved = try decode(
            ChangesDiffResponse.self,
            #"{"ok": true, "seq": 21, "index": 2, "kind": "renamed", "path": "x"}"#
        )
        #expect(moved.kind == .unknown("renamed"))
    }

    // MARK: - Files

    @Test func treeResponseDecodes() throws {
        let tree = try decode(
            TreeResponse.self,
            """
            { "ok": true, "path": "", "total": 3, "truncated": false,
              "entries": [
                { "name": "src", "type": "dir" },
                { "name": "README.md", "type": "file", "size": 1200, "mtimeMs": 1759000000000 },
                { "name": "link", "type": "symlink", "outside": true },
                { "name": "internal", "type": "file", "size": 1, "mtimeMs": 1, "link": true }
              ] }
            """
        )
        let entries = try #require(tree.entries)
        #expect(entries[0].type == .dir)
        #expect(entries[1].size == 1200)
        #expect(entries[2].type == .symlink)
        #expect(entries[2].outside == true)
        #expect(entries[3].link == true)
        #expect(tree.path == "")
    }

    // MARK: - Previews

    @Test func previewsAndDetectionsDecode() throws {
        let previews = try decode(
            PreviewsResponse.self,
            #"{"previews": [{ "previewId": "pv-1", "label": "vite", "port": 5173, "expiresAt": 1759000000000 }]}"#
        )
        #expect(previews.previews?.first?.port == 5173)
        let detections = try decode(
            PreviewDetectionsResponse.self,
            #"{"detections": [{ "port": 3000, "sessionId": "s-1" }]}"#
        )
        #expect(detections.detections?.first?.sessionId == "s-1")
    }

    // MARK: - Balance / Providers

    @Test func balanceDecodesAllStatuses() throws {
        let ready = try decode(
            BalanceResponse.self,
            #"{"version": 1, "status": "ready", "wallets": [{ "currency": "CNY", "balance": "12.34" }], "bonusWallets": []}"#
        )
        #expect(ready.status == .ready)
        #expect(ready.wallets?.first?.balance == "12.34")
        let signedOut = try decode(
            BalanceResponse.self,
            #"{"version": 1, "status": "signed-out", "wallets": [], "bonusWallets": []}"#
        )
        #expect(signedOut.status == .signedOut)
        let future = try decode(
            BalanceResponse.self,
            #"{"version": 1, "status": "frozen", "wallets": [], "bonusWallets": []}"#
        )
        #expect(future.status == .unknown("frozen"))
    }

    @Test func providersDirectoryDecodes() throws {
        let directory = try decode(
            ProvidersResponse.self,
            """
            { "version": 1, "writable": true,
              "providers": [
                { "provider": "deepseek-account", "displayName": "DeepSeek 账户", "kind": "account", "active": true,
                  "custom": false, "keyRef": null, "credential": null,
                  "models": [{ "id": "deepseek-v4-pro", "name": "V4 Pro", "contextWindow": 128000, "maxTokens": 8192 }],
                  "modelsEditable": false, "canDiscover": false },
                { "provider": "openai", "displayName": "OpenAI", "kind": "api", "active": true, "custom": true,
                  "keyRef": "OPENAI_API_KEY",
                  "credential": { "configured": true, "writable": true, "source": "settings" },
                  "models": [], "modelsEditable": true, "canDiscover": true, "futureFlag": 1 }
              ],
              "addable": [{ "provider": "anthropic", "displayName": "Anthropic" }] }
            """
        )
        let account = try #require(directory.providers?.first)
        #expect(account.kind == .account)
        #expect(account.credential == nil)
        let api = try #require(directory.providers?.last)
        #expect(api.kind == .api)
        #expect(api.credential?.configured == true)
        #expect(directory.addable?.first?.provider == "anthropic")
    }

    @Test func discoveredModelsDecode() throws {
        let discovered = try decode(
            DiscoverModelsResponse.self,
            """
            { "version": 1, "provider": "openai",
              "models": [{ "id": "gpt-5", "name": "GPT-5", "contextWindow": 400000, "maxTokens": 16384,
                           "inputModalities": ["text", "image"] }] }
            """
        )
        #expect(discovered.models?.first?.inputModalities == ["text", "image"])
    }

    // MARK: - Diagnostics

    @Test func diagnosticsReportDecodes() throws {
        let report = try decode(
            DiagnosticsReport.self,
            """
            { "version": 1, "generatedAt": 1730000000,
              "checks": [
                { "id": "host.rpc", "status": "ok", "code": "HOST_RPC_OK", "detail": { "ms": 12 } },
                { "id": "tls.cert", "status": "warn", "code": "TLS_CERT_EXPIRING",
                  "detail": { "fingerprintPrefix": "a1b2c3d4", "daysRemaining": 12 } },
                { "id": "pairing.devices", "status": "ok", "code": "PAIRING_SELF_OK", "detail": { "valid": true } },
                { "id": "host.services", "status": "fail", "code": "HOST_SERVICES_MISSING", "detail": {} },
                { "id": "remote.relay", "status": "fail", "code": "REMOTE_DISABLED", "detail": { "enabled": false, "state": "off" } }
              ] }
            """
        )
        let checks = try #require(report.checks)
        #expect(checks.count == 5)
        #expect(checks[0].status == .ok)
        #expect(checks[0].detail?["ms"] == .number(12))
        #expect(checks[1].status == .warn)
        #expect(checks[1].detail?["fingerprintPrefix"] == .text("a1b2c3d4"))
        #expect(checks[2].detail?["valid"] == .flag(true))
        #expect(checks[3].detail?.isEmpty == true)
        #expect(checks[4].detail?["enabled"] == .flag(false))
    }

    // MARK: - Devices

    @Test func devicesDecode() throws {
        let devices = try decode(
            DevicesResponse.self,
            """
            { "version": 1,
              "devices": [
                { "deviceId": "dev-1", "name": "iPhone", "createdAt": 1759000000000, "lastSeenAt": 1759000100000,
                  "via": "lan", "remote": true, "status": "active", "replacing": false,
                  "pendingExpiresAt": null, "pairedFrom": "" },
                { "deviceId": "dev-2", "name": "手机", "createdAt": 1759000000000, "lastSeenAt": 1759000000000,
                  "via": "remote", "remote": false, "status": "pending", "replacing": true,
                  "pendingExpiresAt": 1759000300000, "pairedFrom": "远程（经中继）" },
                { "deviceId": "dev-3", "name": "旧手机", "via": "teleport", "status": "zombie" }
              ] }
            """
        )
        let rows = try #require(devices.devices)
        #expect(rows[0].via == .lan)
        #expect(rows[0].remote == true)
        #expect(rows[1].via == .remote)
        #expect(rows[1].replacing == true)
        #expect(rows[2].via == .unknown("teleport"))
        #expect(rows[2].status == .unknown("zombie"))
    }

    // MARK: - Pairing

    @Test func pairResponseDecodes() throws {
        let pair = try decode(
            PairResponse.self,
            """
            { "ok": true, "token": "t-abc", "deviceId": "dev-1", "name": "iPhone",
              "urls": ["https://192.168.10.17:18640"], "pending": true, "pendingExpiresAt": 1759000300000,
              "replacedDeviceIds": ["dev-0"], "replacing": true,
              "remote": { "e": "wss://relay.example", "r": "cm91dGU", "h": "aGFuZGxl", "k": "a2V5" } }
            """
        )
        #expect(pair.pending == true)
        #expect(pair.replacedDeviceIds == ["dev-0"])
        #expect(pair.remote?.deviceHandle == "aGFuZGxl")

        let simple = try decode(
            PairResponse.self,
            #"{"ok": true, "token": "t", "deviceId": "dev-1", "name": "iPhone", "urls": [], "pending": false}"#
        )
        #expect(simple.remote == nil)
        #expect(simple.replacing == nil)
    }

    @Test func pairConflictDecodes() throws {
        let conflict = try decode(
            PairConflictResponse.self,
            """
            { "error": "已存在同名设备，请先吊销旧设备或更换名称", "code": "SAME_NAME",
              "existing": { "deviceId": "dev-0", "name": "手机", "status": "active" } }
            """
        )
        #expect(conflict.code == "SAME_NAME")
        #expect(conflict.existing?.status == "active")
    }

    @Test func pairInfoPayloadDecodes() throws {
        let info = try decode(
            PairInfoPayload.self,
            """
            { "v": 1, "type": "dsh-link", "deviceId": "host-1", "name": "Mac", "port": 18640,
              "urls": ["https://192.168.10.17:18640"],
              "infos": [{ "url": "https://192.168.10.17:18640", "label": "192.168.10.17",
                          "category": "private", "isRecommended": true }],
              "pairingCode": "123456", "certFingerprint": "sha256/aabbcc",
              "requireConfirm": true,
              "exposure": { "listen": { "address": "0.0.0.0", "port": 18640 },
                            "networks": [{ "label": "en0", "category": "private",
                                           "url": "https://192.168.10.17:18640" }],
                            "level": "lan", "warning": null, "hint": "勿把 18640 暴露到公网" },
              "issuedAt": 1759000000000, "expiresAt": 1759000300000,
              "remote": { "e": "wss://relay.example", "r": "cm91dGU", "s": "c2VlZA==" } }
            """
        )
        #expect(info.port == 18640)
        #expect(info.issuedAt == 1_759_000_000_000)
        #expect(info.expiresAt == 1_759_000_300_000)
        #expect(info.requireConfirm == true)
        #expect(info.exposure?.level == .lan)
        #expect(info.exposure?.warning == nil)
        #expect(info.infos?.first?.category == .privateNetwork)
        #expect(info.remote?.bootstrapSeed == "c2VlZA==")
    }

    // MARK: - Workspaces

    @Test func workspaceResponsesDecode() throws {
        let created = try decode(
            WorkspaceCreateResponse.self,
            """
            { "ok": true, "workspace": { "workspaceId": "w-1", "path": "/repo/sub", "title": "sub", "sessionIds": [] },
              "created": true, "directoryCreated": true, "inputKind": "subdirectory", "resolvedPath": "/repo/sub" }
            """
        )
        #expect(created.workspace?.workspaceId == "w-1")
        #expect(created.inputKind == "subdirectory")
        let pending = try decode(
            WorkspacePendingApprovalResponse.self,
            """
            { "ok": true, "pending": true, "requestId": "req-1", "path": "/realpath",
              "inputKind": "absolute-path", "expiresAt": 0 }
            """
        )
        #expect(pending.requestId == "req-1")
        let list = try decode(
            WorkspaceListResponse.self,
            """
            { "version": 1, "workspaces": [{ "workspaceId": "w-1", "path": "/repo" }], "archivedSessionIds": ["s-9"] }
            """
        )
        #expect(list.workspaces?.first?.title == nil)
        #expect(list.archivedSessionIds == ["s-9"])
    }

    // MARK: - Control

    @Test func promptCancelAndQueueActionDecode() throws {
        let prompt = try decode(PromptResponse.self, #"{"ok": true, "result": {"accepted": true}}"#)
        #expect(prompt.ok == true)
        let cancel = try decode(CancelResponse.self, #"{"ok": true, "sessionId": "s-1"}"#)
        #expect(cancel.sessionId == "s-1")
        let queue = try decode(
            QueueActionResponse.self,
            #"{"ok": true, "sessionId": "s-1", "itemId": "q-1", "action": "edit"}"#
        )
        #expect(queue.itemId == "q-1")
    }

    // MARK: - Events（SSE）

    @Test func hostEventAndSessionStreamFramesDecode() throws {
        let hostEvent = try decode(
            HostSessionStateEvent.self,
            #"{"type": "session/state", "sessionId": "s-1", "state": "awaitingApproval", "title": "修 bug", "origin": "user", "seq": 42}"#
        )
        #expect(hostEvent.state == .awaitingApproval)
        #expect(hostEvent.origin == .user)

        let futureState = try decode(
            HostSessionStateEvent.self,
            #"{"type": "session/state", "sessionId": "s-1", "state": "hibernating", "origin": "automation", "seq": 43}"#
        )
        #expect(futureState.state == .unknown("hibernating"))
        #expect(futureState.origin == .unknown("automation"))

        let resync = try decode(
            ResyncRequiredEvent.self,
            #"{"sessionId": "s-1", "reason": "incomplete", "afterSeq": 100, "oldestAvailableSeq": 50, "nextCursor": 120}"#
        )
        #expect(resync.oldestAvailableSeq == 50)
        #expect(resync.nextCursor == 120)

        let message = try decode(
            SessionEventEnvelope.self,
            #"{"seq": 44, "type": "assistant/chunk", "time": 1759000000000, "data": {"chunk": {"type": "text-delta", "text": "hi"}}}"#
        )
        #expect(message.seq == 44)
        #expect(message.type == "assistant/chunk")

        let stats = try decode(
            StatsEvent.self,
            #"{"tokenUsage": { "outputTokens": 5 }, "sessionStats": null, "contextPressure": null, "contextBreakdown": null, "todos": null}"#
        )
        #expect(stats.tokenUsage?.outputTokens == 5)

        let question = try decode(
            QuestionRequestEvent.self,
            #"{"rpcId": "q-1", "sessionId": "s-1", "questions": [{ "id": "q1", "question": "继续吗？" }]}"#
        )
        #expect(question.rpcId == "q-1")
        let resolved = try decode(
            QuestionResolvedEvent.self,
            #"{"rpcId": "q-1", "sessionId": "s-1", "outcome": "cancelled"}"#
        )
        #expect(resolved.outcome == "cancelled")
    }
}
